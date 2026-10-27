package ai.surstor.node;

import ai.surstor.*;
import ai.surstor.Cells;
import java.util.List;
import convex.core.data.*;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import convex.lattice.generic.CASLattice;
import covia.venue.*;
import io.javalin.Javalin;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Selected-record replication. Never exposes the venue root or its generic cell store. */
final class MemorySync implements AutoCloseable {
    static final int MAX_RECORDS = 2000;
    private static final AString C1 = Strings.create("cell1"), C2 = Strings.create("cell2"), SCHEDULE = Strings.create("schedule");
    private final Engine engine;
    private final RequestContext context;
    private final SyncConfig config;
    private final CASLattice<Hash, ACell> lattice = CASLattice.create();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "surstor-sync"); t.setDaemon(true); return t;
    });
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private Javalin server;
    private volatile String status = "ready";
    private volatile boolean indexDirty;

    MemorySync(Engine engine, SyncConfig config) {
        this.engine = engine; this.config = config;
        this.indexDirty = true; // Repair derived search state after an interrupted import or restart.
        this.context = RequestContext.of(Strings.create(engine.getDIDString() + ":public"));
    }

    void start() {
        synchronized (this) {
            synchronized (engine.getAdapter("surstor")) {
                rebuildSessions(records()); // Repair a crash between asset writes and session projection.
                engine.syncState();
            }
        }
        server = Javalin.create(c -> {
            c.http.maxRequestSize = PrivacyFilter.MAX_CHARS;
            c.routes.before(ctx -> {
                String supplied = ctx.header("Authorization");
                if (supplied == null || !MessageDigest.isEqual(supplied.getBytes(StandardCharsets.UTF_8),
                        ("Bearer " + config.token()).getBytes(StandardCharsets.UTF_8))) {
                    ctx.status(401).result("Unauthorized"); ctx.skipRemainingHandlers(); return;
                }
                if (ctx.header("Origin") != null || ctx.header("Content-Encoding") != null) {
                    ctx.status(403).result("Unsupported request"); ctx.skipRemainingHandlers();
                }
                ctx.header("Cache-Control", "no-store");
            });
            c.routes.get("/v1/ids", ctx -> ctx.contentType("application/json").result(json(inventory())));
            c.routes.get("/v1/status", ctx -> ctx.contentType("application/json").result(json(Maps.of(
                Strings.create("status"), Strings.create(status)))));
            c.routes.get("/v1/record/{id}", ctx -> {
                Hash hash = parseId(ctx.pathParam("id"));
                ACell packet = records().get(hash);
                if (packet == null) { ctx.status(404).result("Not shared"); return; }
                ctx.contentType("application/json").result(json(packet));
            });
            c.routes.post("/v1/record/{id}", ctx -> {
                Hash hash = parseId(ctx.pathParam("id"));
                accept(hash, PrivacyFilter.parse(ctx.body()));
                ctx.status(204);
            });
            c.routes.exception(Exception.class, (e, ctx) -> {
                status = "blocked: validation, privacy policy, capacity, or storage failure";
                ctx.status(422).result("Sync rejected; no unvalidated records accepted");
            });
        }).start("127.0.0.1", config.port());
        worker.scheduleWithFixedDelay(this::tick, 0, config.intervalSeconds(), TimeUnit.SECONDS);
    }

    // The immutable inventory is reconstructed from durable Covia user assets, including after restart.
    // CAS union supplies idempotence; no separate plaintext replication database is created.
    synchronized Index<Hash, ACell> records() {
        synchronized (engine.getAdapter("surstor")) {
            Index<Hash, ACell> out = lattice.zero();
            User user = engine.getVenueState().users().get(context.getUserDID());
            var assets = user == null ? null : user.assets().getAll();
            if (assets == null) return out;
            for (long i = 0; i < assets.count(); i++) {
                var entry = assets.entryAt(i);
                AMap<AString, ACell> cell2 = RT.ensureMap(entry.getValue().get(AssetStore.POS_META));
                if (cell2 == null || cell2.get(Cells.K_BRANCH_REF) == null || !eligible(cell2)) continue;
                AMap<AString, ACell> cell1 = Evidence.resolveCell1(engine, context, Cells.branchRef(cell2));
                Hash scheduleId = Cells.priceScheduleId(cell2);
                ACell schedule = scheduleId == null ? null : Evidence.resolveSchedule(engine, context, scheduleId);
                ACell packet = Maps.of(C1, cell1, C2, cell2, SCHEDULE, schedule);
                validate(packet.getHash(), packet);
                out = out.assoc(packet.getHash(), packet);
                if (out.count() > MAX_RECORDS) throw new IllegalStateException("Sync inventory capacity exceeded");
            }
            return out;
        }
    }

    synchronized AVector<ACell> inventory() {
        AVector<ACell> ids = Vectors.empty();
        var records = records();
        for (long i = 0; i < records.count(); i++) ids = ids.conj(Strings.create(records.entryAt(i).getKey().toHexString()));
        return ids;
    }

    private boolean eligible(AMap<AString, ACell> cell2) {
        ACell signer = cell2.get(Cells.K_SIGNER);
        return config.sessions().contains(Cells.sessionId(cell2)) && signer != null
            && (config.signers().contains(signer.toString()) || signer.toString().equals(engine.getKeyPair().getAccountKey().toHexString()));
    }

    private void validate(Hash expected, ACell packet) {
        // Check full envelope before ANY persistent writes, including schedules and metadata.
        if (!(packet instanceof AMap<?, ?> map) || map.count() != 3 || !map.containsKey(C1) || !map.containsKey(C2) || !map.containsKey(SCHEDULE) || !expected.equals(packet.getHash())
                || json(packet).getBytes(StandardCharsets.UTF_8).length > PrivacyFilter.MAX_CHARS) throw new IllegalArgumentException("Invalid packet");
        PrivacyFilter.requireClean(packet, PrivacyFilter.load(config.privacyPolicy()));
        AMap<AString, ACell> cell1 = RT.ensureMap(map.get(C1)), cell2 = RT.ensureMap(map.get(C2));
        if (cell1 == null || cell2 == null || !eligible(cell2)
                || !Cells.verifyBranchRef(cell2, cell1) || !Evidence.verifySignature(cell2)
                || !(cell2.get(Cells.K_TIMESTAMP) instanceof CVMLong)) throw new IllegalArgumentException("Invalid signed record");
        Hash scheduleId = Cells.priceScheduleId(cell2);
        AMap<AString, ACell> schedule = RT.ensureMap(map.get(SCHEDULE));
        if (scheduleId != null ? schedule == null || !scheduleId.equals(schedule.getHash()) : schedule != null)
            throw new IllegalArgumentException("Invalid schedule binding");
        if ((cell2.get(Billing.KEY) != null || Cells.costUsd(cell2) != null) && (schedule == null || !Billing.verify(cell2, schedule)))
            throw new IllegalArgumentException("Invalid billing");
    }

    synchronized boolean accept(Hash expected, ACell packet) throws Exception {
        validate(expected, packet);
        synchronized (engine.getAdapter("surstor")) {
            var before = records();
            var merged = lattice.merge(before, lattice.zero().assoc(expected, packet));
            if (merged.count() > MAX_RECORDS) throw new IllegalStateException("Sync inventory capacity exceeded");
            boolean added = merged.count() != before.count();
            AMap<AString, ACell> pair = RT.ensureMap(packet);
            // Retrying repeats idempotent asset registrations and repairs an interrupted import.
            for (AString field : List.of(SCHEDULE, C1, C2)) {
                AMap<AString, ACell> value = RT.ensureMap(pair.get(field));
                if (value != null) engine.storeUserAsset(Cells.toJson(value), value, context);
            }
            rebuildSessions(merged);
            engine.syncState();
            if (added) indexDirty = true;
            return added;
        }
    }

    private void rebuildSessions(Index<Hash, ACell> packets) {
        Map<Hash, AMap<AString, ACell>> cells = new HashMap<>();
        Set<Hash> parents = new HashSet<>();
        String own = engine.getKeyPair().getAccountKey().toHexString();
        for (long i = 0; i < packets.count(); i++) {
            AMap<AString, ACell> cell = RT.ensureMap(RT.getIn(packets.entryAt(i).getValue(), C2));
            if (own.equals(cell.get(Cells.K_SIGNER).toString())) continue;
            cells.put(cell.getHash(), cell);
        }
        for (var child : cells.values()) {
            Hash parentId = Cells.prevEvent(child);
            var parent = cells.get(parentId);
            if (parent != null && Objects.equals(parent.get(Cells.K_SIGNER), child.get(Cells.K_SIGNER))
                    && Objects.equals(Cells.sessionId(parent), Cells.sessionId(child))) parents.add(parentId);
        }
        AdapterWorkspace ws = engine.adapterWorkspace("surstor");
        Map<String, AMap<AString, ACell>> desired = new HashMap<>();
        for (var entry : cells.entrySet()) {
            if (parents.contains(entry.getKey())) continue;
            var head = entry.getValue();
            List<AMap<AString, ACell>> chain = new ArrayList<>();
            Set<Hash> visited = new HashSet<>();
            Hash cursor = entry.getKey();
            while (cursor != null) {
                var cell = cells.get(cursor);
                if (cell == null || !visited.add(cursor) || !Objects.equals(Cells.sessionId(cell), Cells.sessionId(head))
                        || !Objects.equals(cell.get(Cells.K_SIGNER), head.get(Cells.K_SIGNER))) { chain.clear(); break; }
                chain.add(cell); cursor = Cells.prevEvent(cell);
            }
            if (chain.isEmpty()) continue; // Incomplete history remains retrievable, but never becomes a complete session.
            Collections.reverse(chain);
            String alias = "sync-" + head.get(Cells.K_SIGNER) + "-" + Strings.create(Cells.sessionId(head)).getHash().toHexString()
                + "-" + entry.getKey().toHexString();
            long created = ((CVMLong) chain.getFirst().get(Cells.K_TIMESTAMP)).longValue();
            var summary = Sessions.createSession(alias, created);
            for (var cell : chain) summary = Sessions.advanceSession(summary, cell.getHash(), Cells.costUsd(cell), ((CVMLong) cell.get(Cells.K_TIMESTAMP)).longValue());
            desired.put(alias, summary.assoc(Strings.create("source-session"), Strings.create(Cells.sessionId(head)))
                .assoc(Strings.create("source-signer"), head.get(Cells.K_SIGNER)));
        }
        // Only this bridge owns these aliases; local session names are never modified.
        AMap<AString, ACell> old = RT.ensureMap(ws.read("sync-session-aliases"));
        if (old != null) for (ACell key : old.keySet()) if (!desired.containsKey(key.toString())) ws.delete("sessions/" + key);
        AMap<AString, ACell> aliases = Maps.empty();
        for (var entry : desired.entrySet()) {
            ws.write("sessions/" + entry.getKey(), entry.getValue());
            aliases = aliases.assoc(Strings.create(entry.getKey()), Strings.create("replicated"));
        }
        ws.write("sync-session-aliases", aliases);
    }

    void syncPeer(URI peer) throws Exception {
        ACell parsed = PrivacyFilter.parse(request(peer, "/v1/ids", null));
        if (!(parsed instanceof AVector<?> ids) || ids.count() > MAX_RECORDS) throw new IllegalArgumentException("Invalid inventory");
        Set<Hash> remote = new HashSet<>();
        for (ACell id : ids) remote.add(parseId(id.toString()));
        var local = records();
        // Receive before sending; a later hop can relay accepted original signatures.
        for (Hash id : remote) if (!local.containsKey(id)) accept(id, PrivacyFilter.parse(request(peer, "/v1/record/" + id.toHexString(), null)));
        local = records();
        for (long i = 0; i < local.count(); i++) {
            var entry = local.entryAt(i);
            if (!remote.contains(entry.getKey())) request(peer, "/v1/record/" + entry.getKey().toHexString(), json(entry.getValue()));
        }
    }

    private String request(URI peer, String path, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(peer.toString() + path)).timeout(Duration.ofSeconds(15))
            .header("Authorization", "Bearer " + config.token());
        if (body == null) builder.GET(); else builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        // Bound the response subscriber itself; a hostile or broken peer cannot stream unbounded data.
        var response = client.send(builder.build(), info -> new LimitedBodySubscriber(PrivacyFilter.MAX_CHARS));
        if (response.statusCode() != (body == null ? 200 : 204)) throw new IllegalStateException("Peer rejected sync");
        return new String(response.body(), StandardCharsets.UTF_8);
    }

    private void tick() {
        boolean failed = false;
        for (URI peer : config.peers()) {
            try { syncPeer(peer); }
            catch (Exception e) { failed = true; }
        }
        synchronized (this) {
            synchronized (engine.getAdapter("surstor")) {
                if (indexDirty && "true".equals(String.valueOf(RT.getIn(engine.adapterConfig("surstor"), Strings.create("search-enabled"))))) {
                    try {
                        engine.getAdapter("surstor").invokeFuture(context, Maps.of(Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("surstor:reindex"))), Maps.empty()).get(60, TimeUnit.SECONDS);
                        indexDirty = false;
                    } catch (Exception | LinkageError e) { failed = true; }
                }
            }
        }
        status = failed ? "retrying: peer unavailable, rejected data, or index failure" : "idle: last exchange completed";
    }

    private static Hash parseId(String id) {
        if (!id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid hash");
        return Hash.fromHex(id);
    }
    private static String json(ACell value) { return JSON.print(value).toString(); }
    @Override public void close() {
        worker.shutdownNow();
        try { worker.awaitTermination(20, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        if (server != null) server.stop();
        client.close();
    }
}
