@echo off
setlocal
rem Vendor Extract Builder: build (first time only) and start on http://localhost:8090
rem Needs Java 17 or newer on the PATH. Gradle is downloaded by the wrapper; nothing else to install.

cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo Java was not found on the PATH. Install a JDK 17 or newer, for example from https://adoptium.net, then run this again.
  pause
  exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VERSION=%%v
echo Using Java %JAVA_VERSION%

echo.
echo Starting the Vendor Extract Builder on http://localhost:8090 ...
echo The first start downloads Gradle and the dependencies, which takes a few minutes.
echo Press Ctrl+C in this window to stop the application.
echo.

start "" /b cmd /c "timeout /t 25 /nobreak >nul && start http://localhost:8090/ui/"

call gradlew.bat bootRun --console=plain
endlocal
