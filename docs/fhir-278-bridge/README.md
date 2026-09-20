# FHIR (Da Vinci PAS) ↔ X12 278 Bridge for eCare – Design Package

Design documentation for the custom bridge that lets the Onyx FHIR prior-authorization platform
(Da Vinci PAS) talk to eCare, the commercial UM system that only accepts X12 278 (005010X217)
through the Harvard Pilgrim Health Care (HPHC / Point32Health) EDI gateway.

Start with `01-executive-summary.md`. Read the documents in order; each is self-contained.

| # | Document |
|---|---|
| 01 | [Executive summary](01-executive-summary.md) |
| 02 | [Architecture and design considerations](02-architecture.md) |
| 03 | [Transaction catalog](03-transaction-catalog.md) |
| 04 | [Request mapping: PAS → 278](04-mapping-request.md) |
| 05 | [Response mapping: 278 → PAS](05-mapping-response.md) |
| 06 | [HPHC 278 companion guide findings and gaps](06-hphc-companion-guide.md) |
| 07 | [Pitfalls](07-pitfalls.md) |
| 08 | [Open questions to confirm](08-open-questions.md) |
| 09 | [Test and certification strategy](09-test-strategy.md) |
| 10 | [Sources and access limitations](10-sources.md) |
| 11 | [HPHC business rules and regulatory context](11-business-rules-and-regulatory.md) |
| – | [samples/](samples/README.md) – X12 Example 1a referral and its PAS equivalent |

## Status of this package (20 Sept 2026)

* Built from: the Da Vinci PAS IG source (v2.2.1, cloned from GitHub), the X12 005010X217 public
  Example 1a, and search-engine-indexed content of the HPHC 278 companion guide v1.2, the HPHC
  provider manual, CMS-0057-F material and state statutes.
* **Not yet incorporated**: the companion guide's own request/response tables and sample
  transactions. The environment used to prepare this package cannot reach point32health.org /
  harvardpilgrim.org (network policy). Place the PDF at `docs/fhir-278-bridge/reference/` and the
  mapping tables in docs 04/05 can be completed line by line (doc 06 §6.9 lists the pages).
* Nothing in this package is invented: every fact from a secondary source is tagged with its
  confidence, and unknowns are listed as questions in doc 08.

## Relationship to this repository

This repo hosts the CMS-1500 claim bundle service. The bridge documented here is a separate
service; the following pieces of this codebase are directly reusable: NPI check-digit validation
(`domain/Patterns`), ASCII folding for X12 basic character set (`pdf/FormText`), the REST/SOAP error
contract pattern (`api/rest/RestExceptionHandler`), and the settings/observability conventions.
