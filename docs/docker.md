# Run SurStor Memory in Docker

The container runs the SurStor node alongside your existing local model. It
includes the node, adapter and embedding model, not the LLM itself. This is
developer-preview packaging. The public image is
`ghcr.io/surstor/surstor-memory:0.2.0-alpha.3` (Linux amd64).
The publishing workflow tests standard storage, encrypted storage and the sync
listener before pushing each versioned image.

## Run the published image

Use the registry-only Compose file from this repository; no registry login is required:

```sh
docker compose -f compose.registry.yaml pull
docker compose -f compose.registry.yaml up -d
docker compose -f compose.registry.yaml ps
```

No Java, Maven, Covia build or LLM download is required on the host for this
path. For encrypted storage, set the protected key-file path as explained below
and use `compose.registry.encrypted.yaml` instead. Use the same file for stop,
start and down commands. These files deliberately contain no `build` directive.

## Build the image

Use Docker Engine 28+ with Compose v2, or a current Docker Desktop using Linux
containers. Start with Linux x86-64; other architectures have not been validated.
The builder also needs Java 21+, Maven and Git LFS. Complete the compatible
Covia `0.9.6-SNAPSHOT` prerequisite in the [local-model guide](local-models.md).
The snapshot is not yet pinned to an upstream commit: this packaging does not
claim a fully reproducible clean-machine source build.

From the repository root:

```sh
git lfs install
git lfs pull
mvn package
docker compose build
docker compose up -d
docker compose ps
```

Run each command only after the preceding command succeeds. The Dockerfile
packages the freshly built JARs; it does not download arbitrary Covia sources or
copy your Maven cache. Its allowlisted build context excludes Git history, keys,
local stores and private outputs. Rebuild the JARs and image after code changes.

The image runs as UID/GID 10001. Compose mounts a persistent volume at `/data`,
uses a read-only root filesystem and supplies writable temporary space for
native libraries and model extraction. Allow sufficient Docker memory for Java,
embeddings and the temporary files, separately from your LLM's memory needs.

## Connect your local model

- MCP: `http://127.0.0.1:8484/mcp`
- Capture review: `http://127.0.0.1:8484/capture`

Use the same [LM Studio configuration](local-models.md#2-load-a-local-model-and-connect-mcp).
Keep the client on the host for this recipe. Another container's localhost is
not the host, and the node's Host/Origin guard rejects arbitrary service names.
Keep host and container ports at 8484; changing only the published port will
also fail that guard. Stop any native node already occupying port 8484.

Inside the container the node binds `0.0.0.0` only because the image explicitly
passes `--allow-container-network`. Without that flag, native launches retain
the loopback-only restriction. Compose publishes **127.0.0.1:8484:8484**, not a
public interface. Do not use host networking, publish on all interfaces or add
untrusted containers to its network. The endpoint still trusts callers and has
no user authentication. Container networking is not an access-control layer.
Use a current Docker release; older releases have localhost-publishing caveats.
See [Docker port publishing](https://docs.docker.com/engine/network/port-publishing/).

## Encrypted storage alternative

The encrypted Compose file is a **standalone alternative**, not an override.
It uses a different project and volume so it cannot silently reuse plaintext
storage. Stop the standard service first (this preserves its volume):

```sh
docker compose down
```

Generate a random 32-byte key as 64 hexadecimal characters in a file outside
this repository and outside the data volume. For example on Linux/macOS:

```sh
mkdir -p "$HOME/.config/surstor"
chmod 700 "$HOME/.config/surstor"
umask 077
openssl rand -hex 32 > "$HOME/.config/surstor/etch.key"
export SURSTOR_KEY_FILE="$HOME/.config/surstor/etch.key"
```

Do not overwrite an existing key: losing or replacing it makes its store
unreadable. On Linux, file-based Compose secrets preserve host ownership; grant
container UID 10001 read access with a narrowly scoped ACL or matching ownership
before starting. Do not make the key world-readable. On Docker Desktop, check
its file-sharing permissions and restrict the host file to your account.
Keep a separately protected recovery copy.

In PowerShell, if you already have a protected key file, set its path with:

```powershell
$env:SURSTOR_KEY_FILE = 'C:/path/outside/repository/etch.key'
```

Then:

```sh
docker compose -f compose.encrypted.yaml config --quiet
docker compose -f compose.encrypted.yaml up -d --build
docker compose -f compose.encrypted.yaml ps
```

Compose mounts only the chosen file at `/run/secrets/etch-key`; the key is not
baked into the image or passed as an environment value. Compose file secrets
are bind mounts, not a host-side encrypted secret vault. Missing files, unreadable
keys, wrong keys and incompatible stores fail startup. Do not change encryption
settings against an existing volume. Read the [encryption guide](encrypted-storage.md)
and [Docker secrets documentation](https://docs.docker.com/compose/how-tos/use-secrets/).

Encrypted records support list/retrieve by ID. Semantic search remains disabled.
Signing identity files, exports and logs are outside Etch encryption; protect
Docker's storage and backups accordingly.

## Optional memory sync

Alpha.3 includes bidirectional selected-memory sync, disabled by default. Use
[the sync guide](node-sync.md) for group credentials, trusted signer keys and
session selection. Keep a separate signing identity and storage key per node.

1. Copy `docker/sync.example.toml` outside the repository. Set `enabled = true`
   under `[sync]`, fill `trusted_signers`, and select shared sessions. Leave
   `sync.bind = "0.0.0.0"` for Docker. This bind requires the container-network flag.
2. For an encrypted volume, preserve the `[encryption]` section from
   `docker/encrypted.toml` in your custom file; keep search disabled. Never apply
   an unencrypted configuration to an encrypted volume.
3. Set `SURSTOR_SYNC_CONFIG` to that complete TOML file and
   `SURSTOR_SYNC_TOKEN_FILE` to your protected 64-hex-character group token file.
   The config's token path must be `/run/secrets/sync-token`. Grant container UID
   10001 read access to both mounted files using a narrow ACL/ownership rule.
4. Use the overlay consistently with your existing profile:

   ```sh
   docker compose -f compose.registry.yaml -f compose.sync.yaml config --quiet
   docker compose -f compose.registry.yaml -f compose.sync.yaml pull
   docker compose -f compose.registry.yaml -f compose.sync.yaml up -d
   ```

   For encrypted storage substitute `compose.registry.encrypted.yaml` and keep
   `SURSTOR_KEY_FILE` configured. The overlay retains the base project's existing
   named data volume. It adds only `127.0.0.1:8485:8485` alongside local MCP.

A native laptop node can connect to a container on a remote server through
`ssh -N -L 18485:127.0.0.1:8485 user@server`, with the laptop peer set to
`http://127.0.0.1:18485`. One initiating connection exchanges records both ways;
the server may leave `peers = ""`.

For container-to-container connections across hosts, put a valid HTTPS proxy in
front of **only** the destination's dedicated sync listener, and configure that
HTTPS URL as the peer. A container's `127.0.0.1` is its own network namespace,
not the Docker host: do not point a container at a host SSH tunnel's localhost
port. Do not weaken the peer URL restriction or expose MCP to work around this.
Keep the credential header out of proxy logs and retain normal TLS checks.
Remote proxy/tunnel provisioning is not automated by Compose.

## Persistence, stopping and upgrades

```sh
docker compose stop
docker compose start
docker compose logs --tail 50
```

For encrypted storage, add `-f compose.encrypted.yaml` to every Compose command.
The Java process handles termination by closing the venue; Compose gives it
60 seconds before forced termination. A health check only confirms the HTTP
review route responds; it does not verify storage integrity or semantic search.

The named volume holds both records and the signing identity. Keep the same
Compose project name to reopen it. `docker compose down` preserves data;
**`docker compose down -v` deletes the volume**. Do not run two nodes against
the same volume. Stop the node before a consistent backup; back up the whole
volume and protect the encryption key separately. Verify restoration before
an upgrade and keep a rollback copy. Never copy a live Etch store as a backup.

## Acceptance checks

Before trusting a deployment:

1. Confirm `docker compose ps` reports healthy and only `127.0.0.1:8484` is published.
2. Run the synthetic save/retrieve/verify exercise in the local-model guide.
3. Restart the container, then retrieve and verify the same record ID.
4. For standard storage, search for the saved text after restart.
5. For encrypted storage, confirm search is disabled and the record survives restart.
6. With the encrypted service stopped, verify missing/wrong-key startup fails;
   restore the original key before proceeding. Use disposable test volumes for this check.

Docker was not installed on the development workstation. GitHub Actions on
Ubuntu successfully built the image and tested standard/encrypted startup,
MCP save/retrieve/verify, persistence across restart, text search in the standard
profile and absence of a plaintext search index in the encrypted profile.
Missing/wrong-key behavior is covered by Java tests; the container smoke test
does not yet exercise those negative cases. Docker Desktop and ARM64 have not
been tested. Anonymous registry manifest access was verified after publication.

## Maintainer publication

`.github/workflows/container.yml` runs for published releases and manual release-tag
dispatches. It downloads the two maintainer-built JAR assets from that release,
checks their SHA-256 digests against `docker/release-inputs.json` in the tag,
builds on Linux amd64, and runs disposable standard/encrypted container tests
before pushing the versioned image. It uses the repository's short-lived
`GITHUB_TOKEN` with `packages: write`; no registry password belongs in source.

This is an artifact-based build, not a reproducible build from upstream source:
the compatible Covia snapshot is still supplied by the maintainer's Maven build.
Do not replace the release JARs or reuse a published image version. For a new
release, update version references, run `mvn package`, record the actual JAR
hashes, commit and tag, attach both JARs to a draft release, then publish it.
Public source does not automatically imply public package visibility: set the
GHCR package to public and verify an anonymous pull before announcing availability.
