package ai.surstor;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import convex.core.crypto.AKeyPair;
import convex.core.crypto.ASignature;
import convex.core.data.ABlob;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMDouble;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.adapter.AAdapter;
import covia.api.Fields;
import covia.venue.AdapterWorkspace;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * SurStor evidence layer adapter for Covia venues.
 *
 * <p>Stores AI work artifacts as two-cell CAD3 records with cryptographically
 * bound provenance and cost accounting. Exposes twelve operations via the
 * venue's MCP endpoint, including local semantic search
 * ({@code surstor:search} / {@code surstor:reindex}) backed by on-node
 * embeddings and a Lucene HNSW index — derived, unsigned, disposable data
 * that never enters signed cells.</p>
 */
public class SurStorAdapter extends AAdapter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SurStorAdapter.class);

    private static final String ADAPTER_NAME = "surstor";

    private static final String K_CONTENT = "content";
    private static final String K_CONTENT_TYPE = "contentType";
    private static final String K_SESSION_ID = "sessionId";
    private static final String K_AGENT_ID = "agentId";
    private static final String K_MODEL_VERSION = "modelVersion";
    private static final String K_USAGE = "usage";
    private static final String K_ID = "id";
    private static final String K_SHORT_ID = "shortId";
    private static final String K_DEEP = "deep";
    private static final String K_LIMIT = "limit";
    private static final String K_OFFSET = "offset";
    private static final String K_SCHEDULE = "schedule";
    private static final String K_SESSION_ID_OPT = "sessionId";
    private static final String K_ID_A = "idA";
    private static final String K_ID_B = "idB";
    private static final String K_TOTAL_USD = "totalUsd";
    private static final String K_ARTIFACT_COUNT = "artifactCount";

    @Override
    public String getName() {
        return ADAPTER_NAME;
    }

    @Override
    public String getDescription() {
        return "SurStor evidence layer: store AI work artifacts as tamper-evident CAD3 records "
            + "with provenance, cost accounting, and session hash chains. "
            + "Every artifact is two CAD3 cells: content + metadata with Merkle binding.";
    }

    @Override
    protected void installAssets() {
        String BASE = "/adapters/surstor/";
        installAsset("surstor/store",    BASE + "store.json");
        installAsset("surstor/retrieve", BASE + "retrieve.json");
        installAsset("surstor/verify",   BASE + "verify.json");
        installAsset("surstor/list",     BASE + "list.json");
        installAsset("surstor/diff",     BASE + "diff.json");
        installAsset("surstor/cost",     BASE + "cost.json");
        installAsset("surstor/sessions", BASE + "sessions.json");
        installAsset("surstor/export",  BASE + "export.json");
        installAsset("surstor/prices/get", BASE + "prices-get.json");
        installAsset("surstor/prices/put", BASE + "prices-put.json");
        installAsset("surstor/search",   BASE + "search.json");
        installAsset("surstor/reindex",  BASE + "reindex.json");
    }

    @Override
    public CompletableFuture<ACell> invokeFuture(RequestContext ctx, AMap<AString, ACell> meta, ACell input) {
        requireInvoke(ctx);
        try {
            return switch (getSubOperation(meta)) {
                case "store"       -> CompletableFuture.completedFuture(handleStore(input, ctx));
                case "retrieve"    -> CompletableFuture.completedFuture(handleRetrieve(input, ctx));
                case "verify"      -> CompletableFuture.completedFuture(handleVerify(input, ctx));
                case "list"        -> CompletableFuture.completedFuture(handleList(input, ctx));
                case "diff"        -> CompletableFuture.completedFuture(handleDiff(input, ctx));
                case "cost"        -> CompletableFuture.completedFuture(handleCost(input, ctx));
                case "sessions"    -> CompletableFuture.completedFuture(handleSessions(input, ctx));
                case "export"      -> CompletableFuture.completedFuture(handleExport(input, ctx));
                case "prices/get"  -> CompletableFuture.completedFuture(handlePricesGet(input, ctx));
                case "prices/put"  -> CompletableFuture.completedFuture(handlePricesPut(input, ctx));
                case "search"      -> CompletableFuture.completedFuture(handleSearch(input, ctx));
                case "reindex"     -> CompletableFuture.completedFuture(handleReindex(input, ctx));
                default -> CompletableFuture.failedFuture(
                    new IllegalArgumentException("Unknown surstor operation: " + getSubOperation(meta)));
            };
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ========== surstor/store ==========

    private synchronized ACell handleStore(ACell input, RequestContext ctx) {
        // Defence in depth for embedded callers. HTTP ingress must also filter
        // before Covia's JobManager can persist an invocation input.
        ACell policyFile = RT.getIn(engine.adapterConfig(getName()), Strings.create("privacy-policy-file"));
        PrivacyFilter.requireClean(input, PrivacyFilter.load(policyFile == null ? null : policyFile.toString()));
        ACell contentCell = RT.getIn(input, Strings.create(K_CONTENT));
        ACell contentTypeCell = RT.getIn(input, Strings.create(K_CONTENT_TYPE));
        ACell sessionIdCell = RT.getIn(input, Strings.create(K_SESSION_ID));
        ACell agentIdCell = RT.getIn(input, Strings.create(K_AGENT_ID));
        ACell modelVersionCell = RT.getIn(input, Strings.create(K_MODEL_VERSION));
        ACell usageCell = RT.getIn(input, Strings.create(K_USAGE));

        if (contentCell == null) throw new IllegalArgumentException("content is required");
        String contentType = (contentTypeCell instanceof AString) ? contentTypeCell.toString() : "text";
        String sessionId = (sessionIdCell instanceof AString) ? sessionIdCell.toString() : "default";
        if (sessionId.startsWith("sync-")) throw new IllegalArgumentException("Replicated sessions are read-only; use a new local session");
        String agentId = (agentIdCell instanceof AString) ? agentIdCell.toString() : getDefaultAgentId();
        String modelVersion = (modelVersionCell instanceof AString) ? modelVersionCell.toString() : null;

        AMap<AString, ACell> usage = (usageCell instanceof AMap) ? RT.ensureMap(usageCell) : null;
        if (usageCell != null && usage == null) throw new IllegalArgumentException("usage must be an object");
        long timestamp = System.currentTimeMillis();
        ACell contextCell = RT.getIn(input, Strings.create("billingContext"));
        if (contextCell != null && !(contextCell instanceof AMap)) throw new IllegalArgumentException("billingContext must be an object");

        // Build Cell 1
        String canonicalType = Cells.canonicalContentType(contentType);
        AMap<AString, ACell> cell1 = Cells.buildCell1(contentCell, canonicalType);

        // Compute cost
        String costUsd = null;
        Hash priceScheduleId = null;
        AMap<AString, ACell> billing = null;
        AMap<AString, ACell> schedule = getCurrentSchedule(ctx);
        if (Billing.isV2(schedule)) {
            billing = Billing.estimate(modelVersion, usage, schedule,
                Billing.context(Billing.map(contextCell), timestamp));
            costUsd = Billing.total(billing);
            priceScheduleId = Pricing.scheduleId(schedule);
        } else if (usage != null && modelVersion != null) {
            if (contextCell != null) throw new IllegalArgumentException("billingContext requires a v2 schedule");
            // Legacy custom schedules retain their original three-category contract.
            for (ACell k : usage.keySet()) {
                if (!java.util.Set.of("input","output","cached-input").contains(k.toString()))
                    throw new IllegalArgumentException("Legacy schedules do not support charge: " + k);
                if (!(usage.get(k) instanceof CVMLong n) || n.longValue() < 0)
                    throw new IllegalArgumentException("Legacy usage must contain nonnegative integer counts");
            }
            if (schedule != null) {
                costUsd = Costs.compute(modelVersion, usage, schedule);
                // Only cite the schedule when a cost was actually computed —
                // a model missing from the schedule yields no cost claim.
                if (costUsd != null) priceScheduleId = Pricing.scheduleId(schedule);
            }
        }

        // Get session and prev-event
        AMap<AString, ACell> session = getSession(ctx, sessionId);
        Hash prevEvent = Sessions.head(session);

        // Build Cell 2
        AMap<AString, ACell> cell2 = Cells.buildCell2(
            cell1, sessionId, agentId, modelVersion, timestamp,
            usage, costUsd, priceScheduleId, prevEvent);
        if (billing != null) cell2 = cell2.assoc(Billing.KEY, billing);
        cell2 = cell2.assoc(Strings.create("privacy"), Maps.of(
            Strings.create("detector"), Strings.create(PrivacyFilter.VERSION),
            Strings.create("result"), Strings.create("passed-storage-filter")));

        // Budget enforcement
        if (costUsd != null) {
            String newTotal = Costs.add(Sessions.totalCost(session), costUsd);
            String budget = getSessionBudget();
            if (Costs.exceedsBudget(newTotal, budget)) {
                throw new BudgetExceededException(
                    "Session budget exceeded", newTotal, budget, sessionId);
            }
        }

        // Sign the unsigned cell's hash with the node identity; the signature
        // rides inside the stored cell (the signed form excludes these fields).
        AKeyPair nodeKey = engine.getKeyPair();
        ASignature signature = nodeKey.sign(cell2.getHash());
        cell2 = cell2
            .assoc(Cells.K_SIGNATURE, Strings.create(signature.toHexString()))
            .assoc(Cells.K_SIGNER, Strings.create(nodeKey.getAccountKey().toHexString()));

        // Store cells
        Hash cell1Hash = engine.storeUserAsset(Cells.toJson(cell1), cell1, ctx);
        Hash cell2Hash = engine.storeUserAsset(Cells.toJson(cell2), cell2, ctx);

        // Update session
        AMap<AString, ACell> updatedSession = Sessions.advanceSession(session, cell2Hash, costUsd, timestamp);
        putSession(ctx, sessionId, updatedSession);

        // Derived semantic-search index. This is unsigned, disposable data —
        // it never touches the signed cells, and a failure here must NEVER
        // fail the store: log, leave the index dirty, let surstor:reindex
        // repair it later.
        if (searchEnabled() && contentCell instanceof AString) {
            try {
                indexArtifact(searchState(), Cells.artifactId(cell2),
                    contentCell.toString(), sessionId, timestamp);
            } catch (Exception | LinkageError e) {
                // LinkageError too: adapters running on a plain classpath
                // (e.g. surstor-node tests) have no DJL classes at all, so
                // the lazy model load throws NoClassDefFoundError — an Error.
                log.warn("Search indexing failed for {} — store unaffected; run surstor:reindex to repair: {}",
                    Cells.artifactId(cell2), e.toString());
            }
        }

        // Return IDs
        return Maps.of(
            Fields.ID, Strings.create(Cells.artifactId(cell2)),
            Strings.create(K_SHORT_ID), Strings.create(Cells.shortId(cell2)),
            Strings.create("cell1Id"), Strings.create(cell1Hash.toHexString()),
            Billing.KEY, billing, Strings.create("warning"), Strings.create(Billing.WARNING));
    }

    // ========== surstor/retrieve ==========

    private ACell handleRetrieve(ACell input, RequestContext ctx) {
        ACell idCell = RT.getIn(input, Fields.ID);
        if (idCell == null) idCell = RT.getIn(input, Strings.create("shortId"));
        if (idCell == null) throw new IllegalArgumentException("id or shortId is required");

        String artifactId = idCell.toString();
        AMap<AString, ACell> cell2 = Evidence.resolveCell2(engine, ctx, artifactId);
        if (cell2 == null) throw new ArtifactNotFoundException("Artifact not found: " + artifactId);

        Hash cell1Hash = Cells.branchRef(cell2);
        AMap<AString, ACell> cell1 = Evidence.resolveCell1(engine, ctx, cell1Hash);
        if (cell1 == null) throw new ArtifactNotFoundException("Content cell not found: " + cell1Hash);

        ACell content = cell1.get(Cells.K_CONTENT);
        AMap<AString, ACell> metadata = Maps.of(
            Strings.create("cell2Id"), Strings.create(Cells.artifactId(cell2)),
            Strings.create("cell1Id"), Strings.create(cell1Hash.toHexString()),
            Strings.create("sessionId"), Strings.create(Cells.sessionId(cell2)),
            Strings.create("agentId"), Strings.create(getString(cell2, Cells.K_AGENT_ID)),
            Strings.create("modelVersion"), Strings.create(getString(cell2, Cells.K_MODEL_VERSION)),
            Strings.create("timestamp"), cell2.get(Cells.K_TIMESTAMP),
            Strings.create("tokenCount"), cell2.get(Cells.K_TOKEN_COUNT),
            Strings.create("costUsd"), cell2.get(Cells.K_COST_USD),
            Billing.KEY, cell2.get(Billing.KEY),
            Strings.create("privacy"), cell2.get(Strings.create("privacy")),
            Strings.create("warning"), Strings.create(Billing.WARNING),
            Strings.create("priceScheduleId"), cell2.get(Cells.K_PRICE_SCHEDULE_ID),
            Strings.create("prevEvent"), cell2.get(Cells.K_PREV_EVENT));

        return Maps.of(
            Fields.CONTENT, content,
            Strings.create("warning"), Strings.create(Billing.WARNING),
            Fields.METADATA, metadata);
    }

    // ========== surstor/verify ==========

    private ACell handleVerify(ACell input, RequestContext ctx) {
        ACell idCell = RT.getIn(input, Fields.ID);
        if (idCell == null) throw new IllegalArgumentException("id is required");
        ACell deepCell = RT.getIn(input, Strings.create(K_DEEP));
        boolean deep = (deepCell instanceof CVMBool) && ((CVMBool) deepCell).isTrue();

        return Evidence.verify(engine, ctx, idCell.toString(), deep);
    }

    // ========== surstor/list ==========

    private ACell handleList(ACell input, RequestContext ctx) {
        ACell sessionIdCell = RT.getIn(input, Strings.create(K_SESSION_ID_OPT));
        ACell limitCell = RT.getIn(input, Strings.create(K_LIMIT));
        ACell offsetCell = RT.getIn(input, Strings.create(K_OFFSET));

        long limit = (limitCell instanceof CVMLong) ? Math.max(1, Math.min(1000, ((CVMLong) limitCell).longValue())) : 100;
        long offset = (offsetCell instanceof CVMLong) ? Math.max(0, ((CVMLong) offsetCell).longValue()) : 0;
        String sessionFilter = (sessionIdCell instanceof AString) ? sessionIdCell.toString() : null;

        // List user's assets and filter to SurStor metadata cells
        covia.venue.User user = engine.getVenueState().users().get(ctx.getUserDID());
        AMap<ABlob, AVector<?>> userAssets = (user != null) ? user.assets().getAll() : null;
        long rawTotal = (userAssets != null) ? userAssets.count() : 0;

        AVector<ACell> items = Vectors.empty();
        long matched = 0;
        long emitted = 0;

        if (userAssets != null) {
            for (long i = 0; i < rawTotal; i++) {
                var entry = userAssets.entryAt(i);
                if (entry == null) continue;
                AVector<ACell> record = (AVector<ACell>) entry.getValue();
                AMap<AString, ACell> meta = RT.ensureMap(record.get(covia.venue.AssetStore.POS_META));
                if (meta == null) continue;

                // Only include Cell 2 records (those with branch-ref)
                if (meta.get(Cells.K_BRANCH_REF) == null) continue;

                String sessionId = Cells.sessionId(meta);
                if (sessionFilter != null && !sessionFilter.equals(sessionId)) continue;

                matched++;
                if (matched <= offset) continue;
                if (emitted >= limit) continue;

                Hash h = Hash.wrap(entry.getKey().getBytes());
                AMap<AString, ACell> summary = Maps.of(
                    Fields.ID, Strings.create(h.toHexString()),
                    Strings.create(K_SHORT_ID), Strings.create("ss_" + h.toHexString().substring(0, 12)),
                    Strings.create("sessionId"), Strings.create(sessionId),
                    Strings.create("agentId"), Strings.create(getString(meta, Cells.K_AGENT_ID)),
                    Strings.create("timestamp"), meta.get(Cells.K_TIMESTAMP),
                    Strings.create("costUsd"), meta.get(Cells.K_COST_USD));
                items = items.conj(summary);
                emitted++;
            }
        }

        return Maps.of(
            Fields.ITEMS, items,
            Fields.TOTAL, CVMLong.create(matched),
            Strings.create("warning"),Strings.create(Billing.WARNING),
            Fields.OFFSET, CVMLong.create(offset),
            Fields.LIMIT, CVMLong.create(limit));
    }

    // ========== surstor/diff ==========

    private ACell handleDiff(ACell input, RequestContext ctx) {
        ACell idACell = RT.getIn(input, Strings.create(K_ID_A));
        ACell idBCell = RT.getIn(input, Strings.create(K_ID_B));
        if (idACell == null || idBCell == null) throw new IllegalArgumentException("idA and idB are required");

        AMap<AString, ACell> cell2A = Evidence.resolveCell2(engine, ctx, idACell.toString());
        AMap<AString, ACell> cell2B = Evidence.resolveCell2(engine, ctx, idBCell.toString());
        if (cell2A == null || cell2B == null) throw new ArtifactNotFoundException("One or both artifacts not found");

        Hash cell1HashA = Cells.branchRef(cell2A);
        Hash cell1HashB = Cells.branchRef(cell2B);
        AMap<AString, ACell> cell1A = Evidence.resolveCell1(engine, ctx, cell1HashA);
        AMap<AString, ACell> cell1B = Evidence.resolveCell1(engine, ctx, cell1HashB);
        if (cell1A == null || cell1B == null) throw new ArtifactNotFoundException("Content cells not found");

        ACell contentA = cell1A.get(Cells.K_CONTENT);
        ACell contentB = cell1B.get(Cells.K_CONTENT);

        // Only text content can be diffed
        if (!(contentA instanceof AString) || !(contentB instanceof AString)) {
            return Maps.of(
                Strings.create("diffable"), CVMBool.FALSE,
                Strings.create("message"), Strings.create("Only text content can be diffed"));
        }

        String textA = contentA.toString();
        String textB = contentB.toString();
        String diff = unifiedDiff(textA, textB);

        return Maps.of(
            Strings.create("diffable"), CVMBool.TRUE,
            Strings.create("diff"), Strings.create(diff));
    }

    // ========== surstor/cost ==========

    private ACell handleCost(ACell input, RequestContext ctx) {
        ACell sessionIdCell = RT.getIn(input, Strings.create(K_SESSION_ID_OPT));
        String sessionId = (sessionIdCell instanceof AString) ? sessionIdCell.toString() : null;

        if (sessionId != null) {
            AMap<AString, ACell> session = getSession(ctx, sessionId);
            return Maps.of(
                Strings.create(K_TOTAL_USD), Strings.create(Sessions.totalCost(session)),
                Strings.create(K_ARTIFACT_COUNT), CVMLong.create(Sessions.artifactCount(session)),
                Strings.create("warning"), Strings.create(Billing.WARNING),
                Strings.create("unpricedCount"), Sessions.unpricedCount(session),
                Strings.create("sessionId"), Strings.create(sessionId));
        }

        // Aggregate local sessions only; replicated fork views can share ancestors.
        AMap<AString, ACell> sessions = getAllSessions(ctx);
        String total = "0.000000";
        long artifactCount = 0;
        long unpricedCount = 0;
        boolean coverageKnown = true;

        for (ACell k : sessions.keySet()) {
            if (k.toString().startsWith("sync-")) continue;
            ACell v = sessions.get(k);
            if (!(v instanceof AMap)) continue;
            AMap<AString, ACell> session = RT.ensureMap(v);
            total = Costs.add(total, Sessions.totalCost(session));
            artifactCount += Sessions.artifactCount(session);
            ACell missing = Sessions.unpricedCount(session);
            if (missing instanceof CVMLong n) unpricedCount += n.longValue();
            else coverageKnown = false;
        }

        return Maps.of(
            Strings.create(K_TOTAL_USD), Strings.create(total),
            Strings.create("scope"), Strings.create("local-sessions"),
            Strings.create("warning"), Strings.create(Billing.WARNING),
            Strings.create("unpricedCount"), coverageKnown ? CVMLong.create(unpricedCount) : null,
            Strings.create(K_ARTIFACT_COUNT), CVMLong.create(artifactCount));
    }

    // ========== surstor/sessions ==========

    private ACell handleSessions(ACell input, RequestContext ctx) {
        AMap<AString, ACell> sessions = getAllSessions(ctx);
        AVector<ACell> items = Vectors.empty();

        for (ACell k : sessions.keySet()) {
            ACell v = sessions.get(k);
            if (!(v instanceof AMap)) continue;
            AMap<AString, ACell> session = RT.ensureMap(v);
            String sessionId = k.toString();
            AMap<AString, ACell> summary = Maps.of(
                Strings.create("sessionId"), Strings.create(sessionId),
                Strings.create("sourceSession"), session.get(Strings.create("source-session")),
                Strings.create("sourceSigner"), session.get(Strings.create("source-signer")),
                Strings.create("readOnly"), CVMBool.create(sessionId.startsWith("sync-")),
                Strings.create("head"), session.get(Sessions.K_HEAD),
                Strings.create("count"), session.get(Sessions.K_COUNT),
                Strings.create("artifactCount"), session.get(Sessions.K_ARTIFACT_COUNT),
                Strings.create("totalCostUsd"), session.get(Sessions.K_TOTAL_COST_USD),
                Strings.create("unpricedCount"), Sessions.unpricedCount(session),
                Strings.create("warning"), Strings.create(Billing.WARNING),
                Strings.create("created"), session.get(Sessions.K_CREATED),
                Strings.create("updated"), session.get(Sessions.K_UPDATED));
            items = items.conj(summary);
        }

        return Maps.of(Fields.ITEMS, items, Strings.create("warning"),Strings.create(Billing.WARNING));
    }

    // ========== surstor/export ==========

    private static final long EXPORT_MAX_EVENTS = 10000;

    private ACell handleExport(ACell input, RequestContext ctx) {
        ACell sessionIdCell = RT.getIn(input, Strings.create(K_SESSION_ID_OPT));
        if (!(sessionIdCell instanceof AString)) throw new IllegalArgumentException("sessionId is required");
        String sessionId = sessionIdCell.toString();

        AMap<AString, ACell> session = getSession(ctx, sessionId);
        Hash head = Sessions.head(session);
        if (head == null) throw new ArtifactNotFoundException("Session is empty: " + sessionId);

        // Walk the chain from the head back to genesis, collecting both cells
        AVector<ACell> artifacts = Vectors.empty();
        AMap<AString, ACell> schedules = Maps.empty();
        Hash cursor = head;
        long n = 0;
        while (cursor != null) {
            if (++n > EXPORT_MAX_EVENTS) throw new IllegalStateException(
                "Session exceeds export limit of " + EXPORT_MAX_EVENTS + " events");
            AMap<AString, ACell> cell2 = Evidence.resolveCell2ByHash(engine, ctx, cursor);
            if (cell2 == null) throw new ArtifactNotFoundException(
                "Chain gap during export — event not found: " + cursor.toHexString());
            Hash cell1Hash = Cells.branchRef(cell2);
            AMap<AString, ACell> cell1 = (cell1Hash != null) ? Evidence.resolveCell1(engine, ctx, cell1Hash) : null;
            if (cell1 == null) throw new ArtifactNotFoundException(
                "Content cell not found during export: " + cell1Hash);

            artifacts = artifacts.conj(Maps.of(
                Strings.create("cell1"), cell1,
                Strings.create("cell2"), cell2));

            Hash scheduleId = Cells.priceScheduleId(cell2);
            AString scheduleKey = (scheduleId != null) ? Cells.hashString(scheduleId) : null;
            if (scheduleKey != null && !schedules.containsKey(scheduleKey)) {
                AMap<AString, ACell> schedule = Evidence.resolveSchedule(engine, ctx, scheduleId);
                if (schedule != null) schedules = schedules.assoc(scheduleKey, schedule);
            }

            cursor = Cells.prevEvent(cell2);
        }

        return Maps.of(
            Strings.create("format"), Strings.create("surstor-export/1"),
            Strings.create("warning"), Strings.create(Billing.WARNING),
            Strings.create(K_SESSION_ID_OPT), Strings.create(sessionId),
            Strings.create("exportedAt"), CVMLong.create(System.currentTimeMillis()),
            Strings.create("session"), session,
            Strings.create("artifacts"), artifacts,
            Strings.create("schedules"), schedules);
    }

    // ========== surstor/prices/get ==========

    private ACell handlePricesGet(ACell input, RequestContext ctx) {
        AMap<AString, ACell> schedule = getCurrentSchedule(ctx);
        if (schedule == null) throw new ScheduleUnknownException("No price schedule available");
        Hash id = Pricing.scheduleId(schedule);
        return Maps.of(
            Strings.create("schedule"), schedule,
            Strings.create("warning"), Strings.create(Billing.WARNING),
            Strings.create("scheduleId"), Strings.create(id.toHexString()));
    }

    // ========== surstor/prices/put ==========

    private ACell handlePricesPut(ACell input, RequestContext ctx) {
        ACell scheduleCell = RT.getIn(input, Strings.create(K_SCHEDULE));
        if (scheduleCell == null) throw new IllegalArgumentException("schedule is required");

        AMap<AString, ACell> schedule;
        if (scheduleCell instanceof AMap) {
            schedule = RT.ensureMap(scheduleCell);
        } else if (scheduleCell instanceof AString) {
            schedule = Pricing.parse(scheduleCell.toString());
        } else {
            throw new IllegalArgumentException("schedule must be a JSON object or JSON string");
        }

        if (!Pricing.isValid(schedule)) {
            throw new IllegalArgumentException("Invalid price schedule: expected valid legacy rates or a USD v2 tariff schedule");
        }

        Hash scheduleId = engine.storeUserAsset(Pricing.toJson(schedule), schedule, ctx);
        putCurrentSchedule(ctx, schedule, scheduleId);

        return Maps.of(Strings.create("scheduleId"), Strings.create(scheduleId.toHexString()),
            Strings.create("warning"), Strings.create(Billing.WARNING));
    }

    // ========== surstor/search ==========

    private ACell handleSearch(ACell input, RequestContext ctx) {
        ACell queryCell = RT.getIn(input, Strings.create("query"));
        if (!(queryCell instanceof AString) || queryCell.toString().isBlank()) {
            throw new IllegalArgumentException("query is required");
        }
        String query = queryCell.toString();

        ACell kCell = RT.getIn(input, Strings.create("k"));
        int k = (kCell instanceof CVMLong) ? (int) ((CVMLong) kCell).longValue() : getSearchKDefault();
        ACell sessionCell = RT.getIn(input, Strings.create(K_SESSION_ID_OPT));
        String sessionFilter = (sessionCell instanceof AString) ? sessionCell.toString() : null;

        long t0 = System.nanoTime();
        SearchState s = searchState();
        float[] queryVector = s.embeddings().embed(query);
        List<SearchIndex.SearchHit> hits;
        try {
            hits = s.index().search(queryVector, k, sessionFilter);
        } catch (IOException e) {
            throw new SearchUnavailableException("Search index unavailable: " + e.getMessage());
        }
        long tookMs = (System.nanoTime() - t0) / 1_000_000;

        AVector<ACell> results = Vectors.empty();
        for (SearchIndex.SearchHit hit : hits) {
            results = results.conj(Maps.of(
                Fields.ID, Strings.create(hit.artifactId()),
                Strings.create(K_SHORT_ID), Strings.create("ss_" + hit.artifactId().substring(0, 12)),
                Strings.create("score"), CVMDouble.create(hit.score()),
                Strings.create("snippet"), Strings.create(hit.snippet()),
                Strings.create("sessionId"), Strings.create(hit.sessionId()),
                Strings.create("timestamp"), CVMLong.create(hit.timestamp())));
        }

        return Maps.of(
            Strings.create("results"), results,
            Strings.create("model"), Strings.create(s.embeddings().modelId()),
            Strings.create("tookMs"), CVMLong.create(tookMs));
    }

    // ========== surstor/reindex ==========

    private synchronized ACell handleReindex(ACell input, RequestContext ctx) {
        SearchState s = searchState();
        long t0 = System.nanoTime();
        long reindexed = 0;
        long skipped = 0;

        // Clear + replay under the index write lock — safe to run while
        // serving; searches just wait for the rebuild to finish.
        synchronized (s.index().writeLock()) {
            try {
                s.index().clear();

                // Same enumeration as surstor:list — the etch store is the
                // source of truth; the index replays from it.
                covia.venue.User user = engine.getVenueState().users().get(ctx.getUserDID());
                AMap<ABlob, AVector<?>> userAssets = (user != null) ? user.assets().getAll() : null;
                long rawTotal = (userAssets != null) ? userAssets.count() : 0;

                for (long i = 0; i < rawTotal; i++) {
                    var entry = userAssets.entryAt(i);
                    if (entry == null) continue;
                    AVector<ACell> record = (AVector<ACell>) entry.getValue();
                    AMap<AString, ACell> meta = RT.ensureMap(record.get(covia.venue.AssetStore.POS_META));
                    if (meta == null) continue;
                    if (meta.get(Cells.K_BRANCH_REF) == null) continue;  // Cell 2 records only

                    String artifactId = Hash.wrap(entry.getKey().getBytes()).toHexString();
                    Hash cell1Hash = Cells.branchRef(meta);
                    AMap<AString, ACell> cell1 = Evidence.resolveCell1(engine, ctx, cell1Hash);
                    ACell content = (cell1 != null) ? cell1.get(Cells.K_CONTENT) : null;
                    ACell tsCell = meta.get(Cells.K_TIMESTAMP);
                    long timestamp = (tsCell instanceof CVMLong) ? ((CVMLong) tsCell).longValue() : 0L;

                    if (!(content instanceof AString)) {
                        skipped++;
                        continue;
                    }
                    indexArtifact(s, artifactId, content.toString(), Cells.sessionId(meta), timestamp);
                    reindexed++;
                }
            } catch (IOException e) {
                throw new SearchUnavailableException("Reindex failed: " + e.getMessage());
            }
        }

        long tookMs = (System.nanoTime() - t0) / 1_000_000;
        return Maps.of(
            Strings.create("reindexed"), CVMLong.create(reindexed),
            Strings.create("skipped"), CVMLong.create(skipped),
            Strings.create("tookMs"), CVMLong.create(tookMs));
    }

    // ========== Search plumbing (embeddings + index lifecycle) ==========

    /** Lazily initialised search state — model load takes ~1–2 s, so node boot must not pay it. */
    private volatile SearchState searchState;

    private record SearchState(Embeddings embeddings, SearchIndex index) {}

    private SearchState searchState() {
        SearchState s = searchState;
        if (s == null) {
            synchronized (this) {
                s = searchState;
                if (s == null) {
                    if (!searchEnabled()) throw new SearchUnavailableException(
                        "Semantic search is disabled (search.enabled = false in node config)");
                    s = openSearchState();
                    searchState = s;
                }
            }
        }
        return s;
    }

    private SearchState openSearchState() {
        Embeddings embeddings;
        try {
            embeddings = Embeddings.loadBundled();
        } catch (LinkageError e) {
            // DJL/Lucene absent from the classpath (e.g. adapters unit-tested
            // outside the module jar) surfaces as NoClassDefFoundError, an
            // Error — normalise it so callers see an ordinary failure.
            throw new SearchUnavailableException("Semantic search unavailable: " + e);
        }
        try {
            SearchIndex index = SearchIndex.open(searchIndexDir());
            try {
                if (index.documentCount() == 0) {
                    log.info("Search index is empty — stored artifacts are not searchable until surstor:reindex runs");
                }
            } catch (IOException ignored) {
                // count is diagnostic only
            }
            return new SearchState(embeddings, index);
        } catch (IOException e) {
            embeddings.close();
            throw new SearchUnavailableException("Cannot open search index: " + e.getMessage());
        }
    }

    private void indexArtifact(SearchState s, String artifactId, String content,
                               String sessionId, long timestamp) throws IOException {
        List<String> chunks = s.embeddings().chunk(content);
        for (int i = 0; i < chunks.size(); i++) {
            float[] vector = s.embeddings().embed(chunks.get(i));
            s.index().addChunk(artifactId, i, chunks.get(i), vector, sessionId, timestamp);
        }
    }

    private boolean searchEnabled() {
        ACell cfg = engine.adapterConfig(getName());
        if (cfg != null) {
            ACell v = RT.getIn(cfg, Strings.create("search-enabled"));
            if (v instanceof AString) return Boolean.parseBoolean(v.toString());
        }
        return true;
    }

    private int getSearchKDefault() {
        ACell cfg = engine.adapterConfig(getName());
        if (cfg != null) {
            ACell v = RT.getIn(cfg, Strings.create("k-default"));
            if (v instanceof AString) {
                try {
                    return Integer.parseInt(v.toString());
                } catch (NumberFormatException ignored) {
                    // fall through to default
                }
            }
        }
        return 10;
    }

    private Path searchIndexDir() {
        ACell cfg = engine.adapterConfig(getName());
        if (cfg != null) {
            ACell v = RT.getIn(cfg, Strings.create("search-index-dir"));
            if (v instanceof AString) return Path.of(v.toString());
        }
        return Path.of(System.getProperty("user.home"), ".surstor-node", "search-index");
    }

    /** Engine.close() closes AutoCloseable adapters — flush index, unload model. */
    @Override
    public void close() {
        SearchState s = searchState;
        if (s == null) return;
        try {
            s.index().close();
        } catch (IOException e) {
            log.warn("Failed to close search index: {}", e.toString());
        }
        s.embeddings().close();
    }

    // ========== Session and schedule helpers ==========

    private AMap<AString, ACell> getSession(RequestContext ctx, String sessionId) {
        AdapterWorkspace ws = adapterWorkspace();
        ACell existing = ws.read("sessions/" + sessionId);
        if (existing instanceof AMap) return RT.ensureMap(existing);
        return Sessions.createSession(sessionId, System.currentTimeMillis());
    }

    private void putSession(RequestContext ctx, String sessionId, AMap<AString, ACell> session) {
        adapterWorkspace().write("sessions/" + sessionId, session);
    }

    private AMap<AString, ACell> getAllSessions(RequestContext ctx) {
        ACell all = adapterWorkspace().read("sessions");
        if (all instanceof AMap) return RT.ensureMap(all);
        return Maps.empty();
    }

    private AMap<AString, ACell> getCurrentSchedule(RequestContext ctx) {
        // Try stored schedule first
        ACell stored = adapterWorkspace().read("current-schedule");
        if (stored instanceof AMap) {
            AMap<AString, ACell> current = RT.ensureMap(stored);
            // Upgrade only the byte-for-byte built-in placeholder. Preserve custom schedules and old assets.
            if (!current.equals(Pricing.loadResource(Pricing.LEGACY_SEED_RESOURCE))) return current;
        }

        // Fall back to seed schedule
        AMap<AString, ACell> seed = Pricing.loadSeedSchedule();
        Hash id = engine.storeUserAsset(Pricing.toJson(seed), seed, ctx);
        putCurrentSchedule(ctx, seed, id);
        return seed;
    }

    private void putCurrentSchedule(RequestContext ctx, AMap<AString, ACell> schedule, Hash id) {
        adapterWorkspace().write("current-schedule", schedule);
        adapterWorkspace().write("current-schedule-id", id);
    }

    private String getDefaultAgentId() {
        ACell cfg = engine.adapterConfig(getName());
        if (cfg != null) {
            ACell v = RT.getIn(cfg, Strings.create("default-agent-id"));
            if (v instanceof AString) return v.toString();
        }
        return "local-user";
    }

    private String getSessionBudget() {
        ACell cfg = engine.adapterConfig(getName());
        if (cfg != null) {
            ACell v = RT.getIn(cfg, Strings.create("session-budget-usd"));
            if (v instanceof AString) return v.toString();
        }
        return "5.00";
    }

    // ========== Utilities ==========

    private static String getString(AMap<AString, ACell> m, AString k) {
        ACell v = m.get(k);
        return (v instanceof AString) ? v.toString() : null;
    }

    private static String unifiedDiff(String a, String b) {
        String[] linesA = a.split("\n", -1);
        String[] linesB = b.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- a\n+++ b\n");

        int max = Math.max(linesA.length, linesB.length);
        int context = 3;
        List<String> diffLines = new ArrayList<>();

        for (int i = 0; i < max; i++) {
            String la = i < linesA.length ? linesA[i] : null;
            String lb = i < linesB.length ? linesB[i] : null;
            if (la != null && la.equals(lb)) {
                diffLines.add(" " + la);
            } else {
                if (la != null) diffLines.add("-" + la);
                if (lb != null) diffLines.add("+" + lb);
            }
        }

        for (String line : diffLines) sb.append(line).append("\n");
        return sb.toString();
    }

    // ========== Typed exceptions ==========

    public static class ArtifactNotFoundException extends RuntimeException {
        public ArtifactNotFoundException(String message) { super(message); }
    }

    public static class TamperDetectedException extends RuntimeException {
        public TamperDetectedException(String message) { super(message); }
    }

    public static class BudgetExceededException extends RuntimeException {
        private final String runningTotal;
        private final String budget;
        private final String sessionId;

        public BudgetExceededException(String message, String runningTotal, String budget, String sessionId) {
            super(message);
            this.runningTotal = runningTotal;
            this.budget = budget;
            this.sessionId = sessionId;
        }

        public String getRunningTotal() { return runningTotal; }
        public String getBudget() { return budget; }
        public String getSessionId() { return sessionId; }
    }

    public static class ScheduleUnknownException extends RuntimeException {
        public ScheduleUnknownException(String message) { super(message); }
    }

    public static class SearchUnavailableException extends RuntimeException {
        public SearchUnavailableException(String message) { super(message); }
    }
}
