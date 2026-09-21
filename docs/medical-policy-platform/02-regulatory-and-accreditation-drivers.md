# 2. Regulatory and accreditation drivers

This section inventories every legal, regulatory and accreditation requirement that shapes what a medical policy must contain, how it must be reviewed and approved, how it must be published or disclosed, and what machine-readable or API obligation attaches. Each item states what it means for the platform. The traceability table at the end (section 2.10) is the bridge to the requirements catalogue in section 4.

**Confidence tags.** Primary-source sites (eCFR, Federal Register, CMS, mass.gov, malegislature.gov, NCQA, state legislatures) could not be fetched from the research environment, so citations were confirmed from excerpts returned by search engines or from secondary sources. **[V]** = wording or date confirmed from a primary-source excerpt; **[S]** = confirmed only through a secondary source (law firm alert, trade press, CMS fact sheet); **[K]** = analyst knowledge through mid-2026, not re-verified; **[U]** = section number or provision could not be verified. Before any item tagged [K] or [U] is used as a compliance citation, Compliance should pull the primary text. Nothing here invents a statute section; where a section could not be pinned, it says so.

## 2.1 Medicare Advantage (Tufts Medicare Preferred, SCO and One Care as dual-eligible products)

**Internal coverage criteria, 42 CFR 422.101(b)(6)** (CMS-4201-F, effective 1 January 2024) [V]. An MA organisation must follow Medicare NCDs, LCDs and general coverage rules. Only where criteria are "not fully established" may it create **publicly accessible internal coverage criteria based on current evidence in widely used treatment guidelines or clinical literature**; it must make the criteria publicly available, summarise the evidence and sources, and explain the rationale; clinical benefit must outweigh harm. CMS's HPMS FAQ of 6 February 2024 says "publicly accessible" means on a website (the plan's or a delegated vendor's) and **not behind a paywall or subscription** [V via republished memo]. Consequence: licensed criteria (InterQual, MCG) may be applied to MA only if the specific criteria actually applied are posted publicly with the evidence summary; holding the licence is not enough [S]. The same FAQ says an algorithm or AI tool that decides on population data rather than the individual's history, physician recommendation and notes is not compliant with 422.101(c) [V].

*Platform meaning:* structured "Medicare source basis" per MA policy (statute/regulation, NCD id, LCD/Article id with MAC jurisdiction, or a "not fully established" justification code); mandatory evidence summary, sources, rationale and benefit/harm statement rendered on the public site; a validation rule that blocks an MA version that is more restrictive than a governing NCD/LCD; the Medicare Coverage Database snapshot reviewed recorded at each review; licensed-criteria references carry a licence class that either permits public reproduction or forces plan-authored text.

**Inpatient criteria** [K/S]. MA plans must apply the Traditional Medicare inpatient admission rules at 42 CFR 412.3 (two-midnight benchmark, case-by-case exception, inpatient-only list) and may not use internal level-of-care or site-of-service criteria to override them.

**UM committee, 42 CFR 422.137** (effective 1 January 2024; amended for CY2025 and CY2027) [V]. Majority practising physicians, at least one independent physician free of conflict, at least one with expertise in care of elderly or disabled people (CY2025 added health-equity expertise [K]). The committee must review all UM policies and procedures including PA **at least annually** against Traditional Medicare coverage decisions, NCDs, LCDs and law, and must approve internal coverage criteria before use and document decisions [V/K]. The CY2027 final rule (Federal Register, 6 April 2026) rescinds the annual health-equity analysis and its public posting [S]; the exact effective date should be confirmed [U].

*Platform meaning:* committee objects (meeting, agenda, roster with specialty and conflict attestations, votes, minutes), an annual-review clock per policy that blocks use of overdue criteria, and an "NCD/LCD/law check" attestation with the MCD snapshot. CMS announced UM compliance audits in October 2023 [S]; the evidence must be exportable.

**PA validity and continuity, 42 CFR 422.138 and 422.112(b)(8)** [V]. PA may only confirm diagnoses or medical-necessity criteria; an approved PA for a course of treatment stays valid as long as medically necessary; new enrollees get a minimum 90-day transition with no reauthorisation for active treatment; an approved PA may not later be denied on medical-necessity grounds except for good cause or fraud.

*Platform meaning:* policy metadata states authorisation duration rules; a new-enrollee transition flag suppresses PA edits; approvals are immutable.

**Organisation determinations and notices, 42 CFR 422.566, 422.568, 422.572** [V/K]. Adverse medical-necessity determinations are reviewed by a physician or appropriate professional with expertise in the field "including knowledge of Medicare coverage criteria"; denial notices state the specific reasons and cite the rule, NCD/LCD or plan policy relied on (Integrated Denial Notice instructions); standard PA decisions 7 calendar days and expedited 72 hours from 1 January 2026 (CMS-0057-F); Part B drug requests 72 hours standard and 24 hours expedited [K]; the CY2026 rule made concurrent inpatient decisions organisation determinations with full notice and appeal rights from 1 January 2026 [S]. CMS proposed AI guardrails for CY2026 and did not finalise them; the February 2024 FAQ remains the operative guidance [S/V].

*Platform meaning:* each version carries denial-language and a citation string (policy id, version, section, public URL); reviewer-qualification requirements per policy; decision SLA attributes by LOB and request type; AI or decision support binds to a policy version and never produces a denial alone.

**Part B drug step therapy, 42 CFR 422.136** [K]. New starts only with a 365-day lookback, P&T committee approval, evidence basis, exception decisions within 72 hours standard and 24 hours expedited, disclosure to enrollees. *Platform meaning:* a P&T approval path for medical-benefit drug policies, "new start only" and lookback attributes.

**Related activity** [V/S]. The CMS WISeR model (Traditional Medicare PA in six states from January 2026) does not include Point32Health's states; the AHIP pledge of 23 June 2025 commits signatories to 90-day honouring of existing approvals on plan switch, clear denial explanations, standardised FHIR ePA by 1 January 2027 and 80% real-time answers for complete electronic requests. Point32Health signed the original pledge and did not sign the April 2026 technology update, issuing a statement of continued commitment (KFF Health News, July 2026).

## 2.2 CMS-0057-F Interoperability and Prior Authorization (89 FR 8758, 8 February 2024)

Applies to MA (42 CFR 422.122), Medicaid managed care (438.210(f)), CHIP, Medicaid FFS and QHP issuers on the federally facilitated exchanges (45 CFR 156.223). **Tufts Health Direct sits on the Massachusetts state-based exchange, so 156.223 does not apply to it directly; Massachusetts law and the Connector contract govern** [V for the scope of 156.223; Connector terms U].

- **Prior Authorization API, compliance 1 January 2027** [V]: a FHIR API populated with the payer's list of covered items and services (excluding drugs) that require PA, able to identify all documentation the payer requires, supporting request and response, communicating approval (with the end date or circumstance), denial with a specific reason, or a request for more information, and keeping PA information available for at least a year after the last status change. CMS recommends but does not require the Da Vinci CRD, DTR and PAS guides [V]. HHS enforcement discretion (letter remediated 28 February 2024) covers all-FHIR PA flows in place of the X12 278 [V].
- **Decision timeframes from 1 January 2026** [V]: 7 calendar days standard (Medicaid may extend up to 14 more days), 72 hours expedited; drugs excluded.
- **Specific denial reason from 1 January 2026, in every channel** [V].
- **Public PA metrics, first by 31 March 2026 for CY2025, then annually** [V]: the list of all items and services requiring PA plus approval, denial, appeal-overturn, extension and timing statistics, on the public website, using the CMS template.
- **CMS-0062-P (proposed 14 April 2026, comments closed 15 June 2026, not final as of September 2026)** [V]: would extend ePA to drugs for MA, Medicaid managed care, CHIP and FFE QHPs with 72-hour standard and 24-hour expedited windows, expanded metrics, and named standards (Da Vinci guides for medical-benefit drugs, NCPDP for pharmacy benefit).
- **Standards versions** [V]: the FY2027 IPPS final rule with ONC (published 4 August 2026, effective 1 October 2026) adopts Da Vinci CRD 2.2.1, DTR 2.2.0 and PAS 2.2.1 for certified health IT, replacing the HTI-4 versions.

*Platform meaning:* one canonical, versioned code-to-PA-requirement table is the single source for the API's PA list, the public list and the metrics; documentation requirements per policy and code are structured and exportable as DTR questionnaires and CQL; a denial-reason taxonomy maps to criteria ids; version pins for the guides are configuration; the drug-policy model uses the same structures keyed by NDC/RxNorm so drug ePA can be exposed if CMS-0062 is finalised; metrics reporting needs a stable join between PA transactions and policy ids.

## 2.3 Medicaid managed care (Tufts Health Together, RITogether, SCO and One Care on the MassHealth side)

- **42 CFR 438.210** [K text; V for the CMS-0057-F additions]: medical-necessity criteria no more restrictive than the state fee-for-service programme; written policies for authorisation applied consistently; consultation with the requesting provider when appropriate; denials by someone with appropriate expertise; notice per 438.404, which includes the right to receive free of charge the criteria, processes, strategies and evidentiary standards used; 7-day and 72-hour timeframes; the PA API from January 2027; specific denial reasons from January 2026.
- **42 CFR 438.236 practice guidelines** [K]: evidence-based or consensus-based, adopted with provider consultation, reviewed and updated periodically, disseminated to affected providers and on request to enrollees; UM decisions consistent with the guidelines.
- **42 CFR 438.10** [K]: member handbook describes authorisation requirements; website content accessible with taglines and language access.
- **42 CFR 438.915 parity** [K]: MH/SUD medical-necessity criteria available on request to enrollees, potential enrollees and providers; reason for any MH/SUD denial available.
- **MassHealth** [K/U]: 130 CMR 450.204 defines medical necessity; MassHealth publishes Guidelines for Medical Necessity Determination; the MCO and Accountable Care Partnership Plan contracts require contractor guidelines to be no more restrictive than MassHealth's, developed with clinical input, reviewed periodically, posted and available on request; Managed Care Entity bulletins direct coverage and PA practice; MassHealth All Provider Bulletin 413 covers interoperability and PA process changes for the medical benefit [V that it exists]; Tufts Health Together follows the MassHealth Unified Formulary [V]. Contract section numbers must be pulled from the current contracts [U].
- **Rhode Island** [K/U]: EOHHS contract incorporates Part 438 and the Benefit Determination and Utilization Review Act (R.I.G.L. 27-18.9) and OHIC UR regulations.

*Platform meaning:* a "no more restrictive than the state" attestation with the linked state guideline id and version; a distribution log for guidelines to providers; an on-request fulfilment workflow; a tracker for MassHealth bulletins and contract amendments as external requirement sources; accessibility and language support on public pages.

## 2.4 Massachusetts commercial law (Harvard Pilgrim fully insured; Tufts Health Direct; adopted contractually by many self-funded clients)

- **M.G.L. c.176O and 211 CMR 52.00** [K, subsection numbers partly U]: written clinical review criteria developed with input from practising physicians in the service area, evidence-based, updated at least periodically (annual review satisfies any reading), applied consistently, available to providers on request (c.176O §16 and 211 CMR 52.08); adverse determinations within 2 working days of complete information, decided by a same-or-similar specialty clinician, with written notice that gives a substantive clinical justification, the specific information relied on, a discussion of the member's condition and why it fails the criteria, alternatives offered, and the applicable criteria and guidelines referenced and included (c.176O §12); carrier disclosures on request of the criteria used for a specific determination (c.176O §7); c.176O §25 standardised PA forms for designated services with a two-business-day deemed-approval rule [V].
- **211 CMR 52.07(5) amendments effective 5 June 2026** [V from mass.gov and NFP summaries]: PA prohibited for emergency and urgent care, primary care, preventive care, imaging after a cancer diagnosis, maternity care, outpatient SUD treatment, PT/OT, serious mental illness services and certain chronic-condition medications; 24-hour response for urgent requests; DOI Filing Guidance Notice 2026-L governs the filings.
- **Chapter 197 of the Acts of 2024** [V]: PA for transfer from hospital to long-term-care services (DOI Filing Guidance 2025-D).
- **Behavioral health without PA** [K]: 14 days of acute treatment and clinical stabilisation services for SUD (chapter 258 of 2014); no PA for emergency psychiatric and crisis services and certain acute MH admissions, annual parity reporting to DOI (chapter 177 of 2022, Mental Health ABC Act 2.0).
- **Step therapy** [K]: chapter 260 of 2020 requires exception processes with 72-hour and 24-hour windows and public posting of the process; the c.176O section it created is [U].
- **Chapter 342 of the Acts of 2024** (signed 8 January 2025) [V]: PBM licensure and $0 or limited cost-sharing for selected chronic-condition drugs from plan year 2026, with public identification of the selected drugs (DOI Filing Guidance 2026-M). No medical-PA reform (60-day notice, gold carding) was verified in chapter 342 or chapter 343 of 2024 [U].
- **Advance notice of changes** [U]: no statute requiring a fixed 60-day public notice of medical-policy changes was verified; provider-contract notice of material changes (2010 amendments to c.176O) is commonly operationalised as 60 days, and Point32Health's practice observed in 2025 to 2026 is 60 to 90 days or more. Treat 60 days as the default configured notice period and confirm the statutory hook.

*Platform meaning:* physician-input and evidence records; annual review cadence; denial letters embedding the criteria applied; on-request fulfilment log; statutory PA exemptions modelled as hard exclusions with citations (including the June 2026 list); step-therapy exception criteria; provider notice log with a per-state configurable period; a validation rule that refuses to publish a PA requirement on a service the regulation prohibits.

## 2.5 New Hampshire, Maine, Connecticut, Rhode Island (commercial) [K/U]

| State | Core UR law | What it means for the platform |
|---|---|---|
| New Hampshire | RSA 420-J:5 utilisation review standards: written clinical criteria based on evidence, reviewed periodically, available on request; reviewer licensure; 2024 to 2025 PA bills (ePA acceptance, timeframes, gold-card proposals) with enactment status [U] | Criteria on request; ePA intake; NH timeframe overlay; NH denial-notice content |
| Maine | 24-A M.R.S. §4304 (decisions within 72 hours or 2 business days; ePA acceptance required), Bureau of Insurance Rule Chapter 850; LD 796 and later PA measures [U]; step-therapy exceptions (24-A §4320-L) | Public list of services requiring PA; criteria on request; ePA; step-therapy exceptions |
| Connecticut | C.G.S. §38a-591a to 591g (written clinical criteria, reviewer qualifications, adverse-determination content; 24-hour urgent, 15-day standard); 2023 to 2025 public acts on PA [U] | Criteria posting or availability; timeframes; ePA |
| Rhode Island | R.I.G.L. §27-18.9 (written criteria developed with provider input, updated periodically, available on request; reviewer qualifications; adverse-determination content); OHIC regulations and administrative-simplification directives requiring ePA capability; 2024 to 2025 acts on PA limits and approval durations [U] | Criteria on request; ePA; approval-duration rules; public PA list |

The schema must hold **jurisdiction overlays** (timeframes, notice periods, exempt services, posting obligations) rather than one hard-coded set. Compliance should convert this table to [V] from the primary statutes before the state rules are configured.

## 2.6 NCQA Health Plan Accreditation UM standards (2025/2026 edition) [K] and URAC HUM

- **UM 1** programme structure with physician and behavioral-health involvement and annual evaluation.
- **UM 2** clinical criteria: written criteria based on current clinical evidence, developed with appropriate practitioners, **reviewed at least annually**, applied with consideration of individual needs and the local delivery system (Element A); **available to practitioners and members on request** and stated in denial notices (Element B); **inter-rater reliability** evaluated annually for physician and non-physician reviewers with action on findings (Element C).
- **UM 4** appropriate professionals: medical-necessity denials by an appropriately licensed physician (pharmacist or dentist where applicable), BH denials by a BH practitioner, no financial incentives for denials.
- **UM 5** timeliness: 15 calendar days non-urgent pre-service, 72 hours urgent pre-service, 24 hours urgent concurrent, 30 days post-service.
- **UM 7** denial notices: specific reason in plain language, reference to the criteria or benefit provision, offer of a copy of the criteria, reviewer availability, appeal rights.
- **UM 12** pharmaceutical management: criteria reviewed annually, communicated to members and practitioners, exceptions process.
- **AI in UM**: NCQA updates (2025, effective for 2026 surveys per recollection [U]) require policies on oversight, validation and transparency of AI or automated tools, with human clinical review retained for adverse medical-necessity determinations. URAC HUM v8 is comparable and adds its own AI guidance.

*Platform meaning:* annual-review enforcement; practitioner-involvement and evidence records; IRR case-set generation from criteria with results stored against the version; criteria-on-request log; denial templates that auto-cite policy id and section; reviewer role and licence tagging on each policy.

## 2.7 ERISA and ACA claims and appeals (self-insured and fully insured commercial; QHP) [K]

- **29 CFR 2560.503-1**: an adverse benefit determination that relied on an internal rule, guideline, protocol or criterion must state it and offer a free copy on request, or include it; medical-necessity or experimental denials must explain the clinical judgement or offer it; claimants are entitled free of charge to all documents relevant to the claim, which includes any guideline or criterion concerning the denied treatment "without regard to whether such advice or statement was relied upon"; pre-service 15 days, urgent 72 hours, post-service 30 days; appeals by a different qualified professional without deference.
- **45 CFR 147.136 and parallel DOL/IRS rules**: incorporate the ERISA procedure; add denial codes with meanings, a description of the plan's standard used, diagnosis and treatment codes on request, external review rights, culturally and linguistically appropriate notices.
- **CAA 2021**: 90-day continuity of care on network or plan changes (45 CFR 149.430); Transparency in Coverage machine-readable files carry rates, not coverage criteria (no medical-policy publication requirement), though the PA-requirement table can annotate price-tool output with "requires PA".

*Platform meaning:* exact version retrieval by date of service; a free fulfilment channel that can also supply policies that concern the treatment but were not relied on; denial codes with plain-language meanings; retention for the ERISA record period and longer for litigation.

## 2.8 Mental health parity (MHPAEA) [K]

- 29 CFR 2590.712 and 45 CFR 146.136: MH/SUD medical-necessity criteria available on request to current or potential members and contracting providers; reasons for MH/SUD denials on request; NQTLs (PA, concurrent review, step therapy, medical-necessity criteria) comparable to and no more stringent than medical/surgical, as written and in operation.
- CAA 2021 §203: documented NQTL comparative analyses (factors, evidentiary standards, sources, comparability findings) producible to regulators on request.
- The 2024 final rule (89 FR 77586) added meaningful-benefit, data and content requirements; on 15 May 2025 the Departments announced non-enforcement of the 2024 rule's new provisions pending reconsideration; the 2013 rule and the statutory comparative-analysis duty remain in force. Status of any 2026 replacement rulemaking [U]. Massachusetts requires annual parity reports to DOI (chapter 177 of 2022), and the DOI has conducted a mental-health parity market-conduct examination of Point32Health [V that the report exists].

*Platform meaning:* NQTL classification and benefit classification on every policy and PA rule, an MH/SUD flag, and stored factors, sources, evidentiary standards and dated rationale for both "which services get PA" and "what the criteria are", so comparative analyses can be generated rather than reconstructed.

## 2.9 Cross-cutting content requirements

1. **Evidence and dating**: a standard evidence section (guideline citations with year, systematic reviews, specialty-society positions, FDA labelling), rationale, benefit/harm statement, and last-reviewed, next-review, effective and retired dates displayed publicly. Required by MA, expected by NCQA, Massachusetts and Medicaid.
2. **Medicare precedence hierarchy**: statute and regulation, then NCD, then LCD/Article for the MAC jurisdiction (NGS for the New England states), then CMS manuals, then internal criteria only where not fully established; the platform shows which layer governs each MA code.
3. **Continuity and transition**: MA 90-day new-enrollee transition, CAA continuity of care, the pledge's 90-day honouring of prior approvals, MassHealth continuity rules [U]; the PA engine must accept externally issued authorisations and suppress edits in transition windows.
4. **Site-of-service and level-of-care policies**: permitted commercially subject to state UR law and parity; constrained for MA by 412.3 and site-neutral rules.
5. **Licensed criteria**: no law requires a particular vendor; MA requires public accessibility of whatever is applied; NCQA and state law require availability on request; licence terms must permit the excerpting the platform will do.
6. **Gold carding**: no federal requirement; none verified as enacted in Point32Health's states [U]; support provider-level exemptions anyway.
7. **Accessibility and language**: 438.10, ACA §1557 (45 CFR 92), Medicare communications rules; public pages meet WCAG and offer taglines and translation.
8. **Standards versions**: Da Vinci CRD 2.2.1, DTR 2.2.0, PAS 2.2.1 (ONC, effective 1 October 2026); X12 278/275 alongside FHIR with the enforcement-discretion letter documented; NCPDP SCRIPT for Part D ePA and proposed for drug PA under CMS-0062-P.

## 2.10 Requirements traceability

| ID | Derived platform requirement | Source | Conf. |
|---|---|---|---|
| R01 | LOB applicability flags per policy with per-LOB variants (MA, SCO/One Care, MassHealth, RITogether, Connector QHP, Commercial FI, Commercial ASO). | 422.101; 438.210; c.176O; ERISA | V/K |
| R02 | MA policies record the Medicare source basis: statute/regulation, NCD id, LCD/Article id and MAC, or "not fully established" justification. | 42 CFR 422.101(b)(6) | V |
| R03 | MA internal criteria published on a public, non-paywalled page with criteria text, evidence summary, sources and rationale. | 422.101(b)(6); HPMS FAQ 6 Feb 2024 | V |
| R04 | Ingest the Medicare Coverage Database (NCD/LCD/Article) and record the snapshot reviewed at each policy review. | 422.101(b)(2)-(3); 422.137(c) | V/K |
| R05 | Validation blocks an MA version more restrictive than a governing NCD/LCD unless a justification is attached. | 422.101(b)(6) | V |
| R06 | MA inpatient and level-of-care policies reference 412.3 and the inpatient-only list; no internal override. | 422.101(b)(2); CMS FAQ | K/S |
| R07 | UM committee workflow: agenda, votes, minutes, roster with specialty, independence and conflict attestations. | 42 CFR 422.137(b) | V |
| R08 | Annual review of every UM/PA policy with an NCD/LCD/law reconciliation attestation; overdue policies flagged and blocked from use. | 422.137(c); NCQA UM 2A; c.176O §16 | V/K |
| R09 | Health-equity analysis module retired on the CY2027 rule's effective date; 2025 evidence retained. | CY2027 final rule (FR 6 Apr 2026) | S |
| R10 | Policy metadata defines authorisation validity for courses of treatment. | 42 CFR 422.138 | V |
| R11 | New-enrollee 90-day transition flag; configurable commercial continuity-of-care and pledge rules. | 422.112(b)(8); 45 CFR 149.430; AHIP pledge | V/K |
| R12 | Approvals immutable; reopening only for good cause or fraud with audit trail. | 422.138(c); 422.616 | V/K |
| R13 | Every version carries denial-notice language and a citation string (id, version, section, URL). | 422.568(e); 2560.503-1(g); 147.136; NCQA UM 7; c.176O §12 | V/K |
| R14 | Reviewer-qualification requirements per policy (specialty, Medicare-criteria knowledge, BH practitioner, same/similar specialty in MA). | 422.566(d); NCQA UM 4; 211 CMR 52.08; 438.210(b) | V/K |
| R15 | Decision SLA attributes: MA/Medicaid/QHP 7 days and 72 hours; Part B drug 72 and 24 hours; ERISA 15 days and 72 hours; MA commercial 2 working days and 24-hour urgent (June 2026); NCQA 15 days, 72 hours, 24 hours. | CMS-0057-F; 422.136; 2560.503-1; c.176O §12; 211 CMR 52.07(5); NCQA UM 5 | V/K |
| R16 | Concurrent and inpatient decisions treated as organisation determinations from 1 Jan 2026 (MA). | CY2026 rule | S |
| R17 | AI or decision support binds to a policy version, requires individual inputs, and logs human clinical review for adverse decisions. | 422.101(c); HPMS FAQ; NCQA AI updates | V/K |
| R18 | One canonical, versioned code-to-PA table (codes, POS, provider type, LOB, effective dates) feeds the API, the public list and claims edits. | 42 CFR 422.122; 438.210(f); 156.223 | V |
| R19 | Structured documentation requirements per policy and code, exportable as DTR Questionnaire and CQL. | CMS-0057-F PA API | V |
| R20 | PA API live by 1 Jan 2027 for MA and Medicaid managed care with approval end date, specific denial reason or request for information. | 422.122; 438.210(f) | V |
| R21 | Denial-reason taxonomy mapped to criteria ids; specific reason in all channels from 1 Jan 2026. | CMS-0057-F | V |
| R22 | PA data queryable at least one year after last status change; MA records ten years. | CMS-0057-F; 422.504(d) | V/K |
| R23 | Annual public PA metrics report by 31 March generated from PA transactions joined to policy and code lists. | CMS-0057-F; CMS template | V |
| R24 | Public "services requiring PA" list generated per LOB with effective-date snapshots. | CMS-0057-F; ME/RI/NH law | V/K |
| R25 | Configurable guide versions: CRD 2.2.1, DTR 2.2.0, PAS 2.2.1. | ONC FY2027 IPPS rule | V |
| R26 | X12 278/275 supported alongside FHIR; enforcement discretion documented. | HHS letter 28 Feb 2024 | V |
| R27 | Drug-policy model (medical and pharmacy benefit) keyed by NDC/RxNorm/GPI with the same documentation structures, ready for CMS-0062. | CMS-0062-P | V/S |
| R28 | Tufts Health Direct flagged as state-based-exchange QHP; MA law and Connector contract apply. | 45 CFR 156.223 scope | V |
| R29 | Medicaid policies carry a "no more restrictive than MassHealth/RI FFS" attestation with linked state guideline id and version. | 438.210(a)(5); 130 CMR 450.204; contracts | K/U |
| R30 | Written authorisation policies applied consistently; provider consultation step; appropriate-expertise decision maker recorded. | 438.210(b) | K |
| R31 | Guidelines disseminated to affected providers with a distribution log; provided to enrollees on request. | 438.236 | K |
| R32 | Medicaid denial notices include the right to obtain criteria and evidentiary standards free of charge; fulfilment tracked. | 438.404(b)(2) | K |
| R33 | MH/SUD criteria available on request to members, prospective members and providers, all LOBs. | 2590.712(d); 146.136(d); 438.915 | K |
| R34 | NQTL metadata per policy and rule: NQTL type, benefit classification, MH/SUD flag, factors, evidentiary standards, sources, rationale. | CAA 2021 §203; MHPAEA rules | K |
| R35 | Track MassHealth bulletins and contract amendments as external requirement sources linked to policies. | MassHealth MCO/ACPP contract | U |
| R36 | Accessibility (WCAG), taglines and translation on public pages. | 438.10; 45 CFR 92; Medicare communications rules | K |
| R37 | Massachusetts commercial criteria: practising-physician input, evidence basis, review cadence, consistent application, on-request availability. | c.176O §16; 211 CMR 52.08 | K |
| R38 | Massachusetts adverse determination letter content (clinical justification, information relied on, alternatives, criteria included). | c.176O §12 | K |
| R39 | Statutory PA exemptions modelled as hard exclusions with citations (SUD 14 days; emergency psychiatric care; the June 2026 211 CMR 52.07(5) list; hospital-to-LTC transfers per chapter 197 of 2024). | ch. 258 of 2014; ch. 177 of 2022; 211 CMR 52.07(5); ch. 197 of 2024 | K/V |
| R40 | Step-therapy exception criteria and windows (commercial and Part B). | ch. 260 of 2020 (section U); 422.136 | K |
| R41 | Provider notice log; default 60-day notice for material changes, configurable per state and LOB. | c.176O (2010 amendments, section U); practice | U |
| R42 | Chapter 342 chronic-condition drug selections linked to pharmacy policies. | ch. 342 of 2024; DOI FGN 2026-M | V |
| R43 | Jurisdiction overlays for NH, ME, CT, RI. | RSA 420-J; 24-A MRS §4304; CGS §38a-591a; RIGL §27-18.9 | K/U |
| R44 | Criteria-on-request fulfilment workflow (free, logged, SLA) including documents not relied on. | 2560.503-1(h),(m)(8); NCQA UM 2B | K |
| R45 | Denial codes with plain-language meanings; "standard used" text; codes on request. | 45 CFR 147.136 | K |
| R46 | IRR case sets generated from criteria; annual results stored per reviewer and version. | NCQA UM 2C; URAC HUM | K |
| R47 | Lifecycle states with full version history, diff and point-in-time retrieval for any decision date. | ERISA records; 422.504(d); NCQA | K |
| R48 | Evidence section schema and public display of review dates. | 422.101(b)(6); NCQA UM 2; c.176O §16 | V/K |
| R49 | Licensed criteria references carry licence terms; MA-applied vendor criteria reproduced publicly or replaced by plan text. | 422.101(b)(6); HPMS FAQ | V |
| R50 | Provider-level PA exemption (gold card) keyed to policy and code. | AHIP pledge; pending state bills | S/U |
| R51 | Machine-evaluable criteria enabling real-time approvals for complete electronic requests. | AHIP pledge | V/U |
| R52 | Public metrics and PA-list pages versioned and archived. | CMS-0057-F | V |
| R53 | Cross-reference from policy to the EOC or member handbook sections describing PA. | 438.10(g); 422.111 | K |
| R54 | Regulatory-source registry with effective and compliance dates driving "applies from" logic. | all | n/a |
| R55 | Validation refuses to publish a PA requirement on a service a regulation prohibits for that LOB and state. | 211 CMR 52.07(5); ch. 258; ch. 177 | V/K |

## 2.11 Follow-ups for Compliance before configuration

1. Pull the primary text for every [K] and [U] item: 42 CFR 422.101, 422.122, 422.136, 422.137, 422.138, 422.566, 422.568; 438.10, 438.210, 438.236, 438.404, 438.915; 29 CFR 2560.503-1; 45 CFR 147.136, 146.136; M.G.L. c.176O §§7, 12, 16, 25; 211 CMR 52.07 and 52.08; the NH, ME, CT and RI statutes.
2. Pull the current MassHealth MCO and ACPP contracts and the RI EOHHS contract for the UM and medical-necessity-guideline clauses.
3. Confirm the CY2027 rule's effective date for the 422.137(d)(6) rescission and any other UM-committee changes.
4. Track CMS-0062 for a final rule and any Massachusetts PA reform enactment on notice periods or gold carding.
5. Confirm with Legal the notice period the plan will commit to per state and LOB, so it can be configured as data.
