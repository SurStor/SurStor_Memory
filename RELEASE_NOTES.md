# v0.2.0-alpha.3-mcp.1 — current installation package

- Current public Docker/MCP package: `ghcr.io/surstor/surstor-memory:0.2.0-alpha.3-mcp.1`.
- Published as `io.github.SurStor/surstor-memory` in the Official MCP Registry.
- Adds MCP ownership metadata to the tested alpha.3 image; application binaries
  and behavior remain alpha.3. This is a packaging revision, not a new app release.
- Standard and encrypted registry Compose recipes use this same image tag.
- Standard storage remains unencrypted; encrypted storage is a separate opt-in profile.

The versions below are historical release notes, not current installation targets.

# v0.2.0-alpha.3 — selected-memory synchronization

- Add opt-in, authenticated, bidirectional record sync between trusted single-user
  nodes, using Convex CAS merge and existing Covia storage. Separate loopback sync
  listener; remote use requires SSH tunneling or HTTPS termination.
- Preserve original signatures/IDs, allow relaying, validate scope and privacy
  before import/export, and keep local signing/encryption keys independent.
- Expose complete remote chains as read-only session views; preserve forks.
  Global cost totals now explicitly cover local sessions only.
- Serialize local stores to avoid simultaneous capture losing a session head.
- See [node sync](docs/node-sync.md) for configuration, limits and validation.
- Include sync in the alpha.3 image and JARs. Optional Compose overlay mounts
  configuration and a protected token; host ports remain localhost-only.

# v0.2.0-alpha.2 — Docker Developer Preview

- Docker packaging with a non-root Java runtime and persistent data volumes.
- Localhost-only host port publishing and explicit container network opt-in.
- Separate encrypted profile using a file-mounted key; semantic search stays disabled.
- Graceful venue shutdown on container termination.
- Registry-only Compose files for installations without Java or Maven.
- GHCR publication workflow with release-JAR checksum verification and Linux container tests before pushing.

The local Java build passed 71 tests. See the container workflow for the separate
Linux runtime test results and registry publication status. The image target is
`ghcr.io/surstor/surstor-memory:0.2.0-alpha.2` for Linux amd64.

## Build provenance and limitations

This release packages maintainer-built JARs with committed SHA-256 checksums;
it does not yet rebuild the compatible Covia snapshot from pinned upstream
source in CI. The node remains single-user and requires trusted local clients.
The image is not a hosted multi-user service. Read [the Docker guide](docs/docker.md)
for installation, encryption keys, persistence and limitations.
