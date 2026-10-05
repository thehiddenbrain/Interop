# Feedback log: Interop Resolution Service design

Collected from the owner after design draft v0.1 (2026-10-03). Nothing here is applied yet; the
design is revised once the owner says the feedback is final.

## Feedback 1 (2026-10-03): Point32Health data sources, ID formats, vendors, background section

### How member IDs are stored at Point32Health
- **THP Medicare (TMP) and SCO membership** (SCO is under Medicare): stored as 14 characters =
  9 digits + 3 spaces + suffix `01`. The ID card prints only the 9 digits. This is where nearly all
  of the formatting problems are.
- **THP Public Plans** (includes the SNP, Direct and Together populations): one continuous
  11-character ID. All Public Plans member IDs are always 11 characters. No issues with this
  population.
- **HPHC**: stored as an `HP` number, 11 characters in total. The HPHC ID card prints a hyphen
  after `HP` (`HP-xxxxxxxxx`).

### How vendors store the THP Medicare ID
- Some vendors do not accept spaces: they store 11 characters (9 digits + `01`, spaces removed).
- Some vendors store the 14 characters with the 3 spaces.
- EMRs may enter only the 9 digits, because that is what the card shows.

| Vendor | Stores THP Medicare ID as | Notes |
|---|---|---|
| eviCore | 11 characters | |
| MHK | 14 characters | |
| Evolent | 11 characters | new vendor, not in the v0.1 sample table |
| Carelon | 11 or 14, owner to confirm | |
| Optum | 11 characters in their database | We send **two separate fields**: the 9 characters and the `01` suffix. Optum concatenates them when storing / in their extract. |

### Requested additions to the design document
- A background / "why we are doing this" section up front: how the ID appears on the public ID
  card (THP Medicare: 9 digits; HPHC: `HP-` + digits), how it is stored in the Point32Health
  systems (THP Medicare: 14 characters; Public Plans: 11; HPHC: 11), and how each vendor stores it.
- Sample ID cards: from the public website if available, otherwise a mock-up that shows how the
  ID is displayed on the card versus stored versus sent to vendors.

### Transcription notes (to confirm with the owner)
- "TMP" read as Tufts Medicare Preferred; "Medicare and TMP are synonymous".
- "Avility / Avicors" read as eviCore; "Avalent" read as Evolent.

## Questions raised back to the owner (open)
See the conversation; answers will be appended here.

## Feedback 2 (2026-10-03): TMP has no dependents; MMI returns one record

- **TMP / Medicare membership has no dependents.** Whether Onyx sends the 9-digit, 11-digit or
  14-character form, MMI always returns exactly one record. A 9-digit TMP input is **not ambiguous**.
- **Populations with dependents**: HPHC commercial and the Together (Public Plans) population. There a
  9-digit search may return more than one record, so the ambiguity handling still applies to them.
- Design implication to apply in the revision (not applied yet): the "9-digit + one record =
  PERSON_NOT_CONFIRMED" rule from v0.1 is wrong for TMP. A single record returned for a TMP member can
  be accepted as the patient. The AMBIGUOUS / DOB rules are needed only for populations that have
  dependents. The service needs a reliable way to recognise a TMP record in the MMI response (see
  questions).
- Answers question 2 of the open list (suffix is effectively always `01` for TMP; one person per
  9-digit number).

## Feedback 3 (2026-10-03): vendor formats stay in YAML or code, no database table

- No separate database table for vendor formats. The service must stay a simple, stateless service.
- Four or five vendors; if a format changes, a simple code or YAML change plus a deploy is acceptable.
- Clarification recorded: the v0.1 "vendor table" is the `member-id.vendors` map in `application.yml`
  plus the two format enums, not a database. v0.1 also adds (a) an optional ConfigMap override of the
  YAML and (b) a startup check that the SAMPLE values were replaced in PQA/PROD. Both are candidates
  to drop in the revision to keep the mapping as plain as possible. Decision pending final feedback.

## Feedback 4 (2026-10-03): behaviour when no date of service is supplied

- Think again about the case where Onyx does not have a date of service. v0.1 rejects the request
  (400 `DATE_OF_SERVICE_MISSING`); the owner wants an alternative considered, for example returning
  current, recent past and future coverage instead of a single-date status.
- Options to weigh in the revision (none chosen yet):
  1. Keep `dateOfService` required (v0.1).
  2. Default it to today, evaluate as usual, and flag `dateOfServiceDefaulted: true` in the response.
  3. With no DOS, still resolve and format the member ID (that part never needs a date), and return a
     coverage summary instead of one status: the span covering today (if any), the most recent past
     span, the next future span, with status as of today.
  4. Return every span inside a window (for example 1 year back, 1 year forward) and let Onyx decide.
- In all options the ID resolution and vendor formatting are unaffected; only the coverage block
  changes shape, so the contract change is additive.

## Feedback 5 (2026-10-03): coverage must be an explicit yes/no flag

- The response must state plainly whether the member is covered on the date of service: a flag
  (Y/N, true/false). Onyx must never have to derive it from spans.
- Spans may still be sent for other purposes, as supporting detail only.
- v0.1 already does this with `coverage.status: ACTIVE | INACTIVE` (plus `outcome`). For the revision,
  consider an explicit boolean such as `coverage.active: true | false` next to or instead of the
  enum, so the flag is unmistakable. Keep the span and the reason as detail.
- Consequence for feedback 4 (no date of service): option 4, "return spans and let Onyx decide", is
  out. Any no-DOS behaviour must still produce the flag, evaluated as of today, with a marker that the
  date was defaulted.

## Feedback 6 (2026-10-03): final round, go build it

- Terminology: "canonical" was unclear. Use plain words: the member ID **as stored in MMI**.
- No tables. Mapping in YAML (or the simplest equivalent); a format change is a config/code change
  plus a simple deployment. Simple, stateless service. **The only integration is MMI.** Nothing else.
- `retryable` is not needed. MMI is a core service that will not be down; if it is, much else is down
  and it is restored quickly. Do not build machinery around MMI outages.
- If **this** service is unavailable, Onyx's fallback is to pass the member ID exactly as received
  from the EMR to the UM vendor. Document that; nothing to build here.
- Security (tokens, certificates, credentials) is handled outside this code by the Azure API
  Management layer. Do not build an API key or security envelope. Focus on functionality.
- Response must be very lean. Question whether legacy member ID and similar fields are needed at all.
- Performance: MMI answers quickly; this service must stay fast and lean as well. No performance claims
  belong in the code, configuration or documents.
- Resolution rules: if a DOB is supplied and MMI returns several records, pick the matching one. If no
  DOB and the member is non-TMP with a 9-digit ID that returns several records, answer AMBIGUOUS. TMP
  always returns one record.
- Deliverables now: the code (Gradle, Spring Boot, importable straight into STS), unit tests, all
  scenarios tested, a local run against a stub MMI (port 9090 suggested), and a Postman collection
  against localhost. The owner will point it at the PQA MMI first.

## Consolidated decisions applied in v0.2 (code + revised design)
1. Response fields (as shipped): `outcome` (ACTIVE | INACTIVE | NOT_FOUND | AMBIGUOUS),
   `memberId.received`, `memberId.searched`, `memberId.stored`, `memberId.forVendor`
   (+ `memberId.forVendorParts` for split formats on TMP/SCO ids), `vendor`, `company`, `lineOfBusiness`,
   top-level `dateOfService` and `dateOfServiceDefaulted` (top-level so NOT_FOUND and AMBIGUOUS carry
   them too), `coverage.active` (boolean flag), `coverage.reason`, `coverage.span`,
   `coverage.lastEndDate` / `nextEffectiveDate` (whenever such spans exist, ACTIVE included),
   `ambiguity` + `candidates[]` on AMBIGUOUS, `correlationId`, `mmiRequestId`.
   Dropped: retryable, trace block, legacy/converted/migration/restricted/relationship fields.
2. Vendor formats (YAML): eviCore COMPACT_11, MHK SPACED_14, Evolent COMPACT_11, Carelon COMPACT_11
   (to confirm), Optum SPLIT (9 digits and suffix as two fields, plus the 11-character join).
   Formats apply to IDs stored as 9 digits + spaces + suffix (TMP/SCO). Public Plans (11 continuous)
   and HPHC (HP + digits) IDs are passed as stored for every vendor.
3. No date of service: default to today, flag `dateOfServiceDefaulted: true`, and still return the
   nearest past/future span dates.
4. Removed: Resilience4j retry and circuit breaker, API key filter, body-size filter, host-vs-profile
   guard, ConfigMap override, SAMPLE guard, "degraded mode" discussion. Kept: plain timeouts, a clear
   503 when MMI cannot be reached, stub MMI only in the local profile, masked logging.
5. Ambiguity: exactly one record returned is the member (any population). Several records: DOB picks
   one, else AMBIGUOUS with candidates. DOB supplied and matching no record: 422 DOB_MISMATCH.
   Records linked through legacyMemberId (THP<->HPHC conversion) count as one person; the record
   covering the date of service wins.


## Post-review adjustments (2026-10-03, after the adversarial code review)
- A member whose only coverage spans have unreadable dates is answered 502 `MMI_INVALID_RESPONSE` /
  `UNREADABLE_COVERAGE` instead of a confident INACTIVE. Spans with unreadable dates are still skipped when
  readable ones exist.
- The stub additionally refuses to start inside a Kubernetes/OpenShift pod (`KUBERNETES_SERVICE_HOST`),
  because the default profile is `local`; `SPRING_PROFILES_ACTIVE` is mandatory in every deployment.
- For a converted member, coverage reason and nearest dates come from both records' spans together.
- `coverage.lastEndDate` / `nextEffectiveDate` are returned for ACTIVE answers as well.
- 422 `DOB_MISMATCH` carries `mmiRequestId`; 405 carries `Allow: POST`; an unsupported representation
  (e.g. `/api-docs.yaml`) is 406, not 500; a connection failure while reading the MMI body is 503, not 502;
  nested unknown properties report the full JSON path.

## Build alignment (2026-10-04)
- The first import into STS failed with "Unsupported class file major version 69": the workstation runs
  Gradle on JDK 25 and the shipped wrapper (Gradle 8.14.3, Spring Boot 3.5) could not run there.
- The owner's other services on this repository (patient-access-workbench = the EPA Workbench,
  member-profile-service) share one build shape. The project now uses exactly that: Gradle 9.5.0 wrapper,
  Spring Boot 4.0.7 (Spring Framework 7, Jackson 3), no toolchain block, `options.release = 17` plus
  `-parameters`, `springBoot { buildInfo() }`, `internalRepoUrl` repository switch, `application.yaml`
  files, default profile `dev`, `run.sh` / `run.cmd` launchers, `gradle.properties` with the JDK note.
- Consequences in code: Jackson 3 packages (`tools.jackson.*`), Boot 4 starter names, MMI timeouts moved
  under `mmi.connect-timeout` / `mmi.read-timeout` (applied to the RestClient builder in `MmiClientConfig`),
  tests use a plain RestClient and `ApplicationContextRunner`. Behaviour and contract are unchanged;
  124 tests and the 45-request Postman collection are green on the new build.

## Troubleshooting aid (2026-10-04)
- The owner hit an error against the real MMI and could not see what was sent or received. New switch
  `mmi.log-payloads` (default `false`): when `true` the MMI client logs the exact request body and the raw
  response body (status, content type, elapsed ms) as two log lines per call, unmasked. On in `dev` (the stub
  prints the same two lines), `fqa`, `pqa`, `pqa-lite`; off in `prod` because the bodies contain PHI. The
  startup banner shows `mmi payload log : ON / off`.
- Removed the "MMI averages about 16 ms" remarks from the YAML comments, the design and this log, per the
  owner: no bragging in the deliverables.

## Feedback 7 (2026-10-04): no restriction on the incoming member id; pass it through to MMI

- A real PQA member id was rejected with `MEMBER_ID_UNRECOGNIZED_SHAPE`. The owner: the EMR can send 9, 10, 11,
  14, 20 or 40 characters, hyphens, whatever; if MMI can find it, MMI will. This service must not judge the
  shape, the length or the characters of a member id. "The service is just passing through to MMI."
- Applied: the parser (`MemberIdParser`, `ParsedMemberId`, `InputShape`) and the `member-id.hphc-digit-lengths`
  setting are deleted. The only check on `memberId` is that it is present (`MEMBER_ID_MISSING`). The value is sent
  to MMI exactly as received, surrounding whitespace removed, in both `memberId` and `legacyMemberId`.
  `memberId.searched` is dropped from the response because it would always equal `memberId.received`.
  Error codes `MEMBER_ID_UNRECOGNIZED_SHAPE`, `MEMBER_ID_TOO_LONG` and `MEMBER_ID_ILLEGAL_CHARACTERS` no longer exist.
- The dev stub now ignores separators and case when matching fixtures, which is the leniency described for the
  real MMI; the PQA assumption to confirm is that MMI finds a member from a hyphenated or spaced id.

## Feedback 8 (2026-10-04): a second operation when Onyx has no vendor

- Onyx does not always know which vendor will receive the authorization. Given only the member id (and
  whatever else it has: date of service, date of birth), the service must return the member id in every
  vendor's format at once: eviCore 11, MHK 14, Optum two fields, and so on.
- Applied: `POST /api/v1/member-ids/vendor-map`, in the same controller and service. Same MMI call, same
  selection and coverage decision as `/resolve`; no `vendor` in the request; the response carries
  `memberId.received`, `memberId.stored` and `vendorMemberIds[]`, one entry per configured vendor
  (`vendor`, `memberId`, and `memberIdParts` for split formats), sorted by vendor code, present for ACTIVE
  and INACTIVE. NOT_FOUND and AMBIGUOUS answer as in `/resolve`. One MMI call serves all vendors.

## Feedback 9 (2026-10-04): the vendor-map operation must be lenient

- Onyx may send the vendor name, or other details, with a vendor-map request; that must never be an
  "invalid request". The operation exists to answer "for this member, here are the vendors and their member
  ids"; nothing but the member id is required.
- Applied: `/vendor-map` accepts a `vendor` field and ignores it (so the `/resolve` payload can be sent as is),
  ignores unknown properties, and ignores an unusable date of service or date of birth instead of rejecting it:
  the date of service then defaults to today (`dateOfServiceDefaulted: true`) and the response lists what was
  ignored under `ignoredFields`. The only 400 left on `/vendor-map` is a missing member id (or a body that is not
  JSON). A real date of birth is still used to pick among several records and a mismatch is still 422.
  `/resolve` keeps its strict validation.

## Feedback 10 (2026-10-04): MMI answers 404 when it has no member; forward MMI's codes and say "member not found"

- MMI returns HTTP 404 for "no results found". The service must not treat that as an MMI failure: it is a
  normal answer, 200 `NOT_FOUND`. A 404 from this service itself is only for an unknown route. Whatever code MMI
  returns should be forwarded, or turned into a good message; the owner will share MMI's error-code list.
- Applied: an MMI 404 whose body is the MMI envelope (messages, no members) or empty is `NOT_FOUND`; a 404 with a
  non-MMI body (the container's default error page, a proxy's HTML) means a wrong URL and is 502 `HTTP_404` with
  a message naming the URL. A message whose statusCode / messageCode is in `mmi.not-found-status-codes` /
  `mmi.not-found-message-codes` (YAML, defaults 404 and NOT_FOUND-like codes) is also `NOT_FOUND`, whatever its
  messageType. Every 200 now carries one plain `message` sentence ("No member found in MMI for this id", ...),
  `NOT_FOUND` also carries `mmiMessage` (MMI's type, status, code, text), and a 502 `ERROR_MESSAGE` forwards MMI's
  code and text. The dev stub answers 404 with an ERROR-typed MEMBER_NOT_FOUND message for unknown ids, like MMI.
- Pending: align the two YAML lists with the MMI error-code list when it arrives.

## Feedback 11 (2026-10-04): MMI's HTTP contract, definitive

- MMI answers exactly four ways: 200 success, 404 member not found ("not ideal, but it is what it is"), 400 bad
  request, 500 internal error. Map on that basis.
- Applied, fixed in code (nothing configurable): 200 -> outcome from the records; 404 -> 200 `NOT_FOUND` whatever
  the body (a 404 whose body is not MMI's envelope is still NOT_FOUND, with a log warning naming the URL, since a
  wrong URL would look the same); 400 -> 400 `MMI_BAD_REQUEST` forwarding MMI's code and text; 500 -> 503
  `MMI_UNAVAILABLE` (`Retry-After: 10`) with MMI's text when present; any other status is a gateway or proxy, not
  MMI, and is answered 503 (5xx, 429, 408) or 502 saying so. The two not-found code lists from feedback 10 are
  removed again. The stub's 400400400 fault now answers 400 `MMI_BAD_REQUEST`.

## Feedback 12 (2026-10-04): every vendor got the 14-character id in PQA; Carelon confirmed 11; add Onyx

- Against the real MMI every vendor received the 14-character stored id. The owner confirms the formats: Carelon
  11, eviCore 11, Evolent 11, MHK 14, Optum 11 (two fields), and Onyx itself 11.
- Diagnosis: the formatter only reshaped a stored id matching exactly "9 digits, ASCII whitespace, 2 digits"; a
  stored value padded or separated differently (non-breaking spaces, tabs, trailing blanks, another digit count)
  fell back to "as stored" for every vendor. Applied: the formatter now normalises every kind of whitespace and
  reshapes any "digits, spaces, digits" value; when a stored id that is not plain letters and digits still cannot
  be reshaped, the log carries `marker=STORED_ID_NOT_RESHAPED` with the shape (digits as #, other characters by
  code point, never the digits). Onyx added as a vendor (COMPACT_11); Carelon's "to confirm" removed.
- If PQA still shows 14 characters for an 11-character vendor, the `mmi response` payload line or the
  `STORED_ID_NOT_RESHAPED` line shows the exact stored shape to map.

## Feedback 13 (2026-10-04): GitHub into STS instead of zips; default profile pqa

- Applied: README section 1 explains how to clone the branch straight from GitHub into STS (fine-grained token,
  EGit import, Buildship import of the sub-folder, pull and push) and the command-line equivalent.
- `spring.profiles.default` is now `pqa`: "Run As → Spring Boot App" with no profile talks to the PQA MMI; the
  in-process stub needs Profile = `dev`. `run.sh` / `run.cmd` / `run.bat` default to `pqa` too. The deployment rule
  stays: every deployment sets `SPRING_PROFILES_ACTIVE` explicitly (a pod without it would call the PQA MMI).

## Feedback 14 (2026-10-04): the TMP id starts with a letter (S); the service was dropping it

- Root cause of the 14-character answers in PQA: a TMP id is not 9 digits but 9 characters, a letter and 8 digits
  (`S12345678`), stored as `S12345678   01`. The formatter only reshaped an all-digit core, so every vendor got
  the stored 14 characters; the first attempt to widen it then dropped the `S`.
- Applied: the formatter reshapes "letters and digits, separator, 1-3 digit suffix" and copies the core and the
  suffix character for character (`S1234567801`, `S12345678   01`, `S12345678` + `01`). Anything else is passed
  on as stored. A realistic `S98765432   01` member is in the stub, with tests and Postman requests on both
  operations; the banner's sample id is `S12345678   01`; wording "9 digits" is now "9 characters".


## Feedback 15 (2026-10-04): the response is for "active or not"; keep the ids and the line of business, drop the rest

- The owner questioned `nextEffectiveDate`, `lastEndDate` "and those kind of things": the service is used to know whether
  the member is active or not, with the member id, perhaps the coverage period, and the line of business, which Onyx
  needs to route the transaction. Anything else only if it is absolutely needed; also check whether anything is
  missing for the near future.
- Field by field, kept: `outcome`, `message`, `memberId { received, stored, forVendor, forVendorParts }` /
  `vendorMemberIds[]`, `lineOfBusiness`, `dateOfService`, `dateOfServiceDefaulted` (the marker the owner asked for in
  feedback 5), `ignoredFields` on the vendor map, `coverage { active, span }` (the flag and the coverage period),
  `candidates[] { storedMemberId, lineOfBusiness, coverageActive }` on AMBIGUOUS (intake picks from it, feedback 6),
  `mmiRequestId` (support traces the MMI call with it), `mmiMessage` on NOT_FOUND (feedback 10).
- Removed from the 200 bodies: `coverage.lastEndDate`, `coverage.nextEffectiveDate`, `coverage.reason`, `company`,
  the echoed `vendor`, the `ambiguity { reason, hint }` block, `correlationId` (it is the `X-Correlation-Id` response
  header; error bodies keep it so a ticket can quote one body). Why an INACTIVE member is not covered and what to do
  about an AMBIGUOUS answer are now said in `message` in plain words ("coverage ended before <date>", "coverage not yet
  effective on <date>", "no coverage on <date> (gap between coverage periods)", "no coverage on record"; "Several
  members match this id; add patient.dateOfBirth or resend the member's full id including the suffix, or pick from
  candidates"). The reason codes stay in the log line.
- Judgment call recorded: `company` (THP / HPHC) is dropped because the owner named only the line of business for
  routing and the stored id itself shows the company (`HP` prefix). If Onyx turns out to route on company as well, it
  is one field to restore (`MemberRecord` still carries it for the log and the selection).
- Nothing missing for the near future: health and build info are exposed, Swagger documents both operations, a new
  vendor is one YAML block, the error envelope is unchanged. A batch operation, a vendor list endpoint and caching
  were considered and left out as not needed.
- Code: `Coverage` is `{ active, span }`, `CoverageDecision` and `CoverageEvaluator` no longer compute the nearest
  dates, `Ambiguity` is deleted, `Candidate` loses `company`, both response records are trimmed. 119 tests and the
  65-request Postman collection (438 assertions) are green; README, design and this log updated.

## Feedback 16 (2026-10-04): never name MMI towards the caller

- "MMI is internal, you don't need to surface that to the outside world. All the messaging within the service, don't
  say anything about MMI." Nothing a caller receives may name MMI: no field name, no error code, no message text, no
  URL, nothing in Swagger.
- Applied. Fields: `mmiRequestId` is now `traceId` (200 and error bodies), `mmiMessage` is `sourceMessage` (record
  `SourceMessage`, was `MmiNote`). Error codes: `MMI_UNAVAILABLE` → `MEMBER_LOOKUP_UNAVAILABLE`, `MMI_BAD_REQUEST` →
  `MEMBER_LOOKUP_REJECTED`, `MMI_ERROR` → `MEMBER_LOOKUP_ERROR`, `MMI_INVALID_RESPONSE` → `MEMBER_LOOKUP_INVALID_RESPONSE`;
  the detail codes (`HTTP_500`, `CONNECT_FAILED`, `READ_TIMEOUT`, `ERROR_MESSAGE`, ...) were already neutral. Messages:
  "No member found for this id"; "The member lookup rejected the request: <code> <text>"; "The member lookup reported an
  internal error: <code> <text>"; "The member lookup reported an error: <code> <text>"; "The member lookup could not be
  reached: <detail>"; "HTTP <code> from the member lookup, outside its contract (200, 400, 404, 500): a gateway or proxy
  answered", which no longer carries the MMI URL (the URL is in the log line instead). Swagger descriptions say "the
  plan's member records" / "the lookup". The code and text MMI sends are still forwarded (feedback 10), under the
  neutral wording.
- Not changed: the logs (internal) keep MMI, their markers (`MMI_404_WITHOUT_ENVELOPE`, ...) and the payload lines; the
  package, class and property names (`mmi.*`, `RestMmiClient`) stay, they are not visible to a caller.
- A test guard now asserts that no response body of any scenario contains "MMI" or "mmi".
- 119 tests and the 65-request Postman collection (437 assertions) are green; every response body replayed from the
  collection and the OpenAPI document were scanned and contain no "MMI".

## Request 17 (2026-10-05): the high-level diagram in draw.io format, for Lucid

- Asked for: the high-level diagram in a format Lucidchart imports (draw.io).
- Delivered: `interop-resolution-service-high-level.drawio`, one page of uncompressed draw.io XML with a PNG preview
  beside it. Plain shapes only (rounded rectangles, text, straight arrows) on one layer with absolute coordinates and
  no groups, which is the form Lucidchart's draw.io import handles best. It shows the request path EMR, Onyx, Azure
  API Management, the service, MMI and back, numbered 1 to 6; the five steps inside the service with the MMI client
  and the vendor table; the profiles; the UM vendors with their formats and Onyx's fallback; the request and the lean
  response; the ID shapes, masked; what is deliberately left out; and what Onyx does with each answer.
- The diagram names MMI: it is the internal architecture view, not a response (feedback 16 governs what callers
  receive). Member ids appear only masked.
- Source `design-src/high_level_drawio.py` (refuses to write on overlapping boxes or an id-shaped value); preview
  rendered with `design-src/render_drawio_preview.js` (mxGraph 4.2.2 in headless Chromium), which also checked that
  every label fits its box. The Lucidchart import itself could not be tried from the build container.

## Feedback 18 (2026-10-05): the diagram is for vendors

- The owner, on the first diagram: no details like the port number or the Java version; it has to be a diagram the
  owner can present to a vendor.
- Applied: the diagram is rebuilt as a one-page vendor overview, same file name. It shows the business flow only:
  the provider enters the member ID, Onyx sends it with the date of service to the Interop Resolution Service, the
  service looks the member up in Point32Health's member records, verifies coverage and returns the ID in the
  vendor's format, and Onyx sends the request on with that ID; a dashed line shows that the ID goes on as entered,
  as today, when verification is unavailable. A strip at the bottom follows one THP Medicare (TMP) / SCO member ID
  from the card to what Point32Health stores to what the vendor receives, masked; two callouts say why and what
  changes for the vendor.
- Removed: the port, Spring Boot and Java versions, endpoints, timeouts, profiles, Azure API Management, error codes,
  response field names, the outcome table, the internal network, and the name MMI (now "member records", per
  feedback 16). No other vendor is named and the formats are described generically, so the same file serves every
  vendor.
- The generator now refuses to write a label carrying an internal system name, a technical term or a vendor's name.
  The technical view is in the design document; the first diagram stays in the git history (commit 1e74848).

## Feedback 19 (2026-10-05): be clear

- The owner, on the vendor overview: what does this diagram mean; why is it not clear.
- Applied: the diagram now says one thing, written as its subtitle: before a prior-authorization request reaches the
  vendor, Point32Health checks the member ID and sends it in the format the vendor's system stores. Four boxes
  (provider, Onyx, the vendor, the Interop Resolution Service), three arrows (request with the member ID as typed,
  Onyx checks the member ID, request with the member ID in your format) and one example sentence.
- Removed: the step numbers, the two callouts, the coverage status, the member records box, the strip of masked ID
  patterns, the dashed fallback line and the footnote.
- Rule: vendor material says one thing in plain words, with no codes or ID patterns.

## Feedback 20 (2026-10-05): a 2027 date of service came back ACTIVE

- The owner, testing in PQA: with a date of service in 2027 the answer is still ACTIVE, although MMI has no coverage
  record for 2027. The service must compare the date of service with the coverage MMI returns and say ACTIVE only
  when a record covers that date.
- Cause, in `RequestValidator`: on `/vendor-map` a date of service that could not be used (not `yyyy-MM-dd`) or fell
  outside the window (10 years back, 366 days forward) was dropped, today was evaluated instead, and the answer came
  back ACTIVE with `dateOfServiceDefaulted: true` and `ignoredFields: ["dateOfService"]`, easy to read as "active for
  2027". A 2027-10-15 date is also beyond the 366-day window from today. On `/resolve` the same date was a 400
  `DATE_OF_SERVICE_OUT_OF_RANGE`. Neither compared the date with the coverage.
- Applied: a date of service that was sent is always the date judged, on both operations. An unusable value (not a
  real `yyyy-MM-dd` date, or more than 10 years back) is a 400 (`DATE_OF_SERVICE_INVALID` / `DATE_OF_SERVICE_OUT_OF_RANGE`)
  on `/vendor-map` too, never replaced by today; only a missing date defaults to today. The forward limit is gone: a
  future date is judged against the coverage on record (a record ending 12/31/2026 gives INACTIVE "coverage ended
  before 2027-10-15" for a 2027 date). The same for a sent date of birth: unusable means 400. `/vendor-map` stays
  lenient about the vendor and unknown properties. `ignoredFields` is removed from the response and
  `member-id.date-of-service.max-future-days` from the configuration.
- Verification: a new stub member with one calendar-year record (01/01/2026 to 12/31/2026) reproduces the case;
  tests on both operations assert ACTIVE for 2026-10-15, INACTIVE for 2027-10-15 with the date asked about in the
  response, 400 for `10/15/2027`, today only when no date is sent, and a far-future date judged rather than refused.
  121 tests; Postman 70 requests. README, design and handover updated.
- Left as it was, to confirm with the owner: a coverage record with no end date (blank or 12/31/9999) counts as
  covering every future date, so for such a member a 2027 date is still ACTIVE, and the response shows
  `coverage.span.endDate: null`. If the PQA member's record is open-ended and the owner still wants 2027 INACTIVE,
  the rule to add is a cap on how far an open-ended record counts (for example the end of the current calendar year).

## Feedback 21 (2026-10-05): a period of service; Onyx uses the vendor map

- The date of service can be a range, start date to end date; accommodate it. Onyx agreed to use the vendor-map
  operation: it gives every vendor's format at once, so a prior authorization with several codes going to several
  vendors needs one call. (The rename of the service that came with this message is feedback 22.)
- Applied, period of service: a new optional request field `dateOfServiceEnd` on both operations makes the request
  about every day from `dateOfService` to it. Validation like the start: a real `yyyy-MM-dd` date
  (`DATE_OF_SERVICE_END_INVALID`), not before the start (`DATE_OF_SERVICE_END_BEFORE_START`), never without a start
  (`DATE_OF_SERVICE_END_WITHOUT_START`). The response echoes `dateOfServiceEnd` when it was sent. Coverage: spans that
  overlap or touch (one ends 12/31, the next starts 01/01, as plan-year records do) are joined into continuous
  periods; ACTIVE only when one period covers every day asked about, and `coverage.span` is that period (so for a
  single date too, adjacent records now show as one period from the earliest start). When the period covers the
  first day but ends before the last, INACTIVE with the new reason `COVERAGE_ENDS_WITHIN_PERIOD`, the message
  "Member found; coverage active on <start> but ends <end>, before <last>" and the period in `coverage.span` so
  intake sees how far coverage goes. The record returned for a converted member is the one covering the first day.
  Messages for a period read "coverage active from <start> to <end>".
- Applied, Onyx's operation: README, design and handover now say Onyx calls `/vendor-map`; `/resolve` stays available
  for a caller that knows its one vendor. Nothing was removed; the owner may say if `/resolve` should go.
- Verification: a new stub member with adjacent 2025 and 2026 records; evaluator rows for merging, one-day holes and
  periods; scenarios on both operations (ACTIVE across the year boundary with the merged period, INACTIVE when the
  period outlasts coverage, the three 400s, a one-day period reads like a single date). 127 tests; Postman 74
  requests. README, design and handover updated.

## Feedback 22 (2026-10-05): rename the service for all things Interop

- The service may also serve payer ID and payer organization name lookups (nothing to build now); rename it so the
  name covers all of Onyx's interop lookups, and come up with a good name.
- Chosen: **Interop Resolution Service**. "Resolution" is what every planned capability does (a member ID resolved to
  its stored and vendor forms, a payer resolved to its ID, an ID to its organization name) and it keeps the word the
  owner has used all along; "Interop" says who it is for. Considered: Interop Crosswalk Service (a crosswalk is the
  usual word for ID mappings, but this service also verifies coverage), Interop Lookup Service and Interop Reference
  Service (too generic).
- Applied: folder `interop-resolution-service` (git history kept), root project, application name and jar
  `interop-resolution-service`, package `org.point32health.interop`, main class `InteropResolutionApplication`, Postman
  collection `InteropResolution.postman_collection.json`, docs folder `docs/interop-resolution-service`, vendor
  diagram `interop-resolution-service-high-level.drawio`, MMI client id placeholder `INTEROP` (trace ids `INTEROP-…`),
  run scripts' `INTEROP_JAVA_HOME`. API paths, contract and behaviour unchanged; a payer capability would add
  `/api/v1/payers/…`. The branch name and the design artifact URL are unchanged.
- For the owner's STS workspace: the project folder changed, so delete the old project from the workspace (not from
  disk), pull, and import `interop-resolution-service` (README section 1).
