package ai.surstor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Hash;
import convex.core.data.Strings;

/**
 * Unit tests for session hash-chain records.
 */
class SessionsTest {

    @Test
    void newSessionIsEmpty() {
        AMap<AString, ACell> s = Sessions.createSession("s1", 1000L);
        assertNull(Sessions.head(s));
        assertEquals(0, Sessions.count(s));
        assertEquals(0, Sessions.artifactCount(s));
        assertEquals("0.000000", Sessions.totalCost(s));
    }

    @Test
    void advanceChainsHeadAndAccumulates() {
        AMap<AString, ACell> s = Sessions.createSession("s1", 1000L);
        Hash h1 = Cells.buildCell1(Strings.create("a"), Cells.TYPE_TEXT).getHash();
        Hash h2 = Cells.buildCell1(Strings.create("b"), Cells.TYPE_TEXT).getHash();

        s = Sessions.advanceSession(s, h1, "0.010500", 1001L);
        assertEquals(h1, Sessions.head(s));
        assertEquals(1, Sessions.count(s));
        assertEquals(1, Sessions.artifactCount(s));
        assertEquals("0.010500", Sessions.totalCost(s));

        s = Sessions.advanceSession(s, h2, "0.012600", 1002L);
        assertEquals(h2, Sessions.head(s));
        assertEquals(2, Sessions.count(s));
        assertEquals("0.023100", Sessions.totalCost(s));
    }

    @Test
    void sessionRecordSurvivesJsonRoundTrip() {
        AMap<AString, ACell> s = Sessions.createSession("s1", 1000L);
        Hash h1 = Cells.buildCell1(Strings.create("a"), Cells.TYPE_TEXT).getHash();
        s = Sessions.advanceSession(s, h1, "0.010500", 1001L);

        AMap<AString, ACell> reparsed = Cells.parseCell2(Cells.toJson(s).toString());
        assertEquals(h1, Sessions.head(reparsed), "head hash must survive serialisation");
        assertEquals(1, Sessions.count(reparsed));
        assertEquals("0.010500", Sessions.totalCost(reparsed));
    }
}
