# v0.2.0-alpha.1 — Developer Preview

First public alpha of SurStor Memory, an external memory node for local models.

- Store, retrieve and verify signed AI-work records through MCP.
- Local semantic search over text records in the unencrypted profile.
- Optional encrypted venue storage with session/ID retrieval; semantic search is disabled in this profile.
- Prompt/response privacy filtering and a local review screen.
- Portable session exports with offline verification.

## Installation and limitations

See [the local-model guide](docs/local-models.md). A compatible Covia
`0.9.6-SNAPSHOT` source build is required. Installation currently requires
Java 21+, Maven and Git LFS; no prebuilt release binaries are supplied.
The LM Studio walkthrough has not been tested end to end.

This is a single-user, loopback-only developer preview. It does not provide
user accounts, automatic memory supersession or configured node-to-node sync.
Review the README and privacy documentation before use. APIs and storage
behavior may change between alpha releases.
