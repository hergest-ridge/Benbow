# Vision

An interactive diary, life timeline and living notebook built for one person (the owner). None of the existing apps fit, so we borrow small pieces from many and build our own.

## Identity
The name comes from the **Admiral Benbow inn** in *Treasure Island* (Stevenson, public domain) and the Benbow Inn in *Treasure Planet*: Jim Hawkins's home, a cozy starting point for adventures. Jim tells the story as his own written account, which is where the journal comes from. Mood: a warm inn by the sea, lantern light, a hearth, parchment and ink, maps, a compass, brass, a ship's log. *Treasure Planet*'s retro-futurism (solar sails, nebulae) can serve as an occasional accent. It informs the app icon and light touches of the visual style (see ux.md#visual-language). Use only original artwork, no Disney assets.

## Pillars (in priority order)
1. **Data is sacred.** Versioned, backed up, verifiable, exportable from v1 (see `data-safety.md`).
2. **Pleasant, fast, native UX.** Instant open, smooth scrolling, seamless text editing (see `ux.md`).
3. **Low-friction capture.** A thought gets written down in under 3 seconds from anywhere.
4. **Gently therapeutic.** Journaling practices that help without nagging (see `ux.md#psychology`).
5. **Own infrastructure.** LAN server on the owner's Windows PC. Works offline, syncs when home.

## Feature list
P0 = MVP (Phases 2–3), P1 = soon after, P2 = later, P3 = maybe. Numbers refer to the owner's original brief.

| # | Feature | Pri | Notes |
|---|---|---|---|
| 3 | Journal entries tied to a day, several per day | P0 | Configurable "day starts at" offset |
| 5 | High-quality rich editor (WYSIWYG, seamless text, no visible syntax) | P0 | Biggest technical risk. Spike first (`editor.md`) |
| 6 | Portable Text-based storage + exporters (Markdown, plain text, HTML, PDF). Formatting not bound by Markdown | P0 | ADR-015/017, content-format.md. Metadata in DB, front matter on MD export |
| 8b | Quick note (capture in seconds) | P0 | Composer bar, widget, QS tile, share target, shortcut |
| — | Offline-first Android + LAN sync + revisions + backups | P0 | Core of the product |
| — | Server: Windows autostart, standalone, API v1 + docs page | P0 | |
| 3b | Attachments: photo, video, audio, voice recorder | P0/P1 | Background upload to a user-chosen folder on the PC |
| 2 | Feed mode: posts + comments on own entries (self-social) | P1 | Telegram channel / chat-like |
| 11 | Rich metadata: tags, tag categories, favorites, reactions, mood | P1 | |
| 11b | Full-text search over everything | P1 | FTS5, Russian stemming later |
| 8 | Cozy home screen ("rich experience") | P1 | Today, On this day, drafts, resurfaced favorites |
| 4 | Advanced export / copy / share (whole, part, attachments) | P1 | System share sheet, ZIP export, PDF later |
| 1 | Calendar mode, reminders, local notifications | P1 | ICS / Google Calendar integration P2 |
| 9 | Templates ("Today", "This month", reviews) | P1 | Placeholders and prompts |
| 10 | Psychology-informed prompts, reviews, mood check-ins | P1 | |
| 13 | Themes, Keep-like note backgrounds | P1 | Semantic tokens, dark-mode aware |
| — | Web client (simplified UI, full functionality) | P1 | Same Compose code, thin client |
| — | Desktop client | P1 | Same Compose code |
| 7 | Free ink and drawing over notes, paper feel | P2 | "Paper mode" design in `editor.md` |
| 12 | Writer's archive (stories, chapters) | P3 | `space` concept reserved in data model |
| — | Import (Day One, Keep, Telegram, Obsidian folders) | P2 | Depends on owner's existing data |

## What we take from each reference
- **Telegram:** fluid motion, chat-style composer, attach sheet with recent media grid, hold-to-record voice, reactions, overall polish.
- **Google Keep:** note backgrounds (color + illustration), instant capture, checklists.
- **Notion:** slash menu, templates. *Not:* rigid blocks, heaviness, no cross-block select-all.
- **Obsidian:** plain portable Markdown, daily notes. *Not:* visible Markdown syntax.
- **Anytype:** typed metadata and relations, local-first spirit. *Not:* heaviness, rigid blocks.
- **ChatGPT / Claude chat:** bottom composer, threaded replies used for comments.
- **OneNote:** ink over content. *Not:* the clunky fixed canvas.
- **Day One** (extra reference): On this day, rich metadata, journaling UX.

## Non-goals
- Multi-user, sharing, public publishing, cloud SaaS.
- iOS (Compose Multiplatform keeps the door open, but it isn't planned).
- Our own document format, or block-database semantics à la Notion.
- Internet-facing server hardening in v1. LAN only. Remote access via VPN (e.g. Tailscale) if ever needed.
