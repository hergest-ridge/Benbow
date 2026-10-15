# Sync and API

Single owner, several devices, server on LAN, often unreachable. Goal: offline-first clients, never lose data (data-safety S4, S5, S8, S11), simple to debug.

## Why not a CRDT
Conflicts are rare with one human. CRDTs (Automerge, Loro, Yjs) add opaque binary state, bindings that aren't mature on every KMP target, and harder export and debugging. An append-only revision DAG + 3-way merge gives the same "never lose" guarantee with plain, inspectable data. We can revisit this for real-time co-editing, which is a non-goal.

## Client local state
Implemented in `shared/client` (SQLDelight `BenbowDb`, `Benbow.sq`):
- `entry`: mirror of server entries (head revision id + body, LWW fields as JSON, conflict heads).
- `draft (entry_id, body, base_rev)`: in-progress editor text, autosaved. Not synced. The base is fixed when the draft starts, so the revision made from it has the right parent.
- `outbox (seq, op_id, entry_id, op, state pending|rejected, last_error)`: ordered ops as JSON. Acknowledged ops are removed; rejected ops stay for the user (never dropped silently).
- `kv`: connection (url, fingerprint, token, store id, epoch, device id), pull cursor, persisted HLC.
- A local write = cache update + outbox insert in one transaction (`JournalRepository`).
- `SyncEngine.sync()`: checks `/info` (store id, protocol) first, pushes in batches of 200, then pulls pages in transactions. Pull skips entries with pending ops (pending wins until acknowledged) and never touches drafts.
- Trash is the LWW field `deleted = "true"`; restore clears it.

## Revisions from drafts
A draft becomes a revision on: editor close, app backgrounded, 30 s idle, or every 2 min of continuous typing. Op: `entry.revision.put {entry_id, rev_id, parents:[base_rev_id], title, body, body_v, body_sha256}` (body = canonical Document JSON).

## Op types (v1)
`entry.create`, `entry.meta.set {field, value, hlc}` (day, kind, favorite, pinned, mood, appearance, space),
`entry.revision.put`, `entry.delete` / `entry.restore {hlc}`, `entry.tag.set {tag_id, present, hlc}`,
`entry.attachment.set {attachment_id, position, caption, present, hlc}`, `reaction.set {emoji, present, hlc}`,
`tag.upsert`, `tag_category.upsert`, `attachment.register`, `template.*`, `reminder.*`. Calendar events have their own store and ops (see "Calendar store" below, ADR-027).
Implemented: `entry.create`, `entry.revision.put`, `entry.meta.set` (protocol 1); `entry.attachment.put {attachment: meta, position, caption, present, hlc}` (protocol 2, replaces the planned `attachment.register` + `entry.attachment.set`: the immutable file meta travels with every link change, so a link is self-contained). Trash is `entry.meta.set deleted`. Favorite, mood, tags, reactions and a comment's parent are namespaced fields too (ADR-021): `favorite`, `pinned`, `mood`, `tag:<lowercase>` = display name, `reaction:<emoji>`, `parent`.
Metadata fields use per-field LWW ordered by a **Hybrid Logical Clock** (`hlc` = wall ms + counter + device id), so it tolerates phone clock drift.

## Push
`POST /api/v1/sync/push {protocol, device_id, ops:[...]}`. Ops are applied in order. Each op runs in its own transaction and is deduplicated by `op_id`.
Per-op result: `applied | duplicate | merged | conflict | rejected(reason)`. `rejected` stays in the client's "needs attention" list (S5).

## Revision conflicts (server)
When `parents[0] != head`:
1. Store the incoming revision anyway (DAG sibling). It is never lost.
2. Try a diff3 merge (base = parent, ours = head, theirs = incoming) over the canonical serialization, where one line = one block (content-format.md), using the shared code in `shared/core`. Then validate: the result must decode, and `_key`s must be unique (a duplicate counts as a conflict).
3. Clean merge → new merge revision with two parents, result `merged`.
4. Overlapping changes → entry gets `conflict_heads=[head, incoming]`, result `conflict`. Clients show a banner with both versions side by side. The user's resolution becomes a revision whose parents are both heads.

## Pull
`GET /api/v1/sync/pull?cursor=N&limit=500` → `{changes:[entity snapshots], next_cursor, has_more}`.
- `change_log.seq` is assigned inside the write transaction. SQLite's single writer means no gaps or reordering.
- Snapshots carry full current state + head body. Older revisions are fetched on demand (`GET /api/v1/entries/{id}/revisions`).
- Client rule: an entity with pending outbox ops keeps its local optimistic state until the push result arrives, then re-pulls. The server is the arbiter.
- Bootstrap a new device = pull from cursor 0 (heads only). Attachments load lazily.
- Live updates (Phase 2+): `GET /api/v1/sync/events` (SSE or WebSocket) announces new `seq`, which triggers a pull.

## Store identity (S11)
`GET /api/v1/info` → `{server_version, protocol_min, protocol_max, store_id, epoch, schema_version}`.
- Different `store_id` → the client refuses to sync and asks the user (new server? re-pair, keep local cache).
- `epoch` increased (server restored from backup) → client runs reconciliation: it sends the IDs and revision IDs it knows, the server answers with what it lacks, and the client re-pushes those. This is how device caches heal a restored server.
- Server cursor lower than client cursor without an epoch change → treat as a restore and reconcile.
- Implemented (server 0.3.0): `info.cursor`, `POST /api/v1/sync/reconcile {entries:[{id, head}]}` → `{missing_entries, missing_revisions}`. The client then pushes `entry.create` for missing entries, its head revision without parents for missing heads (if the server has an older head, both are kept as a conflict; heads still in the outbox go with their real parents) and every field value with its original HLC (stored locally in `entry.field_clocks`), then resets the cursor to 0 for a full pull. Restores bump the epoch past every epoch the data folder has seen (`--restore`, or the startup high-water check for hand-copied files).

## Calendar store (ADR-027, as built 7.3a, server 0.6.0)
Calendars and events sync on their own channel, beside the journal, and never through `entry` ops. Code: `app.benbow.core.calendar` (ops, `CalendarRules`), server `app.benbow.server.calendar`, client `app.benbow.client.calendar`.
- Op `{opId, kind: "calendar"|"event", id, fields:{name: value|null}, hlc}` (`CalendarOp`). Every field in the op takes that HLC and wins per field by LWW; a record exists from its first put (no create op), so an import is one op per event. Delete = `deleted = "true"` (trash), restore clears it. Idempotent by `opId` (the calendar's own `applied_ops`). An unknown kind, an empty id or no fields is `rejected`.
- `POST /api/v1/calendar/push {protocol, ops}` (calendar protocol 1, at most 500 ops, one transaction per op; another protocol answers 409) returns a `PushResponse`. `GET /api/v1/calendar/pull?cursor=&limit=` (at most 500) returns `{changes:[{kind, id, fields:{name:{value, hlc}}}], nextCursor, hasMore}` from the calendar's own change log. A put is logged even when every field lost, so a device that skipped the record while its op was pending pulls the winner. Results: `applied | duplicate | rejected(reason)`; there are no merges or conflicts. Without a calendar store (its file didn't open) both answer 503 `no_calendar` and the journal works as usual.
- `GET /api/v1/info` gains `calendar: {protocol, epoch, cursor}` (additive in v1; absent on older servers and when the calendar file didn't open). Against such a server calendar ops wait in the outbox (`Unsupported`, shown on the server screen).
- Client: `calendar.db` (its own SQLDelight database `CalendarDb`) holds calendars, events (with `calendar`, `title`, `start`, `start_day`, `end_day`, `deleted` derived from their fields), its own outbox, cursor, epoch, store id and HLC. `AppServices.syncAll` runs the journal first and the calendar (`CalendarSync`) only after the journal's sync succeeded; its result is kept apart (`calendarStatus`) and never fails the journal's. Pending and rejected calendar ops count in the sync status; rejected ones are listed and retried with the journal's.
- The cache belongs to one store: another store id stops it (`WrongServer`). Unpairing waits for the calendar's outbox too, then clears it.
- Restore: the calendar file has its own epoch. A new calendar epoch (or a server cursor below the device's) makes the client re-push every field value it has with its original HLC (one op per record and HLC; LWW makes that safe), then pull from 0. No revision reconciliation is needed.
- Tests: `CalendarSimulation` (shared/testing) against the in-memory and the SQLite store; `CalendarClientTest` (two devices, lost responses, an older server, restore, another store, moving 5.1 events); `CalendarApiTest`; `CalendarStoreTest` (files, snapshots, restore).

## Blobs (attachments)
1. Client copies the media to private storage, computes SHA-256, creates the attachment row (`local`) and links it to the entry. The entry can sync before the blob arrives.
2. Upload (WorkManager on Android, resumable, tus-like):
   `HEAD /api/v1/blobs/{sha256}` (already exists? done) → `POST /api/v1/uploads {sha256,size,mime}` → `PATCH /api/v1/uploads/{id}` with `Upload-Offset`, 4–8 MB chunks → server verifies (S7) → `uploaded`.
3. Download: `GET /api/v1/blobs/{sha256}` with HTTP Range (video streaming). Previews: `/thumb?w=`. The client generates its own preview at capture time (images and video frames) and uploads it, so the server doesn't need ffmpeg early on.
4. Client cache policy: LRU by size cap + max age, favorites pinned offline. Settings are configurable (P1). Start weak (thumbnails + recent items).
   Implemented (3.4, `MediaStore.evict`, after each sync): large files (≥ 1 MB) are kept under a cap (phone 2 GB, desktop 10 GB), least recently viewed first; only downloads and captured files the server confirmed more than 7 days ago may go (its backup job has copied them by then); previews always stay. Thumbnails of files not yet on the server carry an amber dot. Later: stream video with Range instead of downloading it whole, pin favorites offline.
6. Implemented on the client (3.2, `MediaStore`): files live in the app's media folder named by SHA-256 (table `media`, origin `captured` or `downloaded`). Import = copy + hash + fsync + atomic rename, then `JournalRepository.attach` records the file, queues `entry.attachment.put` and shows it at once, in one transaction. Uploads run after each successful text sync (8 MB chunks, resume from the server's offset, restart once on a checksum mismatch); a captured file is marked uploaded only after the server verified it. Downloads are verified by hash before they are kept. When a file is placed, the server logs a change for the entries that point at it, so devices and the Markdown export (relative links) learn it's there.
5. Implemented on the server (3.1, `BlobStore`): bytes are content-addressed by SHA-256 (`blobs` table); attachment metadata is separate (3.2). `HEAD|GET /api/v1/blobs/{sha256}` (Range, `ETag`, immutable caching) · `POST /api/v1/uploads {sha256,size,mime,name?,captured_at?}` → `{id|null, offset, complete}` (null id = already there; an open upload of the same file is resumed) · `PATCH /api/v1/uploads/{id}` + `Upload-Offset`, ≤16 MB per chunk, 409 with the right offset on mismatch, 422 when the finished file doesn't match its hash (upload dropped, start over), 507 when the disk lacks size + 1 GB.

## Sync triggers
App start and foreground, connectivity change, local change (debounced 2 s), periodic WorkManager (15 min, network required), manual pull-to-refresh. Exponential backoff with jitter.
Implemented (`SyncController`): request (start/foreground, pull-to-refresh, after pairing), local change after 2 s, every 2 min while in the foreground, backoff 5 s → 5 min with ±20% jitter. Android: default-network callback (back online → sync), WorkManager periodic sync (15 min, network required) and a one-off sync 5 s after leaving the app, which also survives the process being killed.

## Implemented endpoints (server 0.3.0)
`GET /api/v1/info` · `POST /api/v1/pair` (code from the admin page) · bearer: `GET /api/v1/models/speech` (`{id, ready, files:[{name,size,sha256}]}`), `GET /api/v1/models/speech/{name}` (Range; the speech model, 7.6, ADR-025) · bearer-protected: `POST /api/v1/sync/push` (max 500 ops, 409 on protocol mismatch), `GET /api/v1/sync/pull?cursor=&limit=` (≤500), `GET /api/v1/entries/{id}/revisions`, `GET /api/v1/revisions/{id}`, `POST /api/v1/sync/reconcile` (≤2000 entries). Admin (`/`, `/admin/*`) only from loopback and same-origin. Rules: `SyncRules` in `shared/core` over `SqliteSyncStore`; the shared simulation (`shared/testing`) runs against both the in-memory and the SQLite store.

Client side (`shared/client`): `Pairing.probe(host, port)` connects with first-contact trust and shows the certificate fingerprint for the user to compare with the admin page; `Pairing.pair` then talks only to that pinned fingerprint and refuses a different store. Covered by `server/.../EndToEndTest` (real HTTPS: pair, sync, wrong certificate refused with the outbox intact).

## Pairing and security
- Manual: enter `host:port` in the app plus a one-time 6-digit code shown on the admin page (TTL 5 min, single use, burned after 5 wrong guesses). The admin page with codes and devices is served **only to the server PC itself** (loopback), and admin POSTs must be same-origin (CSRF).
- QR: admin page shows `benbow://pair?h=<host>&p=<port>&c=<code>&fp=<cert sha256>`.
- `POST /api/v1/pair {code, device_name, platform}` → long-lived device token (stored hashed on the server, in Keystore-backed storage on Android). `Authorization: Bearer <token>`. Devices can be revoked on the admin page.
- Transport (ADR-012): HTTPS only. The server generates a self-signed certificate on first run. Clients pin its SHA-256 fingerprint from pairing (QR `fp` or shown next to the manual code) and reject any other certificate. A certificate rotation flow re-pairs or confirms the new fingerprint on the admin page.
- Browser (ADR-029, W2): the web client is served by the server at `/app/` and pairs only with that origin (`Pairing.probeOrigin`): no address to type, no fingerprint to compare or pin, since the browser itself has judged the certificate (a one-time warning for the self-signed one) and its fetch can't read it. The user enters only the code; the connection stores an empty fingerprint. The token lives in the page's own database (IndexedDB of that origin). A different address (IP vs host name) is a different origin, so a separate browser device with its own copy.
- Outside the LAN: recommend Tailscale (no code needed).

## API versioning and docs
- Major version in the path (`/api/v1`). Within v1 changes are additive only. Breaking changes go to `/api/v2`, and the server serves both during the transition.
- `protocol` integer for sync semantics. Clients outside `[protocol_min, protocol_max]` must not push. They show "update app/server".
- OpenAPI generated by the Ktor Gradle plugin (compiler extension) + `ktor-server-routing-openapi` annotations. Swagger UI or Scalar at `/api/docs`, spec at `/api/openapi.json`. Changelog in `docs/api-changelog.md` (created with the first endpoint).

## Test plan
A deterministic simulator in `shared/core` + `server` tests: random ops across N clients, network drop, duplicate, reorder, client kill mid-push, server restart, restore-from-backup (epoch bump). Assertions: (a) every revision ever created exists on the server once outboxes drain, (b) all clients converge to the server state, (c) no op is applied twice.

## Speech model
The voice-transcription model (ADR-025) is not journal data and isn't synced or backed up: the server keeps it in `<data>/models/<id>/` after the owner presses «Скачать модель» on the admin page (`SpeechModel`: pinned URLs and hashes from `SpeechModelSpec` in `shared/core`; anything that doesn't match is thrown away). Phones fetch it with `SpeechModelStore` into `files/models/<id>/`, resuming with Range, and check each file against the same pinned hashes (not the server's list); a `verified` marker saves re-hashing 230 MB on every start. Transcripts are the entry field `transcript:<attachment id>`.
