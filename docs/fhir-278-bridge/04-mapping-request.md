# 04 – Request Mapping: PAS Request Bundle → X12 278 Request (005010X217)

Conventions used in this document

* **FHIR path** is relative to the PAS Request Bundle. `Claim` = the first entry
  (profile `PASClaim` or `PASClaimUpdate`). Extension names are the PAS IG slice names
  (`Claim.extension[levelOfServiceType]` = `http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-levelOfServiceCode`).
* **X12 location** is `Loop – Segment – Element` per the 005010X217 TR3 (E1 errata).
* **CG** in the "HPHC" column = the HPHC 278 companion guide is expected to constrain this element
  (its request table is on pages 20–23 of guide v1.2); **✔** = fact recovered from the guide;
  **?** = unknown, must be confirmed (tracked in `08-open-questions.md`).
* **Rule** = the translation rule the bridge implements, including truncation and defaults.
* X12 code lists (1365 service type, 1525 request category, 1322 certification type, 1338 level of
  service, 98 entity identifier, 66 identification code qualifier, 755 report type, 756 transmission,
  306 action, 901/889/886 reason codes) are X12 intellectual property; the bridge must load them
  from a licensed copy of the TR3. Where a code value is shown here it is for illustration.

The X12 example the PAS IG was written against (X12 Example 1a "Referral Request for Review",
Maryland Capital Insurance / Dr Gardener / Joe Smith / Dr Watson) is reproduced in
`samples/x12-example-1a-referral-request.edi`; the PAS IG's `ReferralAuthorizationBundleExample` is the
same scenario in FHIR, so the two can be read side by side (`samples/README.md`).

---

## 4.1 Interchange and functional group envelope (not derived from FHIR content)

| X12 | Value / rule | Source | HPHC |
|---|---|---|---|
| ISA01/ISA02 | `00` / 10 spaces (no authorization info) | config | CG |
| ISA03/ISA04 | `00` / 10 spaces | config | CG |
| ISA05 / ISA06 | Sender qualifier + **submitter ID assigned by HPHC EDI at enrollment**. PAS carries it in `Claim.extension[transmissionIdentifiers].interchangeSenderID`; for the bridge the value is *ours* (the bridge/Onyx is the trading partner), not the EHR's. | HPHC EDI enrollment form asks for the ISA06 submitter ID ✔ | CG ✔ (qualifier ? – likely `ZZ`) |
| ISA07 / ISA08 | Receiver qualifier + receiver ID. Recovered from the HPHC guide family: **ISA08 = `HPHC0001`** for non-NEHEN channels (`NEHEN003` via NEHEN). Confirm for the 278 and for the eCare-facing channel specifically. | ✔ (cross-document, verify) | CG |
| ISA09/ISA10 | Interchange date/time (YYMMDD/HHMM) at send time | generated | |
| ISA11 | Repetition separator `^` | config | CG (Simple File Structure section) |
| ISA12 | `00501` | fixed | |
| ISA13 | Interchange control number, 9 digits, unique per trading partner, monotonically increasing; persist in the correlation store | generated | |
| ISA14 | Acknowledgment requested: `0` or `1` (TA1). Real-time channels normally `0`. | config | CG ? |
| ISA15 | `T` (test) / `P` (production) | environment | CG |
| ISA16 | Component separator `:` | config | CG |
| GS01 | **`HI`** (Health Care Services Review Information) | fixed | |
| GS02 / GS03 | Application sender code (`Claim.extension[transmissionIdentifiers].applicationSenderCode`, but again *ours*) / application receiver code: recovered **GS03 = `HPHC0001B`** for non-NEHEN channels (`NEHEN003` via NEHEN) | ✔ (cross-document, verify) | CG |
| GS04/GS05 | Date/time CCYYMMDD/HHMM | generated | |
| GS06 | Group control number; persist | generated | |
| GS07 | `X` | fixed | |
| GS08 | `005010X217` (HPHC references the E1 errata; the GS08 value stays `005010X217`) | fixed | ✔ |
| ST01/ST02/ST03 | `278` / transaction set control number (unique in group) / `005010X217` | generated | |
| Delimiters | Segment `~`, element `*`, sub-element `:`, repetition `^` unless the CG says otherwise | config | CG |
| Character set | Basic character set unless the CG "Extended Character Set" section allows extended; normalise FHIR strings (fold accents, strip control chars, replace `*`, `~`, `:`, `^` inside data) | rule | CG ✔ (section exists) |
| Real-time | **One ST/SE per interchange; one request per ST/SE** (HPHC rule) | ✔ | ✔ |

## 4.2 Transaction header

| X12 | FHIR source | Rule | HPHC |
|---|---|---|---|
| BHT01 | – | `0007` (Information Source, Information Receiver, Subscriber, Dependent, Event, Services) | |
| BHT02 | Claim profile / certificationType | `13` Request for all submissions (initial, revised, extension, cancel are distinguished by UM02, not BHT02). *Verify with the TR3 whether HPHC uses any other BHT02 value in its cancel sample (Appendix F).* | CG ? |
| BHT03 | `Bundle.identifier.value` (Submitter Transaction Identifier) | Max 50 AN. Must be unique per submission; reuse on retry of the *same* submission only. | |
| BHT04 / BHT05 | `Bundle.timestamp` | CCYYMMDD / HHMM (convert to Eastern local time? – X12 uses the sender's local time; document choice) | |
| BHT06 | – | Not used on the request | |

## 4.3 Hierarchical structure

| X12 | Rule |
|---|---|
| HL*1**20*1 | Loop 2000A – UMO (Information Source). Always exactly one. |
| HL*2*1*21*1 | Loop 2000B – Requester (Information Receiver). Exactly one. |
| HL*3*2*22*{0|1} | Loop 2000C – Subscriber. HL04 = 1 if a dependent loop follows, else 1 (patient event follows). |
| HL*4*3*23*1 | Loop 2000D – Dependent. Present only when `Coverage.relationship` ≠ self (X12 code 18) **and** the payer does not assign the dependent their own member ID. HPHC members carry individual member IDs (HP prefix + subscriber + suffix pattern seen on ID cards): the CG "Member Identification Numbers" section (p. 9) decides whether dependents go in 2000C with their own ID or in 2000D. **Blocker question.** |
| HL*n*parent*EV*1 | Loop 2000E – Patient Event. Exactly one per Claim. |
| HL*n*parent*SS*0 | Loop 2000F – Service. One per `Claim.item` (subject to CG maximum; TR3 allows 99 per patient event, HPHC may cap lower). |

HL01 numbering is sequential across the transaction; HL02 is the parent's HL01; HL04 = 1 when
child HLs exist. Segment count in SE01 includes ST and SE.

## 4.4 Loop 2000A / 2010A – Utilization Management Organization (the payer)

| X12 | FHIR source | Rule | HPHC |
|---|---|---|---|
| 2010A NM101 | `Claim.insurer` → `Organization(PASInsurer).type` | `X3` (UMO). PAS binds `type` to the 2010A NM101 value set; for HPHC always `X3`. | ✔ samples use `NM1*X3` |
| NM102 | – | `2` (non-person) | |
| NM103 | `Organization.name` | Max 60 AN; uppercase. HPHC's expected name (e.g. `HARVARD PILGRIM HEALTH CARE`) is in the CG. | CG ? |
| NM108 / NM109 | `Organization.identifier[PI]` (payer ID, CARIN `payerid` type) or NPI | Qualifier `PI` (payer identification) with HPHC's payer ID **04271** (HPHC's national payer ID as printed on member cards – verify against CG), or `46` ETIN / `XV` CMS plan ID / `XX` NPI as the CG dictates. The EHR-supplied value is *ignored*; the bridge substitutes the value eCare expects. | CG ? (blocker) |
| 2010A PER | – | Not used on the request (UMO contact is on the response) | |

## 4.5 Loop 2000B / 2010B – Requester (ordering / referring provider or facility)

| X12 | FHIR source | Rule | HPHC |
|---|---|---|---|
| NM101 | `Claim.provider` → `PASRequestor` (Organization) or `PASPractitionerRole` | `1P` Provider when the requester is a practitioner or group; `FA` Facility when the requester is a facility. PAS binds `Organization.type` to the 2010B NM101 value set. | ✔ samples use `NM1*1P` |
| NM102 | resource type | `1` person (Practitioner via PractitionerRole), `2` non-person (Organization) | |
| NM103–NM107 | `Practitioner.name.family/given/middle/prefix/suffix` or `Organization.name` | Family ≤ 60, given ≤ 35, middle ≤ 25, prefix ≤ 10, suffix ≤ 10; uppercase; fold to basic character set | |
| NM108 / NM109 | `Practitioner.identifier` / `Organization.identifier` with system `http://hl7.org/fhir/sid/us-npi` | `XX` + 10-digit NPI. Fallbacks (`24` EIN, `34` SSN, `46` ETIN) only if the CG allows. **HPHC requires the requester's NPI (and TIN) to be pre-registered under the submitter ID and to match provider records** – enrollment rule recovered from the guide. | ✔ (enrollment) |
| 2010B REF | `Organization.identifier[TIN]` (`USEIN`), other identifiers (`0B` state license, `1G` UPIN, `N5` plan network ID, `ZH` carrier-assigned provider number, `EI` EIN, `SY` SSN) | Only send REF qualifiers the CG lists; HPHC "payee number" (from enrollment) may need a specific REF qualifier – ask. Max 50 AN. | CG ? |
| N3 / N4 | `Organization.address` or `PractitionerRole.location → Location.address` or `Practitioner.address` | N301 ≤ 55, N302 ≤ 55; N401 city ≤ 30, N402 state 2, N403 zip 3–15 (PAS FHIR-55643: send full ZIP where known), N404 country only when not US | |
| PER | `Organization.contact.telecom` / `PractitionerRole.telecom` | `PER*IC*name*TE*phone*FX*fax*EM*email`; phone digits only (10); at most 3 contact methods | |
| PRV | `careTeam.qualification` (taxonomy) or `PractitionerRole.specialty` | `PRV*RF*PXC*<taxonomy>` (RF = referring). Only if the CG uses it. | CG ? |

## 4.6 Loop 2000C / 2010C – Subscriber, and 2000D / 2010D – Dependent

| X12 | FHIR source | Rule | HPHC |
|---|---|---|---|
| 2000C TRN | `Claim.identifier` (PASIdentifier; system `urn:trnorg:<TRN03>`) | Optional at 2000C in the request; we put the patient-event trace at 2000E instead (see 4.7). | |
| 2010C NM101 | – | `IL` Insured or Subscriber | |
| NM102 | – | `1` | |
| NM103–NM107 | `Coverage.subscriber → Patient(PASSubscriber).name` (or `Claim.patient` when relationship = self) | Same length limits as 4.5 | |
| NM108 / NM109 | `Coverage.identifier[memberid]` / `Coverage.subscriberId` / `Patient.identifier` type `MB` | `MI` + member ID. **HPHC: member IDs must not contain hyphens or spaces** (recovered). Format/length and whether the suffix is included come from the CG p. 9. HPHC recommends verifying IDs with a 270 first – Onyx/CRD should already have done eligibility. | ✔ / CG |
| 2010C REF | `Coverage.class[group]` (`6P` group number), `Patient.identifier` other (`SY` SSN, `EJ` patient account number, `HJ` identity card, `1L`, `IG`, `N6`, `NQ`) | Only CG-listed qualifiers. `EJ` patient account number is valuable for the office's reconciliation; ask if HPHC echoes it. | CG ? |
| N3 / N4 | `Patient.address` (home) | Optional; include if present | |
| DMG | `Patient.birthDate`, `Patient.gender` | `DMG*D8*CCYYMMDD*M/F/U`. FHIR `other`/`unknown` → `U`. PAS FullDateRule guarantees a full date. | |
| INS | `Coverage.relationship.coding[X12Code]` (1069), `Patient.extension[militaryStatus]` (584), `Patient.multipleBirthInteger` | `INS*Y*18` for subscriber; `INS*N*<relationship>` in 2010D for dependents (01 spouse, 19 child, 20 employee, 21 unknown, 39 organ donor, 40 cadaver donor, 53 life partner, G8 other). INS08 military status (rare), INS17 birth sequence number (twins). | CG ? |
| 2000D / 2010D | `Claim.patient → Patient(PASBeneficiary)` when relationship ≠ self and CG requires the dependent loop | `NM1*QC` patient; REF/N3/N4/DMG/INS as above. If HPHC assigns every member an individual ID, send the dependent in 2000C as `IL` with their own ID and omit 2000D – **CG decides**. | CG (blocker) |

## 4.7 Loop 2000E – Patient Event (one per Claim)

| X12 | FHIR source | Rule | HPHC |
|---|---|---|---|
| TRN | `Claim.identifier` (1..2 in PASClaim) | `TRN*1*<identifier.value>*<TRN03>*<TRN04>`. TRN01 = `1` (current transaction trace). TRN02 ≤ 50 AN – the patient-event trace number; must be unique for the submitter (use the Claim identifier; if the EHR value is unsuitable, generate and store the mapping). TRN03 = originating company identifier: 10 characters, `9` + our 9-digit EIN (X12 rule); PAS `identifier.system` = `urn:trnorg:<TRN03>` and `identifier.assigner`. TRN04 optional reference (`identifier.extension[subDepartment]`). Two TRNs allowed. | |
| AAA | – | Not sent on request | |
| UM01 | `Claim.item[*].extension[requestType]` (1525) | Request category: `AR` Admission Review (inpatient), `HS` Health Services Review, `SC` Specialty Care Review (referral), plus reservation codes the TR3 defines (e.g. `IN`) that HPHC probably does not support. PAS puts the code on each item; the 2000E value must be consistent with the items (TR3/RFI: if 2000F UM01 = `SC` then 2000E UM01 = `SC`). Rule: 2000E UM01 = the item value when all items agree; if items mix `AR` with services, put `AR` at 2000E and `HS` on 2000F lines; if items mix `SC` and `HS` reject with OperationOutcome (cannot be one 278). | CG ? (which of AR/HS/SC HPHC accepts) |
| UM02 | `Claim.extension[certificationType]` (update/cancel) or `Claim.item[*].extension[certificationType]` (1322) | `I` Initial, `R` Renewal, `S` Revised, `4` Extension, `3` Cancel, `1` Appeal-Immediate, `2` Appeal-Standard. Initial submit: `I`. Claim Update: `S`/`4`/`3` per item; whole-request cancel: `3` at 2000E. PAS invariant: certificationType `1` requires levelOfServiceType. HPHC guide has Edit and Cancel samples; the CG says some data cannot be edited and requires cancel + new initial. | ✔ (samples exist) / CG for allowed values |
| UM03 | `Claim.item[*].category` (1365 service type) | Required at 2000E when UM01 = HS/SC (TR3 situational). Take the category of the first item when all items share one category; otherwise the dominant category and put item-level UM03 on 2000F. `Claim.type` is "a high-level mapping of UM03" but is *not* the source. | CG ? (subset HPHC accepts) |
| UM04 | `Claim.item[*].location[x]` (CodeableConcept: CMS POS or NUBC type of bill) and `Claim.extension[encounter] → Encounter` | `UM04-1` facility code value, `UM04-2` qualifier `B` when the code is a CMS place-of-service code, `A` when it is the NUBC facility type (type-of-bill digits 2-3). Institutional (`AR`) requests usually use `A`; professional use `B`. PAS makes `item.location` mandatory, so one value always exists. | CG ? |
| UM05 | `Claim.accident.type` (1362) + `Claim.accident.location.state` / `.country` | Related causes: `UM05-1..3` codes (auto accident, employment, …), `UM05-4` state, `UM05-5` country. Only when `Claim.accident` present. | |
| UM06 | `Claim.extension[levelOfServiceType]` (1338) | `E` Elective, `U` Urgent, `03` Emergency. Required by TR3 when UM01 = AR (admission). Default `E` only if the business agrees; PAS makes it optional so the bridge needs a default rule. Urgent/expedited status also drives CMS-0057-F decision clocks (72 h). | CG ? |
| UM07 | – | Current health condition code – not mapped by PAS (leave empty) | |
| UM08 | `Claim.extension[homeHealthCareInformation].prognosis` (923) | Prognosis code – only for home health (the CR6 segment carries it too). | |
| UM09 | – | Release of information code; TR3 situational. Set `Y` (yes, provider has a signed statement) by policy, as X12 Example 1a does. Confirm with compliance. | CG ? |
| UM10 | – | Delay reason code – not mapped by PAS | |
| REF*BB | `Claim.extension[authorizationNumber]` / `Claim.item[*].extension[authorizationNumber]` | Previous authorization/certification number – required on updates, extensions, cancels (UM02 ≠ I). Max 50. | CG ? |
| REF*NT | `Claim.extension[administrationReferenceNumber]` | Administrative reference number returned by the UMO on a pended/disallowed outcome; send when updating a pended request. | CG ? |
| REF*EJ | `Patient.identifier` (account) / `Claim.identifier[1]` | Patient account number – if CG lists it | CG ? |
| DTP*435 | `Claim.supportingInfo[AdmissionDates].timingDate/Period` or `Encounter.period.start` | `DTP*435*D8*CCYYMMDD` (admission) or `RD8*start-end`. Required for `AR`. **HPHC Admission sample uses `DTP*435*D8*…` and `DTP*096*D8*…`** (recovered). | ✔ |
| DTP*096 | `Claim.supportingInfo[DischargeDates].timingDate` or `Encounter.period.end` | `DTP*096*D8*CCYYMMDD` (discharge / expected discharge) | ✔ |
| DTP*472 | `Claim.supportingInfo[PatientEvent].timingDate/Period` | Service date(s) at event level; `D8` single date or `RD8` range. PAS forbids partial dates. If both event and item dates exist, event-level = envelope of items. | CG ? |
| DTP other | `Claim.diagnosis.extension[recordedDate]`, `Claim.accident.date`, onset (`431`), last menstrual period (`484`), accident (`439`), estimated DOB (`ABC`), …  | Only the qualifiers the CG accepts; PAS maps the diagnosis recorded date into `HI` (HIxx-4), not DTP. | CG ? |
| HI | `Claim.diagnosis[1..12].diagnosisCodeableConcept` + `.type` + `.extension[recordedDate]` | `HI*ABK:<code>:D8:<date>*ABF:<code>…`. Qualifiers: `ABK` principal ICD-10-CM (first diagnosis or type = principal), `ABF` other ICD-10-CM, `ABJ` admitting ICD-10 (type = admitting), `APR` patient reason for visit ICD-10 (type = patientreasonforvisit), `BK/BF/BJ/PR` for ICD-9 (should never occur), `DR` DRG. **Strip the decimal point** (`G89.4` → `G894`). Max 12 diagnoses (PAS: "only the first 12 can be sent"). HIxx-4 date only when `recordedDate` present. SNOMED-coded conditions must be translated to ICD-10-CM by the bridge (PAS §7.2.10 note 3) or rejected. | CG ? (ABK/ABF required?) |
| HSD (2000E) | `Claim.item[*].quantity` (PASQuantity, unit codes from HSD01 list) + `RequestedService → ServiceRequest.occurrenceTiming (PASTiming)` | Event-level HSD only when a single item; otherwise put HSD on each 2000F. See 4.8 for composition. | CG ? |
| CRC | `Claim.extension[conditionCode]` (category 1136, indicator, codes 1321) | `CRC*<category>*Y/N*<code1>…<code5>`. PAS marks ambulance/chiro/DME/oxygen/functional-limitation CRCs as *not supported* in this IG version; only generic condition codes travel. | CG ? |
| CL1 | `Claim.extension[encounter] → Encounter.type` (1315 admission type), `.hospitalization.admitSource` (1314), `.extension[patientStatus]` (1352), `.extension[nursingHomeResidentialStatus]` (1345) | `CL1*<admissionType>*<admitSource>*<patientStatus>*<nursingHomeStatus>` for institutional/admission requests. NUBC code systems on the FHIR side map 1:1 onto X12 code values. | CG ? |
| CR1 / CR2 / CR5 | – | Ambulance / spinal manipulation / oxygen – PAS does not map them; omit. | |
| CR6 | `Claim.extension[homeHealthCareInformation]` (prognosis, start date) | `CR6*<prognosis>*<D8 start date>*…` for home health (HPHC Home Care sample; SV2 revenue code rule). CR6-03/04 must be both present or both absent. | ✔ (Home Care sample exists) |
| PWK | `Claim.supportingInfo[AdditionalInformation].extension[documentInformation]` (reportTypeCode 755, transmissionMethod 756, controlNumber, description) | `PWK*<PWK01>*<PWK02>***AC*<controlNumber>*<description>`. PWK02: `EL` electronically only (attachment sent in a 275), `BM` mail, `FX` fax, `AA` available on request, `FT` file transfer. Control number ≤ 50 and must equal the 275 `TRN`/`REF*BLT` attachment control number. One PWK per supportingInfo, TR3 limit 10 per 2000E. | CG ? (does eCare accept PWK / 275?) |
| MSG | `Claim.supportingInfo[MessageText].valueString` | `MSG*<text ≤ 264>`; TR3 limit 1 per 2000E in request. Concatenate/truncate; fold characters. | CG ? |
| 2010EA NM1 (Patient Event Provider) | `Claim.careTeam[OverallClaimMember]` (`careTeamClaimScope` = true), up to 14 | `NM1*<role>*1/2*names…*XX*<NPI>`: role from `careTeam.role` (2010EA NM101 value set): `71` attending, `72` operating, `73` other physician, `77` service location, `AAJ` admitting, `DD` assistant surgeon, `DK` ordering physician, `DN` referring provider, `FA` facility, `G3` consulting physician, `P3` primary care provider, `QB` purchase service provider, `QV` group practice, `SJ` service provider. **HPHC samples use `NM1*SJ` for the servicing entity.** REF (secondary IDs), N3/N4 (address of the servicing location – `PractitionerRole.location`), PER (phone – X12 Example 1a has `PER*IC**TE*…`), PRV (`PRV*<role>*PXC*<taxonomy>` from `careTeam.qualification`). | ✔ / CG |
| 2010EB | – | Patient event transport info (ambulance pickup/drop-off) – PAS not supported; omit | |
| 2010EC | – | Other UMO – omit | |

## 4.8 Loop 2000F – Service level (one per `Claim.item`)

| X12 | FHIR source | Rule | HPHC |
|---|---|---|---|
| HL | – | `HL*n*<2000E HL01>*SS*0` | |
| TRN | `Claim.item.extension[itemTraceNumber]` (PASIdentifier) or generated | `TRN*1*<item trace>*<TRN03>`. **Bridge rule: always send a 2000F TRN whose TRN02 encodes `item.sequence`** (e.g. `<claim trace>-<sequence>`), so eCare's echo (TRN01 = 2) lets us map responses back to `item.sequence` even when eCare re-orders lines. Store the mapping regardless. | CG ? (does eCare echo 2000F TRN?) |
| UM01 | `item.extension[requestType]` | Required on 2000F (TR3). Same consistency rule as 4.7. | CG ? |
| UM02 | `item.extension[certificationType]` | `I` on initial; `S`/`4`/`3` on updates; cancelled items also carry `modifierExtension[infoCancelledFlag] = true` in PAS. | ✔ samples |
| UM03 | `item.category` (1365) | Required (PAS makes `item.category` 1..1 precisely for this). | CG ? |
| UM04 | `item.location[x]` (POS or type of bill) | As 4.7 | CG ? |
| UM06 | `Claim.extension[levelOfServiceType]` | Only if item-level urgency differs (PAS has no item-level level of service; use the claim value) | |
| REF*BB / REF*NT | `item.extension[authorizationNumber]` / `item.extension[administrationReferenceNumber]` | Item-level previous authorization / admin reference (updates) | CG ? |
| DTP*472 | `item.servicedDate` / `item.servicedPeriod` | `D8` or `RD8`. Required on 2000F when the service dates differ from the event. Full dates only. | CG ? |
| DTP*AAH | – | Certification dates come back on the response; not sent | |
| SV1 (professional) | `item.productOrService` (CPT/HCPCS), `item.modifier[1..4]`, `item.unitPrice` × `item.quantity` (SV102 amount – optional), `item.quantity` (SV103 basis `UN` units / `MJ` minutes / `DA` days; SV104 quantity), `item.location` (SV105 POS), `item.extension[epsdtIndicator]` (SV111 `Y`), `item.extension[nursingHomeLevelOfCare]` (SV120), `item.extension[productOrServiceCodeEnd]` (SV101-8 end of code range) | `SV1*HC:<cpt>:<mod1>:<mod2>:<mod3>:<mod4>*<amount>*UN*<qty>*<pos>`. Qualifier `HC` for both CPT and HCPCS (PAS note: "when receiving the codes from an X12 system, the system returned will be HCPCS"). `ER` jurisdiction-specific, `IV` HIEC, `WK` ABC codes only if the CG permits. X12 service-type-only items (no procedure code) omit SV1 and rely on UM03. **HPHC: oral surgery must be coded with CPT/HCPCS in SV1; institutional lines use SV2; dental uses SV3.** | ✔ (SV1/SV2/SV3 usage) / CG |
| SV2 (institutional) | `item.revenue` (NUBC revenue code → SV201), `item.productOrService` (SV202 `HC:` composite), `item.unitPrice`/`net` (SV203), `item.quantity` (SV204 `DA` days / `UN` units, SV205), `item.extension[revenueUnitRateLimit]` (SV206), `item.extension[nursingHomeResidentialStatus]` (SV209), `item.extension[nursingHomeLevelOfCare]` (SV210) | `SV2*<rev>*HC:<code>*<amount>*DA*<qty>*<rate>***<status>*<loc>`. **HPHC: a revenue code is required when home care services are identified by revenue code per the provider contract.** SV1 and SV2 cannot both appear on one authorization (payer constraint seen in other CGs; confirm for HPHC) – the bridge must reject mixed professional/institutional items in one Claim, or split into two 278s and two ClaimResponses (not IG-friendly). | ✔ / CG |
| SV3 / TOO (dental) | `Claim.type = oral`, `item.productOrService` (CDT), `item.bodySite`/`subSite` | Not supported by PAS profiles (use cases §3.3 "Loop 2000F SV3 – not currently mapped"). Out of scope unless the business needs dental PA via FHIR. | ✔ (HPHC mentions SV3) |
| HSD | `item.quantity` (PASQuantity: `value` + `code` from HSD01 list: `DY` days, `FL` units, `HS` hours, `MN` month, `VS` visits), `item.extension[requestedService] → ServiceRequest.occurrenceTiming` (PASTiming: `repeat.frequency/period/periodUnit` → HSD03/HSD04 frequency, `repeat.boundsDuration`/`count` → HSD05/HSD06 time period, `extension[calendarPattern]` 678 → HSD07, `extension[deliveryPattern]` 679 → HSD08) | `HSD*VS*12*DA*7*7*30` = 12 visits, one every 7 days, for 30 days (HSD01 qualifies HSD02, HSD03 qualifies HSD04, HSD05 qualifies HSD06). If only a count is known send `HSD*VS*12`. PAS restricts units to international units, days, units, minutes, hours, months, visits. Any other UCUM unit → OperationOutcome. | CG ? |
| PWK / MSG (2000F) | `item.informationSequence → supportingInfo[AdditionalInformation]/[MessageText]` | Item-scoped attachments/messages; same rules as 4.7 | CG ? |
| 2010F NM1 (Service Provider) | `Claim.careTeam[ItemClaimMember]` referenced by `item.careTeamSequence`, up to 10 | Same construction as 2010EA; typical roles `SJ` service provider, `FA` facility, `77` service location. REF/N3/N4/PER/PRV as above. | ✔ (`NM1*SJ` in samples) |

## 4.9 Terminology and formatting rules (the bridge's translation tables)

| Concept | FHIR | X12 | Rule |
|---|---|---|---|
| Diagnosis | `http://hl7.org/fhir/sid/icd-10-cm` | HI `ABK`/`ABF`/`ABJ`/`APR` | Remove `.`; uppercase; validate against ICD-10-CM edition valid on service date |
| Procedure | `http://www.ama-assn.org/go/cpt`, `http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets` | SV1/SV2 `HC` | 5 characters; modifiers 2 characters |
| Drug (medical benefit) | `http://hl7.org/fhir/sid/ndc` | SV1 `N4` composite (only if CG allows) or HCPCS J-code | Prefer HCPCS J-code; NDC 11-digit no hyphens |
| Service type | `https://codesystem.x12.org/005010/1365` | UM03 | Pass through; validate against the CG subset |
| Place of service | CMS POS code set | UM04-1 + `B`, SV105 | 2 digits |
| Type of bill / facility type | NUBC type of bill | UM04-1 + `A` | Digits 2–3 of TOB (e.g. `0111` → `11`) |
| Revenue code | NUBC revenue codes | SV201 | 4 digits with leading zero |
| Taxonomy | NUCC taxonomy | PRV03 `PXC` | 10 characters |
| Units | UCUM / X12 673 | HSD01, SV103, SV204 | Map `{d}`→`DA`, `min`→`MJ`, `h`→`HS`, `mo`→`MN`, `1`/`{unit}`→`UN`, `{visit}`→`VS`; PASQuantity should already carry the X12 code |
| Dates | `date` / `Period` | `D8` / `RD8` | Reject partial dates (PAS FullDateRule) |
| Money | `Money` | SV102/SV203 `R` | 2 decimals, no currency symbol, no thousands separators |
| Names / text | strings | AN fields | Uppercase, fold accents to ASCII, remove `*~:^` and control characters, collapse whitespace, truncate at X12 max with a warning logged |
| Phone | `ContactPoint` | PER04 | 10 digits, no punctuation; extension in `PER05 EX` |
| Identifiers | `Identifier` | NM109 / REF02 | Strip spaces and hyphens (HPHC member IDs); preserve case |
| Free text | `supportingInfo[MessageText]` | MSG01 | ≤ 264; split across MSG only where the TR3 allows repeats (2000E request: 1) |

## 4.10 Claim Update → 278 (UM02 = S / 4 / 3)

PAS sends the *whole* Claim with every item and supportingInfo, flagged:

| PAS flag | 278 rendering |
|---|---|
| `Claim.extension[certificationType] = 3` (cancel all) | One 2000E with `UM02 = 3`, `REF*BB` = previous authorization number (from the last ClaimResponse), no 2000F lines (HPHC Appendix F "Cancel – Ambulatory Surgery" shows the exact shape – obtain). |
| `item.extension[infoChanged] = changed` + `modifierExtension[infoCancelledFlag] = true` + `item.extension[certificationType] = 3` | 2000F line with `UM02 = 3`, `REF*BB` item auth number |
| `item.extension[infoChanged] = changed` (dates/quantity/provider changed) | 2000F line with `UM02 = S` (revised) or `4` (extension when only the period is extended) – rule: if only `servicedPeriod.end` moved later and quantity unchanged → `4`, else `S` |
| `item.extension[infoChanged] = added` | 2000F line with `UM02 = I` under the same 2000E (`UM02 = S` at event level) |
| unchanged items | **omitted** from the 278 ("From an X12 perspective, only those items/attachments that are being added, modified, or cancelled need to be present") |
| `supportingInfo` changed/added | new PWK (+275) |
| `Claim.related.claim` | used to find the previous 278 correlation record (TRN, REF*BB, REF*NT) |

HPHC business rule recovered: "Some data cannot be modified on any transactions. To change certain
items, you must submit a Cancel Request and submit a new Initial Request." The bridge must know
*which* fields are immutable (member, requesting provider, service type, admission vs outpatient are
typical) and, for those, either reject the update with an OperationOutcome telling the EHR to cancel
and resubmit, or perform cancel + new initial itself and return the *new* authorization – the
business must choose (open question).

Denied requests cannot be updated (PAS §7.2.10 note 2): reject with OperationOutcome
`business-rule` and instruct to submit a new Claim.

## 4.11 Claim Inquiry → 278 Inquiry (005010X215)

Only if eCare supports X215 (open question). Mapping is structurally identical for loops
2000A–2000D; the 2000E/2000F loops carry search criteria: `Claim.billablePeriod` → DTP*472
range, `item.productOrService` → SV1/UM03 (`not-applicable` → omit), `Claim.extension[authorizationNumber]` →
REF*BB, `administrationReferenceNumber` → REF*NT, `item.extension[certIssueDate/EffectiveDate/ExpirationDate]` →
DTP*050/AAH/036 equivalents per X215, `item.extension[reviewActionCode]` → HCR01 filter. PAS: a multi-diagnosis
inquiry must be split into one X215 per diagnosis and the results unioned.

## 4.12 Validation the bridge performs before sending (→ OperationOutcome 400)

1. Bundle: first entry is Claim/ClaimUpdate; every reference resolvable; unique fullUrls.
2. Claim: `use = preauthorization`, `status = active`, `insurer`, `patient`, `provider`,
   `insurance[0].coverage` present; ≥ 1 item; every item has `category`, `location`, `requestType`,
   `certificationType`; `sequence` unique.
3. Identifiers: member ID present and matches the HPHC format; requester NPI 10 digits + Luhn
   (reuse this repo's `Patterns.npi` check); TRN uniqueness.
4. Code systems: diagnosis in ICD-10-CM; procedure in CPT/HCPCS; units in the PAS list; service
   type in the HPHC subset; POS/TOB valid.
5. Limits: ≤ 12 diagnoses; ≤ 14 overall care team, ≤ 10 per item; ≤ 99 (or CG max) items; text
   lengths; MSG ≤ 264.
6. Mixed SV1/SV2 items → reject (or split, per business decision).
7. Update rules: `related.claim` known to the bridge; not previously denied; immutable fields unchanged.
