@echo off
rem Starts PAVE on the mock profile, or on the live profile with "run.cmd live".
rem Anything after the profile is passed to the application, for example:
rem   run.cmd live "--server.port=8091"
rem The quotes matter: cmd splits an argument at the equals sign when it is not quoted.
rem
rem The script moves to its own folder first, so it can be started from anywhere, and the runtime
rem folders config-store\ and run-history\ always land beside it.
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "NAME=PAVE"
set "VERSION=1.0.0"
set "JAR=build\libs\epa-pave-%VERSION%.jar"
set "PORT=8057"
set "PROFILE=mock"

if /i "%~1"=="mock" (
    set "PROFILE=mock"
    shift
) else if /i "%~1"=="live" (
    set "PROFILE=live"
    shift
) else if /i "%~1"=="-h" (
    goto usage
) else if /i "%~1"=="--help" (
    goto usage
) else if not "%~1"=="" (
    set "FIRST=%~1"
    if not "!FIRST:~0,2!"=="--" (
        echo run.cmd: '%~1' is not a profile. Use mock or live, for example: run.cmd live 1>&2
        exit /b 2
    )
)

rem Collect what is left after the profile, and follow a --server.port argument in the message.
set "ARGS="
:collect
if "%~1"=="" goto collected
set "ARG=%~1"
set "ARGS=!ARGS! "%~1""
if /i "!ARG:~0,14!"=="--server.port=" set "PORT=!ARG:~14!"
shift
goto collect
:collected

if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" (
    set "JAVA=%JAVA_HOME%\bin\java.exe"
    goto havejava
)
where java >nul 2>nul
if errorlevel 1 (
    echo run.cmd: no Java was found. PAVE needs a JDK 21. 1>&2
    echo Install a JDK 21, then either put its bin folder on the PATH or set JAVA_HOME to it. 1>&2
    exit /b 1
)
set "JAVA=java"
:havejava

rem "java -version" prints for example: openjdk version "21.0.5" 2024-10-15. Java 8 prints
rem "1.8.0_402", where the major version is the second number.
set "JVERSION="
for /f "tokens=3" %%v in ('"%JAVA%" -version 2^>^&1 ^| findstr /i "version"') do (
    if not defined JVERSION set "JVERSION=%%~v"
)
if not defined JVERSION (
    echo run.cmd: could not read the Java version of %JAVA%. PAVE needs a JDK 21. 1>&2
    exit /b 1
)
for /f "tokens=1,2 delims=." %%a in ("%JVERSION%") do (
    set "MAJOR=%%a"
    if "%%a"=="1" set "MAJOR=%%b"
)
set /a MAJORNUM=MAJOR+0 2>nul
if !MAJORNUM! LSS 21 (
    echo run.cmd: %JAVA% is Java %JVERSION%. PAVE needs a JDK 21. 1>&2
    echo Install a JDK 21 and set JAVA_HOME to it, or put its bin folder first on the PATH. 1>&2
    exit /b 1
)

if not exist "%JAR%" (
    echo %JAR% is not there yet. Building it once with the Gradle wrapper ^(tests are skipped here;
    echo run gradlew.bat clean build to run them^).
    call gradlew.bat --console=plain bootJar
    if errorlevel 1 exit /b 1
)

echo Starting %NAME% %VERSION% on http://localhost:%PORT%/ (%PROFILE% profile). Stop it with Ctrl+C.
"%JAVA%" -jar "%JAR%" "--spring.profiles.active=%PROFILE%" %ARGS%
exit /b %errorlevel%

:usage
echo Usage: run.cmd [mock^|live] [application arguments]
echo   run.cmd                          mock profile on port 8057
echo   run.cmd live                     live profile on port 8057
echo   run.cmd mock "--server.port=8091"  mock profile on another port, quoted because of the equals sign
exit /b 0
