package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;
import convex.core.data.prim.CVMLong;

/**
 * Unit tests for the two-cell CAD3 construction.
 *
 * <p>The load-bearing invariant: cells are JSON-native, so a cell's in-memory
 * value hash equals the hash of its JSON round-trip (which is what the venue
 * asset store uses as the asset ID). A regression here makes every stored
 * artifact unresolvable by its own ID.</p>
 */
class CellsTest {

    private static AMap<AString, ACell> sampleCell1() {
        return Cells.buildCell1(Strings.create("hello world"), Cells.TYPE_TEXT);
    }

    private static AMap<AString, ACell> sampleCell2(AMap<AString, ACell> cell1) {
        return Cells.buildCell2(cell1, "session-1", "agent-1", "test/model",
            1700000000000L, Costs.tokenCount(10, 5, null), "0.000105", null, null);
    }

    @Test
    void jsonRoundTripPreservesHash() {
        AMap<AString, ACell> cell1 = sampleCell1();
        assertEquals(cell1.getHash(), Cells.parseCell1(Cells.toJson(cell1).toString()).getHash(),
            "Cell 1 hash must be stable across JSON round-trip");

        AMap<AString, ACell> cell2 = sampleCell2(cell1);
        assertEquals(cell2.getHash(), Cells.parseCell2(Cells.toJson(cell2).toString()).getHash(),
            "Cell 2 hash must be stable across JSON round-trip");
    }

    @Test
    void branchRefBindsCell1() {
        AMap<AString, ACell> cell1 = sampleCell1();
        AMap<AString, ACell> cell2 = sampleCell2(cell1);
        assertEquals(cell1.getHash(), Cells.branchRef(cell2));
        assertTrue(Cells.verifyBranchRef(cell2, cell1));
    }

    @Test
    void tamperedCell1BreaksBranchRef() {
        AMap<AString, ACell> cell1 = sampleCell1();
        AMap<AString, ACell> cell2 = sampleCell2(cell1);

        // Byte-flip equivalent: same shape, altered content
        AMap<AString, ACell> tampered = Cells.buildCell1(Strings.create("hello worle"), Cells.TYPE_TEXT);
        assertFalse(Cells.verifyBranchRef(cell2, tampered),
            "Altered content cell must fail branch-ref verification");
    }

    @Test
    void tamperedCell2ChangesArtifactId() {
        AMap<AString, ACell> cell1 = sampleCell1();
        AMap<AString, ACell> cell2 = sampleCell2(cell1);
        AMap<AString, ACell> forged = cell2.assoc(Cells.K_AGENT_ID, Strings.create("mallory"));
        assertNotEquals(Cells.artifactId(cell2), Cells.artifactId(forged),
            "Any metadata change must change the artifact ID");
    }

    @Test
    void prevEventLinksChain() {
        AMap<AString, ACell> cell1 = sampleCell1();
        AMap<AString, ACell> first = sampleCell2(cell1);
        Hash firstId = first.getHash();

        AMap<AString, ACell> second = Cells.buildCell2(
            Cells.buildCell1(Strings.create("v2"), Cells.TYPE_TEXT),
            "session-1", "agent-1", null, 1700000001000L, null, null, null, firstId);

        assertEquals(firstId, Cells.prevEvent(second));
        assertNull(Cells.prevEvent(first), "Genesis event has no prev-event");

        // Chain fields survive JSON round-trip
        AMap<AString, ACell> reparsed = Cells.parseCell2(Cells.toJson(second).toString());
        assertEquals(firstId, Cells.prevEvent(reparsed));
    }

    @Test
    void contentTypeValidation() {
        assertEquals("text", Cells.canonicalContentType("text"));
        assertEquals("model-output", Cells.canonicalContentType("model-output"));
        assertNull(Cells.canonicalContentType(null));
        assertThrows(IllegalArgumentException.class, () -> Cells.canonicalContentType("nonsense"));
    }

    @Test
    void parseHashForms() {
        Hash h = sampleCell1().getHash();
        assertEquals(h, Cells.parseHash(h), "Hash passes through");
        assertEquals(h, Cells.parseHash(Strings.create(h.toHexString())), "bare hex");
        assertEquals(h, Cells.parseHash(Strings.create("0x" + h.toHexString())), "0x-prefixed hex");
        assertNull(Cells.parseHash(Strings.create("tooshort")));
        assertNull(Cells.parseHash(null));
    }

    @Test
    void shortIdFormat() {
        AMap<AString, ACell> cell2 = sampleCell2(sampleCell1());
        String shortId = Cells.shortId(cell2);
        assertTrue(shortId.startsWith("ss_"));
        assertEquals(15, shortId.length());
        assertTrue(Cells.artifactId(cell2).startsWith(shortId.substring(3)));
    }

    @Test
    void buildCellValidation() {
        assertThrows(IllegalArgumentException.class,
            () -> Cells.buildCell1(null, Cells.TYPE_TEXT));
        assertThrows(IllegalArgumentException.class,
            () -> Cells.buildCell1(CVMLong.create(42), Cells.TYPE_TEXT), "non-string/blob content rejected");
        AMap<AString, ACell> cell1 = sampleCell1();
        assertThrows(IllegalArgumentException.class,
            () -> Cells.buildCell2(cell1, "  ", "agent", null, 0L, null, null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> Cells.buildCell2(cell1, "s", "", null, 0L, null, null, null, null));
    }
}
