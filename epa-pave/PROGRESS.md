# PROGRESS

Where the build is, what is done, what remains. Updated at every commit.

## Current position

This describes revision `902e0d9` on branch `epa-pave`, which is on the remote, before the commit that
adds these records. The integrator built the line at this content through the shared build wrapper
(`gw clean build`): 2870 tests, 0 failures, 0 errors, 2 skipped, "No house style violations.", BUILD
SUCCESSFUL in 4m 31s. The two skipped tests need a folder that cannot be written
(`ConfigStoreTest.aDirectoryThatCannotBeWrittenFailsAtStartupNamingThePath`,
`JsonlRunLedgerTest.startupFailsWhenTheDirectoryIsReadOnly`), which a build running as root cannot
make; the same refusals are covered by tests that did run. The integrator's last gates ran on the
commit that held the records of that revision, and their result is in the pull request description,
part 2 (D-267). What the head holds since `902e0d9`, and its later build, follow below.

It holds `428eff5` and, merged since: the records and README pass that settled the lines marked for the
four closing tasks (`final-touch` at `c4bc498`, D-253 to D-259), and **the four last fixes**, one for
each part of the last independent check that found a blocker or a major: completeness on screen
(`last-render` at `fff3bd0`), the walkthrough (`last-uat` at `10108d4`), the ledger (`last-ledger` at
`54f9912`, merged by hand at `60876fa`) and the guardrail (`last-guardrail`, content at `49ae839`,
merged at `7df9718`, its last commit `ca716a1` at `902e0d9`); and one test the integrator corrected
(`6b7abba`, D-267). **The last fixes have been checked by nobody but their authors, and no check
follows them** (D-268). `428eff5` holds the line of `e1d37dc` (the fix wave, the leftover tasks, the
five tabs joined into one tool, the server work for the screens, the fixes for the second run of V2,
V3 and V4, the last server task and screens pass, and the fixes for V5, V7 and the walkthrough) and the
four closing tasks (D-253 to D-256).

The decisions of the fix wave are D-140 to D-175, those of the leftover tasks and the frontend D-176 to
D-201, those of the join, the server work for the screens and the second audits D-202 to D-228, those
of the last server task, the last screens pass, the last audits and the checks of the fixes D-229 to
D-252, those of the four closing tasks D-253 to D-259, and those of the last check, the last fixes and
how the checking ended D-260 to D-268. D-269 records what the operator replied to the four questions of
D-227 after the pull request was opened, and D-270 the operator's later word that the tool is to be run
as a real service one day, with what an independent check of that entry found; the commits that record
them change this file, `DECISIONS.md` and the README only, and no code. D-271 records the operator's
question whether the tool was ready to load into STS, the first answer that was wrong in part, the
code change it led to and what an emulation of STS showed, with what an independent check of that
entry and the README's STS section found. D-272 records the two runs of the real STS that followed,
on Linux with Spring Tools 5.4.0, and the change made between them, with what an independent check
of that entry and the README's STS section found.

**The code handed over is no longer `902e0d9` alone.** Since then the head has gained the documents of
D-269 and D-270, which change this file, `DECISIONS.md` and the README only, and two code changes:
`05acbf7` (D-271, merged at `a5040bd`), in which every controller argument names itself and
`HandlerArgumentNamesTest` holds that, and `ace5914` (D-272, merged at `5923b3f`), which changes
tests and adds one launch file, `PaveApplication.launch`, and no main code. The whole build of
`05acbf7` through the shared wrapper (`gw clean build`): 2872 tests, 0 failures, 0 errors, 2
skipped, BUILD SUCCESSFUL. The whole build of `ace5914` the same way: 2872 tests, 0 failures, 0
errors, 2 skipped (the two that need a folder that cannot be written), BUILD SUCCESSFUL. Its code is
the code at the head, so this is now the last whole build this file can record; the integrator's
gates run after this file, and their result is in the pull request description.

### Open at the revision handed over, the worst first

Each line can be read alone. The detail of each is further down and in the entry named. "Fixed by
its author only" means one agent made the change, saw its own test fail without it, and nobody else
has looked.

1. **The last fixes are checked by nobody but their authors, and no check follows them** (D-268). Four
   fixes (ledger, completeness on screen, walkthrough, guardrail) changed what keeps member data out of
   the ledger, what keeps writes from reaching production, and what the screens say. Each of the three
   rounds of checking before them found something real, and in each the round before had broken
   something. One merge (`60876fa`, `RunRecord.codeEntry`) was joined by hand and checked only by the
   build. After the merges nobody opened a browser: the two proofs were run by the integrator through
   the API on mock, and the screens were last looked at by the four fixers, each on its own branch.
2. **Nothing has been observed against the vendor's service.** Every live check used a stand-in on a
   loopback port. The operator says the vendor's sandbox follows 2.0.1, the version the tool is held
   to; that has not been observed. Of the four questions put to the operator (D-227), two are answered
   and two stand as they were (D-269). The lookups have never met the
   real member API or provider directory, and the token request's HTTP Basic is an assumption (D-233).
3. **Not fit to be run as a shared or production service as delivered.** The operator has said the tool
   will be productionized one day, which is read here as running it that way (D-270). The tool has no
   sign-in, serves plain http, keeps secrets in plain text on disk, records no user, sends a request
   wherever an upload says, and is one copy on one machine; it keeps listening on this machine only, and
   is not to be opened to the network before a sign-in exists. The README, "Before PAVE is run as a
   shared or production service", lists what must exist first; none of it is built.
4. **The guardrail, what it still lets through or cannot see.** A production environment refused at
   upload is not stored, so a sandbox at its address is not refused until the production environment is
   uploaded with its address written plainly. Addresses read from `onyx.<protocol>.url` are no
   production environment's. On production's server a sandbox whose base path holds `%00`, `%20` or
   `%2e`, or a production base path holding `%2F`, is warned about and not refused; this matters behind a
   server that decodes such bytes. Not proven: IPv6, explicit default ports on the wire, hosts other than
   loopback, a real front server, proxies and redirects, another DNS name for a production host, a
   collection variable supplying a production address on the jar (D-145, D-225, D-265).
5. **Member data the ledger can still keep.** A name, a date of birth written in words or an address the
   request does not carry is written as typed in a note (D-217). A body of the tester's own is read for
   identifiers only, not names or dates, and is read as typed when one of its placeholders has no value
   or is filled from a collection variable (D-262). A suite expectation that holds a date of birth or a
   name and is also malformed is repeated in the results file. A supplied identifier of 64 hex
   characters or shaped like a UUID is kept in clear whatever its digits. A note added later on a token
   line cannot be checked for a name or a date of birth. Lines the first ledger fix wrote as "not
   recorded" under a ClaimResponse or subscription id cannot be matched. The configuration store keeps
   uploads as uploaded (D-213), secrets and the installation salt sit in plain text in `config-store/`
   (D-234), and the wire view carries member data.
6. **What a tester meets on screen that is wrong or gives nothing to do** (D-257, D-266):
   - discovering the CDS services on the CRD tab is refused when text is left in the procedure code
     box, although discovery sends nothing of the case; the sentence says which box to clear;
   - the next step still says only that the tool cannot say what should happen next for CRD
     documentation required that cannot be acted on, authorization not stated, an unrecognised code and
     mixed decisions, for DTR, and for a request sent as it is;
   - History, its "Next action" filter and the chain view show a PAS run with no decision as "Look at the
     response detail. The tool cannot say what should happen next.", where the PAS tab gives two lines;
   - on mock, the next step of a played HTTP 500, a played unreachable service and the DTR package played
     malformed still says to check the environment or to report a defect in the service, though its
     heading and action say nothing left the machine; a mock member lookup that plays a timeout is headed
     as a fault in the environment (G2-7);
   - the label of the CRD outcome for an answer that is not what the operation returns still speaks of a
     body that could not be read, in "Happened", History and a chain, also for an OperationOutcome at
     HTTP 200;
   - with an earlier-version production environment active whose address cannot be read, the Config tab
     offers an unlock that is always refused (G2-6);
   - a replay of a run id that does not exist answers with the general action;
   - History, a suite row and a chain say nothing of fields the tool did not interpret (F8);
   - the subscription mock and the sample notification write a scenario body out again, so one that
     repeats a key loses its earlier values on mock with nothing on screen saying so;
   - the server heading of a refused form says nothing of what to do; the CRD finding title for a
     sub-extension reads the same for null and empty; a subscription's headers have no presence of their
     own; "read the response detail below" is shown in the replay panel and the chain view, where nothing
     is below; the repeated-key detail says "the value shown above" for a key not drawn; the action of a
     "not configured" error does not fit a url that is not valid as filled.
7. **Who answered, and what History can tell apart.** A notification posted to the receiver by hand
   while the tool runs on mock is recorded as answered by a mock scenario; lines written between
   `98effa6` and `428eff5` with the service beside a scenario id are read as written. When two responses
   were completed from one package, nothing says which the PAS submission carried (D-181).
8. **What the build cannot see.** Behaviour on screen has no test in the build: the drawing of the
   presences is held by tests that read the script's text (NEW-6), and the test of the code box refusal
   runs through Node and is skipped where no `node` is on the path. A test that searched a line holding
   random tokens failed by chance once in about a hundred builds and was corrected (`6b7abba`); a build
   green twice does not prove no other such test is left. No test holds that a replay on mock uses the
   rewritten scenario sentence.
9. **The fixtures and the style gate, given no fix after the last check.** Fixtures: a CRD note for
   `crd-04` chosen by hand does not say the DTR sentinel came from the body; the DTR sentinel note says
   CRD answers documentation required whatever the file says; two descriptions slip past the
   old-directory check; a PAS list filtered with `?for=inquire` or `?for=notification` hides the CRD
   mapping; the run note of a PAS file chosen by a mapping entry that is no CRD sentinel still says the
   ID "answers for the same case". Style gate (D-258): a non-ASCII file name crashes it under a POSIX
   locale and the failed build shows the last run's "No house style violations."; several dash-like,
   quote-like and invisible characters and entities are not refused; a text file with a binary extension
   or an early NUL byte is skipped unread; a second, weaker copy of the tracking rule; invented surnames
   and 16 files of empty banners remain. The gate does not read commit messages or the pull request
   description.
10. **Never run or never tried.** `run.cmd` (no Windows machine); STS beyond what was seen. The real
   Spring Tools 5.4.0 on Linux imported the project with its defaults and started it on mock from the
   Boot Dashboard and with Run As, on `e70a873` and on `ace5914`; on `e70a873` it also started it on
   live through the launch dialog and ran five test classes, three of them again on `ace5914` (D-272).
   Not seen: Windows, a real tester at a real workstation with a corporate proxy or mirror, the
   screens in a browser after a start from STS, the live profile against a service, the whole test
   suite from the IDE, and STS on a release other than 5.4.0 (the README, "STS (Spring Tools for
   Eclipse)"). Also never tried: a mirror with credentials and a token server; a screen reader,
   contrast, 200% zoom and phone width; many states listed below under the screens as never drawn.
11. **Limits of level 1, by decision** (D-235): in the X12 275 attachment mode the tool names the
    document and sends nothing; no `$submit-attachment`, no claim update, no `$expand`, no
    `$log-questionnaire-errors`, no CQL; DTR is the operations behind a SMART launch, not the launch; the
    notification receiver listens on this machine only by default and does not authenticate its caller;
    the tool has no sign-in.

### Gates, first

- **Section 27: every audit has run at least once, and the walkthrough has run.** Each was done by an
  agent that wrote none of the tool. Which revision each examined, and what was merged after it:
  - *V2 (member data), V3 (guardrail), V4 (fixtures), second run:* on `0658a7c`. Their fixes were
    merged at `5763c41`. **Each fix was then checked**, on `9e5306d`, by an agent that wrote neither
    the tool nor that fix, which took each fix out again and saw its tests fail (D-245). The checks
    found several findings open one step away and some new ones; four tasks fixed them (D-253 to
    D-256). Merged after the checks: the three fixes for the last audits, those four tasks and the four
    last fixes (D-262 to D-265).
  - *V1 (the build from a fresh clone), V5 (house style), V7 (completeness on screen) and the
    walkthrough by a business tester:* on `9e285a1`. Merged after them: the six lines (`seam-4`) and
    the three fixes for the last audits, the four closing tasks and the four last fixes. V5 ran only the style task, no
    clean build. V7 and the walkthrough built only the jar.
  - *V6 (honesty):* on `9e5306d`. Merged after it: the three fixes for the last audits, the four
    closing tasks and the four last fixes.
- **The last independent check has run, all six parts** (D-258, D-260), each by an agent that wrote
  neither the tool nor the fix: the ledger, the fixtures, completeness on screen, the walkthrough and
  the house style gate on `98effa6`, the guardrail on `428eff5`. The fixtures part found every line
  closed; the others found lines open one step away and new findings, among them one blocker and
  fourteen majors. The table of the last check, below, gives each. **The four last fixes made for it
  (D-262 to D-265) are merged and have been checked by nobody but their authors; no check follows them**
  (D-268). The style gate and the fixtures were given no fix: what those two parts left is open.
- **The builds.** V1 built `9e285a1` from a fresh clone of the remote twice through the shared wrapper
  (2714 tests, none failed, 2 skipped) and a third time with the build cache off, started it with
  `./run.sh` and answered every endpoint of section 18. V6 built `9e5306d` from clean and from a fresh
  clone, through the same wrapper (2720 tests, none failed). The line at the content of `902e0d9` has
  been built by the integrator alone, through the wrapper (`gw clean build`: 2870 tests, 0 failures, 0
  errors, 2 skipped). Each last fixer's count (ledger 2834, completeness 2836, walkthrough 2837,
  guardrail 2841) was taken on its own branch before the merges. The wrapper adds `--offline`; every JVM
  on the build machine runs with a machine-wide option that disables two base64 intrinsics; and on this
  machine a build from a fresh clone takes its compiled classes from the shared build cache unless the
  cache is switched off (V6's F-JAVAOPTS and F-CACHE-FRESH).
- **The integrator's last gates run after these records**, on the commit that holds them: the plain
  `./gradlew clean build` twice, the build from a fresh clone with the build cache off, the start and
  `GET /api/mode`, and the changed paths. Their result is in the pull request description, part 2; a
  file cannot hold the result of a build of the commit it is part of (D-267).
- **`run.cmd` has never been run**: there is no Windows machine here, and no agent ran on Windows.
- **Nothing has been observed against the vendor's service.** Every live check any agent reported used
  a stand-in on a loopback port. The operator says the vendor's sandbox follows 2.0.1, the version the
  tool is held to (D-136, D-269); that has not been observed. Of the four questions put to the operator
  (D-227), two are answered and two stand as they were (D-269).
- **"Prove it before handover."** Part 1, the walkthrough by a business tester, ran on `9e285a1`. It was
  automated (browser scripts driven by an agent reading only the screen), on mock with one CRD run on
  live against a stand-in, and is not a sign-off. Its three majors were fixed, the check of those fixes
  found two of them closed in part and four new majors (D-260), and the last walkthrough fix (D-264) closed some and
  left the rest open (above). The two proofs of part 2 were seen on the real tabs, on mock, by the second
  pass over the screens on `0658a7c` (D-208) and by the walkthrough on `9e285a1`. **On `902e0d9`
  they were run by the integrator through the API, not on a screen**: on the jar built from that
  content, on mock, with a script (D-267). On the code of `05acbf7` they were run in the emulation of
  D-271, from classes the Eclipse compiler built, and held; on `e70a873` and `ace5914` they were run
  through the API after starts from the real STS 5.4.0, and held (D-272); the integrator's gates run
  them again on the head, from a fresh clone, and that result is in the pull request description. `P32H-NO-AUTH` gave `NO_AUTH_REQUIRED`,
  "Prior authorization is not required.", next action `FLOW_ENDS`, and `P32H-EMPTY` gave
  `NO_DETERMINATION`, "The service answered but returned no determination.", next action
  `REPORT_NO_DETERMINATION`; their two ledger lines differ in outcome and hold no raw request or
  response. For `P32H-DOC-DTR` the QuestionnaireResponse DTR completed was in the PAS request as
  previewed and as sent (wire view), with the same four answers and status completed, and the PAS
  submission was approved. Nobody opened a browser on `902e0d9`; the screens were last looked at by the
  four last fixers, each on its own branch before the merges: the completeness fix read `P32H-NO-AUTH`
  and `P32H-EMPTY` on its jar, on the tab and in History, the walkthrough fix the journey with `P32H-DOC-DTR` from
  CRD into PAS, each at 1366 by 768.

### Not done, not verified, or known to be wrong

The list above is the short form. Below is the detail by part, brought up to `902e0d9`: where a last
fix (D-262 to D-265) closed a line, the line says so, as fixed and checked by its author only.

**The ledger and member data.**
- A run with a body of the tester's own from the CRD, DTR or PAS tab is checked against the member
  identifiers that body carries (D-253), read from the body as it will be sent, its placeholders filled
  from the run values, the active environment and `application.yml` (D-262, fixed by its author only).
  What is left: only identifiers are read, not a name or a date of birth; collection variables are not
  used, and a body with any placeholder that has no value is read as typed; on the jar the last fix's
  author tried a placeholder body on CRD only, and no test covers DTR or PAS with one; no tab was driven
  in a browser with such a body.
- A note added later on a line that holds only the token is checked against the token: the digits of
  the ID alone are found only when one or two letters stood in front of them, and a name or a date of
  birth cannot be checked, because the line holds neither (D-253).
- An identifier a service supplies is a token unless it is a UUID, an OID or one the tool minted
  (D-253), also when it holds the run's member ID, so its notification is matched (D-262, fixed by its
  author only, seen on the jar through the API). What is left: lines the first fix wrote as
  `(not recorded, N characters)` under a subscription or ClaimResponse id cannot be matched; a supplied
  id of 64 hex characters or shaped like a UUID is kept in clear whatever its digits (NF6, recorded). The
  static hook `RunRecord.codesOnTheList` is gone: what a line says of its codes is judged when it is
  written (D-262); a line from before `ffb336e` holding an identifier-like code now shows it when read.
  The frozen `InMemoryLedger` does not tokenise supplied ids.
- Suite results: an expectation refused for member data is replaced by the refusal and not quoted
  (D-262); one that holds a date of birth or a name and is also malformed is refused for its shape and
  repeated in the results. Three more spellings of the date of birth are found (D-262); one written in
  words is not.
- Checked by unit tests only, not on the jar or in a browser: a long code the code list holds kept as
  written (no loaded list held one); a notification on a subscription this tool deleted, and the next
  step after it; the run id refusal's new sentence on the PAS tab (on live the sample endpoint refuses
  earlier, as the author saw).
- Known limits that stand: a name, a date of birth or an address the request does not carry is written
  as typed, so member data must not be typed into a note (D-217); older lines that hold a name or a
  date are shown and exported as written; a one-word surname in a code is kept (D-218); a
  raw send looks for identifiers only, not names, dates of birth or a `P32H-` sentinel, and a raw write
  the guardrail refuses is checked against the body as typed, not the saved body (D-231). The
  configuration store keeps uploads exactly as uploaded and can hold member data an uploaded file
  contains; the upload warns by the names of variables and saved examples, and nothing is stripped
  (D-213).
- The upload warning's new names (`mbr`, Medicare and Medicaid, MBI, SSN, MRN, HICN, an address, a
  telephone; D-254) are covered by a test and were not checked on the jar.
- The member data check found 70 or more `pave-server-*` folders in `/tmp`, 37 of them empty, left by
  processes it could not attribute and did not look into. The tool removes its own at a clean stop and
  a hard kill leaves it (F7, D-216); whether any of these came from a clean stop of this tool is not
  known.
- The installation salt sits in plain text in `config-store/`; with it a member ID is recovered from
  its token at once (D-234). The README says so (D-250).
- The ledger record holds no count of the fields an answer carried that the tool did not interpret, so
  History, a suite row and a chain say nothing of them (V7's F8). Decided and given to no task (D-251).

**The guardrail.**
- A production environment whose own address the client cannot read is now refused at upload and at
  activation, and one an earlier store left is remembered as its whole host (D-254). The fix's author
  proved it on the jar with every spelling the check used, against stand-ins: nothing arrived. A store of
  an earlier version can leave a stray environment file when an earlier production is uploaded again
  (seen once by the author, not looked into).
- The last guardrail fix (D-265, fixed by its author only, proved by it on the jar against listeners):
  what is remembered for a production environment is read through the environment, collection
  variables and `application.yml`; a production address that cannot be read, has no value or comes
  from an operating system variable is refused at upload, activation and unlock; a remembered whole host
  is dropped only for an upload on that same host; a raw character above 0x7F in a path is refused as
  its escape; `http:///` and `http:/` addresses count as unreadable. What is left: a production
  environment refused at upload is not stored, so a sandbox at its address is not refused until it is
  uploaded with its address written plainly; the `onyx.<protocol>.url` fallbacks are no production
  environment's address; a collection variable supplying a production address was proved by gateway
  tests only; the G2-5 wording was not run on the jar; G2-6 (an unlock offered and always refused for an
  earlier-version production whose address cannot be read) and G2-9 (the member data warning misses
  `MBRID` in capitals, `mobile` and `addr`, and catches `webhookAddress`) are recorded, not fixed.
- Not covered by any decision: a non-production environment on production's server whose base path
  holds `%00`, `%20` or `%2e` is accepted with the m4 warning, and its writes reached production's port
  in the check; the guardrail fix saw the same and left it, as D-247 item 3 decided. A production environment whose base path holds `%2F` does not protect the decoded
  spelling, and a sandbox there took a submit. These matter only behind a server that decodes such
  bytes. D-247 item 3 covers escapes above 0x7F only.
- The unlocked panel's other sentence ("It ends with any change to the configuration... A restart of
  the tool ends it too") is incomplete, though no longer untrue (a neighbour of m2 in the guardrail
  check). Given to no task.
- A notification's notes about short secrets (D-254, item 4) and the new member data names are covered
  by tests and were not checked on the jar. The guardrail fix's new sentences (the whole host in
  `productionDestinations`, the warning for a host whose production address could not be read, the
  played-fault headings) were read through the API on the jar, not on the Config tab or the result
  panel. The jar check of its final commit repeated only the earlier-store run; the fresh-store and mock
  fault runs were made on the commit before the last, which only reworded two refusals.
- The action of a "not configured" error (`ApiExceptionHandler`) always sends the tester to the Config
  tab, which does not fit the executor's new "the url as filled is not a valid url" message; on live
  the gateway refuses that case first, so only a call prepared elsewhere reaches it (D-257).
- Not proven: IPv6 destinations (no IPv6 loopback here); a real nginx, Tomcat or IIS in front of a
  service, on which M-ENC, m3 and N3 rest; a replay or a chain that reaches the gateway against
  production on live (covered over the server by `ProductionRefusalOverTheServerTest`, D-205); proxy
  settings and redirects (read in the code); hosts other than loopback (IDN, other DNS names, read in
  the code); explicit default ports over the wire; TLS listeners. Neither guardrail fix proved these; the IPv6 forms of its whole-host
  reading are unit-tested only. A process killed outright leaves the embedded server's working folder behind (D-216).
- D-145 and D-225 stand, except D-145's second bullet, which D-265 replaced: a production environment
  whose address comes from an operating system variable is now refused. Another DNS name for a
  production host is not recognised.

**Fixtures and the mock.**
- The fixture task (D-255) left: the list notes of a PAS list filtered with `?for=` are tested for
  `submit` only; no test covers a CRD file that speaks of DTR sentinels while another CRD file carries
  `answersDtrSentinels`; the run note of a PAS file chosen by a `crdSentinelMemberIds` entry that is no
  CRD file's sentinel still says the ID "answers for the same case", while the list note says it means
  no CRD case; `crd-04` chosen by hand was driven into DTR in a browser for two sentinels at 1366 by 768,
  not on to PAS and not at 1920; the earlier check's tools `damage.py`, `damage2/3.py`, `chain_suite.py`,
  `live.py` and the browser loop of every DTR sentinel were not run again.
- On mock, every fault a scenario plays is now headed as played and its action says nothing left the
  machine (D-254), but the next step of a played HTTP 500 or a played unreachable service still says
  "Next: check the environment, then send again." (CRD `P32H-ERR500`, DTR `P32H-DTR-ERR500`, PAS
  `P32H-PAS-ERR500` and `P32H-PAS-DOWN`). Given to no task (D-257). The DTR package played malformed
  (`P32H-DTR-MALFORMED`) is now headed as played (D-265, fixed by its author only), and its next step
  still says to report a defect in the service. A mock member lookup that plays a timeout is headed as a
  fault in the environment (G2-7, recorded).
- The subscription mock and the sample notification still write a scenario body out again, so one that
  repeats a key loses its earlier values on mock with nothing on screen saying so; the PAS mock now plays
  such a body as written (D-263, D-266).
- The fixture check's four new minors were given no fix (D-260): the CRD note for `crd-04` chosen by
  hand does not say the DTR sentinel was found in the body; the DTR sentinel note says CRD answers
  documentation required whatever the outcome of the file that carries `answersDtrSentinels`; two kinds
  of description slip past the old-directory check; a PAS list filtered with `?for=inquire` or
  `?for=notification` drops `crdSentinelMemberIds` and its notes.
- An unknown folder at the scenario root (`other/`) is ignored without a word; the fixture check saw it
  and judged it too small to report as a finding. Given to no task.
- An `expect` block whose outcome contradicts what its own file produces, with the same HTTP status, is
  not detected, and its outcome is still advertised (D-224, a known limit). Any new top-level key in a
  scenario file stops the mock start until the loader knows it.

**The screens.** Every pass drove the tool in a real browser with scripts, mostly on mock; no person
has used it, and behaviour on screen has no test in the build (the static tests read the page's
source). What is still wrong, or not seen:
- *Since the last fixes (D-263, D-264, each fixed by its author only, seen by its author at 1366 by 768
  on its own branch, nobody at 1920, nobody on `902e0d9`).* Text left in the code box no longer blocks a
  subscription call or a request of the tester's own, but still blocks CRD discovery, which sends
  nothing of the case. PAS no decision, an unrecognised decision code, items decided differently, an
  unrecognised subscription response and a notification of an unknown type give two lines (record the
  run and report it, then the detail); CRD (documentation required that cannot be acted on, authorization
  not stated, an unrecognised code, mixed), DTR and the raw send still say only that the tool cannot say
  what should happen next, and History, its filter and the chain view show `REVIEW`'s label for all of
  them. A replay of an unknown run id answers with the general action. The chain view says how many of
  its runs were measured. The shortened OperationOutcome headline and the NEW-8 neighbour (a choice that
  names a value set) were not looked at in a browser, and neither was the History token sentence of
  D-262.
- The heading the server gives a refused form, "A problem with what was submitted", says nothing of
  what to do; the action under it does (D-257).
- The title of the CRD finding for a sub-extension reads "was sent with no value" for null and for empty
  alike; the label of the CRD outcome for an answer that is not what the operation returns still speaks
  of a body that could not be read, in the test result's "Happened", History and a chain (D-257).
- The headers of a subscription have no presence of their own, so "none returned" stands for not
  returned, null and empty alike (D-257).
- Next steps that say "read the response detail below" are shown also in History's replay panel and the
  chain view, where nothing is below (D-257).
- The detail of the repeated-key finding says "the value shown above" also for a key the view reads and
  does not draw, such as `type` of a DTR or PAS Bundle (D-257).
- Seen in a browser by the screens task (D-256), at 1366 by 768 and 1920 by 1080: the repeated keys on
  live against a stand-in, its audit cases, the journey, the error sections and the chain view; at 1366
  only: the Config tab, the replay panel, the subscription and notification presences and the unlock
  button. Its tests for the error heading and the button names read the page's source and were not run
  against the code before the change. Not seen in a browser by anyone: the DTR and PAS refusal of a
  note naming the member of a body of the tester's own, the notification on a deleted subscription, the
  run id refusal's new sentence. The ledger task saw its History token sentence in a browser at both
  sizes.
- Also not seen in a browser: the label of `CHECK_ENVIRONMENT` on a chain view and a suite row (tested
  through the API); the colour key's sentence on a fault played on mock; the export's new
  `answeredBy` column; the grouped wording of the History outcome filter.
- The DTR change block (appeared, disappeared, dropped) is drawn twice, in the questionnaire pane and in
  the result detail. On DTR and in a subscription answer the notes for a body that is not JSON were not
  reworded, as CRD's and PAS's were.
- *Seen at one size only.* The last screens pass swept production locked, unlocked and held by the
  floor, UAT, live with an empty store, tracking off, a ledger that cannot write, a suite and a replay
  of one run and of a date range at 1366 by 768 only; its address warnings in the environment list,
  the six lines, the three last fixes, the refused notes and most DTR states were seen at 1366 only.
  No screen reader was used on any tab, contrast was never measured, 200% zoom and phone width were
  never tried (at 390 pixels, in an early review, the History runs table and the CRD elements table ran
  past the edge), and `prefers-reduced-motion` was checked only by reading the stylesheets.
- *Never drawn on screen.* `lookupEnabled` and `blockedShort` (read in code), the 409 path of a mapping
  change, a 422 on a mapping, a DTR question of an unrecognised type, a quantity, an attachment, a
  reference, an open choice from a value set only, a repeating text question, a questionnaire of
  undetermined kind, a wrapped next-question step, a DTR package request of the tester's own, DTR
  "could not be reached" on live, History's view and replay of a DTR run, PAS on live with an
  unreachable sandbox, the masking of a channel header, a PAS payload over the 131072-byte cut, a chain
  joined from the PAS tab, PAS inquiry scenarios other than the pended one (the timeout and server
  error ones were followed by an inquiry through the API only), a PAS replay that is sent, and the PAS "Show the request" preview on live.
  Whether every protocol's determination grid writes "no meaning to state" was not checked. The Config
  tab's files were never reread against the Config review's CFG-01 to CFG-22.
- *Older, still true.* The body source of a raw send is served as a code only and the page shows the
  server's sentence; nothing has been decided about it. A tester reference or a note typed on one tab
  is not carried to the next. The PAS detail and the notifications list rely on the server's masking,
  with no page stopgap. The member and provider detail records of a lookup still hold the mask text of
  a masked field; the page fills the case only from the marked field list, and no build test pins it.
  When a pasted request and the case disagree, only a member ID gets a button; two dates of service
  that disagree are listed with no button. The vendor's sample on
  the CRD tab passes through the browser, and the tool's own hook instance is always one difference.
  The slow-response flag was seen only on mock with a lowered threshold. Whether a run-value field for a
  builder variable (such as `subscriptionHeartbeatSeconds`) says where the request uses it was not
  reported, because `/api/config/resolve` does not describe builder variables. A DTR quantity's unit
  is free text, and an answer the response holds is never put back into a control. Nothing shows how
  an inquiry's answer differs from the last one seen. The blank "Meaning" cell a review saw on a
  subscription status row was never reproduced. A ledger that cannot write can be reached only by renaming its folder
  after start: a folder that cannot be created stops the tool at start.

**The server, by endpoint.**
- *The server work for the screens (D-202 to D-205).* Nobody ran the platform task's tests against the
  code without the change; two of the protocol task's mutations were caught only by tests added
  afterwards and were not run again. No agent but their authors has checked either task, apart from
  what the second audits and the checks read.
- *Refusals on production.* A refused replay and a refused suite row are proven together by
  `ProductionRefusalOverTheServerTest` over the running server, and a refused PAS replay was seen on
  screen; a refused suite row, and a blocked suite row of a protocol chain against production, were not
  driven by hand.
- *Configuration.* A 404 for a removed environment does not name it, and a placeholder in a collection
  body is not counted by "can this operation be sent" (both decided, D-205).
- *CRD.* `POST /api/crd/compare` cannot take a collection request by name, ignores `configEpoch`, and was
  last checked through the API only.
- *DTR.* A failure of the tool inside the response step answers 500 and writes no ledger line (from the
  first DTR report, not checked again). The intended use code system in the PAS bundle was not checked
  against DTR 2.0.1. Replay of a DTR run, and a PAS replay that is sent, were never driven through the
  running tool.
- *Older items still true.* A run with the tester's own body records no member, NPI or line of
  business, and nothing on its line tells two such runs apart (CASE-05). A CRD or PAS step of a chain
  handed only the procedure codes its line already holds is still marked not replayable alone (F7). A
  second suite started while one runs is answered 409 with kind `VALIDATION`. A well-formed chain id
  the tool never issued is accepted. `SuiteCases` refuses a hook with "The hook must be order-sign or
  order-select." rather than the CRD service's sentence. "Existing authorization" is still in
  `RedactionTest`, and "already satisfied" in the description of the member fixture `p32h-pa-sat.json`.
  A lookup fixture file that repeats a key is read. After the ledger folder was restored, the server
  kept saying it cannot write until the next write succeeded. A lookup identifier with an encoded slash
  in the path form ends as a bare 400 (the query form works, D-189).
**The README and the pull request draft.** The last pass over both (D-250) brought them up to
`e1d37dc`, checking every sentence that quotes the screen or the API on the running tool, and is merged.
Its lines for the four closing tasks were settled on `428eff5` from the source and the reports of
the fixes, without starting the tool again (D-259). The draft's attribution footer holds a non-ASCII
character and names the generating tool, which conflicts with the house style and the rule against
naming a tool as an author; which wins is undecided (D-209). Not checked by anyone: `run.sh` refusing a
Java older than 21, and an `{{env:PAVE_...}}` reference at send time on the jar.

**Limits each part reports that are still true.**
- Level 1 is described with its limits (D-235): in the X12 275 attachment mode the tool names the
  document and sends nothing; no `$submit-attachment` and no claim update (D-161), a resubmission reuses
  the claim id (D-133); no `$expand`, no `$log-questionnaire-errors`, no CQL; DTR is the operations
  behind a SMART launch, not the launch; no sign-in.
- The token request authenticates the client by HTTP Basic; a vendor that wants the form body or a
  signed assertion answers 401 (D-233). No token server was tried.
- CRD: US Core 3.1.1 PractitionerRole was not checked. `Organization.active` is never sent (D-141).
- PAS: US Core 3.1.1, HRex, the subscriptions backport 1.1.0 and the X12 code lists were not available;
  the 2.0.1 examples were not compiled; the request was checked against hand-written 2.0.1 rules that
  do not test slices or invariants. Mock X12 reason and category codes are placeholders. The PAS Task
  route to CDex is not modelled (MOCK-03).
- DTR: SDC 3.0.0, US Core 3.1.1, FHIR R4 core and the 2.0.1 narrative pages are not here, so only dtrx-1
  and dtrb-1 are evaluated and the next-question address is an assumption. An answer's author carries
  no role. The rules DTR-07 names were tested only with made-up questionnaires.
- The chain: a DTR package that goes straight on to PAS is driven by no test and no mock scenario.
- Credentials: one typed into a path segment or a host of a collection is shown as written; a secret in
  the path of a call back address with no variable is shown. The collection file keeps the value on
  disk. Secrets sit in plain text in `config-store/`.
- The HTTP edge: only Linux, curl, raw sockets and headless Chromium were used. `OPTIONS *` carries none
  of the tool's headers (D-150). A cross-site request that carries neither `Sec-Fetch-Site` nor `Origin`
  is let through (D-148). Several uploads of 30MB held in memory at once were never tried.
- The ledger: folders and day files made before the first fix keep their old modes. Flush and sync are
  checked by reading the code. Two instances on one ledger directory are not coordinated. A note
  written after the clock was set back across midnight is on disk and not applied.
- Suites: whether a code assertion's value is in its value set is not checked before sending. The test
  ledger does not apply case lines as the real one does.
- The code list: no real P32H export was available, so its header row, flag spellings, date format, time
  zone, vendor names, encoding and columns per line of business are unknown. A real export whose notes
  column held the marker "Not from a P32H export." would be taken as synthetic. The first-row message
  still shows cells of letters alone.
- Lookups: neither has met the real member API or provider directory; both response mappings are
  assumptions (section 26). A name returned in one piece cannot prefill first and last name.
- Notifications: the receiver does not authenticate its caller and has never been called over a socket
  by a real service; with the default bind address no vendor can reach it, and `GET /api/mode` says so.
  Two notifications at the same moment are not tested.
- Postman: no real vendor export was available; every collection the tests use is hand-written.
  Concurrency in the store is reasoned about, not tested.
- The request comparison: no real vendor request was available (D-227). The operator has since said
  that one with no real member data can be shared; none has arrived (D-269). A placeholder whose value
  exists only in a collection's variables is reported as having no value. Codes in NutritionOrder,
  VisionPrescription, CommunicationRequest and Appointment are not read.
- The wire view carries member data. It and every masking that follows its rule miss a secret sent back
  re-encoded (percent, base64, another case) or shorter than six characters.
- The style gate reads files only, not commit messages, the pull request description, names of people
  or comments that restate the code (D-244); its own failure path was seen by hand only. Its check
  (D-258) found, and nothing has fixed: a file name with a character outside ASCII crashes it under a
  POSIX locale, and the failed build then shows the previous run's "No house style violations."; several
  dash-like, quote-like and invisible characters, and entities or escapes that render a forbidden
  character, are not refused (none is in the tree); a text file with a binary extension or a NUL byte
  near its start is skipped unread; `StaticFrontendTest` keeps a second, weaker copy of the tracking
  rule; invented surnames that do not start with `Placeholder` remain in a few tests; empty section
  banners remain in 16 main source files.
- Documentation: STS was seen on Linux with Spring Tools 5.4.0 only, not on Windows or another
  release (D-272); a mirror that needs credentials and a token server were not tested. A plain `./gradlew build` can skip the sample tests after a sample is edited; `clean
  build` runs them.

**Checked only by unit tests, by reading the source, or at the gateway, never in the running tool.** A
chain interrupted part way writing its case line, and the write note of a suite on a production
target; a failure of the tool inside a step; the replay paths for a refused claim id and an
authorization number in several groups; the split of TLS failures into reached and not reached; a secret sent back as a JSON number; a step
done inside the tool on the live profile marked as answered by nobody (`RunRecordTest`); the refusal of
text left in the code box (`CaseCodesFrontendTest` runs the deciding function through Node, skipped
where no `node` is on the path; how the page finds the box was seen in a browser only); the drawing of
the presences on the page (`CompletenessScreenTest` reads the script's text, NEW-6); the style gate's planted violations (`HouseStyleTest`). Not checked at all: whether a chain's
DTR step can produce each fault outcome a suite file may expect of it.

**What the audits and checks did not examine**, as each reports it.
- *V1:* the plain `./gradlew` without `--offline` and the machine's memory cap; `run.cmd` and
  `gradlew.bat`; `run.sh` building the jar itself; a run with no network at all; the tabs beyond the
  first screen; the guardrail with a production environment; the ledger on disk line by line.
- *V5:* no clean build and no running application; the screens judged from source; 50 of 4,769 comment
  blocks; the pull request description.
- *V6:* `DECISIONS.md` in full (it read the operator table and D-208 to D-228); the finding tables of
  `PROGRESS.md` row by row; the journey in a browser; History, chains, suites, the Config panels and the
  colour key on screen; V7's blockers and the walkthrough's majors (not reproduced); replay, the export,
  notes on History, suites against production and the ledger's fault states.
- *V7:* a chain driven by hand on the screens; the live profile beyond one CRD run; a ledger that cannot
  record; PAS inquiry, DTR conditions and CRD suggestions for absent, null and empty.
- *The walkthrough:* the chain box and the History chains view; DTR and PAS on live; the adaptive
  questionnaire, PAS inquiry, subscriptions and notifications on screen; timeouts on DTR and PAS;
  production and the guardrail; layout below 1366 wide; any mock member ID other than the fixtures, so
  whether an ID that is not in the fixtures is found on mock remains open.
- *The member data check:* DTR on live end to end; PAS inquiry and subscription read, update and delete
  on live; DTR and PAS in a browser (the own-body finding was confirmed on CRD and read from the shared
  page code); Windows; the full suite on the unchanged tree.
- *The guardrail check:* IPv6 over the wire; hosts other than loopback; explicit default ports; a real
  front server; a replay or a chain against production; the full suite.
- *The fixture check:* the DTR loop with a required question left empty; `crd-04` chosen by hand in a
  browser; a step-level `expect` mismatch; damaged lookup fixtures; History, suite and chain screens;
  other window sizes; the full suite.
- *What the second member data audit did not examine.* DTR on live (its stand-in had no DTR
  endpoints, so the package, the next question and the response were not run there); PAS inquiry and
  subscription read, update and delete on live (judged by reading the code); a raw send on live and
  the Config tab's "send as written" path; a log file appender, heap or thread dumps and swap; file
  modes and the temporary folder on Windows. It did not read the `postman` package,
  `ConfigStoreGateway` or the CRD and PAS request builders line by line, only the changes since
  `7dade28` in the files that touch member data.
- *What the second guardrail audit did not examine.* A replay that reaches the gateway against
  production (its replays were refused earlier for want of a recorded member); a chain whose PAS step
  runs on production (checked in the code only); a real nginx, Tomcat or HAPI server in front of a
  service; the race between preparing and sending, by a timed run (read in the code only); a
  production address taken from an operating system variable; proxy settings and redirects (the
  client is built never to follow a redirect, read in the code; neither was run); the PAS form's own
  send button (writes from the page were sent with a fetch from the tab, and the CRD, DTR and History
  tabs were not driven in a browser); the findings F2, F6 to F9 and F11 to F15 of the first
  guardrail fix's verification, not repeated; the whole test suite under each of its mutations (only
  the packages concerned were run); Windows.
- *What the second fixture audit did not examine.* The rest of the DTR loop (the branch change of
  `dtr-02`, the HTTP 500 on step 2 of `dtr-14`, the two questionnaires of `dtr-13`, the prepopulated
  answers of `dtr-03`); each scenario file's `basis`, its claim about which guide text it follows,
  not checked against the guides; the History, chain, suite and notification screens and the DTR loop
  on screen (it looked at the CRD tab for 14 scenarios, 5 DTR and 7 PAS, at one size); VF-01 and VF-02
  of the first fixture verification, not run again; lookup fixtures loaded from a damaged directory of
  the tester's own (a test of two files claiming one identifier exists since D-204).

### What the audits found and what was done

State is one of: fixed (verified means a second agent attacked the fix and it held, or what it found
was then fixed), fixed and not yet verified by another agent, fixed in another form, partly fixed,
not fixed, refuted, decided otherwise, left as a known limit. "Fixed by a closing task" means one of
the four closing tasks fixed it (D-253 to D-256) and nobody but its author has
checked that fix before the last check examined it (D-260; its verdict is in the first table).
"Open" means it is listed above as not done.

#### The last check, on `98effa6` (five parts) and `428eff5` (the guardrail), and the last fixes

Each part was done by an agent that wrote neither the tool nor the fix (D-260). "After the check" is
its verdict; "What became of it" is the state at `902e0d9`. **"Last fix" means one of D-262 to D-265:
fixed and checked by its author only, and no check follows** (D-268). "Recorded" means the integrator
decided to record the line, not to fix it (D-261). "Open" means nothing was done after the check. The
tables further down give the state at `428eff5`; where this table differs, this table holds.

| Part | Finding | Grade | After the check | What became of it | Entry |
| --- | --- | --- | --- | --- | --- |
| Completeness | F1 `pa-needed` null or empty said "not returned" | blocker | closed | | D-236 |
| Completeness | F9 an issue's expression null drawn "empty" | blocker | partly closed (details) | Details by NEW-4, last fix | D-263 |
| Completeness | F2 card source and lists | major | closed | | D-236 |
| Completeness | F4 PAS periods | major | closed | | D-236 |
| Completeness | F5 list presences | major | partly closed (notification lists) | By NEW-10, last fix; a subscription's headers still have no presence (open) | D-263, D-257 |
| Completeness | F6 a resource of another kind as no determination | major | partly closed (OperationOutcome at 200) | By NEW-3, last fix; the outcome label still speaks of a body that could not be read (open) | D-263, D-266 |
| Completeness | F10 a notification over 131072 bytes | major | closed | | D-239 |
| Completeness | F13 a repeated key | major | partly closed (`cards`, notifications) | By NEW-2 and NEW-10, last fix | D-263 |
| Completeness | F3 a sub-extension with no value | minor | closed | Its title is the same for null and empty (open) | D-257 |
| Completeness | F7 a sentence twice, mock fault heading | minor | closed apart from a known mock heading | Played next steps still not worded as played (open) | D-257, D-266 |
| Completeness | F8 uninterpreted fields in History, suites, chains | minor | open (decided, no task) | Recorded | D-251, D-263 |
| Completeness | F11 History reads null, empty, absent alike | minor | open: History said "(not a code, 6 characters)" | By NEW-1, last fix | D-263 |
| Completeness | F12 "information" broke mid-word | minor | closed | | |
| Completeness | F14 the mock rewrote a repeated-key body | minor | partly closed (PAS) | PAS by NEW-5, last fix; subscription mock and sample notification still rewrite (open) | D-263, D-266 |
| Completeness | NEW-1 History says a null code is a six-character value | blocker | new | Last fix | D-263 |
| Completeness | NEW-2 a repeated key with something unread beneath gets no finding | major | new | Last fix | D-263 |
| Completeness | NEW-3 an OperationOutcome at 200 passes as no determination | major | new | Last fix; shortened headline not seen in a browser | D-263 |
| Completeness | NEW-4 an issue's details null or empty drawn "not returned" | major | new | Last fix | D-263 |
| Completeness | NEW-6 the page's presences held only by tests that read the script | major | new | Recorded, a limit | D-263 |
| Completeness | NEW-5 the PAS mock rewrites a repeated-key body | minor | new | Last fix, PAS only | D-263 |
| Completeness | NEW-7 the next step of a wrong-kind answer says check the environment | minor | new | Last fix | D-263 |
| Completeness | NEW-8 "options come from a value set" with none named | minor | new | Last fix, page only, no build test | D-263 |
| Completeness | NEW-9 a non-JSON notification over the cut | minor | new | Last fix; its test not run on the old code | D-263 |
| Completeness | NEW-10 a notification's lists "none returned", no repeated-key finding | minor | new | Last fix | D-263 |
| Fixtures | 1 N-RAW-NPI | major | closed | | D-255 |
| Fixtures | 2 N-OLD-SCENARIO-DIR | major | closed | | D-255 |
| Fixtures | 3 N-LISTNOTES-UNTESTED | major | closed | | D-255 |
| Fixtures | 4 N-CRD04-CHOSEN | minor | closed | | D-255 |
| Fixtures | 5 N-CRDLIST-UNCHECKED | minor | closed | | D-255 |
| Fixtures | 6 F-SCEN-LABELS | minor | closed | | D-255 |
| Fixtures | 7 N-STALE-JAVADOC, N-NPI-DOCS | minor | closed | | D-255 |
| Fixtures | N-CHOSEN-BODY-NOTE | minor | new | Open (no fix given) | D-260 |
| Fixtures | N-ANSWERING-NOTE | minor | new | Open | D-260 |
| Fixtures | N-DESC-OTHER-SENTINEL | minor | new | Open | D-260 |
| Fixtures | N-INQUIRE-LIST-HIDES-MAPPING | minor | new | Open | D-260 |
| Ledger | Line 1 N1, own body checked against nothing | major | partly closed (placeholders) | By NF2, last fix | D-262 |
| Ledger | Line 2 N3, more spellings of the date of birth | major | partly closed | By NF5, last fix; a date in words not looked for | D-262 |
| Ledger | Line 3 N2 and N4, supplied ids as tokens | major | partly closed (id holding the member ID) | By NF1, last fix | D-262 |
| Ledger | Line 4 N6, short names | minor | closed | | D-253 |
| Ledger | Line 5 N5, surname parts, city with postal code | minor | closed | | D-253 |
| Ledger | Line 6 N7, long codes | minor | closed | Its static hook replaced by NF3, last fix | D-262 |
| Ledger | Line 7 N8, results repeat a refused expectation | minor | partly closed | By NF4, last fix; malformed and member data together still repeated (open) | D-262 |
| Ledger | Line 8 N-DELETED-SUB | minor | closed | | D-253 |
| Ledger | Line 9 the run id refusal of a sample notification | house style | closed | | D-253 |
| Ledger | NF1 a supplied id holding the member ID not a token | major | new | Last fix, seen on the jar by its author | D-262 |
| Ledger | NF2 a body naming the member through a placeholder | major | new | Last fix, CRD only on the jar | D-262 |
| Ledger | NF3 a listed long code read as "not a code" once the list changes | major | new | Last fix | D-262 |
| Ledger | NF4 results repeat and quote a refused expectation | minor | new | Last fix, in part | D-262 |
| Ledger | NF5 three more date of birth spellings | minor | new | Last fix | D-262 |
| Ledger | NF6 64-hex and UUID-shaped supplied ids kept in clear | minor | new | Recorded, a limit | D-262 |
| Ledger | NF7 the token sentence says "the service gave" | minor | new | Last fix, no test | D-262 |
| Walkthrough | F-NODET-NEXT | major | partly closed (PAS) | PAS and subscriptions by N-PAS-NODECISION, last fix; CRD other outcomes, DTR, raw send open | D-264, D-266 |
| Walkthrough | F-LOCALSOLID | major | closed | | D-240 |
| Walkthrough | F-DUPCODE | major | partly closed (locks out subscriptions) | By N-SUB-LOCKOUT, last fix; CRD discovery still refused (open) | D-264, D-266 |
| Walkthrough | F-01, F-CHAIN-WORDING, F-REPLAY-SCENARIO, F-0MS, F-MRI, F-FILTER-LABELS | minor | closed | | D-237, D-240, D-242, D-243 |
| Walkthrough | F-TRACE-PAS-DTR | minor | closed in the form decided | | D-243, D-251 |
| Walkthrough | V5-SCREEN-ONE-ACTION-NAMES, V5-SCREEN-PLAIN-WORDS | minor | closed | | D-243, D-256 |
| Walkthrough | V5-SCREEN-ERRORS-NO-ACTION | minor | partly closed (replay of an unknown id) | Recorded as N-REPLAY-NOTFOUND (open) | D-264 |
| Walkthrough | N-PAS-NODECISION | major | new | Last fix on PAS and subscriptions; History shows `REVIEW`'s label (open) | D-264, D-266 |
| Walkthrough | N-SUB-LOCKOUT | major | new | Last fix | D-264 |
| Walkthrough | N-DUPCODE-UNTESTED | major | new | Last fix; the test is skipped where no `node` is on the path | D-264 |
| Walkthrough | N-NOTIF-SERVICE | major | new | Last fix; by hand on mock reads as a scenario (open) | D-264, D-266 |
| Walkthrough | N-CHAIN-TOTAL | minor | new | Last fix | D-264 |
| Walkthrough | N-REPLAY-UNTESTED, N-REPLAY-NOTFOUND, N-TRACE-TWO | minor | new | Recorded (open) | D-264 |
| Guardrail | N1 a production address the client cannot read | major | partly closed (`application.yml`, OS variable, same name at another host) | By G2-1 and G2-2, last fix | D-265 |
| Guardrail | N2, N4, N9, F-ADDR-MISQUOTE, F-BUILD-WRITES-TREE | major, minors | closed | | D-254 |
| Guardrail | N3 escapes above 0x7F | minor | partly closed (raw character) | By G2-4, last fix | D-265 |
| Guardrail | N5 the same-server sentence | minor | partly closed (open port) | By G2-5, last fix, not run on the jar | D-265 |
| Guardrail | F-MOCK-500-HEADING | major | partly closed (DTR malformed) | Heading by G2-3, last fix; the next steps of played faults still not worded as played (open) | D-265, D-266 |
| Guardrail | G2-1 a production address from `application.yml` or the OS never remembered | major | new | Last fix, proved on the jar by its author | D-265 |
| Guardrail | G2-2 a whole host forgotten on an upload at another host | major | new | Last fix, proved on the jar by its author | D-265 |
| Guardrail | G2-3 DTR malformed package headed as a fault in the environment | major | new | Last fix | D-265 |
| Guardrail | G2-4 raw character above 0x7F | minor | new | Last fix | D-265 |
| Guardrail | G2-5 whole-host warning for a kept path | minor | new | Last fix | D-265 |
| Guardrail | G2-8 `http:///` and `http:/` production addresses | minor | new | Last fix | D-265 |
| Guardrail | G2-6 an unlock offered that is always refused | minor | new | Recorded (open) | D-265 |
| Guardrail | G2-7 a mock lookup timeout headed as a fault | minor | new | Recorded (open) | D-265 |
| Guardrail | G2-9 member data names missed and caught wrongly | minor | new | Recorded (open) | D-265 |
| Style gate | V5-GATE-NOTEST, V5-INVISIBLE-CHARS | major, minor | closed | | D-244 |
| Style gate | V5-GATE-SCOPE | minor | closed in the form decided | | D-244 |
| Style gate | V5-GATE-PATTERNS, V5-FIXTURE-NAMES, V5-COMMENTS-WHAT | major, minors | partly closed | Open (no fix given) | D-258 |
| Style gate | STYLE2-NONASCII-NAME, -UNICODE-GAPS, -BINARY-SKIP, -SECOND-COPY, -FIXTURE-RESIDUE, -BANNERS-REMAIN | minor | new | Open (no fix given) | D-258 |

#### The last run: V1, V5, V7 and the walkthrough on `9e285a1`, V6 on `9e5306d`

Every fix in these tables was made by one agent and **has been checked by nobody else**. The test
named is the one its author reports failing with the fix taken out; "none" means no such run was
reported.

**Build audit (V1)**, on `9e285a1`. Answer: yes; a fresh clone built green twice (2714 tests), started,
and answered every endpoint of section 18 as the specification and the README say. 2 minors.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F-01 | minor | A request body that repeats a key is accepted and the last value wins | Every JSON request body refuses a repeated key with a 422 naming its place; a body of the tester's own is passed on as written | `ApiExceptionHandlerTest`, `CrdControllerTest`, `PaveApplicationTest` | Fixed, not yet verified by another agent | D-237 |
| F-02 | minor | The README's list of statuses leaves out 400, 405, 406, 413 and 415 | The list names them | | Fixed by the last README pass | D-250 |

**House style audit (V5)**, on `9e285a1`, the style task only. Answer: no file and no commit message
breaks the character and word rules; the gate that enforces them could be removed without a red
build, and its patterns let several forms through. 2 majors, 7 minors.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| V5-GATE-NOTEST | major | No test checks that the gate exists or fails | `HouseStyleTest` plants every rule, checks the wiring of `check`, and scans the tree | `HouseStyleTest` (with the gate taken out of `check`) | Fixed, not yet verified by another agent; the task's failure path seen by hand only | D-244 |
| V5-GATE-PATTERNS | major | Compounds, separators, the short name without its H inside an identifier, tracking set from a script and several characters passed | Every pattern tightened; one class holds the rules | `HouseStyleTest` | Fixed, not yet verified by another agent | D-244 |
| V5-GATE-SCOPE | minor | Dot files, file names and commit messages not read | Files from git, names checked; commit messages said not to be read | `HouseStyleTest` | Fixed for files and names; commit messages, the draft, names of people and restating comments are not checked by any gate | D-244 |
| V5-INVISIBLE-CHARS | minor | Raw invisible characters in test strings | Written as escapes; the gate refuses them raw | The tightened gate listed exactly the eight | Fixed, not yet verified by another agent | D-244 |
| V5-SCREEN-ONE-ACTION-NAMES | minor | The PAS submission, the inquiry, the raw send and the uploads had several names | One name each, the button's, in labels and sentences; the PAS tab's leftover sentences renamed by a closing task | `NextActionVocabularyTest`, `ButtonNamesAndMarksTest` | Fixed, not yet verified by another agent; the renamed Config buttons seen in a browser at 1366 by the screens task | D-243, D-256 |
| V5-SCREEN-ERRORS-NO-ACTION | minor | Errors with no action, an API parameter's name, one check worded two ways, "What went wrong" over a refusal | Server sentences given actions and one wording; the panel headed by the server's heading; the receiver's run id refusal says where to copy one | `RunControllerTest`, `ChainServiceTest`, `ApiExceptionHandlerTest`, `NotificationReceiverTest` | Partly fixed by the closing tasks: the server's heading for a refused form still says nothing of what to do (open) | D-243, D-253, D-256, D-257 |
| V5-SCREEN-PLAIN-WORDS | minor | "mints", "Set this aside", the legend's frame line, bare "Close" and "Dismiss" | Reworded; every button names what it does | `ButtonNamesAndMarksTest` (reads the source) | Fixed by a closing task, checked by nobody but its author | D-243, D-256 |
| V5-COMMENTS-WHAT | minor | 5 of 50 sampled comments restate the code; 3 empty banners | The five rewritten to say why; eight banners removed | None | Fixed for the sample only | D-244 |
| V5-FIXTURE-NAMES | minor | Three invented surnames break the `Placeholder` convention | Renamed, with three more in the same files | None (test data) | Fixed; no test checks the convention | D-244 |

**Completeness audit on screen (V7)**, on `9e285a1`. Answer: no, narrowly; every one of some sixty
fields added to answers appeared outside the wire view, but some fields reached no screen and some
words collapsed absent, null and empty. 2 blockers, 6 majors, 6 minors.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F1 | blocker | `pa-needed` returned null or empty said to be "not returned" | Worded from its presence | `CrdConformanceCheckerTest` | Fixed, not yet verified by another agent | D-236 |
| F9 | blocker | An issue's expression returned null drawn as "empty" | `IssueView` gains presences; the table draws them | `OperationOutcomesTest`, `CompletenessScreenTest` | Fixed, not yet verified by another agent | D-236 |
| F13 | major | A repeated key: the earlier value on no screen but the wire | Every value listed everywhere, the raw send, the lookups and CRD discovery included; a finding on CRD, DTR, PAS and subscriptions | `CrdRepeatedKeyTest`, the three services' and the mappers' tests | Fixed by a closing task, seen on live against a stand-in by its author; the finding's detail says "the value shown above" for a key not drawn (open) | D-237, D-256, D-257 |
| F6 | major | A Patient sent to a CRD hook passed as "no determination" | Outcome `MALFORMED_RESPONSE`; the headline says what came | `CrdOtherResourceTest` | Fixed in another form (existing outcome) and by a closing task; the outcome's label still speaks of a body that could not be read (open) | D-238, D-256, D-257 |
| F2 | major | A card's source and lists absent, null and empty read alike | `CardView.source` and `lists` | `CrdCardPresenceTest` | Fixed, not yet verified by another agent | D-236 |
| F4 | major | PAS periods collapsed | `preAuthPeriod`, `authorizedPeriod` presences | `PasPresenceTest` | Fixed, not yet verified by another agent | D-236 |
| F5 | major | Lists headed "None returned" whatever their presence | `lists` presences on PAS, DTR and CRD views | `PasPresenceTest`, `DtrItemPresenceTest` | Fixed, not yet verified; overturns part of D-058; the inquiry, subscription and notification presences seen in a browser at 1366 by the screens task; a subscription's headers have no presence (open) | D-236, D-256, D-257 |
| F10 | major | A notification over 131072 bytes read from its start only | The whole body read, mapped and matched; only the held text cut | `NotificationReceiverTest` | Fixed, not yet verified by another agent | D-239 |
| F3 | minor | A sub-extension with no value read as "empty" | "sent with no value" in the table, the finding and the grid | `CrdNoValueTest` | Fixed by a closing task; the finding's title is the same for null and empty (open) | D-236, D-256, D-257 |
| F11 | minor | History reads absent, null and empty `pa-needed` alike | `name=(null)`, `(empty)`, `(no value)` on the line | `CrdNoValueTest` | The last check found History undid it; fixed again by the last fix (NEW-1), by its author only | D-236, D-263 |
| F8 | minor | History, suite rows and chains say nothing of uninterpreted fields | Nothing: it needs a component of the ledger record | | Not fixed; given to no task (open) | D-251 |
| F7 | minor | A sentence said twice; "exactly as received" beside a mask; mock fault headed as the environment's | Said once on CRD and PAS; "exactly as received" only when nothing was masked; a played fault headed as played | `CrdResponseMapperTest`, `PasResponseMapperTest`, `PlayedFaultHeadingTest` | Partly fixed: the heading by a closing task; a played 500's next step, and the DTR and subscription notes, not reworded (open) | D-254, D-257 |
| F14 | minor | The CRD mock rewrote a scenario body, losing a repeated key | Played exactly as written when a key repeats | `CrdRepeatedKeyTest` | Fixed, not yet verified by another agent | D-237 |
| F12 | minor | "information" broke mid-word at laptop width | Wider severity column, no wrap | None (layout) | Fixed, seen at 1366 by 768 | |

**The walkthrough by a business tester**, on `9e285a1`, automated and on mock, not a sign-off. Answer:
yes, with three gaps. 3 majors, 6 minors.

| Finding | Grade | What it found | What was done | Fails without the fix | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F-NODET-NEXT | major | "No determination" left a business tester nothing to do | `REPORT_NO_DETERMINATION`: record the run by its id and report it | `CrdServiceTest`, `CrdVerdictsTest`, `ChainServiceTest` | Fixed, not yet verified by another agent | D-241 |
| F-LOCALSOLID | major | A step done inside the tool marked as answered by a scenario or the payer | `AnsweredBy` on the result, the ledger and the chain; a dotted mark and a key row, the History chains list included | `DtrServiceTest`, `RunRecordTest`, `ButtonNamesAndMarksTest` | Fixed, not yet verified; the chains list fixed and the live profile looked at by the screens task | D-240, D-256 |
| F-DUPCODE | major | Text left in the code box sent without being shown, repeating a code | The send is refused and the box marked; one duplicate check | `CaseCodesFrontendTest` (reads the source) | Fixed, not yet verified; behaviour seen in a browser only | D-242 |
| F-TRACE-PAS-DTR | minor | The PAS line does not name the DTR response run | D-181 kept; History lists "Followed by" | `RunControllerTest` | Decided otherwise, with a way between the two | D-243, D-251 |
| F-CHAIN-WORDING | minor | A replay refusal said "one step of a chain" of a single run | Says the run followed run X | `RunReplayTest` | Fixed, not yet verified by another agent | D-243 |
| F-REPLAY-SCENARIO | minor | A mock replay said its scenario was "chosen in the scenario selector" | Says the original's scenario was sent again by its id, same by construction; the replay panel now shows it | `RunReplayTest` | Fixed, not yet verified; seen on screen by the screens task | D-243, D-256 |
| F-0MS | minor | A time not measured written as 0 | Null in the ledger, empty in the export, "not measured" on screen | `RunRecordTest`, `RunCsvTest` | Fixed, not yet verified; the chain view seen and its column widened by the screens task | D-240, D-256 |
| F-MRI | minor | A word typed in the code box taken as a code | The server's `shapeNote` under "not on the list"; still sent as chosen | `ProcedureCodeControllerTest` | Fixed, not yet verified by another agent | D-242 |
| F-FILTER-LABELS | minor | Two outcome filter options were bare codes | Each protocol's wording listed | None | Fixed; the grouped version not looked at again | D-243 |

**Honesty audit (V6)**, on `9e5306d`. Answer: yes, the documents claimed more than the code bears out,
mostly by leaving limits out; every test count, the README's 70 examples, the build and the fresh
clone held. 1 blocker, 5 majors, 10 minors.

| Finding | Grade | What it found | What was done | State | Entry |
| --- | --- | --- | --- | --- | --- |
| F-PR-LIMITS | blocker | The draft said "nothing stubbed" and left out limits the README states | Level 1 described with its limits | Fixed by the last README and draft pass | D-235, D-250 |
| F-TOKEN-BASIC | major | The token request uses HTTP Basic and no document says so | Recorded as a decision; the README's open items say so | Fixed by the last README pass | D-233, D-250 |
| F-MOCK-500-HEADING | major | On mock a played 500 is headed and actioned as a fault in the environment | Every played fault headed as played, its action says nothing left the machine | Partly fixed by a closing task, seen on the jar by its author; the next step still says to check the environment (open) | D-254, D-257 |
| F-SALT | major | The salt reverses member tokens and is not called a secret | Recorded as a decision; the README says so | Fixed by the last README pass | D-234, D-250 |
| F-PR-ASSUMPTIONS | major | The draft's assumptions stop at D-201 and give titles only | One line per entry | Fixed by the last draft pass; carried to the last entry at each pass since | D-250, D-259 |
| F-BEHIND | major | The README, `PROGRESS.md`, D-212, D-226 and the draft lag the merged work | All four brought up to `e1d37dc`, then to `428eff5`, then to `902e0d9` | Fixed by these records and the last README pass | D-229 to D-259 |
| F-PROG-V5 | minor | `PROGRESS.md` said V5 found nothing, leaving out its seven minors | Every V5 finding is in the table above | Fixed by this pass | |
| F-PR-MUT-SCOPE | minor | The draft overstates how the V2 fix's tests were proven | Reworded | Fixed by the last draft pass | D-250 |
| F-PR-M3 | minor | The draft's list of fixes never run against the old code leaves out m3 | Added | Fixed by the last draft pass | D-250 |
| F-PROG-RAWNOTE | minor | `PROGRESS.md` gave no limits of the raw send's note check | The limits are above and in D-231 | Fixed by this pass | D-231 |
| F-README-SENT | minor | "was sent" of a submit checked on mock only | Reworded | Fixed by the last README pass | D-250 |
| F-BUILD-WRITES-TREE | minor | Tests write `config-store/` and `run-history/` into the checkout | Every test JVM gets temporary folders of its own (`TestFolders`) | Fixed by a closing task; its author saw nothing new but `build/` after `clean build` | D-254 |
| F-JAVAOPTS | minor | Every JVM here disables two base64 intrinsics and no document says so | Said above under the gates and in the draft | Fixed | D-250 |
| F-CACHE-FRESH | minor | A fresh clone here compiles nothing, taking classes from the shared cache | Said above under the gates | Recorded; the integrator's gate is to say whether the cache was on | D-250 |
| F-SCEN-LABELS | minor | Three scenario labels read as observations of the vendor | The labels name what the file plays; a test refuses one that names Onyx | Fixed by a closing task | D-255 |
| F-ADDR-MISQUOTE | minor | A refusal at upload quotes an address the file does not hold | An unreadable address quoted as the file writes it | Fixed by a closing task, seen on the jar by its author | D-254 |

#### The second run, on revision `0658a7c`, and the check of each fix on `9e5306d`

The fixes were made by one agent per audit and merged at `5763c41`. **Each was then checked**, on
`9e5306d`, by an agent that wrote neither the tool nor that fix (D-245): it repeated the finding on the
built jar, on live against stand-ins of its own, attacked its neighbours, and took the fix out again to
see its tests fail. "Seen failing" in the check column means that run was made; F-SCREEN and m2 have
no test to undo and were seen in a browser with two contexts. For the member data findings F1, F2 and
F3 the fix's author had run its tests in one run that took all eight changes out at once; the check
took them out one by one.

**Member data audit (V2), second run.** 1 blocker, 3 majors, 3 minors. Its answer: yes, on the live
profile with default tracking the ledger and the export could hold a member ID, a name and a date of
birth, three ways. The check's answer: F0, F3, F5, F6 and F7 closed; F1 and F2 closed for the audit's
own requests and open one step away.

| Finding | Grade | What it found | What was done | The check (`9e5306d`) | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F0 | blocker | A secret a service repeats in a notification was shown in clear | The notification is masked with every secret the tool could have sent | Closed; seen failing (`NotificationReceiverTest`, `SecretsSentBackOverTheServerTest`) | Fixed and verified | D-215 |
| F1 | major | Note, tester reference and expectation were written as typed, member data included | Refused at the way in, checked on a note added later, and not written by the record | Partly closed; seen failing (`LedgerPhiTest`, `SuiteCasesTest`, `LedgerMemberTextTest`); open: N1, N3 | Fixed for the audit's requests and verified; the neighbours N1, N3, N5 and N6 fixed by a closing task | D-217, D-253 |
| F2 | major | Ids a service supplies kept by shape alone | Kept only when they read like neither an identifier nor a date; then, by a closing task, a token unless a UUID, an OID or minted | Partly closed; seen failing (`NotificationIdsNotKeptTest`); open: N2, and N4 broken by the fix | Fixed in another form by a closing task; a ClaimResponse id holding the member ID with separators is still not matched (open) | D-218, D-253 |
| F3 | major | The member ID typed as a procedure code was written in clear | Written as `(not a code, N characters)` | Closed; seen failing (`LedgerMemberTextTest`, `LedgerPhiTest`); N7 found | Fixed and verified | D-218 |
| F5 | minor | The configuration store keeps member data an upload holds | The upload warns by names and saved examples; the store keeps uploads whole | Closed; seen failing (`MemberDataUploadTest`); N9 found | Fixed in another form (warned, not stripped) and verified; shown on screen since D-230 | D-213, D-230 |
| F6 | minor | The log line of an unnamed failure fired for lookups | Not written for a lookup | Closed; seen failing (`FailedCallLogTest`) | Fixed and verified | D-216 |
| F7 | minor | Every start left an empty server folder | The server's folder is removed at stop | Closed; seen failing (`TomcatBaseDirectoryTest`) | Fixed and verified; a hard kill leaves it | D-216 |
| N1 | major (check) | A request of the tester's own from a protocol tab is checked against nothing | Checked against the identifiers its body carries | New | Fixed by a closing task; names and dates in the body not read (open) | D-253 |
| N2 | major (check) | A member ID split by separators, supplied as an id, is kept in clear | Kept as a token | New | Fixed by a closing task, seen on the jar by its author | D-253 |
| N3 | major (check) | Month-name dates, fullwidth digits and the ID without its letter not recognised | Normalised, month names parsed, the digit run looked for | New | Fixed by a closing task, seen on the jar by its author | D-253 |
| N4 | major (check) | Numeric ids a server mints no longer kept, so notifications no longer match | Kept as a token and matched by token | New, broken by the F2 fix | Fixed by a closing task, seen on the jar by its author | D-253 |
| N5 | minor (check) | City with postal code, and one part of a surname, accepted | Both looked for | New | Fixed by a closing task, seen on the jar by its author | D-253 |
| N6 | minor (check) | A name that is a common word refuses honest notes | Under four letters only beside the other name | New | Fixed by a closing task, seen on the jar by its author | D-253 |
| N7 | minor (check) | Codes of seven digits or more no longer kept | A code the list holds is kept | New | Fixed by a closing task; not checked on the jar | D-253 |
| N8 | minor (check) | The suite results file repeats a refused expectation | The refusal written in its place | New | Fixed by a closing task, seen on the jar by its author | D-253 |
| N9 | minor (check) | The upload warning misses common member data names | The names added | New | Fixed by a closing task; not checked on the jar | D-254 |

**Guardrail audit (V3), second run.** 1 blocker, 4 majors, 4 minors. Its answer: yes, a port written
with leading zeros took a write to production with no unlock, and an encoded `../` got a write past a
locked production as a read. The check's answer: eight closed, B1 partly.

| Finding | Grade | What it found | What was done | The check (`9e5306d`) | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| B1 | blocker | A port with leading zeros was not read as a production address | The address is read as the HTTP client reads it; an unreadable destination refuses a write; an unreadable production address refused at upload and remembered as its whole host | Partly closed; seen failing (`AddressSpellingGuardrailTest`, `DestinationOnTheWireTest`); open in the second direction (N1) | First direction fixed and verified; second fixed by a closing task, seen on the jar by its author | D-210, D-254 |
| F-SCREEN | major | After another browser's Lock, the Config tab still said writes were unlocked | Every place redrawn and the tester told once (the screens pass) | Closed, seen with two browser contexts | Fixed and verified | D-230 |
| M-ENC | major | An encoded `../` in a run value turned a read into a write | No request leaves with an encoded separator in its path | Closed; seen failing (`EncodedSeparatorGuardrailTest`); N3 found | Fixed and verified | D-211 |
| T1 | major | No test failed when Lock stopped locking every session | A test over the running server; the 409 at the send now carries its own sentence | Closed; seen failing (`SecondGuardrailAuditOverTheServerTest`); the new 409 seen on the jar | Fixed and verified | D-214, D-229 |
| GAP1 | major | The gap of F0 | As F0 | Closed; seen failing; N4 found | Fixed and verified | D-215 |
| m1 | minor | A raw send of a mapped request refused with an untrue reason | The reason names the mapping | Closed; seen failing (`ProductionGuardrailTest`) | Fixed and verified | D-216 |
| m2 | minor | The unlock panel does not say what Lock does | The server's reason says it and the panel shows it | Closed, seen in a browser | Fixed and verified | D-231 |
| m3 | minor | An environment at `/pa%2Ffhir` took writes on a production server | Nothing is sent to it, and it is warned about | Closed (through M-ENC); its overlong neighbours are N3; its neighbours `%00`, `%20`, `%2e` and, the other way round, a production base path with `%2F` let writes through and are covered by no decision (open) | Fixed and verified for `%2F`, `%5C`, `%3B` and encoded dot segments | D-211 |
| m4 | minor | A non-production environment on a production server, with no word | Warned wherever it is listed, shown on screen since the screens pass | Closed; seen failing; N5 found | Fixed in another form (warned) and verified | D-212, D-230 |
| N1 | major (check) | A production address the client cannot read is not remembered; a sandbox at its plain spelling takes writes | Refused at upload and activation; remembered as its whole host | New; writes arrived at a stand-in | Fixed by a closing task; nothing arrived on the jar | D-254 |
| N2 | major (check) | An unreadable path refused with a false cause | "The url as filled is not a valid url", naming the variable | New; nothing is sent | Fixed by a closing task, seen on the jar by its author | D-254 |
| N3 | minor (check) | Overlong or look-alike encoded slashes not refused | Every escape above 0x7F in a path refused | New; server-dependent | Fixed by a closing task, seen on the jar by its author | D-254 |
| N4 | minor (check) | A short secret in a notification shown with no note | The notes say so | New | Fixed by a closing task; not checked on the jar | D-254 |
| N5 | minor (check) | A false "same server" warning for an unreadable production port | A sentence of its own | New | Fixed by a closing task, read through the API on the jar | D-254 |

**Fixture audit (V4), second run.** 1 blocker, 4 majors, 5 minors. Its answer: mostly yes; every
sentinel picks its scenario and all 14 CRD, 15 DTR and 21 PAS scenarios give the outcome their label
states; four gaps. The check's answer: nine closed or recorded, F-RAW partly.

| Finding | Grade | What it found | What was done | The check (`9e5306d`) | State | Entry |
| --- | --- | --- | --- | --- | --- | --- |
| F-CHAIN-DTR | blocker | A DTR sentinel taken from CRD into DTR ended as "package incomplete" | CRD names the questionnaires that DTR scenario's package holds | Closed; seen failing (`DtrSentinelJourneyTest`); six DTR sentinels driven on the real tabs into PAS; N-CRD04-CHOSEN found | Fixed and verified | D-222, D-248 |
| F-RAW | major | Cross-protocol sentinels skipped in a pasted body | One rule for a typed and a pasted identifier, the NPI included | Partly closed; seen failing; 135 pairs agree; open: N-RAW-NPI | Fixed for the member ID and verified; the NPI fixed by a closing task | D-220, D-255 |
| F-DUP-CRDSENT | major | Two DTR files could claim one CRD sentinel | The duplicate check covers `crdSentinelMemberIds` | Closed; seen failing; N-CRDLIST-UNCHECKED found | Fixed and verified | D-221 |
| F-PAS-SHADOW | major | The PAS mapping of CRD sentinels was a Java map | Scenario data in the PAS files | Closed; seen failing | Fixed and verified | D-221 |
| F-SAMPLE-LIST | major | The sample code list compared with the service as if real | A list that carries the marker is synthetic; sentences and label made true since | Closed on live; seen failing (`CodeListConformanceSeamTest`, `SampleCodeListTest`); N-STALE-JAVADOC found | Fixed and verified | D-223, D-229, D-230 |
| F-PAS-CRDMAP-HIDDEN | minor | The PAS list showed no CRD sentinels | Read from the files; shown on screen since | Closed; seen failing | Fixed and verified | D-221, D-230 |
| F-V4-02-REST | minor | An `expect` at odds with its file; a silent default | The default named; an `expect` status at odds noted | Recorded as the known limit | Partly fixed; the `expect` outcome is a known limit | D-224 |
| F-ORPHAN | minor | Files not read ignored without a word | Named in the notes and one warning; shown on screen since | Closed; seen failing; N-LISTNOTES-UNTESTED found | Fixed in another form (said) and verified | D-224, D-230 |
| F-UNMATCHED-LOOP | minor | The sample that matches nothing sent the tester round in a loop | The next step names a sample that would match | Closed; seen failing; N-DELETED-SUB found | Fixed and verified | D-224 |
| F-NPI-TEST | minor | A test NPI could be a real provider's | Replaced; a scan fails on any NPI outside the placeholder set | Closed; seen failing; N-NPI-DOCS found | Fixed and verified | D-224 |
| N-RAW-NPI | major (check) | A sentinel NPI in a pasted body ignored; the README says the opposite | Read after a member ID, as on the form | New | Fixed by a closing task, seen on the jar by its author | D-255 |
| N-OLD-SCENARIO-DIR | major (check) | A directory from before the mappings became data loses them silently | Each such file named on the lists and at start; nothing guessed back | New | Fixed as decided by a closing task | D-255 |
| N-LISTNOTES-UNTESTED | major (check) | No test fails when the list notes are dropped | Tests in the three controllers and over the running application | New | Fixed by a closing task | D-255 |
| N-CRD04-CHOSEN | minor (check) | `crd-04` chosen by hand with a DTR sentinel names the wrong questionnaire | The rule holds however `crd-04` was chosen | New | Fixed by a closing task; two sentinels driven into DTR in a browser | D-255 |
| N-CRDLIST-UNCHECKED | minor (check) | A mapping entry naming no CRD sentinel is not noticed | Noted at load | New | Fixed as decided; the run note still says such an ID answers for the same case (open) | D-255 |
| N-DELETED-SUB | minor (check) | A deleted subscription treated as live | Decided by ledger order; a notification on it says the tool deleted it | New | Fixed by a closing task; unit-tested only | D-253 |
| N-STALE-JAVADOC | minor (check) | Two comments say the flag means the bundled list | Corrected | New | Fixed by a closing task | D-255 |
| N-NPI-DOCS | minor (check) | The NPI scan does not read the Markdown files | It reads them | New | Fixed by a closing task | D-255 |

#### The first run, and the reviews

The rows of the reviews of the frame, the CRD tab, the DTR tab and the PAS tab that are closed by
D-202 to D-205 were closed by the two server tasks for the screens, which had no verifier of their
own. "Fixed" in those rows means fixed and not yet verified by another agent, unless the row says the
join or the second pass saw it on screen (D-206, D-207); for several of them (FR-17, CMP-12, CRD-3 and
the DTR review's R18 among them) no other agent is reported to have seen the fix hold.

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
| V4-08 minor | A sentinel in a pasted body matched only case-sensitively | Fixed, verified; a foreign sentinel in a pasted body was found by the second run (F-RAW) and fixed; a sentinel NPI in a pasted body selects since a closing task | D-153, D-220, D-255 |
| V4-09 minor | A sentinel's refusal named the scenario field | Fixed | D-154 |
| V4-10 minor | Scenario 8 described a request it did not check | Fixed by description | D-154 |
| V4-11 minor | The NPI check is Luhn only | Not changed: the operator was asked and does not know, so it stays as it is | D-153, D-227, D-269 |

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
chain (D-208), which now pins a blocked suite row of a protocol chain instead (D-229); VSP-3 (a garbled sentence) fixed and pinned;
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
| FR-16 minor | Mock timeout of 400 ms; "was sent without" on an unreachable run | Fixed: no Java class named, "built without"; on mock a played timeout is said once and headed as played | D-204, D-205, D-229, D-231 |
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
| CRD-2 minor | A mock timeout in 400 ms | Fixed: said once, as played, with what live would wait, and headed as played | D-204, D-229, D-231 |
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
| R16 minor | Lines that read badly; raw hand-over keys; dropped answers with no value | Fixed: carry labels and dropped values from the server; the dropped values seen on screen since | D-203, D-207, D-230 |
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
| R11 minor | An unmatched notification in the tone for unknown member data | Fixed: neutral tone; seen on screen since | D-203, D-230 |
| R12 minor | The browser words decision sources, attachments, reachability | Fixed: the server's words read | D-203, D-205, D-207 |
| R13 minor | Small layout faults | Fixed: determination cells break only at a joint | D-206 |
| R14 minor | A form offered although every send is refused | Fixed by the join: on live the form is held back | D-206 |
| R15 minor | Preview notes in the past tense | Fixed | D-203, D-207 |
| R16 minor | Tester reference and note not on the result | Fixed | D-197 |

### What exists at this revision

- **Protocols.** CRD: discovery, order-sign and order-select, a conformance checker that judges by CRD
  2.0.1, 14 mock scenarios. DTR: questionnaire package, the adaptive next-question loop, the response
  step, 15 mock scenarios. PAS: submit, inquire, subscriptions (create, read, list, update, delete),
  the notification receiver, 21 mock scenarios. A request sent from the collection as it is, with its
  saved body and values for the parts of its url, and one mock scenario. A request of the tester's own
  on every protocol. `GUIDE_RULE_BROKEN` in all three. Every finding carries its group. A secret a
  service sends back is masked in every answer, a received notification included; one test searches
  the whole answer of ten endpoints and of a delivered notification for it.
- **What an answer shows.** Not returned, null and empty said in words in every designed view, with
  presences the server sends for lists, periods, a card's source and an issue's expression and location
  (D-236). Every value of a repeated key listed among the returned fields everywhere, and a finding on
  CRD, DTR, PAS and subscriptions (D-237, D-256).
  A resource of another kind sent to a CRD hook is not "no determination", and its headline says what
  came (D-238, D-256). A notification read
  whole (D-239). Who answered each run (`answeredBy`: the service, a scenario, nobody, or not sent) on
  the result, the ledger line, the export and the chain, and "not measured" for a time nobody measured
  (D-240). Every error carries a heading from the server (D-231), which heads the error panel (D-256).
  On mock every played fault is headed as played (D-229, D-231, D-254); the next step of a played 500
  or unreachable service is not (D-257).
- **Configuration.** Postman environments and collections uploaded and stored as uploaded, the
  operation mapping (changed and removed from the Config tab), which request a run uses stated by the
  server, variable resolution with labels for each source, the production guardrail judged by
  destination and reading an address as the HTTP client does, a production address the client cannot
  read refused at upload and remembered as its whole host, no encoded separator (`%2F`, `%5C`, `%3B`,
  an encoded dot segment) and no escape above 0x7F in a path (`%00`, `%20` and `%2e` are open, above
  under the guardrail), a url that is not valid as filled refused naming the value's source, warnings
  at upload and wherever environments are listed about addresses, credentials and member data, Lock
  from any browser ending every session's unlock and worded so by the server. The token request uses
  HTTP Basic (D-233).
- **Request bodies.** Read strictly: an unknown property and a repeated key are each a 422 naming it
  (D-237). A body of the tester's own is passed on as written.
- **Lookups and code list.** Member and provider lookup with 51 synthetic member fixtures and provider
  fixtures, by path or by query, a field holding masked text marked and kept out of the case; the
  placeholder procedure code list, any list that says it is synthetic treated as the placeholder and
  described as that, and the picker, which refuses a code twice and says when a typed entry lacks the
  shape of a code.
- **The ledger.** JSON Lines, the tracking policy, free text refused when it holds this run's member
  data in its usual spellings (and, for any body of the tester's own, an identifier that body states),
  procedure codes held to the identifier-or-date rule unless the code list holds them, identifiers a
  service supplies kept as a token unless nobody's data and matched by token, notes checked against the
  member token, `answeredBy` on every line, health with the files it cannot read, the CSV export with filters,
  replay that returns what the new run found and says what it is, the runs that follow a run, case
  lines, an optional time zone (not offered on screen). The salt beside it is a secret (D-234).
- **Suites and the chain.** Suites from CSV or JSON with a truthful preview, labelled counts and
  comparison with an earlier run; the chain as a suite case and the chain view; manual chains linked;
  every DTR sentinel reaches its DTR scenario from CRD; a replay never joins a chain.
- **Mock scenarios.** One rule chooses a scenario for a typed or a pasted member ID or NPI; the sentinels
  of another protocol are data in the files; the scenario lists carry notes about the default, files not
  read, mapping entries that name no CRD sentinel and a folder from before the mappings, shown on screen;
  the labels name what a file plays.
- **The request comparison** of a pasted CRD request with the tool's own (`POST /api/crd/compare`),
  masking a pasted credential, with its caveat on every answer.
- **The HTTP edge**: bound to this machine, the request guard, no-store, the body limit, the server's
  working folder removed at stop.
- **The style gate**: one class that `houseStyleCheck` and `HouseStyleTest` both run, reading every file
  git lists and every file name (D-244).
- **README, `run.sh`, `run.cmd` and samples** (a Postman environment and collection, a code list, a
  suite). The README was checked on the running tool on `e1d37dc` (D-250), brought to `428eff5` from
  the source and the reports of the four closing tasks (D-259), and brought to `902e0d9` by a last pass
  written beside these records.
- **The screens**, joined into one tool by two passes and finished by three more, each pass by one agent
  who drove them in a real browser with scripts, mostly on mock; walked through once by an agent
  playing a business tester; never used by a person. "Seen" below means seen by a pass, an audit or a
  check in a browser.
  - *Every tab.* One readiness statement: on live with nothing configured each protocol tab holds its
    form back, says what is missing and links to Config, and the form comes back with no reload after
    an upload (seen at 1366 and 1920). One next-step row and one focus after a send. The case carried
    from tab to tab, and what a step handed on dropped when the member changes (seen). The masthead's
    ledger indicator in each state (seen at both sizes). The colour key drawn from the server's finding
    groups, with a row for a step nobody answered (seen). A lock from another browser redraws every
    place that states write access and tells the tester once (seen with two contexts by the pass and
    the check). The note and tester reference of every form carry the hint that the ledger keeps them
    as typed, and a refusal marks its field (seen at 1366 on CRD, DTR, PAS, the raw send, a suite and
    History). Text left in the code box refuses the send (seen at 1366 on all three tabs). The error
    panel is headed by the server's heading (seen at both sizes by the screens task). Buttons name what
    they do: "Dismiss this notice", "Close what the member lookup returned" (seen at 1366).
  - *CRD tab and the comparison.* Every scenario through the real form; the comparison with the caveat,
    the masked note and the server's source labels, a pasted token nowhere on the page but in the
    tester's own box (seen); fill without typing over a lookup, marked "from the pasted request" (seen);
    "no determination" with its next step for a business tester (seen at 1366); absent, null and empty
    in the card, coverage and issues views, and a repeated key with its finding on live (seen at 1366).
  - *DTR tab.* The package, the answers and the completed response, with the server's labels and the
    buttons named as the server's next steps (seen); the completed response drawn as done inside the
    tool, nobody answering (seen at 1366); the answers an adaptive questionnaire dropped (seen); a
    journey by keyboard alone (seen); six DTR sentinels from CRD through DTR into PAS (seen by the
    fixture check).
  - *PAS tab.* A submission carrying the answers completed in DTR, in the preview and in the wire view
    (seen at both sizes); pended then an inquiry; the decision source, the attachment sentence and the
    receiver's `bindNote` from the server (seen); the calls named "Send PAS request" and "Send inquiry"
    (seen at 1366); production locked, unlocked and a second context (seen at 1366); an unmatched
    notification and one naming an id the ledger does not keep (seen at 1366).
  - *History tab.* The two proofs as two rows; the journey as one chain; the suite with its count
    groups; a replay with its verdict; a refused PAS replay; the `testerRef` filter; "Load this case
    into the form"; a dotted mark and "not measured" for a step nobody answered and "Followed by" on a
    run view (seen at 1366).
  - *History tab, since the closing tasks.* The list of chains draws the dotted mark; the replay panel
    shows the new run's supporting lines; a value kept as a token says so (seen at 1366 by the screens
    task and at both sizes by the ledger task).
  - *Config tab.* The default rule, the source labels, a raw send with a field per url part and its 422
    at the field (seen); the address warnings under each environment and after a switch, and the upload
    warnings about member data (seen at 1366); the server's sentence on what Lock does (seen); the raw
    send's refused note (seen at 1366).
  - *Config tab, since the closing tasks.* The renamed buttons and the unlock button were seen at 1366 by
    the screens task, not at 1920. The guardrail task's new sentences were read through the API only.
- *Since the last fixes (D-262 to D-265, each fixed by its author only).* A body of the tester's own is
  read for identifiers with its placeholders filled; a supplied id holding the member ID is a token and
  its notification matched; codes are judged when a line is written; History keeps `(null)`, `(empty)`
  and `(no value)`; an OperationOutcome at HTTP 200 is not "no determination"; issue details, a
  notification's errors and further resources say which of the three presences they were; text in the
  code box holds up only a call that sends the case, decided by one function the build runs through
  Node; PAS and subscription outcomes with no single next step give two lines; a notification on mock
  is answered by a scenario; the chain view counts its measured runs; a production address is read
  through every layer the gateway sends through, and one that cannot be known is refused.
- The build: green at the content of `902e0d9` through the shared wrapper (`gw clean build`), 2870
  tests, 0 failures, 0 errors, 2 skipped, as the integrator ran it (its log, `final/build-c5.log` in the
  build's scratch directory). Nobody else has built this revision; the integrator's last gates are in
  the pull request description. Since then `05acbf7` (D-271): 2872 tests, 0 failures, 0 errors, 2
  skipped, through the same wrapper, built by the change's author; and `ace5914` (D-272), whose code
  is the code at the head: 2872 tests, 0 failures, 0 errors, 2 skipped, the same way, by its author.

| Step | State |
| --- | --- |
| Spine 0: build files, wrapper, empty application, config skeleton | done |
| Spine 1: shared contracts | done, reviewed by six seats, frozen |
| Wave A: A1 config layer, A2 ledger, A3 code list, A4 lookups | done and merged |
| Spine 2: integrate Wave A. V2 PHI audit | done. V2 ran twice; the fixes of the second run were checked on `9e5306d` by an agent that did not write them: five closed, two open one step away, and new findings; those were fixed by a closing task (D-253); the last check found three lines open one step away and three new majors, fixed by the last fix (D-262), checked by its author only |
| Spine 3: CRD reference vertical | done. Held to CRD 2.0.1 since the fix wave |
| Wave B: B1 PAS and subscriptions, B2 DTR | done and merged. Held to PAS 2.0.1 and DTR 2.0.1 since the fix wave |
| Spine 4: integrate Wave B, chain, suites, cross-protocol tests. V3 guardrail audit | done. V3 ran twice; the fixes of the second run were checked on `9e5306d`: eight closed, the blocker open in its second direction; that and the check's new findings were fixed by a closing task (D-254); the last check found N1 open for addresses from other layers and three new majors, fixed by the last fix (D-265), checked by its author only |
| Fix wave: every audit and review finding | merged at `33893aa` |
| Leftovers between the parts of the fix wave | merged at `7dade28`, each verified alone |
| Server work the reviews of the screens asked for | merged (`screens-protocol` at `ce345e4`, `screens-platform` at `3b1ddf4`), each begun by an agent a restart cut off and finished by a second; the second audits were its first independent readers; the platform half has no mutation evidence |
| The last server task, the last screens pass, the six lines between them | merged (`left3-server` at `c15250c`, `screens-fix` at `e995e6b`, `seam-4` at `c8a7b9f`); no verifier of their own; the checks of the second audits' fixes saw several of their changes hold (D-245) |
| Wave C: C1 frontend | merged and joined into one tool by two passes (`fe-join` at `4d795d8`, `fe-join-2` at `d15db15`), then the screens pass, the fixes for V7 and the walkthrough (`final-render` at `23edf9f`, `final-uat` at `a6446cd`) and what those two left each other (`close-screens` at `84d52ec`, D-256); the last check found a blocker and majors, fixed by the last fixes (`last-render`, `last-uat`, D-263, D-264), checked by their authors only; what they left is open (D-266) |
| Wave C: C2 docs and scripts | merged (`docs-3` at `c735dee`, `docs-4` at `38569de`); the README checked on the running tool on `e1d37dc`, brought to `428eff5`, then to `902e0d9` from the source and the reports. `run.cmd` never run |
| Wave C: C3 verification V1 to V7 | every audit has run at least once: V2, V3 and V4 twice, the second time on `0658a7c`, with each fix checked on `9e5306d`; V1, V5, V7 and the walkthrough on `9e285a1`; V6 on `9e5306d`. The fixes for V5, V7 and the walkthrough, and the four closing tasks (D-253 to D-256), are merged. The last independent check ran in six parts (D-258, D-260); the four last fixes for it are merged (D-262 to D-265), checked by their authors only, and no check follows (D-268) |
| Spine 5: clean build twice, fresh-clone build, push, pull request | the integrator's last gates run on the commit that adds these records, and their result is in the pull request description, part 2 (D-267). The branch is pushed at every integration (at `902e0d9` when these records were written); V1 and V6 built earlier revisions from a fresh clone through the wrapper |

### How the build got here

**Interrupted, and taken over.** About an hour into Waves A and B every background agent was stopped
at once by a usage limit; what each had written survived in its worktree and was handed to a new
agent, and since then every agent commits a checkpoint after each green slice. The verifier planned
after each wave package was dropped then, and the section 27 agents and independent reviews took its
place. Later the build machine restarted three times while the tool was built (at about 00:17, 03:02
and 06:00 UTC), without warning, each time cutting off every agent then at work; nothing committed was
lost and each task was taken over in its worktree. Both server tasks for the screens were such
takeovers (the introduction to D-202 says what the second agent checked of the first one's work).
Every build goes through one shared wrapper that lets two run at a time.

**Added by the operator during the build (D-118, D-119, D-136):** paste a request of your own and send
it; compare a pasted CRD request with the tool's own, each difference explained; hold the tool to
version 2.0.1 of the guides. All three are on the line, the first two with their screens on the
protocol tabs. The CRD and DTR 2.0.1 packages were supplied by the operator as files. Four questions
put to the operator had no answer during the build (D-227); after the pull request was opened the
operator answered two and does not know the answer to the other two (D-269). Told what the third
meant, the operator added that the tool will be productionized one day (D-270). Late in the build
the operator asked how much longer the work would take, and was told what remained and that the build prompt allows a finding to be resolved
or recorded; no instruction followed, and the full sequence was carried on (D-252).

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

Target: level 1 of section 0. At revision `902e0d9` the server holds what level 1 asks of it, the
optional capabilities of section 28 included: CRD, DTR and PAS, the chain, suites, conformance checking
and response times, **with the limits D-235 names beside it**: in the X12 275 attachment mode the tool
names the document and sends nothing; there is no `$submit-attachment` and no claim update, so the
guide's route after "attachment required" is not built; DTR runs no `$expand`, no
`$log-questionnaire-errors` and no CQL, and is the operations behind a SMART launch, not the launch;
the token request's way of authenticating is an assumption (D-233). It is never described as having
nothing stubbed. The frontend is on the line with all five tabs joined into one tool, none of them a
stub, and a business tester's walkthrough on mock found every case it ran readable from the screen,
with gaps since fixed in part. Level 1 is not claimed by these records: the last fixes, in the guardrail
and the ledger among them, are checked by nobody but their authors and no check follows them (D-268);
what they left is open (the list at the top); nobody opened a browser on this revision; the integrator's
last gates run after these records; and nothing has been observed against the vendor's service. The
level delivered is stated in the pull request description, part 3, after those gates.
