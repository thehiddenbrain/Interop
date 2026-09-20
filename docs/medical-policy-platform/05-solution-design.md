# 5. Solution design

This section describes the target platform: what it is, what it holds, how content moves through it, what it produces for each consumer, and how it is put together. It is written to be implementable by the current Java/Spring team and to be usable as the yardstick when a vendor product is evaluated ("show me where this lives in your product").

## 5.1 Positioning: one system of record, many consumers

The platform is the **system of record for coverage policy content and prior-authorization (PA) requirements** across every brand and line of business. Nothing else in the enterprise may hold an authoritative copy; every other system (UM, ePA, claims edits, portals, public websites, delegated vendors) is a consumer that receives a versioned, effective-dated extract and can be reconciled back against the source.

```
                          ┌──────────────────────────────────────────────┐
   Inputs                 │        MEDICAL POLICY PLATFORM (SoR)          │           Consumers
                          │                                              │
  CMS NCD/LCD/Articles ──▶│  Terminology   Policy content   PA rules      │──▶ Public websites (HPHC, THP)
  CPT/HCPCS/ICD/NDC   ──▶│  store         + versions       + evaluation  │──▶ Provider portals / Availity / NEHEN
  InterQual / MCG refs ──▶│                                 API           │──▶ ePA platform (CRD rules, DTR packages)
  Delegated vendor    ──▶│  Workflow &    Digitized        Publishing    │──▶ UM system (MHK auth rules, criteria refs)
    guidelines            │  committees    criteria         pipeline      │──▶ Claims edits
  Evidence (Hayes/ECRI,──▶│                (Questionnaire,                │──▶ Delegated vendors
    literature)           │  Audit &       CQL, decision                  │──▶ Appeals & grievances, member services
  Regulatory changes  ──▶│  compliance    tables, tests)                 │──▶ Data warehouse / CMS PA metrics
  Claims & auth data  ──▶│  evidence                                     │──▶ Provider notices (60/90-day bulletins)
   (for impact analysis)  └──────────────────────────────────────────────┘
```

Two design rules follow from this:

1. **Content is data, not documents.** A policy is a structured record whose sections, codes, applicability and criteria are separately addressable. Documents (HTML, PDF, Word) are renderings produced on demand. This is what makes the same policy publishable to a website, exportable to a UM system and compilable into a questionnaire without three teams retyping it.
2. **Every consumer gets a versioned, effective-dated extract and can be reconciled.** The platform keeps a record of what was sent where, when, and which version, and provides a comparison report against what the consumer currently holds. Drift between the website, the UM system and the ePA engine is the failure mode this platform exists to remove.

## 5.2 Domain model

### 5.2.1 Core entities

```
Policy 1───* PolicyVersion 1───* Section
  │                │
  │                ├───* Applicability (brand, LOB, product, state, network, group exception; effective window)
  │                ├───* CriteriaSource (internal | InterQual | MCG | NCD | LCD | Article | vendor | state)
  │                ├───* CodeMapping ──▶ Concept (terminology store)     [role, dates, limits, site, modifiers]
  │                ├───* CriteriaSet (structured logic tree) ──▶ Questionnaire, Library(CQL), DecisionTable, TestCase
  │                ├───* Reference / Attachment
  │                ├───* Approval (who, role, when, committee, vote)
  │                └───* Publication (channel, version, sent at, acknowledged, reconciliation state)
  │
  └───* AuthRequirementRule ──▶ CodeSet, Applicability, Conditions (site, provider type, age, sex, qty/freq)
                          ──▶ Outcome (requirement type, routing, channels, turnaround class, docs, questionnaire)
                          ──▶ Notice (notice date, channel, proof)
```

**Policy.** Identity (`MP-0001`), type, category taxonomy (for example Cardiology > Imaging), owner, steward committee, review cycle, brand aliases (Harvard Pilgrim number, Tufts MNG number, legacy numbers). Everything else hangs off versions.

**PolicyVersion.** A complete, immutable snapshot once approved. Fields: version label (2027.1), status (Draft, In review, Approved, Scheduled, Active, Superseded, Retired, Withdrawn), effective start and end, approval date, publication date, provider-notice date, review date, next review due, change summary, change class (editorial / coding only / criteria clarification / criteria expansion / criteria restriction / new PA requirement / PA removal), and a computed redline against the prior version.

**Section.** Typed rich-text blocks with a fixed vocabulary of section types (Purpose, Description, Coverage criteria prose, Limitations, Documentation requirements, Coding, Definitions, Evidence summary, References, Revision history, Member summary, Regulatory notes). Sections are rich text with controlled markup (headings, lists, tables, cross-references) stored as structured JSON or XHTML; never binary Word.

**Applicability.** One row per combination the policy applies to, with its own effective window and optional precedence note. Rows can express "applies to all Commercial products in MA and NH" or "does not apply to group X under ASO carve-out". A version is publishable only if at least one applicability row is active.

**CriteriaSource.** Records what the criteria are based on, whether the plan may republish it, and the external identifier and version (InterQual 2026 subset name, MCG 30th edition guideline code, NCD 220.6.17 version 3, LCD L33577 revision date). This is what lets the platform answer "which policies rely on an LCD that CMS just revised".

**CodeMapping.** One row per (concept, role) with effective dates and constraints. Roles: `IN_SCOPE_PROCEDURE`, `COVERED_DIAGNOSIS`, `NON_COVERED_DIAGNOSIS`, `INVESTIGATIONAL`, `NOT_COVERED`, `PA_REQUIRED`, `NOTIFICATION_ONLY`, `NEEDS_REVIEW_UNLISTED`, `DRUG_PRODUCT`. Constraints: site of service list, modifiers, age range, sex, quantity per period, lifetime maximum, diagnosis pairing. CodeMapping is the bridge between prose policy and every downstream system.

**CriteriaSet.** The digitized form of the coverage criteria: a logic tree whose leaves are either data assertions (LOINC/SNOMED/ICD/CPT membership, numeric comparisons with UCUM units, time-window checks) or questions (for subjective items). It compiles into a FHIR Questionnaire (SDC profile, with prepopulation expressions), a Library with CQL, ValueSets, a decision table (DMN or JSON) for the CRD/UM rule engines, and carries a suite of TestCases. Each node links to the paragraph of prose it implements.

**AuthRequirementRule.** The evaluable PA rule. Inputs: code set (references CodeMappings or value sets), applicability, conditions. Outcome: requirement type (`PA_REQUIRED`, `NOTIFICATION`, `NO_PA`, `NOT_COVERED`, `PA_IF_CRITERIA_UNMET`, `DELEGATED`), routing (internal UM queue or MHK auth type, delegated vendor id, pharmacy), channels (ePA, portal, fax, X12 278, vendor portal), turnaround class with the regulatory clock per LOB, documentation checklist, questionnaire canonical URL, reason category, gold-card eligibility, continuity rule.

**Publication.** One row per channel per version: channel (public site HPHC Commercial, public site THP Medicare, provider portal, Availity lookup, CRD rules, DTR package, MHK export, claims edits export, vendor feed, bulletin), payload hash, sent at, acknowledged at, reconciliation status and last reconciliation report.

### 5.2.2 Supporting entities

- **Concept / CodeSystemVersion / ValueSet** in the terminology store: code, display, licence class, active period, replaced-by. Loaded from CMS, AMA, FDA, CDC releases.
- **ExternalGuidelineRelease**: an NCD/LCD/Article or vendor guideline release with its diff and the policies it touches.
- **Committee / Meeting / AgendaItem / Vote / Minutes**.
- **Task / Assignment / SLA** for workflow.
- **Notice**: provider communication events with proof (bulletin issue, email batch id, portal banner window).
- **Request** (criteria-on-request log): who asked, for which decision, what was provided, when.
- **ImpactAnalysis**: snapshot of claims/auth volumes and cost for a proposed change (from the warehouse, de-identified).
- **User / Role / Group** from enterprise identity.
- **AuditEvent**: append-only.

### 5.2.3 Identifiers and canonical URLs

Every exported FHIR artefact carries a canonical URL under a plan-owned base, for example `https://policy.point32health.org/fhir/Questionnaire/MP-0123-lumbar-fusion|2027.1`. The version segment is the PolicyVersion label so that a DTR package, a CRD rule and a published web page can be matched to each other and to the approval record without a lookup table.

## 5.3 Lifecycle and workflow

```
 Intake ─▶ Research & Draft ─▶ Coding ─▶ Impact analysis ─▶ Clinical review ─▶ Legal/Compliance/Parity review
                                                                                        │
   ┌────────────────────────────────────────────────────────────────────────────────────┘
   ▼
 Committee (MPC / UM Committee / P&T) ─▶ Approved ─▶ Configuration hand-offs ─▶ Provider notice ─▶ Scheduled
                                                        (UM, ePA, claims, vendors, web)   (60/90 days)      │
                                                                                                             ▼
 Retired ◀── Under review (annual) ◀───────────────────────────────────────────────────────────── Active (effective date)
```

**Intake.** Triggers are recorded with a reason class: new technology or drug, code-set release, NCD/LCD change, vendor guideline update, regulatory change, appeals or denial trend, provider or member inquiry, annual review. The trigger stays attached to the version so the "why" survives.

**Research and draft.** Authors work in the structured editor with the template for the policy type. Evidence is captured as references with links to the source (Hayes/ECRI, PubMed, specialty guidelines, CMS). Licensed criteria are referenced, not pasted. The AI drafting assistant (optional, later phase) can propose a criteria tree and questions from the prose; its output enters as a draft with provenance and is never approved without human review.

**Coding.** A coding specialist builds the code table from the terminology store, sets roles and constraints, and runs the conflict check (overlaps with other policies, NCCI, deleted codes).

**Impact analysis.** The platform requests a de-identified extract from the warehouse: authorizations and claims in the last 12 months matching the proposed code set and applicability, with counts, spend and top providers. The analyst records assumptions and the estimate. For behavioral-health-touching changes, the parity (NQTL) questionnaire is completed.

**Clinical, legal and compliance reviews.** Parallel tasks with comments on sections. Compliance verifies the state-by-state notice period, the Medicare precedence check (is there an NCD/LCD, does the internal criterion add anything the rules allow), Medicaid guideline alignment, and the parity assessment.

**Committee.** The item is placed on the next agenda of the right committee. Packet generated (redline, code changes, impact, evidence). Votes and minutes recorded. Approval creates the immutable version.

**Configuration hand-offs.** The platform opens one task per consumer and generates the extract for it: MHK auth rules (or a reconciliation request when MHK is configured by hand), CRD rules and DTR package to the ePA platform, claims edits, delegated vendor notification, web publication job, notice content. Each task must be closed (with the consumer's acknowledgement where available) before the effective date, or the dashboard shows the version as "at risk".

**Provider notice.** The notice date is computed from the effective date and the notice policy per LOB and state. The bulletin text is generated from the change summary. Proof of notice is stored.

**Active.** On the effective date the publishing pipeline flips the public and API content automatically (no manual midnight job). The previous version becomes Superseded and stays in the archive.

**Annual review.** Each policy has a review due date; a review can end in "reaffirmed, no change" (which still creates a review record and a new approval date) or a new version. The compliance report lists every active policy with its last review date.

**Retirement.** A retirement is itself a versioned, noticed change. The archived version remains retrievable by date of service forever.

## 5.4 Authoring experience

- **Structured editor** with template-driven sections, controlled styles, tables for criteria and codes, inline cross-references to other policies and to code sets, and comments. Word import for the migration and for authors who draft offline, with a structure-recovery step that maps headings to section types and flags what it could not map.
- **Criteria builder** beside the prose: the author selects a paragraph and builds the logic tree for it; each leaf is either a data assertion (pick a concept, an operator and a value with unit) or a question (with answer type and options). The builder shows the compiled questionnaire preview and the test cases.
- **Code table editor** backed by terminology search, with bulk paste of code lists, range expansion, validity checks and the "policies already using this code" panel.
- **Applicability matrix** as a grid of brand × LOB × product × state with effective windows.
- **Redline view** for any two versions.
- **Checklist gate** before submission: required sections filled, evidence present, codes valid on the effective date, applicability set, notice date satisfiable, no licensed text in public sections.

## 5.5 Digitization method (prose to machine-readable)

1. **Normalise the prose.** Split criteria into atomic statements, one condition per line, with explicit connectors (all of, any of, none of). Ambiguous connectors are the first defect found in most legacy policies.
2. **Classify each statement.** Objective (evaluable from structured data: diagnosis, lab value, prior procedure, medication history, age, imaging finding coded), semi-objective (evaluable from documents but not from coded data: a report says "failed conservative therapy"), subjective (clinician attestation or judgement).
3. **Bind objective statements to terminology.** Choose the value set (existing or new), the operator and the unit. Record the FHIR resource type and the search that would find the data (Condition with code in value set X, Observation LOINC Y with value >= Z within 90 days).
4. **Write questions for semi-objective and subjective statements.** Prefer closed answers (yes/no, choice, quantity) that map back to the statement; free text only as a last resort and flagged as non-automatable.
5. **Compose the tree and the expressions.** The tree is the decision logic; each objective leaf gets a CQL expression for prepopulation; the questionnaire is generated with enableWhen logic derived from the tree.
6. **Author test cases.** For each policy at least: one case that satisfies, one that fails each top-level branch, one with missing data (should produce a question, not a denial).
7. **Review with a clinician and a UM nurse** against real (de-identified) cases from the last quarter: does the digitized version reach the same answer as the reviewer did.
8. **Approve, version, publish** the package alongside the policy version.
9. **Monitor.** Once live, capture how often the questionnaire is fully prepopulated, where users abandon, and where determinations were overturned on appeal, and feed that into the next review.

Known pitfalls that the model must handle explicitly: site-of-service rules, bundled and unlisted codes, modifier dependence, frequency and lifetime limits, age and sex constraints, drug policies with dosing and step therapy, exceptions "unless the treating physician documents...", and criteria that cite licensed content (InterQual/MCG) where only a reference may be published.

## 5.5a Machine-readable content model (what the compiler must emit)

The Da Vinci guides adopted for 1 October 2026 (CRD 2.2.1, DTR 2.2.0, PAS 2.2.1) and the HL7 Burden Reduction payer reference implementation (`br-payer`, 2026) define, in effect, the content model a payer must hold. The platform's CriteriaSet and AuthRequirementRule entities compile into the artefacts below. The guides' own conformance ids are quoted so an engineer can find the rule on the rendered page.

### The CRD answer

A CRD response is a CDS Hooks system action that stamps the `coverage-information` extension onto the order. The elements the rule table must be able to populate:

| Element | Values the platform must produce |
|---|---|
| `covered` (1..1) | covered, not-covered, conditional, indeterminate |
| `pa-needed` (0..1) | no-auth, auth-needed (with the "performer must initiate" flag), satisfied, conditional, indeterminate |
| `doc-needed`, `doc-purpose` | clinical / admin / patient; withpa / withclaim / withorder / retain-doc |
| `info-needed` | performer, location, timeframe, contract-window, detail-code |
| `billingCode` (0..*) | the CPT/HCPCS (with modifiers) the assertion assumes; multiple repetitions with disjoint scopes ("not covered if billed as A, PA required if billed as C, no PA if billed as E") |
| `detail` | limitation rows (quantity, period, network) and decisional rows (instructions) |
| `reason` | gold-card, no-member-found, no-active-coverage, coverage-not-found, or text |
| `questionnaire` | canonical(s) of the DTR form(s) to complete |
| `coverage-assertion-id`, `date`, `expiry-date`, `satisfied-pa-id` | the trace id that must accompany the eventual claim and be honoured at adjudication |

Invariants the rule model enforces before export: a questionnaire only when documentation is needed; not-covered implies no PA element; any "conditional" requires an info-needed; satisfied or no-auth implies documentation purpose is not "with PA"; satisfied requires the PA id; indeterminate requires a reason. Servers must answer within 5 seconds 90% of the time (10 seconds for appointment-book and cold order-sign), must be as accurate as a portal or phone answer, and **must retain every assertion and honour it when the claim is adjudicated**. That last rule is why the platform keeps an assertion log and why the claims-edit extract must carry the assertion and satisfied-PA identifiers.

Rules are keyed on **order codes** (SNOMED, RxNorm, LOINC, CPT/HCPCS without modifiers), not claim codes; the platform therefore holds an order-code to candidate-billing-code map with performer and location qualifiers, and records the assumptions it made in `billingCode` and `detail`. The reference implementation's unit of content is one **PlanDefinition per rule** with `useContext` for triggering codes and payer identifiers, `action.trigger` for the hook, and a Library whose CQL defines "Rule Applies", the summary and detail text, and the coverage-information values; the same PlanDefinition links the DTR questionnaire and drives the PAS evaluation (mapping outcomes to X12 review actions A1, A3, A4). The platform's compiler targets that shape whether the ePA vendor consumes PlanDefinitions directly or a flattened rule table.

### The DTR package

- Two form types: **standard** (full questionnaire with logic shipped to the client) and **adaptive** (`$next-question`, logic kept server-side). Adaptive is the standard's route for proprietary or licensed criteria that cannot be published; the guide's licensing note says question text and permitted answers must be exposed in a computable API, so licences with InterQual or MCG must cover that.
- Questionnaire rules the builder enforces: ask only what is needed with `enableWhen` or `enableWhenExpression`; expression logic is CQL; separate clinical and administrative questions and produce distinct questionnaires for distinct purposes; prefer attestation where discrete data will not exist and allow attachments where answers are insufficient; keep pre-populated items to about five seconds of review each; picklists answerable from a typeahead 90% of the time.
- Prepopulation: questionnaires must include logic that populates from the EHR where possible using US Core and HRex elements; every prepopulated answer carries an information-origin marker. Libraries carry both the raw CQL and compiled ELM; library names are unique; all referenced libraries ship in the package; value sets with 40 codes or fewer ship pre-expanded, larger ones are expanded by the client through `$expand`; oversized packages break SMART apps.
- Lifecycle: stable canonical URL per form, semantic version, status (draft, active, retired), `effectivePeriod`, publisher, `useContext` (codes, plan, jurisdiction); `$questionnaire-package` accepts version-specific canonicals and returns the current version otherwise; a QuestionnaireResponse must point at the same canonical; clients stop in-progress responses when the form has expired. The platform's version label is the questionnaire version.
- Package: one Bundle per questionnaire holding the Questionnaire, Libraries (CQL and ELM), ValueSets and a partially prepopulated QuestionnaireResponse; the package is validated with the HL7 validator against the DTR profiles and run through the Inferno DTR test kit as a CI gate.

### PAS and PDex crosswalks

- The PA rule's service catalogue must hold what a PAS Claim needs: CPT/HCPCS/NDC/revenue codes and code ranges, the X12 service-type category, certification type (initial, renewal, revised, cancel), level of service, place of service, and the documentation expectations expressed as attachment codes (LOINC attachment requests and X12 report types) or a QuestionnaireResponse.
- Every internal decision outcome needs a crosswalk to the X12 review action (A1 certified, A2 partial, A3 not certified, A4 pended, A6 modified, C cancelled), the X12 review decision reason (list 886, licensed content), and the plain-language denial text that goes in the response note and in the denial letter; modified authorisations carry the authorised item detail and period.
- The PDex Prior Authorization profile used for Patient, Provider and Payer-to-Payer access has **no element for a policy reference**; if the plan wants to expose which policy applied it needs a local extension or the process note. Denial reasons there are CARC/RARC codes, so the reason taxonomy maps to three vocabularies: X12 886, CARC/RARC, and plain language.

### Terminology and licensing rules built into the exports

- CPT descriptors are AMA-licensed content; public lists and APIs that include descriptors are a licensed distribution with required notices; codes plus plan-authored labels reduce exposure. X12 code lists are also licensed. HCPCS and ICD-10-CM are not freely distributable as FHIR CodeSystem content, so exports reference them by system URI rather than shipping the code system.
- Canonical system URIs: `http://www.ama-assn.org/go/cpt`, `http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets`, `http://hl7.org/fhir/sid/icd-10-cm`, `http://www.nlm.nih.gov/research/umls/rxnorm`, `http://snomed.info/sct`, `http://loinc.org`, `http://unitsofmeasure.org`.
- The Medicare Coverage Database provides NCD, LCD and Article datasets with code-group tables (the ICD-10 covered and non-covered lists and denial reasons sit on the Article, not the LCD); the platform imports them on release and diffs them.

### Artefact catalogue per consumer

| Consumer | Artefact | Key fields |
|---|---|---|
| CRD (ePA platform) | PlanDefinition per rule, Library (CQL + ELM), coverage-information template rows, order-to-billing code map, prefetch templates, card text (summary under 140 characters, links to the public policy) | canonical URL, version, status, effectivePeriod, useContext (codes, payer ids, jurisdiction), trigger hook, "Rule Applies" condition, relatedArtifact to the policy |
| DTR (ePA platform) | Questionnaire (standard or adaptive), ValueSets, Questionnaire Package Bundle | canonical URL, version, status, effectivePeriod, cqf-library, launchContext, items with linkId, type, answer options or value set, enableWhen, initialExpression, required, attestation flags |
| PAS (ePA platform, UM) | Service catalogue, documentation-requirement map, decision crosswalk | codes and ranges, X12 service type, certification type, level of service, place of service; attachment codes; outcome to A1/A3/A4/A6, X12 886 reason, note text |
| PDex (Patient, Provider, Payer-to-Payer APIs) | Prior Authorization EOB mapping | use=preauthorization, service type, product or service, CARC/RARC denial reason, review action, pre-auth period |
| UM system (MHK) | Auth rule and service group export | code, LOB, place of service, provider taxonomy, effective dates, review type, guideline or policy reference |
| Claims edits | Edit specifications | code, diagnosis, age, sex, frequency, modifier, site edits; PA-required flags; honouring of assertion and satisfied-PA ids |
| Portal and public site | PA lookup dataset and pages | code, licensed or plan-authored description, LOB, network, site, requirement, policy link, effective dates, notices |
| Governance | Policy record and artefact registry | policy id, version, status, dates, approvals, source documents, criterion-to-artefact traceability, validator results, publish state, package hash |

### Assertion log

Every CRD answer, DTR package served and PAS decision is logged with the assertion id, inputs, outputs, rule version, expiry and any satisfied-PA id. The log is what lets the plan honour assertions at claim time, reproduce an answer for an appeal, and measure prepopulation and abandonment rates per questionnaire.


## 5.6 PA-requirement evaluation service

A single stateless service answers one question, everywhere: **given a code (or codes), a date of service, applicability (brand, LOB, product, state, network), site of service, provider type and optional member attributes, what is required?** The response contains the requirement type, the routing, the channels, the turnaround class, the documentation checklist, the questionnaire canonical (if any), the policy references (id, version, public URL) and the rule id that fired. The same service backs:

- the public "Does this need prior authorization?" lookup,
- the provider portal and Availity lookups,
- the CRD coverage-information response assembled by the ePA platform (either the platform calls this service or receives a compiled rule table from it),
- the UM system's requirement check (as a compiled table or via call),
- the reconciliation reports.

The rule set is compiled per effective date into an immutable table, so an answer is reproducible for any past date (needed for appeals and audits).

## 5.7 Publishing pipeline

```
 Approved version ──▶ Render (HTML, PDF, JSON, FHIR) ──▶ Stage (preview URL for reviewers)
        │                                                        │
        │   effective date reached                               ▼
        └──────────────────────────────────▶ Release ──▶ Public sites (per brand/LOB)
                                                    ──▶ PA lookup API + downloads (CSV/JSON)
                                                    ──▶ Provider portals / Availity
                                                    ──▶ ePA (CRD rules, DTR packages)
                                                    ──▶ UM / claims / vendor extracts
                                                    ──▶ Bulletin + subscriptions + RSS
                                                    ──▶ Archive (retired versions, by date)
```

- **Rendering** is template-based per brand (Harvard Pilgrim and Tufts Health Plan house styles) and per audience (provider, member). The HTML is the canonical public form; the PDF is generated from the same source and stamped with id, version and effective dates.
- **Public delivery** has two workable patterns. (a) The corporate website CMS pulls from the platform's content API and renders inside its own templates (best when the web team owns look-and-feel and navigation). (b) The platform publishes a static, versioned site (HTML, search index, PDFs, downloads) to object storage behind the corporate CDN under a `policies.` path, and the corporate site links to it (fastest to deliver, simplest to make reproducible, no CMS work per policy). Recommendation: (b) for launch with (a) as an optional later integration; either way the corporate CMS never becomes a second copy of the content.
- **Scheduling**: the release step is driven by the effective date in the data, executed by the platform scheduler, and idempotent; a re-run publishes the same result.
- **Archive**: every released rendering is retained and addressable by version and by date of service (`/policies/MP-0123?asOf=2026-03-15`).
- **Machine-readable**: JSON and CSV downloads of the PA list and the policy-code map, with a published schema, version stamp and change log; FHIR endpoints for Questionnaire, Library, ValueSet and a plan-defined PA-requirement resource profile for partners.

## 5.8 Integration design per consumer

| Consumer | What it receives | Mechanism | Reconciliation |
|---|---|---|---|
| ePA platform (Onyx SAFHIR) | CRD coverage-requirement rules keyed by code/LOB/site/provider; DTR questionnaire packages (Questionnaire + Library + ValueSets); documentation checklists; policy public URLs for cards | Vendor-supported import (API or file) or the platform's PA-evaluation service called by the vendor at request time, whichever the vendor supports; versioned; effective-dated | Nightly comparison of the vendor's loaded rule set and packages against the platform's compiled table |
| UM system (MHK) | Auth requirement tables (code, LOB, site, requirement), auth types/service groups, criteria references (InterQual/MCG subset, internal policy id/version), documentation checklists | Export files or API into MHK configuration; where MHK is configured manually, a work-list plus a reconciliation extract | Weekly diff MHK configuration vs platform |
| Claims editing | Non-covered and diagnosis-restricted code rules, frequency limits, investigational codes, effective dates | Export into the claims edit vendor's custom-rule format | Weekly diff |
| Provider portals / Availity / NEHEN | PA lookup answers; policy links | REST call to the PA-evaluation service; JSON download for partners that cache | Sample checks; version stamp in the response |
| Delegated vendors | Notification of changes in their categories; the categories they own per LOB; the plan's policy where the plan's policy governs | Email/portal feed; vendor pages on the public site | Attestation on annual delegation oversight |
| Appeals & grievances | Policy version by date of service; criteria text; the rule that fired | REST call; deep links | n/a |
| Data warehouse | Full history of policy, code and rule tables | Nightly extract | n/a |
| Provider communications | Bulletin items; 60/90-day notices; RSS/email | Generated content; proof of publication stored | n/a |

## 5.9 Component architecture

```
 ┌────────────────────────────── Presentation ──────────────────────────────┐
 │ Authoring web app (internal, SSO)   │ Public policy site (static/SSR)   │
 │ committee & workflow screens        │ PA lookup UI                       │
 └───────────────┬─────────────────────┴───────────────┬────────────────────┘
                 │ REST/JSON                          │ REST/JSON (read-only, cached)
 ┌───────────────▼─────────────────────────────────────▼────────────────────┐
 │                       Policy Platform services (Java, Spring Boot)        │
 │  Policy & version service │ Terminology service │ Criteria compiler       │
 │  Workflow service         │ PA evaluation svc   │ Rendering & publishing  │
 │  Import/migration service │ Integration adapters│ Audit & reporting       │
 └───────┬───────────────┬───────────────┬────────────────────┬─────────────┘
         │               │               │                    │
   PostgreSQL       Object storage   Search index         Message bus / scheduler
   (content, rules, (renderings,     (full text, code    (publish jobs, events
    audit, versions)  archives)       lookup)              to TIBCO/consumers)
```

- **Policy & version service**: CRUD, versioning, applicability, code mappings, redline computation, checklist gates.
- **Terminology service**: code-system loads, value sets, validity, licence flags, FHIR terminology operations (`$validate-code`, `$expand`) for the compiler and the editor.
- **Criteria compiler**: logic tree to Questionnaire (SDC), Library (CQL), ValueSet, DMN/JSON decision table; runs test cases; validates FHIR output with the HL7 validator and the DTR package profiles.
- **Workflow service**: state machine, tasks, committees, SLAs, notifications. Can be a lightweight state machine in the application for phase 1 and an embedded BPMN engine when routing becomes complex.
- **PA evaluation service**: compiles rules to an immutable table per effective date; answers lookups; serves downloads.
- **Rendering & publishing**: templates per brand and audience, HTML and PDF, static site generation, release scheduler, archive.
- **Integration adapters**: one adapter per consumer (ePA, MHK, claims, vendor feeds) with explicit contract tests and reconciliation jobs. No consumer-specific logic leaks into the core.
- **Audit & reporting**: append-only audit, NCQA/MA evidence reports (annual review, IRR support), publication proof, criteria-on-request log.

## 5.10 Security, privacy and compliance controls

- No PHI in the platform; impact analysis uses aggregate or de-identified data from the warehouse; test cases are synthetic.
- Enterprise SSO (SAML/OIDC), role-based access (author, coder, reviewer, medical director, committee secretary, compliance, publisher, UM configuration, read-only, external reviewer), group membership from the identity provider.
- Approvals are signed events tied to the authenticated identity; approvals cannot be edited, only superseded.
- Public endpoints: anonymous, read-only, rate-limited, cached at the CDN; no admin surface exposed.
- Licence enforcement: CPT descriptors and licensed criteria text carry a licence class; renderers refuse to include licensed text in public outputs.
- Retention and legal hold: nothing is deleted; a legal hold flag blocks retirement-related archival moves.
- Change control of the rule compiler and templates: releases are versioned and recorded with each published artefact so an output can be regenerated bit-for-bit.

## 5.11 Operations

- **Runbooks**: code-set release day (quarterly), NCD/LCD release (as published), year-end effective-date wave, vendor guideline update, emergency policy change (safety recall), rollback of a publication.
- **Dashboards**: versions at risk of missing hand-offs before effective date, overdue reviews, reconciliation drift by consumer, public lookup errors, notice compliance by state.
- **Ownership**: a named product owner (Clinical Policy), a technical owner (Interoperability/Integration engineering), a content operations lead (coding and configuration), and a web publishing owner. Vendor content (digitization services, if bought) is delivered into this platform in its standard formats and accepted through the same checklist gate as internal work.
