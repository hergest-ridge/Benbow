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
  Expected SHA-256 (checked by the build): b5066b7bbdfba1293e5d15cda3caaea88fbeab35bd5b38c41c913d492aadfc4f

Service (run the scripts as Administrator)
  install-service.cmd [data folder]   copies this folder to "%ProgramFiles%\Benbow Server" and installs
                                      the service from there; default data folder: %ProgramData%\Benbow
  update-service.cmd                  run from a NEW package folder: stops the service, updates the
                                      installed copy, starts it. The server backs up the database
                                      (verified) before its first start on a new version.
  uninstall-service.cmd               removes the service and the firewall rule; data stays.
  - starts automatically (delayed) after boot, restarts on failure
  - opens TCP 8765 in the Windows firewall for private networks
  - logs: <data folder>\logs (server), <install folder>\service-logs (wrapper)

The service currently runs as LocalSystem. A dedicated low-privilege account is planned.
