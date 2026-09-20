# 7. Build, buy or partner; roadmap; evaluation kit

## 7.1 The decision, stated plainly

There are four ways to get the capability described in sections 4 and 5. They differ in who owns the system of record, who does the digitization labour, and who runs the ePA content delivery.

| Option | System of record (governance, crosswalk, publishing) | Digitization of criteria | ePA content delivery (CRD rules, DTR packages) | Fit for Point32Health |
|---|---|---|---|---|
| A. Buy a full suite | Vendor (Itiliti Policy Management, or Cohere Policy Studio) | Vendor service | Vendor, through its Onyx connector or its own transport | Fastest if the vendor's governance module proves adequate in a demo; highest lock-in and vendor-viability exposure; no vendor evidenced NCD/LCD precedence, MassHealth overlays, drug policies and MHK integration |
| B. Buy digitization only | Plan-built platform (section 5) | Vendor service delivering open-format outputs (Questionnaire, Library, ValueSet, decision tables) accepted through the platform's checklist gate | Onyx consumes the platform's compiled content, or the vendor's connector | Keeps ownership; buys the scarce clinical-digitization labour; moderate vendor exposure |
| C. Build everything | Plan-built platform | Internal clinical policy analysts and a FHIR/CQL engineer, with open tooling (NLM Form Builder, cqf-tooling, br-payer) | Onyx consumes compiled content | Full control; slowest to reach volume; needs a digitization team the plan does not have today |
| D. Hybrid (recommended) | Plan-built platform for governance, crosswalk, PA evaluation and publishing | Vendor digitization for the high-volume backlog in 2026 to 2027 (RFP among Itiliti, Cohere, Availity, InterQual conversion), delivered in open formats; internal team takes over maintenance and new policies from 2027 | Onyx via the vendor connector where a vendor is used, and via the platform's compiled content for internal work; MHK via the platform's export | Ownership plus speed; the vendor is replaceable because the content lives in the plan's platform in open formats |

Why not A: every vendor in section 3 is strongest at one or two layers and none has shown the governance, multi-regime and integration functions Point32Health needs; a full-suite purchase would still leave the plan building the crosswalk, the NCD/LCD ingestion, the MHK extract and the public rendering, now against a vendor's data model. Why not C alone: the January 2027 date and the size of the backlog (hundreds of MNGs, drug policies, product-level PA lists) make external digitization labour worth buying for the first wave.

**Recommendation: option D.** Build the system of record on the existing stack (section 6), run a competitive proof-of-concept for digitization services, and make "content delivered in open formats into our platform" a contract condition for whichever vendor wins.

## 7.2 Cost and effort framing (order of magnitude, for planning, not a quote)

| Item | Build (option D platform) | Notes |
|---|---|---|
| Core team | 2 to 3 Java/Spring engineers, 1 front-end engineer, 1 FHIR/CQL engineer, 1 product owner (Clinical Policy), 1 business analyst, 1 QA, part-time architect, UX and compliance | 12 to 15 months to phase 3 |
| Content team | 2 to 3 clinical policy analysts, 1 coding specialist, 1 UM configuration analyst (MHK), 1 web publishing owner | Existing roles, re-pointed at the platform |
| Digitization service (option D) | Vendor fee per policy or per LOB for the first wave; typical engagements price per policy digitized plus a platform subscription; no vendor publishes prices | Budget from the RFP; require per-policy pricing and a cap |
| Infrastructure | PostgreSQL, object storage and CDN, container hosting, observability, all on the enterprise cloud | Small relative to labour |
| Licences | AMA CPT distribution licence for public and API publication; X12 code lists; InterQual/MCG terms extended to cover exposure of question text through DTR | Legal to confirm the DTR licensing note with each licensor |

## 7.3 Roadmap

Dates assume a start in Q4 2026 and are driven by the 1 January 2027 API date, the October 2026 guide versions, and the plan's monthly policy cadence.

| Phase | Window | Scope | Exit criteria |
|---|---|---|---|
| 0. Mobilise and evaluate | Oct to Nov 2026 | Approve this design; run the vendor proof-of-concept (section 7.5) on five to ten real policies; sign the AMA and criteria-licensing positions; confirm Onyx's supported content import mechanism; confirm MHK's configuration import options | Decision on option D vendor; Onyx and MHK integration contracts agreed; team staffed |
| 1. System of record and PA lookup | Dec 2026 to Mar 2027 | Policy and version model, applicability, code tables, terminology loads (CPT, HCPCS, ICD-10, NDC, MCD), PA rule model and evaluation API, migration of the existing MNG corpus and product PA lists with a QA queue, public PA lookup and policy pages (static publication behind the CDN), archive, audit, SSO, basic workflow (draft, review, approve, schedule, publish) | Every active MNG and PA list is in the platform with a stable id; the public lookup answers the same as the PDFs; the January 2027 PA list for MA and Medicaid is generated from the platform; CMS PA metrics list for CY2026 generated from it by March 2027 |
| 2. Committees, digitized criteria, ePA and UM extracts | Apr to Sep 2027 | Committee workflow and evidence reports; criteria builder and compiler (Questionnaire, Library/CQL, ValueSet, decision tables, tests); Onyx content delivery and reconciliation; MHK auth-rule export and reconciliation; claims-edit export; provider notice automation; vendor-digitized backlog accepted through the gate | First 100 policies with digitized criteria live in Onyx; MHK reconciliation drift below an agreed threshold; NCQA annual-review report produced from the platform |
| 3. Depth and automation | Oct 2027 to Mar 2028 | Adaptive questionnaires for licensed criteria; drug-policy model ready for CMS-0062; impact-analysis simulation from the warehouse; IRR case-set generation; parity (NQTL) metadata and reports; AI drafting assistant (human-reviewed); optional corporate CMS integration; optional search engine | Coverage targets: 80% of PA-required codes with digitized criteria; every MA policy with evidence summary public; parity analysis exportable |

Dependencies: Availity Essentials rollout (late 2026 onward) for the portal lookup; Onyx's confirmed content mechanism; MHK's confirmed import capability; Compliance's primary-source confirmation of the state rules (section 2.11).

## 7.4 Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Vendor cannot deliver content in open formats or refuses reconciliation | Lock-in; drift between systems | Contract condition; acceptance gate; escrow of content |
| Onyx supports only its own content format | Compiler must target a proprietary shape | Adapter per consumer (section 5.9); insist on DTR-conformant packages, which Onyx must serve anyway |
| MHK configuration cannot be imported | Manual keying persists | Reconciliation extract and work-list at minimum; push for MHK's configuration API |
| Licensed criteria cannot be exposed through DTR | Adaptive forms only; some policies not automatable | Negotiate with Optum/MCG early; use adaptive questionnaires; publish plan-authored text where MA requires public accessibility |
| Corpus migration quality | Wrong codes or dates on public pages | QA queue with two-person review; publish alongside the PDFs for one cycle; diff reports |
| Regulatory change mid-build (CMS-0062 final, Massachusetts PA reform) | Scope growth | Jurisdiction overlays and regulatory-source registry as data (R43, R54); monthly regulatory review |
| Small-vendor viability (Itiliti) | Loss of digitization partner | Open-format deliverables; internal team trained during the first wave; second vendor qualified |
| AI drafting errors | Wrong criteria | AI never on the approval path; provenance; clinician review; test cases |

## 7.5 Proof-of-concept script (for any vendor)

Give each vendor the same ten policies and require the same demonstrations, scored against section 4:

1. **Import** our Word/PDF MNGs; show the recovered structure, the codes recognised, the applicability captured.
2. **Model** one policy that differs by LOB (Commercial vs Medicare Advantage where an LCD governs) and show effective dates per LOB and the precedence note.
3. **Committee**: route the version through clinical review and a committee approval with votes, minutes, redline and an immutable approval; show the annual-review report.
4. **Codes**: bulk-load a code table, show a deleted-code impact report after a simulated quarterly HCPCS update.
5. **PA determination**: ask "is PA required" for a code across all our products and sites of service; show the API response and the public lookup page on our domain (or a white-label plan).
6. **Digitize** the criteria: show the criteria tree, the generated DTR Questionnaire package with Library/CQL and value sets, validator results, and test cases; show an adaptive form for a licensed criterion.
7. **Deliver to Onyx**: load the package and rule into the Onyx UAT tenant and run a CRD and DTR round trip from our ePA workbench.
8. **Deliver to MHK**: show the auth-rule export or the reconciliation extract against a MHK configuration sample.
9. **Publish**: schedule the version for a future effective date, generate the provider notice text, show the archive of the prior version by date of service, and export everything (JSON, HTML, FHIR, CSV).
10. **Drug policy**: model a medical-benefit drug policy with NDC/J-code, dosing, step therapy and a P&T approval path.
11. **MassHealth**: show a policy with the "no more restrictive than MassHealth" attestation and the linked state guideline.
12. **Evidence**: SOC 2 Type II or HITRUST report, BAA, two named references (one non-Blues for Itiliti), financials or runway statement, escrow terms, FHIR CapabilityStatements and Inferno/Touchstone results.

## 7.6 Questions for Itiliti Health (and Cohere) in the RFP

**Company and viability.** ARR, customer count, runway, cap table summary; explain the M25 "exit" listing; explain the July 2026 CEO and CRO changes and who owns product decisions; headcount by function (digitizers, clinicians, engineers, implementation, support); concurrent CMS-0057 implementations scheduled for 2026 to 2027; escrow and continuity terms; data export format.

**Digitization.** Who does it (credentials), throughput per policy, QA and error rates, re-digitization on updates; how a single policy carries LOB variants with independent dates, code ranges, age/sex/site edits, and NCD/LCD precedence for MA and SCO; whether Library/CQL for prepopulation is delivered or only static questionnaires.

**Governance.** Committee routing, role-based approvals, redline, audit trail, scheduled publication, retraction, per-state notice support; public publishing on our domains, member vs provider views, WCAG conformance, search, archive, PDF.

**Standards.** CapabilityStatements for CRD, DTR, PAS; Inferno or Touchstone results; guide versions (2.2.1 / 2.2.0 / 2.2.1); CDS Hooks version; X12 278 mapping and clearinghouse plan; PA metrics reporting; production (not Connectathon) EHR integrations with names and volumes; MHK, Availity and portal integration reference architecture with a live example.

**Outcomes.** For the 30/20/15%, 80% and 650-policy claims: which customer, timeframe, baseline; reference calls with BCBS Alabama and BCBS Minnesota and with any non-Blues customer.

**Security and commercial.** SOC 2 Type II or HITRUST; cloud and region; BAA; penetration-test summary; uptime SLA; pricing model (per policy, per LOB, per transaction, per member), digitization fees vs subscription, and the price path as 2027 transaction volumes ramp.

## 7.7 Vendor scorecard (weights sum to 100)

| Area | Weight | Requirement ids | Scoring guidance |
|---|---|---|---|
| Content model and versioning | 15 | CM-01 to CM-12 | Full marks only with per-LOB effective dating, immutable versions, archive by date of service |
| Coding and terminology | 10 | CT-01 to CT-11 | Terminology store, code-set impact reports, value sets |
| PA determination | 15 | PA-01 to PA-09 | One API, all channels, routing, channels, turnaround, notice enforcement |
| Digitized criteria and standards | 15 | DC-01 to DC-07, IN-01 | Conformant DTR packages with CQL, tests, adaptive forms, validator evidence |
| Workflow and committees | 10 | WF-01 to WF-12 | Demonstrated, not described |
| Publishing and transparency | 10 | PB-01 to PB-12 | On our domain, scheduled, archived, accessible, machine-readable |
| Integrations (Onyx, MHK, Availity, claims) | 10 | IN-01 to IN-08 | Live examples; reconciliation |
| Non-functional, security, exportability | 10 | NF-01 to NF-10 | SOC 2, exports in open formats, no lock-in |
| Company viability and references | 5 | section 3 | Named references, financial disclosure, escrow |

Score each area 0 to 5, multiply by weight, and require a minimum of 3 in Content model, PA determination, Digitized criteria and Non-functional regardless of total. An in-house build is scored on the same sheet using the section 5 design and the phase plan, with delivery risk assessed in the viability row.

## 7.8 Decision checklist for the steering committee

1. Approve the principle: one system of record owned by the plan; vendors deliver into it in open formats.
2. Approve option D and the phase 1 scope for the January 2027 obligations.
3. Approve the proof-of-concept with at least two digitization vendors and the scorecard.
4. Confirm the notice periods per state and LOB that the platform will enforce.
5. Confirm the AMA CPT distribution licence and the InterQual/MCG exposure terms.
6. Name the product owner (Clinical Policy), the technical owner (Interoperability engineering) and the web publishing owner.
