# Open questions

## Answered 2026-10-12 (recorded in decisions.md)
CMP for all clients (ADR-002) · desktop-first, Android ASAP (ADR-003) · Windows service (ADR-008) · TLS from v1 (ADR-012) · unified entry "for now" (ADR-014) · backups to a second local disk, no cloud yet (ADR-010) · Galaxy S24+, Android 16 → minSdk 34 (ADR-016) · ink postponed but kept in the design (Phase 7) · day starts at 04:00 (configurable) · Russian UI, i18n-ready.

## Under discussion
5. **Content format and formatting freedom.** The owner wants formatting not bound by Markdown, with export converters instead. Proposal: ADR-015 (Portable Text-based JSON + exporters, colors and sizes as theme tokens). Waiting on: (a) confirm ADR-015, (b) theme-token palette vs arbitrary colors, (c) must-have formatting for v1 (this defines the editor spike scope).

## Later
13. Existing data to import (Day One, Keep, Telegram, Notion, Obsidian)? Affects importer priority.
15. Encryption at rest (DB, media) beyond BitLocker and Android file-based encryption? Biometric app lock?
16. Repo hosting and CI (private GitHub + Actions?), license.
17. Remote access outside home: Tailscale when needed?
