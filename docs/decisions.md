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

### ADR-006 Editor = single rich `BasicTextField` (Compose 1.12 styles API) [P, gated by spike]
Seamless selection, native IME, works on all targets. Fallbacks in editor.md. WebView/ProseMirror rejected.

### ADR-007 Sync = outbox ops + append-only revision DAG + server diff3 merge + HLC LWW metadata [A]
No CRDT. Conflicts keep both heads. Store identity and epoch protect against restores and wrong servers.

### ADR-008 Server = jlink/jpackage app-image + WinSW Windows service [A]
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
