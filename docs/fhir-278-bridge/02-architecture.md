# 02 – Bridge Architecture and Design Considerations

## 2.1 Where the bridge sits

```
                 FHIR (Da Vinci PAS)                       X12 (HIPAA 005010X217 / X215 / 275)
 EHR / Provider  ───────────────────────►  Onyx PAS API  ───────────────────────►  Bridge  ───────►  eCare (UM system)
 (Epic, Cerner,  ◄───────────────────────  (Claim/$submit, ◄───────────────────────  (this  ◄───────  278 Response
  athena, …)       ClaimResponse Bundle     $inquire,           278 Response          design)         999 / TA1
                                            Subscription)
```

* **Onyx** is the payer-side PAS server: it terminates the provider-facing FHIR API (`Claim/$submit`,
  `Claim/$inquire`, `Subscription` rest-hook notifications, CRD/DTR companions), authenticates the
  EHR, validates the PAS bundle against the IG profiles and persists the FHIR side of the record.
* **The bridge** is a stateless-as-possible translation and orchestration layer that Onyx calls (or
  that subscribes to Onyx events). It turns a PAS request Bundle into one X12 278 request (plus 0..n
  275 attachment transactions), sends it to eCare over the EDI channel described in the HPHC 278
  companion guide, receives the 278 response (and 999/TA1 acknowledgments), and turns that into a PAS
  response Bundle which Onyx returns to the EHR. It also carries pended-decision updates back
  (unsolicited 278 responses or polling via 278 inquiry) so that Onyx can fire subscription
  notifications.
* **eCare** is the utilization-management (UM) system of record. It only speaks X12 278. The
  authoritative constraints on what eCare accepts are the HPHC 278 companion guide (see
  `06-hphc-companion-guide.md`).

The PAS IG explicitly models this: "The system on which the operation is invoked will convert the
Bundle into an ASC X12N 278 and 0..* additional unsolicited 275 transactions and execute them all
against the target payer system. It will then take the resulting 278 response and convert it into a
response FHIR Bundle" (PAS specification, §7.2). Our bridge is the "intermediary" actor of the IG;
Onyx + bridge together form the "Payer system".

## 2.2 Regulatory framing (why the 278 is still needed at all)

* HIPAA (45 CFR 162.1302) names ASC X12N/005010X217 as the referral certification and authorization
  standard. Inside a covered entity (payer), the 278 is *not* required between two internal systems,
  but eCare only accepts 278, so the bridge exists for **technical** reasons, not (only) HIPAA.
* CMS-0057-F (Interoperability and Prior Authorization Final Rule) requires impacted payers (MA,
  Medicaid/CHIP, QHP issuers on FFEs) to expose a Da Vinci PAS-based Prior Authorization API by
  **January 1, 2027**. Commercial fully-insured group business is not directly in scope of CMS-0057-F,
  but the business decision here is to run the commercial line through the same PAS front door
  (Onyx). CMS's 2024 HIPAA enforcement discretion says a payer that uses the FHIR PAS API will not be
  penalised for not also using the X12 278 with the provider. That discretion does not remove eCare's
  need for a 278: it just means the bridge is an internal integration, and Onyx does not have to
  expose a 278 to providers. (See `10-sources.md` for citations recovered from research.)
* The PAS IG conformance page says the intermediary "SHALL convert the FHIR bundle to and from an X12
  278 (and optionally to an X12 275) if necessary" and that the intermediary MAY exchange the 278 with
  the payer "as long as the PA request and response are in an X12 278 format at some time between the
  exchange with the EHR and the payer" (PAS use cases §3, notes 2 and 4).

## 2.3 Design principles

1. **The companion guide is the contract, not the TR3.** The TR3 (X217) defines what *may* be sent;
   eCare's companion guide defines what *will be accepted and what will come back*. Every mapping
   row below is annotated with "CG" where the HPHC companion guide is expected to constrain it.
2. **Lossless on the FHIR side.** Everything in the PAS bundle that has no X12 home is retained by
   Onyx (the IG requires the entire PAS bundle to be made available to the payer, spec-26). The
   bridge must persist a correlation record `{bundleId, claimId, TRN(s), ISA13, GS06, ST02, BHT03}`
   so a 278 response, a 999 rejection or a later unsolicited update can be re-associated with the
   original FHIR Claim and item sequences.
3. **Item sequence is the join key.** PAS: "Each item returned on the PAS ClaimResponse SHALL echo
   the same item.sequence as that same item had on the Claim" (spec-34). X12 has no item sequence:
   we carry it in the 2000F TRN02 (service-level trace number) so eCare echoes it back, and we keep
   a fallback map (TRN02 → item.sequence) in the correlation record.
4. **One PAS Claim → one 278 ST/SE (one patient event, N service lines).** All Claim.items go to
   Loop 2000F under a single Loop 2000E (PAS use cases §3.3.1). Do not split items across 2000E
   loops unless the companion guide forces it (e.g. inpatient admission vs. outpatient services).
5. **Synchronous first, asynchronous by design.** PAS wants the `$submit` round trip in ≤15 s
   (spec-7). HPHC advertises real-time 278 turnaround under 10 s. The bridge must therefore run the
   real-time channel synchronously, but must also handle: (a) eCare returning A4 Pending, (b) eCare
   timing out (return a pended ClaimResponse and reconcile later), (c) unsolicited 278 responses /
   batch response files arriving hours or days later, and (d) 999 rejections arriving after the HTTP
   call already returned. Every one of these ends in a Subscription notification from Onyx.
6. **Fail closed with an OperationOutcome, never with a fake ClaimResponse.** If the bridge cannot
   build a valid 278 (missing member ID, unsupported code system, item count > CG limit) it returns
   an OperationOutcome (HTTP 4xx) with the FHIRPath of the offending element, per PAS §7.2.5.
   Business rejections from eCare (AAA segments) become `ClaimResponse.error` entries with the X12
   error element (extension-errorElement) and follow-up action, not HTTP errors.
7. **Terminology translation is a first-class component**, not an afterthought: ICD-10-CM → HI
   qualifiers; CPT/HCPCS → SV1/SV2 with `HC` qualifier; POS/type-of-bill → UM04; X12 service type
   codes; NUBC revenue codes; units (UN/DA/MJ/VS…) → HSD01/SV103; free text → MSG (264 chars).
8. **Everything is idempotent and replayable.** Store the raw 278 request/response and 999/TA1
   text, plus the PAS bundles, with hashes. Re-sending the same PAS bundle must not create a
   duplicate authorization in eCare (use the same BHT03/TRN02 on retry; ask eCare how it
   de-duplicates).
9. **X12 IP is licensed.** The code lists (service type 1365, action codes 306, reject reasons 901,
   follow-up 889, decision reasons 886, PWK01 755, etc.) and the TR3 are X12 copyrighted. The
   bridge team needs an X12 license (TR3 X217 + X215, and ideally the X12/HL7 "278 to FHIR" mapping
   product) before coding the code tables. The PAS IG binds to `https://valueset.x12.org/...` URLs
   that only resolve for X12 members.

## 2.4 Logical components of the bridge

| Component | Responsibility | Notes |
|---|---|---|
| **Intake adapter** | Receives the PAS request Bundle from Onyx (REST callback, queue, or Onyx "backend adapter" hook). Validates profile conformance already done by Onyx; re-validates the invariants the bridge depends on (Claim first, item.category present, member identifier present). | Confirm with Onyx which integration pattern their PAS product offers (synchronous HTTP hand-off vs. event). |
| **Normalizer** | Resolves all Bundle references into an in-memory graph (Claim → Patient, Coverage, Subscriber, Insurer, Requestor/PractitionerRole, careTeam providers, ServiceRequest/DeviceRequest/MedicationRequest/NutritionOrder, Encounter, DocumentReference, QuestionnaireResponse). Deduplicates providers. | PAS spec-14: each resource appears at most once; spec-15: one instance per real-world entity. |
| **Terminology service** | Code system translations and length/format rules (see `04-mapping-request.md` §4.9). | Back with a versioned mapping table; log every miss. |
| **278 writer** | Builds ISA/GS/ST/BHT + HL hierarchy + loops 2000A–2010F; assigns control numbers; delimiters per CG. | Use an X12 library that validates against a 005010X217 schema before send. |
| **275 writer (phase 2)** | Wraps DocumentReference / QuestionnaireResponse content in 275 (006020X316 per PAS, or the version eCare supports) with PWK06 attachment control numbers matching the 278 PWK. | Only if eCare accepts 275; otherwise route documents to a document store and reference by control number. |
| **EDI channel client** | Real-time HTTPS/SOAP/MIME (CAQH CORE-style) or SFTP batch per CG; manages ISA13/GS06/ST02 control numbers per trading partner; captures TA1/999. | Endpoint, credentials, test/prod ISA15 flag, sender/receiver IDs all come from the HPHC EDI enrollment. |
| **278 reader** | Parses 278 response into a typed model; validates against X217 response schema; extracts AAA/HCR/REF/DTP/HSD/MSG per level. | Must tolerate CG-specific omissions. |
| **ClaimResponse builder** | Produces the PAS response Bundle: ClaimResponse first, echoes request resources with the same fullUrl/ids (spec-33), maps HCR→reviewAction, REF*BB→authorization number, DTP*AAH→preAuthPeriod, AAA→error, MSG→processNote, CommunicationRequest for additional-information requests. | |
| **Correlation store** | `claimId ↔ BHT03 ↔ ISA13/GS06/ST02 ↔ 2000E TRN02 ↔ {item.sequence ↔ 2000F TRN02}` plus status, raw payloads, timestamps. | Needed for pended follow-up, inquiries, updates/cancels, audit, CMS metrics. |
| **Update/cancel handler** | Turns PAS Claim Update bundles (infoChanged/infoCancelled flags, certificationType=3) into 278 with UM02 = S/4/3 and REF*BB previous authorization number. | CG decides whether eCare supports edits and cancels via 278 (HPHC guide has samples of "edits and cancellations"). |
| **Inquiry handler** | Turns PAS `$inquire` (Claim Inquiry profile) into a 278 inquiry (005010X215) if eCare supports it; otherwise answers from the correlation store + last known ClaimResponse. | Confirm X215 support with HPHC EDI. |
| **Pend/notification engine** | Watches for eCare final decisions (unsolicited 278 responses, batch response files, or periodic X215 polling), rebuilds the ClaimResponse Bundle, and tells Onyx to fire the PAS Subscription notification (rest-hook, full-resource). | PAS spec-51/54/60/61. |
| **Observability & metrics** | Logs per PAS §11 metric model (volume, pend rate, time to final decision, errors by type); CMS-0057-F public reporting needs these. | |

## 2.5 Deployment and non-functional requirements

* **Latency budget** (target end-to-end ≤ 15 s per PAS): Onyx validation 1 s, bridge translation
  < 0.5 s, eCare real-time 278 ≤ 10 s (HPHC published figure), response translation < 0.5 s.
  Set the bridge's eCare timeout at ~12 s; on timeout return a pended ClaimResponse (A4) with an
  administrative reference we generate, and reconcile via inquiry/unsolicited response.
* **Throughput**: size for peak PA volume of the commercial line plus retries; batch fallback for
  bulk resubmission.
* **Security**: PHI in transit (TLS 1.2+/mTLS to eCare), PHI at rest (encrypted correlation store),
  no PHI in logs beyond identifiers needed for support; HRex security guidance applies to the FHIR
  side. Retain raw X12 for the audit period required by HPHC compliance.
* **Availability**: the PAS endpoint must answer even when eCare is down (return 5xx per PAS for
  transient failure, or accept-and-pend if the business prefers; decide with the business).
* **Environment parity**: HPHC EDI test environment (ISA15 = T) must be wired to an Onyx
  non-production tenant; certification with the HPHC EDI team is a formal gate (see `09-test-strategy.md`).

## 2.6 Alternatives considered and why we are here

| Option | Status | Notes |
|---|---|---|
| Availity (clearinghouse translation PAS ↔ 278) | Declined by Availity ("not ready") | Would have been the lowest-effort path; keep the door open for phase 2 if their PAS bridge ships. |
| Edifecs (translation engine) | No response | Edifecs XEngine/XEServer can validate/translate X12; it does not remove the need for FHIR mapping logic. Could still be used as the X12 validation engine inside our bridge if licensing works out. |
| Custom bridge with Onyx | **Selected** | Full control; requires X12 license, EDI enrollment with HPHC/eCare, and certification. |
| eCare native FHIR (vendor roadmap) | Unknown | Ask the eCare vendor whether a PAS-native interface is on their roadmap; even if it is, the bridge is needed for the 2027 date. |

## 2.7 Phasing recommendation

1. **Phase 0 – Confirmations (2–3 weeks)**: everything in `08-open-questions.md` marked *blocker*.
2. **Phase 1 – Initial request/response (MVP)**: professional and institutional services (UM01 =
   HS/SC/AR, UM02 = I), diagnoses, service lines, providers, real-time channel, A1/A2/A3/A4/A6
   handling, AAA→error, MSG→processNote, subscription on pend resolution.
3. **Phase 2 – Lifecycle**: updates (UM02 = S), extensions (UM02 = 4), cancels (UM02 = 3),
   inquiry (X215), attachments (275 or alternative), additional-information requests (PWK01/LOINC
   → CommunicationRequest/Task), unsolicited responses.
4. **Phase 3 – Hardening**: batch fallback, CMS metrics/public reporting, DTR/CRD alignment, MA
   and other lines of business if eCare also serves them.
