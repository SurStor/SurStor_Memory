package ai.surstor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherFactory;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

/**
 * On-disk vector index over stored artifacts: Lucene HNSW
 * ({@link KnnFloatVectorField}, cosine similarity), one Lucene document per
 * artifact chunk.
 *
 * <p>This index is DERIVED, UNSIGNED, DISPOSABLE data. The two-cell CAD3
 * record in the etch store is the only source of truth; deleting this index
 * and running {@code surstor:reindex} rebuilds it with zero information
 * loss. Vectors are never written into signed cells.</p>
 */
public final class SearchIndex implements AutoCloseable {

    static final String F_ARTIFACT = "artifactId";
    static final String F_CHUNK = "chunkOrdinal";
    static final String F_VECTOR = "vector";
    static final String F_TEXT = "chunkText";
    static final String F_SESSION = "sessionId";
    static final String F_TS = "timestamp";

    private static final int SNIPPET_MAX_CHARS = 200;

    private final IndexWriter writer;
    private final SearcherManager searcherManager;
    /** Serialises writer/refresh cycles; reindex clear+rebuild locks on this. */
    private final Object writeLock = new Object();

    private SearchIndex(IndexWriter writer, SearcherManager searcherManager) {
        this.writer = writer;
        this.searcherManager = searcherManager;
    }

    /**
     * Opens (creating if absent) an index at the given directory.
     *
     * @param path index directory
     * @return open index
     * @throws IOException if the directory cannot be opened
     */
    public static SearchIndex open(Path path) throws IOException {
        Directory dir = FSDirectory.open(path);
        IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
        IndexWriter writer = new IndexWriter(dir, config);
        SearcherManager sm = new SearcherManager(writer, true, false, new SearcherFactory());
        return new SearchIndex(writer, sm);
    }

    /**
     * Indexes one chunk of one artifact. Commits immediately — durability
     * over throughput (a node crash must not silently drop index entries).
     *
     * @param artifactId Cell 2 hash hex of the owning artifact
     * @param chunkOrdinal 0-based chunk position
     * @param chunkText chunk text (stored only, for snippets)
     * @param vector L2-normalized embedding
     * @param sessionId owning session
     * @param timestamp capture time (epoch millis)
     * @throws IOException on index write failure
     */
    public void addChunk(String artifactId, int chunkOrdinal,
                         String chunkText, float[] vector,
                         String sessionId, long timestamp) throws IOException {
        Document doc = new Document();
        doc.add(new StringField(F_ARTIFACT, artifactId, Field.Store.YES));
        doc.add(new IntPoint(F_CHUNK, chunkOrdinal));
        doc.add(new StoredField(F_CHUNK, chunkOrdinal));
        doc.add(new KnnFloatVectorField(F_VECTOR, vector, VectorSimilarityFunction.COSINE));
        doc.add(new StoredField(F_TEXT, chunkText));
        doc.add(new StringField(F_SESSION, sessionId, Field.Store.YES));
        doc.add(new LongPoint(F_TS, timestamp));
        doc.add(new StoredField(F_TS, timestamp));

        synchronized (writeLock) {
            writer.addDocument(doc);
            writer.commit();
            searcherManager.maybeRefresh();
        }
    }

    /**
     * Top-k semantic search, optionally restricted to one session. Chunks of
     * the same artifact are deduplicated: one hit per artifact, best chunk
     * score wins.
     *
     * @param queryVector L2-normalized query embedding
     * @param k max results, clamped to [1, 100]
     * @param sessionId session filter, or null for all sessions
     * @return hits ordered by descending score
     * @throws IOException on search failure
     */
    public List<SearchHit> search(float[] queryVector, int k, String sessionId) throws IOException {
        k = Math.max(1, Math.min(100, k));
        Query filter = (sessionId != null) ? new TermQuery(new Term(F_SESSION, sessionId)) : null;
        // Over-fetch so per-artifact dedupe can still fill k hits
        int fetchK = Math.min(Math.max(k * 4, 20), 1000);
        KnnFloatVectorQuery query = new KnnFloatVectorQuery(F_VECTOR, queryVector, fetchK, filter);

        IndexSearcher searcher = searcherManager.acquire();
        try {
            TopDocs top = searcher.search(query, fetchK);
            // Score order is descending — the first occurrence of an artifact
            // is its best chunk.
            LinkedHashMap<String, SearchHit> byArtifact = new LinkedHashMap<>();
            for (ScoreDoc sd : top.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String artifactId = doc.get(F_ARTIFACT);
                byArtifact.putIfAbsent(artifactId, new SearchHit(
                    artifactId,
                    doc.getField(F_CHUNK).numericValue().intValue(),
                    sd.score,
                    snippet(doc.get(F_TEXT)),
                    doc.get(F_SESSION),
                    doc.getField(F_TS).numericValue().longValue()));
                if (byArtifact.size() >= k) break;
            }
            return new ArrayList<>(byArtifact.values());
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * Removes all chunks of an artifact.
     *
     * @param artifactId Cell 2 hash hex
     * @throws IOException on index write failure
     */
    public void removeArtifact(String artifactId) throws IOException {
        synchronized (writeLock) {
            writer.deleteDocuments(new Term(F_ARTIFACT, artifactId));
            writer.commit();
            searcherManager.maybeRefresh();
        }
    }

    /**
     * Deletes every document. Used by reindex before replaying the store.
     *
     * @throws IOException on index write failure
     */
    public void clear() throws IOException {
        synchronized (writeLock) {
            writer.deleteAll();
            writer.commit();
            searcherManager.maybeRefresh();
        }
    }

    /**
     * Number of chunk documents currently in the index.
     *
     * @return live document count
     * @throws IOException on reader failure
     */
    public long documentCount() throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            return searcher.getIndexReader().numDocs();
        } finally {
            searcherManager.release(searcher);
        }
    }

    /** Exposed for reindex: clear+replay must be atomic against searches. */
    Object writeLock() {
        return writeLock;
    }

    private static String snippet(String chunkText) {
        if (chunkText == null) return "";
        return (chunkText.length() <= SNIPPET_MAX_CHARS)
            ? chunkText
            : chunkText.substring(0, SNIPPET_MAX_CHARS);
    }

    @Override
    public void close() throws IOException {
        synchronized (writeLock) {
            try {
                searcherManager.close();
            } finally {
                writer.close();
            }
        }
    }

    /**
     * One search hit. Carries sessionId/timestamp beyond the minimal record
     * shape — the {@code surstor:search} output contract needs them, and the
     * Lucene doc already stores both.
     */
    public record SearchHit(String artifactId, int chunkOrdinal,
                            double score, String snippet,
                            String sessionId, long timestamp) {}
}
