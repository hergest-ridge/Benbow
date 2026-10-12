# Decisions (ADR log)

Status: **P** = proposed (waiting for the owner), **A** = accepted, **S** = superseded. Keep each entry ≤10 lines. Append, don't rewrite history.

### ADR-001 Kotlin everywhere [P]
Clients and server in Kotlin 2.4. Shared `shared/core` (models, Markdown codec, merge, sync DTOs).
Alternatives: Go or Rust server (single binary, but duplicates models and sync logic in a second language, which means more AI context and drift).

### ADR-002 Compose Multiplatform for all clients, Android primary [P]
One UI codebase for Android, Desktop (JVM) and Web (wasmJs). Web is still Beta (canvas-rendered, weaker browser-native text features, multi-MB bundle), which is acceptable for a personal LAN app.
Alternatives: native Android + separate TS web app (two UIs), Flutter (Dart, worse Android-native feel and no Kotlin sharing with the server).

### ADR-003 Web client is thin and served by the server [P]
No offline storage on web (KMP web SQLite persistence is immature). Served at `/app`. Desktop JVM is the primary dev/iteration target (Hot Reload + MCP), and web compiles in CI from day one.

### ADR-004 SQLite + SQLDelight on server and clients [P]
Server: WAL, `synchronous=FULL`, single writer + read pool (Monbooru pattern, but stricter durability). FTS5 for search. Android must use a SQLite build with FTS5 (bundled driver). Verify in Phase 1.
Alternatives: Room KMP (annotation-driven, FTS5 only via raw SQL), Postgres (needs a classic server, rejected).

### ADR-005 Canonical content = restricted Markdown subset [P]
Body is Markdown (see editor.md table). Metadata lives in DB columns and becomes YAML front matter on export. Unknown constructs are preserved as opaque blocks.

### ADR-006 Editor = single rich `BasicTextField` (Compose 1.12 styles API) [P, gated by spike]
Seamless selection, native IME, works on all targets. Fallbacks in editor.md. WebView/ProseMirror rejected.

### ADR-007 Sync = outbox ops + append-only revision DAG + server diff3 merge + HLC LWW metadata [P]
No CRDT. Conflicts keep both heads. Store identity and epoch protect against restores and wrong servers.

### ADR-008 Server packaging = jlink/jpackage app-image + WinSW service [P]
Autostart before login, restart on failure, no Docker, no system JRE. GraalVM native-image optional later.
Alternative: tray process started at logon (simpler, but only runs while logged in). See open-questions.

### ADR-009 Attachments = immutable files with human-readable paths in a user-chosen folder [P]
`<media>/YYYY/MM/YYYY-MM-DD_HHMMSS_<shortid>.<ext>`, SHA-256 verified, deduplicated in DB, resumable chunked upload.

### ADR-010 Backups from v1 [P]
Verified SQLite snapshots (GFS retention) + second-location mirror (DB + media) + human-readable Markdown mirror.

### ADR-011 REST/JSON `/api/v1` + generated OpenAPI + `/api/docs` [P]
Additive-only within v1. Separate `protocol` integer for sync semantics.

### ADR-012 Device pairing + bearer tokens [P]
One-time code / QR → device token. Transport security (TLS + pinning vs HTTP on LAN) is open.

### ADR-013 Docs and code in English, conversation in Russian [P]
English needs roughly half the tokens of Russian for the AI workflow. App UI strings in Russian (resources, i18n-ready).

### ADR-014 One `entry` entity with `kind` (journal/post/note/comment) [P]
Shared editing, revisions, attachments, tags, search. Views are queries.
