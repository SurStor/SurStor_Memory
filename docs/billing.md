# Billing, price sources, and estimate limitations

## User warning

**SurStor reports estimated API costs, not provider invoices.** Prices are a
dated snapshot transcribed from public provider documentation, or a schedule
installed by the node operator. Usage is supplied by the caller. Neither is
authenticated by the provider. Only supplied, supported charges are calculated.
Taxes, credits, subscriptions, negotiated discounts, and unreported usage are
excluded. Model aliases and prices can change after the snapshot was checked.

A passing verification proves that the record is intact and that its arithmetic
matches its cited schedule. It does **not** prove that the provider charged that
amount, that the usage is accurate, or that the schedule is authoritative.

Totals exclude unpriced records. A session budget limits which estimated-cost
records SurStor will accept; it does not stop provider requests or cap an API
account's spending. The request may already have incurred a charge before it is
stored. Do not use a SurStor total as an invoice or as the sole spending control.

The MCP store, retrieve, cost, sessions, price, export, and verify responses expose
a `warning` string. Clients should display it alongside costs, including when
the total is zero. Each new billing record also carries the warning inside its
signed metadata. `unpricedCount` in cost/session results counts records without a
calculated cost; `null` means historical coverage is unknown for a legacy session.
An estimated record can still omit charges that the caller did not report.

## Snapshot and coverage

The default schedule is
[`seed-2026-09-10.json`](../surstor-adapter/src/main/resources/pricing/seed-2026-09-10.json).
It contains **31 provider-prefixed model entries and 101 tariffs**, reviewed on
September 10, 2026. `_checked-at` is recorded at UTC day precision (midnight is
the date marker, not a claim about the exact retrieval time). `_review-after`
recommends another review by October 10; it is not a provider expiry date or an
automatic refresh. Known announced changes have explicit tariff time bounds.

This is a selected catalogue of major chat, reasoning, and coding APIs. It is
not every model, every modality, or every regional/enterprise contract. OpenAI
prices here are for the **API**, not ChatGPT Plus/Pro or Codex subscription
allowances. The same distinction applies to Claude, Kimi and Grok memberships.
The provider prefix is a SurStor naming convention; the actual API ID follows
the slash. Use exact catalogue keys, not display names or guessed aliases.

### Official sources

| Provider / prefix | Source used | Included models |
|---|---|---|
| OpenAI / `openai/` | [API pricing](https://developers.openai.com/api/docs/pricing), individual [GPT-5](https://developers.openai.com/api/docs/models/gpt-5), [GPT-5 mini](https://developers.openai.com/api/docs/models/gpt-5-mini), [GPT-4o](https://developers.openai.com/api/docs/models/gpt-4o) pages | GPT-6 Astra; GPT-5.6 Sol, Terra, Luna; GPT-5, GPT-5 mini, GPT-4o |
| Anthropic / `anthropic/` | [Claude pricing](https://platform.claude.com/docs/en/about-claude/pricing), [model IDs](https://platform.claude.com/docs/en/models/overview) | Fable 5.1/5; Opus 5/4.8/4.7/4.6; Sonnet 5/4.6; Haiku 4.5 |
| Kimi / `moonshot/` | [Official platform model cards](https://platform.kimi.ai/), [K3 announcement](https://www.kimi.ai/blog/kimi-k3), [pricing index](https://platform.kimi.ai/docs/pricing/chat) | K3, K2.7 Code, K2.6 |
| Google / `google/` | [Gemini API pricing](https://ai.google.dev/gemini-api/docs/pricing) | Gemini 3.8 Flash, 3.7 Flash, 3.5 Flash-Lite, 3.1 Pro Preview, 2.5 Pro |
| DeepSeek / `deepseek/` | [Models and pricing](https://api-docs.deepseek.com/quick_start/pricing/) | deepseek-flash, deepseek-v4-flash alias, deepseek-v4-pro |
| xAI / `xai/` | [API pricing](https://docs.x.ai/developers/pricing) | Grok 4.6, 4.5, 4.3, grok-build-0.1 |

Every model entry includes its `source-url` and scope notes. Kimi's individual
documentation tables did not render in either the documentation reader or the
browser during review. Its official platform landing page did expose all three
model rate cards; K3's announcement independently corroborated its rates. No
reseller or third-party comparison prices were substituted.

### Standard short-context token prices

USD per million tokens. Columns are ordinary input, cache read, and output.
This compact table omits the additional charges and conditions encoded in the JSON.

| Model (prefix as above) | Input | Cache read | Output |
|---|---:|---:|---:|
| gpt-6-astra | 10 | 1 | 50 |
| gpt-5.6-sol | 4 | 0.40 | 20 |
| gpt-5.6-terra | 2 | 0.20 | 12 |
| gpt-5.6-luna | 0.20 | 0.02 | 1.20 |
| gpt-5 | 1.25 | 0.125 | 10 |
| gpt-5-mini | 0.25 | 0.025 | 2 |
| gpt-4o | 2.50 | 1.25 | 10 |
| claude-fable-5-1 | 10 | 0.25 | 50 |
| claude-fable-5 | 10 | 1 | 50 |
| claude-opus-5 / 4-8 / 4-7 / 4-6 | 5 | 0.50 | 25 |
| claude-sonnet-5 | 2 | 0.20 | 10 |
| claude-sonnet-4-6 | 3 | 0.30 | 15 |
| claude-haiku-4-5 | 1 | 0.10 | 5 |
| kimi-k3 | 3 | 0.30 | 15 |
| kimi-k2.7-code | 0.95 | 0.19 | 4 |
| kimi-k2.6 | 0.95 | 0.16 | 4 |
| gemini-3.8-flash / gemini-3.7-flash | 0.75 | 0.075 | 3.75 |
| gemini-3.5-flash-lite | 0.30 | 0.03 | 2.50 |
| gemini-3.1-pro-preview | 2 | 0.20 | 12 |
| gemini-2.5-pro | 1.25 | 0.125 | 10 |
| deepseek-flash / deepseek-v4-flash (off-peak) | 0.15 | 0.003 | 0.60 |
| deepseek-v4-pro (off-peak, before routing change) | 0.66 | 0.022 | 1.98 |
| grok-4.6 | 2 | 0.50 | 6 |
| grok-4.5 | 2 | 0.30 | 6 |
| grok-4.3 | 1.25 | 0.20 | 2.50 |
| grok-build-0.1 | 1 | 0.20 | 2 |

### Conditions and scope decisions

- **OpenAI:** Astra and GPT-5.6 include standard, batch, flex, and fast tariffs;
  input above 272,000 selects the published long-context rates for the whole
  request. Cache writes have their own replacement category. Older models in
  this snapshot have standard tariffs only. Regional uplifts are not applied.
  Sol's listed price is promotional at least through November 21, 2026; no
  unannounced post-promotion price is invented. See the linked API pricing page
  and [cache accounting formula](https://developers.openai.com/api/docs/guides/prompt-caching).
- **Anthropic:** standard and batch token tariffs include both cache-write
  durations. Web search is a separate charge. US-only routing, fast inference,
  marketplace billing and other tools require an operator-supplied tariff.
  Sonnet 5's cancelled September price increase is not applied. The current
  standard price is used. See [Claude pricing](https://platform.claude.com/docs/en/about-claude/pricing).
- **Kimi:** standard direct API token rates. Batch and tools are not silently
  inferred. The prefix is `moonshot/`, not `kimi/`.
- **Gemini:** paid API rates. Flash 3.8/3.7 include standard, batch, flex and
  priority, plus the published January 1, 2027 price change. The other included
  models have standard tariffs only. Pro pricing changes above 200,000 input
  tokens. Cache storage uses token-hours. Search quantities must already exclude
  shared free allowances. Gemini 2.5 uses `grounded-prompts`; the included Gemini
  3 models use `web-search-requests`. Account-wide quota allocation is outside
  this node. See [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing).
- **DeepSeek:** actual request time is mandatory. Peak windows are Monday-Friday
  01:00-04:00 and 06:00-10:00 UTC; starts inclusive, ends exclusive. All other
  times, including weekends, use off-peak. The V4 Flash alias uses V4.1 Flash
  prices. V4 Pro changes to Flash billing at September 14, 2026, 04:00 UTC.
  The current documentation supersedes earlier search snippets showing older
  model names and rates. See [DeepSeek pricing](https://api-docs.deepseek.com/quick_start/pricing/).
- **Grok:** standard direct API rates. The pricing table says long rates start
  at **200,000 or more**, while a model detail page says “exceed”; this snapshot
  follows the central pricing table. Long token rates do not multiply tool
  charges. On September 21, 2026, 19:00 UTC (12:00 PDT), X Search changes from
  calls to fetched posts/profiles. A caller still reporting the old charge name
  after that time receives `unpriced`. See [xAI pricing](https://docs.x.ai/developers/pricing).

Rates are an estimate for supported quantities, even within the above scope.
Image/audio/video generation, account credits, taxes, currencies other than USD,
provider-reported invoice amounts, session-wide billing state and invoice
reconciliation are not implemented. Use explicit additional charge keys for
unsupported usage so it becomes unpriced instead of disappearing into `input`.

## Cell format

Cell 1 does not change. Cell 2 keeps `token-count` (the submitted usage),
`cost-usd` (the rounded estimate, when available), and `price-schedule-id` (hash of
the entire immutable schedule). It adds a JSON-native `billing` map:

| Field | Meaning |
|---|---|
| `version` | Integer `2`; selects this calculation/replay contract |
| `status` | `estimated` or `unpriced` |
| `basis` | `published-list-price-estimate`; not a provider-confirmed charge |
| `currency` | `USD` |
| `context` | Captured usage time, time source, service tier, input convention |
| `quantities` | Normalized decimal-string quantities; ordinary input excludes cached/write categories |
| `prompt-tokens` | Total prompt tokens used for context thresholds |
| `source-url`, `checked-at` | Source and snapshot date, when a model was found |
| `tariff-id` | Matched tariff, when one could be selected |
| `line-items` | Map keyed by charge name, each with quantity, unit, per, rate-usd, exact amount-usd |
| `total-usd` | Final six-decimal total, only for an estimate |
| `reason` | Explanation when unpriced |
| `warning` | User-facing limitations, included in the signature |

All metadata is included before the node signs Cell 2. Decimal quantities and
money use strings to preserve exact values across JSON/CAD3 round trips. Maps
avoid relying on arbitrary line-item order. No third cell is necessary.

## Caller contract and examples

`usage` is a **normalized charge record**, not an arbitrary provider response.
Both `input` and `output` must be provided; explicit zero is allowed. The store
can still accept artifacts without usage, but records their billing as unpriced.

`billingContext` accepts:

- `usage-time`: nonnegative integer UTC epoch milliseconds for the actual API
  request. When omitted, capture time is recorded with
  `time-source: capture-time-fallback`. Time-dependent DeepSeek tariffs refuse
  this fallback. For delayed imports, always supply the actual request time.
- `service-tier`: exact tariff tier; default `standard`. Unknown tiers do not
  fall back to a cheaper rate. For a regional or negotiated price, install a
  separately named tier with the correct rates.
- `input-mode`: `exclusive` (default, maintains the earlier caller convention)
  or `total`.

In **exclusive** mode, `input` excludes cache reads and all cache writes. In
**total** mode, `input` includes them; the calculator subtracts `cached-input`,
`cache-write`, `cache-write-5m` and `cache-write-1h` before charging ordinary input.
Negative remainder is rejected. Cache-write categories must not overlap.
`output` must include billable reasoning/thinking exactly once. Do not add a
reasoning subcount again if already included in the reported output total.

For OpenAI's current response usage, copy total input into `input`, cached
tokens into `cached-input`, and write tokens into `cache-write`, with total mode.
For Anthropic, its base input count is exclusive; copy cache-read and separately
reported write-duration counts into their respective categories. If the provider
only reports aggregate writes, resolve the TTL breakdown before submitting it.
For Kimi and DeepSeek, distinguish cache hits from misses. For Gemini, include
billable thinking and use the correct billed search unit. Consult each provider's
usage response documentation; the node does not call providers or infer missing
counts from response text.

Example `surstor_store` input using OpenAI total-input accounting:

```json
{
  "content": "Example response",
  "sessionId": "billing-demo",
  "modelVersion": "openai/gpt-6-astra",
  "usage": {
    "input": 10000,
    "output": 1000,
    "cached-input": 4000,
    "cache-write": 2000
  },
  "billingContext": {
    "usage-time": 1789041600000,
    "service-tier": "standard",
    "input-mode": "total"
  }
}
```

This leaves 4,000 ordinary input tokens. The four charges are $0.04 input,
$0.004 reads, $0.025 writes and $0.05 output: **`0.119000` USD**.

For a Gemini storage charge, supply a decimal string such as
`"cache-storage-token-hours": "1000000.5"`. It is the integrated number of stored
tokens times hours, **not** a token count and not simply elapsed wall time.
Report storage once; repeatedly attaching the same storage interval to response
artifacts will overcount. Separate storage-only artifacts may use explicit zero
input and output, with the same model and applicable billing context.

Supply search/tool quantities after any applicable free allowance. The node
cannot determine how much of an allowance was consumed outside this session.
There is no request-ID deduplication: storing the same request twice counts it
twice. This remains artifact accounting, not an invoice ledger.

## Arithmetic and error behavior

1. Validate nonnegative integer/decimal-string quantities. Binary floating-point
   numbers, negative counts and exponent notation are rejected. Decimal strings
   support up to 12 fractional digits and 40 characters total. Token and request
   units must have integral quantities; token-hours may be fractional.
2. Normalize input categories and compute prompt size.
3. Select exactly one tariff by tier, prompt bounds, UTC interval and optional
   peak/off-peak rule. The bundled snapshot does not backdate its tariffs before
   September 10. Earlier requests need a historical schedule.
4. Calculate each line as `quantity * usd / per` using exact decimal arithmetic.
5. Sum exact lines, then round **once**, half up, to six USD decimal places.
   Session totals add those recorded six-decimal event totals.

Missing models, missing required usage, unknown tiers, out-of-range dates/context,
ambiguous matching tariffs, or unsupported nonzero charges produce `unpriced`,
an explanation, and **no `cost-usd`**. No partial subtotal is presented as a full
estimate. Unsupported zero quantities have no charge. Omitted optional quantities
mean “not supplied,” not proof that the provider charged nothing.

The calculator does not guess cache discounts or substitute a nearby model's
price. Missing usage is not fabricated. Installation rejects malformed schedules,
unsupported billing versions/conditions, invalid units and negative prices.
Overlapping tariff conditions are detected at calculation time and become
unpriced; installation does not attempt to prove arbitrary tariff ranges disjoint.

## Schedule schema and updates

A v2 schedule has integer `_schema-version: 2`, `_currency: "USD"`, an ISO UTC
`_checked-at`, and model entries. Each model has `source-url`, optional `notes`,
and a nonempty `tariffs` array. Each tariff has a unique `id`, a `service-tier`
and a `rates` map. Each rate contains exactly `usd`, `unit`, and `per`.

Supported units: `token`, `token-hour`, `request`. Supported denominators: `1`,
`1000`, `1000000`. New named charges can use these units without Java changes.
A new unit, algorithm or currency requires a new explicit implementation.

Optional tariff conditions:

| Condition | Semantics |
|---|---|
| `min-input-exclusive` | Prompt must be strictly greater |
| `max-input` | Prompt must be less than or equal |
| `from` | ISO instant, inclusive |
| `until` | ISO instant, exclusive |
| `time-band` | `peak` or `off-peak`, resolved from model's `peak-utc` |

`peak-utc` holds `weekdays` (ISO Monday=1 through Sunday=7) and `minute-ranges`
(pairs of start-inclusive/end-exclusive UTC minutes since midnight). These rules
are stored in the schedule; replay never relies on the local timezone.

To refresh prices:

1. Read the exact provider's current official API pages; verify model IDs,
   endpoint/region, units, tier, cache semantics and effective dates. A price
   seen in a search snippet can be older than the fetched page.
2. Create a new dated schedule. Preserve source URLs and explain ambiguity or
   exclusions in notes. Do not overwrite a historical schedule file.
3. Update the default resource reference and tests for a bundled release, or
   call `surstor_prices_put` with `{"schedule": <new schedule object>}` for a
   node-local update. The operation also accepts a JSON string.
4. Read `surstor_prices_get` to confirm the active schedule/hash. Store a small
   representative record and verify its exported bundle.

Source URLs are citations only. They are never fetched during calculation or
verification, and a URL does not authenticate an operator's custom rates. This
release contains no automatic web scraper, price polling or provider billing
integration. `_review-after` is a maintenance reminder only.

## Compatibility, migration and verification

The original `seed-2026-08.json` is retained **unchanged** as historical evidence
and a legacy test fixture. Its placeholder values are not the current default.
When a running node reads its active schedule, it upgrades only an **exact match**
to that old built-in placeholder map. Any altered/custom schedule is preserved.
The old asset remains in storage, so old cells keep their original schedule hash.
An explicitly reinstalled exact placeholder is also upgraded on the next read.

Legacy cells have no `billing` field and retain the original `Costs.compute`
algorithm: exclusive input, cached input charged separately, and rounding each
category before summing. V2 uses `Billing.estimate` and the signed context.
`Costs.compute` intentionally does not price v2 schedules without a context.

Existing cell contents, hashes, signatures and session links are not rewritten.
Legacy sessions with no coverage counter report `unpricedCount: null`; the node
does not claim to know their historical missing costs. A full historical audit
would be needed to populate that counter.

Exports remain `surstor-export/1` because the transport already carries arbitrary
cell maps and cited schedules. **Use the updated verifier for v2 records.** Old
verifier binaries do not support their calculation contract. Both current online
and offline verification use the same replay path and check the actual schedule
hash. The full v2 billing map, quantities, selected tariff, context and total
must match replay. Unpriced outcomes replay too. A cost claim without sufficient
evidence fails; an unknown billing version fails instead of falling back to v1.

Version 2's normalization, arithmetic, default conventions and signed warning
text are part of its reproducibility contract. Future semantic changes must use
a new billing version while retaining v1/v2 replay. Provider invoices should be
added later as separately sourced records, not substituted for signed estimates.

The previous aggregate cost response returned empty model totals and zero token
counters that were never calculated. Those fields are now omitted; supported
results are the recorded USD total, artifact count, unpriced count and warning.

## Implementation and validation

- `Billing.java`: v2 calculation, tariff matching, validation and shared replay.
- `Costs.java`: unchanged v1 arithmetic, explicitly isolated from v2.
- `Pricing.java`: new default, legacy resource retention, schedule validation.
- `SurStorAdapter.java`: context capture, signed billing, exact-placeholder
  upgrade, warnings, retrieval/export and estimate-based budget integration.
- `Sessions.java`: pricing coverage for new sessions.
- `Evidence.java` / `OfflineVerifier.java`: billing replay and schedule hash checks.
- MCP resource schemas: usage/context contract and user-facing warnings.

Regression tests exercise all 101 tariffs, exact cache normalization, both cache
write durations, fractional token-hours, one-time rounding, context boundaries,
DeepSeek weekdays/time boundaries and routing, Gemini/Grok announced changes,
invalid quantities, unsupported charges/tiers, JSON hash stability, billing
tampering, mixed legacy/v2/unpriced exports and preservation of custom schedules.
No live provider calls or paid API requests are made by these tests.

Validation on 2026-09-10: the full Maven `verify` build succeeded with 61 tests
passing (54 adapter tests and 7 node tests), with no failures or skipped tests.
The packaged application is available at `surstor-node/target/surstor-node.jar`.
