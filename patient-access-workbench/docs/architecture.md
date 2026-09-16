# Patient Access Workbench – architecture

Separate Gradle project at `patient-access-workbench/` in the Interop repo (self-contained: own Gradle build,
Gradle wrapper, README, run scripts, Dockerfile). Package root `com.thehiddenbrain.interop.patientaccess`.
Spring Boot 3.5.x, Java 17, HAPI FHIR 8.x (structures R4 + validation for FHIRPath / optional profile
validation), Jackson for generic JSON handling, java.net.http.HttpClient for outbound FHIR/OAuth calls,
nimbus-jose-jwt for SMART Backend Services client assertions. Plain HTML/JS/CSS browser UI at /ui/.

## Purpose
Test a payer's CMS-9115-F Patient Access API (FHIR R4; CARIN BB 2.x, PDex 2.x, US Core 3.1.1/6.1.0,
SMART App Launch) including the CMS-0057-F prior-authorization content (PDex PriorAuthorization profile,
ExplanationOfBenefit use=preauthorization) against vendor environments (Onyx SAFHIR UAT / Prod / sandbox).

## Modules (packages)

```
patientaccess/
  PatientAccessWorkbenchApplication
  config/        WorkbenchProperties (paw.*), UiConfig, OpenApiConfig, SecurityHeadersFilter,
                 BasicAuthFilter (optional), HttpClientFactory, StartupReport, JacksonConfig
  common/        WorkbenchException (code -> HTTP status), ApiError, JsonFileStore<T> (atomic JSON
                 persistence with optimistic version), Ids, Clock
  secrets/       SecretCrypto (AES-256-GCM, master key from PAW_MASTER_KEY or data/master.key),
                 Secret (encrypted-at-rest value; masked view {set, hint})
  environment/   Environment, AuthConfig, IdentifierSystem, FhirOptions, EnvironmentTier,
                 EnvironmentStore, EnvironmentService (validation, CRUD, connectivity test), EnvironmentView
  auth/          AuthMode, AccessToken, TokenCache, TokenService, SmartDiscoveryService (well-known +
                 CapabilityStatement oauth-uris), ClientCredentialsFlow, BackendServicesFlow (JWT
                 assertion RS384/ES384), SmartAuthCodeFlow (PKCE, state, /oauth/callback), TokenStore
                 (encrypted persisted tokens incl. refresh tokens), Pkce
  fhir/          FhirGateway (single outbound FHIR call path: build URL, headers, auth, timing,
                 redaction, request log, retry on 429/503, next-link guard against SSRF), FhirRequest,
                 FhirResponse, SearchPage, SearchQuery (params + IG validation warnings), OperationOutcomes,
                 FhirJson (parser helpers: JsonNode + HAPI R4 lenient parser), Paging
  history/       RequestRecord, RequestLog (ring buffer + JSONL files per day), CurlRenderer, Redaction
  catalog/       IgCatalog (loaded from ig-catalog.json generated from the IG packages by
                 tools/generate-catalog.py), SearchParamSpec, ResourceSpec, ProfileSpec, PaCodes
  search/        MemberSearchService (member id / name / birthdate / gender / _id / arbitrary params;
                 identifier-system strategies; Coverage fallback), MemberSearchRequest/Result, PatientSummary
  patient/       PatientWorkspaceService (overview counts, per-data-class fetch), summarizers
                 (ResourceSummarizer registry: Patient, Coverage, EOB, Condition, Observation, ...),
                 PriorAuthSummarizer + PriorAuthSummary (0057-F data elements), ProfileLiteChecker
                 (required + must-support element checks from catalog rules, FHIRPath)
  conformance/   Check, CheckContext, CheckResult, CheckGroup, ConformanceSuite (catalog of checks),
                 ConformanceRunner (async runs, progress, persistence), RunStore, HtmlReport, checks/*
                 (DiscoveryChecks, SmartChecks, SecurityChecks, PatientChecks, CoverageChecks,
                 EobChecks, PriorAuthChecks, ClinicalChecks, PagingChecks, ErrorHandlingChecks,
                 ProvenanceChecks, FormularyChecks, TimelinessChecks), FullValidator (optional HAPI
                 validator with IG npm packages from paw.validation.packages-dir)
  demo/          DemoFhirServer (in-process sample Patient Access API at /demo/fhir with IG example
                 resources: metadata, smart-configuration, token endpoint, Patient/Coverage/EOB/PA/
                 clinical search with basic param filtering and paging) - enabled in dev, off in prod
  api/           EnvironmentController, AuthController (+ OAuthCallbackController), SearchController,
                 FhirProxyController, PatientController, PriorAuthController, CatalogController,
                 ConformanceController, HistoryController, SettingsController, InfoController,
                 RestExceptionHandler
resources/
  application.yaml, application-dev.yaml, application-prod.yaml
  catalog/ig-catalog.json, catalog/pa-codes.json
  demo/*.json (example resources copied from the HL7 packages, CC0)
  ui/index.html, ui/app.css, ui/js/*.js (api, state, views per tab)
```

## Key decisions
- Outbound calls never leave the configured environment: FhirGateway only calls URLs under the
  environment's base URLs (default plus per-IG bases), and next links that start with one of them
  (other hosts only when `fhirOptions.allowNextLinkHostMismatch`); resource types and ids in paths are
  validated and dot segments refused. Token and authorize endpoints come from the auth config or, when
  discovery is on, from the vendor's smart-configuration; discovered endpoints must be absolute http(s),
  https for PROD, and may not point at private or metadata addresses.
- The workbench UI and demo server are disabled by the prod profile only for the demo; the UI stays on
  behind basic auth (`PAW_UI_ENABLED=false` turns it off).
- Secrets (client secret, static token, private key JWK, secret header values, refresh tokens) are
  AES-GCM encrypted at rest and never returned by the API (masked view). Logs and request history
  redact Authorization headers and any header marked secret; token responses are stored redacted.
- Persistence is JSON files in `paw.data-dir` (atomic temp+move writes, `version` for optimistic
  concurrency) so the app runs anywhere with a volume; no database required.
- The IG catalog is generated from the HL7 packages (not hand-typed) and drives both the UI search
  options and the conformance checks. The generator and its output are committed.
- Conformance checks are data + code: each check declares id, group, IG citation, severity (SHALL →
  FAIL, SHOULD → WARN, MAY → INFO), what context it needs (auth? patient?), and records the request
  ids it used as evidence. Runs are persisted and exportable (JSON, HTML).
- Prior-authorization summary is derived exactly from the PDex PriorAuthorization profile elements
  (use, status, outcome, item.adjudication reviewAction / allowedunits / consumedunits / denialreason,
  total submitted/eligible/utilized + PriorAuthorizationUtilization, preAuthRefPeriod, item extension
  preAuthPeriod/preAuthIssueDate/itemTraceNumber/authorizationNumber, when-adjudicated).
- The workbench UI/API can be protected with basic auth (paw.security.basic.*) and always sends
  security headers; the demo server is disabled by default in the prod profile.

## REST API (all JSON under /api/v1)
- `GET/POST /environments`, `GET/PUT/DELETE /environments/{id}`, `POST /environments/{id}/test`,
  `GET /environments/{id}/discovery`, `POST /environments/{id}/duplicate`
- `GET /environments/{id}/auth/status`, `POST /environments/{id}/auth/token` (obtain/refresh),
  `POST /environments/{id}/auth/smart/start` → {authorizeUrl}, `GET /oauth/callback`,
  `POST /environments/{id}/auth/token/manual` (paste a token), `DELETE /environments/{id}/auth/token`
- `POST /environments/{id}/members/search` (member id / name / dob / gender / _id / extra params)
- `GET /environments/{id}/fhir/{type}` (proxied search, query params passed through, IG warnings),
  `GET /environments/{id}/fhir/{type}/{rid}`, `GET /environments/{id}/fhir/page?url=` (next link)
- `GET /environments/{id}/patients/{pid}/overview|coverage|claims|prior-auth|clinical/{type}|
  provenance|everything`
- `GET /catalog`, `GET /catalog/resources/{type}`
- `GET /conformance/checks`, `POST /conformance/runs`, `GET /conformance/runs`,
  `GET /conformance/runs/{id}`, `GET /conformance/runs/{id}/report` (html), `DELETE /conformance/runs/{id}`
- `GET /history?environmentId=&purpose=&limit=`, `GET /history/{id}`, `GET /history/{id}/curl`
- `GET/PUT /settings`, `GET /info`
