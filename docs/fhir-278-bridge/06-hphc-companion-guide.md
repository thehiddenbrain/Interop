# 06 – What the HPHC 278 Companion Guide Tells Us (and what it does not yet)

**Document**: *Harvard Pilgrim Health Care Companion Guide – 278 Request for Review and Response,
ASC X12N 005010X217E1, version 1.2* (indexed title begins "HARVARD PILGRIM HEALTH CARE COMPANION
GUIDE …"; last updated September 2024 per search index).
URL: <https://www.point32health.org/documents/278-req-x217-5010-companion-guidev12-hphcedi>

**Access status**: the network policy of the environment used to prepare this package blocks
`point32health.org` and `harvardpilgrim.org` outright (HTTP CONNECT 403), as well as `hl7.org`,
`x12.org`, `caqh.org`, the Wayback Machine and every other non-GitHub host. The content below was
recovered through search-engine index snippets of the PDF (28 targeted queries), which return
synthesized summaries and only a handful of verbatim strings. Each fact is tagged:

* **✔ verbatim / quoted** – the search engine quoted the guide's own text.
* **✔ attributed** – summary attributed to the 278 guide's URL.
* **⚠ cross-document** – the same result set included sibling Point32Health PDFs (Tufts 278 guide,
  HPHC 270/271, 837P, HPHConnect user guides, provider manual); the fact is probably right for the
  278 guide but may have been blended.
* **✖ not recovered** – must be read from the PDF.

**Action**: obtain the PDF (anyone on the HPHC network can download it) and drop it in
`docs/fhir-278-bridge/reference/` so the loop/segment table on pp. 20–24 and the six sample pairs
on pp. 25+ can be transcribed into `04-mapping-request.md` / `05-mapping-response.md`. Section 6.9
lists exactly which pages to read first.

## 6.1 Document identity and structure

| Item | Finding | Tag |
|---|---|---|
| Standard referenced | ASC X12N 278 version **005010X217E1** (with the errata) | ✔ attributed |
| Scope | Both directions: "278 Request for Review" table (p. 20) and "278 Response" table (p. 24); no separate response guide appears to exist | ✔ attributed |
| Purpose boilerplate | "The Companion Guide is intended to convey information that is within the framework of the ASC X12N Technical Report Type 3 adopted for use under HIPAA … not intended to convey information that in any way exceeds the requirements or usages of data expressed in the TR3 … clarify when conditional data elements and segments must be used and identify codes and data elements that do not apply to Harvard Pilgrim." | ✔ quoted |
| Table of contents | Section 1 Introduction (p. 4); Overview (p. 7); HPHC Business Rules and Limitations: Envelope Identifiers, Simple File Structure, Extended Character Set, **Member Identification Numbers (p. 9)**, Third Party Authorization Services, Products Not Supported by Harvard Pilgrim Health Care; **Tables (p. 20)**: 278 Request for Review (p. 20), 278 Response (p. 24); **Appendices (p. 25)**: A Sample 278 Specialist Referral and Response (p. 25), B Sample 278 Surgical Day Care and Response (p. 26), C Sample 278 Home Care and Response (p. 27), D Sample 278 Admission and Response (p. 29), E Sample 278 Edit – Speech Therapy and Response, F Sample 278 Cancel – Ambulatory Surgery and Response; Revision History | ✔ attributed |
| A "999 – Acknowledgment for Health Care Insurance" heading exists | | ⚠ cross-document |

## 6.2 Connectivity, enrollment and testing

| Item | Finding | Tag |
|---|---|---|
| Submission options | "Harvard Pilgrim Health Care provides three options for submission of production 278s." "Sending these transactions directly eliminates the need for an intermediary and is offered to providers at no cost per transaction." The three options are **not enumerated** in any snippet. HPHC e-channels named across the guide family: HPHConnect, NEHEN / NEHENNet, EDI-Direct, CAQH CORE Phase II connectivity (SOAP and MIME), CORE Phase IV, SFTP. Best reading: (1) CAQH CORE real-time (SOAP/MIME over HTTPS), (2) SFTP batch, (3) HPHConnect – **inference, confirm**. | ✔ quoted / ⚠ |
| Real-time behaviour | "Real Time 278 transactions have a single ST/SE loop." "Typical turnaround time is under 10 seconds during which the portal connection is held open." "There should be one request per ST/SE transaction." | ✔ quoted |
| Transport | "The technical standards and versions for HTTP MIME are specified for HPHC over HTTPs." SOAP/WSDL specifics not recovered. | ✔ attributed |
| Testing support | Monday–Friday 8:30 AM – 5:00 PM EST | ✔ attributed |
| Enrollment | "Harvard Pilgrim requires a list of all individual provider name(s), provider NPI(s), payee number(s) and tax ID(s) for which you will be submitting referral and authorization inquiries & responses." "The provider's name, NPI and TIN must match current Harvard Pilgrim provider information on record." "A new trade partner submitter ID is assigned during the enrollment process." EDI Enrollment Form (rev. 12/2024) asks for the ISA06 submitter ID; an EDI Trade Partner Agreement must be signed. | ✔ quoted / ✔ attributed |
| File naming | includes `[ISA06]_[GS02]` | ⚠ cross-document |
| Format | "All files submitted must be in the ANSI ASC X12N format, version 5010." | ✔ attributed |
| Acknowledgments | TA1 is elective ("If submitters choose not to receive a TA1, the 999 will be the only electronic notification that HPHC has accepted or rejected a file"); 999 and TA1 are also returned on CAQH SOAP/MIME channels. | ⚠ (from 270/271 guide) |
| Contacts | EDI team `edi_team@point32health.org` (also `EDI_Operations@point32health.org`), 800-708-4414 option 1 then option 3, Mon–Fri 8:00/8:30 AM – 5:00 PM ET. Payer ID **04271**. | ✔ attributed |

**Implication for the bridge**: eCare is reached through HPHC's own EDI gateway (the same one
providers use), so the bridge enrolls as a trading partner (submitter ID, TPA, provider roster) unless
Point32Health can offer an internal path directly into eCare. This must be decided in phase 0 –
it changes envelope values, the provider-roster requirement (every requesting provider's NPI/TIN
must be on file under our submitter ID, which is impossible to pre-enumerate for a PAS API open to
all providers) and the "no cost per transaction" statement.

## 6.3 Envelope identifiers

| Element | Non-NEHEN channels | NEHEN / NEHENNet | Tag |
|---|---|---|---|
| ISA08 Interchange Receiver ID | `HPHC0001` | `NEHEN003` | ⚠ cross-document (consistent across HPHC guides) |
| GS03 Application Receiver ID | `HPHC0001B` | `NEHEN003` | ⚠ cross-document |
| Other | "The Interchange Receiver and Application Receiver IDs depend upon which e-Channel is used." "The ISA08 and GS03 values are taken from the header of the transmitted file." | ✔ quoted |
| ISA05/07 qualifiers, ISA11/16 delimiters, ISA14, ISA15, GS02 conventions | | ✖ |

## 6.4 Business rules and limitations recovered

| Rule | Tag |
|---|---|
| Member IDs "should not include hyphens or spaces". | ✔ attributed |
| HPHC "recommends the use of the 270 Benefit Inquiry transaction for trading partners that want to verify member or subscriber IDs" – the 278 is not an eligibility probe. | ✔ attributed |
| "Some data cannot be modified on any transactions. To change certain items, you must submit a Cancel Request and submit a new Initial Request." | ⚠ (also in HPHConnect guide) |
| Edit is possible on approved and modified transactions; Cancel on pended, approved and modified transactions. | ⚠ (HPHConnect UI wording; likely mirrors eCare rules) |
| SV2 is used for institutional service lines; "a Service Line Revenue Code is required when Home Care services are identified by Revenue Code per provider contract." | ✔ attributed |
| SV3 covers dental service. | ✔ attributed |
| For oral surgery procedures, CPT or HCPCS codes should be used in SV1. | ✔ attributed |
| "Third Party Authorization Services" and "Products Not Supported" sections exist (contents not recovered). Expect the former to say that delegated services (Evolent imaging/cardiac/MSK/sleep, Carelon genetic testing, behavioral health carve-outs) must not be sent to HPHC via 278. | ✖ (inference) |
| Sample NM1 usage: `NM1*X3` = Harvard Pilgrim (UMO), `NM1*1P` = requester, `NM1*SJ` = servicing entity / service location. | ✔ attributed |
| Sample DTP usage in the Admission sample: `DTP*435*D8*20151018` (admission), `DTP*096*D8*20151025` (discharge). | ✔ quoted |

## 6.5 Response behaviour recovered

| Finding | Tag |
|---|---|
| "In the 278 Response for the 2000E loop (Health Care Services Review), Harvard Pilgrim returns Action Codes **A1, A3, A4, A6, and C**." | ✔ attributed |
| "If there are no errors, the returned status will be described by one of the codes below." One listed status: "**Service approved, but with changes**" (= A6). | ✔ quoted |
| "Error messages will be returned indicating corrections required." | ✔ attributed |
| Rejected requests "must be corrected by the provider and resubmitted electronically, as these requests will not be entered into the system"; "More than one AAA segment code may be received"; AAA details in an appendix. | ⚠ **high risk** – this wording is from the Tufts 278 guide result set; HPHC's Appendix B is a sample, not an AAA table |
| Turnaround: under 10 seconds real-time. No pended-to-final SLA in the guide. | ✔ quoted |

## 6.6 Use cases covered by the guide's samples (the de-facto supported scope)

| Appendix | Scenario | Likely X12 shape | Maps from PAS |
|---|---|---|---|
| A | Specialist referral | UM01 = SC, UM02 = I, UM03 = 3 (consultation) or specialty service type, HSD visits | Claim with `requestType = SC`, ServiceRequest referral, `item.category` |
| B | Surgical day care (ambulatory surgery) | UM01 = HS, UM03 = 2 (surgical) or 53, UM04 POS 22/24, SV1 CPT | Claim professional/outpatient |
| C | Home care | UM01 = HS, UM03 = 42, CR6, SV2 with revenue code | Claim with `homeHealthCareInformation`, `item.revenue` |
| D | Admission (inpatient) | UM01 = AR, UM06, DTP*435/096, CL1, HI | Claim institutional with `encounter`, `supportingInfo[AdmissionDates]` |
| E | Edit – speech therapy | UM02 = S (or 4) + REF*BB | Claim Update with `infoChanged = changed` |
| F | Cancel – ambulatory surgery | UM02 = 3 + REF*BB (possibly BHT02 = 01) | Claim Update with `certificationType = 3` |

Not represented in the samples: notification-only events (HPHC's "notification" business
process), behavioral health, DME, medications under the medical benefit, inquiry (X215),
attachments (275). Each is an open question.

## 6.7 What could not be recovered (must be read from the PDF)

* The full request table pp. 20–23: per-segment usage (required / situational / not used),
  accepted UM01/UM02/UM03/UM04/UM05/UM06/UM09 values, HI qualifiers, DTP qualifiers, HSD usage,
  PWK/MSG usage, REF qualifiers, NM1 qualifiers (2010B, 2010EA, 2010F), PRV, N3/N4, PER, TRN rules,
  maximum service lines, member ID format details.
* The response table p. 24: which segments HPHC returns (HCR02 vs REF*BB, REF*NT, DTP*AAH, HSD,
  MSG, 2010EA/2010F), decision reason codes, AAA codes.
* Sections: Envelope Identifiers, Simple File Structure, Extended Character Set, Third Party
  Authorization Services, Products Not Supported, 999/TA1, the exact "three options".
* The six sample pairs in full (only two DTP segments recovered).
* Revision history 1.0 → 1.2.
* Whether HPHC supports 278 inquiry (X215), 278 notification (X216), 275 attachments, batch 278
  cut-offs and response retrieval.

## 6.8 Sibling document to compare

Tufts Health Plan "278 Request for Review and Response" companion guide (Point32Health, 005010,
v1.2, April 2016): <https://www.point32health.org/documents/278-request-review-and-response-sta>.
It appears to include an AAA-code appendix and its UM system is MHK, not eCare – useful as a
comparison but not authoritative for the HPHC commercial line.

## 6.9 Reading list for whoever has the PDF (priority order)

1. pp. 20–24 – the two tables (feed straight into the mapping documents).
2. pp. 25–end – six sample pairs (become bridge test fixtures) and revision history.
3. pp. 7–19 – Overview, Envelope Identifiers, Simple File Structure, Extended Character Set,
   Member Identification Numbers, Third Party Authorization Services, Products Not Supported,
   connectivity and acknowledgment sections.
