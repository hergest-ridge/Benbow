# Data model (draft v0)

Conventions: IDs are UUIDv7 as text (client-generated, so offline creation works). `*_at` = UTC epoch ms. `day` = ISO local date.
Soft delete via `deleted_at`. Metadata fields carry `hlc` for LWW. The server is canonical. Clients mirror it and add local-only tables (see sync.md).

## entry — one table for all text content
| Field | Notes |
|---|---|
| id | UUIDv7 |
| kind | `journal` (long, day-anchored) · `post` (feed, short) · `note` (quick capture) · `comment` · `system` · `event` · `reminder` · `checkin` (see "As built" notes below) |
| parent_id | for comments: the entry being commented on (retrospective replies). As built (4.3): the LWW field `parent`, mirrored on the client into an indexed `parent_id` column; comments are hidden from Home/Feed lists, shown in the thread and counted on the feed |
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
- Inline `#hashtags` in text as tags: not wanted for now (owner, 2026-10-13). Tags stay metadata chips.
- As built (4.3): categories live in the system entry `system-tags` (kind `system`, no revisions, created by a single idempotent `entry.create`), fields `cat:<tag key>` = category; defaults Люди, Места, Проекты, Эмоции. System entries are hidden from lists, search and the export.
- Tag colors (2026-10-13): registry fields `color:<tag key>` (a tag's own color) and `catcolor:<category, lowercase>` (a category's color). Values are color names from Google Keep's note palette (`coral peach sand mint sage fog storm dusk blossom clay`), never hex; the app maps each name to a light and a dark shade. A tag shows its own color, else its category's (defaults: Люди storm, Места mint, Проекты dusk, Эмоции blossom; other categories get one from the name), else a neutral chip. Tags are drawn as chips without "#".
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
- As built (5.1): an entry of kind `event` (the text is the title, `day` is its date) with the LWW field `start` = "YYYY-MM-DD" (all day) or "YYYY-MM-DDTHH:MM". Events stay out of Home, Feed and the writing stats; the calendar lists them per day. End time, repeats and ICS come later.
- `reminder(id, target_type (entry|event|none), target_id?, fire_at?, rrule?, message, done_at?, deleted_at)`. Scheduled locally on each device (no server push).
- As built (5.4): a mood check-in is an entry of kind `checkin`: the field `mood` (-2..2), the chosen feeling words as ordinary tags in category Эмоции, the text is the user's words or else the feelings. It is a normal listed entry (Home, Feed, calendar mood tint).
- As built (5.2): an entry of kind `reminder` (text = message) with LWW fields `at` = local "YYYY-MM-DDTHH:MM", `repeat` = daily|weekly, `done`. Repeats are computed forward from `at` (nothing is rewritten when one rings). Each device rings its own copy: Android inexact alarms (5-minute window, no exact-alarm permission, rescheduled after reboot and after each ring), a tray message on desktop while the app runs. Target links come later.

## Templates (P1)
`template(id, name, kind, body (Document JSON, placeholders such as {{date}} {{weekday}} {{prompt}} {{month}} inside span text), default_tags, suggest_rule?)`.
As built (5.3): built-in templates in code (`Templates`: Итоги дня, Три хороших события, Свободное письмо, Разбор мысли, Итоги недели, Итоги месяца, Письмо себе через год) rendered to a Document with placeholders filled; offered as chips in a new, empty entry. The prompts library (`Prompts`, by practice) gives the question of the day on Home. User-made templates come later.

## Ink (P2)
`ink_layer(id, entry_id, head_ink_rev)`, `ink_revision(id, layer_id, parents, format_version, strokes_blob)` (see editor.md).

## System
- `device(id, name, platform, token_hash, created_at, last_seen_at, revoked_at)`
- `change_log(seq INTEGER PRIMARY KEY AUTOINCREMENT, entity, entity_id, op, at, device_id)`
- `applied_ops(op_id PRIMARY KEY, device_id, at, result)`
- `store_meta(key, value)`: `store_id`, `epoch`, settings
- Search: `entry_fts` (FTS5, `unicode61 remove_diacritics 2`, prefix queries in v1, Russian Snowball stems in an extra column in P2).
