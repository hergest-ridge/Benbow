# Data model (draft v0)

Conventions: IDs are UUIDv7 as text (client-generated, so offline creation works). `*_at` = UTC epoch ms. `day` = ISO local date.
Soft delete via `deleted_at`. Metadata fields carry `hlc` for LWW. The server is canonical. Clients mirror it and add local-only tables (see sync.md).

## entry — one table for all text content
| Field | Notes |
|---|---|
| id | UUIDv7 |
| kind | `journal` (long, day-anchored) · `post` (feed, short) · `note` (quick capture) · `comment` |
| parent_id | for comments: the entry being commented on (retrospective replies) |
| space_id | default `journal`. Reserved for the writer's archive (P3) |
| day | local date the entry belongs to. Default = local date of creation with the "day starts at" offset (e.g. 04:00) |
| created_at, tz | creation instant + IANA zone at creation (correct display while travelling) |
| updated_at | last change of anything |
| head_rev_id | current revision. Body and title live in revisions |
| conflict_heads | JSON array of revision ids when an unresolved conflict exists |
| favorite, pinned | bool |
| mood, energy | small ints (e.g. −2..+2), nullable. Emotion words via tags of category "Emotions" |
| appearance | semantic tokens `{bg: "sand", pattern: "paper-grid"}`, theme-adaptive (Keep-like) |
| template_id | origin template, nullable |
| deleted_at | trash |
| location, weather | P2, nullable |

Why one entity: posts, notes, journal entries and comments share editing, revisions, attachments, tags and search. The feed, day and calendar views are queries over `kind`/`day`. A quick note can later be "promoted" by changing `kind`.

## revision — immutable
`id, entry_id, parents (JSON, 1 normally, 2 for merges), title, body (canonical Document JSON, see content-format.md), body_v (format version), body_sha256, created_at, device_id, origin (edit|merge|restore|import)`.
The server denormalizes the head into `entry_text(entry_id, title, plain_text)` for FTS5.

## Tags
- `tag_category(id, name, color, sort)`: e.g. People, Places, Projects, Emotions.
- `tag(id, name, category_id?, color?, parent_id? (P2 hierarchy), deleted_at)`. Name is unique case-insensitively within a category.
- `entry_tag(entry_id, tag_id, present, hlc)`: LWW per pair.
- Idea (decide later): inline `#hashtags` in text auto-link to tags (`#tag` is plain portable Markdown text).
- As built (4.1, ADR-021): the link is the LWW field `tag:<lowercase name>` = display name on the entry; categories and colors (4.3) go to a registry. Reactions: field `reaction:<emoji>`.

## Reactions
`reaction(entry_id, emoji, present, hlc)`. "Likes" on your own posts, Telegram-style.

## Attachments
- `attachment(id, sha256, size, mime, kind (image|video|audio|voice|file), original_name, width, height, duration_ms, captured_at, storage_path (server, relative to media root), preview_sha256?)`
- `entry_attachment(entry_id, attachment_id, position, caption?, present, hlc)`
- Client-only: `attachment_local(attachment_id, local_path, state (local|uploading|uploaded|evicted), last_access_at, pinned)`
- As built (3.2): `AttachmentMeta` (the `attachment` fields, immutable but for a later preview) + `AttachmentState(meta, position, caption, present, hlc)` stored per entry as `attachments` (server `entries.attachments`, client `entry.attachments`). Bytes are separate: server `blobs(sha256, size, mime, path, verified_at)`, client `media(sha256, path, origin, uploaded, last_access_at)`.

## Calendar (P1)
- `event(id, title, start_at, end_at?, all_day, tz, rrule?, entry_id?, source (local|ics:<feed_id>), external_uid?, deleted_at)`
- `reminder(id, target_type (entry|event|none), target_id?, fire_at?, rrule?, message, done_at?, deleted_at)`. Scheduled locally on each device (no server push).

## Templates (P1)
`template(id, name, kind, body (Document JSON, placeholders such as {{date}} {{weekday}} {{prompt}} {{month}} inside span text), default_tags, suggest_rule?)`.

## Ink (P2)
`ink_layer(id, entry_id, head_ink_rev)`, `ink_revision(id, layer_id, parents, format_version, strokes_blob)` (see editor.md).

## System
- `device(id, name, platform, token_hash, created_at, last_seen_at, revoked_at)`
- `change_log(seq INTEGER PRIMARY KEY AUTOINCREMENT, entity, entity_id, op, at, device_id)`
- `applied_ops(op_id PRIMARY KEY, device_id, at, result)`
- `store_meta(key, value)`: `store_id`, `epoch`, settings
- Search: `entry_fts` (FTS5, `unicode61 remove_diacritics 2`, prefix queries in v1, Russian Snowball stems in an extra column in P2).
