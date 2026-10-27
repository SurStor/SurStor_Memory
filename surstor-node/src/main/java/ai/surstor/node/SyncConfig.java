package ai.surstor.node;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Explicit, fail-closed trusted-group replication policy. */
record SyncConfig(int port, int intervalSeconds, String token, Set<String> sessions,
                  Set<String> signers, List<URI> peers, String privacyPolicy, String bind) {
    SyncConfig(int port, int intervalSeconds, String token, Set<String> sessions,
            Set<String> signers, List<URI> peers, String privacyPolicy) {
        this(port, intervalSeconds, token, sessions, signers, peers, privacyPolicy, "127.0.0.1");
    }
    static SyncConfig load(NodeConfig cfg, Path configFile) throws Exception {
        return load(cfg, configFile, false);
    }
    static SyncConfig load(NodeConfig cfg, Path configFile, boolean allowContainerNetwork) throws Exception {
        cfg.validateSyncKeys();
        String enabled = cfg.get("sync", "enabled", "false");
        if (!Set.of("true", "false").contains(enabled)) throw new IllegalArgumentException("Invalid sync.enabled");
        if (enabled.equals("false")) return null;
        String bind = cfg.get("sync", "bind", "127.0.0.1");
        Main.validateBindAddress(bind, allowContainerNetwork);
        int port = Integer.parseInt(cfg.get("sync", "port", "8485"));
        int interval = Integer.parseInt(cfg.get("sync", "interval_seconds", "15"));
        if (port < 1 || port > 65535 || interval < 5 || interval > 3600)
            throw new IllegalArgumentException("Invalid sync port or interval");
        String file = cfg.get("sync", "token_file", "");
        if (file.isBlank()) throw new IllegalArgumentException("sync.token_file is required");
        Path path = Path.of(file);
        if (!path.isAbsolute()) path = configFile.toAbsolutePath().getParent().resolve(path);
        if (Files.size(path) > 256) throw new IllegalArgumentException("Invalid sync token file");
        String token = Files.readString(path).trim();
        if (!token.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("Sync token must be 32 random bytes encoded as 64 hex characters");
        Set<String> sessions = csv(cfg.get("sync", "sessions", ""));
        Set<String> signers = csv(cfg.get("sync", "trusted_signers", ""));
        if (sessions.isEmpty() || signers.isEmpty()) throw new IllegalArgumentException("Explicit sync sessions and trusted_signers are required");
        for (String signer : signers) if (!signer.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid sync signer public key");
        for (String session : sessions) if (session.length() > 128 || !session.matches("[A-Za-z0-9._-]+") || session.startsWith("sync-"))
            throw new IllegalArgumentException("Sync sessions must be simple local session names");
        List<URI> peers = new ArrayList<>();
        for (String value : csv(cfg.get("sync", "peers", ""))) {
            URI uri = URI.create(value);
            boolean loopback = Set.of("127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
            if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || !(uri.getPath().isEmpty() || uri.getPath().equals("/")))
                throw new IllegalArgumentException("Sync peers require HTTPS, or HTTP on a numeric loopback address through a secure tunnel");
            peers.add(URI.create(value.replaceAll("/$", "")));
        }
        ai.surstor.PrivacyFilter.load(cfg.privacyPolicyFile());
        return new SyncConfig(port, interval, token, Set.copyOf(sessions), Set.copyOf(signers), List.copyOf(peers), cfg.privacyPolicyFile(), bind);
    }
    private static Set<String> csv(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (!value.isBlank()) for (String item : value.split(",", -1)) {
            if (item.isBlank()) throw new IllegalArgumentException("Empty sync list entry");
            result.add(item.trim());
        }
        return result;
    }
}
