# Feedback log: Member ID Resolution Service design

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

