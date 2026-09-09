@echo off
REM Starts the CMS-1500 claim bundle service. Needs Java 17 or newer on the PATH.
REM Uses the prebuilt jar in target\ when present, otherwise builds it with the Maven wrapper.
setlocal enabledelayedexpansion
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo java not found on PATH. Install Java 17 or newer ^(https://adoptium.net^) and retry.
  exit /b 1
)
set "JAR="
for %%f in (target\cms1500-claim-service-*.jar) do set "JAR=%%f"
if "%JAR%"=="" (
  echo No prebuilt jar in target\, building ^(first build downloads dependencies^)...
  call mvnw.cmd -q -DskipTests package
  for %%f in (target\cms1500-claim-service-*.jar) do set "JAR=%%f"
)
if not exist data\attachments mkdir data\attachments
if not exist data\bundles mkdir data\bundles
echo Starting %JAR% ^(attachments: .\data\attachments, bundles: .\data\bundles^)
java -jar "%JAR%" %*
