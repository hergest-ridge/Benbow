# Open questions

## Answered 2026-10-12 (recorded in decisions.md)
CMP for all clients (ADR-002) · desktop-first, Android ASAP (ADR-003) · Windows service (ADR-008) · TLS from v1 (ADR-012) · unified entry "for now" (ADR-014) · backups to a second local disk, no cloud yet (ADR-010) · Galaxy S24+, Android 16 → minSdk 34 (ADR-016) · ink postponed but kept in the design (Phase 7) · day starts at 04:00 (configurable) · Russian UI, i18n-ready.

Content format: Portable Text-based JSON + exporters, palette tokens, full v1 formatting list (ADR-015/017).

## Resolved since
- **Release signing key** (2026-10-13): created with `tools/create-release-key.ps1` (key + properties in `%USERPROFILE%\.benbow`); the owner's `app.benbow` is the release build. Android only installs updates signed with the same key, and a new key forces an uninstall that wipes unsynced local data, so both files must stay backed up on both disks.
- **Biometric app lock** (6.4, ADR-022): the platform's fingerprint/face check with the screen lock as fallback.

## Later
13. Existing data to import (Day One, Keep, Telegram, Notion, Obsidian)? Affects importer priority (7.4).
15. Encryption at rest (DB, media) beyond BitLocker and Android file-based encryption? Backups: an optional encrypted copy outside the house (ADR-043, 7.7).
16. Repo hosting and CI (private GitHub + Actions?), license.
17. Remote access outside home: Tailscale when needed?
