package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;

/**
 * Unit tests for cost computation against the preserved legacy placeholder schedule.
 */
class CostsTest {

    private static AMap<AString, ACell> seed() {
        return Pricing.loadResource(Pricing.LEGACY_SEED_RESOURCE);
    }

    @Test
    void computesKnownModelCost() {
        // sonnet-5: $3/1M input, $15/1M output
        // 1000 in + 500 out = 0.003 + 0.0075 = 0.0105
        String cost = Costs.compute("anthropic/claude-sonnet-5", Costs.tokenCount(1000, 500, null), seed());
        assertEquals("0.010500", cost);
    }

    @Test
    void cachedInputUsesCachedRate() {
        // haiku-4-5: $0.25/1M input, $0.025/1M cached, $1.25/1M output
        // 1M cached in = 0.025
        String cost = Costs.compute("anthropic/claude-haiku-4-5", Costs.tokenCount(0, 0, 1_000_000L), seed());
        assertEquals("0.025000", cost);
    }

    @Test
    void unknownModelReturnsNull() {
        assertNull(Costs.compute("no/such-model", Costs.tokenCount(1000, 500, null), seed()));
    }

    @Test
    void missingInputsReturnNull() {
        assertNull(Costs.compute(null, Costs.tokenCount(1, 1, null), seed()));
        assertNull(Costs.compute("anthropic/claude-sonnet-5", null, seed()));
        assertNull(Costs.compute("anthropic/claude-sonnet-5", Costs.tokenCount(1, 1, null), null));
    }

    @Test
    void addAccumulates() {
        assertEquals("0.023100", Costs.add("0.010500", "0.012600"));
        assertEquals("0.010500", Costs.add(null, "0.010500"));
        assertEquals("0.000000", Costs.add(null, null));
    }

    @Test
    void budgetComparison() {
        assertTrue(Costs.exceedsBudget("5.000001", "5.00"));
        assertFalse(Costs.exceedsBudget("5.00", "5.00"), "exactly at budget is not over");
        assertFalse(Costs.exceedsBudget("4.99", "5.00"));
    }

    @Test
    void tokenCountShape() {
        AMap<AString, ACell> tc = Costs.tokenCount(10, 5, null);
        assertNotNull(tc.get(Costs.K_INPUT));
        assertNotNull(tc.get(Costs.K_OUTPUT));
        assertNull(tc.get(Costs.K_CACHED_INPUT), "absent cached-input stays absent");

        AMap<AString, ACell> tcCached = Costs.tokenCount(10, 5, 3L);
        assertNotNull(tcCached.get(Costs.K_CACHED_INPUT));
    }
}
