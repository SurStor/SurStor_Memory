# Prompt capture and privacy controls

## User journey

1. Start the local node and open `http://127.0.0.1:8484/capture`.
2. Enter a prompt, response, session and optional model. Select **Filter and review**.
3. The local node scans the input in memory. It rejects organisation exclusions or replaces detected secrets with `[REDACTED]`. A successful preview clears the original fields in the interface and displays the filtered record, including its metadata.
4. Inspect every field. Edit the filtered prompt, response or metadata if needed, then select **Filter edits again**. Editing invalidates the previous approval in the interface.
5. Tick the review confirmation and select **Save approved record**. The server accepts a single-use token for the exact filtered snapshot. Approvals expire after five minutes and cannot be used after the policy changes. Saving returns the record ID.

If filtering fails, capture stops. Fix the input or policy and preview again. If a **save** cannot be confirmed, check existing records before retrying: an interrupted or partially completed store can have written a record. SurStor does not promise transactional rollback across the two cells, session state and persistence sync.

**User warning:** automated secret detection can miss sensitive information and can flag harmless examples. Review all text and metadata. A signature proves which filtered content was recorded; it does not certify completeness, factual accuracy, regulatory compliance or absence of secrets.

## What is stored

The existing two-cell design is preserved. `contentType: "conversation"` stores a content object containing exactly two string fields, `prompt` and `response`. Both are filtered. The content hash, provenance signature and session chain bind this filtered representation; no original text, original-content hash or reversible redaction mapping is retained by this capture flow.

New records include a signed `privacy` metadata map with `detector: "surstor-filter/1"` and `result: "passed-storage-filter"`. Retrieval and export preserve this map. This is a storage-filter receipt, **not** an assertion that a human reviewed the content or that a particular organisation policy was independently audited. Existing records are not rewritten. Structured conversation records are currently retrievable/exportable but are not added to the text-only semantic search index.

Usage and billing remain caller-supplied. The simple review form does not count tokens or infer usage from redacted text. Advanced clients may include the existing `usage` and `billingContext` fields in the reviewed input. Original provider usage counts should remain unchanged by redaction. A capture without usage is unpriced, not free; existing billing warnings still apply.

## Organisation-specific policy

Add to the node TOML configuration:

```toml
[privacy]
policy_file = "privacy-policy.json"
```

Paths resolve relative to that TOML file. Copy `config/privacy-policy.example.json` and replace its examples:

```json
{
  "redactTerms": ["Confidential client name", "internal.example.test"],
  "excludeTerms": ["Project Never Capture", "restricted/payroll/"]
}
```

Rules are literal, case-insensitive substring matches throughout the submitted strings, including metadata and object keys. `redactTerms` replaces matches. `excludeTerms` rejects the entire capture. A match in a key that would require renaming also rejects capture, avoiding collisions. Exclusion rules take precedence over redaction. Project, path and domain rules match text actually present in the submission; the node does not inspect a caller's filesystem or infer the originating project. Clients must send relevant context in content or an existing metadata field, or exclude it at source.

Only these two policy keys are accepted; lists allow at most 100 nonblank strings each, with 256 characters per term. Arbitrary regular expressions are not supported. Policy files are limited to 64 KiB. Protect the file with local operating-system permissions. The policy is selected by the operator, never by a capture request. The node is single-user; tenant-specific policy selection and organisation administration are not implemented.

The policy reloads on each capture and storage attempt. Missing, unreadable or invalid configured policies block capture; invalid configuration blocks startup. An empty/unset path selects built-in detection only. Preview tokens retain their policy snapshot; a changed policy requires a new preview. This fails closed rather than silently reverting to built-in rules.

## Detection and capture boundary

Built-in patterns cover common API-key prefixes, GitHub and Slack tokens, AWS access-key IDs, JWT-shaped values, Bearer/Basic credentials, private-key PEM blocks, credentials in HTTP URLs, and common password/secret assignments. Sensitive JSON field names are redacted recursively. This is pattern detection, not a comprehensive DLP classifier: custom formats, encoded or split secrets, images, ordinary personal data and obfuscated content may evade it. Binary and unsupported cell types are rejected. Inputs are bounded to 256 KiB at HTTP ingress, 262,144 character/node budget in the recursive filter, and 32 nesting levels.

The node installs an HTTP before-handler because Covia can persist job inputs **before** the adapter runs. Every nonempty HTTP body must be JSON and pass the filter unchanged before reaching normal REST/MCP handlers. The one exception is the preview endpoint: it receives raw input transiently, filters it without creating a job, and keeps only the filtered candidate in a bounded in-memory approval cache (64 entries, five-minute expiry, pruned on capture requests). No raw capture payload is logged by these handlers, queued, embedded, or staged to disk. Invalid requests receive fixed messages rather than exception details containing submitted data.

This changes compatibility: existing REST/MCP clients must submit already-filtered content, or use the preview/commit API. Direct `surstor:store` also rejects detected secrets before creating cells. Direct clean stores remain possible without human review; the review requirement applies to the preview/commit flow. Non-JSON uploads and secret-provisioning request bodies are blocked by the node-wide guard. Filtering is not an optional bypass flag.

The protection applies to this node's HTTP capture path and SurStor's storage adapter. It does not retrofit upstream Covia's JobManager: embedded callers must filter **before invoking JobManager**, and internally generated jobs/other adapters can have their own persistence behavior. It does not erase old records, original harness transcripts, clipboard history, browser/OS memory or crash dumps. Raw content necessarily exists transiently in browser and server memory during preview; Java/browser memory cannot be reliably zeroed. No browser localStorage, autosave, remote detector or telemetry is added.

The node requires a loopback bind and checks Host/Origin to reject cross-site browser requests and DNS rebinding. It remains a trusted single-user local service, not an authenticated multi-user deployment. Local processes are trusted. Do not expose it through a reverse proxy. Preview tokens are bound to the Covia caller identity and are bearer secrets; do not share them. Restart discards pending previews. Clearing the interface removes visible fields; an already-issued filtered preview can remain in server memory until expiry/pruning or restart.

## API

`POST /api/v1/capture/preview`, `Content-Type: application/json`:

```json
{
  "content": {"prompt": "Explain this change", "response": "The change adds a review step."},
  "contentType": "conversation",
  "sessionId": "review-example"
}
```

Returns `token`, filtered `input`, `redactions` (replacement count, not unique secrets), and a user-facing `warning`. Display every field in `input` before approval. To edit, submit the edited input to preview again. Unknown top-level capture fields are rejected. No content is stored at preview time.

`POST /api/v1/capture/commit` accepts **only** `{"token":"<preview token>"}`. It checks identity, expiry, current policy and content again, consumes the token and calls the adapter directly with the approved filtered input. Successful capture syncs the venue and returns the existing store response with IDs and billing warning. Filtering errors use HTTP 422; an expired/consumed approval or unconfirmed save uses 409. Neither returns submitted sensitive values.

## Validation

`PrivacyFilterTest` covers credential formats, nested content and metadata, literal organisation rules, invalid/missing policies, depth/size bounds and unsupported binary input. `CaptureRoutesTest` runs a real venue: filtered preview and save, replay rejection, expired approvals, changed policy, cross-origin requests, malformed input, REST/MCP rejection and fail-closed adapter calls. It inspects the temporary venue files after shutdown to check the synthetic raw secret was not persisted. Existing evidence, billing, persistence and offline verification tests remain applicable.
