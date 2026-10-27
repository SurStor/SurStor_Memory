package ai.surstor.node;

import ai.surstor.SurStorAdapter;
import ai.surstor.Cells;
import ai.surstor.PrivacyFilter;
import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.data.prim.*;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.*;
import covia.venue.server.VenueServer;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MemorySyncTest {
    @TempDir Path dir;
    private final String token = "ab".repeat(32);
    @org.junit.jupiter.api.AfterEach void releaseMappings() { System.gc(); }

    final class Node implements AutoCloseable {
        final VenueServer venue;
        final Engine engine;
        final MemorySync sync;
        final URI uri;
        final AKeyPair key;
        Node(String name, AKeyPair key, Set<String> trusted, String policy) throws Exception {
            this(name, key, trusted, policy, false);
        }
        Node(String name, AKeyPair key, Set<String> trusted, String policy, boolean encrypted) throws Exception {
            this.key = key;
            AMap<AString, ACell> config = Maps.of(Strings.create("port"), CVMLong.ZERO,
                Strings.create("bindAddress"), Strings.create("127.0.0.1"),
                Strings.create("store"), Strings.create(dir.resolve(name).toString()),
                Strings.create("storage"), Maps.of(Strings.create("content"), Strings.create("lattice")),
                Strings.create("seed"), Strings.create(key.getSeed().toHexString()),
                Strings.create("adapters"), Maps.of(Strings.create("surstor"), Maps.of(Strings.create("search-enabled"), Strings.create("false"))));
            if (encrypted) {
                Path keyFile = dir.resolve(name + ".key");
                if (!Files.exists(keyFile)) {
                    byte[] bytes = new byte[32]; new java.security.SecureRandom().nextBytes(bytes);
                    Files.writeString(keyFile, java.util.HexFormat.of().formatHex(bytes));
                }
                config = config.assoc(Strings.create("etch"), Maps.of(
                    Strings.create("cipher"), Strings.create("aes-256-ctr"),
                    Strings.create("version"), CVMLong.create(3),
                    Strings.create("key"), Maps.of(Strings.create("file"), Strings.create(keyFile.toString())),
                    Strings.create("encryptIndex"), CVMBool.TRUE));
            }
            venue = VenueServer.launch(config); engine = venue.getEngine();
            engine.registerAdapter(new SurStorAdapter());
            int port; try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
            uri = URI.create("http://127.0.0.1:" + port);
            sync = new MemorySync(engine, new SyncConfig(port, 3600, token, Set.of("shared"), trusted, List.of(), policy));
            sync.start();
        }
        ACell call(String op, ACell args) throws Exception {
            return engine.getAdapter("surstor").invokeFuture(RequestContext.of(Strings.create(engine.getDIDString() + ":public")),
                Maps.of(Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("surstor:" + op))), args).get();
        }
        ACell store(String text, String session) throws Exception {
            return RT.getIn(call("store", Maps.of(Strings.create("content"), Strings.create(text), Strings.create("sessionId"), Strings.create(session))), Strings.create("id"));
        }
        public void close() { sync.close(); venue.close(); }
    }
    private Set<String> trusted(AKeyPair... keys) {
        Set<String> out = new HashSet<>(); for (var key : keys) out.add(key.getAccountKey().toHexString()); return out;
    }

    @Test void bidirectionalRelayOfflineWritesAndRestartPreserveRecordsAndSessionExports() throws Exception {
        var a = AKeyPair.generate(); var b = AKeyPair.generate(); var c = AKeyPair.generate();
        Set<String> trusted = trusted(a, b, c);
        ACell first, second;
        try (Node left = new Node("a", a, trusted, null); Node right = new Node("b", b, trusted, null); Node third = new Node("c", c, trusted, null)) {
            first = left.store("Remember the approved launch date", "shared");
            second = right.store("Remember the preferred local model", "shared");
            left.store("This collection stays local", "private");
            left.sync.syncPeer(right.uri);
            assertEquals(2, left.sync.records().count()); assertEquals(left.sync.inventory(), right.sync.inventory());
            assertTrue(right.call("retrieve", Maps.of(Strings.create("id"), first)).toString().contains("launch date"));
            assertTrue(left.call("retrieve", Maps.of(Strings.create("id"), second)).toString().contains("local model"));
            // Both devices write independently to the same named collection, retaining separate chains.
            left.store("Second offline laptop decision", "shared"); right.store("Second offline server decision", "shared");
            left.sync.syncPeer(right.uri); third.sync.syncPeer(right.uri);
            assertEquals(4, third.sync.inventory().count()); assertEquals(left.sync.inventory(), third.sync.inventory());
            left.sync.syncPeer(right.uri); assertEquals(4, left.sync.inventory().count());
            AVector<?> sessions = RT.ensureVector(RT.getIn(third.call("sessions", Maps.empty()), Strings.create("items")));
            assertEquals(2, sessions.count());
            for (ACell item : sessions) {
                ACell alias = RT.getIn(item, Strings.create("sessionId"));
                ACell bundle = third.call("export", Maps.of(Strings.create("sessionId"), alias));
                assertTrue(OfflineVerifier.verifyBundle(JSON.print(bundle).toString()).ok());
                assertThrows(Exception.class, () -> third.store("cannot append to another node", alias.toString()));
            }
        }
        try (Node restarted = new Node("b", b, trusted, null)) {
            assertEquals(4, restarted.sync.records().count());
            assertNotNull(restarted.call("retrieve", Maps.of(Strings.create("id"), first)));
            assertEquals("true", RT.getIn(restarted.call("verify", Maps.of(Strings.create("id"), first, Strings.create("deep"), CVMBool.TRUE)), Strings.create("valid")).toString());
        }
    }

    @Test void rejectsUnauthorizedTamperedUntrustedAndPrivacyBlockedPacketsWithoutWriting() throws Exception {
        var a = AKeyPair.generate(); var b = AKeyPair.generate();
        Path policy = dir.resolve("policy.json"); Files.writeString(policy, "{\"excludeTerms\":[\"restricted-project\"]}");
        try (Node left = new Node("a", a, trusted(a,b), null); Node right = new Node("b", b, trusted(a,b), policy.toString())) {
            left.store("restricted-project details", "shared");
            var entry = left.sync.records().entryAt(0);
            assertThrows(PrivacyFilter.Blocked.class, () -> right.sync.accept(entry.getKey(), entry.getValue()));
            assertEquals(0, right.sync.records().count());
            Files.writeString(policy, "{}");
            var pair = RT.<AString, ACell, AMap<AString, ACell>>ensureMap(entry.getValue());
            var bad = pair.assoc(Strings.create("cell1"), Maps.of(Strings.create("content"), Strings.create("tampered")));
            assertThrows(IllegalArgumentException.class, () -> right.sync.accept(bad.getHash(), bad));
            assertEquals(0, right.sync.records().count());
            try (HttpClient client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(right.uri.resolve("/v1/ids")).GET().build();
                assertEquals(401, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
                var redirect = HttpRequest.newBuilder(right.uri.resolve("/mcp")).header("Authorization", "Bearer " + token).GET().build();
                assertEquals(404, client.send(redirect, HttpResponse.BodyHandlers.ofString()).statusCode());
            }
            Files.writeString(policy, "invalid");
            assertThrows(Exception.class, () -> right.sync.accept(entry.getKey(), entry.getValue()));
        }
        try (Node left = new Node("a2", a, trusted(a,b), null); Node untrusted = new Node("b2", b, trusted(b), null)) {
            left.store("signed but untrusted", "shared"); var entry = left.sync.records().entryAt(0);
            assertThrows(IllegalArgumentException.class, () -> untrusted.sync.accept(entry.getKey(), entry.getValue()));
            assertEquals(0, untrusted.sync.records().count());
        }
    }


    @Test void outOfOrderDeliveryAndForksKeepEveryOriginalAndOnlyCompleteChainViews() throws Exception {
        var a = AKeyPair.generate(); var b = AKeyPair.generate();
        try (Node left = new Node("fork-a", a, trusted(a,b), null); Node right = new Node("fork-b", b, trusted(a,b), null)) {
            ACell first = left.store("shared ancestor", "shared");
            var ws = left.engine.adapterWorkspace("surstor");
            ACell initial = ws.read("sessions/shared");
            ACell branch1 = left.store("first branch", "shared");
            ws.write("sessions/shared", initial);
            ACell branch2 = left.store("second branch", "shared");
            var records = left.sync.records();
            for (long i = 0; i < records.count(); i++) {
                var entry = records.entryAt(i);
                ACell cell2 = RT.getIn(entry.getValue(), Strings.create("cell2"));
                if (!cell2.getHash().toHexString().equals(first.toString())) right.sync.accept(entry.getKey(), entry.getValue());
            }
            assertEquals(0, ((AVector<?>) RT.getIn(right.call("sessions", Maps.empty()), Strings.create("items"))).count());
            right.sync.syncPeer(left.uri);
            var sessions = (AVector<?>) RT.getIn(right.call("sessions", Maps.empty()), Strings.create("items"));
            assertEquals(2, sessions.count());
            assertEquals(3, right.sync.inventory().count());
            assertNotNull(right.call("retrieve", Maps.of(Strings.create("id"), branch1)));
            assertNotNull(right.call("retrieve", Maps.of(Strings.create("id"), branch2)));
            for (ACell item : sessions) {
                ACell bundle = right.call("export", Maps.of(Strings.create("sessionId"), RT.getIn(item, Strings.create("sessionId"))));
                assertTrue(OfflineVerifier.verifyBundle(JSON.print(bundle).toString()).ok());
            }
            assertEquals("local-sessions", RT.getIn(right.call("cost", Maps.empty()), Strings.create("scope")).toString());
            assertEquals("0", RT.getIn(right.call("cost", Maps.empty()), Strings.create("artifactCount")).toString());
        }
    }

    @Test void encryptedNodesUseIndependentStorageKeysAndKeepReceivedDataAcrossRestart() throws Exception {
        var a = AKeyPair.generate(); var b = AKeyPair.generate();
        String marker = "private replicated memory synthetic marker unique";
        ACell id;
        try (Node left = new Node("encrypted-a", a, trusted(a,b), null, true); Node right = new Node("encrypted-b", b, trusted(a,b), null, true)) {
            id = left.store(marker, "shared");
            right.sync.syncPeer(left.uri);
            assertTrue(right.call("retrieve", Maps.of(Strings.create("id"), id)).toString().contains(marker));
        }
        assertNotEquals(Files.readString(dir.resolve("encrypted-a.key")), Files.readString(dir.resolve("encrypted-b.key")));
        for (String name : List.of("encrypted-a", "encrypted-b")) {
            assertFalse(new String(Files.readAllBytes(dir.resolve(name)), java.nio.charset.StandardCharsets.ISO_8859_1).contains(marker));
        }
        try (Node right = new Node("encrypted-b", b, trusted(a,b), null, true)) {
            assertEquals(1, right.sync.inventory().count());
            assertTrue(right.call("retrieve", Maps.of(Strings.create("id"), id)).toString().contains(marker));
        }
    }

    @Test void configurationFailsClosedAndResponseSizeIsBounded() throws Exception {
        Path tokenFile = dir.resolve("sync.key"); Files.writeString(tokenFile, token);
        String base = "[sync]\nenabled = true\ntoken_file = \"sync.key\"\nsessions = \"shared\"\ntrusted_signers = \"" + "ab".repeat(32) + "\"\n";
        // Use actual TOML newlines/quotes, not escaped JSON.
        base = base.replace("\\n", "\n").replace("\\\"", "\"");
        Path file = dir.resolve("config.toml"); Files.writeString(file, base + "peers = \"http://192.168.1.2:8485\"\n");
        assertThrows(Exception.class, () -> SyncConfig.load(NodeConfig.load(file), file));
        Files.writeString(file, base + "peers = \"http://127.0.0.1:18485\"\n");
        assertNotNull(SyncConfig.load(NodeConfig.load(file), file));
        Files.writeString(file, base + "bind = \"0.0.0.0\"\n");
        assertThrows(IllegalArgumentException.class, () -> SyncConfig.load(NodeConfig.load(file), file));
        assertEquals("0.0.0.0", SyncConfig.load(NodeConfig.load(file), file, true).bind());
        var subscriber = new LimitedBodySubscriber(3);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        subscriber.onSubscribe(new java.util.concurrent.Flow.Subscription() {
            public void request(long n) {} public void cancel() { cancelled.set(true); }
        });
        subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(new byte[4])));
        assertTrue(cancelled.get()); assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
}
