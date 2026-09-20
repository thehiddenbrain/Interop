# 08 – Questions to Confirm ASAP (Business, EDI Team, eCare, Onyx)

**Blocker** = the design cannot be finalised without it. **High** = affects phase-1 scope or
effort. **Med** = phase 2. Owner suggestions: *EDI* = HPHC/Point32Health EDI team
(`edi_team@point32health.org`, 800-708-4414 opt 1 → 3); *UM* = utilization management / eCare
business owners; *Arch* = Point32Health architecture; *Onyx* = vendor; *Compliance* = regulatory.

## 8.1 Access and licensing

| # | Question | Owner | Priority |
|---|---|---|---|
| Q1 | Provide the HPHC 278 companion guide v1.2 PDF (and any newer version) plus the Tufts 278 guide, the EDI Enrollment/Set-Up forms and the Trade Partner Agreement so the mapping tables can be completed line by line. | EDI | Blocker |
| Q2 | Does the organisation hold an X12 license (Glass) covering 005010X217, 005010X215 and 006020X316, and the X12/HL7 crosswalk? If not, who buys it and when? | Arch | Blocker |
| Q3 | Which PAS IG version will Onyx expose (2.0.1 / 2.1.0 / 2.2.1) and which US Core version? | Onyx | High |

## 8.2 eCare and the EDI channel

| # | Question | Owner | Priority |
|---|---|---|---|
| Q4 | What exactly are the "three options for submission of production 278s"? Which one should an *internal* bridge use: the provider-facing CAQH CORE real-time gateway, SFTP batch, or a direct internal interface to eCare that bypasses the EDI gateway? | EDI / Arch | Blocker |
| Q5 | Real-time channel details: endpoint URL(s) test/prod, CORE Phase II vs IV, SOAP vs MIME, certificate/mTLS, payload envelope, timeout, whether the 999 and the 278 response come back in the same HTTP response, error envelope on failure. | EDI | Blocker |
| Q6 | Envelope values for our submitter: ISA05/06, ISA07/08 (`HPHC0001`?), GS02/GS03 (`HPHC0001B`?), ISA15 test/prod, delimiters, character set (basic/extended), ISA14/TA1 election. | EDI | Blocker |
| Q7 | Provider roster: enrollment requires every requesting provider's name/NPI/payee/TIN to be registered under the submitter ID and to match HPHC records. For a PAS API open to all contracted providers, can the bridge be enrolled as a "universal" submitter, or must the roster be synchronised nightly from the provider directory? What happens (which AAA) when a provider is not on the roster? | EDI | Blocker |
| Q8 | Which UM01 request categories does eCare accept over 278 (AR, HS, SC; anything else)? Which UM02 certification types (I, R, S, 4, 3, 1, 2)? Which UM03 service type codes (full list or subset)? UM04 qualifier expectations (A vs B)? UM06 values and whether it is required? UM09 release of information expectation? | EDI / UM | Blocker |
| Q9 | Which segments are *required* by eCare beyond the TR3 (e.g. HI diagnosis always, DTP*472 always, HSD for visits, PRV taxonomy, N3/N4 for service location, PER)? Which are ignored? Maximum 2000F service lines per request? | EDI | Blocker |
| Q10 | How does eCare return the authorization number: HCR02, REF*BB, both? Format (length, alphanumeric, prefix by LOB), and is the same number used for referrals and authorizations? Does it return REF*NT on pends? DTP*AAH certification period? HSD authorized quantity? MSG text (and is denial text in MSG)? HCR03 decision reason codes (which ones)? | EDI / UM | Blocker |
| Q11 | Which HCR01 codes can eCare emit (guide says A1, A3, A4, A6, C – confirm A2, CT, NA never occur) and under what business circumstances each is used? What does eCare return for "no authorization required"? | UM | High |
| Q12 | Which AAA reject reason codes does eCare emit, at which levels, and with which follow-up action codes? Does eCare send MSG with an AAA? | EDI | High |
| Q13 | Pended decisions: does eCare push an unsolicited 278 response when a pended case closes (real-time callback? SFTP file? schedule?), or must we poll? Is 278 inquiry (005010X215) supported? If neither, can eCare expose an event feed or database view of case status changes? | EDI / Arch | Blocker |
| Q14 | Updates and cancels: exact 278 shape of the Edit (Appendix E) and Cancel (Appendix F) samples; which fields are immutable ("must cancel and submit a new initial request"); does a cancel need service lines; can a pended request be updated; is BHT02 = 01 used for cancels or only UM02 = 3? | EDI / UM | High |
| Q15 | Attachments: does eCare accept 275 (which version), or a document intake keyed by authorization number, or neither? Size limits. Does eCare ever *request* documents via PWK01 / LOINC codes in the 278 response? | EDI / UM | High |
| Q16 | Idempotency: how does eCare detect a duplicate 278 (same BHT03? same TRN? same member/service/date?), and what does it return for a duplicate? | EDI | High |
| Q17 | Batch: is batch 278 supported for production, cut-off times, file naming, where responses are dropped, and can batch be used as a resubmission/fallback path? | EDI | Med |
| Q18 | 999/TA1 behaviour on 278 specifically: always 999? in-band on real-time? TA1 election? Where do batch acknowledgments land? | EDI | High |
| Q19 | Test environment: HPHC EDI test endpoint, test member IDs/provider IDs, certification steps and sign-off criteria for a 278 trading partner, queue time for certification. | EDI | Blocker |
| Q20 | Does eCare also serve other lines of business (Medicare Advantage via Tufts uses MHK; NH exchange QHP; ASO)? Which coverages will the Onyx PAS endpoint route to eCare vs elsewhere (PAS conf-2: one endpoint per coverage)? | Arch | High |

## 8.3 Business rules

| # | Question | Owner | Priority |
|---|---|---|---|
| Q21 | Notification vs referral vs prior authorization: how is each represented in eCare's 278 (request category, service types, flags) and what comes back for a notification (A1? NA?)? Can the PAS channel be used for notifications at all? | UM | Blocker |
| Q22 | Authorization validity period rules by service type (search found an unverified "30 days from final determination"; standing referrals 364 days) – what populates DTP*AAH? | UM | High |
| Q23 | Retro-authorization window and conditions; how a retro request is flagged on the 278 (dates in the past + UM02?). | UM | Med |
| Q24 | Urgent/expedited: which 278 element eCare reads for urgency (UM06 U/03?), and the internal SLA (HPHC states review within 2 business days after receipt of medical information; MA/CT 24-hour urgent rules; CMS 72 h/7 days for the NH QHP block). | UM / Compliance | High |
| Q25 | Which services must be routed to delegated vendors (Evolent, Carelon, EviCore, BH) and how should the bridge answer a PAS request for them (CT + contact info? OperationOutcome?). Provide the current PA/notification/referral requirement lists per product (HMO/POS/PPO/Open Access) so CRD and the bridge agree. | UM | High |
| Q26 | Denial reasons: CMS-0057-F requires a specific denial reason on every denial regardless of channel. Does eCare emit HCR03 codes, MSG text, or neither over 278? If neither, where can the bridge get it? | UM / Compliance | High |
| Q27 | Peer-to-peer / reconsideration / appeal information in the response: should the ClaimResponse carry P2P phone numbers and deadlines (processNote)? | UM | Med |
| Q28 | Immutable data policy for edits (Q14) – the business decision: reject the PAS update and instruct cancel + new, or let the bridge perform cancel + new initial automatically? | UM | High |
| Q29 | `wasHumanReviewedFlag` (PAS reviewer extension) and reviewer NPI/specialty – can eCare supply them (MA reconsideration rules require clinical peer reviewers)? | UM / Compliance | Med |
| Q30 | Ancillary/supporting services adjudication change effective 1 Sept 2026 – does it require additional service lines on the authorization request? | UM | Med |

## 8.4 Onyx

| # | Question | Owner | Priority |
|---|---|---|---|
| Q31 | Integration pattern for the "backend adapter": synchronous HTTP call from Onyx to the bridge during `$submit` (with what timeout), or event/queue with Onyx returning pended immediately? Can Onyx hold the connection ≥ 12 s? | Onyx | Blocker |
| Q32 | How does the bridge push a later (unsolicited) ClaimResponse into Onyx so that Onyx fires the PAS Subscription notification (rest-hook, full-resource)? Is there an API to update the current ClaimResponse for a Claim? | Onyx | Blocker |
| Q33 | `$inquire`: does Onyx answer from its own store or delegate to the bridge? Both need the "current response for a superseded reference number" rule. | Onyx | High |
| Q34 | Does Onyx (or its Availity partnership) already include any X12 278 translation or EDI connectivity we could reuse rather than build? What did Availity actually decline? | Onyx / Availity | High |
| Q35 | Where does Onyx persist the full PAS bundle and DocumentReference payloads, and can eCare reviewers access them (PAS requires the entire bundle to be available to the payer)? | Onyx / Arch | High |
| Q36 | CRD/DTR: are they in scope with Onyx, and will DTR questionnaires be defined by the UM team so that documentation arrives with the initial request (reduces pends)? | Onyx / UM | Med |
| Q37 | Which OperationOutcome/HTTP behaviours does Onyx expect from the bridge for validation errors vs transient failures (4xx vs 5xx per PAS)? | Onyx | High |

## 8.5 Compliance and regulatory

| # | Question | Owner | Priority |
|---|---|---|---|
| Q38 | Confirm the LOB scope of CMS-0057-F for HPHC commercial (NH FFE QHP only?) and the business driver for the rest. | Compliance | High |
| Q39 | Is the CMS HIPAA enforcement discretion (28 Feb 2024 statement) still in effect, and does Point32Health intend FHIR-only PAS with providers (no provider-facing 278) – which makes the bridge purely internal? | Compliance | High |
| Q40 | MA 211 CMR 52.00 (effective 5 June 2026), Maine 24-A §4304, NH RSA 420-J, CT §38a-472g timing rules – confirm which apply to which members and encode them in the SLA engine. | Compliance | Med |
| Q41 | Is Point32Health participating in the MHDC/ZeOmega "NEHEN FHIR" statewide PA network? If so, is the PAS front door NEHEN's rather than Onyx's? | Arch | High |
| Q42 | Retention period for raw X12 and PAS bundles (audit), and PHI logging rules. | Compliance | Med |
