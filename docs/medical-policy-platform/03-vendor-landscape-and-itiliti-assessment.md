# 3. Vendor landscape and the Itiliti Health assessment

This section answers three questions. What does Itiliti Health actually offer, and which of its claims are backed by evidence outside its own press releases? Who else offers the same or adjacent capabilities? And where, regardless of vendor, does the plan still need its own database?

Research method and its limits: about 130 web searches were run across the vendor, its partners, competitors, investors, standards bodies and trade press. Direct page fetches were blocked by the network policy of the research environment, so findings rest on search-engine-returned page content rather than full page reads. Everything below is tagged: **evidenced** (stated by a source other than the vendor, or by a named customer), **vendor-stated** (only the vendor or its investors say it), **unverified** (single secondary source or aggregator data). No independent evaluation of Itiliti by KLAS, Black Book, Gartner, a peer-reviewed study or investigative trade press was found.

## 3.1 How the market splits

No single vendor covers the whole chain (author, approve, version and effective-date, map codes, determine PA requirement per LOB and product, digitize criteria for CRD/DTR, feed UM, publish to the public website). The market has four layers, and a buyer normally assembles at least three of them:

| Layer | What it sells | Vendors | What it does not do |
|---|---|---|---|
| A. Licensed criteria content | Evidence-based clinical indications for the bulk of high-volume services; increasingly delivered over FHIR | MCG (Cite, Cite AutoAuth, MCG Transparency, MCG Path), InterQual/Optum (Connect, AutoReview, Transparency, Exchange, Auth Accelerator), Carelon, EviCore, Evolent guidelines; evidence from symplr Evidence Analysis (ex-Hayes) and ECRI | Author or govern the plan's own policies; determine PA requirements per LOB; publish the plan's non-licensed policies (Transparency products host them, they do not author them) |
| B. Policy digitization and PA-requirement engines | Turn the plan's policy documents into structured rules, PA lookups and DTR questionnaires or CQL | Itiliti Health (PA Checkpoint, Policy Management, Auto Auth, Clinical Decision Assistant), Cohere Policy Studio (Dec 2025), Availity Intelligent UM (ex-Olive AuthAI), ZeOmega MG Digitizer, InterQual custom-policy conversion | Be the committee-governed authoring system of record (claims vary; see matrix); own the public website; integrate natively with MHK (none evidenced except MCG Path) |
| C. UM workflow platforms | Execute rules, route cases, embed AI review | MHK CareProminence (incumbent), HealthEdge GuidingCare, ZeOmega Jiva, Zyter TruCare, Cohere Unify; AI reviewers Anterior, InterQual Auth Accelerator | Author policies, version them, publish them |
| D. FHIR plumbing | CRD hooks, DTR `$questionnaire-package`, PAS endpoints, connections to Epic Payer Platform, Availity, Waystar | Onyx OnyxOS/OnyxEPA (incumbent ePA), 1upHealth, Smile Digital Health, Firely, Health Samurai Aidbox/Payerbox, Edifecs | Supply the content; all of them name a content partner (Onyx names Itiliti and Availity; Smile names MCG; 1up names MHK and Evolent) |

The practical consequence: the "medical policy database" the organisation is asking for sits mostly in layer B plus the governance and publishing functions that layer B vendors only partly cover. Whatever is bought, the plan still owns the master crosswalk (policy to code to product to PA flag to delegate), the committee record, the medical-benefit drug policies, the NCD/LCD ingestion for Medicare lines, and the public rendering on point32health.org.

## 3.2 Itiliti Health: profile

| Item | Finding | Tag |
|---|---|---|
| Company | Itiliti Health, Inc., Eden Prairie, Minnesota. Founded 2019 by Michael Lunzer and Kurt Hulander; idea incubated during Lunzer's entrepreneur-in-residence period at Blue Cross Blue Shield of Minnesota, and the company was co-developed with BCBSMN. | evidenced (MedCity News 2020, Becker's) |
| Leadership change | 28 July 2026: Kevin Aniskovich hired as CEO (previously Jumo Health, Sharecare, Remedy Health, Epocrates), Cindy Hommer as Chief Revenue Officer (previously MCG, MRIoA, Mercer, UnitedHealth); Lunzer becomes President for product and strategy. Framed as preparation for growth. | evidenced (press release); interpretation open |
| Size | Headcount reported between 13 and 25 depending on the aggregator; three Glassdoor reviews; revenue about $2.6M per one aggregator. | unverified |
| Funding | About $5.5M to $7M total across small rounds: a $2M seed led by Bread and Butter Ventures and Altitude Ventures with M25, SpringTime and Groove; a $574K extension (reported for early 2025 or March 2026). Some aggregators list HealthQuest Capital and In-Q-Tel, which is unusual for this round size. One early investor (M25) lists the company under "Exit" with no announced transaction. | unverified; ask |
| Security certifications | No SOC 2 or HITRUST statement found anywhere public. | not evidenced; must demand |
| Pricing | Not published. No cloud marketplace listing found for Itiliti (Onyx has one). | not evidenced |
| Standards community | Lunzer is co-chair of the WEDI Prior Authorization sub-workgroup (Aug 2025); frequent WEDI webinars (most sponsored by Itiliti); HL7 Da Vinci community roundtable case study with BCBSA (June 2026); HIMSS 2026 exhibitor; Epic CRD demonstration at an HL7 Connectathon (vendor blog April 2026). | evidenced for participation |

### Products, as the vendor names them

| Module | What it does (vendor description) | Maturity signal |
|---|---|---|
| PA Checkpoint ("Is auth required?" and "Policy transparency") | A structured database of PA rules and policy metadata. Three or four inputs (plan/product, code, setting) return a definitive PA-required answer with the detailed policy requirements. API interfaces for the CRD transaction, the plan website, the provider portal and other systems. Conceived with BCBSMN. | The mature core. A vendor-hosted public site exists (`mypolicies.itilitihealth.us`) with URLs carrying a policy id, a version segment and a `lob=` parameter, at least for BCBS Alabama, which is evidence of per-LOB versioned public publishing on the vendor's domain. White-labelling on the plan's domain, accessibility conformance and archive: not evidenced. |
| Policy Management | "Creating, maintaining and sharing policies": intelligent import of existing policies, document creation and update features; sharing externally through a DTR interface and APIs "in full form or deconstructed form". | Thin public detail. No evidence of committee routing, e-signature approvals, redline, effective-dating per LOB, audit trail, or NCD/LCD linkage. Must be demonstrated. |
| Prior Authorization Routing | Routes submissions to the right queue, reviewer or delegate. | Almost no public detail; delegated-vendor routing not evidenced. |
| Auto Auth (automatic authorization rules) | Deterministic approvals when records meet governed criteria; otherwise to a clinical reviewer; "no automatic denials". | Design claim consistent with regulator and AMA sentiment; outcomes unverified. |
| Clinical Decision Assistant (successor to PA Complete) | AI-assisted reviewer support: tags evidence in medical-record documents against each criterion, side-by-side view, optional auto-approve on match. | Model provenance and validation not disclosed; Jan 2026 partnership with Concord Technologies suggests document ingestion is partly partner-supplied. |
| CMS-0057 / Da Vinci solution | FHIR-native CRD, DTR, PAS, phased adoption. | Live at a Blues plan per the vendor (portal-mediated; see claims table). No published CapabilityStatements, Inferno/Touchstone results or questionnaire packages found. |

### How digitization is done

The vendor states it does **not** use AI for digitization; it uses a rules-based method that preserves the original policy language and converts it into structured, machine- and human-readable form, working directly with the plan to extract the policy language. Read this as a vendor-performed, tool-assisted manual digitization service with plan validation. Who performs it (clinicians, nurses, coders), throughput per policy, error rate, and how re-digitization happens on each policy update: not evidenced. A consulting partner (Impresiv Health) offers "medical policy optimization services", which suggests part of the labour is outsourced.

Output formats: codes plus criteria per policy per LOB (implied); FHIR Questionnaires aligned to the Da Vinci DTR guide (evidenced through the BCBSA engagement); "full form or deconstructed form" via API. Whether Library/CQL resources for prepopulation are shipped, or only static questionnaires: not evidenced. X12 278 mapping: not mentioned anywhere.

### Customers and references

| Customer | Evidence | Confidence |
|---|---|---|
| Blue Cross Blue Shield of Minnesota | Origin and co-development partner; joint webinar with Mayo Clinic; BCBSMN runs a public PA lookup tool (whether it runs on Itiliti today is unverified). | High that the relationship existed; current status unverified |
| Blue Cross Blue Shield of Wyoming | Becker's Payer coverage of a PA Checkpoint partnership (about 2020 to 2021). | High |
| Blue Cross Blue Shield of Alabama | WEDI webinar on BCBSAL's path to CMS-0057 compliance with Itiliti, all three transactions implemented ahead of 2027; public Itiliti-hosted policy pages with `lob=BCBS AL`. Almost certainly the "large regional Blues payer" of the October 2025 release. | High that BCBSAL is live |
| Blue Cross Blue Shield Association | HL7 blog and Da Vinci roundtable (June 2026): BCBSA digitizing centrally authored reference medical policies with Itiliti and producing standardized DTR questionnaires for member plans. | High, and the most strategically important reference, but it accrues to Blues licensees of the BCBSA Medical Policy Reference Manual. Point32Health is not a Blue plan and cannot use those reference policies or questionnaires. |
| PrimeWest Health, South Country Health Alliance | Minnesota county-based purchasing Medicaid plans announced December 2025 and January 2026. | Medium (vendor releases) |
| "Large regional health plan" (80% automation), "one of the nation's largest BCBS plans" (650 policies, four LOBs) | Vendor releases; plans unnamed. | Vendor-stated |
| Any non-Blues commercial plan, any Medicare-Advantage-only plan, any state Medicaid agency | None named anywhere. | Not evidenced |

### Partnerships that matter for Point32Health

- **Onyx**: "OnyxOS Connector" with Itiliti (May 2025): Onyx supplies the FHIR transport (CRD hook, DTR, PAS endpoints), Itiliti supplies the rules and questionnaire content; joint WEDI live demo. This is the concrete reason leadership's lean makes sense: the incumbent ePA vendor has a published, demonstrated pairing with Itiliti and no native rules or questionnaire authoring of its own. Availity also has an Onyx pairing (August 2025) covering CMS-0057 compliance.
- **MHK**: no Itiliti integration found. MHK CareProminence has a certified MCG Path integration (CRD/DTR/PAS for MCG content) and is on the 1upHealth network; plan-authored policies reach MHK today only through manual configuration or a rules push that a vendor would have to build.
- **VirtualHealth HELIOS** (joint UM bundle), **Concord Technologies** (document processing), **Impresiv Health** (services). No relationship found with MCG, InterQual, Edifecs, Cohere, GuidingCare, TruCare, Jiva, Epic (beyond a Connectathon demo), or Cerner/Oracle.

## 3.3 Claims versus evidence

| Vendor claim | What was found | Verdict |
|---|---|---|
| PA Checkpoint cuts PA submissions 30%, call volume 20%, handle time 15% | Repeated verbatim in releases since about 2020 (BCBS Wyoming, VirtualHealth, Health Plan Alliance); apparently from the original BCBSMN pilot; no customer-attributed quote, methodology or independent study. | Vendor-stated, stale, unverified |
| "Automates 80% of prior authorizations" at a large regional plan (Oct 2024) | Single release; plan unnamed; denominator undefined (all submissions, or a subset of codes?). | Unverified |
| 650+ policies digitized across four LOBs with carve-outs and custom rules at a very large Blues plan (May 2025) | Release and investor blog; plan unnamed; no timeframe, cost or accuracy data; consistent with the BCBSA and BCBSAL activity. | Plausible, unverified |
| "First in the nation to achieve full CMS-0057 compliance at scale" (Oct 2025) | The same release says the transactions "are currently managed through a portal submission process" and are "prepared for integration via API with EMR platforms when vendors are ready." | Overstated: portal-mediated CRD/DTR/PAS is not EHR-integrated ePA; "first" is unverifiable marketing |
| FHIR-native CRD/DTR/PAS | BCBSAL webinar, Epic Connectathon demo, Onyx connector. No CapabilityStatement, test results or published packages found. | Partly evidenced; demand conformance proof |
| Epic connectivity | Connectathon demonstration only (vendor blog, April 2026). | Demo, not production |
| Rules-based digitization with no AI, preserving policy language | Vendor blog; consistent with the product design and with the BCBSA questionnaire work. | Credible design claim; verify in a demo with our policies |
| Works with BUCA plans, TPAs, delegated vendors and state Medicaid agencies | Only Blues plans, BCBSA and two Minnesota county Medicaid plans are named. | Unverified beyond Blues and Minnesota |
| Solutions "meet CMS-0057 requirements" | Itiliti covers only the Prior Authorization API content and transactions; Patient Access, Provider Access and Payer-to-Payer come from partners such as Onyx. | Partial |
| Security posture | No SOC 2 Type II or HITRUST statement found. | Not evidenced |

**Is what they are saying correct?** The core capability (turning policies into a structured PA-requirement database with a public lookup and CRD/DTR outputs) is real and has been done for Blues plans. The outcome numbers are marketing and should be given no weight until a named customer confirms them with a baseline. "Full compliance" and "first in the nation" are overstated. The functions Point32Health needs that are not evidenced at all: committee and approval workflow with audit trail, effective-dating per LOB with redline and archive, Medicare NCD/LCD precedence handling for Medicare Advantage and SCO, MassHealth-specific rules, medical-benefit drug policies, delegated-vendor routing, MHK integration, X12 278 mapping, PA metrics reporting, and a security attestation. They may exist; the vendor has not shown them, and the company's size means each of them is a capacity question as much as a feature question.

## 3.4 Competitor profiles (short form)

**Cohere Health, Policy Studio (launched December 2025).** The closest functional overlap with Itiliti and far better funded ($200M raised, $90M Series C in 2025; customers Humana, Geisinger, Medical Mutual, HealthPartners; Epic Payer Platform integration). Claims a centralised policy lifecycle hub: AI conversion of PDFs to structured criteria, workflow management, automatic version tracking, decision linkage to the source policy, and generation of FHIR resources for DTR including JSON and CQL with documentation requirements; digitizes NCDs, LCDs and proprietary policies; a 2026 AWS write-up describes the agentic digitization architecture with human oversight. Weaknesses: under a year old; strongest when Cohere Unify is also the UM engine (Point32Health runs MHK); public-site publishing not evidenced. Evidence quality medium-high.

**Availity Intelligent UM (ex-Olive AuthAI) and Availity + Onyx.** Imports and codifies payer medical policies into CQL decision trees that "show their work"; 1.1M authorizations processed Jan 2024 to Mar 2025 in imaging, cardiology and MSK with 76% recommended for approval in under 20 seconds; customers are unnamed Blues plans; partnership with Onyx for CMS-0057 compliance (Aug 2025). Availity Essentials is the portal Point32Health is adopting from late 2026, which makes Availity a natural channel for the PA lookup. Weaknesses: not a policy authoring system of record; no committee workflow; codification concentrated in high-volume specialties; transparency delivered inside the portal, not on the public site.

**ZeOmega, MG Digitizer and Smart Authorization Gateway.** Converts PDF policies into assessments and structured decision trees (60 to 80% less manual conversion, vendor-stated); tied to the Jiva UM platform. Direct overlap on digitization; irrelevant unless MHK is replaced.

**MCG (Hearst).** Cite and Cite AutoAuth for guideline-based review and provider auto-authorization; MCG Transparency hosts selected guidelines and "internal policy elements" for transparency laws (live at Blue Cross NC and Fidelis Care); MCG Path (2025) delivers MCG content over FHIR CRD/DTR/PAS and has a certified MHK CareProminence integration and a Smile partnership. No general-purpose authoring or committee tool for plan policies was found (product names such as "Cite Policy Management" did not surface). Point32Health does not publicly reference MCG today.

**InterQual (Optum).** Connect, AutoReview, Transparency (public read-only access to InterQual criteria and custom policies from the plan's website and portals), Exchange (digitized InterQual and custom policies over FHIR APIs, centralised policy storage), a content-customisation tool, third-party conversion of custom content, and the 2026 Auth Accelerator (AI documentation conversion, "no automated denials"). Point32Health already uses InterQual for some MNGs, so Transparency and Exchange are relevant for the licensed subset. Weaknesses: custom-policy digitization is a services conversion, not self-service authoring; Optum/UnitedHealth optics; per-LOB PA determination not visible.

**MHK CareProminence (incumbent UM).** Holds executable business rules, decision trees, pharmacy and medical policy references and auto-approval logic inside the workflow; MHK Interop Suite for FHIR; certified MCG Path integration; on the 1upHealth network. It can consume digitized policy; nothing found suggests it offers a policy library with authoring, committee approval, versioning, code crosswalk or public publishing. Any chosen vendor must integrate with it, and only MCG and 1upHealth have public MHK integrations.

**Onyx (incumbent ePA).** FHIR interoperability platform for CMS-0057 with OnyxEPA for CDS Hooks, DTR and PAS. No native rules or questionnaire authoring studio was found; Onyx's model is to pair with a content partner (Itiliti, Availity).

**Other FHIR platforms and authoring tools.** 1upHealth (all four CMS-0057 APIs deployed to its base by May 2026; MHK and Evolent on its network), Smile Digital Health (the most CQL-capable platform; Clinical Reasoning module; MCG Path partner), Firely (CRD/PAS/DTR support), Health Samurai (Aidbox Forms supports DTR `$questionnaire-package` with prepopulation; Formbox is a no-code SDC questionnaire builder; Payerbox covers CRD/DTR/PAS), NLM Form Builder and LHC-Forms (free SDC questionnaire authoring), the HL7 US Common CQL Artifacts guide and the Da Vinci `br-payer` reference implementation. These are the building blocks for an in-house digitization capability; none replaces clinical authorship.

**Delegated criteria vendors already in use.** Carelon (genetic and molecular testing; public guidelines with annual cycles), Evolent (imaging and sleep for Harvard Pilgrim members; RadMD), eviCore (Tufts products; intelliPath provider-side ePA live with 260+ provider systems), OncoHealth (oncology drug policies). They own content for their carve-outs and answer "is PA required" for them; the plan's platform must carry the category-to-vendor-to-LOB map and link to their criteria.

**Evidence services.** symplr Evidence Analysis (ex-Hayes) and ECRI Clinical Evidence Assessment for Payers are inputs to authoring, not databases.

**Reference databases.** Policy Reporter (now under Valeris) and MMIT track other payers' policies for manufacturers and providers; useful for benchmarking, not authoring. The BCBSA Medical Policy Reference Manual is Blues-only. The CMS Medicare Coverage Database offers weekly NCD/LCD/Article downloads and a REST API with HCPCS and ICD-10 lookups, which any Medicare-line policy database must ingest.

**Hospital policy document tools** (symplr Policy, RLDatix PolicyStat, MCN Policy Manager, HealthStream Policy Manager, NAVEX PolicyTech). Good at approval routing, attestation and version control; no code mapping, no PA logic, no CRD/DTR output, no provider-site API. Some plans use them for committee flow and re-key to the website, which is exactly the gap this platform closes.

**AI review layers** (Anterior, InterQual Auth Accelerator, Humata now being acquired by R1, Sagility Nurse Assist). They consume the plan's policies; they are not a policy database, and none should sit on the approval path.

## 3.5 Comparison matrix

Legend: ✔ evidenced; partial = limited, via partner or services; ✘ not offered; ? unknown or not disclosed.

| Vendor | Author and committee workflow | Versioning, effective dating | Code mapping | Multi-LOB PA determination | CRD/DTR/CQL | UM integration (MHK) | Public site publishing | Provider transparency | Live customers | Pricing | Company risk |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Itiliti Health | partial ? | partial ? | ✔ | ✔ | ✔ (portal-mediated live) | partial (Onyx, HELIOS; MHK ✘) | ✔ (vendor-hosted; white-label ?) | ✔ | ✔ Blues, MN Medicaid | SaaS, undisclosed | High |
| Cohere Policy Studio / Unify | ✔ claimed | ✔ claimed | partial | partial | ✔ (JSON, CQL) | ✔ via Unify/APIs; MHK ? | ? | partial | ✔ | Enterprise, undisclosed | Low to medium |
| Availity Intelligent UM (+Onyx) | ✘ | ? | partial | partial | ✔ (CQL) | ✔ payer-side; MHK ? | ✘ (portal) | ✔ | ✔ | Transaction/enterprise | Low |
| MCG Transparency / Path / AutoAuth | ✘ (MCG content only) | partial | partial | ✘ | ✔ (MCG content) | ✔ (MHK certified) | partial (hosted) | ✔ | ✔ | Licence | Low |
| InterQual Exchange / Transparency / Auth Accelerator | partial | partial | partial | ✘ | ✔ (incl. custom policies) | ✔ (Connect); MHK ? | ✔ (read-only incl. custom) | ✔ | ✔ | Licence | Low |
| ZeOmega MG Digitizer / Jiva | ✘ | ? | partial | partial | ✔ | Jiva only | ✘ | partial | ✔ | Services + licence | Low to medium |
| MHK CareProminence | ✘ | ? | partial | partial | partial (MCG Path, 1up) | native | ✘ | ✘ | ✔ (P32H) | Existing | Low to medium |
| Onyx OnyxOS/OnyxEPA | ✘ | ✘ | ✘ | ✘ | ✔ transport only | partial | ✘ | ✘ | ✔ | Licence | Medium |
| 1upHealth / Smile / Firely / Aidbox | ✘ (form builders) | partial | ✘ | ✘ | ✔ | partial | ✘ | ✘ | ✔ | Licence / free | Low to medium |
| Hospital P&P tools | ✔ | ✔ | ✘ | ✘ | ✘ | ✘ | partial | ✘ | ✔ | Subscription | Low |
| In-house platform (section 5) | ✔ by design | ✔ by design | ✔ | ✔ | ✔ via compiler; questionnaires from open tooling | ✔ adapters built to MHK | ✔ | ✔ | n/a | Build cost | Delivery risk |

## 3.6 Where the plan still needs its own database, whichever vendor is chosen

1. The governance system of record: committee minutes, approvals, effective and retirement dates per LOB, redlines, the regulator-facing audit trail across Commercial, Medicare Advantage, Medicaid, SCO and Marketplace.
2. The master crosswalk of policy to code to product/benefit to PA flag to delegate (Carelon, Evolent, eviCore, OncoHealth carve-outs) that feeds MHK, the website, Availity, HPHConnect and CRD identically.
3. Medical-benefit drug policies, which CMS-0062-P proposes to bring into the same ePA pipeline.
4. Ingestion of the CMS Medicare Coverage Database feed for Medicare Advantage and SCO precedence, and of state Medicaid guidelines.
5. The public rendering on point32health.org, which every vendor treats as an embed or an API rather than something they own.

## 3.7 Verdict on the lean towards Itiliti Health

- **What is right about it.** Itiliti's core product is the closest thing on the market to the PA-rules database with a public lookup and CRD output that the ePA programme needs, it has a published connector with Onyx (the incumbent ePA vendor), it uses deterministic rules rather than an opaque model, and its BCBSA work shows it can produce DTR questionnaires at scale.
- **What is not yet proven.** Everything in the governance, multi-regime and integration space: committee workflow, effective-dating and archive, Medicare precedence, MassHealth, drug policies, delegated routing, MHK integration, security attestation, and the company's capacity to onboard a five-LOB plan while every other customer hits the same January 2027 date. The outcome numbers should be treated as unproven.
- **What to do.** Do not select on the lean. Run a structured evaluation (section 7) in which Itiliti, Cohere Policy Studio and an "in-house system of record plus vendor digitization" option are scored against the requirements in section 4, with a paid proof-of-concept on five to ten real Point32Health policies (one Commercial, one Medicare Advantage with an NCD/LCD interaction, one MassHealth, one medical-benefit drug, one with a delegated carve-out). Whichever way it goes, the plan must own the content in open formats and the crosswalk must live in one place.

Questions to put to Itiliti (and to Cohere) in the demo, and the proof to demand, are listed in section 7.6.
