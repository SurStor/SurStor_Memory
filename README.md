# SurStor Memory — memory for models you run yourself

**v0.2.0-alpha.1 · Developer Preview**

Early software for testing and feedback. Features and interfaces may change;
review the limitations below before storing important information.

Licensed under the [Apache License 2.0](LICENSE). Third-party dependencies and
bundled assets remain subject to their respective licenses.

Keep project decisions, useful answers and work history on your own hardware,
then let your local AI retrieve them across conversations. SurStor Node (v0.2.0-alpha.1)
provides persistent, inspectable external memory through MCP. Your model stays
in your chosen inference application; SurStor stores and retrieves its memories.

**[Start here: install alongside a local model and connect MCP](docs/local-models.md)**
— includes an LM Studio setup, a save-and-recall example, and Ollama integration
requirements. Keep using your tools as always.

```text
Local model <-> MCP-capable chat or agent application
                         |
                  SurStor MCP (localhost:8484)
                         |
              Local records + optional search index
```

- Save approved facts, preferences, decisions and handoff notes as text memories.
- Find relevant past work through local semantic search, or retrieve by record ID.
- Inspect provenance, verify record integrity and export sessions for reuse.
- Reuse the same node with another local model through a compatible MCP host.

SurStor is a single-user node built on [Covia](https://github.com/covia-ai/covia)
venues, Convex lattice and CAD3 content addressing, without running Convex
consensus. It binds to `127.0.0.1:8484`. It does not train your model, automatically
capture every conversation, or automatically resolve conflicting memories.
The host must call its tools. Retrieving a few relevant records can avoid
repeating entire histories; token savings and answer quality need measurement
for your workload.

For a fresh encrypted venue, use [the encrypted-storage guide](docs/encrypted-storage.md)
and `config/surstor-encrypted.toml`. This opt-in profile encrypts Etch records and
its index, requires an external key, and disables the separate plaintext semantic
search index. Existing configurations do not automatically become encrypted.

## What it does

Memory has an evidence trail: you can retrieve the original saved content and
check its integrity. A valid signature does not establish that a saved claim is
true. Save concise, useful context rather than treating all generated text as fact.

Every AI work artifact (draft, tool result, patch, model output) is stored as
a **two-cell CAD3 record**:

- **Cell 1** — the content, content-addressed (SHA3-256 of canonical encoding)
- **Cell 2** — provenance metadata: `branch-ref` (Cell 1 hash, the Merkle
  binding), session ID, agent ID, model version, timestamp, token usage,
  versioned billing breakdown + estimated cost + cited price schedule,
  `prev-event` (session hash chain),
  and the **node's Ed25519 signature** over the unsigned cell hash

Sessions form append-only hash chains (`prev-event` links), so a rewritten or
dropped event is detectable. Costs are computed at capture time against a
versioned price schedule and frozen into the record — historical artifacts
are never repriced. Verification replays the original calculation and schedule.

**Cost warning:** SurStor estimates API costs from a dated snapshot of public
provider prices and caller-supplied usage. These are not provider invoices.
Unreported charges, taxes, credits, subscriptions and negotiated discounts are
excluded. Unpriced records are excluded from totals and counted separately;
legacy sessions may have unknown coverage. Verification proves integrity and
arithmetic, not billing accuracy. The session budget is not a provider spending cap.

The September 10, 2026 snapshot covers 31 major OpenAI, Anthropic, Kimi, Gemini,
DeepSeek and Grok model entries. New billing supports cache writes, cache storage,
tool charges, service tiers, context thresholds and time-dependent rates.
See **[Billing and pricing documentation](docs/billing.md)** for official sources,
rates, caller examples, exclusions, migration and verification behavior.

## Build & run

Requires Java 21+ and Maven 3.7+. **Covia `0.9.6-SNAPSHOT` is the default
installation dependency**, configured by `covia.version` in the root `pom.xml`.
No Maven version override is needed.

Before building SurStor on a fresh machine, build and install a compatible Covia
source checkout that produces `ai.covia:covia-core:0.9.6-SNAPSHOT` and
`ai.covia:venue:0.9.6-SNAPSHOT` into the local Maven repository:

```bash
# Run inside the compatible Covia source checkout first.
mvn clean install -DskipTests
```

Then run the commands below from the SurStor repository root. The snapshot
version is the default, but does not identify an immutable upstream revision;
record the Covia commit used and run SurStor's tests against that build.
See [integration notes](INTEGRATION_NOTES.md) for dependency compatibility.

Prompt-and-response capture now has a local review screen at `/capture`, built-in
secret detection, organisation exclusions and fail-closed filtering. Only the
filtered version is recorded. Automated detection can miss sensitive data;
review every field before saving. See **[Prompt capture and privacy](docs/prompt-capture.md)**
for configuration, API usage, compatibility changes and protection limits.

```bash
mvn package
java -jar surstor-node/target/surstor-node.jar start --config config/surstor.toml
```

The example above uses the unencrypted profile. For encrypted installation,
follow [the encrypted-storage guide](docs/encrypted-storage.md).
The node persists to `~/.surstor-node/` with the example profile (etch store +
Ed25519 identity keypair, auto-generated on first run).

Other commands:

```bash
java -jar surstor-node.jar status                 # config, identity, budget
java -jar surstor-node.jar verify <bundle.json>   # offline verification of an export bundle
```

## MCP operations

All twelve operations are exposed as MCP tools at `http://127.0.0.1:8484/mcp`
(`tools/list`), plus the venue's built-in tool set:

| Tool | Purpose |
|---|---|
| `surstor_store` | Store an artifact (content, sessionId, agentId, modelVersion, usage) |
| `surstor_retrieve` | Fetch content + full provenance metadata |
| `surstor_verify` | Check branch-ref, session chain (deep), schedule, signature |
| `surstor_list` | List artifacts, optionally by session |
| `surstor_diff` | Unified diff between two text artifacts |
| `surstor_cost` | Cost rollup for a session or all sessions |
| `surstor_sessions` | List sessions with chain head, counts, totals |
| `surstor_export` | Export a session as a portable, offline-verifiable JSON bundle |
| `surstor_prices_get` | Show the active price schedule |
| `surstor_prices_put` | Install a new price schedule (future costs only) |
| `surstor_search` | Local semantic search: ranked artifact IDs + snippets for a natural-language query |
| `surstor_reindex` | Rebuild the search index from the etch store (wipe + replay) |

The node binds loopback only; anonymous local callers get full access
(`auth.public.caps: unrestricted`). Loopback limits network exposure, but other
local processes can still access the node. Use only trusted clients and keep the
bind local. See [MCP setup](docs/mcp-setup.md) for client configuration.

## Tamper evidence

- Byte-flip a content cell → `branchRef` check fails
- Rewrite a middle event → the session head (recorded at capture time) never
  cites the forgery; the honest chain still verifies
- Drop an event → deep verify / offline bundle verification reports the chain gap

See `docs/tamper-demo.md` for a walkthrough. Offline bundles
(`surstor_export`) verify with no venue: `surstor-node verify bundle.json`
recomputes every hash and signature from the file alone (exit 0 = valid).

## Tests

```bash
mvn test
```

Tests cover cell/unit semantics, JSON round-trip hash stability, engine-backed
tamper scenarios, budget enforcement, real server-restart persistence,
versioned billing and tariff boundaries, offline export verification,
embedding determinism/similarity, Lucene index
behaviour, and engine-backed semantic search (store→search→reindex).

## Local semantic search (v0.2.0-alpha.1)

`surstor_search` ranks stored artifacts by meaning, not just by ID — e.g.
"when is the project due" finds the client-deadline email. Everything runs
**on-node**: a bundled multilingual MiniLM model (ONNX, via DJL + ONNX
Runtime) embeds text on CPU, and a Lucene HNSW vector index under the data
dir answers queries. The embedding/search path makes no external API calls.
Retrieved content is returned to the client: keeping inference local also
requires a local model and no cloud fallback or external forwarding by the host.

Only string-content records are indexed. Save concise memories as `contentType:
"text"` with a string `content`; structured prompt/response objects are not
semantically indexed. The encrypted-storage profile disables semantic search;
use session listing and retrieval by ID instead.

The two-cell CAD3 record remains the only source of truth. Embeddings and
the index are derived, unsigned, disposable data: if `search-index/` is
deleted, `surstor_reindex` rebuilds it from the etch store with zero
information loss. Indexing failure never fails a store.

Config (defaults shown):

```toml
[search]
enabled = "true"
k_default = "10"
```

Note: the adapter module jar grows by ~190 MB (ONNX native binaries +
bundled model). Accepted for v0.2.

## Caveats (v0.2.0-alpha.1)

- The current price schedule is a dated estimate snapshot, not a live price feed.
  The original August placeholder file is retained unchanged for historical
  verification; it is no longer the default. Only an exact match to the old
  built-in placeholder is automatically upgraded. Custom schedules are preserved.
- Budget enforcement is per session (`session-budget-usd` in adapter config,
  default 5.00)
- Artifacts stored before signature support was added fail the `signature`
  check. Use a separate fresh data directory for a new installation; preserve
  any existing records you need.
- Local model identifiers without a matching API tariff remain unpriced, not
  zero-cost. SurStor does not estimate electricity or hardware costs.
- Automatic memory supersession, a user-facing deletion workflow, multi-user
  isolation and node-to-node synchronization are not provided by this release.
