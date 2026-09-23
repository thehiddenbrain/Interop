@echo off
rem =====================================================================
rem  Member Profile Service - Windows launcher
rem  Needs a JDK 17 or newer: Gradle 9.5 and Spring Boot 4 both refuse older Javas.
rem  Looks for one in MPS_JAVA_HOME, JAVA_HOME, then the PATH, builds the jar with the
rem  Gradle wrapper on first use, then starts it on http://localhost:8081/swagger-ui.html
rem
rem  Options (environment variables, set them before running):
rem    MPS_JAVA_HOME=C:\Program Files\Java\jdk-17     use this JDK for the build and the app
rem    SERVER_PORT=9081                               change the port
rem    MEMBER_PROFILE_DB_URL=jdbc:postgresql://host:5432/member_profile
rem    MEMBER_PROFILE_DB_USER / MEMBER_PROFILE_DB_PASSWORD
rem    MEMBER_DOMAIN_BASE_URL=http://memberdomain:8090/api/v1
rem    SPRING_PROFILES_ACTIVE=prod
rem =====================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"

set "JAVA_EXE="
if defined MPS_JAVA_HOME if exist "%MPS_JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%MPS_JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~p"
if not defined JAVA_EXE (
  echo  No Java found. Install a JDK 17 or newer from https://adoptium.net, or set MPS_JAVA_HOME
  echo  to the JDK the EPA Workbench uses, e.g.  set "MPS_JAVA_HOME=C:\devTools\jdk-17.0.9"
  goto :fail
)
set "JAVA_MAJOR="
for /f "tokens=3" %%v in ('"!JAVA_EXE!" -version 2^>^&1 ^| findstr /i "version"') do if not defined JAVA_MAJOR set "JAVA_MAJOR=%%~v"
set "JAVA_MAJOR=!JAVA_MAJOR:"=!"
for /f "tokens=1,2 delims=." %%a in ("!JAVA_MAJOR!") do (
  if "%%a"=="1" (set "JAVA_MAJOR=%%b") else (set "JAVA_MAJOR=%%a")
)
if !JAVA_MAJOR! LSS 17 (
  echo  Java !JAVA_MAJOR! found at "!JAVA_EXE!"; Java 17 or newer is required. Set MPS_JAVA_HOME to a JDK 17+.
  goto :fail
)
echo Using Java !JAVA_MAJOR! at "!JAVA_EXE!"
rem gradlew.bat and the app must use the same JDK, whatever JAVA_HOME says.
for %%j in ("!JAVA_EXE!") do set "JAVA_HOME=%%~dpj.."
for %%j in ("!JAVA_HOME!") do set "JAVA_HOME=%%~fj"
set "PATH=!JAVA_HOME!\bin;!PATH!"

set "JAR="
if exist "build\libs\" (
  for /f "delims=" %%f in ('dir /b /a-d "build\libs\member-profile-service-*.jar" 2^>nul ^| findstr /v /i "plain"') do set "JAR=build\libs\%%f"
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
  for /f "delims=" %%f in ('dir /b /a-d "build\libs\member-profile-service-*.jar" 2^>nul ^| findstr /v /i "plain"') do set "JAR=build\libs\%%f"
)
if not defined SERVER_PORT set "SERVER_PORT=8081"
echo.
echo  Starting !JAR!  -^>  http://localhost:!SERVER_PORT!/swagger-ui.html
echo  PostgreSQL: !MEMBER_PROFILE_DB_URL!  ^(empty = jdbc:postgresql://localhost:5432/member_profile^)
echo.
"!JAVA_EXE!" -jar "!JAR!" %*
goto :eof

:fail
echo.
pause
exit /b 1
