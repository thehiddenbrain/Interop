# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

**Spine 1 is frozen and both waves are building, for the second time.** Six reviewers read the
shared types before the freeze, each from the seat of one downstream owner, and every finding was
checked against the code and applied. What changed and why is in the four "Changes made after"
sections of `DECISIONS.md` (D-039 to D-096). From here on a wave agent may not edit a shared type:
it reports the change it needs and the integrator makes it.

**The waves were interrupted once.** About an hour into Wave A and Wave B every background agent
was stopped at the same moment by a usage limit, part-way through its package. What each had
written survived in its git worktree: A1 (configuration layer) about half built and not
committed, A2 (ledger) two checkpoint commits and nearly complete, B1 (PAS) four commits and
about half built, B2 (DTR) everything except the service and the controller. A3 (code list), A4
(lookups) and the frontend had not begun. Each half-built package was handed to a new agent that
continues in the same worktree, with the brief, the code that is there and a digest of the notes
its predecessor left. Every agent now commits a checkpoint after each slice that builds green.

Because of the interruption the plan changed in one respect: the verifier that was to follow each
wave package was dropped. The integrator reads each package while merging it, and the
verification agents of section 27 audit the integrated code, V2 (member data) straight after
Spine 2 and V3 (the production guardrail) as soon as the guardrail is merged. An independent
review of the code the integrator wrote alone (suites, chain view, the HTTP guard) was started,
stopped by the same limit before it read anything, and is still owed.

The integrator built the following against fakes while the waves ran. All of it is tested, none
of it is wired as Spring beans yet, because the gateway, the ledger and the code list arrive with
Wave A:

- the CRD vertical: scenarios, mock and live clients, request builder, response mapper, outcome
  rules, verdicts, conformance checker, service, controller and controller tests
- raw send (D-097, D-098): one collection request sent as written through the same gateway and
  guardrail
- the case runner (D-101): one case run from its inputs alone, the path a suite row and a replay
  take, with the CRD step registered
- the chain view (D-111 to D-113, section 28.1): a chain read back from the ledger with where
  each of CRD, DTR and PAS stands, why a stage did not run, and the stated next step. Carrying
  context between the steps and running a chain as one suite case wait for Wave B
- `GET /api/mode` (the controller, not yet a bean) and a dev harness in the test sources (D-114)
  that serves CRD, raw, suites, chains and mode with stand-ins, for the frontend's first pass
- suite runs (D-103 to D-110, section 28.2 and 28.4): the suite file reader and its row rules, the
  sequential runner with a progress count, the results table, the comparison with an earlier run,
  the response time distribution, the CSV export and the controller. The CSV text itself is read
  through a small interface that the code list agent's reader will sit behind when Wave A is merged

**Added by the operator during the build (D-118, D-119):** paste a request of your own and send
it, and compare a pasted CRD request with the one the tool builds, with each difference
explained. Sending a pasted body already worked on the server. The comparison is being built and
independently verified on its own branch. The paste box and the comparison view are part of the
next frontend pass and do not exist on screen yet.

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | done, reviewed by six seats, frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | building again after the interruption. A1 and A2 taken over, A3 and A4 started fresh |
| Spine 2: integrate Wave A. V2 PHI audit | not started |
| Spine 3: CRD reference vertical | built and tested against fakes. To do after Wave A: wire as beans, `/api/mode` |
| Wave B: B1 PAS and subscriptions, B2 DTR | building again after the interruption, both taken over |
| Spine 4: integrate Wave B, chain, suites, cross-protocol tests. V3 guardrail audit | case runner, suites and the chain view built and tested against fakes. Chain as a suite case and cross-protocol tests not started |
| Wave C: C1 frontend, C2 docs and scripts, C3 verification V1 to V7 | C1 pass 0 (frame, result view, CRD, suites, chain) building against the dev harness. The rest not started |
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
- `RunRecord`: the ledger line. It has no component for member data or wire JSON, and it checks
  every component a response can fill, so text that is not a code is not written (D-080).
- `OperationGateway` and `PreparedCall`: the only way a client can be given a target, so an
  unguarded production write does not compile.
- `ExchangeExecutor`: the one live HTTP path, classifying timeouts, refused connections, HTTP
  errors and unreadable bodies.
- `ApiExceptionHandler`: the one error shape.
- All configuration properties records, bound from `application.yml`.

### Not done at this commit

- Nothing runs end to end in the application yet. The CRD, raw and suite services and
  controllers are not Spring beans, so `POST /api/crd`, `POST /api/raw` and `/api/suites` do not
  exist in the running application. `GET /api/mode` does not exist yet either.
- A suite can run CRD cases only. PAS, DTR and chain rows are reported as not runnable until
  their steps are registered in Spine 4.
- No real CSV text has been read as a suite yet: the suite tests use a stand-in that splits on
  commas.
- No ledger, no code list, no lookup, no Postman configuration layer on this branch: those are
  Wave A, still building on its own branches.
- No PAS, no DTR on this branch: those are Wave B, still building. No chain, no suites, no
  frontend.
- Nothing has been run against Onyx. Every test runs against the mock or a fake.
- The build runs 542 tests.

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

`service/chain/` and `web/ChainController` (section 28.1), `service/cases/` case runner used by
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
