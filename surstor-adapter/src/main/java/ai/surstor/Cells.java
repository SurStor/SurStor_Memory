package ai.surstor;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Blob;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.util.JSON;

/**
 * Two-cell CAD3 construction for SurStor evidence records.
 *
 * <p>Cell 1 is the content cell; Cell 2 is the provenance metadata cell whose
 * hash is the artifact's public ID. The {@code branch-ref} in Cell 2 binds
 * to Cell 1's value ID, giving the Merkle binding.</p>
 *
 * <p><b>Canonical form is JSON-native:</b> string keys, string content types,
 * and hashes stored as {@code "0x..."}-prefixed hex strings. Cells never carry
 * Keywords or Hash objects, so a cell's in-memory value hash is identical to
 * the hash of its JSON round-trip — which is what the venue asset store uses
 * as the asset ID. Do not introduce typed values (Keyword, Hash, Blob keys);
 * they silently change the hash across storage.</p>
 */
public final class Cells {

    public static final AString K_CONTENT = Strings.create("content");
    public static final AString K_CONTENT_TYPE = Strings.create("content-type");
    public static final AString K_BRANCH_REF = Strings.create("branch-ref");
    public static final AString K_SESSION_ID = Strings.create("session-id");
    public static final AString K_AGENT_ID = Strings.create("agent-id");
    public static final AString K_MODEL_VERSION = Strings.create("model-version");
    public static final AString K_TIMESTAMP = Strings.create("timestamp");
    public static final AString K_TOKEN_COUNT = Strings.create("token-count");
    public static final AString K_COST_USD = Strings.create("cost-usd");
    public static final AString K_PRICE_SCHEDULE_ID = Strings.create("price-schedule-id");
    public static final AString K_PREV_EVENT = Strings.create("prev-event");
    public static final AString K_SIGNATURE = Strings.create("signature");
    public static final AString K_SIGNER = Strings.create("signer");

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_BLOB = "blob";
    public static final String TYPE_TOOL_RESULT = "tool-result";
    public static final String TYPE_PATCH = "patch";
    public static final String TYPE_MODEL_OUTPUT = "model-output";

    private Cells() {}

    /**
     * Builds a Cell 1 content cell.
     *
     * @param content String or Blob content
     * @param contentType canonical content type string (see {@link #canonicalContentType})
     * @return CAD3 map for Cell 1
     */
    public static AMap<AString, ACell> buildCell1(ACell content, String contentType) {
        if (content == null) throw new IllegalArgumentException("content is required");
        if ("conversation".equals(contentType)) {
            if (!(content instanceof AMap<?, ?> conversation) || conversation.count() != 2
                || !(conversation.get(Strings.create("prompt")) instanceof AString)
                || !(conversation.get(Strings.create("response")) instanceof AString)) {
                throw new IllegalArgumentException("conversation content requires prompt and response strings only");
            }
        } else if (!(content instanceof AString) && !(content instanceof Blob)) {
            throw new IllegalArgumentException("content must be a String or Blob, got: " + content.getClass().getSimpleName());
        }
        if (contentType == null) throw new IllegalArgumentException("contentType is required");
        return Maps.of(K_CONTENT, content, K_CONTENT_TYPE, Strings.create(contentType));
    }

    /**
     * Builds a Cell 2 metadata cell. Hash-valued fields are stored as
     * {@code "0x..."}-prefixed hex strings to keep the cell JSON-native.
     *
     * @param cell1 the Cell 1 map (its hash becomes the branch ref)
     * @param sessionId session identifier
     * @param agentId agent identifier
     * @param modelVersion model version or null
     * @param timestamp epoch millis UTC
     * @param tokenCount token count record {"input" Long, "output" Long} or null
     * @param costUsd decimal string cost or null
     * @param priceScheduleId price schedule value ID or null
     * @param prevEvent previous event value ID or null
     * @return CAD3 map for Cell 2
     */
    public static AMap<AString, ACell> buildCell2(
            AMap<AString, ACell> cell1,
            String sessionId,
            String agentId,
            String modelVersion,
            long timestamp,
            AMap<AString, ACell> tokenCount,
            String costUsd,
            Hash priceScheduleId,
            Hash prevEvent) {
        if (cell1 == null) throw new IllegalArgumentException("cell1 is required");
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId is required");
        if (agentId == null || agentId.isBlank()) throw new IllegalArgumentException("agentId is required");

        AMap<AString, ACell> cell2 = Maps.of(
            K_BRANCH_REF, hashString(cell1.getHash()),
            K_SESSION_ID, Strings.create(sessionId),
            K_AGENT_ID, Strings.create(agentId),
            K_TIMESTAMP, CVMLong.create(timestamp));

        if (modelVersion != null) cell2 = cell2.assoc(K_MODEL_VERSION, Strings.create(modelVersion));
        if (tokenCount != null) cell2 = cell2.assoc(K_TOKEN_COUNT, tokenCount);
        if (costUsd != null) cell2 = cell2.assoc(K_COST_USD, Strings.create(costUsd));
        if (priceScheduleId != null) cell2 = cell2.assoc(K_PRICE_SCHEDULE_ID, hashString(priceScheduleId));
        if (prevEvent != null) cell2 = cell2.assoc(K_PREV_EVENT, hashString(prevEvent));

        return cell2;
    }

    /**
     * Parses a Cell 1 from its JSON representation.
     *
     * @param json JSON string
     * @return parsed Cell 1 map
     */
    @SuppressWarnings("unchecked")
    public static AMap<AString, ACell> parseCell1(String json) {
        ACell parsed = JSON.parse(json);
        if (!(parsed instanceof AMap)) throw new IllegalArgumentException("Cell 1 is not a JSON object");
        return (AMap<AString, ACell>) parsed;
    }

    /**
     * Parses a Cell 2 from its JSON representation.
     *
     * @param json JSON string
     * @return parsed Cell 2 map
     */
    @SuppressWarnings("unchecked")
    public static AMap<AString, ACell> parseCell2(String json) {
        ACell parsed = JSON.parse(json);
        if (!(parsed instanceof AMap)) throw new IllegalArgumentException("Cell 2 is not a JSON object");
        return (AMap<AString, ACell>) parsed;
    }

    /**
     * Extracts the branch-ref (Cell 1 hash) from a Cell 2.
     *
     * @param cell2 metadata cell
     * @return Cell 1 hash, or null if missing
     */
    public static Hash branchRef(AMap<AString, ACell> cell2) {
        return parseHash(cell2.get(K_BRANCH_REF));
    }

    /**
     * Extracts the session ID from a Cell 2.
     *
     * @param cell2 metadata cell
     * @return session ID string, or null if missing
     */
    public static String sessionId(AMap<AString, ACell> cell2) {
        ACell v = cell2.get(K_SESSION_ID);
        return (v instanceof AString) ? v.toString() : null;
    }

    /**
     * Extracts the prev-event hash from a Cell 2.
     *
     * @param cell2 metadata cell
     * @return previous event hash, or null if missing
     */
    public static Hash prevEvent(AMap<AString, ACell> cell2) {
        return parseHash(cell2.get(K_PREV_EVENT));
    }

    /**
     * Extracts the cost-usd decimal string from a Cell 2.
     *
     * @param cell2 metadata cell
     * @return cost string, or null if missing
     */
    public static String costUsd(AMap<AString, ACell> cell2) {
        ACell v = cell2.get(K_COST_USD);
        return (v instanceof AString) ? v.toString() : null;
    }

    /**
     * Extracts the price-schedule-id from a Cell 2.
     *
     * @param cell2 metadata cell
     * @return schedule hash, or null if missing
     */
    public static Hash priceScheduleId(AMap<AString, ACell> cell2) {
        return parseHash(cell2.get(K_PRICE_SCHEDULE_ID));
    }

    /**
     * Extracts the token-count record from a Cell 2.
     *
     * @param cell2 metadata cell
     * @return token count map, or null if missing
     */
    @SuppressWarnings("unchecked")
    public static AMap<AString, ACell> tokenCount(AMap<AString, ACell> cell2) {
        ACell v = cell2.get(K_TOKEN_COUNT);
        return (v instanceof AMap) ? (AMap<AString, ACell>) v : null;
    }

    /**
     * Validates a content-type string and returns its canonical form.
     *
     * @param type string (e.g. "text", "blob")
     * @return canonical content-type string
     */
    public static String canonicalContentType(String type) {
        if (type == null) return null;
        return switch (type) {
            case TYPE_TEXT, TYPE_BLOB, TYPE_TOOL_RESULT, TYPE_PATCH, TYPE_MODEL_OUTPUT, "conversation" -> type;
            default -> throw new IllegalArgumentException("Unknown content-type: " + type);
        };
    }

    /**
     * Serialises a cell to its canonical JSON form for storage.
     *
     * @param cell CAD3 map
     * @return JSON string
     */
    public static AString toJson(AMap<AString, ACell> cell) {
        return JSON.printPretty(cell);
    }

    /**
     * Returns the public artifact ID (Cell 2 hash) as a full hex string.
     * Because cells are JSON-native, this equals the venue asset store ID.
     *
     * @param cell2 metadata cell
     * @return full value ID hex
     */
    public static String artifactId(AMap<AString, ACell> cell2) {
        return cell2.getHash().toHexString();
    }

    /**
     * Returns the short artifact ID: {@code ss_<first 12 hex>}.
     *
     * @param cell2 metadata cell
     * @return short ID
     */
    public static String shortId(AMap<AString, ACell> cell2) {
        String hex = artifactId(cell2);
        return "ss_" + hex.substring(0, Math.min(12, hex.length()));
    }

    /**
     * Verifies that a Cell 2's branch-ref matches the hash of the given Cell 1.
     *
     * @param cell2 metadata cell
     * @param cell1 content cell
     * @return true if the branch-ref matches
     */
    public static boolean verifyBranchRef(AMap<AString, ACell> cell2, AMap<AString, ACell> cell1) {
        Hash ref = branchRef(cell2);
        return ref != null && ref.equals(cell1.getHash());
    }

    /** Encodes a hash as its canonical {@code "0x..."}-prefixed hex string. */
    public static AString hashString(Hash h) {
        return Strings.create("0x" + h.toHexString());
    }

    /**
     * Returns the Cell 2 map with signature fields removed — the exact value
     * the node signs at capture time. The signature covers the hash of this
     * unsigned form, so a signed cell can carry its own proof.
     */
    public static AMap<AString, ACell> unsignedCell2(AMap<AString, ACell> cell2) {
        return cell2.dissoc(K_SIGNATURE).dissoc(K_SIGNER);
    }

    /** Parses a hash from a cell field: accepts a Hash, or a hex string with optional {@code 0x} prefix. */
    public static Hash parseHash(ACell v) {
        if (v instanceof Hash h) return h;
        if (v instanceof AString s) {
            String hex = s.toString();
            if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2);
            if (hex.length() != 64) return null;
            return Hash.fromHex(hex);
        }
        return null;
    }
}
