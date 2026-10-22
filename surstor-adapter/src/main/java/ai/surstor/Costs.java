package ai.surstor;

import java.math.BigDecimal;
import java.math.RoundingMode;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;

/**
 * Cost computation and session rollups for SurStor evidence records.
 *
 * <p>Costs are computed at capture time against a versioned price schedule and
 * frozen into Cell 2. Historical artifacts never recompute.</p>
 */
public final class Costs {

    public static final AString K_INPUT = Strings.create("input");
    public static final AString K_OUTPUT = Strings.create("output");
    public static final AString K_CACHED_INPUT = Strings.create("cached-input");

    public static final AString K_INPUT_PER_1M = Strings.create("input-per-1m");
    public static final AString K_OUTPUT_PER_1M = Strings.create("output-per-1m");
    public static final AString K_CACHED_INPUT_PER_1M = Strings.create("cached-input-per-1m");

    private static final BigDecimal ONE_MILLION = new BigDecimal("1000000");
    private static final int SCALE = 6;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private Costs() {}

    /**
     * Builds a token-count record.
     *
     * @param input input tokens
     * @param output output tokens
     * @param cachedInput cached input tokens or null
     * @return token count map
     */
    public static AMap<AString, ACell> tokenCount(long input, long output, Long cachedInput) {
        AMap<AString, ACell> m = Maps.of(K_INPUT, CVMLong.create(input), K_OUTPUT, CVMLong.create(output));
        if (cachedInput != null) m = m.assoc(K_CACHED_INPUT, CVMLong.create(cachedInput));
        return m;
    }

    /**
     * Computes the USD cost for a usage event against a price schedule.
     *
     * @param model model identifier (e.g. "anthropic/claude-sonnet-5")
     * @param usage token count record {"input" Long, "output" Long, "cached-input" Long?}
     * @param schedule price schedule map model-string → rates
     * @return decimal string cost (6dp), or null if usage or schedule is missing
     */
    public static String compute(String model, AMap<AString, ACell> usage, AMap<AString, ACell> schedule) {
        if (model == null || usage == null || schedule == null) return null;
        // This API is retained for v1 replay only. V2 requires a captured billing context.
        if (schedule.containsKey(Strings.create("_schema-version"))) return null;
        ACell ratesCell = schedule.get(Strings.create(model));
        if (!(ratesCell instanceof AMap)) return null;

        @SuppressWarnings("unchecked")
        AMap<AString, ACell> rates = (AMap<AString, ACell>) ratesCell;

        long input = getLong(usage, K_INPUT, 0);
        long output = getLong(usage, K_OUTPUT, 0);
        long cachedInput = getLong(usage, K_CACHED_INPUT, 0);

        BigDecimal inputRate = getRate(rates, K_INPUT_PER_1M);
        BigDecimal outputRate = getRate(rates, K_OUTPUT_PER_1M);
        BigDecimal cachedInputRate = getRate(rates, K_CACHED_INPUT_PER_1M);
        if (cachedInputRate == null) cachedInputRate = inputRate;

        if (inputRate == null && outputRate == null) return null;
        if (inputRate == null) inputRate = BigDecimal.ZERO;
        if (outputRate == null) outputRate = BigDecimal.ZERO;

        BigDecimal inputCost = new BigDecimal(input).multiply(inputRate).divide(ONE_MILLION, SCALE, ROUNDING);
        BigDecimal outputCost = new BigDecimal(output).multiply(outputRate).divide(ONE_MILLION, SCALE, ROUNDING);
        BigDecimal cachedInputCost = new BigDecimal(cachedInput).multiply(cachedInputRate).divide(ONE_MILLION, SCALE, ROUNDING);

        return inputCost.add(outputCost).add(cachedInputCost).setScale(SCALE, ROUNDING).toPlainString();
    }

    private static long getLong(AMap<AString, ACell> m, AString k, long defaultValue) {
        ACell v = m.get(k);
        return (v instanceof CVMLong) ? ((CVMLong) v).longValue() : defaultValue;
    }

    private static BigDecimal getRate(AMap<AString, ACell> rates, AString k) {
        ACell v = rates.get(k);
        if (v == null) return null;
        String s = RT.ensureString(v).toString();
        return new BigDecimal(s);
    }

    /**
     * Adds two decimal cost strings.
     *
     * @param a decimal string or null
     * @param b decimal string or null
     * @return sum as decimal string (6dp)
     */
    public static String add(String a, String b) {
        BigDecimal x = (a == null) ? BigDecimal.ZERO : new BigDecimal(a);
        BigDecimal y = (b == null) ? BigDecimal.ZERO : new BigDecimal(b);
        return x.add(y).setScale(SCALE, ROUNDING).toPlainString();
    }

    /**
     * Compares two decimal cost strings.
     *
     * @param a decimal string
     * @param b decimal string
     * @return negative if a < b, zero if equal, positive if a > b
     */
    public static int compare(String a, String b) {
        return new BigDecimal(a).compareTo(new BigDecimal(b));
    }

    /**
     * Returns true if the running total exceeds the budget.
     *
     * @param runningTotal decimal string
     * @param budget decimal string
     * @return true if over budget
     */
    public static boolean exceedsBudget(String runningTotal, String budget) {
        return compare(runningTotal, budget) > 0;
    }
}
