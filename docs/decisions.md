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
Voice notes become text on the phone, offline: GigaAM v3 with punctuation (MIT, sherpa-onnx's official package) plus Silero VAD, run by sherpa-onnx 1.13.8 (official release AAR in `apps/android/libs`, SHA-256 checked by the build; arm64 only). Recognition runs in `AsrService`, an `isolatedProcess` service: no permissions, no network, no app files; model files arrive as descriptors (copied into memfds), audio through a pipe, only text returns. The model reaches the server only on the owner's click on the admin page (pinned URLs, parallel ranges, pinned SHA-256 of the archive and of each unpacked file; the server's only internet request), and phones fetch it only from the paired server, checking the same pinned hashes in their own build. Text lives in the LWW field `transcript:<attachment id>` (ADR-021, no protocol change), is indexed for search and exported as a quote under the sound. Rejected: Android `SpeechRecognizer` (live dictation, vendor-dependent, no file input guarantee), ML Kit GenAI (alpha, Pixel 10 only), Whisper on the phone (slower for Russian).
