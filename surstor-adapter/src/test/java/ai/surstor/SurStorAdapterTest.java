package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.Config;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Engine-backed integration tests for the SurStor evidence adapter.
 *
 * <p>Covers the tamper-evidence contract: a byte-flipped content cell fails
 * the branch-ref check, a dropped event fails the chain walk, and a rewritten
 * middle event can never become the session's recorded head.</p>
 */
class SurStorAdapterTest {

    private Engine engine;
    private SurStorAdapter adapter;
    private RequestContext ctx;

    @BeforeEach
    void setup() {
        engine = Engine.createTemp(null);
        adapter = new SurStorAdapter();
        engine.registerAdapter(adapter);
        ctx = RequestContext.of(Strings.create("did:key:z6MkSurStorTest"));
    }

    @AfterEach
    void teardown() {
        engine.close();
    }

    // ========== Helpers ==========

    private static AMap<AString, ACell> meta(String subOp) {
        return Maps.of(Strings.create("operation"),
            Maps.of(Strings.create("adapter"), Strings.create("surstor:" + subOp)));
    }

    private ACell invoke(String subOp, AMap<AString, ACell> input) throws Exception {
        return adapter.invokeFuture(ctx, meta(subOp), input).get(10, TimeUnit.SECONDS);
    }

    private AMap<AString, ACell> store(String content, String sessionId, String model, Long in, Long out) throws Exception {
        AMap<AString, ACell> input = Maps.of(
            Strings.create("content"), Strings.create(content),
            Strings.create("sessionId"), Strings.create(sessionId));
        if (model != null) {
            input = input.assoc(Strings.create("modelVersion"), Strings.create(model));
            AMap<AString, ACell> usage = Maps.of(
                Costs.K_INPUT, CVMLong.create(in),
                Costs.K_OUTPUT, CVMLong.create(out));
            input = input.assoc(Strings.create("usage"), usage);
        }
        return RT.ensureMap(invoke("store", input));
    }

    private static String id(AMap<AString, ACell> storeResult) {
        return RT.ensureString(storeResult.get(covia.api.Fields.ID)).toString();
    }

    private AMap<AString, ACell> verify(String artifactId, boolean deep) throws Exception {
        return RT.ensureMap(invoke("verify", Maps.of(
            covia.api.Fields.ID, Strings.create(artifactId),
            Strings.create("deep"), CVMBool.create(deep))));
    }

    private static boolean valid(AMap<AString, ACell> verifyResult) {
        return RT.bool(verifyResult.get(Evidence.K_VALID));
    }

    private static boolean check(AMap<AString, ACell> verifyResult, AString name) {
        return RT.bool(RT.getIn(verifyResult, Evidence.K_CHECKS, name));
    }

    // ========== Happy path ==========

    @Test
    void billingWarningsAndUnpricedCoverageAreVisible() throws Exception {
        var result = store("unknown model", "unpriced", "unknown/model", 100L, 50L);
        assertNotNull(result.get(Strings.create("warning")));
        assertEquals("unpriced", RT.getIn(result, Billing.KEY, Strings.create("status")).toString());
        var summary = RT.ensureMap(invoke("cost",Maps.of(Strings.create("sessionId"),Strings.create("unpriced"))));
        assertEquals("1",summary.get(Strings.create("unpricedCount")).toString());
        assertEquals("0.000000",summary.get(Strings.create("totalUsd")).toString());
        assertTrue(valid(verify(id(result),true)),"unpriced billing metadata still replays");
        var got=RT.ensureMap(invoke("retrieve",Maps.of(covia.api.Fields.ID,Strings.create(id(result)))));
        assertNotNull(RT.getIn(got,covia.api.Fields.METADATA,Billing.KEY));
        assertNotNull(got.get(Strings.create("warning")));
    }

    @Test
    void migratesOnlyExactPlaceholderAndPreservesOldEvidence() throws Exception {
        var custom = Pricing.loadResource(Pricing.LEGACY_SEED_RESOURCE).assoc(Strings.create("_owner"),Strings.create("custom"));
        invoke("prices/put",Maps.of(Strings.create("schedule"),custom));
        var old=store("old arithmetic","migration","anthropic/claude-sonnet-5",1000L,500L);
        assertEquals("0.010500",Cells.costUsd(Evidence.resolveCell2(engine,ctx,id(old))));
        assertEquals(custom,RT.getIn(invoke("prices/get",Maps.empty()),Strings.create("schedule")));
        invoke("prices/put",Maps.of(Strings.create("schedule"),Pricing.loadResource(Pricing.LEGACY_SEED_RESOURCE)));
        assertEquals(Pricing.loadSeedSchedule(),RT.getIn(invoke("prices/get",Maps.empty()),Strings.create("schedule")));
        var current=store("current arithmetic","migration","anthropic/claude-sonnet-5",1000L,500L);
        assertEquals("0.007000",Cells.costUsd(Evidence.resolveCell2(engine,ctx,id(current))));
        assertTrue(valid(verify(id(old),true)));
        assertTrue(valid(verify(id(current),true)));
    }

    @Test
    void storeVerifyRetrieveRoundTrip() throws Exception {
        AMap<AString, ACell> r = store("hello evidence", "s1", "anthropic/claude-sonnet-4-6", 1000L, 500L);
        String artifactId = id(r);
        assertTrue(r.get(Strings.create("shortId")).toString().startsWith("ss_"));

        AMap<AString, ACell> v = verify(artifactId, true);
        assertTrue(valid(v), "fresh artifact verifies: " + v);
        assertTrue(check(v, Evidence.K_BRANCH_REF_CHECK));
        assertTrue(check(v, Evidence.K_CHAIN_CHECK));
        assertTrue(check(v, Evidence.K_SCHEDULE_CHECK));

        AMap<AString, ACell> got = RT.ensureMap(invoke("retrieve", Maps.of(
            covia.api.Fields.ID, Strings.create(artifactId))));
        assertEquals("hello evidence", RT.ensureString(got.get(covia.api.Fields.CONTENT)).toString());

        // Short ID resolves too
        AMap<AString, ACell> vShort = verify(r.get(Strings.create("shortId")).toString(), false);
        assertTrue(valid(vShort), "short ID resolves and verifies");

        // Cost frozen into the record and rolled up
        AMap<AString, ACell> cost = RT.ensureMap(invoke("cost", Maps.of(
            Strings.create("sessionId"), Strings.create("s1"))));
        assertEquals("0.010500", RT.ensureString(cost.get(Strings.create("totalUsd"))).toString());
    }

    // ========== Tamper scenario 1: byte-flip Cell 1 ==========

    @Test
    void byteFlippedCell1FailsBranchRef() throws Exception {
        AMap<AString, ACell> r = store("original content", "s1", null, null, null);
        AMap<AString, ACell> cell2 = Evidence.resolveCell2(engine, ctx, id(r));
        assertNotNull(cell2);

        // Adversary flips the content: same shape, one byte different
        AMap<AString, ACell> tampered = Cells.buildCell1(Strings.create("original contend"), Cells.TYPE_TEXT);
        assertFalse(Cells.verifyBranchRef(cell2, tampered),
            "flipped content cell must not match the recorded branch-ref");

        // And the genuine record still verifies â€” the CAS never serves the tampered bytes under the true ID
        assertTrue(valid(verify(id(r), true)));
    }

    // ========== Tamper scenario 2: rewrite a middle event ==========

    @Test
    void rewrittenMiddleEventNeverBecomesSessionHead() throws Exception {
        String s = "rewrite-session";
        String id1 = id(store("event 1", s, null, null, null));
        String id2 = id(store("event 2", s, null, null, null));
        String id3 = id(store("event 3", s, null, null, null));

        // Adversary rewrites event 2 with altered content and re-chains event 3 onto it
        AMap<AString, ACell> forgedCell1 = Cells.buildCell1(Strings.create("event 2 â€” edited by adversary"), Cells.TYPE_TEXT);
        AMap<AString, ACell> forged2 = Cells.buildCell2(forgedCell1, s, "agent-1", null,
            1700000002000L, null, null, null, Hash.fromHex(id1));
        AMap<AString, ACell> forged3 = Cells.buildCell2(Cells.buildCell1(Strings.create("event 3"), Cells.TYPE_TEXT),
            s, "agent-1", null, 1700000003000L, null, null, null, forged2.getHash());

        // The forged chain is self-consistent â€” content addressing alone can't reject it...
        assertTrue(Cells.verifyBranchRef(forged3, Cells.buildCell1(Strings.create("event 3"), Cells.TYPE_TEXT)));

        // ...but the session record (written at capture time) still cites the honest head
        AMap<AString, ACell> sessions = RT.ensureMap(invoke("sessions", Maps.empty()));
        boolean found = false;
        for (ACell item : RT.ensureVector(sessions.get(covia.api.Fields.ITEMS))) {
            AMap<AString, ACell> sm = RT.ensureMap(item);
            if (!s.equals(RT.ensureString(sm.get(Strings.create("sessionId"))).toString())) continue;
            found = true;
            assertEquals("0x" + id3, RT.ensureString(sm.get(Sessions.K_HEAD)).toString(),
                "session head still cites the honest event 3");
            assertNotEquals("0x" + forged3.getHash().toHexString(),
                RT.ensureString(sm.get(Sessions.K_HEAD)).toString(),
                "forged head is not the recorded head");
        }
        assertTrue(found, "session present in listing");

        // The honest chain is untouched and still verifies end to end
        assertTrue(valid(verify(id3, true)), "honest chain intact after forgery attempt");

        // The forged event 2 is not the artifact the honest event 3 cites
        assertEquals(Hash.fromHex(id2), Cells.prevEvent(Evidence.resolveCell2(engine, ctx, id3)));
    }

    // ========== Tamper scenario 3: drop an event ==========

    @Test
    void droppedEventFailsDeepVerify() throws Exception {
        String s = "drop-session";
        String id1 = id(store("event 1", s, null, null, null));
        id(store("event 2", s, null, null, null)); // dropped from the "presented" history
        String id3 = id(store("event 3", s, null, null, null));

        // Presenting event 3 with the honest chain: still fine
        assertTrue(valid(verify(id3, true)));

        // An artifact whose prev-event cannot be resolved fails the chain check â€”
        // this is what a dropped middle event looks like to a verifier
        AMap<AString, ACell> gapCell2 = Cells.buildCell2(
            Cells.buildCell1(Strings.create("event 3 presented after drop"), Cells.TYPE_TEXT),
            s, "agent-1", null, 1700000003000L, null, null, null,
            Hash.fromHex("00".repeat(32))); // prev cites an event the verifier doesn't have
        Hash gapId = gapCell2.getHash();
        // Store the forged record so resolveCell2 finds it
        engine.storeUserAsset(Cells.toJson(gapCell2), gapCell2, ctx);
        AMap<AString, ACell> v = verify(gapId.toHexString(), true);
        assertFalse(valid(v), "gap in the chain must fail deep verification");
        assertFalse(check(v, Evidence.K_CHAIN_CHECK), "chain check reports the gap");

        // Chain walk from the honest head passes through every stored event
        AMap<AString, ACell> c3 = Evidence.resolveCell2(engine, ctx, id3);
        assertEquals(Hash.fromHex(id1),
            Cells.prevEvent(Evidence.resolveCell2(engine, ctx, Cells.prevEvent(c3).toHexString())),
            "honest chain walks 3 â†’ 2 â†’ 1 with no gaps");
    }

    // ========== Budget enforcement ==========

    @Test
    void sessionBudgetIsEnforced() throws Exception {
        // Tight budget: $0.01; one sonnet call of 1000+500 tokens costs $0.0105
        engine.close();
        engine = Engine.createTemp(Maps.of(Config.ADAPTERS, Maps.of(
            Strings.create("surstor"), Maps.of(
                Strings.create("session-budget-usd"), Strings.create("0.01")))));
        adapter = new SurStorAdapter();
        engine.registerAdapter(adapter);

        Exception thrown = assertThrows(Exception.class, () ->
            store("over budget call", "budget-s", "anthropic/claude-sonnet-4-6", 1000L, 500L));
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof SurStorAdapter.BudgetExceededException)) cause = cause.getCause();
        assertNotNull(cause, "BudgetExceededException in chain of: " + thrown);
    }

    @Test
    void unknownModelStoresWithoutCost() throws Exception {
        AMap<AString, ACell> r = store("unpriced work", "s1", "vendor/unannounced-model", 100L, 50L);
        AMap<AString, ACell> got = RT.ensureMap(invoke("retrieve", Maps.of(
            covia.api.Fields.ID, Strings.create(id(r)))));
        AMap<AString, ACell> metadata = RT.ensureMap(got.get(covia.api.Fields.METADATA));
        assertNull(metadata.get(Strings.create("costUsd")), "no rate â†’ no cost claim");
        // And a cost-free artifact still verifies
        assertTrue(valid(verify(id(r), true)));
    }
}
