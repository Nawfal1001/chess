@echo off
setlocal
set SCRIPT_DIR=%~dp0
gradle -p "%SCRIPT_DIR%" %*
endlocal
