Benbow Server (Windows)

Folder layout
  runtime\          bundled Java runtime (no system Java needed)
  lib\              server jars
  run-console.cmd   run in a console window for testing
  install-service.cmd / uninstall-service.cmd   Windows service via WinSW (run as Administrator)

WinSW
  The service wrapper is WinSW v2.12.0 (https://github.com/winsw/winsw/releases/tag/v2.12.0).
  Put WinSW.NET461.exe into server\packaging\winsw\ before `gradlew :server:packageServer`;
  it is copied here as benbow-server-service.exe. It uses the .NET Framework built into Windows 10/11.

Service
  install-service.cmd [data folder]   default data folder: %ProgramData%\Benbow
  - starts automatically (delayed) after boot, restarts on failure
  - opens TCP 8765 in the Windows firewall for private networks
  - logs: <data folder>\logs (server), service-logs\ (wrapper)
  uninstall-service.cmd removes the service and the firewall rule; the data folder stays.

The service currently runs as LocalSystem. A dedicated low-privilege account is planned.
