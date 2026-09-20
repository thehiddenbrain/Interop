# 05 – Response Mapping: X12 278 Response (005010X217) → PAS Response Bundle

Same conventions as `04-mapping-request.md`. The response is processed "in the reverse order as
the request" (PAS §7.2.3). The Bundle SHALL start with a `ClaimResponse` (profile
`PASClaimResponse`), reference the original Claim (`ClaimResponse.request`), and echo request
resources with the *same fullUrl and ids* (spec-33).

## 5.1 Envelope and header

| X12 | FHIR target | Rule |
|---|---|---|
| ISA/GS (response) | `ClaimResponse.extension[transmissionIdentifiers]` (applicationSenderCode = GS02, applicationReceiverCode = GS03, interchangeSenderID = ISA06, interchangeReceiverID = ISA08) | Populate from the *response* envelope (payer is sender). HPHC guide: "The ISA08 and GS03 values are taken from the header of the transmitted file" (recovered; meaning the response echoes our receiver IDs). |
| ST02 / SE | – | Validate segment count; ST02 must match the request's ST02 in real-time mode |
| BHT02 | – | Expect `11` Response |
| BHT03 | `Bundle.identifier` (response bundle) | Echo of our submitter transaction identifier; the PAS Response Bundle `identifier` is mandatory (FHIR-51397). Mismatch → treat as mis-routed response, alarm. |
| BHT04/BHT05 | `Bundle.timestamp`, `ClaimResponse.created` | Payer's creation date/time |
| BHT06 | `ClaimResponse.outcome.extension[outcomeCode]` | `18` = Response, no further updates to follow; `19` = Response, further updates to follow (X12 Example 1b). `19` implies pended items exist and an unsolicited final response will come – drives the pend engine. |

## 5.2 ClaimResponse header elements

| FHIR element | X12 source | Rule |
|---|---|---|
| `ClaimResponse.identifier` (PASIdentifier, system `urn:trnorg:<TRN03>`) | 2000E TRN with TRN01 = `2` (referenced transaction trace) or the payer's own TRN01 = `1` | Echo our patient-event trace number; PAS examples label it PATIENT_EVENT_TRACE_NUMBER. Second TRN (payer-assigned) → additional identifier. |
| `status` | – | fixed `active` |
| `type` | request | copy from Claim (`professional` / `institutional` / `oral`) |
| `use` | – | fixed `preauthorization` |
| `patient` | 2010C/2010D echo | Reference the same Patient fullUrl as the request (spec-33) |
| `created` | BHT04/05 | dateTime |
| `insurer` | 2010A NM1 | Reference the request's Insurer Organization; if the payer returns a different UMO (2010EC) add an Organization |
| `requestor` | 2010B NM1 | Reference the request's requester (Organization or PractitionerRole) |
| `request` | correlation | Reference (fullUrl) of the original Claim; if unknown (e.g. unsolicited response for a request submitted by fax/portal) use `request.extension[data-absent-reason]` |
| `outcome` | derived | `complete` when every item has a final HCR (A1/A2/A3/A6/C/CT/NA); `partial` when any item is A4 (pending) or when only some items were returned; `error` when AAA present and no HCR. (PAS restricts outcome to complete / error / partial, FHIR-51436.) |
| `disposition` | MSG (2000E) | Human-readable summary; also copy MSG text to `processNote` |
| `preAuthRef` | 2000E HCR02 (review identification number) or 2000E REF*BB | The authorization / certification number. HPHC calls it the "transaction number"/authorization number; format unknown (open question). Item-level numbers go to `item.extension[...]` / `reviewAction.number` (see 5.4). |
| `preAuthPeriod` | 2000E DTP*AAH (certification effective period) | PAS profile note says "the patient-level administrationReferenceNumber (REF-NT) is mapped to this element" – that text is a known IG oddity; implement `preAuthPeriod` = DTP*AAH RD8 range (or D8 start with open end) and `extension[administrationReferenceNumber]` = REF*NT. |
| `extension[administrationReferenceNumber]` | 2000E REF*NT | Present when HCR01 = A4/A3/CT/NA per common payer practice; required on follow-up requests. |
| `extension[authorizationNumber]` | 2000E HCR02 / REF*BB | Same value as `preAuthRef`; keep both for client convenience. |
| `extension[communicatedDiagnosis]` | 2000E HI (response) | Diagnoses the UMO associates with the decision |
| `extension[claimResponseReviewer]` | – (not in X12) | `wasHumanReviewedFlag` is required by the extension; source it from eCare outside X12 (case data), else omit the extension. CMS-0057-F denial-reason and MA/CT peer-review rules make this valuable. |
| `extension[authorizedProvider]` (claim level) | 2010EA NM1 in the response | When the UMO authorizes a specific provider/facility for the whole event |
| `adjudication` (claim level) | 2000E HCR | `adjudication.category = submitted`, `extension[reviewAction]` (see 5.4) mirrors the 2000E HCR |
| `processNote` | 2000E/2000F MSG | One note per MSG; `number` sequential; link items with `item.noteNumber`. Denial reasons that eCare puts in MSG must land here (PAS "Reason for Denial"). |
| `communicationRequest` | 2000E/2000F PWK, HI (LOINC attachment codes), TRN with LOINC 102089-0 | See 5.6 |
| `error` | AAA at any level | See 5.5 |

## 5.3 Items

One `ClaimResponse.item` per request item, **always** (spec-34: echo `item.sequence`), even when eCare
returned only a 2000E-level HCR or omitted a line.

| FHIR element | X12 source | Rule |
|---|---|---|
| `item.itemSequence` | 2000F TRN02 (our encoded sequence) → correlation store | Never derive from loop order alone. |
| `item.extension[itemTraceNumber]` | 2000F TRN | Echo |
| `item.extension[preAuthIssueDate]` | 2000F DTP (certification issue date qualifier per TR3) | date |
| `item.extension[preAuthPeriod]` | 2000F DTP*AAH | Period; fall back to 2000E DTP*AAH |
| `item.extension[previousAuthorizationNumber]` | 2000F REF*BB | previous certification number on renewals/extensions |
| `item.extension[administrationReferenceNumber]` | 2000F REF*NT | |
| `item.extension[requestedServiceDate]` | 2000F DTP*472 (echo of the requested service date) | dateTime or Period |
| `item.extension[authorizedProvider]` | 2010F NM1 (+ PRV) | `provider` → Practitioner/Organization (create resource if new; reuse request instance if same NPI), `providerType` = NM101 code (98), `role`, `qualification` from PRV |
| `item.extension[authorizedItemDetail]` | 2000F SV1/SV2 (authorized values), HSD | productOrServiceCode, productOrServiceCodeEnd, modifier, unitPrice, quantity (PASQuantity with HSD01 unit), nursingHomeLevelOfCare, revenue, revenueUnitRateLimit |
| `item.extension[admissionDates]` / `[dischargeDate]` | 2000E/2000F DTP*435 / DTP*096 in the response | authorized admission/discharge dates |
| `item.extension[communicatedDiagnosis]` | 2000F HI | |
| `item.adjudication.category` | – | fixed `submitted` |
| `item.adjudication.extension[reviewAction]` | 2000F HCR (fallback 2000E HCR) | see 5.4 |
| `item.noteNumber` | 2000F MSG | index into `processNote` |

### Authorized ≠ requested (PAS rule, spec-36/37)

When the UMO changes quantity, dates, provider or code (HCR01 = A6 Modified, which HPHC does
return), the bridge must:

1. Put the *requested* item in `ClaimResponse.item` with `reviewAction.code = A6`.
2. Put the *authorized* details in `ClaimResponse.addItem` with `itemSequence` pointing at the
   original item: `productOrService`, `modifier`, `serviced[x]`, `location[x]`, `quantity`,
   `unitPrice`, `provider` (+ `providerType`), and extensions `requestType`, `certificationType`,
   `category` (UM03), `admissionDates`, `dischargeDate`, `revenue`, `preAuthPeriod`, `preAuthIssueDate`,
   `itemTraceNumber`, `reviewAction`.
3. Authorized items that do not correspond to any requested item (eCare adds a line) become
   `addItem` entries with a new `itemSequence` that matches no request item.

HPHC's status text "Service approved, but with changes" (recovered from the guide) corresponds to
A6 and is where this logic will be exercised in production.

## 5.4 The review action (HCR) mapping

| X12 HCR | FHIR |
|---|---|
| HCR01 action code (306) | `reviewAction.extension[code].valueCodeableConcept` (system `https://codesystem.x12.org/005010/306`) |
| HCR02 review identification number | `reviewAction.extension[number].valueString` (item level) and `ClaimResponse.preAuthRef` (event level) |
| HCR03 decision reason code (886), up to 5 | `reviewAction.extension[reasonCode]` 0..* – this is the structured **denial reason** CMS-0057-F requires for every denial. If eCare does not populate HCR03, the denial reason must come from MSG (→ processNote) or from case data outside X12. |
| HCR04 second surgical opinion indicator | `reviewAction.extension[secondSurgicalOpinionFlag]` boolean |

HCR01 values and bridge semantics (X12 list 306; HPHC returns A1, A3, A4, A6, C – recovered):

| HCR01 | Meaning | `outcome` contribution | Bridge behaviour |
|---|---|---|---|
| A1 | Certified in total | complete | Final. Store auth number. |
| A2 | Certified – partial | complete | Final; expect authorized detail differing from requested. |
| A3 | Not certified (denied) | complete | Final; denial reason required (HCR03/MSG); no updates allowed afterwards (PAS) – new request only. |
| A4 | Pended | partial | Open. Start pend engine; expect BHT06 = 19. |
| A6 | Modified | complete | Final; use item + addItem pattern (5.3). |
| C  | Cancelled | complete | Confirms a cancel (UM02 = 3) request. |
| CT | Contact payer | partial/complete | Surface as processNote + `communicationRequest`? Treat as pended-with-manual-step; business decision. |
| NA | No action required (e.g. no authorization needed for this service) | complete | Final; tell EHR PA not required. Consider mapping to CRD "no auth needed" semantics. |

## 5.5 Errors (AAA) → `ClaimResponse.error`

| X12 | FHIR |
|---|---|
| AAA01 (Y/N valid request indicator) | not mapped (AAA presence = error) |
| AAA03 reject reason code (901) | `error.code` (system `https://codesystem.x12.org/005010/901`) |
| AAA04 follow-up action code (889) | `error.extension[followupAction]` |
| Loop/segment where the AAA sits | `error.extension[errorElement].extension[error]` string in X12 notation, e.g. `2010C-NM109`; `error.extension[errorPath]` FHIRPath into the request bundle, e.g. `Bundle.entry[3].resource.identifier[0].value` |
| Level | 2000A/2000B → claim-level error (no `itemSequence`); 2000C/2000D → claim-level with patient path; 2000E → claim-level; 2000F → `error.itemSequence` = that item |
| MSG accompanying an AAA | `processNote` + `error.extension[errorElement].extension[processNote]` |

When a higher-level AAA suppresses lower loops (X12 RFI 2445), the ClaimResponse still lists all
items but with no `reviewAction` and `outcome = error`; do not fabricate A3.

Follow-up action codes drive the EHR message: `C` correct and resubmit, `N` resubmission not
allowed, `P` resubmit original, `R` resubmission allowed, `S` do not resubmit – inquiry initiated
to third party, `W` wait 30 days, `X` wait 10 days, `Y` do not resubmit – payer will respond.

## 5.6 Requests for additional information → `CommunicationRequest`

| X12 (response) | FHIR |
|---|---|
| 2000E/2000F PWK01 (report type 755) | `CommunicationRequest.category` (PWK01 code), `extension[serviceLineNumber]` = item sequence for 2000F |
| PWK06 attachment control number | `CommunicationRequest.identifier` (X12 attachment control number; required unless medium = VO) |
| PWK02 transmission code | `CommunicationRequest.medium` (756 or `CDEX`) |
| HI with LOINC attachment request codes (valid-hl7-attachment-requests) | `CommunicationRequest.payload.contentString` = LOINC code; `payload.extension[communicatedDiagnosis]`; `payload.extension[contentModifier]` (LOINC modifier) |
| HI LOINC 102089-0 + TRN | questionnaire request: TRN02 = DTR context id → Task `input[QuestionnaireContext]` (Onyx/EHR side) |
| MSG explaining what is needed | `processNote` |

`ClaimResponse.communicationRequest` references each CommunicationRequest; the Onyx/CDex layer
creates the PAS Task (one per request type) and the provider answers via `$submit-attachment`.
Response `outcome` stays `partial` (pended) until the information arrives and eCare finalises.

## 5.7 Echoed resources in the response bundle

Return, with identical `fullUrl`: Patient (subscriber and beneficiary), Coverage, Insurer
Organization, Requestor Organization/PractitionerRole/Practitioner, any Practitioner/Organization
referenced by `authorizedProvider`, and the original Claim only if the response references it
(PAS: "All resources that are explicitly referenced by the ClaimResponse need to be returned in the
response Bundle"; the Claim itself may be omitted if `request.reference` is a resolvable URL).

## 5.8 Inquiry response (005010X215) → PAS Inquiry Response Bundles

Each matching authorization in the X215 response becomes its own PAS Inquiry Response Bundle with a
`PASClaimInquiryResponse` first (spec-44: reference a Claim or give a data-absent-reason). Map
HCR/REF/DTP/HSD/SV/NM1 exactly as above; `item.extension[certIssueDate/certEffectiveDate/certExpirationDate]`
from the certification DTPs; `reviewActionCode` from HCR01. If a superseded reference number was
searched, return the *current* response (spec-47/48). Inquiry responses omit requests for additional
information (spec-9).

## 5.9 Unsolicited responses and subscription notifications

An unsolicited 278 response (BHT06 = 18 after an earlier 19, or a batch response) is mapped exactly
like the synchronous response, saved as the new *current* ClaimResponse for the Claim, and handed to
Onyx to send the PAS Subscription notification (`Bundle.type = history` containing the
SubscriptionStatus Parameters and the full PAS Response Bundle, per the IG example). The subscription
filter is `org-identifier = <sending system identifier>` = the ISA06 the EHR used with Onyx, so the
bridge must return the EHR's transmission identifiers (kept in the correlation store), not eCare's.

## 5.10 Worked example (X12 Example 1b ↔ PAS `PractitionerRequestorResponseExample`)

X12 response (reconstructed from the X12 1b narrative and the PAS example; verify against the
licensed TR3 example):

```
ST*278*0001*005010X217~
BHT*0007*11*A12345*20050502*1102*18~
HL*1**20*1~
NM1*X3*2*MARYLAND CAPITAL INSURANCE COMPANY*****46*789312~
HL*2*1*21*1~
NM1*1P*1*GARDENER*JAMES****46*8189991234~
HL*3*2*22*1~
NM1*IL*1*SMITH*JOE****MI*12345678901~
HL*4*3*EV*1~
TRN*2*111099*9012345678~
UM*SC*I*3*11:B~
HCR*A1*AUTH0001~
DTP*AAH*RD8*20050502-20050602~
HL*5*4*SS*0~
TRN*2*1122334*9012345678~
UM*SC*I*3~
HCR*A1*AUTH0001~
DTP*472*RD8*20050502-20050602~
DTP*AAH*RD8*20050502-20050602~
HSD*VS*1~
NM1*P3*1*WATSON*SUSAN****XX*1234567893~
SE*22*0001~
```

PAS ClaimResponse (abridged, from the IG example): `identifier 111099`, `outcome = complete`,
`item.itemSequence = 1`, `item.extension[preAuthIssueDate] = 2005-05-02`,
`item.extension[preAuthPeriod] = 2005-05-02/2005-06-02`, `item.extension[authorizedProvider]`
= Dr Watson with providerType `P3`, `item.extension[authorizedItemDetail]` = consultation ×1 @ 100,
`item.adjudication.extension[reviewAction].number = AUTH0001`, `.code = A1 Certified in total`.
