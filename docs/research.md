# Research snapshot (2026-10-12)

Re-verify exact versions at scaffold time. Facts below were gathered from web sources on the date above.

## Kotlin / Compose
- **Kotlin 2.4.0** (June 2026): context parameters, explicit backing fields stable, stable `Uuid` API, Wasm incremental compilation on by default.
- **Compose Multiplatform 1.12.1** (latest stable as of Sept 2026) maps to Jetpack Compose 1.12.1. Platforms: Android API 21+, Windows 10+, macOS 13+, Linux, web (WasmGC browsers), iOS 14+.
- **Web target is Beta** (since CMP 1.9.0, Sept 2025). No stable announcement found. CMP 1.11 reworked web touch scrolling and renamed `WebElementView` → `HtmlElementView` (DOM interop). CMP 1.12 added automatic web font fallback that **downloads Noto subsets on demand**, so bundle fonts for offline LAN use.
- CMP 1.10: common `@Preview`, Navigation 3 on all targets, **Compose Hot Reload stable**.
- CMP 1.12: **experimental MCP server in Compose Hot Reload** (AI agents can reload, take screenshots, inspect the semantics tree, simulate input, read logs on desktop) and desktop Window API v2 (experimental).
- **Material 3 in CMP:** CMP 1.12 ships `org.jetbrains.compose.material3:1.12.0-alpha03` (based on Jetpack Material3 1.5.0-alpha22). `MaterialExpressiveTheme`, `MotionScheme.expressive()` and others exist behind `@ExperimentalMaterial3ExpressiveApi`. Expect API churn, so wrap them.
- **Jetpack Compose 1.12** (BOM 2026.08.00, Aug 2026): **rich text editing in `BasicTextField`**: `TextFieldBuffer.addStyle(SpanStyle|ParagraphStyle)`, `getSpanStyles()/getParagraphStyles()` → `TrackedRange`, `TextFieldState.textStyles`, `originalTextStyles`. Formatting and custom annotations persist across config changes. Also `SelectionState` for `SelectionContainer`. Requires compileSdk 37, AGP 9.1.1+. Not stated: undo of styles, inline content in text fields (verify in the spike).
- Rich editor libraries: **compose-rich-editor** (MohamedRejeb) 1.0.0-rc14 (Compose 1.10.3), `RichTextState`, Markdown/HTML import/export, CMP. **ComposeTextEditor** (Wavesonics/Darkrock) reimplements text entry from scratch because `BasicTextField` used to be too limited.

## Persistence (KMP)
- Room 3.0 alpha: wasmJs runtime resolves, but room-paging lacks wasmJs.
- SQLite in the browser (checked 2026-10-15): `@sqlite.org/sqlite-wasm` 3.53.4 has FTS5 and JSON but no FTS4; sql.js 1.14.2 (SQLite 3.49.1) has FTS3/FTS4, unicode61 and JSON, synchronous API; its `export()` closes and reopens the DB (statements freed), about 20 ms for 50 MB in Node.
- Browsers checked for the web client (2026-10-16, headless with fresh profiles via CDP / WebDriver BiDi): Edge 155 and Firefox 156 run the production build under its CSP; both have WasmGC, Web Locks, `DecompressionStream('deflate-raw')`, `OffscreenCanvas.convertToBlob`, WebCrypto and notifications. Kotlin/Wasm needs WasmGC: Chrome/Edge 119+, Firefox 120+, Safari 18.2+. kotlinx-datetime 0.8 on wasm uses @js-joda/core 3.2.0; @js-joda/timezone 2.23.0 is the last release for core 3.x (2.24+ need core 5.7).
- Coil 3.6 on wasm decodes pictures in a Web Worker it starts from a `blob:` URL: a CSP without `worker-src 'self' blob:` blocks it and every picture stays blank (found on the owner's PC, 0.7.0 → 0.7.1). Check web changes on the production build behind its CSP, with pictures on screen.
- Compose for Web 1.12 fetches missing glyphs (emoji, rare scripts) from fonts.gstatic.com (`NotoFontDownloader`, installed for every viewport, not configurable); preloading our own fonts through `LocalFontFamilyResolver.preload` before the first frame avoids it, and a CSP blocks the rest (ADR-030). Fontsource's Noto Color Emoji carries COLRv1 and an SVG table; skiko draws the SVG glyphs blank, so the SVG table is dropped at build time.
- Compose resources on wasm load asynchronously: a text is empty and a string array is an empty list on the first frame unless preloaded (`getString` fills the per-item cache).
- SQLDelight web worker driver is in-memory by default. OPFS persistence is community-maintained (May 2026). Teams report moving web to thin clients. → Web = thin client (ADR-003).

## Ktor
- Ktor 3.4.x docs: OpenAPI generation through the **compiler extension in the Ktor Gradle plugin** (`ktor { openApi { ... } }`) + runtime annotations `ktor-server-routing-openapi`. Swagger UI plugin can serve the spec built from routing (`OpenApiDocSource.Routing`).

## Monbooru (reference for standalone LAN apps)
Go, AGPL-3.0, v1.9.3 (May 2026), Linux-only, repo moved to `github.com/monbooru/monbooru`.
- Pure-Go SQLite (`modernc.org/sqlite`), **one SQLite file per gallery**, DB + thumbnails in a separate data dir. **Media stays as ordinary files in place** (removing the app leaves images intact). SHA-256 dedup.
- Pragmas (`internal/db/db.go`): `busy_timeout(5000)`, `foreign_keys(on)`, `journal_mode(wal)`, `synchronous(normal)`, `cache_size(-1024)`, `temp_store(memory)`, `mmap_size(64MB)`. **Single write connection + read pool** ("WAL serialises writers").
- Idempotent bootstrap migrations tracked with `PRAGMA user_version`, `PRAGMA optimize` at startup, `VACUUM` + `wal_checkpoint(TRUNCATE)`.
- Distribution: single binary ("lite") or bundled with ffmpeg + ONNX Runtime. Docker optional. REST API under `/api/v1` (off by default), **OpenAPI spec + built-in docs**, optional password, LAN-only (not hardened for the internet).
- **What we take:** standalone binary model, SQLite WAL + single writer, separating data dir from media, files kept as ordinary files, SHA-256, user_version migrations, built-in API docs. **Where we differ:** `synchronous=FULL`, append-only revisions, verified backups, Windows service, sync protocol.

## Pinned in the scaffold (2026-10-12, see gradle/libs.versions.toml)
Gradle 9.8.1 · Kotlin 2.4.21 · AGP 9.4.1 (compileSdk 37) · CMP 1.12.1 (material3 1.12.0-alpha03, Hot Reload bundled: `hotRun`, `hotDev`, `hotMcpServer`) · Ktor 3.6.0 · kotlinx-serialization 1.11.0 · coroutines 1.11.0 · kotest 6.2.5 · logback 1.6.5 · JVM toolchain 17.
Tooling: Spotless 8.10.4 + ktlint 1.8.0 · server runtime Temurin 25.0.4.1 LTS (jlink) · WinSW v2.12.0 (WinSW.NET461.exe).
Available but not yet used: SQLDelight 2.4.1, Koin 4.2.2, kotlinx-datetime 0.8.0, Okio 3.18.2, sqlite-jdbc 3.53.4.0, CMP lifecycle 2.11.0, CMP navigation3 1.1.2, Roborazzi 1.76.0, androidx.sqlite bundled 2.7.1, WorkManager 2.12.0.
AGP 9 notes: KMP libraries use `com.android.kotlin.multiplatform.library` with `kotlin { android { ... } }`. The Android app module uses built-in Kotlin (no `kotlin-android` plugin).
Web observation: Cyrillic text renders on wasm through the font fallback, but that downloads fonts. Bundle fonts for offline LAN use.

## Sources
- https://blog.jetbrains.com/kotlin/2026/06/kotlin-2-4-0-released/
- https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html
- https://kotlinlang.org/docs/multiplatform/whats-new-compose-112.html
- https://kotlinlang.org/docs/multiplatform/whats-new-compose-111.html
- https://kotlinlang.org/docs/multiplatform/whats-new-compose-110.html
- https://blog.jetbrains.com/kotlin/2025/09/compose-multiplatform-1-9-0-compose-for-web-beta/
- https://android-developers.googleblog.com/2026/08/jetpack-compose-august-2026-release.html
- https://kotlinlang.org/api/compose-multiplatform/material3/androidx.compose.material3/-material-expressive-theme.html
- https://ktor.io/docs/openapi-spec-generation.html
- https://github.com/monbooru/monbooru (redirect from leqwin/monbooru), https://pkg.go.dev/github.com/leqwin/monbooru@v1.9.3
- https://slack-chats.kotlinlang.org/t/33164808/room-3-0-wasmjs-room3-paging-missing-wasmjs-target-how-to-us
- https://klibs.io/project/Wavesonics/ComposeTextEditorLibrary
