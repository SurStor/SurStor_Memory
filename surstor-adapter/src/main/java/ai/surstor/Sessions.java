package ai.surstor;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * Session hash-chain management for SurStor evidence records.
 *
 * <p>Every store appends an event whose {@code prev-event} is the previous
 * event's value ID. The chain head is recorded in the session's lattice
 * entry, giving append-only, tamper-evident session history.</p>
 *
 * <p>Session records are JSON-native (string keys; the head hash stored as a
 * {@code "0x..."}-prefixed hex string) so they survive serialisation
 * unchanged, matching the cell canonical form in {@link Cells}.</p>
 */
public final class Sessions {

    public static final AString K_HEAD = Strings.create("head");
    public static final AString K_COUNT = Strings.create("count");
    public static final AString K_CREATED = Strings.create("created");
    public static final AString K_UPDATED = Strings.create("updated");
    public static final AString K_TOTAL_COST_USD = Strings.create("total-cost-usd");
    public static final AString K_ARTIFACT_COUNT = Strings.create("artifact-count");
    public static final AString K_UNPRICED_COUNT = Strings.create("unpriced-count");

    private Sessions() {}

    /**
     * Builds a new empty session record.
     *
     * @param sessionId session identifier
     * @param timestamp epoch millis UTC
     * @return session record map
     */
    public static AMap<AString, ACell> createSession(String sessionId, long timestamp) {
        return Maps.of(
            K_HEAD, null,
            K_COUNT, CVMLong.create(0),
            K_CREATED, CVMLong.create(timestamp),
            K_UPDATED, CVMLong.create(timestamp),
            K_TOTAL_COST_USD, Strings.create("0.000000"),
            K_UNPRICED_COUNT, CVMLong.create(0),
            K_ARTIFACT_COUNT, CVMLong.create(0));
    }

    /**
     * Advances a session record with a new event.
     *
     * @param session existing session record
     * @param newEventHash hash of the newly stored Cell 2
     * @param costUsd cost of the new event (decimal string) or null
     * @param timestamp epoch millis UTC
     * @return updated session record
     */
    public static AMap<AString, ACell> advanceSession(
            AMap<AString, ACell> session,
            Hash newEventHash,
            String costUsd,
            long timestamp) {
        long count = getLong(session, K_COUNT, 0);
        long artifactCount = getLong(session, K_ARTIFACT_COUNT, 0);
        String totalCost = getString(session, K_TOTAL_COST_USD, "0.000000");

        String newTotal = Costs.add(totalCost, costUsd);

        AMap<AString, ACell> updated = session
            .assoc(K_HEAD, Cells.hashString(newEventHash))
            .assoc(K_COUNT, CVMLong.create(count + 1))
            .assoc(K_UPDATED, CVMLong.create(timestamp))
            .assoc(K_ARTIFACT_COUNT, CVMLong.create(artifactCount + 1))
            .assoc(K_TOTAL_COST_USD, Strings.create(newTotal));
        if (session.containsKey(K_UNPRICED_COUNT)) updated = updated.assoc(K_UNPRICED_COUNT,
            CVMLong.create(getLong(session,K_UNPRICED_COUNT,0) + (costUsd == null ? 1 : 0)));

        return updated;
    }

    /**
     * Returns the current chain head of a session.
     *
     * @param session session record
     * @return head hash, or null if empty
     */
    public static Hash head(AMap<AString, ACell> session) {
        return Cells.parseHash(session.get(K_HEAD));
    }

    /**
     * Returns the event count of a session.
     *
     * @param session session record
     * @return event count
     */
    public static long count(AMap<AString, ACell> session) {
        return getLong(session, K_COUNT, 0);
    }

    /**
     * Returns the artifact count of a session.
     *
     * @param session session record
     * @return artifact count
     */
    public static long artifactCount(AMap<AString, ACell> session) {
        return getLong(session, K_ARTIFACT_COUNT, 0);
    }

    /**
     * Returns the total cost of a session.
     *
     * @param session session record
     * @return total cost decimal string
     */
    public static String totalCost(AMap<AString, ACell> session) {
        return getString(session, K_TOTAL_COST_USD, "0.000000");
    }

    /** Null means a legacy session whose historical pricing coverage was not tracked. */
    public static ACell unpricedCount(AMap<AString, ACell> session) {
        return session.get(K_UNPRICED_COUNT);
    }

    private static long getLong(AMap<AString, ACell> m, AString k, long defaultValue) {
        ACell v = m.get(k);
        return (v instanceof CVMLong) ? ((CVMLong) v).longValue() : defaultValue;
    }

    private static String getString(AMap<AString, ACell> m, AString k, String defaultValue) {
        ACell v = m.get(k);
        return (v instanceof AString) ? v.toString() : defaultValue;
    }
}
