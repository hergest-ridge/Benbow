# Data model (draft v0)

Conventions: IDs are UUIDv7 as text (client-generated, so offline creation works). `*_at` = UTC epoch ms. `day` = ISO local date.
Soft delete via `deleted_at`. Metadata fields carry `hlc` for LWW. The server is canonical. Clients mirror it and add local-only tables (see sync.md).

## entry — one table for all text content
| Field | Notes |
|---|---|
| id | UUIDv7 |
| kind | `journal` (long, day-anchored) · `post` (feed, short) · `note` (quick capture) · `comment` · `system` · `event` (5.1; moves to the calendar store in 7.3a, ADR-027) · `reminder` · `checkin` (see "As built" notes below) |
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
- By id (ADR-032, 2026-10-16): an entry's `tag:<id>` = the name the tag was put on with; the registry's `name:<id>` (once set) is the name it shows as everywhere. Tags from before keep their key (the name in lower case) as their id, so no entry was rewritten; new tags get a uuid and a `name:` at once. Categories by id the same way: `cat:<tag id>` = category id (older values hold its name, which in lower case is the id), `catname:<id>`, `catcolor:<id>`; the built-in ones are `люди`, `места`, `проекты`, `эмоции` (check-ins' feelings). Names are not unique; typing a name finds the tag with it (its own name first, then an older tag whose key it is), else makes a new one. `TagRegistry` in shared/client reads it all; `TagNames` in shared/core gives the export the same reading.
- Tag and category records (7.9 T2, all registry fields by id, entries untouched): a tag has `name:`, `desc:` (a few words), `color:`, `cat:`, `archived:` ("true": out of suggestions, still on entries and in search), `into:` (merged into that tag: its entries read as the target, its name leads there; null splits it again) and `trash:` (when it went to the trash, epoch ms: gone from entries' chips, lists, suggestions and search; the merged tags go with it). A category has `catname:`, `catdesc:`, `catemoji:`, `catcolor:`, `catsort:` (its place; others follow the built-in order, then by name) and `cattrash:` (its tags show without a category; their links stay). A merge first gives both tags a name of their own (the one their entries hold most), so every device reads them alike. Бережно's rules match the ids an entry carries and the tags they were merged into, and the categories linked to either, whether in the trash or not. Code: `JournalTags.kt` (shared/client).
- Attachment order (2026-10-14): gallery order = attachments placed in the text (their order there), then the rest by their LWW `position`; moving one between two neighbours sets its position to the midpoint (`moveAttachment`).
- Moving in time (2026-10-14, ADR-023): the LWW field `when` = local "YYYY-MM-DDTHH:MM" overrides where an entry shows (its day and order); the original day and creation time stay in the entry.
- Бережно (ADR-031): the LWW field `veil` = `curtain` | `quiet` (absent = shown as usual); `veil_until` = a local day for "Отложить до…" (as built 7.8d: quiet until that day over its own mark; from that day it shows with its own mark and is listed for Home's card until answered, which clears the field; the client compares with the journal's day, `JournalRepository(today = …)`). Rules live in the system entry `system-gentle`, fields `rule:<id>` = JSON `{name, kind: tag|category|period, value, from, to, level: off|curtain|quiet}` (a tag's or a category's id since ADR-032; a rule from before names it, any case, and matches by that name as well as by the id it gives in lower case; a period's days both included). As built (7.8c): `veil` may also be `open` (shown although a rule covers it); what an entry shows as is its own mark, else the strongest rule (quiet over curtain). The client applies the rules to every list it hands out (`GentleRules.apply` in `JournalRepository`), and the month grid's days are counted in code so its moods follow them. Whether the mode is on is the device's own and lives in memory only (as built 7.8b: every new process starts on; off comes back after 5 minutes away). Nothing is ever filtered out of sync, backups, stats or exports; the export's front matter carries `veil` like any field.
- Note background (6.5): the LWW field `background` = a Keep color name (same `coral … clay` list as tag colors), null = the theme's own. Cards, feed posts, the editor and the post view take it in the current light or dark shade; it lands in the export's front matter like any field.
- Tag colors (2026-10-13): registry fields `color:<tag id>` (a tag's own color) and `catcolor:<category id>` (a category's color; ADR-032: for categories from before, the name in lower case). Values are color names from Google Keep's note palette (`coral peach sand mint sage fog storm dusk blossom clay`), never hex; the app maps each name to a light and a dark shade. A tag shows its own color, else its category's (defaults: Люди storm, Места mint, Проекты dusk, Эмоции blossom; other categories get one from the name), else a neutral chip. Tags are drawn as chips without "#".
- As built (4.1, ADR-021): the link is the LWW field `tag:<lowercase name>` = display name on the entry; categories and colors (4.3) go to a registry. Reactions: field `reaction:<emoji>`. Since ADR-032 the key after `tag:` is the tag's id.

## Reactions
`reaction(entry_id, emoji, present, hlc)`. "Likes" on your own posts, Telegram-style.

## Attachments
- `attachment(id, sha256, size, mime, kind (image|video|audio|voice|file), original_name, width, height, duration_ms, captured_at, storage_path (server, relative to media root), preview_sha256?)`
- `entry_attachment(entry_id, attachment_id, position, caption?, present, hlc)`
- Client-only: `attachment_local(attachment_id, local_path, state (local|uploading|uploaded|evicted), last_access_at, pinned)`
- As built (3.2): `AttachmentMeta` (the `attachment` fields, immutable but for a later preview) + `AttachmentState(meta, position, caption, present, hlc)` stored per entry as `attachments` (server `entries.attachments`, client `entry.attachments`). Bytes are separate: server `blobs(sha256, size, mime, path, verified_at)`, client `media(sha256, path, origin, uploaded, last_access_at)`.

## Calendar (P1)
- As built (5.1), until 7.3a: an entry of kind `event` (the text is the title, `day` is its date) with the LWW field `start` = "YYYY-MM-DD" (all day) or "YYYY-MM-DDTHH:MM". Events stay out of Home, Feed and the writing stats; the calendar lists them per day.
- As built (7.3a, ADR-027): a **separate calendar store** (server `db/calendar.sqlite`, client `calendar.db`), never the `entry` table. Two record types, each a set of LWW fields (value + HLC) with no revisions. In use: `calendar`, `title`, `start`, `end`, `description`, `deleted` (7.3a); `rrule`, `exdate`, `location` and the calendar records (7.3b); `uid` and `import` (7.3d):
  - `calendar(id, name, color, visible, deleted)`: color is a Keep palette name like tag colors; `visible` is the show/hide checkbox and syncs. The built-in `mine` («Мои события») holds events made in Benbow and has no record until it is renamed or recolored (so a fresh device can't overwrite a rename); each imported calendar is its own record.
  - `event(id, calendar, title, start, end?, rrule?, exdate?, location?, description?, uid?, import?, deleted)`: `start`/`end` are local "YYYY-MM-DD" (all day) or "YYYY-MM-DDTHH:MM" like the rest of the app (ICS times are converted to the device's zone on import); `rrule` is an RFC 5545 RRULE subset (DAILY/WEEKLY/MONTHLY/YEARLY, INTERVAL, COUNT, UNTIL, BYDAY, BYMONTHDAY) with `exdate` as a list of skipped starts; `description` is plain text; `uid` is the ICS UID (dedup on re-import); `import` is the import batch id (undo). Unknown fields survive.
  - Derived, indexed columns on the device: `start_day`, `end_day` (an event shows on every day of its range), plus `calendar`, `title`, `start`, `deleted`. Repeating events are expanded in code for the visible range, never stored as copies (`Recurrence`; DAILY may carry BYDAY for "weekdays", MONTHLY a numbered BYDAY like `2TU` or `-1FR`; COUNT counts skipped days too, as RFC 5545 does). As built (7.3b): a repeating event's `end_day` is its UNTIL plus its length, or `9999-12-31`, so the range query finds it with no extra column; a rule outside the subset shows the event once and stays untouched.
  - As built (7.3b): a calendar without a color of its own gets a steady one from its id (`mine`: sage). A hidden (`visible = "false"`) or deleted calendar hides its events; deleting one sends it to the calendar's trash with them. "Only this day" of a series adds the day to `exdate`; the event sheet edits a series as a whole and sends only the fields that changed.
  - Exports (7.3c, ADR-028): `start`/`end` go out as floating DATE-TIMEs or DATEs (all-day DTEND = the day after the last), `rrule` as is, `exdate` as EXDATE (with the start's time for a timed series), `uid` or `<id>@benbow` as UID, the calendar's `name` as X-WR-CALNAME and `color` as X-BENBOW-COLOR. Field names live in `shared/core` (`EventField`, `CalendarField`), shared by the server and the app.
  - Imports (as built 7.3d, `IcsImport`, `CalendarRepository.import`): TZID and UTC times become the importing device's local time (ADR-028), a zone it doesn't know (Outlook's Windows names) is kept as written and counted; an all-day DTEND loses its exclusive day, DURATION gives the end; a changed day of a series (RECURRENCE-ID) goes into the series' `exdate` and comes in as its own event with `uid` = "<uid>/<original start>", a cancelled one only into `exdate`; cancelled events stay out; no SUMMARY → «Без названия». An event whose `uid` is already here (any calendar, the trash included) is left out. A calendar from the file goes into the calendar of the same name (case-insensitive; «Мои события» is the built-in one until renamed), else into a new one with the color picked in the preview; the batch id is in `import` on every new event and calendar.
  - Moving 5.1 events (as built, `moveJournalEvents` on every start): each becomes an event in `mine` with the same id (first line of the text = `title`, the rest = `description`, `start` as is); the old entry goes to the trash (soft delete), nothing is erased. An event already in the calendar isn't written again, so devices can move the same entries.
- `reminder(id, target_type (entry|event|none), target_id?, fire_at?, rrule?, message, done_at?, deleted_at)`. Scheduled locally on each device (no server push).
- As built (5.4): a mood check-in is an entry of kind `checkin`: the field `mood` (-2..2), the chosen feeling words as ordinary tags in category Эмоции, the text is the user's words or else the feelings. It is a normal listed entry (Home, Feed, calendar mood tint).
- As built (5.2): an entry of kind `reminder` (text = message) with LWW fields `at` = local "YYYY-MM-DDTHH:MM", `repeat` = daily|weekly, `done`. Repeats are computed forward from `at` (nothing is rewritten when one rings). Each device rings its own copy: Android inexact alarms (5-minute window, no exact-alarm permission, rescheduled after reboot and after each ring), a tray message on desktop while the app runs. Target links come later.

## Templates (P1)
`template(id, name, kind, body (Document JSON, placeholders such as {{date}} {{weekday}} {{prompt}} {{month}} inside span text), default_tags, suggest_rule?)`.
As built (5.3): built-in templates in code (`Templates`: Итоги дня, Три хороших события, Свободное письмо, Разбор мысли, Итоги недели, Итоги месяца, Письмо себе через год) rendered to a Document with placeholders filled; offered as chips in a new, empty entry. The prompts library (`Prompts`, by practice) gives the question of the day on Home. User-made templates come later.

## Ink (P2)
`ink_layer(id, entry_id, head_ink_rev)`, `ink_revision(id, layer_id, parents, format_version, strokes_blob)` (see editor.md), for paper pages and margin marks later.
As built (7.12, ADR-035): a sound file from elsewhere is an attachment of kind `audio`; its tags read on attaching go into the entry's LWW field `track:<attachment id>` = `{"title","artist","album"}` (only those it has), its embedded cover becomes the attachment's preview (a JPEG up to 1024 px). A track whose tags name an artist is music and is never transcribed.

As built (7.5, ADR-026): drawings in notes are attachments of kind `drawing` (an SVG with the strokes in its metadata); a redraw is a new attachment, the old link turns absent and its file stays.

## System
- `device(id, name, platform, token_hash, created_at, last_seen_at, revoked_at)`
- `change_log(seq INTEGER PRIMARY KEY AUTOINCREMENT, entity, entity_id, op, at, device_id)`
- `applied_ops(op_id PRIMARY KEY, device_id, at, result)`
- `store_meta(key, value)`: `store_id`, `epoch`, settings
- `system-home` (ADR-034): Home's layout, a system entry like the tag registry: LWW fields `block:<id>` = `{"type", "size", "config"}` JSON and `order` = the ids, comma-separated. No fields = the default layout. A tag block's `config`: `tag` (the tag's id), `order` (`random`, or none for newest first). A stats block's: `figures` (comma-separated: `days`, `moments`, `pictures`, `tags`, `since`, `mood`; none = the first three). On this day's: `also` (`months`: a month and half a year ago too). Code: `HomeLayout.kt` (shared/client).
- Search: `entry_fts` (FTS5, `unicode61 remove_diacritics 2`, prefix queries in v1, Russian Snowball stems in an extra column in P2).
- As built (7.1): FTS4 `entry_search`; each entry's body and tags are indexed followed by the Snowball Russian stems that differ from their words (no schema change; index version 3 rebuilds it). A typed word matches its stem exactly or its beginning ("моря" → `мор OR моря*`): all forms, not "морковь", and partial words still match while typing. One MATCH per word (four, then the rest as plain prefixes) keeps OR unambiguous in both SQLite FTS query syntaxes.
