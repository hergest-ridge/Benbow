# Benbow

Personal journal / life-feed app ("my feed of everything"). Single user, many devices.
Android app (primary), desktop + web clients, self-hosted LAN server on Windows (standalone, autostart, no Docker).

**Status:** Phases 2 (safe core MVP), 3 (attachments) and 4 (feed, metadata, search, home) done: 2.1 server storage/sync/pairing, 2.2 backups, 2.3 client data layer (`shared/client`), 2.4 screens (`shared/app`), 2.5 done. Phase 3 (attachments): 3.1 server blob store and 3.2 sync + client media engine done. 3.3 Android capture/UI, 3.4 cache policy + drills done. Phases 2-4 done (Phase 4: metadata, search, tabs, Home, Feed, comments, share/copy, tag categories). Phase 5 done (calendar, reminders, templates + prompts, mood check-ins and reviews), then the owner's first UX feedback round (More tab, motion, reading mode, colored tags). Phase 6: 6.1 capture from anywhere, 6.2 widget, 6.3 export (ZIP, HTML/PDF), 6.4 app lock, 6.5 themes + note colors + icon, 6.6 desktop polish, 6.7 performance pass (R8, baseline profile, macrobenchmarks) done. Phase 7: 7.1 Russian stemming in search, 7.2 inline pictures + swipeable media, 7.5 drawings in notes, 7.6 on-device voice transcription done. 7.3 calendar done: 7.3a its own store (ADR-027, server 0.6.0), 7.3b calendar layer UI, 7.3c ICS codec + export (ADR-028, server 0.6.1), 7.3d import from Google. Now Phase W, the web client (ADR-029, the same app compiled to wasm): W1 foundation (sql.js DB saved to IndexedDB), W2 (served at `/app/`, pairing by code, sync) and W3 (writing, media as Blobs in IndexedDB, players) W4 (list-detail on wide windows, addresses and Back, web keys, bundled emoji + CSP, ADR-030) and W5 (ZIP export from the browser, calendar import with zones, reminders while open, checked in Edge and Firefox) done: Phase W complete. Server 0.7.2 carries the owner's first web feedback round (EXIF-turned photos, carousel buttons, draggable divider, hand cursor). Now 7.8 Бережно (ADR-031: spoilers, curtains, quiet entries): 7.8a spoiler mask, 7.8b curtain, quiet entries and the hidden switch, 7.8c rules and «Ещё → Бережно» done; next 7.8d «Отложить до…». Later: 7.4 importers (open question 13), 7.7 backup encryption.
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
- `./gradlew :apps:web:wasmJsBrowserDevelopmentRun`: web client dev server on 8080; it proxies `/api` to a dev server (`BENBOW_DEV_SERVER`, default `https://127.0.0.1:8797`; `.tmp/web-server` has `port=8797`, `host=127.0.0.1`).
- `./gradlew :server:webApp`: production web build for the server (`/app/`), ~15 min the first time (wasm optimization). `packageServer` includes it.
- `./gradlew :server:run --args="--data <dir>"`: HTTPS server on 8765 (`/`, `/api/v1/info`, `/api/docs`).
- `./gradlew :server:packageServer`: self-contained Windows server folder in `server/build/package/benbow-server` (needs `server/packaging/winsw/WinSW.NET461.exe`, SHA-256 checked).
- `./gradlew spotlessApply`: format Kotlin/KTS (ktlint). `check` fails on violations.
- `./gradlew :apps:android:installBenchmark`: non-debuggable R8 build for performance checks (`app.benbow.bench`, own DB; fill it with `adb shell am start -W -n app.benbow.bench/app.benbow.android.BenchSeedActivity`).
- `./gradlew :apps:benchmark:connectedBenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.benbow.benchmark.StartupBenchmark` (or `FeedBenchmark`): macrobenchmarks on the phone; add `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true` to keep the seeded bench app. One class per run (a comma list runs only the first).
- Baseline profile: `./gradlew :apps:benchmark:connectedProfilingAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.benbow.benchmark.BaselineProfileGenerator`, then copy `*-baseline-prof.txt` from `apps/benchmark/build/outputs/connected_android_test_additional_output/profiling/` to `apps/android/src/main/baseline-prof.txt`.

## Environment gotchas (this machine)
- If a JVM fails with "Unable to establish loopback connection", Java can't create unix sockets in `%TEMP%`. Run Gradle with `export TEMP='C:\projects\Benbow\.tmp' TMP='C:\projects\Benbow\.tmp' JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=C:\projects\Benbow\.tmp'` (gitignored dir). TEMP/TMP matter for Gradle worker daemons (SQLDelight); after a failure run `./gradlew --stop` once.
- `GRADLE_USER_HOME=C:\Android\.gradle`. `ANDROID_HOME` points to a missing path, so `local.properties` (gitignored) sets `sdk.dir`.
- Invisible characters (U+2028, U+200B, ...) are written as `Char(0x2028)`, never as literals or `' '`: the formatter turns escapes into invisible literals.
- Git Bash rewrites `/sdcard/...` in adb arguments; prefix with `MSYS_NO_PATHCONV=1`. To pair the phone with a dev server without touching the firewall: `adb reverse tcp:<port> tcp:<port>` and connect to `127.0.0.1:<port>`.
- `curl` (schannel) can't reach `dl.google.com`, but Java can. Use Java/Gradle for Google Maven lookups.
- Stopping a background `:server:run` or `wasmJsBrowserDevelopmentRun` leaves the app process (java/node) alive. Free ports 8765/8080 with `netstat -ano` + `taskkill //PID <pid> //F //T`.
- GitHub's file host throttles one long stream to a crawl: download big files in parallel byte ranges (a Java `HttpClient`, as the server's `HttpFetch` does).
- The owner's real server runs as a service on port 8765. A dev server for phone tests gets its own data dir with `port=8799` (and `host=127.0.0.1`) in `benbow.properties`; check the certificate fingerprint before pairing anything.
- Macrobenchmarks refuse to run while Android Studio mirrors the phone (`DEVICE-MIRRORING`); for relative comparisons add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=DEVICE-MIRRORING`.
- Testing the web app in the browser pane: while the pane is hidden the browser doesn't fire `requestAnimationFrame`, so Compose stops drawing and applying screens (clicks after typing get lost, screenshots lag). In the test tab run `window.requestAnimationFrame = cb => setTimeout(() => cb(performance.now()), 16)` (again after every reload, including the dev server's live reload), and check results through JS, IndexedDB or the dev server's DB rather than screenshots. Files for tests are generated in JS (canvas PNG, MediaRecorder webm, WAV) and handed over with synthetic `paste`/`drop` events.
- The dev web server has no CSP; the server's `/app/` has one (ADR-030). Check web changes on the production build (`:server:webApp`, then `:server:run` and a local HTTP proxy to it) with pictures on screen: 0.7.0 shipped with Coil's decoding worker blocked.
- On a clean build, `kotlinWasmStoreYarnLock` may fail with "yarn.lock doesn't exist". Run `./gradlew kotlinWasmNpmInstall` once, then retry.
