# INTEGRATION_NOTES.md

## Billing v2 (September 2026)

See [docs/billing.md](docs/billing.md) for the complete contract and official
price-source catalogue. New Cell 2 records carry a signed billing map. Existing
cells and the August placeholder schedule retain their hashes and v1 arithmetic.
The exact placeholder active schedule upgrades on read; customized schedules do
not. The export envelope remains `surstor-export/1`, but v2 billing needs the
updated verifier. Both verifiers check the cited schedule hash and replay the
same billing implementation. They do not certify provider invoice accuracy.

Callers must normalize usage, select a service tier and supply actual request
time for time-dependent rates. `input-mode: total` subtracts read/write cache
counts; default `exclusive` preserves the legacy input convention. Missing or
unsupported charges produce an unpriced record and no total, not a zero price.
Client UIs should display the returned warning and pricing-coverage information.
The old aggregate `byModel`/`tokens` placeholders were never computed and are now
omitted instead of reporting misleading empty/zero values.

## Upstream API surprises and deviations from spec

### Capture ingress and Covia job persistence

The node adds capture routes under `/api/v1/capture/*`, marked with the installed
Covia `VenueRouteFeature.COVIA_API` role for identity/admission middleware. The
installed SNAPSHOT uses matched route roles, not just URL prefixes; older source
checkouts may show the previous prefix-based middleware. A global HTTP before-handler checks JSON request bodies
before REST/MCP operation handlers can persist job inputs. Preview bypasses
JobManager and holds only filtered candidates in memory; commit invokes the
adapter directly and explicitly syncs state. Filtering inside `surstor:store`
alone is insufficient for embedded callers that submit raw input to JobManager.
See [the privacy boundary and compatibility notes](docs/prompt-capture.md).

### 1. Default installation dependency: Covia `0.9.6-SNAPSHOT`

**Installation policy:** Covia `0.9.6-SNAPSHOT` is the selected default for
SurStor installations. The root `pom.xml` supplies this version to both
`ai.covia:covia-core` and `ai.covia:venue`; no user override is required.
This supersedes the earlier installation goal of using only released artifacts
from Maven Central.

**Finding:** `ai.covia` group artifacts (`covia-core`, `covia-venue`) are **not published** on Maven Central. Searched `https://search.maven.org/solrsearch/select?q=g:ai.covia` — zero results. Only `world.convex` artifacts are available on Central.

**Resolution:** Build a compatible Covia source checkout (`mvn clean install
-DskipTests`) and use the locally-installed `0.9.6-SNAPSHOT` artifacts. A fresh
installation needs this step before building SurStor. Record the upstream commit
used; the snapshot version alone does not guarantee identical source or APIs.

**Impact:**
- Local builds require a Covia source checkout
- CI/CD must either build Covia first or host a private Maven repo with the SNAPSHOTs
- Keep `0.9.6-SNAPSHOT` as the default until an explicit dependency upgrade is tested and adopted. For reproducible releases, also pin the upstream source commit.

### 2. Covia build requires `read:org` scope for `gh auth login`

Not directly a build issue, but worth noting: the `gh` CLI refused to log in with a classic token lacking `read:org`, even for non-org operations. Used direct `git` with token in URL instead.

### 3. Adapter framework conventions (from `venue/docs/ADAPTERS.md`)

The Covia adapter API requires:
- Extending `covia.adapter.AAdapter`
- Implementing `getName()`, `getDescription()`, `invokeFuture()`
- Installing operations via `installAsset(catalogPath, resourcePath)` where catalog paths match `[a-z][a-z0-9-]*`
- Using `engine.requireAuthority()` for capability checks
- Using `adapterWorkspace()` for adapter-owned durable state

This matches the spec's expectations. No adaptation needed.

### 4. Venue executable JAR location

`venue/target/covia.jar` is a fat JAR containing all dependencies. The spec assumes a `covia-core` dependency; the actual artifact names are `ai.covia:covia-core` and `ai.covia:venue` (see parent POM).

### 5. MCP tool exposure requires `mcp.includeAdapters`

The venue MCP server only exposes adapter groups in `mcp.includeAdapters`
(default: `covia, grid, asset, secret, agent, skills`). A custom adapter's ops
are invisible to MCP clients until its group is added. The node sets
`includeAdapters: [defaults..., "surstor"]` in the venue config
(`Main.buildVenueConfig`). Tool names derive from `operation.adapter` with
`:` sanitised to `_`.

### 6. Anonymous callers are read-only by default

Without UCAN auth, callers get `crud/read` on the public scope only — invokes
fail with `Capability denied`. The node sets `auth.public.caps: unrestricted`
(`Main.buildVenueConfig`), which is safe **only** because the venue binds
`127.0.0.1`. Never widen the bind address with this setting in place.

### 7. JSON round-trip changes CAD3 hashes (load-bearing)

`AssetStore.store` hashes the **JSON-parsed** metadata map. Convex
`JSON.parse` does not restore Keywords (`:text` → `"text"`), Hash values
(`0x…` → string), or Keyword keys (→ string keys). Any cell built with typed
values has a different hash in memory than in the store — the artifact ID
breaks, and records come back unresolvable by their own ID.

**Rule (enforced by `CellsTest.jsonRoundTripPreservesHash`):** cells, session
records, and schedules are JSON-native — `AString` keys, string content types,
hashes as `"0x..."` hex strings. Never put Keyword/Hash values into a stored
record.

### 8. VenueServer owns the store wiring

`Engine` does not read the `"store"` config key; etch persistence is wired in
`VenueServer.createStore`. Engine-level persistence tests must launch
`VenueServer` (see `RestartPersistenceTest`), not `Engine.createTemp`.

### Selected-memory sync bridge (unreleased)

The installed Covia 0.9.6-SNAPSHOT constructs its NodeServer with port(-1).
Its venue value uses single-authoritative-writer, whole-value LWW semantics.
SurStor therefore keeps venue identities independent and reuses CASLattice for
selected immutable record union, with a separate authenticated HTTP transport.
This does not enable the native lattice wire protocol. Incoming cells are
registered in the local single-user public asset namespace after signature,
billing and privacy verification, without JobManager input persistence.
Read-only derived session aliases are adapter-workspace state; no shared root,
private keys, secrets, or jobs are exposed. See docs/node-sync.md.
