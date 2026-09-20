# 01 – Executive Summary: FHIR (Da Vinci PAS) ↔ X12 278 Bridge for eCare

## The situation

* The commercial line of business needs electronic prior authorization (ePA) through the Onyx
  FHIR platform. Providers' EHRs will send Da Vinci PAS requests (`Claim/$submit`).
* eCare, the commercial referral/authorization/notification system, only speaks X12 278
  (005010X217) through the HPHC EDI gateway described in the HPHC 278 companion guide v1.2.
* Availity declined to provide a PAS↔278 bridge for now and Edifecs did not respond, so a custom
  bridge built with Onyx is the only route to go-live.

## What the bridge is

A translation and orchestration service between Onyx (FHIR) and eCare (X12): PAS Request Bundle →
278 request (+ 275 attachments later) → eCare; 278 response / 999 / TA1 → PAS Response Bundle →
Onyx → EHR; plus pended-decision follow-up (unsolicited 278 responses or 278 inquiry) so that Onyx
can fire PAS subscription notifications; plus updates, cancels and inquiries. The PAS IG models
exactly this "intermediary" role and treats everything behind the FHIR endpoint as a black box.

## What this package contains

| Doc | Content |
|---|---|
| `02-architecture.md` | Position of the bridge, design principles, components, NFRs, phasing |
| `03-transaction-catalog.md` | Every FHIR operation and X12 transaction, pended-decision flow, acknowledgment matrix |
| `04-mapping-request.md` | Element-level PAS → 278 mapping (envelope, HL, loops 2000A–2010F, terminology, validation, updates, inquiry) |
| `05-mapping-response.md` | Element-level 278 response → ClaimResponse (HCR, REF, DTP, HSD, AAA, MSG, PWK, addItem pattern), worked example |
| `06-hphc-companion-guide.md` | Everything recovered from the HPHC 278 companion guide, tagged by confidence, and what still has to be read from the PDF |
| `07-pitfalls.md` | 40 pitfalls with severity and mitigation |
| `08-open-questions.md` | 42 questions for the EDI team, UM/business, eCare, Onyx and compliance, ranked blocker/high/medium |
| `09-test-strategy.md` | Test pyramid, fixtures, HPHC certification checklist, go-live criteria |
| `10-sources.md` | Sources consulted and access limitations |
| `11-business-rules-and-regulatory.md` | HPHC referral/notification/PA rules, CMS-0057-F scope, HIPAA discretion, MA/NH/ME/CT rules, regional platform moves |
| `samples/` | X12 Example 1a referral 278 and its PAS equivalent, side by side |

## Ten things to know before the first design meeting

1. **The companion guide is the contract.** Its request/response tables (pp. 20–24) and six sample
   pairs (pp. 25+) define what eCare accepts and returns. They could not be fetched from this
   environment (network policy blocks point32health.org); the PDF must be dropped into the repo so
   the mapping tables can be finished line by line. Everything recovered so far is in doc 06.
2. **HPHC's real-time 278 is genuinely synchronous**: one ST/SE per request, under 10 seconds,
   connection held open – compatible with PAS's 15-second target.
3. **HPHC returns action codes A1, A3, A4, A6 and C.** A6 ("service approved, but with changes")
   forces the PAS item + addItem pattern; A4 forces the pended/subscription machinery.
4. **Pended decisions are the hardest part.** The bridge needs a way to learn that eCare finalised a
   pended case (unsolicited 278 response, 278 inquiry X215, or an eCare event feed). None is
   confirmed yet – blocker Q13.
5. **Trading-partner enrollment ties a submitter ID to a provider roster** (NPI/TIN/payee must match
   HPHC records). A PAS API open to all contracted providers cannot pre-enumerate them – blocker Q7.
6. **Notification, referral and prior authorization are three different HPHC processes** with
   different timing rules and outcomes. PAS models only "authorization"; how a notification is
   represented over 278 and in a ClaimResponse must be decided – blocker Q21.
7. **X12 IP is licensed.** Code lists (service type, action, reject, decision-reason, PWK) and the
   X12/HL7 crosswalk require an X12 Glass subscription – blocker Q2.
8. **Delegated UM vendors** (Evolent, Carelon, EviCore, phone-only behavioral health) are not in
   eCare; the bridge needs a routing table and a clean "contact payer" response for them.
9. **Regulatory scope is narrower than assumed.** CMS-0057-F legally binds only HPHC's New Hampshire
   federally-facilitated-exchange QHP block; MA and ME are state exchanges. The build is strategic
   for the rest of the commercial line. Meanwhile MA 211 CMR 52.00 (effective 5 June 2026) removed
   PA from many service categories and imposes 24-hour urgent turnaround.
10. **The platform landscape is moving under the bridge**: Point32Health is migrating portals to
    Availity Essentials (late 2026–2027), Availity and Onyx launched a joint CMS-0057 platform,
    and MHDC/ZeOmega are standing up a statewide "NEHEN FHIR" PA network. Confirm eCare's roadmap
    and where the PAS front door will live before committing the build (Q41, A1 in doc 07).

## Recommended next 30 days

| Week | Action |
|---|---|
| 1 | Obtain the companion guide PDF and Tufts sibling; send the blocker questions (doc 08) to the EDI team, UM owners and Onyx; start X12 licensing. |
| 1–2 | Confirm with Point32Health architecture: eCare roadmap, internal vs gateway channel, Availity/NEHEN FHIR position. |
| 2–3 | Transcribe the guide tables into docs 04/05; build fixtures from Appendices A–F; run the FHIR validator and Inferno against Onyx's test tenant. |
| 3–4 | Prototype: PAS referral bundle → 278 → HPHC test → ClaimResponse, using X12 Example 1a as the baseline; measure latency; design the pend engine against the answer to Q13. |
