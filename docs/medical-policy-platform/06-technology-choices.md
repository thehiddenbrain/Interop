# 6. Technology choices

The question asked was: what technology is needed, and does any of it have to be new to the organisation. Short answer: the platform can be built on the stack the interoperability team already runs (Java 17+, Spring Boot, HAPI FHIR, TIBCO integration, GitHub, the existing web CMS and identity provider). Four additions are justified and are called out below; everything else is optional and is listed with the reason it can wait.

## 6.1 What is already in place and reused

| Concern | Existing choice (from the ePA Workbench, Patient Access Workbench and CMS-1500 service) | Reuse in the policy platform |
|---|---|---|
| Language and runtime | Java 17 bytecode, Spring Boot 4.x (Spring Framework 7, Jackson 3) | All services |
| Build | Gradle 9 wrapper (newer projects), Maven wrapper (CMS-1500 service) | Gradle for the new multi-module project |
| FHIR | HAPI FHIR 8.x structures R4 and validation, HL7 validator with IG packages (`hl7.fhir.us.davinci-dtr`, `davinci-crd`, `davinci-pas`, `sdc`) | Questionnaire/Library/ValueSet generation and validation; terminology operations |
| Integration | TIBCO (BusinessWorks/EMS) as the enterprise integration layer; REST/JSON and SOAP contracts | Outbound extracts and events to UM, claims, portals |
| ePA platform | Onyx SAFHIR (CRD, DTR, PAS gateway) | Consumer of rules and questionnaire packages |
| UM system | MHK (MedHOK) | Consumer of auth requirement tables and criteria references |
| Provider channels | Availity Essentials, NEHEN (X12 278), provider portals | Consumers of the PA lookup service |
| Identity | Enterprise identity provider (SAML/OIDC SSO) | Authoring app and APIs |
| Source control and CI | GitHub, existing pipelines | Platform code, templates, IG packages, test data |
| Web presence | Corporate website CMS and CDN for harvardpilgrim.org / tuftshealthplan.com / point32health.org | Destination for published policy pages |

## 6.2 Additions that are justified (introduce these)

| # | Technology | Why it is needed | Alternatives considered |
|---|---|---|---|
| N1 | **Relational database with document features: PostgreSQL** (or the enterprise standard RDBMS if one is mandated; SQL Server and Oracle both carry JSON columns and full-text search) | The workbenches ran on files; a system of record with versioning, audit, concurrent authors, code tables of 100k+ rows and reproducible date-of-service queries needs a transactional database. JSON columns hold section content and criteria trees; relational tables hold codes, applicability, rules and audit. | Document database (MongoDB): weaker for the relational code/rule queries and reconciliation. Files only: fails CM-11/CM-12/NF-10. |
| N2 | **Embedded workflow engine: Flowable (open source, Apache 2) or Camunda 8** | The lifecycle has parallel reviews, committees, SLAs and hand-off tasks. A BPMN engine gives configurable routing, task lists, timers and history without hand-written state machines per policy type. Start with a simple state machine in phase 1 if the team prefers; move to the engine when committee routing goes live. | ServiceNow or Jira for tasks: keeps the state outside the content and duplicates the audit. Custom state machine: fine for phase 1, expensive by phase 3. |
| N3 | **Terminology server: HAPI FHIR JPA server used as a terminology service** (CodeSystem, ValueSet, `$validate-code`, `$expand`), loaded from CMS/CDC/FDA releases and the licensed AMA CPT distribution | The criteria compiler, the code editor and the exports all need one validated source of codes with versions and licence flags. HAPI is already a dependency; running it as a service is the smallest step. | Ontoserver (commercial, excellent for SNOMED), Snowstorm (SNOMED only), VSAC (US, needs UMLS licence, not a runtime service for CPT). A plain SQL code table works for phase 1 but every downstream FHIR artefact needs ValueSet resources anyway. |
| N4 | **Structured rich-text editor and authoring front end: a component-based SPA (React or Angular per the enterprise web standard) with a ProseMirror/TipTap-class editor** | Authoring with templates, tracked sections, comments, redlines and a criteria builder is beyond the vanilla-JS/Thymeleaf pages used in the workbenches. The editor must store structured content (JSON), not HTML blobs from Word. | Word plus SharePoint: keeps documents unstructured (fails the core design rule). Confluence/wiki: no structured sections or code tables. Headless CMS: good at pages, weak at code tables and rules. |

## 6.3 Optional (later phases or only if a need is proven)

| Technology | Use | When |
|---|---|---|
| CQL toolchain: the open-source CQL-to-ELM translator and engine (`cqframework/clinical_quality_language`), `cqf-tooling` for value-set and library generation and bundling, and the HAPI Clinical Reasoning module (the `cqf-ruler` lineage now in `hapi-fhir-jpaserver-starter`) for `$apply` and CDS Hooks | Prepopulation expressions in DTR Libraries (CQL plus compiled ELM, both required in the package); PlanDefinition evaluation for CRD; running test cases against synthetic FHIR data | Phase 2 (digitized criteria). Until then, questionnaires can ship static or with FHIRPath expressions only. |
| HL7 conformance gates: the HL7 validator with the CRD 2.2.1, DTR 2.2.0, PAS 2.2.1 and SDC packages, and the ONC Inferno test kits for CRD, DTR and PAS, run in CI on every generated package | Proves every exported artefact conforms before it reaches the ePA platform | Phase 1 for the validator (the ePA workbench already does this for PAS), phase 2 for Inferno. |
| HL7 Da Vinci `br-payer` reference implementation (2026, HAPI-based; CRD hooks, `$questionnaire-package`, `$next-question`, PAS submit and inquire) | A local, standards-exact target to test compiled content against before the vendor sandbox; also the model for the PlanDefinition + Library + Questionnaire content shape | Phase 2, as a test harness, not as production. |
| DMN decision tables (Flowable/Camunda DMN engine) | A second, human-readable form of PA rules and criteria logic for the UM team; exportable to rule vendors | Phase 2. Phase 1 uses compiled JSON rule tables. |
| Search engine (OpenSearch/Elasticsearch) | Public search with synonyms, faceting and typo tolerance | Only if PostgreSQL full-text search proves insufficient at launch. |
| Object storage plus CDN for the static public site (Azure Blob + Front Door or S3 + CloudFront, per enterprise cloud) | Reproducible, versioned public publication independent of the corporate CMS | Phase 1 if the corporate CMS cannot consume an API by the launch date; otherwise phase 2. |
| PDF rendering (OpenHTMLtoPDF or Flying Saucer, both Java; or a headless Chromium in the pipeline) | Branded PDFs from the same HTML | Phase 1 (small). |
| Message broker events (TIBCO EMS already; Kafka only if the enterprise has it) | "Policy published/retired" events for subscribers | Phase 2. |
| Large-language-model assistance (Azure OpenAI or Anthropic via an enterprise agreement, private endpoint) | Draft criteria trees, question wording, plain-language member summaries, change-summary drafts, migration structure recovery | Phase 3, human-reviewed, never on the approval path, no PHI. |
| Analytics (existing warehouse, Power BI/Tableau) | Impact analysis, PA metrics, denial and appeal linkage | Phase 2 through the warehouse feed. |
| Observability (Micrometer + Prometheus/Grafana or Azure Monitor/App Insights) | Reconciliation and publication dashboards | Phase 1 (use what the enterprise runs). |

## 6.4 Things not to introduce

- A second copy of the policy content anywhere (a vendor "policy database" that is also authoritative, a SharePoint library that authors keep editing, a CMS that stores the text). One system of record, everything else is a consumer.
- A proprietary rules language that cannot be exported. Rules must round-trip to open forms (JSON tables, DMN, CQL, FHIR Questionnaire).
- Licensed criteria text inside our own database beyond what the licence allows; store references and let the licensed tools serve the text.
- Machine-learning models on the approval path. Deterministic rules and human approval only; AI helps draft.

## 6.5 If a vendor product is bought instead

The same table becomes the acceptance checklist. Whatever is bought must:

1. Export the complete content (policies, versions, codes, applicability, rules, questionnaires, audit) in open formats at any time (JSON, HTML, FHIR, CSV) so the plan owns its content.
2. Publish FHIR artefacts with plan-owned canonical URLs and versions that match the plan's policy version labels.
3. Provide an authenticated API for the PA-requirement evaluation so portals, Availity, the ePA platform and the UM system can call one answer.
4. Integrate with the enterprise identity provider (SSO, groups) and produce an immutable audit trail exportable for NCQA and CMS audits.
5. Support white-label public publishing on the plan's own domains, or a content API the corporate site can consume; the vendor's own hosted site is acceptable only with the plan's domain, branding, accessibility conformance and an archive.
6. Hold a current SOC 2 Type II (or HITRUST) report and sign a BAA even though the platform is designed to hold no PHI (test data and reviewer comments drift into PHI in practice).
7. Provide the reconciliation extracts described in section 5.8 or allow the plan to build them against its API.

## 6.6 Skills the team needs

Clinical policy analysts and coding specialists (content); a UM configuration analyst who knows MHK; one FHIR/CQL engineer (the workbench team already has the FHIR skill); two to three Java/Spring engineers; one front-end engineer for the authoring app; a web publishing owner from the digital team; a compliance partner for state notice rules and Medicare precedence; and, if digitization is outsourced, a clinical reviewer to accept the vendor's output.
