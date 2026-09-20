# 10 – Sources and Access Limitations

## 10.1 How this package was researched

* The environment's outbound network policy allows only GitHub. Direct fetch of
  `point32health.org`, `harvardpilgrim.org`, `hl7.org`, `build.fhir.org`, `x12.org`, `caqh.org`,
  `cms.gov`, `mass.gov`, `uhcprovider.com`, the Wayback Machine and all other hosts returned
  HTTP CONNECT 403 (policy denial), for both `curl` and the WebFetch tool.
* Primary sources read in full from GitHub clones:
  * `HL7/davinci-pas` (PAS IG source, sushi-config version 2.2.1): `specification.md`,
    `usecases.md`, `regulations.md`, `additionalinfo.md`, `conformance.md`, `background.md`,
    `metrics.md`, `changelog.md`, all FSH profiles/extensions/value sets, all examples.
  * `EdiFabric/X12.NET` sample `Files/HIPAA/ServicesReview.txt` and `EdiFabric.Examples.X12.T278`
    (reproduces X12 public Example 1a "Referral Request for Review").
  * `HL7-DaVinci/prior-auth` (PAS reference implementation; no X12 code).
  * `azoner/pyx12` (only 4010 X094 278 maps; not used).
* Secondary sources: search-engine index summaries/snippets (approximately 100 queries across
  four research threads; the session's search budget of 200 queries was exhausted). Every fact from
  these is marked in the documents with its confidence.

## 10.2 Harvard Pilgrim / Point32Health

| Document | URL |
|---|---|
| HPHC 278 Request for Review and Response Companion Guide, 005010X217, v1.2 | https://www.point32health.org/documents/278-req-x217-5010-companion-guidev12-hphcedi |
| Tufts Health Plan 278 companion guide (sibling) | https://www.point32health.org/documents/278-request-review-and-response-sta |
| Point32Health EDI page | https://www.point32health.org/provider/electronic-tools/electronic-data-interchange |
| EDI Enrollment Form (rev. 12/2024) | https://www.point32health.org/documents/f-edi-enrollment-form |
| EDI Set-Up Form (rev. 08/2024) | https://www.point32health.org/documents/edi-set-form |
| HPHC 270/271, 837P, 837I, 835, 277CA companion guides | https://www.point32health.org/documents/270-271-companion-guide-edi , https://www.point32health.org/documents/837-5010-professional-companion-guide-v411-hphcedi , https://www.point32health.org/documents/837-5010-institutional-companion-guide-v321-hphcedi , https://www.point32health.org/documents/835-5010-companion-guide-v103-hphcedi , https://www.point32health.org/documents/277ca-5010-companion-guide-hphcedi |
| Payer ID reference guide | https://www.point32health.org/documents/reference-guide-payer-id-numbers-combined-hphcedi |
| HPHConnect | https://www.point32health.org/provider/electronic-tools/secure-portals/hphconnect |
| Harvard Pilgrim electronic tools / companion guides page | https://www.harvardpilgrim.org/provider/resource-center/electronic-tools-and-hphconnect/ |
| HPHC Commercial Provider Manual – Referral, Notification & Authorization | https://www.point32health.org/provider/policies/provider-manuals/harvard-pilgrim-health-care-commercial-provider-manual/referral-notification-authorization |
| Referral policy; when a referral is not required | https://www.point32health.org/documents/d-1-referral-policy-pm , https://www.point32health.org/documents/d-1-when-referral-not-required-pm |
| Notification policy; elective admission; emergent/urgent | https://www.point32health.org/documents/d-1-notification-policy-pm , https://www.point32health.org/documents/d-1-elective-admission-notification-pm , https://www.point32health.org/documents/d-1-emergent-urgent-pm |
| Prior authorization policy | https://www.point32health.org/documents/d-1-prior-authorization-policy-pm |
| Behavioral health authorization and notification | https://www.point32health.org/documents/behavioral-health-auth-and-notification-pm |
| Utilization management; denials and adverse determinations | https://www.point32health.org/documents/utilization-management-pm , https://www.point32health.org/documents/denials-adverse-determinations-pm |
| Provider appeals overview | https://www.point32health.org/documents/g-provider-appeals-overview-pm |
| Referral/authorization quick reference (commercial) | https://www.point32health.org/documents/ref-auth-qrg-commercial |
| Phone/fax update for referrals, notifications and authorizations | https://www.point32health.org/provider/news/phone-and-fax-update-for-referrals-notifications-and-authorizations/ |
| Elective inpatient admissions initiative halted | https://www.point32health.org/provider/elective-inpatient-medical-admissions-initiative-halted-032026 |
| Home health UM update | https://www.point32health.org/provider/news/utilization-management-update-for-home-health-care-services/ |
| Evolent / EviCore delegation pages | https://www.point32health.org/provider/evolent-formerly-national-imaging-associates , https://www.point32health.org/provider/evicore |
| Provider portal upgrade (Availity Essentials) | https://www.point32health.org/provider/exciting-news-were-upgrading-our-secure-provider-portals-022026 |
| Point32Health unified digital experience press release | https://www.prnewswire.com/news-releases/point32health-launches-unified-digital-experience-for-members-and-providers-302789833.html |
| Harvard Pilgrim interoperability (Patient Access rule) | https://www.harvardpilgrim.org/public/interoperability-website-resources |
| Harvard Pilgrim eCare page (identity unverified) | https://www.harvardpilgrim.org/public/ecare |
| Tufts provider portal (MHK) | https://www.point32health.org/provider/electronic-tools/secure-portals/tufts-health-plan-provider-portal |

## 10.3 HL7 Da Vinci and FHIR

| Item | URL |
|---|---|
| PAS IG v2.2.1 | https://hl7.org/fhir/us/davinci-pas/ (source: https://github.com/HL7/davinci-pas) |
| PAS specification, use cases, additional info, conformance | https://build.fhir.org/ig/HL7/davinci-pas/specification.html , usecases.html , additionalinfo.html , conformance.html |
| PAS reference implementation | https://github.com/HL7-DaVinci/prior-auth |
| CRD, DTR, CDex IGs | http://hl7.org/fhir/us/davinci-crd , http://hl7.org/fhir/us/davinci-dtr , http://hl7.org/fhir/us/davinci-cdex |
| Subscriptions backport | http://hl7.org/fhir/uv/subscriptions-backport/ |
| Inferno Da Vinci PAS test kit | https://inferno.healthit.gov/test-kits/davinci-pas/ |
| Da Vinci Connectathon 26 X12/FHIR mapping deck | https://confluence.hl7.org/download/attachments/97459110/Da%20Vinci%20-%20Connectathon%2026%20-%20X12%20FHIR%20Mapping%20v1.pdf?api=v2 |
| PAS exception guidance (Confluence) | https://confluence.hl7.org/display/DVP/PAS+Exception+Guidance |

## 10.4 X12

| Item | URL |
|---|---|
| 005010X217 public examples (1a/1b referral, 2a admission, 3b BH emergency admission, 4 home health, 5a/5b transportation, 6a medical services reservation) | https://x12.org/examples/005010x217 |
| X12/HL7 interoperability crosswalks announcement | https://x12.org/news-and-events/news/x12-announces-first-interoperability-crosswalks |
| X12 external code lists | https://x12.org/codes |
| RFIs cited: 941 (HCR in X217), 1061 (UM01), 1371 (service loop count), 1540 (AAA errors), 1620 (2000E HCR), 1719 (HL order), 1972 (HCR03/PWK), 2153 (AAA unsupported UM01), 2264 (characters), 2359 (2000A AAA), 2385 (supplemental ID / REF NT), 2445 (AAA at higher levels), 2475 (278N reference numbers), 2477 (notification vs request), 2740 (expedited PA) | https://x12.org/resources/requests-for-interpretation/ |
| Stedi free 278 guides (X217 request A1 / response A3, X215 inquiry A6 / response A7, X216 B1/B2) | https://www.stedi.com/edi/hipaa/transaction-set/278-A1 etc. |
| EdiFabric X12.NET samples (Example 1a) | https://github.com/EdiFabric/X12.NET |

## 10.5 Other payer companion guides (baseline comparison)

UnitedHealthcare X217 and X215 guides (uhcprovider.com), CareCentrix, CMS esMD 278/275, Nebraska
DHHS, Michigan MDHHS, BCBS Massachusetts 278 referral guide, BCBS North Carolina, Humana, Texas
Medicaid LTC, Indiana Medicaid/Acentra, Fidelis, CareSource – URLs in the research threads; the
load-bearing findings used here are "SV1 and SV2 cannot be submitted on the same authorization"
and "REF*NT required on follow-ups when only an administrative reference number was returned".

## 10.6 Regulatory

| Item | URL |
|---|---|
| CMS-0057-F fact sheet and rule | https://www.cms.gov/newsroom/fact-sheets/cms-interoperability-prior-authorization-final-rule-cms-0057-f , https://www.cms.gov/files/document/cms-0057-f.pdf |
| CMS PA API FAQ; HIPAA enforcement discretion FAQ; discretion statement (28 Feb 2024) | https://www.cms.gov/initiatives/burden-reduction/overview/interoperability/frequently-asked-questions/prior-authorization-api , https://www.cms.gov/priorities/burden-reduction/overview/interoperability/frequently-asked-questions/hipaa-transaction-enforcement-discretion , https://www.cms.gov/files/document/discretion-x12-278-enforcement-guidance-letter-remediated-2024-02-28.pdf |
| 45 CFR 162.1302 | https://www.ecfr.gov/current/title-45/subtitle-A/subchapter-C/part-162/subpart-M/section-162.1302 |
| Attachments final rule (24 Mar 2026) | https://www.federalregister.gov/documents/2026/03/24/2026-05676/administrative-simplification-adoption-of-standards-for-health-care-claims-attachments-transactions |
| MA: M.G.L. c.176O §25; 211 CMR 52.00 amendments (effective 5 June 2026); Filing Guidance 2026-K | https://malegislature.gov/Laws/GeneralLaws/PartI/TitleXXII/Chapter176O/Section25 , https://www.mass.gov/news/governor-healey-announces-final-regs-that-eliminate-prior-authorization-requirements-for-routine-and-essential-health-care , https://www.mass.gov/doc/filing-guidance-notice-2026-k-filings-to-implement-amendments-to-211-cmr-52074/download |
| ME: 24-A M.R.S. §4304 | https://legislature.maine.gov/statutes/24-a/title24-Asec4304.html |
| NH: RSA 420-E:4-a, RSA 420-J:7-b | https://law.justia.com/codes/new-hampshire/title-xxxvii/chapter-420-e/section-420-e-4-a/ , https://gc.nh.gov/rsa/html/XXXVII/420-J/420-J-7-b.htm |
| CT: CGS §38a-472g | https://law.justia.com/codes/connecticut/title-38a/chapter-700c/section-38a-472g/ |
| MHDC / ZeOmega NEHEN FHIR | https://www.prnewswire.com/news-releases/massachusetts-health-data-consortium-and-zeomega-launch-statewide-initiative-to-automate-prior-authorization-302504528.html |
| Availity + Onyx CMS-0057 platform; Onyx acquires InteropX | https://www.businesswire.com/news/home/20250806279473/en/Availity-and-Onyx-Launch-Comprehensive-CMS-0057-Compliance-Platform-for-Health-Plans-Powered-by-the-Availity-Network , https://www.healthcareittoday.com/2026/02/26/onyx-the-leading-cms-interoperability-platform-acquires-interopx-to-accelerate-electronic-prior-auth-data-exchange/ |
| Onyx / SAFHIR | https://onyxhealth.io/products/onyxcompliance/ , https://docs.safhir.io/ |

## 10.7 Vendors offering PAS↔278 translation (build-vs-buy reference)

Redix (278↔PAS bridge, PAS 2.1.0), SignalEDI PA Bridge, PilotFish (FHIR→278 demo, X12 Fall 2025),
Health Samurai Payerbox (documented UM hand-off: `outcome = queued` → UM writes back `reviewAction`),
EdiFabric (.NET 278 templates), Stedi, Smile CDR PAS module, InterSystems, Redox, MuleSoft
accelerator, Availity (Provider Authorization API), ZeOmega. Edifecs, Cohere, MCG, Infinx and Smart
Data Solutions were not reached before the search budget ran out.
