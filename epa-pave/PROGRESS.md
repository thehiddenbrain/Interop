# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

**Read this first.** The revision that holds this file is later than `7dade28`, which the rest of
this section describes. Merged since, and built together through the shared wrapper with 2648 tests
(2646 passed, 2 skipped as explained below, none failed):

- the pass that joined the five tabs into one tool. It walked the journey CRD to DTR to PAS on the
  real tabs with `P32H-DOC-DTR` and saw the answers completed on the DTR tab inside the outgoing PAS
  request, in the preview and in the wire view. On the live profile with nothing configured the three
  protocol tabs now hold their form back and say what is missing. So the paragraphs below that say
  nobody has used the five tabs together, and that the tabs present a form that cannot send, are out
  of date. What that pass did not get to is not yet in this file: a sweep for one wording of one
  action across the tabs, the colour key checked entry by entry, several values the server returns
  that no screen reads, and a run on the live profile against a service that sends a secret back;
- the README, brought up to the tool as built, screens included. The list below of README sentences
  that are untrue is out of date. The README quotes some sentences of the screen that the two server
  tasks then reworded, and those quotes have not been checked again;
- the two server tasks of what the reviews of the screens asked of the server (D-200). The request
  comparison now masks a credential a pasted request carries, a raw send masks what a service sends
  back, and one test searches the whole answer of ten endpoints for a secret sent back. Neither task
  was attacked by an independent verifier, and their decisions are not in `DECISIONS.md` yet. One gap
  its author reports: an access token or a secret variable of the environment that a service puts
  into a notification is not masked when the notification is shown.

**Still not done at this revision:** the screens do not yet read the words and values those two
server tasks added, and still keep their own copies of some of them. No audit of section 27 has run
on this revision, nor the walkthrough by a business tester, nor a build from a fresh clone.

This describes revision `7dade28` on branch `epa-pave`, which is on the remote. It holds the line
of the fix wave (`33893aa`), the two tasks that closed what the fix wave left between its parts
(`seams-protocol`, `seams-platform`) and the frontend: the frame and the files every tab shares, the
case, and the CRD, DTR, PAS, History and Config tabs. The integrator built it once through the shared
build wrapper: 2597 tests, 2595 passed, 2 skipped, none failed. The two skipped tests need a folder
that cannot be written (`ConfigStoreTest.aDirectoryThatCannotBeWrittenFailsAtStartupNamingThePath`,
`JsonlRunLedgerTest.startupFailsWhenTheDirectoryIsReadOnly`), which a build running as root cannot
make; the same refusals are covered by tests that did run. The server alone, before the frontend was
merged, built with 2577 tests (`d030efd`). The decisions of the fix wave are D-140 to D-175; those of
the leftover tasks and the frontend are D-176 to D-201.

### Not done, not verified, or known to be wrong

**Gates that have not run.**
- Section 27: the member data audit (V2), the guardrail audit (V3) and the fixture audit (V4) ran
  once, on the revision before the fix wave, and have not run again. Their findings were fixed and
  each fix was attacked by a verifier that did not write it. **Not run at all: V1 (the build auditor,
  from a fresh clone), V5 (house style), V6 (honesty) and V7 (completeness on screen).** The house
  style rules of V5 are enforced by the build on every file, which is not the same as the audit.
- The walkthrough by a business tester ("Prove it before handover", part 1) has not run. Part 2
  (no authorization required told apart from no determination, on screen and in the ledger) was
  seen on the CRD tab alone (`P32H-NO-AUTH` and `P32H-EMPTY` side by side); not in History, a suite or
  a chain on screen.
- No fresh-clone build of this revision. Spine 5 has not run as a step: the branch is pushed, but the
  two clean builds, the fresh-clone build and the pull request have not been done.
- `run.cmd` has never been run: there is no Windows machine here.
- **Nothing has been observed against the vendor's service.** Every live check any agent reported
  used a stand-in on a loopback port. Which version of the guides the vendor's sandbox follows is not
  known; the tool is held to 2.0.1 (D-136).
- The README does not describe the screens or the last server changes, and some of its sentences are
  now untrue: those that say a request sent from the collection as it is does not send the saved body
  (lines 422 to 428, 762, 909, 916, 1194 to 1195, 1228, as the leftover tasks counted them), that the
  wire view shows exactly what went over the wire (line 314), the known gap about a credential in a
  Subscription's channel header (lines 331 to 333, 1162 to 1163), and what a raw send answers for a url
  with a part or a placeholder missing (lines 448 to 449, 707 to 709). It also lacks the query form of
  the lookups, the new filters of History, `urlValues` and the masking of a call back address.

**Started from this revision and not on it.** Three pieces of work began from `7dade28` at the
moment these records were written: the two server tasks of what the screens asked for (one in the
three protocols, one outside them) and the pass that joins the tabs. D-200 says what the integrator
decided for each, line by line, and D-199 what was decided not to do. None of it is done here. Every
item below that D-200 covers is still open at this revision.

**Nobody has used the five tabs together.** Each tab was built on a base that held neither the
other new tabs nor the leftover tasks' server changes, used in a real browser by a reviewer who did
not build it, and finished by a third agent, alone. The journey CRD to DTR to PAS has not been walked
on the real tabs, the hand-over between two real tabs has not been seen (D-192), the completed
answers inside the outgoing PAS request were seen only with a DTR answer injected into the browser
(D-193), and the screens read none of the words and fields the server gained after their base
(D-186, D-187).

**The screens, tab by tab.** What each part's finisher left, what each review did not check, and
what the screen still shows because the server owns the words.
- *Every tab (the frame).* Section 20's "before anything is uploaded, each protocol tab says plainly
  what it is missing and links to Config; it does not present a form that cannot send" is not met on
  any protocol tab. The DTR and PAS tabs read the configuration themselves and say what is missing,
  with a link to Config, but still present their form: PAS keeps Send (made quiet, and a refusal is
  recorded on purpose), DTR keeps Fetch. The CRD tab says nothing until a send is refused. The
  workbench's readiness option exists and no tab passes it. The browser keeps its own grouping of
  finding kinds. The colour key is wrong for three server tones (D-195). Asked of the frame and not
  done: clearing an earlier 409 refusal when a comparison is drawn, marking a case detail as from a
  pasted request, an action box that scrolls below about 800 pixels, determination cells that wrap
  mid-value. Asked of History's `js/chain.js` and not done: a collapsed chain bar at laptop height (it
  takes about 170 pixels above the PAS panes). The frame's fix pass did not open the DTR and PAS tabs,
  and its colour key and other-tab shots at 1920 were not taken (those at 1366 were looked at). The
  frame pass did not reread the Config tab's files against the Config review's CFG-01 to CFG-22; it
  drove only the flows it names. At phone width the
  History runs table and the CRD elements table run past the edge. No screen reader was used on any
  tab, contrast was never measured, 200% zoom was never tried, and `prefers-reduced-motion` was
  checked only by reading the stylesheets. Behaviour on screen has no test in the build: it was
  checked only by browser scripts kept outside the repository.
- *CRD tab and the comparison.* The comparison shows a credential in the pasted request as typed
  (CMP-6). Its caveat is missing when the two requests cannot be compared (CMP-7). The browser words
  the case value sources and which group starts closed (CMP-10). The glossary sends a tester to an API
  path and has two sentences that do not read (CMP-12). A refused comparison says "send again"
  (CMP-9). An issue's expression and location cannot be told absent from empty (CRD-1). A mock timeout
  shows 400 ms under words that say the service timed out (CRD-2); an unreachable service is named by
  a Java class (CRD-3). The vendor's sample passes through the browser. The tool's own hook instance
  is a sentence, so it is always one difference. Not checked: production and blocked writes on this
  tab, a ledger that cannot record, the keyboard through the code picker, the live profile beyond
  laptop size, every scenario's screenshot at both sizes. The slow-response flag was seen only on mock
  with a lowered threshold, never forced on live. When a pasted request and the case disagree, only a
  member ID gets a "Use this member ID" button; two dates of service that disagree are listed with the
  server's sentence and no button.
- *DTR tab.* Still on screen because the server owns the words: "This tool shows and hides them as
  the answers change" (untrue of the screen), the tone of a completed response, next steps that name
  the actions in other words than the buttons, a verdict strip of six to ten technical lines, the raw
  hand-over keys. The result's own "Continue in PAS" is hidden by the tab after each draw. Never drawn,
  because no scenario produces them: an unrecognised question type, quantity, attachment, reference, an
  open choice from a value set only, a repeating text question, a questionnaire of undetermined kind.
  Not sent: a wrapped next-question step, a package request of the tester's own. Not seen: "could not
  be reached" on live, a blocked write, the UAT banner, History's view and replay of DTR runs. Most
  states were seen at 1366 by 768 only. A quantity's unit is free text, because the server gives no
  unit constraint (`ItemView`); an answer the response holds is never put back into a control, because
  the server gives display text, not the answer code (`ResponseItemView.answers`).
- *PAS tab.* What changed per item from what was asked is not shown (decided not to be built, D-199).
  The tab still words where a decision was stated, how attachments travelled and whether the receiver
  can be reached, because the server gives no sentence (and no bind address). A preview's notes are
  in the past tense. A subscription created with no call back address is said to call back one the
  mock invented. An unmatched notification sends the tester after whoever created the subscription
  and repeats its reason. The mock timeout scenario tells the tester to inquire, and an inquiry with
  the same sentinel is refused. Where a subscription value given for one run came from is not said, and
  a run-value field for a builder variable such as `subscriptionHeartbeatSeconds` reads "Where the
  request uses it was not reported.", because `/api/config/resolve` does not describe builder
  variables. "Show how the answer differs from the last one seen" is not built: the inquiry answer
  states no difference. The blank "Meaning" cell the review saw on a subscription status row was not
  reproduced, and whether every protocol's determination grid writes "no meaning to state" was not
  checked.
  Not checked: production with writes allowed and unlocked, live with an unreachable sandbox, a failing
  ledger, masking of a channel header on screen, the chain joined from the PAS tab, inquiry scenarios
  other than `pas-05`, 1920 by 1080 for production, 422 and live states, a payload over the 131072-byte
  cut.
- *Config tab.* The effective configuration says an operation "still runs" when a header or the body
  of its request holds a placeholder with no value (FR-03). It cannot say which of several mapped
  requests an operation uses except in the page's own words (FR-04). A removal or mapping change on an
  item another window removed is answered 404 with "Check the identifier and try again" beneath the
  page's own line (FR-07). A request taken off every operation is noted by the server as "now mapped
  to Unmapped request" (FR-08). A blocked request sent as written repeats its refusal in the next step
  (FR-13). "problem(s)" and "Nothing was sent" after a preview or an unlock (FR-17). The screen does
  not read `sourceLabel`, `lookupEnabled` or `blockedShort` yet. The 409 path of a mapping change and
  a 422 on a mapping have never been seen on screen. A request sent from the collection as it is: the
  screen has no field for `urlValues` and does not show `bodySource` (its view was built before
  D-184), so a request whose url holds a `{token}` cannot be sent from the Config tab. A preview of an
  incomplete url on mock is now a 422 where it was a 200 with a note; no screen was checked against
  that.
- *History tab.* It does not read the new labels and filters (the next action words, suite count
  labels, `testerRef`, `matched`, the export filters, `zone`, the replay's verdict and next step,
  `unreadableFiles`) and still says every date is a UTC day.
- *The case.* The lookup strip says only "Found." when a pasted request disagrees; the case field still
  says "changed by hand" for a value the tester chose to take from a pasted request.
- *A link from another site.* By reading the code, the page and its static files are not controller
  methods, so `CrossSiteNavigationInterceptor` lets a top-level navigation to them through (a unit test
  covers a static file). No navigation from another site to the page itself has been tried through the
  running server.

**The server, by endpoint.**
- *Every answer.* A request sent from the collection as it is builds its "returned but not
  interpreted" fields from the body as received: a secret a service echoes there is shown, while the
  wire view masks it (FR-01). The page covers this as a stopgap (D-196). No test searches the whole
  answer of every endpoint for an echoed secret. A secret returned as a JSON number is masked nowhere.
- *Production refusals of a replay or a suite row.* The services pass their origin and the result
  heads the next step with `SEND_ON_ITS_OWN`; the two halves were built and checked apart (D-183). On
  the protocol branch, where the services pass the origin, the protocol verifier saw the gateway's own
  sentence for a refused suite row on the running application; a refused replay was not tried there.
  On the platform branch, where they did not, the gateway's generic sentence showed with the new next
  action and headline. The two halves are merged and nobody has seen them together.
- *`/api/config/effective`, `/api/mode`, `/api/config/resolve`, the configuration's 404s, every 422.*
  As in the Config tab above, and: no bind address on `/api/mode`; no description of builder variables
  on `/api/config/resolve`; no group for a finding kind; counts written "(s)"; a 422
  worded for a send on every call.
- *CRD.* `POST /api/crd/compare` returns a pasted credential as typed and its caveat only as the first
  note of a comparable answer; it cannot take a collection request by name; it ignores `configEpoch`; a pasted request for
  another hook is answered comparable with a note. The CRD tab, DTR and PAS items above that name
  server words.
- *DTR.* No label for the questionnaire kind, the condition states or the hand-over keys; the dropped
  answers carry no values; a response note only for some response kinds; the two next actions are
  labelled in other words than the buttons. A failure of the tool inside the response step, which sends
  nothing, answers 500 and writes no ledger line (from the first DTR report, not checked again). The
  intended use code system in the PAS bundle was not checked against DTR 2.0.1. Replay of a DTR or a
  PAS run through the running application was not driven.
- *The old `CaseResources` builders* have no caller and are still in the file.
- *Older items still true.* A run with the tester's own body records no member, NPI or line of
  business, and nothing on its ledger line tells two such runs apart (CASE-05). A CRD or PAS step of a
  chain handed only the procedure codes its line already holds is still marked as not replayable alone
  (F7). A second suite started while one runs is answered 409 with kind `VALIDATION`. A well-formed
  chain id the tool never issued is accepted. `SuiteCases` refuses a hook with "The hook must be
  order-sign or order-select." rather than the CRD service's sentence. The CRD fix left "Existing
  authorization" in `RedactionTest` and "already satisfied" in the description of the member fixture
  `p32h-pa-sat.json`. A lookup fixture file that repeats a key is read; two provider fixture files
  claiming one NPI stop the start in the code but no test proves it (D-188). After the ledger folder
  was restored, the server kept saying it cannot write until the next write succeeded; nobody has
  looked into why. A lookup identifier with an encoded slash in the path form still ends as a bare 400
  (the query form works, D-189).

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
  rules DTR-07 names were tested only with made-up questionnaires, never with a real one, and the gap
  sentences of the DTR profile check were not mutation-tested one by one.
- The chain: a DTR package that goes straight on to PAS is driven by no test and no mock scenario.
- The guardrail: everything in D-145. The rule that refuses a call a run value pointed elsewhere while
  it carries a secret sits in the gateway, so a request sent from the collection as it is passes
  through it, but it was checked only for protocol calls and a subscription read, never for such a
  request in the running application. How a real FHIR server acts on `//`, `;`, `..;` or an encoded
  slash inside a path segment was not checked; the tool sends the form it judged. A lock that lands
  between preparing and sending a write is tested at the gateway only.
- Credentials: one typed into a path segment or a host of a collection is shown as written; a secret in
  the path of a call back address with no variable is shown. The collection file keeps the value on
  disk.
- The HTTP edge: only Linux, curl, raw sockets and headless Chromium were used; Windows, Firefox and
  Safari were not tried. `OPTIONS *` carries none of the tool's headers (D-150). A hard kill during an
  upload was checked by hand once. A cross-site request that carries neither `Sec-Fetch-Site` nor
  `Origin` is let through (D-148). Several uploads of 30MB held in memory at once were never tried.
  The sentences for an upload of 60 parts, a long file name and a 3MB field rest only on
  `EdgeOverTheServerTest`.
- The ledger: a one-word surname written where a code belongs is kept (D-151). The live profile was
  checked in unit tests only. Folders and day files made before the fix keep their old modes. Flush and
  sync are checked by reading the code. Two instances on one ledger directory are not coordinated. A
  note written after the clock was set back across midnight is on disk and not applied.
- Fixtures: any new top-level key in a scenario file stops the mock start until the loader knows it. A
  file numbered 0 still becomes the default for unmatched IDs, and an `expect` block that contradicts
  its body is still listed as the expected outcome.
- Suites: whether a code assertion's value is in its value set is not checked before sending. The test
  ledger does not apply case lines as the real one does.
- The code list: no real P32H export was available, so its header row, flag spellings, date format,
  time zone, vendor names, encoding and columns per line of business are unknown. A copy of the
  placeholder set as `codelist.file` counts as a real list. The first-row message still shows cells
  of letters alone.
- Lookups: neither has met the real member API or provider directory; both response mappings are
  assumptions (section 26). A name returned in one piece cannot prefill first and last name.
- Notifications: the receiver does not authenticate its caller and has never been called over a socket
  by a real service; with the default bind address no vendor can reach it. Two notifications at the
  same moment are not tested.
- Postman: no real vendor export was available; every collection the tests use is hand-written, and
  detection by url is verified only for the standard FHIR operation paths and `/cds-services`.
  Concurrency in the store is reasoned about, not tested.
- The request comparison: no real vendor request was available. A placeholder whose value exists only
  in a collection's variables is reported as having no value. Codes in NutritionOrder,
  VisionPrescription, CommunicationRequest and Appointment are not read.
- The wire view: a secret sent back re-encoded (percent, base64, another case) or shorter than six
  characters is not found.
- Documentation: STS, a mirror that needs credentials and a token server were not tested. A plain
  `./gradlew build` can skip the sample tests after a sample is edited; `clean build` runs them.

**Checked only by unit tests or at the gateway, never in the running tool.** A chain interrupted
part way writing its case line, and the write note of a suite on a production target; a failure of
the tool inside a step; the lock that lands between preparing and sending; the replay paths for a
refused claim id and an authorization number in several groups; the split of TLS failures into
reached and not reached; the ledger on the live profile; the chain view of a blocked replay
(`SEND_ON_ITS_OWN` on the chain view, `ChainServiceTest`): a replay is not part of its original's
chain (`RunReplay`), and on the running application a replayed PAS submission did not join its
original's chain, so no chain whose latest line is a blocked replay could be shown. Not checked at
all: whether a chain's DTR step can produce each fault outcome a suite file may expect of it.

**Seen once on the running application, against a stand-in, and pinned by a unit test.** A secret in
the call back address that the service echoes in its Subscription is masked in every view of the
answer: the protocol verifier saw it on live against a stand-in that echoed the address, and
`SubscriptionServiceTest.aSecretInTheAddressToCallBackThatTheServiceEchoesInItsSubscriptionIsMaskedInEveryViewOfTheAnswer`
pins it.

### What the audits found and what was done

State is one of: fixed (verified means a second agent attacked the fix and it held, or what it
found was then fixed), fixed in another form, partly fixed, not fixed, refuted, decided otherwise.
"Open" means it is listed above as not done.

**Member data audit (V2).** No blocker; 7 majors, 10 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| M1 major | A questionnaire address on the ledger line could hold a member ID, a name or a date | Fixed, verified. The verification found the first fix checked shape only; each part is now checked | D-151 |
| M2 major | A code that is really an identifier was kept | Fixed in another form: identifier-or-date rule; a one-word surname is still kept | D-151 |
| M3 major | Any text was kept as a line of business | Fixed, verified | D-151 |
| M4 major | The server logged an unparsable cookie or an unmappable address | Fixed, verified (log levels) | D-150 |
| M5 major | Note, tester reference and expectation had no limit | Fixed, verified; a suite row's over-long expectation is now reported by its length alone | D-151, D-189 |
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

**Guardrail audit (V3).** 4 blockers, 5 majors, 2 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| G1 blocker | A run value, or an unlock for another production environment, let a write through | Fixed, verified | D-143, D-144 |
| G2 blocker | With a sandbox active, a write reached a production address from a run value, a literal or `application.yml` | Fixed, verified | D-143 |
| G3 blocker | A read could be pointed at any host with the token | Fixed, verified, for every call; a request sent from the collection as it is now attaches by scheme, host and port, proven on the wire | D-143, D-144, D-184 |
| G4 blocker | A refused replay was told writes are locked for this session | Fixed in two halves on two branches, each checked alone; merged and not yet seen together (open) | D-143, D-183 |
| G5 major | Dot segments let an inquiry act as a write | Fixed, verified, including `..;`; a url part of `.` or `..` is now refused on a raw send | D-144, D-184 |
| G6 major | A deleted production environment could return as a sandbox | Fixed, verified | D-143 |
| G7 major | The "not production" acknowledgement covered urls from `application.yml` | Fixed, verified | D-143 |
| G8 major | The unlock's name check had no test | Fixed | |
| G9 major | Locking again changed the epoch suites pin to | Fixed, verified | D-143 |
| G10 minor | "prod" was looked for before escapes were decoded | Fixed | D-143 |
| G11 minor | A refused lookup was shown as switched off | Fixed in another form: `FAULT`, `BLOCKED` | D-146 |

The verification of that fix (F1 to F15): F1 to F3, F5 to F7 and F9 to F14 fixed (D-144). F4 (the
refusal through the running application) is G4 above: merged, not yet seen together. F8 (a request sent
from the collection as it is attached the token by host) fixed, verified on the wire (D-184). F15 (the
fix edited two shared files) accepted at the merge (D-175, item 9).

**Fixture audit (V4).** 1 blocker, 4 majors, 6 minors. On the revision before the fixes, every
synthetic NPI passed the tool's check and all 240 scenario runs gave what their files claim. The
CRD, DTR and PAS scenario files have been rewritten since (and PAS scenario 21 added) and not
audited again.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| V4-01 blocker | The placeholder code list was compared with a live service as if real | Fixed, verified | D-169 |
| V4-02 major | A damaged scenario set was never refused | Fixed, verified; a suite row now says the mock scenarios cannot be used, naming only the directory | D-153, D-188 |
| V4-03 major | The lookups did not know the DTR and PAS sentinels | Fixed, verified | D-153 |
| V4-04 major | Sentinels did not mean the same case across protocols | Fixed, verified, in each protocol | D-154 |
| V4-05 major | No test scanned CRD and PAS scenarios for identifiers | Fixed, verified | D-153 |
| V4-06 minor | Two synthetic data sets contradicted each other | Fixed by rewording scenario 10 | D-154 |
| V4-07 minor | Tests used a member ID outside the placeholder style | Fixed | |
| V4-08 minor | A sentinel in a pasted body matched only case-sensitively | Fixed, verified | D-153 |
| V4-09 minor | A sentinel's refusal named the scenario field | Fixed | D-154 |
| V4-10 minor | Scenario 8 described a request it did not check | Fixed by description | D-154 |
| V4-11 minor | The NPI check is Luhn only | Not changed: a question for the operator | D-153 |

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
still open (FR-01 below). V-02 to V-10 fixed, V-07 by correcting the reason given for ATT-01 (D-161).

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
to VSP-9): VSP-1 (major, the headline of a blocked replay still said to unlock) fixed (D-183), but the
gateway's own sentence for a replay was not seen; VSP-2 (major, no test of the chain view's next
action) fixed with a unit test, not seen on the jar; VSP-3 (a garbled sentence) fixed and pinned;
VSP-4 and VSP-5 (incomplete raw urls answered differently by profile, one recorded as a run) fixed
(D-184); VSP-6 (`..` in a url part moved a read) fixed by refusing it (D-184); VSP-7 (the mock
headline) fixed; VSP-8 (no test of two lookup fixtures claiming one identifier) partly: a member
fixture test exists, none for providers (D-188); VSP-9 (a frozen test helper edited) resolved by
restoring it (D-189).

**Reviews of the case, the Config tab and the History tab** (2 blockers, 5 majors and 8 minors; 4
blockers, 8 majors and 10 minors; 1 blocker, 8 majors and 7 minors). Each was fixed on its branch
before the fix wave was merged (D-172), and is now on the line. What they handed to the frame was
done by the frame pass (the ledger indicator from health, a blocked result on mock, the colour key
entries, the preview's words, a run id as a link, PUT and DELETE, the Colour key button at phone
width, the case pane on a wide screen, the shared stylesheet's two selectors). What they handed to
the server was done by the platform task (the words and filters of D-186 and D-187), and no screen
reads it yet. Still open: CASE-05 (two runs with the tester's own body cannot be told apart) and the
second half of CMP-2 (a detail taken from a pasted request is marked "by hand").

**Review of the frame and the shared files**, in a browser. 1 blocker, 5 majors, 14 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| FR-01 blocker | A secret the service echoed shown in clear under "returned but not interpreted" | The page masks it as a stopgap; the server masks it for CRD, DTR, PAS and lookups, not for a request sent as written (open) | D-179, D-196 |
| FR-02 major | A form that cannot send is shown on live with nothing configured | Partly: the workbench can withhold it, no tab turns that on; DTR and PAS say what is missing themselves and still offer the form, CRD says nothing (open) | D-191 |
| FR-03 major | "Can it be sent" looks at the url only | Not fixed: server (open) | D-200 |
| FR-04 major | No way to see or choose which mapped request an operation uses | Partly: the page states the rule and names the request a run uses; no endpoint to choose, decided; the server's sentence is decided (open) | D-199, D-200 |
| FR-05 major | The ledger indicator stayed in the fault frame after recording worked again | Fixed | D-197 |
| FR-06 major | Focus lost after a send | Fixed | D-197 |
| FR-07 minor | A 404 for a removed item says to check an identifier nobody typed | Page part fixed; server wording open | D-200 |
| FR-08 minor | Mapping wording, one action with two names | Page part fixed; the server's "now mapped to Unmapped request" open | D-200 |
| FR-09 minor | Notices for a change that changed nothing | Fixed | |
| FR-10 minor | The whole environment strip was a live region | Fixed | D-197 |
| FR-11 minor | The browser keeps the grouping of finding kinds | Open (server groups decided); the second half, codes breaking mid-word, refuted | D-198, D-200 |
| FR-12 minor | An unknown result drawn as a fault | Fixed | D-195 |
| FR-13 minor | A blocked raw write sent to the PAS tab, refusal said four times | Page part fixed; the server's next step and reason open | D-200 |
| FR-14 minor | The masked row was a second loud element | Fixed | |
| FR-15 minor | A 3,000-character value unfolded | Fixed | D-197 |
| FR-16 minor | Mock timeout of 400 ms; "was sent without" on an unreachable run | Not fixed: server (open) | D-200 |
| FR-17 minor | "problem(s)", and "Nothing was sent" after a preview or an unlock | Not fixed: server (open) | D-200 |
| FR-18 minor | "Record this result" above NOT recorded | Fixed | D-197 |
| FR-19 minor | An unexplained heavy rule; the title did not show an unlock | Fixed | D-195 |
| FR-20 minor | The indicator's frame split at 390 pixels | Fixed | |

**Review of the CRD tab and the comparison**, in a browser. 1 blocker, 7 majors, 7 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| CMP-1 blocker | A comparison stayed on screen after the box, hook or mode changed | Fixed | D-194 |
| CMP-2 major | Fill mixed two people's details | Fixed in the tab; marking a value as from the pasted request needs the frame (open) | D-194 |
| CMP-3 major | Focus lost after Compare | Fixed | |
| CMP-4 major | Tabbing hid rows under the pinned documents | Fixed | D-194 |
| CMP-5 major | Next difference jumped to a closed group | Fixed | D-194 |
| CMP-6 major | A credential in the pasted request shown in clear | Not fixed: server (open) | D-200 |
| CMP-7 major | The caveat missing when the requests cannot be compared | Not fixed: server (open) | D-200 |
| CMP-8 minor | "Comparison ready." for one that was not made | Fixed | |
| CMP-9 minor | A refused comparison says "send again" | Partly: the server's wording and the frame's clearing of an old refusal open | D-200 |
| CRD-1 major | Expression and location: absent and empty cannot be told apart | Not fixed: server and result view (open) | D-200 |
| CRD-2 minor | A mock timeout in 400 ms | Not fixed: server (open) | D-200 |
| CRD-3 minor | A Java class named to a tester | Not fixed: server (open) | D-200 |
| CMP-10 minor | The browser words case sources and which group starts closed | Not fixed: server (open) | D-200 |
| CMP-11 minor | Long values cut mid-line; templates broken mid-identifier | Fixed | |
| CMP-12 minor | The glossary points at an API path | Not fixed: server (open) | D-200 |

**Review of the DTR tab**, in a browser. 2 blockers, 9 majors, 7 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| R01 blocker | "Continue in PAS" carried the old answers after a change | Fixed | D-192 |
| R02 blocker | "This tool shows and hides them as the answers change" was untrue | Tab part fixed (items say as of which step); the server's sentence open; no evaluation endpoint, decided | D-199, D-200 |
| R03 major | The package's expectation applied again to the response step | Fixed | D-197 |
| R04 major | A completed response drawn as "nothing further is required" | Not fixed: server tone (open) | D-195, D-200 |
| R05 major | The browser kept words for codes | Fixed in the tab (codes shown); the server's labels open | D-200 |
| R06 major | The form drawn when DTR is not configured | Partly: the tab says what is missing on arrival; the form and Fetch are still offered (open) | D-191, D-197 |
| R07 major | Focus lost after three updates | Fixed | |
| R08 major | A step's refusal shown beside the wrong button | Fixed | D-197 |
| R09 major | One action, two names | Tab part fixed; the server's labels open | D-200 |
| R10 major | Steps not said to be recorded or not | Fixed | D-197 |
| R11 major | Cream used for an override the key did not name | Fixed in the key | D-195 |
| R12 minor | Answers named without labels | Fixed | |
| R13 minor | "Take back" ambiguous; the removal sent again | Wording fixed; resending refuted | D-198 |
| R14 minor | The change block after a failed step | Fixed | |
| R15 minor | A long word clipped | Fixed | |
| R16 minor | Lines that read badly; raw hand-over keys; dropped answers with no value | Tab part fixed; carry labels and dropped values from the server open | D-200 |
| R17 minor | The loud strip about another questionnaire | Fixed | |
| R18 minor | Six to ten technical lines in the verdict strip | Not fixed: server (open) | D-200 |

**Review of the PAS tab**, in a browser. 8 majors, 8 minors.

| Finding | What it found | State | Entry |
| --- | --- | --- | --- |
| R01 major | A notification with a decision did not say it | Fixed | D-197 |
| R02 major | An unmatched notification sends the tester after a third party | Tab part fixed (the sample's notes shown, a warning when no subscription exists); the server's next step open | D-200 |
| R03 major | "Continue in PAS" on the PAS tab invited a resubmission | Fixed | D-192 |
| R04 major | Focus lost after each send | Fixed | |
| R05 major | A subscription said to call back an address the request never named | Tab part fixed (two statements); mock and verdict open | D-200 |
| R06 major | 200 notifications expanded, no filter | Fixed; the reason said twice is the server's (open) | D-197, D-200 |
| R07 major | No quantities, dates or what changed per item | Decided not to build the comparison | D-199 |
| R08 major | The case pane short on production at laptop size | Partly: the tab's box shorter; the frame's part open | D-191 |
| R09 minor | "Mock profile: this answer came from a scenario file" on a blocked run | The merged frame no longer says it on a blocked run (by reading the code; not seen on the PAS tab) | |
| R10 minor | No way to the inquiry after a fault | Fixed: "Choose the Inquire call" | D-197 |
| R11 minor | An unmatched notification in the tone for unknown member data | Tab part fixed; the tone open | D-195, D-200 |
| R12 minor | The browser words decision sources, attachments, reachability | Not fixed: server (open) | D-200 |
| R13 minor | Small layout faults | Tab part fixed; determination cells wrapping mid-value open (frame) | |
| R14 minor | A form offered although every send is refused | Partly: the tab says what is missing; the form is still offered and Send, made quiet, still sends (open) | D-191, D-197 |
| R15 minor | Preview notes in the past tense | Tab introduces them; the server's tense open | D-200 |
| R16 minor | Tester reference and note not on the result | Fixed | D-197 |

### What exists at this revision

- **Protocols.** CRD: discovery, order-sign and order-select, a conformance checker that judges by CRD
  2.0.1, 14 mock scenarios. DTR: questionnaire package, the adaptive next-question loop, the response
  step, 15 mock scenarios. PAS: submit, inquire, subscriptions (create, read, list, update, delete),
  the notification receiver, 21 mock scenarios. A request sent from the collection as it is, with its
  saved body and values for the parts of its url, and one mock scenario. A request of the tester's own
  on every protocol. `GUIDE_RULE_BROKEN` in all three. A secret a service sends back masked in every
  designed view but that of a request sent as written.
- **Configuration.** Postman environments and collections uploaded and stored, the operation mapping
  (changed and removed from the Config tab), variable resolution with labels for each source, the
  production guardrail judged by destination, credentials in collections named and masked.
- **Lookups and code list.** Member and provider lookup with 51 synthetic member fixtures and provider
  fixtures, by path or by query; the placeholder procedure code list and the picker.
- **The ledger.** JSON Lines, the tracking policy, notes, health with the files it cannot read, the CSV
  export with filters, replay that returns what the new run found, case lines, an optional time zone.
- **Suites and the chain.** Suites from CSV or JSON with a truthful preview, labelled counts and
  comparison with an earlier run; the chain as a suite case and the chain view; manual chains linked.
- **The request comparison** of a pasted CRD request with the tool's own (`POST /api/crd/compare`).
- **The HTTP edge**: bound to this machine, the request guard, no-store, the body limit.
- **README, `run.sh`, `run.cmd` and samples** (a Postman environment and collection, a code list, a
  suite). The README predates the screens and the leftover tasks.
- **The screens.** Each part was built by one agent, used in a real browser by a reviewer who did not
  build it, and finished by a third, on its own base. Seen in a browser means by those agents, on that
  base, mostly on mock, never by a business tester.
  - *The frame*: masthead with the environment strip and the ledger indicator, the production banner,
    the colour key, the shared result view (verdict, test result, next step, ledger line, findings,
    determination, detail, returned fields, wire, errors), the workbench, the case context, the
    hand-over. Seen: the four ledger states, a blocked result on mock, every key entry, the masking of
    returned values, the Config mapping and removal flows on mock and on a production environment, some
    with the keyboard and the others with the mouse (not every flow both ways). The readiness option was seen only by injecting it into a tab in the
    browser.
  - *The case* (member and provider lookup, code picker, a request of the tester's own, values for one
    run): seen in every lookup state on mock in the review before the fix wave merged (D-172).
  - *CRD tab and the comparison*: every one of the 14 scenarios ran through the real form, the
    comparison in each state the pass names (identical, one value different, the two vendor fixtures,
    no prefetch, another hook, not JSON, empty, very large, markup), fill and send, discovery. Live only
    at laptop size: the pass against an unreachable address, the review also against a loopback stub
    of its own.
  - *DTR tab*: the 15 scenarios swept at 1366 by 768, a static questionnaire to a complete response, a
    required question left unanswered, the adaptive loop to completion with a question appearing and
    one disappearing, a loop failing in the middle, a pre-populated answer overridden, two
    questionnaires, the keyboard through a whole questionnaire. PAS was a placeholder on its base.
  - *PAS tab*: the 16 submit scenarios, partial approval, pended then a sample notification then an
    inquiry (`pas-05`), an error returned by the payer, a subscription through its whole life, no
    notifications and many, production locked, the X12 attachment mode, the answers inside the request
    with a DTR answer injected. DTR was a placeholder on its base.
  - *History tab* (runs, one run, notes, replay, export, health, chains, suites) and *Config tab*
    (environments, collections, mapping, effective configuration, try a value, production writes,
    self-checks, a request sent as written): seen in their reviews before the fix wave merged
    (D-172); the Config mapping and removal flows again in the frame pass.
- The build: green on `7dade28`, 2597 tests, 2595 passed, 2 skipped (see the top of this section).

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | done, reviewed by six seats, frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | done and merged |
| Spine 2: integrate Wave A. V2 PHI audit | done. V2 ran once, before the fixes; its findings are fixed and the fixes verified; a second run has not happened |
| Spine 3: CRD reference vertical | done. Held to CRD 2.0.1 since the fix wave |
| Wave B: B1 PAS and subscriptions, B2 DTR | done and merged. Held to PAS 2.0.1 and DTR 2.0.1 since the fix wave |
| Spine 4: integrate Wave B, chain, suites, cross-protocol tests. V3 guardrail audit | done. V3 ran once, before the fixes; its findings are fixed, G4 in two halves merged and not yet seen together; a second run has not happened |
| Fix wave: every audit and review finding | merged at `33893aa` |
| Leftovers between the parts of the fix wave | merged at `7dade28` (protocol task `150b009`, platform task `e8f07b4`), each verified alone |
| Wave C: C1 frontend | merged at `7dade28`: the frame, the case, the CRD, DTR, PAS, History and Config tabs, each reviewed alone. What the screens asked of the server and the pass that joins the tabs are started and not merged |
| Wave C: C2 docs and scripts | merged. `run.cmd` never run. The README predates the screens and the leftovers |
| Wave C: C3 verification V1 to V7 | V2, V3, V4 ran once before the fixes. V1, V5, V6, V7 not run |
| Spine 5: clean build twice, fresh-clone build, push, pull request | not run as a step. The branch is pushed at every integration (at `7dade28` now); the clean builds, the fresh-clone build and the pull request have not been done |

### How the build got here

**Interrupted three times.** About an hour into Waves A and B every background agent was stopped at
once by a usage limit; what each had written survived in its worktree and was handed to a new agent,
and since then every agent commits a checkpoint after each green slice. The verifier planned after
each wave package was dropped then, and the section 27 agents and independent reviews took its place.
Later the build machine restarted twice, each time when several full builds ran side by side, and
everything then running was cut off; nothing on disk was lost and each task was handed on in its
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

Target: level 1, everything in the build prompt. At revision `7dade28` the server holds everything
level 1 asks of it, the optional capabilities of section 28 included: CRD, DTR and PAS implemented and
not stubbed, the chain, suites, conformance checking and response times, with the gaps listed under
"Not done" above. The frontend is on the line with all five tabs, and none of them is a stub. Level 1
is still not claimed: the five tabs have not been used together, section 20's rule that a tab does not
present a form that cannot send is met on no protocol tab, the screens do not yet read what the server
gained after their base, and the gates of section 27 and "Prove it before handover" have not run. The
level delivered is stated at the top of the handover once the pass that joins the tabs is merged and
those gates have run.
