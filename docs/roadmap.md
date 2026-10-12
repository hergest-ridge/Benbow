# Roadmap

Vertical slices. Each phase ends with a usable, tested increment. **Current phase: 1.**

## Phase 0: Foundations (docs) ✅ done 2026-10-12
Docs, research, ADRs accepted by the owner.

## Phase 1: Scaffold + de-risking spikes
1. ✅ (2026-10-12, lint/format still pending) Gradle multi-module KMP skeleton (modules from architecture.md), version catalog, convention plugins, `./gradlew check`, formatting/lint (ktlint or detekt), `.editorconfig`, fill the CLAUDE.md "Commands" section. Android app shell installable on the S24+ from the first scaffold (ADR-003).
2. 🔄 (prototype running on S24+, findings in editor.md) **Editor spike** (editor.md checklist) on Desktop + Android (S24+, Samsung Keyboard and Gboard), then a Web sanity check. Scope: ADR-017 (editor.md).
3. **Server packaging spike:** Ktor + SQLite hello-world → jlink/jpackage app-image → WinSW service. Survives reboot, restarts on crash, serves `/api/v1/info` and `/api/docs`.
4. **Sync core:** `shared/core` HLC, diff3 merge, op model + the deterministic simulation test harness (no UI).
5. Document codec (content-format.md): decode/encode, canonicalizer, plain-text + Markdown exporters, property tests.
**Exit:** spikes pass or fallbacks are chosen. ADR-006 and ADR-008 move to Accepted or get revised.

## Phase 2: Safe core MVP (text only, dogfood-ready)
Server: entries, revisions, tags (basic), change_log, push/pull, pairing, backups (snapshots + verification + retention + Markdown mirror), integrity checks, admin page, OpenAPI docs.
Android: connect/pair, journal by day, editor with autosave drafts, quick-note composer, offline outbox, sync status, revision history (view/restore), trash.
Desktop: same screens via shared UI.
**Exit:** the owner can journal daily for real. Kill/airplane/restore drills pass (data-safety runbook).

## Phase 3: Attachments
Photo/video picker, camera, voice recorder (hold-to-record), resumable background upload, server blob store in a user-chosen folder, client previews, gallery in entries, basic cache policy, media viewer.

## Phase 4: Feed, metadata, search, home
Feed with composer, comments (threads), reactions, favorites, tag categories, FTS search with filters, Home v1 (Today, On this day, drafts, favorites resurfacing), share/copy actions.

## Phase 5: Calendar, reminders, templates, psychology
Calendar views, events, reminders with local notifications, templates with placeholders, prompts library, mood check-ins with emotion words, weekly/monthly reviews.

## Phase 6: Polish and platforms
Themes and Keep-like backgrounds, export (ZIP / Markdown / PDF), web client polish, desktop polish (shortcuts, tray), performance pass (baseline profiles, macrobenchmarks), app lock, widget, QS tile.

## Phase 7: Later
Ink layer (paper mode), ICS import/export and Google Calendar, Russian stemming, inline images in text, importers, writer's archive module, backup encryption, on-device voice transcription.
