# 09 – Test and Certification Strategy

## 9.1 Test pyramid for the bridge

| Layer | What | Tooling |
|---|---|---|
| Unit – terminology | Every translation table (ICD-10 formatting, CPT/HCPCS → HC, POS/TOB → UM04, units → HSD, service type validation, text folding/truncation, NPI Luhn) | JUnit (Java, as in this repo) with table-driven tests |
| Unit – writer | PAS Bundle → 278: golden-file tests per scenario (referral, surgical day care, home care, admission, edit, cancel, mixed items rejected, >12 diagnoses rejected, dependent placement) | Golden EDI files derived from the HPHC guide appendices A–F and the X12 X217 examples |
| Unit – reader | 278 response → ClaimResponse: A1, A2, A3 (+HCR03/MSG), A4 (+REF*NT, BHT06 = 19), A6 (item + addItem), C, CT, NA, AAA at each level (2000A 42, 2000C 72/75, 2000F 86), missing 2000F lines, extra lines, MSG, PWK/HI attachment requests | Golden responses |
| Schema validation | Every generated 278 validated against a 005010X217 schema (SNIP levels 1–4) before send; every received response validated | X12 library with X217 schema (EdiFabric, Stedi, Edifecs, imsweb parser + custom rules) |
| FHIR conformance | Request/response bundles validated against PAS 2.2.1 profiles | HL7 FHIR validator with `hl7.fhir.us.davinci-pas#2.2.1`; Inferno Da Vinci PAS test kit (client and server suites) |
| Integration – channel | Envelope generation, control numbers, TA1/999 handling, timeouts, retries, idempotency, in-band vs out-of-band acknowledgments | HPHC EDI test environment (ISA15 = T); mock eCare for CI |
| End to end | EHR sandbox (Epic/Cerner PAS client or Inferno client) → Onyx test tenant → bridge → HPHC test → back; pended flow with subscription notification; inquiry; update; cancel | Connectathon-style scripts |
| Non-functional | Latency (p95 ≤ 12 s at bridge, ≤ 15 s end to end), throughput at peak, failover when eCare is down, PHI redaction in logs | Load tool + chaos tests |

## 9.2 Fixture set (to be built from the companion guide)

| Fixture | Source | Purpose |
|---|---|---|
| `x12-example-1a-referral-request.edi` (in `samples/`) | X12 public Example 1a via EdiFabric sample | PAS `ReferralAuthorizationBundleExample` ↔ 278 baseline |
| HPHC Appendix A – Specialist Referral request + response | CG p. 25 | referral path |
| HPHC Appendix B – Surgical Day Care | CG p. 26 | outpatient surgery, POS 22/24 |
| HPHC Appendix C – Home Care | CG p. 27 | SV2 revenue code, CR6 |
| HPHC Appendix D – Admission | CG p. 29 | AR, DTP*435/096, CL1, UM06 |
| HPHC Appendix E – Edit Speech Therapy | CG | UM02 = S, REF*BB, A6 |
| HPHC Appendix F – Cancel Ambulatory Surgery | CG | UM02 = 3, C |
| PAS IG examples (Homecare, HomecareUpdate, MedicalServices, Surgical, Inquiry, Rejection, Error, Pending) | `davinci-pas` repo `input/fsh/Examples.fsh` | FHIR-side conformance |
| Synthetic negative cases | ours | AAA at each level, 999 reject, TA1 reject, timeout, duplicate submission |

## 9.3 HPHC EDI certification checklist (to confirm with the EDI team)

1. EDI Enrollment Form + Trade Partner Agreement submitted; submitter ID (ISA06/GS02) assigned;
   provider roster (NPI/TIN/payee) loaded – or universal submitter agreed.
2. Connectivity test (CORE SOAP/MIME) with test envelope; TA1/999 round trip.
3. Transaction tests: at least one of each Appendix A–F scenario accepted with the expected HCR;
   negative tests (invalid member, provider not on file) return the documented AAA.
4. Real-time SLA measured (< 10 s).
5. Production cut-over date, monitoring contacts, escalation path.
6. Testing support hours: Mon–Fri 8:30 AM – 5:00 PM ET.

## 9.4 Acceptance criteria for phase 1 go-live

* 100 % of PAS profile-valid initial requests for supported categories produce a schema-valid 278
  accepted (999 A) by HPHC test.
* Every eCare response type observed in test (A1/A3/A4/A6/C + AAA set) maps to a
  PAS-profile-valid ClaimResponse with correct item sequences and authorization numbers.
* Pended → final delivery demonstrated end to end via subscription notification within the agreed
  window.
* Inferno PAS server suite passes for the supported scope; documented exceptions for unsupported
  segments (CRC ambulance etc.).
* Operational runbook: control-number recovery, replay of a submission, reconciliation report of
  open pends, PHI-safe logging verified.
