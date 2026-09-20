# Samples – X12 Example 1a ↔ PAS `ReferralAuthorizationBundleExample`

`x12-example-1a-referral-request.edi` is X12's public 005010X217 Example 1a ("Referral Request
for Review": Dr James Gardener asks Maryland Capital Insurance Company to approve a consultation
with cardiologist Dr Susan Watson for subscriber Joe Smith), as reproduced in the EdiFabric X12.NET
sample repository. One correction was applied: GS01 is `HI` (Health Care Services Review
Information) for a 278; the EdiFabric copy had `HC`. The envelope values are placeholders.

The Da Vinci PAS IG's `ReferralAuthorizationBundleExample` (in `HL7/davinci-pas`,
`input/fsh/Examples.fsh`) is the same scenario in FHIR, which makes the pair a ready-made
round-trip fixture. Side by side:

| X12 segment (Example 1a) | PAS element (ReferralAuthorizationExample and referenced resources) | Note |
|---|---|---|
| `BHT*0007*13*A12345*20050502*1101` | `Bundle.identifier.value = A12345`, `Bundle.timestamp = 2005-05-02T11:01:00` | BHT01 fixed; BHT02 = 13 request |
| `HL*1**20*1` / `NM1*X3*2*MARYLAND CAPITAL INSURANCE COMPANY*****46*789312` | `Claim.insurer → Organization(InsurerExample)`: `name`, `type = X3`? (the IG example uses `PR` on the insurer and `X3` on the requester organisation – an IG example quirk), `identifier` (NPI in the example; ETIN 46 in X12) | For HPHC the bridge substitutes HPHC's own UMO name/ID |
| `HL*2*1*21*1` / `NM1*1P*1*GARDENER*JAMES****46*8189991234` | `Claim.provider → Organization(UMOExample) "DR. JOE SMITH CORPORATION", NPI 8189991234` | NM102 = 1 person vs 2 organisation depends on the FHIR resource type |
| `HL*3*2*22*1` / `NM1*IL*1*SMITH*JOE****MI*12345678901` | `Claim.patient → Patient(SubscriberExample)`: `name SMITH JOE`, `identifier 12345678901`; `Coverage.relationship = self / X12 18`, `Coverage.subscriberId 1122334455` | Subscriber is the patient → no 2000D |
| `HL*4*3*EV*0` | one patient event per Claim | HL04 = 0 here because Example 1a puts the service at event level; the bridge always adds a 2000F line per `Claim.item` (HL04 = 1) |
| `TRN*1*111099*9012345678` | `Claim.identifier`: `system urn:trnorg:9012345678`, `value 111099`, `assigner 9012345678`, `extension[subDepartment] 223412` (→ TRN04) | |
| `UM*SC*I*3*11:B*****Y` | `item.extension[requestType] = SC` (1525), `item.extension[certificationType] = I` (1322), `item.category = 3 Consultation` (1365), `item.locationCodeableConcept = POS 11` (→ `11:B`), UM09 = Y by policy | `Claim.extension[levelOfServiceType] = U` would populate UM06 |
| `HI*BF:41090:D8:20050430` | `Claim.diagnosis[1]`: `icd-10-cm G89.4`, `extension[recordedDate] 2021-05-10` | The X12 example is ICD-9 (`BF`); with ICD-10-CM the bridge emits `HI*ABK:G894:D8:20210510` |
| `HSD*VS*1` | `item.extension[requestedService] → ServiceRequest.quantityQuantity = 1 visit` | PASQuantity unit code `VS` |
| `NM1*SJ*1*WATSON*SUSAN****34*987654321` / `PER*IC**TE*4029993456` | `Claim.careTeam[1] (careTeamClaimScope = true) → PractitionerRole(ReferralPractitionerRoleExample)`: `Practitioner WATSON SUSAN NPI 1234567893`, `telecom 4029993456`, `location → Location(REFERRAL CLINIC, 111 1ST STREET SAN DIEGO CA 92101)` | Bridge emits `NM1*SJ*1*WATSON*SUSAN****XX*1234567893`, `N3/N4` from Location, `PER*IC**TE*4029993456` |
| (no X12 equivalent in Example 1a) | `Claim.supportingInfo[AdditionalInformation]` with `documentInformation` (PWK01 `PY` physician's report, PWK02 `EL`, control number `111222`) → `DocumentReference` (text/plain, inline base64) | Bridge emits `PWK*PY*EL***AC*111222*A procedure note...` and, in phase 2, a 275 carrying the document with TRN = 111222 |
| (no X12 equivalent) | `item.extension[itemTraceNumber] 1122334`, `authorizationNumber 1122445`, `administrationReferenceNumber 9988311`, `epsdtIndicator false`, `nursingHomeResidentialStatus 2`, `nursingHomeLevelOfCare 2`, `revenueUnitRateLimit 100.00` | These populate 2000F TRN, REF*BB, REF*NT, SV111, SV209/SV120, SV206 respectively when the bridge adds the 2000F loop |

Expected 278 produced by the bridge for the PAS example (illustrative, envelope omitted):

```
ST*278*0001*005010X217~
BHT*0007*13*A12345*20050502*1101~
HL*1**20*1~
NM1*X3*2*HARVARD PILGRIM HEALTH CARE*****PI*04271~
HL*2*1*21*1~
NM1*1P*2*DR. JOE SMITH CORPORATION*****XX*8189991234~
N3*111 1ST STREET~
N4*SAN DIEGO*CA*92101~
HL*3*2*22*1~
NM1*IL*1*SMITH*JOE****MI*12345678901~
DMG*D8*19470118*M~
HL*4*3*EV*1~
TRN*1*111099*9012345678*223412~
UM*SC*I*3*11:B**U***Y~
HI*ABK:G894:D8:20210510~
PWK*PY*EL***AC*111222*A PROCEDURE NOTE INCLUDED TO PROVIDE DETAILS OF WHAT HAS BEEN TRIED BEFORE THE REFERRAL~
NM1*SJ*1*WATSON*SUSAN****XX*1234567893~
N3*111 1ST STREET~
N4*SAN DIEGO*CA*92101~
PER*IC**TE*4029993456~
HL*5*4*SS*0~
TRN*1*111099-1*9012345678~
UM*SC*I*3*11:B~
REF*BB*1122445~
REF*NT*9988311~
HSD*VS*1~
SE*27*0001~
```

The exact set of segments HPHC/eCare requires (N3/N4, DMG, PWK, 2000F) comes from the companion
guide tables – see `../06-hphc-companion-guide.md`.
