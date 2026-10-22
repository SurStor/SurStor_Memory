# Fresh encrypted venue storage

This profile encrypts stored venue records with Covia/Convex Etch v3, including
the Etch index. It is not end-to-end encryption: the running venue can decrypt
records. It does not require Convex consensus.

Covia `0.9.6-SNAPSHOT` is the default installation dependency, including for this
encrypted profile. The installed build and Convex `0.8.15` support this policy.
On a fresh machine, complete the [Covia build prerequisite](../README.md#build--run)
before building SurStor.
Snapshot contents can differ between machines; run the encryption tests against
the exact dependency build used for deployment.

## Fresh start

1. Stop the old node. Select a new empty data directory. The supplied
   `config/surstor-encrypted.toml` uses `~/.surstor-node-encrypted`.
   No automatic deletion or conversion of existing stores is performed.
2. Generate a random 32-byte key using a cryptographic generator. Supply its
   64 hexadecimal characters as `SURSTOR_ETCH_KEY` through the service secret
   manager. Never use the venue password or signing seed as this key. Keep a
   separately protected recovery copy; losing the key means losing the records.
3. Alternatively remove `key_env` and set `key_file` to a protected file containing
   the hex key. Relative paths resolve beside the configuration file. Keep the
   key outside the data directory and repository. Restrict access to the service
   identity (0600 on Unix or an equivalent Windows ACL). The node does not install
   OS-level key-file permissions.
4. Build and run from the repository root:

   ```sh
   mvn package
   java -jar surstor-node/target/surstor-node.jar start -c config/surstor-encrypted.toml
   ```

   Packaged deployments must place the adapter module at
   `modules/surstor-adapter-module.jar` beside the launch working directory.
5. Save, retrieve and verify a synthetic capture. Restart with the same secret
   and verify again. Preserve the signing identity across restarts and restores.

Before launching the venue, the node checks the policy and actual store. Invalid
settings, missing/malformed/wrong keys and incompatible or plaintext stores fail
startup. It never falls back to plaintext when encryption is requested. AES-256-CTR
and ChaCha20 are accepted; the sample uses AES. Configurations without an encryption
section retain their previous behavior: encryption remains an explicit opt-in.

## Search and other files

For local-model memory, connect the same MCP endpoint described in the
[local-model guide](local-models.md). Encryption does not change tool arguments.
Use `surstor_sessions` or `surstor_list` to locate records, then
`surstor_retrieve` by ID. This profile provides persistent memory with explicit
lookup, but not similarity-based recall. Keep useful project/session IDs in
your host instructions so it can find the right context.

- Semantic search defaults to disabled when encryption is enabled. Explicitly
  enabling it is rejected because the separate Lucene index is not encrypted by
  Etch. A nonempty old search index also blocks the encrypted profile. The Etch
  index is different and is always encrypted by this profile.
- Content storage uses `storage.content = lattice`, keeping content inside Etch.
- Signing identity files, configuration, logs, exports, crash dumps and prior
  stores are not encrypted by this feature. Use an encrypted volume and restricted
  permissions for the full deployment. Do not log prompts, responses or keys.
  Privileged operators must not re-enable plaintext file storage or search through
  venue administration tools after startup.
- Back up the stopped venue's store and signing identity. Encrypt the backup
  container too, because the signing identity is outside Etch. Keep the recovery
  encryption key separately protected and test restoration.
- Changing a key does not re-encrypt an existing store. Rotation and migration are
  separate work; this release implements a fresh-start profile.

## Hosted deployment

Website credentials do not grant server administration. The operator needs to
deploy the tested node/module artifacts, inject the key, choose a fresh data path
and change the service startup configuration. Keep the node on loopback behind
the authenticated HTTPS proxy. The launcher remains single-user; encryption does
not add user or organisation isolation.

Before switching traffic, test capture, retrieval, verification, restart and backup
restoration. The Codex plugin does not need a payload change. Existing records are
approved for disposal, but this change deletes no deployed data. Old volumes,
snapshots and backups must be retired separately: a fresh path does not encrypt
or erase previous copies. No hosted deployment was performed during development.

## Validation

`EncryptedStorageTest` covers configuration errors, external-index rejection,
missing/malformed/wrong keys, rejection of plaintext stores, encrypted venue
capture, absence of a synthetic plaintext marker in storage, and retrieval plus
signature verification after restart and restoration of a closed-store copy.

On 29 September 2026, `mvn package` passed all 69 tests. A separate packaged-CLI
smoke test started the supplied encrypted profile with a temporary random
environment key, checked the status endpoint and absence of an external search
index, then confirmed that restarting without the key was rejected. Synthetic
test processes were stopped; no production key was generated or installed.

Reference: [Covia configuration](https://docs.covia.ai/docs/operator-guide/configuration/).
The installed source JAR was also checked for the actual configuration contract.
