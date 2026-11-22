@echo off
rem Starts PAVE on the mock profile, or on the live profile with "run.cmd live".
rem Anything after the profile is passed to the application unchanged, for example:
rem   run.cmd live "--server.port=8091"
rem
rem The script moves to its own folder first, so it can be started from anywhere, and the runtime
rem folders config-store\ and run-history\ always land beside it.
rem
rem Delayed expansion stays off: with it on, cmd deletes every "!" from a path or an argument.
rem Blocks in parentheses are avoided where a path is echoed, because a path such as
rem "C:\Program Files (x86)\..." would close the block early.
setlocal EnableExtensions DisableDelayedExpansion

cd /d "%~dp0"

set "NAME=PAVE"
set "VERSION=1.0.0"
set "JAR=build\libs\epa-pave-%VERSION%.jar"
set "PORT=8057"
set "PROFILE=mock"

if /i "%~1"=="-h" goto usage
if /i "%~1"=="--help" goto usage
if /i "%~1"=="mock" set "PROFILE=mock"
if /i "%~1"=="live" set "PROFILE=live"
if /i "%~1"=="mock" goto scan
if /i "%~1"=="live" goto scan
if "%~1"=="" goto scan
set "FIRST=%~1"
if "%FIRST:~0,2%"=="--" goto scan
echo run.cmd: '%FIRST%' is not a profile. Use mock or live, for example: run.cmd live 1>&2
exit /b 2

rem Only the port shown in the message is read from the arguments. The arguments themselves are
rem handed on as typed, through the all-arguments form, which SHIFT does not change. An empty "" argument does not end
rem the scan, because [""] is not []. cmd splits an unquoted --server.port=8091 at the equals sign
rem into two arguments, so that form is read too.
:scan
if [%1]==[] goto scanned
set "ARG=%~1"
if not defined ARG goto nextarg
if /i "%ARG:~0,14%"=="--server.port=" set "PORT=%ARG:~14%"
if /i "%ARG%"=="--server.port" set "PORT=%~2"
:nextarg
shift
goto scan
:scanned

if not defined JAVA_HOME goto javafrompath
if not exist "%JAVA_HOME%\bin\java.exe" goto javafrompath
set "JAVA=%JAVA_HOME%\bin\java.exe"
goto havejava
:javafrompath
where java >nul 2>nul
if not errorlevel 1 goto javaonpath
echo run.cmd: no Java was found. PAVE needs a JDK 21. 1>&2
echo Install a JDK 21, then either put its bin folder on the PATH or set JAVA_HOME to it. 1>&2
exit /b 1
:javaonpath
set "JAVA=java"
:havejava

rem "java -version" prints for example: openjdk version "21.0.5" 2024-10-15. Java 8 prints
rem "1.8.0_402", where the major version is the second number. The output goes through a file,
rem not FOR /F with a command: FOR /F hands a command to cmd /c, which strips the first and the
rem last quote of a command that starts with one, and the java path has to be quoted.
set "JVFILE=%TEMP%\pave-java-version-%RANDOM%.txt"
"%JAVA%" -version 2>"%JVFILE%" >nul
set "JVERSION="
for /f "usebackq tokens=1-3" %%a in ("%JVFILE%") do if not defined JVERSION if /i "%%b"=="version" set "JVERSION=%%~c"
del "%JVFILE%" >nul 2>nul
if defined JVERSION goto haveversion
echo run.cmd: could not read the Java version of %JAVA%. PAVE needs a JDK 21. 1>&2
exit /b 1
:haveversion
set "MAJOR="
for /f "tokens=1,2 delims=._-+" %%a in ("%JVERSION%") do (
    set "MAJOR=%%a"
    if "%%a"=="1" set "MAJOR=%%b"
)
set "MAJORNUM=0"
set /a MAJORNUM=MAJOR+0 >nul 2>nul
if %MAJORNUM% GEQ 21 goto javaok
echo run.cmd: %JAVA% is Java %JVERSION%. PAVE needs a JDK 21. 1>&2
echo Install a JDK 21 and set JAVA_HOME to it, or put its bin folder first on the PATH. 1>&2
exit /b 1
:javaok

rem The jar is built before every start, not only when it is missing. Every build calls itself
rem 1.0.0, so after a pull or an edit the jar of an older build would start as if it were the new
rem one, and that old jar is what answered an upload with an internal error after a pull. Gradle's
rem up-to-date check makes this quick when nothing changed.
echo Building %JAR% with the Gradle wrapper. This is quick when nothing changed; tests are skipped
echo here, run gradlew.bat clean build to run them.
if "%MAJORNUM%"=="21" goto build
echo Note: the build compiles with a JDK 21 toolchain when Gradle finds one. %JAVA% is Java %JVERSION%; 1>&2
echo if Gradle finds no JDK 21, the build compiles for Java 21 with this Java when it is a JDK, and says so. 1>&2
:build
call gradlew.bat --console=plain bootJar
if errorlevel 1 goto buildfailed

rem The time the jar was written tells a fresh build from an old one, which the version cannot.
set "BUILT=at a time this system does not report"
if exist "%JAR%" for %%F in ("%JAR%") do set "BUILT=%%~tF"
echo Starting %NAME% %VERSION% from %JAR%, built %BUILT%.
echo It answers on http://localhost:%PORT%/ (%PROFILE% profile). Stop it with Ctrl+C,
echo then answer Y to "Terminate batch job (Y/N)?".
"%JAVA%" -jar "%JAR%" "--spring.profiles.active=%PROFILE%" %*
exit /b %errorlevel%

:buildfailed
echo run.cmd: the build failed, so %NAME% was not started: the jar in build\libs could be older than the source. 1>&2
echo Gradle's lines above say why. A PAVE still running from this folder holds the jar open, which stops a build 1>&2
echo that has to replace it. 1>&2
exit /b 1

:usage
echo Usage: run.cmd [mock^|live] [application arguments]
echo   run.cmd                            mock profile on port 8057
echo   run.cmd live                       live profile on port 8057
echo   run.cmd mock "--server.port=8091"  mock profile on another port
exit /b 0
