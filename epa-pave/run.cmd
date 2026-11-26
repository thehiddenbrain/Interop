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

rem The first Java 21 or newer wins, looked for in this order: PAVE_JAVA (a java.exe), JAVA_HOME,
rem the java on the PATH, then the Java that STS brings in its usual install folders. A workstation
rem can have only Java 8 on the PATH and in JAVA_HOME while STS carries a Java 21 or newer of its
rem own, so an older Java is passed over with a line that says so instead of ending the search.
rem A JAVA set before this script ran must not stop the search before it starts.
set "JAVA="
set "SOURCE="
if not defined PAVE_JAVA goto nopavejava
rem A path typed with quotes around it, as set PAVE_JAVA="C:\...\java.exe", is still one path.
set "PAVE_JAVA=%PAVE_JAVA:"=%"
call :trycandidate "%PAVE_JAVA%" "named by PAVE_JAVA"
if defined JAVA goto havejava
:nopavejava
if not defined JAVA_HOME goto nojavahome
set "JAVA_HOME=%JAVA_HOME:"=%"
call :trycandidate "%JAVA_HOME%\bin\java.exe" "named by JAVA_HOME"
if defined JAVA goto havejava
:nojavahome
rem cmd searches the PATH itself here, folder by folder in the PATH's order, starting nothing.
set "PATHJAVA="
for %%P in (java.exe) do set "PATHJAVA=%%~$PATH:P"
if not defined PATHJAVA goto nopathjava
call :trycandidate "%PATHJAVA%" "the java on the PATH"
if defined JAVA goto havejava
:nopathjava
rem STS unpacks into a folder named sts-<version>, either directly in one of these folders or one
rem level down when the operator unpacked it into a folder of their own, such as C:\sts.
for /d %%S in ("%USERPROFILE%\sts*" "%SystemDrive%\sts*" "%ProgramFiles%\sts*" "%LOCALAPPDATA%\Programs\sts*") do if not defined JAVA call :stsfolder "%%~fS"
if defined JAVA goto havejava
echo run.cmd: no Java 21 or newer was found, and PAVE needs one to build and to run. 1>&2
echo It looked at PAVE_JAVA, JAVA_HOME, the java on the PATH and the Java STS brings in its usual folders. 1>&2
echo If STS is installed, point PAVE_JAVA at the Java it brings with this line, then start run.cmd again in the same window: 1>&2
echo set PAVE_JAVA=^<the folder STS is in^>\plugins\^<the justj folder^>\jre\bin\java.exe 1>&2
echo The justj folder is the one whose name starts with org.eclipse.justj.openjdk.hotspot.jre.full. 1>&2
echo setx instead of set keeps it for every window opened afterwards. Without STS, install a JDK 21 and set JAVA_HOME to it. 1>&2
exit /b 1
:havejava
echo Using Java %JVERSION% at %JAVA%, %SOURCE%.
rem Gradle builds with the same Java, so a JAVA_HOME left at an older Java cannot stop the build.
rem The setlocal at the top keeps this change inside this script. The java on the PATH can sit in
rem a folder of links with no Java home above it, so Gradle is then left to find it on the PATH.
if "%SOURCE%"=="the java on the PATH" goto gradleonpath
for %%D in ("%JAVA%\..\..") do set "JAVA_HOME=%%~fD"
goto javaok
:gradleonpath
set "JAVA_HOME="
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
echo Java: the first Java 21 or newer of PAVE_JAVA (a java.exe), JAVA_HOME, the PATH and STS's own.
exit /b 0

rem Tries one java.exe: %1 is its path, %2 says where it was found. Sets JAVA, SOURCE, JVERSION
rem and MAJORNUM when it is a Java 21 or newer, and otherwise says why it was passed over.
:trycandidate
set "CANDIDATE=%~1"
set "CANDIDATESOURCE=%~2"
if exist "%CANDIDATE%" goto candidatefound
echo Not using %CANDIDATE%, %CANDIDATESOURCE%: there is no such file.
exit /b 0
:candidatefound
call :readversion
if %MAJORNUM% GEQ 21 goto candidateok
if not defined JVERSION echo Not using %CANDIDATE%, %CANDIDATESOURCE%: its Java version could not be read.
if defined JVERSION echo Not using %CANDIDATE%, %CANDIDATESOURCE%: it is Java %JVERSION%, and PAVE needs 21 or newer.
exit /b 0
:candidateok
set "JAVA=%CANDIDATE%"
set "SOURCE=%CANDIDATESOURCE%"
exit /b 0

rem "java -version" prints for example: openjdk version "21.0.5" 2024-10-15. Java 8 prints
rem "1.8.0_402", where the major version is the second number. The output goes through a file,
rem not FOR /F with a command: FOR /F hands a command to cmd /c, which strips the first and the
rem last quote of a command that starts with one, and the java path has to be quoted.
:readversion
set "JVERSION="
set "MAJOR="
set "MAJORNUM=0"
set "JVFILE=%TEMP%\pave-java-version-%RANDOM%.txt"
"%CANDIDATE%" -version 2>"%JVFILE%" >nul
for /f "usebackq tokens=1-3" %%a in ("%JVFILE%") do if not defined JVERSION if /i "%%b"=="version" set "JVERSION=%%~c"
del "%JVFILE%" >nul 2>nul
if not defined JVERSION exit /b 0
for /f "tokens=1,2 delims=._-+" %%a in ("%JVERSION%") do (
    set "MAJOR=%%a"
    if "%%a"=="1" set "MAJOR=%%b"
)
set /a MAJORNUM=MAJOR+0 >nul 2>nul
exit /b 0

rem Looks for the Java STS brings in the folder %1 and in each sts* folder directly inside it.
:stsfolder
call :stsplugins "%~1"
for /d %%T in ("%~1\sts*") do if not defined JAVA call :stsplugins "%%~fT"
exit /b 0

:stsplugins
for /d %%J in ("%~1\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*") do if not defined JAVA call :trycandidate "%%~fJ\jre\bin\java.exe" "the Java STS brings"
exit /b 0
