The installer drill in Windows Sandbox (ADR-042, I2). Never run setup.exe on the owner's PC itself:
it would update the live service.

Once: Windows Sandbox on (Containers-DisposableClientVM, as Administrator, then a restart).
Each time, in C:\projects\Benbow\.tmp\sandbox:
  in\setup-new.exe   the setup to check (gradlew :server:installer -> server\build\installer)
  in\setup-old.exe   the same setup compiled with an older AppVersion (ISCC /DAppVersion=<older> ...)
  pkg\               an older server package folder (a junction to it is fine); its install-service.cmd runs first
  out\               empty
Then: WindowsSandbox.exe server\packaging\installer\sandbox\benbow-installer.wsb
It writes out\results.txt and the setup logs, puts out\done.txt last and shuts the sandbox down.
Expected: every setup and uninstall exit 0 except the older setup and the folder of other things (1); the
same store_id before and after the update and after the reinstall over the old data; after removal the
service, the firewall rule, the program folder, the uninstall entry and the Start menu link gone and the
data folder kept.
