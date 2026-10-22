package ai.surstor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.translator.TextEmbeddingTranslator;
import ai.djl.inference.Predictor;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;

/**
 * On-node text embeddings: paraphrase-multilingual-MiniLM-L12-v2 (quantized
 * ONNX, 384-dim) running on CPU via DJL + ONNX Runtime, with the HuggingFace
 * tokenizers native library.
 *
 * <p>The model and tokenizer are bundled as classpath resources — no download
 * at runtime, no network access, no content leaves the machine. Embeddings
 * are derived, unsigned, disposable data: they never enter signed cells, and
 * the vector index built from them can be rebuilt by {@code surstor_reindex}
 * at any time.</p>
 *
 * <p>Model choice: the multilingual L12 variant covers 50+ languages
 * (including cross-lingual queries) at ~113 MiB quantized. The English-only
 * all-MiniLM-L6-v2 (~23 MiB) was rejected — EU nodes store mixed-language
 * content.</p>
 */
public final class Embeddings implements AutoCloseable {

    public static final String MODEL_ID = "paraphrase-multilingual-minilm-l12-v2-quantized";
    public static final int DIMENSIONS = 384;

    /** Chunk window in tokens. Well under the model's 512 position limit. */
    static final int CHUNK_TOKENS = 256;
    /** Overlap between consecutive chunks, in tokens (HF "stride" convention). */
    static final int CHUNK_OVERLAP = 32;

    private static final String RESOURCE_BASE = "/models/paraphrase-multilingual-minilm-l12-v2/";

    private final HuggingFaceTokenizer tokenizer;
    private final ZooModel<String, float[]> model;
    private final Predictor<String, float[]> predictor;
    private final Path extractedDir;
    /** DJL Predictor is not thread-safe — all inference serialises on this. */
    private final Object predictLock = new Object();

    private Embeddings(HuggingFaceTokenizer tokenizer, ZooModel<String, float[]> model, Path extractedDir) {
        this.tokenizer = tokenizer;
        this.model = model;
        this.predictor = model.newPredictor();
        this.extractedDir = extractedDir;
    }

    /**
     * Loads the bundled model from classpath resources. The ONNX file and
     * tokenizer are extracted to a temp dir first — DJL/ONNX Runtime want
     * real files, and the resources may live inside the (shaded) module jar.
     * Takes ~1–2 s; callers should load lazily, not at node boot.
     *
     * @return loaded embeddings provider
     * @throws EmbeddingException if the model or tokenizer cannot be loaded
     */
    public static Embeddings loadBundled() throws EmbeddingException {
        // DJL discovers engines via ServiceLoader; pin the TCCL so discovery
        // sees exactly one DJL copy regardless of the caller's context loader
        // (covia's ModuleClassLoader is child-first and would otherwise split
        // DJL class identity between module jar and node fat jar).
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(Embeddings.class.getClassLoader());
        try {
            Path dir = Files.createTempDirectory("surstor-minilm-");
            Path modelPath = extractResource(RESOURCE_BASE + "model.onnx", dir.resolve("model.onnx"));
            Path tokenizerPath = extractResource(RESOURCE_BASE + "tokenizer.json", dir.resolve("tokenizer.json"));

            HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath,
                // The tokenizer.json carries max_length=128 from the model card;
                // truncating there would silently break chunking of long texts.
                // Inputs to the model are always pre-windowed (<= CHUNK_TOKENS
                // tokens) by chunk()/embed(), so no truncation is needed here.
                java.util.Map.of("truncation", "false"));
            TextEmbeddingTranslator translator = TextEmbeddingTranslator.builder(tokenizer)
                .optPoolingMode("mean")
                .optNormalize(true)
                .optIncludeTokenTypes(true)
                .build();

            Criteria<String, float[]> criteria = Criteria.builder()
                .setTypes(String.class, float[].class)
                .optModelPath(dir)
                .optModelName(modelPath.getFileName().toString())
                .optEngine("OnnxRuntime")
                .optTranslator(translator)
                .build();
            ZooModel<String, float[]> model = criteria.loadModel();
            return new Embeddings(tokenizer, model, dir);
        } catch (Exception e) {
            throw new EmbeddingException("Failed to load bundled embedding model " + MODEL_ID, e);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    /**
     * Embeds one text. Returns an L2-normalized 384-dim vector. Text longer
     * than one chunk window is clipped to the first window — embed() is for
     * queries and chunk-sized spans; use {@link #chunk(String)} for
     * documents.
     *
     * @param text input text
     * @return normalized embedding vector
     * @throws EmbeddingException if inference fails
     */
    public float[] embed(String text) throws EmbeddingException {
        String clipped = clipToWindow(text);
        try {
            synchronized (predictLock) {
                return predictor.predict(clipped);
            }
        } catch (Exception e) {
            throw new EmbeddingException("Embedding inference failed", e);
        }
    }

    /**
     * Splits text into overlapping chunks of at most {@link #CHUNK_TOKENS}
     * tokens (overlap {@link #CHUNK_OVERLAP}). Text that fits in one window
     * yields exactly one chunk — the original string, so short snippets stay
     * byte-identical.
     *
     * @param text input text
     * @return chunk list (empty for null/blank input)
     */
    public List<String> chunk(String text) {
        if (text == null || text.isBlank()) return List.of();
        // Content tokens only (no special tokens) — chunk windows are counted
        // in content tokens; the translator adds <s>/</s> at inference time.
        long[] ids = tokenizer.encode(text, false, false).getIds();
        int n = ids.length;
        if (n <= CHUNK_TOKENS) return List.of(text);

        List<String> chunks = new ArrayList<>();
        int step = CHUNK_TOKENS - CHUNK_OVERLAP;
        for (int start = 0; start < n; start += step) {
            int end = Math.min(n, start + CHUNK_TOKENS);
            chunks.add(tokenizer.decode(Arrays.copyOfRange(ids, start, end)));
            if (end == n) break;
        }
        return chunks;
    }

    /**
     * Model identifier for diagnostics.
     *
     * @return e.g. "paraphrase-multilingual-minilm-l12-v2-quantized"
     */
    public String modelId() {
        return MODEL_ID;
    }

    /** Token count (content tokens, no specials) under the bundled tokenizer; exposed for tests. */
    int tokenCount(String text) {
        return tokenizer.encode(text, false, false).getIds().length;
    }

    private String clipToWindow(String text) {
        long[] ids = tokenizer.encode(text, false, false).getIds();
        if (ids.length <= CHUNK_TOKENS) return text;
        return tokenizer.decode(Arrays.copyOfRange(ids, 0, CHUNK_TOKENS));
    }

    private static Path extractResource(String resource, Path target) throws IOException {
        try (InputStream is = Embeddings.class.getResourceAsStream(resource)) {
            if (is == null) throw new IOException("Bundled resource not found: " + resource);
            Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
        }
        target.toFile().deleteOnExit();
        return target;
    }

    @Override
    public void close() {
        try {
            predictor.close();
        } finally {
            try {
                model.close();
            } finally {
                tokenizer.close();
            }
        }
        // Best-effort cleanup of the extracted model files. On Windows the
        // ONNX file may stay locked by the native runtime until GC/JVM exit —
        // deleteOnExit above is the backstop.
        if (extractedDir != null) {
            try {
                Files.deleteIfExists(extractedDir.resolve("model.onnx"));
                Files.deleteIfExists(extractedDir.resolve("tokenizer.json"));
                Files.deleteIfExists(extractedDir);
            } catch (IOException ignored) {
                // leave it for deleteOnExit
            }
        }
    }

    /** Failure to load the model or run inference. */
    public static class EmbeddingException extends RuntimeException {
        public EmbeddingException(String message) { super(message); }
        public EmbeddingException(String message, Throwable cause) { super(message, cause); }
    }
}
