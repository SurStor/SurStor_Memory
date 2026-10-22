package ai.surstor.node;

import ai.surstor.SurStorAdapter;
import convex.core.crypto.AKeyPair;
import convex.core.data.*;
import convex.core.data.prim.CVMLong;
import convex.core.lang.RT;
import convex.etch.EtchStore;
import covia.venue.RequestContext;
import covia.venue.server.VenueServer;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class EncryptedStorageTest {
    @TempDir Path dir;
    @org.junit.jupiter.api.AfterEach void releaseMappings() { System.gc(); }

    private Path key() throws Exception {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        Path file = dir.resolve("key.hex");
        Files.writeString(file, HexFormat.of().formatHex(bytes));
        return file;
    }

    private NodeConfig config(String fields) throws Exception {
        Path file = dir.resolve("surstor.toml");
        Files.writeString(file, fields);
        return NodeConfig.load(file);
    }

    private String enabled() { return "[encryption]\nenabled = true\nkey_file = \"key.hex\"\n"; }

    @Test void failsClosedForConfigurationAndExternalPlaintext() throws Exception {
        key(); Path data = dir.resolve("data");
        for (String invalid : new String[] {
                "[encryption]\nenabled = tru\n",
                "[encryption]\nkey_file = \"key.hex\"\n",
                "[encryption]\nenabled = true\n",
                enabled() + "key_env = \"SURSTOR_TEST_KEY\"\n",
                enabled() + "cipher = \"none\"\n",
                enabled() + "encrypt_indx = true\n",
                enabled() + "[search]\nenabled = true\n"}) {
            NodeConfig cfg = config(invalid);
            assertThrows(IllegalArgumentException.class, () -> EncryptedStorage.policy(cfg, data));
        }
        NodeConfig cfg = config(enabled());
        assertEquals("false", cfg.searchEnabled());
        assertThrows(IllegalArgumentException.class, () -> EncryptedStorage.policy(cfg, dir));
        Files.createDirectories(data.resolve("search-index"));
        Files.writeString(data.resolve("search-index/old-text"), "old content");
        assertThrows(IllegalArgumentException.class, () -> EncryptedStorage.policy(cfg, data));
    }

    @Test void missingMalformedAndWrongKeysAndPlaintextStoreAreRejected() throws Exception {
        Path data = dir.resolve("data");
        NodeConfig cfg = config(enabled());
        assertThrows(Exception.class, () -> EncryptedStorage.policy(cfg, data));
        Path key = key();
        var policy = EncryptedStorage.policy(cfg, data);
        Files.writeString(key, "invalid");
        assertThrows(Exception.class, () -> EncryptedStorage.preflight(policy, data));
        assertFalse(Files.exists(data.resolve("etch")));
        key();
        EncryptedStorage.preflight(policy, data);
        key(); // New random key must not reopen the old store.
        assertThrows(Exception.class, () -> EncryptedStorage.preflight(policy, data));
        Path plain = dir.resolve("plain"); Files.createDirectories(plain);
        try (var store = EtchStore.create(plain.resolve("etch").toFile())) {
            store.setRootData(Strings.create("old plaintext record")); store.flush();
        }
        assertThrows(Exception.class, () -> EncryptedStorage.preflight(policy, plain));
    }

    @Test void encryptedVenueStoresVerifiesAndSurvivesRestartAndBackupRestore() throws Exception {
        key();
        Path data = dir.resolve("data");
        var policy = EncryptedStorage.policy(config(enabled()), data);
        EncryptedStorage.preflight(policy, data);
        var identity = AKeyPair.generate();
        String marker = "synthetic-private-encrypted-record-" + "abc123".repeat(80);
        AMap<AString, ACell> venue = Maps.of(Strings.create("port"), CVMLong.ZERO,
            Strings.create("bindAddress"), Strings.create("127.0.0.1"),
            Strings.create("store"), Strings.create(data.resolve("etch").toString()),
            Strings.create("storage"), Maps.of(Strings.create("content"), Strings.create("lattice")),
            Strings.create("etch"), policy,
            Strings.create("seed"), Strings.create(identity.getSeed().toHexString()),
            Strings.create("adapters"), Maps.of(Strings.create("surstor"),
                Maps.of(Strings.create("search-enabled"), Strings.create("false"))));
        ACell id;
        VenueServer server = VenueServer.launch(venue);
        try {
            var adapter = new SurStorAdapter(); server.getEngine().registerAdapter(adapter);
            var ctx = RequestContext.of(Strings.create(server.getEngine().getDIDString() + ":public"));
            ACell saved = adapter.invokeFuture(ctx, operation("store"),
                Maps.of(Strings.create("content"), Strings.create(marker),
                    Strings.create("contentType"), Strings.create("text"))).get();
            id = RT.getIn(saved, Strings.create("id")); assertNotNull(id);
        } finally { server.close(); }
        assertFalse(Files.exists(data.resolve("search-index")));
        try (var paths = Files.walk(data)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains(marker));
            }
        }
        Path restored = dir.resolve("restored"); Files.createDirectories(restored);
        // Copy the closed encrypted store, preserving all content-addressed records.
        Files.copy(data.resolve("etch"), restored.resolve("etch"));
        for (Path location : new Path[] {data, restored}) {
            EncryptedStorage.preflight(policy, location);
            server = VenueServer.launch(venue.assoc(Strings.create("store"),
                    Strings.create(location.resolve("etch").toString())));
            try {
                var adapter = new SurStorAdapter(); server.getEngine().registerAdapter(adapter);
                var ctx = RequestContext.of(Strings.create(server.getEngine().getDIDString() + ":public"));
                AMap<AString, ACell> args = Maps.of(Strings.create("id"), id);
                ACell record = adapter.invokeFuture(ctx, operation("retrieve"), args).get();
                assertTrue(record.toString().contains(marker));
                ACell verified = adapter.invokeFuture(ctx, operation("verify"), args).get();
                assertEquals("true", RT.getIn(verified, Strings.create("valid")).toString());
            } finally { server.close(); }
        }
    }

    private AMap<AString, ACell> operation(String name) {
        return Maps.of(Strings.create("operation"), Maps.of(Strings.create("adapter"), Strings.create("surstor:" + name)));
    }
}
