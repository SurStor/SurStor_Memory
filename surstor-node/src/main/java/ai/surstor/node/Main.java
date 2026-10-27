package ai.surstor.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.concurrent.Callable;

import convex.core.crypto.AKeyPair;
import convex.core.data.ACell;
import convex.core.data.AMap;
import convex.core.data.AString;
import convex.core.data.Maps;
import convex.core.data.Strings;
import convex.core.data.Vectors;
import convex.core.data.prim.CVMLong;
import covia.venue.server.VenueServer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * SurStor Node CLI entry point.
 *
 * <p>Commands: start, stop, status, verify.</p>
 */
@Command(name = "surstor-node", mixinStandardHelpOptions = true,
         description = "SurStor local node — tamper-evident AI work artifact storage",
         subcommands = {Main.StartCommand.class, Main.StopCommand.class,
                        Main.StatusCommand.class, Main.VerifyCommand.class})
public class Main implements Callable<Integer> {

    @Option(names = {"-c", "--config"}, description = "Config file path (default: ~/.surstor-node/surstor.toml)")
    private Path configPath = Path.of(System.getProperty("user.home"), ".surstor-node", "surstor.toml");

    @Option(names = {"-d", "--data-dir"}, description = "Data directory override")
    private Path dataDirOverride;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("Use one of: start, stop, status, verify");
        return 0;
    }

    // ========== start ==========

    @Command(name = "start", description = "Start the SurStor node")
    static class StartCommand implements Callable<Integer> {
        @Option(names = {"-c", "--config"}) Path configPath = Path.of(System.getProperty("user.home"), ".surstor-node", "surstor.toml");
        @Option(names = {"-d", "--data-dir"}) Path dataDirOverride;
        @Option(names = {"-p", "--port"}) Integer portOverride;
        @Option(names = "--allow-container-network", description = "Allow 0.0.0.0 inside an isolated container; publish the host port on loopback only")
        boolean allowContainerNetwork;

        @Override
        public Integer call() throws Exception {
            NodeConfig cfg = NodeConfig.load(configPath);
            Path dataDir = (dataDirOverride != null) ? dataDirOverride : cfg.dataDir();
            int port = (portOverride != null) ? portOverride : cfg.port();
            validateBindAddress(cfg.bindAddress(), allowContainerNetwork);
            SyncConfig syncConfig = SyncConfig.load(cfg, configPath, allowContainerNetwork);
            CaptureRoutes capture = new CaptureRoutes(cfg.privacyPolicyFile());

            AMap<AString, ACell> encryption = EncryptedStorage.policy(cfg, dataDir);
            EncryptedStorage.preflight(encryption, dataDir);

            // Bootstrap identity
            AKeyPair keyPair = bootstrapIdentity(dataDir, cfg);
            String did = "did:key:" + keyPair.getAccountKey().toString();
            System.out.println("SurStor node identity: " + did);
            System.out.println("Sync signer public key: " + keyPair.getAccountKey().toHexString());

            // Build venue config
            AMap<AString, ACell> venueConfig = buildVenueConfig(cfg, dataDir, port, keyPair);
            if (encryption != null) {
                venueConfig = venueConfig.assoc(Strings.create("etch"), encryption)
                    .assoc(Strings.create("storage"), Maps.of(Strings.create("content"), Strings.create("lattice")));
                System.out.println("Encrypted Etch storage enabled (including Etch index); semantic search disabled.");
            }

            // Launch venue
            VenueServer server = VenueServer.launch(venueConfig, java.util.List.of(capture::register));
            MemorySync sync = syncConfig == null ? null : new MemorySync(server.getEngine(), syncConfig);
            try { if (sync != null) sync.start(); }
            catch (Exception e) { if (sync != null) sync.close(); server.close(); throw e; }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try { if (sync != null) sync.close(); server.close(); }
                catch (Exception e) { System.err.println("Venue shutdown failed; check storage before restarting."); }
            }, "surstor-shutdown"));
            capture.attach(server.getEngine());
            if (sync != null) System.out.println("Selected-memory sync on " + syncConfig.bind() + ":" + syncConfig.port() + "; secure tunnel or HTTPS proxy required for remote access.");
            System.out.println("SurStor node started at http://" + cfg.bindAddress() + ":" + port);
            System.out.println("MCP endpoint: http://" + cfg.bindAddress() + ":" + port + "/mcp");
            System.out.println("Capture review: http://" + cfg.bindAddress() + ":" + port + "/capture");

            // Block forever — the venue runs on daemon threads, so the JVM
            // would exit as soon as main returns without this.
            Thread.currentThread().join();
            return 0;
        }
    }

    // ========== stop ==========

    @Command(name = "stop", description = "Stop the SurStor node")
    static class StopCommand implements Callable<Integer> {
        @Override
        public Integer call() {
            System.out.println("Stop is not yet implemented — kill the process for now");
            return 0;
        }
    }

    // ========== status ==========

    @Command(name = "status", description = "Show node status")
    static class StatusCommand implements Callable<Integer> {
        @Option(names = {"-c", "--config"}) Path configPath = Path.of(System.getProperty("user.home"), ".surstor-node", "surstor.toml");

        @Override
        public Integer call() throws Exception {
            NodeConfig cfg = NodeConfig.load(configPath);
            Path dataDir = cfg.dataDir();

            System.out.println("SurStor Node Status");
            System.out.println("  Data dir: " + dataDir);
            System.out.println("  Config: " + configPath);
            System.out.println("  Bind: " + cfg.bindAddress() + ":" + cfg.port());
            System.out.println("  Default agent: " + cfg.defaultAgentId());
            System.out.println("  Session budget: " + cfg.sessionBudgetUsd() + " USD");
            System.out.println("  Price schedule: " + cfg.priceSchedule());

            Path identityFile = cfg.keypairFile();
            System.out.println("  Identity: " + (Files.exists(identityFile) ? "present" : "not created"));

            return 0;
        }
    }

    // ========== verify ==========

    @Command(name = "verify", description = "Verify a SurStor export bundle offline — no venue required")
    static class VerifyCommand implements Callable<Integer> {
        @Parameters(index = "0", description = "Path to a surstor-export/1 bundle JSON file") Path bundleFile;

        @Override
        public Integer call() throws Exception {
            if (!Files.exists(bundleFile)) {
                System.err.println("Bundle not found: " + bundleFile);
                return 2;
            }
            String json = Files.readString(bundleFile);
            OfflineVerifier.BundleResult result = OfflineVerifier.verifyBundle(json);

            System.out.println("SurStor offline verification: " + bundleFile);
            for (OfflineVerifier.CheckResult check : result.checks()) {
                System.out.printf("  [%s] %s — %s%n", check.ok() ? "PASS" : "FAIL", check.name(), check.detail());
            }
            System.out.println(result.ok()
                ? "VALID — all checks passed"
                : "INVALID — one or more checks failed");
            return result.ok() ? 0 : 1;
        }
    }

    // ========== Helpers ==========

    static void validateBindAddress(String bind, boolean allowContainerNetwork) {
        if (java.util.Set.of("127.0.0.1", "::1", "localhost").contains(bind)) return;
        if (allowContainerNetwork && "0.0.0.0".equals(bind)) return;
        throw new IllegalArgumentException("Use a loopback bind, or explicitly allow 0.0.0.0 with --allow-container-network inside an isolated container.");
    }

    static AKeyPair bootstrapIdentity(Path dataDir, NodeConfig cfg) throws IOException {
        // Anchor the keypair filename to the EFFECTIVE data dir — cfg.keypairFile()
        // resolves against the TOML data_dir and would ignore a -d override,
        // crashing or splitting node state across two directories.
        Path keyFile = dataDir.resolve(cfg.keypairFile().getFileName().toString());
        Files.createDirectories(dataDir);

        if (Files.exists(keyFile)) {
            String hex = Files.readString(keyFile).trim();
            return AKeyPair.create(hex);
        }

        // Generate new Ed25519 keypair
        AKeyPair keyPair = AKeyPair.generate();
        String hex = keyPair.getSeed().toHexString();

        Files.writeString(keyFile, hex);
        // Restrict permissions
        try {
            Files.setPosixFilePermissions(keyFile, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // Non-POSIX filesystem — ignore
        }

        System.out.println("Created new node identity: " + keyFile);
        return keyPair;
    }

    private static AMap<AString, ACell> buildVenueConfig(NodeConfig cfg, Path dataDir, int port, AKeyPair keyPair) {
        Path etchDir = dataDir.resolve("etch");
        Path moduleJar = findModuleJar();

        AMap<AString, ACell> surstorAdapterConfig = Maps.of(
            Strings.create("default-agent-id"), Strings.create(cfg.defaultAgentId()),
            Strings.create("session-budget-usd"), Strings.create(cfg.sessionBudgetUsd()),
            // Semantic search: index lives under the EFFECTIVE data dir so the
            // -d override keeps all node state together
            Strings.create("search-enabled"), Strings.create(cfg.searchEnabled()),
            Strings.create("k-default"), Strings.create(cfg.searchKDefault()),
            Strings.create("search-index-dir"), Strings.create(dataDir.resolve("search-index").toString()));

        AMap<AString, ACell> adaptersConfig = Maps.of(
            Strings.create("surstor"), surstorAdapterConfig.assoc(Strings.create("privacy-policy-file"),
                cfg.privacyPolicyFile() == null ? null : Strings.create(cfg.privacyPolicyFile())));

        return Maps.of(
            Strings.create("name"), Strings.create("SurStor Node"),
            Strings.create("hostname"), Strings.create("localhost"),
            Strings.create("port"), CVMLong.create(port),
            Strings.create("bindAddress"), Strings.create(cfg.bindAddress()),
            Strings.create("store"), Strings.create(etchDir.toString()),
            Strings.create("seed"), Strings.create(keyPair.getSeed().toHexString()),
            // Loopback-only single-user node: anonymous local callers get full access.
            // Container mode relies on isolated networking and a loopback-only host port.
            Strings.create("auth"), Maps.of(
                Strings.create("public"), Maps.of(
                    Strings.create("caps"), Strings.create("unrestricted"))),
            Strings.create("mcp"), Maps.of(
                Strings.create("includeAdapters"), Vectors.of(
                    Strings.create("covia"), Strings.create("grid"), Strings.create("asset"),
                    Strings.create("secret"), Strings.create("agent"), Strings.create("skills"),
                    Strings.create("surstor"))),
            Strings.create("adapters"), adaptersConfig,
            Strings.create("modules"), Vectors.of(Strings.create(moduleJar.toString())));
    }

    private static Path findModuleJar() {
        // In development, the module jar is in surstor-adapter/target/
        Path devPath = Path.of("surstor-adapter/target/surstor-adapter-0.2.0-alpha.3-module.jar");
        if (Files.exists(devPath)) return devPath.toAbsolutePath();

        // When packaged, it should be alongside the CLI
        Path packagedPath = Path.of("modules/surstor-adapter-module.jar");
        if (Files.exists(packagedPath)) return packagedPath.toAbsolutePath();

        throw new IllegalStateException(
            "SurStor adapter module jar not found. Expected at " + devPath + " or " + packagedPath);
    }
}
