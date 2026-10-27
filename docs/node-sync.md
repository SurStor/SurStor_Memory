# Bidirectional memory sync (alpha.3 developer preview)

This source checkout can synchronize selected SurStor memories between two or
more trusted, single-user nodes. Available in the `0.2.0-alpha.3` image and release JARs, or build this
checkout with `mvn package`. For containers see [Docker sync](docker.md#optional-memory-sync).

Each device retains its own signing identity and storage encryption key. Nodes
exchange missing signed records in both directions. A laptop can initiate all
connections to a server; the same connection cycle downloads server records and
uploads laptop records. A third device can use that server as a relay.

## What is reused, and what is deliberately scoped

The bridge uses Convex `CASLattice` for content-addressed union, existing CAD3
hashes/signatures, and Covia user assets and Etch persistence. It does not fork
upstream dependencies, enable consensus, copy live Etch files, or expose Covia's
root over the native `NodeServer` wire protocol.

Inspection of the installed Covia `0.9.6-SNAPSHOT` shows a local-only NodeServer
and a whole-value last-writer-wins venue region with one authoritative writer.
Replicating that root would include unrelated venue state, and sharing one
signing identity across active devices could overwrite concurrent changes.
This first integration therefore uses a separate authenticated HTTP record
bridge, with a narrow inventory and import/export surface. Native lattice
transport with equivalent read authorization remains future work. Keep the
existing Covia version default.

Only records in the explicitly selected session names and from allowed signer
keys are shared. The local node's own signer is automatically eligible. Received
records retain their exact content, metadata, IDs, original signatures and
cited price schedules. Approved records can be relayed to every member of the
same trust group; this is not a per-peer sharing or multi-tenant permission system.
Private keys, jobs, secrets, unrelated assets and venue configuration are not
part of the exchange.

## Configure a laptop and server

1. Start each node normally once, using different data directories/identities.
   Copy each node's **Sync signer public key** from its startup output. This is
   the 64-character public key, not the identity seed file. Never copy the seed
   to another active node.
2. Generate one random group credential and transfer it securely to both hosts.
   Keep it outside the repository and restrict it to the node's OS account.

   Linux/macOS:
   ```sh
   umask 077
   openssl rand -hex 32 > sync.token
   ```

   PowerShell:
   ```powershell
   $syncBytes = New-Object byte[] 32
   $syncRng = [Security.Cryptography.RandomNumberGenerator]::Create()
   $syncRng.GetBytes($syncBytes)
   $syncRng.Dispose()
   [BitConverter]::ToString($syncBytes).Replace('-', '').ToLowerInvariant() |
       Set-Content -Encoding ascii -NoNewline sync.token
   ```
   Restrict the Windows file's ACL to your user/service account. The credential
   authorizes access to the whole configured shared collection. It is separate
   from the node's at-rest encryption key.
3. Append the following to the laptop's existing TOML configuration. Replace
   the public-key placeholders with the real lowercase hex public keys. Session
   and peer lists are comma-separated strings, not TOML arrays.

   ```toml
   [sync]
   enabled = true
   port = 8485
   interval_seconds = 15
   token_file = "sync.token"
   sessions = "shared-memory"
   trusted_signers = "LAPTOP_PUBLIC_KEY,SERVER_PUBLIC_KEY"
   peers = "http://127.0.0.1:18485"
   ```

   The token path is relative to the TOML file. On the server use the same
   sessions, public-key list and token, but `peers = ""`. The server does not
   need an inbound connection to the laptop. Both machines may use local port
   8485 because they have separate network stacks.
4. Keep MCP bound to loopback. Start both updated nodes using their TOML files:
   ```sh
   java -jar surstor-node/target/surstor-node.jar start --config config/surstor.toml
   ```
5. From the laptop, maintain an SSH tunnel to the server:
   ```sh
   ssh -N -L 18485:127.0.0.1:8485 user@your-server
   ```
   SSH encrypts and authenticates the connection. The native sync listener defaults to
   `127.0.0.1`; it does not expose the MCP server or capture screen. For an
   alternative deployment, proxy **only** the dedicated sync listener through
   HTTPS with a valid certificate. Peer URLs allow HTTPS, or HTTP to numeric
   loopback addresses only. HTTP redirects are not followed.
6. Ask your MCP host to store a harmless test memory with
   `sessionId: "shared-memory"` on each device. After one polling cycle, use
   `surstor_list` and `surstor_retrieve` on the other device. Verify the original
   ID with `surstor_verify`, including `deep: true` after the chain arrives.

For a third node, add its public key to every member's trusted-signers list and
restart the services. Configure the third node to connect to the server through
its own secure tunnel. All members must agree on session scope and signer keys.
For two processes on one computer, give each distinct HTTP/sync ports and data
directories; no SSH tunnel is needed for their loopback connection.

## What users see

- `surstor_list`, retrieval, diff and verification include received records.
  A session filter on listing uses the **original** signed session name.
- `surstor_sessions` exposes complete received chains under read-only `sync-...`
  aliases, with `sourceSession`, `sourceSigner` and `readOnly` fields. Use the
  alias for session export or a per-session cost view. The alias includes the
  current head, so it changes when that chain grows; record IDs remain stable.
- Local sessions keep their original heads and budgets. Writing to a replicated
  alias is rejected. Save follow-up work to a local session, such as the same
  `shared-memory` collection name, under your own node's signature.
- Independent writers with the same session name retain separate chains.
  Out-of-order records can be retrieved immediately, but a complete session view
  appears only when its entire same-signer, same-session chain is present.
- If a signer forks a chain, both terminal branches remain visible. They may
  share ancestors. Aggregate `surstor_cost` therefore reports **local sessions
  only**, with `scope: "local-sessions"`; replicated per-chain costs are available
  by alias and must not be summed blindly.
- When local semantic search is enabled, the worker rebuilds its derived index
  after imports and at startup. Failures retry on subsequent cycles; records
  remain retrievable by ID. Encrypted profiles continue to disable semantic search.

An authenticated `GET /v1/status` on the dedicated sync port provides coarse
worker status. Supply the group token as `Authorization: Bearer <token>` using
a trusted client. Do not paste credentials into chat or commit them to the repo.
An idle status describes the last polling cycle, not proof that all peers are
currently online or that every configured member has converged.

## Privacy, encryption and failure behavior

Both export and import re-run the current secret detector and organization
policy. Invalid or unreadable policies fail closed. Imported cells are verified
before persistence: envelope hash, content binding, original signature, allowed
signer/session, schedule hash and billing calculation. Signed content is never
silently redacted; a blocked source must create a new filtered record instead.

Selecting sessions and trusted signers authorizes automatic replication; there
is no per-record review dialog during sync. Review sensitive information at
capture time and choose the shared collection deliberately. A signature proves
integrity and signer possession, not truth or that a human reviewed the record.

Enable encrypted storage independently on every node using the existing guide.
Each destination stores received data under its own Etch key; no plaintext sync
cache is written. Data is necessarily readable in each participating process.
Transport encryption comes from SSH or HTTPS, not the storage encryption setting.
A recipient can retain data it already received: removing trust or changing the
token stops future access after restart but does not erase remote copies.

Imports are idempotent and can be retried after interruptions. Each accepted
record is persisted before acknowledgment. Record-level atomic validation does
not make an entire polling cycle transactional: earlier valid records remain if
a later record fails. Identity files and encryption keys still require separate
backups. Synchronization is not a complete node backup.

## Preview limits and deployment

- Maximum 2,000 eligible records per node; a record envelope or HTTP response is
  limited to 262,144 bytes, including its cited schedule. Oversize inventories or
  packets fail rather than silently dropping records. Inventory paging and
  efficient incremental indexing are future improvements.
- Append-only replication. No deletion/tombstone propagation, automatic memory
  supersession, multi-user account mapping, per-peer ACLs or one-way mode yet.
- Worker errors retry on the configured interval (5–3,600 seconds). A rejected
  record can block that peer's remaining exchange for the cycle; correct the
  trust/privacy policy or remove the problematic collection from scope.
- Docker alpha.3 supports an optional `compose.sync.yaml` overlay. Inside an
  isolated container only, `sync.bind = "0.0.0.0"` requires the explicit
  `--allow-container-network` flag. Publish the host sync port on loopback.
- Do not publish the existing unrestricted MCP port to enable sync.

Validation covers three-node relay, bidirectional exchange, independent offline
writes, duplicate delivery, fork preservation, incomplete chains, offline export
verification, restart recovery, different encryption keys, blocked authentication,
tampered data, untrusted signatures, policy failure and bounded responses. Remote
SSH/HTTPS deployment remains an operator setup step; tests run on loopback.

Validation on this checkout: all 76 Maven tests passed. A separate smoke check
launched two packaged CLI nodes, captured reviewed memories on both through the
HTTP capture endpoints, and confirmed automatic bidirectional convergence to
identical inventories. Those temporary test processes were stopped afterward.
