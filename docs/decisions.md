# Decisions (ADR log)

Status: **P** = proposed, **A** = accepted, **R** = under review, **S** = superseded. Keep each entry ≤10 lines. Append, don't rewrite history.
Owner review on 2026-10-12: everything accepted. ADR-005 is superseded by ADR-015. ADR-006 is still gated by the spike.

### ADR-001 Kotlin everywhere [A]
Clients and server in Kotlin 2.4. Shared `shared/core` (models, content codec, merge, sync DTOs).
Alternatives: Go or Rust server (single binary, but duplicates models and sync logic in a second language, which means more AI context and drift).

### ADR-002 Compose Multiplatform for all clients [A]
One UI codebase for Android, Desktop (JVM) and Web (wasmJs). Web is still Beta (canvas-rendered, weaker browser-native text features, multi-MB bundle), which is acceptable for a personal LAN app.
Alternatives: native Android + separate TS web app (two UIs), Flutter (Dart, worse Android-native feel and no Kotlin sharing with the server).

### ADR-003 Desktop-first iteration, Android right behind, web thin [A]
Desktop JVM is the main dev target (Hot Reload + MCP). Android builds from the first scaffold, so an installable debug APK exists from Phase 1 (the owner wants mobile ASAP). Web is online-only, served by the server at `/app`, compiled in CI from day one, polished later.

### ADR-004 SQLite + SQLDelight on server and clients [A]
Server: WAL, `synchronous=FULL`, single writer + read pool (Monbooru pattern, but stricter durability). FTS5 for search. Android must use a SQLite build with FTS5 (bundled driver). Verify in Phase 1.

### ADR-005 Canonical content = restricted Markdown subset [S → ADR-015]
The owner rejected limiting formatting to what Markdown can express (2026-10-12).

### ADR-006 Editor = single rich `BasicTextField` (Compose 1.12 styles API) [A, 2026-10-12]
Seamless selection, native IME, works on all targets. The spike passed on the S24+ (editor.md "Spike findings"). Architecture: model-first (`RichText` + own undo), styles rendered in `OutputTransformation`, zero-width glyphs at style boundaries, IME bridge and clipboard repair. **Scale limit:** smooth up to ~5k chars; long-form text (writer module) will need a chunked or `EditText`-based engine.

### ADR-007 Sync = outbox ops + append-only revision DAG + server diff3 merge + HLC LWW metadata [A]
No CRDT. Conflicts keep both heads. Store identity and epoch protect against restores and wrong servers.

### ADR-008 Server = jlink runtime + WinSW Windows service [A]
Refined in the spike: runtime is Temurin 25 LTS via jlink (JBR has no jmods; jpackage is unnecessary), and WinSW v2.12.0 (NET461, SHA-256 pinned in the build) starts `java.exe`. The server sets `jdk.net.unixdomain.tmpdir` itself (some Windows profiles break NIO in %TEMP%).
Autostart before login, restart on failure, no Docker, no system JRE. GraalVM native-image optional later.

### ADR-009 Attachments = immutable files with human-readable paths in a user-chosen folder [A]
`<media>/YYYY/MM/YYYY-MM-DD_HHMMSS_<shortid>.<ext>`, SHA-256 verified, deduplicated in DB, resumable chunked upload.

### ADR-010 Backups from v1 [A]
Verified SQLite snapshots (GFS retention) + mirror to a **second local disk** (DB + media) + human-readable export mirror. No cloud target for now.

### ADR-011 REST/JSON `/api/v1` + generated OpenAPI + `/api/docs` [A]
Additive-only within v1. Separate `protocol` integer for sync semantics.

### ADR-012 Pairing + bearer tokens + TLS from v1 [A]
One-time code / QR → device token. Self-signed certificate generated on first server run. Clients pin its SHA-256 fingerprint received during pairing. Web: one-time browser certificate exception.

### ADR-013 Docs and code in English, conversation in Russian [A]
English needs roughly half the tokens of Russian for the AI workflow. App UI strings in Russian (resources, i18n-ready).

### ADR-014 One `entry` entity with `kind` (journal/post/note/comment) [A, "for now"]
Shared editing, revisions, attachments, tags, search. Views are queries.

### ADR-015 Canonical content = structured JSON document based on Portable Text + exporters [A]
Formatting isn't limited by Markdown. Storage is a 1:1 serialization of the editor model (no parsing round-trip). Every block has a stable `_key`, which enables precise merges, ink anchoring and fragment links. Markdown, plain text, HTML and PDF become exporters. The export mirror writes `.md` + `.json` per entry. Colors and sizes are theme tokens.
Alternatives: plain MD (too limiting), MD + inline HTML (fragile), MD dialect with attributes like Djot/Pandoc (custom parser, other apps show noise anyway).

### ADR-016 Android baseline [A]
Target device Galaxy S24+ (Android 16, 120 Hz). minSdk 34, target/compile = latest stable. Performance budgets are measured on this device. IME tests cover Samsung Keyboard and Gboard.

### ADR-017 Formatting scope v1 + palette tokens [A]
Inline: bold, italic, underline, strike, code, highlight, text color, size steps, spoiler, link, entry link. Blocks: h1–h3, align, quote, callout, checklist, nested lists, divider, code block. Later: inline media, tables, toggles as atomic embeds. Colors and sizes are theme tokens (dark mode and note-background aware), never raw hex. Spec: content-format.md.

### ADR-018 Identity: the "Admiral Benbow" inn [A]
A cozy home base and Jim Hawkins's journal (Treasure Island, public domain). It shapes the icon and light visual accents. Original art only, no Disney assets. See vision.md#identity.

### ADR-019 Server restore = new epoch + reconciliation from device caches [A]
Restoring a snapshot (`--restore`, or a hand-copied file noticed via `db/high-water.properties`) moves the store to an epoch past every one this data folder has seen. Devices that see a new epoch (or a server cursor below theirs) ask which of their entries and heads the server lacks and re-push them, then pull from 0. A re-pushed head goes without parents: if the server kept an older head, both stay as a conflict and the user picks. Devices don't keep revision ancestry, and a silent fast-forward could hide edits made after the restore.

### ADR-020 Attachments: content-addressed files + LWW links in the entry [A]
Files are stored and transferred by SHA-256 (dedup, verification, resumable upload); the server places them at human-readable paths (ADR-009). The link to an entry is an `AttachmentState` in the entry, last writer wins per link by HLC, and carries the immutable file meta, so one op type (`entry.attachment.put`, protocol 2) covers add, reorder, caption and remove (`present = false`). A link may sync before its file; clients show it as pending.

### ADR-021 Entry metadata = namespaced LWW fields [A]
Favorite, pinned, mood, tags, reactions and a comment's parent are `entry.meta.set` fields: `favorite`, `pinned`, `mood`, `tag:<lowercase name>` (value = display name, null = removed), `reaction:<emoji>`, `parent` (later: `start`, `at`, `repeat`, `done`, `background`). Each key has its own HLC, so concurrent changes to different tags merge, and restore reconciliation already re-pushes fields. No protocol change. Tag categories and colors (4.3) live in a registry, not in the field key. Replaces the planned `entry.tag.set` / `reaction.set` ops.

### ADR-022 App lock = the platform's owner check, per device [A]
The lock (6.4) uses Android's `BiometricPrompt` with `BIOMETRIC_STRONG | DEVICE_CREDENTIAL`: fingerprint or face, with the phone's screen lock as the way in. No app PIN, so nothing secret is stored and a forgotten code can't lock the owner out of their own diary; without any screen lock on the device the app doesn't lock. It gates the UI only (files rely on device encryption), never applies to quick capture (it shows nothing), lives in local settings (not synced), starts locked in every new process and re-locks after 1 min / 5 min / 1 h / 5 h away. Recents shows a blank card while it is on. Desktop: none yet.

### ADR-023 Moving an entry in time = the LWW field `when` [A]
An entry's `day` and `createdAt` come from `entry.create` and never change (the server and the mirror keep them, so mirror file names stay stable). Moving it sets the field `when` = local "YYYY-MM-DDTHH:MM"; each client rewrites its local `day` and `created_at` columns from it after every local change and every pull (`Moments`), so lists, the calendar and the day view show it there on every device. No protocol change; restore reconciliation re-pushes it like any field. The export's front matter carries `when` next to the original `day`.

### ADR-024 Release = R8 without obfuscation, baseline profile from a non-minified build [A]
Release (and the `benchmark` build) shrink and optimize with R8 (`proguard-android-optimize`, resource shrinking) but `-dontobfuscate`: crash traces from the owner's phone stay readable, class names in error messages stay true, and the baseline profile matches code as written. The profile is collected by `BaselineProfileGenerator` on the `profiling` build (same as `benchmark`, no R8) and committed as `src/main/baseline-prof.txt`; R8 rewrites it for its own changes. No androidx.baselineprofile Gradle plugin: its generated `benchmarkRelease` build would carry the release id and key, i.e. install over the owner's app. Regenerate after big UI changes (command in CLAUDE.md). R8 was verified on the bench build: pairing and a full sync of 430 entries and 240 files against a dev server.

### ADR-025 Voice transcription = GigaAM v3 on the phone, in a sandbox, model only from the own server [A]
Voice notes become text on the phone, offline: GigaAM v3 RNN-T with punctuation (MIT; sherpa-onnx's official ONNX package), run on ONNX Runtime 1.30 (Maven Central, arm64 only) in `AsrService`, an `isolatedProcess` service: no permissions, no network (checked on the S24+: DNS and sockets refused), no app files. The model arrives as descriptors and is mmapped into `createSession(ByteBuffer)` (the sandbox can't open files by path, so sherpa-onnx itself, path-only, was dropped); audio comes through a pipe; only text returns. Kaldi fbank, piece cutting at quiet moments and greedy decoding are ours (`app.benbow.core.speech`, after sherpa-onnx's reference script). ONNX Runtime's telemetry provider is removed from the manifest; in the sandbox it can't reach the network anyway. The model reaches the server only on the owner's click (pinned URL, parallel ranges, pinned SHA-256 of the archive and each file; the server's only internet request); phones fetch it only from the paired server and check the same pinned hashes in their own build. Text lives in the LWW field `transcript:<attachment id>` (ADR-021), is searchable and exported as a quote. Measured: ~1 s to load, 20× faster than real time. Rejected: Android `SpeechRecognizer` (live dictation, vendor-dependent), ML Kit GenAI (alpha, Pixel 10 only), Whisper (slower for Russian).

### ADR-026 Drawings = SVG attachments with their strokes inside [A]
The owner chose drawings in notes, by finger, as the first ink feature (paper pages and margin marks stay in editor.md for later). A drawing is an attachment of kind `drawing`: a plain SVG (filled pen outlines and see-through marker lines in the light theme's colors, so any browser or Obsidian shows it) with the strokes themselves as JSON in `<metadata><benbow-ink>` (canvas 1000 units wide, points x/y/t, tool, palette token, size), which the app reads to edit and to draw in the theme's colors. It sits in the text as an `attachment` block like a picture and syncs like one: no protocol change. Attachment meta is immutable, so a redraw saves a new attachment, the text points at it and the old link becomes absent; the server never deletes files, so earlier versions stay. A finger has no pressure: pen width follows speed. Geometry and the codec live in `app.benbow.core.ink` (tested); the drawing screen is a navigation route that hands its result to the editor through `AppServices.drawn`, saved from `ViewModel.onCleared` (not on rotation).

### ADR-027 Calendar = its own store beside the journal; ICS by file [A]
Owner, 2026-10-15: calendar events are context for a day, not journal content; they are worth less than entries and will outnumber them, so the two must not share storage or code paths. The calendar gets its own store: server `db/calendar.sqlite` (calendars, events, change log, applied ops, schema version, migrations, epoch), endpoints `/api/v1/calendar/push|pull` with their own cursor, client `calendar.db` with its own outbox. The journal DB is never touched by calendar code. Records are LWW per field (HLC); no revisions, merges or conflict heads. Kept cheap guarantees: op ids, outbox until acknowledged, soft delete, verified backups (own snapshots; a calendar failure never stops the journal's). Shared: server process, pairing and token, HTTPS, HLC, backup disk. The journal syncs first; a calendar failure doesn't block it. 5.1 events (entries of kind `event`) move once to «Мои события» with the same ids and the old entries go to the trash. Reminders stay entries for now.
UI: events live only in the Calendar tab (calendars with colors and show/hide checkboxes like Google, quiet dots and multi-day bars under the journal's dots and mood tint, a compact strip in the day, an event sheet instead of the editor, «Написать об этом» opens a journal entry); never in Home, Feed, search, stats, tags or the journal export.
ICS: import from files only (Google's export ZIP holds one `.ics` per owned calendar; each becomes a calendar), a preview with per-calendar checkboxes and a period (all by default), dedup by UID, batch undo to the trash; export one `.ics` per calendar (Calendar tab, archive ZIP, server mirror), reminders not exported. Own codec in `shared/core` (ical4j is JVM-only). URL subscriptions later, only through the server.
Rejected: events as entries (every journal query must filter them out, cf. the `daysWritten` bug; event schema changes would migrate the journal DB; imports would write into it) and separate tables in the journal DB (still the journal's file and migrations, one sync stream).

### ADR-028 ICS = floating local times out, the device's zone in [A]
Benbow keeps event times as local wall time without a zone ("2026-10-14T09:30"), like the rest of the app. Exports (7.3c: the server's `export/calendars`, the app's ZIP and "Сохранить .ics") write them as floating DATE-TIMEs (no TZID, no Z) and all-day events as DATEs with an exclusive DTEND, so nothing is invented: Google, Apple and Outlook place floating times in the calendar's own zone, and weekly 09:30 stays 09:30 across DST without writing VTIMEZONE blocks (kotlinx-datetime can't produce their rules). Imports (7.3d) convert TZID and UTC times into the importing device's zone. UID = the event's own `uid` from an import, else `<id>@benbow`, so re-imports elsewhere update instead of duplicating; Benbow's color travels as `X-BENBOW-COLOR`. The codec is ours, in `shared/core` (`Ics`, `CalendarExport`): checked on 2026-10-15, there is still no Kotlin Multiplatform iCalendar library (only JVM ones, e.g. iCalDAV's icalendar-core).

### ADR-029 Web = the same app compiled to wasm, a full client for PCs on the LAN [A]
Owner, 2026-10-15: the web version becomes a priority, used only on PCs in the home network; offline at most shallow. It is not a second UI: `shared/client` and `shared/app` gain a wasmJs target, so the browser runs the same Compose screens, view models, repositories, sync and calendar as desktop and Android. Only platform pieces differ behind expect/actual, as between Android and desktop: the HTTP engine (the browser's fetch, which also judges the self-signed certificate after a one-time exception), the SQLite driver, files and pickers, playback (the browser's own video/audio element). The local DB is SQLite built for wasm (sql.js 1.14, SQLite 3.49: the official sqlite-wasm build has no FTS4, which search uses), in memory on the main thread behind a synchronous SQLDelight driver (the official web driver is asynchronous and would turn every query of the client into a suspend call), with its whole file saved to IndexedDB shortly after writes (a put is atomic, so the stored file is always a complete snapshot; one tab owns the data via Web Locks, a second tab is refused): a reload starts fast and a short server outage loses nothing; closing the tab with unsent changes asks first. Served by the server at `/app`, paired with a code like any device. Supersedes ADR-003's "web thin, online-only, shared/ui only". Not on web: voice transcription, the widget, app lock (for now); reminders only while the tab is open.

### ADR-030 The web page reaches nothing but its own server [A]
2026-10-16 (W4): Compose for Web fetches glyphs it lacks (emoji, rare scripts) from fonts.gstatic.com at run time, which would tell Google when and from where the journal is open. Two layers stop it. The server sends a Content-Security-Policy with `/app/` (`connect-src 'self'`, `font-src 'self' data:`, scripts only from itself plus `'wasm-unsafe-eval'`, workers only from itself or `blob:`: Coil decodes pictures in one), so the browser refuses any other host whatever a library tries. And the app bundles Noto Color Emoji (OFL, from `@fontsource/noto-color-emoji`, Google's ten slices with the SVG table dropped at build time: Skia draws its COLRv1 glyphs but leaves SVG ones blank) and preloads it before the first frame, so emoji draw without a network. A character no bundled font has shows as a box instead of being fetched. 2026-10-17: so the UI's own texts keep to Latin, Cyrillic, common punctuation and emoji; arrows, stars, triangles and other symbols are drawn as icons (`Icons.kt`, `EditorIcons.kt` in shared/ui), and `UiGlyphsTest` fails on any other character in the app's strings. Symbols the owner types into notes may still show as boxes on the web; bundling Noto Sans Symbols 2 and Math the way emoji are bundled would cover them.

### ADR-031 Бережно: the entry says what it is, the moment decides what shows [A]
Owner, 2026-10-16: some entries are sad or triggering yet part of the journal; sometimes they belong in view, sometimes not. Three tools, mildest first. The inline spoiler: text under a shimmering particle mask wherever rich text is drawn; a tap opens all of the entry's spoilers until it leaves the screen; in the editor it is frosted and clear at the cursor. The curtain: the entry keeps its place but shows only its time at one compact height, without tags, mood, pictures or even its length. Quiet: gone from passive places, with no gap, count or marker.
Marks: by hand per entry (synced LWW field `veil`), and rules by tag, tag category or a named period of days (a synced registry, each rule with a level; new rules start as curtain). The entry's own mark wins over the rules; `open` shows it despite them. A mood rule later; never guessing from the text. Passive places hide (Feed, Home with On this day and the resurfaced favorite, widget, notifications); places the owner searches show quiet entries under the curtain (search, a calendar day, entry links), so nothing ever seems lost.
The mode is per device, on by default. Turned off, it comes back by itself after 5 minutes away and in every new process: opening is temporary, closing lasts. The switch is out of sight: pull the Feed down at its top (like Telegram's archive) or long-press the Feed tab; desktop and web also click the Feed's title. Nothing in the feed says that something is hidden.
It is a view only: text, revisions, sync, backups, stats and exports are untouched (the export carries `veil`). "Отложить до…" (quiet until a day, then a calm card on Home) comes last. No fingerprint for curtains (owner).

### ADR-032 Tags and tag categories by id; the old keys are the ids [A]
Owner, 2026-10-16, before real tag and category editors: names as keys tie everything to a spelling, and Бережно's rules (ADR-031) pointed at names, so a rename would have let quiet entries out. Now a tag is its id: the entry's LWW field `tag:<id>` holds the name it was put on with, the registry (`system-tags`) has `name:<id>` once the tag has a name of its own, and that wins. Categories too: `cat:<tag id>` = the category's id, `catname:<id>`, `catcolor:<id>`.
No entry is rewritten. A tag from before keeps its key (the name in lower case) as its id, a category its lower-case name, so colors, categories and rules work as they did and an older journal reads exactly as before; new tags and categories get new ids (a uuid). A full rewrite onto uniform ids was rejected: no visible gain, the riskiest kind of change (every tagged entry, from every device), and late ops from older versions would need translating. It stays possible later.
Rules point at ids; one made before by name also matches by name, so a rule can cover more, never less. 7.9 T2: everything done to a tag or category is a registry field and entries never change, so each step is undone exactly: `into:<id>` merges a tag into another (entries read as the target, the old name leads to it, `splitTag` undoes it; merges in a circle from two devices end at the smallest id everywhere), `trash:<id>` / `cattrash:<id>` put them in the trash (gone from chips, lists and suggestions; back from «Корзина»; «Эмоции» can't go), `archived:<id>` keeps a tag out of suggestions only. Бережно's rules see the ids an entry carries, through merges, the trash and trashed categories: nothing done to tags lets an entry out. 7.9 T5: exports carry the registry itself as `tags.md` and `tags.json` (`TagBook`), so ids never have to be read by a person. Names are not keys any more: two tags may share one (two «Саша»); typing a name picks the tag with it. Exports list tags by the registry's names (the server's mirror reads the registry and writes again the notes of a renamed tag); the `.json` sidecar gets `tags: [{id, name}]`. Protocol unchanged. Older app versions show new tags by the name on the entry but can't take them off: update every device.
