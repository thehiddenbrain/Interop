# Interop Resolution Service

The service that answers Onyx's interop lookups for Point32Health. Its first capability, and the subject of this
document, is member ID resolution; payer ID and payer organization name lookups are planned to join it.

Spring Boot service for Onyx. Onyx identifies itself and the call (`clientId`, `clientType`, `requestId`) and sends the
member ID exactly as the provider's EMR supplied it, with the date of service, a single day or a period
(`dateOfService` to `dateOfServiceEnd`). The service verifies the ID with MMI (Master Member Index), returns the ID it
**resolved** to and that ID **in every UM vendor's format** with the payer for that vendor's request, and says
whether coverage is **active**. One call serves a
prior authorization whose codes go to several vendors. One operation, `POST /v1/interop/resolve`, two downstreams
(MMI, and the member information service for the coverage records behind the line of business), no database, no state.

| | |
|---|---|
| Endpoint | `POST /v1/interop/resolve`, port **9090** |
| Stack | Spring Boot 4.0.7 (Spring Framework 7, Jackson 3) / Java 17+ / Gradle 9.5 wrapper, springdoc 3: the same build shape as the EPA Workbench and the member profile service |
| Swagger UI | `http://localhost:9090/swagger-ui.html` (off in `PRD`); the raw document is `/api-docs` |
| OpenAPI to share | `docs/openapi/interop-resolution-service-openapi.yaml` and `.json`: `/api-docs` saved without the generated `localhost` server entry. Regenerate after any contract change: start the service on `DEV`, save `/api-docs`, drop `servers`. |
| Health | `http://localhost:9090/actuator/health` |
| Design | `docs/design.html` (open it in a browser); the vendor-facing overview is `docs/interop-resolution-service-high-level.drawio` (draw.io) and its `.png` |

## 1. Run it in STS (or any IDE)

### Import into STS

The project is a standalone Gradle build in this folder, `interop-resolution-service`.

1. **File > Import... > Gradle > Existing Gradle Project**, Project root directory = this folder (keep the folder
   name; Buildship wants it equal to the project name) > Finish. Buildship uses the wrapper (Gradle 9.5.0) and
   downloads the dependencies on the first import; wait for the *Synchronize Gradle projects* job in the
   bottom-right corner to finish before running anything.
2. Right-click `InteropResolutionApplication` > **Run As > Spring Boot App**. The default profile is `PQA`
   (the real PQA MMI, network needed). For the in-process stub open *Run Configurations... > Spring Boot App*
   and put `DEV` in the **Profile** field.

When `build.gradle` changes, right-click the project > **Gradle > Refresh Gradle Project**.

Before the first run check the JDK once: **Window > Preferences > Java > Installed JREs** must list a JDK 17 or
newer, ticked; under **Gradle** leave *Java home* on the workspace JRE unless that JRE is older than 17, in which case
point it at the JDK 17+ folder.

**Every file shows errors right after the import or a Gradle refresh** (Problems view: "The project was not built
since its build path is incomplete", "JRE System Library [JavaSE-25]", types "cannot be resolved" in the test folder,
or "Could not find or load main class" when you run): the Eclipse project got a Java level your STS does not support.
Buildship takes that level from `java.sourceCompatibility`, which the build pins to 17. Right-click the project >
**Gradle > Refresh Gradle Project**, then **Project > Clean...**. Check: Project > Properties > Java Compiler must say
compliance 17, and Java Build Path > Libraries must show a JRE System Library without a red mark. If that does not
help, remove the project from the workspace (without deleting its contents), switch to a new, empty workspace
(**File > Switch Workspace > Other...**) and import it again.

### Build and run

The build is the same shape as the EPA Workbench (`patient-access-workbench`): Gradle 9.5.0 wrapper,
Spring Boot 4.0.7, no toolchain block, `options.release = 17` plus `-parameters` (and `java.sourceCompatibility` /
`targetCompatibility` 17, which is what STS reads for the Eclipse project's Java level), `springBoot { buildInfo() }`,
and an `internalRepoUrl` Gradle property that swaps Maven Central for an internal mirror. Only a JDK 17 or
newer is needed (17, 21 and 25 all work); the wrapper downloads Gradle and the dependencies on first use.

1. Run `InteropResolutionApplication` as a **Spring Boot App**. With no profile set it runs the **`PQA`**
   profile and calls the PQA MMI. With Profile = `DEV` an **in-process MMI stub** answers from
   `src/main/resources/mmi-stub/members.json`, so nothing needs network access.
2. Open `http://localhost:9090/swagger-ui.html` or import the Postman collection in `postman/` (the collection
   expects the `DEV` profile).

Command line: `./run.sh` (Mac/Linux) or `run.cmd` / `run.bat` (Windows) build the jar on first use and start it;
`./gradlew bootRun` (PQA, the default), `./gradlew bootRun --args='--spring.profiles.active=DEV'` (stub),
`./gradlew test` (all tests), `./gradlew bootJar` then `java -jar build/libs/interop-resolution-service-1.0.0.jar`
(PQA) or `... --spring.profiles.active=DEV` (stub).

### Point it at a real MMI

| Profile = file | MMI |
|---|---|
| `DEV` = `application-DEV.yaml` | in-process stub, no network (`--spring.profiles.active=DEV`; Profile field `DEV` in STS) |
| `FQA` = `application-FQA.yaml` | `http://mastermemberindexserviceapp-spring-boot-fqa.apps.tdqocp.thp.tahphq.tahp` |
| `PQA` = `application-PQA.yaml` (**default**) | `http://mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp` |
| `PQA-LITE` = `application-PQA-LITE.yaml` | `http://mastermemberindexserviceapp-spring-boot-pqa-lite.apps.tdqocp.thp.tahphq.tahp` |
| `PRD` = `application-PRD.yaml` | `http://mastermemberindexserviceapp-spring-boot-prod.apps.prodocp.thp.tahphq.tahp` |

One configuration file per environment in `src/main/resources`, named by the environment in upper case; the profile
name is the file suffix (`SPRING_PROFILES_ACTIVE=DEV|FQA|PQA|PQA-LITE|PRD`). `application.yaml` holds what every
environment shares (port, JSON rules, MMI path and timeouts, the vendor table); an environment file sets only what differs
(the MMI and member information URLs, payload logging, the stubs, Swagger, log levels).

#### Pointing at the real MMI, step by step

The profile is the only switch. Any profile other than `DEV` turns the stub off and uses the MMI URL from
that environment's `application-<ENV>.yaml`; nothing else changes.

1. **STS**: Run > Run Configurations > Spring Boot App > `InteropResolutionApplication` > **Profile** field:
   `PQA` (or on the Arguments tab, Program arguments: `--spring.profiles.active=PQA`). Apply, Run.
2. **Command line**: `set SPRING_PROFILES_ACTIVE=PQA` then `run.cmd` (Windows), or
   `SPRING_PROFILES_ACTIVE=PQA ./run.sh`, or `java -jar build/libs/interop-resolution-service-1.0.0.jar --spring.profiles.active=PQA`.
3. **Check the startup banner** in the console. It must say `profiles : [PQA]`, `mmi client : REST`,
   `member-info client: REST` and the PQA URLs. If it says `STUB`, the profile did not apply. `http://localhost:9090/actuator/info` shows the build.
4. **Network**: your machine (or the pod) must reach `mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp`
   on port 80 (plain HTTP, no token, as the MMI contract states). If it cannot, every call answers
   `503 MEMBER_LOOKUP_UNAVAILABLE` with `CONNECT_FAILED` and the log line `mmi call failed ... cause=...` names the reason.
   It must also reach `memberinfoserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp` on port 80; if it cannot, every
   identified member answers `503 MEMBER_PLAN_UNAVAILABLE` with `CONNECT_FAILED` and the log line
   `member-info call failed ... cause=...` names the reason.
5. **Try it**: a Postman request with a real PQA member id, typed as the EMR has it, or
   `curl -X POST http://localhost:9090/v1/interop/resolve -H "Content-Type: application/json" -d "{\"memberId\":\"<real id>\"}"`.
   The response carries `traceId` (`INTEROP-<millis>-<5 digits>`), which the MMI team can find in their logs.

#### Reading the MMI request and response when something fails

`mmi.log-payloads: true` makes the MMI client write the exact request body and the raw response body to the
console as two lines per call. It is on in `DEV`, `FQA`, `PQA` and `PQA-LITE` and off in `PRD`
(the bodies contain PHI). The startup banner shows `mmi payload log : ON` when it is active. A real call
looks like this:

```
INFO  [<correlationId>] [<requestId>] org.p32h.interop.mmi.RestMmiClient - mmi request requestId=INTEROP-1791083521042-40296 POST http://mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp/master/member/v1
{"memberId":"123456789","legacyMemberId":"123456789","dosStartDate":"10/15/2026","voidCoverageRecord":"N","clientId":"INTEROP","clientType":"INT","requestId":"INTEROP-1791083521042-40296"}
INFO  [<correlationId>] [<requestId>] org.p32h.interop.mmi.RestMmiClient - mmi response requestId=INTEROP-1791083521042-40296 status=200 contentType=application/json ms=18
{"clientId":"INTEROP","clientType":"INT","requestId":"INTEROP-1791083521042-40296","messages":null,"members":[ ... ]}
```

What the lines tell you:

| You see | Meaning | What to do |
|---|---|---|
| `mmi request ...` then `mmi call failed ... detail=CONNECT_FAILED cause=...` and no `mmi response` line | The host could not be reached (DNS, VPN, firewall) | Fix the network path to the MMI host; check `cause=` |
| `mmi response ... status=404`, this service answers `200 NOT_FOUND` | Member not found: MMI's normal answer when it has no member for that id in that environment; `sourceMessage` in the response carries MMI's message when the body had one. If every id comes back `NOT_FOUND`, check the URL: a 404 whose body is not MMI's envelope (Spring's default error JSON with `status` / `error` / `path`, an HTML page) is still `NOT_FOUND`, but the log shows `marker=MMI_404_WITHOUT_ENVELOPE` with the URL | Use an id that exists in PQA. If the marker appears, compare the URL on the `mmi request` line with the MMI team's; `mmi.path` is `/master/member/v1` |
| `mmi response ... status=400`, this service answers `400 MEMBER_LOOKUP_REJECTED` with `HTTP_400` | MMI could not process the request as sent; the error message forwards MMI's code and text when the body had a message | Send the request line's JSON and MMI's text to the MMI team; adjust `mmi.client-id` / `mmi.client-type` if they ask |
| `mmi response ... status=500`, this service answers `503 MEMBER_LOOKUP_UNAVAILABLE` with `HTTP_500` and `Retry-After: 10` | An internal error in MMI; the error message forwards MMI's code and text when the body had a message | Retry later; if it persists, send the request line's JSON and MMI's message to the MMI team |
| `mmi response ... status=<anything else>` (403, 502, 503 ...), this service answers `503 MEMBER_LOOKUP_UNAVAILABLE` (5xx, 429, 408) or `502 MEMBER_LOOKUP_ERROR` (other 4xx) with `HTTP_<code>` | The status is outside MMI's contract: a gateway, proxy or container answered, not MMI; the error message says so and repeats the URL | Check the host, the proxy settings and the URL on the `mmi request` line |
| `mmi response ... status=200` then `502 MEMBER_LOOKUP_INVALID_RESPONSE` with `UNPARSEABLE_BODY` from this service | The body is not the MMI JSON (often an HTML sign-in or proxy page, `contentType=text/html`) | The call is being intercepted before MMI; check proxy settings and the host |
| `mmi response ... status=200` and the body has fields this service does not know | MMI added or renamed fields | Paste the response line; the DTOs in `org.p32h.interop.mmi` are updated to match |
| `mmi response ... status=200`, `members` is empty and `messages[]` has no `ERROR` message, this service answers `200 NOT_FOUND` | MMI answered 200 with no member (MMI's contract uses 404 for that; this form is kept as `NOT_FOUND` too) | Use an id that exists in PQA |
| `mmi response ... status=200`, `members` is empty and `messages[]` has an `ERROR` message (for example `ES_TIMEOUT`), this service answers `502 MEMBER_LOOKUP_ERROR` with `ERROR_MESSAGE` | MMI reported a failure of its own; the 502 message forwards MMI's `code=` and `text=` | Send the request line's JSON and MMI's message to the MMI team |

Turn it off with `mmi.log-payloads: false` in the profile file, or `MMI_LOG_PAYLOADS=false` once the
problem is found. The rest of the log is the same whether or not payload logging is on.

Overrides without a rebuild (environment variables or `-D` system properties): `PAYER_ID` / `PAYER_NAME`
(the default payer for the vendor requests), `MMI_BASE_URL` (any MMI, no
profile file needed), `MMI_CLIENT_ID` (placeholder `INTEROP`; register the real application name with the
MMI team), `MMI_CONNECT_TIMEOUT=2s`, `MMI_READ_TIMEOUT=5s`, `MMI_LOG_PAYLOADS=true|false`.

**Deployment rule: always set `SPRING_PROFILES_ACTIVE`.** The default profile is `PQA` so that "Run As >
Spring Boot App" in STS talks to the PQA MMI with no setup. A pod that forgets the variable would therefore call
the PQA MMI, wrong in PRD and in FQA: set `PRD`, `FQA`, `PQA` or `PQA-LITE` explicitly in every deployment. Two
guards back each stub (MMI and member information): it refuses to start with any explicitly active profile other than
`DEV` or `test`, and it refuses to start inside a Kubernetes/OpenShift pod at all (it checks `KUBERNETES_SERVICE_HOST`).

#### The member information service (line of business)

Once MMI has identified the member (`ACTIVE` or `INACTIVE`), the service asks the member information service for that
member's coverage records: `POST {member-info.base-url}/members` with
`{ "memberIds": ["<the resolved id, as stored>"], "dos": "MM/dd/yyyy", "returnCoverageList": true }`
(`returnCoverageList` is always sent, always `true`). Each member entry in the answer carries `coverageRecords`, about
the last five years of coverage, each record with the same fields as the entry's `memberPlan`. `memberPlan` is not read:
it is not the plan on the date of service. Only the records whose `planStartDate` and `planEndDate` include the date of
service (the first day of a period; both ends inclusive, dates read as written, no end date means open-ended; void
records left out) go through the rules. The rules, in `LineOfBusinessDeriver`, are the member portal's "ES Members"
criteria, read against the record (`sourceSysId` is the record's `sourceSystemId`, `coverage.subsidiary` its
`subsidiary`, `coverage.product` its `productCode`; case and spaces ignored):

| `lineOfBusiness` | Coverage record covering the date of service |
|---|---|
| `D-SNP` | `sourceSystemId` 2064 and `productCode` DMA |
| `MA-TOGETHER` | `subsidiary` THPPMA, `sourceSystemId` 2026, `productCode` PL or GT |
| `RI-TOGETHER` | `subsidiary` THPPRI and `sourceSystemId` 2048 |
| `MA-QHP-DIRECT` | `subsidiary` THPPMA, `sourceSystemId` 2026, `productCode` NS or SB |
| MMI's value (`MCR`, `PP`, `COM`, ...) | no record covers the date, or none a rule matches (TMP, SCO, HPHC and the populations whose rules are to come); logged with `marker=LOB_NOT_DERIVED` |

A derived value is logged as `lob derived` with the record's fields and dates. When several records cover the date,
the first one a rule matches gives the value, and `marker=LOB_SEVERAL_ON_DATE` is logged if they disagree. TMP members
will also need a database lookup, to be added beside the rules.

| Profile | Member information service |
|---|---|
| `DEV` | in-process stub, `member-info-stub/members.json` (the Together subscriber's records: `MA-QHP-DIRECT` 2022 to 2024, `MA-TOGETHER` from 2025; any other member has none) |
| `FQA` | `http://memberinfoserviceapp-spring-boot-fqa.apps.tdqocp.thp.tahphq.tahp` (assumed from the PQA URL: confirm) |
| `PQA` | `http://memberinfoserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp` |
| `PQA-LITE` | `http://memberinfoserviceapp-spring-boot-pqa-lite.apps.tdqocp.thp.tahphq.tahp` (assumed: confirm) |
| `PRD` | `http://memberinfoserviceapp-spring-boot-prod.apps.prodocp.thp.tahphq.tahp` (assumed: confirm) |

A 404 is taken as "no records" (`marker=MEMBER_PLAN_404`). Any other failure fails the answer: 503
`MEMBER_PLAN_UNAVAILABLE` (cannot reach, timeout, 5xx, 429, 408; `Retry-After: 10`) or 502 `MEMBER_PLAN_ERROR` (any
other status) / `MEMBER_PLAN_INVALID_RESPONSE` (an empty or unreadable body), with MMI's request id as `traceId`.
With `member-info.log-payloads: true` (on in DEV, FQA, PQA and PQA-LITE) the exact request and response bodies are
logged (`member-info request` / `member-info response`); the startup banner shows `member-info client`,
`member-info url` and `member-info log`. Overrides: `MEMBER_INFO_BASE_URL`, `MEMBER_INFO_CONNECT_TIMEOUT=2s`,
`MEMBER_INFO_READ_TIMEOUT=5s`, `MEMBER_INFO_LOG_PAYLOADS=true|false`.

## 2. The API

One operation, `POST` with a JSON body (the member id is PHI and must not appear in a URL), a pure read that may be
repeated. Every call identifies the caller and itself: `clientId`, `clientType` (`EXT` or `INT`) and `requestId` are
required, and the `requestId` comes back in every answer, 200 or error. Nothing a caller receives names MMI: the
bodies speak of "the member lookup", the trace id of that lookup is `traceId`, and what the lookup said on a miss is
`sourceMessage`. The answer carries the member id in every configured vendor's format, so one call serves a prior
authorization whose codes go to several vendors.

### Member resolution: `POST /v1/interop/resolve`

```http
POST /v1/interop/resolve
Content-Type: application/json
X-Correlation-Id: ONYX-PA-2026-000123        (optional; echoed in the response header, generated when absent)

{ "clientId": "ONYX", "clientType": "EXT", "requestId": "3f6c2a9e-8b1d-4e7a-9c5f-2d4b6a8e0c13",
  "memberId": "123456789", "dateOfService": "2026-10-15", "dateOfBirth": "1950-03-15" }
```

| Field | Required | Notes |
|---|---|---|
| `clientId` | yes | The calling system as registered with the plan; Onyx sends `ONYX`. Letters, digits, `.`, `_` or `-`, at most 50 characters (`CLIENT_ID_MISSING`, `CLIENT_ID_INVALID`). Logged with every request. |
| `clientType` | yes | `EXT` for a caller outside the plan (Onyx), `INT` for an internal one; exactly these values (`CLIENT_TYPE_MISSING`, `CLIENT_TYPE_INVALID`). |
| `requestId` | yes | The caller's id for this call, new for every request (a UUID suits). Letters, digits, `.`, `_`, `:` or `-`, at most 64 characters (`REQUEST_ID_MISSING`, `REQUEST_ID_INVALID`). Echoed in the 200 body and in every error body once it was read and found usable; on every log line of the call. |
| `memberId` | yes | As the EMR typed it. Only checked for presence; sent to MMI exactly as received, with surrounding whitespace removed. Not validated or reshaped here. |
| `dateOfService` | no | `yyyy-MM-dd`; the first day when the service covers a period. **Defaults to today** when omitted (`dateOfServiceDefaulted: true` in the response). A date that is sent is the date judged, never replaced by today: any future date is judged against the coverage on record; a value that is not a real date, or more than 10 years back, is a 400. |
| `dateOfServiceEnd` | no | `yyyy-MM-dd`, the last day when the service covers a period: coverage must then hold on every day from `dateOfService` to it. Not before `dateOfService` (`DATE_OF_SERVICE_END_BEFORE_START`), never alone (`DATE_OF_SERVICE_END_WITHOUT_START`), a real date (`DATE_OF_SERVICE_END_INVALID`). |
| `dateOfBirth` | recommended | The patient's date of birth, `yyyy-MM-dd`. Used only to verify the member or tell apart the members on a plan who share the id; a date that matches no record is 422 `DOB_MISMATCH`, and a value that is not a real date, is in the future or is more than 125 years ago is a 400. Never sent to MMI, never logged, never echoed. |
| anything else | no | **Ignored**, a `vendor` included: the answer is always for every vendor. A field sent as the wrong JSON type (an object or array where a string is expected) is a body the service cannot read: 400 `WRONG_JSON_TYPE`. |

The service does not validate or reshape the incoming member id. Whatever the EMR typed (9, 11 or 14
characters, hyphens, spaces, letters, any length) goes to MMI as is, with only surrounding whitespace removed,
and MMI decides whether it knows the id. A missing or blank `memberId` is the only member-id error this service raises itself
(400 `INVALID_REQUEST`, detail code `MEMBER_ID_MISSING`); when MMI cannot process the id it is sent, MMI's 400 comes
back as 400 `MEMBER_LOOKUP_REJECTED` with MMI's text.

Besides the caller fields, only the member id is required. The operation is not lenient about a date that was sent:
a date of service or date of birth that cannot be used is a 400, because a request that asks about 2027 must never be
answered for today. So the 400s this service raises itself are a missing or unusable caller field
(`CLIENT_ID_MISSING`, `CLIENT_ID_INVALID`, `CLIENT_TYPE_MISSING`, `CLIENT_TYPE_INVALID`, `REQUEST_ID_MISSING`,
`REQUEST_ID_INVALID`), a missing or blank `memberId` (`MEMBER_ID_MISSING`), an unusable date
(`DATE_OF_SERVICE_INVALID`, `DATE_OF_SERVICE_OUT_OF_RANGE`, the three `DATE_OF_SERVICE_END_` codes,
`DATE_OF_BIRTH_INVALID`, `DATE_OF_BIRTH_OUT_OF_RANGE`) and a body it cannot read as this request (`MALFORMED_JSON`,
`WRONG_JSON_TYPE`). Every problem is listed together, and none of them reaches MMI. So

```json
{ "memberId": "T20262026", "dateOfService": "2027-10-15", "vendor": "EVICORE", "anything": "goes" }
```

with the caller fields added answers 200 `INACTIVE` for 2027-10-15 (this stub member's only record ends 12/31/2026; the vendor and the unknown
property are ignored), and the same payload with `"dateOfService": "10/15/2027"` is 400 `DATE_OF_SERVICE_INVALID`.

### Response (HTTP 200), branch on `outcome`

| `outcome` | Meaning | Onyx action |
|---|---|---|
| `ACTIVE` | Member verified, `coverage.active = true` on the date of service | Send each vendor its `memberId.forVendors` entry: it is already in that vendor's format (for Optum, the 9-character core only). The entry's `payerId` and `payerName` go on the same request. Keep `coverage.coverageId` with the transaction if the coverage period must be referred to later. |
| `INACTIVE` | Member verified, `coverage.active = false`; `message` says why (coverage ended, not yet effective, a gap, or no coverage on record) | Hold for intake. `memberId.forVendors` is still there if the business rule says to submit anyway. |
| `NOT_FOUND` | MMI has no member for this id. MMI answers that with HTTP 404; it is a normal answer, so this service answers 200 with `message` "No member found for this id" and, when MMI sent a message, `sourceMessage` with MMI's own type / status / code / text | "Member not found" worklist. |
| `AMBIGUOUS` | Several members on the plan share the ID (a 9-character ID of a population with dependents) and no DOB settled it. Nobody is listed: the others on the plan may not be the patient | Resend with `dateOfBirth` or the member's full ID including the suffix; if it is still `AMBIGUOUS` (twins share a date of birth, or the records carry none), intake takes the full ID from the member's card. |

For the TMP id `123456789` (resolved to `123456789   01`):

```json
{ "outcome": "ACTIVE",
  "message": "Member found; coverage active on 2026-10-15",
  "memberId": { "received": "123456789", "resolved": "123456789   01",
    "forVendors": [
      { "vendor": "CARELON", "memberId": "12345678901", "payerId": "Point32Health", "payerName": "Point32Health" },
      { "vendor": "EVICORE", "memberId": "12345678901", "payerId": "TUFTS", "payerName": "TUFTS" },
      { "vendor": "EVOLENT", "memberId": "12345678901", "payerId": "Point32Health", "payerName": "Point32Health" },
      { "vendor": "MHK",     "memberId": "123456789   01", "payerId": "Point32Health", "payerName": "Point32Health" },
      { "vendor": "ONYX",    "memberId": "12345678901", "payerId": "Point32Health", "payerName": "Point32Health" },
      { "vendor": "OPTUM",   "memberId": "123456789", "payerId": "Point32Health", "payerName": "Point32Health" }
    ] },
  "lineOfBusiness": "MCR",
  "dateOfService": "2026-10-15", "dateOfServiceDefaulted": false,
  "coverage": { "coverageId": "123456789012021010199991231", "active": true, "effectiveDate": "2021-01-01", "endDate": "9999-12-31" },
  "requestId": "3f6c2a9e-8b1d-4e7a-9c5f-2d4b6a8e0c13",
  "traceId": "INTEROP-1791253983086-71493" }
```

`memberId.received` is the ID as Onyx sent it (surrounding whitespace removed): exactly what was sent to MMI.
`memberId.resolved` is the ID it resolved to, exactly as MMI holds it for the member the lookup settled on; for a
converted member it is a different number from the one received. `memberId.forVendors` carries one entry per configured
vendor, sorted by vendor code: that ID in the vendor's format, the value that goes into that vendor's payload. For Optum it is the
9-character core only, the number printed on the card: Optum stores the core, not the 11 characters.
Vendor formatting applies only to a stored ID of the TMP/SCO shape (9 characters, spaces, 2 digits); any other
stored ID (Public Plans, HPHC) is passed as stored for every vendor.
`coverage` is flat, in this order: `coverageId`, `active`, `effectiveDate`, `endDate`. The dates
are those of the continuous period that covers the (first) date of service; an open-ended period ends `9999-12-31`, so
every date sent is a real date and absent dates mean there is no period. Records that touch,
as plan-year records do (one ends 12/31, the next starts 01/01), are one continuous period. `active` is true only when
that period covers every day asked about; when it covers the first day but ends before the last, `active` is false and
the period is still shown so intake sees how far coverage goes. Otherwise there is no period and `message` says why.
`coverage.coverageId` names that period, in the form the Onyx requirement sets: the resolved member
id with its spaces and any punctuation removed, then the period's effective date, then its end date, both `yyyyMMdd`, run
together with no separator (`<MEMBER_ID><yyyyMMdd><yyyyMMdd>`, letters and digits only; `99991231` stands for an open-ended
period). It is present whenever the dates are, and the same member with the same coverage always gets the same
value, so Onyx can refer to the coverage behind a decision. Nothing else is returned about coverage: no reason code, no neighbouring dates.
`lineOfBusiness` is derived from the member's coverage record on the date of service (section 1): `D-SNP`,
`MA-TOGETHER`, `RI-TOGETHER` or `MA-QHP-DIRECT`; a member no rule covers yet keeps MMI's value (`MCR`, `PP`, `COM`, ...).
Onyx routes the transaction on it. The company (THP or
HPHC) is not returned: the resolved id tells it apart (`HP` prefix) and Onyx does not act on it. The response is kept
to what Onyx acts on; the correlation id is in the `X-Correlation-Id` response header, not in a 200 body, and the caller's `requestId`
comes back as `requestId`. Each `memberId.forVendors` entry also carries `payerId` and `payerName`, the payer Onyx
puts on that vendor's request: the vendor's own for the member's company when configured (section 4), else the
default (`payer` block in `application.yaml`; `PAYER_ID` / `PAYER_NAME` override). Today eviCore gets its own (`TUFTS`
for THP, `HPHC` for HPHC) and every other vendor gets `Point32Health`.

Response fields, in JSON order (absent blocks are omitted, not sent as `null`):

| Field | Present | Content |
|---|---|---|
| `outcome` | always | `ACTIVE`, `INACTIVE`, `NOT_FOUND`, `AMBIGUOUS` |
| `message` | always | One sentence for the outcome: `Member found; coverage active on <dateOfService>` (for a period: `Member found; coverage active from <dateOfService> to <dateOfServiceEnd>`) / `Member found; coverage active on <dateOfService> but ends <end of the coverage period>, before <dateOfServiceEnd>` / `Member found; coverage ended before <dateOfService>` / `Member found; coverage not yet effective on <dateOfService>` / `Member found; no coverage on <dateOfService> (gap between coverage periods)` / `Member found; no coverage on record` / `No member found for this id` / `Several members match this id; resend with dateOfBirth or the member's full id including the suffix` (or, when a DOB was sent and several share it, `Several members match this id and date of birth; resend with the member's full id including the suffix`; when a DOB was sent that no record carries, `Several members match this id and their records carry no date of birth to check; resend with the member's full id including the suffix`) |
| `memberId` | always | `received`; `resolved` and `forVendors[]` (`{ vendor, memberId, payerId, payerName }` per configured vendor, sorted by vendor code) when a member was identified |
| `lineOfBusiness` | `ACTIVE`, `INACTIVE` | From the member's coverage record on the date of service: `D-SNP`, `MA-TOGETHER`, `RI-TOGETHER`, `MA-QHP-DIRECT`; otherwise MMI's line of business (`MCR`, `PP`, `COM`, ...). Onyx routes the transaction on it |
| `dateOfService` | always | The (first) date evaluated |
| `dateOfServiceEnd` | when a period was asked about | The last date evaluated, as sent |
| `dateOfServiceDefaulted` | always | Whether the date of service was defaulted to today |
| `coverage` | `ACTIVE`, `INACTIVE` | From the resolved record and the coverage decision: `coverage { coverageId?, active, effectiveDate?, endDate? }`; the id and the two dates come together, whenever a period covers the (first) date of service |
| `requestId` | always | The caller's `requestId`, echoed |
| `traceId` | always | For the logs on both sides (the correlation id is in the response header) |
| `sourceMessage` | `NOT_FOUND`, only when MMI sent a message | `{ "type", "status", "code", "text" }`: the first entry of MMI's `messages[]`, as MMI sent it |

`memberId.forVendors` is present **only when a member was identified**, that is for `ACTIVE` and `INACTIVE`.
`NOT_FOUND` and `AMBIGUOUS` answers carry no `memberId.resolved` and no `memberId.forVendors`; an `AMBIGUOUS` answer
lists nobody; a resend with `dateOfBirth` usually settles it, and the full ID always does.
For an HPHC id (resolved to `HP456789012`) or a Public Plans id (`34567890102`) every entry carries the resolved id
unchanged, Optum included. Absent blocks are omitted, not sent as `null`.

A `NOT_FOUND` answer from the DEV stub (the real MMI's values will differ; the stub answers HTTP 404, as MMI's
contract describes, with an `ERROR`-typed `MEMBER_NOT_FOUND` message of its own; whether the real 404 carries such a
message is confirmed in PQA (section 7)):

```json
{ "outcome": "NOT_FOUND",
  "message": "No member found for this id",
  "memberId": { "received": "HP111222333" },
  "dateOfService": "2026-10-15", "dateOfServiceDefaulted": false,
  "requestId": "3f6c2a9e-8b1d-4e7a-9c5f-2d4b6a8e0c13",
  "traceId": "INTEROP-1791253983094-30664",
  "sourceMessage": { "type": "ERROR", "status": "404", "code": "MEMBER_NOT_FOUND", "text": "No member found for the given id (stub)" } }
```

### Errors (non-200): `{ "error": { "code", "message", "details": [ { "field", "code", "message" } ] }, "correlationId", "requestId", "traceId" }`

Every answer the application produces has this shape. `requestId` echoes the caller's id when it was read and
usable, on a 400 about another field too; `traceId` is present once MMI was called, including on a 422. The one exception is a request Tomcat rejects before it
reaches the application (malformed percent-encoding in the URL, a header block over 8 KB): that returns Spring
Boot's default error JSON.

MMI's contract has four answers: 200 success, 404 member not found, 400 bad request, 500 internal error. This
service maps them one to one; anything else is not MMI speaking. Only an unknown route of this service is a 404
from this service (`ROUTE_NOT_FOUND`). Nothing in this mapping is configurable.

| MMI | This service |
|---|---|
| 200 | `outcome` from the records: `ACTIVE`, `INACTIVE` or `AMBIGUOUS`. No members -> 200 `NOT_FOUND`, unless `messages[]` carries an `ERROR`-typed message (`mmi.error-message-types`): then 502 `MEMBER_LOOKUP_ERROR` / `ERROR_MESSAGE`, forwarding MMI's code and text (`The member lookup reported an error: ES_TIMEOUT ...`) |
| 404 (member not found) | 200 `NOT_FOUND`, whatever the body; `sourceMessage` carries MMI's first message when there is one. MMI's envelope is a JSON object with any of `members` / `messages` / `clientId` / `requestId`; an empty body counts as one. A 404 with any other body (a container's default error JSON, an HTML page) is still `NOT_FOUND`, and the log gets a WARN with `marker=MMI_404_WITHOUT_ENVELOPE` naming the URL, because a wrong `mmi.base-url` / `mmi.path` looks exactly like that |
| 400 (bad request) | 400 `MEMBER_LOOKUP_REJECTED`, `details[0].code` `HTTP_400`, message `The member lookup rejected the request` + `: <MMI code> <MMI text>` when MMI sent a message. No `Retry-After`; `traceId` present |
| 500 (internal error) | 503 `MEMBER_LOOKUP_UNAVAILABLE`, `details[0].code` `HTTP_500`, message `The member lookup reported an internal error` + `: <code> <text>` when present; `Retry-After: 10` |
| any other status | A gateway, proxy or container answered, not MMI. 5xx, 429, 408 -> 503 `MEMBER_LOOKUP_UNAVAILABLE` `HTTP_<code>`; other 4xx -> 502 `MEMBER_LOOKUP_ERROR` `HTTP_<code>`. The message reads `HTTP <code> from the member lookup, outside its contract (200, 400, 404, 500): a gateway or proxy answered` (the URL goes to the log only) |
| cannot reach / timeout | 503 `MEMBER_LOOKUP_UNAVAILABLE`, `CONNECT_FAILED` or `READ_TIMEOUT`; `Retry-After: 10` |
| unreadable body | 502 `MEMBER_LOOKUP_INVALID_RESPONSE`: a 2xx with an empty or non-JSON body (`EMPTY_BODY`, `UNPARSEABLE_BODY`), records without a member id (`NO_MEMBER_ID`), or a member whose only coverage spans have unreadable dates (`UNREADABLE_COVERAGE`) |

| HTTP | `error.code` | When | Onyx action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | Missing or unusable caller fields (`CLIENT_ID_MISSING`, `CLIENT_ID_INVALID`, `CLIENT_TYPE_MISSING`, `CLIENT_TYPE_INVALID`, `REQUEST_ID_MISSING`, `REQUEST_ID_INVALID`), a missing or blank member ID (`MEMBER_ID_MISSING`), bad dates (`DATE_OF_SERVICE_INVALID`, `DATE_OF_SERVICE_OUT_OF_RANGE` for a date more than 10 years back; there is no upper limit, a future date is judged on the coverage on record; `DATE_OF_SERVICE_END_INVALID`, `DATE_OF_SERVICE_END_BEFORE_START`, `DATE_OF_SERVICE_END_WITHOUT_START` for the end of a period of service; `DATE_OF_BIRTH_INVALID`, `DATE_OF_BIRTH_OUT_OF_RANGE`), or a body the service cannot read (`MALFORMED_JSON`, or `WRONG_JSON_TYPE` for the body or any field); every problem is listed in `details[]`. Unknown properties, a vendor included, are ignored, not rejected. (404 / 405 / 406 / 415 use the same envelope for a wrong route, method, representation or content type; the 404 detail `ROUTE_NOT_FOUND` says "the operation is POST /v1/interop/resolve".) | Never retry. `MEMBER_ID_MISSING` and `DATE_OF_SERVICE_OUT_OF_RANGE` are provider data problems, the caller codes are Onyx configuration, anything else is an Onyx mapping defect. |
| 400 | `MEMBER_LOOKUP_REJECTED` | MMI answered 400: it could not process the request as sent (`details[0].code` `HTTP_400`; the message forwards MMI's code and text when MMI sent a message). `traceId` present, no `Retry-After`. | Never retry as is. Alert the service owners with the `traceId`; MMI's text says what it did not accept. |
| 422 | `DOB_MISMATCH` | a DOB was sent and matches no record for this ID | Manual identity review; never file the auth. |
| 502 | `MEMBER_LOOKUP_ERROR`, `MEMBER_LOOKUP_INVALID_RESPONSE` | MMI reported an error of its own in `messages[]` with no members (`ERROR_MESSAGE`; the message forwards MMI's code and text: `The member lookup reported an error: ES_TIMEOUT ...`); a 4xx other than 400 and 404 from the MMI endpoint, which is a gateway or proxy answering, not MMI (`HTTP_<code>`; the message says so and repeats the URL); an unreadable 2xx body (`EMPTY_BODY`, `UNPARSEABLE_BODY`); records without a member id (`NO_MEMBER_ID`); or the member's only coverage spans have unreadable dates (`UNREADABLE_COVERAGE`: the service refuses to say INACTIVE on data it cannot read). | Park, alert the service owners. |
| 503 | `MEMBER_LOOKUP_UNAVAILABLE` (`Retry-After: 10`) | MMI unreachable (`CONNECT_FAILED`) or timed out (`READ_TIMEOUT`); MMI 500 (`HTTP_500`; the message forwards MMI's code and text when present); a 5xx, 429 or 408 from a gateway in front of MMI (`HTTP_<code>`). | Retry later. |
| 502 | `MEMBER_PLAN_ERROR`, `MEMBER_PLAN_INVALID_RESPONSE` | The member information service answered a status other than 200, 404, 408, 429 and 5xx (`HTTP_<code>`), or an empty or unreadable body (`EMPTY_BODY`, `UNPARSEABLE_BODY`). `traceId` present. | Park, alert the service owners. |
| 503 | `MEMBER_PLAN_UNAVAILABLE` (`Retry-After: 10`) | The member information service unreachable (`CONNECT_FAILED`), timed out (`READ_TIMEOUT`) or answered 5xx, 429 or 408 (`HTTP_<code>`). `traceId` present. | Retry later. |
| 500 | `INTERNAL_ERROR` | a bug here | Retry once later, alert the service owners. |

**If this service itself is unreachable**, Onyx's agreed fallback is to pass the member ID exactly as it
received it from the EMR to the UM vendor. Nothing here needs to be built for that; it is an Onyx rule.

## 3. How an answer is produced

1. Take the ID as received: surrounding whitespace removed, nothing else. No separator stripping, no
   upper-casing, no shape or length check; the member id is sent to MMI exactly as the EMR typed it.
2. One MMI call: `POST {mmi.base-url}/master/member/v1` with that ID in `memberId` **and**
   `legacyMemberId` (the MMI spec's hit-rate advice), `voidCoverageRecord: N`, this service's own `clientId` and
   `clientType: INT`, a fresh MMI `requestId` (`INTEROP-<millis>-<5 digits>`, returned as `traceId`; not the caller's
   `requestId`), and the date of service (the first day of a period) in `dosStartDate` as `MM/dd/yyyy`;
   no `dosEndDate`. The coverage MMI returns is evaluated locally. No demographics are ever sent.
   MMI's HTTP status is kept with the answer and mapped as in the table in section 2 (Errors): 200 is parsed;
   404 is MMI's "no member for this id", a normal answer (`NOT_FOUND`) whatever the body, and a body that is not
   MMI's envelope only adds `marker=MMI_404_WITHOUT_ENVELOPE` to the log; 400 is 400 `MEMBER_LOOKUP_REJECTED` with MMI's
   text; 500 is 503 `MEMBER_LOOKUP_UNAVAILABLE`; any other status is a gateway or proxy, not MMI: 503 for 5xx, 429 and 408,
   502 `HTTP_<code>` for the rest. Nothing in this mapping is configurable. A 200 with no members is `NOT_FOUND`
   too, unless `messages[]` carries an `ERROR`-typed message (`mmi.error-message-types`, for example `ES_TIMEOUT`):
   then 502 `MEMBER_LOOKUP_ERROR` / `ERROR_MESSAGE`, forwarding MMI's code and text. A `NOT_FOUND` answer carries MMI's first
   message in `sourceMessage` when there is one. Members beside a 404 are ignored (the status wins) and `marker=MMI_NOT_FOUND_WITH_MEMBERS` is logged.
3. Reduce the records: identical records merged; records linked through `legacyMemberId` (THP<->HPHC
   conversion) are one person and the record covering the date of service wins; a supplied DOB picks one
   person or proves a mismatch; several persons without a DOB -> `AMBIGUOUS`.
4. Coverage: the readable, non-void spans that overlap or touch are joined into continuous periods; `active` when one
   period covers every day from the first to the last date of service (inclusive; an end of null or `12/31/9999` is
   open). The dates are always the ones that were sent (today only when none was sent), whatever year they are in:
   a 2027 date against a record that ends 12/31/2026 is `INACTIVE` ("coverage ended before"), a period from
   2025-12-20 to 2026-01-05 against records for 2025 and 2026 is `ACTIVE` (one continuous period), a period that
   runs past the end of coverage is `INACTIVE` ("coverage active on ... but ends ..., before ..."), and any date
   against a record with no end date is `ACTIVE`, because that is what the record says. A span with an unreadable date is skipped and logged with `marker=UNREADABLE_SPAN`; the
   remaining spans decide. If a member has unreadable spans and no readable one, the answer is 502
   `UNREADABLE_COVERAGE`, never a confident INACTIVE. For a converted member the two records' spans are
   evaluated together, so a gap between the old and the new record is reported as a gap.
5. For an identified member (`ACTIVE` or `INACTIVE`), one member information call with the resolved ID and the date
   of service; the line of business is derived from the coverage record that covers the date of service (section 1;
   MMI's value when none does or no rule matches).
   Its failure fails the answer (503 / 502).
6. Format the resolved ID for every configured vendor, in vendor-code order (never from the input).

Before step 1 the caller fields are checked, the request type ignores what the operation does not need (a `vendor`,
unknown properties), and a date that was sent is validated, because the date of service is the date the answer is about.

TMP / SCO members have no dependents: a 9-character card number returns exactly one record and resolves
directly. Only populations with dependents (HPHC commercial, Together) can produce `AMBIGUOUS`.

## 4. Vendor formats: the only thing to edit when a vendor changes

`src/main/resources/application.yaml`, block `member-id.vendors` (edit it there; the block below is a copy of
the shipped values). Change a value, redeploy; the startup log prints the effective table rendered against
a sample ID. Adding a vendor to this block also adds its entry to `memberId.forVendors` automatically: the answer
is rendered from this table and nothing else needs editing.

```yaml
member-id:
  vendors:
    EVICORE:
      display-name: eviCore
      format: COMPACT_11                # 12345678901
      payer:                            # eviCore keys the payer on the heritage company
        THP:  { id: TUFTS, name: TUFTS }
        HPHC: { id: HPHC,  name: HPHC }
    MHK:
      display-name: MHK (MedHOK)
      format: SPACED_14                 # 123456789   01
    EVOLENT:
      display-name: Evolent
      format: COMPACT_11
    CARELON:
      display-name: Carelon
      format: COMPACT_11
    OPTUM:
      display-name: Optum
      format: CORE_9                    # the 9-character core only: Optum stores the number on the card
    ONYX:
      display-name: Onyx
      format: COMPACT_11
```

Formats: `COMPACT_11`, `SPACED_14`, `CORE_9`, `AS_STORED`. The TMP core is **9 characters, a letter and 8 digits** (`S12345678`), stored with three spaces and the suffix `01`: `S12345678   01`. A format applies when the stored id is a run of letters and digits, a separator (any blanks or punctuation) and a short numeric suffix; the core and the suffix are copied character for character, so the `S` is always kept (`S1234567801`, `S12345678   01`; Optum receives the core alone, `S12345678`). A stored id of any other shape is passed on unchanged and, when it is not plain letters and digits, the log says so (`marker=STORED_ID_NOT_RESHAPED` with the value, every character that is not a letter or digit written as its code point). A new output shape = one constant in
`VendorIdFormat` + one case in `VendorFormatter` + one test row.

The payer per vendor lives in the same table. A vendor that keys the payer on the heritage company gets a `payer`
block with an entry per company (`THP`, `HPHC`, as the member lookup reports it), each with an `id` and a `name`; a
member of any other company, and every vendor without the block, gets the default payer (`payer.id` / `payer.name`,
today `Point32Health`). eviCore has one: `TUFTS` for THP and `HPHC` for HPHC, as both id and name. The startup log
prints each vendor's payer next to its format; an entry without an id or a name stops the application with a message
naming it. If the member lookup reports a company that such a vendor has no entry for, the default payer goes out and
the log gets `marker=VENDOR_PAYER_DEFAULTED` with the vendor and the company.

## 5. Stub fixtures (DEV profile: `--spring.profiles.active=DEV`) and Postman

`src/main/resources/mmi-stub/members.json` behaves like MMI: exact match, 9-character (policy) match returning
the family, legacy-id match and the second pass through `legacyMemberId`. The stub matches ignoring separators
and case (anything that is not a letter or digit is ignored), the leniency expected of the real MMI; the service
itself hands the id over untouched. The stub ignores `dosStartDate` and returns each record's whole coverage
history. `src/main/resources/member-info-stub/members.json` is the member information stub: the Together subscriber's coverage
records (THPPMA, source system 2026; product SB from 2022 to 2024, product GT from 2025 with no end), so its answers carry
`MA-TOGETHER` for a 2026 date and `MA-QHP-DIRECT` for a 2024 one; any other member has no records and keeps MMI's line of
business. The fault ids below are recognised by the first 9 characters of the id with
separators removed.

| Member ID (as typed; the stub ignores separators and case) | Case |
|---|---|
| `123456789` / `12345678901` / `123456789   01` | TMP, active, open-ended |
| `234567890` | SCO, coverage ended 2025-12-31 -> INACTIVE |
| `345678901` | Together family: 01 subscriber (DOB 1985-06-01), 02 dependent (DOB 2012-09-09, gap 06/2024 to 12/2024), 03 twin (ended 2024-12-31) -> AMBIGUOUS without DOB |
| `HP456789012` / `HP-456789012` | HPHC, active (end `12/31/9999`) |
| `567890123 01` <-> `HP567890123` | converted member: THP to 2024-12-31, HPHC from 2025-01-01 |
| `678901234` | void span + ended span -> INACTIVE |
| `789012345` | coverage starts 2027-01-01 -> NOT_YET_EFFECTIVE |
| `890123456` | one unreadable span + one valid -> ACTIVE with a warning |
| `880000000` | only an unreadable span -> 502 `UNREADABLE_COVERAGE` |
| `901234567` | company missing on the record (inferred), restrictedData Y |
| `500500500`, `503503503`, `400400400`, `888888888`, `202202202` | faults: MMI 500 -> 503 `MEMBER_LOOKUP_UNAVAILABLE` `HTTP_500` (message `The member lookup reported an internal error: INTERNAL_ERROR search failed (stub)`), timeout -> 503 `READ_TIMEOUT`, MMI 400 -> 400 `MEMBER_LOOKUP_REJECTED` `HTTP_400` (message `The member lookup rejected the request: INVALID_REQUEST memberId could not be processed (stub)`), an `ERROR` message `ES_TIMEOUT` with no members -> 502 `ERROR_MESSAGE` (the message carries `code=ES_TIMEOUT text=search backend timed out (stub)`), bad body -> 502 `UNPARSEABLE_BODY` |
| `887777777`, `886666666` | an error message beside a member record -> 200 with a warning; a record without a member id -> 502 `NO_MEMBER_ID` |
| anything else (for example `HP111222333`) | NOT_FOUND: the stub answers like the real MMI, HTTP 404 with `messages: [ { messageType ERROR, statusCode "404", messageCode MEMBER_NOT_FOUND, message "No member found for the given id (stub)" } ]` and no members; the service answers 200 `NOT_FOUND` with that message in `sourceMessage`. The payload log line for the stub prints `status=404` |

Postman: import `postman/InteropResolution.postman_collection.json` and
`postman/Local.postman_environment.json` (`baseUrl = http://localhost:9090`). The collection sends `clientId` and
`clientType` from its variables (`ONYX`, `EXT`) and a fresh `requestId` for every call from its pre-request script; a
collection-level test checks that every 200 echoes it. Every request carries tests; run the whole collection with the
Collection Runner for a green scenario pass (77 requests). Every folder but 6 exercises `POST /v1/interop/resolve`
(folder 6 is health and the OpenAPI document): folders 1 to 3 the populations and vendor formats, folder 4 not found
and request validation, the caller fields included, folder 5 the member lookup failures, folder **7** every vendor's
id from one lookup, the leniency (a `vendor` and unknown properties ignored, a missing member id answered 400
`INVALID_REQUEST`, an unusable date a 400) and periods of service. The DEV
stub's "today" is the real date, so those tests assert `dateOfServiceDefaulted` is true rather than a specific date.

## 6. Tests

`./gradlew test` (161 tests): request validation, coverage rules, selection rules (including converted members in
a gap and with overlapping records), vendor formats, MMI mapping, the REST client against a mock server
(`notFoundWithAnMmiEnvelopeIsANormalAnswer`, `notFoundWithAnEmptyBodyIsANormalAnswer`,
`notFoundWithoutAnMmiEnvelopeIsStillNotFoundButWarns`, `badRequestIsForwardedAs400WithMmiText`,
`badRequestWithoutABodyIsStillForwardedAs400`, `internalErrorIsUnavailable503WithMmiText`,
`statusOutsideMmiContractSaysSo`, plus 429, 408, read timeout, connection refused and unparseable body),
configuration validation, and the end-to-end scenario matrix over HTTP against the stub with "today" fixed at
2026-10-03. The scenarios assert `message` on `ACTIVE`, `message` and `sourceMessage` on `NOT_FOUND`,
that `400400400` is 400 `MEMBER_LOOKUP_REJECTED` / `HTTP_400` with MMI's text and no `Retry-After`, and that
the 502 `ERROR_MESSAGE` body carries MMI's `code=ES_TIMEOUT` and `text=`. Four of the scenarios cover
the vendor ids: the TMP id rendered for every vendor from one MMI call (Optum as the 9-character core, `memberId` in the order `received`,
`resolved`, `forVendors`, no `vendor` in the response); HPHC and Public Plans ids passed as stored to every vendor, with the defaulted
date and an INACTIVE gap; NOT_FOUND and AMBIGUOUS answers without `memberId.forVendors`, and a DOB that settles the
ambiguity; and `resolutionIsLenientAboutEverythingExceptTheMemberId`, which proves that a blank member id is a
request-validation 400 (`INVALID_REQUEST`, `MEMBER_ID_MISSING`) that never reaches MMI, that a `vendor` in the request is ignored (200),
that unknown properties are ignored, and that an unusable date of service or date of birth is a 400; a real but wrong
DOB is still 422 `DOB_MISMATCH` after the one MMI call (`traceId` present). `aFutureDateOfServiceIsJudgedOnTheCoverageOnRecord` and
`aSentDateOfServiceIsNeverReplacedByToday` cover a calendar-year record: a member whose only record runs 01/01/2026 to 12/31/2026 is
ACTIVE for 2026-10-15 and INACTIVE for 2027-10-15, the response carries the date asked about, an unusable date is a 400
and never "active for today", only a missing date defaults to today, and a far-future date is judged, not refused. `aPeriodOfServiceMustBeCoveredOnEveryDay`: a member with
adjacent 2025 and 2026 records is ACTIVE from 2025-12-20 to 2026-01-05 with the merged period in the coverage dates, INACTIVE
from 2026-12-20 to 2027-01-05 ("coverage active on 2026-12-20 but ends 2026-12-31, before 2027-01-05"), `dateOfServiceEnd`
is echoed only when sent, and an end before the start, without a start or not a date is a 400. A capturing log appender
asserts no log line (message or exception text; payload logging is off in the `test` profile) contains a date
of birth from the stub data or a member name, and no response body contains names or SSN; a stub call counter proves invalid requests
never reach MMI and that every odd-shaped id (10 digits, 40 digits, letters and punctuation) is sent to MMI
and echoed back unchanged in `memberId.received`, and the stub's `lastSearched()` proves the id arrives at MMI
untouched. The payer is checked on every vendor entry of every identified answer in the scenarios (eviCore's `TUFTS` for THP,
`HPHC` for HPHC, the default elsewhere, following the company that owns the date of service for a converted member),
and `ResolutionServicePayerTest` proves, with an id and a name that differ, that both land in their own fields and that
a company a vendor has no entry for gets the default and one `VENDOR_PAYER_DEFAULTED` warning.
`callerMustIdentifyItselfAndTheCall` covers the caller fields: without them the answer is 400 with the
three `_MISSING` codes and MMI is not called; a `clientType` other than `EXT` or `INT` (lower case included), an
over-long or malformed `clientId` or `requestId` is a 400; a usable `requestId` is echoed even on a 400, an unusable
one never; an internal caller (`INT`) gets its answer and is named in the log line.
The member information call: `RestMemberInfoClientTest` (the exact body, `dos` as `MM/dd/yyyy`, the contract example
parsed, `returnCoverageList` always true, 404 as no records, 5xx/429/408 and timeouts as 503, other statuses and
unreadable bodies as 502), `MemberInfoResponseTest` (the coverage records matched to the member ignoring spacing and
case, void records skipped; a record covers its start and end dates inclusive, open when it has no end, nothing when its
start is missing or a date unreadable), `LineOfBusinessDeriverTest` (each rule and its edges; only the record covering the
date of service is read; several records on the date give the first match; no record or no match keeps MMI's value),
`ResolutionServiceMemberPlanTest` (asked with the resolved id, the first day of a period and the correlation id; the
covering record's line of business replaces MMI's; a failure is a 503 with `Retry-After` and MMI's request id as `traceId`) and `theCoverageRecordsAreAskedForOnlyOnceTheMemberIsIdentified` (the Together subscriber gets `MA-TOGETHER` for 2026,
`MA-QHP-DIRECT` for 2024 and `PP` for 2021; asked for `ACTIVE` and `INACTIVE`, never for
`NOT_FOUND` or `AMBIGUOUS`).

## 7. Assumptions to confirm in PQA

- MMI finds the member from the id as typed, including the 9-, 11- and 14-character forms and ids with
  hyphens or spaces (`123456789-01`, `HP-123456789`); the service sends it unchanged in `memberId` and
  `legacyMemberId`. The stub assumes the same.
- The member information service takes every population's id as MMI stores it (its contract shows the THP form, the
  id, spaces and the two-digit suffix; HPHC and Public Plans ids are sent as stored too), needs no authentication,
  returns `coverageRecords` in each member entry when `returnCoverageList` is true, with `planStartDate` /
  `planEndDate` as date-times whose date part is the date meant, and answers 404 when it has no member (its 400 and 500
  are assumed). Its FQA,
  PQA-LITE and PRD URLs follow the PQA naming.
- The line-of-business values (`D-SNP`, `MA-TOGETHER`, `RI-TOGETHER`, `MA-QHP-DIRECT`) are the names Onyx routes on, and
  the ES Members criteria read the coverage record as `sourceSysId` = `sourceSystemId`, `coverage.subsidiary` = `subsidiary`,
  `coverage.product` = `productCode`. Rules for TMP, SCO, HPHC and commercial members, and the TMP database lookup, are
  to come; until then those members keep MMI's value.
- With `dosStartDate`, which coverage MMI returns: only the segment covering that date or the whole history; and for
  a member with no coverage on that date, the member without coverage or a 404 (`NOT_FOUND` here instead of
  `INACTIVE` with its reason). The service evaluates whatever comes back: contiguous segments are still read as one
  period (`coverage` dates and `coverageId`), and a period of service is judged on what comes back for its first day.
- A single record returned for any population is the member (TMP/SCO always return one).
- MMI reports the company as `THP` or `HPHC`, the values eviCore's payer entries key on. Any other value gets eviCore
  the default payer and logs `VENDOR_PAYER_DEFAULTED`: watch for that marker in PQA.
- MMI's error `messageType` is `ERROR` (`mmi.error-message-types`). MMI's contract is 200 / 404 / 400 / 500;
  confirm in PQA that a not-found 404 carries the envelope (`messages[]` with MMI's code and text), so `sourceMessage`
  can be filled: if the log shows `MMI_404_WITHOUT_ENVELOPE` for an id that exists nowhere, it does not.
- `coverage.active` is the flag; the dates are supporting detail. Legacy IDs, migration dates, PCP and
  group names are intentionally not returned.
