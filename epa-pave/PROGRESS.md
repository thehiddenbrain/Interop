# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

This describes revision `33893aa` on branch `epa-pave`: every fix branch of the audit wave, the
request comparison (D-119, D-170) and the documentation, merged. The integrator's build of it was
green and printed 2515 passed lines, 3 skipped lines and no failure. The XML test reports of a build
of the same code (branch `records`, which changes only this file and `DECISIONS.md`) count 2517
tests: 2515 passed, 2 skipped, none failed. The two skipped tests need a folder that cannot be
written (`ConfigStoreTest.aDirectoryThatCannotBeWrittenFailsAtStartupNamingThePath`,
`JsonlRunLedgerTest.startupFailsWhenTheDirectoryIsReadOnly`), which a build running as root cannot
make; the same refusals are covered by tests that did run. Where the third skipped line of the
integrator's build came from was not established. The decisions of that wave are D-140 to D-175.

### Not done, not verified, or known to be wrong

**Gates that have not run.**
- Section 27: the member data audit (V2), the guardrail audit (V3) and the fixture audit (V4) ran
  once, on the revision before the fixes. Their findings were then fixed, and each fix was attacked
  by a verifier that did not write it. **Not run at all: V1 (the build auditor, from a fresh clone),
  V5 (house style), V6 (honesty) and V7 (completeness on screen). Not run on the final revision: a
  second run of V2, V3 and V4.** The house style rules of V5 are enforced by the build on every
  file, which is not the same as the audit.
- The walkthrough by a business tester ("Prove it before handover", part 1) has not run. Part 2
  (no-auth told apart from no determination, on screen and in the ledger) cannot be shown on screen,
  because there is no frontend in this revision; the ledger and API half is covered by tests.
- No fresh-clone build of this revision. The README's author built from fresh clones of earlier
  revisions. Spine 5 has not run as a step: the branch `epa-pave` is on the remote at `33893aa`, and
  has been pushed at every integration since the first, but the two clean builds and the
  fresh-clone build have not been done and there is no pull request.
- **Nothing has been run against the vendor's service.** Every result in this file, and every result
  any agent reported, is on the mock profile or against stand-in servers on loopback ports.
- The tool is held to version 2.0.1 of CRD, DTR and PAS (D-136). The build prompt names other
  versions, and its section 11 table differs from CRD 2.0.1 (D-137, D-173). Which version the
  vendor's sandbox follows is not known.
- **There is no frontend in this revision.** `GET /` answers 404 with JSON. Three of its parts are
  finished, reviewed in a browser and fixed on branches: the case (lookups, code picker, a request of
  the tester's own), the Config tab and the History tab. Three are being built: the PAS tab, the DTR
  tab and the CRD tab with the request comparison view, with the shared frame. Every sentence of the
  README about the screens is marked as not checked.

**Open at this revision, being worked on now on other branches.** These are what each fix and each
finished frontend part left for someone else: the changes one fix needed in another's files, and
what the finished tabs need from the server or the shared frame (D-140 to D-175 say what was
decided about each). All of it is open at `33893aa`. The server lines are being worked on now on
two branches (`seams-protocol`, `seams-platform`), the frontend lines with the shared frame and
the tabs being built. None of it is on this line, and none of it is done until it is merged.
- CRD and DTR: the CRD 2.0.1 `response` reference is shown and not handed on to DTR (D-156). Broken
  guide rules are filed as `REQUIRED_ELEMENT_MISSING` for want of a kind of their own (D-140). DTR's
  package request still builds its Patient, Coverage and orders the old way: no medical record
  number, `hcpcsSystem` ignored, no HCPCS note (D-157). A questionnaire completed with no item in a
  package of several is still listed as unanswered by a later step, so PAS is not offered (D-166).
- A secret a service sends back reaches the designed view of CRD, DTR and the lookups unmasked; only
  PAS and subscriptions mask it, and the sentences "shown exactly as received" remain in the others
  (D-162).
- The protocol services and the raw service do not pass where a run came from, so a replay or suite
  refused on locked production is told to unlock, which cannot help (D-143, rule 8). The next action
  for it does not exist yet.
- A manual CRD then PAS, or CRD then DTR, run writes no `correlatedRunId` on the later line (D-156).
- A request sent from the collection as it is: attaches the token by host alone (D-098, D-143 rule 7);
  does not send the body saved in the collection (D-139); says "sent" on mock; cannot complete a url
  that holds a `{token}`. After a send that got no usable answer it says "check the environment,
  then send again", even for a POST, PUT or DELETE that may have reached the service: it does not
  use whether the request reached it, as PAS does (D-163), so it can invite a second prior
  authorization.
- A CRD run on mock with a body of the tester's own that is not valid JSON still offers PAS as the
  next step (the case review's CASE-06). A run with the tester's own body records no member, NPI or
  line of business (D-172), and nothing on its ledger line tells two such runs apart (CASE-05).
- Four "not found" sentences repeat an identifier taken from the request (D-150).
- The order a CRD request carries on its ledger line from a tester's own body (member data audit m6),
  and the DTR service writing its own "not a url" statement where a questionnaire address belongs.
- The address a subscription names for notifications, when it holds a secret, may still show in the
  Subscription body in the wire view; what the credential marking added since already covers is to
  be checked.
- What the finished tabs need from the server: labels for variable sources, whether a lookup is
  switched on, a short sentence for a blocked row, the words for each next action, a replay that
  returns what the new run found, filters on the export, a tester reference filter, an optional time
  zone for dates, labels for suite counts, labels for lookup statuses and `exactCode`, and a label
  for the authorization requirement of a procedure code row (CASE-14).
- Fixtures: the scenario list shows only the first sentinel of a file; a damaged scenario set is
  reported on a suite row as "not valid", a chain row included (in the code at this revision such a
  chain keeps and names the steps it already sent, since any failure of a later step ends the chain
  with an unverified case line, D-167; this was not run against a damaged set); the lookup fixtures
  accept two files claiming one identifier and a repeated key; beans only mock uses exist on live
  and no test starts the live context.
- Ledger: a suite row's over-long expectation is still quoted back; the code list's CSV records print
  their cells in `toString`; `RunController` keeps its own copies of the ledger's limits; the
  `RunLedger` Javadoc does not say what the policy does on mock, nor that `query` and `find` apply
  case lines; the DTR case step can split a "not recorded" statement into addresses. The History
  tab's fix reported that the server kept saying "The ledger cannot write" after the ledger folder
  was restored, until a restart; nobody has looked into it. Replay's refusal of a step that used
  what an earlier step handed on does not point to the suite row of protocol `chain`; the ledger's
  health does not say which file holds an unreadable line.
- A lookup identifier holding `%2F` ends as a generic 400 from the server.
- Suites: a second suite started while one runs is answered 409 with kind `VALIDATION`, not a kind
  for a busy tool (suites review m8). `SuiteCases` still refuses a hook with "The hook must be
  order-sign or order-select." rather than the CRD service's sentence about what is built.
- Fixtures: the CRD fix left old wording in two files it did not own: a literal "Existing
  authorization" row in `RedactionTest`, and the description of the member fixture
  `p32h-pa-sat.json`, which still says "already satisfied".
- Frontend lines for the frame: the shared client has no PUT or DELETE; the mock note shows on a
  blocked result; the ledger indicator in the masthead does not poll the ledger's health; the colour
  key lacks the unlocked-production and sandbox entries; the case pane does not widen on a wide
  screen and the colour key button runs off a phone-width screen; a run id in the result view is not
  a link. The result view shows the line "the body the tester supplied was sent" only inside the
  CRD detail, not under the verdict for every protocol, and its member ID line does not show the
  typed and sent forms so that spaces can be seen. Two selectors of the shared stylesheet are too
  narrow, so the case restyles a label and a password field itself. The request preview of the
  workbench shows the raw codes `ENVIRONMENT` and `UNMAPPED with GET`, and writes a url token as
  `{{id}}` (CFG-16).
- For the tabs being built: the DTR packages of scenarios 1, 4, 8 and 13 carry a draft the screen
  must post on the response step and the first loop step, and `dtrIntendedUse` has no form field.
- When the frontend is merged, someone must check that a page of it is not refused when a link
  from another site leads to it: such a navigation is refused whenever its handler is a controller
  method (D-148), so a page served through a controller method would be.

**Limits each part reports that are still true.**
- CRD: US Core 3.1.1 PractitionerRole was not checked (the package is not here; it may require both a
  practitioner and an organization, and the tool sends one or the other). `Organization.active` is
  never sent (D-141). The comparison's own hook instance is still the text "a new id is generated when
  the request is sent".
- PAS: no claim update and no `$submit-attachment` (D-161); a resubmission reuses the claim id (D-133).
  US Core 3.1.1, HRex, the subscriptions backport 1.1.0 and the X12 code lists were not available; the
  2.0.1 examples were not compiled; the request was checked against hand-written 2.0.1 rules that do
  not test slices or invariants. Mock X12 reason and category codes are placeholders. A secret sent
  back as a JSON number is not masked. The guide's PAS Task route to CDex is not modelled by any
  scenario (MOCK-03).
- DTR: SDC 3.0.0, US Core 3.1.1, FHIR R4 core and the 2.0.1 narrative pages are not here, so only the
  invariants dtrx-1 and dtrb-1 are evaluated and the next-question address is an assumption. An
  answer's author carries no role. `$expand` and `$log-questionnaire-errors` are not implemented. A
  failure of the tool inside the response step, which sends nothing, answers 500 and writes no ledger
  line (from the first DTR report, not checked again). Nothing of DTR was run against a stand-in
  server. The rules DTR-07 names are recognised by their extension urls and were tested only with
  made-up questionnaires, never with a real one. The gap sentences of the DTR profile check were not
  mutation-tested one by one.
- The chain: a DTR package that goes straight on to PAS is driven by no test and no mock scenario.
- The guardrail: everything in D-145. The rule that refuses a call a run value pointed elsewhere while
  it carries a secret was checked for protocol calls and a subscription read, not for a request sent
  from the collection as it is. How a real FHIR server acts on `//`, `;` and `..;` was not checked; the
  tool sends the form it judged. A lock that lands between preparing and sending a write is tested at
  the gateway only.
- Credentials in a collection: one typed into a path segment or a host is shown as written. The
  collection file keeps the value on disk.
- The HTTP edge: only Linux, curl, raw sockets and headless Chrome were used; Windows, Firefox and
  Safari were not tried. `OPTIONS *` carries none of the tool's headers (D-150). A hard kill during an
  upload was checked by hand once, with no automated test. A cross-site request that carries neither
  `Sec-Fetch-Site` nor `Origin` is let through: under an `app.allowed-hosts` name served over plain
  http, or from an older browser, another page's image or link still reaches a read (D-148). Several
  uploads of 30MB held in memory at once (D-149) were never tried. The sentences for an upload of 60
  parts, a long file name and a 3MB field rest only on `EdgeOverTheServerTest`.
- The ledger: a one-word surname written where a code belongs is kept (D-151). The live profile was
  checked in unit tests only. Folders and day files made before the fix keep their old modes. Flush
  and sync are checked by reading the code. Two instances on one ledger directory are not
  coordinated. A note written after the clock was set back across midnight is on disk and not applied.
- Fixtures: any new top-level key in a scenario file stops the mock start until it is added to the
  loader's list. A file numbered 0 still becomes the default for unmatched IDs, and an `expect` block
  that contradicts its body is still listed as the expected outcome.
- Suites: whether a code assertion's value is in its value set is not checked before sending. A
  well-formed chain id the tool never issued is accepted. The test ledger does not apply case lines
  as the real one does.
- The code list: no real P32H export was available, so its header row, flag spellings, date format,
  time zone, vendor names, encoding, and whether it has one flag column per line of business are all
  unknown. A copy of the placeholder set as `codelist.file` counts as a real list. The first-row
  message still shows cells of letters alone.
- Lookups: neither has met the real member API or provider directory; both response mappings are
  assumptions (section 26). A member ID holding a slash cannot be looked up. A name returned in one
  piece cannot prefill first and last name.
- Notifications: the receiver does not authenticate its caller and has never been called over a
  socket by a real service; with the default bind address no vendor can reach it. Two notifications
  at the same moment are not tested.
- Postman: no real vendor export was available; every collection the tests use is hand-written to the
  shape the specification describes, and detection by url is verified only for the standard FHIR
  operation paths and `/cds-services`. Concurrency in the store is reasoned about, not tested.
- The request comparison: no real vendor request was available. A placeholder whose value exists only
  in a collection's variables is reported as having no value. Codes in NutritionOrder,
  VisionPrescription, CommunicationRequest and Appointment are not read.
- The wire view: a secret sent back re-encoded (percent, base64, another case) or shorter than six
  characters is not found.
- Documentation: `run.cmd` has never been run. STS, a mirror that needs credentials and a token
  server were not tested. A plain `./gradlew build` can skip the sample tests after a sample is
  edited; `clean build` runs them. The
  README states what a request sent from the collection as it is sends, and will need changing when
  D-139 is closed.

**Checked only by unit tests or at the gateway, never in the running tool.** A chain interrupted
part way writing its case line, and the write note of a suite on a production target (VS-6, VS-7);
a failure of the tool inside a step, which in the running application can only be caused by
editing code; the lock that lands between preparing and sending; the replay paths for a refused
claim id and an authorization number in several groups; the split of TLS failures into reached and
not reached, which was never tried against a real endpoint; the ledger on the live profile. Not
checked at all: whether a chain's DTR step can produce each fault outcome a suite file may expect
of it.

### What the audits found and what was done

State is one of: fixed (verified means a second agent attacked the fix and it held, or what it
found was then fixed), fixed in another form, partly fixed, not fixed, refuted. "Open" means it is
among the work being done now, listed above.

**Member data audit (V2).** No blocker; 7 majors, 10 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| M1 major | A questionnaire address on the ledger line could hold a member ID, a name or a date | Fixed, verified. The verification found the first fix checked shape only; each part is now checked | D-151 |
| M2 major | A code that is really an identifier was kept | Fixed in another form: identifier-or-date rule; a one-word surname is still kept | D-151 |
| M3 major | Any text was kept as a line of business | Fixed, verified | D-151 |
| M4 major | The server logged an unparsable cookie or an unmappable address | Fixed, verified (log levels) | D-150 |
| M5 major | Note, tester reference and expectation had no limit | Fixed for the run form; a suite row's over-long expectation is still quoted (open) | D-151 |
| M6 major | Uploads were written to the temporary folder | Fixed; the hard kill was checked by hand only | D-149 |
| M7 major | A credential typed into a collection was stored and shown | Fixed, verified; the verification found urlencoded bodies and collection helpers missed, then fixed | D-147 |
| m1 minor | Names that hold credentials were not treated as secret | Fixed | D-147 |
| m2 minor | Some answers carried no `no-store` | Fixed, except `OPTIONS *` | D-150 |
| m3 minor | A headerless suite file had cells quoted back | Fixed | D-168 |
| m4 minor | File modes | Fixed for the ledger and the configuration store; Windows is a limit | D-147, D-151 |
| m5 minor | On mock any member ID was written in the clear | Fixed | D-151 |
| m6 minor | A tester's own body put its orders on the ledger line | Not fixed (open) | |
| m7 minor | Records printed text in `toString` | Fixed for model, suites and ledger; the code list's CSV records are open | D-152 |
| m8 minor | Two log statements had no comment | Fixed | |
| m9 minor | A query the server cannot read was a 500 | Fixed | D-149 |
| m10 minor | The runtime folders follow the working directory | Fixed in another form: logged, with a warning inside a git tree | D-147 |

**Guardrail audit (V3).** 4 blockers, 5 majors, 2 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| G1 blocker | A run value, or an unlock for another production environment, let a write through | Fixed, verified | D-143, D-144 |
| G2 blocker | With a sandbox active, a write reached a production address from a run value, a literal or `application.yml` | Fixed, verified | D-143 |
| G3 blocker | A read could be pointed at any host with the token | Fixed for protocol and lookup calls, and an environment's secret headers are now refused too; a request sent from the collection as it is still attaches by host (open) | D-143, D-144 |
| G4 blocker | A refused replay was told writes are locked for this session | Partly fixed: the gateway words it from the origin, which the services do not yet pass (open) | D-143 |
| G5 major | Dot segments let an inquiry act as a write | Fixed, verified, including `..;` | D-144 |
| G6 major | A deleted production environment could return as a sandbox | Fixed, verified | D-143 |
| G7 major | The "not production" acknowledgement covered urls from `application.yml` | Fixed, verified | D-143 |
| G8 major | The unlock's name check had no test | Fixed | |
| G9 major | Locking again changed the epoch suites pin to | Fixed, verified | D-143 |
| G10 minor | "prod" was looked for before escapes were decoded | Fixed | D-143 |
| G11 minor | A refused lookup was shown as switched off | Fixed in another form: `FAULT`, `BLOCKED` | D-146 |

The verification of that fix (F1 to F15) got through again. F1 (address forms a server reads as one),
F2 (a secret in a production address shown and stored), F3 (an environment's secret headers sent
where a run value pointed), F5 (`..;`), F6 (credentials in urlencoded bodies and collection helpers
not named), F7 (a second mapped request lost the token), F9 (an audience or portal url made a whole
host production), F10 to F14 (wording, `sigFormat`, the lock sentence, a test, the lookup sentence):
fixed (D-144); F13 for the decode step only, since the separate host check has no test that fails
without it (the whole-url check already covers the host). F4 (blocker, the refusal through the
running application): partly fixed, open. F8 (a request sent from the collection as it is attaches
the token by host): not fixed, open. F15 (the fix edited `model/Secrets` and
`client/lookup/LookupOutcomes`, outside its files): accepted by the integrator at the merge (D-175,
item 9).

**Fixture audit (V4).** 1 blocker, 4 majors, 6 minors. On the revision before the fixes, every
synthetic NPI passed the tool's check and all 240 scenario runs gave what their files claim. The
CRD, DTR and PAS scenario files have been rewritten since (and PAS scenario 21 added) and not
audited again.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| V4-01 blocker | The placeholder code list was compared with a live service as if real | Fixed, verified | D-169 |
| V4-02 major | A damaged scenario set was never refused | Fixed, verified; how a suite row reports it is open | D-153 |
| V4-03 major | The lookups did not know the DTR and PAS sentinels | Fixed, verified | D-153 |
| V4-04 major | Sentinels did not mean the same case across protocols | Fixed, verified, in each protocol | D-154 |
| V4-05 major | No test scanned CRD and PAS scenarios for identifiers | Fixed, verified | D-153 |
| V4-06 minor | Two synthetic data sets contradicted each other | Fixed by rewording scenario 10 | D-154 |
| V4-07 minor | Tests used a member ID outside the placeholder style | Fixed | |
| V4-08 minor | A sentinel in a pasted body matched only case-sensitively | Fixed, verified | D-153 |
| V4-09 minor | A sentinel's refusal named the scenario field | Fixed | D-154 |
| V4-10 minor | Scenario 8 described a request it did not check | Fixed by description | D-154 |
| V4-11 minor | The NPI check is Luhn only | Not changed: a question for the operator | D-153 |

Its verification: VF-01 (blocker, a damaged scenario set reported on a suite row as "not valid", and a
chain loses its CRD run) partly fixed: the refusal has a type of its own, and the row still says
"not valid" (open). That a chain now keeps the steps it sent follows, in the code, from the chain
fix (D-167); it was not run against a damaged set. VF-02 to VF-06 (no test that the start-up check
runs, the identifier scan missed prefixes, live locked out by mock files, a sentence naming a Java
method, unknown keys quoted) fixed (D-153).

**Review of the HTTP edge.** No blocker; 5 majors, 9 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| E1 major | Another site's page could make the tool look a member up on live | Fixed, verified on live against a stand-in | D-148 |
| E2 major | The tool's own plain form post would be refused | Fixed | D-148 |
| E3 major | A bad escape in a path parameter got past the error handling | Fixed | D-149 |
| E4 major | Server loggers wrote request text | Fixed | D-150 |
| E5 major | Rules of the edge had no test | Fixed | D-149 |
| E6 minor | Answers given before the filters carried no headers | Fixed except `OPTIONS *`, which Tomcat answers first | D-150 |
| E7 minor | A badly formed request was a 500 | Fixed | D-149 |
| E8 minor | Every upload limit said "file too large" | Fixed | D-149 |
| E9 minor | Multipart was parsed for any address; a JSON body had no limit | Fixed; the verification found -1 refused every body, then fixed | D-149 |
| E10 minor | A session id was accepted from the address | Fixed | D-149 |
| E11 minor | Origin compared by host and port only | Fixed | D-148 |
| E12 minor | `app.allowed-hosts` entries and `localhost.` | Fixed; the verification found a false start-up warning, then fixed. An underscore in a host is left to the server | D-149 |
| E13 minor | Error answers repeat request text | Partly fixed: a made-up method is not repeated; four 404 sentences are open | D-150 |
| E14 minor | D-119 described an endpoint not on the line | True now that the comparison is merged | D-119 |

Its verification: V1 and V2 (blockers: a false start-up warning about ports; `-1` refused every body)
fixed; V3 is E13, open; V4 (no test of the server's own session tracking) fixed; V5 (D-117 and D-127
should name the exceptions) done in D-150; V6 (the 400 sentence names the query) not changed, D-174.

**Review of suites and the case runner** (before PAS and DTR were wired in). 3 blockers, 5 majors,
11 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| B1 blocker | The preview counted rows as runnable that the run refused | Fixed, verified | D-168 |
| B2 blocker | A case never sent said it was sent with the member ID alone | Fixed, verified | D-168 |
| B3 blocker | The chain view named the wrong stage as next | Fixed, verified | D-168 |
| M1 major | Any configuration change stopped a suite with a false reason | Fixed | D-168 |
| M2 major | Impossible expectations were accepted | Partly fixed: value-set membership of a code assertion is not checked | D-168 |
| M3 major | A tool failure was shown as "cannot be run from a suite" | Fixed | D-167 |
| M4 major | Rules with no failing test | Fixed, except the trim of a running suite, which cannot be reached | D-174 |
| M5 major | A ragged row raised false problems | Fixed | D-168 |
| m1 | Gaps in the results file | Fixed | D-168 |
| m2 | Unverified wording | Fixed | |
| m3 | Not every problem reported at once | Fixed | |
| m4 | Words between codes accepted | Fixed | D-168 |
| m5 | Another separator or no header | Fixed | D-168 |
| m6 | Row numbers | Partly fixed: `case-N`; no physical line numbers | D-174 |
| m7 | Faults counted as failed | Fixed, verified | D-167 |
| m8 | Stop and busy answers | Fixed, except the kind of the 409 | |
| m9 | Chain edges | Partly fixed: unknown well-formed chain ids are accepted | |
| m10 | Comment rows | Fixed | D-168 |
| m11 | A state read without synchronisation | Fixed, with no test | |

**Review of the chain as a case and the wiring of the second wave.** 6 blockers, 5 majors, 4 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| F1 blocker | A chain whose CRD call went out said "Not sent" | Fixed, verified | D-167 |
| F2 blocker | A blocked chain said nothing was sent | Fixed | D-167 |
| F3 blocker | Lookup notes said a request was sent | Fixed | D-168 |
| F4 blocker | A chain's result lived only in memory | Fixed: a case line in the ledger | D-167 |
| F5 blocker | The comparison with an earlier run used the last step | Fixed | D-167 |
| F6 blocker | A failed chain's status line named no step | Fixed | D-167 |
| F7 major | D-131 said a replay can run a chain; it cannot | Fixed in another form: D-131 corrected (D-138); the chain view says it, for the right steps only after the verification. Replay's own sentence does not point to the suite row yet (open). A later step is still marked not replayable when all it was handed was the procedure codes its line already holds | D-138, D-167 |
| F8 major | The preview overstated what can run | Fixed | D-168 |
| F9 major | Chain rules had no test | Fixed (one mutation survived, then fixed) | |
| F10 major | Only one DTR route reachable in a chain on mock | Fixed | D-154 |
| F11 major | A row never said the default scenario answered | Fixed | D-167 |
| F12 minor | Expectation edge cases | Fixed | D-168 |
| F13 minor | Correlation columns ignored in silence | Fixed: refused | D-168 |
| F14 minor | No line names the step it followed | Fixed for suite chains; manual chains open | D-156 |
| F15 minor | Mock beans exist on live | Not fixed (open) | |

The verification of suites and the chain (VS-1 to VS-12): VS-1 to VS-4 (blockers: a chain counted
as a service fault for an earlier determination; "not recorded" counted twice; the replay note on
every chain; a self-check said to name a file it does not) and VS-5 to VS-10 fixed (D-167, D-168).
VS-11 (manual chains carry no CRD run id) open. VS-12 (two frozen tests edited) refuted: they are
completeness guards that had to list the two new fields.

**CRD compared with 2.0.1.** 3 blockers, 14 majors, 9 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| 01, 02 blocker | Codes 2.0.1 defines called non-conformant | Fixed, verified | D-155 |
| 03 blocker | Documentation went to PAS unless a questionnaire or link was present | Fixed, verified; the `response` reference is not handed on (open) | D-156 |
| 04 major | No medical record number on the Patient | Fixed, verified | D-157 |
| 05 major | HCPCS system of an order | Fixed, verified | D-142 |
| 06 major | No `authoredOn` | Fixed, verified | D-157 |
| 07 major | Required elements missing, organization sent as a Practitioner | Fixed, verified | D-141, D-157 |
| 08 major | The checker applied almost no 2.0.1 rule | Fixed, verified; q2 and q4 not enforced, with reason | D-155 |
| 09 major | No version named | Fixed, verified | D-140 |
| 10 major | `no-doc` | Fixed, verified | D-155 |
| 11 major | Scenarios answered with `no-doc` | Fixed | D-155 |
| 12 major | `qualification` and `category` not read | Fixed, verified | D-155 |
| 13 major | Information wanted hid a definite answer | Fixed, verified | D-156 |
| 14 major | One answer per coverage | Fixed, verified | D-156 |
| 15 major | No coverage information called a missing requirement | Fixed, verified | D-156 |
| 16 major | Scenario 3 answered on a card | Fixed, verified | D-156 |
| 17 major | The build prompt's tables | Recorded, not edited | D-137, D-173 |
| 18 minor | Mock updates echoed a cut-down order | Fixed in another form: the mock echoes the order sent | D-157 |
| 19 minor | Verdict not element by element | Fixed, verified | D-156 |
| 20 minor | Wording of "satisfied" | Fixed, verified | D-156 |
| 21 minor | Vendor compared when it should not be | Fixed | D-156 |
| 22 minor | Hooks and order types not built were not said | Fixed, verified | D-157 |
| 23 minor | Mock discovery | Fixed | D-157 |
| 24 minor | Ballot reason codes | Fixed, verified | D-156 |
| 25, 26 minor | Test fixture and uninterpreted sub-extensions | Fixed | D-155 |

Its verification: V-01 (major, the `response` reference) open; V-02 to V-04 (no test for three rules)
and V-05, V-06, V-08, V-09 (wording) fixed; V-07 (no kind for a broken rule) open; V-10 (tests edited
outside the CRD files) accepted by the integrator at the merge (D-175, item 9); V-11 (DTR's Patient and orders) open; V-12 (scenario
labels) decided otherwise, D-174.

**PAS compared with 2.0.1.** 9 blockers, 11 majors, 6 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| PAS-01 blocker | A missing review action called a missing required element | Fixed, verified | D-159 |
| PAS-02 to PAS-08 blocker | No fullUrl, no item category, HCPCS and supporting information systems, no insurer, an inquiry with no item, a coverage status | Fixed, verified | D-141, D-142, D-158 |
| SUB-01 blocker | A placeholder subscription topic | Fixed, verified | D-160 |
| SUB-02 major | No filter by requesting organization | Fixed, verified | D-160 |
| PAS-09 major | Item authorization number not read where 2.0.1 puts it | Fixed, verified | D-159 |
| PAS-10 major | A part's name | Fixed | D-159 |
| NOT-01, NOT-02 major | A notification's nested bundle and claim id | Fixed, verified | D-159 |
| PAS-11 major | Item trace number and order | Fixed, verified; reading by trace number added after the verification | D-158, D-159 |
| PAS-12 major | Attachment type and category | Fixed, verified | D-158 |
| ATT-01 major | Attachments after "attachment required" | Fixed in another form: the result names the guide's route; nothing builds it | D-161 |
| PAS-13 major | An inquiry with no ClaimResponse | Fixed, verified | D-159 |
| MOCK-01, MOCK-02 major | Scenario shapes | Fixed, verified; responses still refer to resources they do not carry | D-159, D-174 |
| PAS-14 minor | A review action outside its place | Fixed in another form: flagged, no finding | D-159 |
| PAS-15 minor | A code sent without its system | Fixed | D-158 |
| MOCK-03 minor | Scenario 7 and 8 shapes | Fixed; the guide's PAS Task route to CDex is not modelled | D-159 |
| SUB-03 minor | Subscription defaults | Fixed | D-160 |
| SPEC-01, SPEC-02 minor | The build prompt against 2.0.1 | Recorded | D-173 |

Its verification: V-01 (major, a secret sent back reached the designed view) fixed for PAS and
subscriptions, open for CRD, DTR and lookups; V-02 to V-10 fixed, V-07 by correcting the reason
given for ATT-01 (D-161).

**DTR compared with 2.0.1.** No blocker; 8 majors, 6 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| DTR-01 major | Coverage named in a ballot-only extension | Fixed, verified | D-164 |
| DTR-02 major | No intended use | Fixed, verified; the verification found CRD's purpose dropped at the package step, then fixed | D-164 |
| DTR-03 major | A person's answer with no author | Fixed, verified | D-164 |
| DTR-04 major | The copied questionnaire kept url and profile | Fixed, verified (profile after the verification) | D-164 |
| DTR-05 major | No `authoredOn` on the order | Fixed, by the CRD fix | D-157 |
| DTR-06 major | The order not in the PAS bundle | Fixed, by the PAS fix | D-158 |
| DTR-07 major | Questions governed by expressions reported as unanswered | Fixed, verified | D-165 |
| DTR-08 major | Scenario shapes | Fixed; some departures kept on purpose | D-166 |
| DTR-09 minor | Wording of a package with no bundle | Fixed | D-165 |
| DTR-10 minor | A response with no item handed to PAS | Fixed; the verification found the headline wrong, then fixed; several questionnaires open | D-166 |
| DTR-11 minor | Items on a first next-question | Fixed in another form | D-164 |
| DTR-12 minor | The response's author | Fixed | D-164 |
| DTR-13 minor | Version statements | Fixed | D-165 |
| DTR-14 minor | The build prompt understates the package | Recorded | D-173 |

Its verification: V-01 and V-02 (blockers: a wrong headline; a profile 2.0.1 would refuse) fixed;
V-03 to V-07 fixed (D-164, D-165).

**Reviews of the three finished frontend parts**, in a browser, by agents that did not write them:
the case (2 blockers, 5 majors, 8 minors), the Config tab (4 blockers, 8 majors, 10 minors), the
History tab (1 blocker, 8 majors, 7 minors). Each was fixed on its branch by another agent; what
needs the server or the shared frame is in the open list above. None of it is in this revision.

### What exists at this revision

- **Protocols.** CRD: discovery, order-sign and order-select, a conformance checker that judges by CRD
  2.0.1, 14 mock scenarios. DTR: questionnaire package, the adaptive next-question loop, the
  response step, 15 mock scenarios. PAS: submit, inquire, subscriptions (create, read, list, update,
  delete), the notification receiver, 21 mock scenarios. A request sent from the collection as it is,
  with one mock scenario. A request of the tester's own on every protocol.
- **Configuration.** Postman environments and collections uploaded and stored, the operation mapping,
  variable resolution, the production guardrail judged by destination, credentials in collections
  named and masked.
- **Lookups and code list.** Member and provider lookup with 51 synthetic member fixtures and provider
  fixtures; the placeholder procedure code list and the picker.
- **The ledger.** JSON Lines, the tracking policy, notes, health, the CSV export, replay, case lines.
- **Suites and the chain.** Suites from CSV or JSON with a truthful preview and comparison with an
  earlier run; the chain as a suite case and the chain view.
- **The request comparison** of a pasted CRD request with the tool's own (`POST /api/crd/compare`).
- **The HTTP edge**: bound to this machine, the request guard, no-store, the body limit.
- **README, `run.sh`, `run.cmd` and samples** (a Postman environment and collection, a code list, a
  suite).
- The build: green on `33893aa`; 2517 tests in the XML reports, 2515 passed and 2 skipped (see the
  top of this file for the integrator's printed count).
- No frontend.

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | done, reviewed by six seats, frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | done and merged |
| Spine 2: integrate Wave A. V2 PHI audit | done. V2 ran once, before the fixes; its findings are fixed and the fixes verified; a second run has not happened |
| Spine 3: CRD reference vertical | done. Held to CRD 2.0.1 since the fix wave |
| Wave B: B1 PAS and subscriptions, B2 DTR | done and merged. Held to PAS 2.0.1 and DTR 2.0.1 since the fix wave |
| Spine 4: integrate Wave B, chain, suites, cross-protocol tests. V3 guardrail audit | done. V3 ran once, before the fixes; its findings are fixed except G4 (partly) and part of G3; a second run has not happened |
| Fix wave: every audit and review finding | merged at `33893aa`. The changes one fix needed in another's files are being made on two branches |
| Wave C: C1 frontend | three parts finished on branches, three being built with the frame. None merged |
| Wave C: C2 docs and scripts | merged. `run.cmd` never run |
| Wave C: C3 verification V1 to V7 | V2, V3, V4 ran once before the fixes. V1, V5, V6, V7 not run |
| Spine 5: clean build twice, fresh-clone build, push, pull request | not run as a step. The branch is pushed at every integration (at `33893aa` now); the clean builds, the fresh-clone build and the pull request have not been done |

### How the build got here

**Interrupted three times.** About an hour into Waves A and B every background agent was stopped at once
by a usage limit. What each had written survived in its worktree, and each half-built package was
handed to a new agent in the same worktree; since then every agent commits a checkpoint after each
green slice. The verifier planned after each wave package was dropped then, and the section 27
agents and independent reviews took its place. Late in the build the machine restarted, most likely
out of memory with nine build daemons of two gigabytes each running. Nothing on disk was lost; each
task was handed on in its worktree, and the number of agents running at once was halved. From then
on the work not yet merged is also kept on the remote, on the session's own branch, as commits that
change no file and only hold the other branches' heads. The machine restarted a second time, again
when several builds ran side by side, and again nothing on disk was lost. Since then every build on
the machine goes through one lock.

**Added by the operator during the build (D-118, D-119, D-136):** paste a request of your own and send
it; compare a pasted CRD request with the tool's own, each difference explained; hold the tool to
version 2.0.1 of the guides. The first two work on the server; their screens are part of the CRD tab
being built. The CRD and DTR 2.0.1 packages were supplied by the operator as files.

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

Target: level 1, everything in the build prompt. At revision `33893aa` no level of section 0 is met
as written, because every level assumes the screens and there is no frontend on this line. The
server side holds everything level 1 asks of it, the optional capabilities of section 28 included:
CRD, DTR and PAS implemented and not stubbed, the chain, suites, conformance checking and response
times, with the gaps listed under "Not done" above. The frontend is three parts finished on branches
and three being built. The level delivered is stated at the top of the handover, once the frontend
is merged and the gates that have not run have run.
