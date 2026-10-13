# Open questions

## Answered 2026-10-12 (recorded in decisions.md)
CMP for all clients (ADR-002) · desktop-first, Android ASAP (ADR-003) · Windows service (ADR-008) · TLS from v1 (ADR-012) · unified entry "for now" (ADR-014) · backups to a second local disk, no cloud yet (ADR-010) · Galaxy S24+, Android 16 → minSdk 34 (ADR-016) · ink postponed but kept in the design (Phase 7) · day starts at 04:00 (configurable) · Russian UI, i18n-ready.

Content format: Portable Text-based JSON + exporters, palette tokens, full v1 formatting list (ADR-015/017).

## Before real use (Phase 2)
- **Release signing key:** Android only installs updates signed with the same key. Changing keys forces an uninstall, which would wipe unsynced local data. Ready: the owner runs `tools/create-release-key.ps1` once (key + properties in `%USERPROFILE%\.benbow`), backs both files up on both disks, then `installRelease`. Moving from the debug-signed `app.benbow` to release: sync first, uninstall, install release, pair, full pull.

## Later
13. Existing data to import (Day One, Keep, Telegram, Notion, Obsidian)? Affects importer priority.
15. Encryption at rest (DB, media) beyond BitLocker and Android file-based encryption? Biometric app lock?
16. Repo hosting and CI (private GitHub + Actions?), license.
17. Remote access outside home: Tailscale when needed?
