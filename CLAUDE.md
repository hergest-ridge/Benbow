# Benbow

Personal journal / life-feed app ("my feed of everything"). Single user, many devices.
Android app (primary), desktop + web clients, self-hosted LAN server on Windows (standalone, autostart, no Docker).

**Status:** Phase 0, docs only, no code yet. Most ADRs are accepted. The content format (ADR-015) is under discussion.

## Non-negotiables
1. **No data loss or corruption, ever.** Read `docs/data-safety.md` before touching storage, sync, migrations, deletion or backups. Never weaken an invariant to make something pass. Ask instead.
2. **UX quality is a core feature.** It should feel native, instant and smooth (Telegram-level). See `docs/ux.md`.
3. **Portability.** Content is stored in an open, documented, versioned format, and the export mirror (Markdown + raw) is always readable without the app. No lock-in.

## Docs map (read only what the task needs)
| File | Contents |
|---|---|
| `docs/vision.md` | What and why, feature list with priorities, references, non-goals |
| `docs/architecture.md` | Stack, modules, runtime topology, packaging, libraries, testing |
| `docs/data-model.md` | Entities and fields (draft) |
| `docs/data-safety.md` | Invariants, storage layout, revisions, backups, migrations, recovery |
| `docs/sync.md` | Offline outbox, push/pull protocol, conflicts, blobs, pairing, API versioning |
| `docs/editor.md` | Editor approach, Markdown subset, round-trip rules, ink-layer plan, spike checklist |
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
_To be filled in after the scaffold (Phase 1)._
