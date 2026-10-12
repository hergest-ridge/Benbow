# UX

## Principles
1. **Instant.** Everything opens from the local cache. Writes are optimistic. No spinners for local data.
2. **Smooth.** 120 Hz, zero jank in feeds, shared-element transitions, predictive back, spring motion, subtle haptics. Telegram is the bar.
3. **Seamless text.** It reads and edits like a notebook, not a block database (see editor.md).
4. **Low friction.** Capture in under 3 seconds. Metadata is optional and can always be added later.
5. **Forgiving.** Undo everywhere, trash instead of delete, version history, input that never gets lost.
6. **Calm.** Warm, paper-like, unhurried. No guilt mechanics, no noisy badges.

## Navigation (phone)
Bottom bar: **Home · Feed · Calendar · Search**. There is always a quick-capture entry point (a composer on Home and Feed, a FAB elsewhere). Settings, tags, trash and server status sit behind the top-bar avatar/menu.
Desktop and web: navigation rail + adaptive list-detail panes (Material 3 adaptive), with keyboard shortcuts.

## Screens
- **Home ("Сегодня"):** greeting + date, the day's gentle prompt, one-tap mood check-in, quick-capture bar, "On this day" (1/2/5 years ago), continue drafts, recent feed, a resurfaced favorite memory, upcoming reminders, soft stats ("written 12 days this month", no streaks). A weekly reflection card on Sundays and a monthly one at month end.
- **Feed:** a Telegram-channel-style stream of all entries (posts in full, journal entries as previews) with date separators. Bottom composer works like Saved Messages: type, attach, send. Long-press for reactions, swipe to comment, comments as a thread view.
- **Day view:** all entries for a day + mood + events. Swipe between days.
- **Calendar:** month grid with entry dots / mood tint, agenda, reminders.
- **Editor:** full-screen, distraction-free. Toolbar above the keyboard (format, checklist, attach, voice, template, `/`). Backgrounds Keep-style. Metadata sheet (day, tags, mood, kind). History and conflicts accessible from the menu.
- **Search:** instant FTS with filters (date range, tags, kind, has-media, favorites, mood), saved searches.
- **Settings / Server:** connection (host:port, pairing QR scan), sync status, backup status (last verified backup), cache policy, theme, app lock.

## Quick capture entry points
Composer on Home/Feed, launcher app shortcut, home-screen widget (Glance), Quick Settings tile, share target (text, links, media → note), notification action from reminders. Voice: hold-to-record like Telegram. Transcription is P2 (on-device only).

## Visual language
Material 3 + Expressive components and motion, behind our `BenbowTheme` tokens. Optional dynamic color, curated warm palettes. Bundled fonts with full Cyrillic (e.g. a UI sans like Onest/Manrope/Inter and a reading serif like Literata/Lora for journal text; final pick in the design pass). Fonts must be bundled for offline use, including on web. Note backgrounds are theme-aware color pairs + optional illustration/texture.

## Psychology
Evidence-informed practices exposed as **optional** templates, prompts and features. Never forced.
| Practice | Source idea | Feature |
|---|---|---|
| Expressive writing | Pennebaker: writing freely about emotions reduces stress | "Free write" mode: blank page, optional timer, private by default |
| Gratitude / Three Good Things | Seligman; Emmons & McCullough | Template + optional evening prompt |
| Affect labeling, emotional granularity | Lieberman; Feldman Barrett: naming emotions precisely regulates them | Mood check-in with an emotion-word picker (category "Emotions") |
| Cognitive reappraisal (CBT thought record) | Beck: situation → thought → feeling → evidence → balanced thought | Template |
| Self-distancing | Kross: reflect on yourself from a distance or in the third person | Retrospective comments on old entries, "write to past/future self" prompts |
| Savoring and reminiscence | Bryant | On this day, resurfaced favorites, yearly recap |
| Self-compassion | Neff | Prompts after low-mood check-ins ("what would you tell a friend?") |
| Small wins | Amabile, progress principle | Weekly review template, soft stats |
| Reflection cycles | — | Weekly/monthly review templates ("This week", "This month") |
Anti-patterns to avoid: streak loss shaming, forced daily prompts, gamified points, performance metrics, nagging notifications (reminders are opt-in, gentle, snoozable). App lock (biometric) for a sense of privacy.

## Performance budgets (validated in Phase 6 with macrobenchmarks)
Cold start to Home <600 ms on the owner's phone. Entry open <100 ms. Feed scroll: no janky frames at 120 Hz. Typing: no dropped frames on a 20k-char entry.
