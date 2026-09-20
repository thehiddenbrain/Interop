# 11 – HPHC Business Rules and Regulatory Context that Shape the Bridge

All items below come from public Point32Health/HPHC pages, state statutes and CMS material as
surfaced by search-engine summaries (direct fetch was blocked); verify each against the source
before encoding it. URLs are in `10-sources.md`.

## 11.1 The three HPHC processes the 278 must carry

| Process | When | Timing rule (commercial) | 278 / PAS consequence |
|---|---|---|---|
| **Referral** (PCP → specialist) | HMO and POS products; not Open Access HMO/POS; self-referral allowed for routine eye exam and routine OB/GYN | Standing referrals valid 364 days (unverified); claim denied if service rendered without a referral on file | UM01 = SC; scope/visit count must travel (HSD visits); authorization period → DTP*AAH / `preAuthPeriod` |
| **Notification** (event registration, no medical-necessity decision) | Elective inpatient / surgical day care: ≥ 1 week before; urgent/emergent admissions: within 2 business days; behavioral health acute admissions: within 72 h (MA facilities) / 2 business days (other states); home health: first 30 days | Late or missing notification → administrative claim denial (no medical-necessity appeal) | Needs an agreed 278 representation (UM01 = AR + notification semantics) and a ClaimResponse outcome that does not claim "approved" (NA / A1 + processNote) – open question Q21 |
| **Prior authorization** | Elective, non-urgent services on the PA list; selected drugs, procedures, items; home health after 30 days; PT/OT beyond benefit limit; outpatient hospice/palliative | "Review is completed within two business days after receipt of medical information"; changes to date or type of service must be re-notified | UM01 = HS; UM02 = I/S/4/3; pended flow common when documentation is needed |

Other rules recovered:

* Retroactive authorization generally not permitted except medical emergency; services ordered
  outside business hours → contact HPHC next business day for retrospective authorization.
* Phone was discontinued for HPHC commercial medical authorization/notification/referral requests
  on 1 Nov 2024 (portal or fax); from 1 Jan 2025 some behavioral-health service types are
  phone-only – these cannot be served by the bridge without a UM process change.
* Peer-to-peer: 1-844-442-7324, completed within two business days; MA fully-insured
  reconsideration within one business day by a clinical peer; provider appeals: 180-day filing
  limit, decision within 30 days.
* Delegated UM (not in eCare): Evolent (high-tech imaging, cardiac, hip/knee/shoulder, spine,
  interventional pain, sleep), Carelon (genetic/molecular testing), EviCore (mainly Tufts products).
* Elective inpatient medical admissions PA initiative (planned 1 April 2026) was halted; notification
  process continues. Ancillary/supporting services adjudication change effective 1 Sept 2026.
* Provider manual is updated on the 1st of each month – the rules engine needs versioning.

## 11.2 CMS-0057-F and the HIPAA enforcement discretion

* Impacted payers: MA organisations, Medicaid/CHIP FFS and managed care, QHP issuers on the
  **federally-facilitated** exchanges. For HPHC commercial that is the **New Hampshire** QHP block
  only; MA (Health Connector) and ME (CoverME.gov) are state-based exchanges; group business is out
  of scope. HPHC's Stride MA plans were discontinued 1 Jan 2025.
* Operational provisions since 1 Jan 2026: 72 h expedited / 7 calendar days standard decisions
  (not for drugs; FFE QHPs keep 72 h / 15 days), specific denial reason on every denial regardless
  of channel, public PA metrics (first publication due 31 March 2026).
* API provisions by 1 Jan 2027: Patient Access (with PA data), Provider Access, Payer-to-Payer,
  Prior Authorization API (Da Vinci PAS, with CRD/DTR recommended).
* HIPAA enforcement discretion (CMS statement dated 28 Feb 2024): a payer implementing the FHIR PAS
  API under CMS-0057-F that does not use the X12 278 with the provider will not be enforced against;
  FHIR-only, FHIR + X12, or X12-only are all acceptable. Applies only in the CMS-0057-F PA API
  context. (One search summary claimed it "ended June 2024"; the CMS pages are the authority –
  verify, Q39.)
* Point32Health has published nothing on CMS-0057-F, PAS, or a vendor selection; its only
  interoperability page covers the 2020 Patient Access rule for MA and NH exchange members.

## 11.3 State rules in HPHC's footprint (MA, NH, ME, CT, RI, VT)

| State | Rule | Bridge impact |
|---|---|---|
| Massachusetts | M.G.L. c.176O §25: standardized PA forms; portal/web systems allowed if consistent with the form. **211 CMR 52.00 amendments effective 5 June 2026**: no PA for emergency/urgent care, primary care, preventive services, post-cancer-diagnosis imaging, maternity, outpatient SUD treatment, PT/OT, and certain chronic-condition/SMI medications; urgent "health-sensitive" requests answered within **24 hours**; approvals for chronic conditions honoured for the duration of treatment. No FHIR/API transport mandate found. | Rules layer must know which services are PA-free (CRD) and the 24-hour urgent clock; long-lived authorizations affect DTP*AAH periods. |
| New Hampshire | RSA 420-E:4-a uniform PA form / electronic standard for prescription drugs; RSA 420-J:7-b: electronic non-urgent Rx PA decided within 3 business days; carriers may not *require* ePA in several situations (no broadband, no EMR, opt-out…). NH exchange is federally facilitated → CMS-0057-F applies to that block. | Keep fax/portal fallbacks; CMS clocks for NH QHP members. |
| Maine | 24-A M.R.S. §4304: non-emergency PA answered within **72 hours or 2 business days, whichever is less**; provider *and* enrollee notified; electronic PA required for prescription drugs. | SLA engine by member situs. |
| Connecticut | CGS §38a-472g: urgent determinations within **24 hours**; missing-information requests within 24 hours; higher-level-of-care BH/SUD requests automatically urgent. | Urgency detection and clocks. |
| RI / VT | Not researched (budget). | Q40 |

## 11.4 Regional platform developments to track

* **Availity Essentials** replaces HPHConnect and the Tufts portal in phases from late 2026 into
  2027 (single login, PA and referral functions, Digital Correspondence Hub).
* **Availity + Onyx** joint CMS-0057 compliance platform (Aug 2025); **Onyx acquired InteropX**
  (Feb 2026) to accelerate ePA.
* **MHDC + ZeOmega "NEHEN FHIR"** statewide FHIR-based prior-authorization network built on Da
  Vinci IGs (2025); HPHC is an existing NEHEN participant for 278/270/837.
* Tufts Health Plan UM runs on **MHK** (MedHOK) with real-time determinations; HPHC commercial runs
  on eCare – two back ends behind one future portal.
