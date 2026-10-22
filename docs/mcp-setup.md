# MCP setup

For the full local-model installation and save/recall walkthrough, start with
[Local memory for your own models](local-models.md). MCP connects SurStor to
your chat or agent host; that host runs tool calls on behalf of the local model.

The SurStor node exposes its operations over MCP (Model Context Protocol) at:

```
http://127.0.0.1:8484/mcp
```

Transport: streamable HTTP, JSON-RPC 2.0, protocol version `2025-03-26`.
No authentication — the node binds loopback only and treats local callers as
trusted (`auth.public.caps: unrestricted`). Keep the node on loopback; this is
not an authenticated multi-user endpoint. Other local processes can access it.

## Client configuration

Use a host supporting Streamable HTTP MCP and your chosen local model.
For LM Studio, merge this into its `mcp.json` through **Program → Install →
Edit mcp.json**:

```json
{
  "mcpServers": {
    "surstor": {
      "url": "http://127.0.0.1:8484/mcp"
    }
  }
}
```

Other clients have their own configuration formats; do not assume this JSON
works unchanged in every host. See [LM Studio's instructions](https://lmstudio.ai/docs/app/mcp)
and the [ready-to-copy config](../config/mcp/lm-studio.json).
Once connected, `tools/list` returns the twelve `surstor_*`
tools alongside the venue's built-in tools (`asset_*`, `grid_*`, `covia_*`,
etc.). Two of them power local semantic search:

- `surstor_search` — natural-language query → ranked artifact IDs with
  scores and snippets (resolve full content via `surstor_retrieve`)
- `surstor_reindex` — maintenance operation to rebuild the derived vector index
  from the Etch store when semantic search is enabled

Search runs on-node (bundled multilingual MiniLM + Lucene HNSW) without an
embedding API. Only string content is indexed. The encrypted profile disables
semantic search: use `surstor_list` and `surstor_retrieve` instead.
Returned content goes to the host and its model; local inference and appropriate
host settings are needed to keep it on your computer.

For normal memory use, enable store, search, list, retrieve and verify where the
client supports tool selection. Administrative tools are not needed. Direct
MCP stores are filtered but do not automatically open a human review screen;
see [privacy and capture review](prompt-capture.md).

## How exposure works

The venue's MCP server builds its tool registry from adapter operation
catalogs. The SurStor adapter installs its operations under
`v/ops/surstor/*`; the node config includes `surstor` in
`mcp.includeAdapters` (Covia's default list doesn't include custom adapters).
Tool names derive from `operation.adapter` with `:` sanitised to `_`
(`surstor:store` → `surstor_store`).

## Protocol and tool calls

Let an MCP SDK or compatible host handle initialization: send `initialize`
with client information and capabilities, negotiate the protocol, then send
`notifications/initialized` before discovery and tool calls. HTTP requests use
`Content-Type: application/json` and accept both `application/json` and
`text/event-stream`. Preserve any `Mcp-Session-Id` issued by the server and send
the negotiated `MCP-Protocol-Version` on subsequent requests.

After initialization, this is a `tools/call` request body (not a complete
standalone client):

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "surstor_store",
    "arguments": {
      "content": "Project Orchard uses SQLite. Timestamps are UTC.",
      "contentType": "text",
      "sessionId": "local-memory-demo"
    }
  }
}
```

The operation payload contains `id`, `shortId` and `cell1Id`, inside the MCP
JSON-RPC result envelope. Read the host/SDK's decoded tool result, including
errors and returned warnings; do not treat a model's confirmation as proof of
storage. Then call `surstor_retrieve` with that `id` and `surstor_verify` with
the same `id` plus `"deep": true`. Verification checks integrity, not factual
accuracy. The [fresh-chat exercise](local-models.md#4-recall-it-in-a-fresh-conversation)
tests memory recall through your actual client.
