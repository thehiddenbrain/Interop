@echo off
rem Starts the Patient Access API workbench. Needs Java 17 or newer on the PATH.
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo java not found on PATH. Install Java 17 or newer ^(https://adoptium.net^) and retry.
  exit /b 1
)
set JAR=
for %%f in (build\libs\patient-access-workbench-*.jar) do (
  echo %%f | findstr /v "\-plain.jar" >nul && set JAR=%%f
)
if "%JAR%"=="" (
  echo No prebuilt jar in build\libs\, building ^(first build downloads Gradle and the dependencies^)...
  call gradlew.bat -q bootJar
  for %%f in (build\libs\patient-access-workbench-*.jar) do (
    echo %%f | findstr /v "\-plain.jar" >nul && set JAR=%%f
  )
)
if "%PAW_DATA_DIR%"=="" set PAW_DATA_DIR=.\data
if not exist "%PAW_DATA_DIR%" mkdir "%PAW_DATA_DIR%"
echo Starting %JAR% -^> http://localhost:8090/ui/
java -jar "%JAR%" %*
