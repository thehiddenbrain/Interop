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
- Performance: MMI averages about 16 ms over millions of calls a month (Elasticsearch backend). This
  service must be fast and lean too; expect hundreds of thousands of calls, not millions.
- Resolution rules: if a DOB is supplied and MMI returns several records, pick the matching one. If no
  DOB and the member is non-TMP with a 9-digit ID that returns several records, answer AMBIGUOUS. TMP
  always returns one record.
- Deliverables now: the code (Gradle, Spring Boot, importable straight into STS), unit tests, all
  scenarios tested, a local run against a stub MMI (port 9090 suggested), and a Postman collection
  against localhost. The owner will point it at the PQA MMI first.

## Consolidated decisions applied in v0.2 (code + revised design)
1. Response fields: `outcome` (ACTIVE | INACTIVE | NOT_FOUND | AMBIGUOUS), `memberId.received`,
   `memberId.searched`, `memberId.stored`, `memberId.vendor` (+ `memberId.vendorParts` for split
   formats), `vendor`, `company`, `lineOfBusiness`, `coverage.active` (boolean flag),
   `coverage.dateOfService`, `coverage.dateOfServiceDefaulted`, `coverage.reason`, `coverage.span`,
   `coverage.lastEndDate` / `nextEffectiveDate`, `candidates[]` on AMBIGUOUS, `mmiRequestId`.
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
