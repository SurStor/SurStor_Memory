package ai.surstor.node;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import convex.core.crypto.AKeyPair;

/**
 * Regression test: the {@code -d/--data-dir} override must apply to the
 * identity keypair too, not just the etch store. Previously
 * {@code bootstrapIdentity} resolved the key file from the TOML config, so
 * startup either crashed with {@code NoSuchFileException} (configured dir
 * missing) or silently split node state across two directories.
 */
class DataDirOverrideTest {

    @TempDir
    Path tempDir;

    private NodeConfig configWithDataDir(Path dataDir) throws Exception {
        Path toml = tempDir.resolve("surstor.toml");
        Files.writeString(toml, "[node]\ndata_dir = \"" + dataDir + "\"\n");
        return NodeConfig.load(toml);
    }

    @Test
    void identityFollowsDataDirOverride() throws Exception {
        Path configured = tempDir.resolve("configured");
        Path override = tempDir.resolve("override");
        NodeConfig cfg = configWithDataDir(configured);

        // What StartCommand does with -d: effective dir wins over cfg.dataDir()
        AKeyPair keyPair = Main.bootstrapIdentity(override, cfg);

        assertTrue(Files.exists(override.resolve("identity.ed25519")),
            "identity written under the override dir");
        assertFalse(Files.exists(configured.resolve("identity.ed25519")),
            "identity must NOT be written under the configured dir");

        // Restarting with the same -d recovers the same identity
        AKeyPair restarted = Main.bootstrapIdentity(override, cfg);
        assertEquals(keyPair.getAccountKey(), restarted.getAccountKey(),
            "same -d on restart keeps the identity");
    }

    @Test
    void identityDefaultsToConfiguredDir() throws Exception {
        Path configured = tempDir.resolve("default-data");
        NodeConfig cfg = configWithDataDir(configured);

        // No -d flag: StartCommand passes cfg.dataDir() straight through
        Main.bootstrapIdentity(cfg.dataDir(), cfg);

        assertTrue(Files.exists(configured.resolve("identity.ed25519")),
            "identity written under the configured data dir");
    }
}
