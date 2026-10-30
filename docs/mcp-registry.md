# Official MCP Registry publication

Registry name: `io.github.SurStor/surstor-memory`.

The root `server.json`, primary Dockerfile and `docker/registry.Dockerfile`
use this same ownership identity. Publication uses the manually dispatched
`Publish MCP Registry` GitHub Actions workflow on `main`, authenticating through
GitHub OIDC without storing a long-lived registry token.

## Package

`0.2.0-alpha.3-mcp.1` is a registry packaging revision of the alpha.3 runtime.
The dedicated Dockerfile starts from the published alpha.3 image digest and
adds the registry ownership label. It does not change the application binaries
or overwrite the original alpha.3 image. The image is Linux amd64.

The workflow tests standard and encrypted MCP storage/retrieval, persistence,
search and encrypted sync before pushing the image and publishing the listing.
It verifies the pinned publisher download using its Sigstore bundle.

The package uses Streamable HTTP at `http://127.0.0.1:8484/mcp`, not stdio.
Runtime arguments publish the port on loopback only, persist records in the
`surstor-mcp-memory` Docker volume, and use a read-only filesystem, temporary
scratch space and reduced container privileges. Only one instance can bind
this port at a time. A client must support launching HTTP-based OCI packages;
otherwise use the manual Docker installation and configure the MCP URL.

This listing uses the standard profile, which does not encrypt stored records.
For encryption, use the documented encrypted Compose setup and connect to its
local MCP URL. Local processes are trusted by the unauthenticated local endpoint.
Do not expose it as a public remote server. See [MCP setup](mcp-setup.md).

A workflow file alone does not establish publication. Confirm a successful
workflow run and query the registry API for the exact name and version:

https://registry.modelcontextprotocol.io/v0.1/servers?search=io.github.SurStor/surstor-memory

Official references:
- https://github.com/modelcontextprotocol/registry/blob/main/docs/modelcontextprotocol-io/github-actions.mdx
- https://github.com/modelcontextprotocol/registry/blob/main/docs/modelcontextprotocol-io/package-types.mdx
