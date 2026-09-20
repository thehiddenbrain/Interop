@echo off
setlocal
rem Vendor Extract Builder: start on http://localhost:8090
rem Needs Java 17 or newer on the PATH (https://adoptium.net). Nothing else to install.
rem If extract-builder.jar is next to this file it is run directly; otherwise the app is built
rem with the Gradle wrapper (first build downloads Gradle and the dependencies).

cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found on the PATH. Install a JDK 17 or newer from https://adoptium.net, then run this again.
  pause
  exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VERSION=%%v
echo Using Java %JAVA_VERSION%
echo.
echo Starting the Vendor Extract Builder on http://localhost:8090 ...
echo The browser opens by itself once the app is up. Press Ctrl+C in this window to stop it.
echo.

start "" /b cmd /c "timeout /t 12 /nobreak >nul && start http://localhost:8090/ui/"

if exist extract-builder.jar (
  java -jar extract-builder.jar
) else (
  echo No prebuilt jar found, building with Gradle. The first build takes a few minutes.
  call gradlew.bat bootRun --console=plain
)

if errorlevel 1 (
  echo.
  echo The application stopped with an error. If port 8090 is already in use, close the other program or
  echo start with: java -jar extract-builder.jar --server.port=8091
  pause
)
endlocal
