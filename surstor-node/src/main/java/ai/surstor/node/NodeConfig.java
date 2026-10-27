package ai.surstor.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * SurStor node configuration parsed from {@code surstor.toml}.
 *
 * <p>Preserves unknown keys on rewrite for forward compatibility.</p>
 */
public final class NodeConfig {

    private final Path path;
    private final Map<String, Map<String, String>> sections = new HashMap<>();

    private NodeConfig(Path path) {
        this.path = path;
    }

    /**
     * Loads the config from a file, or creates a default if it doesn't exist.
     *
     * @param path config file path
     * @return parsed config
     * @throws IOException if reading fails
     */
    public static NodeConfig load(Path path) throws IOException {
        NodeConfig cfg = new NodeConfig(path);
        if (!Files.exists(path)) {
            cfg.setDefaults();
            cfg.save();
            return cfg;
        }

        String content = Files.readString(path);
        cfg.parse(content);
        return cfg;
    }

    /**
     * Returns the data directory, expanding {@code ~}.
     *
     * @return data directory path
     */
    public Path dataDir() {
        String dir = get("node", "data_dir", "~/.surstor-node");
        return expandHome(dir);
    }

    /**
     * Returns the bind address.
     *
     * @return bind address
     */
    public String bindAddress() {
        return get("node", "bind", "127.0.0.1");
    }

    /**
     * Returns the HTTP port.
     *
     * @return port
     */
    public int port() {
        return Integer.parseInt(get("node", "port", "8484"));
    }

    /** Optional operator-controlled organisation policy, relative to this config. */
    public String privacyPolicyFile() {
        String value = get("privacy", "policy_file", "");
        if (value.isBlank()) return null;
        Path policy = expandHome(value);
        return (policy.isAbsolute() ? policy : path.toAbsolutePath().getParent().resolve(policy)).normalize().toString();
    }

    /**
     * Returns the keypair file path within the data directory.
     *
     * @return keypair file path
     */
    public Path keypairFile() {
        String file = get("identity", "keypair_file", "identity.ed25519");
        return dataDir().resolve(file);
    }

    /**
     * Returns the default agent ID.
     *
     * @return default agent ID
     */
    public String defaultAgentId() {
        return get("surstor", "default_agent_id", "local-user");
    }

    /**
     * Returns the session budget in USD.
     *
     * @return session budget decimal string
     */
    public String sessionBudgetUsd() {
        return get("surstor", "session_budget_usd", "5.00");
    }

    /**
     * Returns the price schedule identifier.
     *
     * @return price schedule ID or "builtin:2026-08"
     */
    public String priceSchedule() {
        return get("surstor", "price_schedule", "builtin:2026-08");
    }

    /**
     * Whether semantic search (local embeddings + vector index) is enabled.
     *
     * @return "true" unless disabled in config
     */
    public String searchEnabled() {
        return get("search", "enabled", encryptionEnabled() ? "false" : "true");
    }

    /** Encryption is opt-in for existing installations; malformed settings fail closed. */
    public boolean encryptionEnabled() {
        String value = get("encryption", "enabled", "false");
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("encryption.enabled must be true or false");
        }
        if (value.equals("false") && sections.containsKey("encryption")
                && sections.get("encryption").keySet().stream().anyMatch(k -> !k.equals("enabled"))) {
            throw new IllegalArgumentException("Encryption settings require encryption.enabled = true");
        }
        return value.equals("true");
    }

    public String encryptionCipher() {
        String cipher = get("encryption", "cipher", "aes-256-ctr");
        if (!java.util.Set.of("aes-256-ctr", "chacha20").contains(cipher)) {
            throw new IllegalArgumentException("Unsupported encryption cipher");
        }
        return cipher;
    }

    public String encryptionKeyEnv() { return get("encryption", "key_env", ""); }

    public Path encryptionKeyFile() {
        String value = get("encryption", "key_file", "");
        if (value.isBlank()) return null;
        Path file = expandHome(value);
        return (file.isAbsolute() ? file : path.toAbsolutePath().getParent().resolve(file)).normalize();
    }

    void validateEncryptionKeys() {
        for (String key : sections.getOrDefault("encryption", Map.of()).keySet()) {
            if (!java.util.Set.of("enabled", "cipher", "key_env", "key_file").contains(key)) {
                throw new IllegalArgumentException("Unknown encryption setting: " + key);
            }
        }
    }

    /**
     * Returns the default result count for semantic search.
     *
     * @return k default decimal string
     */
    public String searchKDefault() {
        return get("search", "k_default", "10");
    }

    /**
     * Saves the config back to disk, preserving unknown keys.
     *
     * @throws IOException if writing fails
     */
    public void save() throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Map<String, String>> section : sections.entrySet()) {
            sb.append("[").append(section.getKey()).append("]\n");
            for (Map.Entry<String, String> entry : section.getValue().entrySet()) {
                sb.append(entry.getKey()).append(" = \"").append(entry.getValue()).append("\"\n");
            }
            sb.append("\n");
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, sb.toString());
    }

    private void setDefaults() {
        sections.put("node", new HashMap<>(Map.of(
            "bind", "127.0.0.1",
            "port", "8484",
            "data_dir", "~/.surstor-node")));
        sections.put("identity", new HashMap<>(Map.of(
            "keypair_file", "identity.ed25519")));
        sections.put("surstor", new HashMap<>(Map.of(
            "default_agent_id", "local-user",
            "session_budget_usd", "5.00",
            "price_schedule", "builtin:2026-08")));
    }

    private void parse(String content) {
        String currentSection = null;
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length() - 1);
                sections.computeIfAbsent(currentSection, k -> new HashMap<>());
                continue;
            }

            int eq = trimmed.indexOf('=');
            if (eq < 0) continue;

            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }

            if (currentSection != null) {
                sections.computeIfAbsent(currentSection, k -> new HashMap<>()).put(key, value);
            }
        }
    }

    void validateSyncKeys() {
        for (String key : sections.getOrDefault("sync", Map.of()).keySet()) {
            if (!java.util.Set.of("enabled", "port", "interval_seconds", "token_file", "sessions", "trusted_signers", "peers").contains(key))
                throw new IllegalArgumentException("Unknown sync setting: " + key);
        }
    }

    String get(String section, String key, String defaultValue) {
        Map<String, String> s = sections.get(section);
        if (s == null) return defaultValue;
        return s.getOrDefault(key, defaultValue);
    }

    private static Path expandHome(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            String home = System.getProperty("user.home");
            return Path.of(home + path.substring(1));
        }
        return Path.of(path);
    }
}
