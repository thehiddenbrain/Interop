@echo off
rem =====================================================================
rem  Patient Access API Workbench - Windows launcher
rem  Needs Java 17 or newer on the PATH (or JAVA_HOME). Builds the jar with
rem  the Gradle wrapper on first use, then starts it on http://localhost:8090/ui/
rem
rem  Options (environment variables, set them before running):
rem    SERVER_PORT=9090        change the port
rem    PAW_DATA_DIR=C:\paw     where environments, tokens and history are kept
rem    SPRING_PROFILES_ACTIVE=prod   production profile (basic auth on, demo off)
rem =====================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "JAVA_EXE=java"
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
  echo.
  echo  java was not found. Install Java 17 or newer from https://adoptium.net
  echo  and make sure "java -version" works in a new command prompt, or set JAVA_HOME.
  goto :fail
)
for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%~v"
echo Using Java !JAVA_VERSION! (%JAVA_EXE%)

set "JAR="
if exist "build\libs\" (
  for /f "delims=" %%f in ('dir /b /a-d "build\libs\patient-access-workbench-*.jar" 2^>nul ^| findstr /v /i "plain"') do set "JAR=build\libs\%%f"
)
if not defined JAR (
  echo.
  echo  No prebuilt jar in build\libs - building with the Gradle wrapper.
  echo  The first build downloads Gradle and the dependencies ^(needs internet once^).
  echo.
  call "%~dp0gradlew.bat" bootJar
  if errorlevel 1 (
    echo.
    echo  Build failed. Scroll up for the Gradle error. Common causes: no internet for the first
    echo  download, a proxy, or a Java older than 17.
    goto :fail
  )
  for /f "delims=" %%f in ('dir /b /a-d "build\libs\patient-access-workbench-*.jar" 2^>nul ^| findstr /v /i "plain"') do set "JAR=build\libs\%%f"
)
if not defined JAR (
  echo  The build finished but no jar was found in build\libs.
  goto :fail
)

if not defined PAW_DATA_DIR set "PAW_DATA_DIR=%~dp0data"
if not exist "!PAW_DATA_DIR!" mkdir "!PAW_DATA_DIR!"
if not defined SERVER_PORT set "SERVER_PORT=8090"
if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=dev"

echo.
echo  Starting !JAR!
echo    profile : !SPRING_PROFILES_ACTIVE!
echo    data    : !PAW_DATA_DIR!
echo    UI      : http://localhost:!SERVER_PORT!/ui/
echo  Press Ctrl+C to stop.
echo.
"%JAVA_EXE%" -jar "!JAR!" --server.port=!SERVER_PORT! "--paw.data-dir=!PAW_DATA_DIR!" %*
if errorlevel 1 goto :fail
endlocal
exit /b 0

:fail
echo.
echo  The workbench did not start. Copy the messages above when asking for help.
pause
endlocal
exit /b 1
