# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

This describes revision `5763c41` on branch `epa-pave`, which is on the remote. It holds the line of
`7dade28` (the fix wave, the two leftover tasks and the five tabs), the pass that joined the tabs
into one tool, the README brought up to the tool as built, the two server tasks of what the reviews
of the screens asked of the server, a second pass that made the screens read what the server then
said, and the fixes for what the second run of three audits of section 27 found. The integrator
built it once through the shared build wrapper: 2707 tests, 2705 passed, 2 skipped, none failed.
The two skipped tests need a folder that cannot be written
(`ConfigStoreTest.aDirectoryThatCannotBeWrittenFailsAtStartupNamingThePath`,
`JsonlRunLedgerTest.startupFailsWhenTheDirectoryIsReadOnly`), which a build running as root cannot
make; the same refusals are covered by tests that did run. The decisions of the fix wave are D-140
to D-175, those of the leftover tasks and the frontend D-176 to D-201, and those of the join, the
server work for the screens and the second audits D-202 to D-228.

### Gates, first

- **Section 27.** The member data audit (V2), the guardrail audit (V3) and the fixture audit (V4)
  have had their second run, on revision `0658a7c`, each by an agent that wrote none of the tool.
  They found three blockers and several majors; the tables below list every finding. **Their fixes
  are merged at this revision and have been checked by nobody but the agent that wrote each.** A
  check of each fix by an agent that did not write it is planned for the final revision and has not
  run. **V1 (the build auditor, from a fresh clone), V5 (house style), V6 (honesty) and V7
  (completeness on screen) have not run at all.** The house style rules of V5 are enforced by the
  build on every file, which is not the same as the audit.
- **"Prove it before handover."** Part 1, the walkthrough by a business tester, has not run. The two
  proofs of part 2 (no authorization required told apart from no determination; the answers
  completed in DTR inside the outgoing PAS request) were seen on the real tabs, on mock, by the
  second pass over the screens on revision `0658a7c`, by browser scripts, at 1366 by 768 and 1920 by
  1080, and are quoted in its report and in D-208. They were not looked at again on this revision.
  `P32H-NO-AUTH` and `P32H-EMPTY` were not looked at in a suite or a chain on screen.
- **No fresh-clone build of this revision.** Spine 5 has not run as a step: the branch is pushed, but
  the two clean builds with plain `./gradlew`, the fresh-clone build and the pull request have not
  been done. Every build reported here went through the shared wrapper, which builds offline.
- **`run.cmd` has never been run**: there is no Windows machine here, and no agent ran on Windows.
- **Nothing has been observed against the vendor's service.** Every live check any agent reported
  used a stand-in on a loopback port. Which version of the guides the vendor's sandbox follows is not
  known; the tool is held to 2.0.1 (D-136). Four questions put to the operator have no answer
  (D-227).

### Not done, not verified, or known to be wrong

**Started from this revision and not on it.** Three pieces of work began from `5763c41` at the
moment these records were written: a server task, a pass over the screens and an update of the
README and of the pull request draft. D-226 lists, line by line, what the integrator decided for
each. None of it is done here, and every item below that D-226 covers is still open.

**Left open by the second audits and their fixes.**
- *Guardrail.* After another browser presses Lock, the Config tab of a session that was unlocked goes
  on saying writes are unlocked and that a submission sent now is real, until it is reloaded, while
  the banner beside it says locked (F-SCREEN). The server refuses the write. The unlock panel does
  not say that Lock ends every session's unlock (m2). A write refused at the send because writes
  were locked again is worded by the shared error handler as a change of configuration, not with
  the gateway's sentence (D-214). No screen shows `addressWarnings`; only the upload answer carries
  those sentences. IPv6 destinations were not proven against a listener (no IPv6 loopback here). No
  real nginx or Tomcat in front of a service was tried: the risk behind M-ENC and m3 rests on how
  such servers are documented to behave. A replay that reaches the gateway against production on
  live was driven by neither the audit nor the fix. A FHIR search with an encoded character in its
  query was checked in a test only. For B1, M-ENC, m3, m1, F5, F6 and F7 the fix's tests were not
  run against the code before the fix; only T1 and GAP1 were shown to fail without it. A process
  killed outright leaves the embedded server's working folder behind. The sample collection's upload
  now warns that it carries saved examples.
- *Member data in the ledger.* A note, a tester reference or an expectation that holds a name, a date
  of birth or an address the request does not carry is written as typed: member data must not be
  typed into them, and no run form says so before a refusal (the History note field does). Short
  values are not looked for, and a member whose name is also a common word makes the run refuse a
  note holding that word. Older ledger lines that hold a name or a date in a note are shown and
  exported as written. A one-word surname in a code or an id is kept (`sub-Zzlastname` was written as
  it came on the fix's live run). The refused tester reference and expectation were seen through the
  API only, and the refused note on the CRD tab at 1366 by 768 only; DTR, PAS and History were not
  looked at in a browser for these refusals. PAS inquiry and subscription read, update and delete
  were not run on live by hand. The configuration store keeps uploads exactly as uploaded and can
  hold member data an uploaded file contains (D-213): the upload warns by the names of variables and
  saved examples, and nothing is stripped.
- *Fixtures.* An `expect` block whose outcome contradicts its own scenario file, with the same HTTP
  status, is not detected and its outcome is still advertised. The scenario lists carry `notes` and
  the PAS sentinels of CRD, and no screen shows them. Two sentences of `CrdConformanceChecker` and
  the label of a code list finding on screen call any list that says it is synthetic the
  placeholder "that ships with the tool" or "the bundled placeholder", untrue of the shipped sample
  and of a tester's own marked list. A chain suite with the DTR sentinels was driven by a test and not
  by hand. Only the CRD tab with `P32H-DTR-ADAPT` into DTR was looked at on screen.

**The screens, after the two passes over them.** Both passes drove the tool in a real browser with
scripts, on mock unless said otherwise. What neither did, or what is still wrong:
- *Not swept, or not swept again.* One state one look (a refused form, a blocked write, a timeout, a
  service that cannot be reached, nothing configured, a run not recorded) was not compared tab by tab;
  the second pass made the action names agree. Production locked, unlocked and blocked by the floor,
  a second browser context, UAT, the live profile with an empty store, tracking switched off, a ledger
  that cannot write, a suite from the samples and a date-range replay were driven by the first pass
  only, at 1366 by 768, before the second pass changed many shared files. The colour key was gone
  through at both sizes by the second pass; `STOP` and `FAULT` were reached on purpose, the tone of an
  unmatched notification was not.
- *Never drawn on screen.* The answers dropped in an adaptive questionnaire (read in code, pinned by a
  test), a CRD coverage section with no "applies to", an unmatched notification, the statement that a
  notification named an id the ledger does not keep, `lookupEnabled` and `blockedShort` (read in code),
  the 409 path of a mapping change, a 422 on a mapping, a DTR question of an unrecognised type, a
  quantity, an attachment, a reference, an open choice from a value set only, a repeating text
  question, a questionnaire of undetermined kind, a wrapped next-question step, a DTR package request
  of the tester's own, DTR "could not be reached" on live, the UAT banner, History's view and replay of
  a DTR run, PAS on live with an unreachable sandbox, the masking of a channel header on screen, a PAS
  payload over the 131072-byte cut.
- *Seen at one size only.* The echo of secrets on live (1366); production, the ledger states, the
  suite and the replays (1366, first pass); the refused note (1366); most DTR states (1366, its own
  pass). No screen reader was used on any tab, contrast was never measured, 200% zoom and phone width
  were never tried by either pass (at 390 pixels, in the review before them, the History runs table
  and the CRD elements table ran past the edge), and `prefers-reduced-motion` was checked only by
  reading the stylesheets. Behaviour on screen has no test in the build: the static tests check the
  files, links, imports and the server words a script reads; everything else was checked by browser
  scripts kept outside the repository.
- *Still wrong on screen, and decided for the next pass or the server (D-226).* A lookup answer that
  holds a masked value fills the case field with the mask text, which a later run would send as data.
  On mock a played timeout is said twice on every protocol tab, and on PAS the verdict says the payer
  may have received the submission beside "Nothing was sent", with the next step "check the
  environment, then send again" where no environment failed. The raw resolve view says a run with a
  url part missing is still answered from a scenario, while the raw preview and send refuse it. An
  empty `issue.expression` shows in the issues table and again among the fields returned but not
  interpreted. The comparison's `notes` repeats the caveat, and the page drops it by its text. The
  body source of a raw send is served as a code only, and the page shows the server's sentence.
- *Older, still true.* A tester reference or a note typed on one tab is not carried to the next (each
  tab has its own fields). The PAS detail and the notifications list are not given the page's masking
  stopgap and rely on the server. When a pasted request and the case disagree, only a member ID gets a
  button; two dates of service that disagree are listed with no button. The vendor's sample on the
  CRD tab passes through the browser, and the tool's own hook instance is always one difference. The
  slow-response flag was seen only on mock with a lowered threshold. A run-value field for a builder
  variable such as `subscriptionHeartbeatSeconds` says where the request uses it was not reported,
  because `/api/config/resolve` does not describe builder variables. A DTR quantity's unit is free
  text and an answer the response holds is never put back into a control (the server gives display
  text). Nothing shows how an inquiry's answer differs from the last one seen. The blank "Meaning"
  cell a review saw on a subscription status row was never reproduced.

**The server, by endpoint.**
- *Refusals of a replay or a suite row on production.* Proven together by
  `ProductionRefusalOverTheServerTest` over the running server, and a refused PAS replay was seen on
  screen by the first pass. A refused suite row was not driven by hand on a merged line.
  `ChainServiceTest` pins what a chain view says for a blocked replay, a state the tool cannot reach,
  because a replay never joins a chain (D-208); bringing it into line is decided and not done.
- *Configuration.* A 404 for a removed environment does not name it (decided, D-205). A placeholder in
  a collection body is not counted by "can this operation be sent" (decided, D-205).
- *CRD.* `POST /api/crd/compare` cannot take a collection request by name, and ignores `configEpoch`.
- *DTR.* A failure of the tool inside the response step, which sends nothing, answers 500 and writes no
  ledger line (from the first DTR report, not checked again). The intended use code system in the PAS
  bundle was not checked against DTR 2.0.1. Replay of a DTR run through the running tool was not driven.
- *Older items still true.* A run with the tester's own body records no member, NPI or line of
  business, and nothing on its ledger line tells two such runs apart (CASE-05). A CRD or PAS step of a
  chain handed only the procedure codes its line already holds is still marked as not replayable alone
  (F7). A second suite started while one runs is answered 409 with kind `VALIDATION`. A well-formed
  chain id the tool never issued is accepted. `SuiteCases` refuses a hook with "The hook must be
  order-sign or order-select." rather than the CRD service's sentence. "Existing authorization" is
  still in `RedactionTest`, and "already satisfied" in the description of the member fixture
  `p32h-pa-sat.json`. A lookup fixture file that repeats a key is read. After the ledger folder was
  restored, the server kept saying it cannot write until the next write succeeded; nobody has looked
  into why. A lookup identifier with an encoded slash in the path form still ends as a bare 400 (the
  query form works, D-189).
- *The README.* It was brought up to the tool at the join (D-209), before the server tasks for the
  screens, the second pass and the audit fixes were merged. Sentences those made untrue include
  "Show the request before sending" (now "Show the request"), the presentation of the sample code
  list and its self-check (D-223), and anything saying that `P32H-PAS-TIMEOUT` or `P32H-PAS-ERR500`
  has no inquiry answer or that a secret written as a JSON number is not masked. It does not yet
  carry the sentences of D-213 and D-217, the refusal of encoded separators, what Lock does, or how a
  notification is masked. Its list of sentences that quote the screen word for word has not been
  checked again.

**Limits each part reports that are still true.**
- CRD: US Core 3.1.1 PractitionerRole was not checked (the package is not here; it may require both a
  practitioner and an organization, and the tool sends one or the other). `Organization.active` is
  never sent (D-141).
- PAS: no claim update and no `$submit-attachment` (D-161); a resubmission reuses the claim id
  (D-133). US Core 3.1.1, HRex, the subscriptions backport 1.1.0 and the X12 code lists were not
  available; the 2.0.1 examples were not compiled; the request was checked against hand-written 2.0.1
  rules that do not test slices or invariants. Mock X12 reason and category codes are placeholders.
  The guide's PAS Task route to CDex is not modelled by any scenario (MOCK-03).
- DTR: SDC 3.0.0, US Core 3.1.1, FHIR R4 core and the 2.0.1 narrative pages are not here, so only the
  invariants dtrx-1 and dtrb-1 are evaluated and the next-question address is an assumption. An
  answer's author carries no role. `$expand` and `$log-questionnaire-errors` are not implemented. The
  rules DTR-07 names were tested only with made-up questionnaires, never with a real one.
- The chain: a DTR package that goes straight on to PAS is driven by no test and no mock scenario.
- The guardrail: everything in D-145 and D-225. Another DNS name for a production host is not
  recognised. A production environment whose address comes from an operating system variable has no
  destination the tool can remember. A lock that lands between preparing and sending a write is
  tested over the server for one submission (D-214) and at the gateway.
- Credentials: one typed into a path segment or a host of a collection is shown as written; a secret
  in the path of a call back address with no variable is shown. The collection file keeps the value on
  disk.
- The HTTP edge: only Linux, curl, raw sockets and headless Chromium were used; Windows, Firefox and
  Safari were not tried. `OPTIONS *` carries none of the tool's headers (D-150). A cross-site request
  that carries neither `Sec-Fetch-Site` nor `Origin` is let through (D-148). Several uploads of 30MB
  held in memory at once were never tried.
- The ledger: the live profile was checked in unit tests and on the fixes' live runs against stand-ins.
  Folders and day files made before the first fix keep their old modes. Flush and sync are checked by
  reading the code. Two instances on one ledger directory are not coordinated. A note written after
  the clock was set back across midnight is on disk and not applied.
- Fixtures: any new top-level key in a scenario file stops the mock start until the loader knows it.
- Suites: whether a code assertion's value is in its value set is not checked before sending. The test
  ledger does not apply case lines as the real one does.
- The code list: no real P32H export was available, so its header row, flag spellings, date format,
  time zone, vendor names, encoding and columns per line of business are unknown. A real export whose
  notes column happened to hold the marker "Not from a P32H export." would be taken as synthetic. The
  first-row message still shows cells of letters alone.
- Lookups: neither has met the real member API or provider directory; both response mappings are
  assumptions (section 26). A name returned in one piece cannot prefill first and last name.
- Notifications: the receiver does not authenticate its caller and has never been called over a socket
  by a real service; with the default bind address no vendor can reach it, and `GET /api/mode` says
  so. Two notifications at the same moment are not tested.
- Postman: no real vendor export was available; every collection the tests use is hand-written.
  Concurrency in the store is reasoned about, not tested.
- The request comparison: no real vendor request was available (D-227). A placeholder whose value
  exists only in a collection's variables is reported as having no value. Codes in NutritionOrder,
  VisionPrescription, CommunicationRequest and Appointment are not read.
- The wire view and every masking that follows its rule: a secret sent back re-encoded (percent,
  base64, another case) or shorter than six characters is not found.
- Documentation: STS, a mirror that needs credentials and a token server were not tested. A plain
  `./gradlew build` can skip the sample tests after a sample is edited; `clean build` runs them.

**Checked only by unit tests or at the gateway, never in the running tool.** A chain interrupted
part way writing its case line, and the write note of a suite on a production target; a failure of
the tool inside a step; the replay paths for a refused claim id and an authorization number in
several groups; the split of TLS failures into reached and not reached; a secret sent back as a JSON
number; the notification next step for a subscription id the ledger does not keep (seen on the jar
by the fix, not on screen). Not checked at all: whether a chain's DTR step can produce each fault
outcome a suite file may expect of it.

**Seen on the running application, against a stand-in, and pinned by a test.** A secret in the call
back address that the service echoes in its Subscription is masked in every view of the answer
(`SubscriptionServiceTest`). A notification repeating a secret variable, the token, the client
secret and a call back credential holds none of them in the list of notifications, the ledger or the
log (`SecretsSentBackOverTheServerTest`, D-215). A note holding the member ID is refused on the CRD
tab and marks its field (`LedgerPhiTest`, D-217). The DTR sentinels from CRD into DTR (D-222).

### What the audits found and what was done

State is one of: fixed (verified means a second agent attacked the fix and it held, or what it found
was then fixed), fixed and not yet verified by another agent, fixed in another form, partly fixed,
not fixed, refuted, decided otherwise, left as a known limit. "Open" means it is listed above as not
done.

#### The second run, on revision `0658a7c`

Every fix in these three tables was made by one agent and **has been checked by nobody else**. The
test named is the one its author ran against the code with the fix taken out and saw fail; "none
named" means no such run was reported.

**Member data audit (V2), second run.** 1 blocker, 3 majors, 3 minors. Its answer: yes, on the live
profile with default tracking the ledger and the export could hold a member ID, a name and a date of
birth, three ways.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F0 | blocker | A secret a service repeats in a notification was shown in clear | The notification is masked with every secret the tool could have sent | `SecretsSentBackOverTheServerTest`, `NotificationReceiverTest` | Fixed, not yet verified by another agent | D-215 |
| F1 | major | Note, tester reference and expectation were written as typed, member data included | Refused at the way in, checked on a note added later, and not written by the record | `LedgerPhiTest`, `LedgerMemberTextTest`, `SuiteCasesTest` | Fixed, not yet verified by another agent; a name or date the request does not carry is a known limit | D-217 |
| F2 | major | Ids a service supplies kept by shape alone | Kept only when they read like neither an identifier nor a date; a notification says when it could not be looked up | `NotificationIdsNotKeptTest`, `LedgerMemberTextTest` | Fixed, not yet verified by another agent; a one-word surname is kept | D-218 |
| F3 | major | The member ID typed as a procedure code was written in clear | Written as `(not a code, N characters)`, in the codes and the subjects | `LedgerMemberTextTest`, `LedgerPhiTest` | Fixed, not yet verified by another agent | D-218 |
| F5 | minor | The configuration store keeps member data an upload holds, and nothing says so | The upload warns by names and saved examples; the store keeps uploads whole | None named | Fixed in another form (warned, not stripped), not yet verified by another agent; no screen shows the warning apart from the upload answer | D-213 |
| F6 | minor | The log line of an unnamed failure fired for lookups | Not written for a lookup | None named (`FailedCallLogTest` added) | Fixed, not yet verified by another agent | D-216 |
| F7 | minor | Every start left an empty server folder in the temporary directory | The server's folder is removed at stop | None named (`TomcatBaseDirectoryTest` added) | Fixed, not yet verified by another agent; a hard kill leaves it | D-216 |

**Guardrail audit (V3), second run.** 1 blocker, 4 majors, 4 minors. Its answer: yes, a port written
with leading zeros took a write to production with no unlock, and an encoded `../` got a write past
a locked production as a read; every other route tried was refused.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| B1 | blocker | A port with leading zeros was not read as a production address | The address is read as the HTTP client reads it; an unreadable destination refuses a write and is warned about | None run against the old code (`AddressSpellingGuardrailTest`, `DestinationOnTheWireTest` added) | Fixed, not yet verified by another agent; IPv6 not proven | D-210, D-212 |
| F-SCREEN | major | After another browser's Lock, the Config tab still said writes were unlocked | Nothing: the page's | | Not fixed: decided for the next pass over the screens (open) | D-214, D-226 |
| M-ENC | major | An encoded `../` in a run value turned a read into a write behind a decoding server | No request leaves with an encoded separator in its path | None run against the old code (`EncodedSeparatorGuardrailTest` added) | Fixed, not yet verified by another agent; no real front server tried | D-211 |
| T1 | major | No test failed when Lock stopped locking every session | A test over the running server | `SecondGuardrailAuditOverTheServerTest` (with the lock call removed) | Fixed, not yet verified by another agent; the refusal at the send is worded generically (open) | D-214 |
| GAP1 | major | The known gap of F0 above | As F0 | As F0 | Fixed, not yet verified by another agent | D-215 |
| m1 | minor | A raw send of a mapped request was refused with an untrue reason | The reason names the mapping and says the method decides | None named | Fixed, not yet verified by another agent | D-216 |
| m2 | minor | The unlock panel does not say what Lock does | Nothing: the page's | | Not fixed: decided for the next pass over the screens (open) | D-214, D-226 |
| m3 | minor | An environment at `/pa%2Ffhir` took writes on a production server | Nothing is sent to it, and it is warned about at upload | None run against the old code | Fixed through M-ENC, not yet verified by another agent | D-211 |
| m4 | minor | A non-production environment on a production server under another path, with no word | Warned wherever it is listed; still accepted | None named | Fixed in another form (warned, not refused), not yet verified by another agent; no screen shows it (open) | D-212 |

**Fixture audit (V4), second run.** 1 blocker, 4 majors, 5 minors. Its answer: mostly yes; every
sentinel picks its scenario and all 14 CRD, 15 DTR and 21 PAS scenarios give the outcome their label
states; four gaps.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F-CHAIN-DTR | blocker | A DTR sentinel taken from CRD into DTR ended as "package incomplete" for six scenarios | CRD names the questionnaires that DTR scenario's package holds | `DtrSentinelJourneyTest` (two tests) | Fixed, not yet verified by another agent | D-222 |
| F-RAW | major | Cross-protocol sentinels skipped in a pasted body by CRD and PAS | One rule for a typed and a pasted identifier | `ScenarioRepositoryTest.aForeignSentinelInAPastedBodySelectsAsTheSameIdTypedOnTheFormDoes` | Fixed, not yet verified by another agent | D-220 |
| F-DUP-CRDSENT | major | Two DTR files could claim one CRD sentinel | The duplicate check covers `crdSentinelMemberIds` | `ScenarioRepositoryTest.aCrdSentinelClaimedByTwoDtrFilesIsAProblemNamingBothFiles` | Fixed, not yet verified by another agent | D-221 |
| F-PAS-SHADOW | major | The PAS mapping of CRD sentinels was a Java map the check did not know | Scenario data in the PAS files; the map is gone | `ScenarioRepositoryTest.aPasFileThatClaimsACrdSentinelAnotherPasFileAnswersIsAProblemNamingBothFiles` | Fixed, not yet verified by another agent | D-221 |
| F-SAMPLE-LIST | major | The sample code list was compared with the service as if real | A list that carries the marker is synthetic | `CodeListConformanceSeamTest` (three tests), `SampleCodeListTest` | Fixed, not yet verified by another agent; the checker's sentences and the screen label still say "bundled" (open) | D-223 |
| F-PAS-CRDMAP-HIDDEN | minor | The PAS scenario list showed no CRD sentinels | Read from the files | `ScenarioRepositoryTest.thePasFilesSayWhichCrdSentinelsSelectThemAndTheListShowsIt` | Fixed, not yet verified by another agent; not shown on screen (open) | D-221 |
| F-V4-02-REST | minor | An `expect` at odds with its file, and a file numbered 0 as a silent default | The default is named; an `expect` HTTP status at odds is noted | `ScenarioRepositoryTest` (two tests) | Partly fixed, not yet verified by another agent; an `expect` outcome at odds with the body is a known limit | D-224 |
| F-ORPHAN | minor | A file with another extension or in a subfolder ignored without a word | Named in the list's notes and one warning at start | `ScenarioRepositoryTest.aFileThatIsNotReadTheDefaultAndAnExpectBlockAtOddsWithItsFileAreSaidAndDoNotStopTheSet` | Fixed in another form (said, not refused), not yet verified by another agent; the integrator passed the notes on; no screen shows them (open) | D-224, D-228 |
| F-UNMATCHED-LOOP | minor | The sample that matches nothing told the tester to repeat what changes nothing | The next step reads the ledger and names a sample that would match | `NotificationReceiverTest.theSampleThatMatchesNothingByDesignSaysSoOnceASubscriptionExistsAndNamesASampleThatWouldMatch` | Fixed, not yet verified by another agent | D-224 |
| F-NPI-TEST | minor | A test NPI that passes the check could be a real provider's | Replaced everywhere; a scan fails on any NPI outside the placeholder set | `NoNpiOutsideThePlaceholderSetTest` proves it catches the old number | Fixed, not yet verified by another agent | D-224 |

#### The first run, and the reviews

**Member data audit (V2), first run.** No blocker; 7 majors, 10 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| M1 major | A questionnaire address on the ledger line could hold a member ID, a name or a date | Fixed, verified. The verification found the first fix checked shape only; each part is now checked | D-151 |
| M2 major | A code that is really an identifier was kept | Fixed in another form: identifier-or-date rule; a one-word surname is still kept | D-151 |
| M3 major | Any text was kept as a line of business | Fixed, verified | D-151 |
| M4 major | The server logged an unparsable cookie or an unmappable address | Fixed, verified (log levels) | D-150 |
| M5 major | Note, tester reference and expectation had no limit | Fixed, verified; a suite row's over-long expectation is reported by its length alone. Its other half, a note that holds member data, was found again by the second run (F1) and fixed | D-151, D-189, D-217 |
| M6 major | Uploads were written to the temporary folder | Fixed; the hard kill was checked by hand only | D-149 |
| M7 major | A credential typed into a collection was stored and shown | Fixed, verified | D-147 |
| m1 minor | Names that hold credentials were not treated as secret | Fixed | D-147 |
| m2 minor | Some answers carried no `no-store` | Fixed, except `OPTIONS *` | D-150 |
| m3 minor | A headerless suite file had cells quoted back | Fixed | D-168 |
| m4 minor | File modes | Fixed for the ledger and the configuration store; Windows is a limit | D-147, D-151 |
| m5 minor | On mock any member ID was written in the clear | Fixed | D-151 |
| m6 minor | A tester's own body put its orders on the ledger line | Fixed, verified: only codes of order resources, an uncoded order by position | D-182 |
| m7 minor | Records printed text in `toString` | Fixed, the code list's CSV records included | D-152, D-189 |
| m8 minor | Two log statements had no comment | Fixed | |
| m9 minor | A query the server cannot read was a 500 | Fixed | D-149 |
| m10 minor | The runtime folders follow the working directory | Fixed in another form: logged, with a warning inside a git tree | D-147 |

**Guardrail audit (V3), first run.** 4 blockers, 5 majors, 2 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| G1 blocker | A run value, or an unlock for another production environment, let a write through | Fixed, verified; a neighbour through a port written with leading zeros was found by the second run (B1) and fixed | D-143, D-144, D-210 |
| G2 blocker | With a sandbox active, a write reached a production address from a run value, a literal or `application.yml` | Fixed, verified; the same neighbour (B1) | D-143, D-210 |
| G3 blocker | A read could be pointed at any host with the token | Fixed, verified, for every call; a request sent from the collection as it is now attaches by scheme, host and port, proven on the wire | D-143, D-144, D-184 |
| G4 blocker | A refused replay was told writes are locked for this session | Fixed in two halves on two branches; seen together since through the running server by a test, and a refused PAS replay seen on screen | D-143, D-183, D-205 |
| G5 major | Dot segments let an inquiry act as a write | Fixed, verified, including `..;`; a url part of `.` or `..` is refused on a raw send; an encoded `../` was found by the second run (M-ENC) and every encoded separator in a path is now refused | D-144, D-184, D-211 |
| G6 major | A deleted production environment could return as a sandbox | Fixed, verified; the second run found it open for a neighbour through B1, since fixed | D-143, D-210 |
| G7 major | The "not production" acknowledgement covered urls from `application.yml` | Fixed, verified | D-143 |
| G8 major | The unlock's name check had no test | Fixed | |
| G9 major | Locking again changed the epoch suites pin to | Fixed, verified | D-143 |
| G10 minor | "prod" was looked for before escapes were decoded | Fixed | D-143 |
| G11 minor | A refused lookup was shown as switched off | Fixed in another form: `FAULT`, `BLOCKED` | D-146 |

The verification of that fix (F1 to F15): F1 to F3, F5 to F7 and F9 to F14 fixed (D-144). F4 (the
refusal through the running application) is G4 above: seen together since (D-205). F8 (a request sent
from the collection as it is attached the token by host) fixed, verified on the wire (D-184). F15 (the
fix edited two shared files) accepted at the merge (D-175, item 9).

**Fixture audit (V4), first run.** 1 blocker, 4 majors, 6 minors. On the revision before the fixes, every
synthetic NPI passed the tool's check and all 240 scenario runs gave what their files claim. The
CRD, DTR and PAS scenario files were rewritten after it (and PAS scenario 21 added); the second
run, above, audited them again.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| V4-01 blocker | The placeholder code list was compared with a live service as if real | Fixed, verified, for the bundled list; its neighbour, the sample list, was found by the second run (F-SAMPLE-LIST) and fixed | D-169, D-223 |
| V4-02 major | A damaged scenario set was never refused | Fixed, verified; what the first fix left (a CRD sentinel claimed twice, a silent default, a file not read) was found by the second run and fixed or said; an `expect` outcome at odds with its file is a known limit | D-153, D-188, D-221, D-224 |
| V4-03 major | The lookups did not know the DTR and PAS sentinels | Fixed, verified | D-153 |
| V4-04 major | Sentinels did not mean the same case across protocols | Fixed, verified, in each protocol | D-154 |
| V4-05 major | No test scanned CRD and PAS scenarios for identifiers | Fixed, verified | D-153 |
| V4-06 minor | Two synthetic data sets contradicted each other | Fixed by rewording scenario 10 | D-154 |
| V4-07 minor | Tests used a member ID outside the placeholder style | Fixed | |
| V4-08 minor | A sentinel in a pasted body matched only case-sensitively | Fixed, verified; a foreign sentinel in a pasted body was found by the second run (F-RAW) and fixed | D-153, D-220 |
| V4-09 minor | A sentinel's refusal named the scenario field | Fixed | D-154 |
| V4-10 minor | Scenario 8 described a request it did not check | Fixed by description | D-154 |
| V4-11 minor | The NPI check is Luhn only | Not changed: a question for the operator, not answered | D-153, D-227 |

Its verification: VF-01 (a damaged scenario set reported on a suite row as "not valid", and a chain
losing its CRD run) fixed: the row is `NOT_CONFIGURED` with a sentence of its own, seen on the jar for
a CRD row and a chain row (D-188). VF-02 to VF-06 fixed (D-153).

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
| E13 minor | Error answers repeat request text | Fixed, verified: no "not found" sentence repeats an identifier from the request | D-150, D-185 |
| E14 minor | D-119 described an endpoint not on the line | True since the comparison was merged | D-119 |

Its verification: V1, V2 and V4 fixed; V3 is E13, fixed; V5 done in D-150; V6 (the 400 sentence names
the query) not changed, D-174.

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
| m8 | Stop and busy answers | Fixed, except the kind of the 409, still `VALIDATION` (open) | |
| m9 | Chain edges | Partly fixed: unknown well-formed chain ids are accepted (open) | |
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
| F7 major | D-131 said a replay can run a chain; it cannot | Fixed in another form: D-131 corrected (D-138); the chain view and now the replay refusal point to a suite row of protocol `chain`. A later step is still marked not replayable when all it was handed was the procedure codes its line already holds (open) | D-138, D-167, D-189 |
| F8 major | The preview overstated what can run | Fixed | D-168 |
| F9 major | Chain rules had no test | Fixed (one mutation survived, then fixed) | |
| F10 major | Only one DTR route reachable in a chain on mock | Fixed | D-154 |
| F11 major | A row never said the default scenario answered | Fixed | D-167 |
| F12 minor | Expectation edge cases | Fixed | D-168 |
| F13 minor | Correlation columns ignored in silence | Fixed: refused | D-168 |
| F14 minor | No line names the step it followed | Fixed, verified, for suite chains and manual runs | D-156, D-181 |
| F15 minor | Mock beans exist on live | Fixed: the one left is mock only, and a test starts the live context | D-188 |

The verification of suites and the chain (VS-1 to VS-12): VS-1 to VS-10 fixed (D-167, D-168); VS-4's
file names in the chain note added (D-189). VS-11 (manual chains carry no CRD run id) fixed, verified
on the jar (D-181). VS-12 (two frozen tests edited) refuted: they are completeness guards that had to
list the two new fields.

**CRD compared with 2.0.1.** 3 blockers, 14 majors, 9 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| 01, 02 blocker | Codes 2.0.1 defines called non-conformant | Fixed, verified | D-155 |
| 03 blocker | Documentation went to PAS unless a questionnaire or link was present | Fixed, verified; the `response` reference is now handed on as a reference, verified on live | D-156, D-176 |
| 04 major | No medical record number on the Patient | Fixed, verified, in DTR's package request too | D-157, D-178 |
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

Its verification: V-01 (the `response` reference) fixed, verified (D-176); V-02 to V-06, V-08, V-09
fixed; V-07 (no kind for a broken rule) fixed, verified: `GUIDE_RULE_BROKEN` in CRD, PAS and DTR
(D-177); V-10 accepted at the merge (D-175, item 9); V-11 (DTR's Patient and orders) fixed, verified
(D-178); V-12 (scenario labels) decided otherwise, D-174.

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
| PAS-11 major | Item trace number and order | Fixed, verified | D-158, D-159 |
| PAS-12 major | Attachment type and category | Fixed, verified | D-158 |
| ATT-01 major | Attachments after "attachment required" | Fixed in another form: the result names the guide's route; nothing builds it | D-161 |
| PAS-13 major | An inquiry with no ClaimResponse | Fixed, verified | D-159 |
| MOCK-01, MOCK-02 major | Scenario shapes | Fixed, verified; responses still refer to resources they do not carry | D-159, D-174 |
| PAS-14 minor | A review action outside its place | Fixed: flagged, and now a `GUIDE_RULE_BROKEN` finding | D-159, D-177 |
| PAS-15 minor | A code sent without its system | Fixed | D-158 |
| MOCK-03 minor | Scenario 7 and 8 shapes | Fixed; the guide's PAS Task route to CDex is not modelled | D-159 |
| SUB-03 minor | Subscription defaults | Fixed | D-160 |
| SPEC-01, SPEC-02 minor | The build prompt against 2.0.1 | Recorded | D-173 |

Its verification: V-01 (a secret sent back reached the designed view) fixed, verified on live, for
PAS, subscriptions, CRD, DTR and both lookups (D-179); a request sent from the collection as it is is
done too (D-205). V-02 to V-10 fixed, V-07 by correcting the reason given for ATT-01 (D-161). A request sent from the
collection as it is reads the masked body since (D-205).

**DTR compared with 2.0.1.** No blocker; 8 majors, 6 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| DTR-01 major | Coverage named in a ballot-only extension | Fixed, verified | D-164 |
| DTR-02 major | No intended use | Fixed, verified | D-164 |
| DTR-03 major | A person's answer with no author | Fixed, verified | D-164 |
| DTR-04 major | The copied questionnaire kept url and profile | Fixed, verified | D-164 |
| DTR-05 major | No `authoredOn` on the order | Fixed, by the CRD fix | D-157 |
| DTR-06 major | The order not in the PAS bundle | Fixed, by the PAS fix | D-158 |
| DTR-07 major | Questions governed by expressions reported as unanswered | Fixed, verified | D-165 |
| DTR-08 major | Scenario shapes | Fixed; some departures kept on purpose | D-166 |
| DTR-09 minor | Wording of a package with no bundle | Fixed | D-165 |
| DTR-10 minor | A response with no item handed to PAS | Fixed, verified, in a package of several too, driven on live against a stand-in | D-166, D-180 |
| DTR-11 minor | Items on a first next-question | Fixed in another form | D-164 |
| DTR-12 minor | The response's author | Fixed | D-164 |
| DTR-13 minor | Version statements | Fixed | D-165 |
| DTR-14 minor | The build prompt understates the package | Recorded | D-173 |

Its verification: V-01 to V-07 fixed (D-164, D-165).

**Verification of the leftover tasks.** The protocol task (SP-1 to SP-5, all minor): SP-1 (three
paths the change added had no test, one of them a secret reaching the ledger) fixed with three tests
that fail without the change; SP-2 (eleven notes on the DTR package request) fixed (D-178); SP-3
(dtrb-1 raised no finding) fixed (D-177); SP-4 (a Javadoc made untrue) fixed; SP-5 (a submission from
DTR names the package run, not the response run) refuted and kept (D-181). The platform task (VSP-1
to VSP-9): VSP-1 (major, the headline of a blocked replay still said to unlock) fixed (D-183), and the
gateway's own sentence for a replay seen since through a test over the server (D-205); VSP-2 (major, no test of the chain view's next
action) fixed with a unit test of a state the tool cannot reach, since a replay never joins a
chain (D-208); VSP-3 (a garbled sentence) fixed and pinned;
VSP-4 and VSP-5 (incomplete raw urls answered differently by profile, one recorded as a run) fixed
(D-184); VSP-6 (`..` in a url part moved a read) fixed by refusing it (D-184); VSP-7 (the mock
headline) fixed; VSP-8 (no test of two lookup fixtures claiming one identifier) fixed for members and,
since, for providers (D-188, D-204); VSP-9 (a frozen test helper edited) resolved by
restoring it (D-189).

**Reviews of the case, the Config tab and the History tab** (2 blockers, 5 majors and 8 minors; 4
blockers, 8 majors and 10 minors; 1 blocker, 8 majors and 7 minors). Each was fixed on its branch
before the fix wave was merged (D-172), and is now on the line. What they handed to the frame was
done by the frame pass (the ledger indicator from health, a blocked result on mock, the colour key
entries, the preview's words, a run id as a link, PUT and DELETE, the Colour key button at phone
width, the case pane on a wide screen, the shared stylesheet's two selectors). What they handed to
the server was done by the platform task (the words and filters of D-186 and D-187), and the screens
read it since (D-206, D-207), apart from the zone of the History dates, decided not to be offered.
Still open: CASE-05 (two runs with the tester's own body cannot be told apart). The second half of
CMP-2 (a detail taken from a pasted request marked "by hand") is done (D-206).

**Review of the frame and the shared files**, in a browser. 1 blocker, 5 majors, 14 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| FR-01 blocker | A secret the service echoed shown in clear under "returned but not interpreted" | Fixed: the server masks every answer, the raw send included; the page masks as a second line | D-179, D-196, D-205 |
| FR-02 major | A form that cannot send is shown on live with nothing configured | Fixed by the join: on live each protocol tab holds its form back while its operation cannot be sent | D-206 |
| FR-03 major | "Can it be sent" looks at the url only | Fixed for the url and the headers; the body decided otherwise | D-205 |
| FR-04 major | No way to see or choose which mapped request an operation uses | Fixed: the server states which request a run uses and the Config tab shows it; no endpoint to choose, decided | D-199, D-205, D-207 |
| FR-05 major | The ledger indicator stayed in the fault frame after recording worked again | Fixed | D-197 |
| FR-06 major | Focus lost after a send | Fixed | D-197 |
| FR-07 minor | A 404 for a removed item says to check an identifier nobody typed | Fixed, except that the 404 does not name the environment, decided | D-205 |
| FR-08 minor | Mapping wording, one action with two names | Fixed | D-205 |
| FR-09 minor | Notices for a change that changed nothing | Fixed | |
| FR-10 minor | The whole environment strip was a live region | Fixed | D-197 |
| FR-11 minor | The browser keeps the grouping of finding kinds | Fixed: the server groups the kinds and the page reads them; the second half, codes breaking mid-word, refuted | D-198, D-205, D-207 |
| FR-12 minor | An unknown result drawn as a fault | Fixed | D-195 |
| FR-13 minor | A blocked raw write sent to the PAS tab, refusal said four times | Fixed | D-205 |
| FR-14 minor | The masked row was a second loud element | Fixed | |
| FR-15 minor | A 3,000-character value unfolded | Fixed | D-197 |
| FR-16 minor | Mock timeout of 400 ms; "was sent without" on an unreachable run | Partly: no Java class named, "built without", a played timeout said to be played; on mock it is said twice (open) | D-204, D-205, D-226 |
| FR-17 minor | "problem(s)", and "Nothing was sent" after a preview or an unlock | Fixed | D-205 |
| FR-18 minor | "Record this result" above NOT recorded | Fixed | D-197 |
| FR-19 minor | An unexplained heavy rule; the title did not show an unlock | Fixed | D-195 |
| FR-20 minor | The indicator's frame split at 390 pixels | Fixed | |

**Review of the CRD tab and the comparison**, in a browser. 1 blocker, 7 majors, 7 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| CMP-1 blocker | A comparison stayed on screen after the box, hook or mode changed | Fixed | D-194 |
| CMP-2 major | Fill mixed two people's details | Fixed: in the tab, and the mark "from the pasted request" in the case | D-194, D-206 |
| CMP-3 major | Focus lost after Compare | Fixed | |
| CMP-4 major | Tabbing hid rows under the pinned documents | Fixed | D-194 |
| CMP-5 major | Next difference jumped to a closed group | Fixed | D-194 |
| CMP-6 major | A credential in the pasted request shown in clear | Fixed: masked in both documents and every difference; only the tester's own box shows what was typed | D-202, D-207 |
| CMP-7 major | The caveat missing when the requests cannot be compared | Fixed | D-202, D-207 |
| CMP-8 minor | "Comparison ready." for one that was not made | Fixed | |
| CMP-9 minor | A refused comparison says "send again" | Fixed: the server words it for a comparison; an old refusal is cleared | D-205, D-206 |
| CRD-1 major | Expression and location: absent and empty cannot be told apart | Fixed | D-205, D-207 |
| CRD-2 minor | A mock timeout in 400 ms | Partly: said to be played and what live would wait; on mock said twice (open) | D-204, D-205, D-226 |
| CRD-3 minor | A Java class named to a tester | Fixed | D-205 |
| CMP-10 minor | The browser words case sources and which group starts closed | Fixed | D-202, D-207 |
| CMP-11 minor | Long values cut mid-line; templates broken mid-identifier | Fixed | |
| CMP-12 minor | The glossary points at an API path | Fixed | D-202 |

**Review of the DTR tab**, in a browser. 2 blockers, 9 majors, 7 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| R01 blocker | "Continue in PAS" carried the old answers after a change | Fixed | D-192 |
| R02 blocker | "This tool shows and hides them as the answers change" was untrue | Fixed: the sentence made true; no evaluation endpoint, decided | D-199, D-203 |
| R03 major | The package's expectation applied again to the response step | Fixed | D-197 |
| R04 major | A completed response drawn as "nothing further is required" | Fixed: the tone asks for action | D-203 |
| R05 major | The browser kept words for codes | Fixed: the server's labels read | D-203, D-207 |
| R06 major | The form drawn when DTR is not configured | Fixed by the join: on live the form is held back | D-206 |
| R07 major | Focus lost after three updates | Fixed | |
| R08 major | A step's refusal shown beside the wrong button | Fixed | D-197 |
| R09 major | One action, two names | Fixed: the buttons and the next steps share the server's labels | D-205, D-207 |
| R10 major | Steps not said to be recorded or not | Fixed | D-197 |
| R11 major | Cream used for an override the key did not name | Fixed in the key | D-195 |
| R12 minor | Answers named without labels | Fixed | |
| R13 minor | "Take back" ambiguous; the removal sent again | Wording fixed; resending refuted | D-198 |
| R14 minor | The change block after a failed step | Fixed | |
| R15 minor | A long word clipped | Fixed | |
| R16 minor | Lines that read badly; raw hand-over keys; dropped answers with no value | Fixed: carry labels and dropped values from the server; the dropped values not seen on screen | D-203, D-207 |
| R17 minor | The loud strip about another questionnaire | Fixed | |
| R18 minor | Six to ten technical lines in the verdict strip | Fixed | D-203 |

**Review of the PAS tab**, in a browser. 8 majors, 8 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| R01 major | A notification with a decision did not say it | Fixed | D-197 |
| R02 major | An unmatched notification sends the tester after a third party | Fixed: the remedy first; the sample that matches nothing by design names a sample that would match | D-203, D-224 |
| R03 major | "Continue in PAS" on the PAS tab invited a resubmission | Fixed | D-192 |
| R04 major | Focus lost after each send | Fixed | |
| R05 major | A subscription said to call back an address the request never named | Fixed | D-203 |
| R06 major | 200 notifications expanded, no filter | Fixed | D-197, D-203 |
| R07 major | No quantities, dates or what changed per item | Decided not to build the comparison | D-199 |
| R08 major | The case pane short on production at laptop size | Fixed by the join: a compact banner and case pane at laptop height | D-206 |
| R09 minor | "Mock profile: this answer came from a scenario file" on a blocked run | Fixed: seen on a blocked PAS run on mock by the join | D-206 |
| R10 minor | No way to the inquiry after a fault | Fixed: "Choose the Inquire call" | D-197 |
| R11 minor | An unmatched notification in the tone for unknown member data | Fixed: neutral tone; not reached on screen | D-203 |
| R12 minor | The browser words decision sources, attachments, reachability | Fixed: the server's words read | D-203, D-205, D-207 |
| R13 minor | Small layout faults | Fixed: determination cells break only at a joint | D-206 |
| R14 minor | A form offered although every send is refused | Fixed by the join: on live the form is held back | D-206 |
| R15 minor | Preview notes in the past tense | Fixed | D-203, D-207 |
| R16 minor | Tester reference and note not on the result | Fixed | D-197 |

### What exists at this revision

- **Protocols.** CRD: discovery, order-sign and order-select, a conformance checker that judges by CRD
  2.0.1, 14 mock scenarios. DTR: questionnaire package, the adaptive next-question loop, the response
  step, 15 mock scenarios. PAS: submit, inquire, subscriptions (create, read, list, update, delete),
  the notification receiver, 21 mock scenarios (scenarios 16 and 17 now answer an inquiry). A request
  sent from the collection as it is, with its saved body and values for the parts of its url, and one
  mock scenario. A request of the tester's own on every protocol. `GUIDE_RULE_BROKEN` in all three.
  Every finding carries its group. A secret a service sends back is masked in every answer, a
  received notification included; one test searches the whole answer of ten endpoints and of a delivered notification for it.
- **Configuration.** Postman environments and collections uploaded and stored as uploaded, the
  operation mapping (changed and removed from the Config tab), which request a run uses stated by the
  server, variable resolution with labels for each source, the production guardrail judged by
  destination and reading an address as the HTTP client does, no encoded separator in a path,
  warnings at upload about addresses, credentials and member data.
- **Lookups and code list.** Member and provider lookup with 51 synthetic member fixtures and provider
  fixtures, by path or by query; the placeholder procedure code list, any list that says it is
  synthetic treated as the placeholder, and the picker.
- **The ledger.** JSON Lines, the tracking policy, free text refused when it holds this run's member
  data, procedure codes and identifiers a service supplies held to the identifier-or-date rule,
  notes checked against the member token, health with the files it cannot read, the CSV export with
  filters, replay that returns what the new run found, case lines, an optional time zone (not offered
  on screen).
- **Suites and the chain.** Suites from CSV or JSON with a truthful preview, labelled counts and
  comparison with an earlier run; the chain as a suite case and the chain view; manual chains linked;
  every DTR sentinel reaches its DTR scenario from CRD.
- **Mock scenarios.** One rule chooses a scenario for a typed or a pasted identifier; the sentinels of
  another protocol are data in the files; the scenario lists carry notes about the default and about
  files not read.
- **The request comparison** of a pasted CRD request with the tool's own (`POST /api/crd/compare`),
  masking a pasted credential.
- **The HTTP edge**: bound to this machine, the request guard, no-store, the body limit, the server's
  working folder removed at stop.
- **README, `run.sh`, `run.cmd` and samples** (a Postman environment and collection, a code list, a
  suite). The README describes the tool at the join (D-209) and not the later changes.
- **The screens**, joined into one tool by two passes, each by one agent who drove them in a real
  browser with scripts, mostly on mock, never by a business tester.
  - *Every tab.* One readiness statement: on live with nothing configured each protocol tab holds its
    form back, says what is missing and links to Config, and the form comes back with no reload after
    an upload (seen at 1366 and 1920). One next-step row and one focus after a send. The case carried
    from tab to tab, and what a step handed on dropped when the member changes (seen). The masthead's
    ledger indicator in each state, an unreadable line noted (seen at both sizes). The colour key
    drawn from the server's finding groups (seen at both sizes).
  - *CRD tab and the comparison.* Every scenario through the real form (in the audits and the passes);
    the comparison with the caveat, the masked note and the server's source labels, a pasted token
    nowhere on the page but in the tester's own box (seen); fill without typing over a lookup, marked
    "from the pasted request" (seen).
  - *DTR tab.* The package, the answers and the completed response, with the server's labels for the
    kind, the conditions and what was handed on, and the buttons named as the server's next steps
    (seen). A whole journey by keyboard alone (seen).
  - *PAS tab.* A submission carrying the answers completed in DTR, in the preview and in the wire view
    (seen at both sizes); pended then an inquiry; the decision source, the attachment sentence and the
    receiver's `bindNote` from the server (seen); production locked, unlocked and a second context
    (seen by the first pass at 1366).
  - *History tab.* The two proofs as two rows; the journey as one chain; the suite with its count
    groups; a replay with its verdict; a refused PAS replay with `SEND_ON_ITS_OWN`; the `testerRef`
    filter; "Load this case into the form" (seen, most by the first pass at 1366).
  - *Config tab.* The default rule, the source labels, a raw send with a field per url part and its
    422 at the field (seen); the blocked raw write on a production environment of the platform task's
    own (seen at 1366).
- The build: green on `5763c41` through the shared wrapper, 2707 tests, 2705 passed, 2 skipped.

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | done, reviewed by six seats, frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | done and merged |
| Spine 2: integrate Wave A. V2 PHI audit | done. V2 ran twice; the fixes of the second run, on `0658a7c`, are merged and not yet verified by another agent |
| Spine 3: CRD reference vertical | done. Held to CRD 2.0.1 since the fix wave |
| Wave B: B1 PAS and subscriptions, B2 DTR | done and merged. Held to PAS 2.0.1 and DTR 2.0.1 since the fix wave |
| Spine 4: integrate Wave B, chain, suites, cross-protocol tests. V3 guardrail audit | done. V3 ran twice; the fixes of the second run, on `0658a7c`, are merged and not yet verified by another agent |
| Fix wave: every audit and review finding | merged at `33893aa` |
| Leftovers between the parts of the fix wave | merged at `7dade28`, each verified alone |
| Server work the reviews of the screens asked for | merged (`screens-protocol` at `ce345e4`, `screens-platform` at `3b1ddf4`), no verifier of its own; the second audits were its first independent readers |
| Wave C: C1 frontend | merged and joined into one tool by two passes (`fe-join` at `4d795d8`, `fe-join-2` at `d15db15`). A third pass, for what the audits asked of the screens, is started and not merged |
| Wave C: C2 docs and scripts | merged (`docs-2` at `81637c2`); the README describes the tool at the join. An update for the later changes is started and not merged. `run.cmd` never run |
| Wave C: C3 verification V1 to V7 | V2, V3 and V4 ran twice, the second time on `0658a7c`; their fixes are merged (`audit-fix-v2` at `d542405`, `audit-fix-v3` at `1557e5c`, `audit-fix-v4` at `fd61e6e`) and not yet checked by another agent. V1, V5, V6, V7 not run |
| Spine 5: clean build twice, fresh-clone build, push, pull request | not run as a step. The branch is pushed at every integration (at `5763c41` now); the clean builds with plain `./gradlew`, the fresh-clone build and the pull request have not been done |

### How the build got here

**Interrupted, and taken over.** About an hour into Waves A and B every background agent was stopped
at once by a usage limit; what each had written survived in its worktree and was handed to a new
agent, and since then every agent commits a checkpoint after each green slice. The verifier planned
after each wave package was dropped then, and the section 27 agents and independent reviews took its
place. Later the build machine restarted three times (at about 00:17, 03:02 and 06:00 UTC on the day
of the second audits), each time cutting off every agent then at work, at least twice when several
full builds ran side by side; nothing committed was lost and each task was taken over in its
worktree. Since then every build goes through one shared wrapper that lets two run at a time.

**Added by the operator during the build (D-118, D-119, D-136):** paste a request of your own and send
it; compare a pasted CRD request with the tool's own, each difference explained; hold the tool to
version 2.0.1 of the guides. All three are on the line, the first two with their screens on the
protocol tabs. The CRD and DTR 2.0.1 packages were supplied by the operator as files.

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

Target: level 1, everything in the build prompt. At revision `5763c41` the server holds everything
level 1 asks of it, the optional capabilities of section 28 included: CRD, DTR and PAS implemented and
not stubbed, the chain, suites, conformance checking and response times, with the gaps listed under
"Not done" above. The frontend is on the line with all five tabs joined into one tool, none of them a
stub; on live each protocol tab holds back a form that cannot send, and the two proofs before
handover were seen on the real tabs on mock by browser scripts. Level 1 is still not claimed: the
fixes of the second audits have been checked by nobody but their authors, V1, V5, V6 and V7 and the
walkthrough by a business tester have not run, the screen still states something false after a lock
from another browser, and nothing has been observed against the vendor's service. The level
delivered is stated at the top of the handover once those gates have run.
