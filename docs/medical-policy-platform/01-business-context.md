# 1. Business context, scope and current state

## 1.1 What "medical policy database" means here

A medical policy is the plan's written statement of when a service, device, procedure, test or medical-benefit drug is medically necessary, experimental/investigational, cosmetic or not covered, and what a provider must document to obtain authorization. A prior-authorization (PA) requirement is the operational rule that says whether a given service, for a given member population, must be authorized before it is delivered, by whom, through which channel and on what clock. The two are related but not the same thing: a policy can exist without a PA requirement (it is applied at claims review or on appeal), and a PA requirement can exist without a plan-authored policy (it points to InterQual, MCG, a CMS coverage determination or a delegated vendor's guideline).

The "medical policy database" is therefore three things bound together:

1. the **content**: policies, criteria, evidence, definitions, code tables, effective dates, versions and applicability;
2. the **rules**: PA requirements per line of business (LOB), product, state, site of service and provider type, with routing and documentation needs, and the machine-readable criteria that electronic prior authorization needs;
3. the **operations**: the authoring and committee workflow, the publication to public sites and portals, the extracts to the UM system, the ePA platform, claims edits and delegated vendors, and the audit trail that regulators and NCQA ask for.

The reason the organisation needs it now is simple to state: the same policy content is today needed in six places (two brand websites now consolidated onto one, the UM system, the ePA platform for CMS-0057-F, the claims edit system, delegated vendors, and provider communications), and there is no single source that all six can be reconciled against.

## 1.2 Lines of business and populations in scope

| Brand | Line of business | Regulatory regime that shapes policy content | Notes |
|---|---|---|---|
| Harvard Pilgrim Health Care | Commercial fully insured (MA, NH, ME, CT, RI) and self-insured/ASO | State insurance law of the situs state (211 CMR 52 in Massachusetts; NH, ME, CT, RI statutes), ERISA claims rules for ASO, ACA claims/appeals, MHPAEA | Tufts Health Plan commercial groups migrated onto Harvard Pilgrim products through mid-2025; a residual THP Commercial population still appears in 2026 guideline notices |
| Tufts Health Plan (Tufts Medicare Preferred) | Medicare Advantage (HMO/PPO) | 42 CFR Part 422 (coverage criteria, UM committee, decision timeframes), NCD/LCD precedence, CMS-0057-F APIs | Internal criteria only where Medicare rules are not fully established, publicly accessible with evidence |
| Tufts Health Plan | Senior Care Options (SCO) and One Care | Medicare Advantage rules plus MassHealth contract | Dual-eligible programmes; both regimes apply |
| Tufts Health Public Plans | Tufts Health Together (MassHealth MCO/ACO partnerships) | 42 CFR Part 438, MassHealth contract and bulletins (including the MassHealth interoperability/PA bulletin), MassHealth Unified Formulary | State medical necessity guidelines and formulary take precedence |
| Tufts Health Public Plans | Tufts Health RITogether (Rhode Island Medicaid) | 42 CFR Part 438, RI EOHHS contract | Separate state guideline set |
| Tufts Health Public Plans | Tufts Health Direct (Marketplace/ConnectorCare QHP) | ACA/QHP rules, Massachusetts Connector, 211 CMR 52, CMS-0057-F for QHPs on the federally facilitated exchange does not apply to the Massachusetts state exchange but state law does | Listed separately in every Point32Health notice |

Every requirement in section 4 that says "per LOB" is because at least two of these regimes give a different answer for the same code.

## 1.3 Consumers of policy content and PA rules

| Consumer | Needs from the platform | Today |
|---|---|---|
| Providers (public web, no login) | Which policy applies, whether PA is needed for a code and plan, what to document, what changed and when | PDF Medical Necessity Guidelines (MNGs) on point32health.org, product-level PA lists and quick-reference guides, monthly newsletter notices, separate vendor pages |
| Providers (portal) | Same answer inside HPHConnect and the Tufts portal; from late 2026 inside Availity Essentials | Portal functions per legacy brand |
| ePA platform (Onyx SAFHIR) for CMS-0057-F | Coverage requirement rules for CRD, questionnaire packages for DTR, documentation lists, policy links for cards | Being built; content is hand-assembled per policy |
| UM system (MHK) | Auth requirement tables, auth types/service groups, criteria references, documentation checklists | Configured by hand from policy documents and spreadsheets |
| Claims editing | Non-covered, diagnosis-restricted, frequency-limited and investigational codes by LOB and date | Configured separately |
| Delegated vendors (Carelon, Evolent, eviCore, OncoHealth, EyeMed, others) | Which categories they own per LOB, plan policy where it governs, notice of changes | Vendor pages and contracts; no single matrix |
| Appeals, grievances, member services, external review | The policy version and criteria in force on the date of service, the rule that fired, criteria on request | Manual retrieval from PDFs and archives |
| Compliance and accreditation | Annual review evidence, committee minutes, notice proof, public accessibility of criteria (MA), parity analyses (MH/SUD) | Assembled manually for audits |
| Regulators and public | Public posting of criteria and PA requirements; CMS PA metrics; state filings on PA changes (for example the June 2026 Massachusetts amendments prohibiting PA for listed services) | Manual |
| Analytics | Which policies drive volume, denials, appeals and overturns | Not linked to policy versions |

## 1.4 Current state (public information, September 2026)

- Provider content has been consolidated on point32health.org (a WordPress-based site with document aliases). The unified vocabulary is "Medical Necessity Guidelines (MNGs)" for both heritages; legacy Harvard Pilgrim "Medical/Clinical Policies" and Tufts MNG URLs still resolve or redirect.
- MNGs, medical-benefit drug MNGs and pharmacy MNGs are PDFs with an HTML index, applicability stated inside each document, monthly update notices in the provider newsletter, and a statement that some MNGs use InterQual (viewable on Optum's site). No public reference to MCG. No public code-level lookup, redline or version archive was observed.
- PA requirements are distributed across product-level PA lists and quick-reference guides (HPHC Commercial, HPHC-NH, Tufts Medicare Preferred, SCO, One Care, Together, RITogether, Direct), provider manual chapters (Harvard Pilgrim lettered chapters such as D-1; Tufts product manuals), payment policies with code lists, and delegated-vendor pages.
- Delegation is fragmented by heritage: Carelon (molecular/genetic testing; scope by product), Evolent for imaging and sleep on Harvard Pilgrim members, eviCore on Tufts products, OncoHealth for oncology drug policy, EyeMed for routine vision on senior products; behavioral health UM is insourced since November 2023.
- Portals: HPHConnect and the Tufts provider portal today; Availity Essentials rolling out in phases from late 2026 with a single login for all products. X12 278 via NEHEN (Harvard Pilgrim) and a Tufts 278 companion guide dated 2016.
- UM platform: MHK (MedHOK), per job postings. ePA: Onyx SAFHIR (from the ePA programme's own material, not from public statements). No public CMS-0057-F readiness statement was found.
- Regulatory motion in the last 18 months that directly changes policy content: the January 2026 CMS-0057-F decision timeframes and denial-reason requirements; the 1 January 2027 API deadline for Medicare Advantage, Medicaid and Marketplace lines; the June 2026 amendments to 211 CMR 52.07(5) prohibiting PA for a list of routine and essential services and setting a 24-hour urgent response; Massachusetts chapter 197 of the Acts of 2024 on hospital-to-long-term-care transfer PA; the MassHealth interoperability/PA process bulletin; the June 2025 AHIP pledge (Point32Health signed the original six commitments, did not sign the April 2026 technology update, and issued a statement of continued commitment).
- Point32Health's own PA change log shows the operational pattern the platform must support: adds (targeted immunomodulator criteria, GLP-1 exclusion, ancillary-service denial rule), removals (home health first 30 days), a change announced and then withdrawn (elective inpatient InterQual review for 1 April 2026), and a continuity-of-care rule for members switching plans. Each of these is a versioned, noticed, per-LOB change.

Detail and sources are in section 2 (regulatory) and the sources appendix.

## 1.5 Problem statement

1. **Content is locked in documents.** MNGs are PDFs; the codes, applicability and criteria inside them cannot be queried, exported or reconciled. Every downstream system re-keys them.
2. **Two heritages, several numbering schemes, no single identifier.** Harvard Pilgrim and Tufts documents carry different conventions; the same clinical policy may exist twice with different effective dates.
3. **PA requirement is not a single answer.** A provider, a portal, Availity, the ePA CRD service and the UM nurse can each reach a different answer for the same code and plan because the answer is assembled from different documents.
4. **ePA needs machine-readable criteria.** CMS-0057-F's CRD and DTR transactions cannot be served from PDFs; someone has to digitize criteria, and that work has no home, no versioning and no test harness today.
5. **Regulatory cadence is monthly and per state.** Massachusetts alone has changed PA rules three times in 18 months; the platform must make a change once and propagate it to every channel with the right notice period.
6. **Audit evidence is assembled by hand.** Annual review, committee approval, public accessibility of Medicare criteria, notice proof and parity documentation are all reconstructed at audit time.

## 1.6 Scope statement

In scope: medical policies, medical-benefit drug policies, behavioral health UM criteria, PA requirement rules for every brand and LOB above, adopted external criteria references (InterQual, MCG, CMS NCD/LCD/Articles, delegated-vendor guidelines, state Medicaid guidelines), digitized criteria for ePA, publication to public sites, portals and partner channels, extracts to UM, ePA, claims edits and vendors, the authoring and committee workflow, and the audit evidence.

Adjacent, linked but not mastered here: payment/reimbursement policies (same platform can host them later; they have a different owner and cadence), pharmacy-benefit formulary and NCPDP transactions, benefit configuration, provider directory, contracts, the UM adjudication itself, and member PHI.

## 1.7 Reading the rest of this document

- Section 2 is the regulatory and accreditation inventory with a traceability table.
- Section 3 is the vendor landscape and the assessment of Itiliti Health against the evidence.
- Section 4 is the requirements catalogue with Must/Should/Could/Won't priorities.
- Section 5 is the solution design.
- Section 6 is the technology position: what is reused, what is new, what is not to be introduced.
- Section 7 is the build/buy/partner analysis, the delivery roadmap, the vendor evaluation scorecard and the demo script.
- Section 8 is the glossary and the sources.
