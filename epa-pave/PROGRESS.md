# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

**The application runs.** On the integration of the first wave it starts on the mock profile with
no file edited and answers on every endpoint that wave and the CRD vertical provide. Observed on
the built jar, on mock, on this revision's parent:

```
$ curl -s localhost:8057/api/mode
{"name":"PAVE","version":"1.0.0","port":8057,"mode":"mock","target":"onyx","environment":{"active":false},"epoch":1795122465000,"writeAccess":{"production":false,"writesAllowed":true,"floorAllows":false,"reason":"No environment is active. On the mock profile nothing is sent, so writes are allowed."}}
```

Also called over HTTP on that jar, each answering as designed: the config endpoints, member and
provider lookup (found, not found, a malformed NPI as 422), the code picker and its self-check,
CRD (two runs, discovery, scenarios), the ledger (query, one run, a note, health, the CSV export,
a replay that came back "same"), a chain, and a suite of six cases read from a CSV with CRLF line
endings and a quoted cell holding a comma.

**What this revision is and is not.** All six backend packages are merged at their agents' final
commits and wired into one application: the configuration layer with the production guardrail
(A1), the ledger (A2), the procedure code list and picker (A3), member and provider lookup (A4),
PAS with subscriptions and the notification receiver (B1), and DTR (B2), with CRD, suites, the
chain view and the chain as a suite case from the integrator. A journey through all three
protocols runs through the real endpoints on mock: CRD says documentation is needed, the
questionnaire package is fetched, a person's answers are completed into a response, and that
response travels inside the request sent to PAS (D-135). Follow-ups from the code list report
and the PAS report are being worked on branches of their own and are not in this revision. There
is no frontend in this revision: the first pass is being built and reviewed on its own branch.

**Three kinds of independent check are running and none has reported.** The member data audit of
section 27 (V2) and two reviews of code the integrator wrote alone, on the first wave as
integrated. And a comparison of what the tool sends, reads and judges for each protocol with the
published guides, which became possible late (D-130). Until those report, treat every message
shape in CRD and PAS as written from memory and unverified.

**The waves were interrupted once.** About an hour into Wave A and Wave B every background agent
was stopped at the same moment by a usage limit, part-way through its package. What each had
written survived in its git worktree, and each half-built package was handed to a new agent that
continues in the same worktree, with the brief, the code that is there and a digest of the notes
its predecessor left. Every agent now commits a checkpoint after each slice that builds green.

Because of the interruption the plan changed in one respect: the verifier that was to follow each
wave package was dropped. The integrator merges and exercises each package, and the verification
agents of section 27 audit the integrated code. An independent review of the code the integrator
wrote alone (the case runner, suites, the chain view, the HTTP guard) was started, stopped by the
same limit before it read anything, and is still owed.

Nothing has been run against Onyx. Every result above is on mock.

**Added by the operator during the build (D-118, D-119):** paste a request of your own and send
it, and compare a pasted CRD request with the one the tool builds, with each difference
explained. Sending a pasted body already worked on the server. The comparison is being built and
independently verified on its own branch. The paste box and the comparison view are part of the
next frontend pass and do not exist on screen yet.

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | done, reviewed by six seats, frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | done, all four merged at their final commits |
| Spine 2: integrate Wave A. V2 PHI audit | merged and wired, application starts, endpoints exercised over HTTP. The V2 audit is running |
| Spine 3: CRD reference vertical | done and wired. Follow-ups from the code list report (placeholder list honesty, coding systems) in progress on a branch |
| Wave B: B1 PAS and subscriptions, B2 DTR | done, both merged at their final commits. Follow-ups from the PAS report in progress on a branch |
| Spine 4: integrate Wave B, chain, suites, cross-protocol tests. V3 guardrail audit | merged and wired. The chain runs as a suite case, and the journey through all three protocols is tested through the real endpoints. V3 has not run. One reference from DTR into PAS does not resolve yet (D-135) |
| Wave C: C1 frontend, C2 docs and scripts, C3 verification V1 to V7 | C1 pass 0 (frame, result view, CRD, suites, chain) built against a stand-in and under independent review in a browser. V2 running. The rest not started |
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

- No frontend. `http://localhost:8057/` answers 404 on this revision.
- CRD and PAS message shapes have not been compared with the published guides. DTR was compared
  by its own author only. An independent comparison of all three is running (D-130).
- The order a DTR response refers to is not in the PAS bundle, so that one reference does not
  resolve there (D-135).
- Comparing a pasted CRD request with the tool's own is not in this revision.
- The procedure code list in use by default is a synthetic placeholder. Until the follow-up lands,
  a conformance finding that says the code list and the service disagree does not say that the
  list it compared with is the placeholder. On mock both sides are synthetic. On live this would
  mislead, and the README must not be written as if it were settled.
- A credential a service sends back inside a Subscription (`channel.header`) is hidden in the
  designed view and still visible in the wire view. Being fixed on the PAS follow-up branch.
- From A1's report: no real vendor Postman export was available, so every Postman file the tests
  use is hand-written to the shape the specification describes, and detection by url is verified
  only for the standard FHIR operation paths and `/cds-services`. Concurrency in the store is
  reasoned about and not tested. A credential typed literally into a url is shown as written.
- From A2's report: everything ran on Linux only. Flush and sync to disk are checked by reading
  the code, since a test cannot observe a sync. Two instances pointed at one ledger directory are
  not coordinated. A note written after the clock was set back across midnight is on disk and is
  not applied when the run is read.
- From A3's report: no real P32H export of the code list was available, so its header row, the
  spellings of its authorization flag, its date format and its vendor names are assumptions.
- From A4's report: neither lookup has met the real member API or the real provider directory.
  Both response contracts are open (section 26 of the build prompt), so the two mappings are
  assumptions no real response has tested. A member ID that contains a slash cannot be looked up.
  A name returned in one piece is shown as a field and cannot prefill first and last name.
- From B1's report: the notification receiver does not authenticate its caller and has never
  been called over a socket by a real service. With the server bound to this machine only, which
  is the default, no vendor can reach it at all. In the attachment mode `x12-275` the tool only
  names the attachment on the claim; nothing sends the attachment. Two notifications arriving at
  the same moment are not tested.
- From B2's report: which version of the DTR guide the sandbox follows is not known, and it
  decides the shape of next-question. A response the tool builds from nothing states no intended
  use, which the 2.2.0 ballot requires. The author of an answer is named without a role. The
  operations `$expand` and `$log-questionnaire-errors` are not implemented. A failure of the tool
  inside the response step, which sends nothing, answers 500 and writes no ledger line.
- The section 27 verification agents other than V2 have not run. The independent review of the
  integrator's own code is running for the first wave only; the chain as a case and the wiring
  of the second wave have not been reviewed by anyone but their author.
- README, `run.sh`, `run.cmd` and the sample files do not exist yet.
- Nothing has been run against Onyx. Every test runs against the mock or a fake.
- The build runs 1831 tests, 2 of them skipped because the build ran as root, for which a
  read-only folder is not read-only. The same refusals are covered by tests that did run.

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
