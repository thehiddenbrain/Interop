# 4. Requirements catalogue

Priorities use MoSCoW: **M** = Must (the platform is not fit for purpose without it, or a regulation, accreditation standard or contract requires it), **S** = Should (needed to run the process well; defer only with a stated workaround), **C** = Could (valuable, schedule when capacity allows), **W** = Won't (deliberately out of scope for this platform; listed so nobody assumes it is in).

Each requirement carries a source tag: `REG` (law/regulation, see section 2 for the citation), `ACC` (NCQA/URAC), `EPA` (electronic prior authorization programme), `OPS` (operations of UM, claims, provider relations), `TECH` (engineering). IDs are stable so they can be used in an RFP scorecard or a vendor gap analysis.

## 4.1 Policy content model

| ID | Requirement | Pri | Source |
|---|---|---|---|
| CM-01 | Every policy has a stable identifier that never changes across versions, brands or lines of business (LOB); display numbers per brand may be aliases of it. | M | OPS |
| CM-02 | Policy types: medical policy, medical-benefit drug policy, behavioral health policy, reimbursement/payment policy (link only, may live elsewhere), UM administrative policy, adopted external guideline (InterQual, MCG, CMS NCD/LCD, delegated-vendor guideline). | M | OPS |
| CM-03 | Structured sections rather than one blob: Purpose/Scope, Description of service, Coverage criteria (structured), Limitations and exclusions, Documentation requirements, Coding table, Definitions, Evidence summary with citations, References, Revision history, Applicability, Related policies. | M | REG, ACC |
| CM-04 | Evidence summary and reference list are mandatory fields for any Medicare Advantage policy that applies internal coverage criteria (public accessibility and evidence basis are required). | M | REG |
| CM-05 | Each version records: version number, effective start, effective end, approval date, publish date, provider notice date, review date, next scheduled review, status, change summary (human readable), and a machine diff against the previous version. | M | REG, ACC |
| CM-06 | Effective dating per applicability: the same policy version can start on different dates per LOB or state (for example a 60-day state notice on Commercial, immediate on Medicare when it mirrors an NCD). | M | REG |
| CM-07 | Applicability dimensions: brand (Harvard Pilgrim, Tufts Health Plan), LOB (Commercial fully insured, Commercial self-insured/ASO, Medicare Advantage, SCO, Medicaid MassHealth, Medicaid RI, Marketplace/ConnectorCare), product (HMO/PPO/POS/EPO), state of situs, network, and employer-group exceptions (ASO carve-outs). | M | OPS |
| CM-08 | Precedence rules are explicit data, not tribal knowledge: for Medicare Advantage, NCD/LCD/Article precedence over internal criteria; for MassHealth, state guideline precedence; for Commercial, internal policy unless state law mandates otherwise. | M | REG |
| CM-09 | Criteria source attribution: internal, InterQual (subset and version), MCG (guideline id and edition), CMS NCD/LCD/Article numbers with versions, delegated vendor guideline id, state Medicaid guideline id, with hyperlinks and licence flags. | M | REG, ACC |
| CM-10 | Policy relationships: supersedes, replaced-by, related-to, drug-of-same-class, companion reimbursement policy, parent guideline. | S | OPS |
| CM-11 | Retired versions are retained indefinitely and remain retrievable by date (what applied on a given date of service) for appeals, audits and litigation. | M | REG, OPS |
| CM-12 | Every field is auditable: who changed what, when, from what to what, with the reason code. | M | ACC, REG |
| CM-13 | Attachments (evidence PDFs, committee minutes, vendor guideline copies) stored with the version, with licence restrictions honoured (licensed content is never published outward). | S | OPS |
| CM-14 | Member-facing plain-language summary as an optional section, reusable in member letters and the member site. | C | REG |
| CM-15 | Multi-language rendering of member-facing text (Spanish, Portuguese, Chinese, Haitian Creole, Vietnamese for MA markets). | C | REG |

## 4.2 Coding and terminology

| ID | Requirement | Pri | Source |
|---|---|---|---|
| CT-01 | Code table per policy version with code system (CPT, HCPCS Level II, ICD-10-CM, ICD-10-PCS, NDC, revenue code, modifier, place of service, DRG, CDT), code, role (in-scope procedure, covered diagnosis, non-covered diagnosis, investigational, not covered, requires PA, notification only, unlisted/needs review), effective dates and free-text note. | M | OPS, EPA |
| CT-02 | Codes are references into a managed terminology store, not typed strings; descriptions and validity dates come from the store. | M | TECH |
| CT-03 | Quarterly and annual code-set loads (CPT annual and quarterly Category III/PLA, HCPCS quarterly, ICD-10 annual and April updates, NDC weekly) with an impact report listing every policy touched by an added, deleted or re-described code. | M | OPS |
| CT-04 | Value sets (named, versioned collections of codes) that several policies can share, with FHIR ValueSet export. | M | EPA |
| CT-05 | AMA CPT licensing is respected: CPT descriptors are shown only where the plan holds a licence; public and API publication use codes with plan-authored short descriptions or licence-cleared text. | M | REG |
| CT-06 | Code ranges, modifier combinations, and code-plus-diagnosis pairs (for example CPT with covered ICD-10 list) are first-class. | M | OPS |
| CT-07 | Age, sex, quantity, frequency and lifetime limits attach to a code-policy row (for example one per 12 months). | M | OPS |
| CT-08 | Site-of-service rules (inpatient, outpatient hospital, ASC, office, home) attach to code-policy rows. | M | OPS |
| CT-09 | Drug policies carry NDC and HCPCS J/Q codes, dosing and quantity limits, step therapy sequence, and diagnosis requirements; medical-benefit drugs are in scope, pharmacy-benefit formulary is out of scope (see W-02). | M | OPS |
| CT-10 | NCD/LCD/Article billing-and-coding tables can be imported from the CMS Medicare Coverage Database and diffed on each CMS release. | S | REG |
| CT-11 | Code search across all policies: "which policies and PA rules involve code 64483 for Medicare Advantage in Massachusetts on this date". | M | OPS |
| CT-12 | Cross-check against NCCI edits and claims-edit vendor rules to flag conflicts. | C | OPS |

## 4.3 Prior authorization requirement determination

| ID | Requirement | Pri | Source |
|---|---|---|---|
| PA-01 | A PA rule is a distinct object from a policy: code set + applicability + site of service + provider type + member attributes -> requirement outcome (PA required, notification only, no PA, not covered, PA required if criteria not met at point of care, delegated). | M | EPA, OPS |
| PA-02 | Outcome carries routing: internal UM (MHK service group / auth type), delegated vendor (name, contact, submission channel), pharmacy, behavioral health partner. | M | OPS |
| PA-03 | Outcome carries submission channels and turnaround class (expedited/standard), regulatory clock per LOB. | M | REG |
| PA-04 | Outcome carries documentation requirements (clinical documents needed) and a link to the digital questionnaire when one exists. | M | EPA |
| PA-05 | A single evaluation API answers "is PA required" given code, LOB, product, state, site, provider type, date, member attributes; it is the same answer for the public lookup, the provider portal, Availity, NEHEN, the CRD service and the UM system. | M | EPA |
| PA-06 | Gold-card / exemption programmes: provider or group exemptions from PA for specified codes, with dates and review cycle. | S | REG |
| PA-07 | Continuity rules: when a PA requirement is added or a policy tightens, an existing authorization remains valid for the regulatory period; the platform records the transition rule per LOB. | M | REG |
| PA-08 | Every PA-requirement change is time-boxed to a provider notice: the system prevents an effective date earlier than the notice period configured for the LOB and state unless an override with reason (regulatory mandate, safety) is recorded. | M | REG |
| PA-09 | A complete PA list per LOB/product can be generated at any date as HTML, PDF, CSV and JSON. | M | REG, OPS |
| PA-10 | Simulation: run the proposed rules against 12 months of claims/auth history to estimate volume, auto-approval, denial and provider impact before approval. | S | OPS |
| PA-11 | Reason codes for each rule (why PA exists: safety, cost, site of service, investigational, regulatory) to support periodic PA list pruning and reporting. | S | OPS |

## 4.4 Digitized criteria (machine-readable content)

| ID | Requirement | Pri | Source |
|---|---|---|---|
| DC-01 | Coverage criteria can be authored as a structured logic tree (AND/OR/NOT groups, comparisons, thresholds, time windows, code-membership tests) linked paragraph by paragraph to the prose. | M | EPA |
| DC-02 | Each criterion is classified objective (can be evaluated from data) or subjective (needs clinician judgement); subjective criteria generate a question, objective ones generate an expression. | M | EPA |
| DC-03 | Export as HL7 FHIR SDC Questionnaire (static and adaptive), with Library resources holding CQL for prepopulation, and ValueSets, packaged the way Da Vinci DTR expects, with canonical URLs and versions. | M | EPA |
| DC-04 | Export decision logic as a decision table or rule set consumable by the CRD engine and by the UM system (DMN, JSON rule set, or the vendor's native format via adapter). | M | EPA |
| DC-05 | Test cases stored with each digitized criteria set (synthetic patient data, expected outcome) and executed on every change. | M | EPA, TECH |
| DC-06 | Round-trip traceability: from a question or expression back to the policy paragraph and the evidence, and forward to which auth decisions used it. | S | ACC |
| DC-07 | Licensed criteria (InterQual, MCG) are referenced, never copied into our exports beyond what the licence allows. | M | REG |
| DC-08 | AI-assisted drafting of criteria trees and questionnaires from prose, always reviewed and approved by a clinician before release; the AI output is marked as a draft with provenance. | C | TECH |
| DC-09 | Quality metrics per policy: percentage of criteria objective, prepopulation coverage, test-case pass rate. | S | EPA |

## 4.5 Authoring and workflow

| ID | Requirement | Pri | Source |
|---|---|---|---|
| WF-01 | Configurable lifecycle: Intake -> Research/Draft -> Coding -> Impact analysis -> Clinical review -> Legal/Compliance review -> Committee -> Configuration -> Notice -> Published -> Under review -> Retired, with parallel review steps where useful. | M | ACC, OPS |
| WF-02 | Role-based tasks with due dates, reassignment, escalation and dashboards (my work, overdue, coming due, by committee). | M | OPS |
| WF-03 | Committee management: agendas built from ready items, packet generation, vote recording, minutes attachment, quorum and conflicts-of-interest capture. | M | ACC, REG |
| WF-04 | Annual review scheduling per policy with reminders and a report proving every active policy was reviewed within the window (NCQA and MA UM committee evidence). | M | ACC, REG |
| WF-05 | Redline comparison between any two versions, in the editor and in exported PDF. | M | OPS |
| WF-06 | Concurrent editing protection (check-out or real-time co-editing) and comment threads on sections. | S | OPS |
| WF-07 | Templates per policy type enforcing required sections and house style. | M | OPS |
| WF-08 | Intake sources: new-technology requests, provider inquiries, vendor guideline updates, CMS NCD/LCD changes, code-set updates, regulatory changes, appeals trend triggers. | S | OPS |
| WF-09 | Electronic sign-off by Medical Director/CMO with identity assurance; approvals immutable. | M | ACC |
| WF-10 | External reviewer access (specialty society reviewer, delegated vendor) with limited scope and expiry. | C | OPS |
| WF-11 | Impact-analysis step captures affected members, claims and providers, cost estimate, and parity (NQTL) assessment where the policy touches behavioral health. | S | REG |
| WF-12 | Configuration hand-off tasks generated automatically to UM configuration (MHK), ePA content (CRD/DTR), claims edits, delegated vendors and web publishing, each tracked to completion before the effective date. | M | OPS, EPA |

## 4.6 Publishing and provider/member transparency

| ID | Requirement | Pri | Source |
|---|---|---|---|
| PB-01 | Public website publication per brand and LOB with no login: policy documents (HTML and PDF), PA lists, code lookup, effective-date filter, archive of prior versions. | M | REG |
| PB-02 | Scheduled publishing: content goes live at the effective date automatically; upcoming changes are visible in advance ("effective 1 January 2027") for the notice period. | M | REG |
| PB-03 | "What changed" feed: monthly bulletin, RSS/email subscription, and a changes page with redline summaries. | M | REG, OPS |
| PB-04 | Machine-readable downloads: PA list and policy-code map as CSV and JSON with a documented schema and version stamp. | S | REG |
| PB-05 | Criteria available on request: workflow and log to fulfil provider/member requests for the criteria used in a decision, including licensed criteria excerpts where the licence allows. | M | REG, ACC |
| PB-06 | Accessibility (WCAG 2.1 AA) and mobile rendering of all public pages. | M | REG |
| PB-07 | Provider portal integration (HPHConnect, Tufts provider portal, Availity Essentials): the same PA lookup service behind the login. | M | OPS |
| PB-08 | Member site rendering of member-facing summaries and of the coverage criteria (MA requires public accessibility of internal criteria). | S | REG |
| PB-09 | Search: full text, by code, by category, by LOB, by keyword synonyms (brand and generic drug names). | M | OPS |
| PB-10 | Printable PDF with header/footer carrying policy id, version, effective dates, and brand. | M | OPS |
| PB-11 | Retired-policy archive searchable by date of service for appeals. | M | REG |
| PB-12 | Delegated-vendor pages: clear statement of which vendor manages which services per LOB with links to the vendor's criteria. | M | REG, OPS |
| PB-13 | Analytics on public usage (most viewed, searches with no result) to prioritise clarity work. | C | OPS |

## 4.7 Integrations

| ID | Requirement | Pri | Source |
|---|---|---|---|
| IN-01 | ePA platform (Onyx SAFHIR): supply coverage-requirement rules for CRD and questionnaire packages for DTR through the vendor's supported mechanism (API, configuration import or file), versioned and effective-dated. | M | EPA |
| IN-02 | UM system (MHK): export auth-requirement tables / service groups / auth rules and criteria references, with a reconciliation report showing differences between the policy database and the live UM configuration. | M | OPS |
| IN-03 | Claims edits: export code-policy rules for medical-necessity edits and non-covered codes to the claims editing system; reconciliation report. | S | OPS |
| IN-04 | Availity / NEHEN / X12 278: PA-requirement lookup service consumed by clearinghouse and portal flows. | M | EPA |
| IN-05 | Terminology feeds: CPT (AMA), HCPCS (CMS), ICD-10 (CMS/CDC), NDC (FDA), NCD/LCD (CMS MCD), and licensed criteria references (InterQual, MCG). | M | OPS |
| IN-06 | Delegated vendors: outbound notifications and inbound receipt of vendor guideline updates that affect delegated categories. | S | OPS |
| IN-07 | Appeals and grievances system: link decisions to the policy version in force on the date of service. | S | REG |
| IN-08 | Provider communications: generate notice content and push to the bulletin/email systems; record proof of notice. | M | REG |
| IN-09 | Identity: enterprise SSO (SAML/OIDC) and group-based roles; public endpoints anonymous, rate-limited. | M | TECH |
| IN-10 | Events: publish "policy published/retired/PA rule changed" events on the enterprise bus (TIBCO) for subscribers. | S | TECH |
| IN-11 | Data warehouse feed of policy, code and PA-rule history for analytics and CMS PA metric reporting (which policies drive denials). | S | REG, OPS |

## 4.8 Non-functional

| ID | Requirement | Pri | Source |
|---|---|---|---|
| NF-01 | Availability: internal authoring 99.5%; public publication and PA-lookup API 99.9% (they are on the critical path of ePA and provider workflows). | M | TECH |
| NF-02 | PA-lookup API latency under 300 ms p95 for a single code query; bulk export within minutes. | M | EPA |
| NF-03 | Security: PHI is not stored in the policy database (only synthetic test data); still treated as confidential business data; encryption in transit and at rest; audit log immutable; least privilege. | M | TECH |
| NF-04 | Retention: all versions and audit records retained at least 10 years (MA record retention) and never purged automatically. | M | REG |
| NF-05 | Disaster recovery: RPO 1 hour, RTO 4 hours for the public and API tier. | S | TECH |
| NF-06 | Observability: metrics, structured logs, traces; dashboards per integration with reconciliation status. | M | TECH |
| NF-07 | Environments: dev, test, UAT, prod, with content promotion (not database copies) so UAT can carry draft content safely. | M | TECH |
| NF-08 | Import of the existing corpus (hundreds of Word/PDF policies, spreadsheets of codes and PA lists) with structure recovery and a manual QA queue. | M | OPS |
| NF-09 | Exportability: full content export in open formats (JSON, HTML, FHIR) at any time; no vendor lock on the system of record. | M | TECH |
| NF-10 | Capacity: 1,000+ policies, 100,000+ code-policy rows, 10 years of versions, 50 concurrent authors, public traffic peaks at year-end code updates. | S | TECH |

## 4.9 Explicitly out of scope (Won't, for this platform)

| ID | Item | Why |
|---|---|---|
| W-01 | Adjudicating an individual authorization or claim. | That is the UM system and the claims system; the policy platform supplies the rules and content. |
| W-02 | Pharmacy-benefit formulary management and NCPDP transactions. | Owned by the PBM/formulary platform; medical-benefit drug policies are in scope. |
| W-03 | Replacing licensed criteria (InterQual, MCG) with home-grown equivalents. | Licensed content stays licensed; the platform references and orchestrates it. |
| W-04 | Storing member PHI or clinical records. | Keeps the platform out of the PHI boundary; simulation uses de-identified extracts through the warehouse. |
| W-05 | Being the provider directory, benefit configuration or contract system. | Applicability points at those systems' identifiers; it does not master them. |
| W-06 | Hosting the corporate website CMS. | The platform publishes into the website; it does not become the website. |
