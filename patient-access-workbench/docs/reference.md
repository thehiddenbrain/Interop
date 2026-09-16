# Patient Access API Reference (CMS-9115-F + CMS-0057-F) — Source of Truth for the Test Workbench

Synthesized 2026-09-16 from five research threads (cms-9115-f, cms-0057-f, pdex-prior-auth, smart-auth, onyx-safhir, test-kits) and four verifier verdicts. Every verifier correction has been applied; where a verdict contradicted a research finding, the verdict wins.

## Confidence legend

- **[verified]** — read in-session from a primary artifact: a local HL7 IG package (the HL7 IG packages listed below), HL7 IG source repositories on GitHub (tags 2.0.0/2.1.0/2.2.0 of davinci-epdx, smart-app-launch commits), Inferno test-kit source, the FHIR IG registry, or the Federal Register public-inspection PDFs of CMS-9115-F (2020-05050), CMS-0057-F (2024-00895) and HTI-1 (2023-28857).
- **[verified: eCFR mirror]** — read from a faithful GitHub mirror of the eCFR (snapshot 2025-02-06, carrying eCFR source notes such as "85 FR 25632, May 1, 2020, as amended at 89 FR 8974, Feb. 8, 2024"). ecfr.gov itself was egress-blocked. Treat as verified text, but re-read from ecfr.gov before quoting paragraph letters in a compliance report.
- **[likely]** — well-known fact, or a primary page seen only through search-engine excerpts.
- **[unverified]** — model recollection, secondary source, or analyst inference. Do not hard-code without confirmation.

Web access in the research sessions: WebSearch worked; raw.githubusercontent.com / github.com / s3.amazonaws.com (public-inspection FR copies) worked; ecfr.gov, federalregister.gov, govinfo.gov, cms.gov, healthit.gov, hl7.org, build.fhir.org, packages.fhir.org, simplifier.net, inferno.healthit.gov, onyxhealth.io, docs.safhir.io, docs.onyxos.io, touchstone.aegis.net were egress-blocked.

Local IG packages available to the workbench (all FHIR 4.0.1) [verified]:

| Package | Version | Canonical | Build date |
|---|---|---|---|
| hl7.fhir.us.carin-bb | 2.1.0 (STU 2.1) | http://hl7.org/fhir/us/carin-bb | 2025-02-18 |
| hl7.fhir.us.davinci-pdex | 2.1.0 (STU 2.1) | http://hl7.org/fhir/us/davinci-pdex | 2025-06-18 |
| hl7.fhir.us.davinci-pas | 2.2.0-ballot (2025Sep) | http://hl7.org/fhir/us/davinci-pas | 2025-07-30 |
| hl7.fhir.us.davinci-drug-formulary | 2.1.0 (STU 2.1) | http://hl7.org/fhir/us/davinci-drug-formulary | 2025-02-26 |
| hl7.fhir.uv.smart-app-launch | 2.2.0 (STU 2.2) | http://hl7.org/fhir/smart-app-launch | 2024-04-30 |
| hl7.fhir.us.davinci-pdex-plan-net | 1.2.0 (STU 1.2) | http://hl7.org/fhir/us/davinci-pdex-plan-net | 2025-02-25 |
| us-core-server-master.json | CI build (profiles suffixed 10.0.0-ballot), date 2026-04-16, no version element | http://hl7.org/fhir/us/core/CapabilityStatement/us-core-server | n/a |

---

## 1. Regulatory requirements matrix

Section numbering below uses the post-CMS-0057-F codification (current eCFR). Program parallels: MA = 42 CFR 422.119/422.120/422.121/422.122; Medicaid FFS = 42 CFR 431.60/431.61/431.70/431.80 (+440.230); Medicaid managed care = 42 CFR 438.242 (+438.210); CHIP FFS = 42 CFR 457.730/457.731/457.732/457.760; CHIP managed care = 42 CFR 457.1233(d) (incorporates 438.242); QHP issuers on individual-market FFEs = 45 CFR 156.221/156.222/156.223.

### 1.1 CMS-9115-F baseline (Interoperability and Patient Access)

| ID | Topic | Citation | Compliance date | Requirement / data content | Timing | Confidence |
|---|---|---|---|---|---|---|
| R-01 | Rule identity | CMS-9115-F, RIN 0938-AT79, "Medicare and Medicaid Programs; Patient Protection and Affordable Care Act; Interoperability and Patient Access for MA Organizations and Medicaid Managed Care Plans, State Medicaid Agencies, CHIP Agencies and CHIP Managed Care Entities, Issuers of QHPs on the FFEs, and Health Care Providers"; FR Doc. 2020-05050; published 85 FR May 1, 2020 (eCFR source notes: 422.119 at 85 FR 25632, 422.120 at 25633, 431.60 at 25634, 438.242 at 25635, 457.730 at 25636, 156.221 at 25638) | Effective June 30, 2020 (DATES: 60 days after publication) | Impacted payers: MA organizations, state Medicaid FFS agencies, Medicaid MCO/PIHP/PAHP, CHIP FFS agencies, CHIP managed care entities, QHP issuers on individual-market FFEs | — | Publication date, section pages, FR Doc number, effective date **[verified]** (public-inspection PDF + eCFR mirror). Start page 85 FR 25510 **[likely]** |
| R-02 | Codification of the Patient Access API | 42 CFR 422.119 (MA); 431.60 (Medicaid FFS); 438.242(b)(5) (Medicaid MCO/PIHP/PAHP, "as if such requirements applied directly"); 457.730 (CHIP FFS); 457.1233(d) (CHIP managed care — a single paragraph incorporating 438.242, no numbered subparagraphs); 45 CFR 156.221 (QHP issuers on individual-market FFEs) | Jan 1, 2021 (see R-16) | Denial/discontinuation criteria live at 422.119(e), 431.60(e), 457.730(e), 156.221(e); managed care plans reach them via 438.242(b)(5) -> 431.60(e) and 457.1233(d) -> 438.242 | — | **[verified: eCFR mirror]** |
| R-03 | Core obligation | 422.119(a) (parallels 431.60(a), 457.730(a), 156.221(a) "Subject to paragraph (h)") | Jan 1, 2021 | "must implement and maintain a standards-based Application Programming Interface (API) that permits third-party applications to retrieve, with the approval and at the direction of a current individual MA enrollee or the enrollee's personal representative, data specified in paragraph (b) of this section through the use of common technologies and without special effort from the enrollee." | — | **[verified: eCFR mirror]** |
| R-04 | MA data content | 422.119(b)(1)(i)-(iii); applicability 422.119(h) | Jan 1, 2021; data with date of service on or after Jan 1, 2016 | (i) adjudicated claims incl. claims data for payment decisions that may be appealed, were appealed, or are in the process of appeal, and provider remittances and enrollee cost-sharing; (ii) encounter data from capitated providers; (iii) all 45 CFR 170.213 data classes/elements the MA organization maintains (clinical data incl. lab results) | (i) no later than one (1) business day after a claim is processed; (ii) no later than 1 business day after encounter data is received; (iii) no later than 1 business day after data is received | **[verified: eCFR mirror]** |
| R-05 | MA-PD formulary and Part D claims | 422.119(b)(2) | Jan 1, 2021 | (i) adjudicated claims for covered Part D drugs incl. remittances and enrollee cost-sharing; (ii) formulary data incl. covered Part D drugs and any tiered formulary structure or utilization management procedure pertaining to those drugs | (i) no later than 1 business day after adjudication; (ii) no explicit timing in (b)(2) | **[verified: eCFR mirror]** |
| R-06 | Medicaid / CHIP FFS data content | 431.60(b)(1)-(4); 457.730(b)(1)-(4) | Jan 1, 2021 | (1) adjudicated claims incl. appealable/appealed decisions, provider remittances and beneficiary cost-sharing; (2) encounter data from providers (other than MCOs/PIHPs/PAHPs) compensated on a capitation basis; (3) 170.213 data incl. lab results if maintained; (4) information about covered outpatient drugs and updates, incl. preferred drug list (PDL) information where applicable | (1) 1 business day after processing; (2) 1 business day after receipt; (3) 1 business day after receipt; (4) no later than 1 business day after the effective date of the information or update | **[verified: eCFR mirror]** |
| R-07 | Medicaid managed care pass-through | 438.242(b)(5) (current text): "Subject to paragraph (b)(8) ... implement and maintain a Patient Access API required in § 431.60 ... as if such requirements applied directly to the MCO, PIHP, or PAHP and: (i) Include all encounter data, including encounter data from any network providers the MCO, PIHP, or PAHP is compensating based on capitation payments and adjudicated claims and encounter data from any subcontractors. (ii) Exclude covered outpatient drugs as defined in section 1927(k)(2) of the Act. (iii) Report metrics specified in § 431.60(f) at the plan level." Timing at 438.242(b)(9) | (b)(9)(i): 431.60 requirements "by January 1, 2021" (not "rating periods on or after"); metrics beginning 2026 by March 31 | — | — | **[verified: eCFR mirror]**. Caveat: mirrored 438.242(b)(9)(ii) says plans must comply with 431.60(b)(5) [PA data] and (g) "by the rating period beginning on or after January 1, 2026", inconsistent with the Jan 1, 2027 date inside 431.60(b)(5); (b)(6)(iii) cites 431.60(h) while (b)(5)(iii) cites 431.60(f). Flag as a CFR redesignation artifact; do not rely on 2026 for managed-care PA data. |
| R-08 | CHIP managed care | 457.1233(d): CHIP MCOs/PIHPs/PAHPs comply with 438.242 "except that the applicability date in 438.242(e) does not apply"; source note ends 85 FR 72842, Nov. 13, 2020 | via 438.242 | Inherits Patient Access API (438.242(b)(5)), Provider Directory API ((b)(6)), 2027 APIs ((b)(7)), denial reason ((b)(8)) | — | **[verified: eCFR mirror]** |
| R-09 | QHP issuers on FFEs | 45 CFR 156.221(a)-(i) | Plan years beginning on or after Jan 1, 2021 ((i)); paragraph (f) metrics beginning 2026 | (b): claims (1 business day after adjudication), encounter data from capitated providers, 170.213 data if maintained, DOS on/after Jan 1, 2016. (h)(1): narrative justification in the QHP application if the issuer believes it cannot satisfy (a)-(g) (reasons, impact on enrollees, current/proposed means of providing information, solutions and timeline); (h)(2): FFE may grant an exception to (a)-(g) if in the interests of qualified individuals. (i): applies to QHP issuers on an individual-market FFE, **excluding issuers offering only stand-alone dental plans** (whole section incl. (f)). No provider-directory API section in 156.221-156.223 (45 CFR 156.230 machine-readable directory already applies) | 1 business day | **[verified: eCFR mirror]**; 156.230 rationale **[likely]** |
| R-10 | Clinical data scope | 422.119(b)(1)(iii), 431.60(b)(3), 457.730(b)(3), 156.221(b)(1)(iii) cross-reference 45 CFR 170.213 generically (retained by CMS-0057-F). 170.213 currently: (a) USCDI v1 (July 2020 Errata) — adoption expires Jan 1, 2026; (b) USCDI v3 | — | All 170.213 data classes and elements the payer maintains | 1 business day after receipt | **[verified: eCFR mirror]** |
| R-11 | Technical standards | 422.119(c)(1) (parallels 431.60(c)(1), 457.730(c)(1), 156.221(c)(1)), as amended by CMS-0057-F: "Must implement and maintain API technology conformant with 45 CFR 170.215(a)(1), (b)(1)(i), (c)(1), and (e)(1)" = FHIR 4.0.1, US Core STU 3.1.1, SMART App Launch 1.0.0 (incl. "SMART Core Capabilities"), OpenID Connect Core 1.0 errata set 1. Bulk Data (d)(1) deliberately omitted. (c)(2): routine testing and monitoring of API function, privacy and security. (c)(3)(i): content/vocabulary standards at 45 CFR 170.213; (c)(3)(ii): 45 CFR part 162 and 42 CFR 423.160 where required. Original 9115-F text cited 170.215 generically; at that time 170.215(a)(2) was US Core STU **3.1.0** (per the 9115-F preamble); 3.1.1 came later | Jan 1, 2021 | — | — | (c)(1),(c)(3) **[verified: eCFR mirror + public-inspection PDF]**; (c)(2) content **[likely]**; 3.1.0->3.1.1 via ONC Nov 2020 IFC **[unverified]** |
| R-12 | Updated-version flexibility | 422.119(c)(4) (parallels 431.60(c)(4), 457.730(c)(4), 156.221(c)(4)) — NOT (c)(3): "May use an updated version of any standard or all standards required under paragraph (c)(1) or (3) ... where: (i) Use of the updated version ... is required by other applicable law; or (ii) ... is not prohibited under other applicable law, provided that: (A) For content and vocabulary standards other than those at 45 CFR 170.213, the Secretary has not prohibited use ...; (B) For standards at 45 CFR 170.213 and 45 CFR 170.215, the National Coordinator has approved the updated version for use in the ONC Health IT Certification Program; and (C) Using the updated version ... does not disrupt an end user's ability to access the data specified in paragraph (b) of this section or §§ 422.120, 422.121, and 422.122 through the required APIs." | — | This is the path by which US Core 6.1.0/7.0.0 and SMART 2.x are **permitted, not required**. The regulation does not cite 45 CFR 170.405 (SVAP); that is a gloss | — | **[verified: eCFR mirror + public-inspection PDF]** |
| R-13 | API documentation | 422.119(d) (parallels 431.60(d), 457.730(d), 156.221(d)) | Jan 1, 2021 | Publicly accessible complete documentation: (1) API syntax, function names, required and optional parameters and data types, return variables and types/structures, exceptions and exception-handling methods and their returns; (2) software components and configurations an app must use to interact with the API and process responses; (3) all technical requirements and attributes necessary for an app to be registered with any authorization server(s). "Publicly accessible" = any person using commonly available internet technology can access it without preconditions or additional steps such as a fee, receiving a copy via email, registering or creating an account, or reading promotional material / agreeing to future communications | — | **[verified: eCFR mirror]** |
| R-14 | Denial / discontinuation of app access | 422.119(e) (parallels 431.60(e), 457.730(e), 156.221(e)) | Jan 1, 2021 | May deny or discontinue a third-party app's connection only if (1) it reasonably determines, consistent with its security risk analysis under 45 CFR part 164 subpart C, that allowing the app to connect or remain connected would present an unacceptable level of risk to the security of PHI on its systems (156.221(e)(1): "personally identifiable information, including protected health information"); and (2) "Makes this determination using objective, verifiable criteria that are applied fairly and consistently across all apps and developers through which parties seek to access electronic health information, as defined in 45 CFR 171.102, including but not limited to, criteria that rely on automated monitoring and risk mitigation tools." Privacy-policy concerns alone are not grounds; payers may only educate. App attestation (privacy-policy provisions) is preamble guidance ("may"), not a regulatory mandate | — | Regulatory text **[verified: eCFR mirror]**; attestation-as-guidance **[likely]** |
| R-15 | Enrollee educational resources | 422.119(g), 431.60(g), 457.730(g), 156.221(g) (post-0057-F lettering; (f) is now metrics in all four) | Jan 1, 2021 | Non-technical, plain-language resources for current and former enrollees: (1) general steps to protect privacy/security, factors in selecting an app incl. secondary uses of data and the importance of understanding an app's security/privacy practices; (2) overview of which organizations/individuals are and are not likely HIPAA covered entities, OCR and FTC oversight, and how to complain to OCR and FTC | — | **[verified: eCFR mirror]** |
| R-16 | Compliance / enforcement dates | 422.119(h), 431.60(h), 457.730(h): "beginning January 1, 2021"; 156.221(i): plan years beginning on or after Jan 1, 2021; 438.242(b)(9)(i): by Jan 1, 2021 | Jan 1, 2021 | CMS enforcement discretion for Patient Access and Provider Directory APIs until July 1, 2021 — not in rule text | — | Dates **[verified: eCFR mirror]**; July 1, 2021 discretion **[likely]** |
| R-17 | Provider Directory API | 422.120 (MA); 431.70 (Medicaid FFS; data per section 1902(a)(83) of the Act); 438.242(b)(6) (Medicaid managed care); 457.760 (CHIP FFS); 457.1233(d) via 438.242(b)(6) (CHIP managed care). No 457.1233(d)(3) exists. No QHP requirement | Jan 1, 2021 (422.120(c), 431.70(c), 457.760(c)) | Minimum data (422.120(b)(1), 457.760(b)(1)): provider names, addresses, phone numbers, specialties; MA-PD pharmacy directory (422.120(b)(2)): pharmacy name, address, phone number, number of pharmacies in the network, mix/type (e.g. retail). "Conformant with the technical requirements at § 422.119(c), excluding the security protocols related to user authentication and authorization and any other protocols that restrict the availability of this information to particular persons or organizations"; "accessible via a public-facing digital endpoint on the [payer's] website"; 422.119(d)-style documentation. Recommended IG: Da Vinci PDex Plan-Net | Directory data available within 30 calendar days of receiving provider directory information or an update | **[verified: eCFR mirror]** |
| R-18 | 9115-F payer-to-payer exchange (superseded) | Original 422.119(f), 438.62(b)(1)(vi)-(viii), 457.1216, 156.221(f) | Was Jan 1, 2022 | USCDI data exchange at enrollee request, up to 5 years after disenrollment; CMS announced enforcement discretion Dec 8, 2021 (FR notice Dec 10, 2021, FR Doc. 2021-26764); never enforced; replaced by CMS-0057-F Payer-to-Payer API (R-26). Current 438.62 no longer has those subparagraphs and 457.1216 cross-references 438.62. PDex index.md: "This aspect of the CMS-9115 Interoperability and Patient Access Rule was never enforced." | — | PDex quote **[verified]**; history **[likely]** |
| R-19 | Implementation guides are recommended, not required | Preamble / CMS guidance | — | CMS "strongly encourages" CARIN BB (claims/EOB), PDex (clinical, PA), US Drug Formulary, Plan-Net; only 45 CFR 170.213/170.215 standards are legally required. PDex index.md: "Use of these implementation guides is not required but is recommended." | — | **[verified]** (PDex text, CMS-0057-F Table H3); CMS web guidance **[likely]** |
| R-20 | Bulk Data not required for Patient Access | 422.119(c)(1) cites (a)(1), (b)(1)(i), (c)(1), (e)(1) only; 422.121(a)(1)(ii) and (b)(1)(ii) cite (d)(1) | — | Bulk Data 1.0.0 group-export is required only for Provider Access and Payer-to-Payer APIs | — | **[verified: eCFR mirror + public-inspection PDF]** |

### 1.2 CMS-0057-F additions (Advancing Interoperability and Improving Prior Authorization)

| ID | Topic | Citation | Compliance date | Requirement / data content | Timing | Confidence |
|---|---|---|---|---|---|---|
| R-21 | Rule identity | CMS-0057-F, RIN 0938-AU87; FR Doc. 2024-00895; published Feb 8, 2024 (eCFR source notes 89 FR 8974 for 422.119/422.121, 89 FR 8976 for 422.122/422.568); released Jan 17, 2024 | Effective April 8, 2024 (DATES: 60 days after publication) | Impacted payers as in R-01 | — | FR Doc, publication and effective dates **[verified]** (public-inspection PDF); 89 FR 8758 start page and Jan 17 release **[likely]** |
| R-22 | New / amended sections | 42 CFR 422.121 "Access to and exchange of health data for providers and payers": (a) Provider Access API, (b) Payer-to-Payer API; 422.122 "Prior authorization requirements": (a) communicating a reason for denial, (b) Prior Authorization API, (c) publicly reporting PA metrics (no definitions paragraph); Medicaid FFS 431.61 (Provider Access (a) / P2P (b) / (c) extensions-exemptions) and 431.80 ((a) denial reason, (b) PA API, (c) extensions/exemptions; metrics at 440.230(e)(3)); CHIP FFS 457.731 and 457.732 ((a)-(d)); QHP 156.222 and 156.223 (with exception paragraphs); managed care 438.242(b)(7) (2027 APIs) and (b)(8) (denial reason), 438.210(d)/(f) | APIs: Jan 1, 2027 (MA, Medicaid/CHIP FFS); rating periods beginning on/after Jan 1, 2027 (Medicaid/CHIP managed care); plan years beginning on/after Jan 1, 2027 (QHP) | — | — | **[verified: eCFR mirror + public-inspection PDF]** |
| R-23 | **PA data in the Patient Access API** | 422.119(b)(1)(iv) (MA, QHP 156.221(b)(1)(iv)); 431.60(b)(5)(i)/(ii) (Medicaid FFS); 457.730(b)(5)(i)/(ii) (CHIP FFS). Drugs defined at 422.119(b)(1)(v) ("any and all drugs covered by the MA organization, including any products that constitute a Part D drug ... covered under the Medicare Part D benefit"), 431.60(b)(6), 457.730(b)(6), 156.221(b)(1)(v) | Beginning Jan 1, 2027 (QHP: plan years beginning on/after Jan 1, 2027) | "(A) The prior authorization request and decision, including all of the following, as applicable: (1) The prior authorization status. (2) The date the prior authorization was approved or denied. (3) The date or circumstance under which the prior authorization ends. (4) The items and services approved. (5) If denied, a specific reason why the request was denied. (6) Related structured administrative and clinical documentation submitted by a provider." **"Quantity used to date" is NOT a regulatory element** (preamble: CMS is "not requiring payers to share the quantity of items or services used under a prior authorization"). Excludes drugs | (B)(1) accessible no later than 1 business day after the payer receives a PA request; (B)(2) updated no later than 1 business day after any status change; (B)(3) accessible for the duration the authorization is active and at least 1 year after the PA's last status change | **[verified: eCFR mirror + public-inspection PDF]** |
| R-24 | Patient Access API usage metrics | 422.119(f) (MA, contract level); 431.60(f) and 457.730(f) (States, State level, "unique beneficiaries"); 156.221(f) (issuer level); 438.242(b)(5)(iii)/(b)(9)(iii) (managed care plans, plan level). Applicability: 422.119(h), 431.60(h), 457.730(h), 156.221(i) | Beginning in 2026, by March 31 following each calendar year (first report: CY2025 data by March 31, 2026) | Aggregated, de-identified, previous calendar year: (1) total number of unique enrollees whose data are transferred via the Patient Access API to a health app designated by the enrollee; (2) total number of unique enrollees whose data are transferred more than once via the Patient Access API to a health app designated by the enrollee. "In the form and manner specified by the Secretary" | Annual | **[verified: eCFR mirror + public-inspection PDF]**. Submission channel (HPMS "Interoperability Reporting" for MA) **[unverified]** |
| R-25 | Provider Access API | 422.121(a); 431.61(a); 457.731(a); 156.222(a) | Jan 1, 2027 | To in-network providers with a treatment relationship: 422.119(b) data with DOS on/after Jan 1, 2016, excluding provider remittances and enrollee cost-sharing (thus including the (b)(1)(iv) PA data); attribution process ((a)(3)); enrollee opt-out before first exchange and at any time ((a)(4)(i)); enrollee education no later than 1 week after coverage start or 1 week after CMS enrollment acceptance, whichever later ((a)(4)(ii)(B)). Standards: 170.215(a)(1), (b)(1)(i), (c)(1), (d)(1) + 422.119(c)(2)-(4), (d), (e) | No later than 1 business day after receiving the provider's request | **[verified: eCFR mirror]** |
| R-26 | Payer-to-Payer API | 422.121(b) (MA — NOT 422.119(f)); 431.61(b); 457.731(b); 156.222(b) | Jan 1, 2027 (QHP plan years / managed-care rating periods on/after) | Opt-in model: offered to current enrollees no later than the compliance date; new enrollees "no later than 1 week after the coverage start date or no later than 1 week after receiving acceptance of enrollment from CMS, whichever is later" ((b)(2)(i)). Requests to previous payers "No later than 1 week after the payer has sufficient identifying information about previous payers and the enrollee has opted in" or "At an enrollee's request, within 1 week of the request" ((b)(4)(iv)), with an attestation of enrollment and opt-in ((b)(4)(iii)). Data ((b)(4)(ii)): "all of the following with a date of service within 5 years before the request: (A) Data specified in § 422.119(b) excluding ... (1) Provider remittances and enrollee cost-sharing information. (2) Denied prior authorizations. (B) Unstructured administrative and clinical documentation submitted by a provider related to prior authorizations." Concurrent payers: request at enrollment and "at least quarterly thereafter" ((b)(6)(i)). Standards: 170.215(a)(1), (b)(1)(i), (d)(1) (Bulk Data required; SMART (c)(1) not cited) | Responding payer: within 1 business day of an authenticated, attested request ((b)(5)) | **[verified: eCFR mirror]** |
| R-27 | Prior Authorization API (provider-facing) | 422.122(b); 431.80(b); 457.732(b); 156.223(b) | Jan 1, 2027 | FHIR API conformant with 422.119(c)(2)-(4), (d), (e) and 170.215(a)(1), (b)(1)(i), (c)(1) that (1) is populated with the payer's list of covered items/services (excluding drugs) requiring PA; (2) can identify all documentation required for approval; (3) supports a HIPAA-compliant PA request and response per 45 CFR part 162; (4) communicates approval (incl. date or circumstance under which the authorization ends), denial (with a specific reason), or request for more information. CMS "strongly encourages" Da Vinci CRD 2.0.1, DTR 2.0.1, PAS 2.0.1 (not required). HHS enforcement discretion: no HIPAA action against entities using an all-FHIR PA API instead of X12 278 | — | Regulatory text **[verified: eCFR mirror]**; IG encouragement and X12 278 discretion **[likely]** |
| R-28 | Specific denial reason (operational) | 422.122(a); 431.80(a); 457.732(a); 156.223(a); managed care 438.242(b)(8) (rating periods on/after Jan 1, 2026) | Jan 1, 2026 | "a specific reason for the denial, regardless of the method used to communicate that information" (excluding drugs); also element (5) of R-23 | — | **[verified: eCFR mirror]** |
| R-29 | PA decision timeframes (operational, not API) | 422.568(b)(1)(ii): "Beginning on or after January 1, 2026, for a service or item subject to the prior authorization rules in § 422.122, 7 calendar days" for standard organization determinations ((b)(1)(i) keeps 14 days otherwise); 422.572(a)(1) expedited 72 hours; 438.210(d)(1)(ii)(B) not more than 7 calendar days, (d)(2) 72 hours expedited; 440.230(e)(1)(i)/(ii) 7 days / 72 hours. Not applicable to QHP issuers on FFEs. 457.495 not checked | Jan 1, 2026 | — | 72 h expedited / 7 calendar days standard | **[verified: eCFR mirror]** (457.495 **[unverified]**) |
| R-30 | Public PA metrics | 422.122(c) (MA contract level); 438.210(f) (plan level, "Beginning January 1, 2026"); 440.230(e)(3) (State level); 457.732(c); 156.223(c) (issuer level) | Beginning 2026, by March 31 for the previous calendar year (first: CY2025 data by March 31, 2026) | Posted on the payer website, excluding drugs, aggregated for all items and services: (1) list of all items/services requiring PA; (2) % standard requests approved; (3) % standard denied; (4) % standard approved after appeal; (5) % of requests with extended review timeframe and approved; (6) % expedited approved; (7) % expedited denied; (8) average and median time from submission to determination, standard; (9) average and median time, expedited | Annual | **[verified: eCFR mirror]**; CMS template PDF **[likely]** |
| R-31 | HIPAA X12 278 enforcement discretion | HHS/CMS National Standards Group statement (letter dated 2024-02-28) and FAQ | — | No Administrative Simplification enforcement against covered entities using an all-FHIR PA API instead of X12 278 as described in CMS-0057-F | — | **[likely]** |
| R-32 | MIPS / Promoting Interoperability ePA measure | CMS-0057-F | CY2027 performance period (MIPS eligible clinicians) / CY2027 EHR reporting period (hospitals, CAHs) | "Electronic Prior Authorization" yes/no attestation (or exclusion) under the Health Information Exchange objective | — | **[likely]** |
| R-33 | IG versions recommended by CMS-0057-F (Table H3) | CMS-0057-F Table H3 | — | Patient Access API: required 170.215(a)(1) FHIR 4.0.1, (b)(1)(i) US Core 3.1.1, (c)(1) SMART 1.0.0, (e)(1) OIDC; recommended CARIN BB STU 2.0.0, PDex STU 2.0.0, US Drug Formulary STU 2.0.1 (Plan-Net STU 1.1.0 for the Provider Directory API). Provider Access / Payer-to-Payer: Bulk Data (d)(1) required, SMART App Launch 2.0.0 recommended "to support Backend Services Authorization". PA API: CRD 2.0.1, DTR 2.0.1, PAS 2.0.1 recommended. Preamble notes US Core 3.1.1 and SMART 1.0.0 adoptions "expire on January 1, 2026" and cites HTI-1 as 89 FR 1192. CMS's web page later lists CARIN BB "STU 2.0.0 and 2.1.0" and PDex "STU 2.0.0 and 2.1.0" | — | — | Table H3 **[verified]** (public-inspection PDF); web-page versions **[likely]** |
| R-34 | CMS-0062-P (proposed, not final) | "2026 CMS Interoperability Standards and Prior Authorization for Drugs" proposed rule; FR Doc. 2026-07205; published April 14, 2026; comments closed June 15, 2026 | Proposed Oct 1, 2027 (drug PA via PA API / NCPDP) and Jan 1, 2028 expirations of older IG versions | Proposes extending PA requirements to drugs, updating required/recommended IG versions (e.g. CARIN BB 2.0.0/2.1.0, PDex 2.0.0/2.1.0, USDF 2.0.1 or 2.1.0, CRD/DTR/PAS newer versions, PAS 2.2.1 to become required per PDex change history), and requiring payers to report API endpoints and usage metrics for all five APIs. Not final as of Sept 16, 2026 | — | Existence/date/title **[verified]** (FR RSS mirror + PDex changehistory); content **[likely]**; 91 FR 19890 **[unverified]** |
| R-35 | ONC context | HTI-1 (89 FR 1192, published Jan 9, 2024, effective 30 days later; correction to March 11, 2024 unverified) revised 170.215 (see section 2); HTI-4 (ePA/RTPB certification criteria, 45 CFR 170.315(g)(31)-(33) adopting CRD/DTR/PAS 2.0.1; published Aug 4, 2025 = 90 FR 37208 amendment link on 170.215; effective Oct 1, 2025); HTI-5 deregulatory proposed rule (Dec 29, 2025) | — | Provider-side certification, not a payer Patient Access requirement. Whether any 2025-2026 ONC rule added US Core 7.0.0/8.0.0, SMART 2.2.0 or Bulk 2.0.0 to 170.215 is unconfirmed; SVAP lists US Core 7.0.0/8.0.1 and SMART 2.0.0/2.2.0 as approved | — | HTI-1 text **[verified]**; HTI-4 amendment link **[verified: eCFR mirror]**; HTI-4/HTI-5 details **[likely]**; SVAP list **[likely]** |

### 1.3 Quantitative limits summary (for check thresholds)

| Limit | Value | Source | Confidence |
|---|---|---|---|
| Claims (adjudicated) availability | no later than 1 business day after a claim is processed/adjudicated | 422.119(b)(1)(i), 431.60(b)(1), 156.221(b)(1)(i) | [verified: eCFR mirror] |
| Encounter data availability | 1 business day after receipt | 422.119(b)(1)(ii), 431.60(b)(2) | [verified: eCFR mirror] |
| Clinical (USCDI) data availability | 1 business day after receipt | 422.119(b)(1)(iii), 431.60(b)(3) | [verified: eCFR mirror] |
| Medicaid covered outpatient drug / PDL info | 1 business day after effective date of information or update | 431.60(b)(4), 457.730(b)(4) | [verified: eCFR mirror] |
| PA data first availability | 1 business day after PA request receipt | 422.119(b)(1)(iv)(B)(1) | [verified: eCFR mirror] |
| PA status change propagation | 1 business day after any status change | 422.119(b)(1)(iv)(B)(2) | [verified: eCFR mirror] |
| PA retention | while active + at least 1 year after last status change | 422.119(b)(1)(iv)(B)(3) | [verified: eCFR mirror] |
| Historical depth | DOS on or after Jan 1, 2016 | 422.119(h), 156.221(i) | [verified: eCFR mirror] |
| Provider directory freshness | within 30 calendar days of receipt/update | 422.120, 431.70, 457.760 | [verified: eCFR mirror] |
| Payer-to-payer response | 1 business day; requests within 1 week; 5-year DOS window; quarterly for concurrent payers | 422.121(b) | [verified: eCFR mirror] |
| Metrics reporting | annually by March 31, from 2026 | 422.119(f) | [verified: eCFR mirror] |
| PA decision | 72 h expedited / 7 calendar days standard from Jan 1, 2026 | 422.568, 422.572, 438.210(d), 440.230(e) | [verified: eCFR mirror] |
| Phrase "no more than" | absent from the Patient Access regulatory text | text search | [verified: eCFR mirror] |

Non-quantitative limits on payer conduct: denial only on objective security-risk criteria ((e)); documentation free of fee/registration/email preconditions ((d)); Provider Directory API without user authentication; data retrievable "without special effort" ((a)).

---

## 2. Standards and IG versions

### 2.1 45 CFR 170.215 as codified (HTI-1 text, 89 FR 1428; identical in the mirror copy carrying the "90 FR 37208, Aug. 4, 2025" amendment link) [verified: eCFR mirror + HTI-1 public-inspection PDF]

| Paragraph | Standard | Expiration | Cited by (Patient Access) |
|---|---|---|---|
| (a)(1) | HL7 FHIR Release 4.0.1 | none | 422.119(c)(1) |
| (a)(2) | [Reserved] | — | — |
| (b)(1)(i) | HL7 FHIR US Core Implementation Guide STU 3.1.1 | "The adoption of this standard expires on January 1, 2026" | 422.119(c)(1) |
| (b)(1)(ii) | US Core Implementation Guide STU 6.1.0 | none | permitted via 422.119(c)(4) |
| (b)(2) | [Reserved] | — | — |
| (c)(1) | HL7 SMART Application Launch Framework IG Release 1.0.0, incl. mandatory support for the "SMART Core Capabilities" | expires January 1, 2026 | 422.119(c)(1) |
| (c)(2) | HL7 SMART App Launch IG Release 2.0.0, "including mandatory support for the 'Capability Sets' of 'Patient Access for Standalone Apps' and 'Clinician Access for EHR Launch'; all 'Capabilities' as defined in '8.1.2 Capabilities,' excepting the 'permission-online' capability; 'Token Introspection' as defined in '7 Token Introspection'" | none | permitted via 422.119(c)(4); recommended by CMS for backend services |
| (d)(1) | FHIR Bulk Data Access (Flat FHIR) (v1.0.0: STU 1), incl. mandatory support for the "group-export" OperationDefinition | **none** (no expiration) | not cited for Patient Access; cited by 422.121(a)/(b) |
| (d)(2) | [Reserved] — **Bulk Data 2.0.0 is not adopted in 170.215** | — | SVAP/"updated version" route only |
| (e)(1) | OpenID Connect Core 1.0, incorporating errata set 1 | none | 422.119(c)(1) |
| (e)(2) | [Reserved] | — | — |

45 CFR 170.213: (a) USCDI v1 (July 2020 Errata), adoption expires Jan 1, 2026; (b) USCDI v3 [verified: eCFR mirror].

Workbench posture for 2026: the codified CMS baseline for the Patient Access API is still FHIR 4.0.1 + US Core 3.1.1 + SMART 1.0.0 + OIDC (422.119(c)(1)); US Core 6.1.0/7.0.0 and SMART 2.0.0/2.2.0 are permitted upgrades under (c)(4) (National-Coordinator-approved, non-disruptive). Support both families; grade a SMART-1.0.0-only server as "legacy-conformant", not failing. [verified: eCFR mirror] for the citations; the posture itself is analyst guidance.

### 2.2 Implementation guides

| Standard / IG | Regulatory status | Published versions | Version under test (local) | Canonical URL / package | Notes | Confidence |
|---|---|---|---|---|---|---|
| HL7 FHIR R4 | Required, 170.215(a)(1) | 4.0.1 | 4.0.1 | http://hl7.org/fhir | All local IGs declare fhirVersions 4.0.1 | [verified] |
| US Core | 3.1.1 required (b)(1)(i), 6.1.0 adopted (b)(1)(ii); 7.0.0 / 8.0.x SVAP-approved | 3.1.1, 4.0.0, 5.0.1, 6.1.0, 7.0.0, 8.0.0; registry lists STU9 9.0.0 as current edition; local CS is a 10.0.0-ballot CI build | none published locally (CI CapabilityStatement only) | http://hl7.org/fhir/us/core ; hl7.fhir.us.core | Obtain a published 3.1.1 and 6.1.0 CapabilityStatement/package before generating tests; PDex 2.1.0 depends on 7.0.0 + 6.1.0 + 3.1.1; CARIN BB 2.1.0 on 6.1.0 + 3.1.1 | registry/local **[verified]**; SVAP **[likely]** |
| CARIN IG for Blue Button (C4BB) | Recommended (CMS-0057-F Table H3: STU 2.0.0; CMS web: 2.0.0 and 2.1.0) | Current published edition **2.2.0 (STU 2.2, http://hl7.org/fhir/us/carin-bb/STU2.2, depends on US Core 7.0.0 and 6.1.0)**; 2.1.0 (STU 2.1); earlier 1.0.0/1.1.0/1.2.0/2.0.0 (history not verifiable from registry) | 2.1.0 (STU 2.1, built 2025-02-18; deps US Core 6.1.0 + 3.1.1, hl7.terminology.r4 6.2.0, us.nlm.vsac 0.21.0, hl7.fhir.uv.extensions.r4 5.2.0) | http://hl7.org/fhir/us/carin-bb ; hl7.fhir.us.carin-bb | Inferno CARIN kit tests only 1.1.0 and 2.0.0. Parameterize version | 2.2.0 current **[verified]** (IG registry + HL7/carin-bb source); 2.1.0 facts **[verified]**; older history **[likely]** |
| Da Vinci PDex | Recommended (Table H3: STU 2.0.0; CMS web: 2.0.0 and 2.1.0) | STU2 = 2.0.0 (deps US Core 3.1.1, HRex 1.0.0, PAS 2.0.1); STU 2.1 = 2.1.0; **STU 2.2 = 2.2.0, current published edition at http://hl7.org/fhir/us/davinci-pdex/STU2.2** (git tag 2.2.0 ~2026-08-25; peer-review block vote 2026-05-18) | 2.1.0 (built 2025-06-18; deps US Core 7.0.0 / v610 6.1.0 / 3.1.1, HRex 1.1.0, CARIN BB 2.1.0, CRD 2.1.0, PAS 2.1.0, ATR 2.1.0, Plan-Net 1.2.0, NDH 1.0.0, DTR 2.1.0, extensions.r4 5.2.0, terminology.r4 6.3.0) | http://hl7.org/fhir/us/davinci-pdex ; hl7.fhir.us.davinci-pdex | 2.2.0 deps: HRex 1.2.0, CARIN BB 2.2.0, CRD 2.2.1, PAS 2.2.1, DTR 2.1.0, extensions 5.3.0, terminology 7.2.0. 2.2.0 breaking changes: $bulk-member-match returns Group ndjson; $everything SHALL return claims EOBs; P2P payload SHOULD->SHALL; 5-year window is a floor; BulkMemberMatch scope URL CamelCase; adds Provider Access V2 ($provider-member-match) and PAS 2.2.1 extensions. STU 2.0.0 date (Oct 20, 2023) and 2.2.0 HL7 announcement date (2026-08-21) unverified | **[verified]** (registry + davinci-epdx tags 2.0.0/2.1.0/2.2.0) |
| Da Vinci HRex | Dependency (Coverage profile, $member-match) | 1.0.0, 1.1.0, 1.2.0 | 1.1.0 (via PDex 2.1.0) | http://hl7.org/fhir/us/davinci-hrex | hrex-coverage is the Coverage profile in PDex pdex-server; member-match OperationDefinition | [verified] |
| Da Vinci PDex US Drug Formulary | Recommended (Table H3: STU 2.0.1) | 1.0.0, 1.0.1, 1.1.0, 2.0.0, 2.0.1, 2.1.0 (current) | 2.1.0 (built 2025-02-26; deps Plan-Net 1.2.0, HRex 1.1.0, US Core 7.0.0/6.1.0/3.1.1) | http://hl7.org/fhir/us/davinci-drug-formulary ; hl7.fhir.us.davinci-drug-formulary | The usdf-server CapabilityStatement inside the 2.1.0 package still carries version 2.0.1 (date 2022-07-17) and has no security element | [verified] |
| Da Vinci PDex Plan-Net | Recommended for Provider Directory API (Table H3: STU 1.1.0) | 1.0.0, 1.1.0, 1.2.0 (current) | 1.2.0 (built 2025-02-25) | http://hl7.org/fhir/us/davinci-pdex-plan-net ; hl7.fhir.us.davinci-pdex-plan-net | IG CapabilityStatement says "SHALL reject any unauthorized requests by returning an HTTP 401" — conflicts with the CMS no-user-auth requirement; CMS rule wins | [verified] |
| Da Vinci PAS | Recommended for the PA API (Table H3: STU 2.0.1); PDex 2.1.0 reuses PAS 2.1.0 extensions; PDex 2.2.0 reuses 2.2.1 | 2.0.1, 2.1.0, 2.2.1 (published; PDex 2.2.0 depends on it), 2.2.0-ballot | 2.2.0-ballot (2025Sep, built 2025-07-30) | http://hl7.org/fhir/us/davinci-pas ; hl7.fhir.us.davinci-pas | Operations Claim/$submit (OperationDefinition/Claim-submit) and Claim/$inquire (Claim-inquiry); profiles profile-claim, profile-claimresponse; CapabilityStatements EHRCapabilities, IntermediaryCapabilities. Extension canonical URLs unchanged across versions | [verified] |
| Da Vinci CRD / DTR | Recommended for PA API (2.0.1); HTI-4 certification (provider side) | CRD 2.0.1, 2.1.0, 2.2.1; DTR 2.0.1, 2.1.0 | none (PDex 2.1.0 deps only) | http://hl7.org/fhir/us/davinci-crd ; http://hl7.org/fhir/us/davinci-dtr | Not part of the Patient Access API surface | [verified] versions via PDex deps; HTI-4 **[likely]** |
| SMART App Launch | 1.0.0 required (c)(1); 2.0.0 adopted (c)(2); 2.2.0 SVAP-approved | 1.0.0 (Release 1.0.0 STU, package-list date 2018-11-13, fhirversion 3.0.1, http://hl7.org/fhir/smart-app-launch/1.0.0); 2.0.0 (STU2, package-list date 2021-10-30, .../STU2); 2.1.0 (STU 2.1, publication-request updated 2023-03-29; month unconfirmed); 2.2.0 (STU 2.2, tag 2024-04-26, package 2024-04-30, .../STU2.2) | 2.2.0 | http://hl7.org/fhir/smart-app-launch ; hl7.fhir.uv.smart-app-launch | PDex 2.1.0 CapabilityStatements declare hl7.fhir.uv.smart-app-launch\|2.1.0. Local package contains only App State / User-access Brands artifacts (no capability code CodeSystem) | [verified] |
| OpenID Connect Core 1.0 incorporating errata set 1 | Required (e)(1) | 1.0 | n/a | https://openid.net/specs/openid-connect-core-1_0.html | id_token, RS256 signing, fhirUser claim via SMART | [verified] (citation) |
| FHIR Bulk Data Access | 1.0.0 STU 1 adopted (d)(1), Provider Access / P2P only; 2.0.0 not in 170.215 | 1.0.0, 1.0.1, 2.0.0 | none local | http://hl7.org/fhir/uv/bulkdata | Inferno Bulk Data kit tests 1.0.1 and 2.0.0 | [verified] |
| Da Vinci ATR | Dependency ($davinci-data-export for Group-based bulk) | 2.1.0 | via PDex deps | http://hl7.org/fhir/us/davinci-atr | Used by PDex Provider Access / P2P bulk, not Patient Access | [verified] |
| USCDI | 170.213 (a) v1 (expires Jan 1, 2026), (b) v3 | v1 (July 2020 errata), v3 | — | https://www.healthit.gov/isa/united-states-core-data-interoperability-uscdi | Clinical data scope | [verified: eCFR mirror] |

### 2.3 Identifier systems relevant to member identification

| Identifier | System URI | Notes | Confidence |
|---|---|---|---|
| Payer member ID (C4BB `memberid` slice) | payer-specific (IG examples: https://www.xxxhealthplan.com/fhir/memberidentifier, https://www.upmchealthplan.com/fhir/memberidentifier, http://example.org/old-payer/identifiers/member; one PDex example misspells `iniquememberidentifier`) | identifier.type patterned to http://terminology.hl7.org/CodeSystem/v2-0203#MB; must be configurable per environment | [verified] |
| Unique member ID (C4BB `uniquememberid`) | payer-specific (https://www.xxxhealthplan.com/fhir/uniquememberidentifier) | type C4BBIdentifierType#um ("unique member identifier assigned by a payer across all lines of business") | [verified] |
| Medicare Beneficiary Identifier (MBI) | http://hl7.org/fhir/sid/us-mbi (THO NamingSystem cmsMBI; OID 2.16.840.1.113883.4.927; identifier type v2-0203#SB) | 11 characters, "represented without any spaces or dashes"; character set excludes B, I, L, O, S, Z. Valid-looking sample: 1EG4TE5MK73 (NOT 1A10B23CD45) | URI/OID/format **[verified]** (HL7/UTG source); excluded-letter rule **[likely]** |
| SSN / NPI / HICN | http://hl7.org/fhir/sid/us-ssn ; http://hl7.org/fhir/sid/us-npi ; http://hl7.org/fhir/sid/us-medicare | FHIR R4 identifier registry | [likely] |
| CMS Blue Button 2.0 internal id | https://bluebutton.cms.gov/resources/variables/bene_id | Medicare FFS only | [likely] |
| State Medicaid ID | no national URI; state/MCO-specific (OID- or domain-based) | configure per environment | [likely] |
| C4BBIdentifierType codes | http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBIdentifierType: payerid, naiccode, pat, um, uc | PDex equivalent http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PDexIdentifierType adds npi | [verified] |

---

## 3. Patient Access API surface

### 3.1 Server-wide conformance expectations (common to C4BB and PDex CapabilityStatements) [verified]

- Response classes the server SHALL return: 400 invalid parameter; 401/4xx unauthorized request; 403 insufficient scope; 404 unknown resource; 410 deleted resource.
- JSON SHALL be supported (C4BB: "Support json source formats for all CARIN-BB interactions"; PDex: json for all US Core and PDex interactions); XML SHOULD.
- Profiles SHALL be identified in `meta.profile` (C4BB SHALL; PDex SHOULD).
- C4BB: "Support the searchParameters on each profile individually and in combination"; "Support all profiles defined in this Implementation Guide"; referencePolicy `resolves` on every resource.
- PDex pdex-server: "Support the US Core 3.1.1 Patient resource profile" and at least one additional US Core/PDex profile; implementationGuide hl7.fhir.uv.smart-app-launch|2.1.0; patchFormat application/json-patch+json; transaction/batch/search-system/history-system MAY.
- Security text: C4BB "A server SHALL reject any unauthorized requests by returning an HTTP 401 'Unauthorized', HTTP 403 'Forbidden', or HTTP 404 'Not Found'"; PDex pdex-server "SHALL reject any unauthorized requests by returning an HTTP 401 unauthorized response code" (pdex-server-6-1 and US Core: 401/403/404). None of the local payer IG CapabilityStatements declare `rest.security.service` = SMART-on-FHIR or the oauth-uris extension; discovery must come from `/.well-known/smart-configuration`.
- Versioned references (C4BB and PDex EOB documentation, verbatim): "Payers SHALL use versioned references whenever they maintain point-in-time data (data that was effective as of the date of service or date of admission on the claim), but MAY use versionless references when they do not maintain versioned data. Clients MAY request referenced resources as part of an EOB search (by supplying the _include parameter) or directly using read or vread. Payers SHALL support both approaches, and SHALL return the same content for referenced resources in either case. ':iterate' should be used if you request to include Coverage:payor in the EOB response bundle, e.g. GET [base]/ExplanationOfBenefit?[parameter=value]&_include=ExplanationOfBenefit:coverage&_include:iterate=Coverage:payor."
- C4BB: Coverage returned must reflect data effective as of the claim date of service; all EOB reference resources carry must-support `meta.lastUpdated` and payers SHALL populate it.
- Search-parameter precision conventions (PDex/US Core searchParam documentation): token — client SHALL provide at least a code and MAY provide system|code, server SHALL support both; reference — client SHALL provide at least an id and MAY provide Type/id, server SHALL support both; date — client SHALL provide a value precise to the day (or second + offset where stated), server SHALL support that precision.

### 3.2 CARIN BB 2.1.0 surface — CapabilityStatement http://hl7.org/fhir/us/carin-bb/CapabilityStatement/c4bb (version 2.1.0, date 2022-09-11, formats xml+json, implementationGuide http://hl7.org/fhir/us/carin-bb/ImplementationGuide/hl7.fhir.us.carin-bb) [verified]

| Resource (expectation) | Profiles (supportedProfile) | Interactions | Search parameters (expectation) | _include / _revinclude | Key profile constraints |
|---|---|---|---|---|---|
| ExplanationOfBenefit (SHALL) | C4BB-ExplanationOfBenefit (abstract base), -Inpatient-Institutional, -Outpatient-Institutional, -Oral, -Pharmacy, -Professional-NonClinician (the five "-Basis" parents are concrete, abstract=false) | search-type SHALL, read SHALL, vread SHOULD | _id token SHALL; patient reference SHALL; _lastUpdated date SHALL; type token SHALL; identifier token SHALL; service-date date SHALL; service-start-date date SHALL; billable-period-start date SHALL | _include: ExplanationOfBenefit:patient, :provider, :care-team, :coverage, :insurer, :payee, :* ("_include:* SHALL be supported"; at minimum patient, provider, care-team, coverage, insurer, payee); _include:iterate=Coverage:payor | meta 1..1; meta.lastUpdated 1..1 MS; meta.profile 1..* (declare the specific type profile); identifier 1..* sliced by type, slice uniqueclaimid 1..1 (type patterned C4BBIdentifierType#uc, value 1..1); status 1..1; type 1..1 required binding claim-type (patterned institutional / oral / pharmacy in type profiles; Professional-NonClinician bound to C4BBProfessionalAndNonClinicianClaimType); use 1..1 **patternCode 'claim'**; patient 1..1; billablePeriod 1..1 with start 1..1; created 1..1; insurer 1..1 MS; provider 1..1 MS; outcome 1..1 (remittance-outcome); insurance 1..* (invariant EOB-insurance-focal: at most one focal=true), insurance.coverage 1..1; MS careTeam.provider/role, insurance.focal, item.adjudication.category; payee.type required C4BBPayeeType; payment.type required C4BBPayerClaimPaymentStatusCode; related.relationship required C4BBRelatedClaimRelationshipCodes; EOB-payee-other-type-requires-party |
| Coverage (SHALL) | C4BB-Coverage | search-type SHALL, read SHALL, vread SHOULD | _id SHALL; _lastUpdated SHOULD | _include Coverage:payor | meta.lastUpdated 1..1; meta.profile 1..*; subscriberId 1..1; status/beneficiary/payor 1..1; optional memberid identifier slice (0..1, v2-0203#MB) |
| Patient (SHALL) | C4BB-Patient | search-type MAY, read SHALL, vread SHOULD | _id SHALL; _lastUpdated SHOULD | — | identifier 1..* (system 1..1, value 1..1, MS) sliced by type: memberid 1..* (type patterned v2-0203#MB), uniquememberid 0..* (C4BBIdentifierType#um); identifier.type binding **extensible** to C4BBPatientIdentifierType; meta.profile 1..* |
| Organization (SHOULD) | C4BB-Organization | search-type MAY, read SHALL, vread SHOULD | _id SHALL; _lastUpdated SHOULD | — | payer/provider organizations; identifier slices (npi, payerid, naiccode etc.) |
| Practitioner (SHOULD) | C4BB-Practitioner | search-type MAY, read SHALL, vread SHOULD | _id SHALL; _lastUpdated SHOULD | — | NPI identifier |
| RelatedPerson (SHOULD) | C4BB-RelatedPerson | search-type MAY, read SHALL, vread SHOULD | _id SHALL; _lastUpdated SHOULD | — | — |

C4BB SearchParameter canonicals (http://hl7.org/fhir/us/carin-bb/SearchParameter/...): explanationofbenefit-patient (ExplanationOfBenefit.patient), -identifier, -type, -service-date (expression `ExplanationOfBenefit.billablePeriod | ExplanationOfBenefit.item.serviced`), -service-start-date (`ExplanationOfBenefit.billablePeriod.start | ExplanationOfBenefit.item.serviced.ofType(date) | ExplanationOfBenefit.item.serviced.ofType(Period).start`), -billable-period-start (`ExplanationOfBenefit.billablePeriod.start`), -care-team, -coverage, -insurer, -provider, coverage-payor (Coverage.payor), practitionerrole-organization, practitionerrole-practitioner. All three date parameters declare comparators eq, ne, gt, ge, lt, le, sa, eb, ap. [verified]

C4BB has no operations and no bulk export.

### 3.3 PDex 2.1.0 surface — CapabilityStatement http://hl7.org/fhir/us/davinci-pdex/CapabilityStatement/pdex-server (version 2.1.0, date 2024-10-20, kind requirements) [verified]

| Resource (expectation) | Profiles | Interactions | Search parameters (expectation) | _include / _revinclude / operations |
|---|---|---|---|---|
| ExplanationOfBenefit (SHALL) | pdex-priorauthorization only | search-type SHALL, read SHALL (no vread/history) | _id SHALL; patient SHALL (SearchParameter/explanationofbenefit-patient); _lastUpdated SHALL; type SHALL (explanationofbenefit-type); identifier SHALL (explanationofbenefit-identifier, status draft); service-date SHALL (explanationofbenefit-service-date, expression `ExplanationOfBenefit.billablePeriod | ExplanationOfBenefit.item.serviced`, comparators eq,ne,gt,ge,lt,le,sa,eb,ap). **Read-interaction documentation (normative): "Searches using service-date, _lastUpdated, or type require a patient search argument. _include:* SHALL be supported."** `use` (SearchParameter/explanationofbenefit-use, token, `ExplanationOfBenefit.use`, active) is defined in the package (since 2.0.0) but is NOT declared in any PDex CapabilityStatement (2.0.0, 2.1.0, 2.2.0) and is not a base R4 EOB search parameter | _include ExplanationOfBenefit:patient, :provider, :care-team, :coverage, :insurer, :*; _include:iterate=Coverage:payor; referencePolicy resolves. OpenAPI (openapi/pdex-server.openapi.json) exposes only the six params |
| Patient (SHALL) | us-core-patient | search-type, read, vread, history-instance, history-type | _id SHALL; identifier SHALL; name SHALL; birthdate, death-date, family, gender, given MAY | _revinclude Provenance:target; operation member-match (http://hl7.org/fhir/us/davinci-hrex/OperationDefinition/member-match) — payer-to-payer, not Patient Access |
| Coverage (SHALL) | http://hl7.org/fhir/us/davinci-hrex/StructureDefinition/hrex-coverage (pdex-server-6-1: us-core-coverage) | search-type, read, vread, history-instance, history-type | patient SHALL (us-core-coverage-patient) | _revinclude Provenance:target |
| Consent | pdex-provider-consent (opt-out recording, MAY) | read, create, search-type, vread, history-instance, history-type | _id (string), patient SHALL | — |
| Group | atr-group, pdex-provider-group, pdex-member-match-group | search-type, read | identifier SHALL, characteristic SHALL | operations bulk-member-match (http://hl7.org/fhir/us/davinci-pdex/OperationDefinition/BulkMemberMatch), davinci-data-export (ATR) — Provider Access / P2P only |
| MedicationDispense | pdex-medicationdispense | search-type, read, ... | status, type, patient | SHALL support _include=MedicationDispense:medication when external Medication is referenced |
| Provenance | us-core-provenance + pdex-provenance | read SHALL, search-type MAY | — | reached via _revinclude=Provenance:target on clinical resources |
| Medication | us-core-medication | read SHALL, search-type MAY | — | — |
| Clinical resources (SHALL unless noted): AllergyIntolerance, CarePlan, CareTeam, Condition, DiagnosticReport (lab + note), DocumentReference, Encounter, Goal, Immunization, Location, MedicationRequest, Observation (US Core vital-sign, lab, smoking, pediatric etc.), Organization, Practitioner, PractitionerRole, Procedure; ValueSet (SHOULD) | US Core profiles (version-less canonicals) | search-type SHALL, read SHALL, vread SHOULD, history-instance SHOULD, history-type MAY | US Core SHALL combinations (section 3.4) | _revinclude Provenance:target on most |

pdex-server has exactly 25 resource entries. The variant http://hl7.org/fhir/us/davinci-pdex/CapabilityStatement/pdex-server-6-1 ("with US core 6.1 support", date 2024-10-20) adds Device, Endpoint, Questionnaire, QuestionnaireResponse, RelatedPerson, ServiceRequest, Specimen and **contains no ExplanationOfBenefit entry**; pdex-provider-access-server and pdex-payer-access-server (date 2024-05-02) contain only Group. [verified]

### 3.4 US Core surface (clinical data) — SHALL search combinations

Source for 3.1.1: Inferno generated metadata (derived from the US Core 3.1.1 server CapabilityStatement) [verified from Inferno source]. Source for the newer list: local us-core-server-master.json (CI build, 10.0.0-ballot profiles) [verified] — use only as a superset guide until a published 6.1.0/7.0.0 CapabilityStatement is obtained.

| Resource | SHALL searches (3.1.1) | SHALL searches (master CS) | SHOULD (3.1.1) | Interactions |
|---|---|---|---|---|
| Patient | _id; identifier; name; birthdate+name; gender+name | _id; identifier; name; birthdate+name (SHALL); birthdate+family, death-date+family SHOULD | birthdate+family; family+gender (NOT SHALL) | read, search-type SHALL; vread, history-instance SHOULD |
| Condition | patient | patient; patient+category | patient+clinical-status (later versions: patient+onset-date, patient+code, patient+asserted-date, patient+category+encounter) | same |
| Observation | patient+category; patient+code; patient+category+date | same | patient+category+status; patient+code+date | same |
| DiagnosticReport | patient+category; patient+code; patient+category+date | same | patient+status; patient+code+date | same |
| MedicationRequest | patient+intent; patient+intent+status | same | patient+intent+encounter; patient+intent+authoredon; _include=MedicationRequest:medication | same |
| Encounter | patient; _id; date+patient | patient; date+patient; _id | identifier; class+patient; patient+status; patient+type | same |
| DocumentReference | _id; patient; patient+type; patient+category; patient+category+date | same + $docref operation SHALL | patient+status; patient+type+period | same |
| Procedure | patient; patient+date | same | patient+code+date; patient+status | same |
| CarePlan | patient+category | same | patient+category+status; patient+category+status+date; patient+category+date | same |
| CareTeam | patient+status | same | — | same |
| Immunization | patient | patient | patient+date; patient+status | same |
| AllergyIntolerance | patient | patient | patient+clinical-status | same |
| Goal | patient | patient | patient+target-date; patient+lifecycle-status | same |
| Device | patient | patient | patient+type | same |
| Coverage (6.1.0+) | — | patient | — | — |
| ServiceRequest (6.1.0+) | — | patient; patient+category; patient+category+authored; patient+code | — | — |
| Location | address; name | address; name | address-city, address-state, address-postalcode | — |
| Organization | address; name | address; name | — | — |
| Practitioner | identifier; name | identifier; name | — | — |
| PractitionerRole | practitioner; specialty | practitioner; specialty | _include PractitionerRole:endpoint, :practitioner | — |
| Provenance | via _revinclude=Provenance:target | same | — | read SHALL |

US Core general rules: status-parameter fallback (if a search without status returns 400 + OperationOutcome the client retries with each status from the required binding); multiple-OR (comma-joined) values; date comparators gt/ge/lt/le; DataAbsentReason via extension http://hl7.org/fhir/StructureDefinition/data-absent-reason or code system http://terminology.hl7.org/CodeSystem/data-absent-reason (code `unknown`); invariant provenance-1 (if agent.who is Practitioner or Device then agent.onBehalfOf required). [verified from Inferno source]

### 3.5 US Drug Formulary 2.1.0 surface — CapabilityStatement usdf-server (version 2.0.1 inside the 2.1.0 package; no security element; typically served unauthenticated) [verified]

| Resource | Profile(s) | Search parameters |
|---|---|---|
| InsurancePlan | usdf-PayerInsurancePlan, usdf-Formulary | _id, _lastUpdated, identifier, status, period, type, name, coverage-type, formulary-coverage (reference), coverage-area (reference) |
| Basic | usdf-FormularyItem | _id, _lastUpdated, code, subject (reference), status, period, formulary (reference), pharmacy-benefit-type, drug-tier |
| MedicationKnowledge | usdf-FormularyDrug | _id, _lastUpdated, status, code, drug-name (string), doseform |
| Location | usdf-InsurancePlanLocation | _id, _lastUpdated, address, address-city, address-state, address-postalcode |

Other profiles: insurance-plan-coverage (Coverage), usdf-FormularyBulkDataGraphDefinition, usdf-PayerInsurancePlanBulkDataGraphDefinition. Extensions: usdf-DrugTierID, usdf-PriorAuthorization, usdf-PriorAuthorizationNewStartsOnly, usdf-QuantityLimit, usdf-QuantityLimitDetail, usdf-StepTherapyLimit, usdf-StepTherapyLimitNewStartsOnly, usdf-PharmacyBenefitType, usdf-AvailabilityStatus, usdf-AvailabilityPeriod, usdf-FormularyReference, usdf-AdditionalCoverageInformation. Satisfies MA-PD "tiered formulary structure or utilization management procedure" (422.119(b)(2)) and Medicaid PDL (431.60(b)(4)) content. The CapabilityStatement lists all CRUD interactions per resource (create/update/patch/delete are irrelevant for a read-only payer API).

### 3.6 Plan-Net 1.2.0 surface (Provider Directory API; no user authentication per CMS) — CapabilityStatement plan-net (version 1.2.0, date 2022-03-18) [verified]

| Resource | Search parameters (each also _id, _lastUpdated) | _include | _revinclude |
|---|---|---|---|
| Practitioner | name, family, given | — | PractitionerRole:practitioner |
| PractitionerRole | practitioner, organization, location, service, network, endpoint, role, specialty | PractitionerRole:practitioner/:organization/:location/:service/:network/:endpoint | — |
| Organization | partof, endpoint, address, name, type, coverage-area | Organization:partof/:endpoint/:coverage-area | Endpoint:organization, HealthcareService:organization, InsurancePlan:administered-by/:owned-by, OrganizationAffiliation:primary-organization/:participating-organization, PractitionerRole:organization/:network |
| OrganizationAffiliation | primary-organization, participating-organization, location, service, network, endpoint, role, specialty | six includes | — |
| Location | partof, organization, endpoint, address, address-city, address-state, address-postalcode, type | Location:endpoint/:organization/:partof | HealthcareService:location, InsurancePlan:coverage-area, OrganizationAffiliation:location, PractitionerRole:location |
| HealthcareService | location, coverage-area, organization, endpoint, name, service-category, service-type, specialty | four includes | PractitionerRole:service, OrganizationAffiliation:service |
| InsurancePlan | administered-by, owned-by, coverage-area, name, plan-type, identifier, type | InsurancePlan:administered-by/:owned-by/:coverage-area | — |
| Endpoint | organization | Endpoint:organization | — |

Interactions: search-type, read, vread on all. Profiles: plannet-Endpoint, -HealthcareService, -InsurancePlan, -Location, -Network (Organization), -Organization, -OrganizationAffiliation, -Practitioner, -PractitionerRole. Extensions: accessibility, communication-proficiency, contactpoint-availabletime, delivery-method, endpoint-usecase, location-reference, network-reference, newpatients, org-description, practitioner-period, practitioner-qualification, qualification, via-intermediary.

### 3.7 Operations and bulk (scope note)

- Patient Access API: no operations are required. Patient/$everything is optional (PDex 2.2.0 §pdex-214: "$everything ... SHALL include the same set of resources and profiles as any other in-scope retrieval method ... including both Prior Authorization ExplanationOfBenefit records (PDex Prior Authorization profile) and Claims / Encounter ExplanationOfBenefit records (CARIN BB Non-Financial Basis profiles)"; clients MAY narrow with `_type` or client-side). [verified]
- $member-match (HRex), Group/$bulk-member-match, Group/$davinci-data-export, Patient/$export belong to Payer-to-Payer / Provider Access (Bulk Data 1.0.0 group-export required there). Not part of the Patient Access surface; include only as optional groups. [verified]
- PDex data-access rules §pdex-218/219: the server SHALL constrain returned data to the requester's permissions (e.g. a requester allowed only PA records must not receive claim EOBs). [verified]
---

## 4. Prior authorization representation — PDex Prior Authorization profile

### 4.1 Identity and discriminators [verified]

- Canonical: http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization ; name PdexPriorAuthorization; title "PDex Prior Authorization"; version 2.1.0; status active; date 2025-06-18T00:57:14+00:00; type ExplanationOfBenefit; baseDefinition http://hl7.org/fhir/StructureDefinition/ExplanationOfBenefit (derived from base R4 EOB, not from any C4BB profile). Description: "The PDex Prior Authorization (PPA) profile is based on the ExplanationOfBenefit resource and is provided to enable payers to express Prior Authorization information to members."
- A PA EOB is distinguished from a claim EOB by three signals: (1) `ExplanationOfBenefit.use` 1..1 MS **patternCode `preauthorization`** (C4BB claim profiles: patternCode `claim`); (2) `meta.profile` 1..* with required slice `supportedProfile` 1..1 patternCanonical = the profile URL above (version-less since 2.1.0; 2.0.0 patterned `...|2.0.0`); (3) IG text: "Prior Authorizations can be identified by the ExplanationOfBenefit.use = preauthorization."
- `type` (extensible binding claim-type; example institutional) distinguishes institutional/professional/pharmacy/oral/vision PAs, NOT PA vs claim.
- Regulatory mapping caveat: CMS-0057-F recommends the PDex IG; no CMS text naming "ExplanationOfBenefit" vs "Claim/ClaimResponse" for the Patient Access API was found [likely]. PAS Claim/ClaimResponse is the provider-facing PA API, not the member-facing surface.

### 4.2 Element-by-element (2.1.0) [verified]

| Element | Card. | MS | Binding / type / pattern | Meaning for a human-readable PA |
|---|---|---|---|---|
| meta.lastUpdated | 0..1 (base) | — | instant | Drives `_lastUpdated` polling; CMS 1-business-day status-change propagation |
| meta.profile:supportedProfile | 1..1 | — | patternCanonical pdex-priorauthorization | Identification |
| extension:levelOfServiceType | 0..1 | MS | http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-levelOfServiceCode ; valueCodeableConcept, REQUIRED binding https://valueset.x12.org/x217/005010/request/2000E/UM/1/06/00/1338 (X12 UM06); example system https://codesystem.x12.org/005010/1338, codes U 'Urgent', E 'Elective' | Urgency ("Urgent"/"Elective") |
| identifier | 0..* | no | Identifier; example system https://www.exampleplan.com/fhir/EOBIdentifier value PA123412341234123412341234 | PA / authorization number (searchable via `identifier`) |
| status | 1..1 | MS | required http://hl7.org/fhir/ValueSet/explanationofbenefit-status\|4.0.1 (active \| cancelled \| draft \| entered-in-error) — not constrained further | Record state, not the decision; `cancelled` = authorization withdrawn |
| type | 1..1 | MS | extensible claim-type | Claim family |
| use | 1..1 | MS | required claim-use\|4.0.1 + patternCode `preauthorization` | Identification |
| patient | 1..1 | MS | Reference(us-core-patient) | Member |
| created | 1..1 | no | dateTime ("Response creation date") | Fallback timestamp only |
| enterer | 0..1 | MS | Reference(us-core-practitioner \| us-core-practitionerrole) | Submitting clinician |
| insurer | 1..1 | MS | Reference(us-core-organization) | Payer |
| provider | 1..1 | MS | Reference(us-core-practitioner \| practitionerrole \| organization) | Requesting provider |
| priority | 0..1 | no | required http://hl7.org/fhir/ValueSet/process-priority | — |
| facility | 0..1 | MS | Reference(us-core-location) | Service location |
| outcome | 1..1 | no | required remittance-outcome\|4.0.1 (queued \| complete \| error \| partial) | Processing state only — the 2.1.0 example is `queued` while its line is A1 certified; **never infer the decision from outcome** |
| disposition, preAuthRef | 0..1 / 0..* | no | string | free text |
| preAuthRefPeriod | 0..1 | MS | Period ("Prior Authorization in-effect period") | Authorization validity window; `.end` = date the PA ends |
| careTeam.provider | 1..1 within careTeam | MS | Reference | — |
| insurance | 1..* | — | insurance.focal 1..1; insurance.coverage 1..1 Reference(http://hl7.org/fhir/us/davinci-hrex/StructureDefinition/hrex-coverage) | Coverage under which PA was requested |
| item.sequence | 1..1 | — | positiveInt | line number |
| item.category | 0..1 | MS | REQUIRED http://hl7.org/fhir/us/davinci-pdex/ValueSet/PriorAuthServiceTypeCodes (all of https://x12.org/codes/service-type-codes; example '3' Consultation) | Service type |
| item.productOrService | 1..1 | no | REQUIRED http://hl7.org/fhir/us/davinci-pdex/ValueSet/PDexPAInstitutionalProcedureCodesVS (all CPT http://www.ama-assn.org/go/cpt, HCPCS https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets, HIPPS https://www.cms.gov/Medicare/Medicare-Fee-for-Service-Payment/ProspMedicareFeeSvcPmtGen/HIPPSCodes, plus data-absent-reason#not-applicable) | Item/service requested |
| item.encounter | 0..* | — | Reference(us-core-encounter) | — |
| item.serviced[x], item.quantity | 0..1 | no | date/Period; Quantity | requested dates / units (feeds `service-date` search together with billablePeriod) |
| item.extension:itemTraceNumber | 0..* | MS | http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-itemTraceNumber (Identifier profiled by PAS profile-identifier; "(2000F-TRN)") | Trace number |
| item.extension:preAuthIssueDate | 0..1 | MS | .../extension-itemPreAuthIssueDate (valueDate; "The date when this item's preauthorization was issued.") | Date approved (line) |
| item.extension:preAuthPeriod | 0..1 | MS | .../extension-itemPreAuthPeriod (valuePeriod; "The date/period when this item's preauthorization is valid.") | Line validity; `.end` = date the PA ends |
| item.extension:previousAuthorizationNumber | 0..1 | MS | .../extension-authorizationNumber (valueString; "assigned by the UMO to an authorized review outcome") | Prior auth number |
| item.extension:administrationReferenceNumber | 0..1 | MS | .../extension-administrationReferenceNumber (valueString; "assigned by the UMO to the original disallowed review outcome") | Denial reference |
| item.extension:authorizedItemDetail | 0..1 | MS | .../extension-itemAuthorizedDetail (complex: productOrServiceCode [X12278RequestedServiceType], productOrServiceCodeEnd, modifier, unitPrice, quantity, epsdtIndicator, nursingHomeLevelOfCare, revenue, revenueUnitRateLimit, authorizedService); PAS description "The details of what has been authorized for this item."; PDex slice short "...if different from what was requested." | What was actually approved (may differ from request) |
| item.extension:authorizedProvider | 0..* | MS | .../extension-itemAuthorizedProvider (provider Reference, providerType) | Authorized provider |
| item.adjudication | 0..* | MS | sliced by category pattern, rules CLOSED; category REQUIRED binding http://hl7.org/fhir/us/davinci-pdex/ValueSet/PDexAdjudicationCategoryDiscriminator | Decision carrier (see 4.3) |
| item.adjudication:adjudicationamounttype | 0..* | MS | category required-bound PDexAdjudication; amount 1..1 MS (Money) | Amount |
| item.adjudication:allowedunits | 0..1 | MS | category pattern PDexAdjudicationDiscriminator#allowedunits; value 1..1 MS (decimal) | Units approved |
| item.adjudication:consumedunits | 0..1 | **no** | category pattern #consumedunits; value 1..1 | Units used to date (IG extra; item-level only) |
| item.adjudication:denialreason | 0..* | MS | category pattern #denialreason; reason 1..1 MS REQUIRED binding http://hl7.org/fhir/us/davinci-pdex/ValueSet/X12ClaimAdjustmentReasonCodesCMSRemittanceAdviceRemarkCodes (all of https://x12.org/codes/claim-adjustment-reason-codes and https://x12.org/codes/remittance-advice-remark-codes) | Specific denial reason (CARC/RARC) |
| item.adjudication.extension:reviewAction | 0..1 | MS | http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-reviewAction (see 4.3) | Decision |
| item.adjudication.extension:adjudicationActionDate | 0..1 | MS | http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/base-ext-when-adjudicated (name WhenAdjudicated; valueDateTime; "Date and Time when Adjudication Action took place"; contexts EOB.adjudication and EOB.item.adjudication; note it may be replaced after R6) | Decision date (line) |
| adjudication (header) | 0..* | MS | CLOSED slices **adjudicationamounttype (0..* MS; amount 1..1 not MS) and denialreason (0..* MS; reason 1..1 MS) only** — no allowedunits/consumedunits at header level; category MS; extensions reviewAction 0..1 MS and adjudicationActionDate 0..1 MS | Header decision |
| total | 0..* | — | total.category 1..1 MS EXTENSIBLE binding http://hl7.org/fhir/us/davinci-pdex/ValueSet/PriorAuthorizationAmounts (includes CodeSystem http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PriorAuthorizationValueCodes: submitted "Proposed amounts of units or services", eligible "Eligible/agreed items or services", utilized "Amount of items or services consumed to date"); total.amount 1..1 Money | Totals |
| total.extension:priorauth-utilization | 0..1 | MS | http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/PriorAuthorizationUtilization (context EOB.total; value Quantity \| Ratio; "amount of an item or service that has been consumed under the current prior authorization") | Quantity used (IG extra) |
| addItem.provider | — | — | Reference(us-core-practitioner \| practitionerrole \| organization) | — |
| Invariant adjudication-has-amount-type-slice | warning | — | "If Adjudication is present, it must have at least one adjudicationamounttype slice"; expression uses the typo'd URL http://hl7.org/fhir/us/davinc-pdex/ValueSet/PDEXAdjudication, so validators may not evaluate it meaningfully | Because item.adjudication slicing is closed and every entry needs an amount, allowed units, or denial reason, an approved/pended decision must hang on an adjudication entry (the example uses category `submitted` + amount 300.99 to host the reviewAction) |

### 4.3 reviewAction extension and X12 codes

- http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-reviewAction (2.1.0): contexts ExplanationOfBenefit.item.adjudication, ExplanationOfBenefit.adjudication, ExplanationOfBenefit.addItem.adjudication; value[x] 0..0; sub-extensions: `code` 0..1 (typed by http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/extension-reviewActionCode, short "Healthcare Services Outcome", valueCodeableConcept, REQUIRED binding https://valueset.x12.org/x217/005010/response/2000F/HCR/1/01/00/306 = X12 278 HCR01 action codes); `number` 0..1 valueString ("Item Level Review Number"); `reasonCode` 0..* valueCodeableConcept ("Explanation of the pending, review denial or partial approval", REQUIRED binding http://hl7.org/fhir/us/davinci-pdex/ValueSet/X12278ReviewDecisionReasonCode = all of https://codesystem.x12.org/external/886); `secondSurgicalOpinionFlag` 0..1 valueBoolean. Note: extension-reviewActionCode declares context only `ExplanationOfBenefit.item.adjudication.extension`, so strict validators may warn on header-level use. [verified]
- X12 306 (HCR01) codes, system https://codesystem.x12.org/005010/306: A1 "Certified in total" (PDex example + PAS examples), A3 "Not Certified" (PAS), A4 "Pending" (PAS) [verified in local packages]; A2 "Certified - partial", A6 "Modified", C "Cancelled", CT "Contact Payer", NA "No Action Required" [likely — not in any local package; X12 licensing prevents verification]. X12 terminology is not resolvable by tx.fhir.org: the IG's own build logs only warnings for these systems — the workbench must downgrade unresolvable X12/HIPPS terminology to warnings.
- CodeSystem http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PDexAdjudicationDiscriminator: allowedunits "allowed units", consumedunits "consumed units", denialreason "Denial Reason". ValueSet PDexAdjudicationCategoryDiscriminator = PDexAdjudication + PDexPayerBenefitPaymentStatus (innetwork \| outofnetwork \| other) + all C4BBAdjudication + all PDexAdjudicationDiscriminator. ValueSet PDexAdjudication = http://terminology.hl7.org/CodeSystem/adjudication submitted, copay, eligible, deductible, benefit + C4BBAdjudication coinsurance, noncovered, priorpayerpaid, paidbypatient, paidtopatient, paidtoprovider, memberliability, discount, drugcost. [verified]

### 4.4 Deriving a human-readable PA summary (workbench algorithm; analyst design over verified structure — the IG publishes no crosswalk table) [unverified as a whole; each input element verified]

1. **Decision / status**: collect all `reviewAction.code` codings (item-level first, then header). Map: A1 -> Approved (in total); A2 -> Approved (partial); A6 -> Approved (modified); A3 -> Denied; A4 -> Pended/Pending; C -> Cancelled; CT -> Contact payer; NA -> No action required. If no reviewAction anywhere: if any `denialreason` slice exists -> Denied; else if `status`=cancelled -> Cancelled; else if `outcome`=queued and no decision date -> Pending; else Unknown (flag). Combine with `EOB.status` (active/cancelled/entered-in-error/draft) as the record state and `outcome` (queued/complete/partial/error) as processing state only. If the PA has mixed line decisions, report per line plus an overall "Partially approved".
2. **Date approved/denied**: the `adjudicationActionDate` (base-ext-when-adjudicated) on the adjudication entry that carries the decision; else `item.extension:preAuthIssueDate`; else none (`created` is response creation, not decision — show only as fallback with a caveat).
3. **Date / circumstance the PA ends**: `preAuthRefPeriod.end` (resource) and/or `item.extension:preAuthPeriod.end`; if absent and reviewAction code is C/A3, state "ended by cancellation/denial"; otherwise "open-ended / not stated".
4. **Items and services approved**: per item — `item.category` display (X12 service type), `item.productOrService` (CPT/HCPCS/HIPPS), `item.modifier`, `item.quantity`; if `authorizedItemDetail` present, prefer its productOrServiceCode/quantity/authorizedService as the approved content; `authorizedProvider` for the approved provider; `allowedunits.value` as approved units.
5. **Quantity used (IG extra, not a CMS element)**: `total[category=utilized].amount` and/or `total.extension:priorauth-utilization` (Quantity or Ratio) and/or `item.adjudication:consumedunits.value`; compare against `allowedunits.value` / `total[category=eligible]`.
6. **Specific denial reason**: `adjudication:denialreason.reason` (CARC/RARC code + display; item and header) and `reviewAction.reasonCode` (X12 886 decision reason codes); also `administrationReferenceNumber`.
7. **Supporting documentation (CMS element 6)**: PDex 2.2.0 (FHIR-56252, narrative only) recommends base R4 `ExplanationOfBenefit.supportingInfo` with category PASSupportingInfoType#additionalInformation and valueReference to a DocumentReference (inline `content.attachment.data` or `content.attachment.url`). Resolve and list attachments. [verified: 2.2.0 change history]
8. **Identifiers**: `identifier` (PA number), `item.extension:previousAuthorizationNumber`, `reviewAction.number`, `itemTraceNumber`.
9. **Urgency**: `extension:levelOfServiceType` (U Urgent / E Elective).
10. **Freshness**: `meta.lastUpdated` vs decision date; flag if lastUpdated precedes the latest adjudicationActionDate.

### 4.5 Mapping to the six CMS-0057-F elements (42 CFR 422.119(b)(1)(iv)(A)) [regulatory list verified: eCFR mirror; mapping is analyst interpretation]

| CMS element | PDex carrier(s) |
|---|---|
| (1) PA status | reviewAction.code (X12 306) + EOB.status (+ outcome as processing state); denialreason presence |
| (2) date approved or denied | adjudicationActionDate (when-adjudicated); item preAuthIssueDate |
| (3) date or circumstance under which the PA ends | preAuthRefPeriod.end; item preAuthPeriod.end; reviewAction code C / status cancelled for non-date circumstances |
| (4) items and services approved | item.productOrService / item.category / item.modifier / item.quantity; authorizedItemDetail; authorizedProvider; allowedunits |
| (5) if denied, a specific reason | denialreason.reason (CARC/RARC); reviewAction.reasonCode (X12 886) |
| (6) related structured administrative and clinical documentation submitted by a provider | supportingInfo -> DocumentReference (PDex 2.2.0 guidance); no slice in 2.1.0 |
| (not a CMS element) quantity used to date | total[utilized] / PriorAuthorizationUtilization / consumedunits — IG capability only |

### 4.6 Narrative rules from the IG (verbatim where quoted) [verified from davinci-epdx 2.1.0 source]

- Profile intro: "The PDex Prior Authorization profile has been created to enable Payers to communicate prior authorization decisions and changes to the status of a prior authorization to members." and "Payers SHALL make available pending and active prior authorization decisions and related clinical documentation and forms for items and services, not including prescription drugs, including the date the prior authorization was approved, the date the authorization ends, as well as the units and services approved and those used to date, no later than one (1) business day after a provider initiates a prior authorization for the beneficiary or there is a change of status for the prior authorization." followed by the example `[BaseURL]/ExplanationOfBenefit?use=preauthorization&patient=Patient/1`.
- payertopayerbulkexchange.md: "Prior Authorizations SHALL be limited to current/active Prior Authorizations in addition to Prior Authorizations that have changed status within the last year, as of the date of request for information." and "For example, a Prior Authorization may be denied, but then approved upon appeal. A prior Authorization might be pended and then subsequently approved or denied."
- 2.1.0 index.md: "The STU 2.1 version of the IG incorporates changes to support the sharing of Prior Authorization information with members, providers and other payers. This is done through the profiling of the ExplanationOfBenefit resource."
- The sentence "Prior authorization data includes all pending and active prior authorizations for the patient, including the reason for denial on denied authorizations" is NOT PDex text (it appeared in a search snippet) — do not cite it to the IG.
- 2.2.0 additions: FHIR-56438 clarifies any PA whose status changed in the previous 12 months must be included; FHIR-56439 aligns wording to 42 CFR 422.121(b)(4)(ii)(B); FHIR-56380 re-characterizes `?use=preauthorization` as "an illustrative, non-normative example" and recommends `[BaseURL]/ExplanationOfBenefit?_profile=http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization&patient=Patient/1` with client-side filtering on `use` as fallback, noting a future version may formalize a `use` SearchParameter.

### 4.7 Query patterns for PA in the Patient Access API [verified against CapabilityStatement/SearchParameters]

1. `GET [base]/ExplanationOfBenefit?patient=[id]` (SHALL) — returns claim EOBs and PA EOBs together; filter client-side on `use` / `meta.profile`.
2. `GET [base]/ExplanationOfBenefit?patient=[id]&_profile=http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization` — PDex 2.2.0 recommended form (`_profile` is a base FHIR parameter; support not guaranteed).
3. `GET [base]/ExplanationOfBenefit?patient=[id]&use=preauthorization` — IG-defined SearchParameter, not in any CapabilityStatement; test as SHOULD/optional.
4. `GET [base]/ExplanationOfBenefit?patient=[id]&_lastUpdated=ge[dateTime]` (SHALL; incremental status-change polling; patient argument required).
5. `GET [base]/ExplanationOfBenefit?_id=[id]` and `GET [base]/ExplanationOfBenefit/[id]` (SHALL).
6. `GET [base]/ExplanationOfBenefit?patient=[id]&identifier=[system]|[PA number]` (SHALL).
7. `GET [base]/ExplanationOfBenefit?patient=[id]&service-date=ge2021-10-01` (SHALL; matches billablePeriod or item.serviced; patient required).
8. `GET [base]/ExplanationOfBenefit?patient=[id]&type=institutional` (SHALL; token with or without system; patient required).
9. `_include=ExplanationOfBenefit:patient|provider|care-team|coverage|insurer|*` and `_include:iterate=Coverage:payor` (`_include:*` SHALL).

### 4.8 Example and validation notes [verified]

- The only PA example in the 2.1.0 package is example/ExplanationOfBenefit-PDexPriorAuth1.json (IG name "PdexPriorAuth", "PDex Prior Authorization based on EOB Inpatient Example"): APPROVED urgent institutional PA — meta.lastUpdated 2024-02-06T09:14:11+00:00; levelOfServiceCode U; identifier PA123412341234123412341234; status active; type institutional; use preauthorization; patient Patient/1; billablePeriod 2021-10-01..2021-10-31; created 2021-09-20; insurer Organization/Payer1; provider Organization/Payer2; priority normal; related XCLM1001; outcome **queued**; preAuthRefPeriod 2021-10-01..2021-10-31; diagnosis ICD-10-CM G89.4 principal; insurance focal Coverage/Coverage1; item 1 category service-type-codes#3 Consultation, productOrService HIPPS BB201; item.adjudication id '1' with reviewAction{number AUTH0001, code 306#A1} and when-adjudicated 2024-07-23T17:26:23.217+00:00, category submitted amount 300.99 USD; total eligible 100 USD with utilization valueQuantity 1.
- No denied, pended, partially-approved or cancelled example exists; no example of allowedunits/consumedunits/denialreason slices, preAuthIssueDate/preAuthPeriod, header-level adjudication, or supportingInfo. The workbench needs synthetic fixtures for A2/A3/A4/A6/C.
- IG build validation (other/validation-oo.json): the example has 12 warnings, 0 errors (unresolvable X12 value sets/code systems 1338, 306, service-type-codes; HIPPS; exampleplan identifier system). "Multiple different potential matches" warnings for version-less us-core-* targetProfiles and hrex-coverage are logged on the profile itself (package depends on US Core 3.1.1, 6.1.0 and 7.0.0 simultaneously). Pin a US Core version when validating.

### 4.9 Version deltas [verified from davinci-epdx tags]

- 2.0.0 already contained: the PAS item.extension slices, allowedunits/denialreason/consumedunits slices, reviewAction, levelOfServiceCode, PriorAuthorizationUtilization, the `use` SearchParameter and the same six EOB CapabilityStatement search params.
- 2.0.0 -> 2.1.0: (a) added adjudicationActionDate (base-ext-when-adjudicated) slices on item and header adjudication (FHIR-45353); (b) item.adjudication:consumedunits and its value lost Must Support (FHIR-44807); (c) header adjudication:denialreason gained category pattern, reason binding and reason 1..1 MS; (d) meta.profile supportedProfile pattern became version-less; (e) experimental=false.
- 2.1.0 -> 2.2.0: PAS dependency 2.1.0 -> 2.2.1; item.extension slices admissionDates and dischargeDate (0..1 MS) and root claimResponseReviewer (0..1 MS) added (FHIR-57521); narrative changes in 4.6; EOB CapabilityStatement search list unchanged.

---

## 5. Authorization

### 5.1 Regulatory baseline [verified: eCFR mirror + public-inspection PDFs]

- 422.119(c)(1) (and parallels): SMART App Launch 1.0.0 incl. "SMART Core Capabilities" (170.215(c)(1)) + OpenID Connect Core 1.0 errata set 1 (170.215(e)(1)). SMART 2.0.0 (170.215(c)(2), mandatory Capability Sets "Patient Access for Standalone Apps" and "Clinician Access for EHR Launch", all 8.1.2 capabilities except permission-online, Token Introspection) is permitted via 422.119(c)(4) and recommended by CMS only for backend services (Provider Access / P2P). Token Introspection is therefore not mandated for a 1.0.0-baseline Patient Access server.
- PDex member-authorized OAuth2 page: OAuth 2.0 token issuance; the member SHALL authenticate with credentials issued by or accepted by the health plan; SHALL be presented with an authorization process; "After successfully authorizing an application an Access Token and Optional Refresh Token SHALL be returned to the requesting application"; the app SHALL use the access token against the plan's secure FHIR API. [verified]
- App registration with the authorization server is permitted; registration requirements must be publicly documented (422.119(d)(3)); denial only per (e). [verified: eCFR mirror]

### 5.2 Discovery — `/.well-known/smart-configuration` [verified from SMART source]

- Location: `<FHIR base URL>/.well-known/smart-configuration` (appended even when the base has a path). 2.2.0: responses SHALL be JSON with Content-Type application/json regardless of Accept; all endpoint URLs SHALL be absolute (clients SHOULD resolve relative URLs against the FHIR base per RFC1808). CapabilityStatement-based declaration is deprecated in 2.x.
- Field status by version:

| Field | 1.0.0 | 2.0.0 | 2.1.0 | 2.2.0 |
|---|---|---|---|---|
| authorization_endpoint | REQUIRED | REQUIRED | REQUIRED | CONDITIONAL (required if launch-ehr or launch-standalone) |
| token_endpoint | REQUIRED | REQUIRED | REQUIRED | REQUIRED |
| capabilities | REQUIRED | REQUIRED | REQUIRED | REQUIRED |
| grant_types_supported | absent | REQUIRED | REQUIRED | REQUIRED (authorization_code / client_credentials) |
| code_challenge_methods_supported | absent | REQUIRED (S256 SHALL, plain SHALL NOT) | REQUIRED | REQUIRED |
| issuer / jwks_uri | — | CONDITIONAL (sso-openid-connect) | CONDITIONAL | CONDITIONAL (issuer omitted otherwise; jwks_uri optional otherwise) |
| scopes_supported, response_types_supported, management_endpoint, introspection_endpoint, revocation_endpoint | RECOMMENDED | RECOMMENDED | RECOMMENDED | RECOMMENDED |
| token_endpoint_auth_methods(_supported) | OPTIONAL (1.0.0 table names it `token_endpoint_auth_methods`; sample uses `_supported`; options client_secret_post, client_secret_basic) | OPTIONAL | OPTIONAL | OPTIONAL (client_secret_post, client_secret_basic, private_key_jwt) |
| registration_endpoint | OPTIONAL | OPTIONAL | OPTIONAL | OPTIONAL |
| smart_app_state_endpoint | — | — | CONDITIONAL (with smart-app-state) | OPTIONAL, DEPRECATED |
| associated_endpoints | — | — | — | OPTIONAL, experimental |
| user_access_brand_bundle / user_access_brand_identifier | — | — | — | RECOMMENDED |
| token_endpoint_auth_signing_alg_values_supported | — | required only by the asymmetric profile (RS384/ES384) | same | same |

- 2.2.0 capability codes: launch-ehr, launch-standalone; authorize-post; client-public, client-confidential-symmetric, client-confidential-asymmetric; sso-openid-connect; context-banner, context-style; context-ehr-patient, context-ehr-encounter; context-standalone-patient, context-standalone-encounter; permission-offline, permission-online, permission-patient, permission-user, permission-v1, permission-v2; smart-app-state. 1.0.0 already used context-banner/context-style (NOT "context-passthrough-*") and lacked authorize-post, client-confidential-asymmetric, permission-online, permission-v1/v2, smart-app-state. Non-HL7 capabilities SHALL be full URIs.
- Capability Set "Patient Access for Standalone Apps" (the relevant set for a payer): launch-standalone + at least one of client-public or client-confidential-symmetric (MAY also client-confidential-asymmetric) + context-standalone-patient + permission-patient.
- Inferno CARIN kit additionally requires capabilities ⊇ {launch-standalone, client-public, client-confidential-symmetric, sso-openid-connect, context-standalone-patient, permission-offline, permission-patient, permission-user} [verified from Inferno source] — stricter than the IG; grade as warn.
- Legacy declaration (1.0.0 mechanism): CapabilityStatement.rest.security.service coding code `SMART-on-FHIR` (1.0.0 example system http://hl7.org/fhir/restful-security-service; R4 THO system http://terminology.hl7.org/CodeSystem/restful-security-service) and extension http://fhir-registry.smarthealthit.org/StructureDefinition/oauth-uris with sub-extensions authorize and token (required by Inferno STU1) plus optional register, manage, introspect, revoke. No payer IG CapabilityStatement declares it — treat as optional/info.

### 5.3 Standalone launch (authorization code) [verified from SMART 2.2.0 source]

- Authorize request (GET query or POST form; servers SHALL support both in 2.x): response_type=code, client_id, redirect_uri (pre-registered, fully specified), scope, state (unpredictable per session; SHALL be validated on return), aud (the FHIR server base URL the app will call; RFC 8707 `resource` MAY be accepted as a synonym), code_challenge, code_challenge_method=S256. `launch` only for EHR launch. PKCE: all apps SHALL support; servers SHALL support S256 and SHALL NOT support plain; the EHR SHALL verify code_verifier at exchange. 1.0.0 had no PKCE — send it anyway and tolerate servers that ignore it.
- Token request: POST application/x-www-form-urlencoded to token_endpoint: grant_type=authorization_code, code, redirect_uri, code_verifier, client_id (public apps only; omit for confidential). Client auth: symmetric = HTTP Basic (username client_id, password client_secret) is the only method defined by the client-confidential-symmetric profile; client_secret_post appears only as a discovery value — support both, default Basic, honor token_endpoint_auth_methods_supported; asymmetric = private_key_jwt.
- Token response: access_token (required), token_type "Bearer" (compare case-insensitively), expires_in (recommended; SHOULD be <= 3600), scope (required; may differ from requested — inspect before proceeding), id_token / refresh_token / authorization_details optional, context: patient ("123" -> [base]/Patient/123), encounter, fhirContext (array of objects with reference/canonical/identifier + role), need_patient_banner, smart_style_url, intent, tenant. Headers SHALL include `Cache-Control: no-store` and `Pragma: no-cache`. Store tokens only in app-specific storage.
- Patient context rule: if a patient/ scope is granted the EHR SHALL establish a patient in context; it MAY refuse patient/ requests lacking launch/patient or MAY infer it.
- Refresh: POST grant_type=refresh_token, refresh_token, optional scope (strict subset of original; omitted = same); public apps do not authenticate; confidential apps authenticate as above; refresh token SHALL be bound to the same client_id and SHALL contain the same or a subset of claims; response access_token, token_type, expires_in, scope, optional new refresh_token (replace the old one), context params. Requested via offline_access (online_access = EHR launch only).

### 5.4 Scopes [verified from SMART source]

- v1 (1.0.0; permission-v1): `(patient|user)/(ResourceType|*).(read|write|*)`; identity `openid fhirUser` ("openid profile" legacy, profile deprecated); launch/patient; offline_access / online_access.
- v2 (2.0.0+; permission-v2): `(patient|user|system)/(ResourceType|*).<subset of cruds in order>` (c create, r read incl. vread/history, u update incl. patch, d delete, s search incl. type/system history); granular `?param=value` suffix (e.g. patient/Observation.rs?category=http://terminology.hl7.org/CodeSystem/observation-category|laboratory); undefined/out-of-order letters MAY be ignored, replaced, or rejected. Servers SHOULD advertise permission-v1, SHOULD return v1 scopes when v1 requested, and map v1 .read => .rs, .write => .cud, .* => .cruds. URI form prefix http://smarthealthit.org/fhir/scopes/.
- Payer Patient Access default request: `openid fhirUser launch/patient offline_access` + either `patient/*.read` (v1) or `patient/*.rs` (v2), negotiated from capabilities; Inferno CARIN default: `launch/patient openid fhirUser patient/ExplanationOfBenefit.read patient/Coverage.read patient/Patient.read patient/Organization.read patient/Practitioner.read user/...`.

### 5.5 OpenID Connect [verified from SMART source]

- Request `openid` (+ `fhirUser`) to receive an id_token; validate per OIDC Core; `fhirUser` claim = absolute URL or relative (to aud FHIR base) reference to Patient, Practitioner, PractitionerRole, RelatedPerson or Person. Server SHALL support Authorization Code Flow, publish bare JWK keys (MAY add X.509), include fhirUser when openid+fhirUser granted, "support Signing ID Tokens with RSA SHA-256" (RS256). Apps SHALL NOT pass auth_time/max_age to servers that do not support them. Client procedure: GET {issuer}/.well-known/openid-configuration -> jwks_uri -> validate signature and claims (iss, sub, aud, exp, iat; header alg RS256 + kid per Inferno) -> GET fhirUser URL must return 200 with matching resourceType.

### 5.6 Client authentication and Backend Services [verified from SMART source]

- Asymmetric (client-confidential-asymmetric / private_key_jwt): JWT header alg (RS384 or ES384), kid, typ "JWT", optional jku (must match registered JWKS URL); claims iss = sub = client_id, aud = token endpoint URL, exp <= 5 minutes ahead, jti unique; server validates per RFC 7523 §3, rejects replayed jti, matches client_id to iss; clients SHALL support RS384 and ES384; servers SHALL validate at least one; JWKS URL registration "strongly preferred", inline JWK Set "strongly discouraged"; each JWK has kty, kid and bare values (n,e or crv,x,y); server SHALL NOT cache JWKS longer than the client's Cache-Control.
- Backend Services (moved into the SMART IG in **2.0.0**, not 2.1.0): discovery via smart-configuration (grant_types_supported includes client_credentials; authorization_endpoint conditional only from 2.2.0); POST token endpoint with scope (system/*.rs etc.), grant_type=client_credentials, client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer, client_assertion; TLS 1.2+; response access_token, token_type "bearer", expires_in (SHOULD NOT exceed 300), scope; server SHOULD fail requests for unauthorized/unsupported data and MAY withhold results with OperationOutcome in bulk error output. Not required for the Patient Access API; relevant to Provider Access / P2P.
- Token Introspection (RFC 7662; SMART servers SHOULD support; mandatory under 170.215(c)(2)): response SHALL include active, scope, client_id, exp; SHALL include original launch-context params (patient, intent); SHALL include iss and sub if an id_token was issued; SHOULD include fhirUser; endpoint MAY be access-controlled and SHALL accept a SMART/Backend bearer token.
- CORS (2.2.0): servers supporting browser apps SHALL allow any origin on the discovery endpoints (.well-known/smart-configuration and metadata) and registered origins on the token and FHIR endpoints.

### 5.7 SMART version deltas (corrected) [verified from HL7/smart-app-launch commits and tags]

- 1.0.0 (Release 1.0.0 STU, 2018-11-13, fhirversion 3.0.1): .read/.write/.* scopes; no PKCE; well-known fields per 5.2; capability names as in 5.2; CapabilityStatement oauth-uris normative; "SMART on FHIR Core Capabilities and Capability Sets" section.
- 2.0.0 (STU2, package-list 2021-10-30): granular cruds scopes; POST-based authorization; PKCE mandatory; token introspection profiled; discovery properties updated (grant_types_supported, code_challenge_methods_supported REQUIRED); Backend Services moved from Bulk Data IG; asymmetric and symmetric client auth patterns; fhirContext as array of relative reference strings; authorization_endpoint still REQUIRED.
- 2.1.0 (STU 2.1, publication-request 2023-03-29): richer fhirContext; PractitionerRole allowed for fhirUser; absolute-URL requirement documented; experimental SMART App State (capability smart-app-state; smart_app_state_endpoint CONDITIONAL); Task profiles for app launch; authorization_endpoint still REQUIRED.
- 2.2.0 (STU 2.2, tag 2024-04-26): authorization_endpoint CONDITIONAL; smart_app_state_endpoint OPTIONAL/DEPRECATED in favour of associated_endpoints; User-access Brands and Endpoints; fhirContext canonical/identifier references.
- Inferno SMART kit 1.0.3 (2026-08-26): suites STU1, STU2, STU2.2; STU2 well-known test: issuer/jwks_uri asserted only when sso-openid-connect advertised; presence of issuer without sso-openid-connect is a **warning**, not a failure.

### 5.8 Vendor variations

| Vendor / platform | Observed behaviour | Workbench implication | Confidence |
|---|---|---|---|
| Onyx SAFHIR / OnyxOS | Authorize `https://api-<tenant>-<env>.safhir.io/v1/authorize?aud=https://api-<tenant>-<env>.safhir.io/v1&response_type=code&client_id=<uuid>&redirect_uri=<uri>&scope=launch/patient fhirUser openid offline_access patient/List.read` (aud = /v1 root, not the IG base; space-separated v1 .read scopes); token POST with grant_type=authorization_code, redirect_uri, code, client_id, client_secret (client_secret_post style); response access_token, token_type Bearer, expires_in 3600, id_token, patient (FHIR Patient id), scope, refresh_token; token URL /v1/token (third-party config); grant types authorization_code + refresh_token (BCBSAL doc); introspection supported behind an external IdP (path unpublished); no evidence of .well-known/smart-configuration, PKCE enforcement, or client_secret_basic support; per-app credential bundle = client ID, client secret, authorization URI, token URI, onyxOS base URI, redirect URIs, scopes | Store authorize/token/introspection URLs explicitly; send aud = configured audience; support client_secret_post; take patient from token response; treat discovery as optional | Docs facts via search snippets **[likely]** (verifier could not reach docs.safhir.io); /v1/token **[likely]**; well-known absence **[unverified]** |
| Okta (reference SMART architecture) | Okta itself does not return launch-context (`patient`) with tokens; reference design adds /smart/authorize proxy, patient picker/consent, token inline hook, /token proxy returning `patient`, launch-response cache; supports launch/patient and refresh for confidential apps; **no refresh tokens for public apps**; PKCE passes through | Configurable patient-id source; expect vendor proxies in front of Okta | README facts **[verified]**; generic Okta discovery/audience/aud-ignored behaviours **[likely]** |
| Azure AD B2C + Azure Health Data Services FHIR service | Endpoints https://{tenant}.b2clogin.com/{tenant}.onmicrosoft.com/{USER_FLOW}/oauth2/v2.0/authorize\|token; discovery .../{USER_FLOW}/v2.0/.well-known/openid-configuration; "The scope request must be a fully qualified URL, for example, https://testb2c.onmicrosoft.com/fhir/patient.all.read" (dotted scope variant); custom claim `fhirUser` (case-sensitive) = fully qualified Patient id; FHIR service "supports SMART v1.0.0 and SMART v2.0.0. You can't mix and match"; SMART user role read/search only; smart-configuration served by the FHIR service; Entra ID needs an orchestration layer | Per-environment scope-syntax override; scope translation table | **[verified]** (MicrosoftDocs source) |
| Auth0 | API `audience` parameter required on /authorize and /oauth/token to get a JWT; offline_access + "Allow Offline Access" for refresh; auth methods client_secret_post (SDK default), client_secret_basic, private_key_jwt; only openid-configuration discovery; `patient` injected via Action/custom claim or token proxy | Per-environment extra authorize params (audience/resource); patient-id source options | **[likely]** (docs unreachable) |
| Generic aud handling | Exact-string aud comparison (trailing slash, http/https) breaks on mismatch; some servers ignore aud; some want `resource`/`audience` | Send aud exactly as configured (same string used for well-known lookup); optionally also resource/audience; log which parameters the server honoured | **[likely]** |

### 5.9 Workbench negotiation strategy (analyst synthesis)

1. GET `/.well-known/smart-configuration` on each authenticated FHIR base; if 404, fall back to configured endpoints and to CapabilityStatement oauth-uris; record discovery mode.
2. Validate the 2.2.0 REQUIRED/CONDITIONAL set but grade 1.0.0-only servers (authorization_endpoint, token_endpoint, capabilities) as "legacy-conformant" (CMS baseline).
3. Always send PKCE S256, state, aud (+ optional resource/audience); pick scope syntax from capabilities (permission-v1 -> patient/*.read; permission-v2 -> patient/*.rs) plus openid fhirUser offline_access launch/patient; allow per-environment scope override (e.g. Azure dotted scopes, Onyx resource-level lists).
4. Client auth: none, client_secret_basic, client_secret_post, private_key_jwt (RS384/ES384, jti, exp <= 5 min, aud = token endpoint).
5. Patient id: token response `patient`, else id_token `fhirUser`, else introspection `patient`, else configured expectedPatientId (flag).
6. Verify Cache-Control/Pragma, expires_in <= 3600, refresh binding and scope-subset rule, id_token signature via jwks_uri.
---

## 6. Onyx SAFHIR / OnyxOS environment model

### 6.1 Platform facts

Confidence note: the verifier could not reach any Onyx host; "verified" below means the fact was quoted verbatim in a search snippet attributed to an Onyx/HL7 URL or read from the CMS SMA Endpoint Directory CSV / public GitHub code. Treat all Onyx facts as **[likely]** at best and confirm against the tenant's live docs and portal.

| Fact | Detail | Confidence |
|---|---|---|
| Vendor / product | Onyx Technology LLC (onyxhealth.io); platform originally "SAFHIR", marketed as "OnyxOS" since Spring'23 (v5.0, May 2023); docs at docs.safhir.io and docs.onyxos.io (parallel page names); sub-products GLEAM (enterprise access), OnyxEPA (CRD/DTR/PAS), OnyxP2P, OnyxCAP (Provider Access), OnyxIDM, OnyxInsights (Patient Access metrics), MoveMyHealthData | [likely] |
| FHIR base URL pattern | `https://api-<tenant>-<env>.safhir.io/v1/api/<ig_path>/` ; env suffixes seen: -prd, -uat, -t31, -t32, -alpha; some tenants omit the suffix (api-idmedicaid.safhir.io); Onyx portal also fronts non-safhir hosts (fite.ar-prd.gw02.abacusinsights.ai with /carin-bb, /pdex, /provider-directory, /open-formulary and `?_format=json`) | [likely] (CMS SMA Endpoint Directory CSV) |
| Tenant root | `.../v1/api` is an HTML landing page "SAFHIR-<tenant>-<env>" linking one CapabilityStatement per IG ("dynamically allocated set of APIs generated from reading capability statements"); each IG is a separate FHIR base | [likely] |
| IG path segments | carin-bb; pdex; provider-directory (older: plannet); secure-formulary (authenticated); open-formularyv2 / formulary-net (open); carin-bb-pharmacy — per-tenant, version-specific | [likely] |
| Discovery per IG base | `[base]/[ig]/metadata` CapabilityStatement ("same content, only [base_url] changes per environment"); `[base]/[ig]/openapi.json` Swagger; Postman collections published | [likely] |
| OAuth endpoints | `/v1/authorize` and `/v1/token` on the tenant API host; `aud=https://<host>/v1` in the documented authorize example; token response includes patient, id_token, refresh_token, expires_in 3600; scopes openid, fhirUser, launch/patient, offline_access, patient/*.read or resource-level patient/<Resource>.read (space-separated); grant types authorization_code + refresh_token; introspection supported (SAFHIR 3.1.2) but path unpublished; no evidence of .well-known/smart-configuration, PKCE, client_secret_basic, API-key headers, rate limits, $everything, _include | [likely]/[unverified] |
| Member IdP | Tenant-specific: ID.me (CT, MD, ID Medicaid), Auth0 email one-time-code (AR, PA), payer member portals (commercial) | [likely] |
| Patient context | token response `patient` = FHIR Patient logical id; "searches are typically bounded by the Patient ID for the member that authenticated" | [likely] |
| Pagination | FAQ: bundles paged, "up to 10 records"; `_total=accurate` supported; follow Bundle.link[next]; `_lastUpdated` for incremental pulls; `_count` recognized; next links may point at a different host than the vanity base (api-idmedicaid -> api-ida-prd, opaque cursor continuation); AHDS backend since OnyxOS 5.7 (Apr 2024) implies `ct` continuation tokens and server-side _count caps | [likely]/[unverified] |
| Open APIs | Plan-Net "is an Open API" (no member auth); open formulary variants unauthenticated; CARIN/PDex/secure-formulary need a bearer token | [likely] |
| Portals | portal.safhir.io (prod developer portal; /portal/documentation), dev.safhir.io (sandbox "TEST - Onyx Test Environment"), developer.safhir.io, con.safhir.io (connectathon), per-tenant `https://api-<tenant>-prd.safhir.io/registration-portal/`; support@safhir.io, support@onyxhealth.io; status.safhir.io | [likely] |
| Registration flow | Register developer -> register app choosing >= 1 IG -> sandbox only at first -> email support@safhir.io for payer data providers -> payer POC approves -> credentials issued per payer organization, per application | [likely] |
| IG versions | Spring'25 (Feb 26, 2025): US Core 6.1.0, CARIN BB 2.1, PDex 2.1, Plan-Net 1.1, US Drug Formulary 2.1 (+ CRD/DTR/PAS); older tenants still on CARIN 1.0.0/1.1.0/1.2.0, USDF 1.0.0, US Core 3.1.1 — record expected versions per environment and validate against the served CapabilityStatement | [likely] |
| FHIR version | 4.0.1 on all observed tenants | [likely] |
| Sandbox test members | Pattern test<5 digits>@dmd.com / Track@NN (e.g. Test25001@dmd.com / Track@03 on con.safhir.io / DMDH; test30081@dmd.com / Track@04 on api-dmdh-alpha) — Onyx sandbox only; payer UAT members are tenant-provisioned | [likely] |
| Data cadence examples | MD (api-crsp-prd) live 2021-11-05; ID (api-idmedicaid) live 2022-01-15, weekly refresh, no 2016+ history; CT (api-cdss-prd) live 2022-09-08, biweekly refresh; CT/MD provider directory hosted elsewhere (convergent-pd.com) | [likely] |
| Security posture | SOC 2 Type II, HITRUST e1, Drummond re-certification of CARIN BB / Plan-Net / Formulary APIs; App Gateway + WAF (expect WAF 403s on malformed requests) | [likely] |
| Consent | No CMS-fixed consent duration; "many payers are considering a one year period"; refresh tokens stop after payer-configured expiry/revocation | [likely] |
| Metrics | OnyxInsights (Winter'24, Jan 2025): daily extraction + retrieval APIs and dashboard for 422.119(f) metrics | [likely] |

### 6.2 Environment record schema (fields an environment record needs)

```
Environment {
  identity: { name, tier: sandbox|uat|prod, vendor: "onyx-safhir"|other, tenantCode, docsUrl, portalUrl, statusUrl, supportEmail,
              goLiveDate, dataRefreshCadence, historyStartDate }          // freshness/depth expectations
  fhir: { fhirVersion: "4.0.1", rootUrl,                                  // e.g. https://api-<tenant>-<env>.safhir.io/v1
          igBases: { carinBb, pdex, usCore?, providerDirectory, formularySecure?, formularyOpen?, pas? : {
              baseUrl, expectedIgCanonical, expectedIgVersion, expectedUsCoreVersion (3.1.1|6.1.0|7.0.0),
              requiresAuth: bool, metadataUrl (default <base>/metadata), openApiUrl (default <base>/openapi.json),
              wellKnownUrl (default <base>/.well-known/smart-configuration) } } }
          // free-form per IG: Abacus-hosted and idmedicaid-style hosts break the tenant template
  oauth: { discoveryMode: wellknown|configured|capabilitystatement, authorizeUrl, tokenUrl, introspectionUrl?, revocationUrl?,
           issuer?, jwksUri?, audience (sent as aud; Onyx default <rootUrl>), extraAuthorizeParams (resource/audience),
           clientId, clientSecretRef, redirectUris[], scopes (string; default "openid fhirUser launch/patient offline_access patient/*.read"),
           scopeSyntax: v1|v2|custom, grantTypes ["authorization_code","refresh_token"],
           tokenAuthMethod: client_secret_post|client_secret_basic|private_key_jwt|none (Onyx default client_secret_post),
           privateKeyJwt?: { keyRef, alg: RS384|ES384, kid, jwksUrl },
           pkce: required|optional|off, patientIdSource: tokenResponse|idTokenFhirUser|introspection|configured,
           accessTokenTtlSecondsObserved (3600), consentDurationDays?, smartVersionExpected: 1.0.0|2.0.0|2.2.0 }
  backendServices?: { tokenUrl, clientId, keyRef, alg, systemScopes }     // Provider Access only
  memberAuth: { idpType: payer-portal|id.me|auth0-email-code|other, browserLoginNotes,
                testMembers: [{ label, username, passwordRef, expectedPatientId, expectedMemberId, memberIdSystem, mbi?, notes }] }
  http: { extraHeaders {} (reserve x-api-key / Ocp-Apim-Subscription-Key), userAgent, timeoutMs, retry { on: [429,503], honorRetryAfter: true },
          maxRps (default conservative, e.g. 5), acceptHeader "application/fhir+json" }
  pagination: { followNextLinksVerbatim: true, allowedNextLinkHosts[], sendAuthToAllowedHostsOnly: true,
                defaultPageSizeObserved (10), preferredCount (50-100), useTotalAccurate: bool, incrementalSyncParam "_lastUpdated" }
  capabilities (auto-detected from metadata, overridable): { supportsInclude, supportsRevinclude, supportsEverything, supportsProfileSearch,
                supportsUseSearch, supportsVread, supportsXml, supportsMemberMatch, supportsBulkExport, supportsIntrospection }
  cms0057: { paEnabled: bool, paIgBase (which base serves pdex-priorauthorization), paQueryStrategy: profile|use|clientFilter|auto,
             metricsApiUrl?, providerAccess?: { groupExportUrl, authType: backend-services }, payerToPayer?: { memberMatchUrl, mtlsCertRef } }
  memberIdentifiers: { memberIdSystem, uniqueMemberIdSystem?, medicaidIdSystem?, mbiSystem: "http://hl7.org/fhir/sid/us-mbi" }
  oracle?: { claimsFeedUrl|file, paEventFeed, clinicalReceiptFeed }     // ground truth for timeliness checks
}
```

Key quirks to encode: (1) one environment = several FHIR bases, each with its own metadata/openapi.json/well-known; (2) `aud` on authorize; (3) patient id from the token response; (4) Plan-Net/open formulary need no token; (5) next links may point at another host; (6) IG path names and versions vary by tenant; (7) no public evidence of API keys, rate limits, $everything or _include — make them detectable/configurable rather than assumed. [likely]

---

## 7. Conformance check catalog

Severity: **E** = error (SHALL / regulatory), **W** = warning (SHOULD / recommended IG), **I** = informational, **M** = manual attestation. Result model (Inferno-compatible): PASS / FAIL / SKIP (precondition unmet, e.g. "No X resources appear to be available") / OMIT (not applicable) plus messages; every check persists the full HTTP exchange (URL, headers, body hash, timestamps) for audit. Profile validation always uses `canonical|version`, fails only on validator `error` severity, and downgrades unresolvable X12/HIPPS terminology and version-less US Core canonical ambiguity to warnings. Unless stated, `{base}` is the IG base under test, `{pid}` the authorized patient id, and requests carry `Accept: application/fhir+json` and the bearer token.

### 7.1 Discovery and capability (DISC)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| DISC-01 | TLS version on every configured base and OAuth endpoint | TLS handshake probe | Negotiates TLS >= 1.2; TLS 1.0/1.1 rejected | E | US Core/SMART security; Inferno TLS tests [verified] |
| DISC-02 | CapabilityStatement retrievable | GET {base}/metadata | 200; body parses as CapabilityStatement; Content-Type application/fhir+json | E | FHIR; Inferno carin_bb_conformance_support [verified] |
| DISC-03 | FHIR version | from DISC-02 | CapabilityStatement.fhirVersion == "4.0.1" | E | 170.215(a)(1) [verified: eCFR mirror] |
| DISC-04 | JSON format declared | from DISC-02 | format contains json / application/fhir+json / application/json | E | C4BB/PDex SHALL json [verified] |
| DISC-05 | XML format declared | from DISC-02 | format contains xml | W | C4BB/PDex SHOULD xml [verified] |
| DISC-06 | CapabilityStatement is a valid FHIR resource | validate DISC-02 body | no validator errors | E | Inferno US Core CS group [verified] |
| DISC-07 | IG declaration | from DISC-02 | implementationGuide or instantiates contains the expected IG canonical (http://hl7.org/fhir/us/carin-bb/ImplementationGuide/hl7.fhir.us.carin-bb; PDex; US Core) | W | Inferno carin_bb_instantiate [verified] |
| DISC-08 | Supported profiles roster | from DISC-02 | rest.resource[].supportedProfile covers: C4BB base: C4BB-Patient, C4BB-Coverage, 6 EOB profiles, Organization, Practitioner, RelatedPerson; PDex base: us-core-patient + >= 1 other US Core/PDex profile; PA-enabled: pdex-priorauthorization on ExplanationOfBenefit | W | C4BB/PDex CapabilityStatements [verified] |
| DISC-09 | SHALL search parameters declared | from DISC-02 | each IG SHALL param (section 3) declared on the resource with matching type | W | [verified] |
| DISC-10 | EOB searchInclude declared | from DISC-02 | ExplanationOfBenefit searchInclude contains patient, provider, care-team, coverage, insurer, (payee for C4BB), * | W | [verified] |
| DISC-11 | Read-only surface | from DISC-02 | no create/update/patch/delete interactions declared on Patient Access resources (Consent create allowed on PDex) | I | analyst |
| DISC-12 | SMART configuration retrievable | GET {base}/.well-known/smart-configuration (no Accept header) | 200; Content-Type starts with application/json; valid JSON | E (W if discoveryMode=configured and CMS-1.0.0 baseline accepted with CapabilityStatement declaration) | SMART 1.0.0/2.x [verified] |
| DISC-13 | Well-known baseline fields (1.0.0) | from DISC-12 | authorization_endpoint (string), token_endpoint (string), capabilities (array of strings) present and non-blank | E | SMART 1.0.0 / Inferno STU1 [verified] |
| DISC-14 | Well-known STU2 fields | from DISC-12 | grant_types_supported includes authorization_code; code_challenge_methods_supported includes S256 and excludes plain | W (E if smartVersionExpected >= 2.0.0) | SMART 2.x / Inferno STU2 [verified] |
| DISC-15 | OIDC discovery fields | from DISC-12 | if capabilities includes sso-openid-connect: issuer and jwks_uri are strings; else issuer absent (warn only) | E / W | SMART 2.x [verified] |
| DISC-16 | Capability set "Patient Access for Standalone Apps" | from DISC-12 | capabilities ⊇ {launch-standalone, context-standalone-patient, permission-patient} and (client-public or client-confidential-symmetric) | E (2.x) / W (1.0.0) | SMART conformance [verified]; 170.215(c)(2) |
| DISC-17 | Recommended capabilities | from DISC-12 | sso-openid-connect, permission-offline, permission-user present; permission-v1 advertised when v1 scopes accepted | W | Inferno CARIN launch group; SMART backwards-compat [verified] |
| DISC-18 | Absolute endpoint URLs | from DISC-12 | every *_endpoint / jwks_uri / issuer is an absolute https URL | E (2.2.0) / W | SMART 2.2.0 [verified] |
| DISC-19 | scopes_supported covers requested scopes | from DISC-12 | if scopes_supported present it includes openid, fhirUser, launch/patient, offline_access and a patient/ scope form | W | SMART RECOMMENDED [verified] |
| DISC-20 | token_endpoint_auth_methods_supported consistent with configured method | from DISC-12 | configured tokenAuthMethod listed (if field present) | W | SMART [verified] |
| DISC-21 | introspection/revocation/management endpoints advertised | from DISC-12 | present and absolute | I | SMART RECOMMENDED [verified] |
| DISC-22 | CORS on discovery endpoints | GET well-known and /metadata with Origin: https://example.org | Access-Control-Allow-Origin equals origin or * | W | SMART 2.2.0; Inferno STU2.2 [verified] |
| DISC-23 | Legacy CapabilityStatement OAuth declaration | from DISC-02 | rest.security.service coding SMART-on-FHIR and oauth-uris extension with authorize + token; values equal well-known | I (E only if discoveryMode=capabilitystatement) | SMART 1.0.0 / Inferno STU1 [verified] |
| DISC-24 | Multi-base discovery (Onyx) | repeat DISC-02/12 per configured IG base | each authenticated base has its own metadata (and well-known or configured OAuth); Plan-Net/open-formulary metadata reachable unauthenticated | I | Onyx model [likely] |
| DISC-25 | OpenAPI document (Onyx) | GET {base}/openapi.json | 200 JSON; paths consistent with CapabilityStatement search params | I | Onyx docs [likely] |
| DISC-26 | Public documentation link discoverable | GET configured docsUrl without auth/cookies | 200 without registration/fee/email gate | E (regulatory, also DOC-01) | 422.119(d) [verified: eCFR mirror] |

### 7.2 Security and authorization (SEC)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| SEC-01 | Reject missing token | GET {base}/Patient/{pid} without Authorization | 401 (C4BB also allows 403/404); body OperationOutcome or empty; no resource data | E | C4BB/PDex security text [verified] |
| SEC-02 | Reject invalid / expired token | GET with Authorization: Bearer garbage; and with an expired token | 401 (or 403/404 for C4BB); no data | E | same |
| SEC-03 | Insufficient scope | token granted without patient/Coverage.* ; GET {base}/Coverage?patient={pid} | 403 (C4BB/PDex "403 insufficient scope") or empty/401; never data | E | [verified] |
| SEC-04 | Cross-patient isolation | GET {base}/Patient/{otherPid}; GET {base}/ExplanationOfBenefit?patient={otherPid} with member A token | 403 or 404 (or empty searchset); zero resources belonging to another patient | E | 422.119(a) member direction; C4BB security [verified: eCFR mirror] |
| SEC-05 | Plain HTTP refused | GET http://{host}/... | connection refused, 4xx, or redirect to https; never data | E | SMART/US Core TLS [verified] |
| SEC-06 | Authorize request accepted | browser-driven GET/POST authorize with response_type=code, client_id, redirect_uri, scope, state, aud, code_challenge (S256) | redirect to redirect_uri with code and unchanged state; no error param | E | SMART app-launch; Inferno smart_code_received [verified] |
| SEC-07 | POST-based authorization | POST authorize (form) if authorize-post advertised | same result as GET | W | SMART 2.x authorize-post [verified] |
| SEC-08 | PKCE enforced | exchange code without code_verifier (or wrong verifier) | 400/401 invalid_grant when PKCE advertised (S256 in well-known); tolerate success for 1.0.0-only servers (I) | E (2.x) / I | SMART 2.x PKCE [verified] |
| SEC-09 | state tampering detected client-side | callback with altered state | workbench rejects; server not blamed | I | SMART state rule [verified] |
| SEC-10 | aud handling | authorize with aud = configured audience; separately with aud = wrong URL | correct aud succeeds; wrong aud yields error (W if server ignores aud); record which of aud/resource/audience is honoured | W | SMART aud/resource [verified]; vendor variance [likely] |
| SEC-11 | redirect_uri mismatch rejected | authorize with unregistered redirect_uri | error / no code issued | E | OAuth 2.0 / SMART registration [verified] |
| SEC-12 | Token exchange succeeds | POST token (form-urlencoded) grant_type=authorization_code, code, redirect_uri, code_verifier, client auth per config | 200 JSON | E | SMART [verified] |
| SEC-13 | Token response headers | from SEC-12 | Cache-Control contains no-store; Pragma contains no-cache | E (2.x) / W (1.0.0) | SMART; Inferno smart_token_response_headers [verified] |
| SEC-14 | Token response body | from SEC-12 | access_token present; token_type == "Bearer" (case-insensitive); expires_in numeric; scope present; refresh_token present when offline_access requested; id_token present when openid requested | E | SMART; Inferno smart_token_response_body [verified] |
| SEC-15 | Access token lifetime | from SEC-12 | expires_in <= 3600 | W | SMART "SHOULD ... no greater than one hour" [verified] |
| SEC-16 | Granted scopes cover the test plan | from SEC-12 | granted scope ⊇ needed resource scopes (v1 or v2 forms, wildcard accepted) | E (skip downstream groups otherwise) | SMART scope rules [verified] |
| SEC-17 | Patient context established | from SEC-12 / id_token / introspection | `patient` in token response, or fhirUser resolves to a Patient, or introspection returns patient; value used as {pid} | E | SMART "SHALL establish a patient in context" [verified] |
| SEC-18 | Authorization code single-use | replay the same code at the token endpoint | 400 invalid_grant | E | OAuth 2.0 [likely] |
| SEC-19 | Invalid grant_type | POST token grant_type=not_a_grant_type | 400 | E | Inferno backend_services_invalid_grant_type pattern [verified] |
| SEC-20 | Client auth method variants | exchange with client_secret_basic and with client_secret_post | at least the configured method works; record the other | I | SMART symmetric profile [verified] |
| SEC-21 | Refresh token flow | POST token grant_type=refresh_token, refresh_token (no scope) | 200; no-store/no-cache headers; new access_token; token_type bearer; expires_in; scope ⊆ original; new refresh_token (if any) replaces old | E | SMART; Inferno smart_token_refresh_stu2 [verified] |
| SEC-22 | Refresh with scope subset | grant_type=refresh_token + scope = subset | 200 and granted scope ⊆ requested subset | W | SMART [verified] |
| SEC-23 | Refresh bound to client | refresh with a different client_id / no client auth for confidential app | 400/401 | E | SMART "bound to the same client_id" [verified] |
| SEC-24 | id_token validation | decode id_token; GET {issuer}/.well-known/openid-configuration; fetch jwks_uri | header alg RS256 + kid; signature valid; iss == issuer; aud == client_id; exp/iat sane; sub present | E (when openid granted) | SMART OIDC; Inferno smart_openid_connect [verified] |
| SEC-25 | fhirUser claim resolves | GET fhirUser URL with bearer token | 200; resourceType in {Patient, Practitioner, PractitionerRole, RelatedPerson, Person}; for member apps equals Patient/{pid} | E | SMART OIDC [verified] |
| SEC-26 | Token introspection | POST introspection_endpoint token=... (bearer auth if required) | active true; scope, client_id, exp present; patient present; exp consistent with expires_in | W (E if 170.215(c)(2) profile claimed) | SMART Token Introspection [verified] |
| SEC-27 | Introspection of revoked/expired token | after revocation or expiry | active false | W | RFC 7662 [likely] |
| SEC-28 | Revocation endpoint | POST revocation_endpoint | 200; subsequent API call with the token -> 401 | I | SMART RECOMMENDED [verified] |
| SEC-29 | Backend services (if Provider Access in scope) | POST token grant_type=client_credentials, client_assertion_type jwt-bearer, client_assertion (iss=sub=client_id, aud=token_url, exp<=5 min, jti, kid) | 200/201; access_token; token_type bearer; expires_in numeric (SHOULD <= 300); scope; invalid client_assertion_type -> 400/401; invalid JWT -> 400/401 | W (OMIT for Patient Access only) | SMART Backend Services; Inferno [verified] |
| SEC-30 | Provider Directory / open formulary require no user auth | GET providerDirectory base /Practitioner?name=a without token | 200 searchset | E (Provider Directory) / I (formulary) | 422.120 etc. [verified: eCFR mirror] |
| SEC-31 | WAF/proxy behaviour on malformed requests | GET {base}/Patient?name=%00%FF | 400 or 403 with OperationOutcome/empty; never 5xx | I | Onyx WAF [likely] |
| SEC-32 | Consent expiry behaviour | refresh after configured consentDurationDays (or after revocation in member portal) | 400 invalid_grant; API 401 | I | Onyx consent guidance [likely] |

### 7.3 Patient (PAT)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| PAT-01 | Read the context patient | GET {base}/Patient/{pid} | 200; resourceType Patient; id == {pid} | E | C4BB/PDex read SHALL [verified] |
| PAT-02 | Profile validation | validate PAT-01 (C4BB base: C4BB-Patient\|2.1.0; PDex base: us-core-patient\|<expectedUsCoreVersion>) | no errors | E | [verified] |
| PAT-03 | Must-support coverage | across all Patient instances | each MS element (identifier(+system/value), name, gender, birthDate, address, telecom, communication, race/ethnicity/birthsex extensions per US Core) populated in >= 1 instance or DAR | W | Inferno MS algorithm [verified] |
| PAT-04 | Member identifier slice | PAT-01 | identifier with type v2-0203#MB present with system and value; system == configured memberIdSystem; value == expectedMemberId | E (C4BB) | C4BB-Patient memberid 1..* [verified] |
| PAT-05 | Unique member identifier | PAT-01 | if present: type C4BBIdentifierType#um with system/value | I | [verified] |
| PAT-06 | MBI format (Medicare) | PAT-01 | any identifier with system http://hl7.org/fhir/sid/us-mbi has 11 chars, no spaces/dashes, no B/I/L/O/S/Z, matches MBI pattern | W | THO cmsMBI [verified]; letter rule [likely] |
| PAT-07 | meta.lastUpdated and meta.profile | PAT-01 | meta.lastUpdated present (instant); meta.profile lists the served profile canonical | E (C4BB) / W (PDex SHOULD) | [verified] |
| PAT-08 | Search by _id | GET {base}/Patient?_id={pid} | 200 searchset; exactly one Patient == {pid} | E | C4BB/PDex/US Core _id SHALL [verified] |
| PAT-09 | Search by identifier | GET {base}/Patient?identifier={system}\|{value} and identifier={value} | 200; the context patient; another member's identifier -> empty/403/404 | E (PDex/US Core) / I (C4BB MAY search) | [verified] |
| PAT-10 | Search by name | GET {base}/Patient?name={family} | 200; context patient returned | E (US Core/PDex) | US Core 3.1.1 SHALL [verified] |
| PAT-11 | Search birthdate+name; gender+name | GET {base}/Patient?birthdate=...&name=... ; ?gender=...&name=... | 200; context patient | E (US Core) | US Core 3.1.1 SHALL [verified] |
| PAT-12 | Search birthdate+family; family+gender | as above | 200 | W | US Core 3.1.1 SHOULD [verified] |
| PAT-13 | _lastUpdated search | GET {base}/Patient?_id={pid}&_lastUpdated=ge{lastUpdated-1d} | 200; patient returned; lt comparator excludes | W | C4BB SHOULD [verified] |
| PAT-14 | vread | GET {base}/Patient/{pid}/_history/{versionId} | 200 same content | W | C4BB/PDex vread SHOULD [verified] |
| PAT-15 | Provenance revinclude | GET {base}/Patient?_id={pid}&_revinclude=Provenance:target | 200; >= 1 Provenance (PDex/US Core base); Provenance validates; provenance-1 invariant holds | W | US Core/PDex [verified] |
| PAT-16 | Cross-base identity consistency | compare Patient from carin-bb and pdex bases | same logical id or same MB identifier; names/birthDate agree | I | analyst |

### 7.4 Coverage (COV)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| COV-01 | Search by patient | GET {base}/Coverage?patient={pid} and ?patient=Patient/{pid} | 200 searchset; >= 1 Coverage; identical results for both forms; every beneficiary references {pid} | E | PDex/US Core SHALL; Inferno search semantics [verified] |
| COV-02 | Search by _id | GET {base}/Coverage?_id={covId} | 200; one Coverage | E (C4BB) | [verified] |
| COV-03 | Read | GET {base}/Coverage/{covId} | 200; id matches | E | [verified] |
| COV-04 | Profile validation | validate (C4BB-Coverage\|2.1.0 / hrex-coverage\|1.1.0 / us-core-coverage) | no errors | E | [verified] |
| COV-05 | Required elements | each Coverage | meta.lastUpdated, meta.profile, status, beneficiary, payor, subscriberId (C4BB 1..1) present | E (C4BB) | C4BB-Coverage [verified] |
| COV-06 | _include Coverage:payor | GET {base}/Coverage?_id={covId}&_include=Coverage:payor | payor Organization(s) in Bundle | E (C4BB) | [verified] |
| COV-07 | Payor resolves | GET each Coverage.payor reference | 200 Organization; validates C4BB-Organization; has payerid identifier | E | C4BB referencePolicy resolves [verified] |
| COV-08 | _lastUpdated search | GET {base}/Coverage?patient={pid}&_lastUpdated=ge... | 200 with comparator semantics | W | C4BB SHOULD [verified] |
| COV-09 | Coverage effective as of claim DOS | for each EOB.insurance.coverage: Coverage.period covers EOB.billablePeriod.start | true (or versioned reference resolves to a version whose period covers it) | W | C4BB Coverage-as-of-DOS rule [verified] |
| COV-10 | vread | GET {base}/Coverage/{covId}/_history/{v} | 200 | W | [verified] |
| COV-11 | Provenance revinclude (PDex base) | GET {base}/Coverage?patient={pid}&_revinclude=Provenance:target | Provenance returned | W | PDex [verified] |

### 7.5 Claims and encounter EOBs — CARIN BB (EOB)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| EOB-01 | Search by patient (two forms) | GET {base}/ExplanationOfBenefit?patient={pid} ; ?patient=Patient/{pid} | 200 searchset; same result set; every EOB.patient == {pid}; SKIP if zero | E | C4BB SHALL; Inferno carin_search_test [verified] |
| EOB-02 | POST _search equivalence | POST {base}/ExplanationOfBenefit/_search (form) patient={pid} | same count/ids as GET | E | Inferno [verified] |
| EOB-03 | Search by _id | GET ?_id={eobId} | 200; exactly that EOB | E | [verified] |
| EOB-04 | _lastUpdated (with patient) | GET ?patient={pid}&_lastUpdated=ge{d-1}; &_lastUpdated=lt{d} | comparator semantics hold on returned meta.lastUpdated | E | C4BB SHALL [verified] |
| EOB-05 | type | GET ?patient={pid}&type={code} and type=http://terminology.hl7.org/CodeSystem/claim-type\|{code} | 200; all returned EOB.type contain the code; both token forms accepted | E | C4BB SHALL; token precision rule [verified] |
| EOB-06 | identifier (unique claim id) | GET ?patient={pid}&identifier={system}\|{value} | 200; the EOB | E | [verified] |
| EOB-07 | service-date | GET ?patient={pid}&service-date=ge{start-1d}; lt{end+1d}; sa/eb | matches billablePeriod or item.serviced per comparator | E | C4BB SearchParameter [verified] |
| EOB-08 | service-start-date | GET ?patient={pid}&service-start-date=ge... | matches billablePeriod.start / item.serviced start | E | [verified] |
| EOB-09 | billable-period-start | GET ?patient={pid}&billable-period-start=ge... | matches billablePeriod.start | E | [verified] |
| EOB-10 | Parameter combinations | GET ?patient={pid}&type=...&service-date=ge...&_lastUpdated=ge... | 200; intersection semantics | E | C4BB "individually and in combination" [verified] |
| EOB-11 | _include each target | GET ?_id={eobId}&_include=ExplanationOfBenefit:{patient\|provider\|care-team\|coverage\|insurer\|payee} | every referenced target of that path present in Bundle (mode include) | E | C4BB SHALL includes [verified] |
| EOB-12 | _include=* and iterate | GET ?_id={eobId}&_include=ExplanationOfBenefit:*&_include:iterate=Coverage:payor | all six targets + payor Organization present | E | "_include:* SHALL be supported" [verified] |
| EOB-13 | Included content equals direct read | compare each included resource with GET {type}/{id} (or vread for versioned refs) | byte-equal JSON after canonicalization | E | C4BB/PDex "SHALL return the same content" [verified] |
| EOB-14 | Read | GET {base}/ExplanationOfBenefit/{eobId} | 200; id matches | E | [verified] |
| EOB-15 | vread | GET .../_history/{v} | 200 | W | vread SHOULD [verified] |
| EOB-16 | Profile declaration | each EOB | meta.profile contains exactly one concrete C4BB type profile (Inpatient-Institutional, Outpatient-Institutional, Professional-NonClinician, Pharmacy, Oral); use == claim | E | C4BB meta.profile 1..*, use patternCode claim [verified] |
| EOB-17 | Profile validation | validate against declared profile\|2.1.0 (or configured version) | no errors | E | [verified] |
| EOB-18 | Must-support coverage per profile | across EOBs of each type | each MS element/slice (e.g. Inpatient: adjudication slices, billablePeriod.start/end, careTeam.provider/role, diagnosis, identifier:uniqueclaimid.value, insurance.coverage/focal, item.*, meta.lastUpdated, outcome, payee, payment.date/type, procedure, processNote.text, provider, related, status, subType, supportingInfo slices, total.category, type, use) present in >= 1 instance | W | Inferno must_support_test lists [verified] |
| EOB-19 | Required core elements | each EOB | meta.lastUpdated; identifier slice uniqueclaimid (C4BBIdentifierType#uc) with value; status; type in claim-type; billablePeriod.start; created; insurer; provider; outcome; insurance with exactly one focal=true; insurance.coverage | E | C4BB-ExplanationOfBenefit [verified] |
| EOB-20 | type has no data-absent-reason | each EOB | EOB.type carries no DAR extension/code | E | Inferno c4bb_v200_custom_eob_type_data_absent [verified] |
| EOB-21 | insurer equals focal payor | each EOB | EOB.insurer.reference == Coverage(focal).payor[0].reference and != payor of non-focal coverages | E | Inferno c4bb_v200_custom_eob_insurer_same [verified] |
| EOB-22 | outcome complete | each EOB | EOB.outcome == complete | E | Inferno c4bb_v200_custom_eob_outcome_complete [verified] |
| EOB-23 | Required bindings | each EOB | payee.type in C4BBPayeeType; payment.type in C4BBPayerClaimPaymentStatusCode; related.relationship in C4BBRelatedClaimRelationshipCodes; item.adjudication.category / total.category in C4BB adjudication value sets | E | [verified] |
| EOB-24 | Reference resolution | for each MS Reference (patient, provider, insurer, careTeam.provider, insurance.coverage, payee.party, facility) | GET resolves 200 and validates against target profile | E | Inferno reference_resolution_test; referencePolicy resolves [verified] |
| EOB-25 | Versioned references when point-in-time | each EOB | if references are versioned they resolve via vread; consistent policy across EOBs | I | C4BB versioned-reference rule [verified] |
| EOB-26 | Claim family coverage | over all EOBs | at least institutional and professional present; Pharmacy EOBs present for MA-PD / Medicaid (configurable expectation) | I | 422.119(b)(2) [verified: eCFR mirror] |
| EOB-27 | Historical depth | GET ?patient={pid}&service-date=lt2017-01-01 (test member with old claims) | EOBs with DOS back to 2016-01-01 returned when the payer holds them (skip if historyStartDate later, e.g. Idaho) | W | 422.119(h) DOS >= Jan 1, 2016 [verified: eCFR mirror] |
| EOB-28 | Appealed / appealable claims | inspect EOBs | claims under appeal present (related / processNote / status) per payer oracle | M | 422.119(b)(1)(i) [verified: eCFR mirror] |
| EOB-29 | DAR usage recorded | across EOBs | log data-absent-reason extension and code usage (informational scratch for US Core-style missing-data tests) | I | Inferno validation_test flags [verified] |
| EOB-30 | Search without patient for non-patient params | GET ?_lastUpdated=ge... (no patient) | either 200 scoped to the token's patient or 400; never another patient's data | I | PDex "require a patient search argument" (PDex base) [verified] |

### 7.6 Prior authorization EOBs — PDex (PA)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| PA-01 | PA capability declared | DISC-02 on PA base | ExplanationOfBenefit supportedProfile includes pdex-priorauthorization | W (E from Jan 1, 2027 if paEnabled) | PDex pdex-server [verified]; 422.119(b)(1)(iv) [verified: eCFR mirror] |
| PA-02 | Query strategy discovery | GET ?patient={pid}&_profile=<pdex-priorauthorization>; GET ?patient={pid}&use=preauthorization; GET ?patient={pid} + client filter | record which strategies return only PA EOBs; at least the client-filter path yields the PA set; `_profile`/`use` graded W if unsupported | E (client filter) / W | PDex 2.2.0 FHIR-56380; SearchParameter explanationofbenefit-use [verified] |
| PA-03 | Mixed result set | GET ?patient={pid} | contains both claim EOBs (use=claim) and PA EOBs (use=preauthorization) for a member with both | W | PDex §pdex-214 [verified] |
| PA-04 | use = preauthorization | each PA EOB | ExplanationOfBenefit.use == preauthorization | E | patternCode [verified] |
| PA-05 | meta.profile declaration | each PA EOB | meta.profile contains http://hl7.org/fhir/us/davinci-pdex/StructureDefinition/pdex-priorauthorization (version-less or \|2.x) | E | supportedProfile slice 1..1 [verified] |
| PA-06 | Profile validation | validate against pdex-priorauthorization\|2.1.0 (US Core pinned; X12/HIPPS warnings downgraded) | no errors | E | [verified] |
| PA-07 | Must-support coverage | across PA EOBs | status, type, use, patient, enterer, insurer, provider, facility, preAuthRefPeriod, careTeam.provider, item.category, item.extension slices, item.adjudication slices, reviewAction, adjudicationActionDate, total.category, priorauth-utilization, levelOfServiceType each present in >= 1 instance | W | MS list [verified] |
| PA-08 | Status derivable | each PA EOB | at least one reviewAction.code (item or header) or a denialreason slice or status=cancelled; derived status != Unknown | E (regulatory element 1) | reviewAction; 422.119(b)(1)(iv)(A)(1) [verified] |
| PA-09 | reviewAction.code vocabulary | each reviewAction | coding.system == https://codesystem.x12.org/005010/306 and code in {A1,A2,A3,A4,A6,C,CT,NA}; unknown code -> W | W | X12 306 [verified A1/A3/A4; others likely] |
| PA-10 | Decision date present when decided | PA EOBs with A1/A2/A3/A6 | adjudicationActionDate (when-adjudicated) or item preAuthIssueDate present; dateTime parses; not in the future | E (element 2) | [verified] |
| PA-11 | Pended PA has no decision date | PA EOBs with A4 | no adjudicationActionDate for that decision; status active | W | analyst over [verified] structure |
| PA-12 | End date / circumstance | approved PA EOBs | preAuthRefPeriod.end or item preAuthPeriod.end present; end >= start; cancelled/denied PAs carry status cancelled or code C/A3 | E (element 3) | [verified] |
| PA-13 | Items and services approved | approved PA EOBs | each item has productOrService (CPT/HCPCS/HIPPS or DAR not-applicable) and category in X12 service-type codes; authorizedItemDetail/allowedunits present when partial (A2/A6) | E (element 4) / W for detail | [verified] |
| PA-14 | Specific denial reason | denied PA EOBs (A3 or denialreason slice) | denialreason.reason coded (CARC/RARC system https://x12.org/codes/claim-adjustment-reason-codes or .../remittance-advice-remark-codes) and/or reviewAction.reasonCode (https://codesystem.x12.org/external/886) present, with display or text | E (element 5; from Jan 1, 2027) | [verified] |
| PA-15 | Supporting documentation | each PA EOB | supportingInfo entries with valueReference DocumentReference (or attachment) resolve 200; attachments retrievable | W (element 6; representation is PDex 2.2.0 guidance) | FHIR-56252 [verified] |
| PA-16 | Quantity consistency (IG extra) | PA EOBs with units | consumedunits.value <= allowedunits.value; total[utilized] <= total[eligible]; PriorAuthorizationUtilization value is Quantity or Ratio | I | [verified] structure |
| PA-17 | Adjudication amount-type invariant | each PA EOB with adjudication | >= 1 adjudicationamounttype slice with amount when adjudication present | W | invariant adjudication-has-amount-type-slice (warning) [verified] |
| PA-18 | Header adjudication slices | each PA EOB | header adjudication uses only adjudicationamounttype / denialreason categories (closed slicing) | E | [verified] |
| PA-19 | Item adjudication slices | each PA EOB | item.adjudication categories within PDexAdjudicationCategoryDiscriminator; allowedunits/consumedunits have value; denialreason has reason | E | [verified] |
| PA-20 | outcome not used as decision | each PA EOB | workbench derived status ignores outcome; report if outcome=complete with no reviewAction (W) | I | example outcome=queued [verified] |
| PA-21 | Coverage reference | each PA EOB | insurance.coverage resolves; Coverage validates hrex-coverage; beneficiary == {pid}; exactly one focal | E | [verified] |
| PA-22 | Reference resolution | enterer, insurer, provider, facility, careTeam.provider, authorizedProvider.provider | resolve 200 and validate US Core targets | E | [verified] |
| PA-23 | Identifier / PA number search | GET ?patient={pid}&identifier={system}\|{paNumber} | returns that PA EOB | E | identifier SHALL [verified] |
| PA-24 | _lastUpdated polling | GET ?patient={pid}&_lastUpdated=ge{watermark} | returns PA EOBs changed since watermark; meta.lastUpdated >= latest adjudicationActionDate | E | _lastUpdated SHALL; 1-business-day update rule [verified] |
| PA-25 | service-date / type / _id / read | as EOB-03/05/07/14 on PA EOBs | pass criteria as in 7.5 | E | PDex SHALL [verified] |
| PA-26 | _include on PA EOBs | GET ?_id={paId}&_include=ExplanationOfBenefit:* &_include:iterate=Coverage:payor | patient, provider, care-team, coverage, insurer (+payor) included; equals direct read | E | "_include:* SHALL be supported" [verified] |
| PA-27 | Drug PAs excluded / flagged | PA EOBs with type pharmacy or NDC productOrService | informational count (drugs excluded from CMS-0057-F; CMS-0062-P may change) | I | 422.119(b)(1)(v) [verified: eCFR mirror] |
| PA-28 | Urgency | each PA EOB | levelOfServiceType coding system https://codesystem.x12.org/005010/1338 with U/E | I | [verified] |
| PA-29 | Human-readable summary generation | each PA EOB | section 4.4 algorithm yields status, decision date, end date, items, denial reason without exceptions; summary attached to report | E | analyst |
| PA-30 | Scope-filtered access | token lacking PA permission (if payer distinguishes) | PA EOBs omitted while claims returned (or 403) | I | PDex §pdex-218/219 [verified] |
| PA-31 | Retention window (fixture-driven) | seeded PAs: active; denied 11 months ago; expired 13 months ago | active and <= 1-year-since-last-status-change PAs returned; older ones may be absent | E (from Jan 1, 2027) | 422.119(b)(1)(iv)(B)(3) [verified: eCFR mirror] |
| PA-32 | Pended-to-decided transition | seeded PA whose status changes during the run | new reviewAction and updated meta.lastUpdated visible within 1 business day (TIME-05) | E (from Jan 1, 2027) | (B)(2) [verified: eCFR mirror] |
| PA-33 | Synthetic fixture validation | validate workbench fixtures (A2/A3/A4/A6/C variants) | fixtures validate with warnings only | I | package has only an A1 example [verified] |

### 7.7 Clinical data — US Core / PDex (CLIN)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| CLIN-01 | Search by patient per declared clinical resource | GET {base}/{Type}?patient={pid} (and Patient/{pid}) for AllergyIntolerance, CarePlan, CareTeam, Condition, DiagnosticReport, DocumentReference, Encounter, Goal, Immunization, MedicationRequest, Observation, Procedure, Device, ServiceRequest, MedicationDispense (PDex) | 200 searchset; all entries of the type; subject/patient == {pid}; SKIP when empty | E | US Core/PDex SHALL [verified] |
| CLIN-02 | Required combination searches | Condition patient+category; Observation patient+category, patient+code, patient+category+date; DiagnosticReport same; MedicationRequest patient+intent(+status); Encounter date+patient, _id; DocumentReference _id, patient+type, patient+category(+date); Procedure patient+date; CarePlan patient+category; CareTeam patient+status; ServiceRequest patient+category(+authored), patient+code | 200 and every result matches all parameters | E | US Core SHALL combos [verified] |
| CLIN-03 | SHOULD combination searches | Condition patient+clinical-status; Observation patient+category+status, patient+code+date; MedicationRequest patient+intent+encounter/authoredon; Encounter class+patient, patient+status, patient+type; Immunization patient+date/status; AllergyIntolerance patient+clinical-status; Goal patient+target-date/lifecycle-status | 200 | W | US Core SHOULD [verified] |
| CLIN-04 | Status fallback | if a search returns 400 + OperationOutcome, retry with each required-binding status | eventual 200 | I | US Core / Inferno search_test [verified] |
| CLIN-05 | Multiple-OR values | GET ?patient={pid}&code=a,b,c (values from fixtures) | union of results | W | Inferno [verified] |
| CLIN-06 | Date comparators | gt/ge/lt/le around fixture dates | comparator semantics hold | E | Inferno date_search_validation [verified] |
| CLIN-07 | POST _search equivalence | POST {Type}/_search | same as GET | E | Inferno [verified] |
| CLIN-08 | Read each fixture | GET {Type}/{id} | 200; id matches | E | [verified] |
| CLIN-09 | Profile validation | validate against US Core profile\|<version> (Observation: category-specific profiles; DiagnosticReport lab/note; PDex MedicationDispense, Device) | no errors | E | [verified] |
| CLIN-10 | Must-support coverage | across instances per profile | MS elements present in >= 1 instance | W | Inferno MS [verified] |
| CLIN-11 | Reference resolution | MS References (subject, encounter, performer, medication, requester, author) | resolve 200 and validate | E | Inferno reference_resolution_test [verified] |
| CLIN-12 | MedicationRequest / MedicationDispense _include medication | GET ?patient={pid}&_include=MedicationRequest:medication ; MedicationDispense:medication | external Medication resources included when referenced | E (PDex MedicationDispense SHALL) / W | PDex CS [verified] |
| CLIN-13 | Provenance via _revinclude | GET {Type}?patient={pid}&_revinclude=Provenance:target | >= 1 Provenance; targets are the returned resources; validates us-core-provenance / pdex-provenance; provenance-1 holds; no unexpected resource types | W | US Core/PDex [verified] |
| CLIN-14 | Missing data representation | across all clinical resources | when required elements are absent the DAR extension or DAR code system is used; record dar_extension_found / dar_code_found | W | US Core Missing Data tests [verified] |
| CLIN-15 | $docref | GET/POST {base}/DocumentReference/$docref?patient={pid} | 200 Bundle of DocumentReference (if US Core 6.1.0+ claimed) | W | US Core SHALL [verified via master CS] |
| CLIN-16 | Vital signs / lab profiles | Observation?patient={pid}&category=vital-signs ; laboratory | instances validate against the category-specific US Core profiles (LOINC codes, UCUM units) | E | US Core [verified] |
| CLIN-17 | USCDI class coverage matrix | aggregate CLIN-01 results | report which USCDI v1/v3 classes return data for the member (patient demographics, allergies, meds, problems, labs, vitals, procedures, immunizations, care team, care plan, clinical notes, encounters, goals, devices, provenance) | I | 170.213 [verified: eCFR mirror] |
| CLIN-18 | Clinical resources scoped to patient | GET {Type}?patient={otherPid} | 403/404/empty | E | SEC-04 |
| CLIN-19 | Location / Organization / Practitioner searches | GET Location?name= ; Organization?name= ; Practitioner?identifier= ; PractitionerRole?practitioner= | 200 (scope permitting) | W | US Core SHALL [verified] |

### 7.8 Formulary and Provider Directory (FORM / PDIR)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| FORM-01 | Formulary metadata | GET {formularyBase}/metadata (with and without token per requiresAuth) | 200; usdf-server profiles declared (InsurancePlan usdf-PayerInsurancePlan/usdf-Formulary, Basic usdf-FormularyItem, MedicationKnowledge usdf-FormularyDrug, Location usdf-InsurancePlanLocation) | E (MA-PD, Medicaid) | 422.119(b)(2), 431.60(b)(4); USDF CS [verified] |
| FORM-02 | InsurancePlan searches | GET InsurancePlan?type=...; ?_id; ?identifier; ?status; ?name; ?coverage-type; ?formulary-coverage; ?coverage-area; ?period | 200; results match | E | USDF CS [verified] |
| FORM-03 | Formulary linkage | each PayerInsurancePlan | coverage / usdf-FormularyReference resolves to a usdf-Formulary InsurancePlan | E | USDF profiles [verified] |
| FORM-04 | FormularyItem searches | GET Basic?formulary={id}; ?drug-tier=; ?pharmacy-benefit-type=; ?code=; ?subject=; ?status=; ?period= | 200; results match | E | [verified] |
| FORM-05 | FormularyDrug searches | GET MedicationKnowledge?code={rxnorm}; ?drug-name=; ?doseform=; ?status= | 200; code system RxNorm | E | [verified] |
| FORM-06 | Location searches | GET Location?address-state=... etc. | 200 | W | [verified] |
| FORM-07 | Profile validation | validate all usdf-* instances against \|2.1.0 (or expected version) | no errors | E | [verified] |
| FORM-08 | Tier / UM content | FormularyItems | usdf-DrugTierID present; usdf-PriorAuthorization, usdf-QuantityLimit(+Detail), usdf-StepTherapyLimit present where applicable; usdf-PharmacyBenefitType, usdf-AvailabilityStatus/Period | E (regulatory content) | 422.119(b)(2)(ii) [verified: eCFR mirror] |
| FORM-09 | _lastUpdated freshness | GET Basic?formulary={id}&_lastUpdated=ge{effectiveDate} | items updated within 1 business day of effective date (Medicaid) — oracle-driven | M | 431.60(b)(4) [verified: eCFR mirror] |
| FORM-10 | Open vs secured variants | GET open formulary base without token; secure base without token | open -> 200; secure -> 401 | I | Onyx [likely] |
| FORM-11 | Bulk GraphDefinition | GET GraphDefinition/usdf-FormularyBulkDataGraphDefinition | 200 (optional) | I | USDF [verified] |
| PDIR-01 | Unauthenticated access | GET {pdBase}/metadata ; /Practitioner?name=smith without token | 200; no auth challenge | E | 422.120 / 431.70 / 457.760 [verified: eCFR mirror] |
| PDIR-02 | Practitioner / PractitionerRole searches | GET Practitioner?name=; ?family=&given=; PractitionerRole?specialty=; ?network=; ?organization=; ?location= with _include | 200; results match; includes present | E (recommended IG) | Plan-Net CS [verified] |
| PDIR-03 | Organization / Location / HealthcareService / InsurancePlan searches | GET Organization?name=; Location?address-postalcode=; HealthcareService?specialty=; InsurancePlan?type= | 200 | E | [verified] |
| PDIR-04 | Minimum data present | PractitionerRole + Practitioner + Location + Organization | names, addresses, phone numbers, specialties populated; MA-PD: pharmacy Organizations/Locations with type | E | 422.120(b) [verified: eCFR mirror] |
| PDIR-05 | Profile validation | validate plannet-* instances \|1.2.0 (or 1.1.0) | no errors | E | [verified] |
| PDIR-06 | Freshness | meta.lastUpdated vs payer update feed | within 30 calendar days | M | 30-day rule [verified: eCFR mirror] |

### 7.9 Pagination (PAGE)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| PAGE-01 | Bundle structure | any search | type searchset; link[self] present; entry.fullUrl present and consistent with base; entry.search.mode match/include | E | FHIR R4 Bundle [likely] |
| PAGE-02 | _count honored | GET ?patient={pid}&_count=2 | <= 2 entries; link[next] when more exist | E | FHIR search [likely] |
| PAGE-03 | Follow next links verbatim | iterate link[next] until absent | every page 200; union has no duplicate ids; all pages of the same type; bearer token sent only to allowedNextLinkHosts | E | FHIR; Onyx alternate-host quirk [likely] |
| PAGE-04 | Cross-host next links | inspect next URLs | host in allowedNextLinkHosts (else W and record) | W | Onyx [likely] |
| PAGE-05 | Total consistency | GET ?patient={pid}&_total=accurate | Bundle.total == count of all pages (if supported) | W | Onyx FAQ _total=accurate [likely] |
| PAGE-06 | Default page size observed | GET ?patient={pid} | record entry count (Onyx says up to 10) | I | [likely] |
| PAGE-07 | Large _count capped gracefully | GET ?patient={pid}&_count=1000 | 200 with server cap, not 4xx/5xx | W | analyst |
| PAGE-08 | Page stability | fetch page 2 twice | identical content (or documented cursor TTL); expired cursor -> 4xx OperationOutcome not 5xx | I | analyst; AHDS ct tokens [unverified] |
| PAGE-09 | POST _search pagination | POST _search then follow next (GET) | works | W | FHIR [likely] |
| PAGE-10 | _elements / _summary | GET ?patient={pid}&_summary=count ; &_elements=id,status | 200 or graceful ignore | I | FHIR MAY |
| PAGE-11 | Included resources across pages | _include with paging | included resources appear on the page of the referencing match | W | FHIR [likely] |

### 7.10 Error handling (ERR)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| ERR-01 | Unknown resource id | GET {base}/ExplanationOfBenefit/does-not-exist | 404 + OperationOutcome | E | C4BB/PDex "404 unknown resource" [verified] |
| ERR-02 | Invalid parameter value | GET ?patient={pid}&service-date=not-a-date | 400 + OperationOutcome | E | "400 invalid parameter" [verified] |
| ERR-03 | Unknown parameter | GET ?patient={pid}&bogus=1 (and with Prefer: handling=strict) | lenient: 200 ignoring (self link shows applied params); strict: 400 | W | FHIR search handling [likely] |
| ERR-04 | Unsupported resource type | GET {base}/Foo | 404 (or 400) OperationOutcome | E | FHIR [likely] |
| ERR-05 | Deleted resource | GET a deleted id (fixture) | 410 | W | "410 deleted resource" [verified] |
| ERR-06 | Write attempts refused | POST/PUT/DELETE {base}/Patient... | 403/405 (Consent create may be allowed on PDex) | E | read-only Patient Access [analyst] |
| ERR-07 | Unsupported _format / Accept | Accept: text/csv ; ?_format=csv | 406 or 400 OperationOutcome | W | FHIR [likely] |
| ERR-08 | XML negotiation | Accept: application/fhir+xml | XML (SHOULD) or 406 | W | C4BB/PDex SHOULD xml [verified] |
| ERR-09 | OperationOutcome validity | every 4xx body | valid OperationOutcome with issue.severity and issue.code | E | FHIR [likely] |
| ERR-10 | Content-Type on success | any 200 | application/fhir+json (charset utf-8 optional) | E | FHIR [likely] |
| ERR-11 | Rate limiting behaviour | burst above maxRps | 429 with Retry-After honoured by workbench; no 5xx; document limit | I | Onyx limits unpublished [unverified] |
| ERR-12 | No 5xx during run | aggregate | zero 5xx responses | E | analyst |
| ERR-13 | Non-patient EOB search rejected/scoped | GET ?service-date=ge2020-01-01 (no patient) on PDex base | 400 or scoped 200; never other patients' data | I | PDex "require a patient search argument" [verified] |
| ERR-14 | Malformed JSON / oversized request | POST _search with invalid body | 400, not 5xx | W | analyst |
| ERR-15 | HEAD / OPTIONS | HEAD {base}/metadata; OPTIONS with Origin | 200/204 or 405; CORS headers per DISC-22 | I | analyst |

### 7.11 Data timeliness and completeness (TIME) — oracle-driven; require a payer-side event feed or seeded fixtures

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| TIME-01 | Claims within 1 business day | for each adjudicated claim in the oracle feed, poll GET ?patient={pid}&identifier={claimId} | EOB present with meta.lastUpdated <= adjudication time + 1 business day | E | 422.119(b)(1)(i) [verified: eCFR mirror] |
| TIME-02 | Encounter data within 1 business day | oracle encounter receipt events | EOB/Encounter visible within 1 business day of receipt | E | (b)(1)(ii) [verified: eCFR mirror] |
| TIME-03 | Clinical data within 1 business day | oracle clinical receipt events | resource visible within 1 business day | E | (b)(1)(iii) [verified: eCFR mirror] |
| TIME-04 | PA visible within 1 business day of request receipt | oracle PA request events | PA EOB (A4/pending) visible within 1 business day | E (from Jan 1, 2027) | (b)(1)(iv)(B)(1) [verified: eCFR mirror] |
| TIME-05 | PA status change within 1 business day | oracle status-change events | updated reviewAction/adjudicationActionDate and meta.lastUpdated within 1 business day | E (from Jan 1, 2027) | (B)(2) [verified: eCFR mirror] |
| TIME-06 | PA retained >= 1 year after last status change | seeded historical PAs | still returned | E (from Jan 1, 2027) | (B)(3) [verified: eCFR mirror] |
| TIME-07 | History back to 2016-01-01 | EOB-27 | present unless historyStartDate documented | W | 422.119(h) [verified: eCFR mirror] |
| TIME-08 | Medicaid PDL / covered outpatient drug updates | FORM-09 | within 1 business day of effective date | E (Medicaid/CHIP) | 431.60(b)(4) [verified: eCFR mirror] |
| TIME-09 | Provider directory 30 days | PDIR-06 | within 30 calendar days | E (Provider Directory) | [verified: eCFR mirror] |
| TIME-10 | meta.lastUpdated present and sane | all resources | present; <= now; >= created/adjudication dates | E (C4BB) / W | C4BB meta.lastUpdated 1..1 [verified] |
| TIME-11 | Incremental sync correctness | two runs separated by oracle changes with _lastUpdated=ge{watermark} | all changed resources returned; unchanged omitted | W | Onyx FAQ; C4BB/PDex _lastUpdated SHALL [verified] |
| TIME-12 | Business-day calendar | configuration | workbench uses payer business-day calendar (weekends/holidays) when evaluating TIME-01..05 | I | analyst |

### 7.12 Documentation, policy and attestation (DOC)

| ID | Description | Request | Pass criteria | Sev | Basis |
|---|---|---|---|---|---|
| DOC-01 | Public API documentation | fetch docsUrl anonymously | reachable without fee/registration/email/promo preconditions; contains (1) syntax, function names, parameters and types, return structures, exceptions; (2) software components/configurations; (3) authorization-server registration requirements | E | 422.119(d) [verified: eCFR mirror] |
| DOC-02 | Registration requirements documented | docsUrl / portal | app registration steps, redirect URI rules, client auth methods, scopes listed | E | 422.119(d)(3) [verified: eCFR mirror] |
| DOC-03 | Enrollee educational resources | payer website | plain-language privacy/security guidance, app-selection factors, HIPAA covered-entity overview, OCR/FTC complaint routes | E | 422.119(g) [verified: eCFR mirror] |
| DOC-04 | App denial policy | payer policy document | denial/discontinuation criteria are objective, verifiable, security-risk-analysis based, applied consistently; no privacy-policy-only denials | M | 422.119(e) [verified: eCFR mirror] |
| DOC-05 | Metrics readiness | payer metrics process | ability to count unique enrollees transferred >= 1 and > 1 times per calendar year via the Patient Access API, aggregated/de-identified, reportable by March 31 | M | 422.119(f) [verified: eCFR mirror] |
| DOC-06 | App attestation flow (optional) | authorization UI | if the payer asks apps to attest to privacy-policy provisions, the enrollee is informed before authorization; attestation is not used as a denial ground | I | 9115-F preamble [likely] |
| DOC-07 | Standards versions declared | docs + CapabilityStatement | declared FHIR/US Core/SMART/IG versions match served artifacts and are either the 170.215 baseline or an approved updated version | W | 422.119(c)(1)/(c)(4) [verified: eCFR mirror] |
| DOC-08 | Audit record | workbench | every run stored immutably (endpoint, IG/version, check id, request, response headers/body hash, timestamps, verdict) | E | audit evidence expectation [likely] |

---

## 8. Open questions and unverified items

1. **Primary-source re-read**: all CFR text was verified against GitHub mirrors of the eCFR (snapshot 2025-02-06) and public-inspection PDFs, not ecfr.gov/federalregister.gov. Re-read 42 CFR 422.119(b)(1)(iv)-(v), (c)(1)-(4), (f), (h); 431.60; 438.242(b)(5)-(9); 45 CFR 156.221; 45 CFR 170.213/170.215 on ecfr.gov before quoting paragraph letters in compliance reports. Federal Register start pages (85 FR 25510, 89 FR 8758, 89 FR 1192, 91 FR 19890) and the HTI-1 March 11, 2024 effective-date correction remain [likely]/[unverified].
2. **438.242 redesignation artifacts**: mirrored 438.242(b)(9)(ii) points Medicaid managed care to 431.60(b)(5)/(g) "by the rating period beginning on or after January 1, 2026" and (b)(6)(iii) cites 431.60(h) instead of (f). Confirm CMS's intended date for managed-care PA data (2027 per 431.60(b)(5)).
3. **45 CFR 170.215 after Aug 4, 2025 (90 FR 37208, HTI-4) and any later ONC rule**: whether US Core 7.0.0/8.0.x, SMART 2.2.0 or Bulk Data 2.0.0 were added, and whether the Jan 1, 2026 expirations of US Core 3.1.1 / SMART 1.0.0 remain — text not retrievable. What CMS expects for the Patient Access API once (b)(1)(i)/(c)(1) have expired while 422.119(c)(1) still cites them is unresolved; no CMS FAQ found.
4. **CMS-0062-P status**: whether a final rule (CMS-0062-F) has issued after the June 15, 2026 comment close, its final IG version requirements, drug-PA scope, endpoint/metrics reporting, and dates (Oct 1, 2027; Jan 1, 2028 expirations were proposed).
5. **CMS FAQ positions** (strongly-encouraged IG versions, "use updated CARIN BB", HPMS "Interoperability Reporting" submission channel for 422.119(f) metrics, QHP/State channels, pre-2027 PA backfill expectations at go-live) — all [likely]/[unverified]; cms.gov unreachable.
6. **PA representation**: no CMS text names ExplanationOfBenefit vs Claim/ClaimResponse for the Patient Access API; PDex publishes no status/outcome crosswalk; whether cancelled/expired PAs should use EOB.status=cancelled vs reviewAction C; complete X12 306 list with sanctioned displays (A2, A6, C, CT, NA unverified); whether PDex 2.2.0's supportingInfo guidance will become a formal slice; whether a `use` SearchParameter will be added to a CapabilityStatement.
7. **PDex publication dates**: STU 2.0.0 (Oct 20, 2023?) and STU 2.2.0 HL7 announcement (2026-08-21?) unconfirmed; "2.1.1 on Simplifier" unconfirmed.
8. **US Core artifacts**: obtain published 3.1.1 and 6.1.0 (and 7.0.0) server CapabilityStatements/packages; the local file is a 10.0.0-ballot CI build. Registry shows US Core STU9 9.0.0 as current edition.
9. **SMART history**: 1.0.0/2.0.0/2.1.0 facts come from repository commits bracketing each release (not the published HTML); 2.1.0 publication month unconfirmed; SVAP approval dates for SMART 2.0.0/2.1.0/2.2.0 unconfirmed.
10. **Onyx SAFHIR specifics** (all from search snippets / third-party configs): exact token endpoint path per tenant; client_secret_basic vs post; PKCE support; whether `aud` is mandatory; introspection path; existence of `.well-known/smart-configuration` per IG base; CapabilityStatement SMART extension; max/default `_count`, cursor TTL, rate limits and 429 behaviour; `_include`/`_revinclude`/`$everything`/`_profile` support; which base serves PA EOBs for CMS-0057-F-enabled tenants; Provider Access `$export`/Group naming/backend-services details; `$member-match` and mTLS/UDAP for P2P; member ID identifier systems; UAT tier naming and alternate next-link hosts.
11. **Vendor IdPs**: Auth0 and generic Okta behaviours are model knowledge; verify per tenant via discovery documents.
12. **Inferno/Touchstone**: Inferno CARIN kit covers only 1.1.0/2.0.0 and PDex kit only 2.0.0 P2P (no Patient Access PA tests); Touchstone suite names/assertions unverified; decide whether SHOULD-level interactions (vread, XML, Coverage _lastUpdated) fail or warn, and whether to add the negative tests (401/403/404/400/410) that Inferno omits.
13. **Medicaid identifier systems**: no national URI; confirm each tenant's Medicaid ID and member ID systems from CapabilityStatement/docs.
14. **MBI excluded-letter rule** (B, I, L, O, S, Z) is from CMS format guidance not readable this session.
15. **9115-F ancillary dates**: July 1, 2021 enforcement discretion; Dec 2021 payer-to-payer enforcement discretion (FR Doc. 2021-26764); ONC Nov 2020 IFC updating 170.215(a)(2) from US Core 3.1.0 to 3.1.1 — all [likely].
16. **HIPAA X12 278 enforcement-discretion letter (2024-02-28)**, MIPS ePA measure dates, HTI-4/HTI-5 details, CMS PA metrics template — [likely], primary pages unreachable.
