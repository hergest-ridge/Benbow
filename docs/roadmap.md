# Roadmap

Vertical slices. Each phase ends with a usable, tested increment. **Current phase: 7** (Phases 0-6 done by 2026-10-14). Next slice: 7.3c ICS codec + export (ADR-027).

## Phase 0: Foundations (docs) ✅ done 2026-10-12
Docs, research, ADRs accepted by the owner.

## Phase 1: Scaffold + de-risking spikes
1. ✅ (2026-10-12; Spotless + ktlint 1.8 `intellij_idea` style, part of `check`) Gradle multi-module KMP skeleton (modules from architecture.md), version catalog, convention plugins, `./gradlew check`, formatting/lint (ktlint or detekt), `.editorconfig`, fill the CLAUDE.md "Commands" section. Android app shell installable on the S24+ from the first scaffold (ADR-003).
2. ✅ (2026-10-12, ADR-006 accepted, findings in editor.md) **Editor spike** (editor.md checklist) on Desktop + Android (S24+, Samsung Keyboard and Gboard), then a Web sanity check. Scope: ADR-017 (editor.md).
3. ✅ (2026-10-12; HTTPS, store identity, /api/docs with inferred schemas, packaged folder verified with its own runtime. Installing the service is the owner's step: `install-service.cmd` as admin) **Server packaging spike:** Ktor + SQLite hello-world → jlink/jpackage app-image → WinSW service. Survives reboot, restarts on crash, serves `/api/v1/info` and `/api/docs`.
4. ✅ (2026-10-12, `shared/core/.../sync`: SyncRules over SyncStore, diff3 block merge, HLC, simulation of 1000 histories with faults) **Sync core:** `shared/core` HLC, diff3 merge, op model + the deterministic simulation test harness (no UI).
5. ✅ (2026-10-12, `shared/core/.../content`, 24 tests × JVM/Android/Wasm) Document codec (content-format.md): decode/encode, canonicalizer, plain-text + Markdown exporters, property tests.
**Exit:** ✅ spikes passed, ADR-006 and ADR-008 accepted (with refinements).

## Phase 2: Safe core MVP (text only, dogfood-ready)
Slices: **2.1 server storage + sync + pairing ✅** · **2.2 backups ✅** · **2.3 client data layer (local DB, outbox, sync engine, pinned TLS) ✅** · **2.4 screens (connect, journal by day, editor autosave, quick note, history, trash) ✅** · **2.5 drills (kill/airplane/restore), restore + reconciliation, background sync, Keystore token ✅**.
Server: entries, revisions, tags (basic), change_log, push/pull, pairing, backups (snapshots + verification + retention + Markdown mirror), integrity checks, admin page, OpenAPI docs.
Android: connect/pair, journal by day, editor with autosave drafts, quick-note composer, offline outbox, sync status, revision history (view/restore), trash.
Desktop: same screens via shared UI.
**Exit:** the owner can journal daily for real. Kill/airplane/restore drills pass (data-safety runbook).

## Phase 3: Attachments
Photo/video picker, camera, voice recorder (hold-to-record), resumable background upload, server blob store in a user-chosen folder, client previews, gallery in entries, basic cache policy, media viewer.
Slices: **3.1 server blob store (content-addressed, resumable chunked upload, verified placement S7, Range download, media mirror + scrub) ✅** · **3.2 attachment metadata in sync (protocol 2) + client attachment store and upload/download engine ✅** · **3.3 Android capture and UI (Photo Picker, camera, voice, gallery strip, viewer, Media3) ✅** (owner checked picker, camera, voice on the S24+; video pick still to try) · **3.4 desktop attach/view, cache policy, drills (kill mid-upload, resume, disk full) ✅**.

## Phase 4: Feed, metadata, search, home
Feed with composer, comments (threads), reactions, favorites, tag categories, FTS search with filters, Home v1 (Today, On this day, drafts, favorites resurfacing), share/copy actions.
Slices: **4.1 entry metadata (favorite, pinned, mood, tags, reactions as namespaced LWW fields, ADR-021) + on-device FTS search with filters ✅** · **4.2 navigation shell + Home v1 + Feed ✅** · **4.3 comments (threads), share/copy, tag categories ✅**.

## Phase 5: Calendar, reminders, templates, psychology
Calendar views, events, reminders with local notifications, templates with placeholders, prompts library, mood check-ins with emotion words, weekly/monthly reviews.
Slices: **5.1 Calendar tab (month grid with dots and mood tint, day view with swipe, events as entries of kind `event`) ✅** · **5.2 reminders (kind `reminder`, local notifications, optional gentle daily nudge) ✅** · **5.3 templates with placeholders + prompts library + question of the day ✅** · **5.4 mood check-ins with emotion words, self-compassion follow-up, weekly/monthly review cards ✅**.

## Phase 6: Polish and platforms
Themes and Keep-like backgrounds, export (ZIP / Markdown / PDF), web client polish, desktop polish (shortcuts, tray), performance pass (baseline profiles, macrobenchmarks), app lock, widget, QS tile.
Slices (owner agreed the order on 2026-10-13): **6.1 capture from anywhere (share target, QS tile, launcher shortcuts, one quick capture sheet) ✅** · **6.2 home-screen widget (Glance) ✅** · **6.3 export from the app (ZIP with media, Markdown, PDF) ✅** · **6.4 app lock ✅** · **6.5 themes, Keep-like note backgrounds, app icon ✅** · **6.6 desktop polish ✅** · **6.7 performance pass (R8, baseline profile, macrobenchmarks, feed off the main thread) ✅** · web client as its own larger step.

## Phase 7: Later
Ink layer (paper mode), ICS import/export and Google Calendar, Russian stemming, inline images in text, importers, writer's archive module, backup encryption, on-device voice transcription.
Slices (started 2026-10-14, quick daily value first): **7.1 Russian word forms in search ✅** · **7.2 inline images in text, swipeable media ✅** · 7.3 calendar as its own store + ICS (ADR-027): **7.3a calendar store (server file, sync, backups, client DB, 5.1 events moved) ✅** (2026-10-15, server 0.6.0: simulation, end-to-end restore drill over HTTPS, restore of the packaged server; on the S24+ bench build: the move of 5.1 events, kills around sync, a calendar restore healed by the phone) · **7.3b calendar layer UI (calendars with colors and checkboxes, marks and multi-day bars, day strip, event sheet; end, repeat, place, description; the calendar's trash) ✅** (2026-10-15) · 7.3c ICS codec + export (Calendar tab, archive ZIP, server mirror) · 7.3d import (Google's ZIP or `.ics`, preview by calendar, UID dedup, undo; performance with 10-20k events) · 7.4 importers (sources: open question 13) · **7.5 drawings in notes (finger, SVG attachments; paper pages and margin marks later) ✅** · **7.6 on-device voice transcription (sandboxed GigaAM, model via the own server, ADR-025) ✅** · 7.7 backup encryption · later: ICS subscriptions by URL (through the server), Google Calendar (needs the owner's Google Cloud project; or read the phone's calendars via CalendarContract), reminders into the calendar store, writer's archive.
