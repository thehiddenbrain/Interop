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
set "JAVA_IS_JDK="
set "JAVA_SEEN="
set "PF86=%ProgramFiles(x86)%"
rem 1. explicit choices
if defined PAW_JAVA_HOME call :try "%PAW_JAVA_HOME%\bin\java.exe"
if not defined JAVA_IS_JDK if defined JAVA_HOME call :try "%JAVA_HOME%\bin\java.exe"
rem 2. every java on the PATH
if not defined JAVA_IS_JDK for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_IS_JDK call :try "%%~p"
rem 3. JDKs registered by their installer (Oracle, Adoptium, Microsoft, Corretto, Zulu, ...)
if not defined JAVA_IS_JDK for %%k in ("HKLM\SOFTWARE\JavaSoft\JDK" "HKLM\SOFTWARE\JavaSoft\Java Development Kit" "HKLM\SOFTWARE\JavaSoft\JRE" "HKCU\SOFTWARE\JavaSoft\JDK") do (
  if not defined JAVA_IS_JDK for /f "tokens=2,*" %%a in ('reg query %%k /s /v JavaHome 2^>nul ^| findstr /i "JavaHome"') do if not defined JAVA_IS_JDK call :try "%%~b\bin\java.exe"
)
if not defined JAVA_IS_JDK for %%k in ("HKLM\SOFTWARE\Eclipse Adoptium" "HKLM\SOFTWARE\Eclipse Foundation" "HKLM\SOFTWARE\Azul Systems" "HKLM\SOFTWARE\Microsoft\JDK" "HKLM\SOFTWARE\Amazon") do (
  if not defined JAVA_IS_JDK for /f "tokens=2,*" %%a in ('reg query %%k /s /v Path 2^>nul ^| findstr /i "REG_SZ"') do if not defined JAVA_IS_JDK call :try "%%~b\bin\java.exe"
)
rem 4. deep scan of the usual install and dev folders, including the JRE that ships inside STS / Eclipse
if not defined JAVA_IS_JDK for %%d in (
    "C:\devTools" "C:\dev" "C:\tools" "C:\Java" "C:\jdk" "C:\opt" "C:\sts" "C:\eclipse" "D:\devTools" "D:\dev"
    "%ProgramFiles%" "!PF86!" "%LOCALAPPDATA%\Programs" "%USERPROFILE%\.jdks" "%USERPROFILE%\scoop\apps"
    "%USERPROFILE%\Downloads" "%USERPROFILE%\Desktop" "%USERPROFILE%\Documents"
  ) do (
  if not defined JAVA_IS_JDK if exist "%%~d\" for /f "delims=" %%j in ('dir /s /b "%%~d\java.exe" 2^>nul') do if not defined JAVA_IS_JDK call :try "%%~j"
)
rem 5. STS / Eclipse installs anywhere on C: (their plugins folder carries a full JRE 17 or 21)
if not defined JAVA_IS_JDK for /d %%s in ("C:\*sts*" "C:\*eclipse*" "C:\*spring*" "%USERPROFILE%\*sts*" "%USERPROFILE%\*eclipse*") do (
  if not defined JAVA_IS_JDK for /f "delims=" %%j in ('dir /s /b "%%~s\java.exe" 2^>nul') do if not defined JAVA_IS_JDK call :try "%%~j"
)
rem 6. last resort: the whole C: drive (one time, can take a minute)
if not defined JAVA_EXE (
  echo  No Java 17+ in the usual places, scanning the whole C: drive for one ^(can take a minute^)...
  for /f "delims=" %%j in ('dir /s /b "C:\java.exe" 2^>nul') do if not defined JAVA_IS_JDK call :try "%%~j"
)
if not defined JAVA_EXE (
  echo.
  echo  No Java 17 or newer was found on this PC. Javas that were checked and skipped:
  echo   !JAVA_SEEN!
  echo.
  echo  The workbench needs a JDK 17 or newer, the same as the EPA Workbench. Either install one from
  echo  https://adoptium.net, or point PAW_JAVA_HOME at the JDK the EPA Workbench uses, e.g.
  echo     set "PAW_JAVA_HOME=C:\devTools\jdk-17.0.9"
  echo     run.bat
  echo  Inside STS the path is under Window ^> Preferences ^> Java ^> Installed JREs.
  goto :fail
)
if not defined JAVA_IS_JDK (
  echo  Note: "!JAVA_EXE!" is a runtime without javac. Running the prebuilt jar works; if the build
  echo  fails with "no compiler", install a JDK 17+ or set PAW_JAVA_HOME to one.
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

rem ---- :try <path to java.exe>  remembers the first Java 17+ found, and prefers one that has javac (a JDK)
:try
set "CANDIDATE=%~1"
if not exist "!CANDIDATE!" exit /b 0
if /i "!CANDIDATE!"=="!JAVA_EXE!" exit /b 0
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
if !MAJOR! LSS 17 exit /b 0
for %%j in ("!CANDIDATE!") do set "CANDIDATE_JAVAC=%%~dpjjavac.exe"
if exist "!CANDIDATE_JAVAC!" (
  set "JAVA_EXE=!CANDIDATE!"
  set "JAVA_MAJOR=!VERSION!"
  set "JAVA_IS_JDK=1"
) else if not defined JAVA_EXE (
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
