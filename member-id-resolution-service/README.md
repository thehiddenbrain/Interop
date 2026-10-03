# Member ID Resolution Service

Spring Boot service for Onyx. Onyx sends the member ID exactly as the provider's EMR supplied it, the
date of service and the UM vendor; the service verifies the ID with MMI (Master Member Index), returns it
**as stored**, returns it **in the vendor's format**, and says whether coverage is **active** on the date
of service. One endpoint, one downstream (MMI), no database, no state.

| | |
|---|---|
| Endpoint | `POST /api/v1/member-ids/resolve` (port **9090**) |
| Stack | Java 17+, Spring Boot 3.5, Gradle (wrapper included), springdoc OpenAPI |
| Swagger UI | `http://localhost:9090/swagger-ui.html` (off in `prod`) |
| Health | `http://localhost:9090/actuator/health` |

## 1. Run it in STS (or any IDE)

1. **File → Import → Gradle → Existing Gradle Project**, pick this folder (`member-id-resolution-service`),
   accept the defaults (Gradle wrapper, version 9.8). JDK 17, 21 and 25 all work: the code compiles for
   Java 17, and Gradle 9.8 runs on any of those JDKs. If the import ever fails with
   `Unsupported class file major version NN`, Gradle is being run on a JDK newer than the wrapper
   supports; either keep the shipped wrapper version or point STS at a supported JDK
   (Window → Preferences → Gradle → Advanced Options → Java home).
2. Run `MemberIdResolutionApplication` as a **Spring Boot App**. With no profile set it runs the `local`
   profile: an **in-process MMI stub** answers from `src/main/resources/mmi-stub/members.json`, so nothing
   needs network access.
3. Open `http://localhost:9090/swagger-ui.html` or import the Postman collection in `postman/`.

Command line: `./gradlew bootRun` (local profile) · `./gradlew test` (all tests) · `./gradlew bootJar`
then `java -jar build/libs/member-id-resolution-service-0.1.0-SNAPSHOT.jar --spring.profiles.active=pqa`.

### Point it at a real MMI

| Profile | MMI |
|---|---|
| `local` (default) | in-process stub, no network |
| `fqa` | `http://mastermemberindexserviceapp-spring-boot-fqa.apps.tdqocp.thp.tahphq.tahp` |
| `pqa` | `http://mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp` |
| `pqa-lite` | `http://mastermemberindexserviceapp-spring-boot-pqa-lite.apps.tdqocp.thp.tahphq.tahp` |
| `prod` | `http://mastermemberindexserviceapp-spring-boot-prod.apps.prodocp.thp.tahphq.tahp` |

In STS: Run Configurations → Spring Boot App → **Profile: `pqa`**. From a jar: `--spring.profiles.active=pqa`
or `SPRING_PROFILES_ACTIVE=pqa`. Overrides without a rebuild: `MMI_BASE_URL`, `MMI_CLIENT_ID` (placeholder
`MBRIDSVC`; register the real application name with the MMI team), `SPRING_HTTP_CLIENT_READ_TIMEOUT=5s`.

**Deployment rule: always set `SPRING_PROFILES_ACTIVE`.** The default profile is `local` (the stub) so that
STS runs with one click. Two guards back the rule: the stub refuses to start with any explicitly active
profile other than `local` or `test`, and it refuses to start inside a Kubernetes/OpenShift pod at all
(it checks `KUBERNETES_SERVICE_HOST`), so a pod that forgot its profile fails loudly instead of answering
from canned data.

## 2. The API

### Request

```http
POST /api/v1/member-ids/resolve
Content-Type: application/json
X-Correlation-Id: ONYX-PA-2026-000123        (optional; echoed, generated when absent)

{ "memberId": "123456789", "dateOfService": "2026-10-15", "vendor": "EVICORE",
  "patient": { "dateOfBirth": "1950-03-15" } }
```

| Field | Required | Notes |
|---|---|---|
| `memberId` | yes | As the EMR sent it. 9 digits, 11 digits, 14 characters with spaces, `HP-…`, any separators, any case. |
| `dateOfService` | no | `yyyy-MM-dd`. **Defaults to today** when omitted (`dateOfServiceDefaulted: true` in the response). Must be within 10 years back / 366 days forward. |
| `vendor` | yes | Code or alias from the vendor table, case-insensitive: `EVICORE`, `MHK`, `EVOLENT`, `CARELON`, `OPTUM` (samples). |
| `patient.dateOfBirth` | recommended | `yyyy-MM-dd`. Used only to verify or pick among the records MMI returned. Never sent to MMI, never logged, never echoed. |

### Response (HTTP 200): branch on `outcome`

| `outcome` | Meaning | Onyx action |
|---|---|---|
| `ACTIVE` | Member verified, `coverage.active = true` on the date of service | Put `memberId.forVendor` in the vendor payload. For Optum use `forVendorParts` when it is present (TMP/SCO ids); for Public Plans and HPHC ids there are no parts, send `forVendor`. |
| `INACTIVE` | Member verified, `coverage.active = false`; `coverage.reason` and the nearest span dates say why | Hold for intake (owner decision). |
| `NOT_FOUND` | MMI has no record for this ID | "Member not found" worklist. |
| `AMBIGUOUS` | A 9-digit ID of a population with dependents matched several persons and no DOB settled it | Resend with the 11-character ID or `patient.dateOfBirth`, else intake picks from `candidates[]`. |

```json
{ "outcome": "ACTIVE",
  "memberId": { "received": "123456789", "searched": "123456789", "stored": "123456789   01", "forVendor": "12345678901" },
  "vendor": "EVICORE", "company": "THP", "lineOfBusiness": "MCR",
  "dateOfService": "2026-10-15", "dateOfServiceDefaulted": false,
  "coverage": { "active": true, "reason": "COVERED", "span": { "effectiveDate": "2024-01-01", "endDate": null } },
  "correlationId": "ONYX-PA-2026-000123", "mmiRequestId": "MBRIDSVC-1760000000000-48213" }
```

`memberId.stored` is the ID exactly as MMI holds it. `forVendor` is that ID in the vendor's format.
For Optum (`SPLIT`) **and a TMP/SCO id** the response also carries `forVendorParts: { "memberId": "123456789",
"suffix": "01" }`; for Public Plans and HPHC ids only `forVendor` is returned (their ids have no suffix to split).
Public Plans IDs (11 continuous characters) and HPHC IDs (`HP` + 9 digits) are passed as stored for every vendor.
`coverage.reason` is one of `COVERED`, `NO_COVERAGE_RECORDS`, `NOT_YET_EFFECTIVE`, `COVERAGE_ENDED`,
`COVERAGE_GAP`. `coverage.lastEndDate` (end of the latest span before the date) and `coverage.nextEffectiveDate`
(start of the earliest span after it) are present whenever such spans exist, for ACTIVE answers too, so a
defaulted date of service still shows recent past and future coverage.

### Errors (non-200): `{ "error": { "code", "message", "details": [ { "field", "code", "message" } ] }, "correlationId", "mmiRequestId" }`

Every answer the application produces has this shape (`mmiRequestId` is present once MMI was called,
including on a 422). The one exception is a request Tomcat rejects before it reaches the application
(malformed percent-encoding in the URL, a header block over 8 KB): that returns Spring Boot's default
error JSON.

| HTTP | `error.code` | When | Onyx action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | bad member ID shape, bad dates, missing vendor, malformed JSON, unknown property, wrong JSON type. Every problem is listed in `details[]`. (404 / 405 / 406 / 415 use the same envelope for a wrong route, method, representation or content type.) | Never retry. `MEMBER_ID_*` and `DATE_OF_SERVICE_OUT_OF_RANGE` are provider data problems; anything else is an Onyx mapping defect. |
| 400 | `UNKNOWN_VENDOR` | vendor not in the table; `details[0].message` lists the known codes | Never retry; routing table and this table disagree. |
| 422 | `DOB_MISMATCH` | a DOB was sent and matches no record for this ID | Manual identity review; never file the auth. |
| 502 | `MMI_ERROR`, `MMI_INVALID_RESPONSE` | MMI rejected the call, answered unreadably, or the member's only coverage spans have unreadable dates (`UNREADABLE_COVERAGE`: the service refuses to say INACTIVE on data it cannot read) | Park, alert the service owners. |
| 503 | `MMI_UNAVAILABLE` (`Retry-After: 10`) | MMI unreachable, timed out, or 5xx | Retry later. |
| 500 | `INTERNAL_ERROR` | a bug here | Retry once later, alert the service owners. |

**If this service itself is unreachable**, Onyx's agreed fallback is to pass the member ID exactly as it
received it from the EMR to the UM vendor. Nothing here needs to be built for that; it is an Onyx rule.

## 3. How an answer is produced

1. Normalise the ID: trim, drop separators (spaces, dashes, `_ . /`), upper-case. Accept `\d{9}`,
   `\d{11}`, `HP\d{9}`; reject everything else (no guessing of suffixes).
2. One MMI call: `POST {mmi.base-url}/master/member/v1` with the normalised ID in `memberId` **and**
   `legacyMemberId` (the MMI spec's hit-rate advice), `voidCoverageRecord: N`, `clientId`, `clientType: INT`,
   a fresh `requestId` (`MBRIDSVC-<millis>-<5 digits>`). No date filter: coverage is evaluated locally so
   INACTIVE can say why. No demographics are ever sent.
3. Reduce the records: identical records merged; records linked through `legacyMemberId` (THP↔HPHC
   conversion) are one person and the record covering the date of service wins; a supplied DOB picks one
   person or proves a mismatch; several persons without a DOB → `AMBIGUOUS`.
4. Coverage: a non-void span with `effDate ≤ DOS ≤ endDate` (inclusive; null or `12/31/9999` = open)
   → active. A span with an unreadable date is skipped and logged with `marker=UNREADABLE_SPAN`; the
   remaining spans decide. If a member has unreadable spans and no readable one, the answer is 502
   `UNREADABLE_COVERAGE`, never a confident INACTIVE. For a converted member the two records' spans are
   evaluated together, so a gap between the old and the new record is reported as a gap.
5. Format for the vendor from the stored ID (never from the input).

TMP / SCO members have no dependents: a 9-digit card number returns exactly one record and resolves
directly. Only populations with dependents (HPHC commercial, Together) can produce `AMBIGUOUS`.

## 4. Vendor formats: the only thing to edit when a vendor changes

`src/main/resources/application.yml`, block `member-id.vendors` (edit it there; the block below is a copy of
the shipped values). Change a value, redeploy; the startup log prints the effective table rendered against
a sample ID.

```yaml
member-id:
  vendors:
    EVICORE:
      display-name: eviCore
      aliases: [EVI, EVICORE_HEALTHCARE]
      format: COMPACT_11                # 12345678901
    MHK:
      display-name: MHK (MedHOK)
      aliases: [MEDHOK]
      format: SPACED_14                 # 123456789   01
    EVOLENT:
      display-name: Evolent
      aliases: [EVOLENT_HEALTH]
      format: COMPACT_11
    CARELON:
      display-name: Carelon
      aliases: [AIM]
      format: COMPACT_11                # TO CONFIRM with the owner: 11 or 14
    OPTUM:
      display-name: Optum
      format: SPLIT                     # "123456789" + "01" as two fields (TMP/SCO ids)
```

Formats: `COMPACT_11`, `SPACED_14`, `SPLIT`, `AS_STORED`. A new output shape = one constant in
`VendorIdFormat` + one case in `VendorFormatter` + one test row.

## 5. Stub fixtures (local profile) and Postman

`src/main/resources/mmi-stub/members.json` behaves like MMI: exact match, 9-digit (policy) match returning
the family, legacy-id match and the second pass through `legacyMemberId`.

| Member ID (any accepted form) | Case |
|---|---|
| `123456789` / `12345678901` / `123456789   01` | TMP, active, open-ended |
| `234567890` | SCO, coverage ended 2025-12-31 → INACTIVE |
| `345678901` | Together family: 01 subscriber (DOB 1985-06-01), 02 dependent (DOB 2012-09-09, gap 06/2024–12/2024), 03 twin (ended 2024-12-31) → AMBIGUOUS without DOB |
| `HP456789012` / `HP-456789012` | HPHC, active (end `12/31/9999`) |
| `567890123 01` ↔ `HP567890123` | converted member: THP to 2024-12-31, HPHC from 2025-01-01 |
| `678901234` | void span + ended span → INACTIVE |
| `789012345` | coverage starts 2027-01-01 → NOT_YET_EFFECTIVE |
| `890123456` | one unreadable span + one valid → ACTIVE with a warning |
| `880000000` | only an unreadable span → 502 `UNREADABLE_COVERAGE` |
| `901234567` | company missing on the record (inferred), restrictedData Y |
| `500500500` · `503503503` · `400400400` · `888888888` · `202202202` | faults: MMI 500 → 503, timeout → 503, MMI 400 → 502, error message with no members → 502, bad body → 502 |
| `887777777` · `886666666` | an error message beside a member record → 200 with a warning; a record without a member id → 502 `NO_MEMBER_ID` |
| anything else | NOT_FOUND |

Postman: import `postman/MemberIdResolution.postman_collection.json` and
`postman/Local.postman_environment.json` (`baseUrl = http://localhost:9090`). Every request carries tests;
run the whole collection with the Collection Runner for a green scenario pass.

## 6. Tests

`./gradlew test`: parser rows, coverage rules, selection rules (including converted members in a gap and
with overlapping records), vendor formats, MMI mapping, the REST client against a mock server
(500/429/408/400/bad body/connection refused/read timeout), configuration validation, and the end-to-end
scenario matrix over HTTP against the stub with "today" fixed at 2026-10-03. A capturing log appender
asserts no log line (message, exception text or MDC) contains an unmasked 9- or 11-digit run or an
MM/dd/yyyy date, and no response body contains names or SSN; a stub call counter proves invalid requests
never reach MMI.

## 7. Assumptions to confirm in PQA

- MMI accepts the 9-digit, 11-digit and 14-character forms of a TMP ID equally; the service sends the
  compact form (separators removed) and the same value in `legacyMemberId`.
- A single record returned for any population is the member (TMP/SCO always return one).
- HPHC IDs are `HP` + 9 digits (`member-id.hphc-digit-lengths`).
- MMI's error `messageType` is `ERROR` (`mmi.error-message-types`); "no match" is `members: null`.
- Carelon's format (11 or 14).
- `coverage.active` is the flag; the span is supporting detail. Legacy IDs, migration dates, PCP and
  group names are intentionally not returned.
