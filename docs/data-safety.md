# Data safety (highest priority)

Losing or corrupting data is unacceptable from v1. Every storage, sync, migration or deletion change must keep these invariants, and tests must enforce them.

## Invariants (testable)
- **S1 Append-only content.** Entry text is never overwritten. Each save creates an immutable revision. "Restore" creates a new revision. Revisions are never deleted, except by an explicit purge (S6).
- **S2 Atomicity.** Every mutation is a single SQLite transaction that also writes its `change_log` row. There are no partial states.
- **S3 Durability.** Server SQLite runs with `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`. A write is acknowledged to a client only after it commits.
- **S4 Idempotency.** Every client op carries an `op_id`. Replays are no-ops (`applied_ops` table). Retries are always safe.
- **S5 Nothing silently dropped.** The client outbox keeps an op until the server acknowledges it. Rejected ops go to a visible "needs attention" list. They are never discarded automatically.
- **S6 Soft delete.** Deletion sets `deleted_at` (trash). Hard purge happens only by explicit user action from the trash, with confirmation, and only on the server. Orphaned blobs are moved to `media/.trash/` after a grace period, never deleted directly.
- **S7 Verified blobs.** Uploads go to `tmp/` → fsync → SHA-256 and size check → atomic rename. Files are immutable once placed. A periodic scrub re-verifies hashes.
- **S8 Conflicts keep both.** Concurrent edits never discard either side (see `sync.md`).
- **S9 Verified backups.** A backup counts only after it has been reopened, passed `PRAGMA integrity_check` and matched row counts.
- **S10 Safe migrations.** Automatic backup before every schema migration. Migrations are forward-only and tested on fixtures. The server refuses to open a DB with a newer schema than it knows.
- **S11 Store identity.** Each data store has a `store_id` and `epoch`. Clients detect a different or restored server and never mix data blindly (see `sync.md#store-identity`).
- **S12 Drafts survive crashes.** The editor persists drafts locally on a debounce (≤500 ms) and on lifecycle stop. Killing the app loses at most the last half-second of typing.
- **S13 Format safety.** Unknown block types and fields survive every read/write byte-for-byte. A document with a newer format `v` opens read-only. Old revisions are never rewritten into a new format version (readers support every version). The codec is property-tested (content-format.md).

## Server storage layout
```
<data root>/                 (default %LOCALAPPDATA%\Benbow, user-configurable e.g. D:\Benbow)
  benbow.toml                config
  db/benbow.sqlite(-wal,-shm)
  tmp/uploads/               in-progress resumable uploads
  thumbs/                    derived previews (regenerable, not backed up)
  backups/db/                verified DB snapshots
  backups/manifest.json      backup history + verification results
  export/                    human-readable Markdown mirror (see below)
  logs/
<media root>/                (user-chosen folder, may live inside data root)
  2026/10/2026-10-12_153012_<shortid>.jpg   human-readable, immutable
  .trash/
```
Media paths are human-readable on purpose: if the app ever dies, the files can still be browsed. Dedup works by SHA-256 in the DB (the same hash reuses the existing file).

## Backups
- **DB snapshots** via the SQLite online backup API or `VACUUM INTO` into `backups/db/benbow-YYYYMMDD-HHMMSS.sqlite`. Triggers: hourly while there have been changes, daily, before migrations and updates, and on demand.
- **Retention (GFS):** 24 hourly, 14 daily, 8 weekly, 12 monthly, plus all pre-migration snapshots for 90 days.
- **Second location:** a second local disk (owner's choice, ADR-010). It gets copied DB snapshots plus an incremental media mirror (immutable files, so copying by hash is enough). Warn loudly if the target disk is missing or full. Cloud targets come later, and they must be encrypted.
- **Export mirror:** periodic export of every entry to `export/YYYY/MM/YYYY-MM-DD HHmm <title>.md` (rich Markdown with YAML front matter: id, kind, day, tags, mood, favorite, attachments as relative links) **plus** a `.json` sidecar with the canonical Document and metadata (lossless). This is the disaster-recovery format that needs no app. Optionally a local git repo for free history.
- **Status visible:** the admin page and the client settings show last backup time, last verification, and last mirror sync. The UI warns if the latest backup is older than 48 hours.

## Integrity checks
- On startup: `PRAGMA quick_check`. Nightly: `PRAGMA integrity_check` + `foreign_key_check` + a blob scrub over a rolling subset (all files over 7 days).
- On failure: switch to read-only mode, alert in admin page and clients, keep serving reads. Never auto-repair destructively.

## Migrations
- Schema versioned with SQLDelight `.sqm` files plus `verifyMigrations`. `PRAGMA user_version` is tracked.
- Startup sequence: detect pending migration → snapshot backup (verified) → migrate in a transaction → integrity check → serve.
- Fixture DBs for every released schema version live in tests. Each must migrate to latest with identical content checksums.

## Client-side safety
- The local DB is a cache + outbox + drafts. The outbox is the only client-owned data, and it is deleted only after the server acknowledges it.
- Captured media is copied into app-private storage immediately, so deleting the source in the gallery can't lose it. The local copy is evicted only after upload is confirmed (and, if available, after the server reports it as backed up).
- Exclude the DB from Android Auto Backup. Restoring a stale cache onto a new phone would confuse sync. Device recovery happens through a fresh pull.
- Every device cache doubles as a partial backup. After a server restore, clients can re-upload what the server lacks (see `sync.md`).

## Disaster recovery runbook (to be expanded with the implementation)
1. Server DB corrupt → stop service → restore the latest verified snapshot → start → server bumps `epoch` → clients reconcile and re-push newer data.
2. PC dead → install the server on a new PC → point it at the backup target → restore → re-pair devices.
3. App unusable → the Markdown mirror plus the media folder are readable as plain files.
