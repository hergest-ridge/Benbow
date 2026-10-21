Benbow Server (Windows)

Folder layout
  runtime\          bundled Java runtime (no system Java needed)
  lib\              server jars
  web\              the web client (the app in a browser), served at https://<this PC>:8765/app/
  run-console.cmd   run in a console window for testing
  install-service.cmd / uninstall-service.cmd   Windows service via WinSW (run as Administrator)

WinSW
  The service wrapper is WinSW v2.12.0 (https://github.com/winsw/winsw/releases/tag/v2.12.0).
  Put WinSW.NET461.exe into server\packaging\winsw\ before `gradlew :server:packageServer`;
  it is copied here as benbow-server-service.exe. It uses the .NET Framework built into Windows 10/11.
  Expected SHA-256 (checked by the build): b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f

Service (run the scripts as Administrator)
  install-service.cmd [data folder]   copies this folder to "%ProgramFiles%\Benbow Server" and installs
                                      the service from there; default data folder: %ProgramData%\Benbow
  update-service.cmd                  run from a NEW package folder: stops the service, updates the
                                      installed copy, starts it. The server backs up the database
                                      (verified) before its first start on a new version.
  uninstall-service.cmd               removes the service and the firewall rule; data stays.
  restore-backup.cmd [latest|<file>] [data folder]
                                      stops the service, puts a verified snapshot back as the live
                                      database (the old one is kept in db\replaced-<time>), starts it.
                                      Paired devices re-upload what the snapshot lacks.
  restore-calendar.cmd [latest|<file>] [data folder]
                                      the same for the calendar file alone (ADR-027); the journal
                                      is not touched.
  restore-from-mirror.cmd <mirror folder> [data folder] [media folder]
                                      on a NEW PC: builds the data folder from the backup mirror
                                      (settings, certificate, snapshots, calendar, media, each file
                                      checked; the mirror is only read). Then install-service.cmd.
                                      The old server must stay off. A server for a friend is installed
                                      fresh, never from a mirror or a copied data folder.
  - starts automatically (delayed) after boot, restarts on failure
  - opens TCP 8765 in the Windows firewall for private networks
  - logs: <data folder>\logs (server), <install folder>\service-logs (wrapper)

Web client
  Open https://<this PC's address>:8765/app/ in Edge, Chrome or Firefox on any PC of the home network
  (https://localhost:8765/app/ on this PC). The browser warns once about the self-signed certificate;
  compare its SHA-256 with the one on https://localhost:8765, then continue. In the app, enter the code from
  "Получить код" on the admin page. Use one address per browser: another address is another device.

The service currently runs as LocalSystem. A dedicated low-privilege account is planned.
