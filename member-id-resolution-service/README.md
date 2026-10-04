# Member ID Resolution Service

Spring Boot service for Onyx. Onyx sends the member ID exactly as the provider's EMR supplied it, the
date of service and the UM vendor; the service verifies the ID with MMI (Master Member Index), returns it
**as stored**, returns it **in the vendor's format**, and says whether coverage is **active** on the date
of service. When Onyx does not yet know the vendor, the **vendor map** operation needs only the member id (anything else sent with it is accepted)
and returns it in every vendor's format at once. Two operations, one downstream (MMI), no database, no state.

| | |
|---|---|
| Endpoints | `POST /api/v1/member-ids/resolve` (one vendor) · `POST /api/v1/member-ids/vendor-map` (every vendor), port **9090** |
| Stack | Spring Boot 4.0.7 (Spring Framework 7, Jackson 3) / Java 17+ / Gradle 9.5 wrapper, springdoc 3: the same build shape as the EPA Workbench and the member profile service |
| Swagger UI | `http://localhost:9090/swagger-ui.html` (off in `prod`) |
| Health | `http://localhost:9090/actuator/health` |

## 1. Run it in STS (or any IDE)

### Get the code straight from GitHub (no zip)

Repository `https://github.com/thehiddenbrain/Interop`, branch `claude/member-id-normalization-design-ihju8o`,
folder `member-id-resolution-service`. The repository holds other services too; import only this folder as the
Gradle project.

One-time, a GitHub token (GitHub does not accept the account password for Git any more): GitHub → your avatar →
**Settings → Developer settings → Personal access tokens → Fine-grained tokens → Generate new token**. Resource
owner: the owner of `Interop`; Repository access: *Only select repositories* → `Interop`; Permissions →
Repository permissions → **Contents: Read and write**. Copy the token once; it is the "password" below.

In STS (EGit and Buildship are bundled):

1. **File → Import… → Git → Projects from Git (with smart import) → Next → Clone URI → Next.**
2. URI `https://github.com/thehiddenbrain/Interop.git`; Authentication: User = your GitHub user name,
   Password = the token, tick *Store in Secure Store* → Next.
3. Branch selection: untick everything, tick `claude/member-id-normalization-design-ihju8o` → Next.
4. Local destination: Directory for example `C:\git\Interop`, Initial branch = that branch, Remote name
   `origin` → Next. The clone runs.
5. On the *Import Projects* page tick only `member-id-resolution-service` if it is listed as a Gradle project.
   If only the repository root is offered, **Cancel** here (the clone stays on disk) and use
   **File → Import… → Gradle → Existing Gradle Project**, Project root directory
   `C:\git\Interop\member-id-resolution-service` → Finish. Buildship uses the wrapper (Gradle 9.5.0) and
   downloads the dependencies on the first import.
6. Right-click `MemberIdResolutionApplication` → **Run As → Spring Boot App**. The default profile is `pqa`
   (the real PQA MMI, network needed). For the in-process stub open *Run Configurations… → Spring Boot App*
   and put `dev` in the **Profile** field.

Later updates: right-click the project → **Team → Pull**. If `build.gradle` changed, right-click → **Gradle →
Refresh Gradle Project**. Your own changes: **Team → Commit… → Commit and Push** (the token's *Contents: Read and
write* covers it). Command line equivalent:
`git clone --branch claude/member-id-normalization-design-ihju8o https://github.com/thehiddenbrain/Interop.git`,
then import the folder as in step 5. Once the branch is merged, switch with **Team → Switch To → Other… →
origin/main → New Branch**.

### Build and run

The build is the same shape as the EPA Workbench (`patient-access-workbench`): Gradle 9.5.0 wrapper,
Spring Boot 4.0.7, no toolchain block, `options.release = 17` plus `-parameters`, `springBoot { buildInfo() }`,
and an `internalRepoUrl` Gradle property that swaps Maven Central for an internal mirror. Only a JDK 17 or
newer is needed (17, 21 and 25 all work); the wrapper downloads Gradle and the dependencies on first use.

1. From a zip instead of GitHub: **File → Import → Gradle → Existing Gradle Project**, pick this folder
   (`member-id-resolution-service`; keep the folder name, Buildship wants it equal to the project name), accept
   the defaults (Gradle wrapper).
2. Run `MemberIdResolutionApplication` as a **Spring Boot App**. With no profile set it runs the **`pqa`**
   profile and calls the PQA MMI. With Profile = `dev` an **in-process MMI stub** answers from
   `src/main/resources/mmi-stub/members.json`, so nothing needs network access.
3. Open `http://localhost:9090/swagger-ui.html` or import the Postman collection in `postman/` (the collection
   expects the `dev` profile).

Command line: `./run.sh` (Mac/Linux) or `run.cmd` / `run.bat` (Windows) build the jar on first use and start it;
`./gradlew bootRun` (pqa, the default) · `./gradlew bootRun --args='--spring.profiles.active=dev'` (stub) ·
`./gradlew test` (all tests) · `./gradlew bootJar` then `java -jar build/libs/member-id-resolution-service-1.0.0.jar`
(pqa) or `... --spring.profiles.active=dev` (stub).

### Point it at a real MMI

| Profile | MMI |
|---|---|
| `dev` | in-process stub, no network (`--spring.profiles.active=dev`; Profile field `dev` in STS) |
| `fqa` | `http://mastermemberindexserviceapp-spring-boot-fqa.apps.tdqocp.thp.tahphq.tahp` |
| `pqa` (**default**) | `http://mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp` |
| `pqa-lite` | `http://mastermemberindexserviceapp-spring-boot-pqa-lite.apps.tdqocp.thp.tahphq.tahp` |
| `prod` | `http://mastermemberindexserviceapp-spring-boot-prod.apps.prodocp.thp.tahphq.tahp` |

#### Pointing at the real MMI, step by step

The profile is the only switch. Any profile other than `dev` turns the stub off and uses the MMI URL from
that profile's `application-<profile>.yaml`; nothing else changes.

1. **STS**: Run → Run Configurations → Spring Boot App → `MemberIdResolutionApplication` → **Profile** field:
   `pqa` (or on the Arguments tab, Program arguments: `--spring.profiles.active=pqa`). Apply, Run.
2. **Command line**: `set SPRING_PROFILES_ACTIVE=pqa` then `run.cmd` (Windows), or
   `SPRING_PROFILES_ACTIVE=pqa ./run.sh`, or `java -jar build/libs/member-id-resolution-service-1.0.0.jar --spring.profiles.active=pqa`.
3. **Check the startup banner** in the console. It must say `profiles : [pqa]`, `mmi client : REST` and the PQA
   URL. If it says `STUB`, the profile did not apply. `http://localhost:9090/actuator/info` shows the build.
4. **Network**: your machine (or the pod) must reach `mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp`
   on port 80 (plain HTTP, no token, as the MMI contract states). If it cannot, every call answers
   `503 MMI_UNAVAILABLE` with `CONNECT_FAILED` and the log line `mmi call failed ... cause=...` names the reason.
5. **Try it**: a Postman resolve request with a real PQA member id, typed as the EMR has it, or
   `curl -X POST http://localhost:9090/api/v1/member-ids/resolve -H "Content-Type: application/json" -d "{\"memberId\":\"<real id>\",\"vendor\":\"EVICORE\"}"`;
   for the id in every vendor's format,
   `curl -X POST http://localhost:9090/api/v1/member-ids/vendor-map -H "Content-Type: application/json" -d "{\"memberId\":\"<real id>\"}"`.
   Either response carries `mmiRequestId` (`MBRIDSVC-<millis>-<5 digits>`), which the MMI team can find in their logs.

#### Reading the MMI request and response when something fails

`mmi.log-payloads: true` makes the MMI client write the exact request body and the raw response body to the
console, unmasked, as two lines per call. It is on in `dev`, `fqa`, `pqa` and `pqa-lite` and off in `prod`
(the bodies contain PHI). The startup banner shows `mmi payload log : ON` when it is active. A real call
looks like this:

```
INFO  [<correlationId>] o.p.memberid.mmi.RestMmiClient - mmi request requestId=MBRIDSVC-1791083521042-40296 POST http://mastermemberindexserviceapp-spring-boot-pqa.apps.tdqocp.thp.tahphq.tahp/master/member/v1
{"memberId":"123456789","legacyMemberId":"123456789","voidCoverageRecord":"N","clientId":"MBRIDSVC","clientType":"INT","requestId":"MBRIDSVC-1791083521042-40296"}
INFO  [<correlationId>] o.p.memberid.mmi.RestMmiClient - mmi response requestId=MBRIDSVC-1791083521042-40296 status=200 contentType=application/json ms=18
{"clientId":"MBRIDSVC","clientType":"INT","requestId":"MBRIDSVC-1791083521042-40296","messages":null,"members":[ ... ]}
```

What the lines tell you:

| You see | Meaning | What to do |
|---|---|---|
| `mmi request ...` then `mmi call failed ... detail=CONNECT_FAILED cause=...` and no `mmi response` line | The host could not be reached (DNS, VPN, firewall) | Fix the network path to the MMI host; check `cause=` |
| `mmi response ... status=404`, this service answers `200 NOT_FOUND` | Member not found: MMI's normal answer when it has no member for that id in that environment; `mmiMessage` in the response carries MMI's message when the body had one. If every id comes back `NOT_FOUND`, check the URL: a 404 whose body is not MMI's envelope (Spring's default error JSON with `status` / `error` / `path`, an HTML page) is still `NOT_FOUND`, but the log shows `marker=MMI_404_WITHOUT_ENVELOPE` with the URL | Use an id that exists in PQA. If the marker appears, compare the URL on the `mmi request` line with the MMI team's; `mmi.path` is `/master/member/v1` |
| `mmi response ... status=400`, this service answers `400 MMI_BAD_REQUEST` with `HTTP_400` | MMI could not process the request as sent; the error message forwards MMI's code and text when the body had a message | Send the request line's JSON and MMI's text to the MMI team; adjust `mmi.client-id` / `mmi.client-type` if they ask |
| `mmi response ... status=500`, this service answers `503 MMI_UNAVAILABLE` with `HTTP_500` and `Retry-After: 10` | An internal error in MMI; the error message forwards MMI's code and text when the body had a message | Retry later; if it persists, send the request line's JSON and MMI's message to the MMI team |
| `mmi response ... status=<anything else>` (403, 502, 503 ...), this service answers `503 MMI_UNAVAILABLE` (5xx, 429, 408) or `502 MMI_ERROR` (other 4xx) with `HTTP_<code>` | The status is outside MMI's contract: a gateway, proxy or container answered, not MMI; the error message says so and repeats the URL | Check the host, the proxy settings and the URL on the `mmi request` line |
| `mmi response ... status=200` then `502 MMI_INVALID_RESPONSE` with `UNPARSEABLE_BODY` from this service | The body is not the MMI JSON (often an HTML sign-in or proxy page, `contentType=text/html`) | The call is being intercepted before MMI; check proxy settings and the host |
| `mmi response ... status=200` and the body has fields this service does not know | MMI added or renamed fields | Paste the response line; the DTOs in `org.point32health.memberid.mmi` are updated to match |
| `mmi response ... status=200`, `members` is empty and `messages[]` has no `ERROR` message, this service answers `200 NOT_FOUND` | MMI answered 200 with no member (MMI's contract uses 404 for that; this form is kept as `NOT_FOUND` too) | Use an id that exists in PQA |
| `mmi response ... status=200`, `members` is empty and `messages[]` has an `ERROR` message (for example `ES_TIMEOUT`), this service answers `502 MMI_ERROR` with `ERROR_MESSAGE` | MMI reported a failure of its own; the 502 message forwards MMI's `code=` and `text=` | Send the request line's JSON and MMI's message to the MMI team |

Turn it off with `mmi.log-payloads: false` in the profile file, or `MMI_LOG_PAYLOADS=false` once the
problem is found. Everything else in the log stays masked whether or not payload logging is on.

Overrides without a rebuild (environment variables or `-D` system properties): `MMI_BASE_URL` (any MMI, no
profile file needed), `MMI_CLIENT_ID` (placeholder `MBRIDSVC`; register the real application name with the
MMI team), `MMI_CONNECT_TIMEOUT=2s`, `MMI_READ_TIMEOUT=5s`, `MMI_LOG_PAYLOADS=true|false`.

**Deployment rule: always set `SPRING_PROFILES_ACTIVE`.** The default profile is `pqa` so that "Run As →
Spring Boot App" in STS talks to the PQA MMI with no setup. A pod that forgets the variable would therefore call
the PQA MMI, wrong in prod and in FQA: set `prod`, `fqa`, `pqa` or `pqa-lite` explicitly in every deployment. Two
guards back the stub: it refuses to start with any explicitly active profile other than `dev` or `test`, and it
refuses to start inside a Kubernetes/OpenShift pod at all (it checks `KUBERNETES_SERVICE_HOST`).

## 2. The API

Two operations, both `POST` with a JSON body (the member id is PHI and must not appear in a URL), both pure
reads that may be repeated. **Resolve** answers for one vendor; **vendor map** answers for every vendor at once.

### Resolve: `POST /api/v1/member-ids/resolve`

```http
POST /api/v1/member-ids/resolve
Content-Type: application/json
X-Correlation-Id: ONYX-PA-2026-000123        (optional; echoed in the response header, generated when absent)

{ "memberId": "123456789", "dateOfService": "2026-10-15", "vendor": "EVICORE",
  "patient": { "dateOfBirth": "1950-03-15" } }
```

| Field | Required | Notes |
|---|---|---|
| `memberId` | yes | As the EMR typed it. Only checked for presence; sent to MMI exactly as received, with surrounding whitespace removed. Not validated or reshaped here. |
| `dateOfService` | no | `yyyy-MM-dd`. **Defaults to today** when omitted (`dateOfServiceDefaulted: true` in the response). Must be within 10 years back / 366 days forward. |
| `vendor` | yes | Code or alias from the vendor table, case-insensitive: `EVICORE`, `MHK`, `EVOLENT`, `CARELON`, `OPTUM`, `ONYX`. |
| `patient.dateOfBirth` | recommended | `yyyy-MM-dd`. Used only to verify or pick among the records MMI returned. Never sent to MMI, never logged, never echoed. |

The service does not validate or reshape the incoming member id. Whatever the EMR typed (9, 11 or 14
characters, hyphens, spaces, letters, any length) goes to MMI as is, with only surrounding whitespace removed,
and MMI decides whether it knows the id. A missing or blank `memberId` is the only member-id error this service raises itself
(400 `INVALID_REQUEST`, detail code `MEMBER_ID_MISSING`); when MMI cannot process the id it is sent, MMI's 400 comes
back as 400 `MMI_BAD_REQUEST` with MMI's text.

### Resolve: response (HTTP 200), branch on `outcome`

| `outcome` | Meaning | Onyx action |
|---|---|---|
| `ACTIVE` | Member verified, `coverage.active = true` on the date of service | Put `memberId.forVendor` in the vendor payload. For Optum use `forVendorParts` when it is present (TMP/SCO ids); for Public Plans and HPHC ids there are no parts, send `forVendor`. |
| `INACTIVE` | Member verified, `coverage.active = false`; `message` says why (coverage ended, not yet effective, a gap, or no coverage on record) | Hold for intake (owner decision). |
| `NOT_FOUND` | MMI has no member for this id. MMI answers that with HTTP 404; it is a normal answer, so this service answers 200 with `message` "No member found in MMI for this id" and, when MMI sent a message, `mmiMessage` with MMI's own type / status / code / text | "Member not found" worklist. |
| `AMBIGUOUS` | MMI matched the ID to several persons (a 9-character ID of a population with dependents) and no DOB settled it | Resend with the member's full ID including the suffix, or with `patient.dateOfBirth`, else intake picks from `candidates[]`. |

```json
{ "outcome": "ACTIVE",
  "message": "Member found; coverage active on 2026-10-15",
  "memberId": { "received": "123456789", "stored": "123456789   01", "forVendor": "12345678901" },
  "lineOfBusiness": "MCR",
  "dateOfService": "2026-10-15", "dateOfServiceDefaulted": false,
  "coverage": { "active": true, "span": { "effectiveDate": "2024-01-01", "endDate": null } },
  "mmiRequestId": "MBRIDSVC-1760000000000-48213" }
```

`memberId.received` is the ID as Onyx sent it (surrounding whitespace removed): exactly what was sent to MMI.
`memberId.stored` is the ID exactly as MMI holds it. `forVendor` is that ID in the vendor's format.
For Optum (`SPLIT`) **and a TMP/SCO id** the response also carries `forVendorParts: { "memberId": "123456789",
"suffix": "01" }`; for Public Plans and HPHC ids only `forVendor` is returned.
Vendor formatting applies only to a stored ID of the TMP/SCO shape (9 characters, spaces, 2 digits); any other
stored ID (Public Plans, HPHC) is passed as stored for every vendor.
`coverage` is the flag and, when active, the coverage period: `coverage.span { effectiveDate, endDate }` is the span
that covers the date of service (`endDate` is an explicit `null` for open-ended coverage). When inactive there is no
period and `message` says why. Nothing else is returned about coverage: no reason code, no neighbouring span dates.
`lineOfBusiness` is MMI's value (`MCR`, `PP`, `COM`, ...); Onyx routes the transaction on it. The company (THP or
HPHC) is not returned: the stored id tells it apart (`HP` prefix) and Onyx does not act on it. The response is kept
to what Onyx acts on; the correlation id is in the `X-Correlation-Id` response header, not in a 200 body.

Response fields, in JSON order (absent blocks are omitted, not sent as `null`):

| Field | Present | Content |
|---|---|---|
| `outcome` | always | `ACTIVE`, `INACTIVE`, `NOT_FOUND`, `AMBIGUOUS` |
| `message` | always | One sentence for the outcome: `Member found; coverage active on <dateOfService>` · `Member found; coverage ended before <dateOfService>` · `Member found; coverage not yet effective on <dateOfService>` · `Member found; no coverage on <dateOfService> (gap between coverage periods)` · `Member found; no coverage on record` · `No member found in MMI for this id` · `Several members match this id; add patient.dateOfBirth or resend the member's full id including the suffix, or pick from candidates` (or, when a DOB was sent and several share it, `Several members match this id and date of birth; resend the member's full id including the suffix, or pick from candidates`) |
| `memberId` | always | `received`; `stored`, `forVendor`, `forVendorParts` when a member was identified |
| `dateOfService`, `dateOfServiceDefaulted` | always | The date evaluated and whether it was defaulted to today |
| `lineOfBusiness`, `coverage` | `ACTIVE`, `INACTIVE` | From the stored record and the coverage decision: `coverage { active, span? }` |
| `candidates[]` | `AMBIGUOUS` | `{ storedMemberId, lineOfBusiness, coverageActive }` per person, sorted by id |
| `mmiRequestId` | always | For the logs on both sides (the correlation id is in the response header) |
| `mmiMessage` | `NOT_FOUND`, only when MMI sent a message | `{ "type", "status", "code", "text" }`: the first entry of MMI's `messages[]`, as MMI sent it |

A `NOT_FOUND` answer from the dev stub (the real MMI's values will differ; the stub answers HTTP 404, as the owner
describes for MMI, with an `ERROR`-typed `MEMBER_NOT_FOUND` message of its own; whether the real 404 carries such a
message is confirmed in PQA (section 7)):

```json
{ "outcome": "NOT_FOUND",
  "message": "No member found in MMI for this id",
  "memberId": { "received": "HP111222333" },
  "dateOfService": "2026-10-15", "dateOfServiceDefaulted": false,
  "mmiRequestId": "MBRIDSVC-1760000000000-48215",
  "mmiMessage": { "type": "ERROR", "status": "404", "code": "MEMBER_NOT_FOUND", "text": "No member found for the given id (stub)" } }
```

### Vendor map: `POST /api/v1/member-ids/vendor-map`

Onyx does not always know which vendor will receive the authorization. The vendor map operation takes the
member id and returns it in every vendor's format; Onyx keeps the answer and picks the entry for the vendor
once it knows. Same single MMI call, same selection and coverage decision as `/resolve`; the rendering differs,
and the request is lenient: only the member id is required.

```http
POST /api/v1/member-ids/vendor-map
Content-Type: application/json
X-Correlation-Id: ONYX-PA-2026-000124        (optional; echoed in the response header, generated when absent)

{ "memberId": "123456789", "dateOfService": "2026-10-15",
  "patient": { "dateOfBirth": "1950-03-15" } }
```

| Field | Required | Notes |
|---|---|---|
| `memberId` | yes | As the EMR typed it. Same rule as `/resolve`: checked for presence only, sent to MMI exactly as received with surrounding whitespace removed. The only field whose value can cause a 400 (blank: `MEMBER_ID_MISSING`). A field sent as the wrong JSON type (an object or array where a string is expected, a string where `patient` is expected) is a body the service cannot read: 400 `WRONG_JSON_TYPE`, on either operation. |
| `dateOfService` | no | `yyyy-MM-dd`. **Defaults to today** when omitted (`dateOfServiceDefaulted: true`). A value that is not a real date or is outside 10 years back / 366 days forward is **ignored**: the date defaults to today and `ignoredFields` names `dateOfService`. |
| `vendor` | no | **Accepted and ignored**: this operation answers for every vendor. Lets Onyx send the `/resolve` payload as is. |
| `patient.dateOfBirth` | recommended | `yyyy-MM-dd`. A real date is used to verify or pick among the records MMI returned (a date that matches no record is still 422 `DOB_MISMATCH`). A value that is not a real date, is in the future or is more than 125 years ago is **ignored** and `ignoredFields` names `patient.dateOfBirth`. Never sent to MMI, never logged, never echoed. |

`/vendor-map` is lenient: only the member id is required; a `vendor` or any unknown property (at the top level
or inside `patient`) is accepted and ignored; an unusable date is ignored, not rejected, and named in
`ignoredFields`. The only 400 `INVALID_REQUEST` on this operation is a missing or blank `memberId`
(`MEMBER_ID_MISSING`) or a body the service cannot read as this request: malformed JSON (`MALFORMED_JSON`) or a
wrong JSON type (`WRONG_JSON_TYPE`: a non-object body, an object or array where a string is expected, a string
where `patient` is expected). A blank id never
reaches MMI. So

```json
{ "memberId": "123456789", "dateOfService": "10/15/2026", "patient": { "dateOfBirth": "not-a-date" } }
```

answers 200 `ACTIVE` with `"dateOfService": <today>`, `"dateOfServiceDefaulted": true`,
`"ignoredFields": ["dateOfService", "patient.dateOfBirth"]` and the full `vendorMemberIds` list.

**Response (HTTP 200)**: the same `outcome` values and `message` sentences, the same `coverage`,
`candidates[]` and `mmiMessage` blocks and the same Onyx actions as the tables above, with two differences.
There is no `vendor`, no `forVendor` and no `forVendorParts`; instead `vendorMemberIds[]` carries one entry per
configured vendor, sorted by vendor code, and for `ACTIVE` Onyx picks the entry for its vendor and puts that
`memberId` (for Optum, `memberIdParts` when present) in the vendor payload. And `ignoredFields[]` (after
`dateOfServiceDefaulted`, before `coverage`) lists the request fields that were present but unusable and
therefore ignored; it is absent when nothing was ignored, as below.

| Field | Present | Content |
|---|---|---|
| `outcome`, `message` | always | Same values and sentences as `/resolve` |
| `memberId` | always | `received`; `stored` when a member was identified |
| `lineOfBusiness`, `dateOfService`, `dateOfServiceDefaulted` | as on `/resolve` | |
| `ignoredFields[]` | when a request field was unusable | `dateOfService`, `patient.dateOfBirth` |
| `coverage`, `candidates[]` | as on `/resolve` | |
| `vendorMemberIds[]` | `ACTIVE`, `INACTIVE` | `{ vendor, memberId, memberIdParts? }` per configured vendor, sorted by vendor code |
| `mmiRequestId` | always | |
| `mmiMessage` | `NOT_FOUND`, only when MMI sent a message | The first entry of MMI's `messages[]`, as on `/resolve` |

For the TMP id `123456789` (stored `123456789   01`):

```json
{ "outcome": "ACTIVE",
  "message": "Member found; coverage active on 2026-10-15",
  "memberId": { "received": "123456789", "stored": "123456789   01" },
  "lineOfBusiness": "MCR",
  "dateOfService": "2026-10-15", "dateOfServiceDefaulted": false,
  "coverage": { "active": true, "span": { "effectiveDate": "2024-01-01", "endDate": null } },
  "vendorMemberIds": [
    { "vendor": "CARELON", "memberId": "12345678901" },
    { "vendor": "EVICORE", "memberId": "12345678901" },
    { "vendor": "EVOLENT", "memberId": "12345678901" },
    { "vendor": "MHK",     "memberId": "123456789   01" },
    { "vendor": "ONYX",    "memberId": "12345678901" },
    { "vendor": "OPTUM",   "memberId": "12345678901", "memberIdParts": { "memberId": "123456789", "suffix": "01" } }
  ],
  "mmiRequestId": "MBRIDSVC-1760000000000-48214" }
```

`vendorMemberIds` is present **only when a member was identified**, that is for `ACTIVE` and `INACTIVE`.
`NOT_FOUND` and `AMBIGUOUS` answers carry no `vendorMemberIds` and no `memberId.stored`; a `NOT_FOUND` answer
carries `message` "No member found in MMI for this id" and `mmiMessage` when MMI sent a message, exactly as on
`/resolve`; an `AMBIGUOUS` answer carries `candidates[]` and the same `message` exactly as on `/resolve`, and a resend
with `patient.dateOfBirth` settles it. `memberIdParts` appears only on vendors whose format is `SPLIT` (Optum) and only for a TMP/SCO id.
For an HPHC id (stored `HP456789012`) or a Public Plans id (`34567890102`) every entry carries the stored id
unchanged and no entry has `memberIdParts`: the same rule as `forVendor` on `/resolve`. Absent blocks are
omitted, not sent as `null`.

**Which one to call.** Call `/resolve` when Onyx already knows the vendor; call `/vendor-map` when it does not,
and pick the entry for the vendor later. The same payload can go to either: `/vendor-map` ignores the `vendor`.
Either way there is one MMI call, and if this service is down Onyx's fallback is unchanged: pass the EMR's id
through to the vendor as received.

### Errors (non-200), both operations: `{ "error": { "code", "message", "details": [ { "field", "code", "message" } ] }, "correlationId", "mmiRequestId" }`

Every answer the application produces, on `/resolve` and on `/vendor-map` alike, has this shape (`mmiRequestId`
is present once MMI was called, including on a 422). The one exception is a request Tomcat rejects before it
reaches the application (malformed percent-encoding in the URL, a header block over 8 KB): that returns Spring
Boot's default error JSON. `UNKNOWN_VENDOR` cannot occur on `/vendor-map`: a `vendor` sent to it is accepted
and ignored, like any unknown property.

MMI's contract has four answers: 200 success, 404 member not found, 400 bad request, 500 internal error. This
service maps them one to one; anything else is not MMI speaking. Only an unknown route of this service is a 404
from this service (`ROUTE_NOT_FOUND`). Nothing in this mapping is configurable.

| MMI | This service |
|---|---|
| 200 | `outcome` from the records: `ACTIVE`, `INACTIVE` or `AMBIGUOUS`. No members → 200 `NOT_FOUND`, unless `messages[]` carries an `ERROR`-typed message (`mmi.error-message-types`): then 502 `MMI_ERROR` / `ERROR_MESSAGE`, forwarding MMI's code and text (`MMI reported an error: type=ERROR status=500 code=ES_TIMEOUT text=...`) |
| 404 (member not found) | 200 `NOT_FOUND`, whatever the body; `mmiMessage` carries MMI's first message when there is one. MMI's envelope is a JSON object with any of `members` / `messages` / `clientId` / `requestId`; an empty body counts as one. A 404 with any other body (a container's default error JSON, an HTML page) is still `NOT_FOUND`, and the log gets a WARN with `marker=MMI_404_WITHOUT_ENVELOPE` naming the URL, because a wrong `mmi.base-url` / `mmi.path` looks exactly like that |
| 400 (bad request) | 400 `MMI_BAD_REQUEST`, `details[0].code` `HTTP_400`, message `MMI rejected the request as a bad request` + `: <MMI code> <MMI text>` when MMI sent a message. No `Retry-After`; `mmiRequestId` present |
| 500 (internal error) | 503 `MMI_UNAVAILABLE`, `details[0].code` `HTTP_500`, message `MMI reported an internal error` + `: <code> <text>` when present; `Retry-After: 10` |
| any other status | A gateway, proxy or container answered, not MMI. 5xx, 429, 408 → 503 `MMI_UNAVAILABLE` `HTTP_<code>`; other 4xx → 502 `MMI_ERROR` `HTTP_<code>`. The message reads `HTTP <code> from the MMI endpoint, which is not in MMI's contract (200, 400, 404, 500): a gateway or proxy answered, not MMI (<url>)` |
| cannot reach / timeout | 503 `MMI_UNAVAILABLE`, `CONNECT_FAILED` or `READ_TIMEOUT`; `Retry-After: 10` |
| unreadable body | 502 `MMI_INVALID_RESPONSE`: a 2xx with an empty or non-JSON body (`EMPTY_BODY`, `UNPARSEABLE_BODY`), records without a member id (`NO_MEMBER_ID`), or a member whose only coverage spans have unreadable dates (`UNREADABLE_COVERAGE`) |

| HTTP | `error.code` | When | Onyx action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | `/resolve`: missing member ID, bad dates (`DATE_OF_SERVICE_INVALID`, `DATE_OF_SERVICE_OUT_OF_RANGE`, `DATE_OF_BIRTH_INVALID`, `DATE_OF_BIRTH_OUT_OF_RANGE`), missing or malformed vendor (`VENDOR_MISSING`, `VENDOR_INVALID`), unknown property (`UNKNOWN_PROPERTY`), malformed JSON, wrong JSON type; every problem is listed in `details[]`. `/vendor-map`: only a missing or blank member ID (`MEMBER_ID_MISSING`) or a body the service cannot read (`MALFORMED_JSON`, or `WRONG_JSON_TYPE` for the body or any field); bad dates and unknown properties are ignored there, not rejected. (404 / 405 / 406 / 415 use the same envelope for a wrong route, method, representation or content type; the 404 detail `ROUTE_NOT_FOUND` says "the operations are POST /api/v1/member-ids/resolve and POST /api/v1/member-ids/vendor-map".) | Never retry. `MEMBER_ID_MISSING` and `DATE_OF_SERVICE_OUT_OF_RANGE` are provider data problems; anything else is an Onyx mapping defect. |
| 400 | `UNKNOWN_VENDOR` | `/resolve` only: vendor not in the table; `details[0].message` lists the known codes | Never retry; routing table and this table disagree. |
| 400 | `MMI_BAD_REQUEST` | MMI answered 400: it could not process the request as sent (`details[0].code` `HTTP_400`; the message forwards MMI's code and text when MMI sent a message). `mmiRequestId` present, no `Retry-After`. | Never retry as is. Alert the service owners with the `mmiRequestId`; MMI's text says what it did not accept. |
| 422 | `DOB_MISMATCH` | a DOB was sent and matches no record for this ID | Manual identity review; never file the auth. |
| 502 | `MMI_ERROR`, `MMI_INVALID_RESPONSE` | MMI reported an error of its own in `messages[]` with no members (`ERROR_MESSAGE`; the message forwards MMI's code and text: `MMI reported an error: type=ERROR status=500 code=ES_TIMEOUT text=...`); a 4xx other than 400 and 404 from the MMI endpoint, which is a gateway or proxy answering, not MMI (`HTTP_<code>`; the message says so and repeats the URL); an unreadable 2xx body (`EMPTY_BODY`, `UNPARSEABLE_BODY`); records without a member id (`NO_MEMBER_ID`); or the member's only coverage spans have unreadable dates (`UNREADABLE_COVERAGE`: the service refuses to say INACTIVE on data it cannot read). | Park, alert the service owners. |
| 503 | `MMI_UNAVAILABLE` (`Retry-After: 10`) | MMI unreachable (`CONNECT_FAILED`) or timed out (`READ_TIMEOUT`); MMI 500 (`HTTP_500`; the message forwards MMI's code and text when present); a 5xx, 429 or 408 from a gateway in front of MMI (`HTTP_<code>`). | Retry later. |
| 500 | `INTERNAL_ERROR` | a bug here | Retry once later, alert the service owners. |

**If this service itself is unreachable**, Onyx's agreed fallback is to pass the member ID exactly as it
received it from the EMR to the UM vendor. Nothing here needs to be built for that; it is an Onyx rule.

## 3. How an answer is produced

1. Take the ID as received: surrounding whitespace removed, nothing else. No separator stripping, no
   upper-casing, no shape or length check; the member id is sent to MMI exactly as the EMR typed it.
2. One MMI call: `POST {mmi.base-url}/master/member/v1` with that ID in `memberId` **and**
   `legacyMemberId` (the MMI spec's hit-rate advice), `voidCoverageRecord: N`, `clientId`, `clientType: INT`,
   a fresh `requestId` (`MBRIDSVC-<millis>-<5 digits>`). No date filter: coverage is evaluated locally so
   INACTIVE can say why. No demographics are ever sent.
   MMI's HTTP status is kept with the answer and mapped as in the table in section 2 (Errors): 200 is parsed;
   404 is MMI's "no member for this id", a normal answer (`NOT_FOUND`) whatever the body, and a body that is not
   MMI's envelope only adds `marker=MMI_404_WITHOUT_ENVELOPE` to the log; 400 is 400 `MMI_BAD_REQUEST` with MMI's
   text; 500 is 503 `MMI_UNAVAILABLE`; any other status is a gateway or proxy, not MMI: 503 for 5xx, 429 and 408,
   502 `HTTP_<code>` for the rest. Nothing in this mapping is configurable. A 200 with no members is `NOT_FOUND`
   too, unless `messages[]` carries an `ERROR`-typed message (`mmi.error-message-types`, for example `ES_TIMEOUT`):
   then 502 `MMI_ERROR` / `ERROR_MESSAGE`, forwarding MMI's code and text. A `NOT_FOUND` answer carries MMI's first
   message in `mmiMessage` when there is one. Members beside a 404 are ignored (the status wins) and `marker=MMI_NOT_FOUND_WITH_MEMBERS` is logged.
3. Reduce the records: identical records merged; records linked through `legacyMemberId` (THP↔HPHC
   conversion) are one person and the record covering the date of service wins; a supplied DOB picks one
   person or proves a mismatch; several persons without a DOB → `AMBIGUOUS`.
4. Coverage: a non-void span with `effDate ≤ DOS ≤ endDate` (inclusive; null or `12/31/9999` = open)
   → active. A span with an unreadable date is skipped and logged with `marker=UNREADABLE_SPAN`; the
   remaining spans decide. If a member has unreadable spans and no readable one, the answer is 502
   `UNREADABLE_COVERAGE`, never a confident INACTIVE. For a converted member the two records' spans are
   evaluated together, so a gap between the old and the new record is reported as a gap.
5. Format for the vendor from the stored ID (never from the input).

`/vendor-map` runs the same steps 1 to 4 and renders step 5 for every configured vendor instead of one, in
vendor-code order; the member is identified once, from one MMI call, whichever operation is used. Before step 1
it drops what it cannot use instead of rejecting it: an unusable date of service becomes today and an unusable
date of birth is left out of step 3, both named in `ignoredFields`; a `vendor` or unknown property is ignored.

TMP / SCO members have no dependents: a 9-character card number returns exactly one record and resolves
directly. Only populations with dependents (HPHC commercial, Together) can produce `AMBIGUOUS`.

## 4. Vendor formats: the only thing to edit when a vendor changes

`src/main/resources/application.yaml`, block `member-id.vendors` (edit it there; the block below is a copy of
the shipped values). Change a value, redeploy; the startup log prints the effective table rendered against
a sample ID. Adding a vendor to this block also adds its entry to `/vendor-map` automatically: the vendor map
is rendered from this table and nothing else needs editing.

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
      format: COMPACT_11
    OPTUM:
      display-name: Optum
      format: SPLIT                     # "123456789" + "01" as two fields (TMP/SCO ids)
    ONYX:
      display-name: Onyx
      format: COMPACT_11
```

Formats: `COMPACT_11`, `SPACED_14`, `SPLIT`, `AS_STORED`. The TMP core is **9 characters, a letter and 8 digits** (`S12345678`), stored with three spaces and the suffix `01`: `S12345678   01`. A format applies when the stored id is a run of letters and digits, a separator (any blanks or punctuation) and a short numeric suffix; the core and the suffix are copied character for character, so the `S` is always kept (`S1234567801`, `S12345678   01`, `S12345678` + `01`). A stored id of any other shape is passed on unchanged and, when it is not plain letters and digits, the log says so (`marker=STORED_ID_NOT_RESHAPED` with the shape, never the characters). A new output shape = one constant in
`VendorIdFormat` + one case in `VendorFormatter` + one test row.

## 5. Stub fixtures (dev profile: `--spring.profiles.active=dev`) and Postman

`src/main/resources/mmi-stub/members.json` behaves like MMI: exact match, 9-character (policy) match returning
the family, legacy-id match and the second pass through `legacyMemberId`. The stub matches ignoring separators
and case (anything that is not a letter or digit is ignored), the leniency expected of the real MMI; the service
itself hands the id over untouched. The fault ids below are recognised by the first 9 characters of the id with
separators removed. The same fixtures serve `/resolve` and `/vendor-map`.

| Member ID (as typed; the stub ignores separators and case) | Case |
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
| `500500500` · `503503503` · `400400400` · `888888888` · `202202202` | faults: MMI 500 → 503 `MMI_UNAVAILABLE` `HTTP_500` (message `MMI reported an internal error: INTERNAL_ERROR search failed (stub)`), timeout → 503 `READ_TIMEOUT`, MMI 400 → 400 `MMI_BAD_REQUEST` `HTTP_400` (message `MMI rejected the request as a bad request: INVALID_REQUEST memberId could not be processed (stub)`), an `ERROR` message `ES_TIMEOUT` with no members → 502 `ERROR_MESSAGE` (the message carries `code=ES_TIMEOUT text=search backend timed out (stub)`), bad body → 502 `UNPARSEABLE_BODY` |
| `887777777` · `886666666` | an error message beside a member record → 200 with a warning; a record without a member id → 502 `NO_MEMBER_ID` |
| anything else (for example `HP111222333`) | NOT_FOUND: the stub answers like the real MMI, HTTP 404 with `messages: [ { messageType ERROR, statusCode "404", messageCode MEMBER_NOT_FOUND, message "No member found for the given id (stub)" } ]` and no members; the service answers 200 `NOT_FOUND` with that message in `mmiMessage`. The payload log line for the stub prints `status=404` |

Postman: import `postman/MemberIdResolution.postman_collection.json` and
`postman/Local.postman_environment.json` (`baseUrl = http://localhost:9090`). Every request carries tests;
run the whole collection with the Collection Runner for a green scenario pass. Folders 1 to 5 exercise
`/resolve` (folder 6 is health and the OpenAPI document); folder **7 Vendor map** exercises `/vendor-map` with the same fixtures (the TMP id in every
vendor's format, HPHC and Public Plans ids passed as stored, answers without `vendorMemberIds`, and the lenient
request: a `vendor` or an unusable date accepted and ignored, only a missing member id answered 400 `INVALID_REQUEST`). The dev
stub's "today" is the real date, so those tests assert `dateOfServiceDefaulted` is true rather than a specific date.

## 6. Tests

`./gradlew test` (119 tests): request validation, coverage rules, selection rules (including converted members in
a gap and with overlapping records), vendor formats, MMI mapping, the REST client against a mock server
(`notFoundWithAnMmiEnvelopeIsANormalAnswer`, `notFoundWithAnEmptyBodyIsANormalAnswer`,
`notFoundWithoutAnMmiEnvelopeIsStillNotFoundButWarns`, `badRequestIsForwardedAs400WithMmiText`,
`badRequestWithoutABodyIsStillForwardedAs400`, `internalErrorIsUnavailable503WithMmiText`,
`statusOutsideMmiContractSaysSo`, plus 429, 408, read timeout, connection refused and unparseable body),
configuration validation, and the end-to-end scenario matrix over HTTP against the stub with "today" fixed at
2026-10-03. The scenarios assert `message` on `ACTIVE`, `message` and `mmiMessage` on `NOT_FOUND` on both
operations, that `400400400` is 400 `MMI_BAD_REQUEST` / `HTTP_400` with MMI's text and no `Retry-After`, and that
the 502 `ERROR_MESSAGE` body carries MMI's `code=ES_TIMEOUT` and `text=`. Four of the scenarios cover
`/vendor-map`: the TMP id rendered for every vendor from one MMI call (Optum with `memberIdParts`, no `vendor`
or `forVendor` in the response); HPHC and Public Plans ids passed as stored to every vendor, with the defaulted
date and an INACTIVE gap; NOT_FOUND and AMBIGUOUS answers without `vendorMemberIds`, and a DOB that settles the
ambiguity; and `vendorMapIsLenientAboutEverythingExceptTheMemberId`, which proves that a blank member id is the
only request-validation 400 (`INVALID_REQUEST`, `MEMBER_ID_MISSING`) and never reaches MMI, that the `/resolve` payload with its `vendor` is accepted as is (200, no
`ignoredFields`), that an unknown property, a `10/15/2026` date of service and a `not-a-date` date of birth
answer 200 with today's date, `dateOfServiceDefaulted: true` and `ignoredFields: ["dateOfService",
"patient.dateOfBirth"]`, that an out-of-window date of service is defaulted the same way, that a real but wrong
DOB is still 422 `DOB_MISMATCH` after the one MMI call (`mmiRequestId` present), and that an unknown property with a
`10/15/2026` date of service on `/resolve` is still 400 `INVALID_REQUEST`. A capturing log appender
asserts no log line (message or exception text; payload logging is off in the `test` profile) contains an
unmasked 9- or 11-digit run or an MM/dd/yyyy date, and no response body contains names or SSN; a stub call counter proves invalid requests
never reach MMI and that every odd-shaped id (10 digits, 40 digits, letters and punctuation) is sent to MMI
and echoed back unchanged in `memberId.received`, and the stub's `lastSearched()` proves the id arrives at MMI
untouched.

## 7. Assumptions to confirm in PQA

- MMI finds the member from the id as typed, including the 9-, 11- and 14-character forms and ids with
  hyphens or spaces (`123456789-01`, `HP-123456789`); the service sends it unchanged in `memberId` and
  `legacyMemberId`. The stub assumes the same.
- A single record returned for any population is the member (TMP/SCO always return one).
- MMI's error `messageType` is `ERROR` (`mmi.error-message-types`). MMI's contract is 200 / 404 / 400 / 500 (owner);
  confirm in PQA that a not-found 404 carries the envelope (`messages[]` with MMI's code and text), so `mmiMessage`
  can be filled: if the log shows `MMI_404_WITHOUT_ENVELOPE` for an id that exists nowhere, it does not.
- `coverage.active` is the flag; the span is supporting detail. Legacy IDs, migration dates, PCP and
  group names are intentionally not returned.
