# Tamper-evidence demo

What SurStor proves, and how to reproduce each proof. Everything below is also
covered by automated tests (`mvn test`, 30 tests).

## The record format

Each artifact is two content-addressed cells:

```
Cell 1 (content):     {"content": "...", "content-type": "model-output"}
Cell 2 (provenance):  {"branch-ref": "0x<cell1 hash>",
                       "session-id": "...", "agent-id": "...",
                       "model-version": "...", "timestamp": ...,
                       "token-count": {...}, "cost-usd": "0.012600",
                       "price-schedule-id": "0x<schedule hash>",
                       "prev-event": "0x<previous cell2 hash>",
                       "signature": "<Ed25519 sig over unsigned cell2 hash>",
                       "signer": "<node account key hex>"}
```

Artifact ID = Cell 2 hash. Sessions chain events via `prev-event`; the chain
head is recorded in the session record at capture time.

## Scenario 1 — byte-flip a content cell

Adversary changes one character in stored content.

- Cell 1's hash changes → it no longer equals the `branch-ref` recorded in
  Cell 2
- `surstor_verify` reports `branchRef: false`, `valid: false`
- Test: `SurStorAdapterTest.byteFlippedCell1FailsBranchRef`

## Scenario 2 — rewrite a middle event

Adversary rebuilds event 2 with altered content and re-chains event 3 onto the
forgery. The forged chain is internally consistent — content addressing alone
can't reject it. But:

- The session record's `head` was written at capture time and still cites the
  honest event 3; the forged head never appears in `surstor_sessions`
- Verifying the honest head still walks the intact original chain
- Test: `SurStorAdapterTest.rewrittenMiddleEventNeverBecomesSessionHead`

## Scenario 3 — drop an event

Adversary presents a history with a middle event missing.

- Deep verify: the event after the gap cites a `prev-event` that doesn't
  resolve → `chain: false`
- Offline: the bundle's chain walk from the session head hits an artifact that
  isn't in the bundle → `GAP: event not in bundle`
- Tests: `SurStorAdapterTest.droppedEventFailsDeepVerify`,
  `OfflineVerifyTest.droppedEventFailsOffline`

## Scenario 4 — forged or stripped signature

Adversary modifies Cell 2 metadata, or strips the signature.

- The signature covers the hash of the *unsigned* cell (cell minus
  `signature`/`signer`); any metadata change invalidates it
- The signer key is embedded in the record, so verification needs no venue —
  and no trust in the presenter
- Test: `OfflineVerifyTest.tamperedContentFailsOffline`

## Try it by hand

```bash
# Store and verify
curl -s -X POST http://127.0.0.1:8484/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"surstor_store","arguments":{"content":"draft v1","sessionId":"demo"}}}'

# Export the session, then verify the bundle with no venue running
# (call surstor_export, save structuredContent as bundle.json)
java -jar surstor-node/target/surstor-node.jar verify bundle.json

# Flip one byte of content inside bundle.json → verify again:
#   [FAIL] artifact[1] branchRef — MISMATCH: cell1 content does not match branch-ref
#   INVALID — one or more checks failed   (exit code 1)
```
