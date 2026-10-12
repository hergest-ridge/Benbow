# Open questions (waiting on the owner)

Format: question → my recommendation. Move each to decisions.md once answered.

## Blocking Phase 1
1. **Stack: Compose Multiplatform for all three clients (ADR-002)?** → Yes. Android is primary. Desktop JVM is the main dev target. Web compiles from day one, polished in Phase 6.
2. **Web vs desktop priority.** The owner proposed web first, in parallel with mobile. → With CMP both come from the same code. Iterate on desktop (Hot Reload + MCP for AI checks), keep web compiling, polish web later.
3. **Server process model (ADR-008):** Windows service (runs before login, survives logoff) or tray app at logon? Is the PC usually on, or does it sleep often? → Service.
4. **Transport security on LAN:** self-signed TLS + pinning from v1, or HTTP + token for v1? → TLS from v1 (cheap to do now, painful to retrofit). The web client then needs a one-time browser certificate exception.
5. **Editor scope:** is the restricted formatting set OK (no colors, font sizes, underline, alignment, tables)? → Yes for v1. Keeps exports clean.

## Needed by Phase 2–3
6. **Journal entry vs post:** are they really different things, or views over one "entry" (ADR-014)? What's the mental difference for you?
7. **Backup second location:** second disk, NAS, or a cloud-synced folder (OneDrive / Google Drive / Yandex Disk)? Encrypt backups that go to the cloud?
8. **Attachments:** a gallery per entry in v1 (Telegram album style), inline images in text later? → Yes.
9. **Phone:** model + Android version → minSdk. → minSdk 29 or higher if the phone allows (less compat code).
10. **Desktop client location:** same PC as the server, or also a laptop elsewhere (needs offline cache)? → Support both (same code).
11. **"Day starts at"** offset for night writing (entries at 02:00 count as the previous day)? → 04:00 default, configurable.
12. **App UI language:** Russian only, or RU + EN? → Russian strings in resources, i18n-ready.

## Later
13. Existing data to import (Day One, Keep, Telegram, Notion, Obsidian)? This affects importer priority.
14. Ink: agree to "later, paper mode only"?
15. Encryption at rest (DB, media) beyond BitLocker and Android file-based encryption? Biometric app lock?
16. Repo hosting and CI (private GitHub + Actions?), license (private / none).
17. Remote access outside home: Tailscale when needed?
