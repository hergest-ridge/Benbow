# Benbow

Personal journal / life-feed app ("my feed of everything"). Single user, many devices.
Android app (primary), desktop + web clients, self-hosted LAN server on Windows (standalone, autostart, no Docker).

**Status:** Phases 2 (safe core MVP) and 3 (attachments) done: 2.1 server storage/sync/pairing, 2.2 backups, 2.3 client data layer (`shared/client`), 2.4 screens (`shared/app`), 2.5 done. Phase 3 (attachments): 3.1 server blob store and 3.2 sync + client media engine done. 3.3 Android capture/UI, 3.4 cache policy + drills done. Next: Phase 4 (feed, metadata, search, home).
**Privacy:** never pick, open or copy the owner's photos, camera or microphone for testing; ask the owner to do hands-on media checks.

## Non-negotiables
1. **No data loss or corruption, ever.** Read `docs/data-safety.md` before touching storage, sync, migrations, deletion or backups. Never weaken an invariant to make something pass. Ask instead.
2. **UX quality is a core feature.** It should feel native, instant and smooth (Telegram-level). See `docs/ux.md`.
3. **Portability.** Content is stored as Benbow Document (Portable Text-based JSON, `docs/content-format.md`), and the export mirror (Markdown + JSON) is always readable without the app. No lock-in.

## Docs map (read only what the task needs)
| File | Contents |
|---|---|
| `docs/vision.md` | What and why, feature list with priorities, references, non-goals |
| `docs/architecture.md` | Stack, modules, runtime topology, packaging, libraries, testing |
| `docs/data-model.md` | Entities and fields (draft) |
| `docs/data-safety.md` | Invariants, storage layout, revisions, backups, migrations, recovery |
| `docs/sync.md` | Offline outbox, push/pull protocol, conflicts, blobs, pairing, API versioning |
| `docs/content-format.md` | **Canonical content format** (Portable Text-based JSON): schema, tokens, canonical serialization, exports |
| `docs/editor.md` | Editor approach, formatting scope, text mapping, ink-layer plan, spike checklist |
| `docs/ux.md` | UX principles, screens, motion, journaling psychology |
| `docs/decisions.md` | ADR log. Check it before re-deciding anything |
| `docs/roadmap.md` | Phases, current phase, exit criteria |
| `docs/open-questions.md` | Items waiting on the user |
| `docs/research.md` | Tech status snapshot (2026-10) with sources. Re-verify versions when scaffolding |

## Working rules (token economy)
- Talk to the user in **Russian**. Write code, comments, docs and commit messages in **English**.
- Don't redo research that `docs/research.md` already covers, unless exact versions matter for the task.
- Work in small vertical slices. Each slice ends green on `./gradlew check` (once the project is scaffolded).
- When a contract or decision changes, update the matching doc in the same change. Record new decisions in `docs/decisions.md` (≤10 lines each).
- Keep files small and modules cohesive. No speculative abstractions, no premature module splits.
- Verify UI with screenshot tests (Roborazzi) or the Compose Hot Reload MCP on desktop, not by reading large amounts of code.
- Use subagents only for broad searches. Keep the main context lean.

## Commands
- `./gradlew check`: the single gate (tests for all modules + Android lint).
- `./gradlew :apps:desktop:run`: desktop app. `./gradlew :apps:desktop:hotRun`: desktop app with Compose Hot Reload. `hotMcpServer`: MCP server so an agent can screenshot and drive the running app.
- `./gradlew :apps:android:installDebug`: dev build `app.benbow.debug` ("Benbow Dev", own local DB). The owner's real app is the release build `app.benbow` (`installRelease`, signed with the key from `tools/create-release-key.ps1`, kept in `%USERPROFILE%\.benbow`). Never test against the owner's real app or pair it with a throwaway server.
- `./gradlew :shared:app:jvmTest`: headless UI smoke test; screenshots land in `shared/app/build/screens` (look at them after UI changes).
- `./gradlew :apps:web:wasmJsBrowserDevelopmentRun`: web client dev server.
- `./gradlew :server:run --args="--data <dir>"`: HTTPS server on 8765 (`/`, `/api/v1/info`, `/api/docs`).
- `./gradlew :server:packageServer`: self-contained Windows server folder in `server/build/package/benbow-server` (needs `server/packaging/winsw/WinSW.NET461.exe`, SHA-256 checked).
- `./gradlew spotlessApply`: format Kotlin/KTS (ktlint). `check` fails on violations.
- `./gradlew :apps:android:installBenchmark`: non-debuggable build for performance checks (`app.benbow.bench`).

## Environment gotchas (this machine)
- If a JVM fails with "Unable to establish loopback connection", Java can't create unix sockets in `%TEMP%`. Run Gradle with `export TEMP='C:\projects\Benbow\.tmp' TMP='C:\projects\Benbow\.tmp' JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\projects\Benbow\.tmp'` (gitignored dir). TEMP/TMP matter for Gradle worker daemons (SQLDelight); after a failure run `./gradlew --stop` once.
- `GRADLE_USER_HOME=C:\Android\.gradle`. `ANDROID_HOME` points to a missing path, so `local.properties` (gitignored) sets `sdk.dir`.
- Invisible characters (U+2028, U+200B, ...) are written as `Char(0x2028)`, never as literals or `' '`: the formatter turns escapes into invisible literals.
- Git Bash rewrites `/sdcard/...` in adb arguments; prefix with `MSYS_NO_PATHCONV=1`. To pair the phone with a dev server without touching the firewall: `adb reverse tcp:<port> tcp:<port>` and connect to `127.0.0.1:<port>`.
- `curl` (schannel) can't reach `dl.google.com`, but Java can. Use Java/Gradle for Google Maven lookups.
- Stopping a background `:server:run` or `wasmJsBrowserDevelopmentRun` leaves the app process (java/node) alive. Free ports 8765/8080 with `netstat -ano` + `taskkill //PID <pid> //F //T`.
- On a clean build, `kotlinWasmStoreYarnLock` may fail with "yarn.lock doesn't exist". Run `./gradlew kotlinWasmNpmInstall` once, then retry.
