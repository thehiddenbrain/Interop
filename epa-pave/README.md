# PAVE 1.0.0

PAVE is a test tool for the Da Vinci electronic prior authorization services that Point32Health
runs through the Onyx sandbox: CRD (is prior authorization required), DTR (the payer's
questionnaire) and PAS (submit the authorization and follow its status). A tester picks a member, a
provider and a procedure code, runs a case, and sees the answer in plain words, the standard codes
behind it, and the exact request and response. Every run is written to a ledger on disk so it can
be found, exported and run again.

It is one Spring Boot application with a browser front end, run on the tester's own machine. Out of
the box it runs on the **mock profile**: every answer comes from synthetic scenario files, nothing
leaves the machine, and no credential or configuration is needed.

Read "[What this README was checked against](#what-this-readme-was-checked-against)" at the end
before relying on a sentence here. Where something could not be checked, the section that
describes it says so.

## First run checklist

Six steps from nothing to a running tool on the mock profile, used through its API: this revision
has no screens yet (step 6). Each shows the exact command and what
correct output looks like. The output shown is what these commands printed when this README was
checked (see the last section for where and on which revision).

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

Anything older than 21 will not build or run PAVE. Install a JDK 21 and set `JAVA_HOME` to it.

**2. Get the code and go into the project directory.**

```
git clone --branch epa-pave https://github.com/thehiddenbrain/Interop.git
cd Interop/epa-pave
ls run.sh
```

Correct output of the last command is `run.sh`.

**Stop here if it prints `ls: cannot access 'run.sh': No such file or directory`.** That is what
it printed on 2026-11-19: the `epa-pave` branch on GitHub (then at commit `44c7760`) did not yet
hold this README, `run.sh`, `run.cmd` or `samples/`. They are on the branch `docs-c2`, which had
not been merged into `epa-pave` or pushed to GitHub. Until it is, step 4 fails in a clone from
GitHub. Ask whoever gave you this README for the copy that holds it, clone that with
`git clone --branch docs-c2 <that copy>`, go into its `epa-pave/` directory, and check
`ls run.sh` again.

PAVE lives in the `epa-pave/` directory. The application at the root of the repository is a
different project; leave it alone. Every command below is run from `epa-pave/`.

**3. Build and run the tests.**

```
./gradlew clean build
```

On Windows: `gradlew.bat clean build`. The first build downloads Gradle 9.5.0 and the
dependencies, so it needs to reach `services.gradle.org` and Maven Central, or the internal
mirrors described under [Running on a corporate workstation](#running-on-a-corporate-workstation).
It runs about 1,850 tests and takes a minute or two. Correct output ends with:

```
> Task :verifyParametersFlag
> Task :check
> Task :build

BUILD SUCCESSFUL in 1m 24s
11 actionable tasks: 8 executed, 2 from cache, 1 up-to-date
```

The time and the task counts differ from machine to machine. `BUILD SUCCESSFUL` is what matters.

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
Started PaveApplication in 18.439 seconds (process running for 20.228)
```

If it says `Port 8057 was already in use`, something else is on that port: start with
`./run.sh mock --server.port=8091` and use 8091 in place of 8057 below.

**On Windows**, run `run.cmd`, or `run.cmd mock "--server.port=8091"` for another port. **`run.cmd`
has never been run**: no Windows machine was available, so it was only read. If it stops with an
error, or does not print the lines above, start PAVE without it, from `epa-pave\`:

```
gradlew.bat bootJar
java -jar build\libs\epa-pave-1.0.0.jar --spring.profiles.active=mock
```

The same two commands, with `./gradlew` and `build/libs/...`, were run on Linux and start PAVE as
`run.sh` does. Started through `run.cmd`, Ctrl+C is followed by the prompt
`Terminate batch job (Y/N)?`; answer `Y`.

**5. Ask the tool what it is, from a second terminal.**

```
curl -s localhost:8057/api/mode
```

Correct output (the `epoch` number is the configuration epoch: it differs from one start to the
next and changes when the configuration changes, but two calls in a row answer the same number):

```
{"name":"PAVE","version":"1.0.0","port":8057,"mode":"mock","target":"onyx","environment":{"active":false},"epoch":1795129834662,"writeAccess":{"production":false,"writesAllowed":true,"floorAllows":false,"reason":"No environment is active. On the mock profile nothing is sent, so writes are allowed."}}
```

`"mode":"mock"` is the line to check: nothing you do now reaches a real service.

**6. Open the tool in a browser.**

Open `http://localhost:8057/`.

**This revision has no screens.** They are built on a separate branch that is not merged here. So
the correct output of this step, on this revision, is not a page: the browser shows this error in
JSON (HTTP 404):

```
{"kind":"NOT_FOUND","title":"There is nothing at this address.","action":"Check the path against the endpoint table in the README.","fieldErrors":[],"issues":[]}
```

That means the tool is running and answering; it has the API and no screens. Everything below that
uses `curl` works without them. What the screens are meant to show once merged is described,
unchecked, under [Using the screens](#using-the-screens).

To stop the tool, press Ctrl+C in the terminal where it runs.

## What PAVE is, and what it is not

PAVE drives the three Da Vinci operations a provider system would call, against whichever
environment you point it at, and shows you what went over the wire and what it made of it.

- **CRD**: a CDS Hooks `order-sign` or `order-select` call. The answer is read from the standard
  coverage information codes (`covered`, `pa-needed`, `doc-needed`, `doc-purpose`, `info-needed`,
  `satisfied-pa-id` and the delegated vendor), not from card wording. CRD never denies on clinical
  grounds and nothing in PAVE reads a CRD answer as a denial.
- **DTR**: the payer's questionnaire, fetched with `$questionnaire-package`, answered step by step
  through `next-question` for an adaptive questionnaire, and completed into a
  QuestionnaireResponse that PAS can carry.
- **PAS**: `Claim/$submit` and `Claim/$inquire`, plus subscriptions (create, read, update, delete)
  and a receiver for the notifications a payer sends back. PAS can carry a denial and PAVE shows it
  as one.
- Member lookup by member ID, provider lookup by NPI, a procedure code picker backed by the P32H
  code list, a ledger of every run, replay, chains of CRD, DTR and PAS for one case, and suites of
  many cases from one file.

Every result shows two things side by side, and they are different things: the **authorization
outcome** (what the payer said, for example "The prior authorization is denied") and the **test
result** (whether that is what you said you expected: pass, fail, or unverified when you declared
nothing). A case you expect to be denied that comes back denied is a denial **and** a pass.

### What PAVE is not

- Not provider facing, not a replacement for the Availity portal, not a load test tool, and not a
  clinical decision system.
- **DTR here is the server-side operations, not a SMART launch.** There is no SMART authorization
  server, no launch handshake and no EHR embedding. When you exercise DTR in PAVE you exercise the
  `$questionnaire-package` and `next-question` operations behind a provider-facing SMART launch,
  not the launch itself. Every DTR result says so. Do not report a DTR run here as a test of the
  SMART launch.
- **PAVE does not tell you what the published guides require.** Its request shapes and its reading
  of responses were written before the guides could be read in the build environment (decisions
  D-008 and D-130 in `DECISIONS.md`). The operator named version 2.0.1 of the guides as the one to
  hold the tool to (D-136). On this revision the independent comparison of each protocol with
  2.0.1 had not reported. It has since reported, on a later commit of the `epa-pave` branch
  (`44c7760`, in `PROGRESS.md` there): **the tool does not conform to 2.0.1**, with blockers in CRD
  and PAS and majors in all three (CRD 3 blockers, 14 majors, 9 minors; PAS 9 blockers, 11 majors,
  6 minors; DTR 8 majors, 6 minors), not fixed at that commit. Among them: CRD calls some codes
  2.0.1 defines non-conformant, and PAS builds bundles a validating 2.0.1 service would refuse. So
  a conformance finding from PAVE can be wrong in either direction, and a request PAVE builds can
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
  required." (both observed). They are different
  outcomes, with different codes in the ledger.
- **A code list finding on the bundled placeholder list is about synthetic rows.** See
  [The procedure code list](#the-procedure-code-list).

### The notification receiver will very likely not be reachable

PAS subscriptions work by the payer calling PAVE back at `POST /api/pas/notifications`. That only
happens if the payer's servers can open a connection to the machine PAVE runs on. On a corporate
workstation they very likely cannot: the machine sits behind the corporate network, and PAVE
itself listens on this machine only by default (see [Who can reach this tool](#who-can-reach-this-tool)).
With the defaults, **no vendor can reach the receiver at all**.

What to do then:

- Use **polling**: submit, then inquire with `POST /api/pas/inquire`. Polling needs nothing from
  the network beyond the outbound call and works on its own.
- Subscription create, read, update and delete still work, because those are calls PAVE makes.
  Only the notifications coming back are lost.
- On mock, `POST /api/pas/notifications/sample` delivers a sample notification through the same
  code a real one reaches, so the receiver's behaviour can be seen without the network.
- To receive real notifications, someone must give the machine an address the payer can reach and
  open PAVE to it. Read [Who can reach this tool](#who-can-reach-this-tool) before doing that, and
  ask the owner of the installation first.

The receiver does not authenticate its caller and has never been called over a socket by a real
payer (see [Limitations](#limitations)).

## Mock and live

| | Mock (the default) | Live |
| --- | --- | --- |
| Start with | `./run.sh` | `./run.sh live` |
| Answers come from | scenario files in `src/main/resources/scenarios/` | the services in the active Postman environment |
| Members and providers | synthetic fixtures in `src/main/resources/fixtures/` | the member API and the provider directory, when configured |
| Needs | nothing | an uploaded environment, its credentials, network access |
| Ledger records the member ID | yes, it is synthetic | no, a salted token and the last four characters, unless configured |
| Scenario picker | yes | no: a forced outcome against a real service would be a result that did not come from it |

**Every scenario is synthetic.** Sentinel member IDs select the mock scenario: `P32H-PA-REQ`
answers "prior authorization required", `P32H-NO-AUTH` answers "not required", `P32H-EMPTY`
answers with no determination, and so on. The full lists are served by `GET /api/crd/scenarios`,
`GET /api/dtr/scenarios` and `GET /api/pas/scenarios`, with the outcome each one is meant to
produce. A run can also name a scenario with `scenarioId`. Mock member fixtures use
`P32H-...` sentinels and `S123456789`-style placeholders; the placeholder NPI is `1234567893`.
NPI `9999999995` selects "provider not found".

**Switching** is a restart: stop the tool and start it with `./run.sh live` (or `./run.sh` for
mock). The mode is shown by `GET /api/mode` (and, once the screens are merged, on every screen;
this revision has none). The application refuses to start
when `epa.mode` and the Spring profile disagree, so the badge can never say mock while the wiring is
live. Both profiles use the same `config-store/` and `run-history/` directories, and every ledger
line records the profile it ran on.

Starting on live with nothing configured was checked: the tool starts, `GET /api/mode` says
`"mode":"live"` and that writes are refused because no environment is active, the scenario list
is empty with the reason, a member lookup answers `DISABLED` ("Member lookup is not configured"),
and a CRD run answers 409 ("No collection request is mapped to CRD order-sign, and onyx.crd.url is
not set. Nothing was sent."). **Nothing has been run against Onyx or any real service.**

On mock, a short artificial delay is added to each answer so that the screens behave as they will
on live. `app.mock-latency-enabled: false` removes it.

## Using the screens

**Not checked against a screen.** The frontend is being built on its own branch and was not on the
revision this README was checked on. This section is written from the specification (section 20)
and from what the endpoints behind each tab do, which was checked. It does not name buttons or
fields, because none was seen. Expect it to be revised once the screens are merged.

Every tab shows, at the top, the tool name and version and the **active environment with its
kind**. A production environment is marked so that it reads as production from across the room.
The browser tab title carries the same two facts: `PAVE 1.0.0 | <environment name>`.

- **CRD.** Pick or type a member, a provider and one or more procedure codes, optionally declare
  what you expect, and send. The member is looked up when you leave the field (`/api/members/{id}`)
  and the provider likewise (`/api/providers/{npi}`); found details fill the form and stay
  editable, and an edit is marked as typed by hand. The code picker (`/api/procedure-codes`) shows
  what the code list says about each code before you send: `PA`, `No PA`, the vendor name, or
  nothing when the list does not say. The result (`POST /api/crd`) starts with one sentence in
  plain words, then the standard codes, then the cards, then anything returned that the tool did
  not interpret, then the wire view, collapsed.
- **DTR.** Fetch the questionnaire package (`POST /api/dtr/package`), answer questions, step the
  adaptive loop (`POST /api/dtr/next-question`) with the question count, the answers so far and the
  completion status shown at each step, and complete the response (`POST /api/dtr/response`). The
  completed response is handed on to PAS.
- **PAS.** Submit (`POST /api/pas/submit`), inquire (`POST /api/pas/inquire`), manage
  subscriptions, and see notifications as they arrive (`GET /api/pas/notifications`). Writes to a
  production environment are blocked unless unlocked; see
  [The production guardrail](#the-production-guardrail).
- **History.** The ledger (`GET /api/runs`), filtered by date, protocol, outcome and result; one
  run in full; a note on a run; CSV export; replay; the count of runs recorded today
  (`GET /api/runs/health`), which is how you notice that tracking has stopped.
- **Config.** Upload a Postman environment and collection, choose the active environment, see and
  correct how each request was mapped, unlock production writes for your session, and see
  everything in force (`GET /api/config/effective`).

Before anything is uploaded, the specification says each protocol tab says what it is missing and
points to Config; after an upload, each tab offers the requests mapped to its operations, the
vendor's sample body can be loaded and edited, the resolved url is shown before sending, and a
request with a pre-request script is marked. The API behind each of these was checked with the
sample files; how the screens present them was not.

## The wire view carries member data

The wire view shows the request and the response exactly as they went over the wire. That is the
point of it, and it means that **on the live profile it shows member data**: the member ID, the
name, the date of birth, and whatever the payer sent back. The lookup results do the same.

So:

- Do not screenshot or screen share the wire view, a lookup result, or a request body on live
  unless everyone who can see it may see that member's data. Collapse the wire view first.
- Do not paste a request or a response into a ledger note, a ticket or a chat. A note is free text
  and is written to disk as typed.
- On the mock profile every member is synthetic, which is what makes the default safe to demo.

PAVE never writes the wire view to disk: the ledger holds no request or response body, no name,
date of birth, address, gender or subscriber ID under any setting. Every `/api/` response carries
`Cache-Control: no-store`, so the browser does not keep member data in its cache either (observed
on the running tool).

One known gap on this revision: a credential that a service sends back inside a Subscription
(`channel.header`) is hidden in the designed view but still visible in the wire view. A fix is on a
follow-up branch.

## Who can reach this tool

PAVE has **no sign-in**. Anyone who can reach its port can use everything it offers. On the live
profile that means looking up real UAT members, submitting prior authorizations to whatever
environment is active, reading the ledger, and uploading or deleting configuration. So by default:

- **It listens on this machine only.** `server.address` is `127.0.0.1` in `application.yml`
  (decision D-115). Other machines cannot connect at all. The cost is the receiver: no vendor can
  reach `POST /api/pas/notifications` until this is changed.
- **It answers only to `localhost`**, a loopback address, the bound address, or a host name listed
  under `app.allowed-hosts` (D-116, D-127). A request addressed to any other name gets 403. This
  stops a web page served from another name that points at this machine from reading answers.
  Observed: `Host: other.example.invalid` answered 403, and with
  `--app.allowed-hosts=pave-box.example.invalid` that name answered 200.
- **It refuses a change that comes from another site.** A request that changes something and names
  another origin gets 403 ("This request came from a page that is not part of this tool, so it was
  not carried out."). A client that names no origin, such as curl or a vendor's server, is let
  through. The session cookie, which holds the production unlock, is `SameSite=Strict` and
  `HttpOnly`.
- **Nothing it serves is stored by the browser** (`Cache-Control: no-store` on every response), and
  pages load scripts, styles and data from this tool only (a Content-Security-Policy header).

The notification receiver is exempt from the host check, for that exact address only, because a
vendor addresses it by whatever name it was given. Observed: a POST to `/api/pas/notifications`
with a foreign `Host` answered 200 with no body and was recorded.

**Opening it to the network.** Start with `--server.address=0.0.0.0` (or one interface address)
and list the name others will use under `app.allowed-hosts`. This was not tried for this README:
only `app.allowed-hosts` was, on this machine. Then every machine that can reach that
address can do everything listed above, with nobody signing in. **Do not do this without asking
whoever owns the installation and whoever owns the environment it points at**, and never with a
production environment active. If the point is to receive notifications, also set
`onyx.subscription.callback-url` (or the collection's callback variable) to the address the vendor
can reach.

## Configuration through Postman

No endpoint url, variable or credential is built into PAVE. It reads the Postman files the vendor
ships. `application.yml` holds behaviour and storage locations; it needs no edits in normal use.

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
- Uploading never activates. Activating switches every tab at runtime, with no restart, and drops
  any production unlock.
- A disabled variable is listed and ignored, as Postman ignores it.
- Several environments can be held at once. The path takes the environment's name, url-encoded, or
  its id from the upload answer. The id is derived from the name, so the sample's id is always
  `pave-sample-sandbox-977ed8ee`.
- The active environment is remembered across a restart (observed). `config-store.active-environment`
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
- **`POST /api/raw` does not send the body saved in the collection.** It sends the request's
  method, url and headers, with placeholders resolved, and a body only when you supply one as
  `rawBody`. To send the saved body, read it from
  `GET /api/config/collections/{id}/requests/{requestId}` (the `body` field), edit it if you like,
  and pass it as `rawBody`. Without `rawBody`, a POST such as `Claim/$submit` goes out with no
  body, and `POST /api/raw/preview` says so ("No body will be sent, because none was supplied.").
  Observed with the sample's `submit Happy Path`.
- **Pre-request scripts are never run.** A request that has one is flagged, and every run that
  uses it carries the finding `PRE_REQUEST_SCRIPT_NOT_RUN`, because a value Postman would have
  computed was not computed here. Observed with the sample's `order-sign Happy Path`.

### Variables

A `{{placeholder}}` is resolved from, in order: a value typed for this run, the active environment,
the variables of the collection the request came from, then `onyx.variables` in
`application.yml`. A defined but blank variable counts as not set. When a placeholder cannot be
resolved the request is **not sent**: the answer is 422 (`"kind":"CONFIGURATION"`) and names the
variable and the active environment, for example "PAS inquire cannot be sent: the placeholder
{{baseUrl}} has no value, and the active environment is "Blank Url Sandbox". Nothing was sent."
(observed on live). `POST /api/config/resolve` shows what a request would become without sending
it.

**On mock the url of a CRD, DTR or PAS run is not resolved at all**, because nothing is sent: the
scenario answers whatever the environment holds. Observed: with `baseUrl` defined but blank, a PAS
submit, a CRD run and a DTR package request on mock each answered 200 with no finding about the url.
So on mock, an incomplete environment is found only by asking: `POST /api/config/resolve` marks each
unresolved variable `UNRESOLVED` with the reason. `POST /api/raw` is the exception: on mock it
does refuse an unresolved placeholder with 422.

`{{$guid}}`, `{{$randomUUID}}`, `{{$timestamp}}` and `{{$isoTimestamp}}` are generated. Any other
dynamic variable, such as `{{$randomInt}}`, is not computed: a request that needs one is refused
with 422 naming it, and nothing is sent, unless you type a value for it for that run (in
`variables`, for example `"variables":{"$randomInt":"5"}`). Observed with `POST /api/raw` on mock.

### Secrets

An exported environment can hold a client secret in plain text, and PAVE keeps uploaded files
verbatim in `config-store/`. So:

- **The upload warns**, naming the variable and the stored file, when a secret variable holds
  literal text.
- **Secret variables are masked** in every answer that shows configuration: the answer says the
  secret is set, not what it is. Observed: a variable `apiKey` holding literal text did not appear
  in the upload answer or in `GET /api/config/effective`. A variable is secret when Postman typed
  it `secret` or when its name reads as a credential.
  Redaction is by name, never by matching a value. On the wire view, credential headers
  (`Authorization`, `Cookie`, API key headers) show at most their scheme.
- **What masking does not catch.** Because masking goes by the variable's name, a key written
  inside a url, or held by a variable with an ordinary name, is shown as written, in the upload
  answer, in `GET /api/config/effective`, in `POST /api/config/resolve` and in the wire view.
  Observed: an environment whose `baseUrl` was
  `https://sandbox.onyx.example.invalid/fhir?api_key=LITERALURLKEY999` showed that key in all
  three answers, while a variable named `apiKey` in the same file was masked. Keep every key in a
  variable of its own whose name reads as a credential (`apiKey`, `clientSecret`, `token`), typed
  `secret`, and refer to it from the url or the header.
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
  no warning (observed). Reading the variable at send time was tested in the build, not against a
  real token server.
- Scalar settings in `application.yml` can also come from the environment through Spring's usual
  mapping, for example `ONYX_AUTH_CLIENT_SECRET` for `onyx.auth.client-secret`.
- `config-store/` and `run-history/` are listed in `.gitignore`. Never commit `config-store/`: it
  holds what you uploaded, credentials included.

**Access tokens.** OAuth2 client credentials are requested when `onyx.auth.enabled` is true. The
four `onyx.auth` values are resolved like any placeholder, so they can point at the environment's
variables, for example `--onyx.auth.enabled=true --onyx.auth.token-url={{tokenUrl}}
--onyx.auth.client-id={{clientId}} --onyx.auth.client-secret={{clientSecret}}`. This is how the
code reads them; it was not run against a token server. A Postman authentication helper in a
collection is reported and not applied.

### Environment kinds

| Kind | Banner on screen (not checked) | Writes |
| --- | --- | --- |
| `sandbox` | none | allowed |
| `uat` | yes | allowed |
| `prod` | yes, persistent | blocked unless unlocked, and never when the floor forbids it |

Every ledger line records the kind of the environment it ran under.

### The production guardrail

A PAS submit against production creates a real prior authorization request against a real member.
On a `prod` environment:

- **Reads go through**: inquiry, subscription read and list, discovery, the lookups. Observed for
  the inquiry.
- **Writes are blocked**: PAS submit and subscription create, update and delete. A blocked write
  answers 403 with a full result saying nothing was sent, and is recorded in the ledger as
  `BLOCKED`.
- **The floor.** `config-store.allow-prod-writes` is `false` by default, and while it is false no
  unlock is possible from any screen. Only whoever owns the installation can change it, with a
  restart. Observed: "Production writes cannot be unlocked: an owner has locked this installation
  against them. Nothing was sent."
- **The unlock.** With the floor set to `true`, a tester unlocks writes for their own browser
  session by typing the active environment's name exactly:

  ```
  curl -s -c pave-cookies -b pave-cookies -X POST -H 'Content-Type: application/json' -d '{"action":"unlock","environment":"Exact Environment Name"}' localhost:8057/api/config/writes
  ```

  This was checked with an environment of another name, uploaded as `prod`, with the floor set to
  `true` on the command line (`--config-store.allow-prod-writes=true`). A wrong name, even in other
  capitals, answers 422. The unlock lives in server memory, in that session only. It is never
  written anywhere, so it does not survive a restart (by design; a restart with an unlock held was
  not tried). Another browser or another tester stays locked (observed). Any change
  to the configuration, including switching environment, drops it (observed). `{"action":"lock"}`
  locks again.
- A replay or a suite never carries an unlock, so a write in a replay or a suite against production
  is reported as blocked. A deliberate production write is made one at a time.
- Two further routes were closed during the build (D-125): a write whose url contains "prod" is
  refused under an environment not declared production unless the uploader confirmed it is not;
  and a request named like a read that would change something is judged as a write. "Reads as
  production" means the text `prod` anywhere in the url and nothing cleverer.

**Not yet audited.** The independent guardrail audit (V3 in the specification) was running when
this README was written and had not reported. The behaviour above was observed on mock, where the
guardrail is designed to behave as it does on live (D-021). Live was not tried with a production
environment.

## The test run ledger

Every run, on both profiles, is appended as one JSON line to
`run-history/runs-YYYY-MM-DD.jsonl` (one file per day; the folder is beside `run.sh` when started
with it, otherwise in the directory you start from; `tracking.directory` moves it). A line is
written before the result is shown, then flushed and synced to disk (this was checked by reading
the code: no test can observe a sync); files are never rewritten. If a write
fails, the result says so. If the folder cannot be written at startup, the tool refuses to start
and names the path. `GET /api/runs/health` says whether tracking is alive and how many runs were
recorded today.

**A line holds:** run id, time, protocol and operation, target, profile, environment and its kind,
scenario, procedure codes, line of business, outcome, the standard codes returned, delegated
vendor, HTTP status, elapsed time, error type, what was expected and the result (pass, fail or
unverified), note, tester reference, and where they apply the chain, suite and case ids, the hook,
the next step the result stated, the collection request used, the findings, correlation ids (claim
id, ClaimResponse id), and the inputs the line does not hold (a hand-edited body, details typed by
hand, values typed for one run, answers carried from an earlier step), so a replay can refuse what
it cannot reproduce.

**A line never holds**, under any setting: member first or last name, date of birth, address,
gender, subscriber ID, or any request or response body. Observed on the running tool: after CRD,
DTR and PAS runs, one of which sent a member named `Placeholder-Surname` born `1970-01-01`, neither
the name, the date nor any `resourceType` appeared in the ledger file.

**The member ID policy** has two settings:

```yaml
tracking:
  record-member-id: false     # the mock profile sets it to true
  record-npi: true            # an NPI is not PHI
```

- On mock, member IDs are synthetic and are recorded, so runs can be replayed.
- On live, by default, the member ID is replaced by a salted token (HMAC-SHA-256) and its last four
  characters, so two runs of one member can be matched without the file becoming a roster. The salt
  is created at first start in `config-store/installation-salt`, or wherever `tracking.salt-file`
  says; it may not be inside the ledger folder. Deleting it, or moving the ledger without it, breaks
  matching with older tokens. An authorization number is treated like a member ID.
- If an owner sets `record-member-id: true` on live, PAVE honours it and logs one warning at
  startup. Not observed: the jar was not started that way for this README.
- A run whose member ID was not recorded cannot be replayed, and replay says so rather than
  skipping it.

**A note is free text that PAVE cannot vet.** `POST /api/runs/{runId}/note` keeps what you type,
up to 4,000 characters, and writes it to disk. Do not put member data in it. The tester reference,
the expectation and a suite's case id are written as given too.

**Export** with `GET /api/runs/export.csv?from=YYYY-MM-DD&to=YYYY-MM-DD` (both optional): UTF-8 with
a byte order mark, newest first, one column per field. A cell that starts with `=`, `+`, `-` or `@`
gets a leading quote so a spreadsheet shows it as text and calculates nothing.

**Replay** with `POST /api/runs/replay`, by date range or run ids: each run is sent again against
the configuration in force now and compared with the original (same, changed, not comparable, not
replayable, blocked, not configured, invalid, error). A replayed submission is a new submission with
a new claim id. At most 1,000 runs per replay.

## The procedure code list

The picker shows what the P32H code list says about a code before you send. The real list is a
P32H export of about 7,000 rows whose header row is not known.

**Until it is supplied, a synthetic placeholder is used**: `src/main/resources/codelist/placeholder-codes.csv`,
43 rows, every one marked "Placeholder row. Not from a P32H export." The startup log and the
self-check both say so.

**What that means for findings, on this revision:** the conformance check compares each CRD answer
with whatever list is loaded. With the placeholder, a finding such as "the code list disagrees with
the service" is a statement about synthetic rows, and the finding itself does not yet say that the
placeholder was used. On mock both sides are synthetic. **On live, with the placeholder still in
place, ignore code list findings.** A follow-up branch changes this so the finding says so, and so
that live does not compare with the placeholder at all.

**Pointing at the real export.** Set `codelist.file` to its path (a relative path is resolved
against the directory PAVE starts in) and restart:

```
./run.sh mock --codelist.file=samples/codelist/sample-code-list.csv
```

`samples/codelist/sample-code-list.csv` is a sample in the shape the real export is expected to
have, every row marked `SAMPLE ROW`. Its vendor names are real: they are the four delegated
vendors the specification names as current (eviCore, Evolent, Carelon, Optum Post Acute), the
specification asks for them in fixtures, and they are the only names the tool recognises as
vendors (D-081), so a made-up name would be treated as an unknown vendor. The codes are real CPT
and HCPCS codes. **Which code goes to which vendor, and every flag and date, is invented**: it
follows the specialties the specification lists (sleep, imaging, genetic testing, post-acute) and
says nothing about P32H's actual assignments. The bundled placeholder list is built the same way.
A test loads it through the real loader, and the command above
was run: the self-check then answered `"placeholder":false`, `"rowCount":11`,
`"statingAuthRequirement":10`, `"namingVendor":7` and `"unrecognisedColumns":[]`, with each column
found under the sample's own header (`"code":"CPT/HCPCS"`, `"authRequired":"Prior Auth Required"`,
`"lineOfBusiness":"LOB"` and so on).

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
- Dates are read as `2026-01-31`, `1/31/2026` or `20260131`. Anything else is kept as written.
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
- `POST /api/suites/preview` says what the tool makes of a file without sending anything.
- One suite runs at a time. Writes against production are reported as blocked, case by case.
- A suite is pinned to the configuration it started under. If the configuration changes while it
  runs (an upload, a removal, a switch of environment), it stops and says so, and the cases left
  are marked as not run. Observed: "The configuration changed while the suite was running."
- Every case writes its own ledger line. Results can be exported as CSV.

**A chain** is one case carried through CRD, DTR and PAS. Start it by sending a run with
`"chainId":"new"`; the result carries the new chain id, and later runs sent with that id join it.
`GET /api/chains/{chainId}` shows each step, its outcome and what comes next, read back from the
ledger. A step can be run again without the ones before it.

## Endpoints

Every endpoint answers JSON in one shared error shape, never an HTML error page. The statuses:

- **200** for a completed call, **including one where the service returned a fault**: the fault is
  described in the body, with the request that caused it.
- **422** when the form failed validation (and for an unknown property in a request body, which is
  named), and on live (and for `POST /api/raw` on mock too) when a placeholder the request needs
  has no value (`"kind":"CONFIGURATION"`,
  naming the variable and the active environment; see [Variables](#variables)).
- **403** when the production guardrail blocked a write, or when the request came from another
  site or a host name the tool does not answer to.
- **409** when no url is configured for an operation, or the configuration changed since the screen
  was drawn.
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
| GET | `/api/providers/{npi}` | Provider lookup (the NPI check digit is checked first) |
| GET | `/api/procedure-codes?q=&lob=` | Code type ahead |
| GET | `/api/procedure-codes/self-check` | What the tool made of the code list |
| POST | `/api/crd` | Run one CRD call |
| POST | `/api/crd/preview` | The CRD request, without sending it |
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
| POST | `/api/raw` | Send one collection request: its method, url and headers, and `rawBody` if given (the saved body is not sent) |
| POST | `/api/raw/preview` | The same, without sending it |
| GET | `/api/runs?from=&to=&protocol=&operation=&result=&outcome=&chainId=&suiteRunId=&limit=` | Query the ledger |
| GET | `/api/runs/{runId}` | One run |
| POST | `/api/runs/{runId}/note` | Add or change a note |
| GET | `/api/runs/export.csv?from=&to=` | CSV for sign-off |
| POST | `/api/runs/replay` | Run again and compare |
| GET | `/api/runs/health` | Is tracking alive, runs recorded today |
| GET | `/api/chains?from=&to=&limit=` | Chains, newest first |
| GET | `/api/chains/{chainId}` | One chain, step by step |
| POST | `/api/suites/preview` | What the tool makes of a suite file (multipart: `file`) |
| POST | `/api/suites` | Start a suite (multipart: `file`, optional `testerRef`, `compareWith`, `configEpoch`); answers 202 |
| GET | `/api/suites` | Suite runs held in memory |
| GET | `/api/suites/{suiteRunId}?rows=` | One suite run |
| POST | `/api/suites/{suiteRunId}/stop` | Stop a running suite |
| GET | `/api/suites/{suiteRunId}/results.csv` | Suite results as CSV |

### Examples, on mock

Run them in this order from `epa-pave/`, with the tool started by `./run.sh`. They upload the two
sample Postman files first, and every identifier in them is a placeholder: `P32H-...` sentinels,
`S123456789`-style member IDs and the NPI `1234567893`. Where an example says `RUN_ID`,
`CHAIN_ID` or `SUITE_RUN_ID`, put in the id from an earlier answer (`runId` on any run result,
`chainId` on the chain example, `suiteRunId` on the suite example). Two of them save a CSV file in
the current directory (`pave-runs.csv`, `suite-results.csv`). Version control does not ignore
those, so delete them when you are done; on live they hold ledger data. Each was run as written, with
those three ids put in, against a fresh clone; the status each answered is in the comment above it.

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
# 200, NOT_FOUND
curl -s localhost:8057/api/members/P32H-NOMEM
# 200, FOUND
curl -s localhost:8057/api/providers/1234567893
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

Sending a collection request (method, url and headers; a body only when you give `rawBody`):

```bash
# 200: on mock every raw request gets the same fixed answer, which says the mock does not model it
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-6d8194d8fa41"}}' localhost:8057/api/raw
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-6d8194d8fa41"}}' localhost:8057/api/raw/preview
# 200: "No body will be sent, because none was supplied.", although the saved request has a body
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-e8574dbaa06d"}}' localhost:8057/api/raw/preview
# 200: with rawBody, the preview shows the body that would be sent
curl -s -X POST -H 'Content-Type: application/json' -d '{"request":{"collectionId":"pave-sample-collection-632d456c","requestId":"r-e8574dbaa06d"},"rawBody":"{\"resourceType\":\"Bundle\",\"type\":\"collection\"}"}' localhost:8057/api/raw/preview
```

The ledger and chains:

```bash
# 200
curl -s 'localhost:8057/api/runs?protocol=CRD&limit=5'
# 200
curl -s localhost:8057/api/runs/RUN_ID
# 200
curl -s -X POST -H 'Content-Type: application/json' -d '{"note":"Checked for ticket T-0001","testerRef":"SAMPLE"}' localhost:8057/api/runs/RUN_ID/note
# 200: writes pave-runs.csv in the current directory
curl -s -o pave-runs.csv localhost:8057/api/runs/export.csv
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
| `spring.servlet.multipart.max-request-size` | `30MB` | Largest upload request |
| `server.port` | `8057` | The port. Not 8080, so PAVE can run beside the tools that use 8080 |
| `server.address` | `127.0.0.1` | Listen on this machine only. See [Who can reach this tool](#who-can-reach-this-tool) |
| `server.servlet.session.cookie.name` | `PAVESESSION` | A name of its own, so other tools on localhost do not overwrite PAVE's session |
| `server.servlet.session.cookie.same-site` | `strict` | The production unlock lives in the session; no other site can borrow it |
| `server.servlet.session.cookie.http-only` | `true` | No script reads the session cookie |
| `server.error.whitelabel.enabled` | `false` | No HTML error page; errors are JSON |
| `logging.level.org.apache.coyote.http11.Http11Processor` | `WARN` | Keeps a refused address, which can hold a member ID, out of the log (D-128) |
| `logging.level.org.springframework.web.servlet.resource.ResourceHandlerUtils` | `ERROR` | The same, for a path with `../` in it |
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
| `tracking.salt-file` | `""` | Blank: `installation-salt` inside the config store directory. Never inside the ledger folder |
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

**Not verified.** No STS installation was available, so no minimum version is claimed. What is
known: the project needs an IDE that supports Java 21 and a Gradle integration (Buildship) that can
run Gradle 9.5. Check the release notes of your STS for both before importing.

- Import with File, Import, Gradle, Existing Gradle Project, and choose the `epa-pave/` directory,
  not the repository root.
- Point STS at a JDK 21: Window, Preferences, Java, Installed JREs, add the JDK 21 and tick it.
  Also set Java home in the Gradle preferences, or in the import wizard, to the same JDK.
- **Red markers before the first resolution finishes are normal.** The project has no classes to
  compile against until Gradle has downloaded the dependencies.
- **If they stay, first try Gradle, Refresh Gradle Project** (right click the project). Most
  import problems are a refresh that never ran or ran before the mirror settings were in place.
- `bin/`, `.classpath`, `.project` and `.settings/` that STS creates are in `.gitignore`.

## Open items

Each of these is unknown. PAVE uses an obvious placeholder for it rather than a plausible guess.

| Open item | What stands in for it | Where the real value goes |
| --- | --- | --- |
| The Onyx CRD service id | The Postman variable `crdOrderSignServiceId` (and `crdOrderSelectServiceId`); the sample uses `placeholder-order-sign-service` | The vendor's environment file. `GET /api/crd/discover` lists what the environment offers. Fallback: `onyx.variables` in `application.yml` |
| Whether the sandbox collection has `order-select` and a `/cds-services` discovery request | Detection reads both by name and by url; neither is in the sample | Upload the vendor's collection and correct with `PUT /api/config/collections/{id}/mapping` |
| The member API response contract | A tolerant mapping: several spellings per field, any common envelope | `lookup.member.url` in `application.yml`; narrow the tables at the top of `src/main/java/org/p32h/interop/epa/pave/client/lookup/MemberResponseMapping.java` (D-129) |
| The provider directory response contract | The same | `lookup.provider.url`; `src/main/java/org/p32h/interop/epa/pave/client/lookup/ProviderResponseMapping.java` |
| How the lookup APIs authenticate | Nothing is assumed | `lookup.member.headers`, `lookup.provider.headers`, or `lookup.*.use-onyx-auth` |
| The procedure code list header row, flag spellings, date format, line and vendor names | The bundled placeholder list and candidate header lists | `codelist.file`; `codelist.columns.*`; check with `GET /api/procedure-codes/self-check` |
| Client secret or private-key JWT | OAuth2 client credentials | `onyx.auth.*`. For private-key JWT, `requestToken` in `src/main/java/org/p32h/interop/epa/pave/client/support/OnyxTokenService.java` is the one method to replace |
| The PAS attachment mechanism in use | `fhir` | `onyx.pas.attachment-mode`. `x12-275` names the attachment and sends nothing else |
| Whether the receiver is reachable from Onyx | Assumed not | `server.address`, `app.allowed-hosts`, `onyx.subscription.callback-url` |
| Which version of each guide the sandbox follows (D-130, D-136) | 2.0.1 on the operator's word; DTR reads both published shapes of next-question | `wrapInParameter` on a next-question request chooses the wrapped form |
| How Onyx signals an unknown member or provider, inactive coverage and the delegated vendor (D-033) | A rule kept in the client layer, stated in each mock scenario | `src/main/java/org/p32h/interop/epa/pave/client/crd/OnyxCrdConventions.java`, `.../client/pas/OnyxPasConventions.java`, `.../client/dtr/OnyxDtrConventions.java` |
| PAS profile elements Onyx requires, the shape of `$inquire` it accepts, the review action codes it returns | Defaults sent and named in the request notes | `OnyxPasConventions.java` |
| What a subscription must name (topic, filter, payload) | A topic under `example.org/p32h-placeholder`, so a default subscription is meant to look wrong | Run values `subscriptionTopic`, `subscriptionFilter` and the others listed in D-133 |
| The shape of a notification from Onyx | Both published forms are read | Nothing to set; check the first real one |
| The reference implementation `onyx-crd-harness-1.2.0.zip` (D-001) | Not available; everything was written fresh | Compare its scenario bodies and operation detector with this build if it turns up |

## Limitations

What is not done, not verified, or known to be wrong on this revision. `PROGRESS.md` is the
project's own account, but **on this revision it is out of date about this work**: it still says
that the README, `run.sh`, `run.cmd` and the sample files do not exist, gives the build as 1831
tests (1,846 now, 2 of them skipped), and lists Wave C2 as not started. Everything else it lists
as not done is repeated below.

- **Nothing has been run against Onyx or any real service.** Every result anyone has seen came from
  the mock profile or a fake.
- **No screens on this revision.** The frontend is on its own branch.
- **Message shapes do not conform to 2.0.1.** On this revision they were unverified: CRD and PAS
  were written from memory of the guides, and DTR was compared by its own author only. The
  operator has since supplied the CRD and DTR 2.0.1 packages (`hl7.fhir.us.davinci-crd-2.0.1.tgz`
  and `hl7.fhir.us.davinci-dtr-2.0.1.tgz`), which removes the reason D-136 gave for comparing CRD
  and DTR with the 2.2.0 ballot only. The comparison with 2.0.1 reported on a later commit of the
  `epa-pave` branch: the tool does not conform (see
  [What PAVE is not](#what-pave-is-not)). None of it is fixed on this revision. This README was not
  compared with those packages.
- **The section 27 verification agents** other than the member data audit had not run. The
  guardrail audit, the fixture audit and the independent review of the integrator's own code (the
  case runner, suites, the chain, the request guard) were owed.
- **Code list findings with the placeholder list** do not say that the placeholder was used. On
  live, ignore them until a real export is loaded.
- **A secret a service sends back** in a Subscription's `channel.header` is visible in the wire
  view.
- **No real vendor Postman export was available.** The sample files and every test file were
  written by hand to the shape the specification describes. Detection by url is verified only for
  the standard FHIR operation paths and `/cds-services`.
- **The notification receiver** does not authenticate its caller, has never been called by a real
  payer, holds at most 200 notifications in memory (each cut at 128 KiB) and loses them on restart;
  the ledger lines remain.
- **In attachment mode `x12-275`** nothing sends the attachment.
- **DTR:** which version of the guide the sandbox follows is not known and decides the shape of
  next-question. A response built from nothing states no intended use. The author of an answer is
  named without a role. `$expand` and `$log-questionnaire-errors` are not implemented. A failure of
  the tool in the response step answers 500 and writes no ledger line.
- **A DTR response refers to its order as `ServiceRequest/service-request-1`, and the PAS bundle
  holds no ServiceRequest**, so that one reference does not resolve inside the submission (D-135).
- **Lookups** have never met the real member API or provider directory. A member ID containing a
  slash cannot be looked up. A name returned in one piece cannot fill first and last name.
- **The ledger** was run on Linux only. That a line is flushed and synced to disk was checked by
  reading the code, not by a test. Two instances pointed at one ledger folder are not
  coordinated. A note written after the clock was set back across midnight is on disk and not
  applied when the run is read.
- **Comparing a pasted CRD request with the tool's own** (D-119) is not in this revision.
- **Concurrency in the configuration store** (two uploads or a switch at the same moment) is
  reasoned about and not tested.
- **Two notifications arriving at the same moment** at the receiver are not tested.
- **The receiver stops reading a notification at 8 MiB** (D-133): what lies beyond is not read,
  and the record says the size is not known. Of what it reads it keeps the first 128 KiB.
- **Reviewed by nobody but their author:** the chain as a suite case and the wiring of the second
  wave (PAS and DTR into the application). The independent review of the integrator's own code
  covered the first wave only.
- **A key written into a url, or held by a variable with an ordinary name,** is shown as written
  wherever configuration is shown (see [Secrets](#secrets)).
- **`POST /api/raw` sends no body unless one is supplied** as `rawBody`; it does not send the body
  saved in the collection.
- **On mock, a run does not check that its url resolves.** Use `POST /api/config/resolve`.
- **`run.cmd` has not been run.** No Windows machine was available. It was written to do what
  `run.sh` does, and corrected once after a reader found, by reading, that its Java version check
  would have failed on every machine. Quote an argument with an equals sign
  (`run.cmd mock "--server.port=8091"`). If it fails, use the two commands under step 4 of the
  checklist.
- **Two tests are skipped** when the build runs as root, because a read-only folder is not
  read-only for root. The same refusals are covered by tests that do run.

## What this README was checked against

- **Revision:** branch `docs-c2` at the base commit `52469d7` with this README, the run scripts and
  the samples added. The frontend, the audits and the follow-up branches were not on it.
- **Where:** Linux, OpenJDK 21.0.13, Gradle 9.5.0 through the wrapper, behind a TLS-inspecting
  proxy. The Gradle cache already held the dependencies, so how long a first download takes was not
  measured.
- **Fresh clone:** the branch was cloned into a new directory, twice, and `./gradlew clean build`
  ran in each: 1,844 tests passed, 2 skipped, 0 failed, both times. After a newcomer's reading
  of this README was answered, a third fresh clone built the same way (1,846 tests, 2 skipped, 0
  failed). The clones were made from a local copy of the branch. `git clone --branch epa-pave`
  from GitHub was run too, on 2026-11-19: it gave commit `44c7760`, whose `epa-pave/` holds no
  README, `run.sh`, `run.cmd` or `samples/` (see step 2).
- **`run.sh`** was started from another directory with the jar missing: it built the jar, started on
  the mock profile on port 8057, `curl -s localhost:8057/api/mode` answered as shown in step 5, and
  it was stopped. `./run.sh live` was started the same way on another port and stopped. Its refusal
  of Java 8 and of an unknown profile was checked.
- **Every `curl` example under [Endpoints](#endpoints)** was run against the second fresh clone,
  started with `./run.sh` on port 8057, in order, and answered the status in its comment: 66
  examples, none different, on the third clone. `./run.sh mock --codelist.file=samples/codelist/sample-code-list.csv`
  and `./run.sh mock --server.port=8091` were run too.
- **Observed on the running tool, as quoted above:** the production guardrail (floor, unlock, a
  second session, switching), the host and origin checks, the receiver, the ledger contents, the
  lookups, the code list self-check, `POST /api/raw` sending no saved body, a key in a url shown
  as written, `{{$randomInt}}` refused, and an unresolved url ignored on mock and refused on live.
- **Not checked:** the screens, `run.cmd`, STS, a credentialed mirror, a token server, Onyx.
