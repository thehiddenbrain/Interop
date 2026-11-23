# PAVE 1.0.0

PAVE is a test tool for the Da Vinci electronic prior authorization services that Point32Health
runs through the Onyx sandbox: CRD (is prior authorization required), DTR (the payer's
questionnaire) and PAS (submit the authorization and follow its status). A tester builds a case
(a member, a provider, a procedure code), runs it through four steps, and sees the answer in plain
words, the standard codes behind it, and the exact request and response. Every run is written to a
ledger on disk so it can be found, exported and run again.

It is one Spring Boot application with a browser front end, run on the tester's own machine. Out
of the box it runs on the **mock profile**: every answer comes from synthetic scenario files,
nothing leaves the machine, and no credential or configuration is needed.

## This release

**Two corrections made the same evening (D-311).** The build of `a580682` answered every page that
asks for the Submit options with an internal error: the Workbench asks when it opens, with no call in
hand, and a check for that case was missing. And a coverage check sent to a typed address still went in
PAVE's own named Bundles, though the collection's sample was stored: the operator had typed the address
to correct it, and the vendor's service answered "No patient data provided" again. Now the Submit
options answer with no call in hand, and a call to a typed address follows the sample of the
collection's request for its operation in its request shapes; it still carries no header and no body of
that request. Held by tests and by a check that opens every page of the started tool; not seen against
the vendor's service.

**Tonight's head is release 2 with four additions made after it**, each with its decision: the switch
between mock and live in the top bar (D-304); the sign-in read from the files, and a refused sign-in
that says why (D-305); the saved lists filled from a collection, with a third list for procedure codes
(D-306); and the form that takes and shows everything the files give (D-307). They were built apart,
after the operator's first run against the vendor's UAT, and joined on this head, which also carries
what the join itself needed (the merge `e09dfe8` and the commits after it). A fifth, the values a
sample request states (D-308), was added after the join. The parts below say what each gives, the newest
first; [Setting up a service](#setting-up-a-service) gives the joined form step by step.

**A fifth correction after release 2: the coverage check goes the way the collection's sample goes**
(D-309). On the same evening the vendor's UAT answered PAVE's coverage check with one card, "Error",
"No patient data provided", and answered the same check sent from the vendor's Postman collection
properly. PAVE sent its prefetch as five Bundles under names of its own (`patientBundle`,
`coverageBundle`, ...), the arrangement an earlier sandbox accepted; CDS Hooks fixes no names for the
prefetch, each service names its own keys, so a service that looks for the patient under another key
finds none. The code of commits `fc9d2a5`, `338e757`, `f3b8f55` and `ced7239`, with this README after
them, changes that. Every resource is still built from the case; only the arrangement is read from the
sample:

- A new request shape, **Prefetch**, beside "draftOrders type": "As the collection's sample (<its
  keys>)" or "PAVE's named Bundles (patientBundle, coverageBundle, ...)". With nothing stated it follows
  the collection's coverage-check sample when that sample writes a prefetch, and is PAVE's named Bundles
  otherwise, so a collection with no such sample and every mock scenario go as before.
- In the sample's arrangement each key of the sample goes holding what the sample holds there: a single
  resource, or a Bundle with the sample's type, its entry types in its order, its search mode and a
  total when it states one. A key or an entry of a type PAVE does not build, a placeholder, or anything
  that is no resource is left out and said by key and type ("The sample's prefetch also carries Encounter
  under "encounter". PAVE does not build it, so it is not sent."), and so is a member of the sample's
  context PAVE has no value for ("The sample's context also carries encounterId. ...").
- The member ID's system and type and the NPI's system follow the sample where PAVE has only a default
  (a Member ID system set for the environment still wins); the values are always the case's.
- "What these files give" shows the shape with the request it was read from and those lines; Edit shows
  them under the shape; the request notes and the preview say which arrangement went.
- After "No patient data" following the named Bundles the card says "The service found no patient in
  the request. PAVE sent the prefetch under its own names; set Prefetch to As the collection's sample in
  Configuration > Environments > Edit > Request shapes." (with no sample, it names CDS discovery).
- "Compare with a request that works" says "The prefetch is arranged differently", key by key.

The Coverage id the vendor supplies is not read from the sample. Seen on live against a copy of the
stand-in changed to answer "No patient data provided" unless the patient comes under the sample's key, at
1440 by 900; never against the vendor's service (D-309).

Since then (D-310), where the sample's order carries a diagnosis as `reasonCode`, the Diagnosis codes of
the Service card go on every order the same way, with the sample's coding system, and a case with none is
told to add them. What the sample's Patient, Coverage or order carries and PAVE's does not (an address, a
`performer`, a class of type group) is named, never filled in, in the request's notes, under Prefetch in
the form and, for a pasted request, in "Compare with a request that works".

**A fourth correction after release 2: the values a sample request states are taken from it.** On the
evening of the first real run against the vendor's UAT, the coverage check was refused: `"fhirServer"
is required`. The vendor's collection stated the value all along, in the body of its sample coverage
check, but the form took a value only from a variable of its own name, so it had to be found in Postman
and typed. The code of commits `d7a3246`, `f390ba0` and `8354f76`, with this README after them, changes
that:

- "What these files give" takes a value from the sample request that states it when no variable of its
  name gives it, and says which: "from the collection's request "1. CRD / CRD - Prior Auth Check"". The
  FHIR server from the coverage-check sample's `fhirServer`; from the Submit sample's Bundle, the payer's
  name, its identifier and that identifier's system (the Organization `Claim.insurer` refers to, when it
  has one identifier), the member ID system (the identifier typed MB on the Patient `Claim.patient`
  refers to, else the only one), and the PAS request, service and certification types (the first item,
  where PAVE writes them) and the report type. Several identifiers, or items that disagree, give none,
  and a line says so.
- "Save environment" stores them, on a new environment and on Edit, under the ticked box "Use the values
  these samples state", for a value nothing else gives; a typed value and the export's are never
  replaced. The result says each ("FHIR server set from the collection's request "..."."), and Edit
  shows each as in force from that request, with "Change".
- What the coverage-check sample sends and PAVE does not is said by name: "The sample also sends
  fhirAuthorization. PAVE does not send it: if the service refuses the check, that is the first thing to
  look at."
- A refusal of the coverage check that names `fhirServer` now says "Set FHIR server in Configuration >
  Environments > Edit > Values.", and "The vendor's sample request states one." when the request it was
  sent from does.

The callback url is not taken from any sample: it is the address of PAVE's own receiver. Seen on live
against a copy of the stand-in that refuses a check without `fhirServer`, at 1440 by 900 and 1024 by 768,
never against the vendor's service (D-308).

**A third correction after release 2: the form takes, and shows, everything the files give.** After
"Fill the form from these files" on that first UAT run the addresses and the values had to be typed by
hand, and on Edit they stood as empty boxes; a typed address then replaced the collection's request.
The code of commits `25b200a`, `6664bd1`, `fc80b7d` and `050f977`, on top of the sign-in correction
below, with this README after them, changes that:

- Right after "Fill the form from these files", a part "What these files give" shows the sign-in,
  each address with the request a call will use and where it comes from, and each value with the
  variable it comes from. Where no request was found for an operation, a list of the collection's
  requests offers one; Save environment maps it. Nothing needs typing when the files give it.
- On Edit, a value or address in force shows itself and where it comes from ("from the collection's
  request ...", "from the export's variable ...", "from application.yml", "typed here"); "Change" opens
  the box to type over it.
- A request is also told by what its body says: a CDS Hooks body that states its hook, a Bundle that
  carries a Claim, a Parameters body that names a coverage.
- A collection in Postman's older format v2.0 is read instead of refused.

Seen on live against the stand-in at 1440 by 900 and 1024 by 768, never against the vendor's service
(D-307).

**A second correction after release 2: the saved lists filled from a collection.** On the same first
run against the vendor's UAT, every member, provider and procedure code was typed by hand, though the
vendor's collection states them in its sample requests. The code of commits `babb793`, `fdce17b`,
`85b8834` and `76743d4`, merged as `165bbd2` after the sign-in correction, with this README after it,
changes that:

- In the environment form, choose the collection export (and the environment export if you have it)
  and press "Fill the form from these files". A part "Found in the collection" lists the members,
  providers and procedure codes the collection's requests state, each with the request it was found
  in, and under "Seen and not used" what cannot be used and why. It stands after "What these files
  give": what configures the environment first, the case data after.
- Leave "Add these to the saved lists when the environment is saved" ticked and press "Save
  environment": they go into Configuration > Members, Providers and Procedure codes, each labelled
  "From <request name>", and one line says what was added, for example "Added to the saved lists: 2
  members, 2 providers, 4 procedure codes.", with links to the lists; the same result says the request
  chosen for an operation, if one was ("CRD order-sign uses the request "Coverage / Prior auth
  sample"."). On Configuration > Collections,
  after an upload, "Add to the saved lists" does the same.
- They are then picks in the Case column, like any saved entry. Configuration > Procedure codes is a
  third saved list, and the Service card has "Saved codes": a pick adds the code with its description
  and the line "<code> from saved list" under it.

What is read and what is left alone: [Lookups and test lists](#lookups-and-test-lists). Seen on mock
and on live against the stand-in, never with the vendor's files or against the vendor's service
(D-306).

**A correction after release 2: the sign-in.** On the first run against the vendor's UAT, "Fill
the form from these files" left the sign-in empty: the vendor's collection asks for its token with a
request of its own and its variables carry a prefix, and PAVE read neither. Typed by hand, the
sign-in was refused, and PAVE said only "the token endpoint answered HTTP 401". The code of commits
`80962e5`, `c0f9cf1`, `27e3a96` and `b7a1770`, on top of release 2 (`777fd1e`), with this README after them, changes
that:

- The form fills the sign-in from the collection's token request (its url, client id, client secret,
  scope and how the credentials are sent), else from the environment's variables by wider names
  (`uat_client_id`, `tokenEndpoint`). Where several variables could fill one field, each is a button
  under it; a value the export leaves empty is said. See step 4 of
  [Setting up a service](#setting-up-a-service).
- A refused sign-in says what the token endpoint said, for example "No access token: the token
  endpoint answered HTTP 401 (invalid_client). It said: ...", and names the setting to change: "The
  client id and secret were sent in a Basic header: if the service wants them in the form body, choose
  In the form body under Client authentication in Configuration > Environments > Sign-in." "Test the
  sign-in" on Configuration > Access token says the same.

Seen on live against the stand-in and a token endpoint on a loopback port, never against the vendor's
service (D-305).

**Mock and live switch on screen, added after release 2.** In the top bar, `MOCK` and `LIVE` are one
control: the mode in force looks as the badge did, the other is a quiet button. Press the other one,
read "Switch to live?" (or "Switch to mock?") and press "Switch to live": PAVE restarts itself in
place on the other mode, in the same process, on the same port, with the same folders and arguments.
The page says "Switching to live..." and, a few seconds later, "Now on live." on the tab it was on.
The case on the Workbench is cleared. A production unlock of this browser session and a pasted access
token are dropped; the dialog says so first and the line after ("The production unlock was
dropped."). The switch is refused, and nothing restarts, while a suite runs, while runs are replayed,
while another switch is under way, and after PAVE's files changed on disk since it started (then stop
PAVE and start it again). If the other mode cannot start (a damaged scenario file stops mock, for one),
PAVE starts again on the mode it had and the page says "PAVE could not start on mock and is back on
live. Its console says why."; if nothing answers within a minute, "PAVE did not come back. Look at its
console, then start it again." **A real start still follows the launcher**: `./run.sh` is mock,
`./run.sh live` and `live` in STS's Profile field are live. **The mode chosen on screen is not
remembered**: it lasts until PAVE stops. The switch is commits `f9774a5` to `954f796` on top of release
2 (`777fd1e`); this README and the records were committed after them. See [Mock and live](#mock-and-live),
D-304 and [How the switch was checked](#how-the-switch-was-checked).

**Release 2 of the rebuilt screens.** The screens were rebuilt after the operator found the earlier
ones unusable; the engine behind them (the requests, the reading of the answers, the guardrail, the
ledger) is the one PAVE had. Release 1 was handed over on 2026-11-22 at 16:00 UTC. Release 2 adds to
it the same day: the code of commit `c31ae00` of the branch `release2`, which is the second half of
the plan with release 1 and all its corrections merged into it (`86d4807`) and four corrections after
one independent check; this README and the records were committed after it. The top bar of a running
build shows the day and time (UTC) that build was made; the same time is `build.time` in `GET /api/mode`.

What release 2 holds:

- One top bar and three tabs: **Workbench**, **Audit** and **Configuration**.
- The Workbench: the case on the left (Member, Provider, Service), and four steps on the right,
  **Coverage check** (CRD), **Questionnaire** (DTR), **Submit** (PAS) and **Status** (PAS
  inquiry, subscriptions, notifications). Each result is a row of chips, one headline, a few
  facts and closed folds. A step refused for a placeholder with no value offers "Values for this
  run" in place.
- **A reload keeps the case.** The address of a journey is `#workbench/<journey id>`; a reload, or
  "Load into the Workbench" in Audit, reads the journey back from the trail and the ledger and draws
  the case and each step's result again; a field read back is marked "from the trail", or "from the
  ledger" when the trail no longer holds the run.
- **The trail**: the requests and responses of the last 500 runs, secrets masked, member data inside,
  in its own folder beside the ledger (see [Audit and the trail](#audit-and-the-trail)).
- **Audit**: Runs, Journeys and Suites. One run shows its "Request JSON" and "Answer JSON" from the
  trail with "Download", "Run again" and "Load into the Workbench".
- **Lookups set up on screen**: Configuration > Members and Configuration > Providers, "Lookup
  settings", GET or POST, a sign-in of their own, and "Test the lookup" (see
  [Lookups and test lists](#lookups-and-test-lists)).
- **Questionnaire answers from the case**, each marked "from the case", never over the payer's
  answer or the tester's.
- Configuration: Environments edited on screen (sign-in, member ID rule, timeouts, request shapes,
  values, an address per operation), Collections, saved test Members and Providers with a CSV
  import, Access token with a token pasted for the session, Production writes with **"Forget this
  production address"**, and Advanced: In force now (with the stored files PAVE could not read),
  Send as written, Try a value, Check this installation, CDS discovery, Compare with a request that
  works, and **Trail**.
- **The store**: a second copy of PAVE on the same configuration store is refused at start, and a
  stored file that cannot be read is left out and listed instead of stopping the start.
- Submit follows Da Vinci PAS 2.0.1, as CRD and DTR do, unless the environment's "PAS guide"
  chooses 2.2.1 (see [Submit](#submit)); the request shapes the vendor's sandbox accepted; one
  retry after a refused token; a wait of up to 120 s for the vendor.
- The fixes for the ways an upload was found to fail. A file that is not text is named as what it
  is; a CSV saved with semicolons or tabs is told how to save it again; a Postman file of the wrong
  kind is told where it goes.

**Old place names in some server sentences.** The sentences a tester meets on the Workbench name
the new places; some others the server writes, in a fold, in Configuration, Audit or the API, keep
the old names. Read them this way:

| A sentence says | It means |
| --- | --- |
| the Config tab, Config | Configuration |
| Ready to send, on the Config tab | "Ready to send?", at the top of Configuration > Advanced > In force now |
| the CRD tab | the Coverage check step |
| the DTR tab | the Questionnaire step |
| the PAS tab | the Submit step (for an inquiry, a subscription or a notification: the Status step) |
| the History tab | Audit |
| Continue in PAS | Continue to Submit |
| the environment strip | the top bar |

The sentences of the production guardrail keep their words: in a production refusal "the Config
tab" is Configuration (Production writes, for the unlock), "the PAS tab" is step 3 Submit or step 4
Status, and the strip is the top bar.

**Known to be open in release 2**, each with what to do:

- Started with `-Dpave.classes.load-at-start=false` (not the default), the top bar's environment
  list can answer with an internal error that asks you to report it just after the IDE rebuilt the
  classes: stop PAVE and start it again.
- A replay sent from Audit that fails just after a pull does not say that PAVE's files changed:
  stop PAVE and start it again, then replay once more.
- The Date of service shows in the browser's own form (for example 01/10/2026); the value sent is
  the ISO date whatever it shows.
- On live, answers from the case have not been seen by anyone: before "Complete the response",
  read each answer marked "from the case", the dates above all.
- A sandbox or uat environment refused for a remembered production address: forget each address the
  refusal names in Configuration > Production writes, then save again (the naming is held by tests
  and not seen on a screen).
- With a lookup set up in Configuration > Members or Providers, In force now's "Ready to send?"
  can still count that lookup as to do: read "Lookups in force" for it instead.
- A reload after step 4 (an inquiry, a subscription, a notification) has not been seen to redraw
  that step: open the journey in Audit > Journeys.
- A red `Failed to load resource: ... 422` line in the browser console after a refused form is
  expected: the page shows the refusal.

**Before you rely on it**: nothing here has been run against the vendor's service or on the
operator's Windows workstation; see [Before you rely on it](#before-you-rely-on-it). How it was
checked: [How release 1 was checked](#how-release-1-was-checked),
[How release 2 was checked](#how-release-2-was-checked) and
[How the switch was checked](#how-the-switch-was-checked).

## Start here

Six steps from nothing to a working screen on mock. Nothing here reaches a real service.

**1. Check the Java: 21 or newer.** `java -version`. Correct output names version 21 or newer, for
example `openjdk version "21.0.13" 2026-10-24`. On Windows, `run.cmd` looks for the Java STS brings
in STS's usual folders (`sts*` under your user folder, `C:\`, Program Files or Local AppData
Programs); if STS is elsewhere it prints the `set PAVE_JAVA` line to use (step 4).

**2. Get the code.** `git clone --branch epa-pave <repository address>`, the address copied from
the repository's page on GitHub (Code, HTTPS; it ends in `/Interop.git`), then
`cd Interop/epa-pave` and `ls run.sh`. Correct output: `run.sh`. Every command below runs from
`epa-pave/`; the application at the root of the repository is another project. From STS, see
[STS (Spring Tools for Eclipse)](#sts-spring-tools-for-eclipse).

**3. Build and run the tests.** `./gradlew clean build` (Windows: `gradlew.bat clean build`). The
first build downloads Gradle and the dependencies (behind a corporate network see
[Running on a corporate workstation](#running-on-a-corporate-workstation)). Correct output ends
with `BUILD SUCCESSFUL in ...`.

**4. Start it.** `./run.sh` (Windows: `run.cmd`). It builds the jar first (quick when nothing
changed), so a pull is never followed by an old jar. Correct output includes, in this order:

```
Using Java 21.0.13 at /usr/lib/jvm/java-21/bin/java, the java on the PATH.
Building build/libs/epa-pave-1.0.0.jar with the Gradle wrapper. This is quick when nothing changed; tests are skipped
Starting PAVE 1.0.0 from build/libs/epa-pave-1.0.0.jar, built 2026-11-22 14:06:08 UTC.
Tomcat started on port 8057 (http) with context path '/'
```

The version, the path and the time differ by machine. If no Java 21 or newer is found, the script
says where it looked and how to name one: `PAVE_JAVA=/path/to/jdk-21/bin/java ./run.sh`, or on
Windows the `set PAVE_JAVA=...` line `run.cmd` prints. If port 8057 is taken:
`./run.sh mock --server.port=8091`, and use 8091 below. A copy of `run.cmd` (its folder search
without quotes, which Wine's cmd needs) was run by its builder under Wine on Linux; `run.cmd`
itself has never run on Windows.

**5. Ask it what it is**, from a second terminal: `curl -s localhost:8057/api/mode`. Correct output
is one line of JSON that begins `{"name":"PAVE","version":"1.0.0","port":8057,"mode":"mock"` and
holds `"build":{"version":"1.0.0","time":...}`. `"mode":"mock"` is what to check.

**6. Open `http://localhost:8057/`.** Correct output: a top bar with `Point32Health` `PAVE`, the
tabs Workbench, Audit (with today's count of runs) and Configuration, "No environment active", a
yellow `MOCK` beside a quiet `LIVE` (the switch, see [Mock and live](#mock-and-live)) and the build's
day and time. Go on with
[Your first case on mock](#your-first-case-on-mock). To stop PAVE, press Ctrl+C in its terminal;
a call still out gets up to 30 seconds to answer, so that its ledger line is written.

**From STS, and after every pull.** Stop PAVE in STS before Team > Pull or Gradle > Refresh Gradle
Project, and start it again after. If you did not, PAVE keeps its old classes while the IDE
rebuilds them, and a line under the top bar says "PAVE's files changed on disk since it started.
Stop PAVE and start it again." An internal error that names `NoClassDefFoundError` or
`NoSuchMethodError` means the same: stop and start it.
While that line shows, a refusal on a screen ends with "PAVE's files changed on disk since it
started, which can cause this. Stop PAVE and start it again, then try once more." A page left open
while PAVE restarts shows what the new start holds and says only "PAVE was started again." Both
were seen in the real STS 5.4.0, with `19ddf8e` imported and started from the Boot Dashboard.

**One copy of PAVE per configuration store and ledger folder.** The switch in the top bar changes the
mode of one copy. To run mock and live at the same time, side by side, give the second copy folders
and a port of its own:

```
./run.sh live --config-store.directory=config-store-live --tracking.directory=run-history-live --server.port=8058
run.cmd live "--config-store.directory=config-store-live" "--tracking.directory=run-history-live" "--server.port=8058"
```

A second copy started on the same configuration store stops at once with "Another copy of PAVE is
using the configuration store <folder>. Stop it, or give this copy its own config-store.directory
and tracking.directory."

## Before you rely on it

- **Nothing here has been run against the vendor's service**, and nothing on the operator's
  Windows workstation. Every result anyone has seen came from the mock profile or from a stand-in
  server on a loopback port of a Linux machine.
- **A pass on mock says nothing about the live service.** The scenario files were written from the
  same reading of the guides as the code that reads the answers.
- **On live the screens show member data**: the wire view, the lookups, the saved test lists, and
  the trail keeps it on disk. See
  [The wire view carries member data](#the-wire-view-carries-member-data).
- **PAVE has no sign-in** and listens on this machine only. See
  [Who can reach this tool](#who-can-reach-this-tool).
- **DTR here is not a SMART launch**, and the notification receiver very likely cannot be reached
  by the vendor. See [What PAVE is not](#what-pave-is-not).
- **The configuration store holds secrets and test data in plain text**, readable by your account
  only. Never commit `config-store/` or `run-history/`. See [Secrets](#secrets).

## Your first case on mock

A whole journey on the mock member `P32H-DOC-DTR`, whose coverage check asks for documentation.
On mock every member, provider and code is invented.

1. On the Workbench, in the Case column, open the member list and choose `P32H-DOC-DTR`
   ("documentation required", under "Coverage check"). The member ID, the NPI `1234567893` and the
   code `E0470` are filled in and looked up.
2. Press **Run check** in step 1, Coverage check. The headline reads "Documentation is required."
   beside a yellow `MOCK` chip, and the step's chip reads "Documentation required".
3. Press **Continue to the questionnaire**. Step 2 opens and fetches at once: "The questionnaire
   package was loaded with one questionnaire."
4. Answer "Has the member had an attended sleep study in the past 12 months?": Yes.
5. Type a number for "Epworth Sleepiness Scale score".
6. Choose one option for "Primary symptom reported" (the fourth question, "Additional clinical
   notes", is optional).
7. Press **Complete the response**: "The questionnaire response is complete."
8. Press **Continue to Submit**. Step 3 opens and sends nothing: a write is never one click from
   another step's result.
9. Press **Send PAS request**: "The prior authorization is approved." Step 4, Status, reads "Not
   needed", because the journey ended at Submit with "Approved".
10. Press **Open in Audit** to see the run as the ledger holds it.

These words were seen in a browser on the release candidate's jar (`7fc32c8`, mock).

## Reading a result

Every step answers in the same shape:

- **A row of chips**: the HTTP status, who answered (`MOCK`, "Nothing sent" or "Not sent"), the
  time in ms (with "slow" over 15 s), the test result ("Passed", "Failed", or "Unverified" when no
  expectation was set in "Expect"), and "Open in Audit".
- **One headline**: what the payer said, in plain words. Under it, a few **facts**: each standard
  code returned, as a chip in its colour, and "not returned", "returned as null" or "returned
  empty" told apart; the expectation; at most eight rows.
- **One way on**: the button of the next step, or one sentence when there is none.
- **Closed folds**: "Why" (findings, the next step's reason, request notes), "Response detail"
  (every card, item and resource of the answer), "Returned but not interpreted" (only when
  something was), and "Wire traffic" (the request and the response as they went).

The **authorization outcome** and the **test result** are different things: a case you expect to
be denied that comes back denied is a denial and a pass. "No determination" is not "not required":
mock member `P32H-EMPTY` answers with no determination, `P32H-NO-AUTH` with "not required", and
they are different outcomes with different codes in the ledger.

**A service fault and a tool fault look different.** When the service fails or refuses, the
headline says so ("The service failed (HTTP 500).").
Under it, in the same one card, come what the service said (a CDS service's summary and detail
included), the issues it sent, and the next step beside its button.
When PAVE
itself fails, the result is headed "A failure of this tool, not of the service" and shows
`Reference` with an id, and "Cause, as the server log names it:". Press "Copy reference and cause" and
send what it copies with what you were doing: the same line is the first line of the log entry
`Unhandled exception, reference`. If PAVE answers with no JSON at all, the page says "PAVE did not
answer as expected (HTTP <status>). If PAVE runs from STS, stop it and start it again."

## Setting up a service

Everything is done in **Configuration > Environments**. Nothing needs a restart: a save is used
by the next call.

1. **New environment.** Under "Start from Postman exports (optional)", choose the vendor's
   Postman environment export as "Environment export" and the collection export as "Collection
   export", and press "Fill the form from these files". The form is filled for review; nothing is
   saved until "Save environment". **Name**: taken from the environment export when one is chosen,
   otherwise typed; it cannot be changed once saved (see "Moving from the sandbox to UAT" below).
2. **Read "What these files give"**, under the two "Read: ..." lines. It shows what the files give
   and where each part comes from, so nothing the files give needs typing:
   - **Sign-in**: the line "Sign-in filled from ..." (step 4 says from where); the fields are filled
     under Sign-in.
   - **Addresses**: one row per operation of the Edit form (CRD order-sign, CRD order-select, CDS Hooks
     discovery, DTR questionnaire package, DTR next question, PAS submit, PAS inquire, Subscription),
     each with the address with the export's values put in and the request it comes from: "from the
     collection's request "1. CRD / Prior Auth Check"". A secret shows masked; a placeholder nothing
     defines stays as `{{name}}` with the line "{{name}} is not defined in these files, so it stays as
     written: a run asks for its value before it sends." Where two requests fit one operation, the
     first in the file is used and the row says so ("2 requests fit this operation. ... is used, the
     first in the file: choose another below to use it instead.").
   - **A row no request was found for** says "No request of the collection was found for it. Choose
     one below." and offers, under "Use a request of the collection", the requests that no operation
     uses. Choosing one maps it to that operation when "Save environment" stores the collection (it is
     the mapping of Configuration > Collections, which can be taken back there); a row with a request
     offers the others behind "Choose another request". "Type an address instead" stays the last choice:
     a typed address replaces the request wholly, its headers and saved body included; the request
     shapes of the call still follow that request's sample (D-311). A request is
     told by its name, its url, its folders and, when those say nothing, its body: a CDS Hooks body that
     states `"hook": "order-sign"`, a Bundle that carries a Claim (sent to `$submit` or `$inquire`), a
     Parameters body that names a coverage (`$questionnaire-package`).
   - **Values**: the FHIR server, the payer's name and identifier, the member ID system and the others,
     each with what the files give and where it comes from: a variable of its name ("from the export's
     variable fhirServer"), else the sample request that states it ("from the collection's request "1.
     CRD / CRD - Prior Auth Check""), or "not given". The coverage-check sample gives the FHIR server;
     the Submit sample the payer, its identifier and system, the member ID system and the PAS types.
     With such a value, the box "Use the values these samples state" stands ticked above the rows: the
     save then stores each for a value nothing else gives. What the coverage-check sample sends and PAVE
     does not is named under the FHIR server ("The sample also sends fhirAuthorization. ..."). A value
     these files do not give is left out of the requests that read it; type it in its box there, or on
     Edit.
   - **Found in the collection**, after "What these files give" when a collection is chosen: the
     members, providers and procedure codes its requests state, each with the request it was found in,
     what was seen and not used, and the box "Add these to the saved lists when the environment is
     saved", ticked. See [Lookups and test lists](#lookups-and-test-lists).
3. **Kind**: sandbox, uat or prod. It is never guessed:
   the field reads "Choose a kind" until you choose.
   An environment whose name or address reads as production cannot be saved as sandbox or uat until
   "This environment is not production" is ticked.
4. **Sign-in**, filled from the files in this order, the first that gives client credentials winning:
   the collection's OAuth2 helper; the collection's token request (a request whose form body or url
   query carries `grant_type=client_credentials`: its url as written, its `client_id`, `client_secret`
   and `scope` with "In the form body", or its Basic helper with "Basic header"); the environment's
   variables by name (`tokenUrl`, `clientId`, `clientSecret`, `scope` exactly, else a name that holds
   `tokenurl`, `tokenendpoint`, `authurl`, `oauthurl`, `clientid` or `clientsecret`, or ends with `scope`,
   with case, `_`, `-`, `.` and spaces aside). A bearer helper of the collection is used only when none
   of these gives client credentials. One line says where it came from: "Sign-in filled from the
   collection's token request "Auth / Get token": Token url, Client id, Client secret, Scope and Client
   authentication." Where it cannot fill, a line says why:
   - several variables could be one field: "Several variables could be the client id:
     {{uat_client_id}}, {{prod_client_id}}. Choose one.", and each is a button under the field that
     fills it with one press;
   - a value the export leaves empty: "{{clientSecret}} is empty in this export. Postman exports a
     variable's initial value only: type the value here, or set the initial value in Postman and export
     again.";
   - a secret typed into the file is never filled or shown ("... typed into the file. It was not filled:
     type it in Client secret, or a {{variable}} or {{env:PAVE_NAME}} reference.");
   - another grant type: "The collection's token request "Token" uses the grant type password, so
     sign-in was not filled from it. PAVE signs in with client credentials.";
   - what the token request sends that PAVE's sign-in does not, by name only: "The token request also
     sends audience and the header Ocp-Apim-Subscription-Key. PAVE's sign-in does not send them: if the
     sign-in is refused, that is the first thing to look at."

   The collection's scripts are never read or run. The methods:
   "As PAVE was started" (the `onyx.auth` settings of `application.yml`),
   "None", "Client credentials" (token url, client id, client secret, scope, client authentication
   "Basic header" or "In the form body") or "Bearer token". A 401 from the service drops the token,
   signs in again and sends the call once more; the wire view says "The first attempt was refused
   with HTTP 401; PAVE signed in again and sent it once more."
5. **Save environment**, then make it active with "Make active" in the list or in the top bar's
   Environment. Saving never changes the active environment. Once the environment is saved, the
   request chosen in step 2 for an operation is mapped and the entries found are added to the saved
   lists (with the box ticked), and the result says both: "CRD order-sign uses the request "Coverage /
   Prior auth sample"." and "Added to the saved lists: 2 members, 1 provider, 4 procedure codes.", with
   links to the lists. Each value stored from a sample is said too: "FHIR server set from the
   collection's request "1. CRD / CRD - Prior Auth Check".". If a chosen request could not be mapped, the save says "The environment is
   saved, but the request chosen for <operation> was not recorded." and the environment stays saved:
   choose it again in Configuration > Collections.
6. **Edit** the saved environment for the rest. A new environment chosen without files says "The
   member ID rule, request shapes, values, timeouts and addresses can be set once the environment is
   saved." On Edit, nothing in force stands as an empty box: a value or an address shows what is in
   force and where it comes from, and "Change" opens the box to type over it.
   - **Member ID rule**: packed (spaces removed, the default and what the vendor's sandbox wants),
     spaced (nine characters, three spaces, the last two, as on the card) or split (the first nine
     of an 11-character ID). Every result shows the form sent: "Sent as <sent>, rule: <rule>".
   - **Timeouts**: the wait for an answer is 120000 ms unless the environment states its own. Their
     boxes stay boxes: the value in force stands in the empty box as its hint ("120000, from
     application.yml").
   - **Request shapes**: "draftOrders type", "Sent (FHIR requires it)" or "Left out (this vendor
     refuses it)". Filled from the collection's own sample when the environment states none. The
     builders' sample collection carries the type, so the vendor's sandbox (and the stand-in that
     plays it) answers the first coverage check with HTTP 400 until this reads Left out.
     "Prefetch", "PAVE's named Bundles (patientBundle, coverageBundle, ...)" or "As the collection's
     sample (<its keys>)": the keys the coverage check's prefetch goes under (D-309). Filled from the
     collection's coverage-check sample when the environment states none and that sample writes a
     prefetch; the lines under it say what following the sample leaves out, by key and type, what
     the sample's resources carry that PAVE never writes ("The sample's Patient also carries
     address, telecom and meta. PAVE has no field for them, so they are not sent."), and, when the
     sample's order carries a diagnosis, that the Diagnosis codes of the Service card go there
     (D-310). These elements are standard FHIR; CRD does not require them.
     "PAS guide", "2.0.1" or "2.2.1": the version of Da Vinci PAS that Submit and the inquiry
     follow, 2.0.1 when the environment states none ("Submit follows Da Vinci PAS 2.0.1 unless the
     service asks for 2.2.1."). See [Submit](#submit).
   - **Values**: the FHIR server, the payer's name and identifier, the member ID system, the PAS
     request, service, certification and report types, the callback url, and any other variable a
     request reads. Each row shows the value in force and where it comes from ("from the export's
     variable fhirServer", "from the collection's request ...", "from application.yml", "typed here",
     "not set"). A typed value lies over the export's own; "Use the file's again" takes it away at the
     next save. A value stored from a sample stays when its box is left closed; "Change" and a typed
     value replace it. To take a sample's value on Edit, choose the collection export there, press "Fill
     the form from these files" and save. A name that reads as a secret is refused there.
   - **Addresses**: one row per operation, with the address in force and where it comes from ("from
     the collection's request ...", "from application.yml", "typed here", "not set"). A typed address
     replaces the collection request for that operation, its headers and saved body included. The
     request shapes (draftOrders type, Prefetch) still follow the sample of that request, so an address
     typed to correct it does not change how the request is arranged (D-311); "Use the
     collection's request again" closes the box before anything is typed, and "Use the file's again"
     takes a typed address away at the next save.
   - **Sign-in**: a stored client secret or bearer token is said as stored ("Stored:
     {{uat_client_secret}}"); "Change" opens its box.

Walked on the release candidate's jar on live against the stand-in: an environment made from a
copy of the sample environment export with the stand-in's addresses, and the sample collection,
saved and made active, a coverage check refused with HTTP 400, draftOrders type
set to Left out and saved, and the same check answered "Documentation is required." with no
restart.
**A refused sign-in says what the token endpoint said** when it answered with an OAuth `error`: "No
access token: the token endpoint answered HTTP 401 (invalid_client). It said: <its description>. The
request was not sent." Only its `error` and `error_description` are shown, without control
characters, cut at 300 characters, with any secret of the sign-in replaced by its mask ("[secret client
secret]"); they are never logged. After an `invalid_client`, or a 401 with no reason, "what to do" says
where the client id and secret were sent and how to send them the other way; after an `invalid_scope`,
it points at Scope. "Test the sign-in" says the same.
When a sign-in fails, "Open Configuration" opens the active environment's form at Sign-in; when an
answer points at the address (404, unknown host, refused connection), at Addresses. A token url
that does not answer is named first: "Check the token url of the environment <name>, set in
Configuration > Environments > Sign-in: <url>." Both were seen again on live against the stand-in,
on the jar of `19ddf8e`, for a wrong client secret and a 404.

**Secrets.** A secret field left blank keeps the stored value; "Remove the stored secret" removes
it. Type `{{env:PAVE_NAME}}` to read it from an operating system variable at the moment of sending
(only names that start with `PAVE_` are read). **A client secret or bearer token typed any other way
is kept as typed in `config-store/store.json`**, a file only your account can read on Linux and
macOS (on Windows, the folder's access list decides who can read it), and the save warns once. **A token pasted on Configuration > Access token** ("Paste a token", then "Use this
token") is held in memory for the active environment only, never saved, and dropped by a 401, a
restart or any change of the configuration. It is refused on mock. "Test the sign-in" on the same
page asks the token url for a token now and sends nothing to the service.

**Moving from the sandbox to UAT** is a new environment made from the UAT export, never an edit of
the sandbox one: an environment's name cannot be changed, because production unlocks and remembered
production addresses go by it.

**Where the store is.** `config-store/` in the folder PAVE starts in (`config-store.directory`).
`store.json` names the environments, `environments/` and `collections/` hold the exports,
`test-lists/` the saved members and providers, `installation-salt` the ledger's salt. To bring it
to a new folder, stop PAVE and copy the whole folder. **A store saved by release 1 is version 2**:
the first save of a setting copies the old file to `store.json.before-v2` once and writes version
2. An older build refuses a version 2 store at start ("... it was written by a later version of this
tool. Repair it, or move the directory ... aside to start with an empty store."). To go back to an
older build, keep `store.json.before-v2`: stop PAVE and rename it to `store.json`.

**A production address remembered by mistake.** An environment once saved or uploaded as prod
leaves its addresses remembered as production, also after it is removed, and a sandbox or uat
environment on that address is refused. Configuration > Production writes lists each remembered
address; beside an address whose environment is no longer stored as prod it offers **"Forget this
production address"**. The dialog asks for the name of the environment it was remembered for,
typed exactly, and then says "<address> is no longer remembered as production." An address of a
stored production environment is never forgotten. Another remembered address above or beneath the
one forgotten still blocks: the refusal at save names every remembered address that blocks ("forget
the address <address> in Configuration > Production writes and upload this environment again."),
so forget each one it names. A send refused for this reason says so under the guardrail's sentence,
names the environment the address was remembered from and says "press Forget this production
address beside it in Configuration > Production writes." Where Forget is not offered (an owner is
still stored as prod), it gives the hand edit instead: stop PAVE, delete those entries from
`productionDestinations` in `config-store/store.json`, start again.

**A sandbox that cannot sign in after a slip.** An environment saved as prod by mistake on the same
host as a working sandbox, and then removed, stops that sandbox from signing in, reads included,
when the sandbox's token url is, or lies beneath, an address remembered from it. The refusal says
"Nothing was sent: the token url, at <address>, is an address of the production environment ...",
then that the environment "is no longer stored, so this tool no longer knows the token url it
signed in with", and gives the two ways out: store it again (this helps only when the sandbox signs in at
that environment's own token url), or, if it was stored as prod by mistake, "press Forget this
production address beside each address of" that environment in Configuration > Production writes,
"and this environment signs in again." A test shows the sandbox's token url no longer refused after
Forget; no one has seen it on a screen.

**What the vendor's sandbox refused before, and what answers it now.**

| Refused before | Now |
| --- | --- |
| HTTP 400 on CRD for `context.draftOrders.type` | Request shapes, "Left out (this vendor refuses it)"; filled from the collection's sample when it leaves the type out. "Open Configuration" opens the list of environments: press Edit on the active one and open Request shapes. After that 400 the card says what the service said and "Set draftOrders type to Left out for <name> in Configuration > Environments, then run the check again." |
| A coverage check answered "No patient data provided" (the vendor's UAT, D-309) | Request shapes, Prefetch "As the collection's sample", filled from the collection's coverage-check sample. After that answer to the named Bundles the card says "The service found no patient in the request. PAVE sent the prefetch under its own names; set Prefetch to As the collection's sample in Configuration > Environments > Edit > Request shapes." |
| A member ID with spaces | Member ID rule packed, the default |
| An inquiry with no declared profile | The inquiry Claim declares its profile; nothing to set |
| A patient or practitioner id its FHIR server did not hold | "Vendor patient id" in the Member card, "Vendor practitioner id" in the Provider card and on saved entries |
| A token refused before its stated expiry | One sign-in again and one retry after a 401 |
| An inquiry that took longer than 20 s | A wait of up to 120 s, or the environment's own timeout |

### Collections

Configuration > Collections: "Upload collection", then each request's mapping to an operation,
which can be corrected and taken back. A collection in Postman's format v2.1 or the older v2.0 is
read; a v2.0 file says so in one line ("This collection is in Postman's older format v2.0, which this
tool reads as it reads v2.1."). Pre-request scripts are never run: a request that has one is
flagged, and every run that uses it carries the finding `PRE_REQUEST_SCRIPT_NOT_RUN`.

**A file that cannot be read is told so in a sentence**, on every upload: the environment form,
"Upload collection" and the lists' "Import CSV". In the checks on `9dbf61a`, none of 453 uploads
of broken or wrong files in those three places ended in the internal error box.
A zip, a compressed file, a picture or a PDF is named as what it is ("<name> is a zip file. Unzip
it and choose the .json file inside."); a CSV saved with semicolons or tabs, or a JSON file chosen
as CSV, is told how to save it again; an environment export chosen as a collection, or the
reverse, is told where it goes. Seen again in all three places on the jar of `19ddf8e`.

### Variables

A `{{placeholder}}` is filled from, in order: a value typed for one run (Configuration > Advanced >
Try a value, `variables` in a request body, or "Values for this run" on the Workbench), the environment's values
(Configuration), the environment export, the variables of the collection, then `onyx.variables` in
`application.yml`. A defined but blank variable counts as not set. When one cannot be filled, the
request is not sent: 422 with `"kind":"CONFIGURATION"`, naming the variable. On mock the url of a
CRD, DTR or PAS run is not resolved, because nothing is sent: an incomplete environment shows up
in Configuration > Advanced > In force now, or with `POST /api/config/resolve`. `{{$guid}}`,
`{{$randomUUID}}`, `{{$timestamp}}` and `{{$isoTimestamp}}` are generated; any other dynamic
variable is refused unless a value is typed for it.

PAVE never invents a value nobody supplied (D-141): without these, the request goes without the
element and the result names it and the variable.

| Protocol | Variable | What it supplies |
| --- | --- | --- |
| CRD, DTR, PAS | `memberIdSystem` | The identifier system of the member ID |
| CRD, DTR | `patientMrn`, `patientMrnSystem` | The medical record number, never filled from the member ID |
| CRD, DTR, PAS | `payerIdentifier`, `payerIdentifierSystem`, `payerName` | The payer; the insurer of a PAS claim, which PAS requires (2.2.1 also requires the identifier's system) |
| CRD, PAS | `fhirServer` | The hook's `fhirServer`, and the base of every `fullUrl` PAVE sends |
| CRD | `crdOrderSignServiceId`, `crdOrderSelectServiceId` | The CDS service ids |
| DTR | `dtrIntendedUse`, `dtrCoverageExtension` | The intended use; `qr-context` (2.0.1) or `qr-coverage` (the 2.2.0 ballot) |
| PAS | `pasRequestType`, `pasServiceType`, `pasCertificationType` | Each item's request type, service type and certification type, when the Service card gives none |
| PAS | `pasReportType` | Under PAS 2.2.1 only, the X12 report type of the documentInformation of each attachment and of the questionnaire response; PAS 2.0.1 has no place for it, so it is not sent and a note says so |
| PAS | `itemTraceNumberSystem` | The system of the item trace numbers, which PAS 2.2.1 requires and 2.0.1 does not |
| PAS | `pasHcpcsSystem`, `coverageRelationship`, `claimIdentifierSystem`, `claimType`, `claimPriority`, `attachmentControlNumberSystem` | Parts of the claim |
| PAS | `pasRequestorType`, `pasRequestingOrgIdentifier`, `pasRequestingOrgIdentifierSystem` | The requesting organization, and a subscription's filter by it |
| PAS | `subscriptionTopic`, `subscriptionFilter`, `subscriptionStatus`, `subscriptionReason`, `subscriptionChannelType`, `subscriptionPayloadContent`, `subscriptionHeartbeatSeconds`, `subscriptionEnd` | Parts of a subscription |

### Secrets

- **Secret variables are masked** in every answer that shows configuration (`[secret apiKey]`). A
  variable is secret when Postman typed it `secret` or its name reads as a credential. Masking
  goes by name: a key written inside a url, or held by a variable with an ordinary name, is shown
  as written. Keep every key in a variable of its own whose name says what it is.
  A name that says where, not what (`authBase`, `authServer`: its last word is `base`, `server`,
  `domain`, `address`, `origin`, `hostname` or `port`), is not read as a secret.
- **A credential typed into a collection** is named at upload and masked wherever the collection is
  shown. The file on disk keeps it.
- **Keep the secret out of the file**: give the variable the value `{{env:PAVE_NAME}}` in Postman
  or on the form, and set `PAVE_NAME` where PAVE runs. The sample does this:

  ```
  "key": "clientSecret", "value": "{{env:PAVE_ONYX_CLIENT_SECRET}}", "type": "secret"
  ```

  ```
  export PAVE_ONYX_CLIENT_SECRET='the real secret'
  ./run.sh live
  ```

- Settings of `application.yml` can also come from the environment, for example
  `ONYX_AUTH_CLIENT_SECRET` for `onyx.auth.client-secret`.
- **The ledger's salt** (`config-store/installation-salt`, or `tracking.salt-file`) turns each
  member ID into the token the ledger keeps. With the salt and the last four characters the ledger
  also keeps, a member ID is recovered at once. Never copy it with the ledger or its export.
- `config-store/` and `run-history/` are in `.gitignore`; PAVE warns at start when either lies
  inside a git working tree. Never commit them.

### Member data in an uploaded file

The store keeps an export as it was read, every value, header, body and saved example response
included. The upload warns, naming the variables or requests and never a value, when a variable's
name reads as member data or a request carries a saved example. It cannot judge the values:
**never upload an environment or a collection that holds a real member's data**; use placeholders
such as `S123456789`, and remove saved examples taken from real calls.
What a collection's requests state about a member can now also be put into the saved lists, by your
own tick: the box in the environment form, or "Add to the saved lists" on Configuration > Collections
([Lookups and test lists](#lookups-and-test-lists)). Saved example responses are not read for it.

### Environment kinds

| Kind | Top bar | Writes |
| --- | --- | --- |
| `sandbox` | grey outline badge | allowed |
| `uat` | purple outline badge, and a line "UAT" with the whole name | allowed |
| `prod` | the whole bar purple, and a line "Production" with the whole name and whether writes are locked | blocked unless unlocked, and never when the floor forbids it |

Every ledger line records the kind of the environment it ran under.

### The production guardrail

A PAS submit against production creates a real prior authorization request against a real member.
**What decides is where a request goes, not only which environment is active** (D-143):

- PAVE remembers the addresses (scheme, host, port and base path) of every environment declared
  `prod`, typed addresses included, also after it is removed and across a restart. A write to one
  of them is a write to production whatever environment is active.
- **Reads go through**: inquiry, subscription read and list, discovery, the lookups.
- **Writes are blocked**: PAS submit, subscription create, update and delete, and a request sent as
  written whose method can change something. A blocked write answers 403 with a full result that
  begins "Nothing was sent.", and is recorded in the ledger as `BLOCKED`.
- **The floor.** `config-store.allow-prod-writes` is `false` by default, and while it is false no
  unlock is possible: "Production writes cannot be unlocked: an owner has locked this installation
  against them." Only whoever owns the installation changes it, with a restart.
- **The unlock.** With the floor `true`, a tester unlocks writes for their own browser session on
  Configuration > Production writes by typing the active environment's name exactly, or:

  ```
  curl -s -c pave-cookies -b pave-cookies -X POST -H 'Content-Type: application/json' -d '{"action":"unlock","environment":"Exact Environment Name"}' localhost:8057/api/config/writes
  ```

  The unlock lives in server memory, in that session only, and is for the environment it names.
  Any change to the configuration drops it, a save on Configuration included, and so does
  `{"action":"lock"}` from any browser, for every session (D-144).
- **A replay or a suite never carries an unlock**: a write in one against production is reported
  as blocked, with the next step to send it on its own.
- **A value typed for one run may not move a write** to another scheme, host, port or base path.
- **A token url is held against every production address**: a sign-in's token url that is, or
  lies beneath, any address a production environment (stored or remembered) calls, reads included,
  is refused at its field (`signIn.tokenUrl`) at save and never sent to. A token url that a stored
  production environment itself signs in with stays allowed. So where a production environment's
  address is the root of a host, no other environment can sign in on that host, unless at the token
  url the stored production environment itself signs in with; after a slip this stops a sandbox,
  see
  [A production address remembered by mistake](#a-production-address-remembered-by-mistake).
- **An address is compared as a server reads it**: each percent-escape of its path decoded once,
  so `%24submit` is `$submit`; an address that still holds a `%` after that is refused ("An address
  holds no percent-escape of an escape."). Seen again on the jar of `19ddf8e`, with every spelling
  refused before still refused.
- **An address PAVE cannot read the way the HTTP client reads it is never taken as "not
  production"**: a write to it is refused with 403, "Blocked: the address it would go to could not
  be read." A production environment whose address PAVE does not know exactly (unreadable, a
  placeholder with no value, or read from an operating system variable) is refused at upload and
  cannot be made active or unlocked.
- **No request leaves with an encoded separator in its path** (`%2F`, `%5C`, `%3B`, a dot segment
  hidden by an escape, or a character above 0x7F): 422, naming the variable or the request, never
  the value.
- **The "not production" acknowledgement** covers that environment's own addresses only. "Reads as
  production" means the text `prod` in its name or an address, and nothing cleverer.

What the guardrail does not see (D-145): another DNS name for a production host, and the
`onyx.<protocol>.url` fallback settings of `application.yml` as an address of a production
environment. Agents that did not write it looked at the guardrail twice before the rebuild and
once on the rebuilt tool (`0b69049`).
Its sentences are those of `79a7c25`, word for word.

## Submit

**Submit follows Da Vinci PAS 2.0.1**, the version the operator says the payer uses, unless the
active environment chooses 2.2.1 under "PAS guide" (Configuration > Environments, Request shapes;
see [Setting up a service](#setting-up-a-service)). An environment that states no guide, and mock
with no environment active, get 2.0.1. CRD, DTR and the reading of PAS answers follow 2.0.1
throughout. `GET /api/pas/options` names the guide in force (`"version":"Da Vinci PAS 2.0.1"`, or
`"Da Vinci PAS 2.2.1"` for an environment that chose it) and serves the code lists the Service card
offers. Every Submit and inquiry carries a note that names the guide it follows and where it is set.
Submit was first built to 2.2.1 on an answer of the operator's; they then said again that the payer
uses 2.0.1, so 2.0.1 is the default and 2.2.1 is kept as a choice, with everything built for it
(D-300).

- **What it reads**: from the Service card, each code with its quantity and unit, the date and
  place of service, the diagnosis codes and, in "Service details", the level of service, the
  certification type, the request type and the service type; from the Provider card, the phone
  or fax (PAS asks for one) and an optional servicing provider; from the environment's values or
  its variables, `payerName` and `payerIdentifier` (the insurer, which PAS requires),
  `pasRequestType`, `pasServiceType` and `pasCertificationType` when the card gives none,
  `itemTraceNumberSystem`, and under 2.2.1 only `pasReportType` (under 2.0.1 it is not sent, and a
  note says PAS 2.0.1 has no place for it).
- **Under 2.0.1** each Service card field is sent when given. What 2.0.1 only marks must-support
  (the request type, the certification type, the place of service, the level of service, the
  subscriber, the claim identifier's system) is never counted as left out: when it is missing, a
  note says so. An attachment given by its url is sent by its url, with a note.
- **Under 2.2.1** the request and its checks are those slices E1 to E3 built: more is required (the
  identifier and trace number systems, the request and certification types, the place of service,
  the provider's phone or fax, among others), so more is listed as left out.
- **"To complete before sending"** lists what the guide in force requires that the request would
  go without, one dashed chip each (on mock under 2.0.1, with the member P32H-DTR-PREPOP: Insurer,
  Service type); pressing one opens the field, on the card or in the environment's Values. Sending
  stays possible: an incomplete request is a negative test, and the first request note says what
  was left out and names the guide ("This request goes out without N elements PAS 2.0.1 requires,
  ...").
- **Attachments**: "Add files" in step 3. PDF, PNG, JPEG, TIFF, GIF, text and Word files, up to 5 MB
  each and 10 MB in all. In the attachment mode `fhir` (the default, `onyx.pas.attachment-mode`)
  each file travels inside the bundle as a document reference with its data; in `x12-275` PAVE
  names the document on the claim and sends no 275 transaction, which is another channel. A file
  is never kept in the ledger.
- The questionnaire response completed in step 2 goes with the request unless "Include it" is
  unticked; it is not handed on when the case changed since step 2.

## Lookups and test lists

**Saved test members, providers and procedure codes** live in Configuration > Members,
Configuration > Providers and Configuration > Procedure codes, for sandbox test data only: "Add
member", "Import CSV", "CSV template" ("Manage lists" in the Case column opens them). They are kept in
`config-store/test-lists/`, readable by your account only, never in the ledger or the log, at most
200 entries a list. Picking a saved entry in the Case column fills the card, each field marked
"from saved list"; it does not look the member up. A saved member holds a member ID, a vendor patient
id, or both; picking one that holds only the vendor patient id fills that and leaves Member ID empty
for you to type. A saved procedure code (label, code, code system, description) is picked in the
Service card under "Saved codes". Load the vendor's sandbox patient and practitioner once, with the
vendor patient id and the vendor practitioner id, from a collection (below), by hand or with the
CSV import, which shows the columns used and each row's outcome before anything is stored. From
Excel, save the sheet as CSV first. `test-lists.enabled: false` switches the lists off, and the
reading of collections below with them.

**Filled from a collection.** PAVE reads only what each request of a collection sends: its body and
its url as written, with `{{placeholders}}` filled from the environment export chosen with it, else
from the stored environment being edited, else (on Configuration > Collections) from the active
environment, then from the collection's own variables. Saved example responses are never read;
scripts are never read or run. Reading sends nothing and stores nothing. A body with a placeholder
nothing fills, or that uses a secret, is not read, and one line says so.

- **Members**: `context.patientId` of a CDS Hooks request, a Patient id in a request's url, and each
  `Patient` resource wherever it sits in a body (a prefetch value, a Bundle entry, a contained
  resource, a Parameters part): its id as the vendor patient id, its name, a whole birth date and its
  gender; a `Coverage` of that patient gives the subscriber id, status, period and plan. The member ID
  is the identifier of the environment's `memberIdSystem`; else the one identifier typed `MB`; else
  the only identifier; else there is none: it is never guessed.
- **Providers**: `context.userId` when it reads `Practitioner/<id>`, a Practitioner id in a request's
  url, and each `Practitioner` resource: its id, its name, and its NPI (system
  `http://hl7.org/fhir/sid/us-npi`) only when its check digit holds.
- **Procedure codes**: the codings of `ServiceRequest.code`, `Procedure.code`,
  `DeviceRequest.codeCodeableConcept` and `Claim.item.productOrService` with its details. An RxNorm or
  NDC code is a medication and is left out.
- The same member, provider or code in several requests is one entry.

It is offered in two places: the environment form, after "Fill the form from these files", with the
box "Add these to the saved lists when the environment is saved", ticked at first (the entries are
added only once the save went through); and Configuration > Collections, after an upload, with the
button "Add to the saved lists". An entry a list already holds (the same member ID, vendor patient id,
NPI, vendor practitioner id, or code and code system) is left as it is; a full list says how many did
not fit; an entry the list's rules refuse is named with why. Choose the collection at that moment: a
collection already stored is not read again. A collection in Postman's older format v2.0 is read as
v2.1 is (D-307); a format the parser does not read gives one line ("The collection was not read: ...
This tool reads formats v2.1 and v2.0.").

**Lookups.** On mock, lookups answer from PAVE's synthetic fixtures ("On mock, lookups answer from
PAVE's test fixtures. These settings are used on live."). On live, Configuration > Members holds
"Member lookup" and Configuration > Providers "Provider lookup", each with a fold "Lookup settings":
"Lookup on", "Method" (GET or POST), "Address" (a GET puts `{id}` where the ID goes, in the path or
the query), "Body template" for a POST, extra headers, "Sign in with" (None, API key header, Bearer
token, Client credentials, or the active environment's sign-in), "Where the answer's fields are"
and the timeouts. "Save lookup" stores it in `config-store/lookups.json`; a secret is masked on
screen and kept as typed in that file, like the environment's. "Test the lookup" sends one ID (for
example `S123456789`) through the saved settings and answers in one line, such as "Found, HTTP 200,
145 ms, Member found, with coverage reported as active.". A lookup saved on screen is used in place
of `lookup.member.url` or `lookup.provider.url` of `application.yml` (see [Properties](#properties)),
which still apply when nothing is saved. When no lookup is set up, or it does not answer, the card
says so, links "Set up the member lookup" or "Set up the provider lookup", and you type the fields by
hand. Nothing a lookup returns is kept. A member lookup answers "Found", "Several matched", "Not
found", "Not answering", "The lookup failed" or "No lookup is configured". The real member API's and
provider directory's contracts are not known (see [Open items](#open-items)).

## Audit and the trail

- **Runs**: today's count is on the Audit tab ("!" when the ledger is not recording, with a line
  under the top bar). Filters by day, step, test result, outcome and tester reference; "Export
  CSV"; "Replay the runs listed".
- **One run**: its result, "Run again" (sends the same case again and compares the answers, for
  example "Same outcome."), "Load into the Workbench" (reads the case and each step back into the
  Workbench; sends nothing), and a note and tester reference ("Save note"). A run whose ledger line
  lacks what a replay needs says so in the button's place. On live with the default policy that is
  every run: "This run cannot be run again: its member ID was not kept."
- **A run's request and answer**: the folds "Request JSON" and "Answer JSON", read from the trail,
  with "Download" (one file, `<run id>.json`, for the vendor). A run the trail no longer holds says
  "Not kept: the trail holds the last 500 runs, and never a write that was refused."
- **Journeys**: each journey of today (UTC) with its member, code, steps and where it stands;
  "Include journeys of one run"; one journey shows its four steps and "Load into the Workbench".
- **Suites**: a file of cases run one after another. "CSV template", "Suite file", "Tester
  reference", "Preview the suite", "Run suite", then the results and "Results CSV". Suites PAVE
  holds are listed under "Suite runs PAVE holds", in memory only.
  `samples/suites/sample-suite.csv` has six CRD cases that run on mock (four pass, one fails on
  purpose, one declares no expectation). Columns: `caseId`, `protocol` (crd, dtr, pas or chain),
  `hook`, `operation`, `chain`, `memberId`, `npi`, `procedureCodes` (separated by `|`),
  `dateOfService`, `expected`, `scenarioId`, `testerRef`, `note`, `claimId`, `subscriptionId`; a
  column whose name starts with `#` is never read, any other unknown column stops the file. From a
  terminal: `curl -s -F file=@samples/suites/sample-suite.csv localhost:8057/api/suites/preview`
  says what PAVE makes of it without sending anything.

**The trail** keeps the request and the response of each of the last 500 runs (`trail.max-entries`),
one file a run in `run-history/trail/` beside the ledger (`<tracking.directory>/trail/`), readable by
your account only. Every run is kept except a write the production guardrail refused. **It holds
member data** (the case and the wire as sent and answered) and **never a secret**: what it keeps is
the wire view, masked by the same rule. It is not the ledger: the ledger's rules are unchanged. Never
commit it or send it on except to the vendor, run by run. To empty it: Configuration > Advanced >
Trail, "Empty the trail", or `curl -s -X DELETE localhost:8057/api/trail`. `trail.enabled: false`
keeps nothing: every run then reads "The trail is switched off on this installation
(trail.enabled)."

## Advanced

Configuration > Advanced holds the tools kept off the Workbench: **In force now** (where each
operation goes, whether it can be sent, the lookups in force, the store's folder, address warnings,
and "Stored files PAVE could not read", each with "Remove it from the store", which moves the file
to `config-store/unreadable/` and never deletes it), **Send as written** (a collection request as it
is written, or a body of your own), **Try a value**, **Check this installation** (the code list's
self-check, the ledger, the mode), **CDS discovery**, **Compare with a request that works** (PAVE's
CRD request beside one you paste, element by element; nothing is sent), and **Trail** (how many runs
are kept, their size, the folder, and "Empty the trail").

## What PAVE is, and what it is not

- **CRD**: a CDS Hooks `order-sign` or `order-select` call, and discovery. The answer is read from
  the standard coverage information codes (`covered`, `pa-needed`, `doc-needed`, `doc-purpose`,
  `info-needed`, `satisfied-pa-id`, the delegated vendor), never from card wording. CRD never
  denies on clinical grounds and nothing in PAVE reads a CRD answer as a denial.
- **DTR**: `$questionnaire-package`, `next-question` for an adaptive questionnaire, and the
  QuestionnaireResponse that PAS carries. Not built: `$expand`, `$log-questionnaire-errors`, CQL.
- **PAS**: `Claim/$submit` and `Claim/$inquire`, subscriptions (create, read, list, update,
  delete) and a receiver for the notifications a payer sends back. No claim update and no
  `$submit-attachment` (D-161), so the guide's route after "attachment required" is not built.

### What PAVE is not

- Not provider facing, not a replacement for the Availity portal, not a load test tool, not a
  clinical decision system.
- **DTR here is the server-side operations, not a SMART launch.** There is no SMART authorization
  server, no launch handshake and no EHR embedding. When you exercise DTR in PAVE you exercise the
  operations behind a provider-facing SMART launch, not the launch itself. Do not report a DTR run
  here as a test of the SMART launch.
- **PAVE is held to version 2.0.1 of CRD, DTR and PAS** (D-136); Submit and the inquiry follow PAS
  2.2.1 instead for an environment whose "PAS guide" says so (D-293, D-300). Content of a later
  version is read and named with its version, never called wrong.
  US Core, SDC, HRex, the subscriptions backport, FHIR R4 core and the X12 code lists were not
  available as rules, so a request PAVE builds can still be refused by a conformant service for
  reasons that are PAVE's.

### The notification receiver will very likely not be reachable

PAS subscriptions work by the payer calling PAVE back at `POST /api/pas/notifications`. On a
corporate workstation the payer's servers very likely cannot open a connection to it, and PAVE
listens on this machine only by default: **with the defaults, no vendor can reach the receiver**.

- Use **polling**: "Send inquiry" in step 4, Status, or `POST /api/pas/inquire`. It works on its
  own.
- Subscription create, read, update and delete still work: those are calls PAVE makes.
- On mock, "Deliver a sample" in step 4 (`POST /api/pas/notifications/sample`) sends a sample
  notification through the same code a real one reaches.
- To receive real notifications someone must give the machine an address the payer can reach and
  open PAVE to it. Not before PAVE has a sign-in (D-270); see
  [Who can reach this tool](#who-can-reach-this-tool). The callback url is a value of the
  environment (`callbackUrl`), or `onyx.subscription.callback-url`.

The receiver does not authenticate its caller and has never been called by a real payer.

## Mock and live

| | Mock (the default) | Live |
| --- | --- | --- |
| Start with | `./run.sh` (`run.cmd`) | `./run.sh live` (`run.cmd live`) |
| Switch to it while PAVE runs | `MOCK` in the top bar | `LIVE` in the top bar |
| Answers come from | scenario files in `src/main/resources/scenarios/` | the services of the active environment |
| Members and providers | synthetic fixtures in `src/main/resources/fixtures/`, and the saved test lists | the configured lookups, the saved test lists, or typed by hand |
| Needs | nothing | an environment, its credentials, network access |
| Ledger records the member ID | only a `P32H-` sentinel or an `S` and nine or ten digits; anything else as on live | no: a salted token and the last four characters, unless configured |
| Mock members and "Mock scenario" | yes | no: a forced outcome against a real service would be a result that did not come from it |

**Both ways: the launcher for the start, the switch after.** A start follows the launcher: no
profile is mock, `live` is live. While PAVE runs, the other part of the top bar's control switches it:
PAVE closes its Spring context and starts it again in the same process on the other profile, on the
same port, with the same arguments and folders (D-304). The part in force says which runs, and so does
`GET /api/mode`. The mode is still the Spring profile, so what holds on a live start holds after a
switch to live: nothing on live can play a scenario, and a live call that fails is never answered from
mock. The tool refuses to start when `epa.mode` and the Spring profile disagree. The environment form
shows the profile as "Mock. Switch it in the top bar." or "Live. Switch it in the top bar.".

**What a switch clears and drops.** The page loads again onto the tab it was on; on the Workbench that
is a new empty case. Everything PAVE holds in memory ends with the old context, as at any restart: a
production unlock, a pasted access token, the browser session, suite results held in memory. What is
on disk stays: the configuration store, the saved lists, the ledger and the trail. A page left open in
another window notices the new mode and loads again onto its tab with the same line. **The mode is not
remembered**: the next real start follows the launcher again.

**When it is refused**, in the dialog, in the server's words, with nothing restarted: "PAVE is already
on live."; "A suite is still running, so PAVE did not switch." (stop it in Audit > Suites, or wait);
"Runs are still being replayed, so PAVE did not switch."; "PAVE is already switching to mock."; and
"PAVE's files changed on disk since it started, so it cannot switch in place: a switch loads no class
anew." (stop PAVE and start it again). A copy started some other way than its own main class (a test,
for one) cannot switch itself and says so.

**When the other mode cannot start**, PAVE starts again on the mode it had, and the page says "PAVE
could not start on mock and is back on live. Its console says why."; the console holds Spring's own
account of the failed start. If that start fails too, PAVE logs "PAVE could not start on mock nor
again on live, so it stops. Start it again by hand." and the process ends.

**Every scenario is synthetic.** Sentinel member IDs select the mock scenario: `P32H-PA-REQ`
answers "prior authorization required", `P32H-NO-AUTH` "not required", `P32H-EMPTY` no
determination, and so on; a sentinel means the same case in every protocol (D-154). The member
list of the Case column offers each one with its few words, grouped by the first step it plays,
and fills the member ID, an NPI and the codes its scenario names. `GET /api/crd/scenarios`,
`GET /api/dtr/scenarios` and `GET /api/pas/scenarios` serve them (14 CRD, 15 DTR and 21 PAS
scenario files). The placeholder NPI is `1234567893`; `9999999995` selects "provider not found".
A damaged scenario file stops the start on mock and names the file (D-153). A scenario is chosen
by, in order: "Mock scenario" in the step's Options, the member ID, the NPI, a member ID found in
a body of your own, else the file with the lowest number; the result says which rule chose it. A
scenario folder of your own (`app.scenario-dir`) must carry `crdSentinelMemberIds` and
`answersDtrSentinels` as the bundled files do; the start log names each file that lacks them.

On mock a short delay is added to each answer, so the screens behave as on live;
`app.mock-latency-enabled: false` removes it.

## The wire view carries member data

The wire view (a closed fold under each result) shows the request and the response as they went.
That is its point, and it means that **on live it shows member data**: the member ID, the name,
the date of birth, and whatever the payer sent back. So do the lookup results, "Edit member
fields", the saved test lists, the request preview and a body of your own. **The trail keeps the
same on disk** for the last 500 runs, with the same masking, and Audit and its "Download" show it
again ([Audit and the trail](#audit-and-the-trail)).

- Do not screenshot or share the wire view, a lookup, a preview or a request body on live unless
  everyone who can see it may see that member's data. Keep the fold closed.
- Do not paste a request or a response into a note, a ticket or a chat. A note is written to disk
  as typed.
- On mock every member is synthetic, which is what makes the default safe to demo.

What the wire view hides: credential headers (`Authorization`, `Cookie`, API key headers) show at
most their scheme, and a secret PAVE holds that the service sends back is masked wherever it is
repeated, whether or not this request carried it: the secret variables, the client secret, the
access token (a pasted one included), the secrets of the environment's settings and of the
lookups. The mask names the secret (`[secret clientSecret]`) and the wire view counts the places.
The result is read from the masked body, so a verdict read from a mask says so under its headline.
Not caught: a secret sent back re-encoded or shorter than six characters. A notification is masked
the same way before it is held or shown.

The ledger never holds a request or response body, a name, a date of birth, an address, a gender or
a subscriber ID, under any setting. Every `/api/` answer carries `Cache-Control: no-store`.

## Who can reach this tool

PAVE has **no sign-in**. Anyone who can reach its port can use everything it offers: on live,
look up members, submit to the active environment, read the ledger, change the configuration and
the saved lists. So by default:

- **It listens on this machine only** (`server.address: 127.0.0.1`, D-115). No vendor can reach the
  notification receiver until this changes.
- **It answers only to `localhost`**, a loopback address, the bound address, or a name listed in
  `app.allowed-hosts`; any other name gets 403 ("This request was addressed to a host name this
  tool does not answer to."). The receiver is exempt from this check, for its own address only.
- **It refuses a request from another site**, whatever its method (D-148): 403, "This request came
  from a page that is not part of this tool, so it was not carried out." A client that sends
  neither `Sec-Fetch-Site` nor `Origin`, such as curl, is let through. The session cookie, which
  holds the production unlock, is `SameSite=Strict` and `HttpOnly`.
- Nothing it serves is stored by the browser, pages load scripts and data from this tool only, no
  other site can frame it, and no address of the tool is sent to another site as a referrer.
- **One limit on any request body**: 30MB, held in memory and never written to the temporary
  folder (D-149).

**Opening it to the network** (`--server.address=0.0.0.0` and a name in `app.allowed-hosts`) is
not to be done before PAVE has a sign-in (D-270), and never without asking whoever owns the
installation and the environment it points at, and never with a production environment active.

### Before PAVE is run as a shared or production service

The operator said the tool will be productionized one day (D-270), read here as running it as a
real service. As delivered it is built for one tester on one machine. **None of what follows is
built**; each would have to exist first:

- a sign-in and roles: today anyone who can reach the port can do everything;
- an encrypted connection: today it is plain http;
- secrets kept apart from the tool's files: today the store, the salt and `application.yml` hold
  them in plain text (owner-only on Linux and macOS; on Windows the folder's access list);
- a rule for who may see member data: today the screens, the uploads, the saved lists and the
  ledger's notes show it to whoever can reach the tool;
- a record of who did what: today a ledger line names no user, and anyone can change a note;
- a check of the receiver's caller, and somewhere lasting to keep what arrived: today it holds at
  most 200 notifications in memory, each cut at 131,072 bytes;
- one unlock rule per person: today the active environment and the unlock are one for the whole
  copy;
- storage several copies can share: today one copy uses one store and one ledger folder;
- packaging, monitoring, backup and a retention rule: today there is `run.sh`, `run.cmd`, the jar,
  `GET /api/runs/health` and `GET /api/mode`, and the ledger keeps every day's file;
- a limit on where the tool may send, and on how much one caller may send;
- an observation in the vendor's sandbox of how its token request authenticates the client.

## The test run ledger

Every run, on both profiles, is appended as one JSON line to `run-history/runs-YYYY-MM-DD.jsonl`
(one file per UTC day; `tracking.directory` moves it). A line is written before the result is
shown, then flushed and synced; files are never rewritten, and are readable by their owner only
where the file system allows. If a write fails, the result says "NOT recorded" with the reason, and
the Audit tab shows "!". If the folder cannot be written at start, PAVE refuses to start and names
the path. `GET /api/runs/health` says whether tracking is alive.

**A line holds**: run id, time, protocol and operation, target, profile, environment and its kind,
scenario, procedure codes, line of business, outcome, the standard codes returned, delegated
vendor, HTTP status, elapsed time, who answered, error type, what was expected and the result,
note, tester reference, chain, suite and case ids, the run it replays or follows, the next step,
the collection request used, the findings, correlation ids (claim id, ClaimResponse id,
subscription id), and which inputs it does not hold, so a replay can refuse what it cannot
reproduce.

**A line never holds**, under any setting: a member's name, date of birth, address, gender,
subscriber ID, or any request or response body; nothing of the saved test lists and nothing of an
attachment. A code or a line of business that reads like an identifier or a date is not kept as
written; a claim, ClaimResponse or subscription id a service supplies is written in clear only
when it cannot be anybody's data (a UUID, an OID, an id PAVE minted), else as a token. Known gap: a
one-word surname written where a code belongs is kept (D-151).

**The member ID policy**:

```yaml
tracking:
  record-member-id: false     # the mock profile sets it to true
  record-npi: true            # an NPI is not PHI
```

- On mock a member ID is written in clear only when it is a `P32H-` sentinel or an `S` and nine or
  ten digits; any other is a token, as on live.
- On live by default the member ID is replaced by a salted token (HMAC-SHA-256) and its last four
  characters, so two runs of one member can be matched, **as long as the salt is kept apart** (see
  [Secrets](#secrets)). Deleting the salt, or moving the ledger without it, breaks matching. An
  authorization number is treated the same way.
- If an owner sets `record-member-id: true` on live, PAVE honours it and logs one warning at start.
- A run whose member ID was not recorded cannot be replayed, and replay says so.

**A note is free text, kept as typed** (up to 4,000 characters; the tester reference up to 200, the
expectation up to 1,000). A run whose note, tester reference or expectation holds the member ID,
name, date of birth, subscriber ID or address that the same request carries is refused with 422
before anything is sent, naming the kind of value and never the value. What PAVE cannot recognise
it writes as typed: somebody else's name, a short ID, a date it does not recognise. **Do not type
member data into a note, a tester reference or an expectation.**

**The list** (`GET /api/runs`) filters by day, protocol, operation, outcome, result, chain, suite
run, tester reference and the run a line follows; `zone` (an IANA zone id) makes the days those of
that clock, else every day is a UTC day. **Export** with `GET /api/runs/export.csv`: UTF-8 with a
byte order mark, newest first; a cell that starts with `=`, `+`, `-` or `@` gets a leading quote.
**Replay** (Audit's "Run again" and "Replay the runs listed", or `POST /api/runs/replay`) sends each run again
against the configuration in force now and compares it with the original. A replayed submission is
a new submission; a replay never carries a production unlock; on mock a replay is answered by the
scenario the original recorded, and says so. At most 1,000 runs per replay.

## The procedure code list

The code picker shows what the P32H code list says about a code. **Until the real export is
supplied, a synthetic placeholder is used** (`src/main/resources/codelist/placeholder-codes.csv`,
every row marked "Placeholder row. Not from a P32H export."). A list whose notes hold "Not from a
P32H export." is synthetic whatever its path: on mock each finding that rests on it says so, and on
live PAVE does not compare with it at all (D-169). Point at the real export with `codelist.file`
and restart:

```
./run.sh mock --codelist.file=samples/codelist/sample-code-list.csv
```

The sample is in the expected shape and synthetic: its codes and the four delegated vendors are
real names, every assignment, flag and date is invented. Columns are found by candidate headers
(`CodelistProperties.java`; `codelist.columns.<column>` replaces one list). A flag that is blank
or unrecognised is **not stated**, never "No PA". The first call after dropping in a new export:

```
curl -s localhost:8057/api/procedure-codes/self-check
```

It reports the source, whether it is synthetic, the row count, which header each column was found
under and **which columns were not recognised**.

## Endpoints

Every endpoint answers JSON in one error shape, never an HTML error page. **200** for a completed
call, including one where the service returned a fault (described in the body); **422** for a form
that failed validation, an unknown property in a request body (named), a placeholder with no value
(`"kind":"CONFIGURATION"`), an encoded separator in a path, member data in a note, a repeated key,
or a body that is not JSON; **403** when the guardrail blocked a write, or for another site or host
name; **409** when no address is configured for an operation, the configuration changed since the
screen was drawn, or writes were locked between preparing and sending; **400** for an unencoded
semicolon or encoded slash in a path (use the query form of a lookup); **405**, **406**, **413**
(over 30MB) and **415** in the same shape; **202** when a suite starts; **404** for an unknown run,
environment, collection, saved entry or address; **500** when PAVE itself failed after the call was
made, with the result in the body. Lookups answer 200 once a lookup was made: not found is a
normal answer.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/mode` | Name, version, port, mode, target, active environment, epoch, write access, the receiver's note, the build (`build {version, time, changedOnDisk}`), `startedAt` (the latest start, a switch included), `held {productionUnlock, pastedToken}` (what a switch would drop), and `switchingTo` while a switch is under way |
| POST | `/api/mode` | Switch to the other mode (`{"mode":"live"}` or `{"mode":"mock"}`): 202 with what `GET` answers and `switchingTo`, then PAVE restarts itself; 422 for a mode that is neither, 409 when refused (see [Mock and live](#mock-and-live)) |
| GET | `/api/vocabulary` | The short words, tones and button names the screens look up |
| POST | `/api/config/environments` | Upload a Postman environment (multipart: `file`, `kind`, optional `acknowledgeNotProd`; or JSON: `{fileName, text, kind, acknowledgeNotProd}`) |
| POST | `/api/config/environments/draft` | "Fill the form from these files": what the form can be filled with (`{environmentExport, collectionExport, forId}`, each export `{fileName, text}`); stores nothing |
| GET | `/api/config/environments` | Environments with kind and active flag, and the addresses remembered as production |
| GET | `/api/config/environments/{id}/settings` | One environment's settings, each beside what is in force and where it comes from |
| POST | `/api/config/environments/settings` | Make or save an environment from its form (JSON; a blank secret keeps the stored one) |
| POST | `/api/config/environments/{name}/activate` | Make an environment active |
| DELETE | `/api/config/environments/{name}` | Remove an environment |
| POST | `/api/config/collections` | Upload a collection (multipart: `file`; or JSON: `{fileName, text}`) |
| GET | `/api/config/collections` | Collections with the detected mapping |
| GET | `/api/config/collections/{id}/requests/{requestId}` | One request in full |
| PUT | `/api/config/collections/{id}/mapping` | Correct a mapping |
| DELETE | `/api/config/collections/{id}` | Remove a collection |
| POST | `/api/config/writes` | Unlock or lock production writes for this session |
| POST | `/api/config/production-destinations/forget` | "Forget this production address" (`{destination, environment}`, the name typed exactly); refused for an address of a stored production environment |
| POST | `/api/config/unreadable/remove` | "Remove it from the store" (`{file}`): moves an unreadable stored file to `unreadable/` |
| GET | `/api/config/lookups` | The member and provider lookups saved on screen, secrets masked, and what is in force |
| PUT, DELETE | `/api/config/lookups/{kind}` | Save or remove the `member` or `provider` lookup |
| GET | `/api/config/effective` | Everything in force, secrets masked |
| POST | `/api/config/resolve` | What a request would become, without sending it |
| POST | `/api/config/sign-in/token` | Hold a pasted token for the active environment (`{token, environment}`; 422 on mock) |
| DELETE | `/api/config/sign-in/token` | Forget the pasted token |
| POST | `/api/config/sign-in/test` | "Test the sign-in": ask the token url for a token now; sends nothing to the service |
| GET, POST | `/api/lists/members` | Saved test members: the list, or add one |
| PUT, DELETE | `/api/lists/members/{id}` | Replace or remove a saved member |
| GET | `/api/lists/members/template.csv` | The CSV template, three placeholder rows |
| POST | `/api/lists/members/import` | Import CSV text (`{csv, dryRun}`; a dry run stores nothing) |
| GET, POST, PUT, DELETE | `/api/lists/providers`, `/api/lists/providers/{id}`, `.../template.csv`, `.../import` | The same for saved test providers |
| GET, POST, PUT, DELETE | `/api/lists/codes`, `/api/lists/codes/{id}`, `.../template.csv`, `.../import` | The same for saved test procedure codes |
| POST | `/api/lists/found` | What a collection's requests state for the saved lists (`{collectionExport, environmentExport, forId, fromActiveEnvironment}`, each export `{fileName, text}`); stores nothing |
| POST | `/api/lists/found/add` | The same files read again, and what they state added to the saved lists |
| GET | `/api/members/{memberId}`, `/api/members?memberId=` | Member lookup (the query form for an ID with a slash; optional `dateOfService`) |
| GET | `/api/providers/{npi}`, `/api/providers?npi=` | Provider lookup (the NPI check digit first; optional `dateOfService`) |
| GET | `/api/procedure-codes?q=&lob=` | Code type ahead |
| GET | `/api/procedure-codes/self-check` | What the tool made of the code list |
| POST | `/api/crd`, `/api/crd/preview` | One CRD call, or its request without sending it |
| POST | `/api/crd/compare` | Compare a pasted CRD request (`rawBody`) with PAVE's own; sends and records nothing |
| GET | `/api/crd/discover` | CDS Hooks discovery |
| GET | `/api/crd/scenarios`, `/api/dtr/scenarios`, `/api/pas/scenarios?for=` | Mock scenarios with their short words, and the outcome vocabulary |
| POST | `/api/dtr/package`, `/api/dtr/package/preview` | `$questionnaire-package` |
| POST | `/api/dtr/next-question`, `/api/dtr/next-question/preview` | One step of the adaptive loop |
| POST | `/api/dtr/response` | Complete the QuestionnaireResponse (sends nothing) |
| POST | `/api/dtr/case-answers` | Answers to a questionnaire's questions from the case, each marked with where it came from (sends and records nothing) |
| GET | `/api/pas/options` | The PAS version Submit follows, the Service card's code lists, attachment kinds and limits |
| POST | `/api/pas/submit`, `/api/pas/submit/preview` | `Claim/$submit` |
| POST | `/api/pas/inquire`, `/api/pas/inquire/preview` | `Claim/$inquire` |
| POST, GET | `/api/pas/subscriptions` | Create (and `/preview`), or list |
| GET, PUT, DELETE | `/api/pas/subscriptions/{id}` | Read, update (and `POST .../{id}/preview`), delete |
| POST | `/api/pas/notifications` | **The receiver.** Always 200, no body |
| GET | `/api/pas/notifications?since=` | Notifications received, correlated to runs |
| POST | `/api/pas/notifications/sample` | A sample notification (mock only) |
| POST | `/api/raw`, `/api/raw/preview` | Send one collection request as it is written |
| GET | `/api/runs?from=&to=&protocol=&operation=&result=&outcome=&chainId=&suiteRunId=&limit=&testerRef=&follows=&zone=` | Query the ledger |
| GET | `/api/runs/{runId}` | One run |
| POST | `/api/runs/{runId}/note` | Add or change a note |
| GET | `/api/runs/export.csv?from=&to=&protocol=&result=&zone=` | CSV for sign-off |
| POST | `/api/runs/replay` | Run again and compare (`runIds`, or `from` and `to`) |
| GET | `/api/runs/health` | Is tracking alive, runs recorded today |
| GET | `/api/chains`, `/api/chains/{chainId}` | Journeys, each with its four steps |
| POST | `/api/suites/preview`, `/api/suites` | What PAVE makes of a suite file, or start it (multipart: `file`); 202 |
| GET | `/api/suites`, `/api/suites/{suiteRunId}`, `.../results.csv` | Suite runs held in memory, one, its results |
| POST | `/api/suites/{suiteRunId}/stop` | Stop a running suite |
| GET | `/api/trail` | The trail: switched on or off, runs kept, the limit, size and folder |
| GET | `/api/trail/{runId}`, `/api/trail/{runId}/download` | One run's request and answer as kept, or the same as a file `<runId>.json` (404 when not kept) |
| DELETE | `/api/trail` | Empty the trail |

### Examples, on mock

Run them from `epa-pave/` against a PAVE started by `./run.sh`. Every identifier is a placeholder.
Put in `RUN_ID` and `CHAIN_ID` from an earlier answer (`runId` and `chainId` of the first CRD run).
Run in this order on the release candidate's jar (`7fc32c8`), each answered 200 unless its comment
says otherwise.

```bash
curl -s localhost:8057/api/mode
curl -s localhost:8057/api/vocabulary
curl -s -F file=@samples/postman/pave-sample-sandbox.postman_environment.json -F kind=sandbox localhost:8057/api/config/environments
curl -s -X POST localhost:8057/api/config/environments/pave-sample-sandbox-977ed8ee/activate
curl -s -F file=@samples/postman/pave-sample.postman_collection.json localhost:8057/api/config/collections
curl -s localhost:8057/api/config/environments/pave-sample-sandbox-977ed8ee/settings
curl -s localhost:8057/api/config/effective
curl -s -X POST -H 'Content-Type: application/json' -d '{"operation":"PAS_SUBMIT"}' localhost:8057/api/config/resolve
curl -s localhost:8057/api/pas/options
curl -s localhost:8057/api/members/P32H-PA-REQ
curl -s 'localhost:8057/api/providers?npi=9999999995'
# 422: the check digit does not match, nothing was looked up
curl -s localhost:8057/api/providers/1234567890
curl -s localhost:8057/api/lists/members
curl -s -o test-members-template.csv localhost:8057/api/lists/members/template.csv
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DOC-DTR"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"E0470"}],"chainId":"new"}' localhost:8057/api/crd
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-NO-AUTH"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"E0601"}],"expected":"NO_AUTH_REQUIRED"}' localhost:8057/api/crd
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PAS-DENIED"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"expected":"DENIED"}' localhost:8057/api/pas/submit
curl -s localhost:8057/api/chains/CHAIN_ID
curl -s 'localhost:8057/api/runs?protocol=CRD&limit=5'
curl -s -X POST -H 'Content-Type: application/json' -d '{"note":"Checked for ticket T-0001","testerRef":"SAMPLE"}' localhost:8057/api/runs/RUN_ID/note
curl -s -o pave-runs.csv 'localhost:8057/api/runs/export.csv?protocol=CRD'
curl -s -X POST -H 'Content-Type: application/json' -d '{"runIds":["RUN_ID"]}' localhost:8057/api/runs/replay
curl -s -X DELETE localhost:8057/api/config/environments/pave-sample-sandbox-977ed8ee
```

The two `-o` lines write a file in the current directory that git does not ignore; delete them
when done (on live `pave-runs.csv` holds ledger data).

The two uploads as JSON, the way the screens send them (with `jq`; each answered 200 on the same
jar, started on live):

```bash
jq -Rs '{fileName: "pave-sample-sandbox.postman_environment.json", text: ., kind: "sandbox"}' samples/postman/pave-sample-sandbox.postman_environment.json | curl -s -H 'Content-Type: application/json' -d @- localhost:8057/api/config/environments
jq -Rs '{fileName: "pave-sample.postman_collection.json", text: .}' samples/postman/pave-sample.postman_collection.json | curl -s -H 'Content-Type: application/json' -d @- localhost:8057/api/config/collections
```

## Properties

Every property in `src/main/resources/application.yml` can be given on the command line
(`./run.sh mock --tracking.directory=/data/pave-runs`) or as an environment variable in Spring's
form (`TRACKING_DIRECTORY`). `GET /api/config/effective` shows what is in force. The ones a tester
meets:

| Property | Default | What it does |
| --- | --- | --- |
| `server.port` | `8057` | Not 8080, so PAVE runs beside the tools that use 8080 |
| `server.address` | `127.0.0.1` | This machine only. Not to be changed before PAVE has a sign-in (D-270) |
| `config-store.directory` | `./config-store` | The configuration store, the saved test lists and the salt |
| `config-store.active-environment` | `""` | An environment to make active at every start. Blank keeps the last one |
| `config-store.allow-prod-writes` | `false` | The floor. While false, no production write can be unlocked |
| `tracking.directory` | `./run-history` | The ledger |
| `tracking.record-member-id` | `false` (`true` on mock) | Record member IDs in clear |
| `tracking.record-npi` | `true` | Record the NPI |
| `tracking.salt-file` | `""` | Blank: `installation-salt` in the store folder. Never inside the ledger folder |
| `tracking.slow-threshold-ms` | `15000` | A call slower than this gets the "slow" chip |
| `test-lists.enabled` | `true` | The saved test members and providers. False: the lists answer empty and nothing is read or written |
| `trail.enabled`, `trail.max-entries` | `true`, `500` | The trail of the last runs' requests and responses, in `<tracking.directory>/trail/`. False: nothing is kept |
| `codelist.file` | `""` | The code list export. Blank: the bundled placeholder |
| `app.scenario-dir` | `""` | A folder of scenario files in place of the bundled ones |
| `app.mock-latency-enabled` | `true` | The scenario's delay on mock |
| `app.allowed-hosts` | `[]` | Host names, besides localhost, the tool answers to |
| `onyx.crd.url`, `onyx.dtr.url`, `onyx.pas.url`, `onyx.subscription.url` | `""` | Fallback base urls, used when neither a typed address nor a collection request gives one |
| `onyx.pas.attachment-mode` | `fhir` | `fhir` or `x12-275` |
| `onyx.subscription.callback-url` | `""` | The address the payer should call back; an environment's `callbackUrl` value stands in for it |
| `onyx.connect-timeout-ms` | `5000` | Connect timeout for the vendor's calls, unless the environment states its own |
| `onyx.read-timeout-ms` | `120000` | How long PAVE waits for the vendor's answer, unless the environment states its own. The vendor's inquiry took longer than 20 s |
| `onyx.auth.enabled`, `.token-url`, `.client-id`, `.client-secret`, `.scopes` | off, blank | Client credentials for an environment whose sign-in is left to `application.yml`. Values may be placeholders |
| `onyx.variables` | `{}` | Last-resort values for placeholders |
| `lookup.member.url`, `lookup.provider.url` | `""` | The lookups on live when none is saved in Configuration, with `{id}` in the path or the query. Blank: no lookup |
| `lookup.member.enabled`, `.connect-timeout-ms`, `.read-timeout-ms`, `.headers` | `true`, `3000`, `5000`, `{}` | Lookup switches; the same for `lookup.provider` |

Also read when set: `lookup.member.use-onyx-auth` and `lookup.provider.use-onyx-auth`,
`codelist.format`, `codelist.delimiter`, `codelist.columns.<column>`,
`codelist.line-of-business-aliases`. The rest of `application.yml` (multipart limits, the session
cookie, log levels that keep request text out of the log) is commented where it stands.

## Running on a corporate workstation

The build has to work on a P32H laptop behind the corporate network, where `repo.maven.apache.org`
and `services.gradle.org` may both be blocked. The checks below were made on earlier revisions;
`settings.gradle`, `gradle.properties`, the wrapper and `build.gradle` have not changed in the
rebuild.

### Dependencies through an internal mirror

Set `internalRepoUrl` in `~/.gradle/gradle.properties` (or `gradle.properties` beside
`build.gradle`, but then never commit it):

```properties
internalRepoUrl=https://your-mirror.example.invalid/maven2
# Only if the mirror asks for credentials:
internalRepoUser=your-user
internalRepoPassword=your-password
```

One property switches both the dependencies and the Gradle plugins. Checked with a mirror address
from the command line; **not checked**: a mirror that needs credentials.

### The Gradle distribution

`./gradlew` downloads Gradle 9.5.0 from `services.gradle.org` the first time; if that is blocked the
wrapper cannot start at all. Either point the wrapper at an internal copy in
`gradle/wrapper/gradle-wrapper.properties`:

```properties
distributionUrl=https\://your-mirror.example.invalid/gradle/gradle-9.5.0-bin.zip
```

(checked with a `file:` url), or use an installed Gradle 9.5 in place of the wrapper:
`gradle clean build` (checked with 9.5.0). `run.sh` and `run.cmd` call the wrapper.

### TLS behind an inspection proxy

A proxy that inspects TLS presents its own certificate, and the download fails with
`PKIX path building failed`. Add the corporate root certificate to a trust store and point Java at
it:

```
keytool -importcert -alias corporate-root -file corporate-root.crt -keystore ~/pave-truststore.jks -storepass changeit
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/pave-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
./gradlew clean build
```

`JAVA_TOOL_OPTIONS` is read by every Java process: the wrapper, the Gradle daemon and PAVE. The
machine this was checked on works that way; the `keytool` line itself was not run there.

### STS (Spring Tools for Eclipse)

**Seen on Linux with Spring Tools 5.4.0, never on Windows.** Agents that wrote none of the tool ran
the real IDE (Eclipse 4.41.0 with Buildship 3.1.11) on Linux under a virtual display, on the code
before the rebuild (last on `8463174`) and on the rebuilt tool: the tree of `1a14611` imported
with 0 errors and started from the Boot Dashboard on mock; a Team > Pull to the tree of `9dbf61a`
under the running tool brought the files-changed line to the open page in about 1.2 s; uploads
sent over the API still answered 200 (one from a page reloaded before the restart was refused,
STS-2); a restart from the Boot Dashboard moved the build stamp and removed the line (and showed a
notice of a change in another window, STS-1).
The release branch at `19ddf8e` was imported into the same IDE and started from the Boot
Dashboard: the files-changed line and the restart behaved as "Start here" says. The Problems view
showed one error, in a test line the Eclipse compiler rejects, which made STS ask before every
start; that line was corrected (commit `1772d37`), and the Eclipse compiler STS 5.4.0 brings,
run by the integrator over every source set of `22478b8`, found 0 errors. The release itself was
not imported into STS again.
What one Windows workstation reported is at the end of this section.

**1. Which Spring Tools.** Spring Tools 5 for Eclipse: PAVE is a Spring Boot 4 application, and the
Spring Tools changelog gives 5.0.0 (2025-12-10) as the first release that states Spring Boot 4
support (read on 2026-11-20). STS runs on a Java 25 of its own. A JDK 21 installed in the system's
usual place is found by STS and by Gradle with no setting. Without one, the build compiles PAVE
for Java 21 with the Java that runs Gradle and says so in one line in the Console view, "Gradle
found no JDK 21, so PAVE is compiled for Java 21 with the Java that runs Gradle: ..." (D-274;
seen with the JDK 21 hidden, a simulation). Older than 21 with no JDK 21: the build stops with
"Gradle is running on Java ..., which is older than 21, and Gradle found no JDK 21. ..."; install a
JDK 21.

**2. Get the code.** What STS must import in the end is the folder `epa-pave` inside your clone,
with the clone on the branch `epa-pave`; the top folder is another application with a Maven build.
Either clone from a terminal (steps 2 and 3 of [Start here](#start-here)) and go on with step 3, or
clone from STS with the Git import wizard, whose own choices are wrong twice:

1. File, Import..., `Git`, "Projects from Git (with smart import)", Next >.
2. "Clone URI", Next >.
3. Paste the repository address into "URI:". Leave "User:" and "Password:" empty. Next >.
4. "Branch Selection": "Deselect All", tick only `epa-pave`, Next >.
5. "Local Destination": "Initial branch:" must read `epa-pave`. Next > clones.
6. "Import Projects from File System or Archive" lists one row, `Interop` as `Maven`: **that is
   the other application. Do not press Finish yet.** Add `/epa-pave` to the end of the path in
   "Import source:" and press Tab: the only row becomes `epa-pave`.
7. Finish. Once the status bar no longer shows "Synchronize Gradle project", the Package Explorer
   shows `epa-pave [boot]`. Go on with step 4.

**Already pressed Finish on the `Interop` row?** Open Window, Show View, Other..., "Git
Repositories"; expand your repository, then "Working Tree"; right click the folder `epa-pave`,
Projects, Import Projects..., Finish. Then remove the Maven project: right click it, Close Project
(nothing is removed), or Delete with **both boxes unticked** ("Delete project contents on disk"
would delete your clone). While both projects are open the Boot Dashboard shows two
`PaveApplication` entries and one press of start launches two copies, the second failing with
"Port 8057 was already in use." **Do not open Run, Run Configurations before `epa-pave` is
imported**: closing it rewrote the tracked `PaveApplication.launch`
(`git checkout -- epa-pave/PaveApplication.launch` puts it back).

**3. Import.** File, Import, Gradle, Existing Gradle Project. Choose `epa-pave/`, not the
repository root. The Import Preview shows "Gradle wrapper from target build" and **Gradle version
9.5.0**; "Unknown" or another version means the wrong folder. Finish; within about a minute the
Package Explorer shows `epa-pave [boot]`.

**If the import fails** (none of these raised a dialog):

- "Preview failed" naming `gradle-8.9-bin.zip`: the folder given has no wrapper, so it is not
  `epa-pave`. It reads like the network and is not. Import the `epa-pave` folder of a clone on the
  branch `epa-pave`.
- "Project root directory does not exist.": the field holds an older path with yours added, or the
  clone is on a branch with no `epa-pave` folder.
- "Cannot find a Java installation on your machine ... languageVersion=21": a checkout from before
  D-274; Team, Pull and then Gradle, Refresh Gradle Project.
- The downloads are refused: see the three sections above. A JDK 21 installed while STS was open:
  close STS and run `./gradlew --stop` in `epa-pave` before trying again (not tried).
- **Red markers before Gradle has fetched the dependencies are normal.** If they stay, right click
  the project and choose Gradle, Refresh Gradle Project: that is the first thing to try.

**4. The Problems view** read "0 errors, 18 warnings" on the tree of `1a14611`; on the release the
Eclipse compiler STS brings finds 0 errors, so it should show none. **Never apply the quick fix of
"Unnecessary path variable definition"**: without the names the controllers give their request
parameters, calls such as the Audit list would answer HTTP 500, and `HandlerArgumentNamesTest`
fails the build.

**5. Start it.** The Boot Dashboard (bottom left): expand `local`, select `epa-pave`, press "Start
or restart". Or right click `PaveApplication.java`, Run As, Spring Boot App. The shared launch
configuration `PaveApplication.launch` starts it with no dialog. The Console shows `Tomcat started
on port 8057`; go on with step 5 of [Start here](#start-here). Stop it with the stop button and
wait first for a call that is out: that button may end the process at once, without its ledger
line. **Stop PAVE before every Team, Pull or Gradle refresh**, and start it again after (see
[Start here](#start-here)).

**When the running tool answers "This tool hit an internal error"**, press "Copy reference and
cause" and read the cause:

- "PAVE's classes changed on disk while it was running, as the IDE does when it rebuilds the
  project. Stop PAVE and start it again." says what to do.
- "Unresolved compilation problem": the project has errors. Problems view, then Project, Clean...,
  and start again.
- `NoClassDefFoundError`, `ClassNotFoundException`, `NoSuchMethodError`: stale classes. Stop PAVE,
  Project, Clean..., Gradle, Refresh Gradle Project, start again.
- `OutOfMemoryError`: "Start PAVE with -Xmx512m or more."

**6. The live profile, or another port.** To switch a running PAVE between mock and live, use the
control in the top bar: it needs none of what follows, and the launch configuration is not changed.
To **start** on live: Run, Run Configurations, Spring Boot App,
`PaveApplication`: `live` in the Profile field of the Spring Boot tab, Apply, Run. **That writes
the profile into the tracked file `PaveApplication.launch`**: do not commit it; clearing the field
and Apply puts it back. Or start from a terminal with `./run.sh live` (`run.cmd live`), which
leaves the file alone. `GET /api/mode` says which profile runs; the Boot Dashboard's label can be
stale.

**7. Tests from the IDE.** Run the whole suite with Gradle (`./gradlew test`). Three tests check
the Gradle build's own wiring and are skipped from the IDE's JUnit launcher, saying so.

**8. Later commits.** Stop PAVE. Right click `epa-pave`, Team, Pull (the entry without dots), then
Gradle, Refresh Gradle Project: the import leaves automatic synchronisation off, and a pull alone
does not apply a change of `build.gradle`. Start PAVE again.

**Reported, not seen by the writers of this README:** on one Windows workstation the operator let
STS clone the repository, met the `gradle-8.9-bin.zip` error and the Maven import of the top folder,
later had the tool running from STS, and saw uploads fail with an internal error (D-275). A defect
that wrote that very error was found and corrected (D-279); the likeliest cause left is the IDE
rebuilding the classes under the running tool after a pull, now said on screen (above). Neither was
seen on that workstation. **Seen by agents, and not Windows:** the tool on the Windows Java that
Spring Tools 5.4.0 carries (25.0.4.1), run under Wine on Linux: before the rebuild about 60
uploads gave 200 or a 422 that said why; on `1a14611` a read-only `store.json` had its attribute
cleared once and the save went through, and a store file held open by another program or a full
disk got the sentence that says so. **Not seen by anyone:** Windows itself, `run.cmd` on Windows, a corporate proxy or mirror at a
real workstation, STS on a release other than 5.4.0.

## Open items

Each of these is unknown. PAVE uses an obvious placeholder rather than a plausible guess.

| Open item | What stands in for it | Where the real value goes |
| --- | --- | --- |
| The Onyx CRD service id | The variable `crdOrderSignServiceId` (`placeholder-order-sign-service` in the sample) | The vendor's environment, or Values in Configuration; CDS discovery lists what the environment offers |
| The member API's response contract | A tolerant mapping: several spellings per field, any common envelope | "Where the answer's fields are" in Configuration > Members, or `lookup.member.url` |
| The provider directory's contract | The same; none appears in any earlier session | "Provider lookup" in Configuration > Providers, or `lookup.provider.url` |
| How the lookup APIs authenticate | Nothing is assumed | "Sign in with" in the lookup's settings, or `lookup.*.headers` and `lookup.*.use-onyx-auth` |
| How the vendor's token request authenticates the client | Client authentication by a Basic header, or in the form body, chosen in the environment's Sign-in; never observed against the vendor | The environment's Sign-in. A signed JWT assertion would need `requestToken` in `client/support/OnyxTokenService.java` changed |
| The code list's header row, flag spellings, dates, encoding | The bundled placeholder and candidate headers | `codelist.file`, `codelist.columns.*`; check with the self-check |
| The PAS attachment mechanism in use | `fhir` | `onyx.pas.attachment-mode` |
| Whether the receiver is reachable from Onyx | Assumed not | Not to be changed before PAVE has a sign-in (D-270) |
| Which guide version the sandbox follows | 2.0.1 for CRD, DTR and PAS, on the operator's word (for PAS said again on 2026-11-22); never observed | `dtrCoverageExtension`, `wrapInParameter` on a next-question request, and the environment's "PAS guide" (2.0.1 or 2.2.1) |
| How Onyx signals an unknown member or provider, inactive coverage and the delegated vendor (D-033) | A rule in the client layer, stated in each mock scenario | `client/crd/OnyxCrdConventions.java`, `client/pas/OnyxPasConventions.java`, `client/dtr/OnyxDtrConventions.java` |
| Values only the payer's or provider's set-up knows (D-141) | Nothing: the request goes without them and names each | [Variables](#variables), or Values in Configuration |
| The shape of a notification from Onyx | Both published forms are read | Check the first real one |

## Limitations

What a tester meets that is not done, not verified or known to be wrong. `PROGRESS.md` lists every
open item, the worst first, with the entry that gives the reason.

- **Nothing has been run against the vendor's service or on the operator's workstation.** Every
  live check used a stand-in on a loopback port. The lookups have never met a real member API or
  provider directory.
- **No person has used the rebuilt screens**; they were built and looked at by agents, on mock and
  on live against a stand-in only.
- **The switch between mock and live has not been seen on a Windows workstation.** It was seen in the
  real STS 5.4.0 on Linux and on the Windows Java under Wine
  ([How the switch was checked](#how-the-switch-was-checked)). The mode it chooses is not remembered
  over a real start.
- **A value is taken from a sample only when files are chosen and saved** (D-308): an environment saved
  before keeps "not set" until its collection is chosen again on Edit, "Fill the form from these files"
  pressed and the environment saved. A value stored from a sample stays when the export later gives one
  under its own name, and Edit offers no way to empty it: type another value over it with "Change".
  `fhirAuthorization` is never sent.
- **The prefetch keys are read from the collection's sample, not from the service's discovery answer**
  (D-309). A service names its prefetch keys in discovery; PAVE follows the sample a call is sent from
  instead, and a key of a type PAVE does not build (an Encounter, say) is left out and said. "Compare
  with a request that works" builds PAVE's request with the named Bundles whatever the environment
  states. Not seen against the vendor's service.
- **What a sample's resource carries beyond PAVE's is named, not sent** (D-310): a `performer`, a
  Patient's address, telecom or extensions, a Coverage's relationship or class of type group. PAVE has
  no field for them, and a member's address the case holds is not written on the coverage check's
  Patient. Only the diagnosis is sent, and only where the sample's order carries one.
- **No second sign-in for Submit and Status**, which the vendor's sandbox holds a client for; never
  shown to be needed.
- **Inquiry needs a claim id**: inquiry by member and dates of service is not built.
- **DTR is not a SMART launch**; no CQL is run. Answers from the case are filled only into empty
  fields, never over the payer's or the tester's, and were not seen on live.
- **PAS**: no claim update and no `$submit-attachment`; `x12-275` sends no 275 transaction; a
  resubmission reuses the claim id.
- **Two PAS 2.0.1 value sets were not settled**: whether the X12 role code SJ PAVE sends for a
  servicing provider, and the X12 unit codes of an item's quantity, are in the X12 value sets PAS
  2.0.1 binds was not settled, because those value sets were not at hand. Both are sent when given.
- **The receiver** has never been called by a real payer, does not check its caller and keeps at
  most 200 notifications, in memory.
- **Conformance** is checked against hand-written rules of the guides' versions, not against
  their profiles' slices and invariants.
- **The code list** is a synthetic placeholder until the P32H export is supplied.
- **The ledger** refuses only the member data the same request carries; a one-word surname where a
  code belongs is kept.
- **The guardrail** does not see another DNS name for a production host, or the `onyx.*.url`
  fallbacks as production addresses.
- **Some server sentences name the old screens** ([This release](#this-release)).
- **The trail holds member data** in `run-history/trail/`; it is emptied only on purpose
  ([Audit and the trail](#audit-and-the-trail)).

- **Started with `-Dpave.classes.load-at-start=false`** (not the default, which loads every class
  at start), the top bar's environment list can fail after the IDE rebuilt the classes, with an
  internal error that asks you to report it. Stop PAVE and start it again.
- **The refusal that names each remembered address that blocks a save**, and the Forget dialog's
  words on an address above or beneath, are held by tests only, not seen on a screen
  ([A production address remembered by mistake](#a-production-address-remembered-by-mistake)).
- **"Ready to send?"** in In force now can count a lookup set up on screen as to do; "Lookups in
  force" says where it goes.
- **A replay from Audit that fails just after a pull** does not add the files-changed line: stop
  PAVE and start it again, then replay once more.
- **The Date of service and the questionnaire's dates** show in the browser's own form; the ISO
  date is sent.
- **Saved lists from a collection, in the Edit form** of a stored environment (its stored variables
  filling the placeholders) are held by tests only, not seen on a screen.
- **"Add to the saved lists" on Configuration > Collections** is held by tests only, not seen on a
  screen.
- **A full list, lists switched off, and a body with a placeholder nothing fills**, when read from a
  collection, are held by tests only, not seen on a screen.
- **A collection already stored is not read again** for the saved lists: choose it again in the
  environment form, or upload it again.
- **On Edit, Timeouts** still show the value in force as the hint of an empty box, not as a line
  with "Change" as Values and Addresses do.
- **A request chosen for an operation** in "What these files give" is the collection's mapping, shared
  by every environment, and is not checked at save against the production rules a typed address
  meets; the guardrail at sending still is.
- **Placeholders of a collection read for the saved lists** are filled from the environment and the
  collection only, never from `onyx.variables` of `application.yml`.
- **An entry the list's rules refuse** (a description over 500 characters, for one) is not cut down to
  fit: it is not added, and a line says why.

## How release 1 was checked

Agents that built none of it checked merged builds before the
candidate: the Workbench on mock and its look and words, twice (`1a14611`, then `9dbf61a` with
steps 2 to 4); the upload hunts and the Windows Java under Wine (`1a14611`); the real STS 5.4.0
under a virtual display on Linux, with a Team > Pull under the running tool (`1a14611` pulled to
`9dbf61a`); Audit, every broken upload, and every feature of release 1 on mock and on live against
a stand-in (`9dbf61a`); the production guardrail, what is sent on live, configuration on live and
secrets (`0b69049`). A second agent confirmed or refuted each finding: 86 were confirmed (31
major, 55 minor), and the integrator decided what the plan had left open (I-1 to I-36, and
three more of the integrator's own; a narrowing of the token url rule decided among them was
withdrawn before the hand-over, so that rule stays as built).
Four correctors then corrected the confirmed findings and the decisions (11:57 to 12:45 UTC), and
agents that wrote no correction looked again on the jar of `19ddf8e` (the Workbench's screens on
that of `e5d0972`): the guardrail, secrets, Audit and uploads, configuration and what is sent on
live, and the real STS 5.4.0. A second, short round corrected most of what they left, each corrector looking at its
corrections on the running tool (`correct-workbench.json`, `correct-server.json`,
`correct-config-audit.json`), among them a folder named like a file now told it is a folder, the
environment's whole name at 1024 pixels, and a refused environment save that names each field in
its panel and keeps that panel in view; what it left on purpose is said below. The Eclipse compiler STS 5.4.0 brings, run by the
integrator over every source set of `22478b8`, found 0 errors. Agents that wrote no correction then
rechecked the final build, `22478b8`: the guardrail and every feature, the Workbench on mock, the
look and words, and configuration on live against the strict stand-in. They saw every earlier
finding of their areas closed or made as decided (on the guardrail: a sandbox that shared its token
url with a production copy saved, made active and removed again stayed not signed in, and the
refusal ended with the note and the way out quoted in
[A production address remembered by mistake](#a-production-address-remembered-by-mistake)), and
found four faults: on mock with no environment active, In force now said "No environment is
active." 14 times, a regression; a form refused for a missing member ID did not name Member ID by
its label; a table cell broke a word in its middle at 1024 pixels; and the README as it stood at
`22478b8` had no This release section (this README has one). The integrator's whole build of
`22478b8` failed in one test, whose expectation the second round had not brought up to its own
change (3,561 tests, 1 failure). A third, short round corrected all of it: In force now says it
once, the panel names Member ID, table cells wrap at spaces and hyphens only, and the test expects
what the page shows. Each corrector saw its correction on the running tool; no agent that wrote no
correction has looked at them again. Last, Submit was brought back to Da Vinci PAS 2.0.1
([Submit](#submit)): its corrector compared the mock preview with that of the build before the
rebuild, walked the Workbench to step 3 on mock, and ran Submit and the inquiry live against the
strict stand-in under both guides. No second agent rechecked that change, and nothing was sent to
the vendor's service. What stays open on purpose is in [This release](#this-release) and
[Limitations](#limitations).
What was seen and decided is in `PROGRESS.md` and `DECISIONS.md`.

## How release 2 was checked

Release 2's slices were built on the release candidate of release 1 before its corrections. One
agent merged release 1, its corrections and the PAS 2.0.1 decision into them (`86d4807`), settled
each place both sides changed, and walked the jar on mock at 1440 by 900: P32H-DTR-PREPOP from a
fresh page to "The prior authorization is approved.", a reload that brought the case and step 1
back, Audit's request and answer from the trail, and Configuration's new pages. Two agents that built
none of it then checked the merge once: the mock journeys at 1440 and 1024, Submit and the inquiry
on live against the strict stand-in under both PAS guides, the lookups, the trail, suites and Audit;
and the secrets and the guardrail across the store, the trail, the ledger, the logs and every
answer. They found a completed questionnaire response missing from the trail, so a reload after
step 2 lost it (major); a Forget dialog that promised too much when another remembered address
still blocks (major); the PAS guide note naming the environment when none states a guide; the
download named `run-run-...`; and In force now telling a lookup set up on screen to take an address
from the environment. One corrector corrected all five (`3d71571`, `868b036`, `eba3e14`, `c31ae00`),
seeing the reload on the jar at both sizes; the Forget and In force now corrections are held by tests
only. **No second check of the corrections and no recheck round was run**, to keep spending low:
release 2 rests on its tests, its whole build and that one independent look. Nothing was run on
Windows or against the vendor's service. What stays open is in [This release](#this-release) and
[Limitations](#limitations); what was seen and decided is in `PROGRESS.md` and `DECISIONS.md`.

## How the switch was checked

**By its builder**, on the jar on Linux: switches both ways over the API and in Chromium at 1440 by
900 and 1024 by 768, from no profile, from `--spring.profiles.active=live`, from
`SPRING_PROFILES_ACTIVE` and from `-Dspring.profiles.active`, each landing on the mode asked; the
port, the store folder and the ledger folder kept; forty switches on one process, with 37 threads
before and 39 after; three POSTs at once making one restart; the start that falls back after a damaged scenario
file; the refusals for a running suite, a running replay and changed files, each in the dialog; a pasted token and a
production unlock dropped and said; a frozen process shown as "PAVE did not come back".

**By an independent check** (an agent that built none of it), on the jar on Linux: the control's
accessible name and keyboard order at 1440 and 1024, Escape and Cancel sending nothing, a second page
redrawn with "Now on live.", the pasted token and the production unlock dropped and never written to
the log, the ledger or the trail, live after a switch answering as live (no scenario, nothing from
mock), a call 8 seconds slow finishing with its ledger line across a switch, thirty switches on one
process, six POSTs at once making one restart, the fallback on screen, and the guards (another site,
`text/plain`, an unknown property). **In the real STS 5.4.0 on Linux** under a virtual display: started
from the Boot Dashboard, switched to live and back on screen while the Dashboard kept the process as
running, then Stop from the Dashboard ended it and freed the port, also right after a switch. **On the
Windows Java that STS carries, under Wine**: five switches, an environment saved after each, a ledger
line after the two on mock (the three on live were refused before sending for a missing address, which
writes no ledger line). It found two gaps in the tests, no fault on screen: the frame's part of the
switch was held only by the text of `app.js`, and the in-place test's check of the store's lock could
not fail.

**By the agent that closed it**: both gaps corrected, each test seen failing without what it holds
(`c17b525`, `954f796`); on the jar on Linux, a switch to live at 1440 by 900 with a second page, and back
to mock at 1024 by 768 with the process frozen for a minute ("PAVE did not come back...", then "Now on
mock." once it went on).

**Not seen by anyone**: a Windows workstation; STS on Windows; STS's Restart button right after a
switch; a start from STS's Profile field set to live followed by a switch; "PAVE is already
switching" in the dialog of a second page (seen with three and six simultaneous POSTs, held by tests).
The independent check did not try the refusal while runs are replayed; only the builder saw it.

## What this README was checked against

Brought up to the release candidate on 2026-11-22: commit `7fc32c8` of the integration branch, cut
at 11:48 UTC, its jar built from that commit without running the tests. Its stamp read
`11-22 11:56 UTC`. On that jar, on mock, "Start here" steps 5 and 6, "Your first case on mock" and
the result shapes of "Reading a result" were followed in a browser; on live against the stand-in
on a loopback port, "Setting up a service" from New environment to a coverage check answered after
a setting changed, and Access token. Every example of [Endpoints](#endpoints) was run on it in
order. Every endpoint listed is one the controllers at that head map. Every sentence quoted from a
screen was searched in `src/main/resources/static` at that head, or, where the server writes it, in
the server's code. In the last pass, on `19ddf8e` (the candidate with the first round of
corrections), every sentence this README quotes from a screen in the parts that changed was
searched again in `src/main` at that head; those parts were not walked again in a browser, and
what they say was seen is what the rechecks name. In the final touch, the sentences quoted in the
parts that changed (the token url refusal and its no-longer-stored note) were searched in
`src/main` at `22478b8`; those parts were not walked in a browser, and what they say was seen is
what the correctors' reports and the recheck of the final build name. In the pass for the code
handed over, the sentences quoted in the parts that changed (This release, Setting up a service,
Variables, Submit, Limitations, How release 1 was checked) were searched in `src/main` at
`6e1bff3`; those parts were not walked in a browser, and what they say was seen is what the
correctors' reports name. Steps 3 and 4 of "Start here" (the Gradle build and `run.sh`) were not run for this
README; the lines step 4 shows are those `run.sh` prints at that head.
Whole builds: the candidate `7fc32c8`, 3,519 tests, 0 failures, 0 errors, 2 skipped; `19ddf8e`,
after the first round of corrections, 3,551 tests, 0 failures, 0 errors, 2 skipped; `22478b8`,
after the second round, 3,561 tests, 1 failure; `f0dace9`, after the third round, 3,563 tests, 0
failures, 0 errors, 2 skipped. The code handed over, `6e1bff3`, was built whole twice and once from
a fresh clone: 3,571 tests, 0 failures, 0 errors, 2 skipped, each time.

For release 2, the parts that changed (This release, Start here's line on a second copy, Variables,
the production address remembered by mistake, Lookups and test lists, Audit and the trail, Advanced,
Endpoints, Properties, Open items, Limitations, How release 2 was checked) were brought to commit
`c31ae00` of the branch `release2`: every sentence they quote from a screen was searched in
`src/main` at that head, and every endpoint added was found in its controller there. They were not
walked in a browser for this README; what they say was seen is what the merge agent's walk, the two
checks and the corrector name. The last whole build of `c31ae00` ended BUILD SUCCESSFUL, and the
Eclipse compiler check at that head found 0 errors. The gates before the hand-over (two whole builds
from clean, a build from a fresh clone, a start and the two proofs) are run by the integrator on the
commit that carries this README, after it was written; their result is reported with the hand-over.

For the saved lists filled from a collection (D-306), the parts that changed (This release, Member data
in an uploaded file, Lookups and test lists, Endpoints, Limitations) were brought to the merge
`165bbd2`: every sentence they quote from a screen was searched in `src/main` at that head, and every
endpoint added was found in its controller there. They were not walked in a browser for this README;
what they say was seen is what the builder's report names. The gates are run by the integrator on the
commit that carries this README, after it was written; their result is reported with the hand-over.

For the switch, the parts that changed (This release, Start here, Mock and live, Endpoints, STS step 6,
Limitations, How the switch was checked) were brought to the code of `954f796`: every sentence they
quote from a screen or the server was searched in `src/main` at that head, and `POST /api/mode` was
found in `ModeController`. What they say was seen is what the builder's report, the independent check
and the closing agent's own walk name. The gates of section 0 are run by the integrator on the commit
that carries this README; their result is reported with the hand-over.

For tonight's head, the four additions joined (D-304 to D-307), the parts that changed (This release,
Setting up a service, Collections, Lookups and test lists, Limitations) were brought to the join. The
sentences they quote from a screen were searched in `src/main` at the commit of the join's last code
change, `7c14ef8`. Its jar was walked in Chromium at 1440 by 900, with placeholder files written for the
walk (an environment export and a collection with a token request, a coverage request the tool does
not recognise, and a PAS submit Bundle that states members, a provider and codes), as the tester will
walk it: started on mock; "Switch to live" in the top bar, "Now on live." on a fresh Workbench;
Configuration > Environments > New environment, both files, "Fill the form from these files"; "What
these files give" (the sign-in from the token request, each address, each value) and after it "Found
in the collection" (2 members, 1 provider, 4 procedure codes); the coverage request chosen for CRD
order-sign from the list; kind uat; saved, the result saying "CRD order-sign uses the request
"Coverage / Prior auth sample"." and "Added to the saved lists: 2 members, 1 provider, 4 procedure
codes."; made active; Edit showing every value and address in force as a line with "Change"
(Timeouts excepted, see [Limitations](#limitations)); "Test the sign-in" signed in at the stand-in on a
loopback port; on the Workbench the saved member, the saved provider and the saved code picked, a date
of service typed, and the coverage check sent with a token to the chosen request's address, answered
with HTTP 400 for `context.draftOrders.type`; draftOrders type set to Left out from its list, and the
same check answered "Documentation is required."; "Switch to mock", "Now on mock." on a fresh
Workbench. The environment form after Fill, the Workbench and Configuration > Procedure codes were also
looked at at 1024 by 768, on mock. A v2.0 collection was read for the saved lists through the running
jar's endpoint, not on a screen. Not seen: the Edit form's own "Fill the form from these files", the
vendor's files or service, Windows, STS. The gates of section 0 are run by the integrator on the
commit that carries this README; their result is reported with the hand-over.
