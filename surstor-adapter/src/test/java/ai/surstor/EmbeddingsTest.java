package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Embedding model semantics: determinism, meaningful similarity (including
 * cross-lingual — the reason the multilingual model is bundled), and chunk
 * windowing. Loads the real bundled model once for all tests (~1–2 s).
 */
class EmbeddingsTest {

    private static Embeddings embeddings;

    @BeforeAll
    static void loadModel() {
        embeddings = Embeddings.loadBundled();
    }

    @AfterAll
    static void unloadModel() {
        embeddings.close();
    }

    private static double cosine(float[] a, float[] b) {
        // Vectors are L2-normalized, so cosine is the dot product
        assertEquals(a.length, b.length);
        double dot = 0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot;
    }

    @Test
    void embedIsDeterministic() {
        float[] a = embeddings.embed("the client moved the deadline");
        float[] b = embeddings.embed("the client moved the deadline");
        assertEquals(Embeddings.DIMENSIONS, a.length);
        assertEquals(Embeddings.DIMENSIONS, b.length);
        for (int i = 0; i < a.length; i++) {
            assertEquals(a[i], b[i], 1e-6, "dimension " + i + " differs between runs");
        }
    }

    @Test
    void semanticSimilarityIsMeaningful() {
        float[] query = embeddings.embed("the client moved the deadline");
        float[] related = embeddings.embed("deadline discussion with customer");
        float[] unrelated = embeddings.embed("recipe for banana bread");
        assertTrue(cosine(query, related) > cosine(query, unrelated),
            "related text must score higher than unrelated text");
    }

    @Test
    void crossLingualSimilarityIsMeaningful() {
        // The multilingual model must bridge languages: a German paraphrase of
        // the English query should score higher than an unrelated English text
        float[] query = embeddings.embed("the client moved the deadline");
        float[] german = embeddings.embed("der Kunde hat die Frist verschoben");
        float[] unrelated = embeddings.embed("recipe for banana bread");
        assertTrue(cosine(query, german) > cosine(query, unrelated),
            "German paraphrase must score higher than unrelated English text");
    }

    @Test
    void chunkingBoundaries() {
        // Short text: exactly one chunk, and it is the original string
        String shortText = "a short note";
        List<String> single = embeddings.chunk(shortText);
        assertEquals(1, single.size());
        assertEquals(shortText, single.get(0));

        // Long text: multiple chunks, each within the window
        String longText = "lorem ipsum dolor sit amet ".repeat(500);
        assertTrue(embeddings.tokenCount(longText) > Embeddings.CHUNK_TOKENS,
            "test text must exceed one window");
        List<String> chunks = embeddings.chunk(longText);
        assertTrue(chunks.size() >= 3, "expected >=3 chunks, got " + chunks.size());
        for (String chunk : chunks) {
            assertTrue(embeddings.tokenCount(chunk) <= Embeddings.CHUNK_TOKENS,
                "chunk exceeds window: " + embeddings.tokenCount(chunk) + " tokens");
        }
    }
}
