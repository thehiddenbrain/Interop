# 03 – Transaction Catalog

Every interaction the bridge must support, with the FHIR side, the X12 side and the eCare/HPHC
dependency. "CG" = to be confirmed against the HPHC 278 companion guide / EDI team.

## 3.1 PAS operations exposed by Onyx (provider-facing)

| # | FHIR operation | Input | Output | Bridge involvement |
|---|---|---|---|---|
| F1 | `POST [base]/Claim/$submit` (initial request) | PAS Request Bundle: Claim (profile-claim) first, then referenced resources | PAS Response Bundle: ClaimResponse first + echoed resources, or OperationOutcome (4xx/5xx) | Build 278 request (BHT02=13, UM02=I), send, translate 278 response |
| F2 | `POST [base]/Claim/$submit` (update) | PAS Request Bundle with Claim Update (profile-claim-update), `related.claim` → prior Claim, item/supportingInfo flagged `infoChanged` = added/changed, `infoCancelled` = true, item certificationType = 3 for cancelled items | Same as F1 | Build 278 with UM02 = S (revised) / 4 (extension) / 3 (cancel) per item, REF*BB with the previous authorization number; only changed items go on the 278 |
| F3 | `POST [base]/Claim/$submit` (cancel entire request) | Claim Update with Claim-level `certificationType` = 3, no items required | Same as F1 | Build 278 cancel (UM02 = 3 at 2000E; CG decides whether eCare wants the service lines repeated) |
| F4 | `POST [base]/Claim/$inquire` | PAS Inquiry Request Bundle: Claim Inquiry (profile-claim-inquiry) with patient member ID, requester, insurer, optional REF-NT/REF-BB extensions, optional dates, optional productOrService (or `not-applicable`) | Parameters with 0..* PAS Inquiry Response Bundles | Build 278 inquiry (005010X215) if eCare supports it; otherwise resolve from the bridge correlation store (see 3.4) |
| F5 | `POST [base]/Subscription` (rest-hook, full-resource, criteria `org-identifier=<ISA06>`) | Subscription per sending system | Notifications with full PAS Response Bundle | Bridge must tell Onyx when a pended decision changes (see 3.3) |
| F6 | CDex `$submit-attachment` (payer endpoint for solicited attachments) | Attachments keyed by PA identifiers, LOINC/PWK01 codes, line numbers | OperationOutcome | Bridge converts to 275 or files to eCare's document intake; needs PWK06 attachment control number continuity |

## 3.2 X12 transactions on the eCare side

| # | Transaction | Version | Direction | Purpose | eCare/HPHC dependency |
|---|---|---|---|---|---|
| X1 | 278 Request | 005010X217(E1) | bridge → eCare | Referral / authorization / notification / admission review request | Companion guide: supported UM01/UM02/UM03, required segments, limits |
| X2 | 278 Response | 005010X217 | eCare → bridge | Decision (HCR), pend (A4), reject (AAA), authorization numbers (HCR02 / REF*BB / REF*NT), certification dates (DTP*AAH), authorized quantities (HSD), text (MSG), authorized providers (2010EA/2010F) | Which HCR codes eCare emits; whether decision reason codes (HCR03) are populated; whether MSG carries denial text |
| X3 | 278 Request – cancellation / revision / extension | 005010X217 | bridge → eCare | UM02 = 3 / S / 4 with REF*BB | HPHC guide includes samples of "edits and cancellations" (speech therapy, ambulatory surgery) – confirm exact shape |
| X4 | 278 Inquiry / Response | 005010X215 | bridge ↔ eCare | Query-by-example status retrieval | Is X215 supported by eCare at all? (CG) |
| X5 | 278 Response – unsolicited (final decision after pend) | 005010X217 | eCare → bridge | Deliver the final determination for a previously pended request | Does eCare push unsolicited responses (real-time callback, SFTP drop) or must we poll? (CG) |
| X6 | 275 Additional Information (unsolicited with the 278; solicited after PWK/LOINC request) | 006020X316 (PAS) or 005010X210/X314 | bridge → eCare | Attachments (DocumentReference, QuestionnaireResponse, PDFs) | Does eCare accept 275? Which version? Size limits? (CG) |
| X7 | 999 Implementation Acknowledgment | 005010X231A1 | eCare → bridge | Syntax / IG-level acceptance or rejection of the functional group | Real-time: is the 999 returned in the same HTTP response as the 278 response, or separately? Batch: where is it dropped? (CG) |
| X8 | TA1 Interchange Acknowledgment | 005010 | eCare → bridge | Envelope-level rejection (ISA/IEA) | When does HPHC send TA1? (CG) |

## 3.3 Pended decisions: how a final answer gets back to the EHR

PAS: "In the event that the prior authorization cannot be evaluated and a final response returned
within the required timeframe, a response in which one or more of the requested authorization items
are 'pended' will be returned. A subscription-based mechanism SHALL be used by the client to be
informed of updates" (spec-8). "Implementers SHALL support subscriptions to provide the final
response" (spec-51). The notification "SHALL include a full PAS Response Bundle" (spec-61).

Three candidate mechanisms on the eCare side, in order of preference:

1. **Unsolicited 278 response push** from eCare when the case closes (real-time or batch file). The
   bridge re-associates by 2000E/2000F TRN (TRN01 = 2, echoing our trace numbers) or by REF*NT
   administrative reference number, rebuilds the ClaimResponse Bundle, and calls Onyx to notify.
2. **Scheduled 278 inquiry (X215) polling** by the bridge for every open pended case (back-off:
   15 min → 1 h → 4 h, stop at the CG-defined max; PAS says pended authorizations must remain
   queryable for at least 6 months after anticipated service completion, spec-74). Note PAS spec-9:
   the inquiry response does not carry everything a submit response carries (e.g. requests for
   additional information), so unsolicited push is preferred.
3. **eCare event/API hook** outside X12 (database trigger, case-management event, Point32Health
   integration bus). Non-standard but often the most reliable; confirm with the eCare vendor.

Whatever the mechanism, the bridge owns the state machine per item:
`submitted → pended (A4) → {certified A1/A2/A6 | not certified A3 | cancelled C | contact payer CT | no action NA}`
and per request: `open → final | error (AAA) | rejected (999)`.

## 3.4 Inquiry without X215

If eCare does not support 005010X215, the bridge can still satisfy F4 by answering from its own
correlation store: match on patient member ID + requester NPI (+ REF-NT / REF-BB / dates /
service code if given) and return the latest ClaimResponse Bundle per matching Claim. PAS spec-47/48
require that a search by a superseded reference number returns the *current* response. The
bridge must therefore version ClaimResponses per Claim and keep the "current" pointer. This is
acceptable under the IG (the operation is a black box), but tell the business that inquiry results
will only cover requests submitted through the FHIR channel, not those keyed directly into eCare by
phone/fax/portal staff unless eCare feeds those back.

## 3.5 Additional-information requests (payer asks for documents)

PAS additional-information page: a payer MAY request more information by responding to the 278 with
(1) one or more PWK01 codes, (2) one or more LOINC attachment codes in the HI segment, or (3) the
single LOINC 102089-0 in HI with a TRN that is the DTR questionnaire context id. The bridge maps
these into `ClaimResponse.communicationRequest` → PAS CommunicationRequest (category = PWK01 code,
payload.contentString = LOINC code, serviceLineNumber = item sequence, identifier = attachment
control number) and Onyx/the EHR turn that into a PAS Task and a CDex `$submit-attachment`.
Confirm whether eCare ever emits PWK/HI-LOINC in its 278 response; if it never does (most UM
systems pend and call the office), this whole path is documentation only for phase 1.

## 3.6 Acknowledgment handling matrix

| Event | Bridge action |
|---|---|
| TA1 with TA104 = R (rejected) | Fix envelope config (sender/receiver IDs, control numbers, delimiters); this is a configuration defect. Return HTTP 500 to Onyx (transient, resubmittable) and page operations. |
| 999 with AK9 = R (rejected) | The 278 violated the IG (syntax, situational rule). This is a bridge mapping defect or a companion-guide constraint we missed. Return HTTP 400 OperationOutcome with diagnostics = the IK3/IK4/CTX text (PAS §7.2.5: "addl information (response from validation, TA1, 999)"), log at error. |
| 999 with AK9 = A / E (accepted / accepted with errors) | Continue; log the errors. |
| 278 response with AAA at 2000A/2000B/2000C/2000D/2000E/2000F | Business-level rejection: `ClaimResponse.outcome = error`, one `ClaimResponse.error` per AAA (code = AAA03 from code system 901, followupAction = AAA04 from 889, errorElement = loop-segment-element e.g. `2010C-NM109`, errorPath = FHIRPath into the bundle). Per X12 RFI guidance, when AAA is at a higher level, the lower levels may not be returned – do not treat missing items as "approved". |
| 278 response with HCR at 2000E only (no 2000F) | Apply the 2000E HCR result to every item, keep `ClaimResponse.adjudication` (claim level) populated too. |
| No response within timeout | Pend: build a ClaimResponse with A4 on every item, `administrationReferenceNumber` = bridge-generated reference, outcome = partial; schedule reconciliation. |

## 3.7 Out of scope for the bridge (but adjacent)

* CRD (CDS Hooks coverage requirements) and DTR (questionnaires) – Onyx-side products, feed the
  PAS bundle but do not touch the 278.
* Provider-facing 278 (HPHC's existing direct-278 channel for providers and clearinghouses) stays
  as is; the bridge is only for PAS-originated requests. Beware collisions: the same authorization
  could be touched via both channels – eCare is the system of record, and the correlation store must
  tolerate "authorization changed outside FHIR".
* Pharmacy-benefit PA (NCPDP SCRIPT) – out of PAS scope.
