package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.surstor.SearchIndex.SearchHit;

/**
 * Lucene HNSW index semantics: round-trip, persistence across reopen, k
 * clamping, session filtering, per-artifact chunk dedupe, clear. Uses
 * synthetic 384-dim unit vectors along distinct axes so "nearest" is
 * deterministic.
 */
class SearchIndexTest {

    @TempDir
    Path tempDir;

    private SearchIndex index;

    @BeforeEach
    void openIndex() throws Exception {
        index = SearchIndex.open(tempDir.resolve("search-index"));
    }

    @AfterEach
    void closeIndex() throws Exception {
        if (index != null) index.close();
        // Windows keeps memory-mapped index files locked until GC — without
        // this, @TempDir cleanup fails though all assertions pass
        System.gc();
    }

    private static float[] unit(int axis) {
        float[] v = new float[Embeddings.DIMENSIONS];
        v[axis] = 1f;
        return v;
    }

    /** Vector mostly along axisA with a smaller axisB component. */
    private static float[] mix(int axisA, int axisB, float weightB) {
        float[] v = new float[Embeddings.DIMENSIONS];
        v[axisA] = 1f;
        v[axisB] = weightB;
        return v;
    }

    @Test
    void addSearchRemoveRoundTrip() throws Exception {
        index.addChunk("aaa", 0, "first artifact", unit(0), "s1", 1000L);
        index.addChunk("bbb", 0, "second artifact", unit(1), "s1", 2000L);
        assertEquals(2, index.documentCount());

        List<SearchHit> hits = index.search(unit(0), 10, null);
        assertEquals(2, hits.size());
        assertEquals("aaa", hits.get(0).artifactId(), "exact axis match must rank first");
        assertEquals("first artifact", hits.get(0).snippet());
        assertEquals("s1", hits.get(0).sessionId());
        assertEquals(1000L, hits.get(0).timestamp());

        index.removeArtifact("aaa");
        hits = index.search(unit(0), 10, null);
        assertEquals(1, hits.size());
        assertEquals("bbb", hits.get(0).artifactId());
    }

    @Test
    void persistsAcrossReopen() throws Exception {
        index.addChunk("aaa", 0, "durable content", unit(2), "s1", 1000L);
        Path dir = tempDir.resolve("search-index");
        index.close();

        index = SearchIndex.open(dir);
        assertEquals(1, index.documentCount());
        List<SearchHit> hits = index.search(unit(2), 10, null);
        assertEquals(1, hits.size());
        assertEquals("aaa", hits.get(0).artifactId());
    }

    @Test
    void kIsClamped() throws Exception {
        for (int i = 0; i < 120; i++) {
            index.addChunk(String.format("art%03d", i), 0, "chunk " + i, unit(3), "s1", i);
        }
        assertEquals(1, index.search(unit(3), 0, null).size(), "k=0 clamps to 1");
        assertEquals(100, index.search(unit(3), 1000, null).size(), "k=1000 clamps to 100");
    }

    @Test
    void sessionFilterRestricts() throws Exception {
        index.addChunk("aaa", 0, "in session one", unit(4), "s1", 1000L);
        index.addChunk("bbb", 0, "in session two", unit(4), "s2", 2000L);

        List<SearchHit> hits = index.search(unit(4), 10, "s2");
        assertEquals(1, hits.size());
        assertEquals("bbb", hits.get(0).artifactId());

        assertEquals(2, index.search(unit(4), 10, null).size(), "null filter searches all sessions");
    }

    @Test
    void clearEmptiesIndex() throws Exception {
        index.addChunk("aaa", 0, "to be wiped", unit(5), "s1", 1000L);
        assertEquals(1, index.documentCount());

        index.clear();
        assertEquals(0, index.documentCount());
        assertTrue(index.search(unit(5), 10, null).isEmpty(), "empty index returns no hits, not an error");
    }

    @Test
    void dedupesChunksPerArtifact() throws Exception {
        // Two chunks of the same artifact, both close to the query; a second
        // artifact slightly less close. Top-10 must contain ONE hit per artifact.
        index.addChunk("aaa", 0, "deadline chunk one", unit(6), "s1", 1000L);
        index.addChunk("aaa", 1, "deadline chunk two", mix(6, 7, 0.01f), "s1", 1000L);
        index.addChunk("bbb", 0, "budget notes", mix(6, 7, 0.2f), "s1", 2000L);

        List<SearchHit> hits = index.search(unit(6), 10, null);
        assertEquals(2, hits.size(), "one hit per artifact");
        assertEquals("aaa", hits.get(0).artifactId());
        assertEquals(0, hits.get(0).chunkOrdinal(), "best chunk wins");
        assertEquals("bbb", hits.get(1).artifactId());
    }
}
