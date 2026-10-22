package ai.surstor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import convex.core.data.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivacyFilterTest {
    @TempDir Path dir;
    private final PrivacyFilter.Policy defaults = PrivacyFilter.load(null);

    @Test void detectsCredentialFormatsAndPreservesOrdinaryText() {
        for (String secret : List.of("sk-proj-abcdefghijklmnop0123456789", "ghp_abcdefghijklmnopqrstuvwxyz",
            "AKIAABCDEFGHIJKLMNOP", "Bearer abcdef12345", "password=hidden-value",
            "https://user:password@example.org", "-----BEGIN PRIVATE KEY-----\nanything\n-----END PRIVATE KEY-----",
            "eyJabcdefghijk.abcdefghijk.abcdefghijk")) {
            var result = PrivacyFilter.filter(Strings.create("before " + secret + " after"), defaults);
            assertTrue(result.redactions() > 0);
            assertFalse(result.value().toString().contains(secret));
            PrivacyFilter.requireClean(result.value(), defaults);
        }
        var plain = Strings.create("Explain how a password manager works. Input tokens: 100.");
        assertEquals(plain, PrivacyFilter.filter(plain, defaults).value());
    }

    @Test void scansNestedPromptsResponsesAndMetadata() {
        ACell input = PrivacyFilter.parse("{\"content\":{\"prompt\":\"api_key=hidden\",\"response\":{\"password\":123}},\"sessionId\":\"Bearer hidden\"}");
        var result = PrivacyFilter.filter(input, defaults);
        assertTrue(result.redactions() >= 3);
        assertFalse(result.value().toString().contains("hidden"));
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.requireClean(input, defaults));
    }

    @Test void organisationPolicyRedactsOrBlocksIncludingMetadata() throws Exception {
        Path policy = dir.resolve("policy.json");
        Files.writeString(policy, "{\"redactTerms\":[\"Client Orion\"],\"excludeTerms\":[\"payroll/\"]}");
        var p = PrivacyFilter.load(policy.toString());
        assertEquals("[REDACTED] notes", PrivacyFilter.filter(Strings.create("CLIENT ORION notes"), p).value().toString());
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.filter(PrivacyFilter.parse("{\"sessionId\":\"payroll/private\"}"), p));
        Files.writeString(policy, "{\"redactTerm\":[\"typo\"]}");
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.load(policy.toString()));
        Files.delete(policy);
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.load(policy.toString()));
    }

    @Test void failuresNeverReturnOriginalAndUnsupportedInputsAreBlocked() {
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.parse("[".repeat(33) + "]".repeat(33)));
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.filter(Strings.create("x".repeat(PrivacyFilter.MAX_CHARS + 1)), defaults));
        assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.filter(Blob.fromHex("abcd"), defaults));
        var e = assertThrows(PrivacyFilter.Blocked.class, () -> PrivacyFilter.parse("{private broken"));
        assertFalse(e.getMessage().contains("private broken"));
        assertNull(e.getCause());
    }
}
