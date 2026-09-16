# Patient Access API Workbench

Spring Boot 3.5 / Java 17 / Gradle application for testing a payer's **CMS-9115-F Patient Access API**, including the prior
authorization content added by **CMS-0057-F**, against vendor FHIR environments such as Onyx SAFHIR
UAT and Prod. It manages environments and their OAuth/SMART credentials, finds members with the
search parameters the HL7 implementation guides define, browses claims, prior authorizations, coverage
and clinical data with profile checks, and runs an automated conformance suite whose results can be
exported as HTML or JSON. Every outbound request is recorded (secrets redacted) and can be replayed as
cURL.

Standards covered (bundled as a generated catalog, see `tools/generate-catalog.py`):

| Standard | Version | Used for |
|---|---|---|
| HL7 FHIR | R4 4.0.1 | everything |
| CARIN IG for Blue Button (C4BB) | 2.1.0 | Patient, Coverage, ExplanationOfBenefit (5 claim profiles), Organization, Practitioner, RelatedPerson |
| Da Vinci PDex | 2.1.0 | PDex PriorAuthorization profile (CMS-0057-F prior auth in the Patient Access API), `ExplanationOfBenefit?use=preauthorization`, MedicationDispense, Provenance, `$member-match` metadata |
| US Core | 3.1.1 / 6.1.0 search expectations (catalog built from the US Core server CapabilityStatement) | clinical data (USCDI) |
| Da Vinci US Drug Formulary | 2.1.0 | formulary resources (optional checks) |
| SMART App Launch | 1.0 / 2.x | discovery (`/.well-known/smart-configuration`), standalone launch with PKCE, backend services |

## Quick start

Java 17 or newer is required; Maven is downloaded by the wrapper on first use.

```bash
cd patient-access-workbench
./run.sh            # Mac / Linux  (run.cmd on Windows)
```

Open `http://localhost:8090/ui/`. The first time, press **Add demo environment**: the workbench ships
an in-process sample Patient Access API (`/demo/fhir`, HL7 example data plus synthetic prior
authorizations and clinical data, OAuth token endpoint and a SMART login page) so every screen can be
tried offline. Then create real environments for the vendor's UAT and Prod tiers.

Swagger UI: `http://localhost:8090/swagger-ui.html` (spec at `/api-docs`). Health: `/actuator/health`.

## Running on your PC or in Spring Tool Suite / Eclipse

Get the code either way:

```bash
git clone https://github.com/thehiddenbrain/Interop.git
cd Interop
git checkout claude/cms-9115-f-api-workbench-2dw36s
cd patient-access-workbench
```

or download the branch as a zip from GitHub (`Code` > `Download ZIP` on that branch) and unzip the
`patient-access-workbench` folder anywhere. Only Java 17+ is needed; the Gradle wrapper downloads Gradle
8.14 and the dependencies on first use (about 250 MB, needs internet once). The build is Gradle
(`build.gradle`, Spring Boot plugin 3.5.16, Java toolchain 17).

**Command line**: `run.cmd` (Windows) or `./run.sh` (Mac/Linux), then open `http://localhost:8090/ui/`.

**Spring Tool Suite 4 / Eclipse**:

1. *File > Import > Gradle > Existing Gradle Project*, root directory `patient-access-workbench`, keep
   the wrapper selected, finish. Buildship downloads Gradle and the dependencies (progress in the
   bottom-right corner).
2. Make sure the project uses a JDK 17 or newer (*Project > Properties > Java Build Path > Libraries*),
   and that *Project > Properties > Java Compiler* is set to 17.
3. In the *Boot Dashboard* select `patient-access-workbench` and press *(Re)start*, or right-click
   `PatientAccessWorkbenchApplication.java` > *Run As > Spring Boot App*. The `dev` profile is the
   default: data goes to `./data` inside the project, the demo server and the UI are on.
4. Open `http://localhost:8090/ui/` and press *Add demo environment*. To change the port or the data
   folder, add `--server.port=9090` / `--paw.data-dir=C:\paw-data` as program arguments or set the
   `SERVER_PORT` / `PAW_DATA_DIR` environment variables in the run configuration.
5. Tests: right-click the project > *Run As > JUnit Test* (or `gradlew test`); they need no network
   after the first build.

IntelliJ IDEA: *File > Open* the `build.gradle` as a project and run the same main class.

## Working with environments

An environment is one FHIR endpoint with its own credentials, e.g. *Onyx UAT* and *Onyx Prod*:

* **General**: name, vendor, tier (`SANDBOX`, `UAT`, `PROD`, `OTHER`), FHIR base URL. PROD refuses
  plain http and "trust all certificates".
* **Authorization**: one of
  * `NONE` – open server;
  * `STATIC_TOKEN` – a bearer token you paste (stored encrypted);
  * `CLIENT_CREDENTIALS` – OAuth 2.0 client id + secret (`client_secret_basic` or `client_secret_post`),
    scopes, optional `audience`, extra token parameters;
  * `BACKEND_SERVICES` – SMART Backend Services: a private JWK (RSA or EC) signs the `client_assertion`
    (RS384/ES384); the public JWK Set to register with the vendor is available from the Auth panel;
  * `SMART_AUTHORIZATION_CODE` – SMART App Launch standalone launch with PKCE through the member's
    browser login; the redirect URI to register is `<public base URL>/oauth/callback`. Refresh tokens are
    used automatically when the access token expires.
  Endpoints are discovered from `/.well-known/smart-configuration` (falling back to the CapabilityStatement
  `oauth-uris` extension) unless you enter them.
* **Extra headers**: API-management keys and the like; mark them *secret* to encrypt them and mask them
  in the history.
* **Member identifier systems**: the `Patient.identifier.system` URIs the payer uses (member id, MBI,
  Medicaid id...). Member-id searches try every default system as `identifier=<system>|<value>`.
* **FHIR options**: `_count`, pages to follow, Accept header, timeouts, `Prefer: handling=lenient`.

**Test connection** fetches `metadata` and the SMART discovery document, obtains a token and makes one
authenticated call, reporting each step with a link to the recorded request.

Secrets (client secrets, static tokens, private keys, secret headers, refresh tokens) are AES-256-GCM
encrypted at rest with the master key (`PAW_MASTER_KEY`, 32 bytes base64). Without one, a key is generated
into `<data-dir>/master.key` – fine on a laptop, not for a shared deployment.

## Finding members

The Search tab runs the searches the IGs require: `Patient?identifier=<system>|<member id>` (with a
`Coverage?identifier=` fallback that resolves the beneficiary), `Patient?name=&birthdate=&gender=`,
`family=` / `given=`, `Patient/{id}`, plus any other Patient search parameter. The right-hand panel lists
every Patient search parameter and required combination from C4BB, PDex and US Core with its
SHALL/SHOULD/MAY expectation. Every query that was sent is listed with status, count and a link to the
history entry.

## Patient workspace

For a member the Patient tab shows counts per data class (Coverage, claims, prior authorizations and the
US Core clinical resource types), then tables per class with the key columns, `meta.profile`, and a
**profile check** (required and must-support elements of the C4BB / PDex profiles, slice and fixed-value
aware; US Core profiles are labelled, full validation is optional, see below). Claims can be filtered by
type and `_lastUpdated`; clinical classes accept extra search parameters. The *Advanced request* box
sends any search, read or page URL with the environment's token and shows the raw response.

## Prior authorizations (CMS-0057-F)

The Prior auth tab reads `ExplanationOfBenefit?patient=<id>&use=preauthorization` (PDex search
parameter; when a server rejects or ignores it, all EOBs are fetched and filtered by `use` / profile and a
note says so) and derives, per authorization: decision (from the PDex `reviewAction` extension codes
A1 certified, A2 partial, A3 not certified, A4 pended, A6 modified, C cancelled, CT contact payer; else from
denial reasons and outcome), decision date, validity period, insurer/provider, the items and services with
approved and consumed units, denial reasons (X12 CARC/RARC), totals with the utilization extension, and
the profile issues found. A checklist counts the CMS-0057-F data elements present across the member's
authorizations.

## Conformance suite

The Conformance tab runs groups of automated checks against an environment (with a member id for the
member-level groups): discovery, SMART discovery, security, Patient, Coverage, claims (EOB), prior
authorization, clinical (US Core), Provenance, paging, error handling, formulary and performance. Each
check names its IG citation and severity (a failed SHALL is `FAIL`, a failed SHOULD is `WARN`, a MAY is
`INFO`) and links the requests it used as evidence. Runs are stored under `<data-dir>/conformance/` and
can be exported as a self-contained HTML report or JSON.

## Request history

Every FHIR, discovery and token request is kept in memory (`paw.history.max-entries`) and appended to
`<data-dir>/history/requests-<date>.jsonl`. Authorization headers, secret headers and token bodies are
redacted before they are stored; response bodies contain member data, so the files are purged after
`paw.history.retention-days` (30 by default). Any entry can be copied as a cURL command (`$TOKEN` placeholder).

## Configuration

`application.yaml` holds the defaults, `application-dev.yaml` and `application-prod.yaml` the profile
overrides. Everything can be set with environment variables.

| Property | Env variable | Default | Meaning |
|---|---|---|---|
| `paw.data-dir` | `PAW_DATA_DIR` | `./data` (dev), `/var/lib/patient-access-workbench` (prod) | environments, tokens, history, runs |
| `paw.master-key` | `PAW_MASTER_KEY` | generated file | 32 random bytes, base64; encrypts secrets |
| `paw.public-base-url` | `PAW_PUBLIC_BASE_URL` | derived from requests | builds the SMART redirect URI behind a proxy |
| `paw.ui.enabled` | `PAW_UI_ENABLED` | `true` | browser UI at `/ui/` |
| `paw.demo.enabled` | `PAW_DEMO_ENABLED` | `true` (dev), `false` (prod) | in-process sample server at `/demo/fhir` |
| `paw.security.basic.enabled` | `PAW_BASIC_AUTH_ENABLED` | `false` (dev), `true` (prod) | HTTP basic auth for the whole workbench |
| `paw.security.basic.username/password` | `PAW_BASIC_AUTH_USERNAME/PASSWORD` | `workbench` / (none) | credentials (password required when enabled) |
| `paw.http.connect-timeout`, `read-timeout` | – | 10s / 60s | outbound timeouts (per environment overrides) |
| `paw.http.max-retries` | – | 1 | retries on 429/503 honouring `Retry-After` |
| `paw.history.max-entries`, `max-body-bytes`, `persist`, `retention-days` | – | 1000 / 262144 / true / 30 | request history; files older than the retention are purged daily |
| `paw.search.page-size`, `max-pages` | – | 50 / 20 | `_count` and pages followed |
| `paw.conformance.max-pages`, `concurrency`, `slow-warn-ms`, `slow-fail-ms` | – | 5 / 4 / 3000 / 10000 | conformance runs |
| `paw.validation.packages-dir` | `PAW_PACKAGES_DIR` | `./packages` | FHIR IG npm packages for optional full validation |
| `server.port` | `SERVER_PORT` | 8090 | HTTP port |

Generate a master key: `openssl rand -base64 32`.

## Production deployment

```bash
docker build -t patient-access-workbench .
docker run -d --name paw -p 8090:8090 \
  -e PAW_MASTER_KEY="$(openssl rand -base64 32)" \
  -e PAW_BASIC_AUTH_PASSWORD='choose-a-long-password' \
  -e PAW_PUBLIC_BASE_URL=https://workbench.example.com \
  -v paw-data:/var/lib/patient-access-workbench \
  patient-access-workbench
```

The image runs with the `prod` profile: basic auth on, demo server off, Swagger off, UI on (set
`PAW_UI_ENABLED=false` for an API-only deployment), data on the volume. Size the heap for the history
ring (`max-entries` × `max-body-bytes` worst case) and, when IG packages are loaded for full validation,
allow 1.5 GB or more.
Put TLS on a reverse proxy in front (the app honours `X-Forwarded-*`). Keep the master key outside the
volume (a secret manager or the orchestrator's secret store): losing it means re-entering every secret.
Readiness/liveness probes: `/actuator/health/readiness` and `/actuator/health/liveness`.

## Build and test

```bash
./gradlew build                                           # build + tests (gradlew.bat build on Windows)
./gradlew bootRun                                         # run from sources with the dev profile
java -jar build/libs/patient-access-workbench-0.1.0-SNAPSHOT.jar
SPRING_PROFILES_ACTIVE=prod PAW_MASTER_KEY=... PAW_BASIC_AUTH_PASSWORD=... java -jar build/libs/patient-access-workbench-0.1.0-SNAPSHOT.jar
```

Regenerate the IG catalog after upgrading a package: download the packages as described in
`tools/generate-catalog.py`, then `python3 tools/generate-catalog.py <packages-dir>`.

## Project layout

```
src/main/java/com/thehiddenbrain/interop/patientaccess/
  config/        properties (paw.*), UI, OpenAPI, security headers, optional basic auth
  secrets/       AES-GCM encryption of stored secrets
  environment/   environment model, store (environments.json), validation, connection probe
  auth/          SMART discovery, token service (client credentials, backend services JWT,
                 authorization code + PKCE, refresh), encrypted token store
  fhir/          HTTP executor with history recording and retries, URL builder with target guard, gateway
  history/       request log (ring + JSONL), redaction, cURL rendering
  catalog/       IG catalog (search parameters, profiles, codes) generated from the HL7 packages
  search/        member search strategies
  patient/       summaries per resource type, prior-auth summarizer, profile-lite checker, workspace
  conformance/   check framework, runner, run store, HTML report, checks/ (the suite)
  demo/          in-process sample Patient Access API + OAuth server
  api/           REST controllers
src/main/resources/catalog/ig-catalog.json   generated catalog
src/main/resources/demo/*.json               sample data (HL7 IG examples, CC0, plus synthetic records)
src/main/resources/ui/                       browser UI (plain HTML/CSS/JS)
docs/                                        architecture and reference notes
tools/generate-catalog.py                    catalog generator
```

See `docs/architecture.md` for the module design and the REST API, and `docs/reference.md` for the
regulatory and IG reference the checks are based on.
