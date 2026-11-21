# PAVE 1.0.0

PAVE is a test tool for the Da Vinci electronic prior authorization services that Point32Health
runs through the Onyx sandbox: CRD (is prior authorization required), DTR (the payer's
questionnaire) and PAS (submit the authorization and follow its status). A tester picks a member, a
provider and a procedure code, runs a case, and sees the answer in plain words, the standard codes
behind it, and the exact request and response. Every run is written to a ledger on disk so it can
be found, exported and run again.

It is one Spring Boot application with a browser front end of five tabs (CRD, DTR, PAS, History,
Config), run on the tester's own machine. Out of the box it runs on the **mock profile**: every
answer comes from synthetic scenario files, nothing leaves the machine, and no credential or
configuration is needed.

## Before you rely on it

- **Nothing here has been run against the vendor's service.** Every result anyone has seen came
  from the mock profile or from a stand-in server on this machine. The operator says the vendor's
  sandbox follows version 2.0.1 of the guides, the version PAVE is held to for CRD, DTR and PAS;
  nobody has yet seen that sandbox answer.
- **The mock answers from files** written for this tool, from the same reading of the guides as the
  code that reads them. A pass on mock says nothing about the live service.
- **The wire view carries member data** on the live profile, and so do the lookup results. See
  [The wire view carries member data](#the-wire-view-carries-member-data).
- **Secrets can sit in plain text on disk**: an uploaded Postman environment or collection is kept
  as uploaded in `config-store/`, credentials included; a mirror password goes in
  `gradle.properties`. See [Secrets](#secrets) for keeping them out of files.
- **The ledger's salt is a secret.** `config-store/installation-salt` is what turns a member ID into
  the token the ledger keeps. With the salt and the last four characters the ledger also keeps, a
  member ID is recovered from its token at once. Never copy the salt with the ledger or its export.
- **So can member data, if you upload it.** The store keeps an uploaded file whole, values and
  saved example responses included. The upload warns when a variable's name reads as member data
  or a request carries a saved example, but it cannot judge the values: never upload an
  environment or a collection that holds a real member's data. See
  [Member data in an uploaded file](#member-data-in-an-uploaded-file).
- **The note, the tester reference and the expectation are kept in the ledger as typed.** PAVE
  refuses one that holds the member ID, name, date of birth, subscriber ID or address the same
  request carries, and cannot recognise anything else. A run sent with a request of your own from
  the CRD, DTR or PAS tab is checked against the member identifiers that body carries once its
  `{{placeholders}}` are filled from the values of the run, the active environment and
  `application.yml`, and only against those: a name or a date of birth inside such a body is not
  looked for. Do not type member data into them.
- **The last fixes were checked by nobody but their authors, and nothing checks them before
  handover.** Three rounds of independent checking each found something real; the fourth round of
  fixes (the ledger, completeness on screen, the walkthrough, the guardrail) was merged without a
  check after it. What is open at this revision is under [Limitations](#limitations), as what a
  tester meets and what to do about it; `PROGRESS.md` lists every item, the worst first.
- **DTR here is not a SMART launch**, and **the notification receiver very likely cannot be reached
  by the vendor** from a corporate workstation. See [What PAVE is not](#what-pave-is-not).
- **PAVE has no sign-in** and listens on this machine only. See
  [Who can reach this tool](#who-can-reach-this-tool).
- **STS has been seen on Linux only.** Two agents imported and started PAVE in Spring Tools 5.4.0 on
  Linux and ran some of its tests there: one on `e70a873` (after the change of D-271), on mock and
  once on live; one on `ace5914` (after the change of D-272), on mock. On `d089e65` STS also cloned
  it from GitHub itself, where its Git import wizard's own choices are the wrong branch and the other
  application, and an import without a JDK 21 was simulated (D-273). Windows and other releases were
  not tried by the writers; one Windows workstation has reported a failed import, at the end of that
  section. A profile typed in the launch dialog is written into a tracked file. See [STS (Spring Tools for Eclipse)](#sts-spring-tools-for-eclipse).
- **PAVE is not fit to be run as a shared or production service as delivered.** The operator has
  said the tool will be productionized one day, which is read here as running it that way (D-270);
  what must exist first, none of it built, is listed under
  [Before PAVE is run as a shared or production service](#before-pave-is-run-as-a-shared-or-production-service).
- Before PAVE can reach a real service, real values must be filled in: the vendor's Postman
  environment and collection (uploaded on the Config tab), and, where used, `lookup.member.url`,
  `lookup.provider.url`, `codelist.file`, `onyx.auth.*` and `onyx.subscription.callback-url` in
  `src/main/resources/application.yml` or on the command line. Every one is listed under
  [Open items](#open-items).
- The screens, the tabs together and the gates that close the build are described as they are on
  the revision named at the end, with what was not checked. Read
  "[What this README was checked against](#what-this-readme-was-checked-against)" before relying
  on a sentence here.

## First run checklist

Six steps from nothing to a working screen on the mock profile. Each shows the exact command and
what correct output looks like. The output shown is what these commands printed when this README
was checked (see the last section for where and on which revision).

**1. Check that Java 21 is installed.**

```
java -version
```

Correct output starts with `openjdk version "21` or `java version "21`, for example:

```
openjdk version "21.0.13.0.3" 2026-10-24
OpenJDK Runtime Environment (build 21.0.13.0.3+1-1-24.04.1-Ubuntu)
OpenJDK 64-Bit Server VM (build 21.0.13.0.3+1-1-24.04.1-Ubuntu, mixed mode, sharing)
```

Anything older than 21 will not build or run PAVE: install a JDK 21 and set `JAVA_HOME` to it. That
is what this README was checked on. A newer JDK alone builds it too, since D-274: where Gradle finds
no JDK 21, the build compiles for Java 21 with the Java that runs Gradle and says so in one line,
"Gradle found no JDK 21, so PAVE is compiled for Java 21 with the Java that runs Gradle: ...". Seen
on Linux with only a Java 25 visible (a simulation: the machine's JDK 21 hidden): the whole build
passed with its 2872 tests, and the jar it made ran on that Java 25 and passed both proofs. A checkout
from before that change stops there with "Cannot find a Java installation on your machine ...
matching: {languageVersion=21, ...}. Toolchain download repositories have not been configured."
(D-273).

**2. Get the code and go into the project directory.**

```
git clone --branch epa-pave https://github.com/thehiddenbrain/Interop.git
cd Interop/epa-pave
ls run.sh
```

Correct output of the last command is `run.sh`.

PAVE lives in the `epa-pave/` directory. The application at the root of the repository is a
different project; leave it alone. Every command below is run from `epa-pave/`. Cloning from inside
STS instead? Follow (b) of step 2 of [STS (Spring Tools for Eclipse)](#sts-spring-tools-for-eclipse):
two of its Git wizard's choices are wrong for this project.

**3. Build and run the tests.**

```
./gradlew clean build
```

On Windows: `gradlew.bat clean build`. The first build downloads Gradle 9.5.0 and the
dependencies, so it needs to reach `services.gradle.org` and Maven Central, or the internal
mirrors described under [Running on a corporate workstation](#running-on-a-corporate-workstation).
It runs about 2,872 tests and takes a few minutes. Correct output ends with:

```
> Task :verifyParametersFlag
> Task :check
> Task :build

BUILD SUCCESSFUL in 3m 39s
12 actionable tasks: 9 executed, 3 from cache
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.5.0/userguide/configuration_cache_enabling.html
```

The time and the task counts differ from machine to machine. `BUILD SUCCESSFUL` is what matters.
Two tests are skipped when the build runs as root (see [Limitations](#limitations)).

Working in STS? Run this build once before the import, then read
[STS (Spring Tools for Eclipse)](#sts-spring-tools-for-eclipse) for the import and how to start the
tool from there.

**4. Start the tool.**

```
./run.sh
```

The script checks for Java 21, builds the jar if it is not there yet, and starts PAVE on the mock
profile on port 8057. Correct output includes these lines, and then the terminal stays busy while
the tool runs:

```
Starting PAVE 1.0.0 on http://localhost:8057/ (mock profile). Stop it with Ctrl+C.
...
The following 1 profile is active: "mock"
...
The bundled placeholder code list is in use. Every row in it is synthetic. Set codelist.file to the real export.
...
Tomcat started on port 8057 (http) with context path '/'
Started PaveApplication in 2.674 seconds (process running for 3.151)
```

Started from inside the clone, it also prints two warnings, one for `config-store` and one for
`run-history`: "... lies inside the git working tree ... Check that the tree's .gitignore covers
it, or start the tool from a directory outside the tree." Both folders are listed in the
project's `.gitignore`, so in a clone of this branch the warnings need no action.

If it says `Port 8057 was already in use`, something else is on that port: start with
`./run.sh mock --server.port=8091` and use 8091 in place of 8057 below.

**On Windows**, run `run.cmd`, or `run.cmd mock "--server.port=8091"` for another port. **`run.cmd`
has never been run**: no Windows machine was available, so it was only read. If it stops with an
error, or does not print the lines above, start PAVE without it, from `epa-pave\`:

```
gradlew.bat bootJar
java -jar build\libs\epa-pave-1.0.0.jar --spring.profiles.active=mock
```

The same two commands, with `./gradlew` and `build/libs/...`, start PAVE on Linux as `run.sh`
does. Started through `run.cmd`, Ctrl+C is followed by the prompt `Terminate batch job (Y/N)?`;
answer `Y`.

**From STS**, start it as [STS (Spring Tools for Eclipse)](#sts-spring-tools-for-eclipse) says, in
place of `run.sh` or `run.cmd`; that was seen on Linux with Spring Tools 5.4.0, not on Windows.

**5. Ask the tool what it is, from a second terminal.**

```
curl -s localhost:8057/api/mode
```

Correct output (the `epoch` number is the configuration epoch: it differs from one start to the
next and changes when the configuration changes, but two calls in a row answer the same number):

```
{"name":"PAVE","version":"1.0.0","port":8057,"mode":"mock","target":"onyx","environment":{"active":false},"epoch":1795177156113,"writeAccess":{"production":false,"writesAllowed":true,"floorAllows":false,"reason":"No environment is active. On the mock profile nothing is sent, so writes are allowed."},"bindAddress":"127.0.0.1","bindNote":"This tool listens on this machine only (127.0.0.1), so nothing on another machine, a vendor's service included, can call its notification receiver. To let a service call it, set server.address to an address that service can reach, and read the README section on the receiver first."}
```

`"mode":"mock"` is the line to check: nothing you do now reaches a real service. `bindNote` says
who can reach the tool (see [Who can reach this tool](#who-can-reach-this-tool)). Where it says to
set `server.address`, do not do so before PAVE has a sign-in (D-270): the tool's own text was not
changed when that was decided.

**6. Open the tool in a browser.**

Open `http://localhost:8057/`.

Correct output is the PAVE page: `PAVE 1.0.0` at the top left; at the top right `Profile mock`,
`Target onyx`, `Environment none active` and `Ledger 0 runs today (UTC)`; under it a hatched strip
that begins "Mock profile. Scenario files answer in place of the service."; and the five tabs CRD,
DTR, PAS, History and Config, with CRD open. The right half of the CRD tab says "Nothing has been
sent from this tab yet." and lists the member IDs that select each mock scenario. Go on with
[Using the screens](#using-the-screens).

To stop the tool, press Ctrl+C in the terminal where it runs.

## What PAVE is, and what it is not

PAVE drives the three Da Vinci operations a provider system would call, against whichever
environment you point it at, and shows you what went over the wire and what it made of it.

- **CRD**: a CDS Hooks `order-sign` or `order-select` call, and discovery of the CDS services an
  environment offers. The answer is read from the standard coverage information codes (`covered`,
  `pa-needed`, `doc-needed`, `doc-purpose`, `info-needed`, `satisfied-pa-id` and the delegated
  vendor), not from card wording. CRD never denies on clinical grounds and nothing in PAVE reads a
  CRD answer as a denial. A CRD request you paste can be compared, element by element, with the
  one PAVE would build for the same case (added at the operator's request, D-118, D-119).
- **DTR**: the payer's questionnaire, fetched with `$questionnaire-package`, answered step by step
  through `next-question` for an adaptive questionnaire, and completed into a
  QuestionnaireResponse that PAS carries.
- **PAS**: `Claim/$submit` and `Claim/$inquire`, plus subscriptions (create, read, list, update,
  delete) and a receiver for the notifications a payer sends back. PAS can carry a denial and PAVE
  shows it as one. There is no claim update and no `$submit-attachment` (D-161), so the guide's
  route after "attachment required" is not built. In the attachment mode `x12-275` PAVE names the
  document on the claim and sends no 275 transaction: that is another channel, and nothing here
  sends it.
- Not built at all: `$expand` and `$log-questionnaire-errors`, and no CQL is run.
- On every protocol a tester can send a request body of their own in place of the one PAVE builds,
  and any request of the uploaded collection can be sent as it is written (Config tab).
- Member lookup by member ID, provider lookup by NPI, a procedure code picker backed by the P32H
  code list, a ledger of every run, replay, chains of CRD, DTR and PAS for one case, and suites of
  many cases from one file.

Every result shows two things side by side, and they are different things: the **authorization
outcome** (what the payer said, for example "The prior authorization is denied.") and the **test
result** (whether that is what you said you expected: Passed, Failed, or Unverified when you
declared nothing). A case you expect to be denied that comes back denied is a denial **and** a
pass.

### What PAVE is not

- Not provider facing, not a replacement for the Availity portal, not a load test tool, and not a
  clinical decision system.
- **DTR here is the server-side operations, not a SMART launch.** There is no SMART authorization
  server, no launch handshake and no EHR embedding. When you exercise DTR in PAVE you exercise the
  `$questionnaire-package` and `next-question` operations behind a provider-facing SMART launch,
  not the launch itself, and you type each answer yourself: no patient record pre-populates
  anything, and no CQL is run. Every DTR result says so. Do not report a DTR run here as a test of
  the SMART launch.
- **PAVE is held to version 2.0.1 of CRD, DTR and PAS**, on the operator's word (D-136), and the
  operator says the vendor's sandbox follows 2.0.1 as well (D-269); that has not been observed
  against the sandbox. The request shapes and the reading of
  responses were first written without the guides (D-008, D-130), then compared with 2.0.1 by
  auditors who wrote none of the code, and brought in line (D-140 to D-166; what each comparison
  found and what was done is in `PROGRESS.md`). What that means on screen:
  - every conformance finding names the guide and the version it holds the response to, for
    example "CRD 2.0.1";
  - content that a later version defines (the 2.2.0 ballot) is read and named with its version,
    never called wrong;
  - a value nobody supplied is never invented: the request goes without it, and the result says
    what was left out and which variable would supply it (finding `REQUEST_INCOMPLETE`, D-141). A
    service that refuses such a request is not at fault. See
    [Variables that fill a request](#variables-that-fill-a-request).
  
  Limits of that comparison: US Core 3.1.1, SDC 3.0.0, HRex, the subscriptions backport, FHIR R4
  core and the X12 code lists were not available, so their rules are not checked; the 2.0.1
  examples were not compiled; requests are checked against hand-written 2.0.1 rules that do not
  test slices or invariants beyond the few named in `PROGRESS.md`. A request PAVE builds can still
  be refused by a conformant service for reasons that are PAVE's.

### What a tester must not conclude

- **A result on the mock profile says nothing about Onyx.** The mock answers from scenario files
  written for this tool. They were written from the same understanding of the guides as the code
  that reads them, so a mock run can pass where the real service would disagree.
- **A pass is only as good as the expectation.** "Unverified" means you declared no expectation; it
  is not a failure and not a pass.
- **"No determination" is not "not required".** CRD scenario 14 (`P32H-EMPTY`) returns no answer at
  all and PAVE says "The service answered but returned no determination." Scenario 2
  (`P32H-NO-AUTH`) returns `pa-needed=no-auth` and PAVE says "Prior authorization is not
  required." They are different outcomes (`NO_DETERMINATION` and `NO_AUTH_REQUIRED`), drawn in
  different colours, with different codes in the ledger.
- **A code list finding on a synthetic list is about synthetic rows.** The bundled placeholder and
  the shipped sample list both say of themselves that they are synthetic. On mock every finding
  that rests on such a list says so; on live PAVE does not compare with it at all. See
  [The procedure code list](#the-procedure-code-list).

### The notification receiver will very likely not be reachable

PAS subscriptions work by the payer calling PAVE back at `POST /api/pas/notifications`. That only
happens if the payer's servers can open a connection to the machine PAVE runs on. On a corporate
workstation they very likely cannot: the machine sits behind the corporate network, and PAVE
itself listens on this machine only by default (see [Who can reach this tool](#who-can-reach-this-tool)).
With the defaults, **no vendor can reach the receiver at all**.

What to do then:

- Use **polling**: submit, then inquire (`Send inquiry` on the PAS tab, or
  `POST /api/pas/inquire`). Polling needs nothing from the network beyond the outbound call and
  works on its own.
- Subscription create, read, update and delete still work, because those are calls PAVE makes.
  Only the notifications coming back are lost.
- On mock, `POST /api/pas/notifications/sample` (the Notifications call on the PAS tab) delivers a
  sample notification through the same code a real one reaches, so the receiver's behaviour can be
  seen without the network.
- To receive real notifications, someone must give the machine an address the payer can reach and
  open PAVE to it. That is not to be done before PAVE has a sign-in (D-270): read
  [Who can reach this tool](#who-can-reach-this-tool), and ask the owner of the installation
  first.

The receiver does not authenticate its caller and has never been called over a socket by a real
payer (see [Limitations](#limitations)).

## Mock and live

| | Mock (the default) | Live |
| --- | --- | --- |
| Start with | `./run.sh` | `./run.sh live` |
| Answers come from | scenario files in `src/main/resources/scenarios/` | the services in the active Postman environment |
| Members and providers | synthetic fixtures in `src/main/resources/fixtures/` | the member API and the provider directory, when configured |
| Needs | nothing | an uploaded environment, its credentials, network access |
| Ledger records the member ID | only a `P32H-` sentinel or an `S` and nine or ten digits; anything else as on live | no, a salted token and the last four characters, unless configured |
| Scenario picker | yes | no: a forced outcome against a real service would be a result that did not come from it |

**Every scenario is synthetic.** Sentinel member IDs select the mock scenario: `P32H-PA-REQ`
answers "prior authorization required", `P32H-NO-AUTH` answers "not required", `P32H-EMPTY`
answers with no determination, and so on; a sentinel means the same case in every protocol
(D-154). The CRD, DTR and PAS tabs list them, each with a "Use this member ID" link, and
`GET /api/crd/scenarios`, `GET /api/dtr/scenarios` and `GET /api/pas/scenarios` serve them with
the outcome each is meant to produce (14 CRD, 15 DTR and 21 PAS scenario files). A run can also
name a scenario (the Mock scenario field, or `scenarioId`). Mock member fixtures use `P32H-...`
sentinels and `S123456789`-style placeholders; the placeholder NPI is `1234567893`. NPI
`9999999995` selects "provider not found". A damaged scenario file stops the start on mock and
names the file (D-153).

How a scenario is chosen is one rule for the three protocols, and it is all in the scenario files:

- In order: the scenario you chose; the member ID on the form; the NPI on the form; a member ID
  found as a whole value in a request body you pasted; otherwise the default, the file with the
  lowest number. The result says which rule chose it, for example "found in the request body". In
  a pasted body a member ID is looked for before an NPI, as on the form: a sentinel NPI found
  there as a whole value (`9999999995` as a Practitioner identifier) selects its scenario, and the
  result says "selected by the sentinel NPI 9999999995 found in the request body".
- A DTR or PAS file lists, under `crdSentinelMemberIds`, the CRD sentinels it answers for the same
  case (`P32H-NOMEM` selects PAS scenario `pas-15-member-not-found`, for example). The scenario
  list shows them. No two files of one protocol may claim one sentinel: the start stops and names
  both files.
- On mock, CRD answers every DTR sentinel with "documentation required" (the file that does so,
  `crd-04-doc-dtr`, carries `answersDtrSentinels`), and names the questionnaire that the DTR
  scenario of that sentinel holds, so `Continue in DTR` reaches what the DTR scenario's label says,
  whether the member ID selected `crd-04-doc-dtr` or it was chosen in the Mock scenario field. The
  result note then says that the member ID is the sentinel of that DTR scenario.
- The scenario list carries notes, on the list and on the scenario they are about: which file is
  the default, an `expect` block whose HTTP status differs from the one its file answers with, and
  a file in a scenario folder that is not read (another extension, or a subfolder). It also names
  a `crdSentinelMemberIds` entry that is no CRD file's sentinel, and, for a folder of your own,
  each file whose description speaks of a sentinel of another protocol while the file lacks the
  key that carries the mapping, and DTR files with no CRD file marked `answersDtrSentinels`. On
  mock, each of these except the default and the `expect` notes is also named in one warning per
  protocol in the log at start. Not
  checked: an `expect` outcome that contradicts the file's own body.
- **A scenario folder of your own** (`app.scenario-dir`) copied from the bundled files before the
  cross-protocol keys (`crdSentinelMemberIds`, `answersDtrSentinels`) existed has its mappings
  between protocols read from nothing: they are not guessed back from the descriptions. Add
  `crdSentinelMemberIds` to the DTR and PAS files that answer a CRD sentinel, and
  `"answersDtrSentinels": true` to the CRD file that answers DTR sentinels (compare with the
  bundled files). Until then the scenario lists and the start log name each file that needs it.

**Switching** is a restart: stop the tool and start it with `./run.sh live` (or `./run.sh` for
mock). The mode is shown on every tab, at the top right (`Profile mock` or `Profile live`), and by
`GET /api/mode`. The application refuses to start when `epa.mode` and the Spring profile disagree,
so the screen can never say mock while the wiring is live. Both profiles use the same
`config-store/` and `run-history/` directories, and every ledger line records the profile it ran
on.

Starting on live with nothing configured was checked: the tool starts, `GET /api/mode` says
`"mode":"live"` and that writes are refused because no environment is active, a member lookup
answers `DISABLED`, and the CRD, DTR and PAS tabs each hold back their form and say "This tab
cannot send yet", naming what is missing, with a link to the Config tab. **Nothing has been run
against Onyx or any real service.**

On mock, a short artificial delay is added to each answer so that the screens behave as they will
on live. `app.mock-latency-enabled: false` removes it.

## Using the screens

Open `http://localhost:8057/`. This section takes you through the page as it is on the revision
named at the end. Words in quotation marks are what the screen says; a button's name is given as
it reads. The screens were last walked in a browser on an earlier revision (see the last
section); a sentence the last fixes changed is quoted here from the source, so a line on your
screen may read a little differently from one quoted here; the buttons, the order and the colours
did not change with them.

### What every tab shows

- **The masthead.** `PAVE 1.0.0` on the left. On the right: the profile (`mock` or `live`), the
  target, the active environment with its kind, and the ledger: how many runs were recorded today,
  as a link to History. The browser tab title carries the same facts, for example
  `PAVE 1.0.0 | no environment | mock`.
- **The strip under it** says where answers come from. On mock it is hatched and begins "Mock
  profile. Scenario files answer in place of the service." A production environment puts a solid
  purple banner on every tab that says whether writes are locked, or, once unlocked, "Writes
  UNLOCKED for this browser session". A UAT environment puts a purple outlined banner on every
  tab. A sandbox has none.
- **The ledger indicator** turns into a heavy black frame when runs are not being recorded, for
  example "Ledger tracking is OFF; latest run here NOT recorded" or "cannot write, runs NOT
  recorded". Stop and read it before the next run: a run that was not recorded cannot be found,
  exported or replayed.
- **Colour key**, at the right under the masthead, says what every colour and mark means. In
  short: the verdict strip's colour is what the service said (solid cyan for nothing further
  required, solid orange for something the provider must do, an orange frame for a decision that
  is not final, solid grey for a no, a dashed frame for no answer, cream for a member or provider
  the service does not know, a heavy black frame for an environment that failed, a purple frame
  for a request the guardrail refused). A dotted frame in the colour of what a step means, never
  filled, marks a step done inside this tool that nobody answered, such as completing a
  questionnaire response ("done in this tool, nobody answered"). The test result uses green for
  Passed and magenta for Failed and nothing else. Cream on a form field means it was typed by hand
  or taken from a pasted request rather than given by a lookup.
- **The chain bar.** When a case runs as a chain, a bar under the tabs shows its three stages
  (Coverage requirements (CRD), Documentation (DTR), Prior authorization request (PAS)), what each
  answered, the next step, and the buttons `Open the whole journey` and `Start a new case`.

### The case: the left side of CRD, DTR and PAS

The three protocol tabs share one case, so a member, a provider and the codes typed on one tab are
on the other two.

- **Member.** Type the member ID and press Enter or `Look up member`. Found details fill the fields
  below, each marked "from lookup", and stay editable; a field you change is marked as typed by
  hand. A member ID typed with spaces is sent without them, and both forms are kept. "Not found"
  is a finding about test data, not a fault, and is worded differently from a lookup that failed.
- **Ordering provider.** The NPI, with `Look up provider`. The check digit is checked first.
- **Procedure codes.** Type a code or words of its description; the arrows choose from the code
  list and Enter adds. Each code shows what the list says about it: `PA`, `No PA`, a vendor name,
  or nothing when the list does not say. While a synthetic list is in use the box says
  "Placeholder code list in use: its tags are synthetic, not P32H's." A code already in the case
  is not added twice, and a chip for text that is not on the list and does not have the shape of a
  CPT or HCPCS code says so; it is still sent as you chose it. Text left in the box and not added
  stops a call that sends the case: "Nothing was sent: "MRI" was typed in the procedure code box and
  not added to the case." It does not stop the subscription calls of the PAS tab, a call made while
  the case is hidden, or a request of your own. It does still stop `Discover the CDS services this
  environment offers` on the CRD tab, although discovery sends nothing of the case (open at this
  revision): clear the box, then discover.
- **Run as part of a chain** gives the case one id in the ledger across CRD, DTR and PAS.
- **What to send.** `Built by this tool from the form` (the default) or `A request of my own`: a
  body you paste, sent exactly as written, with `{{placeholders}}` filled in. The tab says what the
  ledger keeps of such a run: no member ID, provider or line of business, and no body, so it
  cannot be replayed from the ledger alone.
- Below the case, each tab has its own fields: the date of service, a tester reference, a note,
  on mock a `Mock scenario` chooser ("The member ID decides" by default), and `Expected outcome
  (optional)`. Leave the expectation empty and the run is Unverified; choose one and the run is
  Passed or Failed. `Assert standard codes as well` adds expectations on single codes.
- **The note, the tester reference and the expectation go into the ledger as typed.** A run whose
  note, tester reference or expectation holds the member ID, the first or last name, the date of
  birth, the subscriber ID or the address that the same request carries is refused before anything
  is sent: the field is marked and the server's sentence is shown under it, for example "The note
  holds this run's member ID. The ledger keeps it as written and may not hold member data, so leave
  it out. Nothing was sent." The sentence names the kind of value, never the value. Member data the
  request does not carry (somebody else's name, say) cannot be recognised and is written as typed:
  do not type member data into these fields. Each of them carries the hint "The ledger keeps this
  as typed. Do not type member data into it." With `A request of my own` on the CRD, DTR or PAS tab,
  these fields are checked against the member identifiers the body carries (a Patient or Coverage
  identifier, a subscriber ID, a Patient id, `context.patientId` or a `Patient/<id>` reference), and
  refused with "The note holds a member ID the request body carries. ..."; a name or a date of
  birth in such a body is not looked for. The body is read with its `{{placeholders}}` filled from
  the values typed for the run, the active environment and `application.yml`, so an ID that a
  placeholder brings in is seen; a value only a collection's variables give is not used for this,
  and when any placeholder has no value the body is read as typed. A request sent as written
  from the Config tab is checked against the member and subscriber identifiers its body carries.
- `Show the request` (on PAS also `Show the inquiry` and the like) shows the request as it would
  be sent, the url it resolves to and what it leaves out, and sends and records nothing.

On mock, before anything is uploaded, the right side of each protocol tab says what to do and lists
the member IDs that select each scenario, each with `Use this member ID`. On live with nothing
configured, each protocol tab holds back its form, says "This tab cannot send yet" with what is
missing, and links to the Config tab; once an environment and a collection are active the form
comes back, with no reload.

### Reading a result: the right side, the same on every tab

From the top:

1. **The verdict strip**: one sentence in plain words, in the colour of the outcome, for example
   "Documentation is required." Beneath it, the lines that support it, and at the foot `mock` when
   a scenario answered (or "done in this tool, nobody answered" for a step that sent nothing), and
   the outcome code (`Outcome code DOCUMENTATION_REQUIRED`).
2. **Test result**, beside the strip: `Passed`, `Failed` or `Unverified`, with `Expected` (what you
   declared, or "nothing declared") and `Happened`. This is the answer to "did it match", and it is
   a different fact from the outcome.
3. **Next step**: what to do now, in one line, with a button where the next step is on another
   tab, for example `Continue in DTR` after a CRD answer that asks for documentation, `Continue in
   PAS` after a completed questionnaire, or the PAS tab's own `Send inquiry` after a pended
   decision. "The flow ends here. Record this result." means there is nothing further to send.
   When the service returned no determination the next step says to record the run and report it:
   "Next: record run run-... as "no determination returned" and report it to the people who look
   after the service." CRD says the same for an answer of the wrong kind, an OperationOutcome
   answered with HTTP 200 among them. PAS, subscriptions and a notification give the same two
   lines for what they cannot act on: "no decision returned", "decision code not recognised" and
   "items decided differently" on PAS, "response not recognised" for a subscription call, "type
   not recognised" for a notification ("this run" in place of the run id while it is not known).
   Still open at this revision: on CRD, documentation asked for in a way the tool cannot act on,
   no authorization answer, an unrecognised code or mixed answers, on DTR an answer that asks
   nothing more and is not complete, or a status the tool does not act on, and a request sent as
   written still say only that the tool cannot say what should happen next. Record such a run by its run id and report it, as for no determination.
4. **Ledger**: "Recorded." with today's count, or, in a heavy frame, "NOT recorded" with the reason.
5. **Response time**, flagged `slow` above the threshold.
6. **Run**: the run id (`Copy run id`), the operation, the environment and profile, the chain, the
   tester reference and the note.
7. **Findings**: things a person should look at, grouped by what they are about (the request this
   tool sent, the response held to the guide, the code list beside the service, and so on). A
   finding is not a failure of the call. `REQUEST_INCOMPLETE`, for example, lists what the request
   went out without because nobody supplied it, and the variable that would supply each.
8. **Determination**: the standard codes returned, each with its meaning and the element it came
   from.
9. **The response detail**: cards and coverage information for CRD, the questionnaire for DTR, the
   decision for each item for PAS.
10. **Returned but not interpreted**: everything the service sent that the tool did not use, so
    nothing is dropped in silence. "not returned", "null" and "empty" are different statements,
    in this list and in the designed views above it, and none is shown as a blank. A key repeated
    inside one object is listed with every value, in order ("(value 1 of 2)"), here and on a
    request sent as written, the two lookups and CRD discovery; on CRD, DTR, PAS and subscriptions
    a finding also names it ("... is sent 2 times in one object").
11. **The wire view**, collapsed: the request and the response as they went over the wire. On mock
    the request reads "not sent". See [The wire view carries member data](#the-wire-view-carries-member-data).

A result made before the configuration changed is marked as such in a heavy frame. A refusal by
the server (a field it refused, a 409, a blocked write) is shown with its own sentence, beside the
field or the button it is about. A fault or a refusal at the end of a result is shown in an error
panel headed by the server's own heading, which says what kind it is, for example "Refused by the
production guardrail", "The service rejected the request" or, on mock, "A timeout played by a mock
scenario, not a fault in the environment". On mock, every fault a scenario plays (a 500, a 400, an
unreachable service, an empty or unreadable body) is headed as played by a mock scenario, and its
action begins "Nothing left this machine: a mock scenario played this answer to show how this tool
reads it." The DTR package that `P32H-DTR-MALFORMED` plays is headed "An unreadable answer played
by a mock scenario, not a fault in the environment" in the same way. Open at this revision: the
next step of a played 500 or unreachable service still reads "Next: check the environment, then
send again.", and that of the malformed DTR package "Next: read the problems listed with this
result and report them as a defect in the service.", neither of which fits a played answer; a
played timeout's next step is already worded as played. On mock, believe the heading and the
action, not that next step. A refused form is headed "A problem with what was submitted", and its
action says "Correct the marked fields and send again."

### The CRD tab

Enter the case, choose the hook (`order-sign` or `order-select`) and, if you like, `Send
prefetch`, then `Send CRD request`. `Discover the CDS services this environment offers` asks the
environment what it offers (CDS Hooks discovery).

`A request of my own` also offers `Compare with this tool's request`: it builds PAVE's own request
for the same case and lists where the two differ, with what each difference means; nothing is sent
and nothing is recorded. `Fill the form from this request` takes the member, provider and codes
from the pasted request; where it disagrees with a lookup, the lookup line says so, and `Put your
request's values in these fields` takes the pasted values, marked "from the pasted request". When
a collection is mapped, `The vendor's sample` loads the vendor's sample body to compare or edit.

### The DTR tab

What CRD handed on ("Handed on from the CRD result": the codes, the questionnaire it named, the
coverage assertion, the order) is listed at the top of the DTR fields; without it, the package is
asked for by the codes of the case or by a questionnaire url you type. Choose `Fetch the
questionnaire package`. The questions appear on the right under "Answering the questionnaire",
each with its link id, its type and whether it is required, and "In the response:" for what the
response holds so far. Answers the payer pre-populated are marked as such; one you change is
marked as overridden.

- For a static questionnaire, answer and choose `Complete the response`. It sends nothing to the
  payer: the response is put together inside PAVE, and the result says whether it is complete or
  which required questions are unanswered.
- For an adaptive questionnaire, each step sends your answers and gets the next question, with the
  question count, the answers so far and the completion status at each step.
- When the response is complete, the next step offers `Continue in PAS`. Change an answer after
  that and the button is withdrawn until the response is completed again, so PAS never receives
  answers you have since changed.

### The PAS tab

Choose the call at the top: `Send PAS request`, `Send inquiry`, `Subscriptions` or
`Notifications`.

- **Send PAS request.** The case, a claim identifier (blank: PAVE creates one, or uses the one handed on),
  diagnosis codes, attachments, then `Send PAS request`. When DTR handed on a completed response,
  the tab says "The answers completed in DTR will travel with this request.", and `Show the
  request` names the QuestionnaireResponse in the bundle. The result lists the answers inside the
  request under "The questionnaire answers in the request built, which was not sent" (on mock),
  with the values you typed, and
  shows the decision for each item: one row per item asked about, read from the review action
  code only, never from the payer's wording.
- **Send inquiry.** `Send inquiry` and `Show the inquiry`, matched by the claim identifier.
- **Subscriptions.** `Create subscription`, `List subscriptions`, `Read subscription`, `Update
  subscription`, `Delete subscription`, each with a preview. Create, update and delete are writes.
  Text left in the procedure code box does not stop them, since a subscription carries no code.
- **Notifications.** What the receiver has received, correlated to runs, `Ask the receiver now`,
  and, on mock, `Deliver a sample notification`. A sample notification is recorded as answered by
  a mock scenario (`answeredBy` `SCENARIO`, observed on the running tool); so, on mock, is one
  that somebody posts to the receiver by hand, and ledger lines that the revisions before the fix
  wrote with `SERVICE` beside a scenario id are read as written (open at this revision). Read [The
  notification receiver will very likely not be
  reachable](#the-notification-receiver-will-very-likely-not-be-reachable) first.

On a production environment, a submit and every subscription write are refused until writes are
unlocked on the Config tab, and the result says "Nothing was sent." with the reason in a purple
frame. See [The production guardrail](#the-production-guardrail).

### The History tab

- **Runs**: the ledger, newest first, filtered by day, protocol, operation, outcome, result and
  tester reference (as typed with the run).
  Above the list: whether tracking is on, how many runs were recorded today and where the file is.
  `Export runs as CSV` downloads the runs of the chosen days, protocol and result; the tab says
  which filters the export does not take. Open a run to read it from the ledger in four
  parts, "What was expected", "What happened", "Did it match" and "What next", then what the line
  recorded and how it ties to other runs: the run it follows and, under "Followed by", the runs
  that name it, so a PAS submission sent from DTR can be traced back to the questionnaire response
  even outside a chain. A run that sent nothing shows its time as "not measured". From there:
  `Save the note`, `Replay this run`, and `Load this case into the form`, which puts its member,
  provider and codes back on the three protocol tabs. Where two protocols word an outcome
  differently, the Outcome filter gives each wording with the protocols that use it. A coverage
  code that came as null, empty or with no value reads `pa-needed=(null)`, `(empty)` or `(no
  value)` under "Codes returned". Open at this revision: a PAS run with no decision is shown in
  the run view, under the "Next action" filter and in the chain view with the general label "Look
  at the response detail. The tool cannot say what should happen next.", where the PAS tab itself
  gives the two lines of next step; and History, a suite row and a chain say nothing of the fields
  the tool did not interpret. Open the run's own tab, or replay it, to see both.
- **Chains**: one row for each case followed through CRD, DTR and PAS. Open one to see its whole
  journey on one screen: each stage with its runs, and for a stage that did not run, the reason.
  Its total says how many runs were measured: "3 runs, 2 of them measured, taking ... in all",
  "3 runs, none of them measured" or "1 run, not measured", since a step done inside this tool
  is not timed.
  The list and the view both draw a step nobody answered with the dotted mark.
- **Suite runs**: choose a suite file; PAVE says what it would run and what is wrong with the file
  before anything is sent. `Run suite` runs it, and the result counts the cases by result, for
  example "Finished: 6 cases. 4 passed, 1 failed, 1 unverified." `Download a suite file template
  (CSV)` gives the columns.

Days are UTC days, as the ledger files its runs; the tab says so beside each date field.

### The Config tab

Five parts, chosen at the top:

- **In force now**: where the tool is pointed (profile, target, environment, production or not,
  whether writes are blocked), where uploaded files are kept, and for every operation the address
  a run would go to, where it comes from and whether it can be sent now. On a production
  environment this is where writes are unlocked for this browser session: type the active
  environment's name exactly and choose `Unlock production writes for this browser session`. With nothing
  uploaded it walks you through the three steps: upload the environment, make it active, upload
  the collection.
- **Environments**: `Upload an environment`, saying whether it is `sandbox`, `uat` or `prod`
  (the tool never guesses), make one active, remove one. What the server warns about a file is
  shown under the upload as "The tool has one warning about this file" (or the number), among them
  a variable whose name reads as member data, a secret typed into the file, and an address the tool
  cannot read, has an encoded separator in its path, or shares a server with a production
  environment. The address warnings stay on the environment in the list and on In force now.
- **Collections and mapping**: `Upload a collection`, see which request each operation uses and how
  it was mapped, change a mapping (`Choose a request`), remove a collection. The upload warns, as
  for an environment, about a credential typed into the collection and about requests that carry a
  saved example response, which can hold the member data of whoever it was saved for.
- **Send as written**: any request of the collection, sent with its own method, url,
  headers and body (`Send as written`, `Show the request`). A url part in single
  braces, such as `{claimId}`, gets a field of its own under "Parts of the url".
- **Check this installation**: the ledger, the code list self-check, the tool and where it is
  pointed, in one place, with `Copy as text for support`. It holds no member data and no secret.

### The journey CRD to DTR to PAS, once

On mock, with nothing uploaded. Each step was walked on the running tool; what the screen said is
quoted.

1. Open the CRD tab. Type `P32H-DOC-DTR` as the member ID and press Enter: the member fields fill,
   each marked "from lookup". Type `1234567893` as the NPI and press Enter. In the procedure codes
   type `E0601`, press the down arrow and Enter. Tick `Run as part of a chain`.
2. Choose `Send CRD request`. The verdict strip, in orange, says "Documentation is required.";
   the test result says `Unverified` (nothing was declared); the next step says "Next: complete
   the payer's documentation in DTR." with the button `Continue in DTR`. The chain bar appears.
3. Choose `Continue in DTR`. The DTR tab opens with the same member, provider and code, and lists
   what was handed on from the CRD result, among it the questionnaire CRD named.
4. Choose `Fetch the questionnaire package`. The strip says "The questionnaire package was loaded
   with one questionnaire." Four questions appear, three of them required.
5. Answer them: Yes to the first, `17` for the Epworth score, a symptom, and a note if you like.
   Choose `Complete the response`. The strip says "The questionnaire response is complete." in a
   dotted frame, because nothing was sent and nobody answered, and the next step says "Next:
   choose Continue in PAS, then Send PAS request. The completed questionnaire response goes with
   it." with the button `Continue in PAS`.
6. Choose `Continue in PAS`. The `Send PAS request` call is open with the same case, and the tab says the
   answers completed in DTR will travel with the request. `Show the request` shows the bundle: its
   notes say the answers "travel in the bundle as QuestionnaireResponse/sleep-study-criteria-draft
   and are named in the claim's supporting information", and the body holds the score 17.
7. Choose `Send PAS request`. On mock with this member the default scenario answers: "The prior
   authorization is approved.", with the authorization number, and the next step "The flow ends
   here. Record this result." Under "The questionnaire answers in the request built, which was not
   sent" the four answers are listed, 17 among them, and the wire view's request body holds
   `"valueInteger" : 17`.
8. The chain bar reads "Done: Documentation is required.", "Done: The questionnaire response is
   complete." and "Done: The authorization is approved. The journey ends here.", and `Open the
   whole journey` shows the chain in History: CRD, DTR and PAS, each with its run.

To see the other outcomes, choose a scenario on the PAS tab (for example pended, then `Send
inquiry` from the next step), or start again with another member ID from the table on the CRD tab.
Declare an `Expected outcome` before sending to get Passed or Failed in place of Unverified.

What was not checked on the screens, and what they are known to get wrong, is under
[Limitations](#limitations).

## The wire view carries member data

The wire view shows the request and the response as they went over the wire. That is the point of
it, and it means that **on the live profile it shows member data**: the member ID, the name, the
date of birth, and whatever the payer sent back. The lookup results, the case fields filled by a
lookup (`Show what the lookup returned`), the request preview and a pasted request do the same.

So:

- Do not screenshot or screen share the wire view, a lookup result, a preview or a request body on
  live unless everyone who can see it may see that member's data. Collapse the wire view first.
- Do not paste a request or a response into a ledger note, a ticket or a chat. A note is free text
  and is written to disk as typed.
- On the mock profile every member is synthetic, which is what makes the default safe to demo.

What the wire view hides: credential headers (`Authorization`, `Cookie`, API key headers) show at
most their scheme, and a secret the request carried that the service sends back (a token, a key,
a secret variable) is masked wherever it is repeated in the response. The designed view and the
returned fields of CRD, DTR, PAS, subscriptions and both lookups are read from that masked body
(D-162, D-179). What it does not catch: a secret sent back re-encoded (percent, base64, another
case), shorter than six characters, or as a JSON number; and, for a request sent from the
collection as it is, the "returned but not interpreted" fields are built from the body as
received, so there the page masks what it can and the server does not (D-196). A credential typed
into a pasted CRD request is shown as typed by the comparison.

A notification a service sends to the receiver is masked the same way before it is held or shown,
with everything PAVE could have sent to that service: every secret variable of the active
environment and of `application.yml`, the access token it holds, the client secret, and the
credentials of the address to call back. A notification that had something masked says so: "1
place in this payload is masked because it repeats a secret this tool could have sent to the
service ... What is shown is therefore not exactly what was received." This was checked by the
fix's author, and again by an agent that did not write the fix, against stand-ins on this machine,
not for this README. A secret shorter than six characters repeated in a notification is shown in
clear, and the notification's notes say so, as the wire view does ("... 1 secret shorter than 6
characters. A secret that short is not searched for ..."); they also say when a secret could not
be worked out and the payload was therefore not searched for it (covered by a test, not checked
on the running tool).

A lookup field whose value holds masked text is marked `masked` and is not taken into the case:
"This value holds a secret the request sent, which this tool masked, so it is not what the
service returned and is not offered for the case." (seen by the fix's author on live against a
stand-in, not for this README).

PAVE never writes the wire view to disk: the ledger holds no request or response body, no name,
date of birth, address, gender or subscriber ID under any setting. Every `/api/` response carries
`Cache-Control: no-store`, so the browser does not keep member data in its cache either (observed
on the running tool).

## Who can reach this tool

PAVE has **no sign-in**. Anyone who can reach its port can use everything it offers. On the live
profile that means looking up real UAT members, submitting prior authorizations to whatever
environment is active, reading the ledger, and uploading or deleting configuration. So by default:

- **It listens on this machine only.** `server.address` is `127.0.0.1` in `application.yml`
  (decision D-115). Other machines cannot connect at all. The cost is the receiver: no vendor can
  reach `POST /api/pas/notifications` until this is changed.
- **It answers only to `localhost`**, a loopback address, the bound address, or a host name listed
  under `app.allowed-hosts` (D-116, D-127). A request addressed to any other name gets 403 ("This
  request was addressed to a host name this tool does not answer to."). This stops a web page
  served from another name that points at this machine from reading answers.
- **It refuses a request that comes from another site**, whatever its method (D-148): when the
  browser says the request is cross-site (`Sec-Fetch-Site`), or, without that header, names
  another origin, the answer is 403 ("This request came from a page that is not part of this
  tool, so it was not carried out."). A link from another site still opens the page itself, never
  an answer of the API. A client that sends neither header, such as curl or a vendor's server, is
  let through. Observed: `/api/mode` with `Sec-Fetch-Site: cross-site` answered 403, a cross-site
  navigation to `/` answered 200, and `Host: other.example.invalid` answered 403. The session
  cookie, which holds the production unlock, is `SameSite=Strict` and `HttpOnly`.
- **Nothing it serves is stored by the browser** (`Cache-Control: no-store`), pages load scripts,
  styles and data from this tool only (a Content-Security-Policy header), no other site can frame
  it, and no address of the tool, which can hold a member ID, is sent to another site as a
  referrer.
- **One limit on any request body**: 30MB (`spring.servlet.multipart.max-request-size`), held in
  memory while it is read and never written to the temporary folder (D-149).

The notification receiver is exempt from the host check, for that exact address only, because a
vendor addresses it by whatever name it was given. Observed: a POST to `/api/pas/notifications`
with a foreign `Host` answered 200 with no body.

**Opening it to the network.** Start with `--server.address=0.0.0.0` (or one interface address)
and list the name others will use under `app.allowed-hosts`. This was not tried for this README.
Then every machine that can reach that address can do everything listed above, with nobody
signing in, and a browser sends no `Sec-Fetch-Site` to a name served over plain http, so the
cross-site check rests on `Origin` there (D-148). **Do not do this before PAVE has a sign-in**
(D-270): the setting exists, and changing it is not a decision a tester makes. Even then, not
without asking whoever owns the installation and whoever owns the environment it points at, and
never with a production environment active. If the point is to receive notifications, also set
`onyx.subscription.callback-url` (or the collection's call back variable) to the address the
vendor can reach.

### Before PAVE is run as a shared or production service

The operator said that this tool will be productionized one day (D-270). That is read here as
running it as a real service. Whether it means one copy shared by a team, use against the vendor's
production service, or both was not said, and both are taken as possible. As delivered it is built
for one tester on one machine, and what follows would have to exist first. **None of it is
built.** Each line says what the tool does today and what would have to exist in its place.

- **No sign-in and no roles.** `build.gradle` has no security dependency and the code has no notion
  of a user, so anyone who can reach the port can do everything: look up members, submit, read the
  ledger, upload, activate and delete configuration. A sign-in, and a rule for who may do what,
  would have to exist.
- **Plain http.** `application.yml` has no TLS setting (`server.ssl`), so member data, the session
  cookie and whatever is typed would cross the network readable by anyone on the way. An encrypted
  connection would have to exist.
- **Secrets in plain text on disk.** An uploaded Postman environment or collection is kept as
  uploaded in `config-store/`, credentials included, and so is the installation salt (by default
  `config-store/installation-salt`, see `tracking.salt-file`), with which a member ID is recovered
  from its ledger token. A client secret or a lookup header set in `application.yml`
  (`onyx.auth.client-secret`, `lookup.member.headers`, `lookup.provider.headers`) is in plain text
  there, or on the command line when given there. On Linux and macOS the files the tool writes
  itself (the configuration store, the salt and the ledger) can be read by the account that runs
  the tool only; on Windows they take the access list of their folder. The tool does nothing to
  protect `application.yml` or the command line. Secrets kept apart from the tool's files, with
  access to them controlled, would have to exist.
- **Member data on screen, in uploads and in the ledger.** On live, the wire view, the lookup
  results and the request preview show member data to whoever is looking, and so do the received
  notifications (`GET /api/pas/notifications`), which can carry a ClaimResponse. An uploaded file
  is kept whole, member data included, and the ledger keeps a note as it was typed, a name or an
  address included (D-217); the run history and its export (`GET /api/runs/export.csv`) can be read
  by anyone who can reach the tool. Who may see a member's data would have to be decided and enforced.
- **No record of who did what.** A ledger line has no place for a user, and the tester reference is
  free text that anyone can type. Anyone can also change the note and the tester reference of any
  run afterwards (`POST /api/runs/{runId}/note`; the latest wins). An upload, a deletion, an
  activation and a production unlock name no person either: the unlock is logged with the
  environment's name only. A record of who did what would have to exist.
- **The notification receiver does not check its caller.** `POST /api/pas/notifications` answers
  200 to anyone, and is exempt from the host check. It holds what arrived in memory only: at most
  200 notifications, each cut at 131,072 bytes, the oldest let go when one more arrives, and none
  of them after a restart; the ledger line of each remains. A check of the caller, and somewhere
  lasting to keep what arrived, would have to exist.
- **One configuration and one unlock rule for everyone.** The active environment is one for the
  whole copy: activating or uploading changes it for every person using it. The production unlock
  is held per browser session in server memory and needs only the active environment's name typed
  exactly, a name the masthead of every tab shows. While `config-store.allow-prod-writes` is
  `false`, its default, nobody can unlock. Once it is `true`, anyone who can reach the tool can
  unlock production writes for their own session, and anyone's change to the configuration, or
  anyone locking again, ends the unlock of every session. Only one suite runs at a time for the
  whole copy: while one person's suite runs, anyone else's is refused. Production writes allowed
  only to named people would have to exist.
- **One copy on one machine.** The configuration store (`config-store/`) and the ledger
  (`run-history/`) are files in the folder the tool starts in, by default (`config-store.directory`,
  `tracking.directory`). Nothing guards two copies that use
  the same folders: the ledger takes no lock on its files beyond what appending a line gives, and
  each copy reads the configuration store when it starts and then keeps it in memory, so neither
  sees what the other changed, and the last to write the store's state file wins. A copy that
  replaces an uploaded file also removes the old file, which the other copy's state can still name;
  a store whose state file names a missing file is refused at start-up, so that copy then does not
  start. Either a rule that only one copy uses a store and a ledger, or storage that several copies
  can share safely, would have to exist.
- **Nothing packages it or watches it.** There is no container file and no service unit, only
  `run.sh`, `run.cmd` and the jar. What it has for checking itself is `GET /api/runs/health`
  (whether the ledger can write), `GET /api/mode` and `GET /api/procedure-codes/self-check`; there
  is no general health endpoint. The ledger has no setting for backup or for how long runs are
  kept: a file is added for each day on which something is recorded, and none is ever removed.
  Packaging, monitoring, backup and a rule for how long runs are kept would have to exist.
- **Requests go wherever an upload says.** The tool sends a request to whatever address an uploaded
  environment or collection names, from the network of the machine it runs on and with the stored
  credentials; a request sent as it is in the collection (`/api/raw`) too. Nothing limits the host:
  the address checks warn about, and for some refuse, addresses the HTTP client reads differently
  or whose path holds encoded separators, and warn about a sandbox on the same server as
  production, but none of them limits which host is reached. On a shared copy, anyone who can reach it could point
  it at an address inside that network and read the answer in the wire view. A limit on where the
  tool may send would have to exist.
- **Any caller can make it hold large bodies in memory.** A request body of up to 30 MB is accepted
  (`spring.servlet.multipart.max-request-size`), and an upload is held in memory, not on disk. The
  notification receiver, which needs no sign-in, reads each body up to 8 MB into memory. Nothing in
  the tool limits how many requests one caller sends. Limits on who may send what, and how much,
  would have to exist.
- **Never seen against the vendor.** Nothing has been run against the vendor's service, and the way
  the token request authenticates the client (HTTP Basic) is an assumption (D-233). It would have
  to be observed in the sandbox first.

## Configuration through Postman

No endpoint url, variable or credential is built into PAVE. It reads the Postman files the vendor
ships. `application.yml` holds behaviour and storage locations; it needs no edits in normal use.
Everything below can be done on the Config tab; the `curl` lines show the same through the API.

Two sample files show the expected shape, with placeholder hosts ending in `.example.invalid`:

- `samples/postman/pave-sample-sandbox.postman_environment.json`
- `samples/postman/pave-sample.postman_collection.json`: every request in a folder, the thirteen
  request names of the vendor's sandbox collection, one request with a pre-request script, saved
  example responses.

A test (`SamplePostmanFilesTest`) uploads both through the real parser and store and checks that
all thirteen requests map to the operation the specification names. Those mappings were also seen
on the running tool.

### Uploading an environment

```
curl -s -F file=@samples/postman/pave-sample-sandbox.postman_environment.json -F kind=sandbox localhost:8057/api/config/environments
curl -s -X POST localhost:8057/api/config/environments/pave-sample-sandbox-977ed8ee/activate
```

- **The kind is required** and is never guessed from the file: `sandbox`, `uat` or `prod`.
- An environment whose name or urls contain "prod" cannot be uploaded as `sandbox` or `uat` unless
  you add `-F acknowledgeNotProd=true`, because mislabelling production is the one mistake the
  guardrail cannot recover from. Observed answer: 422, "This environment looks like production and
  was declared as sandbox. It was not stored."
- An environment that is not declared production may not use an address a production
  environment uses (D-143): its upload is refused, naming the production environment. Declaring
  it `prod` is the way through. The address is read the way the HTTP client reads it, so another
  spelling of the same address (a port written with leading zeros, for example) is the same
  address. Observed: with `http://127.0.0.1:18742/fhir` uploaded as `prod`, an environment at
  `http://127.0.0.1:0018742/fhir` declared `sandbox` was refused, naming the production one. A
  readable address is quoted as PAVE read it (`http://127.0.0.1:18742/fhir`). One the client
  cannot read is quoted as the file writes it, without user information or query, with the
  variable that holds it, and the sentence says that it cannot be read.
- **A production environment whose address PAVE does not know exactly is refused** at upload
  (422), cannot be made active and cannot be unlocked, with a sentence that names the variable and
  says why. This holds whether the address is in the environment itself or comes from a
  collection's variables or from `application.yml` (`onyx.variables`), and in three cases: the
  HTTP client cannot read it (a port with a plus sign, a backslash in the path, a colon with
  nothing after it: "... which this tool cannot read the way the HTTP client reads it. It was not
  stored."); a placeholder in it has no value anywhere ("... has no value in this environment, its
  collections or application.yml, so the address it would send to is not known. It was not
  stored."); or it is read from an operating system variable (`{{env:PAVE_...}}`), which happens
  only at the moment of sending ("... is read from an operating system variable only at the moment
  of sending, so the address it would send to is not known before then. It was not stored."). A
  secret that is part of such an address is not quoted. The field error says what to do: "Write
  the address itself into the environment, plainly, as scheme://host:port/path, and upload the
  environment again." Activation answers "... cannot be made active: it is a production
  environment, and ...", the unlock "Writes to "..." cannot be unlocked: ...", each followed by
  "Nothing was changed." and the same advice. The `onyx.<protocol>.url` fallback settings of
  `application.yml` are the same for every environment and are not read as an address of a
  production environment; a write to them is judged at sending like any other url.
- **An unreadable production address that an earlier version stored** is remembered as its whole
  host (every port and every path of that host is production), or, where its path could be read,
  as that path and every path beneath it on every port of that host. Writes to it cannot be
  unlocked meanwhile. It is given up only when that same environment is uploaded again as `prod`,
  its address written plainly, on the same host; uploaded on another host it adds an address and
  takes nothing away, because the old host may still be production. Open at this revision: while
  such an environment is active, `/api/mode` and the Config tab still say "Unlock them by typing
  the environment name" and offer the unlock field, and every unlock is refused with 422 (the
  refusal says why). Upload it again with its address written plainly.
- **Address warnings.** The upload answer, the environment list (`addressWarnings`) and the answer
  of a switch warn about four things, without refusing the file: an address whose scheme, host and
  port PAVE cannot read the way the HTTP client reads them (a port above 65535, a host in a form the
  client refuses), to which no write is sent; an address with an encoded slash, backslash,
  semicolon or dot segment in its path (`%2F`, `%5C`, `%3B`), a percent-escape of a byte above
  0x7F (`%C0`), or a character above 0x7F written as it is, which the client sends as such an
  escape, to which nothing at all is sent; a non-production environment on the same scheme,
  host and port as a production environment under another base path, which PAVE treats as not
  production because the base path differs ("If that server routes this path to production,
  upload this environment with the kind prod."); and a non-production environment on the host of
  a production environment one of whose addresses could not be read, to which no write is sent
  (on every path of that host, or, where the path could be read, "This tool takes the path ...
  and every path beneath it, on every port of that host, as production."; once that production
  environment is deleted the warning ends "... is no longer stored, and an address that was
  production stays production."). The first three were observed on mock on an earlier revision,
  in the upload answer and in the environment list; the character above 0x7F and the fourth were
  seen through the API by the fixes' authors only.
- **Member data in the file.** A variable whose name reads as member data (a member, patient or
  subscriber identifier or name, a first or last name, a birth date, `mbr`, a Medicare or Medicaid
  number, an MBI, SSN, HICN or MRN, an address, unless a machine word such as `host`, `server` or
  `callback` goes with it, or a telephone) is named in a warning (observed with `patientLastName`;
  the newer names are covered by a test only). See
  [Member data in an uploaded file](#member-data-in-an-uploaded-file).
- Uploading never activates. Activating switches every tab at runtime, with no restart and no
  reload, and drops any production unlock.
- A disabled variable is listed and ignored, as Postman ignores it.
- Several environments can be held at once. The path takes the environment's name, url-encoded, or
  its id from the upload answer. The id is derived from the name, so the sample's id is always
  `pave-sample-sandbox-977ed8ee`.
- The active environment is remembered across a restart. `config-store.active-environment`
  names one to activate at every start.

### Uploading a collection

```
curl -s -F file=@samples/postman/pave-sample.postman_collection.json localhost:8057/api/config/collections
```

- Schema v2.1 only. A v1 collection, a truncated file and a file that is not JSON are refused with
  422.
- The folder tree is walked: requests nested in folders are found.
- Each request's method, url with placeholders intact, headers, raw body and saved example
  responses are read.
- **Detection**: each request is mapped to an operation from its name, then its url. The answer
  says why (for example "The name contains "submit", and the url has a $submit segment."). Correct
  a wrong one with `PUT /api/config/collections/{id}/mapping`; the correction is kept across a
  restart. An unmapped request is listed and can be sent with `POST /api/raw`.
- **Sending a request as it is written** (Config tab, `Send as written`, or
  `POST /api/raw`) sends the request's method, url and headers with placeholders resolved, and a
  body: the one supplied for this run as `rawBody`, or else the one saved with the collection
  request, or none when it has none (D-184). The answer says which, in `bodySource` and a note:
  "The body is the one saved with the collection request, with its placeholders filled in,
  because none was supplied for this run." (observed with the sample's `submit Happy Path`). A url
  part in single braces, such as `{claimId}`, takes its value from `urlValues`; without one the
  request is refused with 422 and nothing is sent, on both profiles (observed on mock), and so is
  a url part of `.` or `..`, or one holding `/`, `\` or `;`. A request sent as written is judged
  by its method, whatever it is mapped to: on production, any method but `GET`, `HEAD` and
  `OPTIONS` makes it a write. The vendor's access token goes with it only to a scheme, host and port the
  configuration names for a vendor operation. PAVE does not interpret the answer: it shows
  everything that came back and records the run.
- **Pre-request scripts are never run.** A request that has one is flagged, and every run that
  uses it carries the finding `PRE_REQUEST_SCRIPT_NOT_RUN`, because a value Postman would have
  computed was not computed here. Observed with the sample's `order-sign Happy Path`.

### Variables

A `{{placeholder}}` is resolved from, in order: a value typed for this run, the active environment,
the variables of the collection the request came from, then `onyx.variables` in
`application.yml`. A defined but blank variable counts as not set. Each value shown on screen says
where it came from ("Typed for this run", "The active environment", "The collection",
"application.yml", "An operating system variable", "No value anywhere"). When a placeholder cannot
be resolved the request is **not sent**: the answer is 422 (`"kind":"CONFIGURATION"`) and names
the variable and the active environment. `POST /api/config/resolve` (the Config tab's address
table, `Show how it resolves`) shows what a request would become without sending it, and marks each
unresolved variable `UNRESOLVED`.

**On mock the url of a CRD, DTR or PAS run is not resolved**, because nothing is sent: the scenario
answers whatever the environment holds. Observed: with `baseUrl` defined but blank, a CRD run on
mock answered 200 with no finding about the url, while `POST /api/config/resolve` marked
`baseUrl` unresolved. So on mock, an incomplete environment is found only by asking, on the Config
tab or with `POST /api/config/resolve`. A request sent as it is written is the exception: on mock
too it refuses an unresolved placeholder or url part with 422.

`{{$guid}}`, `{{$randomUUID}}`, `{{$timestamp}}` and `{{$isoTimestamp}}` are generated. Any other
dynamic variable, such as `{{$randomInt}}`, is not computed: a request that needs one is refused
with 422 naming it, and nothing is sent, unless you type a value for it for that run (in
`variables`, for example `"variables":{"$randomInt":"5"}`). Observed with `POST /api/raw` on mock.

### Variables that fill a request

PAVE never invents a value nobody supplied (D-141). These variables, set in the environment or
typed for one run, supply what the 2.0.1 guides ask for; without them the request goes out without
the element and the result's `REQUEST_INCOMPLETE` finding names it and the variable.

| Protocol | Variable | What it supplies |
| --- | --- | --- |
| CRD, DTR | `memberIdSystem` | The identifier system of the member ID (the guides require a system on every identifier). PAS reads it too |
| CRD, DTR | `patientMrn`, `patientMrnSystem` | The member's medical record number and its system, sent as the Patient identifier typed MR. Never filled from the member ID |
| CRD, DTR, PAS | `payerIdentifier`, `payerIdentifierSystem`, `payerName` | The payer: `Coverage.payor`, and the insurer of a PAS claim |
| CRD, DTR | `hcpcsSystem` | The system HCPCS codes are sent with on orders. Unset: the CRD 2.0.1 address |
| CRD, PAS | `fhirServer` | The hook request's `fhirServer`; on PAS the base of full urls |
| CRD | `crdOrderSignServiceId`, `crdOrderSelectServiceId` | The CDS service ids, read by the operation addresses |
| DTR | `dtrIntendedUse` | The intended use stated on a QuestionnaireResponse (`withpa`, `withclaim`, `withorder`, `retain-doc`, `OTH`, or `system\|code`). Unset: what CRD handed on, or nothing |
| DTR | `dtrCoverageExtension` | `qr-context` (2.0.1, the default) or `qr-coverage` (the 2.2.0 ballot form) |
| PAS | `pasServiceType`, `pasRequestType` | The X12 service type and request type of every item (`Claim.item.category` is required) |
| PAS | `pasHcpcsSystem` | Overrides the HCPCS system on a claim |
| PAS | `coverageRelationship` | The member's relationship to the subscriber; `self` names the member as the subscriber |
| PAS | `pasRequestorType`, `pasRequestingOrgIdentifier`, `pasRequestingOrgIdentifierSystem` | The requesting organization, and the subscription's filter by it |
| PAS | `claimIdentifierSystem`, `claimType`, `claimPriority`, `attachmentControlNumberSystem` | Parts of the claim |
| PAS | `subscriptionTopic`, `subscriptionFilter`, `subscriptionStatus`, `subscriptionReason`, `subscriptionChannelType`, `subscriptionPayloadContent`, `subscriptionHeartbeatSeconds`, `subscriptionEnd` | Parts of a subscription. A filter is built only from what was supplied |

Member lookup fields fill the rest when a lookup found them (name, gender, date of birth, coverage
status and period, plan, subscriber ID), and the provider lookup fills the Practitioner or the
Organization.

### Secrets

An exported environment can hold a client secret in plain text, and PAVE keeps uploaded files
verbatim in `config-store/`. So:

- **The upload warns**, naming the variable and the stored file, when a secret variable holds
  literal text.
- **Secret variables are masked** in every answer that shows configuration: the answer says the
  secret is set, not what it is (`[secret apiKey]`). A variable is secret when Postman typed it
  `secret` or when its name reads as a credential. Redaction is by name, never by matching a
  value.
- **A credential typed into a collection** (a header, a query value or a body field whose name
  reads as a credential) is named at upload and masked wherever the collection is shown (D-147).
  The collection file on disk keeps the value.
- **The ledger's salt.** `config-store/installation-salt` (or `tracking.salt-file`) turns each
  member ID into the token the ledger keeps. It is a secret: the token is a plain HMAC-SHA-256 of
  the member ID under the salt, so whoever holds the salt and the ledger, which keeps the last four
  characters beside the token, gets a short member ID back at once by trying candidates. Keep the
  salt where the ledger is not, never copy it with the ledger or its export, and never commit it
  (`config-store/` is in `.gitignore`).
- **What masking does not catch.** Because masking goes by name, a key written inside a url, or
  held by a variable with an ordinary name, is shown as written, in the upload answer, in
  `GET /api/config/effective`, in `POST /api/config/resolve` and in the wire view. Observed: an
  environment whose `baseUrl` was `https://sandbox.onyx.example.invalid/fhir?api_key=LITERALURLKEY999`
  showed that key in the upload answer, and so did a variable named `plainThing`, while a
  variable named `apiKey` in the same file was masked. A credential typed into a path segment or
  a host of a collection is shown as written too. Keep every key in a variable of its own whose
  name reads as a credential (`apiKey`, `clientSecret`, `token`), typed `secret`, and refer to it
  from the url or the header.
- **The call back address.** In `onyx.subscription.callback-url`, a query parameter whose name
  reads as a credential, or a secret variable, is masked in the Subscription shown and sent as
  written; a secret written into its path with no variable is not masked.
- **Keep the secret out of the file.** Give the variable the value `{{env:PAVE_NAME}}` in Postman,
  export, and set the operating system variable `PAVE_NAME` where PAVE runs. The sample does this:

  ```
  "key": "clientSecret", "value": "{{env:PAVE_ONYX_CLIENT_SECRET}}", "type": "secret"
  ```

  ```
  export PAVE_ONYX_CLIENT_SECRET='the real secret'
  ./run.sh live
  ```

  Only names that start with `PAVE_` are read, so an uploaded file cannot read other variables of
  the PAVE process. A value read this way is always treated as secret. Uploading the sample gives
  no warning. Reading the variable at send time was tested in the build, not against a real token
  server.
- Scalar settings in `application.yml` can also come from the environment through Spring's usual
  mapping, for example `ONYX_AUTH_CLIENT_SECRET` for `onyx.auth.client-secret`.
- `config-store/` and `run-history/` are listed in `.gitignore`. Never commit `config-store/`: it
  holds what you uploaded, credentials included. PAVE warns at start when either lies inside a git
  working tree.

### Member data in an uploaded file

The configuration store keeps what you upload exactly as it was uploaded, because it is what the
tool sends from: every variable's value, every header and body, and every saved example response.
An export from Postman can therefore put a real member's data on disk in `config-store/`, where it
stays until the environment or collection is removed.

- **The upload warns**, naming the variables or the requests and never a value, when a variable's
  name reads as member data or a request carries a saved example response. Observed with the
  sample collection, which has saved examples: "4 requests, the first being "CRD / order-sign Happy
  Path", carry a saved example response, which can hold the member data of whoever the example was
  saved for. The collection is kept on disk exactly as it was uploaded, values included, in the
  file .... An environment or a collection that holds real member data must not be uploaded: use
  placeholders such as S123456789 in its place."
- The warning goes by names only. A value under an ordinary name, or member data inside a saved
  body, is not recognised. **Never upload an environment or a collection that holds a real
  member's data**: replace member IDs, names and dates of birth with placeholders before export,
  and remove saved example responses taken from real calls.

**Access tokens.** OAuth2 client credentials are requested when `onyx.auth.enabled` is true, with
the client authenticated by HTTP Basic (the client id and secret in an `Authorization: Basic`
header, `grant_type=client_credentials` in the form body). Whether the vendor wants that,
credentials in the form body or a signed JWT assertion is an open item (see
[Open items](#open-items)); a vendor that wants another method answers 401. The
four `onyx.auth` values are resolved like any placeholder, so they can point at the environment's
variables, for example `--onyx.auth.enabled=true --onyx.auth.token-url={{tokenUrl}}
--onyx.auth.client-id={{clientId}} --onyx.auth.client-secret={{clientSecret}}`. The token goes only
to a scheme, host and port the configuration in force names for a vendor operation without any
value typed for the run; a call pointed anywhere else goes without it, and the wire view says so
(D-143, rule 7). This was not run against a token server. A Postman authentication helper in a
collection is reported and not applied.

### Environment kinds

| Kind | Banner on every tab | Writes |
| --- | --- | --- |
| `sandbox` | none | allowed |
| `uat` | purple outline | allowed |
| `prod` | solid purple, saying whether writes are locked | blocked unless unlocked, and never when the floor forbids it |

Every ledger line records the kind of the environment it ran under.

### The production guardrail

A PAS submit against production creates a real prior authorization request against a real member.
**What decides is where a request goes, not only which environment is active** (D-143):

- PAVE remembers the addresses (scheme, host, port and base path) of every environment declared
  `prod`, also after it is removed and across a restart. A write to one of those addresses is a
  write to production whatever environment is active and wherever the url came from.
- **Reads go through**: inquiry, subscription read and list, discovery, the lookups. Observed for
  the inquiry.
- **Writes are blocked**: PAS submit and subscription create, update and delete, and a request sent
  as it is written whose method can change something. A blocked write answers 403 with a full
  result that begins "Nothing was sent.", and is recorded in the ledger as `BLOCKED`. Observed.
- **The floor.** `config-store.allow-prod-writes` is `false` by default, and while it is false no
  unlock is possible from any screen. Only whoever owns the installation can change it, with a
  restart. Observed: "Production writes cannot be unlocked: an owner has locked this installation
  against them."
- **The unlock.** With the floor set to `true`, a tester unlocks writes for their own browser
  session on the Config tab by typing the active environment's name exactly, or:

  ```
  curl -s -c pave-cookies -b pave-cookies -X POST -H 'Content-Type: application/json' -d '{"action":"unlock","environment":"Exact Environment Name"}' localhost:8057/api/config/writes
  ```

  Checked with an environment uploaded as `prod` and the floor set on the command line
  (`--config-store.allow-prod-writes=true`): the name in other capitals answered 422; the exact
  name unlocked, with the answer "Production writes are unlocked for this session on "...". Any
  change to the configuration locks them again. Locking them again, from this or any other browser,
  ends the unlock of every session."; a submit in that session was let through (on mock, answered
  by its scenario), while the same submit from another session was blocked ("this call carries no
  production unlock"). The unlock lives in server memory, in
  that session only, and is never written anywhere, so it does not survive a restart. Any change
  to the configuration, including an upload or switching environment, drops it (observed).
  `{"action":"lock"}` locks again. An unlock is for the environment it names: a write to another
  production environment's address is refused.
- **A replay or a suite never carries an unlock**, so a write in a replay or a suite against
  production is reported as blocked, and its next step says to send that write on its own from its
  tab (`SEND_ON_ITS_OWN`), not to unlock (D-183). Observed for a replayed PAS submission.
- **A value typed for one run may not move a write** to another scheme, host, port or base path.
- **An address PAVE cannot read the way the HTTP client reads it is never taken as "not
  production".** A write that would go to such an address (a port above 65535, a host in a form the
  client refuses) is refused with 403, naming the variable and its layer, the request or the
  `application.yml` setting it came from: "Blocked: the address it would go to could not be read."
  When the scheme, host and port read well and only the url as filled is not valid, because a
  value holds a space, a stray `%` or a vertical bar, the answer is 422: "... was not sent: the
  url as filled is not a valid url, because the text of the variable "..." (...) holds a character
  that cannot stand in a url as it is ... Its scheme, host and port are not the problem." It names
  the variable and its layer, or the value typed for the run, never the value; on live for every
  method, on mock for writes only, since on mock a read is never sent. Nothing is sent.
  This was checked by the fix's author on live against stand-ins, and again by an agent that did
  not write the fix; on mock a protocol run does not
  resolve its url (see [Variables](#variables)), so there the run is answered from its scenario
  (observed: a PAS submit with such an environment active answered as its scenario says).
- **No request leaves with an encoded separator in its path**: an encoded slash, backslash,
  semicolon (`%2F`, `%5C`, `%3B`, in any letter case and however many times encoded), or a dot
  segment that appears only once an escape is decoded, whatever the environment and whatever the
  method, because a server in front of the service may decode it before it reads the path. The answer is 422, naming the variable, the
  url part or the request, never the value. A query string is not touched. Observed on mock:
  `crdOrderSignServiceId` typed for one run as `..%2F..%2FClaim%2F%24submit` answered "CRD
  order-sign was not sent: the path of its url holds an encoded separator (%2F), which came from
  the variable "crdOrderSignServiceId" (typed for this run). ..." A percent-escape of any byte
  above 0x7F in the path (`%c0%af`, `%E2%88%95`, an overlong or look-alike slash) is refused by the
  same rule with the same 422, since a FHIR path is plain ASCII, and so is a character above 0x7F
  typed or written as it is, which the client would send as such an escape: "CRD order-sign was
  not sent: the path of its url holds a character above 0x7F (sent as %E2%88%95), which came from
  the variable "crdOrderSignServiceId" (typed for this run). ..." (the raw character was seen
  through the API by the fix's author only).
- **Lock, from any browser, locks every session** (D-144). Observed on mock: session A unlocked and
  sent a submit; session B posted `{"action":"lock"}`; A's next submit answered 403, "... production
  writes are locked for this session. An earlier unlock no longer applies, because the
  configuration has changed or writes were locked again since. Nothing was sent." A write A had
  already prepared when B locked is refused when it is sent, with 409: "Production writes were
  locked again before this write was sent, so it was not sent." (tested in the build over the
  running server, and seen on live against a stand-in by the agent that checked the fix). Session
  A's page is redrawn as locked within a few seconds, with a notice that writes were locked again,
  possibly from another browser (seen by that agent in a browser, not for this README).
- **The "not production" acknowledgement** (`acknowledgeNotProd`) covers that environment's own
  addresses only. "Reads as production" means the text `prod` in its name or a url, and nothing
  cleverer.

What the guardrail does not see (D-145): another DNS name for a production host, and the
`onyx.<protocol>.url` fallback settings of `application.yml` as an address of a production
environment. A production address that comes from an operating system variable, or that has no
value, is no longer a gap: such an environment is refused at upload and cannot be made active or
unlocked (above, observed on the running tool for an operating system variable at upload). No
endpoint clears the remembered production addresses: with the tool stopped, edit
`productionDestinations` in `config-store/store.json`, or remove the store folder.

**Audited twice.** The independent guardrail audit (V3) got writes through to production four ways
on the revision before the first fix wave; each was fixed and the fix attacked by a second agent
(`PROGRESS.md`). V3 ran again on revision `0658a7c` and found a write to production through a port
written with leading zeros, an encoded separator that hid a path, a raw send refused for an untrue
reason, no test that Lock locks every session, and a secret shown in clear in a notification. Each
was fixed, and an agent that wrote none of the fixes checked them on revision `9e5306d`: eight of
nine closed; the port written with leading zeros closed in one direction and open in the other
(the production address PAVE cannot read, above), with three smaller neighbours open (the refusal
of an invalid url naming the wrong cause, escapes above 0x7F, and the notification's silence about
short secrets). All four were fixed afterwards, merged at `6037d2a`. A last independent check of
that fix, on `428eff5`, closed five of its nine lines and closed four in part, and found nine new
ones: a production address taken from `application.yml` that was never remembered, an unreadable
production address forgotten when any production environment of that name was uploaded, the
heading of the malformed DTR package on mock, and six minor ones. The three major ones and three
minor ones (a character above 0x7F written as it is; a production address written with three
slashes or one, now refused as one the client cannot read; the warning for an unreadable address
that keeps its path) were fixed last (`ca716a1`, merged in `902e0d9`), as described above, and
**that last fix has been checked by nobody but its author**, who did not try a collection's own
variable as the address on the running tool, IPv6, or TLS. Recorded and not fixed: the unlock
offered for an environment that can never be unlocked (above); a member lookup fixture that plays a
timeout on mock is headed "A fault in the environment: no answer in time", though nothing left the
machine; and the member data warning at upload misses `MBRID` in capitals, `mobile` and `addr`,
and catches `webhookAddress` wrongly. The behaviour above was observed on mock, where the guardrail
behaves as on live (D-021), except where a sentence says otherwise.

## The test run ledger

Every run, on both profiles, is appended as one JSON line to
`run-history/runs-YYYY-MM-DD.jsonl` (one file per UTC day; the folder is beside `run.sh` when
started with it, otherwise in the directory you start from; `tracking.directory` moves it). A line
is written before the result is shown, then flushed and synced to disk (this was checked by
reading the code: no test can observe a sync); files are never rewritten. Files and folders the
ledger creates are readable by their owner only where the file system allows. If a write fails,
the result says "NOT recorded" with the reason, and the masthead's ledger indicator turns into a
heavy frame. If the folder cannot be written at startup, the tool refuses to start and names the
path. `GET /api/runs/health` (History, and Config, `Check this installation`) says whether
tracking is alive, how many runs were recorded today, how many writes failed and which files hold
lines it cannot read.

**A line holds:** run id, time, protocol and operation, target, profile, environment and its kind,
scenario, procedure codes, line of business, outcome, the standard codes returned (a code sent as
null, empty or with no value is written `pa-needed=(null)`, `(empty)` or `(no value)`), delegated
vendor, HTTP status, elapsed time (blank for a run that sent nothing, never `0`), who answered (the
service, a scenario, nobody, or nothing because nothing was sent), error type, what was expected and the result (pass, fail or
unverified), note, tester reference, and where they apply the chain, suite and case ids, the run it
replays or follows, the hook, the next step the result stated, the collection request used, the
findings, correlation ids (claim id, ClaimResponse id, subscription id), and the inputs the line does not hold (a
hand-edited body, details typed by hand, values typed for one run, answers carried from an earlier
step), so a replay can refuse what it cannot reproduce. A chain case also writes a line of its own
with its result.

**A line never holds**, under any setting: member first or last name, date of birth, address,
gender, subscriber ID, or any request or response body. What the service or a tester can fill is
checked before it is kept (D-151): a code or a line of business that reads like an identifier or a
date is not kept; a line of business is written only when the code list knows it, otherwise as its
length; a questionnaire address is kept only in a strict form. A procedure code that the loaded
code list holds is kept as written, whatever its shape (a seven-digit or longer code included),
unless it holds the run's member ID. One the list does not hold and that reads like an identifier
or a date, or holds the run's member ID, is written as `(not a code, N characters)`, and so is the
subject in front of each coverage code (observed on mock: `S123456789` typed as a procedure code
was written so, beside `95810`; the long listed code is covered by a test only). Whether a code
is on the list is decided once, when the line is written: a line is read as it was written and is
not judged again by whatever list is loaded later (the check for the run's own member ID stays on
both sides). A line written before the second member data fix that holds a long code with no
member ID to compare it with therefore shows that code when read; no such build was handed over.
A claim id,
ClaimResponse id or subscription id that a service or a notification supplies is written in clear
only when it cannot be anybody's data: a UUID, an OID (`urn:uuid:`, `urn:oid:`) or an id this
tool minted. Every other one, a plain number included and one that holds the run's own member
ID (`S123-456-789` on a run for `S123456789`), is written as a token, as the authorization number
is, unless the line's member ID is in clear (`tracking.record-member-id`, and on mock for a
synthetic member or none); the line does not mark the token otherwise, and History says under it
"Kept as a token, not as the identifier itself: the ledger cannot tell this identifier from
somebody's data. A notification that carries the identifier is still matched by it." A
notification is matched to its run by comparing tokens, so matching works for a numeric id and for
one that holds the member ID.
A run whose claim id is a token cannot be replayed, and replay says so. Lines written by an
earlier version are not rewritten: their clear ids are shown, exported and matched as tokens when
read. An id that is not one identifier (over 128 characters, or with spaces or other signs) is not
kept and not looked up, and the notification's result says so. Known gaps: a one-word surname
written where a code belongs is kept, because a single word cannot be told from a code; an id a
service supplies that is 64 hexadecimal characters, or shaped like a UUID, is taken for the
ledger's own token or for a UUID and written in clear whatever its digits, even when it ends in a
member ID (recorded, not changed: telling them apart needs a change of the line's shape); and
lines written by an earlier fix with `(not recorded, N characters)` under a subscription or
ClaimResponse id cannot be matched.

**The member ID policy** has two settings:

```yaml
tracking:
  record-member-id: false     # the mock profile sets it to true
  record-npi: true            # an NPI is not PHI
```

- On mock, a member ID is written in the clear only when it is a `P32H-` sentinel or an `S` and
  nine or ten digits, so mock runs can be replayed; any other ID is replaced by a token as on live.
- On live, by default, the member ID is replaced by a salted token (HMAC-SHA-256) and its last four
  characters, so two runs of one member can be matched without the file becoming a roster, **as long
  as the salt is kept apart**: with the salt, a short member ID is recovered from its token and last
  four characters at once, by trying candidates. The salt is a secret (see [Secrets](#secrets)). It
  is created at first start in `config-store/installation-salt`, or wherever `tracking.salt-file`
  says; it may not be inside the ledger folder. Never copy it with the ledger or its export. Deleting it, or moving the ledger without it, breaks
  matching with older tokens. An authorization number is treated like a member ID.
- If an owner sets `record-member-id: true` on live, PAVE honours it and logs one warning at
  startup. Not observed: the jar was not started that way for this README.
- A run whose member ID was not recorded cannot be replayed, and replay says so rather than
  skipping it.

**A note is free text, kept as typed.** `Save the note` on a run in History, or
`POST /api/runs/{runId}/note`, keeps what you type, up to 4,000 characters, and writes it to disk.
The tester reference (up to 200 characters), the expectation (up to 1,000) and a suite's case id
are written as given too. Over a limit the run is refused with 422, which gives the length and
quotes nothing. What PAVE can recognise, it refuses:

- A run whose note, tester reference or expectation holds the member ID, first or last name, date
  of birth (in the usual ways of writing a date), subscriber ID or address that the same request
  carries is refused with 422 on that field before anything is sent, naming the kind of value and
  never the value. A suite row whose note, tester reference or expectation holds its own member ID
  is reported as not valid and not run.
- A request sent as written (`POST /api/raw`) has no member on its form, so its note, tester
  reference and expectation are checked against the identifiers of the Patient and Coverage
  resources, and `Coverage.subscriberId`, in the body it sends: "The note holds a member ID the
  request body carries. ..." Only identifiers are checked there, not a name or a date of birth, and
  a `P32H-` sentinel does not count. When the guardrail refuses a write first, a saved body is never
  read, so the note is recorded with the blocked line as typed.
- A run sent with `A request of my own` from the CRD, DTR or PAS tab is checked against the member
  identifiers its body carries: Patient and Coverage identifiers, `subscriberId`, a Patient id,
  `context.patientId` and a `Patient/<id>` reference (not this tool's own `patient-1`), with the
  same refusal. Only identifiers are read, not a name or a date of birth. The body is read with
  its `{{placeholders}}` filled from the values typed for the run, the active environment and
  `application.yml` (not from a collection's variables), so a note naming a member that a
  placeholder brings in is refused with the same sentence; when any placeholder has no value, the
  body is read as typed. A body with no readable identifier is not refused. The fix's author saw
  the refusal on the running tool for CRD only, and the filled placeholders for CRD only; DTR and
  PAS go through the same code and are covered by tests of the refusal, not of the placeholders.
- A note added later is refused when it holds the member ID of that run, also where the line keeps
  only the token: "The note holds the member ID of this run. The ledger keeps it as written and may
  not hold member data, so leave it out. The note was not added. Nothing was changed." (observed).
- If such text reaches the record all the same, the line keeps `(note not recorded, N characters)`
  (or the tester reference or expectation likewise) in its place, and lines written by older
  versions are shown and exported the same way.

Text is brought to one form (Unicode compatibility normalisation) before it is compared, so the
member ID in fullwidth characters is found, and so is the run of six or more digits of the member
ID written alone; a date of birth is found written in numbers or with the month's name ("July 23,
1961", "23 Jul 1961"); each part of a hyphenated or two-word surname of four letters or more is
looked for alone; the city together with the postal code counts as the address, either alone does
not. A first or last name of four letters or more is looked for alone, as a whole word in any
case; a shorter one (`May`, `An`) only where the first and last name stand together, so an honest
note holding the word "may" is kept. A note added later on a line that holds only the token is
checked against the token, and the digits of the ID alone are found there only when one or two
letters stood in front of them; a name or a date of birth cannot be checked then, because the line
holds neither. What PAVE cannot recognise it writes as typed: somebody else's name or date of
birth, a name or date the request does not carry, a member ID or subscriber ID under six letters
and digits, an address line under five characters. **Do not type member data into a note, a tester
reference or an expectation.** Old lines that hold a name or a date of birth in a note are still
shown and exported as written: the files are append-only. In the results of a suite (on screen
and in `results.csv`), a row refused because its expectation held member data, or was too long,
carries the refusal sentence in place of the expectation, for example "The expectation holds the
member's date of birth. The ledger keeps it as written and may not hold member data, so leave it
out."; one refused for this run's member ID shows only that sentence. Open at this revision: an
expectation that holds the date of birth or a name and is also malformed (`Jul. 23rd 1961:
covered=covered`) is refused for its shape before the member is looked up, and the results file
still repeats it in the expected column. Three spellings of the run's own date of birth still
pass and are written in clear (a month abbreviated with a full stop and an ordinal day, the
compact `MMDDYYYY` form, a two-digit year with an apostrophe), and so does a date written in
words.

**The list** (`GET /api/runs`) filters by day, protocol, operation, outcome, result, chain, suite
run and, through the API, an exact tester reference (`testerRef`) and the run a line follows
(`follows`, which History uses for "Followed by"). An optional `zone` (an IANA
zone id such as `America/New_York`) makes the days those of that clock; without it every day is a
UTC day. The History tab offers the tester reference filter, not `zone`.

**Export** with `GET /api/runs/export.csv?from=YYYY-MM-DD&to=YYYY-MM-DD` (each optional, with
`protocol`, `result` and `zone` too): UTF-8 with a byte order mark, newest first, one column per
field. A cell that starts with `=`, `+`, `-` or `@` gets a leading quote so a spreadsheet shows it
as text and calculates nothing.

**Replay** with `Replay this run` in History, or `POST /api/runs/replay` by date range or run ids:
each run is sent again against the configuration in force now and compared with the original
(same, changed, not comparable, not replayable, blocked, not configured, invalid, error), and each
entry carries the new run's verdict and next step. A replayed submission is a new submission with
a new claim id. A replay never carries a production unlock. On mock a replay is answered by the
scenario the original run recorded, so it comes back the same by construction, and its result says
so. A run that was handed its case by an earlier run cannot be replayed by itself ("This run followed
run run-... and was handed its case by it, so it cannot be sent again alone."): to run the journey
again, run it as a suite row with the protocol `chain`. A run sent with
a body of your own cannot be replayed, because the body is not kept, and neither can a run whose
procedure code or expectation was not recorded: replay says so with a sentence and sends nothing. At most 1,000 runs per replay.

## The procedure code list

The picker shows what the P32H code list says about a code before you send. The real list is a
P32H export of about 7,000 rows whose header row is not known.

**Until it is supplied, a synthetic placeholder is used**: `src/main/resources/codelist/placeholder-codes.csv`,
43 rows, every one marked "Placeholder row. Not from a P32H export." The startup log and the
self-check both say so.

**A list says of itself whether it is synthetic.** A list in which any row's notes hold the
sentence "Not from a P32H export." (in any letter case) is synthetic, whatever its path: the
bundled placeholder, the shipped sample and a list of your own marked that way are all treated
alike. A list with no such row is taken as the real one; PAVE cannot vouch that it is P32H's.

**What that means for findings** (D-169): on mock, the code list is compared with each CRD answer,
and every finding that rests on a synthetic list says so. On live, with a synthetic list in use,
PAVE does not compare at all: the run carries one `CODE_LIST_NOT_COMPARED` finding beside the
determination. The picker and the code chips say "Placeholder code list in use" while a synthetic
list is in use, the self-check answers `"placeholder":true` with a warning, and the start-up log
says so.

**Pointing at the real export.** Set `codelist.file` to its path (a relative path is resolved
against the directory PAVE starts in) and restart:

```
./run.sh mock --codelist.file=samples/codelist/sample-code-list.csv
```

`samples/codelist/sample-code-list.csv` is a sample in the shape the real export is expected to
have, every row's notes reading "SAMPLE ROW. Not from a P32H export.", so the tool treats it as
synthetic, exactly as it treats the bundled placeholder. Its vendor names are real: they are the
four delegated vendors the specification names as current (eviCore, Evolent, Carelon, Optum Post
Acute), the specification asks for them in fixtures, and they are the only names the tool
recognises as vendors (D-081), so a made-up name would be treated as an unknown vendor. The codes
are real CPT and HCPCS codes. **Which code goes to which vendor, and every flag and date, is
invented**: it follows the specialties the specification lists (sleep, imaging, genetic testing,
post-acute) and says nothing about P32H's actual assignments. The bundled placeholder list is built
the same way. A test loads it through the real loader, and the command above was run (as
`./run.sh mock --server.port=8153 ...` from another directory): the self-check then answered `"placeholder":true`, `"rowCount":11`,
`"statingAuthRequirement":10`, `"namingVendor":7` and `"unrecognisedColumns":[]`, with each column
found under the sample's own header (`"code":"CPT/HCPCS"`, `"authRequired":"Prior Auth Required"`,
`"lineOfBusiness":"LOB"` and so on), and the warning "This code list says of itself that it is
synthetic: 11 of its rows carry the note "Not from a P32H export." Its tags are not P32H's, so it
is treated as the bundled placeholder is: on live it is not compared with the service. Set
codelist.file to the P32H export and restart." Use it to see how columns are found, not to test
against a service.

- CSV or JSON, chosen from the extension unless `codelist.format` says otherwise.
- `codelist.delimiter`: one character, or the word `tab`.
- Columns are found by a list of candidate headers each, matching ignoring case, spaces,
  underscores, hyphens and dots: code (required), description, code type, system, prior
  authorization flag, line of business, delegated vendor, effective date, end date, notes. The
  candidate lists are in `src/main/java/org/p32h/interop/epa/pave/config/CodelistProperties.java`;
  `codelist.columns.<column>` replaces the list for one column when the export uses a header none
  of them match.
- The flag reads the common spellings of yes and no (`Yes`, `Y`, `No`, `N`, `1`, `0` and others).
  Anything else, and a blank cell, is **not stated**: the picker shows no tag, never "No PA".
- Dates are read as `2026-01-31`, `1/31/2026` or `20260131`; a date with a time of day is read as
  the date written. Anything else is kept as written.
- A coding system the list gives as a label (CPT, HCPCS Level II and their spellings) is sent as
  the matching uri, with a note; any other system that is not a uri is not sent, and is noted.
- A file with no recognisable code column stops startup and names the headers it found. A title
  line above the header row is refused the same way.
- `codelist.line-of-business-aliases` maps the member API's name for a line of business to the
  list's.

**The self-check is the first call after dropping in a new export:**

```
curl -s localhost:8057/api/procedure-codes/self-check
```

It reports the source, whether it is the placeholder, the row count, how many rows state an
authorization requirement, how many name a vendor, the lines of business, which header each column
was found under, **which columns were not recognised**, and warnings. With the sample file the
unrecognised list is empty (tested).

## Suites and chains

**A suite** is a CSV or JSON file, one case per row, run one after another against the active
environment. `samples/suites/sample-suite.csv` has six CRD cases that run on mock: four pass, one
fails on purpose and one declares no expectation. A test runs it through the real case runner.

- Columns: `caseId`, `protocol` (crd, dtr, pas or chain), `hook`, `operation`, `chain`,
  `memberId`, `npi`, `procedureCodes` (several separated by `|`), `dateOfService` (YYYY-MM-DD only),
  `expected`, `scenarioId`, `testerRef`, `note`, `claimId`, `subscriptionId`. A column whose name
  starts with `#` is yours and never read. Any other unknown column stops the file, because a
  misspelt `expected` would otherwise turn every row into an unverified one.
- The file must be UTF-8. In a spreadsheet, save as "CSV UTF-8".
- `POST /api/suites/preview` (History, Suite runs, on choosing a file) says what the tool makes of
  a file without sending anything: how many cases can run, and every problem at once.
- One suite runs at a time. Writes against production are reported as blocked, case by case, and
  counted apart from failures.
- A suite is pinned to the configuration it started under. If the configuration changes while it
  runs (an upload, a removal, a switch of environment), it stops and says so, and the cases left
  are marked as not run ("The configuration was changed while the suite was running.", seen on
  an earlier revision, not again for this one; the wording was read in the code for this one).
- Every case writes its own ledger line. The summary counts the cases by result, each group
  labelled ("Passed", "Failed", "Unverified: nothing was declared to compare with", and the groups
  for blocked, not valid, not configured and not run). Results can be exported as CSV, and a run can
  be compared with an earlier one of the same file.
- The tool holds its latest ten suite runs until it restarts; every case stays in the ledger under
  its suite run id.

**A chain** is one case carried through CRD, DTR and PAS. On the screens, tick `Run as part of a
chain` on the CRD tab and follow the next step from tab to tab; through the API, send a run with
`"chainId":"new"`, and later runs sent with the id the result carries join it. History, Chains, or
`GET /api/chains/{chainId}`, shows each step, its outcome and what comes next, read back from the
ledger. A whole chain runs unattended as a suite row with the protocol `chain`, which writes a line
of its own with the chain's result.

## Endpoints

Every endpoint answers JSON in one shared error shape, never an HTML error page. The statuses:

- **200** for a completed call, **including one where the service returned a fault**: the fault is
  described in the body, with the request that caused it.
- **422** when the form failed validation (and for an unknown property in a request body, which is
  named); on live, and for `POST /api/raw` on mock too, when a placeholder the request needs has
  no value (`"kind":"CONFIGURATION"`, naming the variable and the active environment; see
  [Variables](#variables)); for `POST /api/raw` when a `{part}` of the url has no value in
  `urlValues` (`"kind":"VALIDATION"`, field `urlValues.<name>`); when the path of a url would hold
  an encoded separator; when a note, a tester reference or an expectation holds member data
  the request carries; when a JSON request body repeats a key inside one object ("The request
  repeats the field "member" inside one object. Nothing was sent."); and for a request body that
  is not valid JSON. A body of your own (`rawBody`) is text and is sent as written.
- **403** when the production guardrail blocked a write (also a write to an address the tool
  cannot read), or when the request came from another site or a host name the tool does not answer
  to.
- **409** when no url is configured for an operation, the configuration changed since the screen
  was drawn, or production writes were locked between preparing a write and sending it.
- **400** for an address with an unencoded semicolon or an encoded slash in its path (observed:
  `/api/runs/a;b` and `/api/members/S1%2F2`); use the query form of a lookup for such an ID.
- **405** for a method the endpoint does not take (observed: `TRACE /api/mode`), **406** when the
  client asks for a type other than JSON (observed: `Accept: application/xml`), **413** for a body
  over the 30MB limit (observed with an upload of about 31.5 MB), and **415** for a body of another
  type (observed: `text/plain` to `POST /api/crd`). Each is JSON in the same error shape, kind
  `VALIDATION`.
- **202** when a suite is started: it runs in the background.
- **404** for an unknown run, environment, collection or address.
- **500** when PAVE itself failed after the call was made: the body still carries the result.
- **Lookups always answer 200** once a lookup was made: not found is a normal answer.

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/mode` | Tool name, version, port, mode, target, active environment, configuration epoch, write access |
| POST | `/api/config/environments` | Upload a Postman environment (multipart: `file`, `kind`, optional `acknowledgeNotProd`) |
| GET | `/api/config/environments` | List environments with kind and active flag |
| POST | `/api/config/environments/{name}/activate` | Switch the active environment |
| DELETE | `/api/config/environments/{name}` | Remove an environment |
| POST | `/api/config/collections` | Upload a collection (multipart: `file`) |
| GET | `/api/config/collections` | List collections with the detected mapping |
| GET | `/api/config/collections/{id}/requests/{requestId}` | One request in full: headers, body, scripts, saved examples |
| PUT | `/api/config/collections/{id}/mapping` | Correct a mapping |
| DELETE | `/api/config/collections/{id}` | Remove a collection |
| POST | `/api/config/writes` | Unlock or lock production writes for this session |
| GET | `/api/config/effective` | Everything in force, secrets masked |
| POST | `/api/config/resolve` | What a request would become, without sending it |
| GET | `/api/members/{memberId}` | Member lookup |
| GET | `/api/members?memberId=` | The same, with the ID in the query, for an ID that holds a slash or a backslash |
| GET | `/api/providers/{npi}` | Provider lookup (the NPI check digit is checked first) |
| GET | `/api/providers?npi=` | The same, with the NPI in the query |
| GET | `/api/procedure-codes?q=&lob=` | Code type ahead |
| GET | `/api/procedure-codes/self-check` | What the tool made of the code list |
| POST | `/api/crd` | Run one CRD call |
| POST | `/api/crd/preview` | The CRD request, without sending it |
| POST | `/api/crd/compare` | Compare a pasted CRD request (`rawBody`) with the one this tool builds for the same case. Sends and records nothing |
| GET | `/api/crd/discover` | CDS Hooks discovery |
| GET | `/api/crd/scenarios` | CRD scenarios (mock) and the outcome vocabulary |
| POST | `/api/dtr/package` | `$questionnaire-package` |
| POST | `/api/dtr/package/preview` | The package request, without sending it |
| POST | `/api/dtr/next-question` | One step of the adaptive loop |
| POST | `/api/dtr/next-question/preview` | The next-question request, without sending it |
| POST | `/api/dtr/response` | Complete the QuestionnaireResponse (sends nothing) |
| GET | `/api/dtr/scenarios` | DTR scenarios (mock) and the outcome vocabulary |
| POST | `/api/pas/submit` | `Claim/$submit` |
| POST | `/api/pas/submit/preview` | The submission, without sending it |
| POST | `/api/pas/inquire` | `Claim/$inquire` |
| POST | `/api/pas/inquire/preview` | The inquiry, without sending it |
| POST | `/api/pas/subscriptions` | Create a subscription |
| POST | `/api/pas/subscriptions/preview` | The create request, without sending it |
| GET | `/api/pas/subscriptions` | List subscriptions |
| GET | `/api/pas/subscriptions/{id}` | Read one subscription |
| PUT | `/api/pas/subscriptions/{id}` | Update a subscription |
| POST | `/api/pas/subscriptions/{id}/preview` | The update request, without sending it |
| DELETE | `/api/pas/subscriptions/{id}` | Delete a subscription |
| POST | `/api/pas/notifications` | **The receiver.** The payer posts here. Always 200, no body |
| GET | `/api/pas/notifications?since=` | Notifications received, correlated to runs |
| POST | `/api/pas/notifications/sample` | Deliver a sample notification (mock only) |
| GET | `/api/pas/scenarios?for=` | PAS scenarios (mock) and the outcome vocabulary |
| POST | `/api/raw` | Send one collection request as it is written: its method, url and headers, and `rawBody` if given, else the saved body; `urlValues` fills `{parts}` of the url |
| POST | `/api/raw/preview` | The same, without sending it |
| GET | `/api/runs?from=&to=&protocol=&operation=&result=&outcome=&chainId=&suiteRunId=&limit=&testerRef=&follows=&zone=` | Query the ledger |
| GET | `/api/runs/{runId}` | One run |
| POST | `/api/runs/{runId}/note` | Add or change a note |
| GET | `/api/runs/export.csv?from=&to=&protocol=&result=&zone=` | CSV for sign-off |
| POST | `/api/runs/replay` | Run again and compare (`runIds`, or `from` and `to` with an optional `zone`) |
| GET | `/api/runs/health` | Is tracking alive, runs recorded today |
| GET | `/api/chains?from=&to=&limit=&zone=` | Chains, newest first |
| GET | `/api/chains/{chainId}` | One chain, step by step |
| POST | `/api/suites/preview` | What the tool makes of a suite file (multipart: `file`) |
| POST | `/api/suites` | Start a suite (multipart: `file`, optional `testerRef`, `compareWith`, `configEpoch`); answers 202 |
| GET | `/api/suites` | Suite runs held in memory (the latest ten, until a restart) |
| GET | `/api/suites/{suiteRunId}?rows=` | One suite run |
| POST | `/api/suites/{suiteRunId}/stop` | Stop a running suite |
| GET | `/api/suites/{suiteRunId}/results.csv` | Suite results as CSV |

### Examples, on mock

Run them in this order from `epa-pave/`, with the tool started by `./run.sh`. They upload the two
sample Postman files first, and every identifier in them is a placeholder: `P32H-...` sentinels,
`S123456789`-style member IDs and the NPIs `1234567893` and `9999999995`. Where an example says `RUN_ID`,
`CHAIN_ID` or `SUITE_RUN_ID`, put in the id from an earlier answer (`runId` on any run result,
`chainId` on the chain example, `suiteRunId` on the suite example). Two of them save a CSV file in
the current directory (`pave-runs.csv`, `suite-results.csv`). Version control does not ignore
those, so delete them when you are done; on live they hold ledger data. Each was run as written,
with those three ids put in, in this order, against a freshly started tool; the status each
answered is in the comment above it.

<!-- examples:start -->

Mode and configuration:

```bash
# 200
curl -s localhost:8057/api/mode
# 200
curl -s -F file=@samples/postman/pave-sample-sandbox.postman_environment.json -F kind=sandbox localhost:8057/api/config/environments
# 200
curl -s localhost:8057/api/config/environments
# 200
curl -s -X POST localhost:8057/api/config/environments/pave-sample-sandbox-977ed8ee/activate
# 200
curl -s -F file=@samples/postman/pave-sample.postman_collection.json localhost:8057/api/config/collections
# 200
curl -s localhost:8057/api/config/collections
# 200
curl -s localhost:8057/api/config/collections/pave-sample-collection-632d456c/requests/r-3c98d3437327
# 200, "changed":true: the request "order-sign Error" is now mapped to CRD order-select
curl -s -X PUT -H 'Content-Type: application/json' -d '{"requestId":"r-b740dcfeaff4","operation":"CRD_ORDER_SELECT"}' localhost:8057/api/config/collections/pave-sample-collection-632d456c/mapping
# 200, "changed":true: mapped back to what detection proposed, so the correction is removed
curl -s -X PUT -H 'Content-Type: application/json' -d '{"requestId":"r-b740dcfeaff4","operation":"CRD_ORDER_SIGN"}' localhost:8057/api/config/collections/pave-sample-collection-632d456c/mapping
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"action":"lock"}' localhost:8057/api/config/writes
# 200
curl -s localhost:8057/api/config/effective
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"operation":"PAS_SUBMIT"}' localhost:8057/api/config/resolve
```

Lookups and the code list:

```bash
# 200, FOUND
curl -s localhost:8057/api/members/P32H-PA-REQ
# 200, FOUND: typed with spaces, sent without them, both forms in the answer
curl -s 'localhost:8057/api/members/S12345678%20%20%2001'
# 200, FOUND: the query form
curl -s 'localhost:8057/api/members?memberId=P32H-PA-REQ'
# 200, NOT_FOUND
curl -s localhost:8057/api/members/P32H-NOMEM
# 200, FOUND
curl -s localhost:8057/api/providers/1234567893
# 200, NOT_FOUND: the NPI that selects "provider not found"
curl -s 'localhost:8057/api/providers?npi=9999999995'
# 422: the check digit does not match, nothing was looked up
curl -s localhost:8057/api/providers/1234567890
# 200
curl -s 'localhost:8057/api/procedure-codes?q=9581&lob=Medicare'
# 200
curl -s localhost:8057/api/procedure-codes/self-check
```

CRD:

```bash
# 200: outcome NO_AUTH_REQUIRED, test result pass
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-NO-AUTH"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"dateOfService":"2026-03-02","expected":"NO_AUTH_REQUIRED"}' localhost:8057/api/crd
# 200: outcome NO_DETERMINATION, which is not "not required"
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-EMPTY"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"dateOfService":"2026-03-02"}' localhost:8057/api/crd
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PA-REQ"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}]}' localhost:8057/api/crd/preview
# 200: compares a pasted request with the tool's own for the same case; nothing is sent or recorded
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PA-REQ"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"rawBody":"{\"hook\":\"order-sign\",\"hookInstance\":\"placeholder-hook-instance\",\"context\":{}}"}' localhost:8057/api/crd/compare
# 200
curl -s localhost:8057/api/crd/discover
# 200
curl -s localhost:8057/api/crd/scenarios
# 200: starts a chain; the answer carries its chainId
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DOC-DTR"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"dateOfService":"2026-03-02","chainId":"new"}' localhost:8057/api/crd
```

DTR:

```bash
# 200: outcome PACKAGE_LOADED
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DOC-DTR"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"dateOfService":"2026-03-02"}' localhost:8057/api/dtr/package
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DOC-DTR"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}]}' localhost:8057/api/dtr/package/preview
# 200: the adaptive scenario asks its first question
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DTR-ADAPT"},"provider":{"npi":"1234567893"},"scenarioId":"dtr-02-adaptive-branching","questionnaire":"{\"resourceType\":\"Questionnaire\",\"url\":\"https://questionnaire.example.invalid/Questionnaire/adaptive\",\"status\":\"active\",\"meta\":{\"profile\":[\"http://hl7.org/fhir/uv/sdc/StructureDefinition/sdc-questionnaire-adapt\"]}}"}' localhost:8057/api/dtr/next-question
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DTR-ADAPT"},"provider":{"npi":"1234567893"},"questionnaire":"{\"resourceType\":\"Questionnaire\",\"url\":\"https://questionnaire.example.invalid/Questionnaire/adaptive\",\"status\":\"active\"}"}' localhost:8057/api/dtr/next-question/preview
# 200: the response is complete and PAS is the next step. Nothing is sent
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-DOC-DTR"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"questionnaire":"{\"resourceType\":\"Questionnaire\",\"url\":\"https://questionnaire.example.invalid/Questionnaire/sample\",\"status\":\"active\",\"item\":[{\"linkId\":\"1\",\"text\":\"Symptoms for more than three months?\",\"type\":\"boolean\",\"required\":true}]}","answers":[{"linkId":"1","values":["true"]}]}' localhost:8057/api/dtr/response
# 200
curl -s localhost:8057/api/dtr/scenarios
```

PAS, subscriptions and notifications:

```bash
# 200: authorization outcome DENIED, test result pass
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PAS-DENIED"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"dateOfService":"2026-03-02","expected":"DENIED"}' localhost:8057/api/pas/submit
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PAS-APPROVED"},"provider":{"npi":"1234567893"},"procedureCodes":[{"code":"95810"}],"dateOfService":"2026-03-02"}' localhost:8057/api/pas/submit/preview
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PAS-PENDED"},"provider":{"npi":"1234567893"},"claimId":"sample-claim-1"}' localhost:8057/api/pas/inquire
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"member":{"memberId":"P32H-PAS-PENDED"},"provider":{"npi":"1234567893"},"claimId":"sample-claim-1"}' localhost:8057/api/pas/inquire/preview
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{}' localhost:8057/api/pas/subscriptions
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{}' localhost:8057/api/pas/subscriptions/preview
# 200
curl -s localhost:8057/api/pas/subscriptions
# 200
curl -s localhost:8057/api/pas/subscriptions/placeholder-subscription-1
# 200
curl -s -X PUT -H 'Content-Type: application/json' -d '{}' localhost:8057/api/pas/subscriptions/placeholder-subscription-1
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{}' localhost:8057/api/pas/subscriptions/placeholder-subscription-1/preview
# 200
curl -s -X DELETE localhost:8057/api/pas/subscriptions/placeholder-subscription-1
# 200 with no body: the receiver answers 200 to anything and records it
curl -s -X POST -H 'Content-Type: application/fhir+json' -d '{"resourceType":"Bundle","type":"history","entry":[]}' localhost:8057/api/pas/notifications
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"scenarioId":"pas-12-notification-unmatched"}' localhost:8057/api/pas/notifications/sample
# 200
curl -s localhost:8057/api/pas/notifications
# 200
curl -s 'localhost:8057/api/pas/scenarios?for=submit'
```

Sending a collection request as it is written (method, url and headers; `rawBody` if you give one, else the body saved with the request):

```bash
# 200: on mock every raw request gets the same fixed answer, which says the mock does not model it
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-6d8194d8fa41"}}' localhost:8057/api/raw
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-6d8194d8fa41"}}' localhost:8057/api/raw/preview
# 200: the preview names the saved body, "because none was supplied for this run" (bodySource saved)
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-e8574dbaa06d"}}' localhost:8057/api/raw/preview
# 200: with rawBody, the preview shows that body in place of the saved one (bodySource supplied)
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-e8574dbaa06d"},"rawBody":"{\"resourceType\":\"Bundle\",\"type\":\"collection\"}"}' localhost:8057/api/raw/preview
```

The ledger and chains:

```bash
# 200
curl -s 'localhost:8057/api/runs?protocol=CRD&limit=5'
# 200: the days of the range on a clock other than UTC
curl -s 'localhost:8057/api/runs?from=2026-03-01&to=2026-03-31&zone=America/New_York'
# 200
curl -s localhost:8057/api/runs/RUN_ID
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"note":"Checked for ticket T-0001","testerRef":"SAMPLE"}' localhost:8057/api/runs/RUN_ID/note
# 200: writes pave-runs.csv in the current directory, the CRD runs only
curl -s -o pave-runs.csv 'localhost:8057/api/runs/export.csv?protocol=CRD'
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"runIds":["RUN_ID"]}' localhost:8057/api/runs/replay
# 200
curl -s localhost:8057/api/runs/health
# 200
curl -s localhost:8057/api/chains
# 200
curl -s localhost:8057/api/chains/CHAIN_ID
```

Suites:

```bash
# 200
curl -s -F file=@samples/suites/sample-suite.csv localhost:8057/api/suites/preview
# 202: the answer carries the suiteRunId, with the state RUNNING
curl -s -F file=@samples/suites/sample-suite.csv localhost:8057/api/suites
# 200
curl -s localhost:8057/api/suites
# 200: ask again until the state is COMPLETED (a few seconds on mock)
curl -s localhost:8057/api/suites/SUITE_RUN_ID
# 200: writes suite-results.csv in the current directory
curl -s -o suite-results.csv localhost:8057/api/suites/SUITE_RUN_ID/results.csv
# 200: a suite that has ended is returned as it is
curl -s -X POST localhost:8057/api/suites/SUITE_RUN_ID/stop
```

Removing what was uploaded:

```bash
# 200
curl -s -X DELETE localhost:8057/api/config/collections/pave-sample-collection-632d456c
# 200
curl -s -X DELETE localhost:8057/api/config/environments/pave-sample-sandbox-977ed8ee
```

<!-- examples:end -->

## Properties

Every property in `src/main/resources/application.yml`, with its default. Any of them can be
given on the command line (`./run.sh mock --tracking.directory=/data/pave-runs`) or as an
environment variable in Spring's usual form (`TRACKING_DIRECTORY`). `GET /api/config/effective`
shows what is in force, secrets masked.

| Property | Default | What it does |
| --- | --- | --- |
| `spring.profiles.default` | `mock` | The profile when none is given. `./run.sh live` selects `live` |
| `spring.servlet.multipart.max-file-size` | `25MB` | Largest uploaded file. A collection can be several megabytes |
| `spring.servlet.multipart.max-request-size` | `30MB` | The one limit on any request body, upload or JSON (D-149). A negative value means no limit |
| `spring.servlet.multipart.file-size-threshold` | `30MB` | Equal to the limit, so no uploaded part is ever written to the temporary folder |
| `spring.servlet.multipart.resolve-lazily` | `true` | Only an endpoint that takes a file reads a multipart body |
| `spring.mvc.formcontent.filter.enabled` | `false` | No form body is read for a PUT or a DELETE |
| `server.port` | `8057` | The port. Not 8080, so PAVE can run beside the tools that use 8080 |
| `server.address` | `127.0.0.1` | Listen on this machine only. Not to be changed before PAVE has a sign-in (D-270). See [Who can reach this tool](#who-can-reach-this-tool) |
| `server.servlet.session.cookie.name` | `PAVESESSION` | A name of its own, so other tools on localhost do not overwrite PAVE's session |
| `server.servlet.session.cookie.same-site` | `strict` | The production unlock lives in the session; no other site can borrow it |
| `server.servlet.session.cookie.http-only` | `true` | No script reads the session cookie |
| `server.servlet.session.tracking-modes` | `cookie` | The session is never taken from the address |
| `server.error.whitelabel.enabled` | `false` | No HTML error page; errors are JSON |
| `logging.level.org.apache.coyote.http11.Http11Processor` | `WARN` | Keeps a refused address, which can hold a member ID, out of the log (D-128) |
| `logging.level.org.springframework.web.servlet.resource.ResourceHandlerUtils` | `ERROR` | The same, for a path with `../` in it |
| `logging.level.org.apache.catalina.webresources.AbstractFileResourceSet` | `ERROR` | The same, for a static file path |
| `logging.level.org.apache.tomcat.util.http.parser.Cookie` | `WARN` | Keeps an unreadable cookie out of the log (D-150) |
| `logging.level.org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver` | `ERROR` | Keeps request text out of the log when an error is answered (D-150) |
| `epa.name` | `PAVE` | Shown in the tab title, on screen and by `/api/mode` |
| `epa.version` | `1.0.0` | The same. A test checks it matches the build |
| `epa.target` | `onyx` | `onyx` or `nehen`. Recorded and shown; it changes no behaviour (D-031) |
| `epa.mode` | `mock` | Follows the profile (`application-live.yml` sets `live`). Do not set by hand: the tool refuses to start when it disagrees with the profile |
| `config-store.directory` | `./config-store` | Where uploaded Postman files and the store's own state are kept |
| `config-store.active-environment` | `""` | An environment to activate at every start. Blank keeps the last one active |
| `config-store.allow-prod-writes` | `false` | The floor. While false, no production write can be unlocked |
| `tracking.enabled` | `true` | Write the ledger |
| `tracking.directory` | `./run-history` | Where the ledger files go |
| `tracking.record-member-id` | `false` (`true` on mock) | Record member IDs in the ledger. False keeps a salted token and the last four characters |
| `tracking.record-npi` | `true` | Record the NPI |
| `tracking.salt-file` | `""` | Blank: `installation-salt` inside the config store directory. Never inside the ledger folder. The salt is a secret: never copy it with the ledger |
| `tracking.slow-threshold-ms` | `0` | A completed call slower than this is flagged as slow. 0 uses `onyx.read-timeout-ms` |
| `codelist.file` | `""` | The code list export. Blank uses the bundled placeholder |
| `codelist.format` | `auto` | `auto`, `csv` or `json` |
| `codelist.delimiter` | `,` | One character, or `tab` |
| `app.scenario-dir` | `""` | A folder of scenario files that replaces the bundled ones, read from `<dir>/crd/`, `<dir>/dtr/` and so on |
| `app.autocomplete-limit` | `25` | How many codes the picker offers at once |
| `app.mock-latency-enabled` | `true` | Add the scenario's delay on mock |
| `app.allowed-hosts` | `[]` | Host names, besides localhost, the tool answers to |
| `onyx.crd.url` | `""` | Fallback base url for CRD when no collection request is mapped |
| `onyx.dtr.url` | `""` | Fallback base url for DTR |
| `onyx.pas.url` | `""` | Fallback base url for PAS |
| `onyx.pas.attachment-mode` | `fhir` | `fhir` carries the document in the bundle; `x12-275` only names it on the claim and sends nothing else |
| `onyx.subscription.url` | `""` | Fallback base url for subscriptions |
| `onyx.subscription.callback-url` | `""` | The address the payer should call back, written into a subscription |
| `onyx.connect-timeout-ms` | `5000` | Connect timeout for the protocol calls. Zero is refused at startup |
| `onyx.read-timeout-ms` | `15000` | Read timeout for the protocol calls. Zero is refused at startup |
| `onyx.auth.enabled` | `false` | Request an OAuth2 client credentials token for the Onyx calls |
| `onyx.auth.token-url` | `""` | Token endpoint. May be a placeholder such as `{{tokenUrl}}` |
| `onyx.auth.client-id` | `""` | Client id. May be a placeholder |
| `onyx.auth.client-secret` | `""` | Client secret. Use a placeholder or `ONYX_AUTH_CLIENT_SECRET`; never commit one |
| `onyx.auth.scopes` | `""` | Scopes to ask for |
| `onyx.variables` | `{}` | Last-resort values for placeholders. Use `{{env:PAVE_NAME}}` for a secret here |
| `lookup.member.url` | `""` | Member API url with `{id}` in the path or the query. Blank means lookup is disabled on live |
| `lookup.member.enabled` | `true` | Switch member lookup off; a tester then types the details |
| `lookup.member.connect-timeout-ms` | `3000` | Shorter than the protocol timeouts, so a lookup gives up quickly |
| `lookup.member.read-timeout-ms` | `5000` | The same |
| `lookup.member.headers` | `{}` | Extra headers, for an API that wants a key. Values may be `{{env:PAVE_NAME}}` |
| `lookup.provider.*` | as for member | The same five settings for the provider directory |

Not in `application.yml` but read when set: `lookup.member.use-onyx-auth` and
`lookup.provider.use-onyx-auth` (`false`; true sends the Onyx access token with the lookup),
`codelist.columns.<column>` (candidate header names for one column) and
`codelist.line-of-business-aliases`.

## Running on a corporate workstation

The build has to work on a P32H laptop behind the corporate network, where `repo.maven.apache.org`
and `services.gradle.org` may both be blocked.

The checks quoted in this section were made on an earlier revision, when this README was first
written (see the last section). `settings.gradle`, `gradle.properties`, the wrapper and the
repository settings of `build.gradle` have not changed since (only the house style gate in
`build.gradle` has), and the checks were not repeated for this revision.

### Dependencies through an internal mirror

Set `internalRepoUrl` in `gradle.properties` beside `build.gradle`, or better in
`~/.gradle/gradle.properties` so it is not committed:

```properties
internalRepoUrl=https://your-mirror.example.invalid/maven2
# Only if the mirror asks for credentials:
internalRepoUser=your-user
internalRepoPassword=your-password
```

One property switches both the dependencies (`build.gradle`) and the Gradle plugins
(`settings.gradle`). Checked: with `-PinternalRepoUrl=https://mirror.example.invalid/maven2` the
build looked for the Spring Boot plugin there and nowhere else ("Searched in the following
repositories: maven(https://mirror.example.invalid/maven2)"), and with
`-PinternalRepoUrl=https://repo.maven.apache.org/maven2` it resolved everything from that url.
**Not checked:** a mirror that needs credentials. Keep the password out of any file that is
committed.

### The Gradle distribution

`./gradlew` downloads Gradle 9.5.0 from `services.gradle.org` the first time. If that is blocked
the wrapper cannot start at all, whatever the dependency mirror says. Two ways round it:

- Point the wrapper at an internal copy of the same file. In
  `gradle/wrapper/gradle-wrapper.properties` change the line to your mirror's address of
  `gradle-9.5.0-bin.zip`:

  ```properties
  distributionUrl=https\://your-mirror.example.invalid/gradle/gradle-9.5.0-bin.zip
  ```

  Checked with a `file:` url pointing at a downloaded copy: the wrapper fetched it from there and
  started Gradle 9.5.0.
- Or use a Gradle 9.5 that is already installed, in place of the wrapper: `gradle clean build`.
  Checked with Gradle 9.5.0. An older installed Gradle was not tried for a build.

### TLS behind an inspection proxy

A corporate proxy that inspects TLS presents its own certificate, which Java does not trust by
default, and the download fails with `PKIX path building failed`. Add the corporate root
certificate to a trust store and point Java at it:

```
keytool -importcert -alias corporate-root -file corporate-root.crt -keystore ~/pave-truststore.jks -storepass changeit
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=$HOME/pave-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit"
./gradlew clean build
```

`JAVA_TOOL_OPTIONS` is read by every Java process, including the wrapper, the Gradle daemon and
PAVE itself. The machine this README was checked on works exactly this way: its
`JAVA_TOOL_OPTIONS` sets `-Djavax.net.ssl.trustStore` to a trust store holding the inspecting
proxy's certificate, and every build and download above went through it. The `keytool` line itself
was not run there. To limit it to Gradle, put the same two settings in `~/.gradle/gradle.properties`
as `systemProp.javax.net.ssl.trustStore=...` and `systemProp.javax.net.ssl.trustStorePassword=...`
(not checked here).

### STS (Spring Tools for Eclipse)

**Seen on Linux with Spring Tools 5.4.0, not on Windows.** What follows was seen in the real IDE,
Spring Tools 5.4.0.RELEASE (Eclipse 4.41.0, that is 2026-09, with Buildship 3.1.11) on Linux, driven
with keyboard and mouse by agents that wrote none of the tool, on `e70a873` and on `ace5914`, which
adds the launch configuration and the test changes named below (D-272), and on `d089e65`, cloned by
STS itself from GitHub (D-273). Nobody who writes this README has seen it on Windows or with another
release; what one Windows workstation reported is at the end of this section. The steps, in order:

**1. Which Spring Tools.** Use Spring Tools 5 for Eclipse. PAVE is a Spring Boot 4 application, and the
Spring Tools changelog gives 5.0.0 (2025-12-10, built on Eclipse 2025-12) as the first release that
states Spring Boot 4 support. The Spring Tools FAQ says that Spring Tools 5 does not run on an Eclipse
older than 2025-12 and that Spring Tools 4 gets no more updates. Both were read on 2026-11-20 in the
Spring Tools wiki (its Changelog and FAQ pages). STS runs on a JDK 25 of its own. Where a JDK 21 is
installed as step 1 of the checklist says, STS found it by itself at startup (Window, Preferences,
Java, Installed JREs) and every launch ran on it.

**What the machine needs: STS 5 alone is enough, since D-274.** Where Gradle finds no JDK 21, the
build compiles for Java 21 with the Java that runs Gradle, which in STS 5 is the Java 25 that STS
brings, and says so in one line: "Gradle found no JDK 21, so PAVE is compiled for Java 21 with the
Java that runs Gradle: ...". In the IDE that line is only in the Console view, under "Gradle
Operations", and a default import leaves that view closed. Seen on Linux with STS 5.4.0, the
machine's JDK 21 hidden from that one IDE and an empty Gradle home, on `6201644` (a simulation): the
import with every default of the wizard gave `epa-pave [boot]` with "0 errors, 25 warnings, 59
others" and "JRE System Library [JavaSE-21]" bound to that Java 25; the first press of start in the
Boot Dashboard started the tool on it with no dialog ("Starting PaveApplication using Java 25.0.4.1");
both proofs of "Prove it before handover" held; and the four test classes run from the IDE gave what
they give on a JDK 21 (step 7). The commit after it, `647b102`, changed the wording of that line and
added one case in which the build stops; it was run with Gradle from a terminal on the same Java 25,
and not in the IDE. With a JDK 21 on the machine nothing changed: seen in the same STS on `6201644`,
where the tool and the tests ran on the JDK 21 as before and the line appeared nowhere.

**On a checkout from before that change, STS alone is not enough.** This was simulated on Linux
with STS 5.4.0, the machine's JDK 21 hidden from that one IDE and an empty Gradle home (D-273):
Installed JREs then listed only `jre`, the Java 25 inside STS. The import ran Gradle 9.5.0 on that Java
25 and downloaded the Gradle distribution and the dependencies, then ended about 10 seconds after
Finish with no dialog. The project it left was `epa-pave [Interop epa-pave]` with an error marker and
no `[boot]`: no source folders, no JRE System Library, nothing under `local` in the Boot Dashboard and
no tasks in the Gradle Tasks view, so the tool could not be started. The Problems view read "1 error,
1 warning, 0 others"; the error, of type "Gradle Problem" at "line 0", reads:

```
The supplied phased action failed with an exception.
Could not resolve all dependencies for configuration ':houseStyleCompileClasspath'.
Failed to calculate the value of task ':compileHouseStyleJava' property 'javaCompiler'.
Cannot find a Java installation on your machine (<your system>) matching: {languageVersion=21, vendor=any vendor, implementation=vendor-specific, nativeImageCapable=false}. Toolchain download repositories have not been configured.
```

At that checkout `build.gradle` asked for a Java 21 toolchain and nothing else, and the build
configures no place to download one from, so Gradle cannot fetch a JDK 21 by itself. The message
names the house style source set, but it is not about the house style: from a terminal with only
Java 25, `./gradlew classes` failed with the same sentence, naming `':compileJava'`. So on such a
checkout get the later commits (step 8), or install a JDK 21 before the import. On the machines seen,
a JDK 21 installed in the system's usual place (on Linux under `/usr/lib/jvm`) was found by STS and by
Gradle with no setting; STS looks for installed Javas at startup ("Detect available JVM installations
at startup", ticked, under Installed JREs). Otherwise give Gradle its Java home as under "If the
import fails" below. A machine that has a Java 17 and no 21 was not tried.

On the Linux machine seen, with no WebKit installed, STS's Tip of the Day dialog failed ("No more
handles because there is no underlying browser available"), followed by "An SWT error has occurred
... Do you want to exit the workbench?"; the answer was No, and after the package
`libwebkit2gtk-4.1-0` was installed the run went on. A plain Eclipse showed a "Chromium Additional
Terms of Service" dialog at its first start; Cancel was pressed, with no effect on the project.

**2. Get the code.** Two ways. Either way, what STS must import in the end is the folder `epa-pave`
inside your clone, with the clone on the branch `epa-pave`. The top folder of the repository is
another application, with a Maven build and no Gradle wrapper, and the repository's default branch,
`claude/cms1500-form-filler-pdf-1wploq`, has no `epa-pave` folder at all.

(a) **From a terminal**, as steps 2 and 3 of the [First run checklist](#first-run-checklist) say
(`git clone --branch epa-pave ...`, then `./gradlew clean build` in `epa-pave/`), so that you know the
build works on your machine before the IDE is involved. Then go on with step 3.

(b) **From inside STS, with the Git import wizard.** Seen on Linux on `d089e65`: STS cloned straight
from GitHub and asked for no credential. STS had been started from a shell whose proxy settings its
Java picked up (see [TLS behind an inspection proxy](#tls-behind-an-inspection-proxy)); what a
workstation behind a corporate proxy asks for was not seen. Two of the wizard's choices are wrong for
this project, at 4 and 6 below.
1. File, Import..., type `Git` in "Select an import wizard:", select "Projects from Git (with smart
   import)" and click Next >.
2. "Select Repository Source": select "Clone URI", Next >.
3. "Source Git Repository": paste `https://github.com/thehiddenbrain/Interop.git` into "URI:". "Host:"
   and "Repository path:" fill in by themselves. Leave "User:" and "Password:" empty. Next >.
4. "Branch Selection" lists nine branches, all ticked. Click "Deselect All", tick only `epa-pave`,
   Next >.
5. "Local Destination": "Initial branch:" must read `epa-pave`. With only `epa-pave` ticked it changes
   to that by itself. With every branch left ticked it reads `claude/cms1500-form-filler-pdf-1wploq`,
   which has no `epa-pave` folder; choosing `epa-pave` in that drop-down also works. "Directory:"
   defaults to `git/Interop` in your user folder; change it if you want the clone elsewhere. Next >
   clones, in a few seconds on the machine seen.
6. The window becomes "Import Projects from File System or Archive". **Do not press Finish yet.** Its
   list has one row, `Interop` imported as `Maven`, ticked and greyed out: it cannot be unticked, and
   `epa-pave` is never listed, not after waiting and not with "Search for nested projects" ticked.
   **That row is the other application, not PAVE.** Add `/epa-pave` to the end of the path in "Import
   source:" and press Tab: the only row becomes `epa-pave` (its "Import as" column stays empty).
7. Finish. After about a minute, once the status bar no longer shows "Synchronize Gradle project", the
   Package Explorer shows `epa-pave [boot] [Interop epa-pave]`, a Gradle project as step 3 would make
   it. Skip step 3 and go on with step 4. Started from the Boot Dashboard (step 5), it passed both
   proofs of "Prove it before handover" on the machine seen.

**Already pressed Finish on the `Interop` row?** Then the Package Explorer holds one project, the top
folder of your clone (named after that folder), shown as a Maven and Boot project with "JRE System
Library [JavaSE-17]", "Maven Dependencies", `pom.xml`, and `epa-pave` as a plain folder inside it.
The Problems view shows about 861 errors, which belong to the other application, and the Boot
Dashboard lists only that project under `local`. From exactly that state, on Linux, these steps got
the tool running:
1. Window, Show View, Other..., type `Git`, select "Git Repositories" under Git, and press Open.
2. Expand your repository (it reads `> <name> [epa-pave] - <the folder of your clone>/.git`; the `>`
   means files git does not track, here the ones the Maven import left), then "Working Tree".
3. Right click the folder `epa-pave` under "Working Tree", then Projects, Import Projects....
4. "Import Projects from File System or Archive" opens with "Import source:" already ending in
   `/epa-pave` and one row, `epa-pave`, ticked. Press Finish and wait until the status bar is idle. A
   second project appears, `epa-pave [boot] [<name> epa-pave]`: a Gradle project, listed in the
   Gradle Tasks view, with 0 errors and 25 warnings of its own.
5. Be rid of the second `PaveApplication` entry (see "Two `PaveApplication` entries, and how to be
   rid of one" below), then start `epa-pave` from the Boot Dashboard as in step 5 of this section,
   "Start it".

The right click menus of the folder `epa-pave` in the Package Explorer and the Project Explorer have
no entry that does step 3: their "Import..." opens the general Import wizard with nothing filled in.
File, Import, Gradle, Existing Gradle Project on the same folder (step 3) also worked.
**Do not open Run, Run Configurations before `epa-pave` is imported.** In that state the shared
`PaveApplication` is hidden by the dialog's filter; shown and selected, its "Project" was empty ("No
project selected"), and closing the dialog, without Apply and without any question, rewrote the
tracked file `epa-pave/PaveApplication.launch` with its project line removed. `git checkout --
epa-pave/PaveApplication.launch` in the top folder of the clone put it back.

**Two `PaveApplication` entries, and how to be rid of one.** While the Maven project and `epa-pave`
are both open, the Boot Dashboard shows `epa-pave [0/2]` with two children, both named
`PaveApplication`, and one press of start launches two Java processes: one serves on 8057, the other
stops with "Web server failed to start. Port 8057 was already in use." Run Configurations meanwhile
lists only one `PaveApplication`. Two ways out, each seen with the tool started afterwards (one
process) and both proofs holding:
- **Close Project, the safest.** Right click the Maven project, Close Project. No dialog, and nothing
  is removed. The Boot Dashboard then shows a single `epa-pave`. But Open Project on it brings the
  second entry back.
- **Delete, the lasting way.** Select the Maven project and choose Delete. "Delete Resources" asks
  "Remove project '<name>' from the workspace?" and shows two checkboxes, both unticked: "Delete
  project contents on disk (cannot be undone)" and "Delete 1 nested project". **Leave both
  unticked** and press OK. The first would, by its label, delete your clone from the disk (no run
  ticked it), and with it the tool, because the folder `epa-pave` lies inside that project's
  folder; the second would take the project `epa-pave` out of the workspace too. Afterwards only
  `epa-pave` remains, whole, and the Problems view reads "0 errors, 25 warnings, 59 others".

The Maven import leaves `.classpath`, `.project` and `.settings/` in the top folder of the clone, and
`git status` there lists them as untracked (`??`), because the top folder's `.gitignore` does not
cover them. Removing the project from the workspace does not delete them. They are Eclipse's files for
that project; delete them by hand if you want `git status` clean. What STS writes into `epa-pave`
itself is ignored by `epa-pave/.gitignore`.

**3. Import.** File, Import, Gradle, Existing Gradle Project. Choose the `epa-pave/` directory, not the
repository root, and leave the Import Options page as it is. If "Project root directory" already
holds a path from an earlier import, clear it first: typing into it adds to that path, and the page
then says "Project root directory does not exist." The Import Preview shows "Gradle wrapper from
target build", **Gradle version 9.5.0**, and as Java home the JDK 25 that STS brings (a folder under
its `plugins/` named `org.eclipse.justj.openjdk...`). With a JDK 21 on the machine that is fine:
Gradle ran the import on it with no dialog and no error (without one, see step 1). "Unknown" or any
other Gradle version means something is wrong (below). Press Finish; within about a minute the
Package Explorer shows `epa-pave [boot]` and the workspace build is over.

**If the import fails.** Each of these was seen on Linux with STS 5.4.0, unless it says otherwise.
None of them raised an error dialog: look at the Import Preview, at the label of the project in the
Package Explorer (no `[boot]`) and at the Problems view.
- **"Preview failed", and the error names `gradle-8.9-bin.zip`.** The Import Preview's header reads
  "Preview failed"; Gradle user home directory, Gradle version and Java home directory read
  "Unknown"; Gradle distribution still reads "Gradle wrapper from target build"; and the box under
  "Gradle project structure:" begins
  `org.gradle.tooling.BuildException: Could not fetch model of type 'GradleBuild' using connection to Gradle distribution 'https://services.gradle.org/distributions/gradle-8.9-bin.zip'.`
  *Cause:* the wrong folder. The folder given has no Gradle wrapper, so it is not `epa-pave` (seen
  with the top folder of a clone, on the branch `epa-pave` and on the default branch alike). With no
  wrapper, STS 5.4.0 falls back to the Gradle that its Gradle support (Buildship 3.1.11) carries, 8.9,
  and runs it on the Java 25 inside STS, which Gradle 8.9 cannot run on. That cause is only in the
  "Caused by" lines, at the end of the trace in the workspace's `.metadata/.log`:
  `java.lang.IllegalArgumentException: Unsupported class file major version 69` (69 is Java 25).
  **It is not the network, although it reads like it:** on the machine seen the download of Gradle 8.9
  succeeded before this error. (Where a download itself failed, in the simulation below, the first
  line read "Could not install Gradle distribution from ..." instead.) *What to do:* Cancel, and
  import the `epa-pave` folder of a clone that is on the branch `epa-pave`. Its Import Preview has no
  error: Gradle user home filled in, Gradle version 9.5.0, Java home the JDK 25 inside STS, and
  `epa-pave` under "Gradle project structure:".
- **Finish was pressed on that failed preview.** The Package Explorer shows a project
  `Interop [Interop epa-pave]` with an error marker; it holds the folders of the whole repository and
  is neither a Java nor a Maven project. Problems: "1 error, 1 warning, 0 others", the error ending
  "Unsupported class file major version 69", of type "Gradle Problem". Remove it with Delete, leaving
  "Delete project contents on disk (cannot be undone)" unticked (its contents are your clone), and
  OK. As after the Maven import, `git status` in the top folder then still lists `?? .project` and
  `?? .settings/`; delete them by hand. Then import `epa-pave`.
- **The clone shows under Git Repositories, and no project is in the Package Explorer.** A clone is
  not a project: importing its `epa-pave` folder is a step of its own. In the Git Repositories view
  the repository line shows the branch in brackets; it must read `[epa-pave]`. Then import that
  clone's `epa-pave` folder with step 3 (File, Import, Gradle, Existing Gradle Project). The Git
  Repositories way, steps 3 and 4 of "Already pressed Finish on the `Interop` row?" above, was seen
  only with the top folder already imported; with no project at all it was not tried.
- **The top folder came in as a Maven project, and the Boot Dashboard has no `epa-pave`.** That is
  the wizard's own choice of step 2 (b); see "Already pressed Finish on the `Interop` row?".
- **Two `PaveApplication` entries, "Port 8057 was already in use."** See "Two `PaveApplication`
  entries, and how to be rid of one" under step 2.
- **"Project root directory does not exist."** Next > and Finish stay greyed out. Either the field
  holds an older path with yours added to it (clear it), or the clone is on a branch that has no
  `epa-pave` folder, such as the default branch; check out `epa-pave` (below). The other way round: a
  clone that held `epa-pave` once and was switched to another branch keeps an `epa-pave` folder holding
  only files git ignores (`.classpath`, `.gradle/`, `.project`, `.settings/`, `bin/`). The wizard
  would likely accept that folder, since it exists (not tried); what it would import was not seen.
  Check the branch first.
- **"Gradle is running on Java ..., which is older than 21, and Gradle found no JDK 21. ..."** (or
  "which is a Java runtime without a compiler"). The build's own message since D-274, and it says
  what to do: point Gradle at a JDK 21 or newer. Seen with Gradle from a terminal on Linux, on a Java
  17 and on a Java 21 runtime that has no compiler, each with the JDK 21 hidden; not seen in an IDE,
  where STS 5 runs Gradle on its own Java 25. In that case every Gradle task stops with it, `help`
  and `javaToolchains` included.
- **"Cannot find a Java installation on your machine ... matching: {languageVersion=21, ...}".** A
  checkout from before D-274 and no JDK 21 for Gradle: see "What the machine needs" under step 1.
  After getting the later commits, right click the project and choose Gradle, Refresh Gradle Project;
  whether that alone turns the project left by the failed import into `epa-pave [boot]` was not
  seen. If a JDK 21 is installed and Gradle still does not find it, give Gradle its Java home: tick
  "Override workspace settings" on the Import Options page (Java home is greyed out until then) and
  set Java home to the JDK 21, or set it under Window, Preferences, Gradle. A plain Eclipse 2026-09
  with Java home set under Preferences, Gradle imported the project with 0 errors and 25 warnings;
  in STS neither way was tried. On such a checkout neither helps a machine that has no JDK 21 at
  all.
- **The downloads are refused** (a simulation: STS 5.4.0 on Linux with no network at all, and with no
  JDK 21). With nothing downloaded yet, the Import Preview failed within a second:
  `Could not install Gradle distribution from 'https://services.gradle.org/distributions/gradle-9.5.0-bin.zip'.`,
  caused by `java.net.UnknownHostException: services.gradle.org`. With the Gradle distribution
  already on the machine and the dependencies not, the preview passed and the import then left one
  error on `build.gradle`, line 3: `Plugin [id: 'org.springframework.boot', version: '4.0.7'] was not
  found in any of the following sources:` ... `Searched in the following repositories: MavenRepo,
  Gradle Central Plugin Repository`. It names no host: those are Maven Central and the Gradle plugin
  portal. A corporate network may refuse in another way (a time-out, or a certificate error); that was
  not seen. What helps is under [The Gradle distribution](#the-gradle-distribution) (point the
  wrapper at a copy of `gradle-9.5.0-bin.zip`), [Dependencies through an internal
  mirror](#dependencies-through-an-internal-mirror) (`internalRepoUrl` in `~/.gradle/gradle.properties`,
  the Gradle user home the Import Preview showed) and [TLS behind an inspection
  proxy](#tls-behind-an-inspection-proxy). The first two were checked with Gradle from a terminal and
  the third only in part (see there); from STS none was tried.
- Red markers before Gradle has fetched the dependencies are normal. If they stay, right click the
  project and choose Gradle, Refresh Gradle Project. It was never needed.

**Seeing the clone and its branch in STS.** Window, Show View, Other..., type `Git`, select "Git
Repositories", Open. A repository reads `<name> [epa-pave] - <the folder of your clone>/.git`: its
name, the branch checked out in brackets, and where the clone is; a `>` in front means files git does
not track, such as those a Maven import of the top folder leaves; a down arrow and a number after the
branch mean it is that many commits behind GitHub. "Working Tree - <the folder of your clone>" lists
the clone's files, `epa-pave` among them while that branch is checked out. If the view lists more
than one clone of the repository (the operator reported two, one inside the folder of the other, on
different branches), use the one whose line reads `[epa-pave]` and give the import the `epa-pave`
folder under its path, not the other's. Once a project from the clone is imported, the repository is
listed by itself. If the view is empty, "Add an existing local Git repository" opens "Add Git
Repositories": put the folder of your clone in "Directory:", press Search, tick the repository found
(it is listed unticked, and "Add" stays greyed out until then), and Add. To check out `epa-pave`: expand Branches; under Local, right click `epa-pave` and choose "Check
Out"; if it is only under Remote Tracking, right click `origin/epa-pave`, "Check Out...", then "Check
out as New Local Branch" in "Check out remote-tracking branch", and Finish in "Create Branch" with
"Check out new branch" ticked. These menus and dialogs were seen; the switch itself was not made from
this view.

**4. The Problems view** reads "0 errors, 25 warnings, 59 others".
- 24 warnings are Java warnings (13 exception classes with no `serialVersionUID`, unused imports, two
  unused private methods, three deprecations). They need no action.
- "Project 'epa-pave' has no explicit encoding set": the import sets no text encoding, so files
  inherit the workspace default, UTF-8 on the machine seen. The Gradle build reads every source as
  UTF-8, and seven test files hold characters outside ASCII. To silence it, and before you run tests
  where the default may not be UTF-8, set the project's Properties, Resource, Text file encoding to
  Other, UTF-8 (not tried; it is kept under `.settings/`, which git ignores).
- The 59 others are Spring Tools' own hints; 54 read "Unnecessary path variable definition
  [WEB_ANNOTATION_NAMES]" and sit on the names the controllers give their request parameters and path
  variables. **Never apply the quick fix of "Unnecessary path variable definition"**: the IDE's
  compiler keeps no parameter names, so without those names calls such as the History list and the
  note would answer HTTP 500, as the emulation of D-271 saw before those names were added, and
  `HandlerArgumentNamesTest` fails the build (the quick fix itself was not tried).

**5. Start it.** The Boot Dashboard is the view at the bottom left: expand `local`, select `epa-pave`
and press "Start or restart", its first button. Or right click
`src/main/java/org/p32h/interop/epa/pave/PaveApplication.java` and choose Run As, Spring Boot App.
The project carries the launch configuration `PaveApplication.launch`, written by STS 5.4.0 itself,
so both start `PaveApplication` with no dialog. On an older checkout without that file the first
start asks "Choose Main Type" with `HouseStyle` selected: choose `PaveApplication` (`HouseStyle` is
the build's style gate). The Console shows `No active profile set, falling back to 1 default profile:
"mock"`, the two warnings of step 4 of the checklist, `Tomcat started on port 8057` and `Started
PaveApplication`, but not the two lines `run.sh` prints; the Boot Dashboard shows
`epa-pave [:8057]`. Go on with step 5 of the checklist. `config-store/` and `run-history/` appear in
the project folder, the launch's working directory, and are in `.gitignore`. Stop it with the stop
button of the Boot Dashboard or of the Console. A plain Eclipse without Spring Tools ignores the
launch file without a word: there, use Run As, Java Application on `PaveApplication`.

**6. The live profile, or another port.** Run, Run Configurations, Spring Boot App, `PaveApplication`.
`live` typed in the Profile field of the Spring Boot tab, Apply, then Run, started the tool on live
(`"mode":"live"`, and `[profile: live]` in the Boot Dashboard); this start was seen on `e70a873`,
before the shared file existed, with the configuration STS had made, `epa-pave - PaveApplication`. On
`ace5914` the profile was typed in `PaveApplication` and applied, but the tool was not started on it.
Another port goes on the Arguments tab, under Program arguments, as `--server.port=8091` (not tried
there). **A profile typed there is written into the tracked file `PaveApplication.launch`**: with
`live` in the Profile field and Apply, `git status` showed it modified, one line; a change on the
Arguments tab presumably lands there too (not tried). Do not commit it. Clearing the field and
pressing Apply put the file back byte for byte; `git restore PaveApplication.launch` in `epa-pave/`
should do the same, but it was not tried, nor what an open Run Configurations dialog does after it.
After the field was cleared on `e70a873`, the Boot Dashboard still read `epa-pave [profile: live]`:
that label can be stale, and `GET /api/mode` says which profile runs. Or start from a terminal with
`./run.sh live` (`run.cmd live`), which leaves the file alone.

**7. Tests from the IDE.** Run the whole suite with Gradle (`./gradlew test`, or step 3): only five
test classes have been started from the IDE. On `ace5914`, from STS's JUnit launcher,
`HandlerArgumentNamesTest` passed 2 of 2, `HouseStyleTest` 8 with 2 skipped and
`PaveApplicationTest` 6 with 1 skipped; on a checkout older than that change the three fail
(`RunControllerTest` and `MemberInTextFormsTest` passed on `e70a873`). Every JUnit launch from the
IDE also prints `WARNING: Type implements CloseableResource but not AutoCloseable` for a helper of
the tests; it needs no action. The three skipped,
`HouseStyleTest.theRealProjectListsItsDotFilesAndBreaksNoRule`,
`HouseStyleTest.theBuildRunsThisGateAsPartOfCheck` and
`PaveApplicationTest.theVersionOnScreenIsTheVersionThatWasBuilt`, check the Gradle build's own wiring
and run only under Gradle. Each shows under Failure Trace a sentence that says it runs only under
the Gradle test task and ends "started by that task (no system property org.gradle.test.worker), so
the test is skipped here, not passed." The pane cuts off a few words of it at its edge, even with the
view maximised.

**8. Later commits.** Right click `epa-pave` and choose Team, Pull (the Team menu also has "Pull...",
"Fetch from origin" and "Switch To"). With nothing new on GitHub it opened "Pull Result for Interop"
reading "Nothing to update - everything up to date". A pull that brings new commits was not seen, and
neither was whether Gradle then needs a refresh. The import leaves automatic synchronisation off, so
after a pull that changes `build.gradle`, right click the project and choose Gradle, Refresh Gradle
Project (inferred, not seen). A clone made with only `epa-pave` ticked at 4 of step 2 (b) fetches only
that branch.

**What was seen, and by whom.** On `e70a873` one agent ran STS 5.4.0 on Linux: the import, the start
from the Boot Dashboard (after "Choose Main Type") and with Run As, both proofs of "Prove it before
handover" and every request it tried answering 200 on mock, a start on live through the launch
dialog, and three of five test classes failing; a plain Eclipse 2026-09, with Java home for Gradle set
to the JDK 21 and started with Run As, Java Application, imported with 0 errors and 25 warnings and
gave the same proofs and failures. On `ace5914` another agent saw the start with
no dialog, the profile written into the file and restored, and the tests as in step 7. On `d089e65`
four more runs (D-273): STS cloned the repository from GitHub through the Git import wizard as in
step 2 (b), imported `epa-pave` by changing "Import source:", started it with both proofs holding, and
saw what Finish on the wizard's own choice gives, the two `PaveApplication` entries and Team, Pull with
nothing new; the Gradle import was given the top folder of a clone on purpose, which gave the error
naming `gradle-8.9-bin.zip` and its cause, and the Git Repositories view was looked at; the state
after Finish on the wizard's own choice was rebuilt and left as under "Already pressed Finish on the
`Interop` row?", with the tool started and both proofs run after Close Project and after Delete; and
a workstation with no JDK 21 was simulated, then one whose network refuses the downloads, as under
"What the machine needs" and "If the import fails". **Reported, not seen by the writers of this
README:** one operator, on a Windows workstation, let STS make the clone, as for another project. The
clone showed under Git Repositories with no project in the Package Explorer; a Gradle import then
failed with the error naming `gradle-8.9-bin.zip`, its cause cut off; their Git Repositories view held
two clones, one on `epa-pave` and one on another branch and 39 commits behind; the Git wizard's last
page offered only the top folder, already imported; and the top folder was then a Maven project with
`epa-pave` a plain folder inside it. Whether the tool has since run there is not known. **Not seen by
anyone:** Windows beyond that report, `run.cmd`, a credential prompt, a real tester at a real
workstation with a corporate proxy or mirror, a machine with a Java 17 and no Java 21, "Clone a Git
repository" in the Git Repositories view, a branch switched from that view, a pull that brings new
commits, the screens in a browser after a start from STS, the live profile against a service, the
whole test suite from the IDE, and STS on a release other than 5.4.0.

## Open items

Each of these is unknown. PAVE uses an obvious placeholder for it rather than a plausible guess.

| Open item | What stands in for it | Where the real value goes |
| --- | --- | --- |
| The Onyx CRD service id | The Postman variable `crdOrderSignServiceId` (and `crdOrderSelectServiceId`); the sample uses `placeholder-order-sign-service` | The vendor's environment file. `Discover the CDS services this environment offers` on the CRD tab (`GET /api/crd/discover`) lists what the environment offers. Fallback: `onyx.variables` in `src/main/resources/application.yml` |
| Whether the sandbox collection has `order-select` and a `/cds-services` discovery request | Detection reads both by name and by url; neither is in the sample | Upload the vendor's collection; correct on the Config tab, `Collections and mapping`, or with `PUT /api/config/collections/{id}/mapping` |
| The member API response contract | A tolerant mapping: several spellings per field, any common envelope | `lookup.member.url` in `src/main/resources/application.yml`; narrow the tables at the top of `src/main/java/org/p32h/interop/epa/pave/client/lookup/MemberResponseMapping.java` (D-129) |
| The provider directory response contract | The same | `lookup.provider.url`; `src/main/java/org/p32h/interop/epa/pave/client/lookup/ProviderResponseMapping.java` |
| How the lookup APIs authenticate | Nothing is assumed | `lookup.member.headers`, `lookup.provider.headers`, or `lookup.*.use-onyx-auth` |
| The procedure code list header row, flag spellings, date format, time zone, encoding, line and vendor names | The bundled placeholder list and candidate header lists | `codelist.file`; `codelist.columns.*`; check with Config, `Check this installation`, or `GET /api/procedure-codes/self-check` |
| How the token request authenticates the client: HTTP Basic, the credentials in the form body, or a private-key JWT | OAuth2 client credentials with the client authenticated by HTTP Basic (`Authorization: Basic`), decided in code and not by the vendor | `onyx.auth.*` for the values. For credentials in the form body or a signed JWT assertion, `requestToken` in `src/main/java/org/p32h/interop/epa/pave/client/support/OnyxTokenService.java` is the one method to change |
| The PAS attachment mechanism in use | `fhir` | `onyx.pas.attachment-mode`. `x12-275` names the attachment and sends nothing else |
| Whether the receiver is reachable from Onyx | Assumed not | `server.address`, `app.allowed-hosts`, `onyx.subscription.callback-url`, none of them to be changed for this before PAVE has a sign-in (D-270; see [Before PAVE is run as a shared or production service](#before-pave-is-run-as-a-shared-or-production-service)) |
| Which version of each guide the sandbox follows (D-130, D-136, D-269) | 2.0.1, which the operator says the sandbox follows; this has not been observed against it. Codes of the 2.2.0 ballot are read and named; DTR reads both published shapes of next-question | `wrapInParameter` on a next-question request chooses the wrapped form; the variable `dtrCoverageExtension` the ballot form of coverage |
| How Onyx signals an unknown member or provider, inactive coverage and the delegated vendor (D-033) | A rule kept in the client layer, stated in each mock scenario | `src/main/java/org/p32h/interop/epa/pave/client/crd/OnyxCrdConventions.java`, `.../client/pas/OnyxPasConventions.java`, `.../client/dtr/OnyxDtrConventions.java` |
| Values the guides require that only the payer's or the provider's set-up knows: the payer, the member ID system, the medical record number, the X12 service and request types, the requesting organization (D-141) | Nothing: the request goes without them and names each one left out | The variables under [Variables that fill a request](#variables-that-fill-a-request), in the vendor's environment |
| What a subscription must name (filter, channel, payload, end) | The topic PAS 2.0.1 defines; a filter only from what was supplied (D-160) | The `subscription...` variables and `pasRequestingOrgIdentifier`, or values typed for the run |
| The shape of a notification from Onyx | Both published forms are read | Nothing to set; check the first real one |
| The reference implementation `onyx-crd-harness-1.2.0.zip` (D-001) | Not available; everything was written fresh | Compare its scenario bodies and operation detector with this build if it turns up |

## Limitations

What is not done, not verified, or known to be wrong on the revision named at the end, said as what
a person who runs the tool meets and what to do about it. `PROGRESS.md` is the project's own
account: it lists every open item, the worst first, with the finding it comes from, and the
entries of the last round in `DECISIONS.md` (from D-260) give the reasons. This section does not
repeat every finding id; it leaves out nothing a tester has to know.

**Gates and checks**

- **Nothing has been run against Onyx or any real service.** Every live check used a stand-in on a
  loopback port. The lookups have never met the real member API or provider directory.
- **The integrator's last gates run after this README is written**, on the commit that holds it:
  the plain `./gradlew clean build` twice, a build from a fresh clone with the build cache off, the
  start with `GET /api/mode`, and the changed paths. A file cannot hold the result of a build of
  the commit it is part of, so their results are in the pull request description (part 2). The last
  whole build this README can name is that of `ace5914` (D-272), whose code is the code handed over,
  through the shared wrapper: 2,872 tests, 0 failures, 0 errors, 2 skipped, BUILD SUCCESSFUL. Before
  it, `05acbf7` (D-271) gave the same counts, and `902e0d9` 2,870 tests, 0 failures, 0 errors, 2
  skipped, "No house style violations.".
  Every build on the machine this README was checked on went through a wrapper that adds
  `--offline`, under a cap on Gradle's memory, with every Java process run with an option that
  turns off two base64 intrinsics (see the last section).
- **How the checking ended.** The verification agents of section 27 of the specification all ran,
  on earlier revisions: the member data audit (V2), the guardrail audit (V3) and the fixture audit
  (V4) a second time on `0658a7c`, with their fixes checked on `9e5306d`; the build audit from a
  fresh clone (V1), the house style audit (V5), the completeness audit on screen (V7) and the
  walkthrough by a business tester on `9e285a1`; the honesty audit (V6) on `9e5306d`. The fixes
  for all of them were then checked once more, in six groups, by agents that wrote none of them:
  the style gate, the fixtures, the ledger, completeness on screen and the walkthrough on `98effa6`,
  the guardrail on `428eff5`. Each of the three rounds (an audit, a check of its fixes, a check of
  those fixes) found something real. The last round of fixes (the ledger, completeness on screen,
  the walkthrough, the guardrail; merged in `902e0d9`) **was checked by nobody but its authors, and
  no check follows it**: a check that always finds something has to stop somewhere. What the last
  check found and was not given to a fix is open, below.
- **One merge was checked by the build only.** The ledger fix and the completeness fix both changed
  how the ledger reads a named coverage entry; the integrator kept both changes by hand (`60876fa`).
- **A test that failed by chance.** A ledger test looked for the four digits of a year anywhere in a
  line that holds random hexadecimal tokens, and failed about once in a hundred builds; it was
  corrected (`6b7abba`), and no other test of that kind was found by reading. It was found only
  because a build happened to fail: two green builds do not prove that no test of that kind is
  left. If a ledger test fails once and passes when run again, report it.
- **No business tester has used the tool.** The walkthrough was made by an automated agent on mock,
  not by a person, and is not a sign-off.
- **`run.cmd` has not been run.** No Windows machine was available. It was written to do what
  `run.sh` does, and corrected once after a reader found, by reading, that its Java version check
  would have failed on every machine. Quote an argument with an equals sign
  (`run.cmd mock "--server.port=8091"`). If it fails, use the two commands under step 4 of the
  checklist.
- **STS was seen on Linux with Spring Tools 5.4.0 only**: the import, the start on mock from the
  Boot Dashboard and Run As, the start on live through the launch dialog (on `e70a873`) and five test
  classes
  (D-272); on `d089e65` the clone by STS's Git import wizard, what goes wrong on its way, and an
  import without a JDK 21, simulated (D-273). No other release, no minimum version and no Windows
  machine was tried, and the whole suite has been run with Gradle only. Typing a profile in the launch dialog writes it into the
  tracked file `PaveApplication.launch`; do not commit that. See
  [STS (Spring Tools for Eclipse)](#sts-spring-tools-for-eclipse).
- **A mirror that needs credentials and a token server** were not tried.
- **Two tests are skipped** when the build runs as root, because a read-only folder is not
  read-only for root. The same refusals are covered by tests that do run. Two tests of the screens
  run a script with Node.js and are skipped, without failing, where no `node` is on the path.
- **The tests write into a temporary folder of their own**: a JUnit extension gives every test JVM
  its own `config-store` and `run-history` and removes them at the end, so after `clean build` the
  project directory holds nothing new but `build/` (checked by the fix's author only). Both folders
  stay in `.gitignore` for the running tool.
- **The house style gate** is enforced by the build on every file git tracks or would track under
  `epa-pave/`, file names included. Commit messages, the pull request description, names of people
  and comments that only restate the code are not checked by the build. The last check of the gate
  found, and nothing has fixed: a file name with a character outside ASCII crashes the gate under
  a POSIX locale, and the failed build then shows the previous run's "No house style violations."
  (if the build is red and says so, delete `build/` and look at the real error); some dash-like,
  quote-like and invisible characters, and entities or escapes that render a forbidden character,
  are not refused; a text file with a binary extension or a NUL byte near its start is skipped
  unread; one rule has a second, weaker copy in a test; a few invented surnames in tests do not
  start with `Placeholder`; and empty section banners remain in 16 main source files. The tree
  itself holds none of the missed characters (that check's own scan).
- **The drawing of the three presences on the page** ("not returned", "null", "empty") is held in
  the build only by tests that read the scripts' text; the build has no browser, so a page that
  stops drawing them would pass. They were checked by eye in a browser by the fix's author.

**Not built** (sections 12 and 13 of the specification)

- **DTR is not a SMART launch**: no authorization server, no launch handshake, no EHR embedding, no
  CQL, no pre-population from a patient record. `$expand` and `$log-questionnaire-errors` are not
  implemented.
- **PAS**: no claim update and no `$submit-attachment`, so the guide's route after "attachment
  required" is not built (the result names it). In attachment mode `x12-275` PAVE names the
  document on the claim and sends no 275 transaction.
- **No sign-in.** PAVE listens on this machine only by default; the notification receiver
  therefore cannot be reached by a vendor, and when opened it does not authenticate its caller.
- **The wire view carries member data**, and secrets and uploads sit in plain text in
  `config-store/`, the ledger's salt among them.

**Open at this revision: member data and the ledger**

- **A note, a tester reference or an expectation** is refused only for member data the same
  request carries. Somebody else's name or date of birth, a name the request does not carry, a
  member or subscriber ID under six letters and digits and an address line under five characters
  are written as typed, and so are three spellings of the run's own date of birth (a month
  abbreviated with a full stop and an ordinal day, the compact `MMDDYYYY` form, a two-digit year
  with an apostrophe) and a date written in words. A suite row is checked at preview for its own
  member ID only; the rest is checked when the case runs. Old ledger lines that hold a name or a
  date of birth in a note are still shown and exported as written. Type no member data there.
- A run sent with `A request of my own` from the CRD, DTR or PAS tab is checked against the member
  identifiers of its body only, once its `{{placeholders}}` are filled from the values of the run,
  the active environment and `application.yml`: a name or a date of birth there is not looked for,
  a value only a collection's variables give is not used, and a body with any placeholder that has
  no value is read as typed. The filled placeholders were seen on the running tool by the fix's
  author for CRD only.
- A note added later on a line that holds only the token cannot be checked for a name or a date of
  birth, and finds the digits of the ID alone only when one or two letters stood in front of them.
- An expectation of a suite row that holds the date of birth or a name and is also malformed is
  refused for its shape before the member is looked up, and `results.csv` still repeats it in the
  expected column. Do not put member data in a suite file.
- An id a service supplies that is 64 hexadecimal characters, or shaped like a UUID, is written in
  clear whatever its digits, even when it ends in a member ID. Lines that an earlier fix wrote with
  `(not recorded, N characters)` under a subscription or ClaimResponse id cannot be matched to a
  notification.
- A line is read as it was written and not judged again by the code list loaded later, so a line
  written before the second member data fix that holds a long code with no member ID beside it
  shows that code (no such build was handed over).
- **A one-word surname** written where a code belongs (`covered=Zzlastname`) is kept (D-151).
- **The configuration store** keeps uploads whole, member data included; the upload warning goes
  by variable names and by saved examples, not by values. It does not name `MBRID` written in
  capitals, `mobile` or `addr`, and it names `webhookAddress` as member data, wrongly.

**Open at this revision: the guardrail**

- With a production environment active whose address an earlier version stored and the tool cannot
  read, `/api/mode` and the Config tab say "Unlock them by typing the environment name" and offer
  the unlock field, and every unlock is refused with 422, whose sentence says why. Upload that
  environment again as `prod` with its address written plainly.
- **The guardrail does not see** another DNS name for a production host, or the
  `onyx.<protocol>.url` fallback settings of `application.yml` as a production address (a write to
  them is judged at sending like any other url). A sandbox on production's port whose base path
  ends in an escape such as `%00`, `%2e` or `%20` gets the warning about another base path and is
  not refused (the decided case of another base path on the same server).
- Not proved on the running tool: a production address that only a collection's own variable
  gives (proved by tests, and for `application.yml` on the running tool, by the fix's author); IPv6,
  an explicit default port on the wire, a host other than loopback, TLS. How a real server in front
  of a service decodes an escaped path was not tested: the encoded separator rule refuses rather
  than finds out. A replay that reaches the gateway against production on live was not exercised.
- If the process is killed outright, the embedded server's working folder `pave-server-*` is left
  under the temporary directory; a normal stop removes it.

**Open at this revision: the screens and the next steps**

- **On the CRD tab, discovery is refused when text is left in the procedure code box** ("Nothing
  was sent: "MRI" was typed in the procedure code box and not added to the case."), although
  discovery sends nothing of the case. Clear the box, then discover.
- **Some outcomes still give no next step of their own**: on CRD documentation asked for in a way
  the tool cannot act on, no authorization answer, an unrecognised code and mixed answers; on DTR
  an answer that asks nothing more and is not complete, and a status the tool does not act on; and
  a request sent as written. Each says only "Next: read the response detail below. This tool
  cannot say what should happen next." (or the like). Record the run by its run id as what came
  back and report it to the people who look after the service, as PAS now says.
- History, its "Next action" filter and the chain view show a PAS run with no decision under the
  general label "Look at the response detail. The tool cannot say what should happen next.", where
  the PAS tab gives the two lines of next step.
- On mock, the next step of a played 500 or unreachable service still reads "Next: check the
  environment, then send again.", and that of the malformed DTR package "Next: read the problems
  listed with this result and report them as a defect in the service.", although their heading and
  action say the answer was played and nothing left the machine. A mock member lookup that plays a
  timeout (`P32H-LOOKUP-TIMEOUT`) is headed "A fault in the environment: no answer in time". On
  mock, nothing of these is a fault in the environment.
- The label of the CRD outcome for an answer that is not what the operation returns, shown as
  "Happened" in the test result, in History and in a chain, still speaks of "a body that could not
  be read", also for a Patient or an OperationOutcome answered with HTTP 200, although the headline
  says what came. Read the headline.
- On CRD, the title of the finding for a coverage sub-extension sent with no value reads "... was
  sent with no value" for a value sent as null and one sent empty alike, while its detail tells
  them apart.
- A subscription's "Headers sent with a notification" has no presence of its own, so a list that
  was not returned, null or empty all read "none returned".
- Next steps that say "read the response detail below" are shown also in the History replay panel
  and the chain view, where no response detail is below.
- The detail of the finding for a key repeated in one object says "that is the value shown above"
  also for a key the view reads without drawing (`type` of a DTR or PAS Bundle).
- On mock, a subscription scenario or a sample notification whose body repeats a key is written
  out again, so it loses the earlier values and nothing on screen says so (the PAS mock now plays
  such a body as written and says so).
- History, a suite row and a chain say nothing of fields the tool did not interpret, not even a
  count. Open the run's own tab, or replay it.
- The heading the server gives a refused form, "A problem with what was submitted", says nothing
  of what to do; the action under it does ("Correct the marked fields and send again."). The action
  under a refusal for an operation that is not configured asks for a collection or a fallback url
  also when the url as filled is not valid; the title says which it is.
- The DTR change block (appeared, disappeared, dropped) is drawn twice, in the questionnaire pane
  and in the result detail. History offers no `zone` filter, and says every date is a UTC day.
- Before anything is uploaded on live, the protocol tabs hold back their form; on mock they offer
  it, because scenario files answer.
- What the last fixes changed on screen was looked at by their authors only, at 1366 by 768: the
  shortened OperationOutcome headline, the History view of a body of your own with placeholders,
  and the DTR and PAS tabs with such a body were not looked at in a browser. At phone width the
  History runs table and the CRD elements table run past the edge. No screen reader was used,
  contrast was never measured, 200% zoom was never tried.

**Open at this revision: notifications, replay and the mock scenarios**

- On mock, a notification somebody posts to the receiver by hand is recorded as answered by a mock
  scenario; ledger lines written with `SERVICE` beside a scenario id before the fix are read as
  written.
- A replay of a run id that does not exist answers with the general action, not the one a `GET` of
  that id gives. The replay's sentence for a mock scenario has no test at the level of the replay.
- When two responses were completed from one DTR package, nothing says which answers a PAS
  submission carried.
- When `crd-04` is chosen in the Mock scenario field and the DTR sentinel comes only from a pasted
  body, the CRD note does not say that it was found in the request body. In a scenario folder of
  your own, the note for a DTR sentinel in CRD says CRD answers documentation required whatever the
  file that answers it plays. The check of an old scenario folder reads only the bundled wording of
  a description. The PAS scenario lists for inquiry and for notifications leave out the
  `crdSentinelMemberIds` mapping, though an inquiry with `P32H-TIMEOUT` still selects `pas-16`
  through it.

**The protocols**

- **Conformance**: checked against 2.0.1 as described under [What PAVE is not](#what-pave-is-not),
  without US Core 3.1.1, SDC, HRex, the subscriptions backport or the X12 code lists. CRD sends the
  ordering provider as a Practitioner or an Organization, never both, and never
  `Organization.active`.
- **CRD**: a pasted request for another hook is answered comparable, with a note.
- **DTR**: which version of the guide the sandbox follows decides the shape of next-question. The
  operator says it is 2.0.1 (D-269); that has not been observed against the sandbox. Only the
  invariants dtrx-1 and dtrb-1 are evaluated. An answer's author carries no
  role. A failure of the tool in the response step answers 500 and writes no ledger line.
- **PAS**: a resubmission reuses the claim id. Mock X12 reason and category codes are placeholders.
- **The notification receiver** has never been called by a real payer, holds at most 200
  notifications in memory (each body read whole up to 8 MiB, and its text held to 128 KiB; for a
  longer body that is not JSON the note says "The text after them is not shown anywhere.") and
  loses them on restart; the ledger lines remain. Two notifications at the same moment are not
  tested. After the tool deletes its subscription, a notification on it says "A notification
  arrived on a subscription this tool created and then deleted." and is never called still there;
  this is covered by a test and was not seen on the running tool.
- **The chain**: a DTR package that goes straight on to PAS is driven by no test and no mock
  scenario. A later step handed only the procedure codes its line already holds is marked not
  replayable alone. A well-formed chain id the tool never issued is accepted.

**Configuration, the guardrail and secrets**

- No real vendor Postman export was available. The sample files and every test file were written
  by hand to the shape the specification describes. Detection by url is verified only for the
  standard FHIR operation paths and `/cds-services`.
- Concurrency in the configuration store (two uploads or a switch at the same moment) is reasoned
  about and not tested.
- A key written into a url, or held by a variable with an ordinary name, or typed into a path
  segment or a host of a collection, is shown as written (see [Secrets](#secrets)). A secret a
  service sends back re-encoded, shorter than six characters, or as a JSON number, is not masked;
  for a request sent as it is written, the server does not mask it in the returned fields.
- **The token request** authenticates the client by HTTP Basic, a choice made in code (see
  [Open items](#open-items)). It was never sent to a real token server.
- **On mock, a CRD, DTR or PAS run does not check that its url resolves.** Use the Config tab or
  `POST /api/config/resolve`.

**The ledger, lookups, suites, code list, the HTTP edge**

- **The ledger** was run on Linux only. Flush and sync are checked by reading the code. Two
  instances pointed at one ledger folder are not coordinated. A note written after the clock was
  set back across midnight is on disk and not applied when the run is read. A run with a body of
  your own records no member, NPI or line of business, and nothing on its line tells two such runs
  apart. After the ledger folder was restored, the server kept saying it cannot write until the
  next write succeeded. A folder that cannot be created stops the tool at start.
- **Lookups** have never met the real member API or provider directory. A name returned in one
  piece cannot fill first and last name. An identifier with an encoded slash in the path form ends
  as a 400; use the query form.
- **Suites**: whether a code assertion's value is in its value set is not checked before sending. A
  second suite started while one runs is answered 409 with kind `VALIDATION`.
- **The code list**: no real P32H export was available, so everything about its shape is assumed.
  A list is synthetic only when a row says so ("Not from a P32H export."); any other list is taken
  as the real one.
- **Fixtures**: any new top-level key in a scenario file stops the mock start until the loader
  knows it. A scenario file whose `expect` outcome contradicts its own body is not detected at
  start. A scenario folder of your own from before the cross-protocol keys is told what it lost,
  and nothing is guessed back: the keys must be added by hand. No test covers a CRD file that
  speaks of DTR sentinels while another CRD file carries `answersDtrSentinels`. The run note of a
  PAS file chosen by a `crdSentinelMemberIds` entry that is no CRD file's sentinel still says the
  ID "answers for the same case", while the list note beside it says the ID means no CRD case. The
  labels of the fault scenarios name what the file plays ("Request rejected with HTTP 400",
  "Server error, HTTP 500"), not an observation of the vendor.
- **The HTTP edge**: only Linux, curl, raw sockets and headless Chromium were used. `OPTIONS *`
  carries none of the tool's headers. A cross-site request that carries neither `Sec-Fetch-Site`
  nor `Origin` is let through.

## What this README was checked against

- **Revision:** `902e0d9`, the head of `epa-pave` when this README was last checked in full against
  the running tool, with this README added; the code changes made since, and what was run on them,
  are in the next three bullets. That is
  `428eff5` with the last four fixes merged (completeness on screen `fff3bd0`, the walkthrough
  `10108d4`, the ledger `54f9912`, the guardrail `ca716a1`), the merge `60876fa` that kept both
  changes to one method of the ledger record, and the test corrected in `6b7abba`. Every sentence
  that the last fixes made untrue was rewritten on `902e0d9` from the source of that revision (each
  quoted sentence found in it) and from the reports of the fixes. What a sentence says a fix does
  was seen by the fix's author where the sentence says so, and by nobody else, unless this section
  names it below.
- **Since `902e0d9`, the code handed over also holds `05acbf7`** (D-271, merged at `a5040bd`): every
  controller argument names itself, held by `HandlerArgumentNamesTest`. Its build through the shared
  wrapper (`clean build`): 2,872 tests, 0 failures, 0 errors, 2 skipped, BUILD SUCCESSFUL.
- **Since then also `ace5914`** (D-272, merged at `5923b3f`): the launch configuration
  `PaveApplication.launch` and changes to three test classes and one test helper, no main code. Its
  build through the same wrapper: 2,872 tests, 0 failures, 0 errors, 2 skipped; an agent that did not
  write it built it again in its own clone of that commit, with the same counts. Apart from these two, the commits after
  `902e0d9` change this README, `PROGRESS.md` and `DECISIONS.md` only.
- **Run for the STS section**, on Linux, by agents that wrote neither this README nor the tool nor
  the changes: Spring Tools 5.4.0.RELEASE (Eclipse 4.41.0, Buildship 3.1.11), driven through a
  virtual display with keyboard and mouse with a screenshot after every step, on `e70a873` and, on a
  new workspace, on `ace5914`; and the plain Eclipse IDE for Java Developers 2026-09 on `e70a873`,
  and again on `ace5914` to see what it does with the launch file.
  `GET /api/mode` after each start, and the two proofs of "Prove it before handover" through the API
  by script after the first start of each run; on `e70a873` also the History list, one run, the note, the export, the notifications, a
  subscription read, the page and one stylesheet. What each run saw is under [STS (Spring Tools for
  Eclipse)](#sts-spring-tools-for-eclipse) and in D-272. Before them, on `1b34181` and `05acbf7`, an
  emulation of STS's compiler without STS started the tool, and on `05acbf7` ran the 70 `curl`
  examples under [Endpoints](#endpoints) (D-271). On `d089e65`, by agents in the same way, four more
  runs of Spring Tools 5.4.0 on Linux: the clone by its Git import wizard, the import of the wrong
  folder, the state that wizard leaves, and a machine with no JDK 21 and then no network, both
  simulated; `GET /api/mode` after each start of the tool, in the first and the third of these (the
  second and the fourth started nothing), and the two proofs after every start but the third run's
  double launch with both projects open (D-273).
- **Run again on `902e0d9` for this README**, on the jar the integrator built from that revision
  (`merged-902e0d9.jar`), freshly started on the mock profile on port 8194 (port 8057 was not used
  on that machine):
  - **every `curl` example under [Endpoints](#endpoints)**, in order, with `localhost:8194` in place
    of `localhost:8057`, from a copy of `samples/`: 70 examples, each answered the status in its
    comment. The three that take a suite run id were run by hand with that id, because the script
    that runs the examples put the run id in its place; the suite finished "Finished: 6 cases. 4
    passed, 1 failed, 1 unverified.";
  - on mock, through the API: the PAS next step for `pas-14-no-decision` ("Next: record run run-...
    as "no decision returned" and report it to the people who look after the service." with its
    reason, action `REVIEW`); a sample notification recorded with `answeredBy` `SCENARIO`; and a
    production environment whose address is `{{env:PAVE_PRODURL}}` refused at upload with the
    sentence quoted under [Uploading an environment](#uploading-an-environment).
- **The two proofs of "Prove it before handover"** were run by the integrator through the API (not
  in a browser) on the same jar on mock: `P32H-NO-AUTH` answered "Prior authorization is not
  required." (`NO_AUTH_REQUIRED`, pass) and `P32H-EMPTY` "The service answered but returned no
  determination." (`NO_DETERMINATION`, next step "Next: record run run-... as "no determination
  returned" ..."), two different ledger lines with no request or response body in them; and the
  journey with `P32H-DOC-DTR` from CRD through DTR to PAS carried the completed
  QuestionnaireResponse, its answers the same as those DTR completed (17 among them), in the PAS
  request as previewed and as sent (wire view), and PAS answered approved.
- **Not run again on `902e0d9`:** `run.sh` (on this machine it would build the jar with `./gradlew`,
  which only the shared wrapper may run; it was last run on `e1d37dc`, and no line of it has changed
  since), the screens in a browser (the journey under [Using the screens](#using-the-screens) was
  last walked by an automated browser on `e1d37dc`, and what the last fixes changed on screen was
  looked at by their authors only), `run.cmd`, STS (other than as the STS section says), a mirror
  (with or without credentials), a token server, Onyx, any behaviour on live against a stand-in, a screen reader, a phone-width
  screen, 1920 by 1080.
- **Where:** Linux, OpenJDK 21.0.13, Gradle 9.5.0. On the machine this README was checked on every
  Gradle command went through a shared wrapper that adds `--offline` and lets two builds run at a
  time, a file in the home directory caps the memory Gradle may use, and every Java process ran with
  `_JAVA_OPTIONS` set to `-XX:+UnlockDiagnosticVMOptions
  -XX:DisableIntrinsic=_base64_encodeBlock,_base64_decodeBlock`. `./gradlew` itself was not run for
  this revision. The last whole build this README names is that of `ace5914` through that wrapper
  (`clean build`): 2,872 tests, 0 failures, 0 errors, 2 skipped, BUILD SUCCESSFUL; `05acbf7` gave the
  same counts, and `902e0d9` 2,870 tests, 0 failures, 0 errors, 2 skipped, "No house style
  violations.". No fresh clone was built for this revision by the writer of this README; the integrator's last gates, a
  fresh clone with the build cache off among them, run after it, and their results are in the pull
  request description. The build in step 3 of the checklist and the output of `run.sh` in step 4
  were pasted from an earlier revision (`e1d37dc`) and were not run again.
