@echo off
rem =====================================================================
rem  Member ID Resolution Service - Windows launcher
rem  Needs a JDK 17 or newer: Gradle 9.5 and Spring Boot 4 both refuse older Javas.
rem  Looks for one in MBRID_JAVA_HOME, JAVA_HOME, then the PATH; builds the jar with the
rem  Gradle wrapper on first use, then starts it on http://localhost:9090/swagger-ui.html
rem
rem  Options (environment variables, set them before running):
rem    MBRID_JAVA_HOME=C:\Program Files\Java\jdk-17   use this JDK for the build and the app
rem    SERVER_PORT=9091                               change the port
rem    SPRING_PROFILES_ACTIVE=dev                     in-process MMI stub instead of the PQA MMI (the default profile is pqa)
rem =====================================================================
setlocal EnableDelayedExpansion
cd /d "%~dp0"
set "JAVA_EXE="
if defined MBRID_JAVA_HOME if exist "%MBRID_JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%MBRID_JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE for /f "delims=" %%p in ('where java 2^>nul') do if not defined JAVA_EXE set "JAVA_EXE=%%~p"
if not defined JAVA_EXE (
  echo No Java found. Install a JDK 17 or newer from https://adoptium.net, or set MBRID_JAVA_HOME.
  pause
  exit /b 1
)
for /f "tokens=3" %%v in ('"%JAVA_EXE%" -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VER=%%~v"
for /f "tokens=1,2 delims=." %%a in ("%JAVA_VER%") do (
  if "%%a"=="1" (set "JAVA_MAJOR=%%b") else (set "JAVA_MAJOR=%%a")
)
if %JAVA_MAJOR% LSS 17 (
  echo Java %JAVA_VER% found at "%JAVA_EXE%"; Java 17 or newer is required. Set MBRID_JAVA_HOME to a JDK 17+.
  pause
  exit /b 1
)
for %%j in ("%JAVA_EXE%") do set "JAVA_BIN=%%~dpj"
set "JAVA_HOME=%JAVA_BIN:~0,-5%"
set "PATH=%JAVA_BIN%;%PATH%"
set "JAR="
for %%f in (build\libs\member-id-resolution-service-*.jar) do (
  echo %%~nxf | findstr /i /v "\-plain" >nul && set "JAR=%%~f"
)
if not defined JAR (
  echo No prebuilt jar in build\libs\, building with the Gradle wrapper (first build downloads Gradle and the dependencies)...
  call gradlew.bat -q bootJar
  if errorlevel 1 ( echo Build failed. & pause & exit /b 1 )
  for %%f in (build\libs\member-id-resolution-service-*.jar) do (
    echo %%~nxf | findstr /i /v "\-plain" >nul && set "JAR=%%~f"
  )
)
if not defined SPRING_PROFILES_ACTIVE set "SPRING_PROFILES_ACTIVE=pqa"
if not defined SERVER_PORT set "SERVER_PORT=9090"
echo Starting %JAR% (profile: %SPRING_PROFILES_ACTIVE%) -^> http://localhost:%SERVER_PORT%/swagger-ui.html
"%JAVA_EXE%" -jar "%JAR%" %*
