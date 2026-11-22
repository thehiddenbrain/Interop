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

**Release 1 of the rebuilt screens.** The screens were rebuilt after the operator found the earlier
ones unusable; the engine behind them (the requests, the reading of the answers, the guardrail, the
ledger) is the one PAVE had. The top bar shows the build's day and time in UTC; the same time is
`build.time` in `GET /api/mode`.
<!-- R1-LAST: the release build (head, build stamp, jar, hand-over hour) once it is built whole after the corrections -->
Release 1 is handed over on 2026-11-22 at 15:00 UTC: the integration branch's release candidate
`7fc32c8` with the corrections the checks asked for. The build handed over is the release branch
at the head named in `PROGRESS.md`, built whole after the corrections; its stamp is the time that
build was made.
<!-- /R1-LAST -->

What release 1 holds:

- One top bar and three tabs: **Workbench**, **Audit** and **Configuration**.
- The Workbench: the case on the left (Member, Provider, Service), and four steps on the right,
  **Coverage check** (CRD), **Questionnaire** (DTR), **Submit** (PAS) and **Status** (PAS
  inquiry, subscriptions, notifications). Each result is a row of chips, one headline, a few
  facts and closed folds.
- Configuration: Environments edited on screen (sign-in, member ID rule, timeouts, request shapes,
  values, an address per operation), Collections, saved test Members and Providers with a CSV
  import, Access token with a token pasted for the session, Production writes, and Advanced: In
  force now, Send as written, Try a value, Check this installation, CDS discovery and Compare with
  a request that works.
- Audit: the runs with filters, one run with "Run again", "Export CSV", "Replay the runs listed".
- Submit follows Da Vinci PAS 2.2.1 (CRD and DTR stay at 2.0.1); the request shapes the vendor's
  sandbox accepted; one retry after a refused token; a wait of up to 120 s for the vendor.
- The fixes for the ways an upload was found to fail, except one after a rebuild under a PAVE
  started with `-Dpave.classes.load-at-start=false` (see [Limitations](#limitations)).
  <!-- R1-LAST: I-21, I-23, I-24, AU-1, AU-2, AU-3, features-6 -->
  A file that is not text is named as what it is; a CSV saved with semicolons or tabs is told how
  to save it again; a Postman file of the wrong kind is told where it goes.
  <!-- /R1-LAST -->

**Not in release 1; release 2, at 22:00 UTC the same day, brings them:**

- The trail of the last 500 runs' requests and responses, and what reads it: a run's request and
  answer in Audit (in release 1 only the page that made a run shows them, while it stays open) and
  Download for the vendor. Configuration > Advanced > Trail says "This page comes in the next
  release."
- A reload of the Workbench that keeps the case. In release 1 a reload starts a new case; the runs
  of the case before are in Audit.
  <!-- R1-LAST: I-8, wb-mock-6, wbm3-6, features-4 -->
  The page says so under the top bar after a reload.
  <!-- /R1-LAST -->
- Member and provider lookups configured on screen (GET or POST, their own sign-in). In release 1
  they come from `application.yml`, or you type the details (see
  [Lookups and test lists](#lookups-and-test-lists)); Configuration has no Lookups page.
- Questionnaire answers filled from the case. In release 1 every answer is typed.
- Journeys and Suites in Audit. In release 1 Audit shows the runs only; suites run through the API
  (see [Audit](#audit)).
- A button on Production writes that forgets an address remembered as production by mistake. In
  release 1 that is a hand edit of the store (see
  [A production address remembered by mistake](#a-production-address-remembered-by-mistake)).
- Values typed for one run on the Workbench, the other store repairs (unreadable stored files
  listed, a lock against a second copy on one store), and the place names below brought up to
  date.

**Old place names in some server sentences.** Some sentences the server writes still name the old
screens.
<!-- R1-LAST: I-1 -->
The sentences of the Workbench name the new places in release 1; the others, in Configuration,
Audit and the API, keep the old names until release 2.
<!-- /R1-LAST -->
Read them this way:

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

The sentences of the production guardrail keep their words in release 1 (release 2 may change only
their place names), "the Config tab" and "the environment strip" included: there the Config tab is
Configuration and the strip is the top bar.

**Before you rely on it**: nothing here has been run against the vendor's service or on the
operator's Windows workstation; see [Before you rely on it](#before-you-rely-on-it). How release 1
was checked: [How release 1 was checked](#how-release-1-was-checked).

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
yellow `MOCK` badge and the build's day and time. Go on with
[Your first case on mock](#your-first-case-on-mock). To stop PAVE, press Ctrl+C in its terminal;
a call still out gets up to 30 seconds to answer, so that its ledger line is written.

**From STS, and after every pull.** Stop PAVE in STS before Team > Pull or Gradle > Refresh Gradle
Project, and start it again after. If you did not, PAVE keeps its old classes while the IDE
rebuilds them, and a line under the top bar says "PAVE's files changed on disk since it started.
Stop PAVE and start it again." An internal error that names `NoClassDefFoundError` or
`NoSuchMethodError` means the same: stop and start it.
<!-- R1-LAST: I-12, I-13, STS-1, STS-2 -->
While that line shows, a refusal on any screen ends with "PAVE's files changed on disk since it
started, which can cause this. Stop PAVE and start it again, then try once more." A page left open
while PAVE restarts shows what the new start holds, without a notice that the configuration
changed in another window.
<!-- /R1-LAST -->

**One copy of PAVE per configuration store and ledger folder.** To run mock and live side by side,
give the second copy folders and a port of its own:

```
./run.sh live --config-store.directory=config-store-live --tracking.directory=run-history-live --server.port=8058
run.cmd live "--config-store.directory=config-store-live" "--tracking.directory=run-history-live" "--server.port=8058"
```

In release 1 nothing stops a second copy on the same folders, and the two then overwrite each
other's store; from release 2 the second copy is refused.

## Before you rely on it

- **Nothing here has been run against the vendor's service**, and nothing on the operator's
  Windows workstation. Every result anyone has seen came from the mock profile or from a stand-in
  server on a loopback port of a Linux machine.
- **A pass on mock says nothing about the live service.** The scenario files were written from the
  same reading of the guides as the code that reads the answers.
- **On live the screens show member data**: the wire view, the lookups, the saved test lists. See
  [The wire view carries member data](#the-wire-view-carries-member-data).
- **PAVE has no sign-in** and listens on this machine only. See
  [Who can reach this tool](#who-can-reach-this-tool).
- **DTR here is not a SMART launch**, and the notification receiver very likely cannot be reached
  by the vendor. See [What PAVE is not](#what-pave-is-not).
- **The configuration store holds secrets and test data in plain text**, readable by your account
  only. Never commit `config-store/`. See [Secrets](#secrets).

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
<!-- R1-LAST: critic-1, critic-8, wbm3-3, look-3, look-5 -->
Under it, in the same one card, come the service's own words (a CDS service's summary and detail
included), its issues and the next step.
<!-- /R1-LAST -->
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
   Postman environment export as "Environment export" and, if you have it, the collection export
   as "Collection export", and press "Fill the form from these files". The form is filled for
   review; nothing is saved until "Save environment". **Name**: taken from the environment export
   when one is chosen, otherwise typed; it cannot be changed once saved (see "Moving from the
   sandbox to UAT" below).
2. **Kind**: sandbox, uat or prod. It is never guessed:
   <!-- R1-LAST: I-15, look-14 -->
   the field reads "Choose a kind" until you choose.
   <!-- /R1-LAST -->
   An environment whose name or address reads as production cannot be saved as sandbox or uat until
   "This environment is not production" is ticked.
3. **Sign-in**, filled from the export's variable names:
   <!-- R1-LAST: I-15, look-14 -->
   "As PAVE was started" (the `onyx.auth` settings of `application.yml`),
   <!-- /R1-LAST -->
   "None", "Client credentials" (token url, client id, client secret, scope, client authentication
   "Basic header" or "In the form body") or "Bearer token". A 401 from the service drops the token,
   signs in again and sends the call once more; the wire view says "The first attempt was refused
   with HTTP 401; PAVE signed in again and sent it once more."
4. **Save environment**, then make it active with "Make active" in the list or in the top bar's
   Environment. Saving never changes the active environment.
5. **Edit** the saved environment for the rest; the new form says "The member ID rule, request
   shapes, values, timeouts and addresses can be set once the environment is saved."
   - **Member ID rule**: packed (spaces removed, the default and what the vendor's sandbox wants),
     spaced (nine characters, three spaces, the last two, as on the card) or split (the first nine
     of an 11-character ID). Every result shows the form sent: "Sent as <sent>, rule: <rule>".
   - **Timeouts**: the wait for an answer is 120000 ms unless the environment states its own.
   - **Request shapes**: "draftOrders type", "Sent (FHIR requires it)" or "Left out (this vendor
     refuses it)". Filled from the collection's own sample when the environment states none. The
     builders' sample collection carries the type, so the vendor's sandbox (and the stand-in that
     plays it) answers the first coverage check with HTTP 400 until this reads Left out.
   - **Values**: the FHIR server, the payer's name and identifier, the member ID system, the PAS
     request, service, certification and report types, the callback url, and any other variable a
     request reads. A value lies over the export's own; blank brings the export's back. A name that
     reads as a secret is refused there.
   - **Addresses**: one row per operation, with the address in force and where it comes from (the
     collection request, `application.yml`, typed here). A typed address replaces the collection
     request for that operation, its headers and saved body included.

Walked on the release candidate's jar on live against the stand-in: an environment made from a
copy of the sample environment export with the stand-in's addresses, and the sample collection,
saved and made active, a coverage check refused with HTTP 400, draftOrders type
set to Left out and saved, and the same check answered "Documentation is required." with no
restart.
<!-- R1-LAST: I-32, I-34, live-config-1, live-config-4 -->
When a sign-in fails, "Open Configuration" opens the active environment's form at Sign-in; when an
answer points at the address, at Addresses.
<!-- /R1-LAST -->

**Secrets.** A secret field left blank keeps the stored value; "Remove the stored secret" removes
it. Type `{{env:PAVE_NAME}}` to read it from an operating system variable at the moment of sending
(only names that start with `PAVE_` are read); anything else is stored as typed, and the save
warns once. **A token pasted on Configuration > Access token** ("Paste a token", then "Use this
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
environment on that address is refused. In release 1, with PAVE stopped, delete the entries whose
environment is that name from `productionDestinations` in `config-store/store.json`, and start
again; the upload refusal's field note says the same.
<!-- R1-LAST: I-19, features-1 -->
A send refused for this reason says so under the guardrail's sentence, names the environment the
address was remembered from and gives the same way out, and Configuration > Production writes and
`GET /api/mode` say that writes to that address are blocked.
<!-- /R1-LAST -->
From release 2 a button on Configuration > Production writes does it, for an address whose
environment is no longer stored.

**What the vendor's sandbox refused before, and what answers it now.**

| Refused before | Now |
| --- | --- |
| HTTP 400 on CRD for `context.draftOrders.type` | Request shapes, "Left out (this vendor refuses it)"; filled from the collection's sample when it leaves the type out. "Open Configuration" opens the list of environments: press Edit on the active one and open Request shapes. <!-- R1-LAST: look-3, look-5, wbm3-3 --> After that 400 the next step names the setting. <!-- /R1-LAST --> |
| A member ID with spaces | Member ID rule packed, the default |
| An inquiry with no declared profile | The inquiry Claim declares its profile; nothing to set |
| A patient or practitioner id its FHIR server did not hold | "Vendor patient id" in the Member card, "Vendor practitioner id" in the Provider card and on saved entries |
| A token refused before its stated expiry | One sign-in again and one retry after a 401 |
| An inquiry that took longer than 20 s | A wait of up to 120 s, or the environment's own timeout |

### Collections

Configuration > Collections: "Upload collection", then each request's mapping to an operation,
which can be corrected and taken back. Pre-request scripts are never run: a request that has one is
flagged, and every run that uses it carries the finding `PRE_REQUEST_SCRIPT_NOT_RUN`.

**A file that cannot be read is told so in a sentence**, on every upload: the environment form,
"Upload collection" and the lists' "Import CSV". In the checks on `9dbf61a`, none of 453 uploads
of broken or wrong files in those three places ended in the internal error box.
<!-- R1-LAST: I-21, I-23, I-24, AU-1, AU-2, AU-3, features-6 -->
A zip, a compressed file, a picture or a PDF is named as what it is; a CSV saved with semicolons
or tabs, or a JSON file chosen as CSV, is told how to save it again; an environment export chosen
as a collection, or the reverse, is told where it goes.
<!-- /R1-LAST -->

### Variables

A `{{placeholder}}` is filled from, in order: a value typed for one run (Configuration > Advanced >
Try a value, or `variables` in a request body; on the Workbench from release 2), the environment's values
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
| CRD, DTR, PAS | `payerIdentifier`, `payerIdentifierSystem`, `payerName` | The payer; the insurer of a PAS claim, which PAS 2.2.1 requires |
| CRD, PAS | `fhirServer` | The hook's `fhirServer`, and the base of every `fullUrl` PAVE sends |
| CRD | `crdOrderSignServiceId`, `crdOrderSelectServiceId` | The CDS service ids |
| DTR | `dtrIntendedUse`, `dtrCoverageExtension` | The intended use; `qr-context` (2.0.1) or `qr-coverage` (the 2.2.0 ballot) |
| PAS | `pasRequestType`, `pasServiceType`, `pasCertificationType` | Each item's request type, service type and certification type, when the Service card gives none |
| PAS | `pasReportType` | The X12 report type of the documentInformation of each attachment and of the questionnaire response |
| PAS | `itemTraceNumberSystem` | The system of the item trace numbers PAS 2.2.1 requires |
| PAS | `pasHcpcsSystem`, `coverageRelationship`, `claimIdentifierSystem`, `claimType`, `claimPriority`, `attachmentControlNumberSystem` | Parts of the claim |
| PAS | `pasRequestorType`, `pasRequestingOrgIdentifier`, `pasRequestingOrgIdentifierSystem` | The requesting organization, and a subscription's filter by it |
| PAS | `subscriptionTopic`, `subscriptionFilter`, `subscriptionStatus`, `subscriptionReason`, `subscriptionChannelType`, `subscriptionPayloadContent`, `subscriptionHeartbeatSeconds`, `subscriptionEnd` | Parts of a subscription |

### Secrets

- **Secret variables are masked** in every answer that shows configuration (`[secret apiKey]`). A
  variable is secret when Postman typed it `secret` or its name reads as a credential. Masking
  goes by name: a key written inside a url, or held by a variable with an ordinary name, is shown
  as written. Keep every key in a variable of its own whose name says what it is.
  <!-- R1-LAST: I-35 -->
  A name that says where, not what (`authBase`, `authServer`: its last word is `base`, `server`,
  `domain`, `address`, `origin`, `hostname` or `port`), is not read as a secret.
  <!-- /R1-LAST -->
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
<!-- R1-LAST: I-28, I-29, look-guardrail-3, look-guardrail-4 -->
- **A token url is held against the production addresses too**: a sign-in's token url that is, or
  lies beneath, an address a production environment uses for a call is refused at its field and
  never sent to. A token url shared with a production environment's own sign-in stays allowed.
- **An address is compared as a server reads it**: each percent-escape of its path decoded once,
  so `%24submit` is `$submit`; an address that still holds a `%` after that is refused ("An address
  holds no percent-escape of an escape.").
<!-- /R1-LAST -->
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

**Submit follows Da Vinci PAS 2.2.1**, at the operator's word; CRD and DTR stay at 2.0.1.
`GET /api/pas/options` names the version (`"version":"Da Vinci PAS 2.2.1"`) and serves the code
lists the Service card offers.

- **What it reads**: from the Service card, each code with its quantity and unit, the date and
  place of service, the diagnosis codes and, in "Service details", the level of service, the
  certification type, the request type and the service type; from the Provider card, the phone
  or fax (PAS asks for one) and an optional servicing provider; from the environment's values or
  its variables, `payerName` and `payerIdentifier` (the insurer, which 2.2.1 requires),
  `pasRequestType`, `pasServiceType`, `pasCertificationType` and `pasReportType` when the card
  gives none, and `itemTraceNumberSystem`.
- **"To complete before sending"** lists what the request would go without, one dashed chip each
  (on mock with a mock member: Report type, Insurer, Service type, Request type, Place of
  service); pressing one opens the field, on the card or in the environment's Values. Sending stays possible:
  an incomplete request is a negative test, and the request notes say what was left out ("This
  request goes out without N elements PAS 2.2.1 requires, because nobody supplied them: ...").
- **Attachments**: "Add files" in step 3. PDF, PNG, JPEG, TIFF, GIF, text and Word files, up to 5 MB
  each and 10 MB in all. In the attachment mode `fhir` (the default, `onyx.pas.attachment-mode`)
  each file travels inside the bundle as a document reference with its data; in `x12-275` PAVE
  names the document on the claim and sends no 275 transaction, which is another channel. A file
  is never kept in the ledger.
- The questionnaire response completed in step 2 goes with the request unless "Include it" is
  unticked; it is not handed on when the case changed since step 2.

## Lookups and test lists

**Saved test members and providers** live in Configuration > Members and Configuration > Providers,
for sandbox test data only: "Add member", "Import CSV", "CSV template" ("Manage lists" in the Case
column opens them). They are kept in
`config-store/test-lists/`, readable by your account only, never in the ledger or the log, at most
200 entries a list. Picking a saved entry in the Case column fills the card, each field marked
"from saved list"; it does not look the member up. Load the vendor's sandbox patient and
practitioner once, with the vendor patient id and the vendor practitioner id, by hand or with the
CSV import, which shows the columns used and each row's outcome before anything is stored. From
Excel, save the sheet as CSV first. `test-lists.enabled: false` switches the lists off.

**Lookups.** On mock, lookups answer from PAVE's synthetic fixtures. On live in release 1, the
member and provider lookups come from `application.yml` (`lookup.member.url`,
`lookup.provider.url`, see [Properties](#properties)); when none is configured, or the lookup
does not answer, the card says so and you type the fields by hand. Nothing a lookup returns is
kept. A member lookup answers "Found", "Several matched", "Not found", "Not answering", "The
lookup failed" or "No lookup is configured". Lookups configured on screen (GET or POST, their own
sign-in, a test of the saved lookup) come in release 2. The provider lookup's own contract is not known
(see [Open items](#open-items)).

## Audit

- **Runs**: today's count is on the Audit tab ("!" when the ledger is not recording, with a line
  under the top bar). Filters by day, step, test result, outcome and tester reference; "Export
  CSV"; "Replay the runs listed".
- **One run**: its result, "Run again" (sends the same case again and compares the answers, for
  example "Same outcome."), and a note and tester reference ("Save note"). A run whose ledger line
  lacks what a replay needs says so in the button's place.
  <!-- R1-LAST: I-20, features-2 -->
  On live with the default policy that is every run: "This run cannot be run again: its member ID
  was not kept."
  <!-- /R1-LAST -->
- **A run's request and answer** are shown only by the page that made the run, while it stays
  open.
  <!-- R1-LAST: I-26, AU-6 -->
  Another page says "The request and answer are shown only by the page that made the run, while it
  stays open."
  <!-- /R1-LAST -->
- **From release 2**: the run's request and answer from the trail with Download for the vendor,
  Journeys and Suites (the suite and journey endpoints already answer, see
  [Endpoints](#endpoints)). The trail will hold member data, is never to be committed, and is
  switched off with `trail.enabled: false`.
- **Suites through the API meanwhile**: `samples/suites/sample-suite.csv` has six CRD cases that run
  on mock (four pass, one fails on purpose, one declares no expectation). Columns: `caseId`,
  `protocol` (crd, dtr, pas or chain), `hook`, `operation`, `chain`, `memberId`, `npi`,
  `procedureCodes` (separated by `|`), `dateOfService`, `expected`, `scenarioId`, `testerRef`,
  `note`, `claimId`, `subscriptionId`; a column whose name starts with `#` is never read, any other
  unknown column stops the file. `curl -s -F file=@samples/suites/sample-suite.csv
  localhost:8057/api/suites/preview` says what PAVE makes of it without sending anything.

## Advanced

Configuration > Advanced holds the tools kept off the Workbench, all of them in release 1: **In
force now** (where each
operation goes, whether it can be sent, the store's folder, address warnings), **Send as written**
(a collection request as it is written, or a body of your own), **Try a value**, **Check this
installation** (the code list's self-check, the ledger, the mode), **CDS discovery**, **Compare
with a request that works** (PAVE's CRD request beside one you paste, element by element; nothing
is sent). Its **Trail** page says "This page comes in the next release."

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
- **PAVE is held to version 2.0.1 of CRD and DTR, and to 2.2.1 of PAS** (D-136; Submit since the
  rebuild). Content of a later version is read and named with its version, never called wrong.
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
| Answers come from | scenario files in `src/main/resources/scenarios/` | the services of the active environment |
| Members and providers | synthetic fixtures in `src/main/resources/fixtures/`, and the saved test lists | the configured lookups, the saved test lists, or typed by hand |
| Needs | nothing | an environment, its credentials, network access |
| Ledger records the member ID | only a `P32H-` sentinel or an `S` and nine or ten digits; anything else as on live | no: a salted token and the last four characters, unless configured |
| Mock members and "Mock scenario" | yes | no: a forced outcome against a real service would be a result that did not come from it |

**Mock and live are chosen when PAVE starts**, never on screen: the top bar's `MOCK` or `LIVE`
badge says which runs, and so does `GET /api/mode`. Switching is a restart. The tool refuses to
start when `epa.mode` and the Spring profile disagree, nothing on live can play a scenario, and a
live call that fails is never answered from mock. The environment form shows the profile as a
fact, "Mock, chosen when PAVE starts" or "Live, chosen when PAVE starts".

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
fields", the saved test lists, the request preview and a body of your own.

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
| GET | `/api/mode` | Name, version, port, mode, target, active environment, epoch, write access, the receiver's note, and the build (`build {version, time, changedOnDisk}`) |
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
| GET | `/api/members/{memberId}`, `/api/members?memberId=` | Member lookup (the query form for an ID with a slash) |
| GET | `/api/providers/{npi}`, `/api/providers?npi=` | Provider lookup (the NPI check digit first) |
| GET | `/api/procedure-codes?q=&lob=` | Code type ahead |
| GET | `/api/procedure-codes/self-check` | What the tool made of the code list |
| POST | `/api/crd`, `/api/crd/preview` | One CRD call, or its request without sending it |
| POST | `/api/crd/compare` | Compare a pasted CRD request (`rawBody`) with PAVE's own; sends and records nothing |
| GET | `/api/crd/discover` | CDS Hooks discovery |
| GET | `/api/crd/scenarios`, `/api/dtr/scenarios`, `/api/pas/scenarios?for=` | Mock scenarios with their short words, and the outcome vocabulary |
| POST | `/api/dtr/package`, `/api/dtr/package/preview` | `$questionnaire-package` |
| POST | `/api/dtr/next-question`, `/api/dtr/next-question/preview` | One step of the adaptive loop |
| POST | `/api/dtr/response` | Complete the QuestionnaireResponse (sends nothing) |
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
| `trail.enabled`, `trail.max-entries` | `true`, `500` | The trail of release 2 |
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
| `lookup.member.url`, `lookup.provider.url` | `""` | The lookups on live in release 1, with `{id}` in the path or the query. Blank: no lookup |
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
<!-- R1-LAST: I-11 -->
The release build itself was imported and started in the same IDE before the hand-over.
<!-- /R1-LAST -->
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

**4. The Problems view** read "0 errors, 18 warnings" on the tree of `1a14611`. **Never apply the quick fix of
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

**6. The live profile, or another port.** Run, Run Configurations, Spring Boot App,
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
| The member API's response contract | A tolerant mapping: several spellings per field, any common envelope | `lookup.member.url`; from release 2 the field paths of the lookup form |
| The provider directory's contract | The same; none appears in any earlier session | `lookup.provider.url`; from release 2 the lookup form |
| How the lookup APIs authenticate | Nothing is assumed | `lookup.*.headers` or `lookup.*.use-onyx-auth`; from release 2 the lookup form's sign-in |
| How the vendor's token request authenticates the client | Client authentication by a Basic header, or in the form body, chosen in the environment's Sign-in; never observed against the vendor | The environment's Sign-in. A signed JWT assertion would need `requestToken` in `client/support/OnyxTokenService.java` changed |
| The code list's header row, flag spellings, dates, encoding | The bundled placeholder and candidate headers | `codelist.file`, `codelist.columns.*`; check with the self-check |
| The PAS attachment mechanism in use | `fhir` | `onyx.pas.attachment-mode` |
| Whether the receiver is reachable from Onyx | Assumed not | Not to be changed before PAVE has a sign-in (D-270) |
| Which guide version the sandbox follows | 2.0.1 for CRD and DTR, on the operator's word; PAS 2.2.1 for Submit, at the operator's request | `dtrCoverageExtension`, and `wrapInParameter` on a next-question request |
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
- **Mock and live are chosen at start**; switching them on screen was not built (the operator is to
  be asked after using the new screens).
- **No second sign-in for Submit and Status**, which the vendor's sandbox holds a client for; never
  shown to be needed.
- **Inquiry needs a claim id**: inquiry by member and dates of service is not built.
- **DTR is not a SMART launch**; no CQL is run, and in release 1 every answer is typed (answers
  from the case come in release 2).
- **PAS**: no claim update and no `$submit-attachment`; `x12-275` sends no 275 transaction; a
  resubmission reuses the claim id.
- **The receiver** has never been called by a real payer, does not check its caller and keeps at
  most 200 notifications, in memory.
- **Conformance** is checked against hand-written rules of the guides' versions, not against
  their profiles' slices and invariants.
- **The code list** is a synthetic placeholder until the P32H export is supplied.
- **The ledger** refuses only the member data the same request carries; a one-word surname where a
  code belongs is kept.
- **The guardrail** does not see another DNS name for a production host, or the `onyx.*.url`
  fallbacks as production addresses.
- **Two copies on one store** are not refused in release 1 (from release 2 they are).
- **Some server sentences name the old screens** until release 2 ([This release](#this-release)).

- **Started with `-Dpave.classes.load-at-start=false`** (not the default, which loads every class
  at start), the first internal error within about 10 seconds after the IDE rebuilt the classes
  can ask you to report it instead of saying to restart, and the top bar's environment list can
  fail. Stop PAVE and start it again. Release 2 corrects it.
<!-- R1-LAST: I-25, AU-4 -->
- **A folder chosen in place of a file** is told that the file "can no longer be read: it was
  moved, renamed, deleted or changed after you chose it". Choose the exported file inside the
  folder.
<!-- /R1-LAST -->

## How release 1 was checked

Agents that built none of it checked merged builds before the
candidate: the Workbench on mock and its look and words, twice (`1a14611`, then `9dbf61a` with
steps 2 to 4); the upload hunts and the Windows Java under Wine (`1a14611`); the real STS 5.4.0
under a virtual display on Linux, with a Team > Pull under the running tool (`1a14611` pulled to
`9dbf61a`); Audit, every broken upload, and every feature of release 1 on mock and on live against
a stand-in (`9dbf61a`); the production guardrail, what is sent on live, configuration on live and
secrets (`0b69049`). A second agent confirmed or refuted each finding: 86 were confirmed (31
major, 55 minor), and the integrator decided what the plan had left open (I-1 to I-35, and three more of the
integrator's own).
<!-- R1-LAST: every finding of findings.jsonl, I-1 to I-35, integrator-1, integrator-2, and what INTEGRATOR_R1.md holds at the last pass -->
Every confirmed finding is being corrected for release 1 and checked again before the hand-over by
agents that wrote no correction, except two the integrator moved to release 2 (named in
[Limitations](#limitations)) and five a decision answered with no change a tester meets.
<!-- /R1-LAST -->
What was seen and decided is in `PROGRESS.md` and `DECISIONS.md`.

## What this README was checked against

Brought up to the release candidate on 2026-11-22: commit `7fc32c8` of the integration branch, cut
at 11:48 UTC, its jar built from that commit without running the tests. Its stamp read
`11-22 11:56 UTC`. On that jar, on mock, "Start here" steps 5 and 6, "Your first case on mock" and
the result shapes of "Reading a result" were followed in a browser; on live against the stand-in
on a loopback port, "Setting up a service" from New environment to a coverage check answered after
a setting changed, and Access token. Every example of [Endpoints](#endpoints) was run on it in
order. Every endpoint listed is one the controllers at that head map. Every sentence quoted from a
screen was searched in `src/main/resources/static` at that head, or, where the server writes it, in
the server's code; the sentences inside the marks wait for the last pass: corrections not made at
that head, the IDE rerun and the release's builds. Steps 3 and 4 of "Start here" (the Gradle build and `run.sh`) were not run for this
README; the lines step 4 shows are those `run.sh` prints at that head.
<!-- R1-LAST: SWEEP.md, the whole builds of the release -->
The candidate `7fc32c8` has not been built whole yet (a try at 11:30 UTC was stopped by the
runner's time limit). The last whole build that holds no slice of release 2 is that of `037efb6`,
the commit before the last slice (H2) was merged: 3,519 tests, 0 failures, 0 errors, 2 skipped. The release handed over is
built whole twice after the corrections; `PROGRESS.md` gives its counts.
<!-- /R1-LAST -->
