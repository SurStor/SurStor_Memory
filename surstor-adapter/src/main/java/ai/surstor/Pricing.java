package ai.surstor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.util.JSON;

/**
 * Price oracle for SurStor evidence records.
 *
 * <p>A price schedule is an asset: a map {@code model-string → {"input-per-1m",
 * "output-per-1m", "cached-input-per-1m"?}} plus {@code effective-date}. Costs
 * are computed at capture time and frozen into Cell 2 with the schedule's
 * value ID.</p>
 */
public final class Pricing {

    public static final String DEFAULT_SCHEDULE_ID = "builtin:2026-09-10";
    public static final String SEED_RESOURCE = "/pricing/seed-2026-09-10.json";
    public static final String LEGACY_SEED_RESOURCE = "/pricing/seed-2026-08.json";

    private Pricing() {}

    /**
     * Loads the built-in seed price schedule.
     *
     * @return price schedule map
     */
    @SuppressWarnings("unchecked")
    public static AMap<AString, ACell> loadSeedSchedule() {
        return loadResource(SEED_RESOURCE);
    }

    public static AMap<AString, ACell> loadResource(String resource) {
        try (InputStream is = Pricing.class.getResourceAsStream(resource)) {
            if (is == null) throw new IllegalStateException("Seed price schedule not found: " + SEED_RESOURCE);
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            ACell parsed = JSON.parse(json);
            if (!(parsed instanceof AMap)) throw new IllegalStateException("Seed schedule is not a JSON object");
            return (AMap<AString, ACell>) parsed;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load seed price schedule", e);
        }
    }

    /**
     * Returns the value ID (hash) of a price schedule.
     *
     * @param schedule price schedule map
     * @return schedule value ID
     */
    public static Hash scheduleId(AMap<AString, ACell> schedule) {
        return schedule.getHash();
    }

    /**
     * Serialises a price schedule to JSON for storage.
     *
     * @param schedule price schedule map
     * @return JSON string
     */
    public static AString toJson(AMap<AString, ACell> schedule) {
        return JSON.printPretty(schedule);
    }

    /**
     * Parses a price schedule from JSON.
     *
     * @param json JSON string
     * @return price schedule map
     */
    @SuppressWarnings("unchecked")
    public static AMap<AString, ACell> parse(String json) {
        ACell parsed = JSON.parse(json);
        if (!(parsed instanceof AMap)) throw new IllegalArgumentException("Price schedule is not a JSON object");
        return (AMap<AString, ACell>) parsed;
    }

    /**
     * Validates that a schedule has the required shape.
     *
     * @param schedule price schedule map
     * @return true if valid
     */
    public static boolean isValid(AMap<AString, ACell> schedule) {
        if (schedule == null) return false;
        if (schedule.containsKey(Strings.create("_schema-version"))) return Billing.validSchedule(schedule);
        int models = 0;
        // Must contain at least one model entry with input/output rates.
        // Keys starting with "_" (e.g. _comment, _effective-date) are metadata.
        for (ACell k : schedule.keySet()) {
            if (!(k instanceof AString)) return false;
            if (k.toString().startsWith("_")) continue;
            models++;
            ACell v = schedule.get(k);
            if (!(v instanceof AMap)) return false;
            AMap<?, ?> rates = (AMap<?, ?>) v;
            if (rates.get(Costs.K_INPUT_PER_1M) == null && rates.get(Costs.K_OUTPUT_PER_1M) == null) {
                return false;
            }
            try {
                for (AString field : new AString[] {Costs.K_INPUT_PER_1M, Costs.K_OUTPUT_PER_1M, Costs.K_CACHED_INPUT_PER_1M}) {
                    if (rates.get(field) != null) Billing.decimal(rates.get(field),field.toString());
                }
            } catch (RuntimeException e) { return false; }
        }
        return models > 0;
    }

}
