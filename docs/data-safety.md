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
  benbow.properties          config (host, port, TLS keystore password)
  tls/server.keystore        self-signed certificate (back it up: clients pin its fingerprint)
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
Default media root: `<data>/media` (`media.root` in benbow.properties moves it, e.g. to another disk). Partial uploads live in `<media root>/.uploads` (same volume, so placement is an atomic rename) and are dropped after 7 days untouched; the device still has the original. The backup job mirrors media to `<backup.mirror>/media` (never deletes there), re-hashes up to 200 files older than 7 days per run (mismatches are reported, never deleted), and at startup the server re-records files the database doesn't know (after a restore).
Media paths are human-readable on purpose: if the app ever dies, the files can still be browsed. Dedup works by SHA-256 in the DB (the same hash reuses the existing file).

## Backups
- **DB snapshots** via the SQLite online backup API or `VACUUM INTO` into `backups/db/benbow-YYYYMMDD-HHMMSS.sqlite`. Triggers: hourly while there have been changes, daily, before migrations and updates, and on demand.
- **Retention (GFS):** 24 hourly, 14 daily, 8 weekly, 12 monthly, plus all pre-migration snapshots for 90 days.
- **Second location:** a second local disk (owner's choice, ADR-010). It gets copied DB snapshots plus an incremental media mirror (immutable files, so copying by hash is enough). Warn loudly if the target disk is missing or full. Cloud targets come later, and they must be encrypted.
- **Export mirror:** periodic export of every entry to `export/YYYY/MM/YYYY-MM-DD HHmm <title>.md` (rich Markdown with YAML front matter: id, kind, day, tags, mood, favorite, attachments as relative links) **plus** a `.json` sidecar with the canonical Document and metadata (lossless). This is the disaster-recovery format that needs no app. Optionally a local git repo for free history.
- **Status visible:** the admin page and the client settings show last backup time, last verification, and last mirror sync. The UI warns if the latest backup is older than 48 hours.

Implemented (server 0.2.0): `Backups` (VACUUM INTO + reopen + integrity_check + row-count match, manifest.json), `BackupJobs` (snapshot on change every `backup.intervalMinutes`, `Retention` GFS pruning, verified SHA-256 copy to `backup.mirror`, Markdown mirror in `<data>/export`), pre-migration and pre-update snapshots in `Database.open`, revisions protected by SQLite triggers (no UPDATE/DELETE). Config paths in `benbow.properties` must use `/` (Java properties treat `\` as an escape); a relative mirror path is rejected.

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
- The local DB runs WAL + `synchronous=FULL` (drafts and the outbox exist nowhere else). The device token is sealed with a key in the Android Keystore.
- Exclude the DB from Android Auto Backup. Restoring a stale cache onto a new phone would confuse sync. Device recovery happens through a fresh pull.
- Every device cache doubles as a partial backup. After a server restore, clients can re-upload what the server lacks (see `sync.md`).

## Disaster recovery runbook
1. Server DB corrupt or a bad change → run `restore-backup.cmd` as Administrator (default: newest verified snapshot; or `restore-backup.cmd <file>`; snapshots from the mirror work too). It stops the service, moves the current DB to `<data>/db/replaced-<time>` (nothing is deleted), puts the snapshot in place with a new epoch and starts the service. Paired devices see the new epoch and reconcile: they re-upload entries, head revisions and field values the snapshot lacks; a lost edit of an entry that exists in the snapshot comes back as a second head (conflict) for the user to pick. Copying a snapshot by hand also works: `db/high-water.properties` lets the server notice the regression at startup and bump the epoch itself.
2. PC dead → install the server on a new PC → point it at the backup target → restore → re-pair devices.
3. App unusable → the Markdown mirror plus the media folder are readable as plain files.

## Drills
Automated (run by `./gradlew check`): client kill mid-typing (`DraftSessionTest`), leftover drafts at startup, lost request/response (`ClientSyncTest`), server restore healed by device caches (`ClientSyncTest`, `EndToEndTest.restoredServerIsHealedByThePhone` over real HTTPS), restore tool and hand-copied DB detection (`RestoreTest`).
Manual, on the S24+ against a dev server (2026-10-13, all passed):
- Kill: force-stop 1 s after typing a new entry, and 0.7 s after editing an existing one → text present after restart, committed and synced.
- Server off: notes written while the server is down show "N изменений ждут"; synced on their own ~25 s after it came back.
- Background: note written with the server down, app sent home, process killed → WorkManager delivered it ~30 s after the server came back, without opening the app.
- Restore: snapshot, then a new note + an edit, then `--restore` of the snapshot → after pull-to-refresh the server had the note back and the edit as a second head; picking it in History resolved the conflict.
- Still to do by hand: airplane mode on/off (adb over Wi-Fi drops, so not automated).
