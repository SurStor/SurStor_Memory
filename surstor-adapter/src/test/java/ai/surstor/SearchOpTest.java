package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.AVector;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import covia.venue.Engine;
import covia.venue.RequestContext;

/**
 * Engine-backed semantic search: store hooks the indexer, search finds by
 * meaning, indexing failure never breaks a store, and reindex replays the
 * etch store after the (disposable) index is wiped.
 */
class SearchOpTest {

    @TempDir
    Path tempDir;

    private Engine engine;
    private SurStorAdapter adapter;
    private RequestContext ctx;

    private AMap<AString, ACell> engineConfig(Path indexDir) {
        return Maps.of(Strings.create("adapters"), Maps.of(Strings.create("surstor"), Maps.of(
            Strings.create("search-index-dir"), Strings.create(indexDir.toString()))));
    }

    @BeforeEach
    void setup() {
        engine = Engine.createTemp(engineConfig(tempDir.resolve("search-index")));
        adapter = new SurStorAdapter();
        engine.registerAdapter(adapter);
        ctx = RequestContext.of(Strings.create("did:key:z6MkSearchTest"));
    }

    @AfterEach
    void teardown() {
        engine.close();  // closes AutoCloseable adapters: index + model
        // Windows keeps memory-mapped index files locked until GC — without
        // this, @TempDir cleanup fails though all assertions pass
        System.gc();
    }

    private static AMap<AString, ACell> meta(String subOp) {
        return Maps.of(Strings.create("operation"),
            Maps.of(Strings.create("adapter"), Strings.create("surstor:" + subOp)));
    }

    private ACell invoke(String subOp, AMap<AString, ACell> input) throws Exception {
        return adapter.invokeFuture(ctx, meta(subOp), input).get(30, TimeUnit.SECONDS);
    }

    private String store(String content, String sessionId) throws Exception {
        ACell result = invoke("store", Maps.of(
            Strings.create("content"), Strings.create(content),
            Strings.create("sessionId"), Strings.create(sessionId),
            Strings.create("modelVersion"), Strings.create("anthropic/claude-sonnet-5"),
            Strings.create("usage"), Maps.of(
                Strings.create("input"), CVMLong.create(1000),
                Strings.create("output"), CVMLong.create(500))));
        return RT.ensureString(RT.ensureMap(result).get(covia.api.Fields.ID)).toString();
    }

    private AMap<AString, ACell> search(String query) throws Exception {
        return RT.ensureMap(invoke("search", Maps.of(Strings.create("query"), Strings.create(query))));
    }

    private static String top1Id(AMap<AString, ACell> searchResult) {
        AVector<ACell> results = RT.ensureVector(searchResult.get(Strings.create("results")));
        if (results.count() == 0) return null;
        return RT.ensureString(RT.ensureMap(results.get(0)).get(covia.api.Fields.ID)).toString();
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void storeThenSearchFindsByMeaning() throws Exception {
        String deadline = store(
            "Email from the client: they moved the project deadline to next Friday.", "demo");
        store("Banana bread recipe: flour, bananas, sugar, eggs, bake 50 minutes.", "demo");
        store("Meeting notes: discussed Q3 budget, hiring plan, and office rent.", "demo");

        AMap<AString, ACell> result = search("when is the project due");
        assertEquals(deadline, top1Id(result), "paraphrased query must rank the deadline email top-1");
        assertNotNull(RT.ensureMap(result).get(Strings.create("model")), "result names the embedding model");
    }

    @Test
    void indexFailureDoesNotBreakStore() throws Exception {
        // Point the index at a FILE so SearchIndex.open always fails
        Path notADir = tempDir.resolve("not-a-dir");
        Files.writeString(notADir, "occupied");
        Engine brokenEngine = Engine.createTemp(engineConfig(notADir));
        try {
            SurStorAdapter brokenAdapter = new SurStorAdapter();
            brokenEngine.registerAdapter(brokenAdapter);

            // Store still succeeds — indexing failure is logged, not propagated
            ACell result = brokenAdapter.invokeFuture(ctx, meta("store"), Maps.of(
                Strings.create("content"), Strings.create("stored despite broken index"),
                Strings.create("sessionId"), Strings.create("demo")))
                .get(30, TimeUnit.SECONDS);
            assertNotNull(RT.ensureMap(result).get(covia.api.Fields.ID), "store succeeds with broken index");

            // ...but search itself reports unavailability
            ExecutionException e = assertThrows(ExecutionException.class, () ->
                brokenAdapter.invokeFuture(ctx, meta("search"),
                    Maps.of(Strings.create("query"), Strings.create("anything")))
                    .get(30, TimeUnit.SECONDS));
            assertInstanceOf(SurStorAdapter.SearchUnavailableException.class, e.getCause());
        } finally {
            brokenEngine.close();
        }
    }

    @Test
    void reindexRestoresResults() throws Exception {
        String deadline = store(
            "Email from the client: they moved the project deadline to next Friday.", "demo");
        store("Banana bread recipe: flour, bananas, sugar, eggs, bake 50 minutes.", "demo");
        store("Meeting notes: discussed Q3 budget, hiring plan, and office rent.", "demo");
        assertEquals(deadline, top1Id(search("when is the project due")));

        // Simulate index loss: close the adapter (releases file locks), wipe
        // the index directory, register a fresh adapter on the SAME engine —
        // the etch store (source of truth) is untouched.
        adapter.close();
        deleteRecursively(tempDir.resolve("search-index"));
        adapter = new SurStorAdapter();
        engine.registerAdapter(adapter);

        assertNull(top1Id(search("when is the project due")), "wiped index returns empty results");

        AMap<AString, ACell> reindexed = RT.ensureMap(invoke("reindex", Maps.empty()));
        assertEquals(3L, ((CVMLong) reindexed.get(Strings.create("reindexed"))).longValue());

        assertEquals(deadline, top1Id(search("when is the project due")),
            "reindex rebuilds the index from the store with identical results");
    }
}
