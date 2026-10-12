@echo off
rem Runs Benbow Server in this console (for testing). Usage: run-console.cmd [--data <folder>]
"%~dp0runtime\bin\java.exe" -Xmx512m -Dfile.encoding=UTF-8 --enable-native-access=ALL-UNNAMED -cp "%~dp0lib\*" app.benbow.server.MainKt %*
