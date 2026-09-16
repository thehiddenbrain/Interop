@echo off
rem =====================================================================
rem  Patient Access API Workbench - Windows launcher
rem  Needs a JDK 17 or newer: Gradle 9.5 and Spring Boot 4 both refuse older Javas.
rem  Looks for one in PAW_JAVA_HOME, JAVA_HOME, the PATH and the usual install folders
rem  (a JDK 8 on the PATH is skipped), builds the jar with the Gradle wrapper on first use,
rem  then starts it on http://localhost:8090/ui/
rem
rem  Options (environment variables, set them before running):
rem    PAW_JAVA_HOME=C:\Program Files\Java\jdk-17   use this JDK for the build and the app
rem    SERVER_PORT=9090                             change the port
rem    PAW_DATA_DIR=C:\paw                          where environments, tokens and history are kept
rem    SPRING_PROFILES_ACTIVE=prod                  production profile (basic auth on, demo off)
rem =====================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "JAVA_EXE="
set "JAVA_MAJOR="
set "JAVA_SEEN="
if defined PAW_JAVA_HOME call :try "%PAW_JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME call :try "%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE call :try "%%~p"
if not defined JAVA_EXE for %%d in (
    "%ProgramFiles%\Java" "%ProgramFiles%\Eclipse Adoptium" "%ProgramFiles%\Eclipse Foundation"
    "%ProgramFiles%\Microsoft" "%ProgramFiles%\Amazon Corretto" "%ProgramFiles%\Zulu" "%ProgramFiles%\BellSoft"
    "%ProgramFiles%\RedHat" "%ProgramFiles%\Semeru"
    "%LOCALAPPDATA%\Programs\Eclipse Adoptium" "%USERPROFILE%\.jdks" "%USERPROFILE%\scoop\apps"
    "C:\devTools" "C:\devTools\java" "C:\Java" "C:\tools"
  ) do (
  if not defined JAVA_EXE if exist "%%~d\" for /d %%j in ("%%~d\*") do (
    if not defined JAVA_EXE if exist "%%~j\bin\java.exe" call :try "%%~j\bin\java.exe"
    if not defined JAVA_EXE if exist "%%~j\current\bin\java.exe" call :try "%%~j\current\bin\java.exe"
  )
)
if not defined JAVA_EXE (
  echo.
  echo  No Java 17 or newer was found. Javas that were checked and skipped:!JAVA_SEEN!
  echo.
  echo  Install a JDK 17 or newer from https://adoptium.net, or point PAW_JAVA_HOME at one:
  echo     set PAW_JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.9-hotspot
  echo     run.bat
  goto :fail
)
echo Using Java !JAVA_MAJOR! at "!JAVA_EXE!"
rem gradlew.bat and the app must use the same JDK, whatever JAVA_HOME says.
for %%j in ("!JAVA_EXE!") do set "JAVA_HOME=%%~dpj.."
for %%j in ("!JAVA_HOME!") do set "JAVA_HOME=%%~fj"
set "PATH=!JAVA_HOME!\bin;!PATH!"

set "JAR="
if exist "build\libs\" (
  for /f "delims=" %%f in ('dir /b /a-d "build\libs\patient-access-workbench-*.jar" 2^>nul ^| findstr /v /i "plain"') do set "JAR=build\libs\%%f"
)
if not defined JAR (
  echo.
  echo  No prebuilt jar in build\libs - building with the Gradle wrapper ^(JAVA_HOME=!JAVA_HOME!^).
  echo  The first build downloads Gradle and the dependencies ^(needs internet once^).
  echo.
  call "%~dp0gradlew.bat" bootJar
  if errorlevel 1 (
    echo.
    echo  Build failed. Scroll up for the Gradle error. Common causes: no internet or a proxy for the
    echo  first download, or a Gradle daemon started with an older Java ^(run: gradlew.bat --stop^).
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
echo    java    : !JAVA_EXE!
echo    profile : !SPRING_PROFILES_ACTIVE!
echo    data    : !PAW_DATA_DIR!
echo    UI      : http://localhost:!SERVER_PORT!/ui/
echo  Press Ctrl+C to stop.
echo.
"!JAVA_EXE!" -jar "!JAR!" --server.port=!SERVER_PORT! "--paw.data-dir=!PAW_DATA_DIR!" %*
if errorlevel 1 goto :fail
endlocal
exit /b 0

rem ---- :try <path to java.exe>  sets JAVA_EXE / JAVA_MAJOR when that Java is 17 or newer
:try
set "CANDIDATE=%~1"
if not exist "!CANDIDATE!" exit /b 0
set "VERSION_FILE=%TEMP%\paw-java-version-%RANDOM%.txt"
"!CANDIDATE!" -version > "!VERSION_FILE!" 2>&1
set "VERSION="
for /f "tokens=3" %%v in ('findstr /i /c:" version " "!VERSION_FILE!"') do if not defined VERSION set "VERSION=%%~v"
del "!VERSION_FILE!" >nul 2>&1
if not defined VERSION exit /b 0
set "MAJOR="
for /f "tokens=1,2 delims=.-_+" %%a in ("!VERSION!") do (
  if "%%a"=="1" (set "MAJOR=%%b") else set "MAJOR=%%a"
)
if not defined MAJOR exit /b 0
set "JAVA_SEEN=!JAVA_SEEN! [!VERSION! at !CANDIDATE!]"
if !MAJOR! GEQ 17 (
  set "JAVA_EXE=!CANDIDATE!"
  set "JAVA_MAJOR=!VERSION!"
)
exit /b 0

:fail
echo.
echo  The workbench did not start. Copy the messages above when asking for help.
pause
endlocal
exit /b 1
