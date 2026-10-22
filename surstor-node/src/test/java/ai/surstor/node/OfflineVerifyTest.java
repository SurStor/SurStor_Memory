package ai.surstor.node;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.core.util.JSON;
import covia.venue.Engine;
import covia.venue.RequestContext;

import ai.surstor.SurStorAdapter;
import ai.surstor.node.OfflineVerifier.BundleResult;
import ai.surstor.node.OfflineVerifier.CheckResult;

/**
 * End-to-end offline verification: export a session bundle from a live
 * engine, then verify it with no venue involved — including a tampered
 * bundle and a bundle with a dropped event.
 */
class OfflineVerifyTest {

    private Engine engine;
    private SurStorAdapter adapter;
    private RequestContext ctx;

    @BeforeEach
    void setup() {
        engine = Engine.createTemp(null);
        adapter = new SurStorAdapter();
        engine.registerAdapter(adapter);
        ctx = RequestContext.of(Strings.create("did:key:z6MkOfflineTest"));
    }

    @AfterEach
    void teardown() {
        engine.close();
    }

    private static AMap<AString, ACell> meta(String subOp) {
        return Maps.of(Strings.create("operation"),
            Maps.of(Strings.create("adapter"), Strings.create("surstor:" + subOp)));
    }

    private ACell invoke(String subOp, AMap<AString, ACell> input) throws Exception {
        return adapter.invokeFuture(ctx, meta(subOp), input).get(10, TimeUnit.SECONDS);
    }

    private void store(String content, String sessionId) throws Exception {
        invoke("store", Maps.of(
            Strings.create("content"), Strings.create(content),
            Strings.create("sessionId"), Strings.create(sessionId),
            Strings.create("modelVersion"), Strings.create("anthropic/claude-sonnet-5"),
            Strings.create("usage"), Maps.of(
                Strings.create("input"), CVMLong.create(1000),
                Strings.create("output"), CVMLong.create(500))));
    }

    private String exportBundleJson(String sessionId) throws Exception {
        ACell bundle = invoke("export", Maps.of(Strings.create("sessionId"), Strings.create(sessionId)));
        return JSON.printPretty(bundle).toString();
    }

    @Test
    void exportedBundleVerifiesOffline() throws Exception {
        store("first", "exp-1");
        store("second", "exp-1");
        store("third", "exp-1");

        BundleResult result = OfflineVerifier.verifyBundle(exportBundleJson("exp-1"));
        for (CheckResult c : result.checks()) {
            assertTrue(c.ok(), c.name() + " should pass: " + c.detail());
        }
        assertTrue(result.ok(), "untampered bundle verifies");
    }

    @Test
    void mixedLegacyV2AndUnpricedRecordsVerifyOffline() throws Exception {
        var legacy=ai.surstor.Pricing.loadResource(ai.surstor.Pricing.LEGACY_SEED_RESOURCE)
            .assoc(Strings.create("_owner"),Strings.create("legacy-test"));
        invoke("prices/put",Maps.of(Strings.create("schedule"),legacy));
        store("old billing","mixed");
        invoke("prices/put",Maps.of(Strings.create("schedule"),ai.surstor.Pricing.loadSeedSchedule()));
        store("new billing","mixed");
        invoke("store",Maps.of(Strings.create("content"),Strings.create("no usage"),Strings.create("sessionId"),Strings.create("mixed")));
        String bundle=exportBundleJson("mixed");
        assertTrue(OfflineVerifier.verifyBundle(bundle).ok());
        // Alter source metadata without updating its cited hash. Even unchanged numeric rates must fail.
        String tampered=bundle.replace("legacy-test","tampered-source");
        assertFalse(OfflineVerifier.verifyBundle(tampered).ok());
    }

    @Test
    void tamperedContentFailsOffline() throws Exception {
        store("honest content", "exp-2");
        String json = exportBundleJson("exp-2");

        // Byte-flip the content inside the exported bundle
        String tampered = json.replace("honest content", "dishonest conten");
        assertNotEquals(json, tampered, "replacement applied");

        BundleResult result = OfflineVerifier.verifyBundle(tampered);
        assertFalse(result.ok(), "tampered bundle must fail");
        assertTrue(result.checks().stream().anyMatch(c -> !c.ok() && c.name().contains("branchRef")),
            "branch-ref check catches the flip: " + result.checks());
    }

    @Test
    void droppedEventFailsOffline() throws Exception {
        store("event 1", "exp-3");
        store("event 2", "exp-3");
        store("event 3", "exp-3");
        ACell bundle = invoke("export", Maps.of(Strings.create("sessionId"), Strings.create("exp-3")));

        // Drop the middle artifact from the bundle
        AMap<AString, ACell> bundleMap = RT.ensureMap(bundle);
        AVector<ACell> artifacts = RT.ensureVector(bundleMap.get(Strings.create("artifacts")));
        assertEquals(3, artifacts.count());
        AVector<ACell> reduced = Vectors.of(artifacts.get(0), artifacts.get(2));
        AMap<AString, ACell> broken = bundleMap.assoc(Strings.create("artifacts"), reduced);

        BundleResult result = OfflineVerifier.verifyBundle(JSON.printPretty(broken).toString());
        assertFalse(result.ok(), "bundle with a dropped event must fail");
        assertTrue(result.checks().stream().anyMatch(c -> !c.ok() && c.name().equals("chain")),
            "chain check catches the gap: " + result.checks());
    }

    @Test
    void garbageDoesNotVerify() {
        assertFalse(OfflineVerifier.verifyBundle("not json at all {").ok());
        assertFalse(OfflineVerifier.verifyBundle("{\"format\":\"surstor-export/1\"}").ok());
    }
}
