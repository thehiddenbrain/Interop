# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

**Spine 1 written, reviewed in part, building. Not yet frozen.** Six reviewers are reading the
shared types, each from the seat of one downstream owner. Two have reported and every finding of
theirs is applied (see "Changes made after the contract review" in `DECISIONS.md`). Four are
still running. The freeze and the Wave A fan-out follow the last report.

While the review runs, the integrator has started the CRD vertical's pure logic, which has no
dependency on Wave A: the scenario loader, the mock client, the response mapper and the outcome
rules. It is not wired into a service or a controller yet.

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | written, build green, 2 of 6 reviews applied, not frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | briefs written, not started |
| Spine 2: integrate Wave A. V2 PHI audit | not started |
| Spine 3: CRD reference vertical | in progress: scenarios, mock client, mapper, outcome rules done and tested. Request builder, validation, service, controller, live client tests to do |
| Wave B: B1 PAS and subscriptions, B2 DTR | not started |
| Spine 4: integrate Wave B, chain, raw send, cross-protocol tests. V3 guardrail audit | not started |
| Wave C: C1 frontend, C2 docs and scripts, C3 verification V1 to V7 | not started |
| Spine 5: clean build twice, fresh-clone build, push, pull request | not started |

### What Spine 1 contains

- Enums: `CrdOutcome` (no member for a clinical refusal), `LookupStatus`, `AuthRequirement`,
  `EnvironmentKind`, `EpaOperation` (each knowing whether it is a write), `OnyxFailureType`, and
  the supporting ones listed in the plan below.
- The result envelope every protocol returns, `RunEnvelope`, with the authorization outcome
  (`VerdictView`) and the test result (`ExpectationView`) as separate parts.
- `TrackedJson`, `Shown` and `AdditionalField`: the mechanism behind "nothing silently dropped"
  and behind telling absent, null and empty apart on screen.
- `Expectations`: expected versus actual, which cannot pass with both sides empty.
- `RunRecord`: the ledger line, with no component that could hold member data or wire JSON.
- `OperationGateway` and `PreparedCall`: the only way a client can be given a target, so an
  unguarded production write does not compile.
- `ExchangeExecutor`: the one live HTTP path, classifying timeouts, refused connections, HTTP
  errors and unreadable bodies.
- `ApiExceptionHandler`: the one error shape.
- All configuration properties records, bound from `application.yml`.

### Not done at this commit

- Nothing calls a vendor yet. There are no clients, no controllers except the fallback error
  controller, and no frontend.
- The contracts may still change as a result of the review.

## The plan, written before any feature code

### Build order

Waves run in parallel, each agent in its own git worktree so that one agent's half-written file
cannot break another agent's build. Spines are sequential. The integrator merges each wave, runs
the full build, fixes the seams and commits. No wave agent merges its own work.

1. **Spine 0.** Gradle project, wrapper, empty application class, `application.yml` skeleton,
   `.gitignore`, the `-parameters` gate and the house style gate.
2. **Spine 1.** Every type that more than one wave touches. Frozen after this commit.
3. **Wave A.** Four disjoint packages, four agents.
4. **Spine 2.** Merge, full build, seam fixes. Then V2, the PHI auditor, runs early.
5. **Spine 3.** CRD end to end as the worked example every other protocol copies.
6. **Wave B.** PAS with subscriptions, and DTR. Two agents.
7. **Spine 4.** Merge, chain, case runner for replay and suites, cross-protocol tests. Then V3,
   the guardrail auditor, runs early.
8. **Wave C.** Frontend, documentation and scripts, verification agents.
9. **Spine 5.** Two clean builds, a fresh-clone build, push, pull request.

### Package and file list

Root package `org.p32h.interop.epa.pave`.

**Spine 1, frozen**

- `config/`: `EpaProperties`, `ConfigStoreProperties`, `TrackingProperties`, `CodelistProperties`,
  `AppProperties`, `OnyxProperties`, `LookupProperties`, `PropertiesConfig`, `StartupChecks`
- `model/` enums: `CrdOutcome`, `LookupStatus`, `AuthRequirement`, `EnvironmentKind`,
  `EpaOperation`, `OnyxFailureType`, `Protocol`, `TestResult`, `ScenarioBehavior`, `Presence`,
  `Tone`, `ErrorKind`, `FindingKind`, `NextAction`, `VariableSource`
- `model/` records: `MemberDetail`, `ProviderDetail`, `ProcedureCode`, `CoverageInformationView`,
  `CardView`, `CardLinkView`, `ErrorView`, `FieldError`, `ScenarioSummary`, `ResolvedUrl`,
  `RunRecord`, plus the envelope every protocol returns: `RunEnvelope`, `VerdictView`,
  `ExpectationView`, `NextStepView`, `Finding`, `AdditionalField`, `Shown`, `WireView`,
  `LedgerReceipt`, `MemberIdForms`, `Scenario`, `VendorExchange`
- `model/` helpers: `JsonSafe`, `Texts.present`, `TrackedJson` (reads that record what tier 1
  consumed), `AdditionalFields` (the tier 2 walker), `Npi` (Luhn), `MemberIds` (no-space form)
- `client/` interfaces: `OnyxCrdClient`, `OnyxDtrClient`, `OnyxPasClient`, `SubscriptionClient`,
  `MemberLookupClient`, `ProviderLookupClient`
- `service/` interfaces other waves call: `OperationGateway` with `PreparedCall` (the only way a
  client can be given a target, minted after the production guardrail has run), `RunLedger`,
  `ProcedureCodeDirectory`, `MemberLookup`, `ProviderLookup`, `CaseRunner`
- `web/`: `ApiExceptionHandler`, the shared error shape, `SessionUnlock`

**Wave A**

| Agent | Owns |
| --- | --- |
| A1 | `postman/` (model, parser, variable resolver, operation detector), `service/config/` (`ConfigStore`, gateway implementation, production guardrail), `web/ConfigController` |
| A2 | `service/ledger/` (JSON Lines ledger, member token, CSV export, replay), `web/RunController` |
| A3 | `service/codelist/` (`CsvReader`, `ProcedureCodeService`), `web/ProcedureCodeController`, the placeholder code list |
| A4 | `client/lookup/`, `service/lookup/`, `web/LookupController`, synthetic member and provider fixtures |

**Spine 3, CRD**

`client/crd/` (mock and live), `service/crd/` (validator, request builder, response mapper,
conformance checker, service), `service/scenario/` (scenario loader), `web/CrdController`,
`web/ModeController`, `scenarios/crd/` with 14 files.

**Wave B**

| Agent | Owns |
| --- | --- |
| B1 | `client/pas/` (PAS and subscription clients), `service/pas/`, `web/PasController`, `scenarios/pas/` |
| B2 | `client/dtr/`, `service/dtr/`, `web/DtrController`, `scenarios/dtr/` |

**Spine 4**

`service/chain/` and `web/ChainController` (section 28.1), `service/run/` case runner used by
replay and suites, `service/suite/` and `web/SuiteController` (section 28.2), cross-protocol
tests.

**Wave C**

| Agent | Owns |
| --- | --- |
| C1 | `src/main/resources/static/`. The shell, shared renderers and the CRD tab are built first as the worked example, then the other tabs fan out one file each |
| C2 | `README.md`, `run.sh`, `run.cmd`, sample Postman files, sample code list CSV |
| C3 | Verification agents V1 to V7 from section 27, none of them an author of what it audits |

### Degradation level

Target: level 1, everything in the build prompt. The level actually delivered is stated at the
top of the handover.
