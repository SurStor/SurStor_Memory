# Bug Fix Brief — SurStor `--data-dir` override ignored for identity keypair

## Task

Fix a startup crash / state-splitting bug in the SurStor node CLI. Work in the
`surstor-node` module only.

## The bug

`Main.StartCommand.call()` computes the effective data directory like this:

```java
Path dataDir = (dataDirOverride != null) ? dataDirOverride : cfg.dataDir();
```

and passes it to `bootstrapIdentity(dataDir, cfg)`. But `bootstrapIdentity`
then ignores that parameter when locating the identity file:

```java
// Main.java, bootstrapIdentity — CURRENT (broken)
Path keyFile = cfg.keypairFile();   // resolves against cfg.dataDir() from the TOML, NOT the -d override
Files.createDirectories(dataDir);   // creates the OVERRIDE dir, not the configured one
```

`NodeConfig.keypairFile()` returns `dataDir().resolve(keypair_file)` where
`dataDir()` comes from the TOML (default `~/.surstor-node`).

**Consequences:**
- If the configured dir (e.g. `~/.surstor-node`) does not exist, startup
  crashes with `java.nio.file.NoSuchFileException: .../identity.ed25519` at
  `Files.writeString` (Main.java:164), because only the override dir was created.
- If it does exist, the identity keypair is written to the configured dir while
  the etch store goes to the override dir — node state silently splits across
  two directories, and restarting with the same `-d` loses the identity.

## Reproduce

```bash
mvn package -DskipTests
rm -rf ~/.surstor-node /tmp/node-data
java -jar surstor-node/target/surstor-node.jar start -c config/surstor.toml -d /tmp/node-data
# => NoSuchFileException: /home/<user>/.surstor-node/identity.ed25519
```

## Required fix

In `bootstrapIdentity`, resolve the keypair **filename** against the effective
data dir passed in:

```java
private static AKeyPair bootstrapIdentity(Path dataDir, NodeConfig cfg) throws IOException {
    Path keyFile = dataDir.resolve(cfg.keypairFile().getFileName().toString());
    Files.createDirectories(dataDir);
    // ... rest unchanged
}
```

This preserves the configurable filename (`identity.ed25519`) while anchoring
it to the directory the node actually uses. Keep the existing
`rw-------` POSIX permission hardening.

## Acceptance criteria

1. The reproduction steps above succeed: node starts, and
   `/tmp/node-data/` contains both `identity.ed25519` and `etch/`.
2. Starting without `-d` still uses `~/.surstor-node` exactly as before
   (backward compatible).
3. Add a regression test in `surstor-node` (e.g. `DataDirOverrideTest`):
   invoke `start` with `-d <tempdir>` and assert the identity file exists
   under `<tempdir>`, not under the configured default. `bootstrapIdentity`
   is `private static` — either test through the CLI entry point on a random
   free port and shut it down, or relax visibility to package-private for
   testability. Follow the existing test style (JUnit 6, see
   `RestartPersistenceTest` in surstor-adapter for the VenueServer pattern).
4. `mvn test` passes: 30 existing tests + your new one, 0 failures.

## Build environment (must be set up first)

- **Java 21+** and **Maven 3.7+** required.
- Dependencies `ai.covia:covia-core` and `ai.covia:venue` version
  `0.9.6-SNAPSHOT` are NOT on Maven Central. Build them from source first:
  ```bash
  git clone https://github.com/covia-ai/covia.git
  cd covia && git checkout c833a444   # last 0.9.6-SNAPSHOT commit (parent of tag 0.9.6)
  mvn install -DskipTests -pl covia-core,venue -am
  ```
- The surstor-adapter integration-test config expects a `covia-ref` directory
  next to the repo root containing the Covia checkout (with
  `venue/target/covia.jar` built). Symlink it: `ln -s /path/to/covia covia-ref`.

## Out of scope (do NOT touch)

- The `auth.public.caps` hardcoding in `buildVenueConfig` — that is a separate
  hardening change already covered by `auth-hardening.patch`.
- `StatusCommand` also reads `cfg.keypairFile()` and has no `-d` option, so
  `status` reports identity presence for the configured dir only. Acceptable
  for now; optional follow-up: add a `-d` flag to `status` for consistency.

## Note

A tested reference implementation of this fix (plus the auth hardening) exists
in `surstor-deploy/auth-hardening.patch` — the relevant hunk is the
`bootstrapIdentity` change in `Main.java`. You may consult it, but implement
and test the fix as specified above.
