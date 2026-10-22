package ai.surstor.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import convex.core.data.*;
import convex.core.data.prim.CVMBool;
import convex.core.data.prim.CVMLong;
import convex.etch.EtchConfig;
import convex.etch.EtchStore;
import covia.venue.Config;

/** Operator-selected encrypted Etch policy. Does not encrypt external files. */
final class EncryptedStorage {
    private EncryptedStorage() {}

    static AMap<AString, ACell> policy(NodeConfig cfg, Path dataDir) throws IOException {
        cfg.validateEncryptionKeys();
        if (!cfg.encryptionEnabled()) return null;
        if (!"false".equals(cfg.searchEnabled())) {
            throw new IllegalArgumentException("Encrypted storage requires search.enabled = false; the external search index is not encrypted by Etch");
        }
        // Old derived files must not be mistaken for protected data after a switch.
        Path index = dataDir.resolve("search-index");
        if (Files.exists(index)) {
            try (var entries = Files.walk(index)) {
                if (entries.anyMatch(Files::isRegularFile)) {
                    throw new IllegalArgumentException("Use a fresh data directory: an existing search index is outside encrypted Etch storage");
                }
            }
        }
        Path keyFile = cfg.encryptionKeyFile();
        String env = cfg.encryptionKeyEnv();
        if ((keyFile == null) == env.isBlank()) {
            throw new IllegalArgumentException("Set exactly one of encryption.key_env or encryption.key_file");
        }
        if (!env.isBlank() && !env.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid encryption.key_env name");
        }
        if (keyFile != null) {
            Path realKey = keyFile.toRealPath();
            Path actualData = Files.exists(dataDir) ? dataDir.toRealPath() : dataDir.toAbsolutePath().normalize();
            if (realKey.startsWith(actualData)) {
                throw new IllegalArgumentException("Keep the encryption key file outside the data directory");
            }
        }
        AMap<AString, ACell> source = keyFile == null
            ? Maps.of(Strings.create("env"), Strings.create(env))
            : Maps.of(Strings.create("file"), Strings.create(keyFile.toString()));
        return Maps.of(Strings.create("version"), CVMLong.create(3),
            Strings.create("cipher"), Strings.create(cfg.encryptionCipher()),
            Strings.create("encryptIndex"), CVMBool.TRUE,
            Strings.create("key"), source);
    }

    /** Check the actual store before the venue can write jobs or listen for requests. */
    static void preflight(AMap<AString, ACell> policy, Path dataDir) throws IOException {
        if (policy == null) return;
        EtchConfig requested = new Config(Maps.of(Strings.create("etch"), policy)).getEtchConfig();
        Files.createDirectories(dataDir);
        try (EtchStore store = EtchStore.create(dataDir.resolve("etch").toFile(), requested)) {
            EtchConfig actual = store.getEtch().getConfig();
            if (actual.getVersion() != 3 || actual.getCipherMode() != requested.getCipherMode()
                    || !actual.isIndexEncrypted()) {
                throw new IllegalArgumentException("Existing store does not match the encrypted policy; use a fresh data directory");
            }
        }
    }
}
