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
