package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;
import covia.venue.server.VenueServer;

/**
 * Restart persistence: an artifact stored by a node must survive a full
 * process restart — same store path, same identity seed — and still verify.
 *
 * <p>Launches real {@link VenueServer} instances (the layer that owns etch
 * store wiring), closing and relaunching against the same on-disk store.</p>
 */
class RestartPersistenceTest {

    @TempDir
    Path tempDir;

    private static AMap<AString, ACell> venueConfig(Path storePath, AKeyPair keyPair, int port) {
        return Maps.of(
            Strings.create("name"), Strings.create("SurStor Restart Test"),
            Strings.create("store"), Strings.create(storePath.toString()),
            Strings.create("seed"), Strings.create(keyPair.getSeed().toHexString()),
            Strings.create("port"), CVMLong.create(port));
    }

    private static AMap<AString, ACell> meta(String subOp) {
        return Maps.of(Strings.create("operation"),
            Maps.of(Strings.create("adapter"), Strings.create("surstor:" + subOp)));
    }

    @Test
    void artifactSurvivesRestart() throws Exception {
        AKeyPair keyPair = AKeyPair.generate();
        Path storePath = tempDir.resolve("venue.etch");
        // Public caller DID is derived from the venue DID, which is derived from the seed
        String userDID = "did:key:" + keyPair.getAccountKey().toString() + ":public";

        String artifactId;

        // First boot: store an artifact
        VenueServer server1 = VenueServer.launch(venueConfig(storePath, keyPair, 18484));
        try {
            Engine engine1 = server1.getEngine();
            SurStorAdapter adapter1 = new SurStorAdapter();
            engine1.registerAdapter(adapter1);
            RequestContext ctx = RequestContext.of(Strings.create(userDID));

            ACell result = adapter1.invokeFuture(ctx, meta("store"), Maps.of(
                Strings.create("content"), Strings.create("must survive restart"),
                Strings.create("sessionId"), Strings.create("restart-s"),
                Strings.create("modelVersion"), Strings.create("anthropic/claude-sonnet-5"),
                Strings.create("usage"), Maps.of(
                    Costs.K_INPUT, CVMLong.create(100),
                    Costs.K_OUTPUT, CVMLong.create(50))))
                .get(10, TimeUnit.SECONDS);
            artifactId = RT.ensureString(RT.ensureMap(result).get(covia.api.Fields.ID)).toString();
        } finally {
            server1.close();
            // Windows keeps etch's memory-mapped venue.etch locked until GC —
            // without this, @TempDir cleanup fails though all assertions pass
            System.gc();
        }

        // Second boot: same store, same identity — artifact resolves and verifies
        VenueServer server2 = VenueServer.launch(venueConfig(storePath, keyPair, 18485));
        try {
            Engine engine2 = server2.getEngine();
            SurStorAdapter adapter2 = new SurStorAdapter();
            engine2.registerAdapter(adapter2);
            RequestContext ctx = RequestContext.of(Strings.create(userDID));

            ACell verified = adapter2.invokeFuture(ctx, meta("verify"), Maps.of(
                covia.api.Fields.ID, Strings.create(artifactId),
                Strings.create("deep"), CVMBool.TRUE))
                .get(10, TimeUnit.SECONDS);
            assertTrue(RT.bool(RT.ensureMap(verified).get(Evidence.K_VALID)),
                "artifact verifies after restart: " + verified);

            ACell retrieved = adapter2.invokeFuture(ctx, meta("retrieve"), Maps.of(
                covia.api.Fields.ID, Strings.create(artifactId)))
                .get(10, TimeUnit.SECONDS);
            assertEquals("must survive restart",
                RT.ensureString(RT.ensureMap(retrieved).get(covia.api.Fields.CONTENT)).toString());
        } finally {
            server2.close();
            System.gc(); // release mapped venue.etch before @TempDir cleanup (Windows)
        }
    }
}
