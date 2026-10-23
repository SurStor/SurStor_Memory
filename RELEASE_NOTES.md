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
