package ai.surstor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import convex.core.data.*;
import convex.core.data.prim.*;
import convex.core.util.JSON;

/** Local, bounded filtering. Never includes submitted values in errors or receipts. */
public final class PrivacyFilter {
    public static final int MAX_CHARS = 262_144;
    public static final String VERSION = "surstor-filter/1";
    public static final String REDACTED = "[REDACTED]";
    private static final Pattern SECRET_KEY = Pattern.compile("(?i)(password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|authorization|private[_-]?key|secret)");
    private static final List<Pattern> PATTERNS = List.of(
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"),
        Pattern.compile("\\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AKIA[A-Z0-9]{16})\\b"),
        Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"),
        Pattern.compile("(?i)\\b(?:Bearer|Basic) +[A-Za-z0-9_./+=-]+"),
        Pattern.compile("(?i)\\b(?:password|passwd|api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret)\\s*[:=]\\s*(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;]+)"),
        Pattern.compile("(?i)https?://[^\\s/@:]+:[^\\s/@]+@"));

    public record Policy(List<String> redactTerms, List<String> excludeTerms) {
        public Policy { redactTerms = List.copyOf(redactTerms); excludeTerms = List.copyOf(excludeTerms); }
    }
    public record Result(ACell value, int redactions) {}
    public static final class Blocked extends IllegalArgumentException {
        public Blocked() { super("Capture blocked: filtering could not approve this input. Check the local privacy policy and review the content."); }
    }
    private PrivacyFilter() {}

    /** Re-read on every capture; a missing/unreadable/invalid configured file never disables filtering. */
    public static Policy load(String file) {
        if (file == null || file.isBlank()) return new Policy(List.of(), List.of());
        try {
            Path path = Path.of(file);
            if (Files.size(path) > 65_536) throw new Blocked();
            ACell parsed = parse(Files.readString(path));
            if (!(parsed instanceof AMap<?, ?> map)) throw new Blocked();
            for (ACell k : map.keySet()) {
                if (!Set.of("redactTerms", "excludeTerms").contains(k.toString())) throw new Blocked();
            }
            return new Policy(terms(map.get(Strings.create("redactTerms"))), terms(map.get(Strings.create("excludeTerms"))));
        } catch (Exception e) { throw new Blocked(); }
    }

    private static List<String> terms(ACell value) {
        if (value == null) return List.of();
        if (!(value instanceof AVector<?> v) || v.count() > 100) throw new Blocked();
        List<String> out = new ArrayList<>();
        for (ACell term : v) {
            if (!(term instanceof AString) || term.toString().isBlank() || term.toString().length() > 256) throw new Blocked();
            out.add(term.toString());
        }
        return out;
    }

    public static ACell parse(String json) {
        if (json == null || json.length() > MAX_CHARS) throw new Blocked();
        int depth = 0; boolean quoted = false, escape = false;
        for (char c : json.toCharArray()) {
            if (quoted) {
                if (escape) escape = false;
                else if (c == '\\') escape = true;
                else if (c == '"') quoted = false;
            } else if (c == '"') quoted = true;
            else if (c == '{' || c == '[') { if (++depth > 32) throw new Blocked(); }
            else if (c == '}' || c == ']') depth--;
        }
        try { return JSON.parse(json); } catch (Exception e) { throw new Blocked(); }
    }

    public static Result filter(ACell input, Policy policy) {
        try {
            int[] count = {0}; int[] budget = {MAX_CHARS};
            ACell value = walk(input, policy, count, budget, 0);
            return new Result(value, count[0]);
        } catch (Exception e) { throw new Blocked(); }
    }

    public static void requireClean(ACell input, Policy policy) {
        if (filter(input, policy).redactions() != 0) throw new Blocked();
    }

    private static ACell walk(ACell v, Policy p, int[] n, int[] budget, int depth) {
        if (depth > 32 || --budget[0] < 0) throw new Blocked();
        if (v == null || v instanceof CVMBool || v instanceof CVMLong || v instanceof CVMDouble) return v;
        if (v instanceof AString s) {
            String text = s.toString(); budget[0] -= text.length();
            if (budget[0] < 0) throw new Blocked();
            String lower = text.toLowerCase(Locale.ROOT);
            for (String term : p.excludeTerms()) if (lower.contains(term.toLowerCase(Locale.ROOT))) throw new Blocked();
            for (Pattern pattern : PATTERNS) {
                var matcher = pattern.matcher(text);
                StringBuffer out = new StringBuffer();
                while (matcher.find()) { n[0]++; matcher.appendReplacement(out, REDACTED); }
                matcher.appendTail(out); text = out.toString();
            }
            for (String term : p.redactTerms()) {
                var matcher = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(text);
                StringBuffer out = new StringBuffer();
                while (matcher.find()) { n[0]++; matcher.appendReplacement(out, REDACTED); }
                matcher.appendTail(out); text = out.toString();
            }
            return Strings.create(text);
        }
        if (v instanceof AMap<?, ?> map) {
            AMap<ACell, ACell> out = Maps.empty();
            for (ACell key : map.keySet()) {
                if (!(key instanceof AString)) throw new Blocked();
                int before = n[0];
                walk(key, p, n, budget, depth + 1);
                if (n[0] != before) throw new Blocked(); // Never rename a key and silently collide.
                ACell original = map.get(key);
                ACell clean = walk(original, p, n, budget, depth + 1);
                if (SECRET_KEY.matcher(key.toString()).matches() && original != null && !REDACTED.equals(original.toString())) {
                    n[0]++; clean = Strings.create(REDACTED);
                }
                out = out.assoc(key, clean);
            }
            return out;
        }
        if (v instanceof AVector<?> vec) {
            AVector<ACell> out = Vectors.empty();
            for (ACell item : vec) out = out.conj(walk(item, p, n, budget, depth + 1));
            return out;
        }
        throw new Blocked(); // Binary and unknown cell types cannot be inspected.
    }
}
