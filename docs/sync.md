# Sync and API

Single owner, several devices, server on LAN, often unreachable. Goal: offline-first clients, never lose data (data-safety S4, S5, S8, S11), simple to debug.

## Why not a CRDT
Conflicts are rare with one human. CRDTs (Automerge, Loro, Yjs) add opaque binary state, bindings that aren't mature on every KMP target, and harder export and debugging. An append-only revision DAG + 3-way merge gives the same "never lose" guarantee with plain, inspectable data. We can revisit this for real-time co-editing, which is a non-goal.

## Client local state
- `cache` tables: mirror of server entities (head revision body included).
- `draft`: in-progress editor text per entry, autosaved on a debounce. Not synced.
- `outbox`: ordered pending ops `(op_id, local_seq, type, entity_id, payload, created_at, attempts, last_error, state)`.
- `sync_state`: `store_id`, `epoch`, `cursor`, `protocol`.
- A local write = cache update + outbox insert in one transaction.

## Revisions from drafts
A draft becomes a revision on: editor close, app backgrounded, 30 s idle, or every 2 min of continuous typing. Op: `entry.revision.put {entry_id, rev_id, parents:[base_rev_id], title, body_md, body_sha256}`.

## Op types (v1)
`entry.create`, `entry.meta.set {field, value, hlc}` (day, kind, favorite, pinned, mood, appearance, space),
`entry.revision.put`, `entry.delete` / `entry.restore {hlc}`, `entry.tag.set {tag_id, present, hlc}`,
`entry.attachment.set {attachment_id, position, caption, present, hlc}`, `reaction.set {emoji, present, hlc}`,
`tag.upsert`, `tag_category.upsert`, `attachment.register`, `template.*`, `event.*`, `reminder.*`.
Metadata fields use per-field LWW ordered by a **Hybrid Logical Clock** (`hlc` = wall ms + counter + device id), so it tolerates phone clock drift.

## Push
`POST /api/v1/sync/push {protocol, device_id, ops:[...]}`. Ops are applied in order. Each op runs in its own transaction and is deduplicated by `op_id`.
Per-op result: `applied | duplicate | merged | conflict | rejected(reason)`. `rejected` stays in the client's "needs attention" list (S5).

## Revision conflicts (server)
When `parents[0] != head`:
1. Store the incoming revision anyway (DAG sibling). It is never lost.
2. Try a diff3 merge (base = parent, ours = head, theirs = incoming) at the paragraph/line level, using the shared code in `shared/core`.
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

## Blobs (attachments)
1. Client copies the media to private storage, computes SHA-256, creates the attachment row (`local`) and links it to the entry. The entry can sync before the blob arrives.
2. Upload (WorkManager on Android, resumable, tus-like):
   `HEAD /api/v1/blobs/{sha256}` (already exists? done) → `POST /api/v1/uploads {sha256,size,mime}` → `PATCH /api/v1/uploads/{id}` with `Upload-Offset`, 4–8 MB chunks → server verifies (S7) → `uploaded`.
3. Download: `GET /api/v1/blobs/{sha256}` with HTTP Range (video streaming). Previews: `/thumb?w=`. The client generates its own preview at capture time (images and video frames) and uploads it, so the server doesn't need ffmpeg early on.
4. Client cache policy: LRU by size cap + max age, favorites pinned offline. Settings are configurable (P1). Start weak (thumbnails + recent items).

## Sync triggers
App start and foreground, connectivity change, local change (debounced 2 s), periodic WorkManager (15 min, network required), manual pull-to-refresh. Exponential backoff with jitter.

## Pairing and security
- Manual: enter `host:port` in the app plus a one-time 6-digit code shown on the admin page (TTL 5 min).
- QR: admin page shows `benbow://pair?h=<host>&p=<port>&c=<code>&fp=<cert sha256>`.
- `POST /api/v1/pair {code, device_name, platform}` → long-lived device token (stored hashed on the server, in Keystore-backed storage on Android). `Authorization: Bearer <token>`. Devices can be revoked on the admin page.
- Transport (ADR-012): HTTPS only. The server generates a self-signed certificate on first run. Clients pin its SHA-256 fingerprint from pairing (QR `fp` or shown next to the manual code) and reject any other certificate. A certificate rotation flow re-pairs or confirms the new fingerprint on the admin page.
- Outside the LAN: recommend Tailscale (no code needed).

## API versioning and docs
- Major version in the path (`/api/v1`). Within v1 changes are additive only. Breaking changes go to `/api/v2`, and the server serves both during the transition.
- `protocol` integer for sync semantics. Clients outside `[protocol_min, protocol_max]` must not push. They show "update app/server".
- OpenAPI generated by the Ktor Gradle plugin (compiler extension) + `ktor-server-routing-openapi` annotations. Swagger UI or Scalar at `/api/docs`, spec at `/api/openapi.json`. Changelog in `docs/api-changelog.md` (created with the first endpoint).

## Test plan
A deterministic simulator in `shared/core` + `server` tests: random ops across N clients, network drop, duplicate, reorder, client kill mid-push, server restart, restore-from-backup (epoch bump). Assertions: (a) every revision ever created exists on the server once outboxes drain, (b) all clients converge to the server state, (c) no op is applied twice.
