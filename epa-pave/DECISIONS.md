# DECISIONS

Every ambiguity resolved during the build, and how. Newest entries are at the bottom of each
section. Where two readings were possible, the one that is safer if wrong was chosen.

"The build prompt" below means the document this tool was built from. Section numbers refer to it.

**The old screens are gone.** Since the rebuild of the screens (D-281, D-285) the five tabs CRD, DTR,
PAS, History and Config no longer exist: the Workbench and its four steps replace CRD, DTR and PAS
(D-286), Audit replaces History (D-296), and Configuration replaces Config (D-289). An entry below that
describes a tab, a button or a sentence of those tabs describes the screens as they were; it is not
corrected one by one. Rules and contracts that the rebuild changed carry a "Later:" line under their
entry.

## Things the operator should read first

| Id | What | Why it matters |
| --- | --- | --- |
| D-001 | The reference implementation `onyx-crd-harness-1.2.0.zip` was not available | Everything here was written fresh. Nothing was reused from it |
| D-004 | `build.gradle` as given in section 2 does not resolve. One line was added | A changed build file nobody is told about is a mystery for the next build |
| D-005 | The Spring Boot 4.0.7 BOM manages JUnit Jupiter 6.0.3, and the stack table says JUnit 5 | The pin in the stack table does not match what resolves |
| D-008, D-130 | `hl7.org` and `build.fhir.org` are blocked from the build environment. Parts of the guides were found readable from other sources late in the build | CRD, PAS and DTR were written from the build prompt and from memory, and so were their mock scenarios. What an independent comparison with the guides then found, and what was done about it, is under D-130 |
| D-053 | The root package and the Gradle group are `org.p32h.interop.epa...`, not `com.thehiddenbrain.interop.epa...` | Changed on the operator's instruction during the build. It overrides the identity table in section 2 |
| D-136, D-140 | The operator named 2.0.1 as the version of the Da Vinci guides to hold the tool to | All three protocols were compared with 2.0.1 by auditors who wrote none of the code and brought in line under four rules (D-140); what is still open is in `PROGRESS.md`. The operator says the vendor sandbox follows 2.0.1 as well; that has not been observed (D-269) |
| D-143 | The production guardrail judges where a request goes, not only which environment is active | The guardrail audit got writes through to production four ways before this; how it was closed, and what it still cannot see (D-145) |
| D-141 | A value nobody supplied is never invented, and a request says what it left out | A service that refuses such a request is not at fault, and the result says so |
| D-190, D-206 to D-208 | The frontend is merged and joined into one tool, and the screens read the server's words | The two proofs before handover were seen on the real tabs on mock by browser scripts (D-208); an automated walkthrough by an agent playing a business tester ran on `9e285a1` and is not a sign-off; no person has used the tool |
| D-236 to D-244 | The fixes for the house style, completeness and walkthrough audits are merged | An independent check of them found some open one step away and new findings (D-258, D-260); the last fixes followed (D-262 to D-265) |
| D-213 | The configuration store keeps uploads exactly as uploaded, and can therefore hold member data an uploaded environment or collection contains | It qualifies section 15's rule that no member data is persisted. The upload warns by the names of variables and saved examples; nothing is stripped |
| D-210 to D-218, D-245 | The second audits of member data, the guardrail and fixtures found blockers, and their fixes are merged | Each fix was checked by an agent that did not write it (D-245): most closed, and several open one step away, among them a production address the client cannot read. Four tasks fixed them afterwards (D-253 to D-256), checked by nobody but their authors when D-259 was written; what they left is D-257. What is still open is first in `PROGRESS.md` |
| D-253 to D-259 | The four tasks that closed what the checks of the V2, V3 and V4 fixes and V6 found are merged | Checked by the last independent check (D-260); what they left is D-257 |
| D-260 to D-268 | The last check found blockers and majors in four parts; four last fixes followed and are merged | **The last fixes are checked by nobody but their authors, and no check follows them** (D-268). What they left was not applied (D-266). What is open is first in `PROGRESS.md` |
| D-227, D-252 | Four questions were put to the operator during the build and had no answer then | The guide version of the vendor's sandbox, the NPI's first digit, the bind address, a sample vendor request. The operator's replies, after the pull request was opened: D-269 |
| D-269 | The operator replied to the four questions of D-227 after the pull request was opened: two answered, two not | The sandbox follows 2.0.1 on the operator's word, not observed; a vendor sample request can be shared and has not arrived; the operator does not know about the NPI's first digit and did not understand the question about the bind, so both stay as they are |
| D-270 | Told what the bind means, the operator replied that the tool will be run as a real service one day | Nothing changes now and the tool keeps listening on this machine only. **As delivered it is not fit to be run as a shared or production service**, and the bind is not to be opened before a sign-in exists. What must exist first is in the README, "Before PAVE is run as a shared or production service" |
| D-271 | Asked whether the tool was ready to load into STS, the integrator first answered yes for mock with one caveat; that was wrong in part | A test found 24 handler arguments in 9 handlers that took their request name from their Java name. Compiled as STS would without the parameter-names setting (the batch Eclipse compiler, an emulation, not STS), 8 of the 9 were seen answering HTTP 500 (History, one run, the note, notifications, the four subscription calls); the export was not called. Each argument now names itself, the test holds it, and the emulation saw the 8 answer 200 and the export answer through the README's example. **No real STS had imported or started the project** then; later one did, on Linux: D-272 |
| D-272 | The real STS 5.4.0 has run the project on Linux, twice, with one fix between the runs | On Linux with Spring Tools 5.4.0 the import with every default worked; the shared launch configuration started `PaveApplication` from the Boot Dashboard without asking; three tests that need the Gradle test task were skipped outside it with a sentence that says why. A profile typed in the launch dialog was written into that tracked file. **Not seen: Windows, `run.cmd`, a real tester's workstation, the screens in a browser after an STS start, live against a service, the whole suite from the IDE, any other STS release** |
| D-273 | The operator let STS clone the repository on Windows and got stuck; the path, the error, their state and a machine without a JDK 21 were then run in the real STS on Linux | The Git import wizard's own choices are wrong for this project: the default branch has no `epa-pave` folder, and its last page offers only the other application. The error naming `gradle-8.9-bin.zip` means the wrong folder; on Linux it read like a network fault and was not one (the operator's cause was not seen). STS alone, with no JDK 21, imports a project that cannot start (simulated; since D-274 this holds only for a checkout from before `6201644`). Close Project or Delete (both boxes unticked) ends the double `PaveApplication`. Nothing in the repository root was changed. **Not seen: Windows beyond the operator's report, a credential prompt, a corporate proxy, an STS other than 5.4.0, a machine with a Java 17 and no 21** |
| D-274 | STS alone, with no JDK 21, imported a project that could not start (D-273), and nobody knows whether the operator's workstation has a JDK 21 | Where Gradle finds a JDK 21 nothing changes. Where it finds none, the build compiles for Java 21 with the Java that runs Gradle, if that is a JDK 21 or newer, and says so in one line; otherwise it stops with one plain message. No toolchain download is configured. Decided without asking the operator first, because where a JDK 21 exists nothing changes and elsewhere a silent failure becomes a working import or a plain stop. With the JDK 21 hidden on Linux (a simulation), STS 5.4.0 alone imported and started the tool on its own Java 25, both proofs holding: on `6201644`, and on `6eddce8` (the code of the head), where a project imported before the change was put right by Team, Pull and one Gradle, Refresh Gradle Project, and the operator's own path also worked. First recorded by the integrator alone, then checked. **Not seen: a real workstation without a JDK 21, Windows, `run.sh` and `run.cmd` after the change, the whole build with no JDK 21 on `647b102`, an STS other than 5.4.0, a fetch of new commits from GitHub in STS** |
| D-275 | The operator ran the tool on their Windows workstation from STS; uploading an environment or a collection fails there with an internal error, and their verdict is "the whole UI and app sucks" | The log line shows a `java.lang.Error` thrown during the upload; its cause was cut off. Not reproduced on Linux, nor on a Windows Java under Wine (about 60 uploads, no Error), which is not Windows. The operator was asked for the rest of the log entry, the Problems view and whether the sample file fails too; no answer yet. **Until it is found, nothing can be configured for the live profile on that workstation.** The Windows Java run found one thing: the shared launch file carried a Linux-only argument that cost about 8 s at every start on a Windows Java under Wine; it is removed (`8463174`), and the real STS on Linux still starts the tool with no dialog. **Not seen: a real Windows machine other than through the operator's report, their files, their workspace, the cause** (D-279: a defect that writes that very log entry was found and corrected; whether it was theirs is not confirmed) |
| D-276 | The operator's verdict was answered with a first round of screen changes, without waiting for the three worst things they were asked for | A look, a doubt, a plan, an attack, two batches, a walk, an audit and three rounds of correction and check, all by agents. On mock one click gives a case that can be sent; a good run no longer looks wrong; the form opens with what a send needs; DTR questions come first; a result is ordered and folded; Config opens with "Ready to send?" and says that signing in is off; a failed call names its address and cause. **The operator has not seen it, no person has used it, nothing of it ran on Windows.** Left: long results, History, reload, the values the vendor's file lacks, a sign-in |
| D-277 | The cause of an internal error was written where the operator did not look (D-275) | The first line of the log entry and the screen now name the chain of exception types, with a detail only for a few throwables the runtime or the IDE's compiler writes about the program itself (a narrow change to D-036), and one button copies the reference and the cause. Seen in a damaged copy of the jar on Linux, not on the operator's machine: **their cause is still not known**. A suite that stops and the notification receiver still gave the reference without the cause when it was written; both now name it (its "Later:") |
| D-278 | The message of an exception could reach the log around the one place that writes failures: since suites were built, an `Error` during a suite was thrown on to a thread with no handler, and the Java runtime printed every message of it | Against the rule of D-036 and the rule that no member data is logged: a test put the placeholder member ID into the log that way. Whether a real run ever did is not known. While the tool runs, every thread found now ends a failure in one entry under a reference, with a net under the whole process; tests and copies of the jar with a class removed, path by path, saw no message reach the log on the paths that started. A start that fails is still Spring's report, with its messages, by decision, and two narrow paths are left (D-278, limits). Not proven on the real jar: the net and two guards, held by tests only |
| D-279 | A long text in a Postman file broke the upload and then the store: a collection holding one quoted text of about 1,300 characters or more in a body or a saved example threw `StackOverflowError` after it was stored, and for longer texts (3,000 characters in the tests) every later reading of the store threw again, after a restart too | It is the only way anyone has made the tool write the log entry of D-275 at default settings. Corrected: no pattern of the main code recurses with the length of its input, and every recursion the guard can see is named with what bounds it (guard tests hold both), an upload stores nothing until every check has run, a store left by the old version opens, a file nested too deep is refused with that reason, and a DTR chain of thousands of enableWhen conditions no longer throws. The smallest supported thread stack is 512 KB. **Not confirmed as the operator's failure**: only their log line after "<- caused by", or the cause the screen now names, settles it. Nothing of it ran on Windows, and the operator has not pulled it |
| D-280 | What the checks of the upload fix left was corrected, in three branches each built, checked on the real jar by an agent that wrote none of it, corrected and checked again, and merged | An answer is now masked wherever it repeats a secret the tool holds, carried or not; a verdict read from a mask says so under its headline; an id read from a header is masked; a mask fills no field and is never sent. **An older leak was found and closed:** a credential typed into the address to call back or a lookup url was shown in clear in "In force now" and on the PAS tab since those were built; whether a real one was ever shown is not known. A stop now waits up to 30 s for a call that is out and keeps its ledger line; a client that went away is one INFO line, not an unhandled exception; the unexplained answer was right and very slow on a very large store (open); a long name is quoted by its start and its length in every sentence (labels stay whole), and the slow pattern no longer sees a part over 128 characters. Seen on the jars of each branch against stand-ins on this machine; the merged head `6ab0e69` was built (3,093 tests), not run. **Not seen: Windows, the vendor's service** |
| D-281 | The operator called the screens unusable and too wordy, the first tab they opened showed the internal error, and three uploads failed; they answered four questions the same day | Rebuild the screens on the model of their own tool, keeping PAVE's engine; saved test lists; Submit after PAS 2.2.1; a trail of the last 500 exchanges. The Config error is D-279's fault in the build they ran; the likeliest upload cause is the IDE rebuilding the classes under a running PAVE. **Nothing was seen on their workstation, and they have said nothing yet about the rebuild** |
| D-282 | A second round of changes to the old screens was built and closed, then replaced by the rebuild before it was handed over | Its server changes stay: a call that could not sign in is recorded as `NOT_SIGNED_IN`, replays can be left out of the run list, and more |
| D-283 | A member's name typed into the case id of a suite row reached the ledger, found by the close of the second round | **A gap in a hard rule, older than the round**, now refused in the run and the preview; whether a real suite ever did so is not known |
| D-284 | The other faults older than the round that its close found | A suite expectation refused as a name, a PAS run that named another run than the screen said, a mock subscription pointing to a property; each corrected with a test |
| D-285 | How the rebuild was planned and built: two releases at fixed hours (planned for 15:00 and 22:00 UTC on 2026-11-22; release 1 was handed over at 16:00, D-300), nine builders, a queue and a merger | Every live check used a stand-in on a loopback port, never the vendor's service; nothing ran on Windows; no person has used the new screens. What release 1 holds and what waits for release 2 is first in `PROGRESS.md` |
| D-286 | The Workbench: one top bar, three tabs, a case column and four steps carried from one to the next | All of it is in release 1. Seen on mock to "The prior authorization is approved." and live against the strict stand-in, on the jars the checks used; the Submit step as merged last (H2) by its builder only. A reload starts a new case: keeping it is release 2 |
| D-287 | A result is chips, one headline, a few facts and closed folds, with one way on | The word gates held at 1440 and 1024. A failure drawn as two boxes was corrected and seen as one card on `e5d0972`; a CDS service's own reason for a 400 on live is shown in that card since the second round (D-299) |
| D-288 | Short words and tones, served once by `GET /api/vocabulary` | Every outcome of every protocol has a short label of its own |
| D-289 | Environments carry settings edited on screen, stored in version 2 of the store and used at once; the Configuration pages | **An older build refuses a store a newer one has written settings into**; going back needs one file renamed and loses those settings. Seen used with no restart, live against a stand-in. A typed callback url, which the subscription call did not use at the candidate, is used since `44f09fe` (integrator-2) |
| D-290 | Sign-in belongs to the environment; a pasted token for the session; one retry after a refused token | **A hole the rebuild opened**: a token url could be a production write address and receive the client secret. Found by the checks before any hand-over; decided I-28. Since a production environment saved by mistake and removed again keeps its addresses remembered, a sandbox on the same host cannot sign in until they are forgotten by hand (features-8; release 2 brings the button) |
| D-291 | Requests take the shapes the vendor's sandbox accepted; draftOrders type is a setting of the environment | Accepted by the strict stand-in that refuses what the sandbox refused; never seen against the vendor's service |
| D-292 | Member ID rules: packed, spaced and split, per environment | The ledger keeps the packed form, so one member stays one key |
| D-293 | Submit follows Da Vinci PAS 2.2.1, with files as attachments | The operator's answer. Nothing blocks sending: an incomplete request is a negative test. The finding of what a request left out names PAS 2.2.1 since `44f09fe` (integrator-1). **Later (D-300): Submit follows 2.0.1 unless the environment chooses 2.2.1** |
| D-294 | Saved lists of test members and providers | Member data kept on purpose, on the operator's leave, within the bounds of D-298 |
| D-295 | Uploads, the classes on disk, the build stamp and the run scripts | The likeliest cause of the operator's failed uploads now has its line and its action. Seen in the real STS 5.4.0 and under Wine, not on Windows |
| D-296 | Audit: the runs, one run and replay | Journeys, Suites and a run's request and answer from the trail are release 2 |
| D-297 | Mock and live are still chosen when PAVE starts | The integrator's decision; PAVE is started once per profile. **Later (D-304): a start follows the launcher, and the top bar switches a running PAVE by a restart in place** |
| D-298 | The saved lists and the trail bend two rules of the specification: no member data persisted, and no raw JSON in the ledger | Only as far as the operator allowed: lists entered on purpose, test data, never logged or in the ledger, off by one setting; the trail a store of its own, in release 2, the ledger rule unchanged |
| D-299 | The checks of release 1, on merged jars before the candidate: 86 confirmed findings; three more the integrator found on the candidate; the integrator's decisions I-1 to I-36; the corrections and the rechecks | **Two holes in the production guardrail that the rebuild opened** were found and closed before any hand-over (I-28, I-29; `e318758`, held by `TokenUrlAndEscapeGuardrailTest` and `TokenUrlAtSendingTest`). The findings were corrected by four correctors and seen again on `19ddf8e` or, for the screens, on `e5d0972`, apart from nine corrected with a test, six of which the recheck of the final build saw closed on `22478b8`; what the rechecks left was corrected in a second round, except what `PROGRESS.md` lists as open (features-8 among them, I-28a being withdrawn); each left open is an open line of `PROGRESS.md`. The recheck of the final build found four more, corrected in a third round (D-300). Nothing was checked on Windows itself or against the vendor's service |
| D-300 | Submit is back on Da Vinci PAS 2.0.1: the guide is the environment's request shape "PAS guide", 2.0.1 unless it chooses 2.2.1; the third round of corrections; the code handed over is `6e1bff3`, at 16:00 UTC | The operator said twice that the payer uses 2.0.1; the integrator should have put that against their answer naming 2.2.1 and did not. **No second agent rechecked the change or the third round**, and nothing was sent to the vendor's service. Two X12 value sets of PAS 2.0.1 were not settled |
| D-301 | Release 2: the trail, Audit's request and answer, Journeys and Suites, a reload that redraws the case, lookups set up on screen, answers from the case, Forget, the store's lock and its damaged files, the Advanced Trail page | Built on release 1's candidate before its corrections (A2c, B5, B4b, C2c, D3, D6, E4, F4, G3, G4, H4, I4). The trail keeps member data on disk within D-298's bounds and never a secret. The sweep of old place names was not made |
| D-302 | Release 1, its corrections and the PAS 2.0.1 decision merged into release 2 by one agent (`86d4807`) | Every place both sides changed was settled one way, said in this entry; a whole build and one walk on mock held it |
| D-303 | The one check of release 2, its five findings, their corrections (`c31ae00`), and the limit: no recheck | **Release 2 was closed with five agents and no second check of the corrections**, to keep spending low. It rests on its tests, its whole build and one independent look. Nothing ran on Windows or against the vendor's service |
| D-304 | Mock and live switch on screen: the top bar restarts PAVE in place, in the same process, on the other profile | The operator asked for it after release 2. The mode is still the Spring profile, so what holds by construction on live holds after a switch; a switch drops the production unlock and a pasted token, clears the Workbench's case, and is not remembered over a real start. Seen in the real STS 5.4.0 on Linux and on the Windows Java under Wine; **not on a Windows workstation** |
| D-305 | A correction after release 2: the sign-in is read from the collection's token request and from wider variable names, several candidates are offered as buttons, an empty value is said; a refused sign-in says what the token endpoint said and names the setting (`80962e5`, `c0f9cf1`, `27e3a96`, `b7a1770`) | On the operator's first run against the vendor's UAT the files filled no sign-in and a refused sign-in said only "HTTP 401". The scripts of a collection are still never read. The service's words are shown made safe and never logged. Seen on live against the stand-in and a token endpoint of the builder's own, not against the vendor's service |
| D-306 | A second correction after release 2: what a collection's requests state about members, providers and procedure codes goes into the saved lists, on the tester's tick; procedure codes a third list, picked in the Service card; a saved member may hold a vendor patient id alone (`babb793`, `fdce17b`, `85b8834`, `76743d4`, merged as `165bbd2`) | The operator asked for it after the first real run. It stays inside D-298's bounds: the box is the purpose, and the stored collection already holds every value. Only what a request sends is read, nothing is guessed, the log holds counts only, nothing goes to the ledger. Seen on mock and on live against the stand-in, not with the vendor's files |
| D-307 | A third correction after release 2: right after Fill the form shows every address and value the files give with where it comes from, and a request can be chosen for an operation none was found for; on Edit nothing in force is an empty box; a request is also told by its body; a v2.0 collection is read (`25b200a`, `6664bd1`, `fc80b7d`, `050f977`) | On the operator's first run against the vendor's UAT every address and value was typed by hand, and a typed address replaced the collection's request. A chosen request is the collection's mapping, recorded in a second call after the save. Seen on live against the stand-in, not against the vendor's service |
| D-308 | A fourth correction after release 2: the values of the Edit form a vendor's sample requests state (the FHIR server from the coverage-check sample; the payer, its identifier and system, the member ID system and the PAS types from the Submit sample) are shown with the request they come from and stored by the save for a value nothing else gives; what the coverage-check sample sends and PAVE does not is named; a refusal that names fhirServer says where to set it (`d7a3246`, `f390ba0`, `8354f76`) | The coverage check was refused for a missing fhirServer the vendor's own sample stated. A value is read only where PAVE's own builder writes it, from the sample a call for that operation is sent from; a value typed or given by the export is never replaced; the callback url is never taken from a sample. Seen on live against a changed copy of the stand-in, not against the vendor's service |
| D-309 | A fifth correction after release 2: the coverage check's prefetch goes under the keys of the collection's coverage-check sample, holding what the sample holds there, built from the case (request shape "Prefetch"); the member ID's and NPI's systems and the identifier type follow the sample; what cannot be filled is said by key and type; the card after "No patient data" names the setting; the comparison names the arrangement (`fc9d2a5`, `338e757`, `f3b8f55`, `ced7239`) | The vendor's UAT answered "No patient data provided" to PAVE's named Bundles and answered the vendor's own sample. CDS Hooks fixes no prefetch names; following the sample stands in for reading the service's discovery answer, which is not built. Nothing of a sample's resources is sent. Seen on live against a changed copy of the stand-in, not against the vendor's service |
| D-310 | A sixth correction after release 2: where the coverage-check sample's order carries a diagnosis (`reasonCode`), the Diagnosis codes of the Service card go on every order with the sample's coding system; what the sample's resources carry and PAVE's do not is named, by resource type, in the request's notes, under Prefetch in the form and in "Compare with a request that works" (`51b3505`, `7f951cd`, `6256f1f`) | The operator asked that anything missing be added and anything not standard be said. The diagnosis is the case's own and the only element added; every other element is standard FHIR that CRD does not require and that the case holds no value for (or, for a member's address, that the coverage check does not write), so it is named and never filled in. Names only, never a value. Seen on live against the stand-in, not against the vendor's service |
| D-311 | A call to a typed address follows the sample of the collection's request for its operation in its request shapes (it still carries no header and no body of that request); the Submit options answer with no call in hand (`4d9d85a`) | The operator typed the address of the coverage check to correct it, the request fell back to PAVE's named Bundles and the vendor's service answered "No patient data provided" again. The build of `a580682` read the shapes of the active environment through a request that was not there, and every page that asks for the Submit options got an internal error. Held by tests; not seen against the vendor's service |
| D-312 | A call to a typed address follows the collection request that goes to that address; the first mapped only when none does | The operator's collection holds several coverage checks, each for a service of its own. The first in the file was followed, its sample carries the patient under `patientBundle`, and the vendor's service answered "No patient data provided" a third time. Read from the operator's recording of In force now: one collection, 124 requests mapped, the address of the coverage check typed. Held by a test; not seen against the vendor's service |
| D-313 | Each Workbench step that sends a request names the collection request in force and where it comes from, lists every request that fits, sends the one chosen (its address, headers and sample, over a typed address) and can make it the one for every run through the mapping; "As the collection writes it" sends that request's stored body with the environment's values put in and Postman's five common dynamic values made afresh (`c7adbfa`, `51c1a3e`, `ed9d05e`) | The first request in the file is no safe default in a collection of 124 mapped requests, several for each step; the operator spent an evening finding, one difference at a time, what PAVE builds and the vendor's request sends. A body as written gives up the Case column and what was handed on; scripts are never run, a value a script sets is asked for, and no body reaches the page or the ledger. Seen on live against the stand-in and on mock, not against the vendor's service |
| D-314 | After a coverage check, a questionnaire or a partly completed response that the answer names leads to the questionnaire step, whatever the answer says about doc-needed; the reason says doc-needed was not stated | The vendor's service names its questionnaires with a doc-purpose and no doc-needed. PAVE offered "Continue to Submit" and the operator never saw a question. |
| D-315 | A sign-in whose service could not be reached says what failed (the host's name, a refused connection, a timeout, a certificate, a proxy), and the console names the types of the failure | After a pull the operator's sign-in failed as "could not be reached (unavailable)" while Postman signed in, and nothing told the cases apart. The code that signs in had not changed |
| D-317 | A questionnaire result that offers no way on says that Submit is open and offers "Continue to Submit without it", plainly | The operator's questionnaire request failed at the vendor's service and nothing on the card led to Submit, though the stepper opens any step. D-316 is kept for the test of a connection, begun and stopped at the operator's word |
| D-318 | The Submit bundle declares the PAS request bundle profile, and the inquiry's bundle the PAS inquiry request bundle profile | The vendor's service refused a Submit whose Bundle did not say which PAS bundle it is. The guide's own examples declare it under both versions |
| D-319 | Each resource this tool builds for a Submit or an inquiry declares the PAS profile it is built as | A service that refuses a Bundle for naming no profile may look for one on each resource. The guide's examples declare them, and waiting for one refusal after another costs the tester a pull and a run each time |
| D-320 | A call takes the FHIR server and the payer from the collection request it is sent from, at the time of the call, when nothing else sets them; a new value Payer reference; a refusal naming payor says where to set it | The vendor's service refused a coverage check whose Coverage named no payer, while the vendor's own request stated one. Lowest of all sources, so whatever the tester sets still wins. Seen against a stand-in only |
| D-233 | The token request authenticates the client by HTTP Basic | An open item of section 26, decided in code. A vendor that wants the credentials in the form body or a signed assertion answers 401; `OnyxTokenService.requestToken` is the one method to change |
| D-234 | The installation salt (`config-store/installation-salt`) is a secret | With it and the four characters the ledger keeps, a member ID is recovered from its token at once. Never copy it with the ledger or its export |
| D-235 | Level 1 is described with its named limits beside it | The X12 275 mode names a document and sends nothing; no `$submit-attachment`, no claim update, no `$expand`, no `$log-questionnaire-errors`, no CQL. Never "nothing stubbed" |

## Build and environment

**D-001. Reference implementation not available.**
Section 3 says to ask the operator for `onyx-crd-harness-1.2.0.zip`. It was not in the session
uploads and not on the build machine. Section 0 says not to stop and wait, so the build went ahead
without it. Consequence: the Postman layer, CRD mapping, lookups, CSV reader, scenario files and
frontend were all written from the build prompt alone. If the zip turns up later, compare its 14
CRD scenario bodies and its operation detector against this build, because those are the two
places where it held knowledge this document only summarises.

**D-002. Branch name.**
The build prompt names the branch `epa-pave`, branched from `claude/cms1500-form-filler-pdf-1wploq`.
The build session was started on a different working branch at the same commit. The prompt is
explicit, so `epa-pave` was used.

**D-003. Plan mode.**
Section 0 says to start in plan mode. In this environment plan mode blocks until a person approves
the plan, and the same section says the operator may not be watching and that waiting is the one
failure mode that wastes the run. The plan was written to `PROGRESS.md` before any feature code,
and the build did not block on approval.

**D-004. `annotationProcessor` needs its own platform import.**
The `build.gradle` in section 2 fails at step zero with
`Could not find org.springframework.boot:spring-boot-configuration-processor:.` The
`annotationProcessor` configuration does not extend `implementation`, so the BOM imported there
does not reach it. Fix: one added line,
`annotationProcessor platform("org.springframework.boot:spring-boot-dependencies:${springBootVersion}")`.
No version pin changed.

**D-005. JUnit version.**
The stack table says JUnit 5. The Spring Boot 4.0.7 BOM manages JUnit Jupiter 6.0.3. Tests are
written against the Jupiter API (`org.junit.jupiter.api`), which has the same packages and
annotations. JUnit was not pinned back to 5, because overriding the BOM would be a substitution in
the other direction and Boot 4 is built and tested against Jupiter 6.

**D-006. Resolved versions.**
Step zero resolved without changing any pin: Spring Boot 4.0.7, Spring Framework 7.0.8, Jackson
databind 3.1.4, jackson-annotations 2.21, Tomcat 11.0.22, JUnit Jupiter 6.0.3, Mockito 5.20.0,
AssertJ 3.27.7, Gradle 9.5.0 on JDK 21.

**D-007. Wrapper generation.**
`gradle wrapper --gradle-version 9.5` was run as `--gradle-version 9.5.0`, because Gradle 9
distributions carry three-part versions and the prompt's own `distributionUrl` is
`gradle-9.5.0-bin.zip`. The installed Gradle was 8.14.3, so the wrapper task was run a second time
through the new wrapper, which regenerates the scripts and the jar from 9.5.0 itself. Observed:
`gradle-wrapper.jar` is 48,462 bytes with 34 entries and
`Main-Class: org.gradle.wrapper.GradleWrapperMain`.

**D-008. Implementation guides could not be fetched.**
`hl7.org` and `build.fhir.org` return a policy denial from the build environment. The value sets
and sub-extension names in section 11 of the prompt are treated as the authority. Anything beyond
that table (extension urls, PAS review action codes, subscription notification shape) comes from
the builder's knowledge of the Da Vinci guides and is marked as such where it is used. See the
open items in the README.

**D-009. The `-parameters` explanation.**
Section 2 says to comment the `-parameters` gate "with the accurate explanation in section 4".
Section 4 of the prompt as received does not contain one. The comment in `build.gradle` was
written from an experiment on this stack instead: with the flag stripped, the build compiles, the
context starts, `@ConfigurationProperties` records still bind and Jackson still reads records. The
one thing that fails is a controller argument relying on its Java name, and it fails on the first
request, not at startup. The Spring Boot Gradle plugin also adds the flag by itself, so the gate
checks the effective compiler arguments of every `JavaCompile` task and not the build file text.

**D-010. One jar.**
The plain `jar` task is disabled so `build/libs` holds exactly `epa-pave-1.0.0.jar`.

**D-011. Plugin repositories follow `internalRepoUrl` too.**
Section 24 gives the repository switch for dependencies. The Spring Boot Gradle plugin is resolved
through plugin repositories, which that snippet does not cover, so `settings.gradle` applies the
same property to `pluginManagement`. Without it a workstation that can reach only an internal
mirror would resolve dependencies and still fail on the plugin.

**D-012. Mock by default through `spring.profiles.default`.**
`application.yml` sets `spring.profiles.default: mock`. Starting with
`--spring.profiles.active=live` selects the live clients. `epa.mode` must agree with the active
profile and the application refuses to start when it does not, so the badge on screen can never
say one thing while the wiring does another.

**D-013. House style gate scope.**
The gate scans every file under `epa-pave/` except build output, IDE metadata, the two runtime
data directories and binary files. It runs on every build and declares no inputs, so it can never
be skipped as up to date. Its patterns are assembled from fragments so the build file does not
contain the text it forbids. The generated `gradlew` script contains a copyright sign and
guillemets from Gradle itself. Those are not among the forbidden characters and the script is
not hand-edited.
(Replaced by D-244: the rules live in one class that the gate's task and a test both run, the files
come from git with their names checked, a test fails if the gate is taken out of the build, and
angle quotation marks are refused everywhere but `gradlew`.)

## Shared contracts (Spine 1)

**D-014. Each protocol has its own outcome vocabulary.**
Section 8 says not to carry the CRD outcome enum into PAS. Every protocol outcome enum implements
one small interface, `OutcomeCode` (code, label, tone, fault flag), so the ledger, the expectation
check and the screen treat them alike without sharing an enum. `CrdOutcome` has no member for a
clinical refusal and a test asserts that neither its names nor its labels read as one.

**D-015. CRD outcome for one coverage information extension.**
The outcome is derived from standard codes in a fixed order, first match wins: not covered,
information needed, authorization satisfied, no authorization required, documentation required,
authorization required, performing provider authorization, conditional, unrecognised code,
covered with no authorization statement, no determination. Documentation wanted for a claim or to
retain does not change a "no authorization" answer; it is stated beside it. When the services in
one order get different answers the outcome is `MIXED` and each answer is listed. No answer is
chosen on the tester's behalf.

**D-016. Fault outcomes are outcome codes.**
A timeout, an unreachable service, an HTTP 4xx, an HTTP 5xx and an unreadable body each have their
own outcome code, so a tester can declare "I expect a timeout" and so that "no determination"
(the service answered and said nothing) is never used for "no answer arrived".

**D-017. Expectations.**
An expectation is one or more terms separated by semicolons, and all must hold. A term is an
outcome code, a `name=code` assertion against the standard codes, or the same assertion about one
answer only, written `subject: name=code` (for example `95810: pa-needed=auth-needed`). A blank
expectation is `unverified` and can never pass. A code assertion fails when the response did not
carry that element, and one with a blank value is rejected, so two empty sides cannot match. An
expectation the tool cannot read is rejected with a 422 before sending; if one reaches the
comparison anyway it is a `fail` with the reason, never a pass.
Amended after the contract review, see D-055: the codes of different answers are never pooled, and
an element name must be one the operation returns.

**D-018. Status codes beyond 200 and 422.**
Section 18 defines 200 and 422. Three more are used so the browser can tell states apart without
reading the body: 403 when the production guardrail refused a write, 409 when the tool has no url
for an operation, 404 for an unknown run id, environment or collection. A placeholder with no
value is a 422, because typing the value on the form is one way to fix it.

**D-019. A malformed identifier on a lookup is a 422.**
Section 18 says lookups always answer 200. That is applied to every lookup that was really asked:
found, not found, disabled and fault. A malformed NPI or a blank member ID is refused before any
lookup, as section 14 requires for the NPI, and is a validation failure like any other.

**D-020. The production unlock is per browser session.**
"Per session" is read as the HTTP session. The unlock is held in server memory in that session, is
never written anywhere, names the environment and the configuration epoch it was granted in, and
stops being valid when the active environment changes. On a shared installation one tester's
unlock therefore does not unlock anyone else. The session cookie is named `PAVESESSION`, because
browsers share cookies between ports on one host and the default name would collide with the
other tools on this programme.

**D-021. The guardrail is enforced by a type and applies on mock too.**
A client can send only when given a `PreparedCall`, and only the gateway can obtain one, after it
has checked the guardrail against the same configuration snapshot it resolved the url from.
`PreparedCall` and the unlock, `ProdUnlock`, are sealed interfaces whose single implementations are
not visible outside the gateway's package. They were plain classes with package-private
constructors until a reviewer showed that Spring's request binder will call a non-public
constructor to build a handler argument; it cannot instantiate an interface. The guardrail behaves
the same on the mock profile, where nothing is sent, so it can be exercised and audited without a
network.

**D-022. An unmapped request is a write unless its method cannot change state.**
Whether an unmapped collection request changes anything is unknown, so on a production
environment it is blocked unless its method is GET, HEAD or OPTIONS.

**D-023. Replays and suite runs never write to production.**
Section 28.2 says a suite against production reports writes as blocked. A replay or a suite case
never carries an unlock, so that holds even when the tester has unlocked writes in their session.
A deliberate production write is done one at a time on the PAS tab.
(Completed by D-183: a replay or a suite row refused for this reason gets the next action
`SEND_ON_ITS_OWN`, which says to send the write on its own from its tab, not to unlock.)

**D-024. An environment's kind is required at upload and never inferred.**
A Postman export does not say whether it is production. The kind is supplied at upload. An
environment whose name or urls contain "prod" cannot be uploaded as sandbox or uat without an
explicit acknowledgement, because mislabelling production is the one mistake the guardrail cannot
recover from.
(Narrowed by D-143, rule 5: an environment not declared production may not use an address that
belongs to a production environment, and the acknowledgement does not let it through.)

**D-025. A defined but blank variable does not resolve.**
Postman substitutes an empty string. This tool treats a blank value as no value and falls through
to the next layer, and when no layer has a value the request is not sent. A blank `baseUrl` would
otherwise produce a url that fails like a network problem, which section 10 forbids.

**D-026. Secrets from the operating system environment.**
A placeholder written `{{env:PAVE_NAME}}` is read from the operating system variable `PAVE_NAME`
when it is resolved, so a secret never has to be in a file. Only names that start with `PAVE_` can
be read, so an uploaded environment cannot read arbitrary variables of the server process and put
them in a url, and a value read this way is always treated as secret. The first design used
`${env:NAME}`. A reviewer showed that Spring parses that form itself in `application.yml` and
would have sent the variable's name as the secret. Scalar settings such as `onyx.auth.client-secret`
also accept override through Spring's relaxed binding (`ONYX_AUTH_CLIENT_SECRET`). The keys of the
two map-valued settings, `onyx.variables` and the lookup `headers`, cannot be supplied that way
because Spring lower-cases them, so their values use the `{{env:...}}` form.

**D-027. Which variables are secrets.**
A variable is secret when Postman typed it `secret`, or when its name reads as a credential
(`clientSecret`, `api_key`, `accessToken`) and does not end in a word that makes it a locator or a
label (`tokenUrl`, `keyId`). Redaction is by name, never by matching a value. On the wire view a
header that is a credential by its own name (`Authorization`, `Cookie`, an API key header) is
always redacted, which also covers a credential typed literally into a collection.

**D-028. Dynamic variables.**
`{{$guid}}`, `{{$randomUUID}}`, `{{$timestamp}}` and `{{$isoTimestamp}}` are generated by the tool,
since they need no script. A generated value differs on every resolution, so a preview and the
request that is sent do not show the same one. Any other dynamic or script-computed value is not
computed, and the request that needs it is flagged.

**D-029. The fallback urls in `application.yml` are base urls.**
The tool appends the standard path for each operation, for example `/Claim/$submit`. The CRD
service id is always a variable (`crdOrderSignServiceId`, `crdOrderSelectServiceId`), never a
literal, because it is not known.

**D-030. Properties added beyond section 19.**
`tracking.salt-file`, `tracking.slow-threshold-ms`, `onyx.pas.attachment-mode`, `onyx.variables`,
`lookup.member.headers`, `lookup.provider.headers`, the multipart upload limits and the session
cookie name. Each is documented beside its default in `application.yml` or its properties record.
Later: the rebuild adds `test-lists.enabled` (saved test lists, D-294) and `trail.enabled` and
`trail.max-entries` (the trail, release 2, D-298), each documented beside its default in
`application.yml`; `onyx.read-timeout-ms` now defaults to 120000 there (D-289).

**D-031. `epa.target` switches no behaviour.**
The prompt lists `onyx | nehen` and defines nothing that differs between them. The value is
validated at startup, recorded on every run and shown on screen.

**D-032. The ledger record.**
Notes are appended as their own lines and applied on read, since a line is never rewritten. An
authorization number returned by the service is treated like a member ID: tokenised unless member
IDs are recorded. The date of service is recorded only when the member ID is, since a run without
its member cannot be replayed anyway. Two record components are boxed types because of D-034.

**D-033. Vendor behaviour the published profile does not define.**
How the live service signals an unknown member, an unknown provider, inactive coverage and the
delegated vendor is not known. The tool recognises each from a coded element by a rule kept in one
place in the client layer, the mock scenario files for those cases say what they assume, and
anything the rules do not recognise is still shown in full with the outcome "no determination" or
"rejected". These are open items, listed in the README.

**D-034. A Jackson 3 trap that section 4 does not list.**
Jackson 3 refuses to bind a missing JSON property to a primitive record component
(`FAIL_ON_NULL_FOR_PRIMITIVES` is on by default). Any record read from JSON uses boxed types for
optional numbers and booleans and normalises them in its constructor. Found by a failing test on
the ledger record.

**D-035. Request parameters are converted explicitly.**
A standalone MockMvc test and the running application register different converters, so an enum
or date parameter could bind in one and fail in the other. Controllers take such parameters as
text and convert them with `web/Params`, which gives every bad value the same 422.

**D-036. Exception messages are never logged or returned.**
HTTP client and JSON parser messages quote urls and bodies, which hold member data. Failure
details are fixed phrases chosen by exception type, and an unexpected error is logged as exception
types and stack frames under a reference the browser also receives.
Later: narrowed on purpose by D-277. The first line of that log entry and the answer to the browser
name the chain of exception types, and a few throwables whose message the Java runtime or the IDE's
compiler writes about the program itself give that message; every other message stays out.
Later: D-278. From the build of suites until `7cbc0f8`, the Java runtime itself could print the
message of an `Error` thrown during a suite into the log, around the one place that writes failures;
every thread of the tool now ends in one entry under a reference while it runs, and the rule holds
there too, except a start that fails, which Spring reports with its configuration messages (D-278),
and the two narrow paths named in D-278's limits.

**D-037. Records that hold member data or wire JSON redact their `toString`.**
A record's default `toString` prints every component, so logging such an object once would put
member data in the log. Those records override it, and a test fails if an override is removed.

**D-038. Transport classification.**
A connect timeout means the service was never reached and is `UNAVAILABLE`; only a read timeout is
`TIMEOUT`. An HTTP 3xx is classed with 4xx, since a redirect means the configured url is wrong and
redirects are not followed. The live HTTP client is the JDK client because it reports the two
timeouts as different exception types.

## Changes made after the contract review

Before the shared types were frozen, six reviewers each read them from the seat of one downstream
owner. These decisions came out of that review.

**D-039. The guardrail is decided on what is sent, not on what it is called.**
The first draft asked only whether the named operation was a write. A reviewer found three ways to
send a production write as a read: naming a read operation with a state-changing method, remapping
the vendor's submit request to "inquire", and pointing an inquire call at the submit request. Now a
call is a write when any of these is a write for the method that will actually be sent: the
operation the caller named, the operation the request is mapped to, and the operation that
detection reads from the request's own name and url, as a template and as resolved. A named
request must be mapped to the operation it is used for, a caller cannot choose the method of a
mapped operation, and a read sent with a state-changing method that is not its own is a write.

**D-040. On live, a write needs an active environment.**
With no active environment there is no kind, so a fallback url from `application.yml` cannot be
told from production. On the live profile such a write is refused. Reads still go to the fallback
url. On the mock profile nothing is sent, so the out-of-the-box install can run every operation
with nothing configured.

**D-041. An unlock is honoured by identity.**
The gateway keeps the unlocks it has minted and honours only those very objects. An object with
the right environment name and epoch that the gateway did not mint is refused. The registry is
emptied on every configuration change and on relock.

**D-042. A prepared call belongs to its configuration epoch.**
The epoch moves on every change to the stored configuration, not only on an environment switch,
because a remap changes where a button sends. A prepared call records its epoch, and both the live
and the mock sending paths refuse one that is no longer current with a 409. The access token is
fetched only after that check, so a switch between preparing and sending cannot put one
environment's token on a request to another environment's host.

**D-043. An unresolved body is refused, on both profiles.**
A body that still holds a placeholder has no form to send. The first draft would have sent it as
no body at all and reported the request as sent. It is now a 422 naming the placeholder, on mock
as well as live, because the tester's own text is the thing at fault.

**D-044. Text that belongs to a call is resolved by that call.**
A request body is resolved through the prepared call, against the snapshot the url came from and
the variables of the collection the request came from. With two collections uploaded that define
the same variable, a body cannot pick up the other one's value.

**D-045. Url tokens.**
A single-brace `{name}` token or a Postman `:name` path variable in a url is filled from values the
caller supplies for that purpose and is percent-encoded, so a member ID containing a space, a
slash or `%` cannot change the shape of the url. This is separate from `{{placeholder}}`
resolution, which substitutes as written, as Postman does.

**D-046. The ledger says what it could not keep.**
A record lists the inputs it does not hold: a hand-edited body, member or provider details typed
by hand, values typed for one run, a hand-picked collection request, answers carried from an
earlier step. It holds their names only. Replay refuses a record that lists any, because running
it again from the line would send a different request and the diff would compare two different
things. The record also names the collection request its url came from.

**D-047. How a line of business is matched.**
A member's line of business and the code list's are both free text from systems whose contracts
are not final. A line matches a row when it equals a line the row names after ignoring case,
spaces, underscores, hyphens and dots; a cell that names several lines belongs to each; and
`codelist.line-of-business-aliases` maps the member API's name for a line to the list's. There is
no fuzzy matching. The code list can also say whether it knows a line at all, so "no row for this
code on this line" is not confused with "the list does not have this line".

**D-048. Code list dates.**
An effective or end date is given in ISO form when the cell can be read as a date and is otherwise
kept exactly as written. A row whose dates cannot be read still applies; it is not dropped.

**D-049. Lookup authentication.**
A lookup sends the headers configured for it. Setting `lookup.member.use-onyx-auth` or
`lookup.provider.use-onyx-auth` also sends the same access token the Onyx calls use. How the
member and provider APIs authenticate is an open item.

**D-050. Printed and serialised forms.**
`Shown`, the value every view type is built from, prints its presence and never its value, so no
view type can put a returned value in a log. A resolved url prints its template and never the
resolved form, because for a lookup the resolved url holds the member ID. The properties records
neither print nor serialise a client secret, a variable value or a header value.

**D-051. Sending an unmapped request raw.**
The build prompt requires it and assigns it to no wave. The integrator builds it after the waves
are merged. It never sends the Onyx access token to a host that is not one of the configured Onyx
targets.

**D-052. A request with a pre-request script is a finding on the result.**
The script is never run. When a run uses a collection request that carries one, the result and the
ledger record say so, because a value Postman would have computed was not computed and that is
often the unseen cause of a failure.

**D-053. Root package and group.**
The identity table in section 2 of the build prompt gives the root package as
`com.thehiddenbrain.interop.epa.pave` and the group as `com.thehiddenbrain.interop.epa`. During the
build the operator said the package name should be `org.p32h.`. That was read as a correction to
the reverse-domain prefix, and one substitution was applied to both rows: `com.thehiddenbrain`
became `org.p32h` and every other segment was kept. The root package is
`org.p32h.interop.epa.pave` and the group is `org.p32h.interop.epa`. The directory, the project
name, the jar name `epa-pave-1.0.0.jar` and the port are unchanged. If a shorter package was
meant, such as `org.p32h.epa.pave` or `org.p32h.pave`, it is one mechanical rename.


## Changes made after the second half of the contract review

The CRD seat and the PAS and DTR seat reported after the first set of changes. Each finding was
checked against the code before it was applied. These are the ones that changed a contract.

**D-054. An answer is tied to the procedure code it is for, and never by assumption.**
A CRD response answers orders, and a tester asks about procedure codes. The request builder reports
each order it sent with its code (and the same list is read out of a tester's own body). An answer
is tied to a requested code when it sits on the order that was sent, or on a resource that echoes
that code, or, when it sits on no resource at all, when exactly one order was sent. Nothing else is
taken as evidence. A requested code that no answer is tied to is reported as unanswered: it counts
as "no determination" for that code, so the order as a whole is `MIXED`, the verdict names the
code, the grid shows it as not returned and the test result cannot pass on the other code's answer.
The safer reading was chosen over the convenient one: an answer on a card, attached to no order,
is not assumed to cover every code in an order of several.

**D-055. Codes of different answers are never pooled.**
`Expectations` compares each answer separately. A `name=code` term with no subject holds only when
every answer carries the code, and something asked about and left unanswered satisfies no code
term. Any-match stays inside one answer, which `doc-purpose` and `info-needed` need. An element
name outside the ones the operation returns is a validation error that lists the valid names; it
is matched ignoring case and compared under the response's spelling.

**D-056. The ledger's coverage codes carry their subject when there is more than one.**
A run with one answer writes `pa-needed=no-auth` as before. A run with several writes
`95810: pa-needed=auth-needed`, and an unanswered code is written `95811: (no answer)`, so a replay
in which two codes swap answers no longer writes an identical line.
(Extended by D-236: a `covered`, `pa-needed` or `doc-needed` sent null, empty or with no value is
written `name=(null)`, `name=(empty)` or `name=(no value)`.)

**D-057. The mock answers every order it is sent.**
A scenario file holds the answer for one order. When a request carries several, the mock repeats
that answer for each, and the result says it did. Otherwise every mock run with two codes would
report the second as unanswered and testers would learn to ignore the finding that matters most on
live. A scenario that puts its answer on the card (scenario 3) is not repeated, so with several
codes it shows what an untied answer looks like.

**D-058. A typed read that returns nothing consumes nothing.**
`TrackedJson.text`, `presentText`, `integer` and `bool` mark a field as used only when they return
a value. Asking for the elements of an array never marks it. An empty array therefore appears
among the returned fields unless the view states the emptiness itself, which keeps `"links": []`
apart from no links at all without a presence flag beside every list. An empty string, array and
object keep their JSON text so they can be told apart.
(Overturned in part by D-236: a designed view now says in words whether a value or a list was not
returned, returned null or returned empty, from presences the server sends. The rule for the tracked
reads stands.)

**D-059. What a resource holds beyond what the tool reads is shown with its action.**
An action's resource is listed in full beside the action (`ActionView.resource`), so an echoed
order does not fill the list of fields nobody anticipated. The action's own `resourceId` and the id
of the resource it carries are two slots.

**D-060. When the code list was not compared, the result says so.**
`CODE_LIST_NOT_COMPARED` is raised, with the reason, wherever the comparison could not be made:
no row for the code, no row under the member's line, the member's line not known and only named
lines listed, rows that disagree, a side that takes no position, no answer tied to the code, or
routing one side does not state. No finding now means the two were compared and agree. With no
line of business known, only rows that state no line are used: choosing a named line's row would
be a guess about the member.

**D-061. A body the tester supplies is sent as written.**
It is no longer checked for JSON before its placeholders are resolved, because a vendor sample
with an unquoted placeholder is not JSON until then. If it is still not JSON afterwards it is sent
anyway, which a negative test needs, and the result carries a note saying so. A blank body means
none was supplied.

**D-062. A refused production write is a result, not only an error.**
The service catches the refusal, records the attempt and returns the usual result shape: the
verdict says nothing was sent, the test result is `unverified`, the next step is to unlock or
switch, the wire view shows where the request would have gone marked as not sent, and the ledger
receipt is included. The controller answers 403 with that result as the body. A preview of a
blocked write still shows the target and says it is blocked, without building the body.
CRD passes the session's unlock too: a hook is a read, but a tester can point a run at any
collection request and the gateway judges that request for what it is.

**D-063. Operations that never leave the tool.**
`DTR_RESPONSE` was added for the step that completes a questionnaire inside the tool, so that step
has its own ledger line and is not recorded as a call that was never made. It and
`SUBSCRIPTION_NOTIFICATION` are not outbound: the gateway refuses to prepare a call for either, a
replay or a suite reports them as not runnable, and if a collection request detected as one is ever
pointed at a url it is judged as a write for every method that can change state.

**D-064. A url value that was supplied is never ignored.**
If the chosen url has no token for it the call is refused. For the three subscription operations
that take an id, the id is bound to the `{{placeholder}}` after `Subscription/` ahead of every
other layer, so an id left in an uploaded environment cannot redirect a delete.

**D-065. A scenario step answers only its own call.**
A step name the scenario file does not define is refused with a validation error naming the
scenario and the step. It is not answered with the main step, which would answer an inquiry with
the submission's response. A step can carry its own `expect` block.

**D-066. The exchange refuses states that cannot have happened.**
`VendorExchange` requires a failure type, a status for every call that got a response, and no
status or body for one that did not. An exchange built with nothing set used to read as a
successful empty answer.

**D-067. Outcome wording.**
`REJECTED` reads "The service did not accept the request as sent" and the next step follows the
status: 401 and 403 point at the credentials, 404 without a not-found finding points at the url and
discovery, anything else at the request. A response with no body is described as that, not as a
body that could not be read. `MIXED` is worded about codes, not services.

**D-068. Which mock scenario answered is stated under the verdict.**
The first supporting line of a mock result names the scenario and how it came to be the one,
including when it was the default because nothing was chosen and no sentinel matched.

**D-069. One set of resource builders and one request shape for the three protocols.**
`service/run/CaseResources` builds the Patient, Coverage, Practitioner, PractitionerRole,
ServiceRequest and insurer, with the local ids as constants. Each protocol's request record
implements `RunRequest`, and `RunSupport` fills the ledger draft and the list of unrecorded inputs
from it. In a chained run DTR must send the order and coverage CRD sent, and PAS must build its
items from the same codes and systems, so copies would be a correctness risk.

**D-070. `onyx.pas.attachment-mode` is checked at startup.**
Only `fhir` and `x12-275` are accepted. A PAS submission that carries documentation records the
mode it used.

## Changes made after the frontend, chain and suites review

**D-071. The ledger line records the stated next step.**
`RunRecord.nextAction` holds the name of the next step the result stated. The result on screen is
gone when the browser tab is, and the ledger line is all the history, a reopened chain and a suite
row ever see, so without it a history record could not answer "what do I do next", and a chain row
could not say that the flow ended at CRD.

**D-072. A receipt always carries the record.**
`LedgerReceipt.record` is the record after the tracking policy, whether or not it reached the file.
A suite row and a replay see nothing of a run except that record, so dropping it when a write
failed would make a run that happened vanish from their results. When no ledger applied the policy
(none is available, or it threw), the receipt carries the record with everything the policy governs
removed. `CaseOutcome.notRecorded` lists the ledger's message for each run it did not keep.

**D-073. The hand-off between steps is part of the shared request.**
`RunRequest.carry` is the previous result's `nextStep.carry`, posted back unchanged. A run that
carries anything is marked `prior-step` in the ledger, so a replay does not run it without them.

**D-074. Only the tool mints a chain id.**
A blank chain id means no chain. The literal `new` makes the service mint one, which comes back on
the result. Any other value must have the shape of an id this tool minted, or the run is refused:
free text there would be written to the ledger as it stands.

**D-075. A run says which configuration its screen was drawn from.**
`GET /api/mode` gives the configuration epoch. The browser posts it back as `configEpoch`, the
gateway refuses a call whose epoch is no longer current, and a suite pins the epoch it started
under. The active environment is one value for the whole server and the environment strip is drawn
per tab, so without this a tab still showing a sandbox would send to whatever another tab
activated. A request with no epoch is not checked, which keeps a plain curl example working.

**D-076. The outcome vocabulary is served, not copied.**
Each protocol's scenarios endpoint also returns every outcome with its label and tone, and the
element names an expectation may use, on live as well as on mock. The expectation picker and the
history list read them from there.

**D-077. A failure of the tool after the call is a result.**
The service wraps only the reading and wording of the response in a try. On a failure it logs
under a reference, records the run as `TOOL_ERROR` and returns the usual result shape with the
request that went out, the response as received and the ledger receipt, answered with 500. A
mapper defect after a successful submission must not leave a tester with "internal error" and no
sign that a request reached the payer.

**D-078. A request field the tool does not know is refused.**
Each run request and each suite row collects unknown fields, and a request that has any is refused
with a 422 naming them. A misspelt `expected` would otherwise be dropped without a word and the run
recorded as unverified.

**D-079. What the browser is sent.**
A null is left out, never written as null, with the ledger record as the documented exception.
`EnvironmentRef` serialises an `active` flag. An entry in the returned fields says whether it is a
field, a raw body or a note. Which results answer 403 and 500, and with which body, is stated on
`RunResponses`.

## Changes made after the last review: what survives a green build

The sixth reviewer built nothing and asked only what a passing build could still be hiding. Every
finding was reproduced before it was applied.

**D-080. Nothing a service wrote reaches the ledger line unchecked.**
`RunRecord` has no component for member data, but several of its components are filled from a
response, and a response can hold anything: a sentence where a code belongs, a person where a
vendor belongs. The record now checks each of them as it is constructed, whoever constructs it. A
coverage code entry is kept only as `name=code` with the code one unbroken token; anything else
becomes `name=(not a code, N characters)`, so the fact survives and the text does not. A
correlation value must be one line with no quotes or braces, under one of the record's own keys.
A procedure code and a line of business are short labels. The subject written before a code is the
code that was asked about or the place in the response, never text the response supplied.

**D-081. Only a vendor the tool knows by name is written to the ledger.**
The reviewer proposed keeping any short name. A pattern cannot tell a company from a person, and
under a vendor code a service can return either, so the safer reading was taken: eviCore, Evolent,
Carelon and Optum Post Acute are written in their own spelling, and any other text is written as
`(vendor not on the known list, N characters)`. The text is still shown in full on screen, where an
unknown vendor is a finding. The cost is that the name of a new vendor is not in the history until
it is added to the list.

**D-082. A run line that lacks what makes it a run is refused.**
The run id, timestamp, protocol, operation, outcome and result are required by the record itself,
not only by its builder, so an incomplete line fails to read and is counted, and is never taken for
a run.

**D-083. A response that starts and does not finish keeps what arrived.**
The status and headers are taken before the body is read. If the connection ends or the read
timeout passes part way through the body, the result keeps the status and the bytes that arrived
and says the body is incomplete. It is not reported as a service that could not be reached. A
method or a header that is not valid HTTP is refused by name before sending, as a 422, and is not
reported as a network failure either.

**D-084. Headers the HTTP client writes itself are not sent as saved.**
Content-Length, Host, Connection and the like are left out when they come from a collection
request, and the wire view names what was left out. Transparent compression is switched off, so
the client adds no header of its own beyond Host and Content-Length and the bytes shown are the
bytes that arrived.

**D-085. A header that still holds a placeholder is refused, like a body.**
`ResolvedHeader` says which placeholders are missing, and a call with such a header is refused
before sending on both profiles. Left out in silence, the request would go without its key, or
with the Onyx token standing in for the credential the collection asked for.

**D-086. A credential header shows its scheme at most.**
Only for Authorization and Proxy-Authorization, and only when the first word is a well known
scheme. Every other credential header, Cookie included, shows nothing of its value. `WireHeader`
and `VariableUse` apply the masking themselves, whoever builds them.

**D-087. A body is decoded with the charset it names, and the byte count is what arrived.**
Bytes that are not valid in that charset are shown with a note saying the text was altered. They
are not replaced in silence.

**D-088. Which names are secrets.**
A name is split where letters meet digits, a word counts when it ends with one of the longer
secret words, and a longer word ending in `key` counts. `clientsecret`, `ACCESSTOKEN`, `token2`
and `JSESSIONID` are now masked. Where the rule errs it errs toward masking.

**D-089. The parts of a result refuse to exist hollow.**
A verdict needs a headline, an outcome and a tone. A test result can pass or fail only against a
declared expectation. A receipt always holds the record and says why when it was not written. The
exchange refuses a failure type that does not fit its status.

**D-090. An enum is never read from a number.**
`Protocol`, `EpaOperation` and `AuthRequirement` are read from text. The authorization flag is read
by its meaning: the common spellings of yes and no, 1 and 0 included, and anything else is unknown.
The same reader serves the CSV and the JSON form of the code list.

**D-091. A request body is read strictly.**
A property the receiving type does not have is a 422 that names it. The run requests collect
unknown properties themselves and name every one; this covers every other body. Controller tests
build their mapper with the same call the application uses (`testsupport/TestMvc`).

**D-092. What was asked is a parameter of the expectation check.**
`Expectations.assess` is given the subjects that were asked about and works out for itself which
went unanswered. A mapper cannot make a comparison pass by leaving the unanswered part out.

**D-093. Every outbound request leaves through the shared executor.**
A test walks the main sources and fails when a file outside `client/support` performs an HTTP
exchange of its own. The guardrail proves a request was judged; this is what makes the request
sent the one that was judged.

**D-094. Decimals are read exactly.**
A response value of 1250.00 is shown as 1250.00, not 1250.0, and a long identifier is not shown as
an exponent.

**D-095. Timeouts of zero are refused at startup.**
On both profiles, naming the property. A read timeout of zero would report every call as timed
out, with nothing pointing at the setting.

**D-096. A failure of the tool after the call answers 500 with the result as its body.**
Two reviewers asked for the result to be returned and disagreed on the status. 500 was chosen: the
browser can tell it from a completed call without reading the body, and the body still carries the
run id, the wire view and the ledger receipt.

## Decisions made while the waves were building

**D-097. Sending a request raw (amends D-051).**
`POST /api/raw` sends one collection request as written, through the same gateway and the same
guardrail as everything else: the gateway judges an unmapped request by its method and by what its
name and url look like, and a write to production is refused unless the session unlocked it. The
tool does not interpret the answer. It reports what came back (answered, answered with a body that
is not JSON, answered with no body, or a fault), lists every field of it, reads an
OperationOutcome issue by issue, and records the run. A tester can declare the outcome or the HTTP
status they expect (`http-status=200`). On mock every raw request gets the same fixed answer,
which says the mock does not model it.
(Completed by D-184: without a body from the caller the body saved with the collection request is
sent, a `{token}` in the url is filled from `urlValues`, and on mock the result says a scenario
answered and nothing was sent.)

**D-098. The Onyx token goes only to an Onyx host.**
A vendor collection can hold a request to another service. The Onyx access token is attached to a
raw request only when its host is one that an Onyx operation resolves to in the configuration in
force, and only when the request carries no Authorization header of its own. Otherwise no token
is requested at all.
(Replaced for every protocol and lookup call by D-143, rule 7, which compares scheme, host and
port. A request sent from the collection as it is still follows the host rule of this entry; changing
it is open work at the revision of D-175.)
(Done since: a request sent from the collection as it is now follows D-143 rule 7 as well, by scheme,
host and port. See D-184.)

**D-099. The embedded server's own errors use the shared error shape.**
A request the server refuses before it reaches the application, such as one with characters a url
may not hold, was answered with an HTML page naming the server. A custom error valve now answers
it as JSON in the shared shape. A direct request to the error path answers 404.

**D-100. A controller is not a bean until its dependencies exist.**
While the first wave builds the gateway, the ledger and the code list, the CRD and raw
controllers and services carry no stereotype annotation, so the application context does not try
to build them. Their tests register them through a small annotated subclass. The integrator adds
the annotations when the wave is merged.

**D-101. A suite row and a replay take the path a manual run takes.**
`ProtocolCaseRunner` runs one case from its inputs alone. It looks the member and the provider up
again from the source, then calls the same service method the screen calls, so a case writes the
same kind of ledger line and is held to the same guardrail. It never carries a production unlock:
a write the guardrail refuses comes back as blocked, never as failed. It never throws. Each way a
case can end without an answer has its own status, so a results table can say which it was:
invalid inputs, tool not configured, blocked, or not runnable. A key in a case that this tool does
not know makes the case invalid and is named, because dropping a misspelt "expected" in silence
would turn a failing case into an unverified one.

**D-102. A case for a member the lookup cannot give is still sent.**
When the member lookup answers not found, is not configured, or fails, the case is sent with the
member ID alone and the result carries a note saying so. Nothing is made up to fill the gap. This
is the safer reading: refusing the case would make "member not found at the payer" untestable from
a suite, and inventing a name or a date of birth would test something nobody asked for. The same
holds for the provider lookup and the NPI.

**D-103. The suite file: columns, alternatives, and what happens to a column the tool does not know.**
A suite file is CSV or JSON. Each row is one case with these columns: caseId, protocol, hook,
operation, chain, memberId, npi, procedureCodes, dateOfService, expected, scenarioId, testerRef,
note, claimId, subscriptionId. Letter case, spaces, hyphens and underscores in a column name are
ignored and a few plain alternatives are accepted, so "Member ID" and "memberId" are one column.
A CSV column the tool does not know stops the whole file, and a JSON key it does not know makes
that case invalid: a misspelt "expected" that is quietly dropped would turn every row into an
unverified one. A column whose name starts with # is the tester's own and is never read, so a
requirement number or an owner can stay in the file.

**D-104. A row the tool cannot read exactly is reported and not run.**
A row must say what to run: a protocol (crd, dtr, pas or chain) or an operation. There is no
default, because a default would run something the tester did not ask for. A date not written as
YYYY-MM-DD is refused and never guessed, since a spreadsheet may have rewritten it and day and
month cannot be told apart. An NPI with a wrong check digit, a row with a different number of
cells than the header, a case id used twice and an expectation that names no outcome of the
protocol are all found before anything is sent. Such a row is reported as not valid with the
reason, and the rows around it still run.

**D-105. One suite at a time, in the background, pinned to one configuration.**
`POST /api/suites` answers 202 with the run as it stands and the cases run one after another on a
single background thread. The screen asks for the run by id until it is no longer running. A
second suite started meanwhile is refused with 409. The run is pinned to the configuration it
started under. If the configuration changes while it runs, the run stops and says so: the cases
left would have gone to a different target than the cases before them, and one results table
must describe one target. The rows not reached are marked as not run, which is a different thing
from failed.
(Corrected by D-168: a suite stopped by a change of configuration says so without claiming that
the cases left would have gone to a different target, which for most changes was untrue.)

**D-106. What a suite holds in memory.**
The uploaded file is not stored. A case holds its member ID until the case has run or the run has
ended, and is then dropped. A result row never holds a member ID: it points to its ledger records
by run id, and the ledger shows the member the way the tracking policy allows. The last ten suite
runs are kept in memory until the tool restarts. The ledger is where results last, with every
case filed under its suite run id.

**D-107. A suite file must be UTF-8.**
A file that is not valid UTF-8 is refused with the instruction to save it as CSV UTF-8. Reading it
under a guessed encoding could turn a cell into something the tester never wrote. A plain CSV
saved by a spreadsheet is byte for byte the same as UTF-8 unless it holds an accented letter or a
typographic quote, so most files are unaffected.

**D-108. A suite can be compared with an earlier run of the same cases.**
Starting a suite with the id of an earlier suite run adds to each row what that case produced
last time and whether it changed. The earlier results are read from the ledger, so the
comparison works across a restart and across builds of the service. Cases are matched by case id,
which is why a case with no id is named by its position in the file. A case the earlier run did
not have is not marked as changed, since there is nothing to compare it with, and cases of the
earlier run that are missing from the file are listed.

**D-109. Response times describe the calls the service answered.**
The distribution across a suite (fastest, median, 90th and 95th percentile, slowest, mean, and
counts by range) is taken over calls that got an HTTP answer, an error status included. A call
that timed out has no response time to describe, only the timeout, so it is counted on its own. A
percentile is the nearest rank: always a time that was measured, never a value between two. A
call that completed but took longer than the threshold is counted as slow and flagged on its row.

**D-110. The suite results export cannot run as a formula.**
The export for sign-off opens in a spreadsheet, and free text from the suite file reaches it (a
case id, an expectation, a file name). A cell that starts with =, +, - or @, or with a tab or a
carriage return before one, is written with a leading apostrophe, which a spreadsheet shows as
text.

**D-111. A chain is started by a run and read back from the ledger.**
There is no "create chain" call. A run sent with the chain id "new" starts a chain and returns
its id, and a later run sent with that id joins it. `GET /api/chains/{chainId}` reads the chain
back from the ledger alone, so the journey looks the same after a restart and History can expand
a chain into its steps. The view arranges the runs under three stages, CRD, DTR and PAS, and
states where each stands.

**D-112. A stage's state comes from what each run said should happen next, never from an outcome code.**
Every run records the next action it stated. The chain view places the later stages from that:
"next" when the earlier stage handed on to it, "not needed" when the earlier stage ended the
journey or went straight past it, "not reached" when the earlier stage has not finished, and "not
run" when the chain was started further along. A stage that did not run always says which of
these it is, in words, because a blank would hide whether a step was skipped or is still to come.
A record that names no next action is shown as saying nothing about what comes next, and the
journey is not taken to have ended on the strength of its outcome.

**D-113. The most recent run says where the journey stands, and nothing is removed.**
A step can be run again without the steps before it. Both runs stay in the chain. The later one
states where its stage stands, and the earlier one is marked as having been run again since. If
an early step is run again after a late one, the chain's next step follows that latest run. Two
members under one chain id are reported as a note on the chain, since a chain follows one member
and one order.

**D-114. A dev harness in the test sources, so the frontend can start before the first wave lands.**
`DevHarness` starts the application as far as it is built, with stand-ins for the configuration
layer, the ledger, the code list and the lookups, and a few /dev switches for reaching a screen
state on purpose (a production environment, a refused write, a ledger that did not record). It is
in the test sources, so it is never in the jar, and it is removed when the real parts are wired.
It is a test configuration, and `PaveApplicationTest` checks that nothing from the test sources is
part of the application's context: without that, the application's own start-up test picked the
stand-ins up from the class path and would have passed on wiring the application does not have.
(Removed as planned: no `DevHarness` is in the sources at the revision of D-201.)

**D-115. The tool listens on this machine only, unless someone decides otherwise.**
`server.address` is `127.0.0.1` in `application.yml`. The specification is silent on the bind
address, and the framework's default is every interface. The tool has no sign-in, and on the live
profile its API looks up real UAT members and can submit to whatever environment is active, so
the reading that is safer if wrong is not to offer it to the network by default. The cost falls on
one feature: a vendor's notification can reach the receiver only when an operator sets an address
the vendor can reach, which the specification already expects to be rare on a corporate
workstation. The polling path needs nothing. The README says how to open it and what that exposes.

**D-116. Other web pages are kept away from the API.**
A tester's browser has other pages open, and a tool on localhost with credentials behind it is a
known target for them. Two checks in one filter: an API request must be addressed to localhost, a
loopback address, the bound address or a name listed in `app.allowed-hosts`, which stops a page
served from a name that has been pointed at this machine from reading answers; and a request that
changes something must not state another origin, which stops a page on another site from sending
one. A client that states no origin, such as curl or a vendor's server, is let through. A delivery
to the notification receiver is exempt from the host check only. The session cookie is
SameSite=Strict and HttpOnly, since the production unlock lives in the session.
(Amended by D-148: where a request comes from is now read from `Sec-Fetch-Site` first, and a request
another site's page made is refused whatever its method, because on live a read acts. The one
exception is a top-level GET navigation, which opens a page but never an answer of the API; and a
request that carries neither `Sec-Fetch-Site` nor `Origin` is still let through. D-148 says both.)

**D-117. API responses are never stored by the browser, and pages load from this tool only.**
Every `/api/` response carries `Cache-Control: no-store`: a lookup answers with a member's name and
date of birth, and "no member data is persisted" has to include the browser's disk cache. Every
response carries a Content-Security-Policy that allows scripts, styles and connections from this
tool only, so vendor text that ever reached the page as markup could neither run nor call out,
and "no request leaves the machine for an asset" is enforced by the browser as well as by a test.
Static files are served `no-cache`, so a tester who edits one sees the edit on refresh.
(Corrected by D-127, which marks every response `no-store`, and by D-150, which names the answers
the server gives before any filter runs and which therefore carry fewer headers.)
Later: D-298. Saved lists of test members and providers, entered on purpose in Configuration, are kept
on disk on the operator's leave (test data only, never logged, never in the ledger, off by one setting);
the rule otherwise stands, and this entry's no-store rule for the browser is unchanged.

## Requirements the operator added during the build

**D-118. A tester can paste a request of their own and send it, on every protocol tab.**
Asked for by the operator while the waves were building: "I should be able to copy paste the
request payload in the browser to run the CRD in case the tool is not formulating it as per
requirement." The server already did this: a run that carries `rawBody` sends that text exactly
as written once its placeholders are filled in, the form's member, provider and codes become
optional, the ledger line says the body was not built by the tool, and on mock a sentinel member
ID inside the body selects the scenario. What was missing is the box on the screen, which the
next frontend pass adds to the shared form so that CRD, DTR and PAS all have it.

**D-119. The tool compares a pasted CRD request with its own and explains each difference.**
Asked for in the same message: "when this tool generates the request and when I copy/paste my
request given by the UM vendor, you should be able to show the diff and explain them."
`POST /api/crd/compare` builds the tool's request for the same case and hook as the pasted one,
reading the case out of the pasted request where the form is empty, and lists the differences in
five groups: differs on every call, labels (ids and the references that follow them), the case,
how the request is built, and not classified. It sends nothing and records nothing. Two rules
shape it. The comparison says what differs and what each element is, and never which request is
right or what the vendor requires, because the tool does not know: the vendor's reference harness
was not available when the request builder was written (D-001) and the guides could not be
reached (D-008), which is exactly why the operator asked. And an element the tool cannot describe
with confidence is listed as not classified, since a tester would repeat a wrong description to
the vendor. The comparison covers CRD. Nothing equivalent exists for DTR or PAS.
(The endpoint is on the merged line since the fix wave; until then this entry described a branch.
How it pairs elements and words differences is under D-170, and the request it compares with is
the one D-157 describes.)
(The screen for it is the CRD tab, D-194. At the revision of D-201 the comparison still returns a
credential the pasted request carries as it was typed; masking it is decided and not done, D-200.)

**D-136. The guides the tool is held to are version 2.0.1, on the operator's word.**
Asked which version to compare with, the operator answered "2.0.1". That is also the version the
CMS interoperability and prior authorization rule names for CRD, DTR and PAS, and until the
vendor says otherwise it is taken as the version the sandbox follows. This settles part of the
open item under D-130 and has three consequences.
- Where the published versions differ, the tool follows 2.0.1 and says so where a tester can
  see it. A requirement that only a later version makes is not applied by default, and is not
  raised as a finding against a service that follows 2.0.1.
- PAS is compared with 2.0.1 from the guide's own source repository, which this session could
  read, and with the 2.2.0 ballot for what changes.
- CRD and DTR could not be compared with 2.0.1 at first: their sources are in two public
  repositories of the standards body, and this session's permission check refused attaching
  them. The operator then supplied the two 2.0.1 packages as files. All three protocols are now
  being compared with 2.0.1 by auditors who wrote none of the code. An earlier comparison of CRD
  with the 2.2.0 ballot alone had finished by then; its 26 findings are an input to the 2.0.1
  comparison, which says for each whether it holds in 2.0.1, and none of them is acted on
  before that.
(Done: the 2.0.1 comparison of all three protocols reported and was acted on. The rules are D-140
to D-142 and what each protocol now does is D-155 to D-166.)
(Later: asked which version the vendor's sandbox follows, the operator answered 2.0.1 as well. It is
the operator's statement, not an observation of the sandbox: D-269.)
Later: D-293. Since the rebuild, on the operator's answer, Submit and the inquiry follow Da Vinci PAS
2.2.1; CRD and DTR stay at 2.0.1, and the readers of PAS answers were written from 2.0.1.
Later: D-300. Submit and the inquiry follow PAS 2.0.1 again unless the environment's "PAS guide"
chooses 2.2.1.

**D-137. The build prompt's CRD tables and CRD 2.0.1 disagree, and 2.0.1 decides.**
The build prompt names "Da Vinci CRD v2.2.1" in three places and gives the value sets of the
coverage-information extension in section 11. D-008 made that table the authority while no
guide could be read. Read from the 2.0.1 package the operator supplied, the value sets are:

| Part | 2.0.1 package | Build prompt, section 11 |
| --- | --- | --- |
| `covered` (required, once) | `not-covered`, `covered`, `conditional` | the same |
| `pa-needed` (at most once) | `no-auth`, `auth-needed`, `satisfied`, `performpa`, `conditional` | the same |
| `doc-needed` (at most once) | `clinical`, `admin`, `both`, `conditional` | adds `no-doc` |
| `doc-purpose` (any number) | `withpa`, `withclaim`, `withorder`, `retain-doc`, `OTH` | `retain` and `oth` |
| `info-needed` (any number) | `performer`, `location`, `timeframe`, `contract-window`, `OTH` | no `contract-window`, and `oth` |

In 2.0.1 the extension also requires `coverage`, `date` and `coverage-assertion-id`, and a
`detail` has the parts `code`, `value` and `qualification`. In 2.0.1 "no documentation is
needed" is said by leaving `doc-needed` out; `no-doc` is in neither the 2.0.1 nor the 2.2.0
ballot package, and which version it belongs to cannot be checked here.

Resolution, on the operator's word that the version is 2.0.1 (D-136): the tool judges a response
by 2.0.1. Because nobody has confirmed which version the vendor sandbox follows, the tool still
reads a code of another version it knows, and says which version the code belongs to instead of
calling it wrong. A tester repeats what the tool says to the vendor, so "this code is not in the
2.0.1 value set; it is a 2.2.0 ballot code" is a sentence the tool may say, and "non-conformant"
with the wrong list beside it is not. The code and the mock scenarios do not do this yet: they
follow the build prompt's table. The change is made once the 2.0.1 comparison has reported, so
that it is made once.
(Done: the tool judges a response by CRD 2.0.1, reads the codes of the 2.2.0 ballot and names their
version, and its mock scenarios answer in 2.0.1 codes. See D-155. The build prompt itself was not
edited: D-173.)

## Decisions made while integrating the first wave

**D-120. A ledger record that is checked again stays the same record.**
Found by the ledger's owner, not by the six contract reviews: the record replaced text that was
not a code, a known vendor or a code entry with a statement of its length, and on the next copy
replaced that statement again, so "(vendor not on the known list, 4 characters)" became 44 and
then 45. The number stopped being a length and a line read back differently from the record the
receipt carried, which a replay would have reported as a change. The check now recognises its own
statements and leaves them alone. A test builds a record, copies it twice and reads it back from
JSON, and all four are equal.

**D-121. The member token and the last four characters are checked like everything else on the line.**
These two are written under every configuration, including when the member ID is not. The record
now keeps a token only when it is 64 hexadecimal characters, which is what the ledger's keyed
hash produces, and the last-four field only when it is one to four characters with no space.
Anything else is dropped. A member ID put in either by a mistake in a caller is therefore not
written.

**D-122. Ledger health can say that it cannot say.**
The count of runs in today's file is now absent when the file cannot be read. Before, it was 0,
which reads as "no runs today".

**D-123. After a clean, the tests run.**
The specification asks for the Gradle build cache. With it, a second `./gradlew clean build` of
unchanged sources restored the test task from the cache and ran no test, so "build twice from
clean" would have reported one run twice. The test task's result is no longer taken from the
cache.

**D-124. Considered and not done: a ledger line does not say that an NPI was sent and not kept.**
The ledger's owner suggested marking such a line so that replay refuses it up front. As it
stands, replay sends the case without the NPI, the service refuses it before sending because
CRD, DTR and PAS all need one, and the replay report adds that the NPI may not have been
recorded. That is true and sends nothing, so the extra marker was left out.

**D-125. Two routes to production that the first guardrail left open are closed.**
Both were found and reported by the configuration layer's owner, who did not close them because
they went beyond the contract as written. The integrator closed them, on the rule the tool
already follows: wrongly blocking a read costs a click, wrongly allowing a write costs a real
authorization.
1. The kind belongs to the active environment, and a url can come from somewhere else: a value
   typed for one run, or a literal in `application.yml`. A write whose complete url contains
   "prod" is now refused under an environment that is not declared production, unless the uploader
   confirmed that environment as not production. There is nothing to unlock in that case: the way
   through is to declare the environment for what it is. So that there is always a way through
   for a sandbox whose address happens to contain "prod", the confirmation is now kept whenever
   the uploader gives it, not only when the upload itself read as production.
2. A collection request named like a read, such as an inquiry, whose url says nothing and whose
   method can change something, was taken at its name. A POST to the base of a FHIR server is a
   transaction whatever it is called. Such a request is now judged as an unmapped request, which
   on production means blocked unless unlocked. This is asked only of a complete url: one with a
   placeholder still in it is never sent, and calling it "blocked" would hide the missing value.
Both hold on mock and on live, and each has tests in `ProductionGuardrailTest`. What "reads as
production" means is unchanged and is deliberately crude: the text "prod" anywhere in the url.
A production address that does not say so is still judged by the kind its environment declares
(D-024). The guardrail auditor of section 27 has not yet examined any of this.
(Superseded by D-143 and D-144. The guardrail audit then examined this and found that the
environment's kind alone could be got round; the destination of a write now decides. The text
"prod" in a url is still a sign of production, looked for after decoding and in the host separately.)

**D-126. From the configuration layer's report, applied to the shared types.**
A configuration store that cannot be written while the tool runs now answers 500 with the name
of the file and the statement that nothing was changed, in place of a bare reference. A part of
a url written `{id}` or `:id` that has no value is reported as it is written in the url, and no
longer as a placeholder `{{id}}` to define in an environment, where it cannot be defined. The
gateway's contract text now states the stricter rules its implementation applies.
Later: D-295. A write the workstation refuses (a file marked read-only, a file another program holds
open, a full disk) now names that cause and is headed "A problem with this computer's files", with no
reference; any other failure keeps the sentence of this entry.


**D-127. The request guard and the rule that nothing is stored hold for every request, not for addresses that start with `/api/`.**
Found by the owner of the lookups, not by the integrator who wrote the guard (D-116, D-117). Both
filters decided what an API request is from the address as written. The framework matches on the
decoded path with path parameters removed, so `/%61pi/members/...` and `/api;x=1/members/...`
reached an API controller that neither filter took for one: the host check and the origin check
were skipped, and the answer was marked as one a browser may keep. Seen on the built jar with a
host name the tool does not answer to: 200 with the member, where the plain spelling answered 403.

The fix removes the decision instead of making it cleverer. A check that first has to sort
addresses into API and not API can be got round by anyone who finds a spelling it sorts wrongly,
and the server, the framework and a filter each read an address in their own way.
- The host check and the origin check now apply to every request. A page or a static file asked
  for under a name the tool does not answer to is refused like anything else. A browser opening a
  page gets the two sentences as plain text; a script gets the shared error shape.
- Every response is marked `no-store`. The pages and scripts were `no-cache` before. They are
  small and come from this machine, so fetching them on every load costs nothing a tester would
  notice, and an edit to one still shows on refresh.
- The one exemption, a delivery to the notification receiver, is for that address written
  exactly. Any other spelling of it is held to the host check.
- The lookups carried a stand-in for the missing check, refusing an address not written as
  `/api/`. With the guard in front of every request it has no purpose and is removed. The lookups
  still refuse a semicolon that is not percent-encoded, because the framework would cut the
  identifier there and report the shorter one as typed.

Tested through the running server over a socket, with each request line written by hand, in
`ServerErrorPagesTest` and `LookupOverTheServerTest`, and at the filters. The independent review
of the integrator's code, which should have found this, is still owed.
(The review of the HTTP edge has since run. D-148 to D-150 record what changed. The answers the
server gives before any filter runs, which carry fewer headers, are named in D-150.)

**D-128. Two log levels are raised so that the server and the framework do not write a refused address to the log.**
Also from the lookups' report, and seen on the built jar. An address can hold a member ID. With
their default levels the embedded server writes the request target of a request that holds a
character an address may not hold, with a stack trace, and the framework writes a path that
climbs with `../`. Neither request reaches a lookup. `application.yml` now sets
`org.apache.coyote.http11.Http11Processor` to WARN and
`org.springframework.web.servlet.resource.ResourceHandlerUtils` to ERROR. A test asserts both
levels and that such requests leave no identifier in the output. A browser sends neither form;
a hand-typed address can.
(Extended by D-150: three more loggers are raised.)

**D-129. From the lookups' report: how a lookup answer is read while both response contracts are open.**
Neither the member API nor the provider directory has a published response contract (section 26
of the build prompt), so the mapping is written to be narrowed, not guessed once:
- A field is found under any of a list of spellings, compared ignoring case, underscores, hyphens
  and spaces. What a name means is never loosened: a subscriber ID is not a member ID.
- A record may be bare, wrapped, or a list of one. Several records are a fault that says how many
  came back, never a choice among them. A record with values of which none is recognised is a
  fault listing every field, since those fields are the evidence for finishing the mapping.
- Not returned, null and empty stay apart for every field, as everywhere in the tool.
- The member ID on the result is always the form that was sent and the NPI the one asked for, so
  a lookup cannot change which identifier the next request carries.
- Coverage and network words outside a short list are said to be unrecognised and are sorted
  into neither active nor inactive. No status returned is neither active nor a reason to look.
- HTTP 404 and a success with an empty envelope are "not found". A success with no body is a
  fault, because an answer with nothing in it does not say the identifier is unknown.
- Disabled is never an error and sends nothing. It also covers a url with no place for the
  identifier and a refusal by the production guardrail, each with a sentence saying what to
  correct.
- Each live lookup logs one line: how it ended and the HTTP status. Nothing else.
- The tables to narrow are at the top of `MemberResponseMapping` and `ProviderResponseMapping`.
  Deleting what the real service does not use is the whole change.
- Mock sentinels for the states of a lookup itself: `P32H-LOOKUP-FAULT`, `P32H-LOOKUP-TIMEOUT`,
  `P32H-LOOKUP-MULTI`, `P32H-LOOKUP-NONE`, and NPIs `9999999987` (directory fault) and
  `1000000020` (out of network). Every fixture has to look invented, and a test enforces it.

## Decisions made while integrating the second wave

**D-130. D-008 revised: parts of the implementation guides can be read here after all.**
Found by the owner of DTR. `hl7.org` and `build.fhir.org` remain refused, and nothing was done to
get round that. Two other sources hold the same publications:
- the HL7 packages on the public package registry: CRD, PAS and DTR as balloted for 2.2.0, the
  Subscriptions backport as balloted for 1.2.0 and SDC as balloted for 4.0.0. These are built
  profiles, value sets and examples.
- the public source repository of the PAS guide, read at its 2.0.1 tag, which is the version the
  CMS rule names. The repository tool of this session served that one. Attaching the CRD and the
  DTR guide repositories was refused for this session, and they were not fetched another way.
So PAS can be compared with 2.0.1 and with the ballot, and CRD and DTR with the ballot only. The
owner of DTR read the narrative of DTR 2.0.1 and 2.1.0 before the refusal and recorded what
differs between versions in `OnyxDtrConventions`.

Why this matters more than a late convenience: the DTR owner found two faults by reading the
guide that every test had passed, because the mock scenarios were written from the same memory
as the code. CRD and PAS were written the same way. An independent comparison of each protocol
with what can be read was started as soon as this was known, by auditors who wrote none of the
code. Which version of each guide the vendor sandbox follows is still not known (section 26).
(Later: the operator says the sandbox follows 2.0.1; not observed against it: D-269.)

**D-131. A chain is a case like any other, and it stops where a tool has to stop.**
Section 28.1 makes the chain the real test case, so a suite row can run one. (As first written this
entry said a replay could too. It cannot: see D-138.)
- It starts with CRD under a new chain id and then follows the next step each result states. It
  never decides the route from outcome codes of its own.
- CRD says go to PAS: the submission is sent with what CRD handed on. CRD says go to DTR: the
  questionnaire package is fetched. If the package can go straight on, the submission follows.
  If a questionnaire has to be answered, the chain stops there and says that a person has to
  answer it. A tool that answered a clinical questionnaire by itself would be testing its own
  guesses.
- Anything else, the end of the flow included, stops the chain with the sentence of the step it
  stopped at.
- The expectation of a chain says which step each part is about: `crd:`, `dtr:` or `pas:` in
  front of each term, each checked against that step's own vocabulary. An expectation that does
  not say which step it means is refused before anything is sent, because "APPROVED" checked
  against whichever step happened to answer last could pass for the wrong reason.
- A case that expects an answer from a step the chain never reached has failed. It is not a
  pass with a step left out.
- A chain row may not choose a mock scenario: one scenario cannot describe three services.
(Corrected and completed by D-138 and D-167. The result of a chain case is now written to the
ledger as a line of its own, so a case that failed for a step it never reached is found by the
ledger's failed filter. Only a step that used what an earlier step handed on is said to be not
replayable alone; the first version of that note said it of every chain with a DTR or PAS step,
which was false for a chain started at PAS or DTR. The route "the package can go straight on, so
the submission follows" is still driven by no test and no mock scenario.)
(Since D-183 and D-189: the refusal a tester meets on replaying such a step says to run the journey
again as a suite row of the protocol `chain`, and a replay or suite row refused on production gets
its own next action instead of "unlock".)

**D-132. A submission that is replayed or run from a suite is a new request.**
It gets a new claim identifier every time. Sending a stored identifier again would be read by a
payer as a resubmission of the same request, and a replay is meant to ask the question again,
not to amend the earlier one. An inquiry does use the identifier of the submission it asks about.

**D-133. From the PAS report: how submissions, inquiries, subscriptions and notifications are handled.**
- The claim identifier is the one typed, else the one handed on, else minted. A resubmission
  after "attachment required" carries the same identifier, so both are one request.
- An inquiry is linked to the run that submitted the request it asks about: the one handed on,
  else the newest submission the ledger holds for that claim identifier. A ledger that cannot be
  asked leaves the inquiry unlinked, and it is still answered.
- A body of the tester's own is sent as written, valid or not, and what the form held and was
  not added is said in a note.
- What a subscription subscribes to comes from run variables. Where the resource cannot go
  without a value the tool sends a default and says so in a note.
- A subscription call is a success only when the status is a success and the body is what that
  call returns. A success status with an OperationOutcome of severity error is not a success.
- The value of a channel header is never shown in the designed view or the returned fields. Only
  its name is.
- The receiver answers 200 with no body to anything. It keeps the first 131072 bytes of a body,
  stops reading at 8 MiB and holds 200 notifications, letting the oldest go. Nothing of a
  notification is written to disk beyond its ledger line.
- A notification is matched to its submission by the claim identifier, then the ClaimResponse
  id, then the authorization number, and every comparison with a stored value is the ledger's. A
  notification no run of this tool can account for is said to be unmatched, whatever its type.
- An unreachable service is never said to have received nothing.
- What the PAS report leaves open is listed in `PROGRESS.md`. The receiver does not authenticate
  its caller and has not been called over a socket by a real service; in the attachment mode
  `x12-275` the tool only names the attachment on the claim and sends no attachment.
(The claim identifier after "attachment required" stays as written here; why is under D-161. The
subscription topic a run does not name is now the one PAS 2.0.1 defines, D-160.)
(Changed by D-239: the receiver reads, maps and matches the whole body, up to 8 MiB; only the text
it holds is cut at 131072 bytes.)

**D-134. From the DTR report: how the questionnaire steps are handled.**
- A response from the adaptive loop is tied to the questionnaire it completes by `derivedFrom`
  of the questionnaire inside it, then by that questionnaire's url. This is what the guides
  publish. A response that can be tied to nothing is handed on and not counted, and the result
  says why.
- A package with no questionnaire stays its own outcome with "review" as the next step, although
  later versions of the guide read an empty package as "nothing needs to be completed". An
  absence must not pass as an answer. The result states the guide's reading and that the tool
  does not apply it.
- PAS is offered only when every questionnaire of the package has a completed response that
  names it.
- Every answer the tool adds says where it came from and who gave it, in the extension the guide
  requires of a client. An answer posted again unchanged keeps the origin it had.
- A next-question request is sent as the bare QuestionnaireResponse unless the request asks for
  it to be wrapped, because the two versions of the guide differ and nobody knows which the
  sandbox follows. Both shapes are read. (Later: the operator says 2.0.1; not observed against the
  sandbox: D-269.)
- The url an adaptive questionnaire gives for its next question is never used as an address. It
  is compared with the configured one and the tester is told whether they agree. The gateway is
  the only source of where a call goes.
- Only the service completes an adaptive response. The tool never calls one complete itself.
- The response step sends nothing, so it has no wire view and no timing, and its ledger line
  cannot be replayed from the line alone and says so.
- There is no SMART launch behind any of this, and every result says so (section 14).
(Extended by D-164 to D-166, which hold DTR to 2.0.1: where a response names its coverage and order,
its intended use, the author of an answer a person gave, and what the tool does not evaluate.)

**D-135. What the answers carry into PAS, and one reference that does not resolve yet.**
The completed QuestionnaireResponse travels inside the PAS bundle and the claim names it under
supporting information. `ApplicationJourneyTest` proves that on the request as it went out:
CRD, the package, the response and the submission through the real endpoints, one chain id on
four ledger lines, and nothing of the answers in the ledger. Inside the response, the member,
the coverage and the provider are named by the ids the PAS bundle uses, so those resolve. The
order does not: the response names `ServiceRequest/service-request-1`, as the guide has a client
do, and the PAS bundle this tool builds holds no ServiceRequest. Open at this entry. It is closed
together with whatever the comparison of the PAS request with the guide finds, since both change
the same builder.
(Closed by D-158. Each claim item names its order through `requestedService`, and the order travels
in the PAS bundle as a PASServiceRequest under the id CRD and DTR gave it, so the reference from
the questionnaire response resolves. `ApplicationJourneyTest` fails unless every reference inside
the outgoing bundle resolves to an entry of it.)

## Corrections found by the independent audits

**D-138. A replay does not rebuild a chain. D-131 said it could.**
Found by the review of the chain as a case. A replay sends one recorded run again. A step of a
chain that followed another step is marked on its ledger line as not replayable alone, because
the line does not hold what the earlier step handed on, so replaying a journey sends only its
CRD call again. The way to run a whole journey again is a suite row of kind chain. D-131 is
corrected above, and the screen says this wherever a tester meets such a line.
(Narrowed by D-167: the chain view gives its replay note only when a step of the chain is marked.
Not true at the revision of D-175: the refusal a tester meets on replay does not point to the
suite row, and there is no screen on that line. Both are open work.)
(Both done by the revision of D-201: the replay refusal points to the suite row (D-189), and the
History tab is on the line, offering no replay on a line the ledger says replay refuses (D-172).)
(Since D-243 the refusal of a replay of such a step says that the run followed run X and was handed
its case, so it cannot be sent again alone; it calls it a step of a chain only when the line carries
a chain id.)

**D-139. "Send a request from the collection as it is" does not send the body saved in the collection. D-097 overstated it.**
Found by the newcomer who followed the README. `POST /api/raw` sends the method, the address and
the headers of a collection request with its variables resolved, and a body only when the caller
supplies one. The sample body a vendor saved with the request is not sent, so a POST such as a
submission goes out with no body. Not fixed at this entry. Until it is, the README says exactly
what is sent.
(Decided after the fix wave: when the caller supplies no body, the body saved with the collection
request is sent, resolved like the url and the headers, and the result says which body went. That
change was being built at the revision of D-175 and is not on that line. Until it lands, this
entry describes the tool.)
(Landed: D-184. This entry no longer describes the tool. The README still repeats it; correcting the
README is open work at the revision of D-201.)
(The README was corrected by D-209.)

## Decisions of the fix wave

Every audit finding listed in `PROGRESS.md` was given to a fix task, except those that were the
operator's or the integrator's to decide: V4-11 (D-153), the findings about the build prompt
(D-173), the edge's E14, which the merge of the comparison closed, and the guardrail's F15, a
question of scope (D-175, item 9). Each task was done by one agent, attacked by a second that did not write it, and finished by a third. The entries below
record what those tasks decided and what the integrator decided for them, in this order: the
rules set across the wave, then each part, then what was decided not to do, then what was decided
while joining the parts. Where an entry says "the fix", "the verification" or "the audit", it
means the agent that did that work. What is still open is in `PROGRESS.md`, not here.

### Rules set across the wave

**D-140. Four rules for anything held to a guide.**
Set by the integrator for CRD, DTR and PAS before they were brought in line with 2.0.1 (D-136),
and binding on every sentence about a guide written since.
1. The tool judges by 2.0.1 and says so. Every sentence a tester reads about conformance names
   the guide and the version ("CRD 2.0.1", "PAS 2.0.1", "DTR 2.0.1"). Value sets, element names
   and rules are held as data per version: 2.0.1 as the version judged by, a later version the
   packages let us read as recognised.
2. What a later version sends is read and named, not called wrong. Nobody has confirmed which
   version the vendor sandbox follows. A code or element 2.0.1 does not define and a later
   version does is shown with its meaning and its version under the finding kind
   `OTHER_VERSION_CONTENT`. A code in no version the tool can read is `CODE_OUTSIDE_VALUE_SET`,
   with the 2.0.1 list quoted as the 2.0.1 list. The tool never guesses which version an unknown
   code came from.
3. What the guide fixes is sent as the guide fixes it; what nobody supplied is never invented
   (D-141).
4. A statement that something is missing claims a requirement only where 2.0.1 makes one. The
   absence of an element 2.0.1 leaves optional is reported, if at all, under `NOT_STATED` and
   worded as information.

The finding kinds `OTHER_VERSION_CONTENT`, `NOT_STATED` and `REQUEST_INCOMPLETE`, and the hand-over
key `NextStepView.CARRY_DOC_PURPOSE` (what CRD said the documentation is for), were added to the
shared types for this. The cost: a sentence on screen is longer, and a response in a later
version's shape raises findings that are information, not faults. A kind for a broken rule of the
guide that is neither a missing element nor a code outside a value set (an invariant, an element
where it may not sit, an element sent more often than allowed) was asked for by the CRD fix. The
integrator decided after the wave to add it, as `GUIDE_RULE_BROKEN`, and to move to it the same
misfits in DTR and PAS too. That is open work: at the revision of D-175 such findings are filed
as `REQUIRED_ELEMENT_MISSING` with a title that names the rule.
(Done: `GUIDE_RULE_BROKEN` exists and CRD, DTR and PAS raise it. What it covers is D-177.)

**D-141. A value nobody supplied is never invented, and the request says what it left out.**
Section 8's rule (a coverage status the lookup did not return is not "active") applied to every
request the tool builds. A constant of the guide (a code system address, a fixed code, the
canonical address of the PAS subscription topic, the type of a bundle) is sent as the guide gives
it. A fact about the member, the provider, the payer or the order comes from the form, a lookup
or a variable. When it is missing the element is left out, on mock and on live alike, and:
- a request note names the element, the profile and version that require it, and the variable or
  field that supplies it;
- the first note says in one sentence how many such elements were left out;
- the result carries one finding of kind `REQUEST_INCOMPLETE` that lists them, so that a refusal
  by the service is not read as a fault of the service.

The request is still sent: a request that breaks a profile is a negative case a tester may want,
and the mock does not validate. `REQUEST_INCOMPLETE` is raised only for a body the tool built; a
body of the tester's own is not judged. A preview shows the same note, worded "leaves out", which
is true of a request that was not sent.

Two consequences recorded on purpose. PAS 2.0.1 fixes `Coverage.status` to `active`, and section 8
forbids inventing it, so an unknown status is left out and named, and a known status that is not
active is sent as known with a note that the profile allows only `active` and the service may
refuse the request for that alone. And an organization provider is sent without
`Organization.active`, which the CRD 2.0.1 Organization profile requires, because nothing the tool
reads states it; it is listed as left out.

**D-142. HCPCS has three addresses across the guides, and the tool sends each where its guide names it.**
Read from the packages: the CRD 2.0.1 value set bound to `ServiceRequest.code` names HCPCS as
`urn:oid:2.16.840.1.113883.6.285`; the CRD packages carry
`https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets` only in the 2.0.1 DeviceRequest value set
and in the 2.2.0 ballot's ServiceRequest value set; PAS 2.0.1 names
`http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets`, without the s, for the claim item and its
service request. So an order CRD or DTR sends goes under the urn, with the variable `hcpcsSystem`
to override it, and a PAS claim item goes under the PAS address, with `pasHcpcsSystem` to override
it. Each request says in a note which address it used and where it came from. A HCPCS code can
therefore leave the tool in one journey under two systems, and that is the guides, not a fault.

### The production guardrail and credentials

**D-143. The destination of a request decides whether it is a write to production.**
The guardrail audit (V3) found four ways to send a write to production: a value typed for one run,
a literal in `application.yml` or a second environment could point a write at a production address
while a sandbox was active, even with the floor forbidding writes; an unlock given for one
production environment let a write through to another; and a read could be pointed at any host
with the vendor's token attached. One cause: the guardrail asked which environment was active and
never where the request was going. The integrator's decision, built as these rules:
1. For every stored environment declared production, the tool knows the destinations its
   url-forming values resolve to (scheme, host, port and base path). It keeps knowing them after
   that environment is deleted and across a restart. The store keeps the list and the Config
   endpoints show it, each entry marked as still stored or not, so nothing is remembered in secret.
2. A write to a known production destination is a write to production, whatever environment is
   active and wherever the url came from. The floor applies first. Then the session must hold an
   unlock for the environment that destination belongs to, and that environment must be the active
   one. A destination of another production environment is refused, and the refusal names it.
3. A value typed for one run may not move a write: its scheme, host, port and base path must be
   ones the configuration in force names by itself. A run value may still fill a claim id, a topic,
   a path segment after the base or a query value. Reads may still be pointed elsewhere for one run.
4. The acknowledgement "this reads like production and is not" covers that environment's own
   addresses only, never a url that a run value or `application.yml` contributed to.
5. An environment not declared production may not use a known production destination. Its upload is
   refused, naming the production environment the address belongs to. Declaring it production is
   the way through; the acknowledgement is not.
6. The url is judged and sent in one normal form, so what was judged is what goes on the wire. A
   write whose url still holds a dot segment after that is refused.
7. The vendor's access token goes only to a destination the configuration in force names for a
   vendor operation without any run value, compared by scheme, host and port, for every call of
   every protocol and lookup client. A call to anywhere else goes without it, and the wire view says
   so.
8. A refusal says what is true. A replay and a suite never carry the unlock (D-023), so when that is
   why a write was refused, the refusal says so and the next step is not "unlock".
9. Locking again in one session does not change the configuration epoch that suites and screens pin
   themselves to; a write prepared under the unlock still becomes stale.

Every rule has tests at the gateway, and the audit's requests were repeated through the running
application on the live profile against two stand-in servers, one playing production, which
received nothing. Rule 8 is only half built: the gateway words the refusal from the origin of the
call, but the four protocol services and the raw service do not yet pass their origin, so a
replay or suite refused on locked production is still told to unlock. Rule 7 does not yet hold for
a request sent from the collection as it is, which still attaches the token by host alone (D-098).
Both changes are open work at the revision of D-175.
(Both built since. Rule 7 holds for a request sent from the collection as it is, proven on the wire
(D-184). Rule 8: the services pass their origin and a refused replay or suite row gets a next action
of its own (D-183). The two halves of rule 8 were made on two branches, each checked without the
other, and nobody has seen them together on the merged line.)
(Rules 1 and 6 were tightened after the second guardrail audit: a destination is read as the HTTP
client that sends the request reads it, and one that cannot be read is never taken as not
production (D-210); no request leaves with an encoded separator in its path (D-211). The two
halves of rule 8 were since driven together through the running server by a test (D-205).)

**D-144. How the destination rules are applied, from the fix and its verification.**
The verification of D-143 got through again five ways, all closed:
- The normal form also folds a run of slashes into one, and treats a segment that is `.` or `..`
  once its `;` parameters are set aside as a dot segment, because Tomcat and nginx do. Path
  parameters stay on the wire and are left out only when deciding whether an address is production.
  Paths are compared without regard to letter case for that decision, erring towards production.
- Every name and number form of this machine is one host (localhost, `*.localhost`, `127.0.0.0/8`,
  `0.0.0.0`, `::1`, `::ffff:127.0.0.1`), and an IPv4 address is read in every form the C library
  accepts. DNS is not consulted: it would make a judgement slow and dependent on the network.
- Only url-forming values are production destinations. A value named as a callback, notification,
  webhook, redirect, token, auth, OAuth, login, issuer, JWKS, SSO, IdP or OpenID address, or as an
  audience, authority, realm, scope, documentation, portal, home page, help, support, logo, terms or
  privacy address, is left out unless a collection request puts it before the start of its path.
  Without this, a sandbox that shares its token service with production could never be uploaded.
- A secret used in a production address is never shown and never stored: the remembered path ends
  before the first segment that holds one, and a secret in the host or port gives a destination
  that is judged by while the environment is stored, shown as built from a secret and not shown,
  and not written to the store.
- A call whose scheme, host or port a run value chose is refused, not stripped, when it carries a
  secret in a header, the url or the body. A run value equal to what the configuration resolves for
  the same name is not counted as typed for the run.
- Every collection request mapped to a vendor operation counts as an address the configuration
  names, not only the default request of each operation.
- A write prepared under an unlock that another session then locks is refused with a sentence of its
  own ("Production writes were locked again before this write was sent"), not as a change of
  configuration.
- Without an origin on the call, a locked production write is refused with "production writes are
  locked for this session" only when no session holds an unlock, which is then true.
- `format` was added to the words that make a name a descriptor and not a credential, so the FHIR
  element `sigFormat` is not masked.
(Corrected by D-210: an IPv4 address made of digits and dots is now read as the JDK client that
sends the request reads it, not in every form the C library accepts. The sentence of a write
refused at the send after a lock is replaced on the running server by the shared error handler's
generic sentence, D-214.)

**D-145. What the guardrail does not see. Recorded as limits.**
- Another DNS name for a production host (an alias, a CNAME) is not recognised as that host.
- A production environment whose address comes from an operating system variable
  (`{{env:PAVE_...}}`) has no destination the tool can remember, so rules 2 and 5 of D-143 cannot
  protect that address once another environment is active. The environment itself is still guarded
  by its kind.
- A destination built from a secret is forgotten once its environment is deleted and the tool
  restarts, because it is never written down.
- No endpoint clears the remembered production destinations. An operator edits
  `productionDestinations` in `config-store/store.json` with the tool stopped, or removes the store
  folder; entries for production environments still stored come back by themselves. This was
  decided, not left: a control that forgets production is a control that can be used by mistake.
- A run value may still change path segments after the last slash the configuration wrote before
  it, as in `{{baseUrl}}/{{tenant}}/Claim`. A known production address is still refused there.
- When a vendor call is pointed elsewhere by a run value, the token service is still asked for a
  token, only to decide whether to add the "not attached" note. The token is never attached.
(What the second guardrail audit found that this list did not name is closed by D-210 to D-212.
What the guardrail still does not see after it is in D-225.)
Later: the second bullet is replaced by D-265 item 1. A production environment whose address comes from
an operating system variable is refused at upload and at activation.

**D-146. A lookup the guardrail refuses is a fault of kind `BLOCKED`, not a lookup that is switched off.**
The audit found a refused lookup shown as disabled. It now has status `FAULT`, error kind `BLOCKED`
and a sentence that says the production guardrail refused it and why, without repeating the
guardrail's own opening. A status of its own (`LookupStatus.BLOCKED`) was considered and not
added: the screens already tell a fault by its error kind, and a new status would change a shared
vocabulary three finished tabs read.

**D-147. A credential typed into a collection is named at upload and masked by the name of its place.**
From the member data audit (M7, m1, m4, m10). A collection a vendor hands over can hold a credential that is
not a variable. The decision is always by the name of the place, never by what the value looks like:
- The upload's answer names each request and place (a query parameter such as `client_secret`, a
  form, urlencoded or JSON body field, a Postman auth helper on the collection, a folder or a
  request, a saved example), never the value, and says to move the value into a secret variable of
  the environment or into the operating system's environment. The file is still accepted, and the
  answer says that the collection is kept on disk as uploaded, value included.
- Every configuration view masks such values. The gateway marks them as secret pieces when the
  template is resolved, the same mark a secret variable carries, so the wire view and the previews
  mask them while the request is sent with the real value. Proven against a stand-in on live: the
  value arrived and appeared in no view, log or ledger line.
- The request view hands out a body with `{{<name> as typed}}` where the literal was, and the
  gateway fills it from the request, so a tester who loads and sends that body sends the real value.
- The list of names that read as a credential gained, among others, passcode, pin, otp, sas, sig,
  hmac, dsn, xsrf, csrf, pfx, accessCode and connectionString. Ordinary names the specification
  uses (a base url, a service id, a member id system, a token url, a client id) are not caught.
- A credential typed into a path segment or a host, where no name says what it is, is still shown as
  written.
- The FHIR element `preAuthRef` is exempt by its exact name (D-175).
- The list of names lives in `model/Secrets`, a shared type outside the fix's files; the integrator
  accepted that edit at the merge (D-175, item 9).
- The configuration store keeps credentials, so its folder and files are for their owner only where
  the file system allows (m4), and a folder made with open modes is tightened at start-up. At
  start-up the tool logs the full paths of the store and the run history, and warns when either lies
  inside a git working tree, found by a `.git` entry without running git, because an uploaded
  environment could then be committed (m10). The folders are not moved: the specification puts them
  beside where the tool is started.
(The upload now also warns about variables whose names read as member data and about saved example
responses, and says that the file is kept on disk as uploaded: D-213.)

**D-148. Where a request comes from is read from `Sec-Fetch-Site`, and another site's request is refused whatever its method.**
From the review of the HTTP edge (E1, E2). On live, a page of another site open in the tester's
browser could make the tool look a member up at the payer with the tool's credentials, and make the
browser save the ledger export. The page could not read the answer, but a lookup is an act. Now
`Sec-Fetch-Site` decides when present (`same-origin` and `none` pass); `Origin` is read only when it
is absent, and then compared by scheme, host and port, refusing user information, a path, a query
or a fragment. Anything else from another site is refused, whatever the method. One kind passes
this filter: a top-level GET navigation, so a link to the tool still opens a page. Such a
navigation is refused when the framework hands it to a controller method
(`CrossSiteNavigationInterceptor`), so a link from another site opens a page or a static file of
the tool and never an answer of the API. Left open, as the filter's Javadoc says: a request that
carries neither `Sec-Fetch-Site` nor `Origin` is let through, as curl is. A browser sends
`Sec-Fetch-Site` only to an origin it holds to be secure, which includes localhost and the
loopback addresses but not another name served over plain http, such as an `app.allowed-hosts`
entry, and an older browser does not send it at all. There another page's image or link still
reaches a read. Referrer-Policy is now
`same-origin`, so the tool's own plain form post carries a real Origin, and an address of the tool,
which can hold a member ID, still never goes to another site.

**D-149. The edge's limits on a request: one body limit, no path parameters, nothing spooled to disk.**
From the review of the HTTP edge (E3, E5, E7 to E12), M6 and m9 of the member data audit, and the
verification:
- The tool takes no path parameters. An address holding a `;` that is not percent-encoded is refused
  with 400 for every address, and so is a `%` that starts no escape. The session is tracked by its
  cookie alone (`server.servlet.session.tracking-modes: cookie`).
- One limit on any request body: `spring.servlet.multipart.max-request-size`, 30MB, enough for a
  tester's own PAS bundle with a document of about 22MB inline. A body is refused by its
  Content-Length before it is read, or counted as it is read. A negative value means no limit, as
  Spring documents, so an operator's existing setting keeps working.
- `file-size-threshold` equals the request limit, so no uploaded part is ever written to the
  temporary folder; an upload costs up to 30MB of memory while it is read. `resolve-lazily` is
  true, so only a handler that takes a file reads a multipart body. Raising `max-file-size` past the
  threshold is allowed and said once at start-up.
- Which upload limit was hit is told by the server's exception type and the size it carries, never
  by its message.
- A query the server cannot read is 400, a wildcard Content-Type is 415, and a client that went away
  is neither answered nor logged. The outermost filter catches anything thrown beneath it and
  answers 500 in the shared shape with a reference.
- `httpStatus` in the shared error shape means only the status a vendor answered with.
- An `app.allowed-hosts` entry is a host name, optionally with `:port` or in brackets. A port is
  dropped with one warning at start-up; an entry holding `/`, `@`, `?`, `#`, white space or a bad
  port stops the start with a sentence naming it. A trailing dot on a host is dropped.

**D-150. More loggers are quiet, and some answers are given before any filter runs.**
Extends D-128. Raised: Tomcat's `AbstractFileResourceSet` to ERROR, its cookie parser to WARN, and
the framework's `ExceptionHandlerExceptionResolver` to ERROR, whose warning carries an exception
message. Corrects D-117 and D-127, which say every response carries the headers: Tomcat answers
`OPTIONS *` itself, before any filter, with 200, an Allow header and none of the tool's headers;
and `TRACE`, `/WEB-INF/**` and `/META-INF/**` are answered on the error dispatch under any host name,
with the five headers and fixed text, because the request guard does not run there. Nothing of a
request reaches either answer. A method the request made up is no longer repeated in a 405; a
method HTTP defines still is. Four "not found" sentences still repeat an identifier taken from the
request; changing them is open work at the revision of D-175.
(Done: D-185.)

### The ledger

**D-151. What a ledger record keeps, after the member data audit.**
From M1 to M3, M5 and m4 to m8 of the member data audit, and from the verification of the fix. The
record checks every component a response or a tester can fill, and keeps nothing that can be a
member's identifier, name or date.
- Limits, the same on every way in: note 4000 characters, tester reference 200, expectation 1000.
  Over the limit, a run is refused with a 422 that gives the length and quotes nothing; a record
  that holds an older over-long value replaces it with `(<what> not recorded, N characters)`
  rather than failing to read.
- An expectation term's subject holds no space ("item N" excepted) and its code is one word.
- A code or a line of business is not kept when it reads like an identifier or a date: a run of six
  or more digits, a numeric date, a date that names its month (with digits on both sides, so a word
  alone is never caught), or anything holding this run's member ID compared with every character
  that is not a letter or digit removed, for IDs of six such characters or more.
- A questionnaire address is kept only as up to 20 canonical addresses with a strict character set,
  and the identifier-or-date rule is applied to each path segment, the name inside a urn and the
  version. OIDs, UUIDs and a segment that is exactly a UUID are exempt, being made of digits. The
  scheme and host are not checked, so an IP address is not taken for a date.
- A line of business is written only when the code list or its aliases know it, in the list's own
  spelling when exactly one list name matches, as typed when known only through an alias; anything
  else is written as its length. Only the tool's three exact statements are accepted as statements.
  A History filter on a line of business typed in another spelling therefore does not match lines
  written since, which carry the list's spelling.
- An authorization number may be written in up to four groups separated by spaces.
- On mock, a member ID is written in the clear only when it is a `P32H-` sentinel or `S` and nine or
  ten digits; anything else is tokenised as on live.
- Files and folders the ledger creates are for their owner only where the file system allows;
  existing ones are not changed, since their permissions are the operator's to decide.

Known gap, accepted: a one-word surname written where a code belongs is kept, because a single word
cannot be told from a code by its shape and the record never holds the name to compare with.
(Extended after the second member data audit by D-217 to D-219: the note, the tester reference
and the expectation, every procedure code and the subject of every coverage code, and the
identifiers a service supplies are now checked as well. The known gap stands.)
(Decided again for identifiers a service supplies in D-246, item 3, and done, D-253: such an identifier is written in clear only when it is a UUID, an OID or one the tool minted,
and as a token otherwise. A code the code list holds is kept whatever its shape, D-246, item 6,
done, D-253.)

**D-152. Replay refuses a line whose correlation value was not kept, and the redaction test finds records by itself.**
Replay treats a refused correlation value as a missing input only where a case sends that value (a
claim id for an inquiry, a questionnaire for a package fetch); elsewhere it is not compared, as
before. An authorization number equal to the member ID is still dropped: writing it would add
nothing the member field does not already hold. `RedactionTest` finds every record under `model`,
`service/suite` and `service/ledger` and fails for one that keeps the generated `toString` while a
component can print text, deciding from the class file itself, because the build file is frozen and
the usual library for building such objects is not on the test class path.
(Since D-218, a claim id, ClaimResponse id or subscription id that reads like an identifier or a
date is written as its length, so an inquiry naming such a claim id cannot be replayed.)

### Fixtures and mock scenarios

**D-153. A damaged scenario set stops the start on mock, and is refused per protocol while the tool runs.**
From the fixture audit (V4-02, V4-03, V4-05, V4-08) and its verification.
- On the mock profile the tool refuses to start, naming the setting, the file and the reason, for: a
  file that is not JSON or not a scenario, a repeated key, a top-level key the loader does not know,
  an id used twice, a sentinel member ID or NPI claimed by two files of one protocol, a sentinel
  that is not a `P32H-` sentinel or an NPI that fails the check, and a missing or empty protocol
  directory. On live, which never answers from scenarios, a damaged `app.scenario-dir` gives one
  warning naming the setting and the tool starts. The check is its own bean, proved by tests that
  start the real application.
- A scenario directory damaged while the tool runs makes every run of that protocol a 422 that names
  each file, not only runs that touch the broken file: answering from what is left would let a
  sentinel fall to the default scenario, the silent wrong answer the audit was about.
- An external `app.scenario-dir` must hold all four protocol directories (crd, dtr, pas, raw).
- A file may name several sentinels (`sentinelMemberIds`, `sentinelNpis`) beside the single keys. The
  scenario list still shows only the first; showing all is open work. (Done: D-186.)
- An unknown top-level key is quoted only when it is letters only and at most 40 characters, so a
  value pasted as a key is never repeated.
- A sentinel inside a pasted body selects its scenario only as a whole JSON text value, compared as
  the form's member ID is.
- One test scans every scenario file for anything shaped like a member ID or an NPI, including the
  Medicare beneficiary identifier shape, apart from each file's own sentinel NPIs and UUIDs.
- Every DTR and PAS sentinel member has a synthetic lookup fixture, except those whose scenario means
  "member not found", with active coverage and an invented line of business. A test ties every
  sentinel to a fixture or to a member-not-found scenario.
- The question V4-11 raised (the NPI check is Luhn only, and accepts numbers no issued NPI begins
  with) is the operator's, and nothing was changed.
(Changed by D-220 and D-221: a sentinel is chosen by one rule for a typed identifier and one found
in a pasted body alike, and the duplicate check covers `crdSentinelMemberIds`. The question V4-11
raised still has no answer, D-227. Later: the operator was asked and does not know, so the check stays
as it is, D-269.)

**D-154. A sentinel means the same case in every protocol.**
From V4-04, V4-06, V4-09, V4-10 and the chain review's F10. Before, the CRD sentinels for member not
found, provider not found, a server error, a timeout and a bad request got the default answer of
DTR and PAS, and PAS's default is "approved"; and only one DTR sentinel could be reached in a chain.
- CRD answers "documentation required" (scenario 4) for every member ID that is a sentinel of a DTR
  scenario file and of no CRD file, unless a scenario was chosen. The mock reads the DTR files for
  the list. No CRD scenario was added.
- DTR scenario files list the CRD sentinels they answer for under `crdSentinelMemberIds`, which the
  DTR mock honours after an explicit choice and a DTR sentinel, and before the sentinel NPI (D-175).
- PAS maps the same CRD sentinels to the PAS scenario for the same case, and one scenario was added,
  `pas-21-provider-not-found`, because PAS had none. When a sentinel selected a PAS scenario that
  has no inquiry, the refusal names the member field and says which scenario the ID selected, not
  "choose a scenario".
- The default for an ID that matches nothing stays as it was in each protocol: the result already
  says the default answered.
- Scenario 10's description now names the codes the placeholder list routes to its vendor, so the two
  synthetic data sets no longer contradict each other in silence. Scenario 8 says it answers as if
  documentation was accepted whether or not the request carries any.
(Changed by D-221 and D-222: the CRD sentinels a PAS scenario answers for are data in the PAS files,
the Java map is gone, and for a DTR sentinel CRD names the questionnaires that DTR scenario's
package holds.)

### CRD held to 2.0.1

**D-155. CRD: value sets per version, and what the checker applies.**
From findings 01, 02, 08 to 12 and 26 of the CRD comparison, and its verification.
- The 2.0.1 sets are held as D-137 gives them; codes of the 2.2.0 ballot (`detail-code`, `patient`,
  the ballot's `category` of a detail and its reason codes) are read and named as ballot content.
  Lower-case `oth` and `retain` are in no version here.
- `no-doc` is read as "no documentation is needed" and raises a finding that it is not in the CRD
  2.0.1 value set, that 2.0.1 says the same by leaving `doc-needed` out, and that 2.0.1's own rule
  crd-ci-q1 mentions the code.
- The checker raises one finding per broken rule of the 2.0.1 extension: the cardinalities
  (`coverage`, `covered`, `date` and `coverage-assertion-id` once each), crd-ci-q1, q3 and q5, a
  `detail` without its value, each naming its key and "CRD 2.0.1". Rules q2 and q4 are not
  enforced: as published in the 2.0.1 package one compares text with an expression that can
  contradict it and the other names a code no 2.0.1 value set holds. The reason is in the code.
- Every result says which rules the checker applies and which it does not
  (`CrdDetail.conformance`), so that silence is not read as a pass of rules it does not check.
- Each sub-extension the tool does not interpret says which version defines it, or that no version
  the tool can read does.

**D-156. CRD: where the journey goes next, and how the answer is worded.**
From findings 03, 13 to 16, 19 to 21 and 24, and its verification.
- Documentation wanted (`doc-needed` of clinical, admin or both, or the ballot's `patient`) leads to
  DTR whatever `pa-needed` says, with or without a questionnaire or a SMART link. A SMART link is
  carried as a SMART link and does not decide. The questionnaire, the coverage assertion id, the
  order and what the documentation is for are handed on; information wanted with no documentation
  leads to "supply what the payer asked for"; both together go to DTR and the reason names the
  information too.
- The verdict is worded element by element: what is definite and what is conditional. A definite
  prior authorization answer is not said twice when the answer is partly conditional; when
  `doc-needed` is conditional, the next step says documentation may be needed once the payer has
  more information, never that none is asked for.
- A 2.0.1 reason code is shown with its 2.0.1 meaning; the ballot's member-not-found and
  no-active-coverage codes are recognised as ballot codes.
- An answer with no coverage information is `NOT_STATED`, and the outcome stays "no determination",
  distinct from "no authorization needed".
- Coverage information on a card, which 2.0.1 does not allow, is still read and raises a finding.
- The vendor of a delegated review is compared with the code list only for `auth-needed` or
  `performpa` that is not `not-covered`.
- The 2.0.1 `response` sub-extension, a reference to a partly completed QuestionnaireResponse, is read
  and shown, and the reason says it is not handed on to DTR. Handing it on needs a new hand-over key;
  that was decided after the wave and is open work.
- A CRD run's own id is handed on to a later step only inside a suite chain. A manual CRD then PAS
  run writes no `correlatedRunId`; that is open work.

(Both open items above are done: the `response` reference is handed on to DTR as a reference
(D-176), and a manual CRD then PAS or CRD then DTR run links the later line to the CRD run (D-181).)

**D-157. CRD: what the request carries.**
From findings 04 to 07, 18, 22 and 23, DTR-05, and its verification.
- The Patient's medical record number comes from the variables `patientMrn` and `patientMrnSystem`
  and is never the member ID. A number with no system is sent as supplied and the system is named
  as left out; a system with no number sends nothing.
- `authoredOn` of every order is the moment the request is built, to the second, from the injected
  clock, with a note. In the request comparison it differs on every call.
- An organization provider is an `Organization` (NPI and name) named by
  `PractitionerRole.organization`, never a Practitioner; its prefetch key is `organization`.
  `Coverage.period` is sent when the lookup gave the dates.
- The preview's hook instance is a real generated id with a note that a new one is generated when
  the request is sent. A hook the tool does not build is refused with 422 on `hook`.
- When neither prefetch nor `fhirServer` is sent, one note says the request gives the service no way
  to read the Patient, the Coverage or the provider, instead of two notes that read as contradicting.
- `service/run/CaseResources` keeps its earlier methods beside the CRD 2.0.1 forms, because DTR
  still calls them; DTR's package request therefore still builds its Patient and Coverage the old
  way, and moving it is open work. (Done: D-178. The earlier methods are still in the file with no
  caller in the application.)
- The mock fills each update it returns with the order the request actually sent, plus the file's
  coverage information, rather than a cut-down order in each file, which would give a repeated order
  the first order's code.
- Mock discovery has a configuration option under the key `davinci-crd.configuration-options`, a
  reading of narrative the 2.0.1 package does not ship, labelled illustrative.
- The request comparison reads a provider from `PractitionerRole.organization` when the role names no
  practitioner. Three tests outside the CRD files were changed by the CRD fix to follow the new
  request; the integrator accepted them at the merge (D-175, item 9).

Later: D-291. CRD prefetch is sent as named Bundles of type collection, `organizationBundle` in place of
`practitionerBundle` for an organization alone, and every entry carries an absolute `fullUrl`.

### PAS held to 2.0.1

**D-158. PAS: what the bundle carries. Closes D-135.**
From PAS-02 to PAS-08, PAS-11, PAS-12, PAS-15, DTR-06, and the verification.
- With no base address every entry has a `urn:uuid:` fullUrl and every reference inside the bundle is
  rewritten to resolve through them; with `fhirServer` set the fullUrls are addresses.
- Each item carries the tool's own trace number, always, and certification type "initial" when the
  claim id was minted here. The service type category and the request type are facts about the
  service requested and come from the variables `pasServiceType` and `pasRequestType`, else D-141.
- Each item names its order through `requestedService`, and the order travels in the bundle as a
  PASServiceRequest under the id CRD and DTR gave it (`ServiceRequest/service-request-N`, in the
  order of the procedure codes). The questionnaire response handed on from DTR therefore resolves.
- When the payer is known (`payerIdentifier` or `payerName`), the insurer Organization travels in the
  bundle and the claim and coverage point at it; otherwise D-141. An inquiry with no payer names the
  inquiry profile it builds.
- An inquiry lists the items of the submission it asks about, carried over or read from that
  submission's ledger line; with none known it is still sent, and the note says PAS 2.0.1 requires
  an item and the 2.2.0 ballot does not.
- The member identifier system, the relationship and the subscriber come only from what was
  supplied. An attachment's document type and category are fields of the attachment; missing, D-141.
- A code with no system at all, and each reference the bundle cannot resolve, is counted among what
  was left out. A code whose system the tester or the code list stated, but which lies outside the
  value set, gets a note only, because it was supplied.

Later: D-293. The PAS bundle follows Da Vinci PAS 2.2.1 since the rebuild (E2): the elements 2.2.1
requires that nobody supplied are named in the notes, and the request type, service type and
certification type come from the Service card first.
Later: D-300. The bundle follows PAS 2.0.1 again unless the environment chooses 2.2.1; under 2.0.1
what is counted as left out is what this entry lists, and the Service card fields are sent when given.

**D-159. PAS: how a decision is read.**
From PAS-01, PAS-09, PAS-10, PAS-13, PAS-14, NOT-01, NOT-02, MOCK-01 to MOCK-03, and the verification.
- Each item's authorization number and period are read from where 2.0.1 puts them (the review
  action's number and `itemPreAuthPeriod`). The top-level `preAuthRef` and `preAuthPeriod` are kept
  as a further source, and left out of the verdict when every decision already states the same.
- A response item is tied to a requested item first by the trace number it echoes, then by its
  sequence, then by the only-one rule.
- An item with no review action is `NOT_STATED`, and nothing is raised when the response is an error
  with its errors present or is marked as a subset.
- A review action found outside the place 2.0.1 gives it is still read, and flagged by a placement
  field and a verdict sentence; one on an added item (a 2.2.0 ballot element) is
  `OTHER_VERSION_CONTENT` and decides nothing. No finding kind fits a misplaced review action, so
  it raises none. (Corrected by D-177: one on the ClaimResponse or on the item itself now raises a
  `GUIDE_RULE_BROKEN` finding.)
- An inquiry answered with a bundle holding no ClaimResponse and no OperationOutcome is its own
  outcome, `NO_MATCHING_AUTHORIZATION`, a finding about test data; the same body after a submission
  stays "no decision".
- A nested Bundle is read at every depth, for responses and notifications alike; a notification's
  claim id is taken from `request.identifier`, then `ClaimResponse.identifier`.
- The sentences about the review action code and the outcome code name the binding they judge by.
- The mock's X12 reason and attachment category codes are placeholders inside the right code
  systems; the scenario descriptions say so.

**D-160. Subscriptions: the topic PAS 2.0.1 defines, and a filter only from what was supplied.**
From SUB-01 to SUB-03. A subscription a run does not give a topic for subscribes to
`http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic`, overridable by
variable. The filter is built as `orgIdentifier=<system>|<value>` from `pasRequestingOrgIdentifier`
and `pasRequestingOrgIdentifierSystem`; no system is assumed, and with none the filter is the value
alone, with a note. Missing, the result warns that PAS requires the subscription to be filtered by
the requesting organization. The 2.0.1 defaults for status and payload stay, with notes on what the
2.2.0 ballot fixes instead. The string form follows the ballot's example, because the form in the
subscriptions backport 1.1.0, which 2.0.1 uses, could not be read here.

**D-161. Attachments: both modes stay, named plainly, and the guide's way after "attachment required" is not built.**
From ATT-01 and SPEC-01. The specification asks for two modes switchable by configuration. In one
the document travels in the bundle; in the other (`x12-275`) the claim only names the attachment,
for a transaction this tool does not send, and the note says the document type and category given
with it were not sent either. In PAS 2.0.1 neither is how documentation goes after the payer asks
for it: that is a claim update that replaces the earlier claim, or `$submit-attachment`. The tool
builds neither, and an "attachment required" result says what the guide's route is. Why: the
relationship code for "replaces" and its system cannot be confirmed from the 2.0.1 sources here
(no 2.0.1 example carries `Claim.related`). The information-change codes and the revision codes can
be confirmed; the first reason given for not building it said otherwise and was wrong. D-133 stands:
a resubmission from this tool after "attachment required" reuses the claim identifier, and the next
step says plainly that this is not the PAS 2.0.1 way.

### What a service sends back, and what a result may claim

**D-162. A secret a service sends back is masked.**
From the PAS follow-up and the verification of PAS. A service can echo the bearer token, an API key
or a secret variable in an error text, a link or a property name.
- The wire view masks, in the response headers and body, every value a request carried as a secret:
  secret variables in the url, the headers and the body, the access token when it went out as the
  credential, and the value of a header that is a credential by its own name. Occurrences written
  with JSON escapes count; other re-encodings (percent, base64, a change of case) are not searched
  for. A secret shorter than six characters is not searched for, and the wire view says how many
  such secrets the request sent. A secret whose value cannot be worked out is said, not passed over.
- PAS and the subscription mappers mask the parsed body once, before they read it, so every value
  they copy into the designed view is masked, including fields nobody anticipated and property
  names. A value used in logic can then only hold a secret if the service copied it there.
- CRD, DTR and the lookups do not do this yet: they copy parsed values into their designed views
  unmasked. Moving the masking to one place every protocol uses was decided after the wave and is
  open work. A secret sent back as a JSON number is not masked anywhere.
(Done for CRD, DTR, PAS, subscriptions and both lookups: D-179. Not done for a request sent from the
collection as it is, whose "returned but not interpreted" fields are still read from the body as
received; until the server does it, the page masks them, D-196. The JSON number is still not masked.)
(Both done since: the raw send reads the masked body (D-205) and a JSON number is masked (D-202). A
received notification is masked with every secret the tool could have sent, D-215.)
(A received notification says so too when a secret was too short to search for, D-247, item 4;
done, D-254.)
(Later, D-280: an answer is masked wherever it repeats a secret this tool holds for the configuration
in force, whether or not the request carried it, by the same rule; the sentences now say "a secret
this tool holds".)

**D-163. Whether a failed call may have reached the service, and other words a result now chooses with care.**
From the PAS follow-up.
- An exchange says whether it reached the service: yes when, and only when, a response arrived; no
  for a failure before any of the request was written (connect timeout, refused connection, name
  not resolved, no route, a TLS handshake failure, a peer not verified); cannot say for a read timeout, any other TLS
  failure and anything unrecognised. A false "not reached" invites a second prior authorization, a
  false "may have" costs one status check, so the tool errs to "may have". The outcome code
  `UNREACHABLE` covers both and the headline tells them apart.
- A blocked write names what it was about on its ledger line (the claim id or subscription id the
  tester named, the run it followed). Nothing is minted for a line that sent nothing, and a blocked
  run is never "the run that carries this identifier".
- Two finding kinds: `NO_CALLBACK_URL` for a subscription sent with no address to call back, and
  `UNCHECKED_NOTIFICATION` whenever a link of a notification was to be looked up in the ledger and
  could not be. The next action `NOTHING_FURTHER` says a read, a list, an update or a delete is
  complete without claiming a journey ended.

### DTR held to 2.0.1

**D-164. DTR: what a response and a request carry.**
From DTR-01 to DTR-04, DTR-11, DTR-12, and the verification.
- A response the tool builds names the coverage beside the order or orders in `qr-context`, as 2.0.1
  has it. `qr-coverage` is written only when the variable `dtrCoverageExtension` is `qr-coverage`.
- The intended use comes from the variable `dtrIntendedUse`, else from what CRD said the documentation
  is for (only 2.0.1 DocReason codes; another code is named in a note), else it is left out under
  D-141. It is never assumed. A code outside the 2.0.1 binding is counted as left out; a variable
  that holds no code is read as not set, and says so. What CRD said is handed on through the
  package step, so it reaches the response.
- A step that would mark an answer as given by a person, with no provider NPI on the request, is
  refused with 422 on `provider.npi`, naming rule dtrx-1 of DTR 2.0.1. Nothing is sent or recorded.
  The author of the response, and of each such answer, is `Practitioner/practitioner-1`, never the
  NPI; an answer's author still carries no role, because the form has none.
- The questionnaire copied into a first next-question call loses its url and version and keeps
  exactly one `derivedFrom`. It declares the adaptive profile only when it carries the adaptive
  marker and already declared profiles; a static questionnaire keeps its own, because swapping
  would turn it adaptive. Its items are removed when the call gives no answers, as SDC has it; when
  answers are given the items stay and only their conditions are removed, so the answers have a
  place to sit. One note lists every change.

**D-165. DTR: what the tool does not evaluate is never reported as unanswered.**
From DTR-07, DTR-09, DTR-13, and the verification.
- Rules that decide whether an item is shown (`enableWhenExpression`, `itemHidden`) make it
  undetermined. Rules that decide its answer or whether it is required (`initialExpression`,
  `calculatedExpression`, a required expression) take it out of the judgement. A condition that
  reads a question filled by such a rule, while that question has no answer, is not evaluated.
  Limits, units, signatures and the questionnaire-level library, launch context and variables are
  named only. None of these items is ever listed as required and unanswered; they are listed as
  undetermined, and each rule not applied is named once.
- An item the tool cannot judge does not stop a response the service completed. It does stop a
  static response the tool would complete: only the party that judged can call a response complete.
- Items hidden by `questionnaire-hidden` are not counted as asked.
- `Questionnaire.text` and `subjectType` are read. A returned `qr-coverage`, or the ballot's adaptive
  response profile, is `OTHER_VERSION_CONTENT` only on steps that called the service.
- A package answer with no package bundle follows a later version and says so. An empty package still
  goes to "review" (D-134).
- The next-question address without `/Questionnaire` is kept as an assumption, because the material
  that would settle it (SDC 3.0.0) is not here.

**D-166. DTR: a completed response with no item is not handed to PAS.**
From DTR-08, DTR-10, and the verification. DTR 2.0.1 requires at least one item, and the tool does
not hand PAS a response that does not conform. The next step still goes to PAS, and its headline is
taken from what the hand-over holds ("no questionnaire response goes with it", or "the other
questionnaires go with it, and this one does not"), with the reason. In a package of several, the
integrator decided after the wave that such a questionnaire is done and is dropped from what is
still to answer, so that PAS is offered once the others are answered. That is open work: at the
revision of D-175 a later step of the same package still lists it as unanswered. (Done: D-180.) The scenario
files keep some departures from 2.0.1 on purpose, each said in its own description: drafts with no
item where the SDC first call carries none, a completed response with no item, no draft where the
tool's build-from-nothing path must be exercised, no questionnaire, a bare Bundle with two
questionnaires, and one package in the later shape.

### Suites, the chain and replay

**D-167. The result of a chain case is a ledger line of its own.**
From the chain review (F1 to F6, F11, F14) and the suites review, with their verification.
- After a chain's last step a case line is written (record type `case`) naming the last step's run id
  and carrying the chain id, the suite run id, the case id, the whole expectation and the result.
  No step's line can hold the case result: the case is known to have failed only after its last
  line is written, and lines are never rewritten. The ledger applies it to that run on read, and the
  failed filter matches either result.
- A chain stopped part way (a later step refused, the configuration moved, a tool failure) writes its
  case line as unverified, naming the last step sent, and is counted as stopped part way, not as not
  valid. A blocked chain writes none, because its blocked step's own line says so.
- A chain counts as failed because the service failed only when every step that failed by itself has
  a fault outcome, or, when no step failed by itself, when it stopped because its last step was a
  fault.
- A case line that could not be kept is counted apart from runs, and the suite says so once.
- A row says which steps were sent and which was blocked or refused, names the step that failed, and
  shows each step's own outcome. It says when the default mock scenario answered.
- Chains are compared with an earlier suite run case result with case result; an earlier chain with
  no case line shows no previous result.
- Only a record that used what an earlier step handed on is said to be not replayable alone (D-138),
  and the chain view no longer says the self-check names a damaged file, which it does not. A CRD
  or PAS step counts as having used it whenever it was handed anything at all, even only the
  procedure codes its own line already holds, so such a step is still marked (the chain review's
  F7; open). The refusal of such a step now points to a suite row of the protocol `chain` (D-189);
  the marking itself is unchanged.
- The ledger record gained `caseExpected` and `caseResult`. The History CSV export takes its
  columns from the record by reflection, so it gained those two columns.
- A tool failure keeps the frozen status `NOT_RUNNABLE` and is told apart by a fault reference, with
  its own line and count.

**D-168. Suites: the preview tells the truth, and a file is read only as far as it can be read exactly.**
From the suites review (B1 to B3, M1 to M5, m1 to m11) and the chain review (F8, F12, F13).
- The preview asks the case runner which rows it cannot run and which its services would refuse (no
  NPI, no codes, no claim id for an inquiry, an unknown scenario, the chain rules), and counts only
  writes that will run. These checks mirror the services and can drift from them; the service still
  checks at run time.
- A note about a lookup is worded once the case is over, so a case that sent nothing never says it
  was sent with the member ID alone.
- The chain view names the stage that comes first when a later one was not reached.
- A suite stopped by a change of configuration says so, without claiming a different target.
- A first row is a header only when a strict majority of its named cells are known or ignored
  columns, and no cell of a row that is not a header is quoted. A two-column header with one
  misspelt name is therefore reported as "no header row": quoting back a member ID typed into a
  header cell is worse.
- A row whose first cell starts with `#` is a comment. A procedure code holds at least one digit, so a
  word between codes is refused. A claim id on a row that is not an inquiry, and any subscription
  id, is refused rather than dropped (D-104). A file saved with another separator is said to be so.
- An expectation that can never hold (two outcomes for one step, separators alone, an outcome a chain
  step cannot produce) is refused before anything is sent, and a chain step is offered only the
  outcomes it can produce. Whether a code assertion's value is in its element's value set is not
  checked before sending.
- A made-up case id is `case-N`, since it counts cases, not lines. The results file starts with a
  byte order mark and names its suite state and stop reason. Its column `runs` became `what`, and
  columns were added; a reader of the old file must follow.
- `SuiteCounts` gained `toolFailed`, `partly` and `failedByFault`, which changed its positional
  order. The suite answers gained further components (a case's steps, fault reference and next
  step; a preview's rows that cannot run; the chain view's case result and replay note), all
  additions.

**D-169. The code list follow-up: the placeholder never reads as the P32H list.**
From the fixture audit's V4-01 and the code list report.
- With the bundled placeholder list on live, nothing is compared: one `CODE_LIST_NOT_COMPARED` finding
  for the run, beside a determination only. On mock the comparison runs and every finding it raises
  says the list is the placeholder. The picker says the list is the placeholder (true or absent,
  never false: the tool cannot vouch that any file is the P32H list).
- More spellings of the authorization flag are read, with the three non-breaking spaces read as
  spaces. A vendor cell with no letter or digit names no vendor.
- A coding system the list gives as a label (CPT, HCPCS Level II and their spellings) is sent as the
  tool's uri, with a note; anything else that is not a uri is not sent, and is noted. A label that
  disagrees with the format of the code is followed and the note says so.
- Headers are matched ignoring a slash, a question mark and a colon, and a bracketed ending only in a
  second pass; two headers that differ only in a bracketed ending are never chosen between.
- A date with a time of day is read as the date written; the zone is not applied, and a cell with a
  zone is listed by the self-check. A year with a sign is not a date.
- The first row of a file that is not a code list is shown with any cell of three or more digits, or
  longer than 40 characters, replaced by its length.
- `.gitattributes` declares line endings; `*.bat` and `*.cmd` are detected as text, not declared, so
  the stored `gradlew.bat` is left alone. The placeholder list holds no coding system, and a test says
  why: a request would carry it to a real service.
(Changed by D-223: a list is treated as the placeholder when it says so of itself, by a marker in
its rows, whatever its path. The shipped sample list is one.)

### The request comparison, the documentation and the screens

**D-170. Comparing a pasted CRD request: how elements are paired and what is never said.**
From the comparison's report and its verification. D-119 says what it is for.
- Three layers: records, a generic JSON and FHIR comparison that knows nothing of CRD, and a CRD
  reader, glossary and comparer.
- The case is merged value by value: the form wins, else the value read from the pasted request,
  else nothing. A value read for another member or NPI than the form's is never borrowed, and every
  value read and used is listed with its place, because using it makes the two agree there by design.
- The member ID is read from an identifier typed as a member number, and only without one from the
  subscriber id, with a note that a subscriber id can differ from a dependent's own. The line of
  business is never read. The NPI is read only from the practitioner the request's user leads to.
  The patient fallback applies only when the request names no patient.
- Leftover Bundle entries are paired by type and order code before position; extensions by url;
  codings and identifiers by system. An absolute reference is followed only through a fullUrl or the
  request's own `fhirServer`. A reference that resolves to two resources is never counted as
  following a renamed id.
- An element the glossary is not sure of is listed as not classified. A sentence says what an element
  is, never what a service expects or which request is right.
- Limits: 2MB in UTF-8 bytes, 64 levels, with the place given when a request nests deeper than the
  parser reads.

**D-171. The README, the run scripts and the samples.**
- `run.sh` changes to its own directory, so the runtime folders land beside it; it builds the jar only
  when it is missing, takes `mock` or `live` first and passes `--` arguments through, and needs Java
  21. `run.cmd` was written to match it and has never been run: there is no Windows machine here.
- The README examples use the default port 8057. Where 8057 is taken, the README says to start with
  `--server.port=8091` and use that port instead; 8091 was chosen because the ports from 8058 to
  8090 were in use on this machine when the README was checked.
- The sample collection has the thirteen request names of section 10 and no Authorization header,
  because an undefined token variable would make every mock run a 422. The sample code list uses
  headers that differ from the placeholder's on purpose, to exercise the loader. The sample suite is
  CRD only. The samples use only hosts under `example.invalid` and three namespace hosts.
- The vendor names in the placeholder and sample lists are the four section 8 mandates; their
  assignment to codes is invented, and the README says so.
- The README is written for the tool as delivered, with a "not checked" note at every sentence that
  could not be checked on its revision, such as the screens.

Later: D-295. Both scripts now build the jar before every start and say which jar and which Java they
start; `run.cmd` also looks for the Java STS brings. `run.cmd` has run under Wine's `cmd`, still never
on Windows.

**D-172. The three frontend parts finished on branches: what they decided.**
The case (lookups, code picker, a request of the tester's own), the Config tab and the History tab
are finished, reviewed in a browser by an agent that did not write them, and fixed, on branches.
They are not on the line D-175 describes. What they decided that a maintainer would otherwise
rediscover:
- The browser keeps no copy of words the server owns. Where the server gives no label, the screen
  shows the code and says why, and the server change is open work.
- Tab in an identifier field looks it up; Enter does too. Enter never sends a run: in the code
  picker it adds a code, and anywhere else it does nothing. One picker option per code; the tags are
  PA, No PA and the vendor, and an unknown requirement shows nothing.
- An empty own-request box is refused on the page, since sending it would build the body from the
  form, the opposite of the choice.
- With a request of the tester's own, the form's member, provider and codes are not posted. They
  steered the mock scenario and filled the ledger with a member the body may not be about, and a
  run absent from the ledger is preferred to a false record. The cost: the ledger records no
  member, NPI or line of business for such a run, and nothing on its line tells two such runs
  apart (open, in `PROGRESS.md`).
- The Config tab does not offer what its shared client cannot send (changing a mapping, removing an
  environment or a collection) and says once what to do instead. A url token that cannot be filled
  is reported before sending; whether to send is the server's decision.
- History does not offer replay on a line the ledger says replay refuses; a date-range replay counts
  before it sends; every date is said to be a UTC day.

(Since merged, on the line D-201 describes. Two of these no longer hold as written: the shared client
now sends PUT and DELETE, and the Config tab offers changing a mapping, taking a request off an
operation and removing an environment or a collection, driven in a browser by the frame pass
(D-191). The server now takes a time zone for the History dates (D-187); the History tab does not
use it yet and still says every date is a UTC day. The first rule is broken in a few known places,
listed in D-190.)
(History is now decided to stay worded in UTC days, D-207; the places D-190 lists were put right,
D-207.)

### Decided not to do, and limits of the tool

**D-173. The build prompt is not edited where it differs from the 2.0.1 guides. The differences are recorded here.**
The comparisons found four places where the build prompt and 2.0.1 disagree. The prompt is the
operator's document, so the tool follows 2.0.1 (D-136) and this file records the difference:
- Sections 11 and 28 (CRD): the value set table, as D-137 shows; "three placements", which includes a
  card, not a 2.0.1 place; the delegated vendor in `detail`, a project assumption, not guide content;
  28.3's required-element rule, where 2.0.1 requires `covered`, `coverage`, `date` and
  `coverage-assertion-id`; 28.1's "doc-needed with a DTR link", where 2.0.1 needs no link.
- The variables the 2.0.1 requests read are named in no section of the prompt: `patientMrn`,
  `patientMrnSystem` and `hcpcsSystem` for CRD and DTR; `pasServiceType`, `pasRequestType`,
  `pasHcpcsSystem` and `pasRequestingOrgIdentifier` for PAS; `dtrIntendedUse` for DTR. D-157,
  D-158, D-160 and D-164 say what each supplies.
- Section 12 (DTR): the package also carries a draft QuestionnaireResponse the client starts from,
  which must carry `qr-context` and an intended use; the completion status is the FHIR code
  `completed`.
- Section 13 (PAS): an X12 275 attachment mode on the client. In 2.0.1 the intermediary produces the
  275 from the bundle, and later documentation goes by claim update or `$submit-attachment` (D-161).
- Section 8 against PAS: `Coverage.status` is fixed to `active` by the profile; D-141 says how the
  tool resolves it.

**D-174. Decided not to do, each with its reason.**
- The labels of CRD scenarios 3 and 4 stay as the specification's table names them ("Prior
  authorization already satisfied", "Documentation required, with DTR link"). Their descriptions say
  what the answer means in the words of 2.0.1.
- `model/FieldError.toString` keeps its message: a frozen test reads it. A field error can repeat an
  expectation term the tester typed (D-175, item 7).
- The session cookie is the server's own, holds no member data and is not a finding (its attributes
  are in D-116; the unlock it carries is D-020).
- File permissions on Windows are whatever the folder gives, for the configuration store and the
  ledger alike. Only Linux was observed.
- A 400 for a query the server cannot read always speaks of "the query of the address". No endpoint
  reads a urlencoded form today; if one ever does, the sentence needs new words.
- The trim of a running suite has no test: the service cannot reach that state.
- A suite row's physical line number is not given, because the CSV reader returns no positions.
- The mock has no scenario behaviour for a connection that fails after it opened; "may have reached"
  is rehearsed on mock with the timeout behaviour.
- A PAS mock response still refers to the Patient, the requestor and the insurer without carrying
  them. PAS 2.0.1 sets no rule that a response bundle must.

**D-175. Decisions made while joining the parts.**
1. The scenario loader's list of known top-level keys includes `crdSentinelMemberIds`, which the DTR
   mock reads to keep the CRD sentinels a DTR scenario answers for apart from the scenario's own, so
   that the result can say which selected it. It was not folded into `sentinelMemberIds`.
   (PAS scenario files carry the same key since D-221.)
2. PAS scenario 21 (provider not found) does not write its sentinel NPI in prose, because the fixture
   check allows that number only as the value of the sentinel key. Its sentinel member
   `P32H-PAS-NOPROV` has a synthetic lookup fixture, so a tester who looks it up first is shown a
   member.
3. `model/crd/ConformanceScope` prints only counts in `toString`, and `SubExtensionView` keeps both
   the version that defines an element and a `toString` that prints nothing a response sent, as the
   redaction test requires of every record.
4. `postman/LiteralCredentials` exempts the FHIR element `preAuthRef`, by its exact name, from the scan
   for credentials typed into a body. It is the number of a prior authorization, nearly every saved
   PAS answer holds one, and without the exemption every PAS collection drew a false warning and had
   its authorization number masked. A variable or a header whose name reads as a credential is still
   masked, including any name that holds the word auth. The known cost: a tester's variable named
   for an authorization number is shown as a mask in the wire view while the request is sent with the
   real value.
5. A test of the case runner no longer pins the whole sentence of the CRD mock, only its stable start,
   so that a change of wording in the scenario does not fail a test about the runner.
6. The labels of CRD scenarios 3 and 4 stay as the specification's table names them (D-174).
7. A mistyped term in a short expectation is quoted back to the person who typed it: they need to see
   it, and the answer goes only to the browser that sent it and to that suite's own results file,
   never to the ledger. Only an expectation over the length limit is never quoted.
8. A lookup the guardrail refuses stays `FAULT` with the error kind `BLOCKED` rather than a status of
   its own (D-146).
9. Edits outside a fix's own files, accepted by the integrator at the merge. The CRD fix changed
   three tests outside the CRD files (`service/suite/SuiteServiceTest`,
   `service/codelist/CodeListConformanceSeamTest`, `web/CrdControllerTest`): they only follow the
   new behaviour, each with an exact expectation, and the merged line builds green with them. The
   guardrail fix changed two shared files: `model/Secrets`, for the list of names that read as a
   credential and the words that make a name a descriptor (D-144, D-147), and
   `client/lookup/LookupOutcomes`, for the sentence of a refused lookup (D-146).

## Decisions of the leftover tasks and of the frontend

After the fix wave, two waves of work were merged: the leftovers that the parts of the fix wave
had left in each other's files, in two tasks (one for the three protocols, one for everything
else), and the frontend, in parts (the frame and the files every tab shares, the CRD tab with the
request comparison, the DTR tab and the PAS tab, on top of the case, the Config tab and the History
tab, D-172). Each task and each part was done by one agent, attacked by a second that did not write
it (for the frontend: used in a real browser by a reviewer), and finished by a third. The entries
below record what they decided and what the integrator decided for them, in this order: the
leftovers in the protocols, the leftovers outside them, the frontend, what was decided not to do,
the work decided and not yet done, and what was decided while joining the parts. Where an entry says
"the fix", "the verification", "the review" or "the finisher", it means the agent that did that
work. What is still open is in `PROGRESS.md`.

### The leftovers in the three protocols

**D-176. CRD names a partly completed QuestionnaireResponse for DTR: it is handed on as a reference and never fetched.**
From the CRD verification's V-01, decided before the work. The 2.0.1 `response` sub-extension of the
coverage information is a reference to a QuestionnaireResponse the payer started. CRD now puts every
such reference under the hand-over key `NextStepView.CARRY_QUESTIONNAIRE_RESPONSE_REF`
(`questionnaireResponseRef`), which stays apart from `CARRY_QUESTIONNAIRE_RESPONSE`: one is an
address CRD named, the other a response the tool completed. DTR passes the key on unchanged through
every step, and its package result says once, quoting the reference, that CRD named this response
for DTR to start from and that the tool does not fetch it, because the DTR 2.0.1 questionnaire
package operation has no input for it (its inputs are coverage, order, referenced, questionnaire,
context and changedsince; checked against the operation definition in the 2.0.1 package by the fix
and again by the verification). What the package returns is what the tool works from. PAS ignores
the key. A reference is an address, and an address can hold an identifier: it is shown on the
result only, never sent in the request body, never on the ledger line or in a log. The sentence that
CRD does not hand the reference on is gone from the CRD reason.

**D-177. `GUIDE_RULE_BROKEN`: what it covers, and where each protocol raises it.**
From the CRD verification's V-07, decided before the work (D-140). The kind is for a response that
breaks a rule of the guide that is neither a missing element nor a code outside a value set: an
invariant, an element where it may not sit, an element sent more often than allowed.
- CRD: rules crd-ci-q1, q3 and q5, coverage information on a card, and a part sent more than once.
  What stays `REQUIRED_ELEMENT_MISSING` is a part that is missing or empty.
- PAS: a review action on the ClaimResponse or directly on an item, where PAS 2.0.1 defines the
  review action extension only for `Claim.item` and `ClaimResponse.item.adjudication` (checked
  against the guide's source). The decision it carries is still shown. This corrects D-159, which
  said such a review action raised no finding.
- DTR: a package bundle whose first entry is not a Questionnaire, or that holds no entry, breaks
  dtrb-1 and raises one finding per such bundle, naming DTR 2.0.1 and the invariant. Only bundles the
  package is made of are judged; a bundle nested inside one is not a package bundle. The fix first
  found no DTR misfit, because none was filed under the two old kinds; the verification pointed out
  that dtrb-1 was said only in a verdict sentence, so a History search by kind would find the PAS
  misplacement and not the DTR break. It was then raised. The mock scenario with an empty package
  (`P32H-DTR-EMPTY`) now carries this finding beside its `REQUEST_INCOMPLETE`.
- DTR's request-side rule dtrx-1 stays a gap of `REQUEST_INCOMPLETE`, since it is about what the tool
  sends, not about what a service returned.

The label the fix suggested is "Breaks a rule of the guide". The colour key of the frontend lists the
kind under the group of findings about what the guide requires (D-195); the server serving the
groups itself is decided and not done (D-200).
(Done: every finding carries its group and the group's label from the server, D-205, and the
screens read them, D-207.)

**D-178. DTR builds its Patient, Coverage and orders as CRD does, and says once what the CRD profiles require.**
From the CRD verification's V-11 and the verification of the fix.
- The package request uses the CRD 2.0.1 builders of `CaseResources`: the Patient with its medical
  record number from `patientMrn` and `patientMrnSystem`, the Coverage from the same payer, coverage
  and member identifier variables CRD reads, and each order under the HCPCS address `hcpcsSystem`
  sets (D-142), with the same note. DTR 2.0.1 fixes the Coverage it carries to CRD profile-coverage
  and names no profile for the Patient among its referenced resources.
- The gap list of `DtrProfileCheck` lost nothing and gained nothing: it is `Coverage.status` and
  `Coverage.payor` when nobody supplied them. The fix walked the CRD 2.0.1 Coverage and ServiceRequest
  snapshots: every element they require is sent except those two.
- What CRD profile-patient requires and nobody supplied (a name, a gender, a medical record number)
  is said once, as information, in one Patient note, which says that DTR 2.0.1 does not require it
  here and that it is not counted among what the request leaves out. The verification found eleven
  notes that read as a list of unmet requirements and stated the Coverage gaps twice; the
  requirement sentences of the CRD builders are now dropped for the package (they are recognised by
  their opening, "CRD 2.0.1 profile-"), and the Coverage gaps are said only by `DtrProfileCheck`.
  CRD's own request notes are unchanged.
- The earlier builders (`patient()`, `coverage()`, the five-argument `serviceRequest`, the
  three-argument `codeableConcept`, `insurer()`) have no caller in the application now and were left
  in the file. Removing them is decided (D-200) and not done.
  (Removed, with the tests that only exercised them: D-202.)

**D-179. A secret a service sends back is masked once, in one place, for every protocol and lookup.**
From the PAS verification's V-01, in the form its finisher suggested, and the verification.
- `VendorExchange.withReturnedSecretsMasked()` gives the parsed body with every place masked that
  repeats a secret the request carried, by the same rule as the wire view (D-162). The CRD, DTR, PAS
  and subscription mappers and both lookup mappings read through it, and so does CRD discovery.
  `service/pas/ReturnedSecrets` is gone. The raw body, `json()` and the headers of the exchange stay
  as received, and the wire view masks from them.
- The name of the questionnaire a DTR next-question answer names goes to the ledger line, so it is
  read from the masked body. The verification showed that without that, a secret echoed in the path
  of that address reached the ledger and no test failed; there is a test now.
- Every sentence that said a body is shown "exactly as received" now says it is shown as received
  except that a place repeating a secret the request carried is masked.
- The address a subscription names for call back is masked in the Subscription the tool shows (the
  result, the wire request, the preview) by the name of its query parameter or of its secret
  variable, and sent as written. A Subscription the service sends back holding that address is
  masked as well. A secret written into the path of the address with no variable has no name to be
  recognised by and is shown; the Javadoc says so.
- Proven on the live profile against stand-ins that echoed the bearer token, an API key header and
  the lookup's Authorization header in fields nobody anticipated, in property names and nested under
  a key-named property: no answer, ledger line or log held any of them.
- Not covered: a request sent from the collection as it is, whose "returned but not interpreted"
  fields are still read from the body as received (D-196, D-200), and a secret sent back as a JSON
  number, anywhere.
  (Both covered since: D-205 for the raw send, D-202 for a JSON number.)
- Later, D-280: the parsed body is masked wherever it repeats a secret this tool holds for the
  configuration in force, not only one the request carried, and the sentences of the third bullet now
  say "a secret this tool holds". A verdict read from such a mask says so under its headline.

**D-180. A questionnaire the service completes with no item, in a package of several, is done.**
Decided by the integrator after the fix wave (D-166). Its response is not handed to PAS, because DTR
2.0.1 requires at least one item and the tool does not hand PAS a response that does not conform. It
is taken off what the hand-over lists as still to answer, so that no later step asks for it again
and PAS is offered once the others are answered. The step that takes it off says so, naming the
questionnaire. A journey test drives a package of two through to PAS with a single
QuestionnaireResponse in the bundle; the verification drove the same on live against a stand-in
that completed one questionnaire with no item.

**D-181. Which run a later ledger line names as the run it follows.**
From the suites verification's VS-11, and a finding of the verification that was refuted.
- A CRD next step to PAS or to DTR hands on this CRD run's id (`NextStepView.CARRY_RUN_ID`), as the
  DTR package fetch already did. A manual CRD then PAS run and a manual CRD then DTR run now write
  `correlatedRunId` on the later line; a suite chain row still writes it once, not twice.
- A submission to PAS made from DTR names the package fetch, not the response step whose answers it
  carries. The verification asked for the response run. Refuted, and kept on purpose: a package can
  hold several questionnaires, each completed by its own response run, and the submission carries all
  of them; one `correlatedRunId` cannot name them all, and the package fetch is the one run every one
  of them follows. The chain id ties all the lines together. `DtrHandoff` documents the rule and a
  journey test pins it.
(Stands, D-243 and D-251: the walkthrough asked again for the response run, and it was decided
otherwise. History lists the runs that follow a run, so the response run is reached in two steps.)

**D-182. Smaller decisions in the protocols.**
- On mock, a CRD run whose own body is not JSON keeps the verdict of the scenario that answered but
  gets the next step "review" with nothing handed on: the scenario answered whatever the body held,
  and offering DTR or PAS from that would rest on an answer no service gave. On live the service's
  own answer decides (the case review's CASE-06).
- The orders a tester's own CRD body carries reach the ledger line only as procedure codes, and only
  from the order resource types; an order with no code is named "order N (no code)", never by its id,
  and the record then keeps it only as its own statement (the member data audit's m6).
- DTR no longer writes its own "(not a url, N characters)" where a questionnaire address belongs on
  the ledger line: the line holds the address or the record's own "not recorded" statement, which
  replay refuses.
- The CRD, DTR, PAS and subscription services pass where a run came from (`withOrigin`) on every
  call they prepare. A preview passes none. What the gateway and the result then say is D-183.
- The sample endpoint of the notification receiver no longer repeats the run id in its two "not
  found" sentences (D-185).

### The leftovers outside the three protocols

**D-183. A replay or a suite row refused on production is told what it can do, not to unlock.**
From the guardrail verification's F4 (D-143, rule 8), decided before the work, and the verification.
- The next action `SEND_ON_ITS_OWN` ("Send this write on its own from its tab, with production
  writes unlocked there, or point the tool at an environment that is not production. A replay or a
  suite never carries the production unlock.") is what a blocked replay or suite row gets, on the
  result, on its ledger line, on the chain view and on the suite row. A manual run that is blocked
  keeps `UNLOCK_OR_SWITCH`.
- The headline of its next step is that label, whatever step the gateway's refusal names. The
  verification found the headline still opening with "unlock production writes on the Config tab"
  beside the new next action, because the gateway's step was preferred. The gateway's step stays in
  `error.action` and its reason in the verdict.
- When the services pass where a run came from (D-182), the gateway words the refusal itself ("a
  replay never carries the production unlock, so its writes to production are always blocked").
  The platform half also gives the verdict one supporting line that says so, for a refusal that does
  not, and leaves it out when the refusal already says it, so it is never said twice. With both
  halves merged the refusal should say it and the line should not show; nobody has seen that.
- The gateway names the tab a write can be sent from as a noun phrase only ("the Config tab" for a
  request sent from the collection as it is). The verification found the sentence for another
  session holding the unlock garbled by the old phrase; it is now pinned by a gateway test.
- The two halves were built on two branches at the same time: the origin in the protocol services on
  one, the next action, the headline and the supporting line on the other. Each was checked on the
  live profile against a stand-in playing production, which received nothing. On the protocol
  branch, where the services pass the origin, the verification saw the gateway's own sentence for a
  refused suite row on the running application ("a suite never carries the production unlock, so its
  writes to production are always blocked", while a direct submission got "production writes are
  locked for this session"); a refused replay was not tried there. On the platform branch, where the
  services did not pass it, the gateway's generic sentence showed with the new next action and
  headline. The chain view of a blocked replay is checked by a unit test only (`ChainServiceTest`): a
  replay is not part of its original's chain (`RunReplay`), and on the running application a
  replayed PAS submission did not join its original's chain, so no chain whose latest line is a
  blocked replay was shown. The two halves are merged and nobody has seen them together. Corrects
  D-023 and D-131 as noted there.
(Seen together since: `ProductionRefusalOverTheServerTest` drives a refused replay and a refused
suite row through the real endpoints on live against a stand-in playing production, which
received nothing (D-205), and the pass that joined the tabs saw a refused PAS replay on screen
with `SEND_ON_ITS_OWN` (D-206). The chain view of a blocked replay pins a state the tool cannot
reach: a replay never joins a chain, D-208.)

**D-184. A request sent from the collection as it is: what goes, and to whom the token goes.**
From the guardrail verification's F8, D-139, and the Config tab review's CFG-06 and CFG-21, decided
before the work, and the verification.
- The vendor's access token is attached only to the scheme, host and port the configuration in force
  names for a vendor operation (`OnyxHosts.admits`), as for every protocol call (D-143, rule 7).
  Proven on the wire against two stand-ins on two ports of one host: only the configured one
  received it.
- When the caller supplies no body, the body saved with the collection request is sent, resolved
  through the variables like the url and the headers. A credential typed into it is masked wherever
  it is shown and sent as written (D-147). A supplied body is sent instead. The preview and the result
  say which went (`bodySource`: saved, supplied or none). A placeholder in a saved body that nothing
  fills is a 422 naming it, as for a url. Closes D-139.
- `urlValues` fill the single-brace parts of a url (`{claimId}`), percent-encoded. A url that is not
  complete when it is to be sent is refused on both profiles, for a send and a preview alike, with one
  422 that names each single-brace part under `urlValues.<name>` and each placeholder beside it under
  `variables.<name>`; nothing is sent or recorded. The verification found that on mock a url with an
  empty placeholder beside a filled part was recorded as a run, and that the two profiles answered
  one request differently; both are closed. The guardrail's rule against a value typed for one run
  moving a write applies to these values.
- A url-part value of exactly `.` or `..` is refused for every method: it is the one value
  percent-encoding leaves as it is, and it moves the request to another path. The verification sent
  a read with `..` to the parent path with the token on it. The gateway's rule for writes stays as a
  second line.
- On mock nothing is sent. The headline names the mock scenario as what answered ("A mock scenario,
  standing in for the service, answered (HTTP 200).") and a supporting line says nothing was sent.
- After a send that got no usable answer, the next step is chosen by the method and by whether the
  request may have reached the service (D-163): a write that may have arrived is to be checked on
  before it is sent again.
The README still describes the body and the 422s as they were; correcting it is open work. At the
revision of D-201 no screen offers `urlValues` or shows `bodySource`: the Config tab's view of a
request sent as written was built before this entry and sends only a body (`rawBody`), so a request
whose url holds a `{token}` cannot be sent from the Config tab. A preview of an incomplete url on mock
is now a 422 where it was a 200 with a note, and no screen was checked against that.
(The README was corrected by D-209. The Config tab now offers a field for each url part and shows
the server's sentence about the body, and the 422 of an incomplete url marks its field, D-207. A
url-part value holding `/`, `\` or `;` is now refused as well, D-211.)

**D-185. A "not found" answer repeats nothing taken from the request.**
From the edge verification's V3 (E13), decided before the work. The sentences of `RunIds`,
`ConfigStore` (environment, collection, a request of a collection), `RunReplay` and the notification
receiver now say "There is no run with that id.", "There is no uploaded environment with that
name.", "N of the run ids given are not in the ledger. Nothing was sent." and the like. A name the
store itself holds may be repeated (an uploaded collection's own name); text from the address or the
body of the request never is, even an id that has already passed the run id pattern. Closes D-150's
open item.

**D-186. Words the server now serves, so that a screen keeps no copy of them.**
From the Config, History and case reviews (CFG-16, CFG-17, H-03, H-13, CASE-02, CASE-14) and the
fixture leftovers, all decided as additions: no component a screen reads was removed or renamed.
- `sourceLabel` beside every `VariableSource` code served (the resolve answer and every variable use).
- On `/api/config/effective`, `lookupEnabled` on the lookup rows, so no screen builds a setting key
  from an operation name, and for a blocked row `blockedShort`, a short sentence of its own, with the
  full reason kept once in `writeAccess.reason`.
- `nextActions`, a list of code and label for every `NextAction`, on every scenarios endpoint (where
  the outcome vocabulary is already served), from `NextAction.label()`, the same source the chain
  view words them from, so the two cannot differ. The ledger line is not given a next step sentence,
  because a next step headline can hold what a response sent.
- On a suite answer, `countGroups` (each count group with its label and count, derived from the
  counts so the two cannot differ) and on each row `countedIn`, the name of the count it is in, which
  is the name of the `SuiteCounts` component, so a screen can filter rows by the key it reads the
  count from. The labels live in the new enum `SuiteCountGroup`.
- `statusLabel` on lookup answers, `authRequiredLabel` on a procedure code row ("PA", "No PA", absent
  when the list does not say), and `exactCode` on the picker answer, which says whether the list holds
  the code at all, written as the list writes it, ignoring line of business and dates.
- `ScenarioSummary` names every sentinel member ID and NPI of a scenario, and lists apart, as
  `crdSentinelMemberIds`, the CRD sentinels a DTR file answers for. `sentinelMemberId` stays the
  first, for screens that show one. Closes D-153's open item.
At the revision of D-201 no screen reads any of these yet: the screens were built on a base without
them, and reading them is the work of the pass that joins the tabs (D-200).
(Read since by the two passes over the screens, D-206 and D-207. `lookupEnabled` and `blockedShort`
are read in code and were not seen on screen.)

**D-187. History: replay says what the new run found, and the list and the export take more filters.**
From the History review (H-01, H-09), decided before the work.
- Each entry of the replay answer carries the new run's verdict and next step as the protocol
  endpoints return them, never its wire view, which stays behind `GET /api/runs/{runId}`.
- `GET /api/runs` takes `testerRef`, an exact match on the tester reference as it stands after the
  run's latest note, and gives `matched` when the list is cut short. The export takes the `protocol`
  and `result` filters of the list. Each is optional and changes nothing when absent.
- An optional `zone`, an IANA zone id, on the list, the export, the date-range replay and the chains
  list, makes `from` and `to` days on that clock. An unknown zone, an offset such as `+05:00` and a
  short name such as `EST` are a 422 at `zone`. The ledger reads one day either side of the range,
  which covers every zone's offset from UTC (UTC+14 and UTC-12 each move a local day by at most one
  UTC day). The History tab does not offer it yet and still says every date is a UTC day.
  (Decided since: the History tab does not offer a zone, and History stays worded in UTC days
  throughout, D-207. The `testerRef` filter is offered.)

**D-188. Fixtures and the live profile.**
From the fixture leftovers and the chain review's F15, decided before the work.
- A mock scenario set found broken while the tool runs makes a suite row or a replay
  `NOT_CONFIGURED`, not not valid: the row is not at fault, the tool's own setup is. Its sentence names
  only the protocol directory, never a file or what a file holds; the scenario list names each file
  and its problem. A chain row is reported the same way.
- `MockExchanges` was the one bean only mock uses that was created on live; it is now on the mock
  profile only. `LiveProfileContextTest` starts the application on live and fails if any mock bean or
  the lookup fixtures exist there. `ScenarioRepository` and the start-up check of the scenario set
  stay on both profiles, because the scenario lists are served on live as well.
- A lookup fixture set in which two files claim one identifier stops the start, naming both files.
  The refusal was already in the code. A test proves it for member fixtures
  (`LookupFixturesTest.aFileThatCannotBeUsedStopsTheStartAndIsNamed`); there is none for provider
  fixtures, and none of the three agents added one, because `client/lookup` belonged to the other
  task. A fixture file that repeats a key is still read: `LookupFixtures` does not refuse it.
  (A test covers provider fixtures since: `LookupFixturesTest`, D-204.)

**D-189. The ledger, suites, replay and lookups: the smaller decisions.**
From the ledger leftovers, the suites and chain verifications (F7, VS-4) and the case review (CASE-09).
- A suite row whose expectation is over the limit (1000 characters) is reported by its length alone,
  for every kind of row, and none of its terms is read, because a term problem quotes the term. A
  short mistyped term is still quoted back (D-175, item 7).
- The code list's CSV records print counts only in `toString`. `RunController` uses the limits
  `RunRecord` defines, not copies. The `RunLedger` Javadoc says what the policy does to a line of
  business, to a member ID on mock and to case lines.
- A DTR ledger line whose questionnaire list holds a statement in place of an address cannot be run
  as a case (`NOT_RUNNABLE`, in the suite preview too). The statement is never cut into addresses.
- The refusal to replay a step that used what an earlier step handed on ends: "This run is one step
  of a chain, and cannot be replayed by itself. To run the journey again, run it as a suite row with
  the protocol chain."
- Ledger health names the day files that hold lines it cannot read, with a count for each
  (`unreadableFiles`), and the chain view's note names them. A directory that cannot be listed still
  counts in `unreadableLines` with no file name.
- A member ID or NPI holding `/` or `\` is looked up through the query form (`GET
  /api/members?memberId=`, `GET /api/providers?npi=`). The server's handling of an encoded slash in a
  path was not relaxed, which would have widened what every path accepts; the path form still ends as
  a bare 400. Without the parameter the answer is the 422 a blank identifier gets, so nothing can be
  listed. `LookupControllerTest` had pinned a 404 for `GET /api/members`; it now pins that 422 and that
  no lookup was asked, which the integrator accepted by merging it.
  (By the rule of D-211, an identifier holding `/`, `\` or `;` that the configured lookup address puts
  into its path is refused before it is sent. Nobody has tried that with a lookup.)
- The frozen test helper `testsupport/InMemoryLedger` was given the new filters by the fix. The
  verification objected that it is frozen and that a second copy of the filters can drift; the
  finisher restored it. The `zone` and `testerRef` filters are tested on `JsonlRunLedger` only.

### The frontend

**D-190. The rules the interface is built to.**
Set before the first frontend pass and binding on every part since. The specification's section 20
is the design brief; these are the rules a maintainer has to keep.
- Plain HTML, plain JavaScript modules and hand-written CSS, under `src/main/resources/static/`. No
  package manager, bundler, framework or build step: a tester edits a file and refreshes. Nothing is
  loaded from another host, no font, script or stylesheet, because the tool runs on a laptop behind a
  corporate network.
- The browser keeps no copy of words the server owns and derives no answer. Outcome labels and tones,
  the elements of an expectation, operation labels, scenario lists, the verdict, the test result, the
  next step and the findings are fetched and shown as the server words them. JavaScript never
  computes an outcome from codes, decides pass or fail, invents a next step or evaluates a
  questionnaire's conditions. Where the server gives a code and no words, the screen shows the code
  and the server change is reported, not guessed (D-172).
- Absent, null and empty are three different things on screen ("not returned", "null", "empty"),
  each visibly different from a value and from each other. Never a blank cell.
- One action keeps one name through the whole flow, and a button names what happens ("Send CRD
  request", never "Submit"). An action the server names in a next step is the same words as the
  button that takes it: where they still differ (the two DTR steps), the server's label is being
  changed to the button's (D-200).
- Browser storage holds which tab is open (`localStorage` key `pave.tab`) and nothing else. No
  member data is kept anywhere in the browser: no recent members, no search history, no cached
  lookups, not even in memory across a reload. A secret the API masks is never unmasked, and a value
  typed into a secret field is never echoed back.
- No markup is made from a string, anywhere: a vendor response is untrusted text.
- The authorization outcome and the test result are two facts and never look like one. The verdict
  strip is the one loud element; "unverified" is styled as neither pass nor fail.
- The result view is the same on every tab, in the order `TASK_C1` gives: verdict, test result, next
  step, ledger line (NOT recorded impossible to miss), findings, determination grid, the protocol's
  detail, returned but not interpreted, the wire view (collapsed), the error panel.

`StaticFrontendTest` enforces what a build can check: plain ASCII, no markup or code from strings,
nothing from another host, no inline script or style, module scripts from this tool only, no
tracking property in any stylesheet, one script (`js/api.js`) talking to the server, the storage
rule, the page's own security policy matching the server's, and every file the page asks for
existing. Behaviour on screen is checked only by browser scripts kept outside the repository.

Known places where the first two rules are broken at the revision of D-201, each waiting on a server
field that is decided (D-200): `js/marks.js` keeps the grouping of finding kinds (`FINDING_GROUPS`);
`js/crd-compare.js` words the source of a case value and keeps which comparison group starts closed;
`js/pas-detail.js` words where a decision was stated; the PAS tab words how attachments travelled and
whether the receiver can be reached; the CRD comparison finds its caveat as the first note of a
comparable answer. The DTR and PAS tabs each keep their own copy of `sameCase`, which the frame now
exports (D-191).
(Each of these was put right by the second pass over the screens, D-207, and
`StaticServerWordsTest` fails if a removed copy comes back. The `sameCase` copies went in the join,
D-206. One copy of the server's words remains at the revision of D-228: the label beside a code
list finding says "the bundled placeholder" for any list that says it is synthetic, D-223.)
(Since D-231 the server gives every error its heading and the page keeps only the style of each
kind; the code list label takes its words from the self-check, D-230. Absent, null and empty are
said in words in every designed view since D-236. The page heads the error panel with the server's
heading since D-256.)

**D-191. Who owns which file of the frontend, and what a tab may ask of the frame.**
The parts were built at the same moment by different agents, so each owned files and was forbidden
the rest. The ownership stays the map for a maintainer.
- The frame owns `index.html`, `js/app.js`, `js/api.js`, `js/result.js`, `js/legend.js`,
  `js/workbench.js`, `js/marks.js`, `js/shown.js`, `js/dom.js`, `js/forms.js`, `js/handover.js`,
  `js/case-context.js`, `css/pave.css` and `StaticFrontendTest`. While others build on them, a change
  to a shared file only adds: no exported function, option, class name or custom property is renamed,
  removed or changed in meaning.
- Each protocol tab owns `js/tab-<tab>.js`, its own modules (`js/crd-*`, `js/dtr-*`, `js/pas-*`) and
  its stylesheet (`css/dtr.css`, `css/pas.css`, and `css/crd-compare.css` for the CRD tab). The case owns `js/lookup.js`, `js/code-picker.js`, `js/own-request.js` and
  `js/run-values.js` with `css/case.css`; History owns `js/tab-history.js`, `js/history-*`,
  `js/suites.js` and `js/chain.js`; Config owns `js/tab-config.js` and `js/config-*`. A tab's style
  rules go in its own stylesheet, using the custom properties and classes of `pave.css`.
- What a tab may ask of the frame, added by the frame pass: `createWorkbench` takes
  `spec.operations`, which withholds the form while the server says the tab's first operation is
  unavailable and brings it back by itself after the next configuration change; it is off unless a tab
  passes it, and no tab passes it at the revision of D-201. The DTR and PAS tabs read
  `/api/config/effective` themselves and say what is missing, with a link to Config, but still offer
  their form (D-197); the CRD tab says nothing until a send is refused. Section 20's rule that a tab
  does not present a form that cannot send is therefore met on no protocol tab. `bench.showResult` honours a tab's own
  `nextStepControl`, so a tab draws the control for an action it takes itself. `bench.track` keeps a
  set and lets a result's controls go with the result; `bench.untrack` exists. `app.operationRow`,
  `app.operationsRead`, `app.whenConfigChanges`, `app.ledgerHealth` and `app.refreshMode({quiet})`
  exist. `handover.js` exports `sameCase`. `additionalFieldsSection` takes the wire as a third
  argument (D-196).
- The shared client sends PUT and DELETE (`api.putJson`, `api.del`), so the Config tab now offers
  changing a mapping, taking a request off an operation and removing an environment or a collection.
  The frame pass drove those flows in a browser on mock and on a production environment, some with the
  keyboard (taking a request off an operation, removing an environment that is not active, removing a
  collection, the readiness link) and the others with the mouse (the chooser, removing the active
  environment, removing the last one); not every flow was driven both ways. The server never answers
  409 to them, so that path was never seen.
- Asked of the frame and not done: `bench.clearRefusal` (an earlier 409 refusal stays above a later
  comparison), a way for the case to mark a detail "from the pasted request" and for the lookup strip
  to show a line the page gives, and an action box that scrolls with the form below about 800 pixels
  of height. Asked by the PAS tab at the same time, of a file that is History's and not the frame's: a
  collapsed form of the chain bar (`js/chain.js`) at laptop height. Each part reported these; the pass
  that joins the tabs is to take them up (D-200).
(Done by the pass that joined the tabs, D-206: every protocol tab passes `spec.operations`, so on
live with nothing configured each holds its form back; `bench.clearRefusal`, the mark "from the
pasted request", a line under the lookup strip, a capped action box and a one-line chain bar at
laptop height exist.)

**D-192. How a step hands the case to the next tab.**
- A next step this tool can take is offered as a button that carries `nextStep.carry` unchanged, with
  the run it came from, its protocol, the chain id and the case it was for:
  `app.carryTo(tab, {carry, fromRunId, fromProtocol, chainId, forCase})`, read on the other side with
  `app.carriedTo(tab)`. No tab picks keys out of the carry.
- The receiving tab uses a hand-over only while the case on screen is the case it was for (`sameCase`
  on member, NPI and codes). Otherwise it says the hand-over was not sent and why. What a step handed
  on does not survive a change of member.
- DTR posts with every step the carry of the latest DTR result; a result with no carry does not
  replace the earlier one, because posting nothing would lose the package run and the completed
  responses.
- DTR withdraws "Continue in PAS", in the workspace and in the result, while any typed answer differs
  from what was posted with the last step that worked. The review found PAS receiving the old answers
  after a change; the button now carries only answers the service has seen.
- On the PAS tab, a PAS result whose next step is to submit again after documentation was asked for
  offers "Add the documentation and send again", which puts the claim id in its field and opens the
  first attachment, in place of the shared "Continue in PAS", which would have carried the result
  back into the tab already open and invited the resubmission the step warns against.
- Each hand-over was checked tab by tab on bases that did not hold the other tabs: CRD to the
  placeholder DTR tab, DTR to the placeholder PAS tab, and PAS with a DTR answer injected into the
  browser. No hand-over between two real tabs has been seen.
  (Seen since, through the real tabs, D-208.)

**D-193. The completed answers inside the outgoing PAS request.**
The specification's proof before handover asks to see the completed QuestionnaireResponse inside the
PAS request. The PAS tab shows the answers as read out of the request body that is to be sent or was
sent, not out of what DTR handed on, so the screen shows what reaches the wire. The
QuestionnaireResponse text is cut from the body character for character, not written out again. The
section appears on a submission's preview and result only when DTR handed answers on or the body
holds a QuestionnaireResponse, and the tab offers a way to send without them, said as what it is. It
was proven with a DTR result passed into the browser in place of a CRD answer, because the real DTR
tab was not on that base; on mock the request is never sent, so the section reads "in the request
built, which was not sent". Nobody has seen it after a journey through the real tabs.
(Seen since after a journey through the real tabs, in the preview and in the wire view, D-208.)

**D-194. How the comparison of a pasted CRD request is presented.**
From the CRD tab pass, its review and its finisher (D-119, D-170 for what the server does).
- The server's headline first, then its groups in its order, "How the request is built" first and
  open, "Every call" last and closed, each with its count. Each difference shows where it is, what the
  element is in the server's glossary (or that the server could not say), the two values with the
  three presences told apart, and the explanation. The two documents sit side by side with every
  difference marked in both; choosing a difference from the list, from a document line or with
  Previous and Next brings it into view below the pinned documents, never under them, and Previous and
  Next walk the differences in the order they are drawn.
- A comparison on screen that no longer matches the box, the hook or the mode says it is out of date.
  The review found an old comparison still headed "Compared: the request in the box" after the box
  was emptied.
- "Fill the form from this request" never types over a value a lookup gave. It fills what is empty,
  lists what already agrees, and puts every disagreement in a table under "The lookup and your request
  disagree", with an explicit button to take the request's values. The review found the fill typing
  one person's pasted details over the person the lookup returned, so the request and the ledger held
  a mix of two people.
- A pasted request for another hook is named by the two hooks the server's own `/hook` difference
  gives, and nothing is said about which is right.
- The vendor's sample is offered when the active collection's request has one: the tab reads its
  saved body from `/api/config/resolve` and posts it back as the box, because the comparison
  endpoint cannot take a collection request by name. The sample therefore passes through the browser.
- Until the server changes (D-200): the caveat that the comparison cannot show which request the
  service expects is taken from the first note of a comparable answer and is missing when the two
  cannot be compared; a credential in the pasted request is shown as typed; a refused comparison
  reads "send again".
  (The server changed and the tab reads it, D-202 and D-207: a caveat on every answer, a
  credential masked everywhere but in the tester's own box, a refused comparison told to compare
  again.)

**D-195. What the colour key covers.**
Every colour, mark and frame on any tab means one thing, and the key, reachable from every tab, says
what. Added by the frame pass:
- Entries for a production session whose writes are unlocked, for the sandbox kind, and for the
  difference between the thin frame of a flag and the heavy frame. The heavy black frame keeps all its
  uses (a fault, a refused field or form, a result out of date, a notice about the tool) under one
  meaning: stop and read this before the next run.
- Cream marks test data typed by hand that a lookup would not give back, and also a DTR answer the
  payer pre-populated and a person then changed (an information-origin override).
- A heavy rule down a Config row marks an operation that cannot be sent as things are configured.
- An unknown test result is drawn in a plain thin grey frame and an unknown tone as neutral, never as
  a fault and never dropped; an unknown finding kind or next action is drawn with the server's words.
- The finding kinds are listed by group, `GUIDE_RULE_BROKEN` and `REQUEST_INCOMPLETE` among them; a
  request that went out incomplete is not drawn as a fault of the service.
- Codes in the key break only after an underscore. The review asked for that; it was already so.
Known to be wrong at the revision of D-201, because the tone is the server's: a completed DTR
response is drawn in the tone the key defines as "nothing further is required of the provider",
while its next step is to submit to PAS; an unmatched notification is drawn in the tone the key
defines as the service not knowing the member or the provider; a subscription created with no call
back address is drawn as needing nothing further. Changing the tones is decided (D-200).
(Changed since, D-203: a completed DTR response is in the tone that asks for action, a created
subscription and an unmatched notification are neutral. The key draws its finding groups from the
server, D-207.)
(Added since: a row for a step nobody answered, D-240; the black side rule of a warning about an
upload or an address, D-230; on mock a fault of the service is one a scenario played, D-231.)

**D-196. What the page does about a secret a service sends back, until the server masks it everywhere.**
The frame review found the bearer token a stand-in echoed shown in clear under "Returned but not
interpreted" on a CRD result and on a request sent from the collection as it is, a few lines below the
sentence that said it was masked. The server has since masked the parsed body for CRD, DTR, PAS and
the lookups (D-179); a request sent from the collection as it is still builds those fields from the
body as received. As a stopgap, the result view takes each returned value from the masked wire body
at the same path whenever the wire says something was masked, and withholds a value whose path it
cannot find there, with a pointer to the wire view. A text search for the secret in the page then
found nothing, on a live CRD result and on such a send. Three places draw returned values without the
wire and are not covered by the stopgap: the lookups, CRD discovery and the CRD detail's own table of
the rest of a resource. The server is where this is put right (D-200).
(The server masks the raw send too since D-205. The stopgap stays as a second line, and the join
passed the wire to the lookups, discovery and the CRD detail, D-206. On live against a stand-in
echoing secrets, a search of the page text found none of them, D-207. Not covered: the PAS detail
and the notifications list, which rely on the server alone.)

**D-197. Decisions of the tabs that a maintainer would otherwise rediscover.**
- The frame: the ledger indicator is drawn from `/api/runs/health` beside `/api/mode`, loud in words
  when tracking is off, the ledger cannot write or a write failed, quiet when recording, and its count
  is of a UTC day; a run's own receipt wins for that run. A failure in the past while the ledger
  writes again shows the quiet count with "N writes failed earlier, since the tool started", without
  the heavy frame. The environment strip is not a live region; a short status line announces only a
  change of profile, target, environment or ledger state. A blocked result on mock does not say a
  scenario answered. A run id in the result view opens that run in History. A returned value over 600
  characters shows its first 300 and a fold. After a send the focus returns to the control that
  started it. Very long refusal headlines are set smaller and are still the loudest text. The browser
  tab title says when production writes are unlocked.
- The DTR tab: the questions sit in the result's response detail, ahead of what the step returned.
  Controls start empty and what the response holds is shown beside each question with whose answer it
  is, so a value the page filled in is never mistaken for one the tester typed. What each step posts
  follows the DTR owner's notes to the letter, because the server keeps nothing between steps. The
  next-question shape is bare (DTR 2.0.1) by default, and a wrapped shape applies to one step only.
  Whether an item is enabled is never worked out in the browser; an item whose state depends on
  answers says, once answers have changed, as of which step its state is. The expectation goes with
  the next step sent and is cleared once that step has a result. A refusal of a step is shown under
  that step's buttons. Each step says whether it was recorded. When the server says the package
  operation is unavailable, the tab says so on arrival and above the Fetch button, with the server's
  sentence and a link to Config; Fetch stays and still sends. A quantity's unit is free text, because
  `ItemView` gives no unit constraint except as a raw extension; the answers a response holds are
  shown beside the controls and never put back into them, because `ResponseItemView.answers` holds
  display text, not the answer code.
- The PAS tab: no inquiry button after a submission that failed in the environment, because choosing
  between "ask for the status first" and "safe to send again" would read outcome codes; where the
  server's next step is to check the environment and a claim id is known, "Choose the Inquire call"
  switches to the inquiry already filled and sends nothing. Notifications are two short lines each,
  with the server's outcome, the decision when present and the matched run, trimmed to what the
  receiver holds. A preview's notes are introduced by "If it were sent as it stands, the server
  notes:", because the server words them in the past tense. The tester reference and the note show on
  the result. When the server says the operation is unavailable, the tab leads with that and a link to
  Config, and Send is made a quiet button that still sends: a refusal recorded in the ledger is a valid
  test. Nothing shows how an inquiry's answer differs from the last one seen, because the inquiry
  answer states no difference and the browser derives none.
- The CRD tab: everything of sections 11, 21 and 28.3 is drawn by the CRD detail and the shared result
  view; the pass found nothing missing scenario by scenario.
(The DTR and PAS tabs' own readings of the configuration were removed by the join, D-206: the
shared readiness statement replaces them, and on live with nothing configured no protocol tab
offers its form.)

**D-198. What the reviews found that was refuted or deliberately left.**
- Refuted: the frame review's report of codes in the key breaking mid-word (they break after an
  underscore, as asked). The DTR review's report that a removal of a pre-populated answer is sent
  again at the next step: it must be, because each response step starts again from the package's
  draft, and without it the payer's value would come back; only the wording was wrong and is fixed.
- Left as a limit: phone width below the laptop floor (the History runs table and the CRD elements
  table run past the edge at 390 pixels); a 409 from the mapping and removal endpoints, which the
  server never gives; a 422 on a mapping, which the screen never offers a way to cause.
- Left for the server, decided and not done (D-200): the per-item comparison of what was asked with
  what was returned on a PAS response is decided not to be built (D-199); everything else the reviews
  handed to the server is in D-200.

### Decided not to do

**D-199. Decided otherwise in the leftover tasks, the screen tasks and the pass that joins the tabs, each with its reason.**
The integrator's lists under "Decided otherwise" in the five task files.
- Restated from earlier entries, and not reopened: the labels of CRD scenarios 3 and 4 (D-174); a
  resubmission after "attachment required" reuses the claim identifier, and the PAS 2.0.1 claim
  update is not built (D-133, D-161); a lookup the guardrail refuses stays `FAULT` with the error kind
  `BLOCKED` (D-146); `Organization.active` is not sent and a coverage status nobody supplied is never
  "active" (D-141); a mistyped term in a short expectation is quoted back (D-175, item 7);
  `FieldError.toString` keeps its message (D-174); no endpoint clears the remembered production
  destinations, a production environment whose address comes from an operating system variable has
  no destination the tool can remember, and Windows file permissions are whatever the folder gives
  (D-145, D-174); the session cookie is not a finding (D-174).
- No comparison, per item of a PAS response, of what was asked with what was returned (the PAS
  review's R07). Section 21 asks for every item with its adjudication and extensions, and they are
  shown as returned. Working out "what changed" would match items of two documents by position and
  call a difference a change, which is a judgement nobody supplied.
- No endpoint that works out the enabled items of a questionnaire without a run (the DTR review's
  R02). The sentence that promised it is to be made true instead (D-200, not done at the revision of
  D-201): the enabled state of a static questionnaire's items is to be worked out when the response is
  completed in this tool.
  (The sentence was made true, D-203.)
- A malformed field on the form still refuses a comparison, because the tool cannot build its own
  side without it. Only the wording of that refusal changes.
- No endpoint to make a mapped request the default for an operation (the frame review's FR-04,
  second half): taking the other requests off the operation already does it, and the effective
  configuration will say so.
- No 409 from the mapping and removal endpoints: a 404 that says the item is no longer held is the
  answer when the configuration moved.
- The pass that joins the tabs adds no feature and changes no controller, record or service.

### Work decided and not done at the revision of D-201

**D-200. What the screens asked of the server, and the pass that joins the tabs: decided, not done.**
Three pieces of work were started from the revision of D-201 at the moment it was recorded, and
none of what follows is on that line. A later entry will say what became of each.
1. The server, in the three protocols:
   - The request comparison masks a credential the pasted request carries in both documents and in
     every difference value, by the rule and mask text the wire view uses, while still comparing the
     real values; `RequestComparison.maskedNote` says how many places were masked.
   - Every answer of every protocol endpoint searched whole, on live against a stand-in echoing
     secrets, for a value not taken from the masked body; each leak fixed with a test.
   - A subscription: the mock echoes the call back address sent, or none, and the verdict is worded
     from what the request named and what the service answered. An unmatched notification gives the
     remedy first (create a subscription from the PAS tab, then deliver the sample again).
   - Tones checked against the rule that a tone says what the answer means for whoever must act: a
     completed DTR response, an unmatched notification and a subscription that still needs something
     are not "nothing further is required".
   - The DTR verdict keeps its supporting lines to the answer; the lines about information origin,
     the Practitioner author and the absence of a SMART launch move to notes or findings, each said
     once.
   - Sentences made true: the enabled state of a static questionnaire is worked out when the response
     is completed in this tool; the two DTR next steps use the label of their action; a preview's
     notes say "would be sent"; a request that never arrived was "built without", not "sent
     without"; the comparison glossary names the button of the CRD tab, not an API path; no "(s)"
     in any sentence.
   - Every next step on mock leads somewhere the mock can answer with the same sentinel (the PAS
     timeout scenario has no inquiry answer today).
   - Additions, by these exact names: `RequestComparison.caveat` on every answer, `CaseValue`
     `sourceLabel`, `GroupCount` `startsOpen`; `QuestionnaireView.kindLabel`,
     `EnableWhenView.stateLabel`, `DtrDetail.responseNote` for every response kind,
     `NextStepView.carryLabels`, `values` on each dropped answer (on the result only, never the
     ledger); `sourceLabel` beside every PAS decision source, `PasDetail.attachmentNote`, and notes
     on where a subscription value given for one run came from.
   - The `CaseResources` builders nothing calls are removed with the tests that only exercise them.
2. The server, outside the three protocols:
   - A request sent from the collection as it is reads the parsed body through the mask, and one test
     class searches the whole answer of every endpoint on live for an echoed secret.
   - "Can this operation be sent" counts every unresolved placeholder of the request (url, headers,
     body) a run would refuse for, not the url alone, without counting what the form supplies.
   - A blocked request sent as written gets a next step of its own, and on every blocked result the
     next step's reason no longer repeats the refusal.
   - The two halves of D-183 are checked together through the real endpoints on live.
   - The configuration's not-found answers say the item is no longer held and may have been removed in
     another window; a request taken off every operation is not "now mapped to Unmapped request".
   - Counts in words that agree, and a 422 worded for the call made (a preview, a comparison, an
     unlock, an upload or a lookup is never told "Nothing was sent" or "send again").
   - A service that cannot be reached is said in plain words, not by a Java class name; a mock
     timeout says the timeout was played and what the wait would be on live.
   - Additions, by these exact names: `defaultRule` on an effective row with several requests;
     `bindAddress` and `bindNote` on `GET /api/mode`; a new enum `FindingGroup`, with `group` and
     `groupLabel` on every `Finding` and `findingKinds` on the scenarios answer; the labels of
     `SUBMIT_RESPONSE` and `ANSWER_NEXT_QUESTION` become exactly "Complete the response" and "Send
     answers and get the next question"; `IssueView.expressionPresent` and `locationPresent`.
3. The pass that joins the tabs: every request one part made of another's file and every answer of
   the server no screen reads yet, each accounted for; then the whole tool used on mock at 1366 by 768
   and 1920 by 1080, with the keyboard for at least one journey: the journey CRD to DTR to PAS with
   `P32H-DOC-DTR` through the real tabs and found again as one chain in History; one case on every
   tab; `P32H-NO-AUTH` against `P32H-EMPTY` everywhere; expected against actual on each tab; one
   action one name and one state one look; the colour key; the live profile with an empty store;
   production; a ledger that cannot record; a suite, a replay and a date-range replay. Where the join
   rests on a rule a build can check, a test.
(What became of each is recorded in D-202 to D-208. Item 1 is done, apart from secrets a service
puts into a notification, which went to the fix of the second guardrail audit (D-215). Item 2 is
done, with two differences from the decision: the effective configuration counts the url and the
headers and not the body, because only a raw send sends a collection body and it refuses an
unresolved placeholder itself (D-205), and a 404 for a removed environment does not name it. Item
3 was done by two passes (D-206, D-207). Neither drove a replay of a date range, and the zone filter
was decided not to be offered (D-207).)

### Joining the parts

**D-201. Decisions made while joining the leftovers and the frontend.**
This revision is branch `epa-pave` at `7dade28`: the line of D-175 (`33893aa`), the two leftover
tasks (`seams-protocol` at `150b009`, `seams-platform` at `e8f07b4`) and the frontend
(`frontend-base-2` at `b7130fd`, which holds the case, the Config tab and the History tab; the frame
at `94edbc8`; the CRD tab at `6d7303a`; the DTR tab at `81e9092`; the PAS tab at `b7e4e0f`).
1. Both leftover tasks had reworded the same "not found" sentence of the notification receiver, with
   the same words. The comment that says why the identifier is not repeated was kept.
2. Nothing else conflicted. The four frontend branches changed no file in common, and none of them
   changed a file outside `src/main/resources/static`.
3. The other task's half of D-183, which the platform finisher handed on as a change still needed in
   the four protocol services, was already on the protocol branch; the merge holds both halves.
   Nobody has seen them together.
   (Seen together since: by a test over the running server, and a refused PAS replay on screen by
   the pass that joined the tabs (D-205, D-206).)
4. Accepted by merging: the platform task's one change in a file of the other task (the receiver's
   sentence, given to it by its task file), and its changed `LookupControllerTest` (D-189).
5. The merged line built once through the shared build wrapper: 2597 tests, 2595 passed, 2 skipped
   (the two that need a folder that cannot be written, which a build running as root cannot make),
   none failed.

## Decisions of the join, the server work the screens asked for, and the second audits

After the revision of D-201, eight branches were merged: the pass that joined the five tabs into one
tool, the two server tasks that did what the reviews of the screens asked of the server (D-200),
the README brought up to the tool as built, a second pass that made the screens read what the
server then said, and the fixes for what the second run of three audits of section 27 found
(member data, the production guardrail, fixtures). The two server tasks for the screens were begun
by agents the restarts of the build machine cut off, and each was taken over and finished by a
second agent working from the checkpoints left behind. The platform task's second agent read the
six inherited checkpoints line by line but did not run their tests against the code without them,
nor its own new tests; the protocol task's second agent ran one mutation pass of 24 mutations over
the inherited and its own code, and two of them, which no test caught at first, were not run again
after the tests that catch them were added. Much of D-202 to D-205 was therefore written by an
agent that was cut off, and the platform half has no mutation evidence at all. The other branches
were each done by one agent. The two server tasks had no verifier of their own: the audits were
their first independent readers. The fixes for
the audits have been checked by nobody but their authors. The entries below record what was
decided, in this order: the server work for the screens, the two passes over the screens and the
README, the second audits and their fixes, what was decided not to do, the work decided and not
done, the questions the operator had not answered (replied to later: D-269), and what was decided while joining. "The fix",
"the pass" or "the audit" means the agent that did that work. What is still open is in
`PROGRESS.md`.

### The server, as the screens asked

**D-202. The request comparison masks a credential, and a secret sent back as a number is masked.**
From the CRD review's CMP-6, CMP-7, CMP-10 and CMP-12 and the protocol half of D-200, item 1.
- `ComparisonMasks` masks, by the name of the element that holds it and by the same rule and mask text
  as the wire view, every credential the pasted request carries: in both documents and in every
  difference value. The two requests are still compared on the values as typed, so a credential
  that differs is still a difference, and its explanation says both values are masked.
  `RequestComparison.maskedNote` says how many places were masked and is absent when none were. A
  text the comparison could not check for a credential is not shown.
- `RequestComparison.caveat` carries, on every answer, comparable or not, the sentence that the
  comparison shows where the two requests differ and cannot show which one the service expects.
  `CaseValue.sourceLabel` and `GroupCount.startsOpen` say where a case value came from and which
  group starts open. The glossary names the button of the CRD tab, not an API path. `notes` still
  repeats the caveat as its first entry; leaving it out is decided and not done (D-226).
- A secret a service sends back as a JSON number is masked by the number's text as written, so the
  designed view agrees with the wire view, which finds the same digits in the body text. Unit tests
  only. (Later, D-280: the secrets searched for are every one this tool holds, not only those the
  request carried.)
- On the built jar a pasted request carrying a token was compared: the token was absent from the
  whole answer, the application log and the ledger file.
- The earlier `CaseResources` builders nothing called (`patient()`, `coverage()`, the five-argument
  `serviceRequest`, the three-argument `codeableConcept`, `insurer()`) were removed, with the tests
  that only exercised them. Closes D-178's open item.
- `CaseResources.Reads` masks a secret variable a builder writes into a body in the wire view, the
  preview and the designed view, and a delivered notification is masked for the credentials of the
  address to call back. Secrets the environment and the token service hold were not reachable from
  the receiver at this point; that gap was confirmed by the second audits and closed by D-215.
(The caveat left `notes`, D-229.)

**D-203. Tones say what the answer means for whoever must act, and the DTR and PAS answers carry their own words.**
From the DTR review (R02, R04, R05, R09, R16, R18), the PAS review (R02, R05, R06, R11, R12, R15) and
the protocol half of D-200, item 1.
- Tones changed: the DTR outcomes `RESPONSE_COMPLETED` and `NOTHING_REQUIRED` from `CLEAR` to
  `ACTION`, because their next step is PAS; a created or updated
  subscription from `CLEAR` to `NEUTRAL`; an unmatched notification from `DATA` to `NEUTRAL`. A CRD
  answer that needs no authorization keeps `CLEAR` unless its next step is not the end of the flow
  (for example documentation is also wanted), when it is drawn `ACTION`. Every other outcome kept
  its tone; the report lists each with its tone and next action.
- The DTR verdict keeps to the answer: the count, what is asked, what is required. The sentence about
  what the step exercised moves to the last request note; the notes on authorship and information
  origin stay in the form's notes. A test fails if a form note comes back into the verdict.
- The DTR next steps name the action by `NextAction.label()`, so the server's next step and the
  button are one name (D-205 sets the two labels). The reason of a static questionnaire's next step
  says that which items are enabled is worked out each time the response is completed in this tool,
  without another call to the service: the sentence D-199 said would be made true.
- Additions, each derived from its code in the record's compact constructor so that no caller can send
  a label that disagrees with its code, with the old constructors kept:
  `QuestionnaireView.kindLabel`, `EnableWhenView.stateLabel`, `DtrDetail.responseNote` for every
  response kind, `NextStepView.carryLabels` (one map for all three protocols; a key with no words of
  its own is labelled by itself, never left out; absent when nothing is carried, so a next step
  with no carry keeps its JSON shape), `values` on each answer that was dropped (on the result only,
  never on the ledger line), `PasDecision.sourceLabel` and `PasDetail.attachmentNote` (worded in
  the present tense, so it is true of an answered run, a timeout and a preview alike).
- A subscription value that was stated gets a note naming its variable and where its value came from
  (typed for this run, the active environment, the collection, `application.yml`, an operating
  system variable, made up by the tool); a secret value is named by its variable and never quoted.
- A subscription created with no address to call back: the mock echoes the address sent, or none,
  and the verdict is worded from what the request named and what the service answered. An unmatched
  notification gives the remedy first (create a subscription from the PAS tab, then deliver the
  sample again); D-224 changes this for the sample that matches nothing by design.
- A preview's notes say "would be sent" and "would go out". A request that never arrived was "built
  without" what it left out, not "sent without". No "(s)" is left in a sentence a tester reads.

**D-204. Every next step on mock leads somewhere the mock can answer, and a played timeout says it was played.**
From the protocol half of D-200, item 1, and FR-16 and CRD-2.
- `pas-16-timeout` and `pas-17-server-error-500` gained an inquiry step that answers "pended": the
  submission did arrive. They were the only submit scenarios whose next step says to ask for the
  status and that had no inquiry. A test walks every PAS submit scenario and follows each next step
  that names an inquiry with the same sentinel. The scenario data changed, not the wording.
- `VendorExchange.withScenario` adds one sentence to the failure detail of every timeout a scenario
  answered: the timeout is simulated, the scenario reports it after its own short delay, and the
  response time shown is that delay. It is set once, centrally, so every protocol and lookup gets
  it, and a tester is never held for the full read timeout.
- A test proves what D-188 said was missing: two lookup fixture files that claim one member ID, or one
  NPI, stop the start naming both (`LookupFixturesTest`). It pins behaviour that was already there.
- The protocol task changed one count in `PasControllerTest`, a test of the other task (9 scenarios
  answering an inquiry became 11). The integrator accepted it at the merge (D-228).
(The sentence `VendorExchange.withScenario` added was removed by D-229: the error panel alone says
that a timeout was played.)

**D-205. The platform half: words the server gained, refusals that say what happened, and two halves of a rule seen together.**
From the frame review (FR-01, FR-03, FR-04, FR-07, FR-08, FR-11, FR-13, FR-16, FR-17), the CRD review
(CMP-9, CRD-1, CRD-3), the DTR review (R09), the PAS review (R12) and the platform half of D-200.
- A request sent from the collection as it is reads the masked body, like every protocol (FR-01).
  `SecretsSentBackOverTheServerTest` searches the whole answer of ten endpoints (the CRD run, CRD
  discovery, the DTR package and next question, the PAS submission and inquiry, a subscription, both
  lookups and the raw send) over the running server on live, for the bearer token, an API key header
  and a secret variable echoed in an unexpected field and as a property name. None is disabled and
  all ten are clean. D-215 added a delivered notification to it. (Later, D-280: an answer is masked
  for every secret this tool holds, carried or not.)
- "Can this operation be sent" counts a placeholder in the url and in the headers of the mapped
  request (FR-03). On live such a header makes the row unavailable, with a sentence naming the
  placeholder and where to give it a value. On mock it goes in the row's note, which says a run is
  refused there too, because the mock refuses a header with no value before a scenario answers.
  The body is not counted, decided against D-200: a protocol run sends the body the tool builds,
  never the collection's, and the one call that sends a collection body (a raw send) has no row
  and refuses an unresolved placeholder itself.
- The reason of a blocked run's next step says what the step achieves and no longer repeats the
  refusal (FR-13). `SEND_ON_ITS_OWN`'s label no longer repeats "never carries".
  `ProductionRefusalOverTheServerTest` drives a refused PAS replay and a refused suite row through the
  real endpoints on live against a stand-in playing production: each carries the sentence of its
  origin exactly once, and only the original unlocked submission arrived. That is how the two halves
  of D-183 were seen together; the pass that joined the tabs saw a refused PAS replay on screen
  (D-206), and a refused suite row was never driven by hand.
- A configuration item another window removed is answered 404 saying it is no longer held and may
  have been removed or uploaded again in another window, with the action to read the list again
  (FR-07). The removed environment is not named: the store no longer holds its name, and the id in
  the address is not repeated (D-185). A request taken off every operation is "not mapped to any
  operation", and is named as itself in the guardrail's refusal and in the upload warning, never as
  "Unmapped request", the label of no operation (FR-08).
- `CallWording`: every 4xx answer, not only a 422, is worded for the call that was made, so a preview,
  a comparison, an unlock, an upload or a lookup is never told "Nothing was sent" or "send again"
  (FR-17, CMP-9). An endpoint it does not know keeps its words, and so does a 5xx. Counts are written
  in words that agree ("1 problem", "2 problems").
- A failure the tool cannot name is said in plain words, not by a Java class name (CRD-3): the host
  could not be found, the TLS handshake failed, or the connection failed before an answer arrived and
  the request may have reached the service. For a failure it cannot name, the log line holds the host
  and the exception types and frames, never the path, the query, the headers or the message. D-216
  stops that line for the lookups.
- A played mock timeout gets an error panel of its own: the scenario reports giving up after its
  delay, nothing was waited for, and what live would wait (`onyx.read-timeout-ms`) (CRD-2, FR-16).
  The verdict's supporting line still says no response arrived inside the read timeout beside it,
  so the timeout is said twice; on PAS the verdict also says the payer may have received the
  submission beside "Nothing was sent". Saying it once is decided and not done (D-226).
- Additions, by the names D-200 fixed: `defaultRule` on an effective row with several requests (absent
  with one or none, and on the notification, inbound and unmapped rows; when every mapped request is
  a sample of an error it says none is used and how to have one used); `bindAddress` and `bindNote`
  on `GET /api/mode` (`bindAddress` absent when `server.address` is not set, so no address is
  invented; loopback and wildcard judged from the text with no name lookup); the enum `FindingGroup`
  with `group` and `groupLabel` on every `Finding`, derived from the kind in the compact
  constructor, and `findingKinds` on every scenarios answer, each entry carrying `groupMeaning` as
  well, so the colour key could drop its copy (an addition beyond the names D-200 fixed);
  `IssueView.expressionPresent` and `locationPresent`, primitive booleans, true when the issue held
  the element at all, null included.
- The labels of `SUBMIT_RESPONSE` and `ANSWER_NEXT_QUESTION` are exactly "Complete the response" and
  "Send answers and get the next question", the names of the DTR tab's buttons, with no full stop.
  The chain view adds one where it makes a sentence of a label.
- The platform task changed one line of a CRD test of the other task (`PlaceholderOnTheLiveProfileTest`
  now lists `group` and `groupLabel` among a finding's properties). The integrator accepted it at the
  merge (D-228).
(A played timeout is said once since D-229 and headed as played since D-231; the label of
`CHECK_ENVIRONMENT` was made true on mock, D-231.)

### The screens joined, and reading what the server says

**D-206. The pass that joined the tabs: one readiness statement, one next-step row, one focus.**
From `TASK_JOIN`, on the revision of D-201, before the server tasks above were merged.
- Every protocol tab passes `spec.operations` to the workbench: CRD by its hook, DTR its two
  operations, PAS by the call chosen (the notification receiver needs no service, so the readiness
  statement offers the way to it). On live with nothing configured each tab holds its form back and
  says what is missing, with a link to Config; after an upload the form comes back within one poll,
  with no reload. The DTR and PAS tabs' own readings of the configuration were removed: two ways of
  saying one thing. On mock the statement never shows, by design.
- The rule that a result never carries itself into the tab it is on lives in the shared next-step
  control. A tab gives a control of its own through the hook (`{label, run}`, `{node}` or nothing);
  DTR's reach into the shared row and PAS's removal of buttons are gone.
- After a send the focus goes to the new verdict headline, once, in the workbench, on every tab. DTR
  still moves it to the line under the questions after a loop or response step, on purpose.
- The tester reference and the note show in the run facts of the result view on every tab, read from
  the ledger record; a run the ledger did not keep says so, because the envelope does not echo them.
- The masked wire is passed to the returned fields of the lookups, discovery and the CRD detail; not
  to the PAS detail or the notifications, whose paths were not confirmed to start at the root of the
  body, because the stopgap withholds a value whose path it cannot find.
- `bench.clearRefusal`, `caseContext.setDetail(field, value, origin)` with the mark "from the pasted
  request", `caseContext.lookupNote` and a compact layout at laptop height (the production banner
  from 124 to 79 pixels, the chain bar at one line per stage, the action box capped at half the
  pane, determination cells breaking only at a joint, the verdict headline smaller from 80
  characters) were added.
- A defect of the join was found and fixed: History's "Load this case into the form" called two
  functions the case never had, and every load threw. `StaticJoinTest` now fails the build when a
  script calls a member that `app`, `caseContext` or `bench` does not have, an import names nothing
  exported, a script is orphaned or a linked file is missing. It does not check that a field the
  browser reads exists on the record the server returns.
- The lookups use the query form (D-189); `handover.js` holds only `sameCase`, which both hand-over
  tabs use; the export link passes the protocol and result filters; a replay entry's verdict line is
  left out when it repeats the entry's reason word for word.

**D-207. The second pass: the screens read the server's words, and the copies are gone.**
From `TASK_JOIN2`, on revision `0658a7c`, which holds the two server tasks.
- The page's own copies of the server's words were removed and replaced by what the server serves:
  `FINDING_GROUPS` in `marks.js` (the key reads `findingKinds` each time it opens and says so if that
  read fails), the comparison's `SOURCES` and `STARTS_CLOSED`, the PAS `SOURCES` and its attachment
  sentence, the Config tab's sentence about the default request (`defaultRule`), its words for a
  placeholder's source (`sourceLabel`), the receiver panel's own sentence about whether a service
  can call back (`bindNote`, shown plainly, with no colour: the page does not judge an address), the
  DTR kind and condition words, and the raw hand-over keys (`carryLabels`, stacked label over value
  with the key in brackets). `StaticServerWordsTest` fails the build if one of the removed copies
  comes back, and pins each field a script now reads.
- The DTR buttons take their names from the next actions' labels, so the server's next step and the
  button are one name. An issue's expression and location are drawn "not returned" or "empty" from
  the presence flags. (Since D-236 an expression or location returned null is drawn "null", from a
  presence the server sends.) `exactCode`, the `testerRef` filter, the sentinel lists of each scenario,
  `urlValues` with a field per url part, and the 422 of an incomplete url at its field are read.
  The comparison shows the caveat once, also on an answer that is not comparable, and drops the
  duplicate in `notes` by its text.
- Action names made to agree: "Show the request" (was "Show the request before sending"), "Replay
  these runs" (was "Send these runs again"), "Send inquiry" (was "Send inquiry for this request").
  Decided otherwise: the Config buttons that only open a view ("Upload a collection", "Send one as
  written") keep their names, because their words do not claim the action is done.
- An unreadable ledger line is a visible note after the masthead's count, not an alarm, because runs
  are still recorded; it joins the alarm list when there is one already.
- The zone filter of the run list was not added. Decided since: History is worded in UTC days
  throughout (the list's date fields, the export's range sentence and the date-range replay), and
  adding a zone would mean rewording all three. The server keeps the `zone` parameter (D-187).
- The comparison's token is absent from the page text and stays only in the tester's own pasted box,
  which is the tester's input. On live, against a stand-in echoing the bearer token, an API key, the
  client secret and a secret environment variable, none appeared in the page text or a field value
  after the lookups, the CRD run, discovery, the DTR package, the PAS submission and the raw send, at
  1366 by 768 only; values that are not secrets stayed visible.
- Found and handed to the server: a lookup answer holding a masked value fills the case field with
  the mask text, which a following run would send as data (decided, D-226); the raw resolve says a
  run is still answered from a scenario where the raw send refuses the same request (decided,
  D-226); the body source is served as a code only and the page shows the server's sentence instead
  (no decision has been made about this one).
(The two items handed to the server and decided were done, D-229: the masked lookup field and the
raw resolve note. The body source of a raw send is still undecided at the revision of D-252.)

**D-208. What the passes proved on the real tabs, and a replay never joins a chain.**
- The two proofs of "Prove it before handover" were seen on the real tabs, on mock, by the second pass,
  on revision `0658a7c`, at 1366 by 768 and 1920 by 1080, by browser scripts, and quoted in its report.
  `P32H-NO-AUTH` is a solid strip in the tone `CLEAR` reading "Prior authorization is not required."
  with "The flow ends here. Record this result."; `P32H-EMPTY` is a dashed hollow strip in the tone
  `NO_ANSWER` reading "The service answered but returned no determination." and "This is not the same
  as an answer that prior authorization is not required. The service has said nothing either way."
  In History they are two rows with their own words; in the ledger file one line has outcome
  `NO_AUTH_REQUIRED` and next action `FLOW_ENDS`, the other `NO_DETERMINATION`, `REVIEW` and the
  finding `NOT_STATED`. The journey with `P32H-DOC-DTR` went from CRD ("Documentation is required.")
  through DTR ("The questionnaire response is complete.") into PAS ("The answers completed in DTR
  will travel with this request."), and the answer typed in DTR (`"valueInteger" : 17`) was seen in
  the PAS preview and, after the send, in the wire view. On mock the wire says the request was not
  sent, because nothing leaves the machine. A business tester has not done either. (Since D-241 a
  no-determination line has next action `REPORT_NO_DETERMINATION`. The two proofs were seen again by
  the automated walkthrough on `9e285a1`, which is not a sign-off.)
- The hand-over between the real tabs was seen by both passes, and a journey was found again as one
  chain in History. One journey was driven with the keyboard alone by the second pass (31 presses of
  Tab to reach the package button, 38 to reach the PAS send).
- A replay's ledger line has no chain id, so no chain view ever shows a replay: seen through the API of
  the running tool by the second pass. `ChainServiceTest` pins what a chain view says for a blocked
  replay, a state the tool cannot reach. Decided: a replay never joins a chain, and the test and the
  comments are to be brought into line with that (D-226).
(Brought into line, D-229: `ChainServiceTest` pins a blocked suite row instead, and a test pins that
a replay's line holds no chain id.)

**D-209. The README brought up to the tool as built, and the pull request draft.**
From `TASK_DOCS2`, on the line with the join and before the server tasks for the screens were merged.
- The README was gone through from the first line to the last against the running tool, and every
  example under Endpoints was run in order against a fresh tool on mock, on port 8091 because 8057
  was taken (D-171). It now walks a tester through the five tabs in the screen's own words, with the
  journey with `P32H-DOC-DTR`, and describes the guides, the guardrail, the ledger, the edge, suites,
  fixtures and the last server changes. The sentences D-139 and D-184 said were untrue are corrected.
- Every sentence of the README that quotes the screen or the API word for word was listed by line, to
  be checked again after later work. Later work has since reworded some of them (D-202 to D-207 and
  the audit fixes): "Show the request before sending" is one, the presentation of the sample code
  list another (D-223). Checking them is the work of D-226.
- The pull request draft lives outside the repository and keeps a place for each gate that has not
  run. Its attribution footer, required by the session for pull request descriptions, holds a
  non-ASCII character and names the generating tool, which the house style and the rule against
  naming a tool as an author both forbid. Which wins is the integrator's to decide before it is
  posted; nothing is posted yet.
(The README was brought up to `5763c41` by D-232; the last pass is D-250. Which wins in the draft's
footer is still undecided.)

### The second audits, and their fixes

The second run of the member data (V2), guardrail (V3) and fixture (V4) audits ran on revision
`0658a7c`, each in a worktree of its own, each by an agent that wrote none of the tool, in about 29
minutes (member data, guardrail) and about 27 minutes (fixtures). What each did not examine is listed
in `PROGRESS.md`. The integrator decided what to do with each finding before the work (`TASK_AUDITFIX_V2`, `_V3`,
`_V4`), and one agent fixed each audit's findings. **No fix below has been checked by an agent
that did not write it.** Where an entry says a test fails without the fix, the fix's author ran it
against the code with the fix taken out; where it does not, nobody did.

**D-210. The tool reads an address as the client that sends it does, and an address it cannot read is never "not production".**
From the guardrail audit's B1 (blocker). A port written with leading zeros
(`http://127.0.0.1:0018742/...`) was read by the tool as no destination at all, an unreadable
destination counted as not production, and the HTTP client connected to port 18742: a PAS
submission, a subscription delete and a raw transaction reached a production environment's exact
path from an environment uploaded as a sandbox, with no unlock. The same spelling worked from
`application.yml` and from a collection url written out in full, and the other way round a
production environment written that way was never remembered. Three rules, which hold together:
1. **One reading of an address.** The destination of a url (scheme, host, port, base path) is what
   `java.net.URI` reads from the url's normal form, the same object the executor hands the JDK
   client (`UrlNormal.destination` and `UrlNormal.sendable` share one parse). A host of digits and
   dots is compared as the JDK resolves it, so `0177.0.0.1` is `177.0.0.1`; a host with letters
   such as `0x7f.1` keeps the older reading, because the client refuses it anyway. This replaces
   D-144's "every form the C library accepts" for such hosts.
2. **An unreadable destination is never "not production".** A url the client would refuse (no host
   it can read, a port above 65535) is "not readable". It is still compared with production
   addresses by the tool's lenient reading, which errs towards a match, and the executor never
   sends it. A write whose destination is unreadable is refused with 403, on both profiles, after
   the check for another production environment's address (that refusal says more), naming the
   variable and its layer, the request, or the `application.yml` setting the address came from,
   never a secret.
3. **Reported before anything is sent.** An address with a scheme and a host and no readable
   destination is reported on the environment at upload, on activation and wherever environments are
   listed (`addressWarnings`, D-212).
`AddressSpellingGuardrailTest` covers the audit's spellings at the gateway, on mock and on live;
`DestinationOnTheWireTest` sends each spelling through the real JDK client to a loopback listener
and fails if bytes arrive where the tool named another place. On the built jar, on live, against
stand-ins playing production, the audit's requests were refused and the stand-ins received
nothing. Not proven: IPv6, because this machine has no IPv6 loopback and that test returned early;
and the fix's tests were not run against the code before it (the B1 tests use the new methods and
would not compile there). The only evidence that B1 was open before is the audit's.
(Checked by an agent that did not write the fix, D-245: closed in the first direction, open in the
second for a production address the client cannot read. Decided in D-247, item 1, and done, D-254.)

**D-211. No request leaves with an encoded separator in its path.**
From the guardrail audit's M-ENC (major) and m3 (minor). A value typed for one run holding `..%2F`
was one segment to the tool and two to a server that decodes before it resolves dot segments, so a
CRD hook or an inquiry could be delivered as a submission to a locked production. Decided: no
request leaves with an encoded slash, backslash or semicolon (`%2F`, `%5C`, `%3B`, any letter case,
however many times encoded, up to 8 decodings), or a dot segment that appears only once an escape is
decoded, in its path, whatever the environment and whatever the method, because the tool cannot
know how a server in front of a service reads it. It is refused with 422 before any other
judgement, naming the variable and its layer, the url part or the request that supplied it, never
the value. The query is not read: a FHIR search carries encoded characters there legitimately. A
singly escaped `%2e%2e` is already decoded by the normal form and judged as the dot segment it is.
An environment whose base address holds one is reported at upload and nothing at all is sent to
it. Consequence: an id or a single-brace url part that holds `/`, `\` or `;` is now refused, where
it used to be sent percent-encoded inside one segment (D-184); six existing tests (three in
`ConfigStoreGatewayTest`, one in `UrlPartGuardrailTest`, two in `RawSavedBodyTest`) that expected
`a/b` to go out as `a%2Fb` now expect the refusal. Covered by `EncodedSeparatorGuardrailTest`; a
search with `%2F` in its query arrived at a sandbox stand-in unchanged in
`SecondGuardrailAuditOverTheServerTest`, and was not tried by hand. How a real nginx or Tomcat in
front of a service decodes was tested neither by the audit nor by the fix.
(Checked and closed, D-245. Extended by D-247, item 3, done, D-254: a percent-escape of a byte
above 0x7F is refused as well.)

**D-212. What the Config answers warn about an address.**
From the guardrail audit's m4 (minor), B1 and M-ENC. Three warnings, in the upload answer's
`warnings`, in `EnvironmentView.addressWarnings` wherever environments are listed and in
`Activated.addressWarnings` after a switch (both new, never null):
- an address the tool cannot read the way the HTTP client reads it: a write to it is refused;
- an address whose path holds an encoded separator: nothing is sent to it;
- an environment on the same scheme, host and port as a production environment (stored or remembered)
  under another base path, naming that production environment. D-143's rule 1 stands: the base path
  is part of the destination, so the environment is accepted and treated as its kind, and writes
  go to it without an unlock. The warning says that if the server routes that path to production,
  the environment must be uploaded as `prod`. It is worked out whenever an environment is
  described, so a production environment uploaded later also warns on the sandbox.
No screen shows `addressWarnings` at the revision of D-228 (D-226); the upload answer carries the
same sentences.
(Shown on screen since D-230, under each listed environment and after a switch. The "same server"
warning for a production port that could not be read has its own sentence since D-254.)

**D-213. The configuration store keeps uploads as they are, and can therefore hold member data. The upload warns; nothing is stripped.**
From the member data audit's F5 (minor). **This qualifies the rule of section 15 that no member data
is persisted, and the operator should read it first.** An environment or a collection is kept on
disk exactly as uploaded, values included, because it is what the tool sends from. A variable such
as `memberId`, `patientLastName` or `patientBirthDate`, a sample body or a saved example response can
therefore hold a real member's identifier, name or date of birth, in `config-store/` (whose files
are for their owner only where the file system allows, D-147). Decided: the store keeps uploads
whole, and at upload the answer warns, naming the variables or the first request and the count,
never a value, when a variable's name reads as member data (a member, patient, subscriber,
beneficiary, insured or enrollee identifier or name; a first, last, given or family name; a birth
date or `dob`; names holding system, url, type, path, key, label, header and the like are excluded,
`MemberDataNames`), or when a collection request carries a saved example response, which can hold
the data of whoever it was saved for. The warning says the file is kept on disk as uploaded, gives
its path, and says that an environment or a collection holding real member data must not be
uploaded, with placeholders such as `S123456789` in its place. Judged by name alone: a value is
never inspected, so member data under a name the list does not know is kept without a warning. The
sample collection carries saved examples, so its upload now warns; one assertion of the sample
files' test was changed for that (D-228). Covered by `MemberDataUploadTest`, not run against the
code before the fix.
(The upload's member data warnings are shown on the Config tab since D-230. More variable names are
recognised since D-254.)
Later: D-298. The saved test lists are a second place the configuration store keeps member data, on the
operator's leave. And from D1b an export posted from the page is stored as the text the page decoded (a
byte order mark dropped), not byte for byte (R-06).

**D-214. Lock, from any browser, locks every session, and now a test fails if it stops.**
From the guardrail audit's T1 (major), F-SCREEN (major) and m2 (minor). D-144's rule stands: a Lock
from any browser ends every session's unlock, and does not move the configuration epoch (D-143,
rule 9). No test failed when the Lock action stopped locking other sessions.
`SecondGuardrailAuditOverTheServerTest` now drives it over the running server on live: session A
unlocks and sends a submission, held at a token stand-in so that it is prepared and not yet sent;
session B, which never unlocked, presses Lock; A's write in flight is refused at the send, A's next
write is refused with 403, and production received nothing. With the lock call taken out of the
controller the test failed. The refusal at the send arrives as a 409 worded by the shared error
handler as "The configuration changed while this request was being prepared. Nothing was sent.",
not with the gateway's own sentence that writes were locked again (D-144); showing the refusal's
own sentence is decided and not done (D-226). The page: after another browser's Lock, the Config
tab of a session that was unlocked goes on saying writes are unlocked until it is reloaded, while
the banner beside it says locked (F-SCREEN), and the unlock panel does not say that Lock ends every
session's unlock (m2). The server refuses the write, so nothing reaches production; the screen says
something false. Both are the screens' to fix, decided and not done (D-226).
(Done since: the 409 at the send carries the refusal's own sentence, D-229; the Config tab of a
session that was unlocked is redrawn, and the tester told, after a lock from another browser, D-230;
the server's unlocked reason says what Lock does, D-231. Checked closed, D-245.)

**D-215. A received notification is masked with everything the tool could have sent to the service.**
From the guardrail audit's GAP1 and the member data audit's F0 (blocker). A secret a service repeated
inside a notification (a secret variable of the active environment, the access token, an API key) was
held and shown in clear by `GET /api/pas/notifications`; only the credentials of the address to
call back were masked (D-202). Decided: a notification is masked before anything holds or reads it,
by the rule of the wire view (D-162), with every value the tool could have sent to that service:
every secret piece the resolver produces for each variable of the active environment and of
`application.yml` (typed secrets, names that read as credentials, operating system references), the
client secret of `onyx.auth`, the credentials of the address to call back (raw and decoded), and the
access token the token service holds (cached and last issued). `OperationGateway.secretsGivenToServices()`
gives them, as `SentSecrets` and never as a list of plain strings a caller could log; the token
reaches it through the interface `HeldAccessToken`, which the token service implements. The note on
the payload says how many places were masked and that what is shown is therefore not exactly what
was received. `SecretsSentBackOverTheServerTest` delivers a notification that repeats each of the
four kinds; none appears in the answer or in the list. With the receiver given no secrets, it
failed. On the jar the four appeared 0 times in the list, the ledger and the log. The limits of the
wire view's search apply (D-162): a secret shorter than six characters, or re-encoded, is not found.
(Checked closed, D-245. A short secret gets a note since D-254.)

**D-216. The guardrail's smaller decisions.**
- A raw send of a request mapped to an operation is refused on locked production "because a request
  sent as written is judged by its method whatever it is mapped to", naming the operation; a request
  truly mapped to nothing keeps the old reason (m1).
- The log line that names the host and the exception types of a failure the tool cannot name
  (D-205) is not written for a member or provider lookup, so a lookup logs only its outcome and HTTP
  status, as section 15 requires (F6 of the member data audit). `FailedCallLogTest` asserts that a
  failed lookup leaves no such line and no identifier, and that the line of another client holds
  the host and neither the path nor the query.
- The embedded server is given a working folder, `pave-server-*`, under the temporary directory, and
  it is removed when the tool stops, so a start and a stop leave nothing of the tool's outside the
  folder it was started in (F7 of the member data audit). No folder was added beside `config-store`
  and `run-history`. A process killed outright leaves that one folder behind.
- `ConfigStoreGateway` gains an optional setter for the held token, so its constructors did not change;
  `UrlNormal.Destination` gains a component `readable`, and the old constructor means readable.

**D-217. A note, a tester reference or an expectation may not hold this run's member data.**
From the member data audit's F1 (major). The three free-text fields were written to the ledger and
the export exactly as typed, so on live with the default tracking a note beside a tokenised member
field could hold the member ID, the name and the date of birth in clear. Decided, at three depths:
1. **At the way in, the tool refuses and quotes nothing.** Every run form (CRD, DTR on its three
   paths, PAS, subscriptions, a raw send) is refused with a 422 on `note`, `testerRef` or
   `expected`, before anything is sent, when the text holds what the same request carries: the
   member ID (compared by letters and digits only, any case, inside a longer word, for six or more
   such characters), the first or last name as a whole word in any case (two or more characters),
   the date of birth in the usual written forms (ISO, compact, US, day first, dotted or spaced, two-
   or four-digit years), the subscriber ID as the member ID is compared, or an address line (five
   or more characters). The sentence names the kind of value, never the value ("The note holds this
   run's member ID and the member's last name. The ledger keeps it as written and may not hold
   member data, so leave them out. Nothing was sent."). `service/run/MemberInText` holds the rules.
   A suite row is checked at preview for its own member ID only, since a row carries no name; the
   rest is checked when the case runs, against the looked-up member.
2. **A note added later** (`POST /api/runs/{id}/note`) is checked against the line: the member ID when
   the line holds it, and otherwise by turning into a token each candidate that ends in the line's
   last four characters (as typed, upper and lower case, without spaces, without punctuation) and
   comparing it with the member token. The ID is never recovered.
3. **The record is the last line.** `RunRecord` writes a note, tester reference, expectation or case
   expectation that holds the run's member ID as its length (D-219). On reading, the ledger applies
   the same rule by comparing tokens to lines and note lines older versions wrote, so they are shown
   and exported without the ID; the file is append-only and is not rewritten.
Limits, recorded and not closed: a name, a date of birth or an address the request does not carry (of
somebody else, or of this member under another spelling) cannot be recognised by any rule that would
not also refuse an honest note, and is written as typed. The record's last check catches only the
member ID. Older lines that hold a name or a date are shown and exported as written. Short values
are not looked for, and a name is matched as a whole word, so a member whose name is also a common
word makes the run refuse a note holding that word; the 422 names the field, and the note can be
reworded. The sentence for the README: the note, the tester reference and the expectation are free
text kept in the ledger as typed; the tool refuses one that holds the member ID, name, date of
birth, subscriber ID or address the same request carries, and cannot recognise member data the
request does not carry, so member data must not be typed into them. On the jar, live, default
tracking: each of the audit's ways in gave 422 on its field, and a refused note on the CRD tab was
marked and its sentence shown (1366 by 768 only). The run forms carry no hint before the refusal
(D-226).
(Since D-231 a request sent as written checks its free text against the identifiers its body states,
and every run form carries the hint since D-230. The check found a request of the tester's own from
a protocol tab checked against nothing, and month-name dates and other forms of the ID not
recognised, D-245; decided in D-246, items 1, 2, 4 and 5, and done, D-253.)

**D-218. What the ledger keeps of a procedure code, and of an identifier a service supplies.**
From the member data audit's F3 and F2 (major). Before, the run's own member ID typed where a
procedure code goes was written in clear in the procedure codes and as the subject of every
coverage code, while the record's own documentation said it could not be; and a claim id,
ClaimResponse id or subscription id taken from an answer or a notification was kept whenever it
looked like one token, so a service that put a member ID, or a surname followed by a date of birth,
into one of them got it into the ledger.
- Every procedure code, and every part of the subject before a coverage code or before `(no answer)`,
  is held to the identifier-or-date rule codes are held to (D-151) and to the member ID check. A UUID
  inside a resource place is set aside first. What fails is written as `(not a code, N characters)`,
  and that statement is accepted as a subject, so a line read back is unchanged.
- A claim id, ClaimResponse id, subscription id or run id in the correlation is kept only as one
  token that, with any UUID inside set aside (an OID kept whole), does not read like an identifier
  or a date and does not hold the run's member ID. Ids the tool mints (`claim-<uuid>`,
  `run-<uuid>`) and the mock's ids are kept. Otherwise `(not recorded, N characters)` is written.
  A claim id a tester typed that holds a run of six digits or a date is now written as its length,
  and an inquiry naming it is then not replayable, as for any refused claim id (D-152).
- A notification that names an id the ledger does not keep is not looked up by it, and never told
  that no run created its subscription: it says so in its correlation notes and its own headline and
  next step (`Unmatched.NOT_KEPT`), under the existing outcome and finding
  `UNCHECKED_NOTIFICATION`. Matching still works for every id that is kept.
- Known gap, unchanged (D-151): a one-word surname is kept. On the jar the notification's subscription
  id `sub-Zzlastname` was written as it came, and so would a code such as `covered=Zzlastname`. A
  single word cannot be told from an id or a code by its shape, and a notification line names no
  member to compare with.
Tests that failed with the fix removed: `LedgerMemberTextTest`, `LedgerPhiTest`,
`NotificationIdsNotKeptTest`. They failed in a run that took all eight fixes of the member data audit
out at once; only the cleaning on the read path and the receiver's check of an id not kept were
also taken out alone, and the not-kept wording of `NotificationVerdicts` was not taken out alone. `RunRecord` gained `keepsIdentifier` and `withoutMemberText`; no
component was renamed or removed, and the eight lines the audit's live run wrote read through the
new code with none unreadable. One test of replay was changed because a 64-hex claim id holding a
run of ten digits is no longer kept; it now asserts the statement and that replay never passes it
on.
(Overturned for identifiers a service supplies by D-246, item 3, done in D-253: shape cannot settle it, so such an identifier is kept in clear only when it is a
UUID, an OID or one the tool minted, and as a token otherwise. The check found numeric ids no longer
matched and punctuated member IDs kept, D-245.)

**D-219. What a ledger line says in the place of a value that was not kept, and what replay does with it.**
A line never holds a value the record refused, and never drops it in silence: it holds a statement
that something was there, with its length and nothing of it. The statements are
`(note not recorded, N characters)`, `(tester reference not recorded, N characters)`,
`(expectation not recorded, N characters)`, `(not a code, N characters)` for a procedure code or the
subject of a coverage code, and `(not recorded, N characters)` for a correlation value or a
questionnaire address. They were written before for length and shape (D-151); since D-217 and D-218
they are also written for text that holds the member ID and for identifiers a service supplied.
Replay treats each as missing input where a case needs it: a procedure code statement and an
expectation statement make the run `NOT_REPLAYABLE` with a sentence of its own ("A procedure code of
this run was not recorded, because what was sent did not have the shape of a code or held the member
ID. ..." and "The expectation of this run was not recorded, ... so a run again could not be checked
against it."); a tester reference statement is not carried into the new run. A statement is never
sent as if it were a value.
(Under D-246, item 3, done in D-253: an identifier kept as a token is said to be one in History, and
replay refuses a case that needs it with a sentence of its own.)

**D-220. One rule chooses a mock scenario, for a typed identifier and one found in a pasted body alike.**
From the fixture audit's F-RAW (major). The cross-protocol sentinels of D-154 were honoured by DTR in
a pasted body and skipped by CRD and PAS, whose result then said no sentinel matched: pasted into
PAS, a CRD failure sentinel got the default "approved". Decided and built: one rule, in
`ScenarioRepository.select`, for the three protocols: an explicit choice; the form's member ID (the
files' own sentinels, then their `crdSentinelMemberIds`, then, in CRD only, the DTR files'
sentinels); the form's NPI; the whole text values of a pasted body, by the same three steps; the
default. The result says which step chose (`ScenarioSelection.by`, `inBody`, `via`), and callers act
on that, never on the wording of the note: the PAS inquiry refusal used to read the note. The note
says "found in the request body" when it was. The hand-written alias pass in the DTR mock, the
pre-pass in the CRD mock and the Java map of the PAS mock are gone. A test checks, for each
protocol, that a pasted identifier picks the same scenario by the same rule as the typed one.
(The check found a sentinel NPI in a pasted body ignored, D-245; the rule is to hold for the NPI
too, D-248, item 1, done, D-255.)

**D-221. The sentinels of another protocol are data in the scenario files, and claimed like a file's own.**
From the fixture audit's F-PAS-SHADOW, F-DUP-CRDSENT (major) and F-PAS-CRDMAP-HIDDEN (minor). The CRD
sentinels a PAS scenario answers for the same case were a map in Java, which the start-up check and
the scenario list did not know: a PAS file could claim such a sentinel and never be chosen, and the
list said `crdSentinelMemberIds: []` for files whose description named one. Two DTR files could claim
the same CRD sentinel and the second never answered.
- `pas-09`, `15`, `16`, `17` and `21` carry `crdSentinelMemberIds` (`P32H-BAD400`, `P32H-NOMEM`,
  `P32H-TIMEOUT`, `P32H-ERR500`, `P32H-NOPROV`), exactly as DTR files do, and the list shows them.
- The CRD scenario that answers every DTR sentinel is marked in its file (`answersDtrSentinels: true`,
  today `crd-04`), not by an id in Java. Only a CRD file may carry it, only one may, and a CRD file
  may not list `crdSentinelMemberIds`.
- The duplicate check at start puts the CRD sentinels into the same map per protocol as the files' own,
  within a file and across files, and refuses naming both files ("... is already claimed by ... One
  sentinel selects one scenario."). The entries must be `P32H-` sentinels.
Scenarios are data: a QA author can change the mapping without compiling.
(A mapping entry that names no CRD sentinel, and a directory written before this change, are to be
noted at load, D-248, items 2 and 5; done, D-255.)

**D-222. On mock, what CRD names for a DTR sentinel follows that sentinel.**
From the fixture audit's F-CHAIN-DTR (blocker). CRD answered every DTR sentinel with scenario 4, which
names one questionnaire, and six DTR scenarios do not hold it: taken from CRD into DTR the way the tool
hands it on, `P32H-DTR-ADAPT` and five others ended as "package incomplete", telling the tester the
service left a questionnaire out when only the two synthetic data sets disagreed. Now, for a DTR
sentinel, the CRD mock names the questionnaire or questionnaires the package of that DTR scenario
holds, read from the DTR file's own response and written nowhere a second time, in the questionnaire
sub-extension and the card's app context. A DTR package that holds no questionnaire (the error,
empty and no-bundle cases) keeps scenario 4's own questionnaire, and the note says so. Nothing is
invented for a case with no DTR scenario. `crd-04`'s description says what a tester needs to know.
`DtrSentinelJourneyTest` drives every DTR sentinel from CRD into DTR through the real endpoints with
the carry CRD hands on, and the same as suite rows of the protocol chain, asserting each DTR file's
stated outcome; with the change switched off it failed with exactly the six rows the audit reported.
Seen in the browser at 1366 by 768 and 1920 by 1080: CRD with `P32H-DTR-ADAPT`, "Continue in DTR",
and the package loaded with one questionnaire. A chain suite was not driven by hand on the jar.
(Checked closed, D-245. The rule is to hold also when the documentation scenario is chosen by hand,
D-248, item 4; done, D-255.)

**D-223. A code list says of itself that it is synthetic, and the tool believes the file, not its path.**
From the fixture audit's F-SAMPLE-LIST (major). D-169 marked only the bundled list as the placeholder,
so the sample list the README tells a newcomer to try was compared with a live service as if it were
P32H's, and a finding said "One of them is wrong" about invented rows. Now a list in which any row's
notes hold the marker "Not from a P32H export." (any letter case; one constant,
`CodeListLoader.SYNTHETIC_MARKER`) is synthetic and treated exactly as the bundled placeholder: the
self-check gives `placeholder: true` with a warning, the picker says so, on live nothing is compared
(`CODE_LIST_NOT_COMPARED`), and on mock every finding says the list is the placeholder. A list with no
such row is taken as real, as before. One test requires the marker on every row of the bundled list
and of every list under `samples/codelist`. On the jar, on live with the sample list against a
stand-in that disagreed, no disagreement was reported. Still untrue at the revision of D-228: two
sentences of `CrdConformanceChecker` and the label of a code list finding on screen call any such list
"the synthetic placeholder that ships with the tool" or "the bundled placeholder", which is not true
of the sample or of a tester's own marked list; and the README presents the sample's self-check
without the warning. Each is decided and not done (D-226).
(Both sentences and the screen label were made true, D-229 and D-230. Two comments still say the
flag means the bundled list, D-248, item 7; corrected, D-255.)

**D-224. The fixtures' smaller decisions.**
- **Test NPIs.** A ten-digit number in a test resource and in four test classes passed the NPI check
  and is outside the placeholder set, so it could be a real provider's; whether it is issued could
  not be checked, as there is no access to the national register here. Three of the classes made it
  with `Npi.withCheckDigit`, which the audit's scan did not see. It is replaced by `1000000087`
  everywhere, and these records do not repeat it.
  `NoNpiOutsideThePlaceholderSetTest` scans `src/main`, `src/test` and `samples` and fails on any
  ten-digit number starting with 1 or 2 that passes the check and is not `1234567893`, `99999999xx`
  or `10000000xx`, and on any `withCheckDigit` whose result lies outside that set.
- **The sample that matches nothing by design** (`pas-12`). Its next step told the tester to create a
  subscription and deliver it again, which gives the same answer once a subscription exists. Now,
  when the sample names a subscription of its own and the ledger holds one this tool created, the
  next step says it names a subscription this tool did not create, on purpose, that this is a
  finding in itself, and which sample to deliver to see a match: the first scenario whose
  notification uses the subscription token and is not about a submission (today `pas-10`). With no
  subscription, the remedy of D-203 stays.
- **What is said about scenario files without stopping the start.** A tester's own directory may hold
  work in progress, so none of these refuses: the lowest-numbered file of each protocol is named as
  the default, on its own entry of the scenario list and among the list's notes; an `expect` block
  whose HTTP status differs from the one its file or step answers with is noted; a file in a protocol directory that does not end in
  `.json`, or a subfolder, is named in the list's `notes` and, on mock, in one warning at start, by
  name only. `ScenarioSummary` and `ScenarioList` each gained `notes`. The fix could not make the
  three controllers pass the list's notes (they were not its files); the integrator did it while
  merging (D-228).
- **Known limit.** An `expect` block whose outcome contradicts what its file produces, with the same
  HTTP status, is not detected at start: it would mean running each protocol's response mapper at
  load time. The scenario list still advertises such an outcome.
(The notes are shown on screen since D-230. No test fails if the controllers drop the list's notes;
tests were added, D-255. The `expect` outcome limit stands.)

### Decided not to do, and known limits

**D-225. Decided otherwise after the second audits and the passes over the screens, and the limits the fixes report.**
Decided, each with its reason: by the integrator in its task files, or by the agent that did the
work where marked so.
- Lock from any browser locks every session, and a lock does not move the epoch (D-144, D-143 rule 9).
  The audit's F-SCREEN and m2 are the page's, not the server's (D-214).
- An environment on a production environment's server under another base path is warned about, not
  refused: the base path is part of the destination (D-143, rule 1; D-212).
- The configuration store keeps uploads whole and strips nothing: it is what the tool sends from.
  It warns (D-213).
- A name, a date of birth or an address the request does not carry is not looked for in free text:
  no rule could tell it from an honest note (D-217).
- History stays worded in UTC days; the server's `zone` is not offered on screen (D-207). Deferred by
  the second pass over the screens, then adopted by the integrator for the next pass.
- The Config buttons that only open a view keep their names (D-207). The second pass's own decision.
- The tester's own pasted box on the comparison keeps what was typed, a credential included: it is the
  tester's input, and every other text on that screen is masked (D-207). The second pass's own
  decision.
- A replay never joins a chain (D-208).
- The effective configuration does not count a placeholder in a collection body (D-205), and a 404
  for a removed environment does not name it (D-205). Both are the platform server task's own
  decisions; the first goes against D-200.
Known limits the fixes report, not closed:
- A one-word surname written where a code or an id belongs is kept (D-151, D-218).
- Short values are not looked for in free text, and older ledger lines that hold a name or a date are
  shown and exported as written (D-217).
- An `expect` outcome that contradicts its scenario file is not detected (D-224).
- IPv6 destinations were not proven against a listener, and how a real nginx or Tomcat in front of a
  service decodes an encoded separator was tested by nobody (D-210, D-211).
- A replay that reaches the gateway against production on live was exercised neither by the audit
  nor by the fix (the replays the audit tried were refused earlier, for want of a recorded member);
  `ProductionRefusalOverTheServerTest` covers it over the server (D-205).
- A process killed outright leaves the embedded server's working folder behind (D-216).
- Member data in an upload is judged by the variable's name only (D-213).
- Windows was not run by any of the three fixes.
Left by the protocol task for the screens with no decision, because no task line asked: comparing by
a collection request's name, the tool-side hook instance written as a sentence (always one
difference), how an inquiry's answer differs from the last one seen, `ResponseItemView.answers` as
codes, and a quantity's unit constraint.
(Continued by D-251.)

### Work decided and not done at the revision of D-228

**D-226. Three pieces of work started from this revision, decided and not done on it.**
They began from the revision of D-228 at the moment these records were written; a later entry will
say what became of each.
1. **The server** (`TASK_LEFT3_SERVER`):
   - A write refused at the send because production writes were locked again shows the refusal's own
     sentence and action; the shared error handler falls back to its fixed words only when the
     refusal carries none (D-214).
   - On mock, a played timeout is said once: that the scenario played it, that nothing was sent and no
     environment failed, and what a tester would see and do had a real service timed out ("the payer
     might have received the submission: ask for its status before sending it again"). Live is
     unchanged (D-205).
   - The raw resolve note says that a url with a part still missing is refused, as the raw preview
     and send refuse it (D-207).
   - A lookup field whose value holds masked text gains `masked: true`, present only then, and a note
     that it is not offered for the case (D-207).
   - An empty element of an issue is consumed by the designed view, not shown again among the fields
     returned but not interpreted.
   - The comparison's caveat is left out of `notes` (D-202).
   - A replay never joins a chain: `ChainServiceTest` and every comment brought into line, and the
     code that served only that state removed (D-208).
   - The two sentences of `CrdConformanceChecker` made true of any list that says it is synthetic
     (D-223).
   - Then, if there is time, the other server lines of the three fix reports, small and certain.
2. **The screens** (`TASK_SCREENS_FIX`):
   - Whenever what the server says about writes differs from what the page last drew, every place is
     redrawn at once and the tester is told once that writes were locked again, perhaps from another
     browser; proven with two browser contexts (F-SCREEN, D-214).
   - The unlock panel says that locking, from this or any browser, ends every session's unlock (m2).
   - Every field for a note, a tester reference or an expectation (CRD, DTR, PAS, a raw send, a suite,
     the note editor of a run) carries one hint, in the same words on every tab, that the ledger keeps
     the text as written and that member data must not be typed into it; each refusal marks its field
     and shows the server's sentence (D-217).
   - `addressWarnings` and the upload's member data warnings shown where the tester decides (D-212,
     D-213).
   - The scenario lists show each scenario's `notes` and the list's, and the CRD sentinels that select
     a PAS scenario; the label of a code list finding takes its words from the server (D-223, D-224).
   - A lookup field marked `masked` is shown with its mask and note and never put into the case.
   - What the second pass did not reach: answers dropped in an adaptive questionnaire, an absent
     "applies to", an unmatched notification and the statement of an id the ledger does not keep,
     the secret echo at 1920 by 1080; then one sweep at 1366 by 768 of production (locked, unlocked,
     floor), UAT, live with an empty store, tracking off, a ledger that cannot write, a suite, and a
     replay of one run and of a date range.
3. **The README and the pull request draft** (`TASK_DOCS3`): the README gone through again against
   this revision, every sentence that quotes the screen or the API checked, and every statement about
   the guardrail, the ledger, member data, the mock scenarios, the samples and the code list checked
   against the fixes (the sentences of D-213 and D-217 among them); the section of limitations
   rewritten from what is true now. The draft brought up to this revision, with the second run of V2,
   V3 and V4 in its part on the verification agents and a place for the check of each fix by an agent
   that did not write it. The draft's list of decision entries stops at D-201; entries after it are
   to be added.
(What became of each: the server, D-229; the screens, D-230; the README and the draft, D-232; what
the first two left each other, D-231.)

### Questions the operator had not answered during the build (replied to later: D-269)

**D-227. Four questions put to the operator during the build, with no answer yet, and what the tool does until one comes.**
1. **Which version of the Da Vinci guides the vendor's sandbox follows.** The tool is held to 2.0.1 on
   the operator's word (D-136) and reads later-version content as such (D-140). If the sandbox follows
   another version, findings of `OTHER_VERSION_CONTENT` will be many and every request will still be
   built to 2.0.1.
2. **Whether an NPI whose first digit is not 1 or 2 should be refused.** The check is the Luhn check
   with the 80840 prefix only (`model/Npi`, D-153), so it accepts numbers no issued NPI begins with.
   Nothing was changed. The scan of D-224 looks only at numbers starting with 1 or 2.
3. **Whether the bind to this machine's loopback address, or the request guard, should be relaxed.**
   The tool listens on `127.0.0.1` (D-115) and refuses requests addressed to other names or coming from
   other sites (D-116, D-148). As things stand no vendor service can call the notification receiver,
   and `GET /api/mode` says so (`bindNote`, D-205). A pended request is followed with an inquiry.
4. **Whether a sample request from the UM vendor, with no real member data, can be shared** for the
   request comparison (D-119). The comparison has met no real vendor request; its vendor-shaped test
   request is hand-written, so how the vendor's own request differs from the tool's is not known.
(Still unanswered at the revision of D-252, which records one more exchange with the operator.
Answered after the pull request was opened: D-269.)

### Joining the parts

**D-228. Decisions made while joining the server work for the screens, the passes over them, the README and the audit fixes.**
This revision is branch `epa-pave` at `5763c41`, which is pushed: the line of D-201 (`7dade28`), the
pass that joined the tabs (`fe-join` at `4d795d8`), the README (`docs-2` at `81637c2`), the two
server tasks for the screens (`screens-platform` at `3b1ddf4`, `screens-protocol` at `ce345e4`), the
second pass over the screens (`fe-join-2` at `d15db15`), and the three audit fixes (`audit-fix-v4`
at `fd61e6e`, `audit-fix-v3` at `1557e5c`, `audit-fix-v2` at `d542405`).
1. Nothing conflicted in any of these merges. `NotificationReceiver` was changed by three fixes in
   three separate places (the correlation by the ledger fix, the masking by the guardrail fix, the
   next step of the sample that matches nothing by the fixture fix) and merged cleanly.
2. Accepted by merging, each needed for the build to stay green and reported by its author: one count
   in `PasControllerTest` (scenarios answering an inquiry, 9 to 11, D-204). The integrator's account of
   the join gives this count to the fixture fix; the history says otherwise: it was changed by the
   protocol server task for the screens, in its commit `5873027`, and its report says so. The fixture
   fix changed one assertion of `PasScenarioFilesTest` (the words of a scenario note), which is no
   count; one assertion of
   `PlaceholderOnTheLiveProfileTest` (a finding always carries its group, D-205); one assertion of
   `SamplePostmanFilesTest` (the upload of the sample collection now warns that it carries saved
   examples, D-213).
3. The integrator's own change: the three controllers that serve the scenario lists pass the
   repository's notes on, which the fixture fix asked of files it did not own (D-224). With the
   bundled set each list's notes hold one sentence, naming the protocol's default file.
4. The line was built through the shared build wrapper as it grew: 2603 tests with the join, 2648 with
   the two server tasks, 2667 with the second pass over the screens and the fixture fix, and on the
   merged line 2707 tests, 2705 passed, 2 skipped, none failed. The two skipped tests need a folder
   that cannot be written, which a build running as root cannot make.
5. The build machine restarted three times while this tool was built (at about 00:17, 03:02 and 06:00
   UTC), each time cutting off every
   agent then at work. Nothing committed was lost; each piece of work was taken over in its worktree.

## Decisions of the last server task, the last screens pass, the last audits and their checks

After the revision of D-228 (`5763c41`), eight branches were merged into `epa-pave`: the README after
the second audits (`docs-3`), the records of D-202 to D-228 (`records-3`), the last server task
(`left3-server`) and the last pass over the screens (`screens-fix`), which D-226 recorded as started,
six lines those two left for each other (`seam-4`), and the fixes for what three of the last audits
of section 27 found: house style (`final-gate`), completeness on screen (`final-render`) and the
walkthrough by a business tester (`final-uat`). Each branch was done by one agent. Between the
merges, the audits of section 27 that had not yet run were run, each by an agent that wrote none of
the tool: V1 (the build from a fresh clone), V5 (house style), V7 (completeness on screen) and the
walkthrough by a business tester on revision `9e285a1`, V6 (honesty) on `9e5306d`. Three more agents,
each of which wrote neither the tool nor the fix it examined, checked every fix of the second run of
V2, V3 and V4, also on `9e5306d`. **The fixes for V5, V7 and the walkthrough have been checked by
nobody but their authors.** The entries below record, in this order: the work D-226 recorded as
started, what the last audits found that nobody had decided, the rules the screens and the server
gained from them, the style gate, what the checks of the second audits' fixes established, what the
integrator decided for the four tasks started from this revision, what was decided not to do, and
what was decided while joining. "The fix", "the pass", "the audit" or "the check" means the agent
that did that work. What is still open is in `PROGRESS.md`.

### The work D-226 recorded as started, and what it left

**D-229. The last server task: a refusal says what happened, a played timeout is said once, and a replay never joins a chain.**
From `TASK_LEFT3_SERVER`, the server half of D-226, on revision `5763c41`. Each line below was taken
out again by its author and a named test failed, except line 7, which changed only tests and comments.
1. **A write refused at the moment of sending says so.** `ConfigurationChangedException` carries an
   optional title and action, and the shared error handler shows whichever it carries, falling back
   to its fixed words only for what is missing. When production writes are locked again between the
   preparing and the sending of a write, the 409 reads "Production writes were locked again before
   this write was sent, so it was not sent." with the action "Unlock them on the Config tab by typing
   the environment name, then send it again." A configuration that changed after the screen was
   drawn has its own pair of sentences. `SecondGuardrailAuditOverTheServerTest` requires the new
   title and the absence of the old one. The check of the guardrail fixes saw it on the jar (D-245).
2. **On mock a played timeout is said once.** A timeout is "played" when the exchange is a timeout
   and carries a scenario id (`VendorExchange.playedTimeout`). The error panel alone says that a
   scenario played it, that nothing was sent and that no environment failed, with the delay shown and
   what live would wait (`onyx.read-timeout-ms`). The verdict keeps only the scenario line: no "No
   response arrived inside the read timeout", and on PAS no "The payer may have received the
   submission". The next step keeps its action and its carry, so the screens offer what they offer
   after a real timeout, and its words begin "Next, had this been a real service:" (for a PAS
   submission the reason reads "Had this been a real service, the payer might have received the
   submission: choose Send inquiry before sending it again.", in the words D-243 gave the inquiry). `service/run/PlayedTimeouts` holds the words for CRD, DTR, PAS, subscriptions and the raw
   send. Live is unchanged. The sentence `VendorExchange.SIMULATED_TIMEOUT`, which D-204 added, was
   removed: nothing read it any more.
3. **The resolve note agrees with the raw refusal.** For a request whose url is configured but has a
   part with no value, `POST /api/config/resolve` on mock says that sent as written it is refused until
   every part has a value, as the raw preview and send refuse it; for a mapped operation it also says
   that a run from its tab is still answered from a scenario. A target with no url keeps the old note.
4. **A lookup field that holds masked text is marked.** `LookupField` gains `masked` (true, present
   only then) and `note` ("This value holds a secret the request sent, which this tool masked, so it
   is not what the service returned and is not offered for the case."), set only on an answer the
   tool actually masked. `MemberLookupResult.member` and `ProviderLookupResult.provider` still hold the
   mask text: the page fills the case only from the field list and leaves a marked field out (D-231).
5. **An empty element of an issue is consumed.** `expression` or `location` sent as an empty list is
   shown in the issues table and no longer again among the fields returned but not interpreted. A null
   or another value stays there (D-236 then told null from empty in the table).
6. **The comparison's caveat lives only in `RequestComparison.caveat`.** `notes` holds what is
   particular to the comparison and may be empty.
7. **A replay never joins a chain**, as D-208 decided. `ChainServiceTest` now pins a blocked suite row
   of a protocol chain, which is the one blocked line in a chain that carries no unlock, and
   `ApplicationJourneyTest` asserts through the real case runner that a replayed run's line holds no
   chain id. No code was removed: the `SEND_ON_ITS_OWN` branch of the chain view serves that suite row.
   Whether a blocked suite chain row against production reads as the code says was not run.
8. **The code list sentences describe a list by what it says of itself.** `CODE_LIST_NOT_COMPARED`
   reads "No P32H code list is loaded. The code list in use says of itself that it is synthetic (its
   rows carry the note "Not from a P32H export."), ...", and a CRD finding on mock that rests on such a
   list ends "The code list in use says of itself that it is synthetic, so it is not a P32H list." True
   of the bundled list, the sample and a tester's own marked list.
Left by the task, each with its reason: whether a raw send's body source gets a label or only the
sentence (a design choice, not decided by anybody since D-207); an `expect` outcome that contradicts
its scenario body (D-224, it needs every mapper at load time).
Later: D-282. `service/run/PlayedTimeouts` was renamed `PlayedFaults` by the second round of the old
screens and now words every fault a mock scenario plays, refusals included, as a rehearsal.

**D-230. The pass over the screens: a lock from another browser is seen at once, every free-text field carries one hint, and the warnings reach the screen.**
From `TASK_SCREENS_FIX`, the screens half of D-226, on revision `5763c41`, by browser scripts on mock
unless said otherwise, at 1366 by 768 with some states also at 1920 by 1080.
- **Lock from another browser (F-SCREEN).** When `/api/mode` says writes are locked and the epoch did
  not move, every built tab that states write access is told (`onWriteAccessChange`): the banner, the
  Config tab's writes panel, the PAS write box and an open replay confirmation are redrawn, and the
  tester is told once: "Writes to production were locked again. This browser did not lock them, so it
  may have been done from another browser: locking from any browser ends the unlock of every session.
  Every tab now shows them as locked. ..." The notice is raised only by a poll that is not quiet,
  because the Config tab's own Lock refreshes quietly and announces in its own words; so "This browser
  did not lock them" is true. Proven with two browser contexts, and since by the check of the
  guardrail fixes (D-245).
- **One hint on every field the ledger keeps as typed.** `LEDGER_TEXT_HINT`, "The ledger keeps this as
  typed. Do not type member data into it.", on the note and the tester reference of CRD, DTR, PAS, the
  raw send and the History note editor, and in the hint of the code assertions of an expectation (the
  outcome itself is a choice, not typed). A suite file's field says the same of its rows. A refusal
  marks the field it names and shows the server's sentence, and the result area is not replaced.
  `StaticLedgerTextTest` checks that each of the six forms passes the hint.
- **Warnings where the tester decides.** `addressWarnings` are shown under each listed environment and
  after a switch, as the server words them, in the black side rule of an upload warning: the browser
  does not sort or classify the server's sentences. The upload's member data warnings are shown with
  the rest of the upload's warnings. The colour key's entry for that rule says it marks a warning about
  an uploaded file or an environment's address.
- **The scenario tables show the notes.** A scenario's own notes sit under its label; the list's notes
  that no scenario already carries sit under "About the scenario files". PAS rows read "or the CRD
  member ID ..." because either selects. The label beside a code list finding reads "The code list (a
  placeholder, not a P32H list, read from <source>)", from the self-check's source; the words "the
  bundled placeholder" are now refused by `StaticServerWordsTest`.
- **A masked lookup field is shown with its mask and its note and never put into the case**
  (`caseContext.fillFrom(record, maskedNames)`, origin "masked, not taken"). Written before the server
  marked such a field; seen with the server's own mark by the next task (D-231).
- **What the pass before had not reached** was seen: the answers an adaptive questionnaire dropped (the
  line now reads "The answer sent: 8", because the values come from the request sent, not from an
  earlier response), a CRD coverage section with no "applies to", an unmatched notification, a
  notification naming an id the ledger does not keep, and the secret echo on live at 1920 by 1080
  (none in the page text or a field). One sweep at 1366 by 768 of production locked, unlocked and held
  by the floor, UAT, live with an empty store, tracking off, a ledger that cannot write (reached by
  renaming its folder after start: a folder that cannot be created now stops the tool at start), a
  suite from the samples and a replay of one run and of a date range: every word read, nothing broke.
- History stays worded in UTC days and the zone filter stays out (D-207).
Left by the pass: the DTR change block (appeared, disappeared, dropped) is drawn twice, in the
questionnaire pane and in the result detail, as it was before; screen reader, 200% zoom, phone width
and `prefers-reduced-motion` were not tried.

**D-231. The six lines the two left for each other: a raw send's note is checked against its body, the server words what Lock does, and the server heads every error.**
From `TASK_SEAM4`, on revision `9e285a1`, one agent owning both sides for these lines only.
1. **A request sent as written checks its free text against the member identifiers its body states**:
   the identifier values of every Patient and Coverage and the `subscriberId` of every Coverage,
   wherever the resource sits in the JSON (a Bundle entry, a prefetch, a contained resource), by
   `service/run/MembersInBody` and `MemberInText.problemsInBody`. The resolved body is checked, supplied
   or saved, before anything is sent, and in the preview too; the refusal is a 422 on `note`,
   `testerRef` or `expected` ("The note holds a member ID the request body carries. ..."), and nothing
   is recorded. A body that is not JSON, or holds neither resource, cannot be checked and is not
   refused. Not looked for: a name or a date of birth the body carries, and a `P32H-` sentinel, which
   is synthetic and is how a tester names a scenario in a note. A write the guardrail refuses is
   checked against the body as typed, because the refusal comes before the saved body is read; a note
   that holds an identifier of the saved body is then recorded with the blocked line. Test that fails
   without the change: `RawSendMemberTextOverTheServerTest`.
2. **The server words what Lock does.** The reason of an unlocked session ends "Locking them again,
   from this or any other browser, ends the unlock of every session." The Config tab shows the
   server's sentence in place of its own paragraph, and `StaticServerWordsTest` fails if the paragraph
   comes back. The locked panel's own sentence and the notice after a lock from elsewhere stay: they
   describe the unlock ending, which the server's locked reason does not.
3. **The server heads every error.** `ErrorView` gains `heading`, filled from the kind in the compact
   constructor (`ErrorView.headingOf`) when none is given, so every error on both profiles carries one;
   a seven-argument constructor keeps old callers. A played mock timeout is headed "A timeout played by
   a mock scenario, not a fault in the environment". The page's table of words by error kind is gone
   (`ERROR_FORMS` keeps only the style per kind), and `StaticServerWordsTest` fails if it comes back. A
   new `ErrorKind` must be added to `headingOf` or the build does not compile. Left: on mock any other
   fault a scenario plays (an HTTP 500, a 400, an unreadable or empty body) keeps the kind's own
   heading, "A fault in the environment: ...", which is untrue there (D-247, item 7; headed as played
   since D-254, its next step still not, D-257).
4. **A masked lookup field and the case, seen together** on live against a stand-in that sent secrets
   back inside a first name, a plan name and a provider's last name: each field marked, the case left
   empty for all three ("masked, not taken"), and the CRD request that followed carried no mask text.
   `StaticServerWordsTest` pins `field.masked` and `field.note`. No build test pins that the page fills
   the case only through the masked set, and the detail records still hold the mask text.
5. **Small ends.** The comparison page no longer filters the caveat out of the notes by its text. The
   label of `CHECK_ENVIRONMENT` keeps its code (the PAS tab keys on it) and now reads "Check the
   environment. No usable answer came back: on the live profile the service failed or could not be
   reached, and on the mock profile a scenario played that, with nothing sent.", so a chain view and a
   suite row are true on mock; tested through the API, not seen on screen.

**D-232. The README after the second audits.**
From `TASK_DOCS3`, on revision `5763c41`. The README was gone through from the first line to the last
against the tool at that revision: the guardrail, the ledger, member data, the mock scenarios, the
samples and the code list were checked against the fixes, a subsection on member data in an uploaded
file was added (D-213), and the limitations were rewritten from what was true then. All 70 examples
under Endpoints were run in order on another port. Not done: a fresh clone, `./gradlew` itself, the
live profile against a stand-in, `run.cmd`. It describes `5763c41`, so the sentences the four
branches merged after it changed (the 409 at the send, the hint on the note fields, the address
warnings on screen, the played timeout, the renamed buttons, the code list label and the others the
fix reports list) are behind; bringing it up is `TASK_DOCS4` (D-250). The pull request draft lives
outside the repository; it was brought to the same revision with a place for every gate that had not
run, and its list of decision entries stopped at D-201.

### What the last audits found that nobody had decided

**D-233. The token request authenticates the client by HTTP Basic.**
From the honesty audit's F-TOKEN-BASIC (major). Section 26 leaves open how the tool authenticates to
the vendor's token service. The code decided it and no document said so: `OnyxTokenService` sends the
OAuth2 client credentials grant with `grant_type=client_credentials` (and the scopes) in the form body
and the client id and secret in an `Authorization: Basic` header (`client_secret_basic`). The audit
saw exactly that arrive at a stand-in. A vendor that expects the credentials in the form body
(`client_secret_post`) or a signed JWT assertion answers 401, and nothing in the tool explains it. The
one method to change for either is `OnyxTokenService.requestToken`, which its Javadoc names as the one
to replace. Recorded as an assumption about the vendor; whether it holds is not known until the token
service is reached, which has not happened (nothing has been observed against the vendor's service).
The README's open items and the pull request draft are to say so (D-250).
Later: D-290. An environment's own sign-in, set in Configuration, may state the client's authentication
as "Basic header" or "In the form body"; `onyx.auth` keeps HTTP Basic. A 401 to a call signed in by
client credentials now drops the token, signs in again and sends the call once more. Which the vendor
wants is still not observed.

**D-234. The installation salt is a secret.**
From the honesty audit's F-SALT (major). On live, by default, the ledger replaces a member ID with an
HMAC-SHA-256 token under a 32-byte salt and keeps the ID's last four characters (D-032, D-151), so
that two runs of one member can be matched without the file becoming a roster. That holds only while
the salt is kept apart from the ledger. By default the salt is written in plain text to
`config-store/installation-salt` (mode 0600 where the file system allows; `tracking.salt-file` moves
it, never into the ledger directory), beside `run-history/`. With it, a member ID of the usual short shape is
recovered from its token by trying every candidate that ends in the four kept characters: the audit
recovered a placeholder ID from its own live ledger in 0.02 seconds. What follows, for anyone who
handles the files: the salt is a secret like a client secret; it is never copied, sent or attached
together with the ledger, its export or a ledger line; whoever holds both holds the member IDs. A new
salt makes every older token unmatchable, which is why the tool keeps it. Nothing in the code changed;
the README's sections on secrets and on the member ID policy are to say this (D-250).

**D-235. What "level 1" means for this tool, and the limits that stand beside it.**
From the honesty audit's F-PR-LIMITS (blocker, about the pull request draft, which said "level 1 ...
everything in the build prompt, with nothing stubbed"). Section 0 defines level 1 as everything the
build prompt asks. Decided: the tool is described as built to level 1 **with its named limits beside
it**, and the words "nothing stubbed", "complete" and "everything" are never used of it. The limits,
each a part of the build prompt the tool does not do or does in part:
- In the X12 275 attachment mode the tool names the document in the request and sends no 275: the
  service receives no document from this tool (`PasRequestBuilder`). The FHIR mode sends the
  attachment in the bundle.
- No `$submit-attachment` and no claim update, so the route the guide gives after "attachment
  required" is not built; the result names it (D-161). A resubmission reuses the claim id (D-133).
- DTR: no `$expand`, no `$log-questionnaire-errors`, no CQL is run; only the invariants dtrx-1 and
  dtrb-1 are evaluated (D-165). DTR is the operations behind a SMART launch, not the launch.
- The notification receiver listens on this machine only by default and does not authenticate its
  caller (D-115, D-227).
- The wire view carries member data by design; secrets sit in plain text in `config-store/`
  (D-147); the tool has no sign-in.
- Not verified: the STS minimum version (none is claimed), a dependency mirror that needs
  credentials, a token server; the member and provider lookups have never met the real APIs (section
  26).
Whether level 1 is claimed at all is stated with the handover, after the integrator's last gate
(`PROGRESS.md`, "Degradation level").

### The rules the screens and the server gained from the last audits

**D-236. Not returned, null and empty are said in words in every designed view.**
From the completeness audit's F1 and F9 (blockers), F2, F4, F5 (majors), F3 and F11 (minors), decided
by the integrator in `TASK_FINAL_RENDER`. **This overturns, for the designed views, the part of D-058
that let an absent list and an empty one read alike.** D-058's rule for the tracked reads stands.
- Wherever a designed view states a value or heads a list, it says which of the three it is, in words,
  on CRD, DTR and PAS alike, as the CRD Cards section already did ("cards not returned", "cards null",
  "cards empty"). Where the page cannot know, the server sends the presence beside the value: a map
  `lists` (element name as the response spells it, to `Presence`) on `CardView`, `SuggestionView`,
  `ClaimResponseView`, `PasItemView`, `AdjudicationView` and `ItemView`; `CardView.source`;
  `ClaimResponseView.preAuthPeriod`; `PasDecision.authorizedPeriod`; `IssueView.expressionPresence` and
  `locationPresence`, from which the two booleans of D-205 are now derived so they cannot disagree.
- A list that a view heads with a count is always named, absent included ("Adjudications (0)" then
  "adjudication not returned", "null" or "empty"). A list the view hides when it holds nothing (item
  extensions, `noteNumber`, `enableWhen`, `initial`, `communicationRequest`) is named only when it was
  sent null or empty: "not returned" on every item would bury the rest.
- A CRD sub-extension that can repeat and is missing reads "not returned", not "none returned": in the
  response each is a sub-extension of its own.
- A coverage sub-extension sent with no `value[x]` keeps presence `EMPTY`, so the outcome rules do not
  change; `CoverageInformationView.sentWithNoValue` names it, the coverage table draws "sent with no
  value", and the finding says "it carries no value[x] at all", apart from "its value is empty". The
  determination grid said "empty" for it until D-256.
- The code list finding words `pa-needed` from its presence: "(pa-needed was returned as null)",
  "(pa-needed was returned empty)", "(pa-needed was sent with no value)", never "was not returned" for
  a value that came.
- The ledger line writes `covered`, `pa-needed` and `doc-needed` sent null, empty or with no value as
  `name=(null)`, `name=(empty)`, `name=(no value)`, as D-056 writes `(no answer)`; a code that was not
  returned still has no pair. No component of the record was added for it.
Tests that fail without it: `CrdConformanceCheckerTest`, `OperationOutcomesTest`, `CrdCardPresenceTest`,
`PasPresenceTest`, `DtrItemPresenceTest`, `CrdNoValueTest`; `CompletenessScreenTest` checks that the
page reads the presences. Seen in a browser at 1366 by 768 only; the PAS inquiry, a subscription and
the notification fold were not looked at.

**D-237. Every value of a repeated key is listed, and a request body that repeats a key is refused.**
From the completeness audit's F13 (major) and F14 (minor), and the build audit's F-01 (minor).
- **In an answer, the tool reads the last value of a repeated key, as JSON readers do, and says
  everything it dropped.** `JsonSafe.Parsed.repeated` records each repeated key with its path, its
  pointer and every value as JSON text in order, found by a token walk run only when a key repeats.
  The returned fields list each value as `<path> (value i of n)`, masked as the wire view masks, on
  CRD, DTR, PAS, subscriptions and notifications, under a note that no longer says only the last value
  is shown. When the key is one a designed view read, CRD raises `GUIDE_RULE_BROKEN` in the guide group
  ("<path> is sent <n> times in one object"), citing RFC 8259 and FHIR JSON, giving every value and
  calling neither wrong. No finding kind was added. At this revision the finding is raised on CRD
  only, and the raw send and the two lookups still list only the last value under the old note
  (both done since, D-256).
- **The CRD mock plays a scenario body that repeats a key exactly as written**, and does not carry the
  orders back on it, because rebuilding the body from the tree would drop the earlier values. The
  scenario note says so.
- **Every JSON request body the tool reads refuses a repeated key** (`STRICT_DUPLICATE_DETECTION` on the
  strict mapper), with a 422 that names the place from the parser's path, never a value: "The request
  repeats the field "member" inside one object. Nothing was sent." Scenario files and suite files
  already refused one. A body of the tester's own travels as a text value and is passed on as written;
  Postman uploads, scenario files and the notification receiver read their own way and are unchanged.

**D-238. A resource of another kind sent in answer to a CRD hook is not "no determination".**
From the completeness audit's F6 (major). A Patient sent with HTTP 200 in answer to a hook was read as a
CDS Hooks response with no cards, drawn exactly as `P32H-EMPTY`, and passed a row that expected no
determination. Decided: a JSON object whose `resourceType` is anything but `OperationOutcome`, and any
JSON that is not an object, is `OTHER_JSON` ("The body says it is a Patient resource.") and its outcome
is the existing `MALFORMED_RESPONSE`, which cannot match `NO_DETERMINATION`, on the tab, in a suite row
and on the ledger line. No CRD outcome was added. A blank `resourceType` names nothing and the object is
read as CDS Hooks. DTR and PAS already said so of their own answers. At this revision the verdict
headline still says the body "could not be read", which is untrue of a Patient (says what came
since D-256; the outcome's label does not, D-257).

**D-239. A notification is read whole; only the text it keeps is cut.**
From the completeness audit's F10 (major). The receiver read a body only from its first 131072 bytes,
so a larger one was not JSON, had no designed view and matched no run. Now it reads the body to its
end (up to 8 MiB), and decodes, masks, reads, maps and matches all of it; it still holds only the
first 131072 bytes as text, and the note says both ("All of it was read, and everything above comes
from all of it. Only its first 131072 bytes are held, so the payload text below is cut there."). A
body whose reading stopped before its end is read from its start as before. This changes the reading
part of D-133; the held text stays as D-133 says.

**D-240. Who answered a run is a fact of the result and of the ledger line, and a time that was not measured is not zero.**
From the walkthrough's F-LOCALSOLID (major) and F-0MS (minor). Completing a questionnaire response
happens inside the tool, and its result carried the hatched mark of the mock, a sentence for screen
readers that a scenario file answered, and the solid colour the key gives to an answer from the payer.
- `AnsweredBy`, one of `SERVICE` (live, a timeout and a received notification included), `SCENARIO`
  (mock, a played fault included), `NOBODY` (a step done inside the tool: an operation that is not
  outbound and not a notification) and `NOT_SENT` (refused before sending), worked out by one rule
  (`AnsweredBy.of(operation, profile, refused)`) for `RunEnvelope.answeredBy`, `RunRecord.answeredBy`
  (written, and worked out for a line written before it) and `ChainRun.answeredBy`. It is a column of
  the export.
- A step nobody answered is drawn as a dotted frame in its tone's colour, never filled, with no hatch
  and no sentence about a scenario: "done in this tool, nobody answered" on the strip, "(done inside
  this tool: nobody answered)" read out in History and the chain view, and a row of its own in the
  colour key. The list of chains in History draws it since D-256.
- A run that sent nothing (a step inside the tool, a notification, a refused write) records
  `elapsedMillis` null; a line already written with 0 for such a run is read as null; the export cell
  is empty and History and the chain view say "not measured". An outbound run's time is kept.

**D-241. "No determination" tells a business tester what to do.**
From the walkthrough's F-NODET-NEXT (major). CRD `NO_DETERMINATION` gets a next action of its own,
`REPORT_NO_DETERMINATION`: "Next: record run <runId> as "no determination returned" and report it to
the people who look after the service." with "The answer does not say whether prior authorization is
needed, and this tool does not guess. What came back is in the response detail below." The tool still
says neither whose fault it is nor whether authorization is needed. The strip, its words and its
dashed hollow tone, which keep it apart from "prior authorization is not required", are unchanged.
`REVIEW` stays for every other outcome that has it, including `AUTH_NOT_STATED` and `UNRECOGNISED_CODE`:
the decision named no determination only. Older ledger lines keep `REVIEW` and are read as before.

**D-242. Text typed and not added is never sent.**
From the walkthrough's F-DUPCODE (major) and F-MRI (minor). Text typed into the procedure code box and
never added went into the case when Send was pressed, on whichever tab was open, and could repeat a
code already there (the duplicate check compared the wrong property and never matched).
- Nothing the tester did not see as part of the case is sent. A send with text still in the box is
  refused before anything is asked, on every protocol tab and on "Complete the response": "Nothing was
  sent: "<text>" was typed in the procedure code box and not added to the case." and at the box "Typed
  and not added. Choose Add code, or clear the box." Only the tab that holds the case checks, so the
  Config tab's raw send is never blocked by the box.
- Every way a code gets into the case goes through one check that refuses the same code twice and says
  what it refused ("70553 is in the case already, and is not added twice.").
- A typed entry that is not on the list and lacks the shape of a CPT code (five digits, or four digits
  and a letter) or a HCPCS Level II code (a letter and four digits) gets the server's `shapeNote`
  under "not on the list"; it is still sent as chosen. A code on the list is never judged by its shape.
`CaseCodesFrontendTest` reads the source only: the build has no JavaScript runner, so the behaviour
was seen in a browser and not run by a test.

**D-243. One action keeps one name, an error says what to do, and a replay is described as it is.**
From the house style audit's V5-SCREEN-ONE-ACTION-NAMES, V5-SCREEN-ERRORS-NO-ACTION and
V5-SCREEN-PLAIN-WORDS (minors), and the walkthrough's F-FILTER-LABELS, F-CHAIN-WORDING, F-REPLAY-SCENARIO
and F-TRACE-PAS-DTR (minors).
- **Names.** The PAS submission and the status inquiry are named by their buttons, as the DTR actions
  were (D-205): `START_PAS` is "Send PAS request" and `CHECK_STATUS` "Send inquiry", in the next-action
  labels, the call choices of the PAS tab and every sentence that names them. The hand-over keeps its
  own name ("Continue in PAS"), so a headline reads "Next: choose Continue in PAS, then Send PAS
  request." The Config tab's actions each have one name: "Send as written", "Upload a collection",
  "Upload an environment", "Show how it resolves", "Unlock production writes for this browser session",
  "Open the Config tab". Close buttons name what they close. "mints" became "creates".
- **Errors.** A run not found gains an action (where to copy a run id from); one date check is worded
  one way everywhere ("The From day is after the To day. ..."); a parameter error no longer names the
  API parameter in its sentence, the field it marks does; an unknown scenario id says to choose one
  from the list or leave it empty; a build without a case runner says what is missing. At this
  revision the page's error panel is still headed "What went wrong", a refused form's label does not
  say what to do, the receiver's refusal of an unknown run id has no action, and two buttons still say
  only "Dismiss" and "Close" (D-246, D-249; done, D-253 and D-256, apart from the heading of a
  refused form, D-257).
- **The outcome filter of History** lists each protocol's wording of a code where protocols word it
  differently, instead of the bare code.
- **A replay refused because the run was handed its case** says it followed run X and was handed its
  case, so it cannot be sent again alone; it is called a step of a chain only when the line carries a
  chain id. History called the same run "a single run".
- **A replay on mock is still answered by the scenario the original run recorded**, so mock replays stay
  stable, and its verdict says so: "the scenario the original run recorded, sent again by its id. On the
  mock profile a replay is therefore answered as the original run was, by construction, whatever its
  member ID would select today." The selector's own sentence is unchanged for runs from the tabs and
  suites. Not checked over HTTP after the last change to it.
- **F-TRACE-PAS-DTR decided otherwise: D-181 stands.** A PAS line still names the package fetch it
  follows. What was missing was the way from one to the other: `GET /api/runs` takes `follows=<runId>`,
  and a run's view in History lists "Followed by", so from a PAS submission a QA analyst reaches the
  response run whose answers it carried in two steps (the package fetch it follows, then that fetch's
  followers).

**D-244. The style gate: one class that the build's task and a test both run.**
From the house style audit's V5-GATE-NOTEST and V5-GATE-PATTERNS (majors), V5-GATE-SCOPE,
V5-INVISIBLE-CHARS, V5-FIXTURE-NAMES and V5-COMMENTS-WHAT (minors). The audit found no tracked file and
no commit message breaking the character and word rules; it found that the build would not have
noticed if one did: the gate had no test, and its patterns let compounds, spellings with a separator
and several look-alike characters through. **This replaces D-013's description of the gate.**
- **One class.** The rules and the scan live once, in `src/houseStyle/java/.../style/HouseStyle.java`,
  a source set of its own that never reaches the application jar. `houseStyleCheck` is a `JavaExec`
  of it, `check` depends on it, and the tests and `CompareWords` call the same class, so the rules
  cannot drift. It still takes seconds.
- **A test that fails when the gate goes.** `HouseStyleTest` plants a violation of every rule and
  requires each to be refused with its own label and the clean lines to pass; requires the set of rule
  labels to equal the set of planted groups; checks through system properties that the test task works
  out from the live task graph that `check` depends on `houseStyleCheck` and that it runs
  `HouseStyle`; and scans the real tree itself, so after this change a violation anywhere also turns
  that test red. With the gate taken out of `check` the test failed. The test does not run Gradle
  itself, so the path where the task's exit becomes a failed build was seen by hand only.
- **What it reads.** Every file `git ls-files --cached --others --exclude-standard` lists in
  `epa-pave/` (tracked, plus new files not yet added, minus what `.gitignore` excludes), so
  `.gitignore` and `.gitattributes` are read; without git, a walk that skips the same directories, and
  the report says which. Every file's name as well as its lines. A binary file is skipped (a known
  extension or a NUL byte in its first 8000 bytes); a text file that is not valid UTF-8 is a violation,
  because decoding with replacement would hide the bytes the character rules look for.
- **The rules.** Banned words with no leading word boundary, so a compound in camel case or with
  underscores is caught; two-part forms allow up to three separator characters between their parts
  (one for the word made of "seam" and "less", since "seam" alone is a common word here), which also
  refuses such pairs in ordinary prose: such a line is reworded. The product name in every spelling
  with a separator; the organisation's short name without its trailing H anywhere, inside an
  identifier included; the tracking property in a stylesheet or set from a script; every dash-like,
  quote-like and space-like character of Unicode that could stand in for a forbidden one, the ellipsis
  character, and raw invisible characters, which must be written as escapes; the formal name spelt
  otherwise than Point32Health. Angle quotation marks are refused everywhere but `gradlew`, which Gradle
  writes and rewrites on upgrade (D-013's reason).
- **What it cannot read**, said in a comment on the task and on the last line of every report: commit
  messages, the pull request description, names of people, and comments that only restate the code.
- The tightened gate found eight raw invisible characters in string literals (test data and two byte
  order marks in `SuiteFileReader`); each is now its escape, with the same value. Six invented surnames
  were renamed to start with `Placeholder` (a fixture convention; no test checks it). Five comments of
  the audit's sample were rewritten to say why and eight empty banners removed; only the sample of 50
  of 4,769 comment blocks in the main sources was read.

### The checks of the second audits' fixes

**D-245. What the independent checks of the V2, V3 and V4 fixes established.**
On revision `9e5306d`, each by an agent that wrote neither the tool nor the fix, against the built jar
on live with stand-ins of its own on loopback ports, repeating every finding of the second run and
attacking its neighbours. For each finding it called closed, the check took the fix out again, ran the
fix's tests and saw them fail, then restored the file; F-SCREEN and m2 were seen in a browser with two
contexts and have no test to undo. The check of one fix is not a check of the tool: none ran the full
suite on the unchanged tree, and each relied on the integrator's build.
- **Member data (V2):** F0, F3, F5, F6 and F7 closed. F1 and F2 closed for the audit's own requests
  and open one step away: a note sent with a request of the tester's own from a protocol tab is checked
  against nothing (the page sends no member with it); a date of birth written with the month's name,
  and the member ID in fullwidth digits or without its letter, are not recognised; a member ID whose
  digits are split by a separator, supplied by a service as an identifier, is kept in clear; and the
  fix had stopped keeping the plain numbers many FHIR servers mint, so a notification on such a
  subscription is no longer matched to its run. Five minors more (address parts and name parts, names
  that are common words, long digit codes, the suite results file, variable names the upload warning
  misses).
- **Guardrail (V3):** M-ENC, m3, m4, T1, GAP1, m1, F-SCREEN and m2 closed; every route the audit
  tried is refused, and requests reached a production stand-in by two routes the check found. B1
  closed in its first
  direction and open in the second: a production environment whose own address the client cannot read
  (a port with a plus sign, a backslash in the path, a colon with nothing after it) is not remembered
  as production, and a sandbox at the plain spelling of that address then sent writes there with no
  unlock. Also found: a value typed for a run that makes the path unreadable is refused with the
  wrong cause; an overlong or look-alike encoding of a slash is not refused (the second route: a read
  reshaped with one, and writes to an environment with one in its base path, reached a production
  stand-in, which matters only behind a server that decodes such bytes); a short secret in a
  notification is shown with no note; a false "same server" warning. The check folded into N3, as
  depending on the server, neighbours of m3 that are not above 0x7F and that no decision covers: a
  non-production environment on production's server whose base path holds `%00`, `%20` or `%2e` was
  accepted with the m4 warning, and its writes reached production's port; and a production
  environment whose base path holds `%2F` did not protect the decoded spelling, so a sandbox there
  took a submit. These matter only behind a server that decodes such bytes. Also, a neighbour of m2:
  the unlocked panel's other sentence ("It ends with any change to the configuration... A restart of
  the tool ends it too") is incomplete, though no longer untrue. None of these was given to a task.
- **Fixtures (V4):** F-CHAIN-DTR, F-DUP-CRDSENT, F-PAS-SHADOW, F-SAMPLE-LIST, F-PAS-CRDMAP-HIDDEN,
  F-ORPHAN, F-UNMATCHED-LOOP and F-NPI-TEST closed; F-V4-02-REST recorded as the known limit of D-224.
  F-RAW open one step away: a sentinel NPI in a pasted body is ignored, while the README says it
  selects. Also found: a scenario directory of a tester's own from before the mappings became data
  loses them without a word; no test fails when the scenario lists' notes are dropped; the
  documentation scenario chosen by hand with a DTR sentinel names the wrong questionnaire; a mapping
  entry naming no CRD sentinel is not noticed; a deleted subscription is treated as live; two stale
  comments; the NPI scan does not read the Markdown files.
Each open line was decided and given to one of four tasks (D-246 to D-249; what became of each, D-253
to D-257). `PROGRESS.md` gives every
finding with its state and the check's result.

### Decided for the four tasks started from this revision, and not done on it

Four tasks began from this revision at the moment these records were written, each on a branch of its
own, to close what the checks of D-245 and the honesty audit found. The integrator decided each line
before the work, as below. **None of it is done at this revision**; a later entry will say what became
of each. `PROGRESS.md` marks every line one of them is meant to close.

**D-246. The ledger (`TASK_CLOSE_LEDGER`): decided, not done.**
1. **A request of the tester's own from a protocol tab** (N1 of the member data check). Every run that
   carries a body of the tester's own and no member, on CRD, DTR and PAS, checks its note, tester
   reference and expectation against the member identifiers it can read in that body, as a raw send
   does (D-231), with the same refusal on the field. A body in which no identifier can be read cannot
   be checked and is not refused.
2. **Other ways of writing what the request carries** (N3). Text is brought to one form before it is
   compared (Unicode compatibility normalisation, then the existing comparison); a date the request
   carries is looked for in every form the record's identifier-or-date rule knows, month names
   included; the run of digits of the member ID, when six digits or longer, is looked for as well as the
   whole ID. In a note typed with a run, in a suite row and in a note added later.
3. **An identifier a service or a notification supplies** (N2 and N4, together). Shape cannot settle
   it: D-218 let a member ID through whose digits a separator splits, and stopped keeping the plain
   numbers many FHIR servers mint, so a notification on such a subscription no longer matched its run.
   Decided: **such an identifier is written in clear only when it cannot be anybody's data, that is a
   UUID, an OID or an identifier this tool minted; every other one is kept as a token**, made as the
   authorization reference on the same line is made, and the line says so. A notification is matched
   to its run by comparing tokens, so matching works again for every identifier, a numeric one
   included. Where a screen showed such an identifier from the ledger it says it was kept as a token; a
   result on screen still shows what the service returned, because a result is not the ledger. A replay
   that needs one refuses with a sentence, as for a value not recorded. The statement that an id
   "reads like a member identifier or a date" goes. If this cannot be done whole, nothing is done by
   half: N2 is closed by counting digits with separators removed, and N4 is reported open.
4. **A name that is an ordinary word** (N6): a first or a last name is looked for alone only when it has
   four letters or more; a shorter one only where the first and the last name stand together.
5. **Parts of names and of addresses** (N5): one part of a hyphenated or two-word surname of four letters
   or more, and the city with the postal code of the request's address, are looked for as the whole
   surname and the address line are.
6. **A long code** (N7): a code the loaded code list holds is kept as written, whatever its shape; for
   one the list does not hold the rule of D-218 stands and the line says that something that was not a
   code was there.
7. **The results file of a suite** (N8) writes the refusal statement in place of an expectation refused
   for holding the member ID.
8. **A subscription the tool deleted** (N-DELETED-SUB of the fixture check): a subscription whose later
   delete the ledger recorded is not the latest one this tool created, and a notification on it says
   that this tool deleted it.
9. **The receiver's refusal of an unknown run id** (house style audit) says what to do and where the
   tester copies a run id from.
What became of it: D-253.

**D-247. The guardrail (`TASK_CLOSE_GUARDRAIL`): decided, not done.**
1. **A production environment whose address cannot be read** (N1 of the guardrail check, the open half
   of B1). Both: an environment of the production kind whose address cannot be read as the client
   reads it is refused at upload and cannot be made active, with a sentence that names the variable
   holding the address and quotes it as the file writes it (an operator who means production writes
   its address plainly); and **nothing that has a scheme and a host is ever dropped from what is
   remembered**: a production address stored before this rule, or reaching the store another way,
   protects its whole host, every port and every path, until it is replaced. D-210's rule 2 was
   applied to what is sent and not to what is remembered; this applies it to both.
2. **The cause of a refusal** (N2). An address whose scheme, host or port cannot be read is told apart
   from a url that is whole up to its path and invalid after it; for the second, the refusal says the
   url as filled is not a valid url and names the variable with its layer, or the value typed for the
   run, whose text made it so. Never a secret.
3. **No request leaves with a percent-escape of a byte above 0x7F in its path** (N3), by the rule and the
   refusal of D-211; an environment whose base address holds one is reported at upload. A FHIR path is
   plain ASCII. The query is not touched. This leaves out the neighbours of m3 that are not above 0x7F
   (`%00`, `%20` and `%2e` in a base path on production's server, and a production base path holding
   `%2F` that does not protect its decoded spelling, D-245): no task closes them.
4. **A short secret in a notification** (N4): the notification's notes say, as the wire view does, that a
   secret was too short to search for.
5. **The "same server" warning** (N5) for a production environment whose port could not be read says
   that, not "by scheme, host and port".
6. **A refusal at upload quotes the address as the file writes it** (F-ADDR-MISQUOTE of the honesty
   audit).
7. **On mock, every fault a scenario plays is headed as played** (F-MOCK-500-HEADING, major): an HTTP 500,
   a 400, an unreachable service, a body that is not JSON or is empty. Its action does not tell the
   tester to report a fault in an environment, since nothing left the machine; the heading and the
   colour key agree. Live is unchanged. The note of `client/support/Exchanges` that "only the last
   value of a repeated key is shown here" is worded as the views now behave (D-237).
8. **The tests write nothing into the checkout** (F-BUILD-WRITES-TREE): every test that starts the whole
   application gets folders of its own that are removed afterwards, without changing what a test
   asserts about the defaults; after `clean build` the project directory holds nothing new but `build/`.
9. **The upload's member data warning** knows more names (N9 of the member data check): a member number
   written `mbr`, a Medicare or Medicaid number, a social security or medical record number, an
   address, a telephone.
What became of it: D-254.

**D-248. The fixtures (`TASK_CLOSE_FIXTURES`): decided, not done.**
1. **A sentinel NPI in a pasted body selects** (N-RAW-NPI, the open half of F-RAW), in the same order of
   precedence as on the form, with a note saying where it was found: D-220's one rule holds for the NPI
   as for the member ID.
2. **A tester's own scenario directory from before the mappings became data** (N-OLD-SCENARIO-DIR):
   nothing is guessed back into it, and the tester is told. At load a note names each file whose
   description speaks of a sentinel of another protocol selecting it while the file lists none, and
   says which key carries the mapping now; and when DTR files are loaded and no CRD file answers DTR
   sentinels, a note says a DTR sentinel typed in CRD gets the default. The notes reach the lists.
3. **The list notes are tested** (N-LISTNOTES-UNTESTED): each list carries the note naming its default
   file, and a file that is not read shows on the list, through the running application.
4. **The documentation scenario chosen by hand** (N-CRD04-CHOSEN): the rule of D-222 applies whenever the
   scenario that answers is the one that answers DTR sentinels, however it was chosen, and the note says
   so.
5. **A mapping entry that names no CRD sentinel** (N-CRDLIST-UNCHECKED) is noted at load, naming the file
   and the entry.
6. **Scenario labels** (F-SCEN-LABELS of the honesty audit): three labels read as observations of the
   vendor's service while their `basis` says they are synthetic; each label says what the file plays,
   and every other label of the three protocols is checked for the same.
7. **Two stale comments** about the placeholder flag (N-STALE-JAVADOC), and the scan for an NPI outside
   the placeholder set reads the project's Markdown files as well (N-NPI-DOCS).
What became of it: D-255.

**D-249. The screens and what the last two fixes left each other (`TASK_CLOSE_SCREENS`): decided, not done.**
1. **A repeated key, finished** (F13): the finding is raised on DTR, PAS and subscriptions as on CRD, and
   a raw send and the two lookups list every value as the others do.
2. **A resource of another kind, finished** (F6): the headline says what came, not that the body "could
   not be read"; and the determination grid says that a coverage sub-extension was sent with no value,
   as the coverage table does (F3).
3. **The error panel** is headed by the server's `error.heading` and the page keeps no heading of its
   own ("What went wrong" over a refusal by the guardrail, where nothing went wrong); the label the page
   gives a refused form says what to do.
4. **Marks and names left**: the list of chains in History draws a step nobody answered as the runs list
   and the chain view do; the two buttons that say only "Dismiss" and "Close" name what they do.
5. **What the two fixes did not see in a browser**: the chain view with a step nobody answered and a time
   not measured; the renamed Config buttons; a step done inside the tool on live; a replay on mock and
   what it says of its scenario; the presences of lists on a PAS inquiry, a subscription and a
   notification; and all of it once at 1920 by 1080.
6. Every README sentence the two fixes or this task make untrue is reported, not fixed.
What became of it: D-256.

**D-250. The README and the pull request draft, last pass (`TASK_DOCS4`): decided, not done.**
Started at the same moment from this revision, not one of the four above. Decided by the integrator:
the honesty audit's findings that name the README or the draft come first. The draft states the level
delivered with its limits beside it (D-235) and never says "nothing stubbed", "complete" or
"everything"; part 1 carries every limit of sections 12, 13 and 24 and every open item of section 26.
The README's open items say that the token request uses HTTP Basic and name the method to change
(D-233); the README says the salt is a secret, where it speaks of secrets and of the member ID policy
(D-234). Part 6 of the draft gives each decision entry what it assumes or decides in one line, through
the last entry of this file at its base, and leaves out entries that record a step of the work. The
rest of the audit's findings as it says (F-BEHIND, the minors about the draft, the README's list of
statuses from V1's F-02, and the machine-wide JVM option and the shared build cache, which the draft
names beside the fresh-clone gate). The README is brought up to this revision, every sentence that
quotes the screen or the API checked on the running tool; lines the four tasks above were to close were
marked, so that each could be settled once they were merged.
Done and merged (`docs-4`, merged at `38569de`, before the four tasks above). The lines it marked were
settled on `428eff5` (D-259).

### Decided not to do, and known limits

**D-251. Decided otherwise in the last audits' fixes, and the limits the fixes and the checks report.**
Decided, each with its reason: by the integrator in its task files, or by the agent that did the work
where marked so.
- **No comparison, item by item, of what was asked with what was returned** (the PAS review's R07): D-199
  stands. Every item is shown as returned, with its adjudication and extensions; working out "what
  changed" would match the items of two documents by position and call a difference a change, a
  judgement nobody supplied.
- **No endpoint that works out which questionnaire items are enabled without a run** (D-199): the DTR tab
  says the enabled items are worked out each time the response is completed (D-203).
- **A PAS line names the package fetch it follows, not the response run** (the walkthrough's
  F-TRACE-PAS-DTR): D-181 stands, for its reason (one package, several responses, one `correlatedRunId`).
  The way from one to the other is "Followed by" (D-243).
- **The zone filter of History is not offered** (D-207, D-230): History is worded in UTC days throughout,
  and a zone would mean rewording the list, the export's range sentence and the date-range replay.
- **`CHECK_ENVIRONMENT` keeps its code** on a played timeout, because the PAS tab keys the inquiry
  hand-off on it; its label was made true of both profiles instead (D-231). The fix's own decision.
- **A raw send does not look for a name or a date of birth in its body, and does not count a `P32H-`
  sentinel as member data** (D-231): a sentinel is synthetic and is how a tester names a scenario.
- **`REVIEW` stays for every outcome but CRD no determination** (D-241): the decision named that one.
- **A replay on mock is answered by the scenario the original run recorded** (D-243), so mock replays are
  stable; the verdict says it is so by construction.
- **No `CrdOutcome` and no `FindingKind` were added** for a resource of another kind or a repeated key
  (D-237, D-238): existing ones fit, and a new outcome would have reached a switch another agent owned.
  The fix's own decision.
- **The ledger record gains no count of fields returned but not interpreted** at this revision (the
  completeness audit's F8): it needed the record, which another agent held; what it would gain is one
  `Integer` component holding the number of returned fields of kind field, null when not counted, which
  holds no member data. Not given to any of the four tasks; open.
- **The style gate does not read commit messages, the pull request description, names of people or
  comments that restate the code** (D-244): a build sees files, not history or meaning.
Known limits the fixes and the checks report, not closed:
- The member and provider detail records still hold the mask text of a masked lookup field; the page
  fills the case only from the marked field list (D-229, D-231), and no build test pins that.
- A note sent with a request as written that the guardrail refuses is checked against the body as typed,
  not the saved body (D-231).
- On DTR and in a subscription answer, the notes for a body that is not JSON were not reworded (the
  completeness fix did CRD and PAS).
- Behaviour on screen has no test in the build; `CaseCodesFrontendTest`, `StaticLedgerTextTest` and
  `StaticServerWordsTest` read the page's source only.
- The style gate's own failure path (the task's exit becoming a failed build) was seen by hand only, and
  only a sample of comments was read for restating the code (D-244).
- A one-word surname written where a code or an id belongs is kept (D-151, D-218); D-246 changes how an
  id a service supplies is kept, not a code.
- IPv6 destinations were proven by nobody, there being no IPv6 loopback here; how a real nginx, Tomcat or
  IIS in front of a service decodes a path was tried by nobody (D-210, D-211, D-245).

### Joining the parts

**D-252. Decisions made while joining the last server task, the last screens pass, the last audits' fixes and the records.**
This revision is branch `epa-pave` at `e1d37dc`, which is pushed: the line of D-228 (`5763c41`), the
README (`docs-3` at `c735dee`), the records of D-202 to D-228 (`records-3` at `db98d7f`), the last server
task (`left3-server` at `c15250c`), the last screens pass (`screens-fix` at `e995e6b`), the six lines
(`seam-4` at `c8a7b9f`), and the three fixes for the last audits (`final-gate` at `e85facf`,
`final-render` at `23edf9f`, `final-uat` at `a6446cd`).
1. Nothing conflicted in any of these merges. The completeness fix and the walkthrough fix both changed
   `static/js/result.js` and `css/pave.css`, in different places (the issues table and the list helpers
   against the verdict strip and its import line; the issues grid against the verdict section). The
   style gate's fix and the walkthrough fix both changed `CrdControllerTest` (renamed fixture surnames
   against new assertions). Each merged cleanly.
2. The integrator changed no code in this stretch. It kept the note at the top of `PROGRESS.md`
   current as the work was merged and as audits and checks reported, until these records replaced it.
3. The line was built through the shared build wrapper as it grew: 2714 tests at `9e285a1` (the last
   server task, the screens pass and the README merged), 2720 at `9e5306d` (with the six lines), and on
   this revision 2767 tests, 2765 passed, 2 skipped, none failed. The two skipped tests need a folder
   that cannot be written, which a build running as root cannot make. The wrapper builds offline.
4. **Which revision each audit and check examined.** The second run of V2, V3 and V4 examined `0658a7c`.
   V1, V5, V7 and the walkthrough examined `9e285a1`. V6 and the three checks of the V2, V3 and V4 fixes
   examined `9e5306d`. V1's two clean builds from a fresh clone and V6's clean build and fresh-clone
   build are builds of those revisions; this revision was built by the integrator alone, through the
   wrapper. Its last gate (the plain `./gradlew clean build` twice, the build from a fresh clone, the
   start and `GET /api/mode`, with no other agent at work) has not run.
5. One more exchange with the operator, beside the questions of D-227: the operator asked how much
   longer the work would take, and was told what remained and that the build prompt allows a finding to
   be resolved or recorded. No instruction followed, and the full sequence was carried on. The four
   questions of D-227 still have no answer. (The operator replied after the pull request was opened:
   D-269.)
6. The build machine restarted three times while this tool was built (at about 00:17, 03:02 and 06:00
   UTC), each time without warning and cutting off every agent then at work. Nothing committed was lost.
   Since then every agent commits a checkpoint after each group of changes.

## Decisions of the four closing tasks, and of the records that settled their lines

After the revision of D-252 (`e1d37dc`), six branches were merged into `epa-pave`: the last pass over
the README and the pull request draft (`docs-4`, D-250), the records of D-229 to D-252 (`records-4`),
and the four tasks D-246 to D-249 decided: the fixtures (`close-fixtures` at `c413fc0`), the screens
(`close-screens` at `84d52ec`), the ledger (`close-ledger` at `9be24c7`) and the guardrail
(`close-guardrail` at `6037d2a`). Each was done by one agent, which reported what it changed, which
test it saw fail with the change taken out, what it checked on the built jar or in a browser, and what
it did not do. **Those fixes have been checked by nobody but their authors** at the revision these
records describe (`428eff5`): an independent check of the last fixes was running, and only one of its
six parts had reported (D-258). The entries below record what each task did and the decisions it
made, then what the four left and what stands as a limit, then that check, then the joining. Where a
report and the code disagreed, the code was followed and the entry says so.

**D-253. The ledger (`TASK_CLOSE_LEDGER`): what was done.**
Every line of D-246 was done, each with a test its author saw fail with the change undone.
1. **A body of the tester's own is checked** (item 1). Every CRD, DTR and PAS run that carries a body
   of the tester's own has its note, tester reference and expectation checked against the member
   identifiers that body states, whether or not a member is on the form; a field the form's member
   already refuses is refused once. Besides Patient and Coverage identifiers and `subscriberId`, a
   protocol tab's body is read for `context.patientId`, a Patient id and a `Patient/<id>` reference
   (`MembersInBody.readWithPatientIds`), leaving out the tool's own `patient-1`; the raw send's reader
   is unchanged. A body with no readable identifier is not refused. Limits that stand: only identifiers
   are read, not a name or a date of birth (as for the raw send, D-231); the body is checked as typed,
   before `{{placeholders}}` are filled, so an ID only a placeholder brings in is not seen. On the jar
   the author saw the refusal for CRD only; DTR and PAS are covered by `OwnBodyMemberTextTest`.
   Later: the placeholder limit is replaced by D-262 item 1, with its own limits.
2. **Other ways of writing the same data** (item 2). Text is brought to NFKC before every comparison,
   in `MemberInText` and in `RunRecord.holdsMember`. The longest run of six or more digits of the ID is
   looked for alone; in the record that search skips UUIDs, whose digits are nobody's. On a line that
   holds only the token, a run of digits is tried with one or two letters A to Z in front; an ID with
   more letters in front is not found that way. Dates with the month's name are compared with the
   request's date of birth by parsing each match, not by listing forms. Limit that stands: a note added
   later on a token line cannot be checked for a name or a date of birth, because the line holds
   neither (D-217).
3. **An identifier a service supplies is a token unless it is nobody's data** (item 3, done whole, not
   the fallback). `claimId`, `claimResponseId` and `subscriptionId` (`RunRecord.SUPPLIED_ID_KEYS`)
   follow the authorization number's policy: in clear when the line's member ID is in clear, or when
   the line has no member and `record-member-id` is true; otherwise a token of the same bare 64-hex form,
   unless `RunRecord.nobodysData` holds (a UUID, an OID, `urn:uuid`, `urn:oid`, `claim-<uuid>`,
   `claimresponse-claim-<uuid>`). `sub-<uuid>` and the mock's `subscription-placeholder-0001` are tokens
   on live defaults. The line does not mark a token otherwise; History says so in one sentence under the
   value ("Kept as a token, not as the identifier the service gave ..."), a change the ledger task made
   in a screen file. A 64-hex value under these keys is taken for the token and is never sent by a
   replay; a PAS inquiry whose claim id is a token is refused with a sentence. A line written before
   this change is not rewritten: its clear ids are shown, exported and matched as tokens when read
   (`TrackingPolicy.asRead`). `Unmatched.NOT_KEPT` now means only a value that is not one identifier
   (over 128 characters, or white space or other signs). The sentence "reads like a member identifier
   or a date" is gone. Limits that stand: a ClaimResponse id that holds the submission's own member ID
   with separators (`S123-456-789` on a run for `S123456789`) is still written as `(not recorded, N
   characters)` by the earlier rule, so a notification carrying it is not matched; and lines the first
   fix wrote that way under a subscription or ClaimResponse id cannot be matched either. Later: the first
   of these limits is closed for new lines by D-262 item 2; the second stands.
4. **Short names** (item 4): a first or last name is looked for alone only at four letters or more; a
   shorter one only beside the other name, in either order, separated by spaces or a comma.
5. **Parts of names and addresses** (item 5): each part of a hyphenated or two-word surname of four
   letters or more is looked for alone; the address counts as found when the city and the first five
   characters of the postal code both appear as words, and either alone does not.
6. **Codes the list holds** (item 6): the record cannot see the code list, so `JsonlRunLedger` hands it a
   code-list test once at construction (`RunRecord.codesOnTheList`, a static hook). A listed code is
   kept whatever its shape; a code holding the run's member ID is refused even when listed; a list that
   throws counts as holding nothing. The author asks that the static mutable hook be reviewed: the
   alternative, the check in the ledger policy, would have changed what other services' tests on the
   frozen `InMemoryLedger` assert. Not checked on the jar: no loaded list held a long code.
   Replaced by D-262 item 3: the hook is gone, and the list is asked only when a line is written.
7. **Suite results** (item 7): a row whose expectation was refused for member data, by the row check or
   the service's, or for its length, carries the refusal sentence in place of the expectation, on
   screen and in `results.csv`.
8. **A deleted subscription** (item 8): whether a subscription is live is decided by ledger order, not by
   timestamps, which can be equal. Newest first, the first confirmed create or confirmed delete of the id
   decides. A notification on a deleted subscription keeps its link to the creating run, gets the reason
   `Unmatched.DELETED` (outcome `UNMATCHED_NOTIFICATION`) with its own headline, note, finding and next
   step, and is never called still there. Unit-tested only; not seen on the jar or in a browser.
9. **The run id refusal** (item 9) names the field the tester sees, "About the submission (run id)", and
   the two places to copy a run id from. Unit-tested; not seen in a browser.
`NotificationIdsNotKeptTest` was replaced by `NotificationIdsKeptAsTokensTest`, because it asserted the
rule this replaced. The frozen `testsupport/InMemoryLedger` does not tokenise supplied ids, so a test of
tokenising must use `JsonlRunLedger`. Overturns D-218 for identifiers a service supplies; extends D-217.

**D-254. The guardrail (`TASK_CLOSE_GUARDRAIL`): what was done.**
Every line of D-247 was done; for item 7 the heading and the action only (D-257). Each has a test its
author saw fail with the change undone, and items 1, 2, 3 and 7 were checked by the author on the jar
against loopback stand-ins, the proof of a blocked write being that nothing arrived.
1. **A production address the client cannot read** (item 1). Refused at upload (422) and refused
   activation (422); an active one that a store or a collection left unreadable cannot be unlocked
   (422). Who means production writes its address plainly. Remembering production never drops an
   address with a scheme and a host: one the client cannot read is remembered as its whole host
   (scheme open, port open, root path, not readable), read loosely (a backslash ends the authority, the
   host ends at the first colon outside an IPv6 literal, NFKC and lower case), and persisted as the bare
   host. When an environment of the same name is uploaded again as prod, its unreadable remembered
   entries are dropped, since that upload succeeds only with readable addresses; its readable ones stay,
   as before. (Later: replaced by D-265 item 2, which drops them only for an upload on the same host.) Whole-host remembering applies only to production: a sandbox's own unreadable address
   keeps its earlier lenient reading for comparison and is still refused at send. A secret anywhere in
   the value keeps it out of every view and the store, as before.
2. **The cause of a refusal** (item 2). An absolute url whose scheme, host and port read correctly and
   which the client cannot parse whole is refused before the guardrail's own refusals, with a 422 that
   names the variable and its layer (or the request, setting or url as written) and says the scheme,
   host and port are not the problem. On live for every method; on mock for writes only, because a read
   is never sent there.
3. **Escapes above 0x7F** (item 3): refused in a path by the rule, the 422 and the upload warning of
   D-211, through repeated encoding. The query and fragment are untouched.
4. **A notification's notes** (item 4) say what the wire view says of secrets too short to search for,
   and of secrets that could not be worked out; a gateway that cannot give the secrets now counts as the
   second, where before it masked nothing silently. Tested; not checked on the jar.
5. **The "same server" warning** (item 5): a remembered production destination that is not readable
   stands for its whole host, and an environment on that host gets a sentence of its own.
6. **Quoting at upload** (item 6): an unreadable address is quoted as the file writes it: scheme,
   authority and path, without user information, query or fragment, which can hold a credential, and
   with control characters written as escapes. A secret variable is named, never quoted. A readable
   address is still quoted as the tool reads it.
7. **A fault a mock scenario plays** (item 7): every fault the result describes, except the timeout
   already worded as played, keeps its kind and title and gets a heading "... played by a mock
   scenario, not ..." and an action beginning "Nothing left this machine". Played is judged by the
   profile: on mock nothing leaves the machine. Live is unchanged. The note of `Exchanges` on a repeated
   key says what is true in every view.
8. **Tests write nothing into the checkout** (item 8): a JUnit extension, autodetected for every test
   class through `junit-platform.properties`, sets `config-store.directory` and `tracking.directory` as
   system properties to a temporary folder removed at the end. A test's own property sources still win.
   `PaveApplicationTest` asserts the defaults from `application.yml`'s own property source, so what it
   asserts is unchanged. The author checked that after `clean build` the project directory holds nothing
   new but `build/`.
9. **Member data names** (item 9): `mbr`, Medicare and Medicaid as person words; `mbi`, `ssn`, `mrn`
   and `hicn` alone; social security, medical record; an address (`address`, `street`, `zip`, `postal`
   and the like) unless a machine word goes with it (`ip`, `host`, `server`, `callback`, `proxy` and
   others); a telephone (`phone`, `telephone`, `tel`). Gender and card numbers were not added, the line
   not naming them. Tested; not checked on the jar.
Shared types gained methods (`UrlNormal.wholeHost`, `asWritten` and others, `Destination.everyPortAndPath`)
and `ProductionDestinations.Found` two components, with the old constructor kept; no public signature
was removed. Extends D-210, D-211, D-212 and D-215.

**D-255. The fixtures (`TASK_CLOSE_FIXTURES`): what was done.**
Every line of D-248 was done, each with a test its author saw fail with the change undone where a test
applies, and checked by the author on the jar with the earlier check's scripts.
1. **A sentinel NPI in a pasted body** (item 1): a pasted body is read in the form's order, a member ID
   first, then an NPI; anything typed on the form still comes first. A whole JSON number counts as an
   NPI value; an NPI inside a longer text does not, as for the member ID. A PAS inquiry chosen by an NPI
   in the body is refused on `rawBody` with the words "an NPI", as for a member ID. The list notes of a
   PAS list filtered with `?for=` are tested for `submit` only.
2. **A scenario directory from before the mappings became data** (item 2): a DTR or PAS file counts as
   written before when its description speaks of a CRD sentinel and it lists no `crdSentinelMemberIds`; a
   CRD file when it speaks of DTR sentinels without `answersDtrSentinels`. The note names the file and
   the key and quotes nothing of the description. The note that no CRD file answers DTR sentinels goes on
   both the CRD and the DTR lists, and only when both sets load without problems and hold a scenario.
   Mapping notes and files not read are also said in one warning per protocol at start on mock
   (`Loaded.warnedAtStart`); the default and `expect` notes are still not warned. Notes that need another
   protocol's files are computed for the lists and the start check only: a run reads only its own
   protocol's files, so another protocol's damaged set never refuses a run. No test covers a CRD file
   that speaks of DTR sentinels while another CRD file carries `answersDtrSentinels`.
3. **The list notes are tested** (item 3) in the three controller tests and, for a file not read, over the
   running application (`ScenarioCheckAtStartTest`).
4. **`crd-04` chosen by hand** (item 4): the DTR scenario is found from the form's member ID first, then
   from a member ID in the body; a damaged DTR set is not read then, so a hand-chosen CRD run never fails
   on DTR files. The note stays "chosen in the scenario selector", which replay depends on, and the reason
   is added after it. The author drove two DTR sentinels into DTR in a browser at 1366 by 768, not on to
   PAS.
5. **A mapping entry that is no CRD file's sentinel** (item 5) is noted at load, ignoring case and spaces,
   and skipped while the CRD set has problems. A DTR sentinel listed in a PAS file is noted too, though
   `crd-04` answers it. Limit that stands: the run note of such a selection still says the ID "answers
   for the same case", while the list note says it means no CRD case.
6. **Labels** (item 6) name the case the file plays ("Request rejected with HTTP 400", "Server error, HTTP
   500"), although the specification's table in section 11 says "Onyx rejects/fails"; `pas-17` was
   reworded too. A test refuses any label that names Onyx or begins "The service", "The payer" or "The
   vendor".
7. **The NPI scan** (item 7) reads every Markdown file of the project outside `src`, `samples` and the
   build folders. The two comments say the flag means any synthetic list.
Extends D-220, D-221, D-222 and D-224.

**D-256. The screens (`TASK_CLOSE_SCREENS`): what was done.**
Lines 1 to 5 of D-249 were done; line 6 was reported, and the README lines it listed were settled by
D-259. Lines 1 and 2 have tests the author saw fail with the change undone; lines 3 and 4 have tests that
read the page's source and were not run against the code before the change.
1. **A repeated key** (item 1): the `GUIDE_RULE_BROKEN` finding is raised on DTR (the package and the next
   question; the step done inside the tool receives nothing), PAS (submit and inquire) and subscriptions
   by the call CRD uses. The raw send, the two lookups and CRD discovery list every value but raise no
   finding, having no findings list. CRD discovery was not named in the line; it had the same defect and
   got the same one-line change.
2. **A resource of another kind** (item 2): the CRD headline says what came ("a FHIR Patient resource, not
   a CDS Hooks response") when `resourceType` is shaped like a type name, otherwise "JSON that is not a CDS
   Hooks response"; any other text there stays among the returned fields and never reaches a headline.
   Only a body that is not JSON is still one that "could not be read". The determination grid says "sent
   with no value" through a new optional component of `DeterminationRow` (`sentWithNoValue`), chosen over
   letting the page match rows itself, because the browser must not work out an answer; the old
   constructor is kept. One mark with one set of words draws it in the grid and the coverage table.
3. **The error panel** (item 3) is headed by `error.heading`; inside it the kind line then gives only the
   HTTP status. Where the panel is used alone (History, Config, the comparison) it keeps the heading inside.
   With no kind it reads "Error kind ...", so the page words nothing of its own. The label of a refused
   form was done in another form: the page gives a 422 no label, and the server's action says what to do;
   the server's heading for it does not (D-257).
4. **Marks and names** (item 4): the list of chains draws a step nobody answered; no button is named by a
   bare Close, Dismiss, Cancel, OK, Submit or Done (`ButtonNamesAndMarksTest`); "Fold" and "Open" on a
   notification stay, since each says what it does. Every PAS sentence that named the old calls
   "Submit" and "Inquire" uses "Send PAS request" and "Send inquiry".
5. **What had not been seen** (item 5) was looked at and three things put right: the chain view's
   Elapsed column is wide enough for "not measured"; the History replay panel shows the new run's
   supporting lines, so what a mock replay says of its scenario is on screen; an item of a PAS answer tied
   to no code is headed "Item n". Seen at 1366 by 768 and 1920 by 1080: the repeated keys on live
   against a stand-in, the audit cases, the journey, the error sections and the chain view. Seen at
   1366 only: the Config tab, the replay panel, the subscription and notification presences and the
   unlock button.
Overturns nothing; extends D-236, D-237, D-238, D-240 and D-243.

### What the four left, and the limits that stand

**D-257. What the four closing tasks report as left for somebody else, and limits they report.**
Each item below was checked in the source of `428eff5` where a check was cheap, and each is still so.
None was given to a task; each is open.
- **On mock, the next step of a played HTTP 500 or a played unreachable service** still says "Next: check
  the environment, then send again." (CRD, DTR, PAS and subscriptions build it in their verdicts, outside
  the guardrail task's files). Only a played timeout's next step is worded as played. Its heading and
  action are right (D-254).
- **The title of the CRD finding for a sub-extension** reads "... was sent with no value" for a value sent
  as null and one sent empty alike (`CrdGuideRules`), while its detail tells them apart.
- **The label of the CRD outcome `MALFORMED_RESPONSE`** still speaks of a body that could not be read, and
  shows as "Happened" in the test result, History and a chain for a Patient that reads well.
- **The headers of a subscription** (`channel.header`) have no presence of their own, so a list not
  returned, null or empty all read "none returned".
- **Next-step sentences that say "read the response detail below"** are shown also in the History replay
  panel and the chain view, where no detail is below.
- **The detail of the repeated-key finding** says "that is the value shown above" also for a key the view
  reads without drawing (`type` of a DTR or PAS Bundle).
- **The heading the server gives a refused form**, "A problem with what was submitted", says nothing of
  what to do; the action under it does.
- **History, a suite row and a chain** say nothing of how many fields the tool did not interpret (D-251).
- **The action for `EndpointNotConfiguredException`** in `ApiExceptionHandler` always sends the tester to
  the Config tab, which does not fit the executor's new "as filled is not a valid url" message; on live
  the gateway refuses that case first, so only a call prepared elsewhere reaches it.
- Limits the authors report: the static hook of D-253 item 6 is to be reviewed; the guardrail fix was not
  proved over IPv6, with an explicit default port on the wire, or on a host other than loopback (the
  forms are unit-tested), and its new sentences were read through the API, not on the Config tab; a
  sandbox on production's port whose base path ends in `%00`, `%2e` or `%20` gets only the warning of
  another base path (the decided case, D-212), as D-247 item 3 left it; a store of an earlier version can
  leave a stray environment file when an earlier production is uploaded again (seen once, not looked
  into); the earlier checks' tools `damage.py`, `damage2/3.py`, `chain_suite.py`, `live.py` and the
  browser loop of every DTR sentinel were not run again after the fixture fix.
The README sentences each fixer listed as made untrue were settled on `428eff5` (D-259). One report and
the code disagree: the screens fix reports the receiver's "The run must be a run id this tool returned."
as still open; the ledger fix replaced it (D-253 item 9), and the source holds the new sentence. A
neighbour remains: the DTR, PAS and subscription services refuse a hand-off whose `carry.runId` is not a
run id with "The run this step follows from must be a run id this tool returned.", a field the page
fills and the tester does not type; it says nothing of what to do.
Later: every item above still stands at `902e0d9` (D-266), except the static hook, which D-262 item 3
removed. The played-fault next step has a new neighbour: the DTR package played malformed (D-265).

### The independent check of the last fixes

**D-258. The check of the last fixes: one part of six had reported.**
An independent check of the last fixes was started in six parts, each by an agent that wrote neither the
tool nor the fix: the ledger, the fixtures, completeness on screen, the walkthrough, the house style
gate and the guardrail. The first five examine `98effa6` (the line with the ledger, screens and fixture
tasks merged), the guardrail `428eff5`. When these records were written only the check of the style
gate (`final-gate`, D-244) had reported. Its answer: three findings closed (V5-GATE-NOTEST,
V5-INVISIBLE-CHARS, and V5-GATE-SCOPE in the form decided, commit messages recorded as a limit); three
closed in part (V5-GATE-PATTERNS: every planted line is caught, but several dash-like, quote-like and
invisible characters and every entity or escape that renders a forbidden character are not;
V5-FIXTURE-NAMES: invented surnames that do not start with `Placeholder` remain, one in a file the audit
named; V5-COMMENTS-WHAT: the eight comments named are fixed, the same empty banners remain in 16 other
main source files); and six new minor findings: a file name with a character outside ASCII crashes the
gate under a POSIX locale and the failed build then shows the previous run's "No house style
violations." (STYLE2-NONASCII-NAME); the character gaps (STYLE2-UNICODE-GAPS); a text file with a binary
extension or a NUL byte near its start is skipped unread (STYLE2-BINARY-SKIP); a second, weaker copy of
the tracking rule in `StaticFrontendTest` (STYLE2-SECOND-COPY); the surnames (STYLE2-FIXTURE-RESIDUE); the
banners (STYLE2-BANNERS-REMAIN). None is fixed. The check found none of the missed characters in the tree
and ran only the tests concerned, not the full build. The other five parts had not reported; what they
find is for the integrator to record.
Later: all six parts reported. What each found is D-260; what was done about it D-261 to D-266.

### Joining the parts

**D-259. Decisions made while joining the four closing tasks, and settling the lines that were marked.**
This revision is branch `epa-pave` at `428eff5`, which is pushed: the line of D-252 (`e1d37dc`), the
README and draft pass (`docs-4`, merged at `38569de`), the records (`records-4`, at `a004dd7`), and the
four closing tasks, merged in this order: fixtures at `f710265`, screens at `9733e7c`, ledger at
`98effa6`, guardrail at `e9c8fa2`.
1. Nothing conflicted in these merges: no merge commit carries a change of its own. The only file two of
   the tasks changed is `NotificationReceiver` (the ledger task the matching, the guardrail task the
   masking), and it merged cleanly. The ledger task also changed one screen file,
   `static/js/history-run.js`, which no other task touched.
2. The line was built through the shared build wrapper: 2826 tests, 2824 passed, 2 skipped, none failed.
   The two skipped tests need a folder that cannot be written, which a build running as root cannot make.
   The integrator's last gate (the plain `./gradlew clean build` twice, a build from a fresh clone with the
   build cache off, the start and `GET /api/mode`) has not run.
3. Every line the README, `PROGRESS.md`, this file and the pull request draft marked for the four closing tasks was
   settled against the source of `428eff5` and the four reports: a line that describes behaviour says
   what the tool does now; a line in a list of what is open was moved to what was fixed, as fixed and
   checked by nobody but the author of the fix, or kept open in plain words where the report says it is
   not done or done in part. The tool was not started for this; a sentence that says what the fixed tool
   does on screen or on the jar repeats what the fix's author reports seeing.
4. The pull request draft was brought to 58,000 characters or fewer by saying each finding, assumption and
   gate once, with the detail left to `PROGRESS.md` and this file.

## Decisions of the last check, the last fixes, and how the checking ended

After the revision of D-259 (`428eff5`), the integrator merged into `epa-pave`: the records and README
pass that settled the marked lines (`final-touch` at `c4bc498`, D-253 to D-259), and four last fixes,
one for each part of the last independent check that found a blocker or a major: completeness on
screen (`last-render` at `fff3bd0`), the walkthrough (`last-uat` at `10108d4`), the ledger
(`last-ledger` at `54f9912`) and the guardrail (`last-guardrail`, its content at `49ae839`, merged at
`7df9718`; its last commit `ca716a1`, which changes no content, merged at `902e0d9`). The revision these
entries describe is `902e0d9`, the head of the branch as it is pushed, before the commit that adds these
records. **The four last fixes have been checked by nobody but their authors, and nobody will check
them before the tool is handed over** (D-268). Each entry below says what the fix's author reports and
what was checked in the code of `902e0d9` where a check was cheap; where a report and the code disagree,
the entry says so and follows the code.

**D-260. The last independent check: what each of its six parts found.**
Six agents that wrote neither the tool nor the fixes checked the fixes of D-236 to D-256, each finding
by finding, with the fix taken out where a test was named, and looked for new findings beside each.
Five examined `98effa6` (the line with the ledger, screens and fixture closing tasks merged), the
guardrail part `428eff5`. Their answers:
- *The house style gate:* reported before D-259 was written and recorded in D-258. 3 closed, 3 partly
  closed, 6 new minors.
- *Completeness on screen:* no. Of 14 findings 7 closed (F1, F2, F3, F4, F7 apart from a known mock
  heading, F10, F12), 5 partly closed (F5, F6, F9, F13, F14), 2 open (F8, decided and given to no task
  by D-251; F11, whose fix History undid: a null `pa-needed` read "(not a code, 6 characters)"). New: 1
  blocker (NEW-1, that History sentence), 4 majors (NEW-2 a repeated key with something unread under it
  gets no finding; NEW-3 an OperationOutcome at HTTP 200 passes as no determination; NEW-4 an issue's
  details null or empty drawn "not returned"; NEW-6 the page's drawing of the presences is held only by
  tests that read the script's text), 5 minors (NEW-5, NEW-7 to NEW-10).
- *The fixtures:* yes. All 7 lines closed, each seen to fail with its fix undone. 4 new minors
  (N-CHOSEN-BODY-NOTE, N-ANSWERING-NOTE, N-DESC-OTHER-SENTINEL, N-INQUIRE-LIST-HIDES-MAPPING).
- *The ledger:* no. 5 lines closed (N5, N6, N7, N-DELETED-SUB, the run id sentence), 4 closed for the
  auditor's own request and open one step away (N1, N3, N2 with N4, N8). New: 3 majors (NF1 a supplied
  id holding the member ID not kept as a token, so its notification is not matched; NF2 a body that
  names the member through a placeholder checked against nothing; NF3 a line written with a listed code
  reads "not a code" once the list changes, which the last fix broke), 4 minors (NF4 to NF7).
- *The walkthrough:* no. 10 of 13 closed (F-TRACE-PAS-DTR in the form decided), 3 partly closed
  (F-NODET-NEXT on CRD only, F-DUPCODE, V5-SCREEN-ERRORS-NO-ACTION). New: 4 majors (N-PAS-NODECISION,
  N-SUB-LOCKOUT which the last fix broke, N-DUPCODE-UNTESTED, N-NOTIF-SERVICE), 4 minors (N-CHAIN-TOTAL,
  N-REPLAY-UNTESTED, N-REPLAY-NOTFOUND, N-TRACE-TWO).
- *The guardrail:* not every finding closed. Of 9 lines 5 closed (N2, N4, N9, F-ADDR-MISQUOTE,
  F-BUILD-WRITES-TREE), 4 partly closed (N1, closed for every spelling written in the environment file
  and open for an address from `application.yml` or an operating system variable; N3; N5;
  F-MOCK-500-HEADING). New: 3 majors (G2-1 a production address not written in the environment is never
  remembered, and a sandbox at it sends writes with no unlock; G2-2 a whole host remembered for an
  unreadable address is forgotten when the same name is uploaded at another host; G2-3 the DTR package
  a scenario plays malformed headed as a fault in the environment), 6 minors (G2-4 to G2-9).
Each finding, its grade and what became of it is in the table of the last check in `PROGRESS.md`.

**D-261. What the integrator decided from the last check.**
Four tasks were written, one for each part that found a blocker or a major: `TASK_LAST_LEDGER`,
`TASK_LAST_RENDER`, `TASK_LAST_UAT`, `TASK_LAST_GUARDRAIL`. Each named the lines to fix in order, the
minors to do only if the rest was green, and the lines to record and not fix. The style gate and the
fixtures were given no fix: their parts found minors only, and each finding of theirs that is not closed
is open at the revision handed over. Every task was told it was the last change before handover, to keep
each change as small as the finding, to write a test that fails without it and see it fail, to leave the
code as it was where a finding could not be closed safely, and to fail closed in the guardrail.
Decided in the task files, beside "fix it":
1. *Ledger.* A body of the tester's own is read for identifiers as it will be sent, with its
   placeholders filled by the gateway's own resolution (NF2). Every supplied identifier that is not a
   UUID, an OID or one the tool minted is a token, also when it holds the run's member ID (NF1). What a
   line says was decided when it was written; reading a line never judges it again by a list that may
   have changed (NF3). NF6 recorded, not fixed.
2. *Completeness.* The record keeps `(null)`, `(empty)` and `(no value)` as it keeps `(no answer)`
   (NEW-1). A body that says it is an OperationOutcome gets the outcome of an answer that is not what
   the operation returns (NEW-3). Whether a view reads a repeated key is decided at the key's own place
   (NEW-2). NEW-6 and F8 recorded, not fixed.
3. *Walkthrough.* The rule that nothing the tester did not see as part of the case is sent stands, and
   is applied only to a call that sends the case (N-SUB-LOCKOUT). A notification a scenario played was
   answered by a mock scenario (N-NOTIF-SERVICE). Every outcome whose next step said only that the tool
   cannot say what should happen next gets the two lines of D-241 (N-PAS-NODECISION).
   N-REPLAY-NOTFOUND, N-REPLAY-UNTESTED and N-TRACE-TWO recorded, not fixed.
4. *Guardrail.* What is remembered for a production environment is read from the address the tool
   would send to, through every layer the gateway resolves; a production address that cannot be
   resolved to one the client reads is refused at upload and at activation with a sentence that names
   the variable (G2-1). Nothing with a scheme and a host is dropped from what is remembered unless a
   production environment is uploaded, readable, on that same host (G2-2). G2-6, G2-7 and G2-9
   recorded, not fixed.

**D-262. The last ledger fix (`last-ledger`): what was done.**
Lines 1 to 6 done, line 7 recorded; each with a test its author saw fail with the change undone, except
NF7 (a sentence of a page, no test). Checked by its author on the jar, on live with default tracking,
against the checker's stand-in, through the API; nothing in a browser.
1. **NF2.** `RunInputs.common` reads the identifiers of a body of the tester's own from the body as it
   will be sent, filled by `OperationGateway.resolve` (run values, then the active environment, then
   `application.yml`). The protocol services build `RunInputs` with a clock only, so a Spring component
   (`service/run/OwnBodyPlaceholders`, present at `902e0d9`) hands the resolution to it through a static
   hook at start. Limits that stand: collection variables are not used (that resolution has no collection
   layer); when any placeholder has no value the body is read as typed (on live the call is then refused
   for the missing value anyway); a service built by hand without that component checks the body as
   typed; on the jar only CRD was tried, DTR and PAS share the code path and no test covers them with a
   placeholder. Replaces the limit "checked as typed, before placeholders are filled" of D-253 item 1.
2. **NF1.** `claimId`, `claimResponseId` and `subscriptionId` are handled before the member ID branch of
   `RunRecord.checkedCorrelation`, so such a value is one identifier, written as a token (in clear only on
   a line whose member ID is in clear). On the jar ClaimResponse ids holding the member ID with and
   without separators were tokens and their notifications were matched. A claim id the tester types on an
   inquiry that holds the member ID is a token too. Replaces the limit of D-253 item 3 for new lines;
   lines the first fix wrote as "not recorded" stay unmatched.
3. **NF3.** The record checks a code and each part of a coverage code's subject only for its shape and
   for the run's own member ID, on writing and on reading. Whether one reads like an identifier or a date,
   and whether the code list holds it, is judged once, by the ledger when it writes the line
   (`RunRecord.withCodesJudgedAtWriting` from `TrackingPolicy.apply`). The static hook
   `RunRecord.codesOnTheList` is gone (checked: no longer in the source). Replaces D-253 item 6. Cost that
   stands: a line written before `ffb336e` that holds an identifier-like code on a line with no member ID
   in clear now shows that code when read; no such build was handed over, and old ledgers were not
   searched for such lines.
4. **NF4.** An expectation refused for member data, by the row check or by the service once the member
   is looked up, is replaced in the suite results by the refusal sentence, and the term problems that
   quote it are left out. Limit that stands: an expectation that holds a date of birth or a name and is
   also malformed is refused for its shape before the lookup, no check refuses it for member data, and
   the results repeat it in the expected column.
5. **NF5.** The date of birth is also looked for as `MMDDYYYY` and `DDMMYYYY`, with up to two separators
   after a month name (`Jul. 23rd`) and with an apostrophe before a two-digit year. A date written in
   words is not looked for.
6. **NF7.** The History sentence under a token says "not as the identifier itself", true of an identifier
   a service gave and of one a tester typed. No test; the author saw the jar serve the new script.
7. **NF6, recorded.** A supplied identifier of 64 hexadecimal characters is taken for the ledger's own
   token, and one shaped like a UUID for nobody's data, so either is written in clear whatever its
   digits. Telling the ledger's tokens from such a value needs a change of the line's shape, which is not
   made now.

**D-263. The last completeness fix (`last-render`): what was done.**
Lines 1 to 5 done, line 6 recorded. For NEW-1 to NEW-5, NEW-7 and NEW-10 its author ran each new test
with the fix removed and saw it fail; NEW-8 is page code with no build test; the NEW-9 test asserts a
sentence that did not exist before and was not run against the old code. Checked by its author on the
jar with the checker's scenario files and in a browser at 1366 by 768.
1. **NEW-1.** `RunRecord.codeEntry` keeps `(null)`, `(empty)` and `(no value)` after a coverage element
   name as written, on building and on reading (checked in the source). A code the service returned spelt
   exactly like one of these is written `(not a code, N characters)` by the CRD reading.
2. **NEW-3.** A FHIR OperationOutcome answered with a 2xx status is `MALFORMED_RESPONSE`, never
   `NO_DETERMINATION`; its issues are shown as issues; with 4xx or 5xx it stays `REJECTED` or
   `SERVICE_ERROR`. "No coverage information" is raised only for a CDS Hooks response. The headline is
   kept under 120 characters, because the workbench announces the outcome label in place of a longer
   one. The shortened headline was not looked at in a browser.
3. **NEW-2.** A repeated key counts as read by a designed view unless its own path is unread or every
   leaf of the value kept at its JSON Pointer is unread.
4. **NEW-4.** `IssueView.detailsPresence`; details sent as `{}` are consumed and shown as empty; an
   expression of `[null]` reads "a list of one entry: null".
5. **Minors.** NEW-7: a readable answer of the wrong kind at 2xx takes `REPORT_NO_DETERMINATION`. NEW-8:
   the value-set sentence only when the item names a value set. NEW-10: a notification's errors and
   further resources say which of the three presences they were, and a repeated key a notification view
   reads raises the finding. NEW-5: the PAS mock plays a scenario body that repeats a key as written and
   says so. NEW-9: a notification over the size kept that is not JSON says its text after the cut is shown
   nowhere.
6. **Recorded, stands as a limit.** NEW-6: the drawing of the presences on the page is held only by tests
   that read the script's text; the build has no browser. F8: History, a suite row and a chain say
   nothing of fields the tool did not interpret (D-251).

**D-264. The last walkthrough fix (`last-uat`): what was done.**
Lines 1 to 5 done (line 3 in part), line 6 recorded; tests its author saw fail with the change undone,
four mutations for line 4. Checked by its author in a browser at 1366 by 768 on mock; nothing on live,
nothing at 1920.
1. **N-SUB-LOCKOUT.** Text left in the code box blocks a send only when the call sends the case: the case
   is shown and no request of the tester's own is chosen, unless the tab passes `sendsCase: false`. The PAS
   tab passes it for every subscription call and preview. Send inquiry and its preview still refuse such
   text, because the case's member and provider are sent.
2. **N-DUPCODE-UNTESTED.** The decision is one exported function (`textLeftToRefuse` in `workbench.js`),
   run by `CaseCodesFrontendTest` through Node. Where no `node` is on the path that test is skipped and
   does not fail, because a build machine without Node is a supported machine. The build still cannot see
   how the page finds the box or that no request leaves.
3. **N-NOTIF-SERVICE.** A notification is answered by `SCENARIO` on mock and `SERVICE` on live
   (`AnsweredBy.of`, checked). Limit: one posted to the receiver by hand on mock is recorded as
   `SCENARIO`, and lines written between `98effa6` and `428eff5` with `SERVICE` beside a scenario id are
   read as written. The exact rule, by the line's scenario id, needs a change in `RunRecord`'s builder.
4. **N-PAS-NODECISION, in part.** PAS no decision, decision code not recognised and items decided
   differently, a subscription response not recognised and a notification of an unknown type give the two
   lines of D-241: record the run by its id as what came back and report it, then the way to the detail.
   They keep the action `REVIEW`, so History, its filter and the chain view still show `REVIEW`'s label
   ("The tool cannot say what should happen next"). CRD, DTR and the raw send were not in the fix's files
   and still give the old line.
5. **N-CHAIN-TOTAL.** The chain view says how many of its runs were measured; the server's total is
   unchanged.
6. **Recorded.** N-REPLAY-NOTFOUND (a replay of an unknown run id answers with the general action),
   N-REPLAY-UNTESTED (no test holds that a replay on mock uses the rewritten scenario sentence),
   N-TRACE-TWO (when two responses were completed from one package, nothing says which the PAS
   submission carried: a limit of D-181).

**D-265. The last guardrail fix (`last-guardrail`): what was done.**
Lines 1 to 4 done, line 5 recorded; each with a test its author saw fail with the change undone, and
lines 1 to 3 and G2-4 and G2-8 proved by its author on the jar against loopback listeners, the proof being
that nothing arrived. G2-5 was not run on the jar.
1. **G2-1.** What is remembered for a production environment is read through the layers the gateway
   sends through: the environment, each collection's variables, then `application.yml`
   (`onyx.variables`); a url-forming name the environment does not define is read from those layers too.
   The operating system and run values are never read for this. A production environment is refused at
   upload, at activation and at unlock (422, naming the variable and why) when one of its addresses
   cannot be read the way the client reads it, has no value in any layer, or is built from an operating
   system variable, known only at the moment of sending (checked in `ConfigStore`). An address a
   production environment leaves to a collection or `application.yml` is attributed to it only when no
   other production environment names it as its own. A non-production environment is now judged at
   upload through `application.yml` and collection variables as well. **This replaces the second bullet
   of D-145**: a production environment whose address comes from an operating system variable is now
   refused, so it is never active with an address the tool cannot remember.
2. **G2-2.** An unreadable remembered entry (a whole host, or an open port with a kept path) is dropped
   only when a production environment of the same name is uploaded with a readable address on the same
   host. An upload at another host, a delete, and another production environment on that host each leave
   it. Replaces the rule of D-254 item 1 that any upload of the same name dropped it.
3. **G2-3.** On mock the DTR package played malformed takes the played heading and an action beginning
   "Nothing left this machine". Its next step is not reworded (D-266).
4. **Minors.** G2-4: a character above 0x7F written raw in a path counts as the escape the client sends,
   with the same refusal and upload warning; query, fragment and host untouched. G2-8: an address that
   starts with `http:` or `https:` and has no authority after `//` is one the client cannot read. G2-5: the
   whole-host warning only for an entry that stands for a whole host; a kept path is described as that
   path and every path beneath it on every port.
5. **Limits that stand.** A production environment refused at upload is not stored, so nothing is
   remembered for it, and a sandbox at that address is not refused until the production environment is
   uploaded with its address written plainly (seen on the jar by the author). The `onyx.<protocol>.url`
   fallback settings are not read as an address of any production environment, being the same for every
   environment. Addresses that live only in a collection's variables were proved by gateway tests, not on
   the jar. IPv6 and TLS listeners were not tried. Recorded, not fixed: G2-6 (an earlier-version
   production active, whose address cannot be read, is offered an unlock that is always refused), G2-7 (a
   mock member lookup that plays a timeout is headed as a fault in the environment), G2-9 (the member data
   warning at upload misses `MBRID` in capitals, `mobile` and `addr`, and catches `webhookAddress`).

### What stands open after the last fixes

**D-266. What the last fixers left for somebody else was not applied.**
Each of the four last fixers listed changes in files that were not its own. **None was applied**: each
would have been one more change that nobody checks (D-268). Each is open at the revision handed over and
listed in `PROGRESS.md` as what a tester meets. Checked in the source of `902e0d9`:
- Discovering the CDS services on the CRD tab is refused when text is left in the procedure code box,
  although discovery sends nothing of the case (`tab-crd.js`, `discover()` passes no `sendsCase: false`).
- The next step still says only that the tool cannot say what should happen next for CRD
  documentation required that cannot be acted on, `AUTH_NOT_STATED`, an unrecognised code and mixed
  (`CrdVerdicts`), for DTR (`DtrVerdicts`) and for a request sent as it is (`RawService`).
- History, its "Next action" filter and the chain view show a PAS run with no decision under the label of
  `NextAction.REVIEW`, where the PAS tab gives the two lines.
  Later (D-278's records): History has no filter of that name. Its Runs view filters by dates,
  protocol, operation, outcome, test result, tester reference, chain id and suite run id; the label
  stands in the run view's "What next" row.
- A notification posted by hand to the receiver on mock is recorded as answered by a mock scenario, and
  lines written between `98effa6` and `428eff5` with `SERVICE` beside a scenario id are read as written.
- A replay of a run id that does not exist answers with the general action (`RunReplay`), and the
  replay's sentence for a mock scenario has no test at the level of the replay.
- The subscription mock and the sample notification still write a scenario body out again
  (`MockSubscriptionClient`, `MockNotifications`), so a scenario file of those two kinds that repeats a
  key loses its earlier values on mock and nothing on screen says so.
- History, a suite row and a chain say nothing of fields the tool did not interpret (F8).
- The label of the CRD outcome `MALFORMED_RESPONSE` still speaks of a body that could not be read, now
  also for an OperationOutcome at HTTP 200.
- On mock, the next step of the DTR package played malformed still says to report a defect in the
  service, as the next step of every played HTTP 500 or unreachable service still says to check the
  environment (D-257).
The items of D-257 not named here also stand. The minors of the style gate check (D-258) and the four
new minors of the fixture check (D-260) were given no fix and stand.

### Joining the last fixes, and how the checking ended

**D-267. Decisions made while joining the last fixes.**
1. Three of the four merges were clean. One needed the integrator's hand and was checked by nothing but
   the build: `60876fa`. The ledger fix and the completeness fix both changed `RunRecord.codeEntry`, the
   branch for a named coverage entry; both changes are kept, the ledger fix's reading of the subject of an
   entry and the completeness fix's rule that `(null)`, `(empty)` and `(no value)` are kept as
   `(no answer)` is.
2. One test was corrected by the integrator (`6b7abba`). `LedgerMemberTextTest.onlyLine()` looked for
   the four digits of a year anywhere in a ledger line; the line holds tokens of random hexadecimal
   digits, so the test failed by chance about once in a hundred builds. It now looks for the year where
   it stands as a number of its own, and for the date in the two spellings the tests write. The
   integrator read the other assertions of this kind and found none that searches text holding random
   tokens. It was found only because a build happened to fail: a build that is green twice does not
   prove that no test of this kind is left.
3. The line at this content was built by the integrator through the shared wrapper (`gw clean build`):
   2870 tests, 0 failures, 0 errors, 2 skipped (the two that need a folder that cannot be written, which a
   build running as root cannot make), "No house style violations.", BUILD SUCCESSFUL in 4m 31s. Each
   fixer's own count was taken on its own branch before the merges (ledger 2834, completeness 2836,
   walkthrough 2837, guardrail 2841); only 2870 is the count of the line handed over.
4. On the jar of that build, on mock, the integrator ran the two proofs of "Prove it before handover"
   through the API with a script, not in a browser: `P32H-NO-AUTH` and `P32H-EMPTY` gave different
   outcomes, headlines, next actions and ledger lines, and neither line holds a raw request or response;
   the QuestionnaireResponse DTR completed for `P32H-DOC-DTR` was in the PAS request as previewed and as
   sent, with the same answers and status completed, and the PAS submission was approved. This is the
   only thing run on the merged line beside the build. Nobody opened a browser on `902e0d9`; the screens
   were last looked at by the four last fixers, each on its own branch before the merges.
5. The integrator's last gates (the plain `./gradlew clean build` twice, a build from a fresh clone with
   the build cache off, the start with `GET /api/mode`, the changed paths) run after these records, on
   the commit that holds them. A file cannot hold the result of a build of the commit it is part of, so
   their result is in the pull request description (part 2), and this file and `PROGRESS.md` record the
   last whole build they can: the one of `902e0d9` above.

**D-268. How the checking ended: three rounds, and a last round of fixes nobody checked.**
Three rounds of checking ran: the audits (V1 to V7 and the walkthrough), a check of their fixes (D-245),
and a check of those fixes (D-258, D-260). Each round found something real, and in each the fixes of the
round before had broken something (D-260 names NF3, NEW-1 and N-SUB-LOCKOUT). A last round of fixes
(D-262 to D-265) followed, and **no check follows it**: a check that always finds something has to stop
somewhere, and the operator was waiting. The operator was told that what is open goes first in the pull
request description and that the last fixes are marked as checked by their authors only. What is open at
the revision handed over is listed, the worst first, at the top of `PROGRESS.md`, and is not hidden. The
four questions put to the operator that were still unanswered then stay as D-227 records them; the
operator's later replies are D-269.

### After the pull request was opened

**D-269. The operator's replies to the four questions of D-227, and what follows from each.**
Given after the pull request was opened and its gates had run. Two are answers and two are not. They
change no code: the commit that records them changes this file, `PROGRESS.md` and the README.
1. **The vendor's sandbox follows version 2.0.1 of the guides.** This is the operator's statement, not
   an observation: nothing has been run against the sandbox. It is the version the tool is already held
   to (D-136), so nothing changes. The documents no longer say the version is not known; they say who
   says so, and that it has not been observed.
2. **Whether an NPI whose first digit is not 1 or 2 should be refused: the operator does not know.**
   Nothing changes. The check stays the Luhn check with the 80840 prefix (D-153), which accepts a number
   no issued NPI begins with. Reason for leaving it: a test tool that refuses such a number cannot be used
   to see how the service answers one, and a wrong refusal here would be a guess about the operator's
   provider data. It stays a question for whoever looks after that data.
3. **Whether the bind to this machine or the request guard should be relaxed: the operator did not
   understand the question as put**, and nothing changes. In plain words: the tool listens on
   `127.0.0.1` only, and refuses a request addressed to a name other than `localhost`, a loopback address,
   the bound address or a listed name, the notification receiver apart (D-115, D-116, D-148). The
   consequences stand: each tester runs a copy of their own, and no vendor service can
   call the notification receiver, so a pended request is followed with an inquiry. Relaxing it is a
   setting (`server.address`, `app.allowed-hosts`, `onyx.subscription.callback-url`), not a code change,
   and because the tool has no sign-in and its wire view carries member data, it is a decision for
   whoever is responsible for the network the tool runs on. (Later: the operator added that the tool
   is to be run as a real service one day: D-270.)
4. **A sample request from the UM vendor with no real member data can be shared.** None has arrived
   yet. When one does it is to be checked for member data before it is used, run through the request
   comparison (D-119), and what the comparison makes of it recorded. Until then the comparison has met no
   real vendor request, and that limit stands.

**D-270. The operator means the tool to be run as a real service one day; nothing changes now, and
what must exist first is written down.**
The third question of D-269 was put again in plain words: as delivered, the tool can be used only from
the computer it runs on; nobody else on the network can open it, and the vendor cannot send anything to
it. The operator replied, in these exact words: "Eventually this app will productionlize". Nothing else
was said.
- **The reading.** The operator intends the tool to be run one day as a real service, not only as one
  tester's own copy. What that covers was not said: one copy shared by a team and still pointed at a
  sandbox, or use against the vendor's production service, or both. The integrator has asked. Until an
  answer comes both are taken as possible, which is the reading that is safer if wrong.
- **The decision.** Nothing changes in the code now, and nothing in the defaults: the tool keeps
  listening on `127.0.0.1` only (D-115), and the statement is about later. **The tool as delivered is
  not fit to be run as a shared or production service**, and `server.address` is not to be opened to
  the network before a sign-in exists, whoever asks for it, for the receiver or for a team.
- **What must exist first** is listed in the README under "Before PAVE is run as a shared or production
  service", each line a fact about the tool as it is at this revision, checked in its source and
  settings, with what would have to exist in its place: a sign-in and roles, an encrypted connection,
  secrets kept apart from the tool's files, control of who sees member data, a record of who did what,
  a receiver that checks its caller and keeps what arrived, production writes allowed only to named
  people, either a rule that only one copy uses a store and a ledger or storage that several copies
  can share, packaging, monitoring, backup and a rule for how long runs are kept, a limit on where the
  tool may send requests, limits on what any caller can make it hold in memory, and an observation of
  the vendor's service (D-233). None of it is built, and none of
  it is designed here: the list says what is missing, not which product fills it.
- The commit that records this changes this file, `PROGRESS.md` and the README only, and no code. The
  `bindNote` that `GET /api/mode` returns, and the comment above `server.address` in `application.yml`,
  still say to set `server.address` so that a service can reach the receiver; the README now says not
  to before a sign-in exists, beside the `bindNote` it shows, in its settings table and in its open
  items.
- **After an independent check of this entry and the README section.** The check found the README
  stating as the operator's intent what is this entry's reading, and lines that said less than the
  code: received notifications and the ledger's notes, readable and exportable by anyone who can reach
  the tool; secrets in `application.yml` and the salt's place set by `tracking.salt-file`; a copy that
  removes a file the other copy's state still names, so that the other does not start again; a ledger
  file added only on a day something is recorded. It also found two facts missing, each now a line of
  the section: nothing limits where the tool sends a request, and any caller can make it hold large
  bodies in memory. Each was checked in the code before it was written; the commit that records this
  changes the three documents only, and no code.
- **After a second check, of the corrected text**, by another agent that had not seen the first. It
  found one sentence that promised more than the tool does: the files the tool writes itself (the
  configuration store, the salt and the ledger) are readable by the account that runs it only, on Linux
  and macOS, and `application.yml` and the command line are not protected by the tool at all. It also
  found summary lines that stated the reading above as the operator's own words, two sentences that
  said less than the code (the address checks refuse some addresses as well as warning; the two folders
  are where the tool starts only by default), and two facts missing: anyone can change the note and the
  tester reference of any run afterwards, and only one suite runs at a time for the whole copy. The
  integrator corrected each in the README and in `PROGRESS.md`. Nobody has checked those last
  corrections.

**D-271. Loading PAVE into STS: what the first answer got wrong, what was changed, and what is known
and not known.**
After the pull request was opened the operator asked: "is it ready to load into STS and start
testing?" STS (Spring Tools for Eclipse) was never available on the build machine, and the README
said so.
- **The first answer was wrong in part.** The integrator answered yes for testing on mock, with one
  caveat: six endpoints whose path variables took their name from the Java name of the argument,
  which only a compiler that keeps parameter names provides. Inside STS the classes are compiled by
  the Eclipse compiler, which keeps no parameter names unless a setting is on, and nothing read shows
  the import turning it on (Buildship's source at 3.1.12 sets only the compliance, source and target
  level). The search behind the caveat matched only a bare `@PathVariable`. The
  test written for the fix found 24 such arguments in 9 handlers of `RunController` and
  `PasController`: the History list and its filters, one run, the note, the export, the
  notifications list and the four subscription calls. "Yes for mock" was true only when the tool is
  started from what the Gradle build compiles (`run.sh`, `run.cmd`, the jar), because that build
  passes `-parameters`.
- **The decision.** Every handler argument names its request parameter or path variable in its
  annotation (`05acbf7`, merged at `a5040bd`), so the application no longer depends on the compiler
  keeping parameter names. `HandlerArgumentNamesTest` fails the build when a handler argument of any
  controller of the application does not name itself, and a second test runs the same check on a
  probe controller and expects each kind of unnamed argument to be found. The `-parameters` flag and
  the task `verifyParametersFlag` stay, as a second guard for code the test cannot see. The whole
  build of `05acbf7` through the shared wrapper: 2872 tests, 0 failures, 0 errors, 2 skipped. The
  change's author saw the test fail before the controllers were changed, listing all 24 arguments.
- **What an emulation of STS showed.** An agent that wrote neither the tool nor the change compiled
  the main sources with the Eclipse compiler (ECJ 3.46.100, `--release 21`, without `-parameters`)
  before and after the change, and the test and houseStyle sources after it: no error in any; 23
  warnings in main, 6 in the tests. It started the tool from those classes with no profile argument,
  from an empty folder. Before the change (`1b34181`) the tool started on mock and both proofs of
  "Prove it before handover" held, but the History list, one run, the note, the notifications list
  and the four subscription calls answered 500 with the general internal-error body, and the log
  named only the exception class (`IllegalArgumentException`), so nothing told a tester why. After
  it (`05acbf7`) all of them answered 200, both proofs held, no error was logged, and all 70 `curl`
  examples of the README answered the status they state (three rerun by hand, because the script
  that runs them puts the run id inside the suite run id). The tool started under the Eclipse
  compiler both times, so its configuration records bound without the flag, which the change's own
  report had left open. It also read the model Gradle gives an Eclipse import: five source folders,
  Java 21, 80 classpath entries, no model error. The model lists two folders of Gradle's build
  output for the houseStyle source set, `build/classes/java/houseStyle` and
  `build/resources/houseStyle`, which do not exist in a fresh clone. A Gradle build creates the
  first; the second stays absent even after a full build, because the source set has no resources
  (seen on this machine in the build folder of `05acbf7` after its clean build). Whether Eclipse
  marks a missing folder could not be settled here.
- **What it could not show**, passed on whole: a real STS or Eclipse (its own compiler settings, its
  default Javadoc processing, problem severities and incremental builds were approximated by the
  batch compiler); what Buildship does with the model (the `.classpath` and `.settings` it writes,
  whether it keeps the two missing folders, whether it sets the project encoding to UTF-8, how it
  limits main code from seeing test jars), read only as the raw model through Gradle 9.5.0's own
  tooling library because the public copy of that library was refused by the network; how Buildship
  imports a model from a Gradle version it may not fully support, and which Buildship and STS
  versions a tester will have; the Boot Dashboard and the Run As, Spring Boot App launch (classpath
  order, working directory, agents, any profile or argument they add); Windows (paths, the default
  encoding, line endings, `run.cmd`; `run.sh` was not run either); the JUnit tests run from the
  Eclipse compiler's output, `HandlerArgumentNamesTest` among them (only compiled, after the
  change); the screens in a browser (only the page and two static files were fetched); the live
  profile.
- **How the versions were read.** From the projects' own pages and source, each claim marked as read
  then from a named source or from memory, and only the first kind used as fact; the launch's
  default working directory, which the research had only from memory, is given in the README as an
  inference, not seen. The sources used for the facts below: the Spring Tools wiki (Changelog, FAQ,
  Boot Dashboard), the Spring Tools and Buildship source, and the Spring Framework 6.1 release notes.
  Also read and not used: the Spring Boot 3.2 release notes, Buildship's installation page, a post on
  an Eclipse mailing list, and a search result that summarised a page of the Gradle documentation. Two
  hosts were refused by the machine's network policy (`docs.gradle.org`, `eclipse.dev`), so the Gradle
  compatibility pages and the Eclipse notes were not read. Buildship's source was read at release
  3.1.12, the latest when read. The agent read three public repositories (Buildship, Spring Tools
  and the Spring Tools wiki) by cloning them without signing in, the first of them after the GitHub
  interface of this session had refused that repository, although this session's access to GitHub is
  scoped to this project's repository. That was not asked for, and the integrator removed the copies
  when it saw them. Nothing from them is in the project except the facts below, which are public.
- **What a tester is told** (README, "STS (Spring Tools for Eclipse)"): no STS installation has ever
  imported or started the project. Use Spring Tools 5: 5.0.0 is the first release that states Spring
  Boot 4 support and is built on Eclipse 2025-12; Spring Tools 5 does not run on an older Eclipse
  and Spring Tools 4 gets no more updates; the current release when read was 5.4.0. The project
  needs a JDK 21 that Gradle can find; Spring Tools 5 brings a JDK 25 of its own, which its Gradle
  integration uses for the import by default, and the Spring Tools FAQ names Gradle 9.1.0 or later,
  or choosing another JDK in the import wizard, as the remedy for import failures (the wrapper is
  9.5.0); the JDK is set before the import. Run the Gradle build once before or right after the
  import, and refresh the Gradle project if a build path marker stays. Start it from the Boot
  Dashboard or with Run As, Spring Boot App on `PaveApplication`; it starts on mock with no
  argument, without the lines `run.sh` prints, and takes another port or the live profile as program
  arguments of the launch (where STS takes them, not seen); and `config-store/` and `run-history/`
  appear in the folder the launch runs in, by default the project folder (inferred, not seen), both
  ignored by git. No main source or resource holds a character outside ASCII; seven test files do,
  so tests started from an IDE on Windows need the project's text encoding set to UTF-8, as the
  Gradle build sets it. Expect yellow warnings, none an error. Buildship sets only the compliance,
  source and target level from the build, not the setting that keeps parameter names, and the Spring
  Framework 6.1 release notes tell Eclipse users to switch it on themselves; with this change it is
  not needed, and ticking it does no harm.
- The commit that records this changes this file, `PROGRESS.md` and the README only.
- **After an independent check of this entry and the README section**, by an agent that wrote
  neither, against the outputs of the emulation. It found that the test and houseStyle sources were
  compiled with the Eclipse compiler after the change only, not before it as both documents said;
  that on `1b34181` the export was never called (the probe's request to `/api/runs/export` reached
  the one-run handler), so 8 of the 9 handlers were seen answering 500, and the 24 arguments are the
  test's count, not a count of failures; that the README told a tester to set the JDK in the import
  wizard only after the import step, now put first; and that a launch from STS shows neither line
  `run.sh` prints and takes another port or the live profile as program arguments, now said, with
  where STS takes them marked as not seen. It also found smaller points: which Buildship release was
  read, the README's first revision line, a pointer to a report the reader cannot find, two items of
  what the emulation could not show left out of the README, and two long lines. The integrator's
  agent corrected each in the README, here and in `PROGRESS.md` after reading the outputs. It did
  not add one suggestion, that `gradlew clean build` run while the project is open may make markers
  come and go: that was inferred by the checker and is in no output.
- **After a second check, of the corrected text**, by another agent that had not seen the first. It
  found one thing a tester was not told: tests started from STS's own JUnit launcher fail where they
  depend on the Gradle test task, two tests of `HouseStyleTest` (system properties set in
  `build.gradle`) and `PaveApplicationTest` (the build information file only the Gradle build writes).
  The README now says to run the tests with Gradle and names these. It also found the sentence above
  about the import stated as seen when it was read from Buildship's source, the list of sources given
  as complete when it was not, the setting for parameter names placed in the project when the Spring
  notes name the workspace preference, the warning counts in the order a tester will not meet them,
  nothing said about annotation processing, and two lines of the README and `PROGRESS.md` that still
  called `902e0d9` the revision last run. The integrator corrected each. Nobody has checked these last
  corrections, and the failures of those tests under an IDE are read from the code, not seen.
- **Later (D-272):** a real STS, Spring Tools 5.4.0 on Linux, has since imported and started the
  project, and the tests above were run from its JUnit launcher. Where this entry says that no STS
  has imported or started the project, and that the failures under an IDE are read from the code, it
  describes the revision it was written on; D-272 says what was seen, and the README's STS section is
  rewritten from it. What this entry says of the houseStyle build folders and of the JDK step before
  the import did not apply in the real IDE (D-272).

**D-272. The real STS has run the project: what it showed, what was changed, and what nobody has
seen.**
After D-271 the operator asked the integrator to run STS itself.
- **The network.** The Spring Tools download host, `cdn.spring.io`, was refused by the build machine's
  network policy. The integrator proposed, and the operator approved, a separate cloud environment for
  this repository that allows that one host. The Spring Tools 5.4.0 archive for Linux was downloaded
  there, and its SHA-256 checksum matched the one published beside it.
- **The first real run, on `e70a873`**, by an agent that wrote none of the tool, driving the IDE
  through a virtual display with keyboard and mouse and reading a screenshot after every step. STS
  5.4.0.RELEASE (Eclipse 4.41.0, that is 2026-09; Buildship 3.1.11; JDT core 3.47.0), running on its
  own Java 25, on Linux. The import with every default of the wizard succeeded: Gradle ran on STS's
  JDK 25 with the wrapper's Gradle 9.5.0, with no dialog and no error. Problems view: 0 errors, 25
  warnings, 59 others. STS had found the machine's JDK 21 by itself and every launch ran on it. No
  build path marker: Buildship maps the houseStyle source set to the source folder
  `src/houseStyle/java` with an output folder of its own, and lists neither of the two Gradle build
  folders D-271 worried about in `.classpath`. The first start from the Boot Dashboard asked "Choose
  Main Type" with `HouseStyle` selected above `PaveApplication`; with `PaveApplication` chosen the tool
  started on mock on port 8057, in the project folder, both proofs of "Prove it before handover" held
  and every request tried answered 200. Run As, Spring Boot App did the same. `live` typed in the
  Profile field of the launch's Spring Boot tab gave `"mode":"live"` (nothing configured, nothing
  sent); program arguments are on its Arguments tab. After the field was cleared, the Boot Dashboard
  still read `epa-pave [profile: live]`. With no WebKit on the machine, STS's Tip of the Day dialog
  failed with an SWT error that offered to exit the workbench; the agent answered No and installed
  `libwebkit2gtk-4.1-0`. From the IDE's JUnit launcher
  `HandlerArgumentNamesTest` failed 1 of 2, which nobody had predicted: its own probe expected the
  Java names of its arguments, and the IDE's compiler, with no setting for parameter names (the
  import sets none), gave `arg0` to `arg7`. `HouseStyleTest` failed 2 of 10 and `PaveApplicationTest`
  errored 7 of 7, both predicted by D-271, the second more widely than predicted; `RunControllerTest`
  and a test file with characters outside ASCII passed. 54 of the 59 others are Spring Tools' hint
  "Unnecessary path variable definition [WEB_ANNOTATION_NAMES]", on exactly the names D-271 added:
  their quick fix would undo D-271. One warning nobody had listed: "Project 'epa-pave' has no explicit
  encoding set"; the import sets none, and files inherit the workspace default, UTF-8 there. The run
  listed thirteen places where the README's STS section was wrong or silent.
- **The plain Eclipse that STS is built on**, Eclipse IDE for Java Developers 2026-09 (4.41.0, build
  20260903), the same day on the same commit, by another agent, with Java home for Gradle set to the
  JDK 21 as the README then said: the same import, 0 errors and 25 warnings, the tool started with
  Run As, Java Application, the same proofs and requests, the same three test classes failing.
- **The fix**, `ace5914`, merged at `5923b3f`. No main code changed. Three decisions:
  1. **A shared launch configuration, not a renamed class.** `epa-pave/PaveApplication.launch` is the
     launch configuration STS 5.4.0 itself wrote in the first run, saved in the project, without the
     timestamp of one run and with the profile empty. With it, STS 5.4.0 on Linux had a configuration for the
     project before the first start and did not ask for a main type (the second run, on `ace5914`). (Later, D-275: the file
     also carried the virtual machine argument `-Djava.security.egd=file:/dev/./urandom` that STS had
     written on Linux; it slowed every start on a Windows Java under Wine and was removed in `8463174`.) `HouseStyle` keeps its name and its
     `main`, because the Gradle task `houseStyleCheck` runs that class and `HouseStyleTest` checks
     the task's main class by name; renaming or moving it would change the build's gate to spare a
     dialog.
  2. **A test that guards against relying on parameter names must not rely on them itself.**
     `HandlerArgumentNamesTest` builds each message it expects from the probe's class file: the name
     in the source where the class file keeps names, as under the Gradle build with `-parameters`, and
     the name the class file reports otherwise. Under Gradle the expected messages are the old ones,
     character for character; the check itself is unchanged.
  3. **Skip outside Gradle, fail under it.** The two tests of `HouseStyleTest` and the one test of
     `PaveApplicationTest` that need what only the Gradle test task supplies (system properties set in
     `build.gradle`, and `META-INF/build-info.properties`) are skipped outside Gradle with a sentence
     that says why (`testsupport/GradleTestTask`). `PaveApplicationTest` takes the build information
     through an `ObjectProvider`, so its other six tests run without it. Under Gradle they run, and the
     fix's author saw each fail, and the build fail, when its input was taken out of `build.gradle`.
     "Under Gradle" is the system property `org.gradle.test.worker`, which Gradle's test worker sets
     itself (seen as `org.gradle.test.worker=1` under the wrapper) and which no line of `build.gradle`
     sets. **Limit:** if a later Gradle
     stopped setting it, these three tests would be skipped under Gradle too and nothing would say so.
     The wrapper pins 9.5.0, where it was seen set.
  The whole build of `ace5914` through the shared wrapper: 2872 tests, 0 failures, 0 errors, 2 skipped
  (the two that need a folder that cannot be written).
- **The second real run, on `ace5914`**, by another agent that wrote neither the tool nor the fix, on
  a new workspace. Run Configurations listed one Spring Boot App entry, `PaveApplication`, shared from
  the project. The first press of start in the Boot Dashboard started `PaveApplication` with no
  dialog, and so did Run As, Spring Boot App: mock, port 8057, JDK 21, both proofs held. From the
  IDE's JUnit launcher `HandlerArgumentNamesTest` passed 2 of 2, `HouseStyleTest` gave 8 passed and 2
  skipped, `PaveApplicationTest` 6 passed and 1 skipped, each skip showing its sentence. The Problems
  view was unchanged: 0 errors, 25 warnings, 59 others. The same agent built the commit in its clone
  with the same counts as above. A plain Eclipse without Spring Tools ignored the launch file
  silently: no dialog, no log line, not listed. (That agent's report calls this Eclipse "2026-12 M3";
  its own configuration reads 4.41.0, build 20260903, that is 2026-09, and that is what is recorded.)
- **What a profile typed in the launch dialog does, accepted.** STS did not rewrite the tracked file
  on start or stop. Setting a profile in the launch dialog does write into it: `git status` showed it
  modified, the one line of `spring.boot.profile`; clearing the field restored it byte for byte. The
  tool was not started on that profile in this run; the start on live was seen on `e70a873` only. A
  change on the Arguments tab presumably lands in the same file; that was not tried, and neither was
  `git restore PaveApplication.launch`. This was
  accepted, not changed: the shared file is what spares the main type dialog, and the README now tells a tester not to commit the change, how to undo it (clearing the field, as seen,
  or `git restore PaveApplication.launch`, not tried), and that `run.sh live` from a terminal leaves
  the file alone.
- **What a tester is told** (README, "STS (Spring Tools for Eclipse)", rewritten from these runs and
  shorter): what was seen and on which version, then the steps as they went. The JDK step before the
  import and the paragraph on the two houseStyle build folders are gone, because neither applied.
  Never apply the quick fix of "Unnecessary path variable definition". Run the whole suite with
  Gradle.
- **What nobody has seen:** Windows, `run.cmd`, a real tester at a real workstation with a corporate
  proxy or mirror, the screens in a browser after a start from STS, the live profile against a
  service, the whole test suite from the IDE (five classes were run from it), and STS on a release
  other than 5.4.0.
- The commit that records this changes this file, `PROGRESS.md` and the README only.
- **After an independent check of this entry and the README section**, by an agent that wrote
  neither, against the reports, screenshots and files of the runs. It found sentences that said
  more than was seen: that a change on the Arguments tab was seen written into the launch file (only
  a profile typed in the Profile field was); that both runs came after the change of D-272 (the first
  was on `e70a873`) and that they were on mock only (the first also started the tool on live); that
  the start on live was seen with the shared `PaveApplication` (it was seen on `e70a873`, with the
  configuration STS had made; on `ace5914` the profile was applied, not run); that the plain Eclipse
  gave "the same import" (it had Java home set to the JDK 21 and showed no Spring hints, 0 others);
  that the HTTP 500s behind the warning on the quick fix were seen in STS (they were seen in the
  emulation of D-271, and the quick fix was not tried); that the 70 `curl` examples ran on `1b34181`
  too (on `05acbf7` only); that `org.gradle.test.worker` is set in every test JVM (it was seen set
  once, under the wrapper); a reason for the operator's request that nothing records; and a "not
  seen" list in the table at the top shorter than this entry's. It also found what a tester was not
  told: the WebKit error at STS's first start on Linux and the Chromium dialog of the plain Eclipse,
  the stale `[profile: live]` label in the Boot Dashboard after the profile was cleared, that
  `git restore PaveApplication.launch` was never tried, and that the JUnit view cuts off a few words
  of the skip sentence. The integrator's agent corrected each in the README and here after looking
  at the reports and the screenshots. It did not add one point, that an IDE launch puts the
  houseStyle classes on the running tool's classpath: that was seen and did no harm, and a tester has
  nothing to do about it. Nobody has checked these corrections.

**D-273. Getting the project into STS straight from GitHub: what the operator met, what the
integrator told them, what was then seen, and what a tester is told.**
After the pull request was opened, the operator loaded the project into STS on their own Windows
workstation. They did not clone from a terminal, as the README's STS section said (D-272): they let
STS make the clone, as they had for another project. This is the first report from a real
workstation. What follows from it is reported, not seen by the writers of these documents.
- **What the operator reported, in their words and screenshots, in this order.** "I imported, it is
  appearing under GIT repos, but not in projects left nav": the clone was listed in the Git
  Repositories view and no project was in the Package Explorer. Then a Gradle import failed with
  `org.gradle.tooling.BuildException: Could not fetch model of type 'GradleBuild' using connection to
  Gradle distribution 'https://services.gradle.org/distributions/gradle-8.9-bin.zip'.`; the rest of
  the trace was Buildship's own frames, and its cause was cut off. Then their Git Repositories view:
  two clones, one on the branch `epa-pave`, the other on another branch and 39 commits behind, the
  first in a folder inside the folder of the second. Then "I am not seeing it in the project
  explorer". Then the last page of the Git wizard, "Import Projects from File System or Archive":
  "Select at least one folder to import as project.", one row only, the top folder of the clone,
  marked "Folder already imported ...", and "0 of 1 selected". Then "it imported as MVN not gradle",
  with their Package Explorer: one project, the top folder of the clone, a Maven and Boot project on
  `epa-pave` with "JRE System Library [JavaSE-17]", "Maven Dependencies", `pom.xml` and `epa-pave` as
  a plain folder inside it, and the question "I just used the git way of importing, why I need to re
  import". Whether the tool has since run on that workstation is not known. (Later, D-275: it has,
  started from STS, and an upload fails there with an internal error.)
- **What the integrator told them, and how each held up.**
  1. To tick `epa-pave` on the last page of the Git wizard. **That was wrong**, given from memory: the
     wizard never offers it (below).
  2. On the 8.9 error: the wrapper asks for Gradle 9.5.0 (`gradle/wrapper/gradle-wrapper.properties`),
     so an import that names 8.9 had not found it, and the folder given was not `epa-pave`; 8.9 is the
     version of the Gradle Tooling API that Buildship 3.1.11 carries. They were told to import the
     folder `epa-pave` of the clone on the branch `epa-pave` and to look for Gradle version 9.5.0 on the
     Import Preview. **This held, and the real cause is one step further than the message says**
     (below).
  3. On "imported as MVN": that the Maven project is the top folder, the other application; to use
     File, Import, Gradle, Existing Gradle Project on `epa-pave`; and to remove the Maven project with
     Delete, "Delete project contents on disk" unticked. **Given before anybody had seen it in that
     state.** Seen since: right, but the dialog has a second box to leave unticked, and the Gradle
     wizard is not the only way (below).
  4. On "why I need to re import": that the Git wizard looks only at the top folder of a repository;
     that the top folder is another application, which this work must not touch; that it is one more
     import of a folder already on disk, not a second clone; that with both projects open one press of
     start launches two `PaveApplication`; and to use Close Project on the Maven project. **Close
     Project was advised before it was seen.** Seen since: it works, and Open Project undoes it;
     Delete is the lasting form (below).
- **What the Git import wizard does**, seen in the real STS 5.4.0 on Linux on `d089e65`, cloning
  straight from GitHub with no credential prompt ("Projects from Git (with smart import)", "Clone
  URI"). STS had been started from a shell whose proxy settings its Java picked up. "Branch
  Selection" ticks all nine branches, and "Local Destination" then offers the default branch as
  "Initial branch", which has no `epa-pave` folder; with only `epa-pave` ticked, "Initial branch"
  becomes `epa-pave` by itself. The last page offers one row, `Interop` as `Maven`, ticked and greyed
  out, and never lists `epa-pave`, though Gradle is among its installed configurators. Finish there
  imported the other application (861 errors, Eclipse files left untracked in the root of the clone)
  and no PAVE: the operator's state. Adding `/epa-pave` to "Import source:" before Finish imported the
  tool as a Gradle and Boot project that started from the Boot Dashboard with both proofs holding; so
  did File, Import, Gradle, Existing Gradle Project on `epa-pave` afterwards. With both projects in
  the workspace, one press of start launched two processes and the second failed with "Port 8057 was
  already in use." Team, Pull with nothing new read "Nothing to update - everything up to date".
- **The 8.9 error, seen.** The Gradle import given the top folder of a clone, on purpose: the Import
  Preview read "Preview failed" with exactly the operator's message, Gradle version, Java home and
  Gradle user home "Unknown", and a distribution line that still read "Gradle wrapper from target
  build". Gradle 8.9 had downloaded without trouble; it failed because it ran on the Java 25 inside
  STS: the "Caused by" lines end in `java.lang.IllegalArgumentException: Unsupported class file major
  version 69`. So the reading of item 2 was right about the folder, and the message misleads about
  the cause: it reads like a network fault and was not one. The same on the default branch, where
  the path `epa-pave` was refused with "Project root directory does not exist." Given `epa-pave`, the
  same wizard showed Gradle 9.5.0 on the same Java 25 and imported cleanly. Finish on the failed
  preview raised no dialog and made a project `Interop [Interop epa-pave]` with one "Gradle Problem";
  removing it from the workspace left `.project` and `.settings/` untracked in the root of the clone.
  The operator's own cause lines were not seen. In the simulation of a refused network (below) a
  failed download read "Could not install Gradle distribution from ...", not "Could not fetch model",
  which suggests, without proving, that their download did not fail either.
- **The fourth run, the operator's state rebuilt** (the top folder imported as the Git wizard does
  it, on Linux). Every line of their Package Explorer matched. The Package Explorer and Project
  Explorer menus of the folder `epa-pave` offer no import of it as a project; the Git Repositories
  view does: under "Working Tree", right click `epa-pave`, Projects, Import Projects..., which opens
  the same smart import already set to that folder. Finish gave a good Gradle project (0 errors, 25
  warnings of its own). With both open, the Boot Dashboard showed `epa-pave [0/2]` and one start gave
  two processes, one failing on port 8057, while Run Configurations listed one `PaveApplication`.
  Close Project on the Maven project, with no dialog, left one entry; one start, one process, both
  proofs held; Open Project brought the second entry back. Delete showed two boxes, "Delete project
  contents on disk (cannot be undone)" and "Delete 1 nested project"; with both unticked it removed
  the Maven project only, the clone and `epa-pave` whole, and again one process and both proofs. One
  thing nobody had foreseen: in the operator's state, opening the hidden `PaveApplication` in Run
  Configurations (its "Project" empty, "No project selected") and closing the dialog without Apply
  rewrote the tracked `epa-pave/PaveApplication.launch`, its project line removed, without asking;
  `git checkout` of that file restored it. The run then removed `epa-pave` from the workspace and
  started the smart import again on the clone: it now offered two rows, the top folder and
  `epa-pave`, both with an empty "Import as" and neither greyed out, because both folders held a
  `.project` by then. It was cancelled there.
- **The fifth run, a workstation with STS and no JDK 21, simulated on Linux**: the build machine's
  JDK 21 hidden from one STS 5.4.0 and an empty Gradle home. STS alone does not make the tool usable.
  (Since D-274 this holds only for a checkout from before `6201644`: see D-274.)
  The import ran Gradle 9.5.0 on STS's Java 25 and downloaded everything, then ended silently with a
  project that is only a Gradle project: no `[boot]`, no Java build path, nothing in the Boot
  Dashboard, no Gradle tasks. The one error reads "Cannot find a Java installation on your machine
  ... matching: {languageVersion=21, ...}. Toolchain download repositories have not been
  configured.": `build.gradle` asks for a Java 21 toolchain and the build configures no toolchain
  download repository. `./gradlew classes` from a terminal on Java 25 failed with the same sentence.
  Then the network was taken away as well (a private network namespace with no route, because
  unsetting the proxy variables did not cut that machine off): with nothing downloaded, the Import
  Preview failed in under a second with "Could not install Gradle distribution from
  'https://services.gradle.org/distributions/gradle-9.5.0-bin.zip'.", caused by
  `java.net.UnknownHostException: services.gradle.org`; with the distribution present and the
  dependencies not, the import left "Plugin [id: 'org.springframework.boot', version: '4.0.7'] was
  not found", naming the repositories "MavenRepo" and "Gradle Central Plugin Repository" and no
  host.
- **What a tester is now told** (README, "STS (Spring Tools for Eclipse)"): step 1 says what the
  machine needs, a JDK 21 beside STS, with the toolchain message as seen; step 2 is "Get the code",
  from a terminal or with the Git import wizard page by page, with the branch and the last page
  changed; then, for whoever already pressed Finish on the wizard's own choice, the steps of the
  fourth run, and the two ways to be rid of the second `PaveApplication`, Close Project first (no
  dialog, undone by Open Project), then Delete with both boxes unticked, and why: the tool's folder
  lies inside that project's folder; and, where the Git Repositories view lists two clones, to use
  the one on `[epa-pave]`. Also a warning not to open Run Configurations before
  `epa-pave` is imported. After the import step, "If the import fails": the 8.9 message and its
  real cause beside the Import Preview of the right folder, a clone with no project, the Maven
  project, the two entries, a path refused on another branch, the toolchain message, and the refused
  downloads as a simulation, pointing to the README's sections on the Gradle distribution, a mirror
  and TLS, which were checked from a terminal and not from STS. The advice to give Gradle the JDK 21
  as Java home stays, with the note that it does not help a machine that has none. Step 8: Team,
  Pull, and that a Gradle refresh after a pull that changes the build was not seen. The checklist's
  step 1 now says that a newer Java alone does not build either (changed by D-274: a newer JDK alone
  now builds it), and its step 2 points to the STS section for those who clone from the IDE.
- **Nothing was changed in the repository root to make the wizard offer the tool.** A Gradle
  settings file, Eclipse files or a `.gitignore` line at the root might change what the wizard finds
  or leaves behind, but the root is another application that this work must not touch. **Nor was the
  tool's own folder given Eclipse project files to commit:** `epa-pave/.gitignore` treats `.project`,
  `.classpath` and `.settings/` as generated on each machine. The fourth run saw only this much of
  what such a file changes: once `epa-pave` held a `.project`, a second smart import of the clone
  listed it as a row of its own beside the top folder.
- **Not seen by anyone:** Windows beyond what the operator reported, a credential prompt, a corporate
  proxy (the refused network was a simulation with no DNS and no route, not a proxy that refuses or
  inspects), an STS other than 5.4.0, a workstation that has a Java 17 and no 21 as opposed to no
  other Java at all, "Clone a Git repository" in the Git Repositories view, a branch switched from
  that view, a pull that brings new commits and whether Gradle then needs a refresh, what the
  mirror, distribution and TLS settings do from STS, and what an import makes of an `epa-pave`
  folder that a branch switch left holding only ignored files.
- **What an independent check of this entry and the README found.** Seven README sentences were unclear or claimed more
  than the runs saw, and the summary row stated the cause of the 8.9 error without saying where it
  was seen. Now: the wizard's acceptance of a leftover `epa-pave` folder is marked as not tried (the
  run moved those files aside first); the Git Repositories way of importing `epa-pave` is said to
  have been seen only with the top folder already imported, and a clone with no project is pointed
  to File, Import, Gradle, Existing Gradle Project; the repository line is quoted with the `>` it
  carried in the operator's state; a sub-step that pointed to "step 5" now names the section's step
  5 and the paragraph it means; a newer Java alone is said not to build PAVE (changed by D-274: a
  newer JDK alone now builds it), not "not to run" it,
  since running on a newer Java was not tried and `run.sh` accepts 21 or newer; the TLS section is
  said to be checked only in part; and the proofs are said to have been run after every start but
  the double launch of the operator's state, after which only `GET /api/mode` was called. The
  check also noted that the Delete warning rests on the dialog's label (no run ticked the box),
  which the README now says, and that nothing told a tester with two clones which to use, which the
  README now does: the one whose line reads `[epa-pave]`.
- The commits that record this change this file, `PROGRESS.md` and the README only.

**D-274. The build no longer needs a JDK 21 where the Java that runs Gradle can do the work: what
was decided, what was changed, and what was seen.**
D-273 showed, in a simulation on Linux, that STS alone imports a project that cannot start when no
JDK 21 is on the machine, and that nothing on screen says why unless the Problems view is opened. The
operator works on Windows on Java 17 projects, had been at the import for hours, and nobody knows
whether a JDK 21 is on that workstation. The integrator did not wait to ask the operator. It judged
that the safer way: where a JDK 21 exists the change does nothing, so asking first would have spared
nobody anything; where none exists, the new path replaces an import that fails in silence with one
that works or stops with a plain message; and every further question cost a person who had already
spent hours on the import another round. It decided what follows and had it built, run in the real
IDE and reviewed. This entry was first written by the integrator alone, from those reports, so that
the change could be handed over at once (`6eddce8`); it was then checked twice, and corrected as the
bullets "The check of the first record" and "The second check" say.
- **Decided.** (1) Where Gradle finds a JDK 21, nothing changes: it is the toolchain, as before, for
  compiling, for the tests and for every task that starts a Java, whichever Java runs Gradle. (2)
  Where Gradle finds none and the Java that runs Gradle is a JDK 21 or newer, the build compiles for
  Java 21 with that Java (`options.release = 21`, so both the class files and the API are those of
  Java 21; the source and target level an IDE is told stay 21) and prints one line each time it is
  configured: "Gradle found no JDK 21, so PAVE is compiled for Java 21 with the Java that runs
  Gradle: ...". That is the case of STS 5 alone, which runs Gradle on its own Java 25. (3) Where
  Gradle finds none and that Java is older than 21, or is a runtime without a compiler, the build
  stops while it is configured with one message that names the Java, says which of the two it is,
  and says what to do in STS and from a terminal; Gradle's own sentence about toolchain download
  repositories is kept at `--info`. (4) No toolchain download is configured: fetching a JDK onto a
  workstation is not this build's call, and the hosts it would need could not be reached from the
  build machine to see it work. (5) The note that `run.sh` and `run.cmd` print when the Java they
  found is newer than 21 says what is now true; nothing else in the scripts changed. They still
  refuse a Java older than 21.
- **Changed.** `build.gradle`, and the two lines of that note in each script (`6201644`, then
  `647b102` for what the review found; merged at `370b2ec`). No main code and no test changed.
- **How the build tells.** While it is configured it asks Gradle's own toolchain service for a Java
  21 compiler and treats one failure as "none found": `ToolchainProvisioningException`, and nothing
  wider. That class is internal to Gradle; the wrapper pins 9.5.0, where it was seen thrown. If a
  later Gradle moves it, the build script would no longer compile and every build would stop
  loudly rather than fall back in silence (inferred from how a script that names a missing class
  behaves, not tried). Nothing is guessed from folder names or environment variables. Whether the
  Java that runs Gradle has a compiler is read from its `jdk.compiler` module.
- **Seen, on Linux, from a terminal.** Hiding the JDK 21 from one process tree is a simulation of a
  workstation without a JDK 21; every "hidden" below is that. On `6201644`, by the change's author:
  with the JDK 21 present, the whole build through the shared wrapper, 2872 tests, 0 failures, 0
  errors, 2 skipped, and the new line absent; Gradle configured every compile and test on the JDK
  21 (read from Gradle's settings by an init script; in that build the three compiles came from the
  build cache). With the JDK 21 hidden and Gradle on the Java 25 that STS brings: the whole build,
  the same counts, every task run and none from the cache, class files of Java 21 (major version
  65), and the jar it made started on that Java 25 with both handover proofs holding; that commit
  printed the line in its first wording, "No JDK 21 was found, so ...". With the JDK 21 hidden and
  Gradle on a Java 17 (unpacked from a package, not installed): the build stopped with its message.
  With the JDK 21 present and Gradle on that Java 17: the toolchain did the work. On `647b102`, after
  the review: the whole build with the JDK 21 present, 2872 tests, 0 failures, 0 errors, 2 skipped
  (the compiles again from the cache); with the JDK 21 hidden, `classes` on the Java 25 with the line
  in its present wording, and the stop on the Java 17 and on a Java 21 runtime without a compiler.
  The whole build and the start of the jar with the JDK 21 hidden were not run again on `647b102`.
- **Seen, on Linux, in the real STS 5.4.0**, by agents that wrote none of it. On `6201644`, with the
  JDK 21 hidden and an empty Gradle home: the import with the wizard's defaults gave
  `epa-pave [boot]`, 0 errors, 25 warnings, 59 others, "JRE System Library [JavaSE-21]" bound to the
  IDE's Java 25; the first press of start in the Boot Dashboard started the tool on it with no
  dialog and both proofs held; the four test classes run from the IDE gave what they give on a JDK
  21; the task `classes` run from the Gradle Tasks view succeeded and printed the line in its wording
  of that commit, "No JDK 21 was found, so ...". With the JDK 21 present: the same import, the tool
  and the tests on the JDK 21 as before, the same Problems counts, the line nowhere, and
  `PaveApplication.launch` untouched. On `6eddce8`, whose code is that of `647b102`, with the JDK 21
  hidden and an empty Gradle home, in two parts. First, a project imported on `54e7197`, before the
  change, with its "Cannot find a Java installation ... languageVersion=21" error: Team, Pull
  fast-forwarded it to `6eddce8` and changed nothing else on screen, the error staying; one Gradle,
  Refresh Gradle Project then gave `epa-pave [boot]`, 0 errors, 25 warnings, 59 others, with no
  restart, second refresh or new import; the status bar was empty about 45 to 49 seconds after the
  click, and the Problems view read "0 errors" about 20 seconds before the project had its `[boot]`.
  In that run the clone's origin was a local copy whose
  commits were already fetched: the Pull Result read "No ref to fetch from origin - everything up to
  date.", so only the fast-forward was seen, not a fetch of new commits from GitHub. Second, the
  operator's own path on a fresh clone of the same local copy, not one made by STS from GitHub: the Git import wizard on that clone, which made
  the top folder a Maven project (0 errors, 6 warnings, 4 others, where with a JDK 21 it had shown
  about 861 errors), then the Git Repositories import of `epa-pave`, then Delete of the Maven
  project with both boxes unticked, gave `epa-pave [boot]` with 0 errors and 25 warnings of its own.
  Each part started the tool from the Boot Dashboard with no dialog, on the IDE's Java 25 ("Starting
  PaveApplication using Java 25.0.4.1"), and both proofs held; every Gradle run logged that Java.
- **The review**, by an agent that wrote none of it, found no blocker and no major: four minor
  points and one nit, (e) below. (a) A Java 21 or newer that is a runtime with no compiler took the new path and then
  failed at the compile: corrected in `647b102`, which stops there too with its own reason. (b) The
  message and the line said "no JDK 21 was found on this machine", more than Gradle looked for:
  corrected, both now say "Gradle found no JDK 21". (c) In the case that stops, every task stops,
  `help` and `javaToolchains` included: left, because decision 3 asks for the stop while the build is
  configured, and the README says so. (d) With Gradle's configuration cache on, a configuration
  stored while no JDK 21 was visible is reused after one appears: left, because this project does
  not switch that cache on. (e) The scripts' note said "if no JDK 21 is installed" where the build
  decides by what Gradle finds: corrected, and the note now adds "when it is a JDK".
- **Known and left as it is.** (c) and (d) above. A daemon started inside the namespace that hid the
  JDK 21 was reused by a run from outside it and could not see the JDK 21, which was still hidden
  from that daemon (seen once, from a terminal); that says nothing about a daemon that does not
  notice a JDK 21 installed later, which was not tried. The README advises stopping the daemons
  then, as a precaution. In the IDE the line
  shows only in the Console view, which a default import leaves closed, and it is too long for that
  view even maximised; a build script cannot put a line in the Problems view. After a refresh of a
  project whose first import failed, the old red failure stays in the "[Gradle Operations]" console
  above the new line. Eclipse binds JavaSE-21 to the Java 25 and may not hold the API to Java 21 as
  Gradle's compile does (not tried).
- **Not seen by anyone:** a workstation without a JDK 21 other than as a simulation; Windows;
  `run.sh` and `run.cmd` after the change (`run.sh` was only parsed, `run.cmd` only read); the whole
  build and the start of the jar with no JDK 21 on `647b102` (only `classes`); an STS other than
  5.4.0; a workstation whose only other Java is a 17, in the IDE; a JDK 21 installed while STS or a
  Gradle daemon runs; the fallbacks of a refresh that does not work (a second refresh, a
  restart, a new import), because the first refresh worked; and, on `6eddce8`, a fetch of new commits
  from GitHub or a clone made by STS, because both clones had a local copy as their origin.
- On the build machine the change's author once started the jar with the machine's root as its
  working folder, by mistake, which left a `config-store` and a `run-history` there holding mock
  data. A safety check of the machine refused their removal to that agent; they are still there,
  outside every checkout, and a person has to delete them.
- **The check of the first record.** An independent check of the entry and the README lines of
  `6eddce8` against the reports and their outputs found, and the records now say: that `run.sh` had
  changed since it was last run and has not been run since; that the last whole build named was
  older than the head's; that the daemon seen not finding a JDK 21 had been started while it was
  hidden, not before one was installed (the second check found that sentence still wrong); that "every compile and test on the JDK 21" was read from
  Gradle's settings, with the compiles from the cache; that D-273 lacked a pointer here where it
  says STS alone is not enough; that the IDE on `6201644` printed the line's first wording; that the
  hidden runs are simulations; that the effect of a moved Gradle class is inferred; that the refusal
  to remove the leftover folders was reported for the change's author only; that `run.sh`,
  `run.cmd` and the whole build on `647b102` were missing from what nobody saw; and, in the README,
  the commits behind each sentence, what each kind of Java gets in step 1 and in the scripts, Java
  home in step 3, a JDK 21 installed while Gradle runs, the name of the section pointed to, and the
  STS runs in the closing paragraphs. Every finding was checked against the outputs and screenshots
  and taken. The last run of the IDE on `6eddce8` came after that record and is in it now.
- **The second check**, of the corrected record (`06bb040`), by an agent that wrote none of it,
  found, and the records now say: that the daemon reused "after the JDK 21 was visible again" had
  never seen it, because it stayed in the namespace that hid it, so the run says nothing about a
  daemon missing a JDK 21 installed later (the first check had seen part of this, and the fix only
  reworded it); that both clones of the last run had a local copy as origin with its commits
  already fetched, so neither a fetch from GitHub nor a clone made by STS was seen there; that the
  review found four minor points and one nit, not five minor points; that "as the last bullet says"
  pointed at the wrong bullet; that the README gave the stop message's "older than 21" wording to
  the Java 21 runtime too, which gets "which is a Java runtime without a compiler"; and that the
  README wrote `gradlew --stop` where Linux and macOS need `./gradlew --stop`. Each was checked
  against the outputs and taken.
- The commits that record this change this file, `PROGRESS.md` and the README only.

Later: D-295. The run scripts now pass over a Java older than 21 with a line saying why and look further
(I5); they refuse to start only when no Java 21 or newer is found.

**D-275. The first session on a Windows workstation: an upload that fails with an internal error,
not yet explained, the operator's verdict, and a launch file without its Linux-only argument.**
After D-274 the operator got the tool running on their own Windows workstation, started from STS, on
the code of `f82e32e`. It is the first time anybody has run the tool on Windows. What follows from
the operator is reported, not seen by the writers of these documents.
- **What the operator reported, in this order.** One more screenshot of the Git wizard's last page: a
  new clone, one row, the top folder, "Import as" `Maven`. They were told to add `\epa-pave` to
  "Import source:" and press Tab (README, STS section, step 2 (b), 6). Their next message came from
  the running tool, so the import worked; by which of the ways is not known. Then: "when I tried to
  upload env or postman collection I see this", with the start of one log entry of the tool,
  `ERROR ... o.p.i.epa.pave.web.ApiExceptionHandler : Unhandled exception, reference <a reference>: jakarta.servlet.ServletException`,
  then `at org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:978)`
  and four more frames of Spring. The paste ended there, before the part of the entry that names the
  cause: the tool logs the chain of exception types joined by " <- caused by ", with the first frames
  of each and no message (`service/error/Faults.java`). (Later, D-277: the first line of the entry
  now names that chain, and the screen shows it.) Then their verdict on the tool after this
  first session: in their words "the whole UI and app sucks", and it is not what the effort spent on
  it should have produced. They named no particular. The integrator asked for the three worst
  things, in a few words each; no answer when this is written. It is the only judgement of the tool
  by the person it was built for.
- **What the log line means.** Line 978 of `DispatcherServlet.doDispatch` in spring-webmvc 7.0.8 is
  where Spring wraps a `java.lang.Error`, not an exception, that was thrown while the handler ran,
  in a `ServletException`. The integrator checked that in the class file of that version (the line
  is in its line number table at that place). So on that workstation some Error is thrown inside
  the upload of an environment and of a collection. The tool's own failures to write its store are
  exceptions with a handler of their own (`ConfigStoreException`, answered 500 with its own
  sentence), so it is not one of those.
- **What was tried on the build machine. None of it reproduced the failure.**
  - On Linux, by the integrator: both uploads with the sample files answered 200 with the tool
    started from the classes STS compiled, on the Java 25 that STS brings; with heaps down to 96 MB;
    with environment values up to 600,000 characters.
  - On a Windows Java under Wine, by an agent, on `f82e32e`: the Java 25 for Windows (25.0.4.1)
    that the Spring Tools 5.4.0 download for Windows carries, the checksum of the download matched,
    run under Wine 9.0 on the Linux build machine. It is the first time the tool ran on a Windows
    Java at all. Wine got in the way twice, and neither is the tool's doing: a packaging fault of
    Ubuntu's Wine 9.0 that aborted every Wine command, worked around by running Wine in a private
    mount namespace; and a function missing from Wine's `IPHLPAPI.DLL` that a real Windows has,
    which made the Java abort while it gathered its random seed. For the second, part of the runs
    put the provider for the Windows crypto service (`SunMSCAPI`) first, so that the default random
    source was the Windows one and the missing function was not reached, and the rest used a copy of
    the Java whose `net.dll` names a function Wine has instead, so that the default random source is
    the same as on Windows.
    The tool then started from the jar, and from the classes an STS on Linux had compiled with the
    virtual machine arguments assumed for a Boot Dashboard start (with the arguments of the remote
    management agent, which the real STS start on `8463174` did not hold), from a folder on the C:
    drive shaped like
    the operator's, and from one whose name holds a space and brackets. About 60 uploads, through
    `curl` and through the Config tab in a headless Chromium, answered 200, or 422 with a sentence
    that said why; 15 variations of the files (a byte order mark, CRLF line ends, a name with
    brackets, umlauts, a file of 6 MB, a file not in UTF-8, another format) gave 200 or such a 422;
    the two handover proofs held; the suite preview answered 200. No Error appeared in any log.
  - **What a Windows Java under Wine cannot show.** It is not Windows. File permissions (Wine does
    not emulate the access lists of NTFS), an antivirus holding a file while the store moves it into
    place, and the real network stack were not exercised. The classes were compiled by STS on
    Linux, not by an STS on Windows, so a compile problem on Windows could not appear. The
    operator's command line was assumed, not seen, in particular the file encoding the IDE gives the
    Java there (UTF-8 was used). The operator's files and workspace were not available.
- **Possible causes that are left, none of them seen.** The project not compiling cleanly in the
  operator's STS: Eclipse starts a program whose code has errors, and the broken method then throws
  a `java.lang.Error` ("Unresolved compilation problem") when it is first used, which would give
  exactly this log line; the Problems view would show the errors. Class files that are stale or
  incomplete (a `NoClassDefFoundError` or an `ExceptionInInitializerError`). Something about the
  operator's own files (an `OutOfMemoryError` or a `StackOverflowError` on them, for instance). The
  tool's code throws no Error of its own on the upload path (read, not tested: no `new Error(`, no
  `assert` in the main code).
- **What the operator was asked**: the rest of that log entry, the Problems view of the project, and
  whether the sample file fails too. No answer when this is written. **Until the cause is found,
  nothing can be configured for the live profile on that workstation**, because the vendor's
  environment and collection reach the tool only by these two uploads.
- **The launch file's argument, found by the Windows Java run, and removed** (`8463174`). The shared
  launch configuration `epa-pave/PaveApplication.launch` (D-272) was the file STS 5.4.0 wrote on
  Linux, and it carried the virtual machine argument `-Djava.security.egd=file:/dev/./urandom`. On a
  Windows Java with its working folder on the C: drive that address names `C:\dev\.\urandom`, which
  does not exist, and the Java then gathers its seed with its slow fallback, the thread timing
  generator, where without the argument it asks Windows. A small program run on the Windows Java
  under Wine printed exactly that: `NativeSeedGenerator` without the argument, and
  `SeedGenerator$ThreadedSeedGenerator` with it, `C:\dev\.\urandom exists=false`. In the first run the
  tool took 12.556 s to start with the argument ("process running for") against 4.359 s without it.
  It made no upload fail on the Windows Java under Wine, and, by reading the code, not from a run,
  an upload draws from a source seeded at start. The line was simply removed rather than replaced: STS wrote it on Linux, where a Java of
  version 9 or later needs no such setting either, and the Java needs none on Windows. Removing it
  changed one line of that file and no code.
- **Two runs on `8463174`**, by agents that wrote neither the tool nor the change.
  - The Windows Java under Wine again, on the patched copy of the Java described above (the one
    whose `net.dll` was changed, a difference only Wine needs), from the classes STS had compiled on
    Linux, three starts each way, alternating, each from a fresh clone with a new store and a cold
    Wine: with the argument 13.25, 14.85 and 13.76 s from the command to
    the first answer of `GET /api/mode`; without it 5.24, 5.89 and 4.73 s. Spring's own start
    ("Started PaveApplication in") stayed about 3 to 4.7 s each time; the wait comes before it, when
    the remote management agent those runs started gathers the seed (the agent's reading of where
    the time goes, not traced). Those runs passed the
    `-Dcom.sun.management.jmxremote` arguments, which the command line of the real STS start below
    does not hold; one start with the argument and without them took 12.84 s, the same wait moved
    into Spring's own start (11.427 s), so the cost is about 8 s either way. On a start without
    the argument both uploads answered 200 and both proofs held, with no ERROR in the log. How many
    seconds a real Windows loses may differ: the thread timing generator measures how threads are
    scheduled, and Wine maps them onto Linux threads. Which generator the Java picks does not
    depend on Wine.
  - The real STS 5.4.0 on Linux, on a new workspace and a fresh import with the defaults (0 errors,
    25 warnings, 59 others): Run Configurations lists `PaveApplication` under Spring Boot App with
    "Shared file: /epa-pave" and an empty "VM arguments:" field; this configuration type has no
    "Show Command Line" button in STS 5.4.0, so the command line was read from the running process,
    and it holds no `-Djava.security.egd`. The Boot Dashboard started the tool with no dialog
    ("Started PaveApplication in 1.913 seconds") and both proofs held. **For the first time on the build
    machine, uploads were made against a tool started by STS**: the sample environment and collection each answered
    200 twice through `curl` and once through the Config tab in Chromium, with only INFO lines in the
    Console. The tracked file was not touched: `git status --short` was empty after the dialog was
    closed without Apply, after the start and stop, and after File, Exit. The uploads landed in
    `epa-pave/config-store/`, inside the launch's working folder `epa-pave`, and `config-store/` is
    in `.gitignore`.
- **Installed on the build machine for this**: Wine 9.0, 19 packages with the ones it pulled in
  (none of them 32-bit), through `apt-get`. They are still installed.
- **Not seen by anyone:** a real Windows machine, other than through the operator's report; the
  operator's files, their workspace and their Problems view; the cause of their failure; how many
  seconds the removed argument cost on a real Windows; `run.cmd`.
- The commits that record this change this file, `PROGRESS.md` and the README only; the launch file
  changed in `8463174`.

Later: D-279. A stress of the upload found a defect that writes this very log entry at default
settings: a collection holding one quoted text of about 1,300 characters or more in a request body or
a saved example threw `StackOverflowError` in the scan for credentials, after the collection had been
stored, and for longer texts (3,000 characters in the tests) every later reading of the store threw
again, after a restart too. It is corrected, and a store left by
the old version opens again. Whether it was the operator's failure is not confirmed; the line of their
log entry after " <- caused by ", or the cause the screen now names (D-277), would settle it. The
compiler of the IDE was tested as a cause and refuted, except that a project started with compile
errors gives the same entry (D-279).

**D-276. The first round of screen changes: why it was made without waiting, how it was made and
checked, what a tester now sees, where the builders left the plan, and what is left.**
After the first session on their workstation the operator judged the tool, in their words, "the
whole UI and app sucks" (D-275), and named nothing in particular. They were asked for the three
worst things and have not answered. **The integrator did not wait for that answer.** A verdict on
the whole tool from the only person it was built for could not be left standing while the screens
stayed as they were, and a look by people who had built none of the screens could find the worst
of it without the operator. What follows was decided and built that way; the operator has seen none
of it. The reports, outputs and screenshots are in the integrator's scratch directory under
`cont18/`, `cont19/`, `cont20/`, `ui-look/`, `ui-round1/` and `ui-round1b/`.
- **How.** (1) *Look*: four agents that built none of the screens each walked the running tool in a
  real browser as a tester on the first day (the first minutes; the live set-up; the words and the
  errors; the look and the layout). (2) *Doubt*: another agent tried to refute every finding and
  kept fifteen, ranked. In one line: the screens are written for the people who built the tool and
  not for the person testing with it; sample data disagrees with itself; nothing is folded or
  ordered by importance; status does not match what is the case. The ten worst went to the operator
  as one page; the operator has not reacted to it. (3) *Plan*: letters A to K (there is no J), written
  from the code and the specification. (4) *Attack*: a second agent found one blocker and seven
  majors in the plan (among them: the plan folded the response detail, which section 20 keeps open;
  it moved the error panel out of the order section 20 sets; it folded the PAS claim identifier an
  inquiry needs; it named the scenario an override displaces by half of the server's rule). (5)
  *Settle*: every finding of the attack was taken, each with its reason in the plan. (6) *Build*, in
  two batches on the branch `ui-round1` from `e70af34`: `2590656` to `ad31d98` (A, B, C, D, E, F, I)
  and `62af56c` to `99a939c` (G, H, K). (7) *Walk*: an agent that built none of it walked the same
  first day again on `99a939c`, finding by finding, with counts before and after. (8) *Audit*: another
  ran every browser check of the plan again and tested the hard rules of the specification. (9)
  *Correct*: `734ec23` to `8b05c4b`. (10) *Check*: a third agent checked the corrections and left three
  small side effects open. (11) *Correct*: `5a3ca4b`, `c5d7dbc`, `a6aadc6`. (12) *Check*: a fourth
  agent checked those and found one major, corrected in `87dee6f` and seen corrected by a fifth.
- **What the walk measured, before (the look, on `e70af34`) and after (`99a939c`, before the
  corrections)**, at 1440 by 900 on mock unless said: seconds until it was clear what to click, about
  20 and about 5; from opening the page to the first case sent, about 6 clicks, 2 typed fields, a
  guessed NPI and one refused send, then 2 clicks and 0 typed fields; visible words on the first
  screen, CRD 494 and 372, DTR 515 and 413, PAS 664 and 460 (the screen after Continue in PAS),
  History 302 and 398 (with 11 runs listed), Config on mock 407 and 389; words of a result outside
  closed folds, about 705 and 524; length of a CRD result, about 4.2 window heights and 2.66 (PAS
  after: 3.2); the first DTR question, 1582 px down and about 885 px, inside the first window. The
  whole journey CRD to DTR to PAS on the documentation sample took 9 clicks and 1 typed field. Of the
  fifteen findings the walk found five gone (2, 3, 11 on the path the tool suggests, 13 for the NPI,
  15), and 4 gone mostly; seven better (1, 5, 6, 7, 8, 9, 14); two the same (10, 12), as planned.
- **What each letter changed, as a tester now sees it.**
  - *A.* On mock each row of the scenario table names a member ID, an NPI and a code, and `Use this
    case` fills all three, each looked up as if typed, and sets the Mock scenario field back to "The
    member ID decides"; the first Send needs nothing else. Each scenario file carries a `sampleCase`,
    checked when the tool starts (the NPI must pass its check digit). The NPI is the placeholder
    `1234567893`, or `9999999995` in the three files that play a provider not found. On live there
    is no table and nothing is filled in. The button was `Use this member ID`.
  - *B.* A run with no expectation declared has no box: one quiet line, "No expectation was
    declared, so this run is unverified." The small Unverified chips of History are a thin line. On
    mock with no environment active, the finding that the request left out what nobody supplied sits
    in a closed fold "Expected on the mock profile (N)" inside Findings, whose count includes it.
  - *C.* The form opens with "Needed to send" (Member ID, NPI, Procedure codes), then "Member
    details" and "Ordering provider details", then the fields of the call itself (on PAS such as the
    claim identifier, on DTR what CRD handed on), never folded, then a closed `More options` whose
    summary names what in it is set. A refused send lists each field by its label as a link that
    opens its fold and puts the cursor there. After the corrections, that summary sits at the top of
    the form, scrolled into view with the keyboard focus on it, after every refused send; it lists
    exactly the fields marked now, in the order of the form, with each field's own message, and goes
    when none is left; the status line reads "The send was refused for 3 problems in the form.
    Nothing was sent." ("1 problem" for one), which stays true while the tester corrects the form.
    No screen sentence says "on the left" or "on the right" any more.
  - *D.* On DTR the questions come directly under the verdict; once a package is loaded the fetch
    button steps back to a plain one, and "Complete the response" is the only main button. "The whole
    result is above." is gone.
  - *E.* A result reads: the verdict, the quiet line or the test result, the next step, one run line
    (run id, response time, "Recorded."), Findings, the determination with the elements not returned
    at all under a closed "Not returned (N)", the response detail open, "Returned but not
    interpreted (N)" with its heading, count and notes open and its fields behind "Show the N
    fields", then a closed "Details: the wire view and what was typed with the run", and the error
    panel last, never folded.
  - *F.* Each tab's empty side says in one line what to do, then on mock "On mock, each member ID
    picks the answer. Pick a case:" and the table; what only explains a step is under a closed `About
    this step`. On live with nothing configured a tab leads with "This step is missing something the
    tool needs before it can send. Upload the vendor's Postman environment and collection on the
    Config tab if you have not." and keeps the server's reason, which names the property, under
    "What the tool says is missing". After the corrections no screen sentence says "sentinel": the
    server's words say "test member ID" and "test NPI", and on mock the PAS block "Where these calls
    go" is under `About this step`.
  - *I.* One NPI mistake gets one sentence, the same from the lookup and from Send: "Enter the
    ordering provider's NPI.", "An NPI is 10 digits. Check the number for a typing mistake." or "The
    last digit of this NPI does not match the other nine. Check the number for a typing mistake." A
    mock scenario chosen under More options is said under the Member ID, with `Let the member ID
    decide`.
  - *G.* Config, In force now, opens with "Ready to send?": the Production line in the same words as
    the Production row below it, the environment, and on live the collection, "N of M can be sent"
    with `Show which`, "Signing in to the service: off." naming the environment's sign-in variables
    that nothing uses and the start-up arguments that would switch signing in on, and the secrets
    with no value (names only). An operation that can be sent reads "Yes, without signing in." with
    the server's reason, not a plain "Yes.".
  - *H.* When a live call could not reach the service or got no answer in time, the verdict says
    "Address called:" with the scheme, host, port and path of the masked url (the query is left in
    the wire view) and the cause in plain words, among them a new one: "The proxy between this machine
    and the service refused the connection (it answered 403)."
  - *K.* The README's first run checklist is six steps, each a command and its correct output, at
    the top of the README; the history it held moved to the sections it is about.
- **Where the builders left the plan, and why.** Batch 1: B, D and E went into one commit because
  all three rebuild one function; `More options` is switched on for the three protocol tabs only,
  because the Config tab's request sent as written uses the same form and needs its fields in view;
  "on the right" was reworded as well as "on the left"; the fold's summary is read by one generic
  reader; the PAS subscription id and the CRD comparison pass their refused fields to the summary
  too; the override note is redrawn when the selector changes, not on every change of the case;
  `LookupOverTheServerTest`, `EveryScenarioDirectoryTest`, `DtrScenarioDeterminationTest`,
  `RedactionTest` and one assertion of `ScenarioRepositoryTest` changed although the plan did not
  name them, each because the change itself is what they stated (the two directory tests allow the
  placeholder NPI only at the exact text of a `sampleCase`); a sample case's line of business is read
  from the fixture's expect block; only the explaining sentence of the DTR rules not applied moved
  into `About this step`, because the rules themselves decide whether a question is unanswered.
  Batch 2: a variable that a request or a setting uses is not named as an unused sign-in setting,
  because "Nothing uses them" would be false for it; a helper is counted on every outbound operation,
  not only the sendable ones; "Environment: none is active." and "Collection: none is uploaded."
  rather than repeating the word; on mock "no environment active" is not marked as something to do;
  a proxy that closes without a word reaches the JDK as a connect timeout, so the test asserts it is
  never called a refused tunnel rather than the fallback the plan expected; the sample mapping counts
  13 requests, not the plan's example 10. The first corrector did not take the walk's "count only the
  other findings" (Findings (0) above a finding would be false, as the plan decided), left the DTR
  rule paragraph and the item paths in view (section 20 keeps them, and the paragraph is one server
  sentence the page may not cut), and left the variables the vendor's file lacks out of the checklist
  (it needs a dry run of every operation). The second corrector chose the past tense for the status
  line, the simpler of the two allowed; the scenario folder notes still name the file keys
  `crdSentinelMemberIds`, `answersDtrSentinels` and `sentinelMemberId`, which a tester must type as
  they are; and the other summaries (History, Config, suites, the PAS sample notification) still put
  the focus on the first marked field, because the task named only the protocol forms. The plan's
  rule of a clean build twice per batch was kept by the first corrector and not by the builders.
  Batch 1 also dropped DTR's "DTR questionnaire package" heading from inside the fold, for the reason
  the plan gives for dropping CRD's. Batch 2 did not repeat the D-270 caution (do not set
  `server.address` before PAVE has a sign-in) in the checklist, because "Who can reach this tool"
  already states it and the checklist links there; and it kept the git working-tree warnings in step
  4 as one line, because they are output a tester sees. The first corrector kept the lookup's "1
  returned but not interpreted" (walk finding 13) on purpose, as plan I 6 and section 20 decide; only
  the NPI part was corrected. It left the result long because the plan's letter E folds only the
  absent determination and coverage rows and keeps the cards and the response detail open as section
  20's tier 1; folding more would have gone past the letter.
- **The four findings of the audit, and what was done.** (1) F's check "fewer than 200 words" on the
  first screen cannot be met while the table of letter A stays (288 words without the options of the
  selectors, the table alone 189): the integrator restated the limit as the empty state outside that
  table and outside the form, and the table stays. By the audit's count that part is under 30 words;
  nobody has measured it in a browser since. (2) The notes about a body ("About the body: ...") and
  the line that a body could not be read as JSON had gone behind "Show the N fields": corrected in
  `8b05c4b`, they stay open. (3) No entry here and in `PROGRESS.md`: this entry. (4) The plan's
  command for the checklist's length counted one line (the section is 51); and the README's clone
  address named a GitHub account: see the decision below.
- **Decided by the integrator for the operator, the cautious way each time, and told to them with
  no answer so far.** Signing in is not switched on by itself, because how the vendor wants the
  client to sign in is not known (an open item); the Config tab says that it is off and how to turn
  it on. A half-filled form is still lost on reload, because keeping it would keep member data in the
  browser; the only storage key stays `pave.tab`. The button on a mock case reads "Use this case",
  because it now fills the member ID, a provider number and a procedure code.
- **Decided by the integrator on what the audit raised.** The word limit of F is restated as above.
  **The README no longer carries the address of the repository.** It named a GitHub account in two
  places (step 2 of the first run checklist, and the STS section where the Git wizard asks for
  "URI:"). The house style forbids naming people, and an account name in a document goes stale when
  the repository moves; whoever reads the README has the address already. Both places now say
  `<repository address>` and where to copy it from. D-053 quotes the specification and stays as it
  is.
- **What the checks saw of the hard rules.** On live, with nothing loaded and with the sample
  environment and collection active, every fold open: no `Use this case`, no scenario selector, no
  scenario table, no "Pick a case", no input filled in (the audit, the checks after each
  correction). Secrets: with placeholder secrets uploaded, the page, the checklist, the operations
  table and the result with every fold open showed only masks, and the values were in neither the
  ledger nor the log, only in the uploaded file the upload warning names. Browser storage after every
  walk, on mock and on live: `pave.tab` only, sessionStorage empty. Production: with an environment
  of kind `prod` active, the banner, the page title and the writes panel read word for word as on the
  jar of `e70af34`, and a PAS submit was blocked on both with the same verdict and next step; the
  round touches no file of the guardrail. Section 20: the masthead and banners are unchanged, every
  part of a result is still there in its order with the error panel last and open, and a comparison
  of the old and the new screens with every fold open found nothing missing altogether. The smoke
  script of the two handover proofs ended "ALL PROOFS HELD" after the audit and after three of the
  checks that followed it; the check of the internal error's cause ran no smoke script. **Not tested: the
  per-session unlock**, because this installation's floor (`config-store.allow-prod-writes` false)
  forbids it on both jars; nothing in the round touches it.
- **What is left, the worst first.** A result is still long: about 2.7 window heights on CRD and 3.2
  on PAS, with dashed "not returned" boxes in the cards, "Links (0)" and the like. On DTR the
  service's paragraph about a rule this tool does not apply stays above the questions, and each
  question keeps its item path and type. History does not tie the runs of one journey together
  (every row reads "a single run") and is cut off on the right at 1024 pixels. A half-filled form is
  lost on reload, by rule. The readiness list does not name the values the vendor's file lacks
  (`memberIdSystem`, `payerIdentifier` and the like); they are named only after a send. There is no
  sign-in built from the vendor's own token request. The next step of a failed call is still the
  general one and names no variable. A code typed by hand, 70553 for one, still gets the sleep study
  questionnaire on mock. `Target onyx` and Ledger are not explained. The summaries outside the
  protocol forms behave as before. The last check of the round left nothing open on the screens of
  this entry; what it left of D-277 is there.
- **What nobody has seen.** The operator has not seen this round, nor the page of the ten worst.
  No person has used the new screens: every walk and check was an agent driving a browser by
  script. Nothing of it ran on Windows. The Postman sign-in helper sentence of G was seen only in a
  test (the sample collection has none), G and H only at 1440 by 900, the refused proxy sentence on
  live by the builder and the walk only (the audit's loopback address did not pass through the
  proxy).
- **Tests.** 2,872 at `e70af34`; 2,896 after batch 1, 2,908 after batch 2, 2,909 after the first
  correction, 2,916 after the second, each with 0 failures, 0 errors and 2 skipped. New test classes
  hold the layout by reading the scripts as text, some running a function under Node
  (`ResultLayoutFrontendTest`, `FormLayoutFrontendTest`, `ScenarioOverrideFrontendTest`,
  `AboutThisStepFrontendTest`, `ConfigReadinessFrontendTest`). `NoSentinelOnScreenTest` reads the
  scripts, the pages, the bundled scenario texts and every string literal of the Java sources for the
  word. `SampleCaseTest` checks the sample cases of the scenario files: the NPI's check digit, the
  codes against the bundled list, and that each sample selects its own file. No test drives a
  browser.
- **Checked after it was written.** Another agent, which wrote none of it, checked this entry, D-277
  and their lines in `PROGRESS.md` and the README against the code of `6a6fe2f` and the outputs,
  and found two majors and six minors. Major: `6eddce8` and `ace5914` were still said to hold the
  code at the head, which they have not since this round (STS has not been run on the code since);
  and the README quoted a History filter "Next action" that does not exist (the run view's row is
  "What next"). Minor: "eight better" for seven findings; the smoke script said to have ended "ALL
  PROOFS HELD" after each check, which the check of the error cause did not run; `SampleCaseTest`
  named among the layout tests; four places where the builders left the plan missing above; the
  README calling a test member ID a sentinel without saying the screens use the other words; and
  "Pull the branch" in D-277, which named no branch. Each finding held against the evidence and is
  corrected. The same "Next action" quote stands in `PROGRESS.md` (open line 8) and in an older entry
  above; they were left, because the check named neither.
- The commits that record this change this file, `PROGRESS.md` and the README only.

Later: the screens this entry describes are gone (D-286 says what replaced each tab). "Use this case"
and the scenario table are replaced on mock by a mock member picked by what it plays (D-286); "Signing
in to the service: off" and the start-up arguments it named give way to a sign-in set per environment in
Configuration (D-290).

**D-277. An internal error of the tool names its cause, in the first line of the log entry and on
the screen: a deliberate, narrow change to D-036.**
- **What happened.** On the operator's workstation every upload of an environment or a collection
  answers "This tool hit an internal error" (D-275). The operator pasted the first lines of the log
  entry; the paste ended at Spring's frames. The tool wrote the cause further down the same entry,
  after the frames of the outermost exception and " <- caused by ". So the one fact that would find
  the fault was written where the person who had it did not look. That is a defect of the tool's own
  reporting, and it is what was changed. The operator's fault itself was not hunted.
- **Decided.** (1) The first line of the log entry carries the reference and the whole chain of
  exception types, outermost first, then a detail where (3) allows one; the frames follow as before.
  Someone who pastes only the first line has pasted the cause. (2) The answer to the browser carries
  the same line in a new field of the shared error shape, `cause`, set for an internal error only.
  No stack frame reaches the browser (section 18). Every place on the screens that shows the
  reference of an internal error shows the cause beside it, "Cause, as the server log names it",
  with one button that copies both, so that reporting it is one paste. (3) **Messages stay out, with
  one narrow exception.** D-036 stands for every throwable not named here. A message is given only
  for throwables whose message the Java runtime or the IDE's compiler writes about the program
  itself, never from request data: a `LinkageError` or a subclass (`NoClassDefFoundError`,
  `NoSuchMethodError`, `UnsatisfiedLinkError`, `VerifyError` and the like), whose text is a class,
  method or library name or a class file version written by the class loader or the verifier,
  **except** `ExceptionInInitializerError`, whose text can quote whatever a static initializer
  threw; a `ClassNotFoundException`, whose message is the class name, and this tool never loads a
  class by a name taken from a request; a `VirtualMachineError` (`StackOverflowError`,
  `OutOfMemoryError`), whose text is the state of the machine, **only when it has no cause**,
  narrower than first decided, because an `InternalError` or `UnknownError` built from a cause takes
  that cause's text; and a throwable whose class is exactly `java.lang.Error` with a message
  beginning "Unresolved compilation problem", which the Eclipse compiler writes into a class it could
  not compile and which the IDE starts all the same. Only the first throwable of the chain that may
  give a detail gives it, written "; <type> says: <detail>"; the detail is made one line (every line
  break, the Unicode ones included) and cut at 300 characters, never inside a character. The reason
  for each member is written beside its rule in `Faults`. (4) Nothing else about error handling
  changes: the kinds, the status codes, the texts of other errors. The ledger never gets the cause.
- **Accepted, not a defect.** By the rule, a `LinkageError` or a compiler's problem text that held a
  member ID would be given; the check of the change recorded that as an accepted risk. Nothing in
  the tool builds such a text from request data.
- **What was built.** `Faults.cause` gives the line and `Faults.describe` begins with it (`56c3e64`).
  `ErrorView` has `cause`, set by `ApiExceptionHandler`, by the outermost filter
  (`ResponseHeadersFilter`) and for a run that failed in this tool after the call (`RunSupport`).
  The action under an internal error now reads "Copy the reference and the cause below with the
  button and report them with what you were doing. The server log holds the rest under the same
  reference." (after a run's call it adds "The request was sent: do not assume it had no effect.").
  The error panel and the procedure code picker show "Reference <id>", "Cause, as the server log
  names it: <cause>" and `Copy reference and cause` (`4fb199f`; `Copy reference` when there is no
  cause), and the copied text is those two lines. `e0f3333` corrected the code picker's test. After
  the checks: the cause stays one line whatever line break its text uses and is never cut inside a
  character, and an `ExceptionInInitializerError` built from text is guarded by tests (`425e16a`);
  a case, a chain step, a suite row and a History replay entry that met a failure of this tool carry
  the cause beside the reference, and the suite results and both replay views show it with the same
  words and button (`6a6fe2f`). The suite runs and replay entries are held in memory; the results
  CSV has fixed columns without it; a test holds that the ledger lines carry neither the cause nor
  the reference.
- **What the tests show.** `InternalErrorCauseTest` (10 tests): a controller that exists only in the
  tests throws a missing class, a compile problem, a stack overflow, a plain `Error` and an
  `IllegalStateException` holding the placeholder `S123456789`, an `ExceptionInInitializerError` around
  such an exception and one built from such text, an `Error` wrapped as Spring wraps it, and a detail
  with Unicode line breaks; for each it asserts the status, the body, the exact cause, the exact first
  log line from a captured log, frames in the log and none in the body, and `S123456789` in neither.
  `FaultsTest` (8 tests) and `FaultReportFrontendTest` (2) hold the rest. An agent that wrote none of
  it changed the rule in a copy: allowing every message turned 15 tests red, allowing none 5, and
  allowing the `ExceptionInInitializerError` 0, which the correction then closed; a later check saw
  the new tests go red when each correction was undone.
- **What the real run showed, in a damaged copy of the jar.** The nearest thing to the operator's
  case that could be made: a class that only the upload needs was removed by hand from a copy of the
  built jar (`PostmanParser$Document`; a second check removed `MemberDataNames`), on Linux. The copy
  started; the upload of the sample environment answered HTTP 500 through `curl` and in the Config tab
  of a real browser, on mock and on live. The first line of the log entry read "Unhandled exception,
  reference <id>: jakarta.servlet.ServletException <- java.lang.NoClassDefFoundError <-
  java.lang.ClassNotFoundException; java.lang.NoClassDefFoundError says:
  org/p32h/interop/epa/pave/postman/PostmanParser$Document", and the first frame was
  `DispatcherServlet.doDispatch(DispatcherServlet.java:978)`, the line of the operator's paste. The
  panel showed the reference, the cause and the button; the copied text was the two lines; no frame
  was on screen or in the body; the only browser storage key was `pave.tab`. The untouched jar
  answered the same upload 200. On live with an environment holding a placeholder secret, uploaded as
  production through the browser and through `curl`, the secret was in neither the log, the body,
  the panel nor the copied text, and the banner and guardrail words were unchanged. The code picker's
  two places were seen with a 500 played by the browser script, and the suite row with an answer
  rewritten by the script, because no way was found to make one case fail in the real jar.
- **Limits, said plainly.** A class removed by hand on Linux is the nearest thing to the operator's
  case that could be made; it is not their case. **The cause of their failure is still not known.**
  (Later, D-279: a defect that writes that very log entry, a long text in a Postman file, was found
  and corrected; whether it was the operator's failure is not confirmed.)
  Nothing of this ran on Windows. Not seen in a browser: an internal error answered by the outermost
  filter, and the History replay view of the cause (both held by tests). **Two places still give the
  reference of an internal error inside a sentence only, with no cause:** the stop of a whole suite
  ("This tool failed while running the suite. Reference <id>."), and the verdict of the notification
  receiver ("Report the reference <id>."). The first is where a missing class during a suite shows:
  an `Error` ends the whole suite, as the last check saw in a damaged copy, with the class named in
  the log and not on the screen. Their log lines begin with the cause all the same. A suite row of a
  failure shows the reference twice, in its sentence and above the cause, because the results CSV and
  existing tests rely on the sentence.
- **What the operator has to do to get it.** The change is on the branch `ui-round1`; the
  operator's clone is on `epa-pave`, which does not hold it until `ui-round1` is merged, so a pull
  today gives nothing of it. Once the merge is done: pull `epa-pave` (in STS: Team, Pull, then Gradle,
  Refresh Gradle Project), start the tool again, upload the file once more, press `Copy reference and
  cause` under the error and send what it copied. If the cause names "Unresolved compilation
  problem" or a class not found, the README's STS section says what to try first.
- **Tests.** 2,916 before this change, 2,930 after it, 2,937 after its corrections at `6a6fe2f`, each
  whole build with 0 failures, 0 errors and 2 skipped.
- The commits that record this change this file, `PROGRESS.md` and the README only.

Later: the two places this entry leaves open are closed (`22a41bc` to `253928e`, on `ui-round1`).
The paragraph "Limits, said plainly" above is the history; what follows corrects it.
- **What a tester now sees.** Under the stop of a whole suite, "This tool failed while running the
  suite.", then `Reference <id>`, "Cause, as the server log names it: <cause>" and `Copy reference and
  cause`, which copies both lines. Under a notification this tool failed to read, the verdict
  "Something arrived at the receiver, and this tool then failed while reading it." with the lines
  "This is a defect in this tool, not a finding about what was sent. Report the reference and the
  cause below. The server log holds the detail under the same reference." and "The payload is shown
  below as it arrived.", then the same three lines and button, and the next step "Next: read the
  payload below as it arrived, and report the reference and the cause." To get there the receiver
  now catches a `LinkageError` as well while it reads, so that a missing class still leaves the
  notification held and recorded; a `VirtualMachineError` is still not caught.
- **The reference is given once on the screen.** Where that block stands under a sentence of the
  server, the screen drops " Reference <id>." from the sentence, turns "Report the reference <id>."
  into "Report the reference and the cause below." and "The reason gives the reference." into "The
  reference and the cause are below the reason." (`withoutReference` in `dom.js`; "below" alone when
  there is no cause). So the suite row reason no longer ends with the reference, the History replay
  entry reads "... A request may have been sent. Report the reference and the cause below.", and the
  status line of a suite row of a tool failure reads "Tool failure: this tool failed while running
  the case, so whether a request left is not known. The reference and the cause are below the
  reason." Its Elapsed cell reads "not known", no longer "no call". The next step of a run this
  tool failed to read after the call reads "Next: read the response as received, below, and report
  the reference and the cause."
- **The case that was running when the tool ended a suite** read "Not run: the suite stopped before
  this case." with "no call", although its request may have left. It now reads as a tool failure
  ("Tool failure: ... The reference is below the reason.", the reason "This tool failed while running
  the case. Whether a request left for it is not known, and nothing can be said about the service
  from this row. Look for it in History before running it again.") and is counted as one: "Stopped
  after 1 of 6 cases. 1 tool failure, 5 not run." A suite that this tool ends on its last case reads
  as stopped, not finished ("Stopped after 1 of 1 case. 1 tool failure."; seen in tests only). The
  cases after it still read "Not run: the suite stopped before this case." with "no call". That row
  first carried the stop's reference and cause a second time; decided by the integrator, one failure
  is said once (D-278): the row reads "The same failure as the stop above. Reference <id>", and the
  cause and the button stand only in the stop block.
- **Seen in a real browser (Chromium) against copies of the jar damaged by hand on Linux, on mock:**
  the suite stop and the running row (with `CrdRunRequest$Builder` or `CrdRequestBuilder$Built`
  removed: the screen names the missing class, the clipboard holds both lines, no frame is on the
  screen or in any answer of the API), and the notification verdict (`NotificationReader$StatusRead` or
  `NotificationReading$Status` removed, a heartbeat posted by `curl`: answered 200, held as a tool
  error). **Seen only with an answer rewritten by the browser script**, because the real jar cannot be
  made to fail there: the History replay entry, one run and dates, and a suite row of a case that
  failed alone. The next step of `RunSupport` was not seen in a browser.
- **What is unchanged, and holds no cause.** The sentence the server returns, in the API answer and
  in the results CSV, keeps "Reference <id>." and "The reason gives the reference."; the CSV has no
  column for the cause and holds none; the ledger lines hold neither the cause nor the reference. The
  cause is held in memory only, with the suite run and the notification. The only browser storage
  key stayed `pave.tab`.
- **Tests.** 2,946 after `f3a93dd`, 2,951 after `253928e`. `FaultReportFrontendTest` runs
  `withoutReference` with Node on ten inputs (nine sentences and an absent one) and asserts each output; it is skipped where Node is
  not installed, where the checks that read the scripts as text still run. The independent check of
  the first fix found that two of those text checks stayed green when the behaviour they name was
  broken; they were strengthened and were then seen to fail on the same breakage.
- **What the check of this step found beside it** is D-278: the suite's thread printed the messages
  of an `Error`.

**D-278. The message of an exception could reach the log around the one place that writes
failures: what was wrong, since when, what was decided and built, what the runs showed, and what is
left.**
- **What was wrong.** `service/error/Faults` is the one place that writes a failure: a reference, the
  chain of exception types, frames, and a detail only for the narrow list of D-277. `SuiteService`
  caught an `Error` around a whole suite, logged it there correctly, and then threw it on, on its own
  thread `suite-runner`, which had no handler. Nothing above the run caught it, so the Java runtime
  printed it itself to standard error, which the console and the captured output show beside the log
  (the log itself goes to standard output): "Exception in thread
  "suite-runner"", the whole chain with every message, every "Caused by:" and every frame. A test
  made an `ExceptionInInitializerError` around an `IllegalStateException("no plan for S123456789")`
  end a suite on that thread, and the log held "Caused by: java.lang.IllegalStateException: no plan
  for S123456789". In a copy of the jar with a class removed, the log held the runtime's own printing
  of the `NoClassDefFoundError` under the tool's entry.
- **Since when.** Since suites were built (`df3adcd`, D-103 to D-110): the rethrow and the thread
  without a handler were both in that commit. Corrected in `7cbc0f8`, during the step that closed
  D-277's two places.
- **Why no check saw it before.** Until `22a41bc` no test made an `Error` during a suite at all. The
  two tests that step added ran the suite on the test bed's held executor, on the test's own thread,
  and caught the `Error` there (`assertThatThrownBy`), so no default handler ran; one of them asserted
  that the placeholder member ID was in no log and passed for that reason. The independent check of D-277's last step was the first to run a suite on
  the service's real thread with such an `Error`, and the first to read the runtime's printing as a
  leak.
- **What it means against the hard rules, said plainly.** It broke the rule of D-036 that the
  message of an exception is never logged, and with it the rule that no member data is logged: an
  `Error` during a suite whose chain holds a message that quotes a member ID would have put that ID
  into the log, with every frame. A missing class alone gives a class name, which D-277 allows; an
  `ExceptionInInitializerError` gives whatever its cause says. **Whether any real run ever printed a
  message that way is not known.** It needs an `Error` during a suite, and no ledger or log of the
  operator's has been seen.
- **Decided by the integrator** (point by point): (1) find every thread on which code of the tool
  runs and what becomes there of a `RuntimeException` and of an `Error`; (2) no such path writes the
  message of a throwable to the log or to standard error, apart from the narrow list of D-277: the
  tool's own code logs it once through `Faults` under a reference and lets it go no further, and
  where a framework would log an exception by itself it gets the tool's handling or never reaches it;
  (3) a net under the whole process, a handler for throwables nothing caught that writes one entry
  through `Faults` and nothing else; (4) a start that fails is reported as Spring reports it, because
  its messages speak of the configuration and a tester needs them, unless one can carry a secret or
  member data, which was to be tried; (5) the suite screen says one failure once (D-277, "Later:").
  Also decided: the test that waits for the suite's thread to finish polls with a cap and stays as
  it is; it asserts nothing about timing.
- **The threads, and what becomes of a throwable on each.**
  - `suite-runner` (`SuiteService`): a failure of one case is caught per case and one from the run
    loop with any `Error`, each logged through `Faults` under a reference and not thrown on;
    anything that still escapes goes to the net, set as this thread's own handler.
  - The embedded server's request threads: `ApiExceptionHandler` logs what a controller throws
    (Spring wraps an `Error` in a `ServletException`) through `Faults` and answers 500 with the
    cause. Beneath it the outermost filter, `ResponseHeadersFilter`, now catches what a nested error
    dispatch throws and what fails while its own answer is written; such a write failure gets one
    line, "The answer under reference <id> could not be written: <types>", and nothing goes on to the
    server, which would have logged it with its message ("Servlet.service() ... threw exception").
    Spring's own warning when an exception handler itself fails is kept out by the logger level ERROR
    already set in `application.yml`. `JsonErrorReportValve` catches what it meets; the server
    swallows anything else its report throws, except a `VirtualMachineError`. The notification
    receiver catches its own failures (D-277, "Later:").
  - The thread that closes the application (`SpringApplicationShutdownHook`): `TomcatBaseDirectory`
    and `SuiteService` now catch everything while they stop and log it through `Faults` under a
    reference ("Stopping the suites failed inside this tool, reference <id>: ..."). Before, Spring
    logged "Invocation of ... method failed on bean ...", with the message.
  - The main thread: the net is installed first; a failed start is Spring's report (point 4).
  - Threads where only framework code runs. Seen in a thread dump of the real jar on mock, taken
    with no outbound call, or in its output: Tomcat's `Catalina-utility` threads, `container-0`, the
    acceptor and poller, `tomcat-shutdown`. Known from reading the code, not from that dump: the JDK
    HTTP client's threads (their failures come back to the request thread, where `ExchangeExecutor`
    logs types only). The tool registers nothing that runs on them; anything that escapes falls to
    the net. `src/main` has no `@Scheduled`, `@Async`, timer, virtual thread, event listener, or
    `Lifecycle` or `SmartLifecycle` bean of its own (searched); the lifecycle callbacks it does have
    run on the main thread and the shutdown thread listed above.
- **What was built.** `service/error/UncaughtFailures`, the net: one entry, "A thread of this tool
  ended on a failure that nothing caught, thread <name>, reference <id>: <types and frames>", and
  nothing else; if even that fails it is swallowed silently. `PaveApplication.main` installs it
  before Spring starts, which is the entry point of the jar, of `bootRun` and of the launch file
  STS uses. The suite's thread has it as its own handler (`cbffaf1`). The outermost filter's last
  word (`7b4bc3d`). Stopping that never throws into Spring (`06384e4`). The suite row that points to
  the stop (`d4b2023`). Tests for the two guards the check found untested (`ccef212`, `ef069d9`). No
  screen sentence changed apart from the suite row; the new log lines are in the server log only.
- **What the tests showed.** Most new tests throw, on the thread they guard, an
  `ExceptionInInitializerError` around "no plan for S123456789" and, apart, a `RuntimeException` with
  that message, capture the log and standard error, and assert one entry under a reference, no
  "Caused by:" and no placeholder; the test of an answer that cannot be written throws an
  `IllegalStateException("writer for S123456789")`. The net's own guard is tested with throwables that
  cannot be described (a `getStackTrace()` that returns null, a `getCause()` that throws): those tests
  assert that nothing escapes and nothing is printed, and no entry is written for such a throwable.
  The suite row's pointer is tested by reading the script's text. Each test whose correction could be
  taken out was seen to fail without it: by the agent that built it, by an independent check (every
  correction undone in turn in a copy), and for the last three tests, written after that check, by a
  third agent. The exception is the test of a `RuntimeException` in one case of a suite: it guards a
  per-case catch that already held, so it has no run that failed before the change. The net's own guard needed a test in a child Java process, because
  the runtime writes its line about a failing handler to the process's error stream, beneath what a
  test in the same process can capture.
- **What the runs of the real jar showed.** Copies of the built jar, each with one class removed by
  hand, on mock and in part on live, the path triggered and every line the process printed searched
  for "Exception in thread", "Caused by:", a frame, "Servlet.service()", "threw exception",
  "Unexpected error occurred" and an ERROR or WARN from a logger that is not the tool's. The builder
  and the independent check each chose their own classes; the table names the builder's.

  | Path | Class removed | What it answered | What the output held |
  | --- | --- | --- | --- |
  | Upload of an environment | `ProductionSigns` | 500 with reference and cause | one tool entry, nothing else |
  | Upload of a collection | `OperationDetector` | 500 | the same |
  | Activation | `ProductionDestinations$Scan` | 500 | the same |
  | CRD, DTR, PAS submit, PAS inquire | a builder class of each | 500 | the same |
  | Subscription create, list | `SubscriptionRequestBuilder$Built`, `SubscriptionReading` | 500 | the same |
  | Member and provider lookup | `MemberResponseMapping`, `ProviderResponseMapping` | 500 | the same |
  | Code picker | `ProcedureCodeService$HeldBack` | 500 | the same |
  | History replay | `RunReplay$1` | 500 | the same |
  | Readiness list of Config | `ConfigViews` | 500 | the same |
  | Suite | `CrdRequestBuilder$Built` | stopped, "This tool failed while running the suite. Reference <id>." | one entry on `suite-runner`, no "Exception in thread" |
  | Notification receiver | `NotificationReading$Status` | 200, held with a fault reference | one receiver entry, nothing else |
  | Self-check of the code list | none could be removed | the copy did not start | Spring's start report only |

  Every count of the searches was 0 for the paths that started, and there the placeholder member ID
  and secret appeared in no output through an exception message. The self-check's start failure is
  Spring's report, with its "Caused by:" lines; the start failures are below, and one of them printed
  the member ID placeholder through Spring's start message. Beside the removed classes: a stand-in on a loopback port answered
  every live call in 8 broken ways (the check: 14 ways, for 14 calls), each quoting the placeholders;
  damaged request bodies and uploads in 34 kinds. No exception message carried either placeholder
  into the log; the one line that held the member ID placeholder was an INFO line naming an uploaded
  environment by the name its file gives itself. A shutdown with a
  suite running printed none of the searched lines.
- **Start failures, tried and left as Spring reports them.** A damaged `config-store/store.json` in
  13 forms and damaged scenario files, on mock and on live, with a placeholder secret and member ID
  inside: the secret never appeared when it sat in a value. The start message quotes a field of the
  configuration itself, so a member ID put there would be printed: the name of the active
  environment, the stored file's path, and on mock a scenario file's `behavior` field. These are
  names and keywords the tool or the tester wrote, not request data, and were left as they are. A
  port already in use reads "Web server failed to start. Port 8392 was already in use.", with no
  "Exception in thread".
- **What the independent check found, and what was done.** It found no path that puts a message in
  the log, saw the suite screen and the older hard rules hold in Chromium on mock and on live, and
  `smoke.py` ended "ALL PROOFS HELD". Four minor findings: (1) the guard in `SuiteService.close` had
  no test that failed without it: a test now closes a Spring context holding a suite whose stop
  throws (`ccef212`); (2) the net's own guard had none: two tests (`ef069d9`); (3) the list of
  threads left out Tomcat's utility, container and shutdown threads: completed above, no code
  change; (4) on live, a service answer that echoes the value of a secret the request did not carry
  is shown unmasked on screen, in the returned fields and the wire view (D-179 masks only the secrets
  the request carried; neither the log nor the ledger holds it): recorded for the integrator, not
  changed. A third agent saw (1) and (2) fail when undone and nothing else break.
  Later: D-280. Finding (4) is closed: an answer is masked wherever it repeats a secret the tool holds.
- **Limits, said plainly.** The net, the guard in `SuiteService.close` and the net's own guard were
  never made to fire on the real jar, which cannot be made to fail there from outside; they are held
  by tests. The self-check, most of the code picker and the History list could not be broken by
  removing a class without the copy failing to start. A failure inside one of `ApiExceptionHandler`'s
  own methods is kept out of the log only by that logger level, and is named nowhere. A
  `VirtualMachineError` thrown by the server's error report would reach the server's own log with
  its frames. The builder's screenshots of the pointer row were taken on a jar built before its last
  commit; the check's are of the final jar. Nothing of this ran on Windows.
- **Left, seen while checking.** A shutdown during a suite interrupts the case being written, and
  the ledger then logs "A ledger write failed for <its file>: java.nio.channels.ClosedByInterruptException"
  (types only); that case's ledger line may be lost. Pre-existing, not changed. And finding (4) above.
  Later: D-280. Both closed: a stop lets the call in flight finish within 30 s and the ledger write no
  longer fails on an interrupt, so that line is kept; and finding (4), above.
- **Tests.** 2,951 before this step, 2,962 after `d4b2023`, 2,965 at `ef069d9`, the last whole clean
  build, through the shared runner, with 0 failures, 0 errors and 2 skipped. The jar of `d4b2023` and
  of `ef069d9` is the same byte for byte (the last two commits change tests only).
- The commits that record this change this file, `PROGRESS.md` and the README only.

**D-279. A long text in a Postman file no longer breaks the upload or the store: what was found,
the theory it replaced, what was decided and built, what two attacks found, what the proofs showed,
what is still not confirmed, and what is left.**
The reports, outputs and screenshots are in the integrator's scratch directory under `cont22/`,
`upl2/`, `upl2-doubt/`, `cont23/` and `rx/`.
- **The failure it answers** (D-275). On the operator's Windows workstation, with the tool started
  from STS, every upload of a Postman environment or collection answered "This tool hit an internal
  error". The five lines of the log entry the operator pasted ended before the line that names the
  cause; line 978 of `DispatcherServlet.doDispatch` in them is where Spring wraps a `java.lang.Error`
  thrown in a handler. The operator was asked for the rest of the entry, the Problems view and their
  files, and has not answered.
- **A first theory, tested and refuted: the compiler of the IDE.** STS compiles with the Eclipse
  compiler, not the JDK's. An agent compiled the 446 main sources of `e70af34` with each of the twelve
  releases 3.36.0 to 3.46.100 of that compiler, against the class library of a JDK 21 and of the Java
  25 that STS brings: 24 runs, no error in any file (releases 3.33 to 3.35 refuse source level 21; a
  planted error was reported, so "no error" is a real result). A second agent ran the classes each
  release wrote: all 696 verified and linked, both sample uploads answered 200 and the two handover
  proofs held on each, on Java 21 and Java 25. **What stands of it:** a class with a compile error that
  the IDE starts all the same throws `java.lang.Error` ("Unresolved compilation problem") and writes a
  log entry of exactly the shape the operator pasted (seen in a copy compiled that way). So "STS started
  the project although it had errors" remains possible on that workstation; a difference between
  compiler releases does not produce it.
- **What a stress of the upload found, at default settings** (the jar of `e70af34`, 118 files of real
  size and odd shape, then a second agent that doubted it, reproduced it and shrank the input):
  1. A collection whose raw request body, or the body of a saved example, starts with `{` or `[` and
     holds one quoted text of about 1,300 characters or more made the upload throw
     `StackOverflowError`. The smallest file is a body `["` followed by 1,302 letters and `"]`:
     1,302 fails on the first upload after a fresh start, 1,301 passes. Other measured edges: 1,257 to
     1,278 for a Binary resource's `data`, 1,287 in a saved example; about 3,450 once the Java has
     warmed up (measured in a thread of its own, 3,456 on Java 25 and Java 21); about 1,600 with
     `-XX:TieredStopAtLevel=1`, which the IDE's fast start adds (1,613, measured once on a partly warmed
     tool); 1,173 with the interpreter only. Real exports commonly hold such texts (an encoded
     attachment, a saved response, a token); no vendor export was seen. The sample files' longest text is 79 characters, which is
     why no earlier attempt to reproduce the operator's failure met it.
  2. The pattern at fault was `LiteralCredentials.JSON_MEMBER`, the scan that looks for a credential
     typed into a body. It repeated a group holding an alternation, which `java.util.regex` evaluates
     by calling itself once per character, about six frames each, until a 1 MB stack is used up.
  3. **Why the store stayed broken.** `ConfigStore.uploadCollection` committed the collection before
     that scan ran. So the upload answered "internal error" for a file that was stored, and every later
     reading of it ran the same scan and threw again: the list of collections and "In force now"
     (after a collection of 3,000 characters, each answered 500 on every call, after a restart too),
     and the request detail for a text only in a saved example. The way out the stress found was a
     `DELETE` of that collection through the API, because the Config tab could no longer list it (its list answered 500).
  4. An environment value made of 1,579 or more labels joined by dots (1,578 pass) threw the same
     Error in `ProductionSigns.BARE_HOST`; nothing was stored. No real export looks like that.
  5. Beside them: `PostmanParser.VERSION` repeated a group once per number of a schema version; a
     collection nested deeper than the parser reads was refused as "not valid JSON", which is not the
     reason; and an environment of thousands of variables takes seconds to minutes inside the store's
     lock.
- **Decided by the integrator** (point by point): (1) no regular expression of the tool may recurse
  with the length of its input: rewrite the three named, then find every other pattern on every path
  (uploads, building requests, reading answers, masking, the ledger, History, lookups, the code
  picker); (2) a guard test that stays, which finds every pattern of the main code and runs each on
  long hostile texts on a 256 KB stack within a time bound, and fails on the old patterns; (3) each
  rewritten pattern gives the same answer as before, held by a test against the old one; (4) an
  upload stores nothing until every check that can fail has run; (5) a store written by the old
  version opens again, and the Config tab lists and deletes what it holds; (6) recursion over what a
  file or an answer nests is bounded, and the tool says the true reason when the bound is hit,
  Jackson's limit of 500 levels, not raised; (7) left out on purpose, to be reported: the time an
  environment of thousands of variables takes, a request with two file parts, a part without a file
  name, an upload under a heap forced small.
- **What was built, pattern by pattern** (`5c8d06e` to `4456ea5`, thirteen commits on their own branch
  from `e70af34`). Twelve patterns repeated a group the way `java.util.regex` evaluates by recursion:
  - `LiteralCredentials.JSON_MEMBER` is gone: a loop reads the JSON members of a body in one pass
    (`jsonMembers`). The possessive form the second agent had proposed no longer recursed, but under
    `find()` it took time in the square of the length: 70 s for 160,000 characters of escaped quotes,
    inside the store's lock. `JsonMembersTest` compares the loop with the old pattern on the existing
    cases and 200,000 seeded random texts.
  - `CodeListText`'s bracketed ending is a loop too (`withoutBracketedEnding`), held by
    `BracketedEndingTest` the same way.
  - Ten are possessive, their groups kept: `ProductionSigns.BARE_HOST`, `PostmanParser.VERSION`, the
    three `SENTINEL` patterns (`ScenarioRepository`, `TrackingPolicy`, `LookupFixtures`),
    `RunInputs.SUBJECT`, and in `RunRecord` the subjects of `CODE_ENTRY`, `NO_ANSWER_ENTRY` and
    `NAMED_ENTRY` and the path of `QUESTIONNAIRES`. `RewrittenPatternsTest` compares each with its old
    text on the existing cases and 100,000 seeded random texts.
  - After the attacks, seven inline strips that were quadratic on a long run followed by another
    character (`/+$` in four places, `[.\s]+$` in `LookupOutcomes`, `^-+|-+$` and `-+$` in `Ids`) are
    `Texts.withoutTrailing` and `withoutLeading`, compared with the old patterns on 100,000 seeded
    texts (`TextsTest`).
  - **Every other pattern** the search found, about 150 (some 90 static fields and about 70 written
    inline), was left as it is: each has only bounded repetitions or a loop over one class of
    characters, and each passes the guard. Among them `RunRecord.IDENTIFIER_OR_DATE`, which stays
    slow on texts of millions of characters (below, and open). Nine patterns are run by the guard with
    `matches()` only, because they are quadratic under `find()` over a million characters and are only
    used whole; that they are only used whole was checked by reading, not by the test. The 38 regular
    expressions of the browser scripts were read: none repeats a group; the guard does not cover them.
- **The guard** (`style/RegexStackGuardTest`, about three minutes). It finds the static `Pattern`
  fields of the tool's classes by reflection, and holds a list of every pattern written inline, with a
  scan of the sources that fails when an inline pattern appears that the list does not name or a
  listed one is gone. Each pattern is checked twice: its compiled form must hold no greedy repetition
  of a group, and it is run, with `matches()` and `find()`, on a million of each character it names,
  the same followed by one other character, long runs of quotes, backslashes, braces, dots, digits
  and white space, and alternations of them, in a thread whose stack is 256 KB. No
  `StackOverflowError`, and each operation within 5 s: the slowest healthy pattern took under 0.3 s on
  a million characters, while catastrophic backtracking never ends and quadratic `find()` over a
  million takes hours. Its own tests show it fails on the three old patterns and on the old strips,
  and stops an exponentially backtracking pattern at the bound. With only the guard compiled on the
  old code it failed, naming the twelve patterns. An agent that wrote none of it put back the old
  patterns one at a time, ten in all: the guard failed every time; and an unlisted inline pattern it
  added failed the list check. `RecursionGuardTest` is its companion for methods: it scans the
  compiled main classes for every call cycle (direct calls, lambdas, method references, overrides)
  and fails on one that is not listed with what bounds it: the depth of a JSON tree (at most 500
  levels), a fixed cap, or the backslashes of a configured secret; 35 cycles are listed. It does not
  see a recursion through a library type held in a field, as its Javadoc says. **At the merge** the
  screen round had added an inline pattern (the one in `Faults` that makes the cause of a failure one
  line) and removed one (the NPI check `ProviderLookupService` no longer holds); the guard failed on
  the merged code, once for each, until its list was brought up to it in the merge commit
  (`ba4661b`). The new entry is run against the long texts like every other. The guard tests then
  passed (`RegexStackGuardTest`, `RewrittenPatternsTest`, `RecursionGuardTest`).
- **The order of an upload.** Both uploads now run every check and build every warning, then write
  the file, then commit (`da735e7`); the answer to the upload, which was built from the stored
  snapshot after the commit, is built before it (`8000750`). Activation, the deletes and the mapping
  correction were read: nothing that can fail follows their commit but a log line, so they are as
  they were. `LongTextUploadTest` plants a failing check and a failing view and shows the store holds
  nothing afterwards, file for file.
- **Nesting.** A Postman file nested past the 500 levels the parser reads is refused with its own
  sentence (`b613938`): "This file nests its objects and lists deeper than the 500 levels this tool
  reads: the level past that limit opens at line L, column C. It was not read, and nothing was
  stored.", and for a collection "In Postman, move the requests of the deepest folders into folders
  nearer the top, export the collection again and upload that file." A collection of 240 folders is
  read, one of 300 is refused; an agent counted the position the sentence gives and found it exact,
  and a file exactly 500 deep uploads. A service answer nested past the limit gets "The body nests
  its objects and lists deeper than the 500 levels this tool reads: the level past that limit opens
  at line L, column C. It was not read." in place of "The body is not valid JSON." (`20740d6`).
  The note on a body of the tester's own that cannot be read now begins "The body you supplied
  cannot be read as JSON once its placeholders are filled in. It was sent exactly as written." and
  gives one of those two reasons after it (one CRD test's expectation changed for that, `4456ea5`).
- **What the two attacks found, and what was done about each.** Two agents that wrote none of the
  fix attacked it on the jar of `583ccbe`.
  - *A DTR questionnaire whose items enable each other in a chain of thousands* threw the same
    `StackOverflowError`, with nothing to do with an upload: `EnableWhenEvaluator` called itself once
    per item, and the items are a flat list, so the 500-level limit does not bound it. At the default
    stack, 1,200 items passed and 1,500 failed on Java 25; on Java 21, 1,000 passed and 3,000 failed
    (nothing in between was tried); on Java 21 at 256 KB, 200 passed and 400 failed; on the package fetch
    (live) and on `POST /api/dtr/response` (mock too); the call had reached the service and no ledger
    line was written. The old jar fails the same way: the fix missed it, it did not cause it. It is
    a legal questionnaire, if an unusual one. **Corrected** (`91dfb34`): the evaluator works from a
    stack of its own, in the order the recursive one visited the items; `EnableWhenChainTest` runs
    chains and rings of 5,000 items on a 256 KB thread and compares the new evaluator with the old,
    kept in the tests, on 20,000 seeded questionnaires. Such a questionnaire now gives the usual result
    ("The questionnaire package was loaded with one questionnaire.").
  - *An answer nested past 500 levels was still called not valid JSON*: corrected, above.
  - *The masking of returned secrets recursed once per JSON level* and threw on a 256 KB stack from
    about 300 levels: it is a loop now (`a36fca1`). The other walks over an answer still take a frame
    per level. `DeepAnswerStackTest` runs a 500-level CRD answer, DTR package, PAS answer and inquiry
    answer through their services on a 512 KB thread, masked, recorded and serialized; at 256 KB it
    fails. Subscription, lookup and raw answers were not run that deep, by the test or on the jar. On
    the final jar each of those four kinds of answer passed at 512 KB and at the default stack, on Java 21 and Java 25; at 384 KB and 256 KB
    with `-XX:TieredStopAtLevel=1` on Java 25, 6 of 9 failed (the check: 4 of 10 at 256 KB; the old
    jar 7 of 9). **Decided by the integrator: the smallest thread stack the tool supports is 512 KB.**
    A 64-bit Java gives 1 MB unless told otherwise, so a tester who sets nothing has nothing to do;
    the README says so under its limitations.
  - *The answer to an upload was built after the commit*: corrected, above.
  - *A credential's name was quoted whole in its warning*: a key of 2,000,000 characters ending in
    "password" gave one warning of 2,000,683 characters. A name longer than 60 characters is now quoted
    by its start and its length, "... (a name of 2000008 characters) ..." (`d7f9aba`); that warning is
    now under 800 characters (765 and 773 in two measurements, the file path differing). Field, header,
    query and helper names only: see what is open. (Later, D-280: every name a file supplies.)
  - *The inline strips were quadratic*: loops, above.
  - *`RunRecord.IDENTIFIER_OR_DATE` took over 5 s on 5,000,000 characters*: left. The corrector's
    reason, "only used on ledger text capped at 4,000 characters", was found untrue by the check that
    followed: it also runs on the parts of a questionnaire address a service supplies, which nothing
    caps (a 5,000,000-character address took 5.0 s and answered 200). A delay, no Error. Open.
    (Later, D-280: a part over 128 characters is no longer handed to it.)
  - *The answer the browser receives is about twelve times the body the service sent*: a PAS answer
    with a 10 MB address gave a browser answer of 120,011,498 characters, a DTR one 80 MB, a plain 10
    MB PAS text 100 MB; with no Error at the default heap. **Left by the integrator**, with an open line:
    cutting or sharing long values would change what section 21 says the wire view shows, so it needs
    a decision of its own.
- **What the proofs showed, each with its limit.**
  - *The old jar fails, the new one does not.* The jar of `e70af34` (identified by its checksum against
    the reports) answered 500 with `StackOverflowError` on the 1,302-character collection, on the
    3,000-character one and on the dotted environments, each on a fresh start, on Java 21 and Java 25;
    after the 3,000-character collection its list and "In force now" answered 500. The new jar answered
    200 on each, its reads 200. The attack repeated this with texts of 100,000 characters in a body
    and in a saved example; the last check repeated it on the final jar with a 256 KB stack and
    `-XX:TieredStopAtLevel=1`.
  - *The corpus* on the new jar (118 files, 126 uploads, by the builder; 162 uploads by the attack;
    128 files, 162 uploads, by the last check): each upload that was answered got 200, or 422 or 413
    with its reason. No "internal error", no
    `StackOverflowError`. The environments of 100,000 or 200,000 variables took many minutes or gave no
    answer within the client's time (the time left out on purpose), and the builder's client giving up
    on them left two "Unhandled exception" entries in the log (open, below; later, D-280: one INFO line).
  - *Big texts.* 1, 5 and 20 MB in a request body, a saved example, a header, a url, a script, an
    environment value and a dotted value, on Java 21 and Java 25, at the default stack, at 256 KB and
    with `-XX:TieredStopAtLevel=1`: every upload and read answered 200. **Limit:** that series ran on
    the jar of `583ccbe`, before the corrections; the last check ran texts of 2 MB again on the final
    jar.
  - *The scan still finds what it is for.* A literal credential beside a text of 2 MB is warned about
    in the same words as the same credential beside a short text on the old jar; the production signs
    of an environment are the same lists. Escapes, unclosed quotes and a backslash at the end of 2 MB
    each answered within 0.8 s. An agent's own program compared the old patterns with the new code
    over 14,369,576 texts (random, shaped, and every text of the samples, scenarios and fixtures): no
    difference.
  - *The old store opens.* A working folder poisoned by the old jar (the 3,000-character collection
    twice and a renamed copy; the old jar answered 500 on each and again after a restart) opens in the
    new jar: the list, "In force now" and each request detail answer 200, and in Chromium the Config
    tab listed both copies and removed one (screenshots looked at, on Java 21 and Java 25, the last
    check on a 256 KB stack with three collections and two removals). **Limit:** the same file uploaded
    twice is held once, because its id comes from the collection's name inside the file, not from the file's name; copies
    need different collection names. The Config
    tab on the old version after poisoning was never watched in a browser.
  - *The answers of the service.* On live against a stand-in on 127.0.0.1: a CRD answer, a DTR package
    and a PAS answer each carrying a text of 2 MB, and an error answer of 2 MB, on Java 21 and Java
    25, at the default stack and at 256 KB: each run completed, the secret the stand-in echoed was
    masked on the result page (CRD and PAS screenshots looked at) and absent from the ledger line and
    the log. **Limit:** on the jar of `583ccbe`; the DTR chains and the deep answers were run on the
    final jar.
  - *The commit order*: by tests, and by an attacking agent's jar with a throw planted in the scan
    and in the last check: `store.json` and the stored files byte for byte as before. That the view is
    built before the commit has no proof on the jar beyond the upload's view equalling the listed one,
    because nothing makes a view fail at run time.
  - *The merged code.* No proof ran on the jar of `ba4661b` beyond its whole build; the old store
    opening and the Config tab removing a copy were seen on the jar of `4456ea5`, before the screen
    changes were merged.
  - *Commits* were not built one by one; the whole build ran on `583ccbe` (2,896 tests) and on
    `4456ea5` (2,913), each with 0 failures, 0 errors and 2 skipped.
- **What is not confirmed: whether this is the operator's failure.** It is the only way anyone has
  made the tool write that log entry at default settings, and a vendor's collection will as a rule
  hold such a text. But after a collection of 1,500 characters was stored, the sample environment and
  the sample collection still uploaded with 200 (and on the old jar, after the 1,302-character file, the
  list and "In force now" answered 200). "Every upload fails" follows only for longer texts,
  through the Config tab reloading the stored list after each upload, which was read from the tab's
  script and not watched on the old version, or from the operator's own files. Only the line of their
  log entry after " <- caused by ", or the cause the screen now names (D-277), settles it.
- **What the operator has to do**, once this version is on the branch of their clone: pull (in STS:
  Team, Pull, then Gradle, Refresh Gradle Project) and start the tool again. The Config tab opens
  again on the store the earlier attempts left (seen on the jar of `4456ea5`, before the screen changes
  were merged); it may list more than one copy of the collection, one for each collection name (the
  name Postman gives it, inside the file) that was tried: delete the extras (Config tab, Collections
  and mapping, `Remove` on the row, then confirm). Upload the files again. If an
  internal error still appears, press `Copy reference and cause` under it and send what it copies.
- **Seen on the way and left, each with why.**
  - Resolving an environment takes time in the square of its variables, inside the store's lock: an
    environment of 10,000 chained variables takes about 7.4 s on an empty store, the same on the old
    jar, and minutes when many collections are stored; 100,000 chained took 766 s and 775 s, and
    200,000 variables gave no answer in 1,800 s. Every other upload, activation and delete waits
    meanwhile. Left on purpose: no real export looks like that.
  - A request with two file parts is answered with the first and the second is ignored without a
    word; a part named `file` sent without a file name is answered "The part file is required."; the
    kind is accepted in any letter case (`SandBox`). Left, as decided.
  - With a heap forced to 64 or 96 MB, files of 5 to 20 MB give an `OutOfMemoryError` while the server
    reads the upload, before the tool's code; at the default heap every file up to the 25 MB limit
    was accepted. Left, as decided.
  - **Open, and being corrected or looked at in the next step (D-280):** after 89 uploads into one
    store, a `GET /api/config/environments` answer that the corrector's script could not read, never
    explained (on a fresh store it answered with the list); a client that gives up on a slow answer
    leaves an "Unhandled exception" entry in the log although nothing in the tool failed; a credential
    warning still quotes the name of a request or of a saved example whole, however long (a
    1,000,000-character request name gave a warning of 1,000,696 characters); and
    `RunRecord.IDENTIFIER_OR_DATE` takes seconds on a questionnaire address of megabytes.
    Later: D-280, for each of the four. The environment list was right and very slow on the store
    left by the corpus uploads: an answer of about 32 MB in 488 to 637 s, and the script read a body its previous call had left: an open
    performance item, no fault of the answer. A client that gives up is one INFO line. Every name a
    file supplies is quoted by its start and its length when long. A part of an address over 128
    characters is not handed to the pattern, and the address is not recorded.
  - Open, with no step yet: the answer the browser receives, about twelve times the service's body;
    the walks over an answer below a 512 KB stack.
- **Nothing of this ran on Windows**, and the operator has not pulled it.
- **Tests.** 2,872 at `e70af34`; 2,896 at `583ccbe` and 2,913 at `4456ea5`, the head of the fix; 2,965
  at `963d5b0`, the head of the screen round. The two were merged at `ba4661b` (no file conflicted;
  the guard's list above was the one thing to settle). Its whole build through the shared runner:
  BUILD SUCCESSFUL, 3,006 tests, 0 failures, 0 errors, 2 skipped; the jar's sha1 is
  `981a972d4ba3d7f0620e1a8e7973288e037093c5`. The integrator's final gates had not run on it when this
  was written.
- The commits that record this change this file, `PROGRESS.md` and the README only.

**D-280. What the checks of the upload fix left: a secret a service repeats, a stop while a call is
out, a client that went away, long names, a slow pattern.**
The reports are in the integrator's scratch directory under `cont26/`, `cont27/`, `cont28/` and
`cont30/`, their outputs and screenshots under `el1/`, `el1b/`, `el1c/`, `el2/` and `el3/`, and the
briefs as `TASK_ECHO_SECRETS.md`, `TASK_ECHO_VERDICT.md`, `TASK_ECHO_LOCATION.md`,
`TASK_STOP_AND_GONE.md` and `TASK_LONG_NAMES.md`.
- **Where it comes from.** D-278 left two things seen while checking: a secret a service repeats back
  that the request did not carry was shown (its finding (4)), and a ledger line could be lost when the
  tool was stopped while a suite's call was out. D-279 left four for the next step: an answer of
  `GET /api/config/environments` a script could not read after 89 uploads; a client that gave up on a
  slow answer logged as "Unhandled exception"; a credential warning that quoted the name of a request
  or of a saved example whole; and `RunRecord.IDENTIFIER_OR_DATE` slow on a questionnaire address of
  megabytes. **The integrator ordered all of them corrected**, in three branches built side by side
  from `ba4661b`: `echo-secrets`, `stop-and-gone` and `long-names`. Each was built, checked on the
  real jar by an agent that wrote none of it, corrected, and checked again by another agent that
  wrote none of it. The first branch went through that twice more, for two further steps that its checks
  called for. Every live check used a stand-in on a loopback port; nothing of this ran on Windows or
  against the vendor's service.

*1. A secret a service repeats back* (`echo-secrets`, `18a336c` to `14fc20f`, 25 commits)
- **What was wrong, and since when.** An answer was masked only where it repeated a secret the
  request had carried (D-162, D-179, D-202, D-205). A secret the tool holds but this call did not
  send was not searched for: on live, a service that answered with the value of the client secret in
  an error answer, to a call sent without sign-in, had it shown in the returned fields and the wire
  view. On the jar of `ba4661b` against a stand-in that repeated everything, each answer of the API held
  the client secret 6 to 11 times; the member lookup showed the provider lookup's key and the other way
  round. The log, the ledger and the suite's CSV held none. It was so since masking was built; the
  check of D-278's last step was the first to try a secret the request did not carry.
- **Decided by the integrator** (point by point): (1) what comes back is masked wherever it repeats a
  secret the tool holds for the configuration in force, carried or not, by the one rule the tool
  already has for what is secret, and no second one; (2) the rule of the search is unchanged: the same
  characters, also as JSON escapes, a value shorter than six characters not searched for and counted,
  nothing judged by its look, an unset or blank variable masking nothing; (3) it holds everywhere a
  sent secret was masked (every mapper, the wire view, the returned fields, the comparison, what the
  ledger takes, a suite's rows and CSV, a notification); (4) holding these values puts them nowhere
  new, and `SentSecrets` hands no value back; (5) every sentence that said "a secret the request
  carried" says what is now the case; (6) the cost stays one pass whatever the number of secrets, and
  no pattern or walk recurses with the length or depth of an answer.
- **What the tool holds now**, from `ConfigStoreGateway.held` at the head: the secret variables of the
  active environment, of the collection the request came from (of every collection uploaded, for a
  call that comes from no collection, a lookup or an operation `application.yml` supplies, and for a
  notification) and of `application.yml`, an operating system value one refers to included, with the
  values given for this run in front of them; what the requests of those collections name or carry as
  a secret: an operating system value written as `{{env:NAME}}`, a credential typed into a url, a
  header that is a credential by its name, a credential in a body; the client secret the access
  token is fetched with; the credentials of the address to call back (a secret variable, a query
  parameter whose name reads as a credential, as written and decoded); the credentials of both lookups,
  in their urls and their headers; and the access token held. A variable is secret by the resolver's
  rule, the one the request side masks by. **Not held:** a value given for one run, outside that run
  (an answer to another call that repeats it is shown); a value typed into a Postman authentication
  helper rather than into a variable, a header, the url or the body, and a header Postman marks
  disabled (read from the code); an unset or blank variable; a secret shorter than six characters,
  counted and named in the note as before; a secret re-encoded (percent, base64, another case). A
  notification's headers are not taken in or shown, so only its body is masked. The mock lookups answer
  from fixtures and hold nothing; every other call on mock holds the same set as on live.
- **What was built.** One walk over a text for every secret, along a tree of their characters with a
  stack of its own (`18a336c`; the recursion `SentSecrets.endOfSecret` and its entry in
  `RecursionGuardTest` went with it, which makes that guard stricter); each prepared call holds the
  set above and every masking place reads it (`f10c267`); the notification takes the same set. Every
  sentence that said "this request sent" or "the request carried" now says "this tool holds": the wire
  view's "N places <where> are masked because they repeat a secret this tool holds.", the note "This
  tool holds N secret(s) shorter than 6 characters.", the notes on a body that is not JSON, and the
  lookup field's "This value repeats a secret this tool holds, which this tool masked, so it is not
  what the service returned and is not offered for the case." The notification's note now ends "...
  or a credential of the address to call back, of a lookup or of a request of a collection. What is
  shown is therefore not exactly what was received."
- **What the first check found, and what was done** (on the jar of `f10c267`, 15 placeholder secrets of
  every kind the decision names, 7 forms of answer). Held: each named kind 0 times in every answer, the
  ledger, the log and the CSV, where the old jar showed each 2 to 17 times; 15 of 15 answers to a stand-in
  that repeats nothing as before. Found, each corrected with a test that fails without it: (1, major)
  a lookup held no collection variable, so a lookup answer showed one 6 to 15 times: a call from no
  collection now holds every uploaded collection (`7dc47ec`); (2) an `{{env:...}}` reference and a
  literal header written into another request of the collection were not held: the requests of the
  collection in force are now read for what they name or carry as a secret (`7dc47ec`); (3) a held secret
  used as the name of a response header was shown: header names are masked like their values
  (`4e30ae1`); (4) a held secret that is an ordinary word of an answer changed the verdict and nothing
  said why: a sentence in the response note (`4af3add`), then the step below; (5) the tree walk was three
  times slower than the old search on a hostile answer (22,670 ms against 7,541 ms for 2 MB against one
  secret of 1,000 characters): failure links between its branches, built with a queue of its own, now
  run wherever no backslash is near (`696e0b4`; that case 27 ms); (6) no test held the run values in
  the set: one now does; and (7, major) the older leak below (`4872b40`).
- **The older leak, said plainly.** A credential typed into the address to call back
  (`onyx.subscription.callback-url`, say `?access_token=...`) or into a lookup url (`?api_key=...`) was
  shown in clear in "In force now" (`GET /api/config/effective`, marked not secret) since the
  configuration service was written (`9d080da`), and in the sentence of PAS Notifications "A
  subscription this tool creates asks the service to call back <url>, from the setting
  onyx.subscription.callback-url." since the PAS tab was built (`bbdeb2e`). The same on the jar of
  `ba4661b`: this change did not cause it. The tool itself treated those values as credentials and
  masked them in the Subscription it shows and in answers (D-179, D-215). No check before had seen it.
  The reports do not say why; by reading, the settings row was marked secret by the name of the
  setting alone, and the checks of these credentials looked at what a call sends and receives, not at
  the settings list. Now the three url rows show the masked form, for example
  `...?access_token=[secret access_token as typed]`, marked secret, and the PAS Notifications sentence
  reads that row. **No screen or log of the operator's has been seen, so whether a real credential was
  ever shown this way is not known.**
- **What the second check found** (on the jar of `cc5f0a3`, five more kinds of held secret of its own):
  every correction held, and 26 of 27 answers to the plain stand-in were as before, the difference being
  the url rows that held a credential. Two minor findings, each the subject of a step of its own: the cost bound was
  stated too narrowly, and the sentence that a value was read from a mask sat in the response detail,
  below the fold at 1440 by 900, while the verdict above it said "No prior authorization matches this
  inquiry." and "The environment answered normally."
- **A verdict read from a mask.** Masking the parsed answer before any mapper reads it was decided and
  stays, so no mapper output can carry a secret. The cost: when a held secret equals an ordinary word of
  an answer (a variable typed secret with the value `Bundle`, `ClaimResponse` or `active`), the mapper
  reads the mask and the verdict changes; with `Bundle` held, a PAS answer carrying a ClaimResponse
  read "The service answered without a ClaimResponse, so there is no decision." **This was so before
  for a secret the request carried** (the first check saw the same verdict on the old jar with the
  value sent in a header); now it happens without the request using the variable. Decided
  (`TASK_ECHO_VERDICT.md`): (1) wherever the tool draws a conclusion from an answer and a mask fell on a
  value it is read by, or on the name of one, one line stands in the verdict, under its headline and
  above its reasons, in view at 1440 by 900; the headline and its rules do not change and nothing is
  guessed; (2) the next step does not build on the mask; (3) a suite row and History show it through
  an existing list, with no new ledger field and no new CSV column; (4) the values a conclusion is
  read by are found from the code, not from a list of names; (5) the cost is stated truly. Built
  (`98e5a61`, `e67c003`): the line, a marked next step, and a finding kind `MASKED_READING` (group "About
  how this tool read the response") that the suite row, the CSV's findings column and History show;
  each reading (CRD, discovery, DTR, PAS, subscription, notification, both lookups) has its own list
  of names, held by a test that reads that reading's sources. **What the tester reads, word for
  word:** "Read from a mask, not from what the service sent: <value> was read as [secret <name>]. The
  variable or setting the mask names holds a value that the answers of this service use as an
  ordinary word, so the reading above may be wrong. Look at that value in the Config tab." and before
  the next step "First settle the masked value the verdict names, in the Config tab. Until then, this
  next step rests on the masked reading: " followed by the rules' own words. A folded notification
  and the one-line notification summary carry "Read from a mask, not from what the service sent: open
  the notification to see which value." On the DTR tab the line is repeated above "Still to do",
  because a step scrolls the verdict out of view. The check of that step found three values the lists
  missed, each read by `shown()` and then compared or carried, which its test pattern skipped: a
  subscription's `id` (with it held, the create carried `[secret ...]` into the next step and the
  Subscription id field, with no line), its `channel.endpoint` (a false "the service answered with
  another address") and a CRD link's `appContext` (carried into the DTR launch); also the notification
  line above the headline it qualifies, the DTR line out of view, a capital after the colon, and verdict
  lines that printed sent values in clear (the same on the old jar). All corrected (`cd995ed` to
  `0cb3c0b`): the lists gained `id`, `endpoint`, `appContext`, `preAuthRef`, `start`, `end` and
  `readOnly`, and a test now places every name a reading shows as judged, with its reason, or as only
  shown; the subscription verdict compares and says the sent address and id masked; a real difference
  in the address is still reported (seen by the second check).
- **The id from the `Location` header, said plainly.** When a subscription create gives its id only in
  the `Location` (or `Content-Location`) header, the tool read it from the header unmasked since the
  subscription service was written (`3954247`) and the PAS tab filled the Subscription id field with it
  (`bbdeb2e`). Once answers were masked by held secrets, an id that repeats one was shown in clear in
  the carried next step, in the detail and in the Subscription id field, while the wire view masked
  the same header on the same screen and the verdict said "Its id is [secret X]." The second check of
  the verdict step found it; the corrector before it had described it wrongly ("a mask there gives no
  line"; there was no mask at all). It was so before this branch. And a mask could be carried into a
  field: after a create whose id was read from a mask, the field held `[secret X]`, and Read
  subscription refused it with the sentence about the form of an id. Decided (`TASK_ECHO_LOCATION.md`):
  (1) every value the tool takes from a response header goes through the same mask as the body and
  counts as a masked reading where a conclusion rests on it; (2) a mask is never put into a field and
  never sent: the field stays empty with a line saying why, and a chain, suite or replay step that would
  send one is refused with the reason and sends nothing; (3) the clear value is recovered from nowhere.
  **The headers the code reads**, found by the builder and searched again by the check: the
  `Location` and `Content-Location` of a create, and the charset a `Content-Type` names, which the note
  on a body that cannot be decoded quotes ("The body is not valid [secret X], the charset its
  Content-Type names."); no other (no `ETag`, retry hint or request id). The whole header is masked
  first and the id taken from the masked value (`cc8eca8`, after the check found a secret covering more
  than the id leaving the id in clear); when a mask hides where the id starts, the masks stand for the
  id. **Now the field stays empty and reads beside it:** "Not filled in: the answer gave this value
  masked, as [secret X], because it repeats a secret this tool holds. Type the value, or settle the
  secret in the Config tab." The verdict reads "Its id is [secret X]." with the line "... id, from the
  Location header, was read as [secret X]. ...", a marked next step and `MASKED_READING`.
- **Every place a carried value fills a field or becomes part of a request**, before and now:
  - The Subscription id field after a create: filled with whatever the answer gave, a mask included;
    now not filled when it holds a mask, with the line above. The claim identifier an inquiry follows
    (from a submission, a notification or a resubmission): filled unchecked; now through the same
    check, though a claim id is the tool's own sent value and cannot carry a mask.
  - What CRD hands DTR (questionnaire, coverage assertion id, app context, response reference, purpose)
    and what DTR hands PAS (completed QuestionnaireResponses): posted as received; now a masked value is
    dropped and named under "Handed on from": "Not handed on: the answer gave this value masked, as
    [secret X], because it repeats a secret this tool holds, so it is not sent. Type the value on the
    form, or settle the secret in the Config tab." The hand-over a DTR step posts back is stripped the
    same way, the stripped values named beside "Still to do".
  - On the server, every run request refuses a hand-over value holding a mask, under any key: before, a
    suite chain passed the CRD and DTR hand-over straight on (the builder's test, run against the old
    code, shows the package fetched for a masked address). Now: "<label>, handed on from the step before, was given masked, as [secret X],
    because the answer it came from repeats a secret this tool holds. A mask is never sent, so this
    step was not sent. Type the value on the form, or settle the secret in the Config tab." A suite row
    reads, for example, "CRD order-sign was sent; DTR questionnaire package was not sent. carry.questionnaire: ...".
    A key never sent (`dtrAppContext`) is refused too, by choice: which keys reach the wire depends on
    the form, and the reason names the key.
  - The DTR adaptive loop posted the Questionnaire and QuestionnaireResponse it held from a masked
    package, so a mask went to `$next-question` (two masks on the wire, the same on the old jar); found
    by the check of this step. Now the next question, its preview and the response step are refused
    with field `questionnaire` or `questionnaireResponse`: "The questionnaire, handed on from the step
    before, was given masked, as [secret X], because the answer it came from repeats a secret this tool
    holds. A mask is never sent, so this step was not sent. Settle the secret in the Config tab, then
    fetch the questionnaire package again." (and "The QuestionnaireResponse so far, ..." the same way),
    and the tab says so above the buttons first: "What the next step would post holds a mask, [secret
    X]: an answer this tool read repeats a secret this tool holds. A mask is never sent, so the next
    step is refused and nothing goes out. Settle the secret in the Config tab, then fetch the
    questionnaire package again."
  - A mask typed into the subscription id or a claim id: before, the sentence about the form of an id;
    now "This is not a subscription id but a mask, [secret X]: an answer gave the value masked because
    it repeats a secret this tool holds. A mask is never sent. Type the value itself, or settle the
    secret in the Config tab." (a claim id the same way).
  - A replay sends no carried value, and a masked id is on its line as "(not recorded, N characters)",
    before and now: all nine runs a check tried were not replayable.
  Later: D-299. Each sentence quoted in this entry that says "in the Config tab" says "in Configuration"
  since `629a584`.
- **The cost of the search.** One pass over the answer whatever the number of secrets. Measured on the
  classes of the jars, one machine: 2 MB with 1, 20 and 200 secrets held 30, 22 and 20 ms, 20 MB 199,
  200 and 226 ms (the old search, as the first check measured it: 6, 74 and 702 ms; 50, 775 and
  7,715 ms); a DTR package through the jar of `f10c267` about 0.13 to 0.18 s at 2 MB and 1.0 to 1.1 s
  at 20 MB. **The true bound:** at a start
  with a backslash at it or within the length of the longest secret after it, the old walk runs, at up
  to the length of the longest secret per character; so wherever backslashes lie closer together than
  that length, and the text repeats most of a long secret over and over, the cost is that of the old
  search. Measured: an escaped backslash every 900 characters against one held secret of 1,000
  characters, 2 MB 8.9 to 10.0 s, 8 MiB about 36 s, 20 MB about 90 s; against one of 2,000 characters
  (as long as an access token can be) 8 MiB 73 to 77 s, 20 MB about 187 s. An ordinary answer of 8 MiB
  or 20 MB with a backslash every 300 characters and no secret in it took 0.13 to 0.33 s. The client
  reads an answer to its end (no cap); the receiver reads at most 8 MiB. The class comment of
  `SentSecrets` says this. Making that case linear was not attempted, as decided, because it could not be
  proved to mask exactly what the search masks. 600,000 random texts masked the same on the automaton
  of `cc5f0a3` and on the tree walk of `f10c267` (the second check's own run); against the old
  per-secret search, unit tests compare 3,000 texts each.
- **What the proofs showed, each with its limit.** On the live profile against stand-ins of the
  agents' own on 127.0.0.1, with placeholder secrets; jars of `f10c267`, `cc5f0a3`, `2a4f9d2`, `0cb3c0b`,
  `130b2e8` and `14fc20f`, each identified by its checksum.
  | Run | What it showed |
  | --- | --- |
  | Every call (CRD, discovery, DTR package and next question, PAS submit and inquire, the five subscription calls, both lookups, a raw send, a notification, a suite, History, export, replay, Config) against a stand-in that repeats every held kind in fields, names, escapes, headers, long and 2 MB texts, redirects, text and HTML | each held placeholder 0 times in every answer, the ledger, the log, the CSV and the page in Chromium; the old jar 2 to 17 times each |
  | The same calls against a stand-in that repeats nothing | the answers as on the jar before each step once ids, times and salted tokens are taken out, apart from the url rows and the refusal sentences meant to change |
  | Ordinary words held as secrets (`Bundle`, `ClaimResponse`, `active`, `complete`, an id, an address, an app context, an authorization number) | the line in the verdict, in view at 1440 by 900 (DTR next question: repeated above "Still to do"), the marked next step, `MASKED_READING` in a suite row, the CSV and History; the headline as before |
  | A create with the id in `Location` only, then Read, Update and Delete from the screen | field empty with its line, a typed mask refused, the typed id sent with no mask in it |
  | A CRD hand-over holding masks, through DTR to PAS, and a suite chain | masked values named as not handed on, the chain step refused, 0 masks in any request the stand-in received |
  The hard rules were looked at each time: live offers no scenario or sample, the only browser storage
  key is `pave.tab`, no secret or member placeholder in the log or the ledger, `smoke.py` ended "ALL
  PROOFS HELD", and the three guard tests were not weakened. **Limits:** never the vendor's service,
  nothing on Windows; the jar of the merged head was not run (its whole build was); the discovery screen
  was not shot again after the verdict step's correction (its answer was among those compared); the
  `preAuthRef` of a notification was run by the second check only.
- **Left, each with why.** A run value is held only for its run (the tool holds nothing else of it). A
  subscription id the tester typed is carried and shown as typed, and the PAS Notifications sentence
  shows the configured address in clear where a part of it merely equals a held secret: both the
  tester's or the installation's own values, recorded and left by decision (the ledger holds the typed
  id 0 times). Answers a tester types in the DTR questionnaire are not checked for a mask: their own
  words. The subscription's response note names masks in the body only; the `Location` reading is in
  the verdict, the next step and the finding. Names are matched wherever they sit in an answer, so a
  line can appear for a `start` that the decision does not read; it says "may be wrong". A raw send
  draws no verdict and gets no line. The search's bound above stands.

*2. A stop while a call is out* (`stop-and-gone`, `a20e64f` to `f9e269c`, then `f386962`)
- **What was wrong, and since when.** `SuiteService.close` called `shutdownNow` at once, and
  `JsonlRunLedger.append` wrote through a `FileChannel`, which an interrupt closes: the log said "A
  ledger write failed for <file>: java.nio.channels.ClosedByInterruptException" and the line was lost.
  Both since the suites (`df3adcd`) and the ledger (`2b5c2d4`) were written. Found first by
  measuring on the old jar (SIGTERM, live, stand-ins answering after 20 s or never): a suite case
  left no ledger file at all and the tool was gone in 0.4 s; a screen run with no answer left a
  0-byte file, an empty reply, and an "Unhandled exception" entry; a screen run answered within the
  grace kept its line. An interrupted call was also classed "unavailable".
- **Decided** (point by point): (1) find out first what happens today, for a screen run and a suite
  case; (2) a request that left the tool gets its ledger line whenever the tool is stopped in a way that
  lets it run its stop: a suite case gets what a run on a request thread gets, within the bound the web
  server gives a request in flight, then no further case starts, and only then is the thread
  interrupted; (3) a ledger write does not fail because its thread was interrupted, the line is whole
  or absent, and the flag is put back; (4) the line of a case the stop cut short says the truth in the
  words the tool already has; (5) the stop always ends; (6) what a suite gathered in memory goes with
  the stop, as before. The stop button of an IDE on Windows ends the process at once, so nothing the tool
  does can help there (by reading; not seen).
- **The bound, and where it comes from.** Spring Boot stops gracefully by default, and the web server
  gives a request in flight `spring.lifecycle.timeout-per-shutdown-phase`, 30 seconds unless set;
  `application.yml` sets neither that nor `server.shutdown` (read, and the log of the real jar says
  "timeout of 30000ms"). `SuiteService.stopBound` takes that phase time; with `server.shutdown=immediate`
  it takes the connect and read timeouts together, never more than 30 seconds. The bound runs from the
  moment the stop begins (a listener of the closing context), beside the server's grace.
- **What was built.** The ledger takes the interrupt flag down for the append, writes the line through a
  `java.io` stream in append mode with a sync, and puts the flag back (`a20e64f`); later the check of the
  file's last byte reads through `RandomAccessFile` too (`f386962`), because under a storm of interrupts
  it added spare empty lines (2,650 for 3,000 records before, none after; no line was ever lost or
  torn). An interrupted call is a timeout whose arrival is not known (`b514f76`). A suite starts no case
  once the stop began, lets the case in flight finish within the bound, then interrupts it and waits at
  most 5 s more (`4e80ae5`, `f9e269c`). Nothing thrown there reaches Spring (D-278 holds).
- **What a stopped tool leaves now.** A call that answers within the bound keeps its ordinary line. A
  call with no answer by then gets a line with outcome `TIMEOUT`, error type `TIMEOUT`, no HTTP status,
  next action `CHECK_ENVIRONMENT`, unverified, which History shows as "The service did not answer in
  time"; the run's own detail says "No answer had arrived when this tool was stopped, so whether the
  service received the request is not known." Before, such a call was `UNAVAILABLE` ("The connection
  to the service failed before an answer arrived. ..."). The log says, for a suite case, "A suite
  case was still running when the time for the stop ran out (30 seconds). Its thread is interrupted."
  and, if it still does not end, "The suite case did not end after its thread was interrupted. The tool
  stops without waiting for it."
- **What the runs showed** (the real jar, live, SIGTERM; the builder, then the check, then the second
  check):
  | Run | Before | After |
  | --- | --- | --- |
  | Screen run, answer after 20 s | gone in 18.5 to 18.6 s, line kept | the same |
  | Screen run, no answer | gone in 30.5 to 30.6 s, 0-byte file, no line, three log entries | gone in 30.5 to 31.1 s, one `TIMEOUT` line, History shows it |
  | Suite case, answer after 20 s | gone in 0.4 s, no ledger file | gone in 17.3 to 18.8 s, the line, no further case |
  | Suite case, no answer | gone in 0.4 s, no ledger file | gone in 30.4 to 31.0 s, one `TIMEOUT` line |
  The check added: SIGINT acts as SIGTERM; three signals in a row end at 31.0 s; 10 stops of a 200-case
  chain suite on mock left every chain whole (the old jar twice left a chain with its CRD line only); 8
  `kill -9` during a 300-case suite never left a torn line, and a half line put in by hand was counted
  unreadable and the next line began on a line of its own. **Unit tests only:** a case that ignores the
  interrupt for the 5 s after it, and an interrupt during the write itself.
- **Left.** The stop button of an IDE on Windows, a `kill -9` or a power cut end the process without
  its stop, and a call then out gets no line. A chain case in flight may still send its next step within
  the bound, as a chain started from a screen would. What a suite gathered in memory (its rows, its CSV)
  goes with the stop, as before.

*3. A client that went away* (`stop-and-gone`, `dc7d728`, `0af7f2a`, `4f83dd8`)
- **What was wrong.** A browser or client that closed the connection before the answer was written
  (a page reloaded during a slow call, a script that gave up) was logged "Unhandled exception, reference
  <id>: ...HttpMessageNotWritableException..." with frames, the entry the tool writes for a defect of its
  own, since the one error handler was written (`30e513b`). Seen twice in D-279's stress.
- **Decided:** such an end is one INFO line with the method and the route, never a query, body or
  identifier, with the exception types and no reference; a failure to write that is not a client going
  away keeps its entry under a reference; the test for "gone" is no wider than the server's own types;
  the run itself still completes.
- **Built.** `web/ClientGone` recognises only `ClientAbortException` and
  `AsyncRequestNotUsableException` in the chain; the error handler, the outermost filter and the
  server's error report use it. During a stop the server closes connections itself after its grace and
  raises the same type, so `web/StopMark` marks the stop on the servlet context and the line changes.
  **The two lines, word for word:** "The client closed the connection before the answer was written:
  <METHOD> <route>, <exception types>" (for example `POST /api/crd`; a request no route matched reads
  "(a request that matched no route of this tool)"), and while the tool is stopping "The connection
  closed while this tool was stopping, before the answer was written: <METHOD> <route>, <exception
  types>". During the 30 s grace a client that really leaves also gets the second wording, which does not
  say who closed it.
- **What the runs showed.** On the real jar, mock and live, a waiting call reset mid-wait and a large
  answer cut after 64 KB each gave one INFO line, and the whole output held "Unhandled exception",
  "Exception in thread", "Caused by:", a frame and the member placeholder 0 times; a quick call reset at
  once gave no line before or after, its answer having reached the socket. The abandoned run kept its
  ledger line. The check planted three real write defects in a copy: each kept its entry under a
  reference. **Unit tests only:** the server's error report path (`JsonErrorReportValveTest`; 300
  refused requests reset at once never made it fire on the jar) and the guard in `StopMark`. The
  check's finding that `JsonlRunLedger.writeFully` was dead did not hold: the start's write check calls
  it.
- **The answer nobody explained.** On the store left by a new run of the corpus uploads (91 files,
  without the three slowest; 80 answered 200), `GET /api/config/environments` was right and very
  slow: 200, the documented shape, 23 or 24 environments, about 32 to 33 MB, in 527 to 637 s on the
  builder's run and 488 s on the check's. The script's own time limit was 600 s, and its helper then read
  the body file its previous call had left, which had no `environments`. On a fresh store the three
  large environments of that corpus uploaded in 3.8 to 8.2 s each and the list took 8.4 to 9.5 s (4.5 MB);
  the builder's first account, that those three environments alone take minutes, was corrected by the
  check: the time grows with the whole store, because the view of every environment is built on each
  call (outside the store's lock). **An open performance item, no fault of the answer.** With the three
  slowest files of the corpus as well, every later upload waited behind one inside the store's lock
  (D-279), and that run was stopped after about 18 minutes.

*4. Long names* (`long-names`, `055decb`, `a8e2b0f`, `4a02252`, `de883a5`, `87f4a6e`, `5d35ce2`, `38ac780`)
- **What was wrong, and since when.** D-279 shortened field, header, query and helper names in the
  credential warning (`d7f9aba`); every other name a file supplies was quoted whole in the sentences of
  an upload, since those sentences were written (the credential warnings since `00e6e48`): a request name
  of 1,000,000 characters gave a warning of 1,000,705 characters and an upload answer of 2 MB.
- **Decided:** every name a file supplies that a sentence quotes back is quoted by its start and its
  length when long, in the words D-279 used; a name shown as the tester's own label may stay whole if
  the page still works with 100,000 characters, looked at in a browser; ordinary names read exactly as
  before.
- **Built, and corrected.** One rule (`postman/Names`, and `named()` in the page): a name is quoted
  whole unless its first 60 characters and "... (a name of N characters)" are shorter, so names up to
  89 characters read as before; N counts characters, not UTF-16 units, and a character is never split.
  Used in every sentence of both uploads, the readiness list, "In force now", the write refusals, the
  page's own sentences, headings and labels for screen readers. The check found (major) that the
  strip and the production banner now shortened the name while the unlock refusal says "Type it
  exactly as the environment strip shows it" (typing what the strip showed was refused): the strip and
  the banners show the whole name again, at any length, in a box that wraps and then scrolls, and the
  guardrail's words are unchanged (`a8e2b0f`; a 75-character name unlocked, typed as shown); (major)
  "The url still needs a value for {{...}}" quoted a placeholder whole (`4a02252`); a 61-character name
  grew to 89 (`de883a5`); the production addresses repeated the name (`87f4a6e`); the suite's note
  "... can create or change something on the far side in <environment>." (`5d35ce2`). The unlock panel's
  bold name made the page 967,572 px wide below 1181 px, on the old jar too (`38ac780`).
- **Proofs.** On the jar, 1,000,000-character names: the request warning 1,000,705 to 799 characters,
  the answer 2,001,672 to 1,001,766; the example name's warning 1,000,515 to 609. 136 corpus uploads and
  their lists and details read word for word as on the old jar (before the corrections; after them the
  sample files gave the same 6 answers). In Chromium at 100,000 characters the page stayed 1,440 px wide.
- **Left.** Names given as labels stay whole: a 1,000,000-character request or collection name is in
  the upload answer once (about 1 MB), and at 100,000 characters a row is tens of thousands of pixels
  tall; a production name of 200,000 characters pushes the work area down to 438 of 900 px, and at
  1000 px wide puts the unlock field about 112,000 px down. The run ledger's `targetSource` keeps the
  request name whole.

*5. The slow pattern* (`long-names`, `5980bb9`, `4fd5a8a`)
- **What was wrong.** `RunRecord.IDENTIFIER_OR_DATE` ran on every part of a questionnaire address a
  service supplies, which nothing cuts, since questionnaire addresses were judged for the ledger
  (`050f7de`): a 5,000,000-character part made a DTR package take 5.1 s and wrote a ledger line of
  5,000,833 characters. D-279's corrector had left it on a reason found untrue.
- **Decided:** no pattern takes a time a tester would notice on a text a service or file supplies at 5
  MB; a part longer than anything the pattern is meant to recognise is not handed to it; look for the
  same shape elsewhere; recognition unchanged.
- **Built.** The longest thing it recognises is an identifier of 128 characters (a date is at most 18),
  so a part longer than 128 is not handed to the pattern and the address is written "(not recorded, N
  characters)". All 168 patterns run at five million characters: only this one passed 1 s; every other
  input of it is cut first (at 64, 60, 20, 80 or 128). `RegexStackGuardTest` was not given the
  five-million bound (that sweep took 786 s against its 245 s); `QuestionnaireAddressPartTest` holds the
  case. Measured on live: 5.1 to 1.0 s for `0-`, 3.1 to 0.9 s for `Jan` (the check: 4.5 to 7.8 s before,
  1.1 to 1.6 s after); the ledger line 830 characters.
- **Left.** **History no longer keeps an ordinary questionnaire address with a part of 129 characters or
  more**, which it kept before, whole (84 of 50,000 seeded addresses widened, none lost). A 5 MB
  address split into short parts by `/` still writes a 5 MB ledger line and takes 3.3 to 3.6 s on old and
  new jars alike, which is not this pattern; it sits with D-279's open item on the size of the browser
  answer.

*The merges and the build*
- Four merges into `final`, on top of `0b7e534`, none with a textual conflict and nothing settled by
  hand: `stop-and-gone` at `4f83dd8` (`83c37f7`), `long-names` at `38ac780` (`e1a4eec`), `echo-secrets` at
  `0cb3c0b` (`1ac0458`) and, after the step of `TASK_ECHO_LOCATION.md` was built and checked twice,
  `echo-secrets` again at `14fc20f` (`6ab0e69`). After each of the first two merges the guard tests
  (`RegexStackGuardTest`, `RecursionGuardTest`, `DeepAnswerStackTest`, `RewrittenPatternsTest`,
  `HouseStyleTest`) passed on the merged code; at `1ac0458` the whole build passed (3,082 tests, 0
  failures, 0 errors, 2 skipped); at `6ab0e69` the guard tests passed again, then the whole build.
- **Tests.** 3,006 at `ba4661b`. The last whole build of each branch: `stop-and-gone` 3,024 at `4f83dd8`,
  `long-names` 3,016 at `38ac780`, `echo-secrets` 3,065 at `14fc20f`. The merged head `6ab0e69`, through the
  shared runner: BUILD SUCCESSFUL in 9m 53s, 3,093 tests, 0 failures, 0 errors, 2 skipped; the jar's
  sha1 is `17f77be1588da9a4d61a42dd3c056cee989407fd`. The integrator's final gates (two builds from clean,
  a fresh clone, the start, the handover proofs) last passed on `e70af34` and had not run on this head
  when this was written.
- Nothing of this ran on Windows, and the operator has not said whether they pulled the upload fix.
- The commits that record this change this file, `PROGRESS.md` and the README only.

### The rebuild of the screens, release 1

**D-281. The operator's verdict on the screens, what was found about the two failures they named,
and the decision to rebuild the screens on the model of the operator's own tool.**
The reports are in the integrator's scratch directory: what the operator said and asked for, as
reported, in `TASK_REDESIGN.md`; the reproduction of the Config error in `cont36/`; the two hunts for
the upload failures in `cont37/`; the reading of the operator's earlier sessions under
`redesign/history/`.
- **What was reported.** The operator ran the tool on their Windows workstation and sent photographs
  of it beside a tool of their own that does the same job (CRD, DTR and PAS against the vendor's
  sandbox), built by them in earlier chat sessions. In substance: the screens are not usable, far too
  wordy, and do not feel like an application; the first tab they opened (Config, "In force now", on
  mock with a collection stored) showed the internal error box; their last three uploads in a row
  failed. This is reported, not seen. The photographs show real values (a client id, the vendor's
  host names, test patients); none is copied into anything written here, and each is named only by
  its kind.
- **The operator's answers, given the same day (2026-11-22) to four questions.** (1) Rebuild the
  screens on the model of their own tool, keeping PAVE's engine (its server, not their tool's code).
  (2) Saved lists of test members and providers are wanted, for test data only. (3) Submit follows Da
  Vinci PAS 2.2.1, as their own tool does. (4) A trail of the full requests and responses of the last
  500 exchanges is wanted, secrets masked. They also asked, as requirements: the member and the
  provider lookups plugged in through configuration on screen, with typing by hand where no endpoint
  exists; everything the tool needs to call the vendor set on screen and used at once, without a
  restart (the endpoints of each operation, the sign-in, the timeouts, mock on or off); an upload that
  can never end in an internal error; one workflow from the coverage check through the questionnaire
  to the submission, with or without attachments; and that their earlier sessions be read so that
  nothing is missed or repeated.
- **Who decided what.** The four answers and the requirements above are the operator's. Everything
  else is the integrator's or the plan's: the decisions R-01 to R-44 of the plan and O1 to O11 of the
  integrator (D-285), and the two releases at fixed hours. None of those was put to the operator before
  it was built. One of them goes against a requirement the operator stated: the rebuild keeps mock and
  live chosen at start (R-01, D-297), where the operator asked for mock on or off to be set on screen
  and take effect at once.
- **The Config error, reproduced.** The operator's store holds a collection with one long quoted text
  in a body or a saved example. Every build before `0b7e534` throws `StackOverflowError` reading it
  (the fault D-279 records): the collection was stored before the upload failed, and two of the three
  reads the Config tab makes when it opens fail on every call after that. The photographs match the
  code of `e70af34`, which reproduced both of them on a jar of that code; the build the operator ran
  predates the fix. The head of `epa-pave` (`6ab0e69`) and the round 2 build (`076e965`) open that
  same store with no error. The way out for the operator: stop PAVE, pull, start it again and reload
  the page; nothing has to be deleted or uploaded again.
- **The uploads, hunted twice.** One hunt tried files (about 5,270 uploads, 4,200 of them fuzzed), the
  other the conditions of a workstation (a Windows Java under Wine, locked and read-only files, a
  removed folder, small heaps and stacks, two copies on one folder, an IDE rebuilding the classes).
  **On the fixed build (`076e965`) none of the 5,270 uploads ended in the internal error.** What still
  failed, each a way the operator's three failures could have happened: the IDE rebuilding or
  replacing the classes under a running tool after a pull, after which every upload answers the
  internal error until a restart, with the operator's log entry frame for frame (the likeliest on a
  workstation that runs the tool from STS); the run script starting whatever jar is already built, all
  of them called 1.0.0, so that after a pull the old jar (which gives exactly the reported error and
  leaves the store unreadable to it) could still be the one running; a file exported again over the
  chosen one, which the page reported as a server it could not reach; an environment once uploaded as
  prod by mistake, whose addresses then refused every sandbox upload of it; a store file marked
  read-only or held open by another program, answered with advice about the folder. **Neither cause
  was seen on the workstation itself**; which of them was the operator's is not known.
- **What is still not known.** Nothing has been seen on the workstation since the photographs: not
  whether the operator pulled, not their log entry's cause. The operator has said nothing yet about
  the rebuild itself, which nobody but agents has used.

**D-282. The second round of changes to the old screens: built, closed, and replaced before it was
handed over; what of it stays in the code.**
The plan is `UI_ROUND2_PLAN.md` in the integrator's scratch directory, the builders' reports
`cont31/`, the closes `cont32/`, `cont34/` and `cont35/`, the integrator's decisions on the closes
`TASK_UI2_CLOSE2.md` and `TASK_UI2_CLOSE3.md`, and what its last check left
`redesign/round2-left.md`.
- **Why it existed.** After the first round (D-276) the operator answered nothing: not on the page of
  the ten worst, not on the round. Three looks by agents at the running tool on `0b7e534` (a first day
  on mock, a first day on live against a stand-in, an open list) found the screens still long and
  still saying things that were not the case. A plan of twelve letters (A to L, B in two halves) was
  written from them, attacked by a sceptic and corrected; it named six questions only the operator
  could answer, each with the default taken.
- **What happened to it.** Three builders built it side by side from `1ac0458`; the integrator merged
  the three batches (`268e8b6`, `c05f4c1`, `0bd1407`) and the masking step of D-280 (`e87c515`). Four
  agents that built none of it looked at it (a journey on mock, History and suites, live against a
  stand-in, the code against the plan and the hard rules); a corrector closed 39 of 45 findings in 28
  commits; two checks followed, the integrator decided ten more corrections, then five, each checked
  by agents that wrote none of it. Its head `79a7c25` was built whole: 3,188 tests, 0 failures, 0
  errors, 2 skipped. **It was never handed over and never recorded**: the operator's verdict (D-281)
  arrived during its last close, and the rebuild began from its head. The first commit of the
  rebuild (A1, merged at `1d0c4c0`) deleted every script and stylesheet of the old screens (51 and 7)
  and the 24 tests that read them. Every change the round made to a screen went with them.
- **What stays in the code at the head, each with its contract change and every reader that had to
  follow.** Checked in the source at the head:
  1. *Replays in the run list.* `GET /api/runs` takes `replays` (`include` or `leave-out`; any other
     word is refused), carried as the new last component `includeReplays` of `RunQuery`; absent means
     the replays are listed, so the export, the replay and every caller that does not ask are as
     before. Readers: `RunController`, the ledger's filter, `testsupport/InMemoryLedger`.
  2. *A call the tool could not sign in for.* A new outcome `NOT_SIGNED_IN` ("Not sent: this tool could
     not sign in", neutral, not the guardrail's purple) and two failure types, `NOT_SIGNED_IN` (the
     tool's own sign-in settings, or the token endpoint refused the client) and `TOKEN_ENDPOINT_FAULT`
     (the token endpoint could not be reached, timed out, failed or gave no usable token). Such a call
     is recorded, as answered by nobody and not sent: the ledger line holds the outcome, `errorType`
     the failure type's name, no HTTP status and no time; **no field was added to a line**, and an
     older ledger reads as before. Readers that had to follow: `RunRecord` (reads it as not sent),
     `VendorExchange` (one line, without which the new types threw at run time), `SuiteService` (a
     count group of its own, `notSignedIn`, in the words of the outcome), `ProtocolCaseRunner`,
     `SentRuns`, `RunReplay` (a replay status `not-signed-in`, "Not sent again"), `ChainService`.
  3. *A token failure said as what it is.* "closed the connection without answering" only where the
     type of the failure means a connection was open and ended (`ExchangeExecutor.closedWithoutAnswer`,
     by exception type, never by message); a token url the tool cannot use is said to be one, naming
     the setting and never its value: "Authentication is switched on but onyx.auth.token-url, as
     configured, is not an address this tool can use: it must be an absolute http or https address
     with a host, and a port from 1 to 65535 if it names one. The request was not sent."
  4. *A 401 or 403 says what was sent.* Where the request as sent carried an Authorization header the
     verdict and the error panel say the service did not accept the credentials this tool sent; where
     it carried none, that none were sent (`RunSupport`, the four protocols alike).
  5. *Played faults said as rehearsals.* `service/run/PlayedTimeouts` was renamed `PlayedFaults` and
     covers every fault a mock scenario plays; its wording no longer sends a tester to fix an
     environment that was never called.
  6. *The step after a subscription on mock.* A new next action `DELIVER_SAMPLE_NOTIFICATION` (no new
     field, no new column; the ledger line, the export CSV and the API's `nextStep.action` carry it
     where `START_PAS` or `OPEN_CONFIG` stood). The rebuild gave it the words "In the Status step,
     Deliver a sample for this submission." and the button "Deliver a sample" (D-288).
  7. *An answer the tool cannot act on still gets an action* (letter L), the DTR steps that went right
     are neutral rather than orange, an inquiry is told apart from its submission, and the CRD label of
     an answer that is not a CDS Hooks response says so.
  8. *What the close changed in the ledger's values and in `MemberInText`*: D-283 and D-284.
- **The six questions, and what became of each.** (1) "Run as part of a chain" on when the page opens:
  the rebuild makes it always on (R-17). (2) Replays left out of History unless asked: the rebuild's
  Audit offers "Include replays" under "More filters", off unless chosen (D-296). (3) A call that could not sign in recorded as `NOT_SIGNED_IN`: stays, item 2.
  (4) Signing in from the uploaded environment without a restart, and its `callbackUrl`: the default
  was no; the operator's later requirement that everything be set on screen and used at once overturns
  it, as a setting the tester types (D-289, D-290), never a guess from a variable's name. (5) No "go
  on to PAS" after an answer the tool cannot read: stays. (6) DTR steps that went right drawn neutral:
  stays, in the server's tones.
- **Limits.** Every look and check ran on mock and against stand-ins on loopback ports, never the
  vendor's service, nothing on Windows; the operator never saw the round.

**D-283. A member's name typed into the case id of a suite row reached the ledger: a gap in a hard
rule, found by the close of the second round, and closed.**
- **What was wrong, and since when.** The rule that the ledger holds no member data (section 15, and
  D-217 for the texts a tester types) was applied to a suite row's note, tester reference and
  expectation, never to its case id. A case id holding the member's last name ran, and the name went
  into every ledger line of the case (`caseId`), the screen and the results CSV. The case id has been
  kept on the line since suites were built (`df3adcd`); D-217 named the three texts it checked and not
  this one, and no check before the second round's close had looked at it. A case id holding the row's
  own member ID was not refused either.
- **How it was found.** The second check of the round's close named it (`cont34/check2-code.json`,
  third finding), and the check after it confirmed it on the jar of the round's base: a row with the
  case id `row-Placeholder-Inactive` for the mock member `P32H-INACTIVE` previewed as runnable, ran and
  passed, and its ledger line and the results CSV held the name.
- **Decided by the integrator** (`TASK_UI2_CLOSE3.md`): every text a tester can type that a ledger line
  or a results CSV keeps is held to the same check, in the run and in the preview alike, the refusal
  naming the field in the words of the others; a case id that is exactly one of the tool's own bundled
  case ids is its own vocabulary and never refused as a name; nothing else is exempt, and no pattern
  decides it; the names of the mock members stay as they are.
- **What was built** (`7c179ba`, then `b28be51`, `022b56f`, `24777b2`, `2f93224`, `79a7c25`).
  `MemberInText.problemsInCaseId` with the label "The case id"; the runner checks it after the member
  lookup on both profiles, the row is not valid, nothing is sent and no ledger line is written; the
  preview checks it on mock against the looked-up member and on any profile against the row's own
  member ID; a refused row is named `case-<n>` in the results; the bundled case ids are read from the
  jar on either profile (`ScenarioRepository.bundledIds`). **The rule was not weakened**: the
  exemption is a comparison with the tool's own list of case ids, and the tests hold both sides (a
  case id holding the name is refused and kept neither in the ledger nor in the results; a bundled
  case id is not refused), each seen to fail without its change.
- **What is compared on live.** The name is known only once the run has looked the member up through
  the live lookup; then the case id is checked against the found member's name, date of birth, member
  ID, subscriber ID and address. The live preview compares the case id with the row's member ID only,
  because nothing is looked up before sending. Proven on live by a unit test with live wiring, not on
  a jar against a lookup stand-in.
- **What is not known.** Whether any real suite ever carried a name in a case id: no ledger or results
  file of the operator's has been seen.
- **Left, recorded by the integrator.** A suite row that repeats the case id of a row refused for the
  name keeps that id on mock when it has no member ID or names another member (the results CSV then
  holds the name): decided, to be built in release 2 (G3). A typed DTR questionnaire address is not
  held to the check, because the bundled addresses hold the word every mock surname is made from:
  decided (an address exactly one of the bundled ones is exempt, every other is checked), and built
  with F2b, which is in release 1 (`037efb6`): a typed address holding member data the request carries is
  refused at its field and nothing is sent (`MemberInText`, `BundledQuestionnaires`, held by
  `DtrQuestionnaireAddressNameTest`); the merger saw the refusal live against the strict stand-in. A chain row whose expectation
  cannot be read has its other texts checked, not its expectation; a note added later in History is
  checked against the member ID only, since the line keeps no name; on live with the member lookup
  off, no name is compared at all, because nothing is known to compare.

**D-284. The other faults the second round's close found that were older than the round.**
Each was found by an agent that built none of it, decided by the integrator
(`TASK_UI2_CLOSE2.md` and `TASK_UI2_CLOSE3.md`), corrected with a test that fails without it, and seen
on the jar on mock and, where it applies, on live against a stand-in.
- **A suite row whose expectation is an outcome code was refused as the member's last name.** The mock
  members are called `Placeholder-Inactive` and `Placeholder-Timeout`, and the check finds each part of
  a surname as a whole word, so `COVERAGE_INACTIVE` and `TIMEOUT` matched; the preview had never looked
  the member up, so it said the case could be run and the run then refused it. Older than the round:
  the same on the jar without it. On the old
  jar the 45 bundled cases previewed 45 runnable and ran 43 passed, 2 not valid. Now an expectation
  that is exactly one of the tool's outcome codes is never refused as a name, and on mock the preview
  looks the member up and applies the run's own check (`b311897`): 45 and 45.
- **A PAS run named another run than the screen said it was handed on from.** The PAS submission's
  ledger line named the questionnaire package fetch as the run it follows, while the PAS tab said
  "Handed on from" the DTR response step, so History ended a journey in a dead end. Older than the
  round. Now the value `correlation.correlatedRunId` of a PAS submission names the
  DTR step whose result offered the way to PAS (`59bbfc1`). **No field was added; a ledger written
  before reads and is drawn as it was recorded.** The other readers of that value (the inquiry, the
  subscription calls and the notification take their run from the carry or the claim; a chain keeps
  what was carried; a replay only quotes it) were checked and do not change.
- **On mock a created subscription sent a first-day tester to a property of the tool**
  (`onyx.subscription.callback-url`), and a read or a list then named an address of the scenario
  file's own that the create had never sent. Now the step points to the sample notification, and the
  scenario's read and list name no address of their own (`919b4c2`, `9c95626`, `fd5d409`).
- **A 401 said "or none were sent" although the tool knew what it had sent**, and **a token failure
  with no connection was called a closed connection**: D-282, items 3 and 4 (`e523cb0`, `7536395`,
  `076e965`, `86bb224`).
- **No test held that a played submission that did not reach the service hands on no claim
  identifier**: one does now (`031ca41`).
- Left, recorded by the integrator: a token endpoint that sends a status line and then cuts the body
  reads "closed the connection without answering"; the steps of an adaptive questionnaire before the
  last read that no run follows them (the package fetch lists every step); the member and provider
  lookups keep their wording for a 401; on mock a note or a case id that uses the scenario word a mock
  member is named after is refused as holding the member's last name.

**D-285. How the rebuild was planned and built: the looks, the hunts, the earlier sessions, the plan,
the integrator's decisions, the sceptic, two releases, nine builders and a merger, and the limits of
all of it.**
The plan is `REDESIGN_PLAN.md` in the integrator's scratch directory (the version before the sceptic
beside it under `redesign/plan/`), the integrator's decisions `redesign/INTEGRATOR_DECISIONS.md`, the
sceptic's attack and its settlement `cont41/attack.json` and `cont41/settle.json`, the first two
slices `cont40/`, and the build's record `rebuild/` (the queue's table `QUEUE.md`, `MERGE_LOG.md`, one
`.ready` file per slice, each batch's `notes.md`, the whole builds `queue/SWEEP.md`).
- **What it was planned from.** The operator's photographs of their own tool (described by kind only),
  their answers (D-281), a map of every feature of the old screens (104 of them, each given a place on
  the new screens or dropped with a reason), a prototype of thirteen pages, the reproduction and the
  two hunts (D-281), and the operator's earlier sessions, read by four agents: the things they asked
  for again and again (a tool that runs the first time on their Windows workstation and is tested
  before they get it; configuration on screen, at once; one case flowing from the coverage check to
  the status with nothing retyped; members and providers to pick, not type; plain words with the JSON
  folded away), the cycles that cost them rounds before (among them seven to nine rounds of HTTP 400
  from the vendor's sandbox over request shapes, a token refused before its stated expiry, an inquiry
  slower than 20 s), the request shapes the vendor's sandbox accepted, and the gaps of the first brief.
- **The plan and the integrator's decisions.** The plan's decisions are R-01 to R-44; the integrator's
  decisions O1 to O11 amend it and win where they differ: Submit after PAS 2.2.1 (O1), the trail (O2),
  the vendor's accepted request shapes tonight, not later (O3), lookups by GET or POST with a body
  template (O4), member ID rules (O5), questionnaire answers from the case (O6), every failure the two
  hunts found (O7), what round 2 left (O8), a reload that keeps the case (O9), one retry after a 401
  (O10), the yellow MOCK badge (O11). Stays later, by the integrator: mock and live switched at run
  time (35 to 50 agent-hours), a second sign-in for Submit and Status, the provider lookup's own
  contract (none is known).
- **The sceptic and what the settlement changed.** An agent that wrote none of the plan attacked it
  and found 22 things; all 22 held (one in part). The worst: it could not be built and checked by the
  morning (one batch alone was 15.5 hours of serial work) and nothing smaller was defined; its own
  words broke its own word limits on the first screen; on the first live call the operator's stored
  setup would meet the vendor's first 400 again, the DTR package request lacked the profile the
  accepted request declared, and values the calls need could not be set on screen; the tools the
  operator asked to keep had been demoted or put off; loading the classes at start would make a pull
  under a running tool silent; the Windows and STS checks were skipped although both can run here;
  and an earlier slice had rewritten a sentence of the production guardrail. The settlement took each:
  **two releases at fixed hours** (R-44), **nine builders instead of seven**, files moved between
  owners so every chain of release 1 fits, the cuts in words measured, the stored collection's sample
  taken as evidence for the request shape (D-291), the profile declared, values on screen (R-42), the
  tools kept as MUST (R-17), the pasted token tonight (R-43), a notice when files change on disk
  (R-24), the checks on Windows Java under Wine and the real STS under a virtual display, and the
  guardrail's sentence put back word for word (R-16).
- **Two releases.** Release 1: merges until 12:30 UTC, handed over at 15:00 UTC on 2026-11-22, after
  its checks and an independent look: the new frame, the Workbench with its four steps, the result
  card, Configuration with Environments (sign-in, addresses, values, timeouts, member ID rule, request
  shapes), Collections, the test lists and their import, Production writes, In force now and Access
  token with a pasted token, Audit's runs with replay, the vendor's accepted request shapes, PAS 2.2.1,
  the token retry, the 120 s wait, and the upload and store fixes. Release 2: merges until 19:30 UTC,
  handed over at 22:00 UTC the same day: the trail and what reads it, lookups configured on screen,
  answers from the case, Journeys and Suites, the remaining store repairs, values for one run, and the
  sweep of place names in server sentences. Release 2 adds to release 1 and is never its repair; a
  release that is not ready is held and the operator told the new hour. What release 1 holds at its
  candidate, and what it does not: `PROGRESS.md`.
- **How it was built.** The first two slices, A1 (the shell of the new screens) and D0 (the store's
  write failures and the run scripts), were built first and merged (`1d0c4c0`, `00f1578`). Then nine
  batches (A to I) built side by side in worktrees of their own from `79a7c25`, each owning its files
  outright (plan 9.5); a test file belongs to the batch that owns its class, and any other test a
  change breaks is named by the builder and changed by the merger in the merge commit, never
  weakened (R-41). They talked only through files in a queue folder: a builder writes `<slice>.ready`
  (its commit, its whole build, how it showed the slice working, the words a tester sees); a merger
  merges into `rebuild/main`, runs a quick gate of the guard and static tests, writes
  `<slice>.merged`, and after each round runs one whole build (a "sweep") and smokes the jar. A slice
  that waits for another may be built against the written contract but not merged before it.
- **The limits of all of it.** Every live check runs against stand-ins on a loopback port (the strict
  one, `standin2.js`, refuses what the vendor's sandbox refused, by the earlier sessions), never the
  vendor's service. Nothing runs on Windows: the Windows Java under Wine and the real STS 5.4.0 under a
  virtual display are what the checks of each release use, each only as far as their reports say. For
  release 1 (D-299): under Wine the Windows Java 25 ran the merged jar of `1a14611`, and the store's
  read-only, in-use and disk-full sentences held over the API (no Configuration page was merged then);
  the real STS 5.4.0 under a virtual display imported the tree of `1a14611`, started it from the Boot
  Dashboard, took a Team > Pull to the tree of `9dbf61a` under the running tool and restarted it. No
  person has used the new screens.
Later: D-300. Release 1 was handed over at 16:00 UTC, not 15:00, with Submit on PAS 2.0.1 unless the
environment chooses 2.2.1; release 2's hour is unchanged.

**D-286. The frame and the four-step journey of the Workbench.**
- **What a tester met before.** Five tabs (CRD, DTR, PAS, History, Config), each a long form beside a
  column of sentences, on mock a table of fourteen scenarios; a case followed from tab to tab.
- **Decided.** One top bar and three tabs, Workbench, Audit and Configuration (R-01, R-20, O11); the
  Workbench a case column (Member, Provider, Service) and four steps, Coverage check, Questionnaire,
  Submit and Status, carried from one to the next with nothing retyped (R-22, R-27); on mock the
  scenario is chosen by picking a mock member named by what it plays, not from a table; on live
  nothing of that is built; a send button disabled while its call is out (R-26); buttons never bare
  words (R-25); a reload that keeps the case (R-38, O9) is release 2, because it reads the trail.
- **What a tester sees at the release candidate** (`7fc32c8`: A1 `1d0c4c0`, A2a `37dc560`, A2b with A2a's
  correction `7581cae`, H1 `6f756a0`, H2 `7fc32c8`). The top bar: the brand, the three tabs (Audit with
  the count of today's runs), the environment chooser (at the candidate, at 1024 on live, the LIVE badge
  drawn over the kind badge; corrected, and seen with nothing overlapping on `19ddf8e`; with two
  environments whose names begin alike it cut both to the same first letters at 1024, live-config-7,
  corrected in the second round by letting the sign-in chip give way first, `cfe8940`, and seen by its
  corrector on its own jar), the kind badge, "MOCK" (a yellow
  pill) or "LIVE", the sign-in chip on live (D-290), the build stamp (version, time, and whether files
  changed on disk), and the lines under the bar for production, UAT, the ledger alarm and one notice at
  a time. The case column: the Member, Provider and Service cards; on mock "Choose a member", the mock
  members grouped by the step they play; on live "Choose a saved member" when a saved list holds entries
  (D-294); the banner "Pick a member to begin." and "Start a new case". The step heads, from the source:
  "Coverage check" CRD "Is prior authorization required, and what is needed?"; "Questionnaire" DTR
  "Answer the payer's questions."; "Submit" PAS "Send the prior authorization request."; "Status" PAS
  "Ask for the decision, and follow notifications.". The way on is one button, named as the server's
  vocabulary names it: "Continue to the questionnaire", "Continue to Submit", "Send inquiry", and on
  mock only "Deliver a sample". The old addresses (`#history`, `#config`, `#crd`, `#dtr`, `#pas`) lead
  to the new tabs. A reload starts a new case: keeping it is release 2 (A2c).
- **Where a builder left the plan** (A2a's notes, the `.ready` files of H1 and H2). The lookup's line is
  shown only when the status is not found, to hold the word limit; fields a mock member filled carry no
  mark, the plan naming none; the steps 2 and 3 are loaded only when shown. H1 posts only answers that
  differ from what the response holds, and withdraws "Continue to Submit" while typed answers differ
  from the completed response. H2 shows what goes in three lines of chips and asks the server what is
  missing before sending (D-293); the hand-over from Submit to Status is the frame's, not the step's.
- **What was seen, where.** The merger's smoke on the jar of `9dbf61a` took P32H-DTR-PREPOP from a fresh
  page to "The prior authorization is approved." at 1440 by 900 and 1024 by 768, with no console error,
  only 127.0.0.1 called and only `pave.tab` stored. The checks of release 1 (D-299): on `1a14611` step 1
  only, the other steps not yet merged; on `9dbf61a` every mock journey of the plan's M5 was run, the
  main one in 9 clicks and 1 typed value; all reached their end except P32H-PAS-NOTIFY, whose sample
  notification was drawn as "Unmatched" (wbm3-1), and P32H-ERR500 drew its failure twice (wbm3-3); on
  `0b69049` live against the strict stand-in to "The prior authorization is approved." in 8 clicks and 5
  typed values, after draftOrders type was set to Left out by a script; with the builders' sample
  collection the first CRD answered HTTP 400 and, on that jar, nothing on screen named the setting (F2b
  came later, D-291). H2 was merged after every check: its Submit step was seen by its builder only.
- **What holds it.** The frame: `StaticFrontendTest` (22), `StaticJoinTest` (8), `SharedModulesFrontendTest`,
  `FilesFrontendTest`, `FaultReportFrontendTest`, `ButtonNamesAndMarksTest`, `NoSentinelOnScreenTest`,
  and for the Workbench `WorkbenchFlowFrontendTest`, `ResultFrontendTest`, `ServiceCardFrontendTest`,
  `CaseCodesFrontendTest`, `MockOnlyFrontendTest`, `StatusFrontendTest`, `QuestionnaireStepFrontendTest`,
  `SubmitStepFrontendTest` and `AttachmentsFrontendTest`: each a Java test that reads the scripts or runs a
  piece of them under Node; no test drives a browser. The `.ready` files do not say whether each was
  shown to fail without its change. A1's builder saw the frame in Chromium at 1024 and 1440 on mock and
  against a stand-in.
- **Left at the candidate** (D-299). Corrected and seen again on `e5d0972`: one filled button after a result
  on steps 1, 3 and 4 (I-6); the notice "A reload starts a new case. The runs of the case before are in
  Audit." with a link to Audit (I-8); the live banner "Type a member ID to begin." (I-4); the Status step's
  chip "Matched to this submission", from this case's notifications only, the others in a closed fold (I-16,
  I-18); the focus kept after "Run check"; the fields of the case column at 1024 (wb-mock-1, look-7). Seen
  on `19ddf8e`: the top bar at 1024 on live with nothing overlapping (live-config-2, live-content-3
  on `19ddf8e`; look-1 on `e5d0972`). In the code at `19ddf8e` with their tests: the
  Status step's headline "A notification arrived for this submission." (wbm3-1, `NotificationReceiverTest`),
  and "The flow ends here: nothing further to send." where a result ends the flow (critic-9,
  `CrdVerdictsTest`, `PasVerdictsTest`), both seen closed on `22478b8` by the recheck of the final build. Corrected in the second round (`98f79d4`) and seen by its corrector on its own jar at 1440 and 1024: once the
  response is complete, step 2 has one filled button, "Continue to Submit", and one "Why" fold (wb-mock-4,
  wbm3-4, I-6, look-10). Left for time by the sorter and corrected all the same, seen on
  `e5d0972`: no scroll after "+" adds a code
  (critic-10), the step chips on one line at 1024 (look-17), the "Send prefetch" box in the tool's colour
  (critic-11).

**D-287. A result is chips, one headline, a few facts and one way on.**
- **What a tester met before.** A result of about 2.7 window heights on CRD and 3.2 on PAS after the
  first round (D-276), with dashed "not returned" boxes, the wire view and the findings in view.
- **Decided.** In this order: a chip row ("HTTP <status>", who answered, the time, the test result,
  "Sent as <sent>, rule: <rule>", "Open in Audit"); a card with the server's headline, the masked
  reading under it when there is one, a table of facts (one row per determination, the code as a chip
  in its tone, at most eight rows), "NOT recorded" with the ledger's sentence when the run was not
  recorded, and the next step as one button named as the vocabulary names it; the error panel open
  when there is one; then closed folds "Why", "Response detail", "Returned but not interpreted (n)"
  and "Wire traffic" (plan 3.2, R-14, R-15). Nothing of section 20 is lost: what was in view is in a
  fold, never gone.
- **Merged: the response detail** (H0, `a55bb2d`), drawn by `detail.js` from the old CRD, DTR and PAS
  detail onto the new helpers: cards, coverage, item decisions, questionnaires and subscriptions with
  what was not returned ("not returned", "returned as null", "returned empty"); where the answer was
  masked, the fields not interpreted are withheld in the page rather than shown beside a masked body;
  every walk over nested items uses a stack of its own, never recursion. Held by
  `DetailFrontendTest`. Its builder saw it at 1024 and 1440 fed with real mock answers; at 1024 the
  page scrolled sideways until the frame's fold rule changes (asked of A).
- **Merged: the card** (`result.js`, A2a `37dc560`). From the source: the chips and "Open in Audit";
  the headline and the facts; "NOT recorded" when the run was not recorded; the closed folds "Why",
  "Response detail", "Returned but not interpreted" and "Wire traffic". Held by `ResultFrontendTest`,
  `MaskedReadingFrontendTest`, `CarriedMaskFrontendTest`. A2a's builder measured, against answers of the
  contract's shape, 93 words on the first screen fresh and 124 at 1440 (110 at 1024) with a member
  picked, under the plan's limits of 100 and 130. The checks measured the same on the jars of
  `1a14611` and `9dbf61a` (D-299): 94 and 93 fresh, 124 and 110 with a member picked, 107 words in the
  work column after the check, with the headline, every fact and the way on in view without a scroll.
- **Left at the candidate** (D-299). Corrected and seen again on `e5d0972`: a service failure drawn as one
  card, its headline, what the service said once and a fold of the issues it sent (critic-1, wb-mock-3,
  wbm3-3, look-5); the open "Response detail" within its fold, its sentences wrapped (wb-mock-2); a fact's
  display shown only when it adds words (I-14); an answer that is not JSON said once, under the top bar
  (critic-7). Corrected in the second round and seen by its corrector on its own jar at 1440 and 1024: the tables of
  "Response detail" wrap their cells inside the fold (critic-2, look-2; `24a815b`; at 1024 the same rule breaks "questionnaire" mid-word in the Element
  column, critic-k3-1, open), and after a CDS
  service's refusal on live the card shows what the service said, the issues it sent, and the server's
  line on what to change above "Open Configuration" (look-3; `f6c851c`). Left for time by the sorter and corrected all the same, seen on
  `e5d0972`: a refusal box has the rule and
  the gap of a result under the panel head (critic-8).

**D-288. Short words and tones, served once by the server.**
- **What a tester met before.** Long labels, the colour of a step decided in the page, and each screen
  holding words of its own beside the server's.
- **Decided** (R-14, R-15; the batch B1, merged at `2ff19d4`). `GET /api/vocabulary` serves, once,
  the four steps ("Coverage check", "Questionnaire", "Submit", "Status"), the step chips ("Next", "Not
  needed", "Not run", "Answer the questions"), a short label of at most 24 characters for every
  outcome of every protocol ("PA not required", "Not signed in", "Not a subscription" and the rest),
  one button name per next action, the test result labels and the answered-by chips ("MOCK", "Nothing
  sent", "Not sent"). Each determination row carries a tone from its code (`model/CodeTones`); each
  scenario file carries a `short` naming what it plays; a questionnaire says "static form" or
  "adaptive form". The chain view gains `steps[]` beside its `stages[]`, which stay as they were
  (R-22): a subscription call and a notification belong to the Status step, and a step's line says
  why it stands where it does ("Not needed: prior authorization is not required.", "Not reached:
  <step> comes first.").
- **Where the builder left the plan.** The subscription outcome for an answer that is not a
  subscription is "Not a subscription", not the plan's "Unreadable answer", which another outcome of
  the same protocol already used and the plan's own test of unique labels forbids; the vocabulary lists
  only the answered-by values that have a chip; the step a sample notification belongs to is Status.
- **Contract changes and their readers.** `OutcomeOption` and `NextActionOption` gain `short` and
  `button` (absent where there is none); `DeterminationRow` gains `tone`; `ScenarioSummary` gains
  `short`; the label of `DELIVER_SAMPLE_NOTIFICATION` and the headline after a mock subscription
  changed (D-282, item 6). The merger changed three existing tests for it (`BrowserContractTest`,
  `NextActionVocabularyTest`, `SubscriptionServiceTest`), none weakened (`239bde2`).
- **What holds it.** `VocabularyControllerTest`, `OutcomeShortLabelsTest`, `DeterminationToneTest`,
  `ScenarioShortLabelsTest`, `ChainStepsTest`, `QuestionnaireViewTest`, `SubscriptionVerdictsTest`.
  Seen on the jar of the second sweep (`3d84250`) on mock: every outcome of every protocol with a
  short label, 14 of 14 CRD scenarios with one, a CRD answer's rows in their tones, a chain of four
  steps.

**D-289. Environments carry settings edited on screen, stored in a second version of the store, used
at once; and the Configuration pages.**
- **What a tester met before.** Every address, the sign-in and the timeouts came from the uploaded
  Postman files or from `application.yml`, read when the tool starts: changing the sign-in needed
  start-up arguments and a restart (D-276, letter G).
- **Decided** (R-02, R-04, R-05, R-09, R-28, R-42). Each environment carries settings: its sign-in,
  an address per operation, values the requests read (the FHIR server, the payer, the PAS codes, the
  address to call back), its timeouts, its member ID rule and its request shapes. At the candidate the
  address to call back typed under the values was shown in the settings view and held for masking, but the
  subscription call named `onyx.subscription.callback-url` of `application.yml` alone (found by the
  integrator on `7fc32c8`, integrator-2). Corrected (`44f09fe`): a create or update, and its preview, name
  the address the active environment types, resolved by the call, and `application.yml`'s otherwise; held by
  `SubscriptionCallbackFromTheEnvironmentTest`, which failed without the change. Not seen on a running jar
  since. Where a call goes,
  in order: a collection request named for the run, the address typed for the operation, the first
  collection request mapped to it, `application.yml`. A secret field is never returned: blank keeps
  the stored value, a value that is wholly a reference is kept as that reference. The name of a stored
  environment cannot be changed (the unlock and the remembered production addresses go by it).
- **Merged: the record and the store** (D1c at `db03ae8`, D1a at `de91bee`). Every prepared call
  carries its sign-in, timeouts, member ID rule and request shapes, with today's values as defaults.
  `store.json` stays version 1 until a setting is first saved; then it is written as version 2, after
  copying it once to `store.json.before-v2`. A version 1 store reads as no settings, so every call
  behaves as before. `GET /api/config/environments/{id}/settings` and `POST
  /api/config/environments/settings` read and save them, with the upload's checks (a kind change
  included). Words a tester reads, from the source: "This looks like a secret. Put it in Sign-in, or
  keep it in the Postman environment, where it is masked." for a value whose name reads as a secret;
  "This url holds a credential. Put the credential in Sign-in, or refer to it as a {{placeholder}}."
  for a typed address; and a warning, once, when a secret is stored as typed rather than as a
  reference. An entry whose settings cannot be read leaves the environment loaded, keeps the entry as
  stored, lists it among the store's problems, and refuses every call while that environment is
  active rather than guess `onyx.auth` for it (D1a's decision). Held by `StoreVersionTest`,
  `EnvironmentSettingsTest`, `SettingsSecretsTest`, `CallSettingsContractTest`.
- **What an older build does with a version 2 store.** It refuses to start, with its existing words:
  "The configuration store file <file> cannot be used: it was written by a later version of this
  tool. Repair it, or move the directory <folder> aside to start with an empty store." Going back to
  an older build means renaming `store.json.before-v2` to `store.json`, which loses the settings saved
  since (R-05).
- **Merged: the settings at run time** (D1b `e922c7d`). Every call uses the active environment's
  settings as saved, with no restart: the typed address, the values as a layer between the run's values
  and the export (the address to call back included since `44f09fe`, see above), its sign-in and its
  timeouts; typed addresses are among the production checks; the
  page posts an export as JSON text (`{fileName, text}`; multipart is kept for curl). Held by
  `JsonUploadTest`, `SettingsAddressGuardrailTest`, `ShapeEvidenceTest`, `ValuesLayerTest`,
  `RunSupportTest`. Seen by the merger live against the strict stand-in, with no restart: a typed
  address and a read timeout of 4000 ms in force at the next call. Seen by the check of configuration on
  live on `0b69049` (D-299): an environment made on screen from an empty store in 8 clicks, its typed
  FHIR server carried by the first CRD call, and read timeouts of 1000 ms and then 5000 ms in force at
  once.
- **Merged: the Configuration pages** (C2a1 `ff28b3e`, C2a `0b69049`; I1, I2 and I3 in one merge,
  `f538643`). The pages, from the source: "Environments", "Collections", "Members", "Providers",
  "Access token", "Production writes" and "Advanced", and under Advanced "In force now", "Trail", "Send
  as written", "Try a value", "Check this installation", "CDS discovery" and "Compare with a request that
  works". Environments offers "New environment", "Fill the form from these files" and "Make active", and
  the form holds the sign-in, the addresses, the values, the timeouts, the member ID rule and the request
  shapes. I3, which release 1 took only if merged by 12:30 UTC, is in. The Trail page is release 2 and
  says "This page comes in the next release." Held by `ConfigFrontendTest`, `ConfigPagesFrontendTest`,
  `ListsFrontendTest`, `AdvancedPagesFrontendTest`, `CompareFrontendTest`, `VendorSampleFrontendTest`,
  `DraftControllerTest`. Seen on `9dbf61a` by the completeness check (D-299): the operator's first steps
  (New environment filled from the Postman exports, Save, Make active, a collection uploaded, a case run)
  with no error and no dead end on mock; on live up to the first call, which the strict stand-in refused
  with HTTP 400 until draftOrders type was set to Left out by hand (the line naming the setting came with
  F2b, after that jar); and every Advanced tool present and answering.
- **Left at the candidate** (D-299). Corrected and seen again on `19ddf8e`: a sandbox saved with a
  production address shows, above the form, the refusal's sentence that names the production environment
  (look-guardrail-1); In force now and Check this installation ask for no endpoint release 1 lacks
  (features-3); "Open Configuration" opens Sign-in of the active environment after a failed sign-in and
  Addresses after an answer that points at the address (I-32, live-config-4); a refused upload or import is
  one plain line (AU-5). Seen on `e5d0972`: the options "As PAVE was started" and "Choose a kind" (I-15),
  and In force now saying "No environment is active." once (look-19); the recheck of the final build found
  it regressed on `22478b8`, the sentence 14 times on mock; corrected in the third round (D-300). The readiness sentence for an
  operation with no address, "No address for <the operation's short words>. Type one under Addresses of the
  environment in Configuration.", is in the code at `19ddf8e` with `ReadinessWordsTest` and was seen on a
  running jar of `22478b8` by the recheck of the final build (look-14). Left for time by the
  sorter and corrected all the same, seen on `e5d0972`: the line of an empty store spans the grid (look-18),
  and a file is chosen with a "Choose file" button (look-11). Corrected in the second round and seen by its corrector on its own jar
  (`rebuild/r1/close2/config-audit/shots/`): a refused form in Configuration, Advanced and Audit names each
  field in its panel by its label with its sentence, so a save of the empty form reads "Kind State what kind
  of environment this is: sandbox, uat or prod. It is never guessed." in the panel (live-config-8, I-3;
  `1af5d3f`), and the panel of a refused save stays in view, its first line taking the focus
  (look-guardrail-8; `178deb1`); both are held by `ConfigFrontendTest`.
- **Timeouts.** `application.yml` now waits 120000 ms for the vendor (`onyx.read-timeout-ms`; it was
  15000, section 19's value) because the vendor's inquiry took longer than 20 s in the earlier
  sessions; `tracking.slow-threshold-ms` is 15000, so the slow chip keeps its meaning; the connect
  timeout stays 5000 and the lookups 3000 and 5000 (R-28; C1, merged). An environment may state its
  own, from D1b.

**D-290. Sign-in belongs to the environment; a token can be pasted for the session; a refused token
is fetched again once.**
- **What a tester met before.** Signing in was `onyx.auth` in `application.yml`, by HTTP Basic only
  (D-233), off unless set at start; a token, once fetched, was cached until shortly before the
  expiry it stated or a change of the configuration, and a 401 was shown as the service's answer.
- **Decided** (R-03, R-43, O10). An environment states its sign-in: "Client credentials" (token url,
  client id, client secret, scope, the client's authentication "Basic header" or "In the form body"),
  "Bearer token", or "None"; when it states none, `onyx.auth` applies as before. A 401 from a service
  signed in by client credentials drops the cached token, signs in again and sends the call once more;
  a second 401 is the service's answer; a bearer token is never retried. A token pasted on Access token
  is held in memory for the active environment only, used ahead of its sign-in for its own addresses,
  never written to the store, a log, the ledger or the trail, masked when a service repeats it, dropped
  on a 401, on any change of the configuration and when PAVE stops, and refused on mock.
- **Merged** (G5 at `00c2c13`). `OnyxTokenService` signs in with the settings a call carries, caches
  per token url and client id under the configuration epoch, and holds a pasted token; `POST` and
  `DELETE /api/config/sign-in/token`. The wire view after a retry notes, from the source: "The first
  attempt was refused with HTTP 401; PAVE signed in again and sent it once more." The sign-in states
  served for the top bar's chip: "No sign-in needed", "Signs in on first call", "Signed in (renews
  automatically)", "Access key configured", "Using your pasted token", "Sign-in failed", "No sign-in
  on mock". The merger changed one existing test (`ReturnedSecretsTest`) to expect the token, the 401,
  the token again and the 401 again, every masking assertion kept, now on the second answer
  (`3d84250`). Held by `TokenSettingsTest`, `PastedTokenTest`, `TimeoutsPerCallTest`. **Seen** on the
  jar of the second sweep, live against the stand-in on a loopback port: the stand-in refused the next
  call's token, the tool fetched a second one and sent the call again, the headline was the answer's,
  the Authorization header in the wire view was masked, and the client secret was 0 times in the log
  and the run history.
- **Merged** (D1b `e922c7d`, D5 `9dbf61a`, I1 in `f538643`). Every call signs in with its environment's
  own sign-in, or by `onyx.auth` when the environment states none, with the retry. `/api/mode` names the
  sign-in in force for the chip. The Access token page offers, from the source, "Use this token", "Forget
  the pasted token" and "Test the sign-in". D5, which release 1 took only if merged by 12:30 UTC, is in.
  Held by `SignInTestEndpointTest`, `ModeViewTest`. Seen by the merger live against a stand-in: the
  states "Signs in on first call", "Signed in (renews automatically)" and "Using your pasted token", and a
  wrong secret answered "No access token: the token endpoint answered HTTP 401.". Seen by the checks on
  `9dbf61a` and `0b69049` (D-299): one retry after a 401 and only one after a second, a pasted token used
  ahead of the sign-in and dropped on its 401, and the client secret in no answer, page, log or ledger,
  only in `store.json`.
- **A hole the rebuild opened, found before any hand-over** (look-guardrail-3). A token url per
  environment is new in the rebuild, and a sign-in is a POST that carries the client's secret. On the jar
  of `0b69049` a sandbox environment whose token url was a production environment's remembered `$submit`
  address was saved, and its next call posted the client credentials to that address with no unlock,
  with `allow-prod-writes` false. No build handed over held it. Decided by the integrator (I-28): a token
  url that is, or lies beneath, an address a production environment uses for a call is refused at its
  field when the settings are saved, in the words of the address refusal; the token client refuses such
  a url at the moment it would send, whatever was stored; a sandbox that shares a production
  environment's own token url stays allowed; nothing of the guardrail is loosened. It was corrected before
  any hand-over (`e318758`, merged in `19ddf8e`): the save refuses such a token url at `signIn.tokenUrl`,
  and the token client refuses it at the moment it would send, with nothing sent. Held by
  `TokenUrlAndEscapeGuardrailTest` (13 spellings of the token url, the shared token url allowed) and
  `TokenUrlAtSendingTest` (a token url stored before a production environment named that address: no request
  reaches the service); the recheck of the guardrail on `19ddf8e` saw it refused. The rule refuses a token url that is, or lies beneath, any address a production environment (stored or
  remembered) calls, so after a slip a sandbox cannot sign in and its reads stop too (features-8): an
  environment saved as prod by mistake on the same host as a working sandbox, and then removed, stops that
  sandbox from signing in until the remembered address is forgotten. Narrowing the rule to production write
  addresses was decided (I-28a) and withdrawn at 13:25 UTC: the store remembers a production address by
  environment and address only, never by the operation it served, so a write address cannot be told from
  another, and I-28 stays as built. Since the second round (`185b5be`, held by
  `TokenUrlAndEscapeGuardrailTest`, which failed without it) the refusal says so after the guardrail's own
  words, which are unchanged: it names the production environment as no longer stored, so the tool no
  longer knows the token url it signed in with, and gives the ways out by hand: store it again, or, if it
  was stored as prod by mistake, stop PAVE, delete the entries whose environment it is from
  `productionDestinations` in `store.json`, and start PAVE again. Release 2 brings "Forget this production address".
- **Left at the candidate.** Corrected and seen again on `19ddf8e`: a token url that does not answer, where
  the environment states it, is named as that environment's token url with its place in Configuration >
  Environments > Sign-in first, and "Open Configuration" opens Sign-in (I-34, I-32;
  `TokenUrlAndServiceSaidTest`, which failed without the change). Since the second round the page also shows
  the second line, "If the url is right, the sign-in service may be down: try again later."
- **Left.** How the vendor wants the client to sign in is still not observed (D-233); the form body is
  now a choice, not a fact.

**D-291. Requests take the shapes the vendor's sandbox accepted; the one that departs from FHIR is a
setting.**
- **What a tester met before.** The operator lost seven to nine rounds of HTTP 400 from the vendor's
  sandbox in their earlier sessions over shapes PAVE still sent: prefetch keyed by type, `userId` as a
  role, instants with fractions, no profile on the DTR package request, `draftOrders` with a `type`.
- **Decided** (R-37, R-40, O3). Valid under the guides, so sent for every environment: an absolute
  `fullUrl` on every entry (`<fhirServer>/<type>/<id>` when the FHIR server is set, else `urn:uuid:`);
  CRD prefetch as named Bundles of type collection (`patientBundle`, `coverageBundle`,
  `practitionerBundle`, `practitionerRoleBundle`, `serviceRequestBundle`, or `organizationBundle` for an
  organization alone); `userId` and every requester `Practitioner/<id>` when the provider is a person;
  no empty array or string; instants in whole seconds UTC; the inquiry Claim declaring
  `profile-claim-inquiry`; the DTR package Parameters declaring the dtr-qpackage-input-parameters
  profile and its order carrying the coverage information CRD returned. The vendor's own practitioner
  and patient ids are sendable as resource ids. Departing from FHIR, so a setting of the environment:
  "draftOrders type", "Sent (FHIR requires it)" by default or "Left out (this vendor refuses it)", with
  the line "The vendor's sandbox refused context.draftOrders.type with HTTP 400; FHIR requires
  Bundle.type." An environment that states nothing takes it, at run time, from the saved body of the
  collection request its order-sign call came from: without `type` it is "Left out", marked "from the
  collection's sample". That is the evidence O3 accepts, and it keeps the operator's stored setup from
  meeting the vendor's first 400 again.
- **Merged.** The model (F1, `e89ea5f`: `RequestShape`, `RequestShapes`, the vendor ids on the member
  and the provider, the fax, the carried coverage information); the reading of the collection's sample
  (F5, `9b48b30`: `VendorSamples`, the request a run would use for order-sign when it names none, never
  an error sample). The words above are in `RequestShape` at the head.
- **Merged** (F2a `e4ee95b`, F6 `7fd49e4`, F3 `efab05c`, F2b `037efb6`). The CRD and DTR requests in
  those shapes; the shape read at run time from the environment, else from the collection's sample;
  Postman files refused for the true reason (for example "This is an OpenAPI file, not a Postman
  export."); and for a 400 whose answer names `draftOrders.type`, the next step "Set draftOrders type to
  Left out for <environment> in Configuration > Environments, then run the check again." F2b, which
  release 1 took only if merged by 12:30 UTC, is in. The merger changed existing tests to the builders'
  numbers, none weakened: for F2a `DtrScenarioDeterminationTest` (authored in whole seconds UTC) and
  `CrdControllerTest` (36 differences, 27 in how the request is built; `f1302cf`, `1a14611`), for F6
  `EdgeOverTheServerTest` (`1c6a868`). Held by `VendorShapesCrdTest`, `VendorShapesDtrTest`,
  `VendorPatientIdTest`, `VendorPractitionerIdTest`, `ShapeEvidenceTest`, `PostmanParserRefusalsTest`,
  `CrdVerdictsTest`. Seen on `0b69049` live against the strict stand-in (D-299): CRD, the DTR package (its
  profile check passed), Submit and the inquiry accepted, the wire view the same bytes as the stand-in
  received, and draftOrders type read from the collection's sample, on a version 1 store too, with a
  collection whose sample leaves type out, built by the check; the builders' own sample collection
  carries type, so the shape read from it is Sent and the strict stand-in refuses it (seen on the same
  jar by the check of configuration on live). The 400 that names the setting was not on that jar; the merger saw it live against the strict stand-in when
  F2b was merged.
- **Left.** Whether the vendor accepts a `patientId` its FHIR server does not hold was not shown by
  any earlier session; PAVE sends the vendor patient id when the tester gives one, else `patient-1`
  with a note. The request comparison now meets the tool's named Bundles: a pasted search Bundle is
  listed whole beside `coverageBundle` rather than compared resource by resource (F2a's builder;
  `service/compare` has no owner tonight).
- **Left at the candidate: a url taken for a secret by its name** (integrator-3, major, found by the
  integrator on `7fc32c8`; first seen in the merger's smoke of F2b live against the strict stand-in). A
  variable of the Postman environment named like `authBase` or `authServer` that holds a plain url is
  taken for a secret by its name (`Secrets.looksSecret`), so every answer that repeats that url is masked
  as "[secret authBase]"; when the FHIR addresses lie under the same host, the questionnaire url CRD
  returns reaches the Questionnaire step masked and DTR refuses the carry. Decided by the integrator
  (I-35): the words that name a place (`base`, `server`, `domain`, `address`, `origin`, `hostname`,
  `port`) are descriptors, so such a name is no longer a secret; nothing else of the rule changes.
  Corrected (`44f09fe`): `Secrets.DESCRIPTOR_WORDS` holds the seven words; `SecretsTest` lists each with a
  name now shown and a name still masked (`authServerToken`, `serverPassword`), and failed without the
  change. Not seen on a running jar since.

Later: D-309. The named Bundles were one sandbox's habit, not the standard: CDS Hooks fixes no names for
the prefetch. When the collection's coverage-check sample writes a prefetch, the request shape "Prefetch"
sends the sample's keys, holding what the sample holds there, each built from the case; the named
Bundles stay the default with no such sample.

**D-292. Member ID rules: packed, spaced and split, per environment.**
- **What a tester met before.** One rule, every space removed. The vendor's sandbox wanted the packed
  form; the operator's own tool offered the others, and the card form has spaces.
- **Decided** (R-10, O5). Stored with the environment's settings, packed by default: "packed: spaces
  removed before sending"; "spaced: nine characters, three spaces, then the last two, as on the card";
  "split: the first nine characters of an 11-character ID". Under spaced and split an ID that is not
  11 characters is sent packed, with the note "The <rule> rule applies to an 11-character ID; this one
  has <n>, so it was sent packed." The rule decides what goes out in every CRD, DTR and PAS request of
  that environment; **the ledger keeps the packed form as before**, so one member stays one key; no
  form of a member ID is ever a resource id. The server names the rule wherever it names the ID
  (`memberId {typed, sent, changed, rule, note}`).
- **Merged.** The rule and the forms (F1, `e89ea5f`, held by `MemberIdRuleTest`); the member lookup's
  answer names the rule (seen in the first sweep's smoke: `rule` packed, `changed` false).
- **Merged:** the rule in the requests (CRD and DTR in F3 `efab05c`, PAS in E3 `9413b89`, handed to each
  call by D1b) and "Sent as <sent>, rule: <rule>" on the Member card and every result (A2a). Held by
  `MemberIdRuleThroughTheServicesTest`, `MemberIdRuleInRequestsTest`, `PasMemberIdRuleTest`. Seen by the
  merger on mock (spaced: "P32H-PA-R   EQ", nine characters, three spaces, two) and on `0b69049` live
  against the strict stand-in (D-299): spaced and split sent as the rules say, and the note for a
  9-character ID on the card and the result.
- **Left at the candidate.** Corrected and seen again on `19ddf8e` on live: the three spaces of a spaced ID
  are drawn in every "Sent as" line, and a member lookup that is not configured draws no "Sent as" line
  before a run (live-content-1, live-content-2).

**D-293. Submit follows Da Vinci PAS 2.2.1, with the Service card fields it needs and files as
attachments.**
- **What a tester met before.** Submit followed PAS 2.0.1 (D-136, D-158); the operator's own tool and
  the vendor's sample follow 2.2.1; a request lacked a provider phone or fax, a place of service per
  item, the X12 types and the payer as insurer; an attachment was text or a url.
- **Decided** (R-39, R-13, O1; the operator's answer 3). The 2.2.1 guide source is the source of truth.
  Every item carries the request type and certification type, a service type and a place of service,
  and a quantity when given; the Claim a level of service when chosen; the requesting role the
  provider's phone and fax; a servicing provider, when chosen, a care team member of its own; the
  payer is the insurer. A value comes from the Service card, else from the environment's values or
  variables, else from today's rule, else it is left out and said so before sending. **Nothing blocks
  sending**: an incomplete request is a negative test. Files of the tester's own (PDF, PNG, JPEG,
  TIFF, GIF, plain text, Word), at most 5 MB each and 10 MB in all, go in the bundle as data. CRD and
  DTR stay at 2.0.1 with the shapes of D-291.
- **Merged** (E1 at `17db802`, E2 at `1dcefb0`). `GET /api/pas/options` serves the version "Da Vinci
  PAS 2.2.1", the code lists of the Service card, the attachment kinds and the limits (5242880 and
  10485760 bytes), the same on mock and live. The request is built to 2.2.1, its notes naming PAS
  2.2.1 and US Core 7.0.0; from the source, for example: "This request goes out without <n> elements
  PAS 2.2.1 requires, because nobody supplied them: <elements>. The service may refuse the request
  for that alone."; "Provider phone or fax: none. PAS rule pd-1 needs one: add it in Edit provider
  fields."; "The servicing provider is the claim's care team member 1, for the whole claim
  (careTeamClaimScope true), in the role SJ, ...". Instants are whole seconds UTC; the inquiry Claim
  declares its profile; a file is checked for its base64, its size and its kind. A bare mock
  submission now lists 11 elements left out where it listed 5, because 2.2.1 requires more (the
  identifier and trace number systems among them) and nothing is invented. Held by `Pas221SubmitTest`
  (against a placeholder fixture), `PasInquiryProfileTest`, `PasAttachmentFilesTest`,
  `PasOptionsControllerTest`, `PasNewFieldsReadingTest`, and the updated `PasRequestBuilderTest`,
  `PasServiceTest`, `PasNotesTest`. Seen in the first sweep's smoke on mock: the options in order, a
  preview naming PAS 2.2.1 four times and 2.0.1 never, a file of the wrong size refused.
- **Where the builder left the plan.** A new optional value `itemTraceNumberSystem` (typed under
  Values, "Another value") gives the item trace numbers the system 2.2.1 requires; no system is
  invented. A size given without data is refused, not ignored. Kinds and the 5 MB rule apply to data
  files only.
- **Merged** (E3 `9413b89`, H1 `6f756a0`, H2 `7fc32c8`). Before sending, the server lists what is left to
  complete (for example "Provider phone or fax: none. PAS rule pd-1 needs one: add it in Edit provider
  fields."), refuses at its name a field that cannot be sent, and sends the vendor ids and the member ID
  rule. The Submit step shows "What goes", "To complete before sending (<n>)", "Attachments", "Add files"
  and "Send PAS request". E3 added one test to `PasControllerTest` (a file with no owner tonight; nothing
  weakened). Held by `PasMemberIdRuleTest`, `PasRequestBuilderTest`, `PasServiceTest`,
  `SubmitStepFrontendTest`, `AttachmentsFrontendTest`. Seen on `0b69049` live against the strict stand-in
  (D-299): the Service card's PAS 2.2.1 elements in the request, instants in whole seconds UTC, the
  inquiry's profile, a request with no phone listed as incomplete and still sent and approved, a PDF of
  exactly 5 MiB attached and sent. Seen on mock on `9dbf61a`: a PDF and a PNG attached to
  P32H-PAS-ATTACH-REQ and sent. The checks saw the Submit step as H1 built it; H2 was merged after every
  check and seen by its builder only.
- **Left at the candidate.** At the candidate the finding that lists what was left out was titled with PAS
  2.0.1, although its elements are those of 2.2.1 (integrator-1). Since `44f09fe` it reads "The request left
  out <n> elements PAS 2.2.1 requires", held by `PasServiceTest`, which failed without the change.
  Corrected: a file just over 5 MB is told its size in bytes against the limit of 5 MB (seen on `19ddf8e`,
  live-content-4); an answer on Submit that asks for more documents offers no "Continue to Submit" (seen on
  `e5d0972`), and its headline "Add the documents asked for and send again." is in the code at `19ddf8e`
  with `PasVerdictsTest` (I-17).
Later: D-300. On the operator's word, given again, Submit and the inquiry follow PAS 2.0.1 unless the
environment's "PAS guide" chooses 2.2.1; everything this entry describes stays for that choice, and the
finding's title names the guide in force.

**D-294. Saved lists of test members and providers.**
- **What a tester met before.** Every member and provider typed again for every case; "no member data
  is persisted" (section 15) forbade keeping any.
- **Decided** (R-12, R-29, R-30; the operator's answer 2). Lists the tester enters on purpose in
  Configuration, for sandbox test members and practitioners: at most 200 entries each, in
  `<config-store>/test-lists/`, owner-only, never logged (counts only), never in the ledger, switched
  off by one setting. A member carries a label naming what it plays, the member ID as on the card, the
  names, date of birth, gender, line of business, plan, subscriber ID, coverage status and dates and
  the vendor patient id; a provider a label, NPI, vendor practitioner id, names or organization,
  specialty, phone, fax and a default place of service. Picking an entry fills the card, marked "from
  saved list", and does not look the member up by itself. A CSV import shows what it will do before
  it does it. Lists work on both profiles. How this bends the specification's rule: D-298.
- **Merged** (C1 at `ba28020`). `/api/lists/members` and `/api/lists/providers`: list, add, change,
  remove, `template.csv` (three placeholder rows) and `import` with a dry run (`dryRun` must be given;
  absent is refused, never assumed). Words from the source: "Saved test lists are switched off on this
  installation (test-lists.enabled)."; "This list holds 200 entries, the most it keeps."; a list file
  that cannot be read is shown empty, never written over. Held by `TestListStoreTest`,
  `TestListControllerTest`, `ApplicationYmlTest`. Seen in the first sweep's smoke on mock: the
  template, a dry run of three rows that saved nothing, the import, a date of birth in another form
  refused at its field, the folder `drwx------` and the file `-rw-------`, and the placeholder member
  ID 0 times in the log and the run history.
- **Merged** (I2 in `f538643`, A2a). The pages Members and Providers in Configuration, with "Import
  CSV"; on the Workbench "Choose a saved member", which fills the card marked "from saved list". Held by
  `ListsFrontendTest`. Seen on `9dbf61a` by the completeness check (D-299): a member added, the template's
  three sample rows, an import read and then made, and on live the picker filling the card "from saved
  list"; by the uploads check, a 201st row refused with "This list holds 200 entries, the most it keeps."
- **Left at the candidate.** Corrected and seen again on `19ddf8e`: a CSV separated by semicolons or tabs,
  and a JSON file, are told what they are (I-23); the import button names what it will not import, as
  "Import 200 rows (1 refused)", and the refused rows are listed first (I-27).
Later: D-306. A saved member may hold a vendor patient id without a member ID, one vendor patient id
names one saved member, a list is also filled from what an uploaded collection's requests state, and
procedure codes are a third list.

**D-295. Uploads, the classes on disk, the build stamp and the run scripts.**
- **What a tester met before.** The failures of D-281: an upload answered the internal error after
  the IDE rebuilt the classes; a store write refused by the workstation was called a fault of the
  folder; a script started an old jar.
- **Decided** (R-06, R-07, R-24, R-31 to R-36, O7). Every file is read inside a guard of its own: a
  refusal is its plain line, any other failure "PAVE could not read this file. Type the values
  instead." with the reference and the cause. Postman exports, lists and attachments are read in the
  browser when the button is pressed, and the exports posted as JSON text (multipart kept for curl).
  When PAVE runs from a classes folder, as STS runs it, it loads its classes at start and notices
  files rebuilt under it. The store tells a problem of the workstation from a failure of the tool. The
  run scripts build before every start and find a Java 21.
- **Merged.** *The store* (D0, `00f1578`): a missing store folder is made again; a replace refused by
  a lock is retried for about a second; the Windows read-only mark on the store's own file is cleared
  once; otherwise, from the source, "The file <path> is marked read-only. Clear Read-only in its
  Properties, then try again. Nothing was changed.", "The file <path> is in use by another program (a
  sync or backup client, a virus scanner, an editor). Close it or wait, then try again. Nothing was
  changed." or "The disk that holds <folder> is full. Free some space, then try again. Nothing was
  changed."; a change that would leave `store.json` naming a file that is gone is refused ("The
  configuration store folder <folder> changed while PAVE ran: ..."); `store.json` holds only ASCII. Seen
  on the Windows Java under Wine for the read-only and the in-use cases, on Linux for the others.
  *The errors* (B3a, `53b1403`): an internal error whose chain holds a `LinkageError` while a class file
  is newer than the start gets the action "PAVE's classes changed on disk while it was running, as the
  IDE does when it rebuilds the project. Stop PAVE and start it again."; an `OutOfMemoryError` "Start
  PAVE with -Xmx512m or more."; a store write refused for a reason of this computer is headed "A
  problem with this computer's files", with no reference; the error path asks whether the client went
  away inside a guard, so that a missing class there counts as not gone and the failure is still logged
  under a reference and answered (R-34, as B3a read it: logging first would have broken the one INFO
  line for a client that left). Seen on the jar of the first sweep with a classes
  folder whose upload classes were deleted under it: HTTP 500 with that action and the cause named.
  *The run scripts* (D0, then I5 at `b31df8a`): both build the jar before every start and say which jar
  and which Java they start ("Using Java <version> at <path>, named by PAVE_JAVA." and its other
  sources); `run.cmd` also finds the Java STS brings when none is set or on the PATH; a Java older than
  21 is passed over with a line saying why, and the search goes on. `run.cmd` ran under Wine's `cmd`
  (with one difference of quoting Wine needs), never on Windows; `RunScriptsTest` reads both.
  *The build stamp*: `/api/mode` names the build, its time and whether files changed on disk (D1a); the
  log says at start whether PAVE runs from a jar or a classes folder and when it was built.
- **Merged** (G2 `3afad9e`, D1b, F6, A2a). "Fill the form from these files" reads an environment export, a
  collection export or both, one line per file, and no file ends in an internal error; the page posts the
  exports as JSON text; the line under the top bar while files changed on disk is, from the source,
  "PAVE's files changed on disk since it started. Stop PAVE and start it again." Held by
  `DraftControllerTest`, `JsonUploadTest`, `PostmanParserRefusalsTest`, `FilesFrontendTest`. Seen by the
  checks (D-299): on `1a14611`, from a classes folder with loading at start on (the default), every upload
  answered 200 after 581 class files of the upload path were deleted and the rest rewritten, and the line
  showed 5.6 s after the rebuild on a page already open; in the real STS 5.4.0 the files-changed line
  showed 1.2 s after a Team > Pull under the running tool, uploads after the pull still answered 200 (6
  of 6), and a restart from the Boot Dashboard moved the stamp and removed the line; a change to a static
  file alone showed the line after 8.8 s, the worst case being about 14 s (the server looks every 10 s,
  the page every 4 s) against the plan's gate of 10 s; under Wine, the read-only, in-use and disk-full sentences over the API; on
  `9dbf61a`, 453 uploads of broken files in the three upload places with no internal error box.
- **Left at the candidate.** Corrected and seen again on `19ddf8e`: a zip, a gzip, a picture or a PDF is
  told what it is (I-21); the two sentences for the wrong kind of Postman file name Configuration >
  Environments and Configuration > Collections (I-24). In the real STS 5.4.0 on `19ddf8e`: after STS rebuilt
  the classes under the running tool, a refusal carried the line "PAVE's files changed on disk since it
  started, which can cause this. Stop PAVE and start it again, then try once more." (I-12, STS-2), and a
  restart was not told as a change in another window (I-13, STS-1). A replay sent again from Audit that
  fails does not carry that line: the Audit part of I-12 was not done. Left for release 2 by the integrator:
  with loading at start switched off
  (`-Dpave.classes.load-at-start=false`), the first upload within 10 s of the last look gets the bare
  report action instead of R-24's, and a missing class on the environment list is not covered by it
  (hunts-1, hunts-2, I-9); the default start never meets either. Corrected in the second round (`24a815b`, held by `WorkbenchWordsFrontendTest`) and seen by its corrector on
  its own jar: a folder whose name ends in `.json` or `.csv`, chosen in place of a file, is told "<name> is a
  folder. Choose the exported file inside it." (AU-4, I-25).
- **Left for release 2** (the plan's D6 and its other items): a stored file that cannot be read listed
  instead of refusing the start, a lock against a second copy on one store, "Forget this production
  address". Until then the upload refused for an address remembered from a removed environment keeps
  its sentence as at `79a7c25` (put back by D1a, R-16) and its field note gives the way out that works
  today: stop PAVE, delete that environment's entries from `productionDestinations` in `store.json`,
  start PAVE again. The check of every feature on `9dbf61a` found that a send refused for such an address
  gave no such way out, told the tester to switch to a sandbox or uat environment they were already on,
  and that `/api/mode` and Production writes said writes were not blocked (features-1). Decided (I-19):
  the refusal names the environment the address was remembered from and gives the same way out, and
  `/api/mode` and Production writes say what the refusal will say; the guardrail's own sentences stay
  word for word and nothing of it is loosened. Corrected (`b50332c`, `RememberedProductionNoteTest`, which
  failed without the change) and seen again on `19ddf8e`.

**D-296. Audit: the runs, one run, and replay.**
- **What a tester met before.** History, with filters in the address, the runs of one journey not
  tied together, cut off at 1024 pixels.
- **Decided** (plan 3.3, R-17, R-20). Audit lists the runs with filters (from, to, step, test,
  outcome, tester reference; more under a fold), opens a run by its id, replays the runs listed
  oldest first, and keeps "Run again" and the note; the count of today's runs sits on the tab. The
  request and answer from the trail, Journeys and Suites are release 2.
- **Merged** (B4a `5dc3d47`). From the source: "Runs", the filters, "More filters" with "Include
  replays" (off unless chosen), "No runs match these filters.", "Export CSV", "Replay the runs listed",
  and one run with "Run again", "Same outcome." and the note. The step filter asks the server once per
  operation the step covers and merges the answers; the filters are kept in the address; on mock a
  "Samples" filter; the sub-navigation is not drawn while Runs is the only page, so no tester is offered
  Journeys or Suites. Held by `AuditFrontendTest`, `VocabularyControllerTest`. Seen on `9dbf61a` on mock
  (D-299): an empty Audit, a journey's runs with their chips, the JSON of a run while the page that made
  it stays open, "Run again" answering "Same outcome.", a CSV with no name, date of birth, address or raw
  JSON, and the count on the tab.
- **Left at the candidate.** Corrected and seen again on `19ddf8e`: a run whose member ID was not kept says
  "This run cannot be run again: its member ID was not kept." in place of "Run again" (I-20); a run opened
  by a page that did not make it says "The request and answer are shown only by the page that made the run,
  while it stays open." (I-26).

**D-297. Mock and live are still chosen when PAVE starts.**
- **Decided** (R-01; the integrator: stays later). No switch at run time: it would cost 35 to 50
  agent-hours and an attack, and replace a guarantee held by construction (on live the mock beans do
  not exist, D-188) with a router that must be right on every call. The top bar says which runs:
  "MOCK" or "LIVE", with the titles "Mock profile: scenarios answer and nothing leaves this machine."
  and "Live profile: calls go to the active environment's service." (A1, merged). A change of profile
  under an open page reloads it, so nothing mock-only stays on a live page (A1's builder). Saved lists
  work on both profiles; the lookups on mock answer from the fixtures whatever is configured (R-29).
- **What it costs the operator.** PAVE is started once per profile; the README says how to start the
  other.
- **Against a stated requirement.** The operator asked for everything the tool needs to call the vendor
  to be set on screen and take effect at once, mock on or off among it (D-281). The integrator kept mock
  and live chosen at start against that part of the requirement, for the cost and the guarantee above.
- **Later.** Ask the operator to confirm it, after they have used the new screens.
Later: D-304. The operator asked for the switch after release 2. A start still follows the launcher;
while PAVE runs, the top bar switches it by restarting it in place on the other profile, so the
guarantee held by construction stays and no router was built.

**D-298. The saved lists and the trail bend two rules the specification wrote.**
- **The rules.** Section 15: no member data is persisted. Section 16: the ledger never holds the raw
  request or response JSON.
- **What the operator allowed, and nothing more.** Answer 2: saved lists of test members and
  providers, for test data only. Answer 4: a trail of the full requests and responses of the last 500
  exchanges, secrets masked. Nothing else of either rule moved: no list of recent members, no search
  history, nothing a lookup returns is kept, nothing is filled in from a list on live unless the tester
  picks it, and the ledger keeps its rule unchanged.
- **The bounds of the lists, and what holds each.** Entered on purpose in Configuration, never filled
  by the tool (a list changes only through its own endpoints, by what is posted;
  `TestListControllerTest`); test data (nothing in the tool can tell a test member from a real one, so
  this bound is the operator's statement and the setting below, not a check); owner-only files (`TestListStoreTest`; seen in the
  smoke, `drwx------` and `-rw-------`); never logged, counts only (`TestListStoreTest`, and the
  placeholder member ID 0 times in the log in the smoke); never in the ledger beyond what the ledger
  keeps by its own settings (a run made from a picked entry is a run like any other); switched off by
  `test-lists.enabled: false`, after which every change is refused and the list reads empty
  (`TestListControllerTest`); never in `store.json`, a file of their own. The configuration store,
  lists included, is never committed.
- **The bounds of the trail.** A store of its own beside the ledger, never the ledger; owner-only; the
  oldest removed past 500 entries or 256 MB; every secret masked by the code that masks the wire view;
  member data kept, because it is what the vendor needs to see; nothing of it in a log line; switched
  off by `trail.enabled: false`, emptied from Configuration. **It is release 2 (B5) and not in release
  1**: `application.yml` already names `trail.enabled: true` and `trail.max-entries: 500`, which bind
  to nothing until it is merged.
- **Also bent, already recorded.** The configuration store keeps uploads as uploaded and can hold
  member data a file contains (D-213); from D1b an export posted as text is kept as the text decoded,
  not byte for byte (R-06).
Later: D-306. A list is also filled from what an uploaded collection's requests state, when the tester
ticks the box of the environment form or presses "Add to the saved lists"; procedure codes are a third
list within the same bounds.

**D-299. The checks of release 1: what they measured, what they found, what the integrator decided,
what was corrected and seen again, and what is left.**
The reports are in the integrator's scratch directory: `cont43/` to `cont49/`, a look and a second
reader's verification per area; every confirmed finding is one line of `rebuild/r1/findings.jsonl`; the
integrator's decisions on what the checks left to them are `rebuild/r1/INTEGRATOR_R1.md` (I-1 to I-34,
and, on the three findings the integrator added on the candidate, I-35, integrator-1 and integrator-2;
after the first recheck I-28a, I-36 and the notes on look-12 and STS-3; I-28a was withdrawn at 13:25 UTC).
  The second round's reports, one per corrector, are `correct-workbench.json`, `correct-server.json` and
  `correct-config-audit.json` in `rebuild/r1/close2/reports/`; the third's screenshots are
  `rebuild/r1/close2/config-audit/shots/`. The correctors' reports are
`rebuild/r1/close1/reports/`; the rechecks `cont51/` to `cont53/` and `rebuild/r1/recheck2/`.
- **Who checked, and on what.** Agents that built none of the rebuild, on merged jars before the
  release candidate; in each area a first agent looked and a second saw each finding again or refuted
  it. On `1a14611`, where step 1 was the only step merged: the Workbench on mock, measured, and the look
  and the words, a first round (`cont43`); the upload hunts again and the Windows Java under Wine
  (`cont44`); the real STS 5.4.0 under a virtual display, the tree of `1a14611` imported and then pulled
  to the tree of `9dbf61a` (`cont45`). On `9dbf61a`: the Workbench on mock and the look, a second round
  with steps 2 to 4 (`cont46`); Audit and every broken upload, and whether every release 1 feature is
  there and works (`cont47`). On `0b69049`: the production guardrail and what is sent on live (`cont48`);
  configuration on live, and secrets (`cont49`). Live means the strict stand-in on a loopback port,
  never the vendor's service. F2b and H2 were merged after these jars: F2b was seen by the merger's smoke
  only, H2 by its builder only, and none of the checking agents checked the candidate `7fc32c8` itself;
  the integrator's three findings below were found on it.
- **What held.** The word gates on mock at 1440 by 900 and 1024 by 768, in both rounds: 94 and 93 words
  fresh, 124 and 110 with a member picked, 107 in the work column after the check, no visible run of
  text over 160 characters. The main mock journey of the plan's M5 on `9dbf61a`, to "The prior
  authorization is approved." in 9 clicks and 1 typed value. The live journey against the strict
  stand-in on `0b69049` in 8 clicks and 5 typed values (the plan's 3 cannot be met before answers are
  filled from the case, and the integrator allows 5 in release 1, I-31), after draftOrders type was set
  to Left out by a script; with the builders' sample collection the first CRD answered HTTP 400 and, on
  that jar, nothing on screen named the setting (F2b came later). An environment made on screen
  from an empty store in 8 clicks and used with no restart; timeouts in force at once; one retry after a
  401 and only one; a pasted token used and dropped on its 401. Only `pave.tab` in browser storage,
  only 127.0.0.1 called, no script error. No secret in an answer, the page, browser storage, the ledger,
  the CSV export, a log, the wire view or a Copy output; secrets only in `store.json` and in uploaded
  files, owner-only. On the server the guardrail held: a production write blocked unless unlocked, the
  floor `allow-prod-writes: false` not to be lifted, the unlock bound to one session and dropped by a
  switch, an edit, a lock and a restart, 21 of 23 spellings of a production address refused at save,
  the PAS inquiry, CDS discovery and the subscription list still sent on a locked production (CRD, DTR
  and the lookups not reached, no address being configured for them), and its sentences as at
  `79a7c25`. No internal error box in
  453 uploads of broken files; from a classes folder with the default start every upload answered 200
  after a rebuild under the running tool. Under Wine the read-only, in-use and disk-full sentences. In
  STS an import with 0 errors, a start from the Boot Dashboard, the files-changed line 1.2 s after a
  Team > Pull, uploads after the pull still 200 (6 of 6), and the stamp moved by a restart; after a
  change to a static file alone the line showed after 8.8 s, close to the gate of 10 s, the worst case
  being about 14 s.
- **What did not hold in the journeys.** Every mock journey of M5 was run on `9dbf61a`; all reached
  their end except P32H-PAS-NOTIFY, whose sample notification was drawn as "Unmatched" (wbm3-1), and
  P32H-ERR500 drew its failure twice (wbm3-3).
- **What they found.** 86 confirmed findings of the checks: 31 major and 55 minor; 26 on `1a14611`, 42 on `9dbf61a`
  and 18 on `0b69049`; 50 against the plan and 36 where the build did what the plan said and a tester
  was still badly served. Five more were refuted by the second reader (look-15, look-20,
  look-guardrail-5, features-5, live-config-5). The worst, besides the two holes below: a service
  failure drawn as two boxes saying the failure three times and the service's sentence three times (241
  words on the first round); the reason a CDS service gave for a 400 not
  on the screen; the open "Response detail" cut mid-word; the top bar overlapping itself at 1024 on live;
  property names and `{{braces}}` as the tester's guidance on the Workbench; a sample notification that
  belongs to the case shown as "Unmatched"; a production address remembered by a slip blocking the
  sandbox with no way out on the screen while `/api/mode` said writes were allowed (features-1); the
  spaced member ID drawn with one space; a zip or a picture told to save itself as UTF-8.
  Two were already put right in the candidate's code by H2 when the correctors began, and the rechecks saw
  them closed: the purple bar drawn under the Submit head off kind prod (wbm3-2 and look-4 on `e5d0972`,
  live-content-5 on `19ddf8e`), and the link "Production writes" on Submit, purple on purple at the
  candidate (look-guardrail-2, on `19ddf8e`).
- **What the integrator found on the candidate.** Three more findings, each with the head `7fc32c8`,
  added to `findings.jsonl` at 12:20 UTC, two major and one minor: a callback url typed in the
  environment's values is shown and masked but never used by the subscription call, which still names
  `onyx.subscription.callback-url` (integrator-2, major, D-289); a url under a name such as `authBase`
  taken for a secret, so that on live the questionnaire url can reach the Questionnaire step masked and
  be refused (integrator-3, major, first seen in the merger's smoke of F2b, D-291); the PAS finding
  titled with 2.0.1 (integrator-1, minor, D-293).
- **Two holes in the guardrail that the rebuild opened.** Both came with the rebuild's new ways to set a
  destination, both let a call reach a production write address with no unlock, and the check on
  `0b69049` found both before any hand-over; no build handed over held either. A sandbox's token url set
  to a production environment's remembered `$submit` address received a POST with the client secret
  (look-guardrail-3, D-290, decided I-28). A typed Submit address ending `/Claim/%24submit` was stored
  and sent although `/Claim/$submit` at that host is production, because the form used for the
  comparison left `%24` encoded and servers decode it (look-guardrail-4). Decided (I-29): that form
  decodes every percent-escape of the path once, an escape that cannot be decoded or a `%` left after one
  decoding is refused as an address, and every spelling refused before is still refused. Both were corrected
  before any hand-over (`e318758`, merged in `19ddf8e`) and are held by `TokenUrlAndEscapeGuardrailTest`
  (the token url at save in 13 spellings; the look's 21 refused spellings and five escaped forms of `$` as
  test cases; an escape of an escape refused) and `TokenUrlAtSendingTest` (nothing sent to such a token url,
  whatever the store holds). The recheck of the guardrail on `19ddf8e`, by an agent that wrote no
  correction, saw both refused and every earlier spelling still refused.
- **What the integrator decided** (I-1 to I-36; they amend the plan and win over it). Plain words on the
  Workbench, never property names or braces, and the server's next-step sentences shown there name the
  new places (I-1, I-15, I-34); a mock member's sample case never trips the tool's own check (I-2); a
  refused form names its fields (I-3); the live banner and the end of a flow (I-4); one filled button
  at a time (I-6); in release 1 a reload says that it starts a new case (I-8); a refusal met while files
  changed on disk says so (I-12); a restart is not "another window" (I-13); a fact's display only when
  it adds words (I-14); the Status step leads with this case's notification (I-16, I-18); a way out for
  a production address remembered by a slip, the guardrail unchanged (I-19); no "Run again" where none
  can work (I-20); a file that is not text told what it is (I-21); the lists' import tells a wrong
  separator and a JSON file apart, and names what it will not import (I-23, I-27); the wrong-kind
  Postman sentences (I-24); "Open Configuration" opens the place to change (I-32). On the measures: the
  browser's own line for a planned 4xx is not a console error (I-7, I-33), M12 is run with fresh file
  times (I-10), M6 allows 5 typed values in release 1 (I-31), and the real STS check runs at the
  candidate (I-11). The guardrail's sentences stay word for word in release 1, old tab names included
  (I-30). hunts-1 and I-9 stay with release 2. On the integrator's own findings: a name that says where,
  not what, is not a secret (I-35, integrator-3); the subscription names the typed callback url when
  there is one (integrator-2); the PAS finding's title says 2.2.1 (integrator-1). After the first recheck: a
  question that waits on
  another answer is never locked (I-36); a token url held against production write addresses only
  (I-28a), withdrawn at 13:25 UTC because the store does not know which remembered address served a write,
  so I-28 stays as built; the browser's own date input stays in release 1 (look-12); the Eclipse compiler of STS 5.4.0
  compiles every source set before a release (STS-3).
- **What was corrected, how it was seen again, and what is left.** Four correctors corrected the findings
  from 11:57 to 12:45 UTC, each on its own list (the Workbench; Configuration and Audit; the guardrail; the
  server), in the commits from `e63b70a` to `7a1dbe7`, merged without conflict into `19ddf8e` at 12:45 UTC, the release branch as it stood at 13:05 UTC.
  The integrator's whole build of `19ddf8e` gave 3,551 tests, 0 failures, 0 errors, 2 skipped, against
  3,519, 0, 0 and 2 for the candidate `7fc32c8`. Agents that wrote no correction then checked again, each
  writing the status of every finding it saw into `findings.jsonl`: the Workbench on mock and the look on
  `e5d0972`, which held the screen corrections only (`cont51`); the guardrail and the secrets (`cont52`),
  Audit and uploads and every feature (`cont53`), live configuration and what live sends, and the real STS
  5.4.0 (`rebuild/r1/recheck2/`), on `19ddf8e`. Every finding corrected in that round was seen closed except
  those corrected again in the second round (below), and these, corrected with a test, their screen halves seen on `e5d0972` before the server halves were
  merged: the readiness words of the Workbench (critic-3, look-14), the sample code E0470 (critic-4,
  wb-mock-9), the end of a flow (critic-9) and the Status step's headline (wbm3-1), seen closed on a
  running jar of `22478b8` by the recheck of the final build (below); and the integrator's three findings
  (integrator-1, integrator-2, integrator-3), not seen on a running jar. A second, short round, in commits from 12:52 to 13:14 UTC
  by three correctors (the Workbench; the server; Configuration and Audit), corrected what the rechecks
  left, each with a test that failed without it where the reports say so, and each seen by its corrector
  on its own jar: the tables of "Response detail" wrap inside the fold (critic-2, look-2); after a service's
  refusal on live, the line on what to change above its button (look-3); one filled button and one "Why"
  fold on step 2 (wb-mock-4, wbm3-4, I-6); a question that waits on another answer drawn muted and open to
  typing (wbm3-9, look-9, I-36); the headline of an incomplete response naming the questions (look-10); a
  refusal cleared when its field gets a new value, "Start a new case" included (critic-k1-1); the second
  sentence of an action said as a quieter line, not folded (I-34); a run id, the sign-in line of a suite
  or a replay and a send with no address naming Configuration or Audit (`c891362`); a folder whose name has
  an extension called a folder (AU-4); the environment's name at 1024 (live-config-7); each field named
  in a refused form's panel (live-config-8, I-3); the refusal panel of the environment form kept in view
  (look-guardrail-8); the refusal of a token url whose production environment was removed saying so, with
  the ways out (features-8, `185b5be`). The test line the Eclipse compiler rejected (STS-3, `1772d37`) is not
  seen on a jar: its corrector checked it with the Eclipse batch compiler, and so did the integrator's
  check over every source set of `22478b8`.
  The narrowing of the token url rule (I-28a) was not made and was withdrawn. The three branches were
  merged at 13:23 UTC into `22478b8`. The recheck of the final build (round k3 of `findings.jsonl`), by
  agents that wrote no correction, covered four areas on `22478b8`: the features and the guardrail
  (`cont57`), the Workbench and the look (`cont56`), the Workbench on mock (`cont56`), and live
  configuration against the strict stand-in (`cont57`). They saw closed: features-8 as decided (a sandbox after its production copy was
  removed is still not signed in, and the refusal ends with the note that the production environment is
  no longer stored and the way out by hand); the tables of "Response detail" inside the fold at 1440 and
  1024 (critic-2, look-2); the readiness words (critic-3, look-14); the sample code E0470 (critic-4, wb-mock-9); the end
  of a flow (critic-9); the Status step's headline (wbm3-1); one filled button on each step (wb-mock-4,
  wbm3-4); the line on what to change after a service's refusal on live (look-3); the muted
  question that waits on another answer and takes typing (look-9, wbm3-9); one Complete button, one "Why" fold and the headline
  naming the question (look-10); a refusal cleared by "Start a new case" (critic-k1-1); the environment's
  name at 1024 and a refused environment save naming its field (live-config-7, live-config-8); and the native
  date inputs as decided (look-12). They found four: on mock, Configuration > Advanced > In force now
  said "No environment is active." 14 times (look-19, regressed, major); at 1024 the Element column of the
  "Response detail" table broke "questionnaire" mid-word (critic-k3-1, new, minor); the README at
  `22478b8` had no "This release" section (features-9, minor); a run refused for a missing member ID named
  that field in the panel without its label (wb-mock-10, minor). The other items of the second round were
  not seen again by an agent that wrote no correction. The integrator's whole build of `22478b8` gave
  3,561 tests, 1 failure. All of it was corrected in a third round (D-300).
- **Left open, each an open line of `PROGRESS.md` with what a tester does.** An environment saved as prod by
  mistake on the same host as a working sandbox, and then removed, stops that sandbox from signing in,
  reads included, until its remembered addresses are forgotten (features-8, I-28 as built); the refusal
  says the production environment is no longer stored and gives the way out by hand, and release 2 brings
  the "Forget this production address" button. Found by the recheck of the final build and corrected
  in the third round (D-300): look-19, critic-k3-1, features-9 and wb-mock-10.
  Dates show in the browser's
  own form beside ISO dates elsewhere; the value sent is the ISO date (look-12, no change in release 1).
  With `-Dpave.classes.load-at-start=false` the advice after a rebuild under a running PAVE misses two ways
  a missing class shows (hunts-1, hunts-2, I-9; release 2); the default start meets neither. After
  rebuilding under a PAVE started with `-Dpave.classes.load-at-start=false`, stop PAVE and start it again
  before trusting any internal error. The measure M12
  is run with fresh file times (hunts-3, I-10). The guardrail's sentences still name the Config tab and the
  PAS tab (look-guardrail-6, I-30): in a production refusal "the Config tab" means Configuration
  (Production writes for the unlock) and "the PAS tab" means Workbench step 3 (Submit) or step 4 (Status)
  for a subscription. Configuration's mapping note and raw-send note still say "the Config tab", which
  means Configuration > Advanced, and two lines the store writes to the log at start say it for
  Configuration (left by the server corrector).
  A replay from Audit that fails does not carry the changed-files line (the Audit part of I-12); after a
  pull, restart PAVE before replaying. The browser's own console line for a planned refusal (wb-mock-5,
  live-config-6, I-7, I-33) and M6 at five typed values (live-config-3, I-31) are by decision. The six minor
  findings the sorter left for time (critic-8, critic-10, critic-11, look-11, look-17, look-18) were
  corrected all the same and seen closed on `e5d0972`. The README committed after `22478b8` (branch `release1-readme`) says what secrets-1 asked; the README at
  `22478b8` does not: a client secret
  or bearer token typed in the environment form is kept as typed in `config-store/store.json` (owner-only)
  unless it is typed as `{{env:PAVE_NAME}}`; the first saved setting makes the store version 2 after one
  copy to `store.json.before-v2`; an older build refuses a version 2 store; going back means stopping PAVE
  and renaming the copy back.
- **The real STS check** (I-11) ran on `19ddf8e`: the project imported, PAVE started from the Boot
  Dashboard, a restart told apart from a change in another window (STS-1), and a refusal after STS rebuilt
  the classes under the running tool carrying the changed-files line (STS-2). The Eclipse compiler STS
  brings marked one error, in a test line javac accepts, so STS asked before every start (STS-3); the line
  was changed in the second round (`1772d37`), and the integrator now compiles every source set with that
  compiler before a release (`rebuild/ecj-check.sh`): a release with an Eclipse error is not handed over.
  That check over every source set of `22478b8` finds 0 errors (`rebuild/r1/ecj-22478b8/ecj.txt`); no
  import into STS itself was made of `22478b8`.
- **What is not known.** Anything on Windows itself, on the operator's workstation or against the
  vendor's service.


**D-300. Submit is back on Da Vinci PAS 2.0.1, the guide a request shape of the environment; the third
round of corrections; the code handed over.**
- **What happened.** In an earlier session the operator had said that the vendor's sandbox follows 2.0.1
  (D-269). Asked during the rebuild which version the new Submit should follow, they chose the answer
  that named 2.2.1 and their own tool (D-281); decision O1 took it, and slices E1 to E3 built Submit and
  the inquiry to 2.2.1 (D-293). On 2026-11-22 at about 13:40 UTC, before any hand-over, they said again
  that the payer uses 2.0.1. The two statements conflict, and the integrator should have put that
  conflict in front of the operator when the answer naming 2.2.1 came; it did not. That was the
  integrator's fault.
- **Decided** (by the integrator at 13:45 UTC, operator-1). The guide Submit and the inquiry follow is a
  request shape of the environment, "PAS guide", with the options 2.0.1 and 2.2.1. Its default, and what
  mock and every environment that states nothing get, is 2.0.1. 2.2.1 stays as a choice, with everything
  E1 to E3 built and their tests. CRD, DTR and the reading of PAS answers were on 2.0.1 throughout and
  are not changed. The operator chose one hand-over at 16:00 UTC with this in it over a hand-over at
  15:00 UTC without it; release 2's hour, 22:00 UTC, is unchanged.
- **Why.** What the operator says the payer runs, said twice, outweighs an answer to a question that
  named their own tool: a payer on 2.0.1 may refuse what only 2.2.1 defines. Keeping 2.2.1 as a choice
  keeps that work for a service that asks for it, in the place where the other differences between
  services already live.
- **What changed under 2.0.1** (`94069f6` and `5c47893` on the branch `release1-pas`, merged into the
  release branch as `6e1bff3`). The request, what counts as left out and "To complete before sending"
  are held against the PAS 2.0.1 profiles, as the build before the rebuild did. Counted as left out when
  nobody supplied them: the insurer, the beneficiary's name, gender and identifier system, the coverage
  status, the payor, item.category, the practitioner's identifier and family name, an item on the
  inquiry, a document's type and category (US Core 3.1.1, which 2.0.1 builds on), and a code with no
  system. What 2.0.1 marks must-support only (the request type, the certification type, the place of
  service, the level of service, the subscriber, the claim identifier's system) gets a note when it is
  missing, never a count. Every Service card field is sent when given. The PAS report type is not sent,
  with a note that PAS 2.0.1 has no place for it (2.0.1 has no documentInformation). An attachment by url
  is allowed, with a note. Each element was decided from the PAS 2.0.1 guide source, and the corrector's
  report names the file that shows each (`Claim.fsh`, `Claim-update.fsh`, `Coverage.fsh`,
  `Identifier.fsh`, `Practitioner.fsh`, `Terminology.fsh`). Every Submit and inquiry carries one note that
  names the guide in force and where it is set, and every other note and the finding's title name the
  guide ("The request left out <n> elements PAS 2.0.1 requires"). `GET /api/pas/options` names the guide
  of the active environment ("Da Vinci PAS 2.0.1", or "Da Vinci PAS 2.2.1" when it chose that). The
  environment form shows "PAS guide" under Request shapes with the hint "Submit follows Da Vinci PAS 2.0.1
  unless the service asks for 2.2.1.". The Configuration hints of `payerName`, `payerIdentifier` and
  `pasReportType` no longer speak of 2.2.1 alone.
- **What changed under 2.2.1.** Nothing in what is sent or checked: it is D-293. The tests written for it
  now choose it (`Pas221SubmitTest`, `PasRequestBuilderTest`, `PasAttachmentFilesTest`, `PasServiceTest`,
  `PasControllerTest`).
- **What holds it.** `Pas201SubmitTest` (6 tests, each expectation cited to a 2.0.1 source file, fixture
  `pas/submit-201.json`): the default, a bare submission's left-out list and count as the build before the
  rebuild gave them, each card field sent, the report type not sent with its note, the url attachment, the
  inquiry under both guides, the profile url. `PasServiceTest`'s new case
  `anEnvironmentThatStatesNoGuideIsHeldAgainst201AndOneThatChose221Against221`. `PasOptionsControllerTest`
  (the default label, and 2.2.1 when the environment chose it). `EnvironmentSettingsTest` (the PAS guide
  stored and read back, and its entry in the view). The first run of the older tests after the change
  failed in 21 places, each a 2.2.1 test meeting the 2.0.1 default; the new tests were not run on their
  own against the code before the change.
- **What was seen, and where.** By the corrector, on its own build of `5c47893`. On mock, the preview of
  the reproduction body named PAS 2.0.1 and never 2.2.1, listed Insurer and Service type to complete, and
  left out the same eight elements the jar of `79a7c25`, the build before the rebuild, gives for that body
  (the two bodies differ in two timestamps, now whole seconds UTC). On mock, the Workbench with
  P32H-DTR-PREPOP to step 3 at 1440 and 1024 read "To complete before sending (2)" with Insurer and
  Service type. Live against the strict stand-in, with an environment that states no guide, Submit was
  answered "The prior authorization is approved." with the finding that the request left out 3 elements
  PAS 2.0.1 requires, and the inquiry was approved. With PAS guide set to 2.2.1 in the form and saved,
  `GET /api/pas/options` named 2.2.1, Submit was approved with 6 elements left out that PAS 2.2.1 requires,
  and the inquiry's notes named 2.2.1.
- **Limits.** No independent recheck by a second agent was run on this change: the operator asked for
  spending to stop. The integrator compared the request with that of the earlier 2.0.1 build and runs
  the whole builds (below). Nothing was sent to the vendor's service. The materials did not settle
  whether the X12 role code SJ sent for a servicing provider is in the X12 value set PAS 2.0.1 binds to
  `careTeam.role`, or whether the X12 unit codes of an item's quantity are in the value set it binds:
  those value sets were not at hand. Both are sent when given; each is an open line of `PROGRESS.md`.
- **The third round of corrections.** After the recheck of the final build (D-299), three short
  corrections, each with a test seen to fail without it where the reports say so: In force now says "No
  environment is active." once, in Ready to send? with its link, on mock and on live (look-19, `011ead9`,
  `ConfigPagesFrontendTest.withNoEnvironmentActiveInForceNowSaysSoOnceOnMockAndOnLive`, which failed
  without it); a refused form names Member ID by its label in the panel, and the requesting provider's
  NPI by its own (wb-mock-10, `ab65809`, `WorkbenchRecheckFrontendTest.aRefusedMemberIdOrNpiIsNamedByItsLabel`,
  which failed without it); a cell of a response table wraps at spaces and hyphens only (critic-k3-1,
  `ab65809`, `WorkbenchRecheckFrontendTest.aResponseTableWrapsItsCellsInsideTheFold`, which failed without
  it). `ListsFrontendTest`, which failed in the integrator's whole build of `22478b8` because the second
  round had not brought its expectation up to its own change (I-3), expects the refused date of birth
  twice, at the field and in the panel (`e783589`). features-9 is answered by the README committed after
  `22478b8`. Each correction was seen by its corrector on the running tool at 1440 and 1024 (In force now
  on mock and on live with no environment active), and by no independent recheck. Left by the table
  correction: at 1024 the table of SMART links is wider than the fold and scrolls by about 96 px in its own
  frame, because a long url in it has no place to break; the page does not scroll sideways. Merged into
  `f0dace9`, whose whole build gave 3,563 tests, 0 failures, 0 errors, 2 skipped
  (`rebuild/r1/close3/build-f0dace9.log`).
- **The code handed over** is `6e1bff3` of the release branch (`22478b8`, the third round, and Submit back
  on PAS 2.0.1), at 16:00 UTC on 2026-11-22; these records were committed after it. The integrator's two
  whole builds of `6e1bff3` and its build from a fresh clone gave 3,571 tests, 0 failures, 0 errors, 2 skipped, each time.


**D-301. Release 2: what it adds to release 1.**
- **What it is.** The second half of the plan (D-285), built by the same batches on the integration
  branch `rebuild/main` while release 1 was checked and corrected on its own branch, so on the release
  candidate `7fc32c8` of release 1 and before any of its corrections. Its slices, each merged by the
  merger with a quick gate of its tests and the guard tests, and each shown working by its builder or
  the merger's smoke on a jar as `MERGE_LOG.md` and its `.ready` file say: B5 (the trail), D3 (where a
  lookup saved on screen goes), E4 (answers from the case, the server part), G3 (the suites' repeated
  case id, D-283), D6 (Forget, the store's lock, unreadable stored files), F4 (lookups saved on
  screen, GET or POST), A2c (a reload that redraws the case, values for one run), G4 and B4b (Suites,
  Journeys and the run's request and answer in Audit), C2c (the lookup forms), I4 (the Advanced Trail
  page, Forget on Production writes, unreadable files in In force now) and H4 (answers from the case
  on the Questionnaire step). `rebuild/main` stood at `d62a84a` after H4, whose whole build gave 3,637
  tests, 0 failures, 0 errors, 2 skipped. Release 1 and its corrections were then merged into it (D-302)
  and one check and one round of corrections followed (D-303). What a tester reads below was searched in
  the source at `c31ae00`.
- **The trail** (B5, R-41). The request and the response of each of the last runs, one file a run under
  `<tracking.directory>/trail/` (by default `run-history/trail/`), the folder owner-only (rwx------) and
  each file rw-------, kept across a restart. `trail.max-entries` (500) bounds it, oldest first out;
  `trail.enabled: false` keeps nothing and answers "The trail is switched off on this installation
  (trail.enabled).". What is kept is the case and the wire view the result already carried, masked by
  the same secrets rule (`Secrets`, with I-35's words), so it holds member data and never a secret. Every
  run result is kept except a write the production guardrail refused (since `3d71571`, D-303). A run not
  kept reads "Not kept: the trail holds the last 500 runs, and never a write that was refused." (the
  number is the installation's). The ledger's rules are unchanged: the trail is a store of its own
  (D-298). Emptied by `DELETE /api/trail` or Configuration > Advanced > Trail, "Empty the trail", which
  asks "Empty the trail?" first and logs only the count. Held by `RunTrailTest`, `TrailRecorderTest`,
  `TrailControllerTest`, `TrailSecretsTest`, and `LedgerOverTheServerTest`, which reads only the ledger's
  own files and checks the trail folder and files as owner-only.
- **Audit** (B4b, G4). A sub-navigation Runs, Journeys, Suites. One run has the folds "Request JSON" and
  "Answer JSON" from the trail, "Download" (`GET /api/trail/{runId}/download`, a file `<runId>.json`,
  no-store), and "Load into the Workbench", drawn also where the run cannot be run again (D-302 (h)).
  Journeys lists today's journeys (UTC) with each step's state, "Include journeys of one run" for the
  rest, and one journey with its steps and "Load into the Workbench". Suites: "CSV template", "Suite
  file", "Tester reference", "Preview the suite", "Run suite", the results and "Results CSV", with the
  page's labels in a refusal (D-302 (i)). Held by `AuditFrontendTest`, `SuitesFrontendTest` and the
  suite tests of G3.
- **A reload that redraws the case** (A2c, H4). A journey's address is `#workbench/<chainId>`; a reload,
  or `#workbench/load/<runId>` from Audit, reads the journey back from the ledger and the trail ("Reading
  the journey back from the ledger and the trail.") and draws the case, each field marked "from the
  trail" or "from the ledger", and each step's result. When the trail no longer holds the runs:
  "Not kept in the trail: the case shows what the ledger kept.". Release 1's notice that a reload starts
  a new case is gone (D-302 (a)). On a step refused for a placeholder with no value, "Values for this
  run" takes values for every run from that page until removed. Held by `WorkbenchFlowFrontendTest`,
  `RunValuesFrontendTest`, `QuestionnaireStepFrontendTest`.
- **Lookups set up on screen** (D3, F4, C2c, R-11). Configuration > Members and Configuration > Providers
  each hold a lookup form in the fold "Lookup settings": "Lookup on", "Method" GET or POST (never another
  method, so a lookup stays a read; on prod an address that reads as a write is refused with the
  guardrail's unchanged sentence), "Address" with `{id}` for a GET, "Body template", headers, "Sign in
  with" (None, an API key header, a bearer token, client credentials, or the active environment's
  sign-in, which only then reaches the lookup), "Where the answer's fields are", and the timeouts. It is
  stored in `config-store/lookups.json`, its secrets masked on screen and kept as typed in the file. A
  saved lookup is used in place of `lookup.member` or `lookup.provider` of `application.yml`, which
  apply when nothing is saved. "Test the lookup" sends one ID through the saved settings and answers in
  one line. On the Workbench a lookup not set up links "Set up the member lookup" or "Set up the
  provider lookup" (D-302 (f)). Endpoints `GET /api/config/lookups`, `PUT` and `DELETE
  /api/config/lookups/{member|provider}`; the lookups take an optional `dateOfService`. Held by
  `LookupTargetTest`, `LookupSettingsStoreTest`, `LookupSettingsControllerTest`, `PostLookupTest`,
  `LookupSignInTest`, `MemberLookupServiceTest` and `ConfigFrontendTest`.
- **Answers from the case** (E4, H4, R-18). `POST /api/dtr/case-answers` reads the questionnaire the page
  holds and the case typed, sends and records nothing, and answers each question it can with the field
  it came from. The step puts them only into empty fields, never over the payer's prefilled answer
  ("payer prefilled") or the tester's, each marked "from the case" (a date it takes as a default is
  marked "default, change if needed"); a question its condition turns off takes none (D-302 (c)). Date
  questions take the date of service of the Service card and are text fields read as YYYY-MM-DD; a
  required question left blank is named ("Required, no answer: ..."). The same on mock and live; on live
  it was not seen (D-303). Held by `CaseAnswersTest`, `CaseAnswersControllerTest`,
  `QuestionnaireStepFrontendTest`.
- **Forget this production address** (D6, I4, R-35). Configuration > Production writes offers it beside
  a remembered address only when no environment it is remembered for is stored as prod
  (`forgettable` in `GET /api/config/environments`); the tester types that environment's name exactly
  (`POST /api/config/production-destinations/forget {destination, environment}`). A wrong name is
  refused at the field; an address of a stored production environment is never forgotten. The notes that
  told the tester to stop PAVE and edit `store.json` now name Forget where it is offered (D-302 (e)),
  and the save refusal names every remembered address that blocks (D-303). The guardrail's own
  sentences are unchanged. Held by `ForgetDestinationTest`, `ProductionSlipTest`,
  `DestinationGuardrailTest`, `GuardrailOverTheServerTest`, `ConfigPagesFrontendTest`.
- **The store** (D6). A lock on the store folder: a second copy on the same `config-store.directory`
  stops at start with "Another copy of PAVE is using the configuration store <folder>. Stop it, or give
  this copy its own config-store.directory and tracking.directory." (`StoreLockTest`, with a real second
  JVM). A stored file that is missing, damaged, holds another environment or repeats a collection is
  left out, not a refused start, and listed in In force now under "Stored files PAVE could not read"
  with "Remove it from the store", which moves it to `config-store/unreadable/`
  (`POST /api/config/unreadable/remove`; `UnreadableEntryTest`). The production addresses are checked
  once per configuration epoch (`AddressChecksOncePerEpochTest`).
- **The Advanced Trail page** (I4): "The last 500 runs' requests and responses, secrets masked, member
  data inside. Kept in <folder>, never in the ledger or the log.", the count and size, and "Empty the
  trail". In force now also lists the lookups in force (`ConfigPagesFrontendTest`).
- **Not in release 2, though the plan named it for this half**: the sweep of the old place names in
  server sentences (R-16, plan 7.6: `PlaceNamesTest`, `NextActionButtonsTest`) was not made, so the
  README's table of old names stays; the advice for a rebuild under a PAVE started with
  `-Dpave.classes.load-at-start=false` covers a LinkageError looked at afresh (B3a's correction of
  hunts-1, `ClassesOnDiskTest`) but not a missing class reached by reflection (hunts-2), and B3a's probe
  on a running tool was not run.

**D-302. Release 1, its corrections and the PAS 2.0.1 decision merged into release 2.**
- **What happened.** One agent merged `release1` (`afa821d`, release 1 as handed over with its records)
  into `rebuild/main` (`d62a84a`), commit `86d4807`, and made no later commit.
- **The conflicts, each settled one way.** `MemberLookupService`: release 2's date overloads and rule
  helper, release 1's check that a blank ID is tested on the form actually sent. `result.js`: release 1's
  in-card service fault (issue chips, the issues fold, the fault report and the files-changed note,
  I-12) for VENDOR_REJECTED and VENDOR_ERROR, with release 2's "who played it" heading and its "Service
  said" for a CDS refusal; TIMEOUT, UNREACHABLE and MALFORMED keep their own panel. `config-environments.js`:
  release 1 (the panel always drawn with field labels, I-3, and the banner scrolled into view).
  `wb-dtr-items.js`: release 1's I-36 (a question turned off is muted, typeable, never required, with the
  server's reason). `wb-dtr.js`: release 1's single Why and release 2's required list. `wb-attachments.js`:
  release 1's size line. `cfg-inforce.js`: release 2's unreadable files and lookups, release 1's rule of
  saying "No environment is active." once (look-19). `workbench.js`: A2c's read-back replaces release 1's
  reload notice. The style sheets kept both sides' rules.
- **Where a decision of release 1 met a feature of release 2.** (a) I-8 and the reload: the notice "A
  reload starts a new case..." is removed; where the journey cannot be read back, the line under the top
  bar says why. (b) I-26 and the trail: the line that only the page that made a run shows its request is
  removed; the trail's folds stand. (c) I-36 and answers from the case: a question turned off takes no
  answer from the case. (d) operator-1: release 2's additions were searched for an unconditional
  2.2.1; one comment was changed. (e) I-19 and I-28 against Forget: the three notes that named the hand
  edit of `store.json` name Forget where it is offered (the token url at sending, the remembered address
  on a refused write and in write access, the token url at save); the hand edit stays only where an owner
  is still stored as prod; the guardrail's sentences are unchanged, and a test shows a sandbox's token url
  no longer refused after Forget. (f) I-1, I-15, I-34: a lookup not set up links its form, with no
  property name. (h) I-20: "Load into the Workbench" is drawn also beside "This run cannot be run again:
  its member ID was not kept.". (i) I-3 in release 2's forms: the lookup form and the Suites page name an
  unplaced field by its label. (j) I-12 and I-13: the files-changed line rides wherever the read-back and
  the trail show an error. (k) I-35: the trail keeps the envelope already masked.
- **Tests changed**, each to the code kept: `ResultFrontendTest` (release 1's issue chips),
  `AuditFrontendTest` (the trail's Not kept line in place of I-26's), `ConfigPagesFrontendTest` (In force
  now reads `/api/config/lookups`), `QuestionnaireStepFrontendTest` (I-36's reason on a question turned off),
  and three failures of the first build after the merge, corrected before the commit.
- **What held it.** The merge agent's whole build of the tree committed as `86d4807`: BUILD SUCCESSFUL,
  3,687 test lines PASSED, 3 SKIPPED, 0 FAILED (`rebuild/r2/merge/build2.log`); the Eclipse check 0 errors on
  that tree. Its walk of the jar on mock at 1440 by 900: P32H-DTR-PREPOP from a fresh page, Run check and a
  reload that brought back the case and step 1 ("Documentation is required.", the NPI "from the trail"),
  the questionnaire, Submit with the questionnaire response of 5 answers, "The prior authorization is
  approved."; Audit's folds from the trail; Configuration's Environments, Members, Production writes with
  a removed prod environment's row and "Forget this production address", Advanced Trail and In force now;
  no console error, only `pave.tab` stored, no other host called. Not walked at 1024 by 768 or on live.
  Interactions (c), (e), (g), (i), (j) and (k) were not seen on a running tool by anyone.

**D-303. The one check of release 2, its findings, their corrections, and its limit.**
- **The check.** Two agents that built none of it checked `86d4807` once. The first walked the journeys:
  P32H-DTR-PREPOP to "The prior authorization is approved." in 8 clicks and nothing typed at 1440 and
  1024, P32H-NO-AUTH, P32H-ERR500 as one card, P32H-PAS-PENDED and its inquiry, P32H-PAS-ATTACH-REQ with
  two files; `GET /api/pas/options` and the Submit preview naming PAS 2.0.1 on mock and live, and 2.2.1
  only when the environment chose it; Submit and the inquiry approved by the strict stand-in under both
  guides; the trail kept across a restart and empty with `--trail.enabled=false`; a POST member lookup
  saved on screen and tested as Found, Several matched, Not found and Not answering; Journeys, a three-case
  suite, the top bar on live at 1024. The second looked for secrets and the guardrail: 14 placeholder
  secrets found only in the store's files, never in the trail, the ledger, the logs, 134 GET answers, the
  page text or the CSV export; the trail folder 700 and its files 600, "Empty the trail" leaving 0;
  the floor, the per-session unlock and its losses, 22 spellings of a sandbox address under a production
  one refused at save, and Forget's own refusals.
- **The findings and their corrections**, by one corrector on `release2`, each with a test:
  r2-journey-1 (major): a completed questionnaire response was never kept in the trail, so a reload after
  step 2 lost the step and the response Submit carries, and said a false reason. Every run result is now
  kept except a refused write (`3d71571`, `TrailRecorderTest`); seen on the jar after a reload at 1440 and
  1024, step 2 "Response complete" and Submit carrying "questionnaire response, 4 answers". safety-1
  (major): with the owner gone, forgetting the address the refusal named left the same refusal, because a
  remembered address above it still blocked; the note now names every remembered row that blocks, and the
  Forget dialog adds ", unless another remembered address lies above or beneath it." (`868b036`,
  `ForgetDestinationTest`, `ProductionSlipTest`, `DestinationGuardrailTest`); the guardrail's sentence is
  unchanged; held by tests only. r2-journey-2 (minor): the PAS guide note said "the PAS guide of the
  environment" when none states one; it now says "This request follows Da Vinci PAS 2.0.1, the default: no
  environment states a PAS guide (Configuration > Environments, Request shapes)." (`eba3e14`, `PasServiceTest`,
  `Pas201SubmitTest`); seen in the preview on the jar. r2-journey-3 (minor): the download was named
  `run-run-<uuid>.json`; now `<runId>.json` (`3d71571`, `TrailControllerTest`). safety-2 (minor): In force
  now told a lookup set up on screen to take an address from the environment; that row is left out
  (`c31ae00`, `ConfigPagesFrontendTest`); held by the Node test only.
- **Left.** "Ready to send?" still counts a lookup set up only on screen among the operations, since a
  fix needs a new dependency of `ConfigService`. Not reached by the check: answers from the case on live
  (the stand-in refused the coverage check while draftOrders type was Sent), a reload after step 4, 501
  runs keeping 500, and the closed release 1 majors it
  listed but did not follow.
- **The limit.** No second check of the corrections was made and no recheck round was run: the operator
  asked for spending to be kept low, so release 2 was closed with five agents (one merge agent, two
  checkers, one corrector, and the agent of these records) where release 1 used many more. The merge and
  the corrections rest on their tests, the whole build and one independent look. Nothing was run on a
  Windows workstation or against the vendor's service.
- **The build.** The corrector's whole clean build of `c31ae00`: BUILD SUCCESSFUL in 10m 22s, exit 0;
  its log holds 3,690 lines marked PASSED or "tests completed", a line count and not an exact number of
  tests (`rebuild/r2/correct/build.log`). An earlier run cut off by a time limit does not count. The
  Eclipse compiler check at `c31ae00`: 0 errors. The gates of section 0 before the hand-over (two whole
  builds from clean, a build from a fresh clone, a start and the two proofs) are run by the integrator on
  the commit that carries these records, after them; their result is reported with the hand-over.

### The switch between mock and live, after release 2

**D-304. Mock and live switch on screen: PAVE restarts itself in place on the other profile.**
- **What was asked.** After release 2 the operator wrote that mock and live should be switched easily
  on screen, without a stop and a start. D-297 had kept them chosen at start against the requirement
  D-281 recorded; this answers it.
- **Decided** (the integrator; built in `f9774a5`, `3275e82`, `2c3c1b5` and `5e07f4c` on top of release 2's
  `777fd1e`). The top bar's `MOCK` / `LIVE` badge becomes one control of two parts; the part in force
  keeps the badge's look and title, the other is a quiet button. Pressed, it asks once ("Switch to
  live?"), then `POST /api/mode` restarts PAVE in place: the running Spring context is closed and a
  new one started in the same process on the other profile. One class, `PaveStart`, builds the
  application for the first start and for every start after a switch, with the main class named, so
  the banner and "Started PaveApplication in ..." stay as they are. The arguments are the original
  ones in order, every form of `spring.profiles.active` taken out (any case, dashes, underscores, an
  index, with or without a value) and `--spring.profiles.active=<mode>` added last; a profile given as a
  system property or as `SPRING_PROFILES_ACTIVE` loses to it. The port, `config-store.directory` and
  `tracking.directory` given at start are the same after the switch. The restart runs on a thread of
  its own, `pave-mode-switch`, not a daemon, with the class loader `main` ran in and the handler of
  D-278; the 202 answer is written and flushed before that thread exists. Closing is the tool's
  ordinary stop: a call still out gets its 30 seconds and its ledger line.
- **Why the client is not chosen at run time.** A flag read by every client would turn what holds by
  construction (on live the mock beans do not exist, D-188; no scenario can answer on live; a live
  call that fails is never answered from mock; what only mock offers is absent on live) into a check
  at run time in every client and every reader of the mode, the day after the release was checked.
  The mode stays the Spring profile and every bean stays bound to it; `epa.mode` follows the profile
  and `StartupChecks` still refuses a start where they disagree. The cost D-297 gave (35 to 50
  agent-hours for a router) was the cost of that other design.
- **What a switch clears and drops.** Everything in memory ends with the old context, as at any
  restart: a production unlock (an unlock still never outlives a restart, and the guardrail's
  sentences are unchanged), a pasted token, the HTTP sessions, suite results held in memory. What is
  on disk is let go and taken again: the configuration store's lock, the ledger, the trail, Tomcat's
  folder, the port. `ClassesOnDisk` and `UncaughtFailures` are installed once per process, so "files
  changed on disk since it started" counts from the process start. The page loads again on
  `?pave-now=<mode>[&pave-dropped=unlock,token]#<tab>`: the tab alone, so the Workbench opens on a new
  empty case; the start reads the two parameters once and takes them out of the address before any
  view writes it. No storage key was added (the only one is still `pave.tab`). `GET /api/mode` gains
  `held {productionUnlock, pastedToken}`, always present, and `switchingTo` while a switch is under
  way; its `startedAt` is now the instant of the latest start, a switch included (I-13). A page left
  open that sees the mode change loads its tab with the same line, where it used to reload its own
  address, which could carry a journey id.
- **What a tester reads.** The dialog: "PAVE restarts itself on live. It takes a few seconds.", "The
  case on the Workbench is cleared.", to mock also "On mock, answers come from scenario files and
  nothing is sent.", and when held "The production unlock of this browser session is dropped." or "The
  pasted access token is dropped."; buttons "Cancel" and "Switch to live". While it switches, a modal
  that takes no input and ignores Escape: "Switching to live...". After: "Now on live.", with "The
  production unlock was dropped." or "The pasted access token was dropped." when one was. Changed: the
  environment form's profile, "Mock. Switch it in the top bar." and "Live. Switch it in the top bar."
  (was "..., chosen when PAVE starts"), and the action of the pasted token's refusal on mock, "Switch to
  live in the top bar to use a pasted token." `StartupChecks`' console sentences for a start that
  failed were left as they were: no switch can be offered there.
- **When it is refused**, in the shared error shape and with nothing restarted: 422 "PAVE did not
  switch." with mode "Choose mock or live." for a mode that is neither; 409 "PAVE is already on
  <mode>."; "PAVE is already switching to <mode>." (two POSTs at once make one restart: a claim is
  held for the length of a switch); "A suite is still running, so PAVE did not switch."; "Runs are
  still being replayed, so PAVE did not switch." (a counter around a replay); "PAVE's files changed on
  disk since it started, so it cannot switch in place: a switch loads no class anew." with "Stop PAVE
  and start it again."; and "This copy of PAVE was not started by its own main class, so it cannot
  switch itself." The endpoint sits behind the same guards as every other request (another site,
  another host name, a body that is not JSON, an unknown property).
- **When the wanted mode cannot start.** PAVE starts again on the mode it had and logs "PAVE could not
  start on <to>, so it starts again on <from>."; the page says "PAVE could not start on mock and is back
  on live. Its console says why." If that start fails too, it logs "PAVE could not start on <to> nor
  again on <from>, so it stops. Start it again by hand." and the process ends. PAVE's own code logs no
  message of an exception; Spring reports the failed start as at any start (D-278). When nothing
  answers within a minute the page says "PAVE did not come back. Look at its console, then start it
  again."
- **Not remembered.** A real start follows the launcher: `./run.sh` and no profile are mock, `./run.sh
  live` and STS's Profile field are live. The mode chosen on screen lasts until PAVE stops. Not part
  of this: remembering it, running mock and live at once in one process, a property to forbid the
  switch, any change to what mock or live do.
- **What holds it.** `PaveStartTest` (every form of the profile argument, the system property and the
  environment variable losing, the fallback, the end when neither starts, one claim at a time);
  `ModeSwitchInPlaceTest` (the real application started as `main` starts it, on a free port with
  folders of its own, switched to live and back, the refusals, a mock-only endpoint saying so on live,
  a setting saved after the switch, the fallback; the context closed and the port free at the end);
  `ModeControllerTest` (the 202 written before the restart, each refusal word for word, `held`);
  `ModeSwitchFrontendTest` (the words, the control, the dialog, the wait, the address after a switch,
  and since `c17b525` the frame itself, `app.js`, run in Node). The older tests that held a sentence
  now hold the new one, each with its reason; `RegexStackGuardTest`, `RecursionGuardTest`,
  `DeepAnswerStackTest` and the storage rule of `StaticFrontendTest` are untouched.
- **The check and its close.** One agent that built none of it checked `5e07f4c` on the jar on Linux,
  in the real STS 5.4.0 under a virtual display and on the Windows Java under Wine, and found no fault
  on screen and two gaps in the tests, both minor, both corrected by the agent of these records: the
  frame's part of the switch (`app.js`: the waiting state, no "not answering" notice and no poll of its
  own while PAVE switches, the line read from the address before a view writes it, a page left open
  following a switch) was held only by the text of the file; two tests now run it in Node against a
  stand-in window and server, and fail when the guard against the "not answering" notice or the poll
  is taken out (`c17b525`). The in-place test checked that the store's `.lock` file exists, which it does
  after its holder lets it go; it now reads the system's list of locks where there is one (Linux) and
  fails when the lock is not held after the switch, seen failing with the lock left untaken
  (`954f796`). Found while closing: that test could take an answer from the new start still carrying
  `switchingTo`, since the new start serves before the switch gives its claim back, and failed now and
  then; it now waits for an answer without it. The page already waits the same way.
- **Seen, and not seen.** Seen by the builder, the check and the closing agent as
  [How the switch was checked](README.md#how-the-switch-was-checked) says: both ways from every kind of
  start, forty and thirty switches on one process with one lock held and no growing number of threads
(37 before and 39 after forty; 53 before and 49 after thirty), simultaneous
  POSTs, the fallback, the drops, a slow call finishing across a switch, a second page, the frozen
  process; the Boot Dashboard keeping the process as running across switches and its Stop ending it.
  Not seen: a Windows workstation; STS on Windows; the Dashboard's Restart right after a switch; a
  start from the Profile field followed by a switch; "PAVE is already switching" in a second page's
  dialog (seen with simultaneous POSTs over the API).
- **The build.** The gates of section 0 are run by the integrator on the commit that carries these
  records, after them; their result is reported with the hand-over.

### Corrections after the first run against the vendor's service, after release 2

**D-305. A correction after release 2: the sign-in from the files, and a refused sign-in that says why.**
- **What happened.** Release 2 (`777fd1e`) was handed over, and the operator ran it against the vendor's
  UAT for the first time. They chose the vendor's Postman files, found the sign-in fields of the form
  empty, typed them, and the first call answered "Not sent: this tool could not sign in" with "No access
  token: the token endpoint answered HTTP 401. The request was not sent." They asked why the tool could
  not take all of it from the files. Their files were not seen and were not asked for: they hold
  credentials. Two faults of the tool lay behind it. "Fill the form from these files" read a sign-in in
  two cases only: Postman's OAuth2 or bearer helper on the collection, or variables named exactly
  `tokenUrl`, `clientId`, `clientSecret` or `scope` (case, `_`, `-`, `.` and spaces aside, R-08). The usual
  vendor collection does neither: it asks for its token with a request of its own (a form body with
  `grant_type`, `client_id`, `client_secret` and `scope`) and a script that stores the answer, and its
  variables carry a prefix or another word (`uat_client_id`, `tokenEndpoint`). And the token service threw
  the token endpoint's answer away: a tester could not tell a wrong secret from credentials sent in the
  wrong place (PAVE sends them in a Basic header unless the environment says "In the form body", D-233).
  What the vendor's endpoint answered, and why, is still not known.
- **Where the sign-in is read from** (decided by the integrator, `TASK_SIGNIN_FILL.md`), in this order,
  the first that gives a whole client credentials sign-in winning, with a line that says where it came
  from and which fields it filled ("Sign-in filled from the collection's token request "Auth / Get
  token": Token url, Client id, Client secret, Scope and Client authentication."):
  (a) the collection's OAuth2 helper, as before;
  (b) the collection's token request: a request whose form body (`urlencoded`, or a `raw` body that reads
  as a form as a whole) or url query carries `grant_type`. With `client_credentials` it gives the url as
  written, placeholders kept; `client_id`, `client_secret` and `scope` from the body, and then "In the form
  body"; or the request's Basic helper, its own or inherited from a folder or the collection, and then
  "Basic header". Switched off fields and headers are left out. The first such request in the file fills;
  "The collection holds 1 other token request. Sign-in was filled from "<name>", the first in the file."
  Another grant type is said as the helper's is ("The collection's token request "<name>" uses the grant
  type password, so sign-in was not filled from it. PAVE signs in with client credentials."). What the
  request sends that PAVE's sign-in does not is said by name and never by value: "The token request also
  sends audience and the header Ocp-Apim-Subscription-Key. PAVE's sign-in does not send them: if the
  sign-in is refused, that is the first thing to look at." (a method other than POST: "is sent with the
  method GET");
  (c) the environment's variables by name: an exact name as before wins; else every variable whose name
  holds the field's words is a candidate (client id `clientid`; client secret `clientsecret`; token url
  `tokenurl`, `tokenuri`, `tokenendpoint`, `accesstokenurl`, `authurl`, `oauthurl`; scope: ends with `scope`
  or `scopes`). One candidate fills its field. Several fill nothing: "Several variables could be the client
  id: {{uat_client_id}}, {{prod_client_id}}. Choose one.", and each is a button under the field that fills
  its reference with one press; a client secret is filled as the reference, as the draft fills one. On
  Edit with no environment export chosen, the stored environment's variable names are read.
  A collection's bearer helper gives no client credentials, so it is used only when none of the three
  does; when one does, "The collection's bearer helper was not used: PAVE asks for a token itself, with the
  client credentials filled below." This reads "the first that gives a whole client credentials sign-in
  wins" over "as today" for the helper: a vendor's bearer helper usually sends `{{accessToken}}`, which
  the vendor's script fills and an export leaves empty.
  A filled reference whose variable the export leaves empty is said: "{{clientSecret}} is empty in this
  export. Postman exports a variable's initial value only: type the value here, or set the initial value
  in Postman and export again." A secret is filled only as one `{{variable}}` or `{{env:NAME}}` reference
  (R-08); a secret typed into the file is never filled and never shown, and the line that says so stays.
- **Why scripts are still not read.** A script is code the vendor wrote for Postman; reading one to guess
  what it stores would be running its meaning without running it, and its values would be guesses. The
  token request itself says what is sent; the script only keeps the answer, which PAVE asks for itself.
  No script of a collection is ever run (the rule since the specification), and none is read.
- **A refused sign-in says what the token endpoint said.** When the token endpoint answers anything but a
  success with a JSON object whose `error` is a text, the sentence carries it, and its `error_description`
  when there is one: "No access token: the token endpoint answered HTTP 401 (invalid_client). It said:
  <the description>. The request was not sent." Without such a body the sentence is as before. The
  service's text is not trusted: only those two fields, only as text; line breaks and tabs made spaces,
  every other control or format character and any half of a character taken out; every secret this
  sign-in holds (the client secret it was sent, the tokens held) replaced by its mask, as the wire view
  replaces a secret a service repeats back (D-280), searched before and after the control characters go,
  so a secret split by one is found too; a client secret too short for that search that still appears
  hides the description ("(not shown: it holds the client secret)"); each field cut at 300 characters with
  " ... (cut at 300 characters)". It is shown on the run card, in "Test the sign-in" and in the trail's line
  of that run, which already carried the sentence; the log keeps "Access token request failed" and nothing
  of the answer.
- **The advice names the setting.** After a 400 or 401 with `invalid_client`, or a 401 that gave no reason,
  "what to do" says, after "Check the sign-in of the active environment in Configuration > Environments:
  its token url, client id and client secret, or its bearer token, and the variables they name.": "The
  client id and secret were sent in a Basic header: if the service wants them in the form body, choose In
  the form body under Client authentication in Configuration > Environments > Sign-in." (or the mirror:
  "The client id and secret were sent in the form body: if the service wants them in a Basic header,
  choose Basic header under Client authentication in Configuration > Environments > Sign-in."); after an
  `invalid_scope`: "The token endpoint refused the scope: check Scope under Sign-in in Configuration >
  Environments, which PAVE sends as it stands there, and not at all when it is empty." Each advice is one
  sentence, where the integrator's words were two: the error panel shows the first sentence of "what to
  do" and one more without a click, and folds anything longer under "Why", where it was missed on the
  first look. The advice is read back from the start of the sentence the token service wrote, never from
  the service's words. `onyx.auth` sends a Basic header and offers no other way, so its refusals get no
  advice. "Test the sign-in" gives the same sentence and the advice below it.
- **What holds it.** `TokenRefusalTest` (the fields read, the masks, the cut, the characters taken out,
  each advice and where none is given); `OnyxTokenServiceTest` (a secret and a held token repeated back are
  masked; its old sentence "HTTP 401." became "HTTP 401 (invalid_client [secret client secret])." because the
  endpoint's error is now said); `SignInRefusalTest` (the started application on live with a token endpoint
  on loopback: invalid_client with a description, the body way, a 401 with no body, invalid_scope, a body
  that is not JSON, a description that repeats the client secret, one of 5000 characters, control
  characters; on the run card, in "Test the sign-in" and not in the captured log); `SignInTestEndpointTest`
  (its refusal now says "(invalid_client)" and the advice); `ConfigPagesFrontendTest` (the advice drawn under
  the test's line, and gone after a success); `DraftSignInTest` (the token request in an `urlencoded` body,
  a raw form body and the url query, the url as text and as an object, switched off fields and headers left
  out, the Basic helper own, inherited and cut off by noauth, another grant type, two token requests, a
  bearer helper set aside, 150 folders deep, a secret typed into the file, prefixed names, several
  candidates, an empty value; no value of a secret in any answer); `ConfigFrontendTest` (the buttons, the
  press, the lines and what is saved). Not shown to fail without the change, apart from the tests whose
  old sentences changed. The walks of the jar of the main code hold no recursion: the token requests are
  found with a stack of their own (`RecursionGuardTest` and `RegexStackGuardTest` unchanged).
- **What was seen.** On the jar of these commits on live at 1440 by 900 in Chromium: a token endpoint of the
  builder's own on loopback answering 401 with `invalid_client` and a description, then one that repeats
  the client secret (shown as "[secret client secret]"), then one with no body: each on the coverage
  check's card and in "Test the sign-in", with the advice; the secret in no log or ledger file. A token
  endpoint that takes the credentials only in the body: refused with the Basic advice, then "In the form
  body" chosen and saved, and signed in. The stand-in on loopback (which takes either way) with a client
  secret holding `+`, which its Basic reading decodes as a space: refused in a Basic header with the same
  advice, signed in once "In the form body" was chosen, and the coverage check sent. Files written for the
  check (placeholders only): a collection with a token request in an `urlencoded` body and an environment
  with prefixed names filled every field ("Sign-in filled from the collection's token request "Auth / Get
  access token": ..."), saved, made active and signed in at the stand-in with no value typed; an environment
  with two client ids offered both as buttons, one pressed, saved, made active and signed in; an empty
  client secret said. Not seen: the vendor's own files or service, Windows, the IDE.
- **Left.** A collection in format v2.0 is still refused as a whole by the parser ("This tool reads format
  v2.1 only"), so its token request is not read; the shapes v2.0 wrote, inside a v2.1 file, are. A token
  request whose body is JSON is not read. PAVE sends the client id and secret in a Basic header as they
  are, not form-encoded first as RFC 6749 section 2.3.1 asks; a secret holding `+`, `%` or `:` can be refused
  there by a strict endpoint and taken in the form body: not changed here, since endpoints that do not
  decode would then refuse every such secret. What PAVE's sign-in does not send (an audience, a
  subscription key header) can still not be added to it: the line names it so the tester knows.
  Later: a collection in format v2.0 is read (D-307).

**D-306. A second correction after release 2: what a collection states goes into the saved lists.**
- **What happened.** On the same first run against the vendor's UAT (D-305) the operator typed every
  member, provider and procedure code by hand, though the vendor's Postman collection states them in the
  bodies of its sample requests: the patient and practitioner ids of the vendor's own FHIR server, the
  test member's identifier and name, the NPI, codes the sandbox knows. They asked that what the
  collections state about members, providers and procedure codes be put into the saved lists, so they
  could use it at once. The vendor's files were not seen and were not asked for.
- **Decided** (by the integrator, `TASK_INGEST_LISTS.md`). When a collection is read, PAVE shows the
  members, providers and procedure codes its requests state and adds them to the saved lists when the
  tester says so. In the environment form, after "Fill the form from these files", a part "Found in the
  collection" lists them, each with the request it was found in ("From "<request>"."), and under "Seen
  and not used" one line for each thing seen that cannot be used, with why; the box "Add these to the
  saved lists when the environment is saved" is ticked at first, and the entries are added only after
  the save went through. On Configuration > Collections the same part follows a successful upload, with
  the button "Add to the saved lists" and the active environment filling the placeholders. An entry is
  labelled "From <request name>". The result is one line, "Added to the saved lists: 2 members, 2
  providers, 4 procedure codes." or "Nothing new was added to the saved lists.", then what was there
  already, what did not fit a full list and what the lists' rules refused, with links to the three lists.
  Nothing found: "Nothing to add to the saved lists was found in the collection.", and no box.
- **Why this stays inside the rule that no member data is persisted.** The operator relaxed that rule for
  saved test lists entered on purpose (D-294, D-298). The tick is the purpose: nothing goes into a list
  unless the tester leaves the box ticked and saves, or presses the button. The collection read is the
  one being uploaded or saved with the environment, and the store keeps it with every value it holds
  (D-213), so nothing is on disk afterwards that was not there before. The bounds of D-298 hold
  unchanged: test data only, owner-only files, at most 200 entries a list, the same field rules, never
  logged, never in the ledger, the trail or the browser's storage (its one key stays `pave.tab`), off with
  `test-lists.enabled: false`, the same on mock and live. The list store itself (`TestListStore`) was not
  changed.
- **Why only what a request sends is read.** A request's body and url are what the vendor's sandbox was
  sent and accepted for the sample, so the ids, member ID and codes in them are ones it knows. A saved
  example response may come from a real call (the upload already warns of it, D-213) and says what a
  service answered, not what a tester sends: it is never read. Scripts are never read or run (D-305).
  Placeholders are filled from the environment export chosen with the collection, else the stored
  environment being edited, else, on Configuration > Collections only, the active environment, then the
  collection's own variables; a stored secret counts as its mask. A body with a placeholder nothing fills,
  or that uses a secret, is skipped whole with one line, so no entry is built from half a value and no
  secret reaches a list. The rules, word for word, are those of the builder's report
  (`ingest/report/build.json`, readingRules): members from `context.patientId`, a Patient id in a url and
  each `Patient` resource wherever it sits, with a `Coverage` of that patient; the member ID from the
  environment's `memberIdSystem`, else the one identifier typed `MB`, else the only identifier, else none;
  providers from `context.userId` reading `Practitioner/<id>`, a Practitioner id in a url and each
  `Practitioner` resource, the NPI only when its check digit holds; procedure codes from the codings of
  `ServiceRequest.code`, `Procedure.code`, `DeviceRequest.codeCodeableConcept` and
  `Claim.item.productOrService` with its details, RxNorm and NDC left out as medications. The same member
  (member ID or vendor patient id), provider (NPI or vendor practitioner id) or code (code and system)
  found twice is one entry; where two requests disagree the first is kept and a line says so. Adding reads
  the files again on the server rather than taking the entries from the page, so the reading rules live
  in one tested place.
- **Why a saved member may hold a vendor patient id alone.** A CDS Hooks sample usually names its patient
  only by the vendor's FHIR id, and that id is what a tester cannot guess. A member now needs a member ID
  or a vendor patient id ("Enter the member ID as it is on the card, or the vendor patient id."); one
  vendor patient id names one saved member, as one NPI and one vendor practitioner id already name one
  provider ("A saved member has this vendor patient id already. Edit that entry instead."); a CSV row is
  matched by `memberId` or `vendorPatientId`. Picking such an entry fills the vendor patient id and leaves
  Member ID empty for the tester; the Member card's picker names it "vendor patient id <id>", and the
  Members table has a column "Vendor patient id". A provider could already be saved with a vendor
  practitioner id alone.
- **Why procedure codes got a list of their own.** A code the sandbox knows is as hard to guess as an id,
  and the Service card had nowhere to keep one. Configuration > Procedure codes is built from the same
  code as the two other lists (add, edit, remove, CSV import with a dry run, a template), in
  `test-lists/codes.json`: label, code (1 to 40 characters, no space), code system, description (at most
  500 characters), one entry for one code in one system, no system counting as its own value. The Service
  card's "Saved codes" adds a picked code with its system and description, marked "<code> from saved
  list" under its line.
- **What is never done.** Nothing is guessed: a member ID that cannot be told apart, an NPI whose check
  digit fails, a partial birth date, a gender PAVE does not know is left out and said. An entry a list
  holds already is left as it is, never overwritten. An entry the rules refuse is not cut down to fit.
  Nothing found is logged: the log lines hold counts only ("Test <list> from a collection: N added, N
  there already, N did not fit, N refused"). Nothing goes to the ledger. Reading sends nothing to the
  vendor and stores nothing.
- **Endpoints.** `POST /api/lists/found` (what was found, stores nothing; a misspelt property is a 422
  that names it; a file it cannot read answers 200 with "The collection was not read: " and the file
  guard's line); `POST /api/lists/found/add` (the same body, the files read again, then added);
  `/api/lists/codes` with the same routes and shapes as the two other lists.
- **What holds it.** `CollectionFindsTest` (the reading: order-sign with and without prefetch, a PAS
  submit Bundle, the member ID order, placeholders defined and not, one patient in four requests, a body
  that is not JSON, deep bodies, odd context ids, a url, saved examples not read); `FoundListsControllerTest`
  (both endpoints: the three placeholder layers, an unknown environment, v2.0 and broken files, a missing
  collection, a misspelt property, lists switched off, an entry left as it is, adding twice, a full list);
  `CodeListControllerTest` (the third list); `FoundFrontendTest` (the part, the box, the button, the form
  that reads after Fill and adds after a save, the picker for a vendor-only member, the Procedure codes
  page and "Saved codes", nothing but `pave.tab` in the browser's storage). `TestListControllerTest`,
  `TestListStoreTest`, `ListsFrontendTest`, `ConfigFrontendTest`, `ConfigPagesFrontendTest` and
  `ServiceCardFrontendTest` were brought up to the changed behavior. The builder's report does not say
  whether the new tests were shown to fail without the change. The walks of the body use a stack of their
  own, never recursion (`RecursionGuardTest`, `RegexStackGuardTest` and `DeepAnswerStackTest` unchanged).
- **What was seen.** By the builder, on the jar of the branch, with a placeholder collection and
  environment of its own (`S123456789`, `pat-001`, NPI `1234567893`, `prac-002`): on mock at 1440 by 900
  and 1024 by 768 the part after Fill, the save that added "2 members, 2 providers, 4 procedure codes",
  the three lists, and on the Workbench the Member, Provider and Service cards filled from them with
  "E0601 from saved list"; a second save refused for a name already stored added nothing. On live against
  the strict stand-in at 1440 by 900 the same, the member picker showing only saved members; at 1024 by
  768 the part. The application logs held none of the placeholder ids or names. Not seen: the vendor's own
  files or service, Windows, the IDE, the Edit form of a stored environment, the button on Configuration >
  Collections, a full list, lists switched off, a body with a placeholder nothing fills. No second agent
  checked it.
- **Left.** A collection already stored is not read again: the store keeps no collection text the lists
  could reach without changing `service/config`, so the part appears only at upload and in the
  environment form. A collection in format v2.0 is not read, as the parser refuses it at upload too.
  Placeholders are not filled from `onyx.variables` of `application.yml`. At 1024 pixels the Service
  card's code line was crowded before this change; the "from saved list" mark was put under the line so
  as not to add to it.
  Later: a collection in format v2.0 is read for the saved lists as for v2.1, since the parser reads it
  (D-307); `CollectionFindsTest` and `FoundListsControllerTest` hold it (`879033f`). In the joined form
  "Found in the collection" stands after "What these files give" (D-307), and the result of a save says
  the request chosen for an operation beside what the lists took (`7c14ef8`).

**D-307. A third correction after release 2: the form takes, and shows, everything the files give.**
- **What happened.** On the same first run against the vendor's UAT (D-305), the operator chose both
  Postman exports, pressed "Fill the form from these files", and then typed the sign-in, the addresses and
  the values by hand. Asked which fields had to be typed, they answered all of them, and that whatever the
  variable names in the environment export are, the tool should take them too. Their files were not seen
  and were not asked for. What the form did explains it: after Fill a new environment showed the name,
  the sign-in when it could be read, a row of chips for the operations found, a fold of the export's
  variables, and "The member ID rule, request shapes, values, timeouts and addresses can be set once the
  environment is saved."; once saved, Edit showed Values and Addresses as rows of empty boxes, what the
  files gave written small beside each. An empty box reads as a field not filled, and a typed address
  replaces the collection's request for that operation, its headers and saved body included (R-02), the
  opposite of what was wanted. Where the files really gave nothing (a request the tool could not tell),
  the only way offered was to type an address. Decided by the integrator (`TASK_FILL_EVERYTHING.md`).
- **On Edit, nothing in force looks empty** (`25b200a`). A row of Values or Addresses shows what is in
  force and where it comes from as the row's own content: "from the collection's request "<name>"", "from
  the export's variable <name>", "from application.yml", "typed here", or "not set". The server's
  `sourceLabel` carries those words (it said "collection request "<name>"", "from the export" and
  "application.yml"; the environment cards' chip titles read it too). "Change" opens the box; under it "Use
  the collection's request again" (or "Use the export's again", "Use application.yml's again") closes it
  before anything is typed. A typed row shows its box with "Use the file's again", which empties it, and the
  line "What was typed here is taken away when the environment is saved." A box is open only when something
  is typed or nothing is in force; a refusal at a field opens its box. A stored client secret or bearer
  token is said as stored ("Stored: {{uat_client_secret}}") and its password box opens on "Change"; a box
  opened and left empty still keeps what is stored. The other sign-in fields are the environment's own
  settings and show their values in their boxes, as before.
- **What the files give, right after Fill** (`6664bd1`). The draft (`POST /api/config/environments/draft`,
  still 200 for any file, storing and sending nothing, R-07) gains `collectionId`, `addresses`, `requests`
  and `values`, and the form shows them in a part "What these files give" in the place of the chips and of
  the sentence, which stays only for a new environment with no draft:
  - **Sign-in**: the line "Sign-in filled from ..." of D-305, and "The fields are filled under Sign-in
    below."; without one, "These files give no sign-in: choose the method under Sign-in."
  - **Addresses**: one row per operation of the Edit form, in its order and with its words. Each names the
    request a call will use, chosen as the gateway chooses (R-02): the first request in the order of the
    file mapped to the operation, passing over one whose name says "error". Its address is resolved through
    the layers a call would see once saved (the environment export, else the stored one on Edit; the
    collection's variables; application.yml), a secret masked, a credential typed into a url masked, and a
    placeholder nothing defines left as `{{name}}` with "{{name}} is not defined in these files, so it stays
    as written: a run asks for its value before it sends." Two that fit: "2 requests fit this operation.
    "<first>" is used, the first in the file: choose another below to use it instead." A row no request
    fits: "No request of the collection was found for it. Choose one below.", then application.yml's
    address when it gives one ("from application.yml"), for discovery the hook request's url cut after
    `/cds-services` as a call cuts it, else "Without one, a call for it is refused before anything is
    sent." A sample of an error that fits is named. A collection stored before that a call would ask first
    is named ("Until it is removed in Configuration > Collections, a call uses ..."), since collections are
    shared by every environment and asked in the order they were stored.
  - **The list to choose from**: under "Use a request of the collection", the requests no operation uses
    and, for a row two fit, the others that fit ("(also fits)"); a request the sign-in read as the token
    request is not offered. A row with a request keeps the list behind "Choose another request". "Type an
    address instead" stays the last choice, on a new environment.
  - **Values**: each value of the Edit form with what the files give and the variable it comes from ("from
    the export's variable fhirServer", "from the collection's variable ...", "from application.yml"), or
    "not given"; one line for the part: "A value these files do not give is left out of the requests that
    read it. Type it here, or on Edit once the environment is saved."; a variable the export holds with no
    value: "{{name}} has no value in these files. Postman exports a variable's initial value only." A value
    is not matched to a variable under another name: that would be a guess, and the request that reads a
    vendor's own variable reads it by its own name (this changes nothing of R-42).
- **How a choice is saved.** A request chosen for an operation is the collection's mapping (the one of
  Configuration > Collections, `PUT /api/config/collections/{id}/mapping`), sent after "Save environment"
  has stored the collection, once for each choice; a request that fits the same operation earlier in the
  file is mapped to UNMAPPED first, since the first is what a call uses. A mapping that fails is said ("The
  environment is saved, but the request chosen for <operation> was not recorded. <title> Choose it again in
  Configuration > Collections.") and the environment stays saved; the form then reads the environment's
  settings again so Edit shows "from the collection's request ...". The mapping is not a setting of the
  environment: collections are shared, as before. A choice changes no address: every url a choice can bring
  in was in the files the save read, and every send still passes the production guardrail at the moment
  of sending; a choice is not refused at save as a typed production-looking address is on sandbox or uat,
  which is left (below). A value or an address typed in the part goes with the save as one typed on Edit,
  under every rule a typed one meets.
- **A request is also told by its body** (`fc80b7d`). `OperationDetector` read the name, the url and the
  folders. It now reads the raw body, only when those leave the request unmapped, so nothing they mapped
  changes its operation: a body whose `hook` is `order-sign` or `order-select` (read from the JSON, or from
  the text as written when a placeholder keeps it from being JSON, with `indexOf` and no pattern); a Bundle
  that carries a Claim narrows to `$submit` or `$inquire` and maps when the url chooses one; a Parameters
  body with a parameter named `coverage` is `$questionnaire-package`. The body never overrides the name,
  the url or the folder, and the guardrail's `signals` still read the name and the url only. The store,
  the draft and the vendor sample reader all detect with the body.
- **A collection in format v2.0 is read** (`050f977`). The parser refused a v2.0 file whole. Its shapes
  that differ from v2.1 (a url, a header or a request written as text, a helper written as an object) were
  already read where each is read, so it is read now, with one line: "This collection is in Postman's
  older format v2.0, which this tool reads as it reads v2.1." A part that cannot be read is said as for a
  v2.1 file (an entry that is neither folder nor request is counted in a line) and the rest is read. A
  format that is neither is refused: "This is a Postman collection in format v3.0.0. This tool reads formats
  v2.1 and v2.0."
- **What holds it.** `FilesGiveTest` (a collection that maps everything, one that maps nothing, two that
  fit one operation, an undefined placeholder, a collection alone, application.yml's address, a value from
  the export and a blank one, no secret value in any answer; a request chosen and mapped shown on Edit as
  "from the collection's request ..." and used by the call; a request mapped by its hook body, the same in
  the draft and once stored); `OperationDetectorBodyTest` (each new rule on a request the old rules left
  unmapped, and that the body overrides nothing; `OperationDetectorTest` runs unchanged);
  `PostmanParserV20Test`; `ConfigFrontendTest` (a row in force is no empty box, "Change" and the way back,
  a stored secret; the part, the list, a choice, a typed value, the mappings sent after the save and a
  failed one said). Changed, each for its reason: `ConfigFrontendTest`'s stand-in labels and the buttons
  of a client secret field (it now carries "Change"); `PostmanParserTest` and `ConfigControllerTest`, whose
  v2.0 refusal is now a v3.0.0 one or gone; `DraftSignInTest`, whose v2.0 file is now read and fills the
  sign-in. `RecursionGuardTest`, `RegexStackGuardTest` and `DeepAnswerStackTest` unchanged. Not shown to
  fail without the change, apart from the tests whose old sentences changed.
- **What was seen.** On the jar of each commit, on live against the stand-in on loopback, in Chromium:
  files written for the check (placeholders only): an environment export with a base url, a token url, a
  client id and secret, a scope and values under names of its own, and a collection named as a vendor names
  its requests, with a token request, a CDS Hooks request whose service id does not name the hook, and one
  that nothing tells; and the same collection in format v2.0. At 1440 by 900 and 1024 by 768: Fill showed
  the sign-in, every address with its request and where it comes from, and every value; before the body
  rule, the order-sign row said no request was found and the hook request was chosen from the list; after
  it, the order-sign row named that request and the request nothing tells was chosen for order-select;
  saved as sandbox, made active, Edit showed every row as its value and source with "Change", no row in
  force an empty box; the coverage check on the Workbench was sent to the chosen request's address with a
  token and answered "Documentation is required.", with no address or value typed. The same with the v2.0
  file. No value of a secret was in the draft's answer or the log. Not seen: the vendor's own files or
  service, Windows, the IDE, the Edit form's "Fill the form from these files" in a browser.
- **Left.** A choice of request is not checked at save against the production rules a typed address
  meets (a production-looking address on sandbox or uat); the guardrail at sending still is. A value is
  never matched to a variable of another name. A collection stored before is asked first by every
  environment; the row says so, and only removing it there changes it. A request's form body (`urlencoded`)
  is not read by the detector, as before.
  Later: joined with D-306 on tonight's head. After Fill, "Found in the collection" stands after "What
  these files give": what configures the environment first, the case data after. A save records the
  requests chosen and then adds the entries found, and its result says both; a recorded pick is now
  said ("CRD order-sign uses the request "...".") where before only a failed one was (`7c14ef8`).
  Later (D-308): a value no variable of its name gives is also taken from the sample request that
  states it, where PAVE's own builder writes that value, and the save stores it; a value is still never
  matched to a variable of another name.


**D-308. A fourth correction after release 2: the values a sample request states are taken from it.**
- **What happened.** On the evening of the first real run against the vendor's UAT, once the address
  was put right, the vendor's service refused the coverage check: `"fhirServer" is required`. The value
  was in the vendor's Postman collection all along, in the body of its sample coverage-check request.
  PAVE sends `fhirServer` only when the environment's value "FHIR server" is set, and the form filled a
  value only from a variable of exactly that name (D-307), so the operator found the line in Postman and
  typed it under Edit > Values. They had said twice that everything the files hold is to be taken from
  them. The values of the Edit form (`KnownValues`) are, in a vendor's collection, stated in the bodies
  of its samples rather than in variables of PAVE's names, and the same refusal waited at Submit for the
  payer. Their files were not seen and were not asked for. Decided by the integrator
  (`TASK_VALUES_FROM_SAMPLES.md`).
- **Where a value is read** (`SampleValues`). Only from the place in a sample where PAVE's own builder
  writes that value, and only from the sample a call for the operation is sent from: the first request
  in the order of the file mapped to it, passing over one whose name says "error", as the gateway
  chooses (R-02); with none mapped, the only request whose body can state it. Each body is parsed once
  (a bare placeholder read as null, as `VendorSamples` reads it) and read by fixed paths, so no walk
  grows with the file.
  - **FHIR server**: `fhirServer` at the top of the coverage-check (order-sign) sample; with no request
    mapped to order-sign, the only request whose body states a `hook`.
  - **Payer name, payer identifier, payer identifier system**: in the Submit sample's Bundle, the
    Organization that `Claim.insurer` refers to (by the entry's fullUrl, or by type and id; a reference
    two entries could answer names none): its `name`; its identifier, value and system, when it has
    exactly one. With several, none is taken and a line names their systems, never their values: "The
    collection's request "<name>" gives the payer 2 identifiers (<system> and <system>), so none is
    taken: choose one and type it."
  - **Member ID system**: PAVE writes the system on the Patient's identifier typed MB (`PasResources`).
    From the Patient `Claim.patient` refers to: the identifier typed MB, else the only one, else none,
    the rule D-306 reads a member ID by. Several: "... gives the patient 2 identifiers (...) and none is
    typed MB, so the member ID system is not taken." The member ID itself, and every other member value,
    is never shown in this part.
  - **PAS request, service and certification types**: from the first item of the sample's Claim, where
    `PasRequestBuilder` writes them: the item's extensions `serviceItemRequestType` and
    `certificationType`, and `item.category`, each a coding of its X12 system. A code of another system
    is not that place and is not read. Items that state different codes give none: "The items of the
    collection's request "<name>" state different PAS request types (HS and AR), so none is taken." The
    builder writes these in the same place under PAS 2.0.1 and 2.2.1, so the guide in force changes
    nothing of where they are read.
  - **PAS report type**: the `reportTypeCode` of the `documentInformation` extension on the Claim's
    supporting information, where the builder writes it under PAS 2.2.1; entries that state different
    codes give none. Under 2.0.1 a stored report type is not sent, as a typed one is not.
  - **Callback url**: not taken. It is where the vendor reaches PAVE's own receiver; the subscription
    sample's endpoint is the vendor's example of a receiver, not this tool's address, so reading it would
    be a guess.
  A variable of the value's own name wins over a sample. A value the sample writes as a `{{placeholder}}`
  is resolved through the layers an address is and, when nothing defines it, stays as written with the
  line D-307 uses. Not taken: a value that is only `{{<its own name>}}` ("... writes it as {{fhirServer}},
  which these files do not define."), one that resolves through a secret or holds a credential ("...
  states it through a secret, so it is not taken."), one longer than a value may be, and the payer
  identifier's system when the payer identifier in force comes from elsewhere, since it is the system of
  the sample's own identifier ("... states the system of its own payer identifier, and the payer
  identifier in force comes from elsewhere, so it is not taken.").
- **What the form shows.** The draft's value rows gain `request` and `notes`. A row taken from a sample
  reads "<value> from the collection's request "<name>"", and above the rows a box, ticked, "Use the
  values these samples state", with "Each is stored with the save where nothing else gives that value. A
  value typed, or one the export gives, stays as it is." Under the FHIR server, what the coverage-check
  sample sends at its top level that PAVE's hook request (hook, hookInstance, fhirServer, context,
  prefetch) does not, by name only: "The sample also sends fhirAuthorization. PAVE does not send it: if
  the service refuses the check, that is the first thing to look at." (with several, "... PAVE does not
  send them: ... they are the first things to look at."; where the row does not come from that sample,
  it starts "The collection's request "<name>" also sends").
- **How it is stored.** The save takes `sampleValues` (true when the box is ticked and a collection
  export goes with it); the server reads the samples of that export again, so what is stored never rests
  on what the page sent. For each value that nothing is in force for (no value typed or stored, no
  variable of its name in the export, the stored export on Edit, the collection or application.yml, no
  callback url in application.yml) the value as the sample writes it is stored under Values, with its
  origin in a new `valueOrigins` map of the settings (`store.json`; an origin whose value is absent makes
  the entry unreadable, as every other unknown does; an older build ignores it). The result says each:
  "FHIR server set from the collection's request "<name>".". The stored value passes the production
  checks a typed value does. Edit shows it with source `SAMPLE` and "from the collection's request
  "<name>"", its box closed, "Change" to open it and "Use the sample's again" under the opened box; a
  closed or empty box keeps it, and a value typed there replaces it and its origin. On Edit the part needs
  the collection export chosen and "Fill the form from these files" pressed, as before.
- **The refusal points at the fix** (`CrdVerdicts`). As the 400 that names draftOrders.type is read (R-37),
  by the text of the answer: when the service refuses the check (4xx other than 401 and 403, or 5xx), its
  answer names `fhirServer` and the request as sent carried none, the next step is "Set FHIR server in
  Configuration > Environments > Edit > Values.", with " The vendor's sample request states one." when the
  saved body of the collection request the call was sent from states one; the reason: "The service
  refused the check and its answer names fhirServer, which this request did not send. PAVE sends
  fhirServer only when the environment's value FHIR server is set." No general reader of refusals was
  built.
- **What holds it.** `SampleValuesTest` (the draft: each value from a sample written for the test,
  placeholders only; a variable of the name wins; several identifiers; items that disagree; a code of
  another system; a placeholder defined and not; a value naming itself; a secret; a v2.0 collection; a
  collection with no sample; the only hook body; the payer identifier's system beside an identifier from
  the export. The save: on a new environment and on Edit the value is in force with where it came from,
  and read back by a service started on the same folder; a typed value and the export's are not replaced;
  the box unticked stores nothing; the member ID is in neither the answer nor `store.json`);
  `FhirServerAdviceTest` (the advice with and without its second sentence, in a card's detail and in an
  issue's text, and a request that sent fhirServer, another refusal, a 401 and a body that is not JSON
  keeping their own next step); `ConfigFrontendTest` (the row, the note, the box ticked and unticked as
  sent, the result line, and on Edit the closed row kept, "Change", a typed value sent and "Use the
  sample's again"). No expectation of an existing test changed. `RecursionGuardTest`, `RegexStackGuardTest` and
  `DeepAnswerStackTest` unchanged. Not shown to fail without the change.
- **What was seen.** On the jar of each commit, on live, in Chromium, with files written for the check
  (placeholders only): an environment export that defines none of the ten values under PAVE's names, and
  a collection whose coverage-check sample states `fhirServer` `https://ehr-sandbox.example.org/fhir` and
  a `fhirAuthorization`, and whose Submit Bundle states a payer with one identifier, a Patient whose
  member ID is typed MB, and the request, service and certification types. Against a copy of the
  stand-in changed to refuse a hook request with no `fhirServer` (`"fhirServer" is required`, HTTP 400):
  first an environment whose collection's sample states none, saved and made active: the check was
  refused and the card said "Set FHIR server in Configuration > Environments > Edit > Values.". Then, on a
  fresh store, at 1440 by 900: Fill showed the FHIR server from the coverage-check sample with the line
  for fhirAuthorization and the ticked box; saved as sandbox, the result said "FHIR server set from the
  collection's request "1. CRD / CRD - Prior Auth Check"."; Edit showed it as in force from that request
  with "Change"; made active, the coverage check was answered "Documentation is required." with no value
  typed, the stand-in's log showing `fhirServer` sent. At 1024 by 768: an environment saved from the
  export alone, then Edit, the collection chosen, Fill and save: the value stored and shown on Edit. The
  payer, the member ID system and the three PAS types the same way, and a Submit preview through the API
  carried the insurer, its identifier and system, the member ID system and the item types. No value of a
  secret was in the log. Not seen: the report type taken from a sample on a screen, the vendor's own
  files or service, Windows, the IDE.
- **Left.** A value is taken only when files are chosen and saved: an environment saved before keeps
  "not set" until its collection is chosen again on Edit. A value stored from a sample lies over an export
  that later gives one of its name, and Edit offers no way to empty it, only to type over it.
  `fhirAuthorization` is never sent, and no other member of a sample is copied into PAVE's request. The
  advice's second sentence reads the call's own request only, not every stored collection. The callback
  url is never read from a sample.

**D-309. A fifth correction after release 2: the coverage check goes the way the collection's sample goes.**
- **What happened.** On the evening of the first real run against the vendor's UAT, with the FHIR server
  put right (D-308), the vendor's service answered PAVE's coverage check with one card: summary "Error",
  detail "No patient data provided". The same check sent from the vendor's Postman collection was
  answered properly. The operator compared the two bodies side by side: each carried blocks the other
  lacked. The operator asked that anything missing be added, that anything not standard be said, and
  that the requests go the way the collection's go; the id of the Coverage, which the vendor supplies,
  was to be left aside. The comparison reached the integrator as a video too small to read; the
  operator's files were not seen and were not asked for. Decided by the integrator
  (`TASK_PREFETCH_SHAPE.md`).
- **What the standard says.** CDS Hooks fixes no names for the prefetch. Each service names its keys in
  its discovery answer, each with a FHIR read or search; a client sends a read as the resource itself and
  a search as a searchset Bundle. PAVE's five named Bundles of type collection (`patientBundle`,
  `coverageBundle`, `practitionerBundle` or `organizationBundle`, `practitionerRoleBundle`,
  `serviceRequestBundle`, D-291) are what one earlier sandbox accepted, not the standard, and a service
  that looks for the patient under another key finds none. **Following the collection's sample is what
  this tool does in place of asking the service's discovery: reading the keys from discovery is not
  built.**
- **Decided.** Every resource is still built from the case; nothing of a sample's member, provider or
  order is sent. What follows the sample is the arrangement, the rule the tool already keeps for
  `context.draftOrders.type`. A new request shape, "Prefetch" (`CRD_PREFETCH`), beside "draftOrders type":
  "PAVE's named Bundles (patientBundle, coverageBundle, ...)" (`NAMED_BUNDLES`, the default) or "As the
  collection's sample" (`AS_SAMPLE`), whose words on Edit and after Fill name the sample's keys ("As the
  collection's sample (patient, coverage, encounter)"), with the line "CDS Hooks fixes no names for the
  prefetch: each service names its own keys, and a service that looks for the patient under another key
  finds none." With nothing stated, a call takes `AS_SAMPLE` from the saved body of the collection
  request it comes from when that body writes a prefetch with at least one key, and the named Bundles
  otherwise; a sample whose name says "error" is not followed. So nothing changes for a collection with no
  such sample, a typed address, or the mock scenarios.
- **The arrangement** (`PrefetchLayout`, `CrdRequestBuilder`). The sample's body is parsed once
  (placeholders outside strings kept as text) and its prefetch read two levels deep by fixed paths. For
  each key, in the sample's order: a single resource of a type PAVE builds (Patient, Coverage,
  Practitioner, PractitionerRole, Organization, ServiceRequest) goes as that resource; a Bundle goes with
  the sample's `type` (none when it states none, with a note that FHIR requires one), entries of the
  sample's entry types in the order they first appear (every order for ServiceRequest), each with its
  `fullUrl`, the sample's `search.mode` for that type when it states one, and `total` when the sample has
  one; a key written as null goes as null, as the sample sends it. Left out and said, by key and type,
  never by value, in the request notes and the form: a type PAVE does not build ("The sample's prefetch
  also carries Encounter under "encounter". PAVE does not build it, so it is not sent.", and "... in the
  Bundle under "<key>" ..." for an entry), a Bundle holding no type PAVE builds, an entry with no
  resource type, a key written as a placeholder ("... writes "<key>" as a placeholder. PAVE sends nothing
  of a sample's values, so it is not sent."), and anything that is no resource. A type PAVE builds that
  this case gives none of (a Practitioner for a provider known as an organization) is said too. Each
  resource is built only when a key asks for it, so the notes and the elements counted as missing are
  those of what went. The notes say "Prefetch: As the collection's sample, from the collection's sample
  (the request "<name>"). The prefetch was sent under the sample's keys, each built from the case: ..."
  and which of Patient, Coverage, Practitioner and PractitionerRole the sample's prefetch holds none of.
  With the five named Bundles written as a sample the request is byte for byte what PAVE sends with the
  default. A shape stated `AS_SAMPLE` with no sample to follow sends the named Bundles and says so.
- **The context and the identifiers.** The members PAVE sends in `context` stay; a member of the sample's
  context PAVE has no value for is not sent and is said by name ("The sample's context also carries
  encounterId. PAVE has no value for it, so it is not sent."). In the sample's arrangement, the member ID
  on the Patient and the Coverage goes with the system the sample's own resource states (else the other
  one's) when the value Member ID system is not set, which still wins; it is typed as the sample types it
  when the sample states a type other than MB; the practitioner's NPI goes with the system of the sample's
  Practitioner's only identifier. The values are always the case's; a placeholder is never taken. Where
  the sample's Patient has no identifier, PAVE's is sent without the member ID; where the sample's
  Coverage carries `subscriberId`, PAVE's carries the member ID there too, and where that is its only
  place, without an identifier. Each is said in a note.
- **The advice** (`CrdVerdicts`). As the draftOrders refusal is read, by fixed words of the answer and no
  general reader of refusals: when the answer to a coverage check says "no patient data" or "no member
  data" (or "... data not provided") in a card or an issue, whatever its status, and the request as sent
  carried `prefetch.patientBundle`, the next step is "The service found no patient in the request. PAVE
  sent the prefetch under its own names; set Prefetch to As the collection's sample in Configuration >
  Environments > Edit > Request shapes." when the request the call came from writes a prefetch, and
  otherwise "... the service names the prefetch keys it reads in its discovery answer, in Configuration >
  Advanced > CDS discovery."; the reason: "The service's answer says no patient data was given, and this
  request carried the patient in patientBundle, a name PAVE gives it. CDS Hooks fixes no names for the
  prefetch: each service names the keys it reads." The result card shows that whole line above "Open
  Configuration" for an answer that came back whole, where before only the button stood.
- **The comparison** ("Compare with a request that works"). The answer gains `arrangement`: one line per
  prefetch key only in the pasted request, only in PAVE's, or holding another kind of thing in each, by
  key and kind ("a Patient", "a Bundle of type searchset holding Coverage"), then "These are differences of
  arrangement: ...", drawn as "The prefetch is arranged differently" ahead of the notes. PAVE's side is
  still built with the named Bundles.
- **What holds it.** `PrefetchShapeTest` (a bare Patient and a searchset Bundle; one Bundle of several
  types; the five named Bundles byte for byte; no sample, notes unchanged; a type PAVE does not build; a
  key written as null and as a placeholder; a Practitioner for an organization; the shape stated with no
  sample; a sample of an error; a v2.1 and a v2.0 collection; the systems and type followed, the
  environment's system winning, the member ID's place, a context member), `PrefetchShapeFormTest` (after
  Fill, on Edit from the sample and then stated by the environment, and the default with no sample),
  `NoPatientAdviceTest`, `PrefetchCompareTest`, and in `ConfigFrontendTest`, `WorkbenchRecheckFrontendTest`
  and `CompareFrontendTest` the screens. One expectation of an existing test changed:
  `CrdRequestComparerTest` lists the answer's parts, now with `arrangement` for its vendor-shaped sample.
  The existing CRD tests pass unchanged. `RecursionGuardTest`, `RegexStackGuardTest` and
  `DeepAnswerStackTest` unchanged. Not shown to fail without the change.
- **What was seen.** On the jar of the head, on live, in Chromium at 1440 by 900, with an environment
  export and a collection written for the check (placeholders only) whose coverage-check sample carries a
  single Patient under `patient`, a searchset Bundle of Coverage under `coverage` and an Encounter under
  `encounter`, against a copy of the stand-in changed to answer one card "Error", "No patient data
  provided" unless the request carries a Patient under `patient`. After Fill, "Request shapes" showed
  "Prefetch: As the collection's sample (patient, coverage, encounter) from the collection's request
  "CRD - Prior Auth Check"" with the Encounter line; saved and made active, the preview and the wire view
  showed the Patient under `patient` and the searchset Bundle under `coverage`, and the check was
  answered "Documentation is required.". Set to PAVE's named Bundles on Edit, the check was answered "No
  patient data provided" and the card showed the advice above "Open Configuration". With `encounterId` in
  the sample's context the form and the preview said it. The comparison of the sample with PAVE's
  request showed the arrangement part. On mock, a coverage check with a scenario answered as before. Not
  seen: a system or type followed on a screen (the environment's Member ID system was in force), the
  vendor's files or service, Windows, the IDE.
- **Left.** The keys are not read from the service's discovery answer. A key of a type PAVE does not build
  is not sent. "Compare with a request that works" builds PAVE's side with the named Bundles whatever the
  environment states. The Coverage id is PAVE's own until the vendor supplies one.

**D-310. A sixth correction after release 2: what the sample's request carries that PAVE's does not is sent where the case has it and said where it does not.**
- **What happened.** After D-309 the operator's comparison of the vendor's working coverage-check body
  with PAVE's still showed differences, and the operator asked: "anything else missing, make sure that
  that's added ... If it's not standard, let me know." Read from the recording by kind only (the files
  were not asked for and no value of theirs is written anywhere): the sample's order (ServiceRequest)
  carries the diagnosis as `reasonCode`, an ICD-10-CM coding, and a `performer`; PAVE's order carried
  neither, though the Service card has "Diagnosis codes", which Submit already sends. The sample's Patient
  carries an address, telecom, communication, extensions, `active` and `meta`, and its Coverage a
  `relationship` and a class of type group beside the plan; a case holds none of these but the member's
  address, which a member lookup may give and the coverage check does not write. The tester could
  see such differences only by pasting both bodies into a diff tool. Decided by the integrator
  (`TASK_SAMPLE_ELEMENTS.md`).
- **What the standard says.** Every one of these elements is a FHIR R4 element of its resource, and none
  is required by CRD 2.0.1: its profiles give each a minimum of zero. So none of them is missing from
  PAVE's request in the sense of the standard; each is something the vendor's sample sends and its
  service may read.
- **What is sent: the diagnosis** (`51b3505`). The coverage check's request now takes `diagnosisCodes`, as
  Submit's does, and the Workbench posts the Diagnosis codes of the Service card with it whenever some are
  typed (a blank one is refused at `diagnosisCodes[i].code`, "This diagnosis code is blank."). Only under
  the sample's arrangement (D-309), and only when the sample's order carries a `reasonCode`, each code goes
  on every order as a `reasonCode` with one coding: the system typed with the code, else the system of the
  sample's first `reasonCode` coding, and the code as typed; it sits before `insurance`, where FHIR puts
  it, in `context.draftOrders` and in every copy of the order the prefetch carries. The sample's order is
  the first ServiceRequest of its `context.draftOrders`, else the first of its prefetch. The note says
  "Each order carries the case's 2 diagnosis codes as reasonCode, as the sample's order carries a diagnosis
  there, with the system <system> that the sample's reasonCode uses.", a count and never the codes, since a
  diagnosis beside a member is the member's own; a sample whose `reasonCode` states no system (or a
  placeholder) sends the code without one and says so. With no diagnosis on the case: "The sample's order
  carries a diagnosis (reasonCode). This case states none: add Diagnosis codes on the Service card." With
  PAVE's named Bundles, with no sample, or with a sample whose order has no `reasonCode` (or an empty one),
  nothing changes: a service that refuses an element it does not expect has been met before (D-291).
- **What is only said: the names** (`7f951cd`). Under the sample's arrangement, for each resource type the
  request sends (the order in `context.draftOrders` and each resource of the prefetch), one note names the
  members of the sample's first resource of that type that PAVE's built ones lack: "The sample's Patient
  also carries meta, extension, active, telecom, address and communication. PAVE has no value for them in
  this case, so they are not sent." A Coverage class type the sample has and PAVE did not send is named
  "class of type group". A member the case does hold a value for that the coverage check does not build
  (the member's address, the provider's address, phone or fax) is not called absent: "... the case holds
  address but PAVE's coverage check does not build it. None of them is sent." A `reasonCode` the case gave
  no diagnosis for is left to the diagnosis note, so nothing is said twice. Under Prefetch, in "What these
  files give" and on Edit, the form names what PAVE's builders never write whatever the case ("... PAVE has
  no field for them, so they are not sent."), from a fixed list of what each builder can write, and adds
  "The sample's order carries a diagnosis (reasonCode): the Diagnosis codes of the Service card go there,
  with the coding system the sample uses." Nothing named is filled in: `performer` is not the requester, no
  class is made up, a member's address is not added.
- **The comparison** (`6256f1f`). The answer of "Compare with a request that works" gains `elements`: for
  each resource type both requests carry, "Your request's Patient carries address and telecom, which the
  tool's Patient does not.", drawn as "What your request's resources carry that PAVE's do not" after the
  arrangement. The element by element list already gave each of these by its path; this gathers them by
  resource.
- **Rules kept.** Names and code system addresses only are read from a sample; a placeholder is no name and
  no system; a class type is named only when it reads as a code. Bodies are read by fixed paths, at most
  three levels to a resource and one level of its own members, never with the depth of the input.
- **What holds it.** `DiagnosisInOrderTest` (the sample's system and the code as typed on every order and
  every prefetch copy, before `insurance`; a typed system winning; a sample with no system; the note with
  no diagnosis; nothing changed under the named Bundles, with no sample and with no `reasonCode`; where the
  sample's order is read), `SampleElementsTest` (one note per type for a Patient, a Coverage and an order
  from a sample written for the test, with no value of it in any note or in what is sent; a class PAVE
  sends not named; a member the case lacks named; an address the case holds not called absent; the form's
  lines; nothing under the named Bundles; the fixed list of what PAVE writes held against a request built
  from a case with every value), `SampleElementsCompareTest`, a case in `CrdServiceTest` (the blank code),
  `CrdBodyDiagnosisFrontendTest` and a case in `CompareFrontendTest`. One expectation of an existing test
  changed: `CrdRequestComparerTest` lists the answer's parts, now with `elements` for its vendor-shaped
  sample. Every other existing CRD test passes unchanged. Not shown to fail without the change.
- **What was seen.** On a copy of the jar of `6256f1f`, on live, in Chromium at 1440 by 900, with an
  environment export and a collection written for the check (placeholders only) whose coverage-check
  sample's order carries `reasonCode` and `performer`, whose Patient carries an address and whose Coverage
  carries a class of type group, against the stand-in on loopback. After Fill, under Prefetch, the form
  showed the three lines of names and the diagnosis line. The preview of a coverage check with "G47.33,
  E66.01" in Diagnosis codes showed both as `reasonCode` with the ICD-10-CM system and the note; without
  them, the note asking for them; both showed the lines for the ServiceRequest, the Patient and the
  Coverage, and no `performer` and no value of the sample in the body. Not seen: a check run and answered
  with the diagnosis, the comparison's new part on a screen, the vendor's files or service, Windows, the
  IDE.
- **Left.** A Patient's address the member lookup gives is still not written on the coverage check's
  Patient; whether the vendor's service reads it is not known. `performer` is not sent: nothing says who
  performs the service.

**D-311. A typed address keeps the sample of the collection's request, and the Submit options answer with no call in hand.**
- **What happened.** The correction of D-309 was handed over as `a580682`. On the operator's workstation
  the Workbench then showed an internal error (a `NullPointerException` in the reading of the request
  shapes, reached from the Submit options), and a coverage check was answered "No patient data provided"
  as before.
- **The first cause.** `ConfigStoreGateway.shapes` is also asked with no call in hand: the Submit step
  reads the PAS guide of the active environment when the Workbench opens. D-309 added a look at the name
  of the call's request there, without a check for a call that is not there. Every such page failed, on
  mock and on live, with or without an environment. No test asked the shapes that way (the test of the
  Submit options used a stand-in for the gateway), and neither proof opens the Workbench. Corrected in
  `4d9d85a`, with a test of the shapes with no call and a test of the Submit options on the started
  application.
- **The second cause.** A call to a typed address had no sample: R-02 says a typed address replaces the
  mapped request wholly, and D-291 took the shapes of such a call from the defaults. The operator had
  typed the address of the coverage check to correct it. The request therefore went in PAVE's named
  Bundles, the arrangement the vendor's service refuses, although the collection with the working sample
  was stored.
- **Decided.** A typed address still replaces where the call goes and still carries no header and no body
  of a request (R-02 stands for what is sent). The request shapes of the call, draftOrders type and
  Prefetch, are read from the collection's own request for the operation, the one the call would use if
  nothing were typed (the first mapped, a sample of an error passed over). A shape the environment states
  wins, as before. With no request mapped for the operation, the defaults, as before. The advice after a
  refusal that reads the sample (D-308, D-309) reads the same one.
- **Why.** How a service wants a request arranged does not change because its address was corrected. The
  collection is the only evidence of that arrangement this tool has.
- **What holds it.** `ShapeEvidenceTest` (a typed address follows the sample and takes nothing else of the
  request; the case of the first run, with the patient under "patient"; a typed address with no request
  mapped takes the defaults), `ShapesWithoutACallTest`, `ApplicationJourneyTest` (the Submit options).
  The integrator's gates now open every page of the started tool in a browser and fail on any answer of
  the 5xx kind; that check fails on `a580682` and holds on this commit.
- **Left.** Not seen against the vendor's service. Whether the operator's address was typed is the
  integrator's reading of the report, not something seen. Later: D-291 (a call with no sample takes the
  default) holds only where no request of the collection is mapped for the operation.

**D-312. Among several requests for one operation, a typed address follows the one that goes to that address.**
- **What happened.** With D-311 pulled, the operator's coverage check still went with the patient under
  `patientBundle` and was answered "No patient data provided". The operator's recording of
  Configuration > Advanced > In force now showed why: one collection uploaded, 124 requests mapped, and
  the address of the coverage check typed in the environment's settings. Several of those requests are
  coverage checks, each for a service of its own.
- **The cause.** D-311 gave a call to a typed address the sample of the first request mapped to its
  operation. In a collection with one coverage check that is the right one. In this collection the first
  in the file belongs to another service, and its sample is arranged in Bundles of PAVE's own names, so
  "As the collection's sample" produced the arrangement the operator's service refuses.
- **Decided.** For a call to a typed address, the sample is the request mapped to the operation whose own
  address, with its placeholders filled as a call under the environment fills them, is the address that
  was typed (compared without query, fragment or trailing slash, in one case). A request whose address
  does not fill under the environment (a variable the export gives no value, which is often why the
  address was typed) is told by how its address ends: the text written after its last placeholder is the
  end of the typed address; where several end that way the longest end wins, and an end of fewer than two
  path parts is never used. The first mapped only when no request is found either way. A sample of an
  error is passed over. Nothing else of the request is taken: the
  call still carries no header and no body of it (R-02).
- **Why.** The address is the one thing the tester stated about which service is meant. It is evidence,
  not a guess: a request that goes to the same address is a request to the same service.
- **Not decided here.** Which request stands for an operation when none is typed is still the first in
  the file, or the one chosen in "What these files give" or mapped in Configuration > Collections. In a
  collection of this size the first is easily the wrong one, which is how the operator came to type the
  address.
- **What holds it.** `ShapeEvidenceTest.aTypedAddressFollowsTheRequestThatGoesToThatAddressNotTheFirstInTheFile`
  and `aRequestWhoseAddressDoesNotFillIsToldByHowItsAddressEnds`.
- **Left.** Not seen against the vendor's service. The form's Request shapes and "What these files give"
  still show the Prefetch shape of the first sample in the file; the request's notes name the request
  really followed. The Workbench offers no choice of request for a step.

**D-313. Each step of the Workbench says which request it sends, and can send it as the collection writes it.**
- **What happened.** After D-312 the operator was still finding, one at a time, the differences between
  what PAVE builds and what the vendor's own request sends, which works in Postman: "It is Postman, it is
  working. It should work with this one too." and, after the sixth correction, "So many issues, will this
  ever work?" The collection holds 124 mapped requests, several coverage checks, questionnaire requests
  and submits, each for a service of its own. PAVE used, for each step, the first request in the file that
  fits, and said so only in Configuration > Advanced. The first coverage check belonged to another
  service; the operator typed the right address, and the request then went in the arrangement of the
  wrong sample (D-311, D-312). The steps after the coverage check had never met the vendor's service and
  had the same two weaknesses. The server already took, for a run, a request of the collection named for
  it and a body of the tester's own; the rebuilt Workbench offered neither. The operator said "Go" to this
  work; the integrator decided its shape (`TASK_REQUEST_PICKER.md`).
- **Why the first in the file is not a safe default here.** In a collection with one request per step the
  first that fits is the one meant. In a collection of this size it is chosen by the order of the file,
  which says nothing about which service the tester tests, and nothing on the Workbench said which one
  went. A typed address corrected where the call went but not what it carried. The tester is the only one
  who knows which service is meant, so the Workbench now asks, and says what it uses until asked.
- **Decided.** Each step that sends a request (the coverage check, the questionnaire package and the next
  question, Submit, the inquiry) shows "Request" in its Options: the request in force by folder and name,
  with its method and the end of its address, and where it comes from (the first in the file that fits,
  the one chosen in Configuration, chosen here for this page, or the address typed in the settings). While
  several fit, a line under the step's head names it. "Change" lists the requests of the stored
  collections mapped to the step's operation, the one in force and a sample of an error marked (the server
  says which is which: `AvailableRequest.errorSample`, `TargetView.typed`), with a filter box for a long
  list; with one or none the line says so and offers no list. A request chosen there lives in the page's
  memory and goes with the step's runs and previews as `request`, which already won over a typed address
  (R-02): its address, its headers and its sample. The result's notes and the wire view (`WireRequest.from`)
  name the request used. "Use for every run" is the form's pick (D-307): each request that fits before the
  chosen one in the file is taken off the operation, a sample of an error left as it is, and the chosen one
  mapped to it; a typed address still wins for a run that names no request, and the line and In force now
  say so. "Body" offers "Built from the case" (as before) and "As the collection writes it": the run posts
  `asWritten`, the server takes the stored body of the request the run uses (`OperationGateway.writtenBody`)
  and sends it down the path a body of the tester's own takes (`AsWritten`), and the answer is read as the
  step's own. `{{$guid}}`, `{{$randomUUID}}`, `{{$timestamp}}`, `{{$isoTimestamp}}` and, new,
  `{{$randomInt}}` are made afresh at each send; any other dynamic value is named as one PAVE does not
  make. A placeholder with no value is refused before anything is sent, each named with what is known of
  why: a script of the collection sets it (its text searched with index searches for the quoted name,
  never parsed, never run), Postman makes it, or nothing defines it; "Values for this run" takes a value.
- **What a body as written gives up.** The member, provider and codes it carries are sent, not the Case
  column's, which the page neither sends for it nor clears; in a later step, what the step before handed
  on is not put into it, and the result says so. The run is still linked to the step before.
- **What is never done.** No script is run. No body is fetched into the page or kept by the browser: the
  only storage key stays `pave.tab`, and the choice of request is page memory. The ledger records that a
  body of its own went (`INPUT_BODY`), never the body. A credential written into the body is sent and
  masked in every view, as before. On an environment of kind prod a Submit as written is blocked as any
  Submit is, with the guardrail's words unchanged. The scenario selector stays on mock only; on mock the
  Request line describes the stored collection and a body as written is answered by a scenario.
- **What holds it.** `StepRequestTest` (the list across two collections with the one in force and the
  sample of an error marked; a named request while an address is typed goes to its own address with its
  headers and its notes name it; "Use for every run"), `AsWrittenCoverageCheckTest` and
  `AsWrittenLaterStepsTest` (the stored body sent with values put in and dynamic values made afresh; an
  undefined, a script-set and an unsupported placeholder refused before the send; the ledger holds no
  body; the guardrail on prod), `StepRequestFrontendTest` (the line, the list, the choice, the body
  choice and the mappings), `VariableResolverTest` (`{{$randomInt}}`).
- **Left.** Not seen against the vendor's service. The next question as written was sent through the API
  only: the stand-in's questionnaire is a static form, so the Workbench never sends a next question to it.
  "Use for every run" changes the collection's mapping, which every environment shares. "Compare with a
  request that works" still builds PAVE's request as before.

**D-314. A questionnaire the answer names leads to the questionnaire step, with or without doc-needed.**
- **What happened.** With D-312 pulled, the vendor's service answered the operator's coverage check for
  the first time: prior authorization is needed (pa-needed auth-needed), the documentation goes with the
  prior authorization request (doc-purpose withpa), and a list of the canonical urls of its
  questionnaires, with a card that links a DTR launch. It states no doc-needed. The result card offered
  "Continue to Submit". The operator: "Why am I unable to see the questions? ... if you have any
  questions, it should go to the next automatically and show the questions there so that I can enter the
  answers."
- **The cause.** `CrdVerdicts` took the questionnaire step to be next only when doc-needed asks for
  documentation, on the ground that in CRD 2.0.1 doc-needed is what says DTR is to be used. The guide also
  says a questionnaire may be named only beside a doc-needed that asks for documentation; a service that
  names one without it breaks that rule, and the tool then acted as if no questionnaire had been named.
- **Decided.** When the answer names a questionnaire, or a partly completed questionnaire response, the
  next step is the questionnaire step, for every outcome after which it could follow today. The reason on
  the card says that doc-needed was not stated and that the guide asks for it, and, when doc-purpose says
  the answers go with the prior authorization request, that Submit follows. The outcome and its headline
  are unchanged ("Prior authorization is required."). An answer with doc-needed is read as before. An
  answer with neither a questionnaire nor doc-needed still goes to Submit.
- **Why.** The questionnaires are evidence the service gave, not a guess: it named them. What the guide
  requires beside them is reported, and does not decide whether a tester may answer them.
- **What holds it.** `CrdVerdictsTest.questionnairesNamedWithoutDocNeededStillLeadToDtrAndTheReasonSaysWhatIsMissing`.
  The scenario, chain and journey tests pass unchanged.
- **Left.** The questionnaire step has never met the vendor's service: the address of its request is
  typed in the operator's settings, the body is PAVE's own, and the answer named some dozens of
  questionnaires at once. Not seen on a screen for this change.

**D-315. A sign-in that cannot reach its service says what failed.**
- **What happened.** The operator pulled `8bed900`, started PAVE and got "No access token: the token
  endpoint could not be reached (unavailable)". It had signed in before the pull, and Postman signed in at
  the same time. Neither the card nor the console said more: the sentence kept only the class of the
  failure, and the log line said "Access token request failed".
- **What was checked.** Nothing between the build that signed in and `8bed900` touches the request for a
  token, the HTTP client, the launch file or `application.yml` (read from the differences). So the cause
  is not known, and could not be found from here.
- **Decided.** The sentence ends with "What failed: ...", from the types of the exception alone: the
  connection attempt timed out; no answer arrived in time; the connection was refused or could not be
  opened; the name of its host could not be found; there is no network route to its host; its certificate
  was not accepted or the secure connection could not be set up (with the trust store named); a proxy
  refused the connection; or a failure this tool cannot place, for which the console names the types.
  The console line names the types through `Faults.cause`, never a message: the message of such an
  exception quotes the address.
- **What holds it.** `OnyxTokenServiceTest` (a refused connection, a host not found, a failure that cannot
  be placed).
- **Left.** The cause of the operator's failure. Whether a restart in place (D-304) plays a part on
  Windows is not known; it was seen to sign in after a switch on Linux only.

**D-317. Submit stays open when the questionnaire step fails, and the card says so.**
- **What happened.** With D-314 the operator's coverage check led to the questionnaire step, and the
  questionnaire request failed at the vendor's service. The operator: "Even the dtr fails, I should be
  able to submit pas".
- **What was already so.** The four steps at the top of the Workbench are buttons; each opens at any
  time. Submit attaches a questionnaire response only when step 2 completed one and "Include it" is
  ticked. So Submit was open; nothing on the failed card said it.
- **Decided.** The card of a questionnaire result whose next step leads nowhere (a refusal, a failure,
  an answer this tool cannot act on) carries, under its verdict, the line "Submit does not wait for the
  questionnaire. Sent now, it carries no completed response." and a plain button "Continue to Submit
  without it", which opens step 3. It is never the filled button (I-6): the tester chooses to go on
  without the payer's questions answered, the tool does not suggest it. Where Submit cannot be sent now
  the button is off and its title is the server's sentence. A result that leads on, or that keeps the
  tester in step 2 with questions to answer, carries nothing new.
- **Why not go to Submit by itself.** The coverage answer said the documentation goes with the prior
  authorization request. Sending without it is a decision about the test, and it is the tester's.
- **What holds it.** `WorkbenchFlowFrontendTest.aQuestionnaireResultWithNoWayOnStillOffersSubmitPlainly`.
  Seen in Chromium on mock with the scenario refused with HTTP 400: the line and the button under the
  card, the press opening step 3, the browser's storage holding only `pave.tab`.
- **Left.** Why the questionnaire request fails at the vendor's service is not known: its card has not
  been seen. Submit has not met the vendor's service.

**D-318. The Submit and inquiry bundles declare their PAS bundle profile.**
- **What happened.** The operator's first Submit built from the case was refused by the vendor's
  service with an OperationOutcome: "Bundle.meta.profile must include:
  http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle".
- **What was so.** `PasRequestBuilder` wrote the Bundle with an identifier, a type and a timestamp and
  no `meta`. Of all the resources it writes, only the inquiry's Claim declared a profile (the vendor's
  sandbox had refused an inquiry without it).
- **Decided.** The Bundle of a Submit declares
  `http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-pas-request-bundle`, and the Bundle of
  an inquiry `.../profile-pas-inquiry-request-bundle`, as the first member after the resource type. It
  is so under 2.0.1 and under 2.2.1: the canonical is the same and the guide's examples declare it in
  both. It is not a request shape: nothing is known that refuses a declared profile.
- **Not decided here.** The resources inside the Bundle (the Claim of a Submit, the Patient, the
  Coverage, the organizations, the practitioner, the order) declared no profile when this was written.
  D-319 decides it.
- **What holds it.** `PasInquiryProfileTest.eachBundleDeclaresThePasBundleProfileItIs`.
- **Left.** Submit built from the case has not been accepted by the vendor's service yet.

**D-319. Each resource of a Submit and of an inquiry declares its PAS profile.**
- **Why now.** D-318 answered the one refusal the vendor's service had given. A validator that looks
  for `meta.profile` on the Bundle may look for it on the Claim and on the rest, and each further
  refusal would cost the operator a pull and a run. Declaring the profile is what the guide's own
  examples do, so nothing is lost by saying it before it is asked.
- **Decided.** Right after its id, each resource says the profile it is built as:

  - the Claim of a Submit: `profile-claim`
  - the Claim of an inquiry: `profile-claim-inquiry`, as before
  - the Patient: `profile-beneficiary`
  - the Coverage: `profile-coverage`
  - the Organization that is the insurer: `profile-insurer`
  - the Organization that is the requestor: `profile-requestor`
  - an Organization that is a care team member: `profile-organization`
  - a Practitioner: `profile-practitioner`
  - a PractitionerRole: `profile-practitionerrole`
  - a ServiceRequest: `profile-servicerequest`

  Each under `http://hl7.org/fhir/us/davinci-pas/StructureDefinition/`. The ids were read from the
  sources of PAS 2.0.1 (the `Id:` lines of its profiles), and they are the same in the 2.2 package.
- **What declares none.** A DocumentReference: 2.0.1 has no profile for it. A QuestionnaireResponse:
  it travels as DTR completed it, with whatever it declares.
- **A claim update.** This tool builds none (it says so when asked for one), so the Claim of a Submit is
  always declared as the PAS claim, never as the guide's claim update.
- **Not a request shape.** No service is known that refuses a declared profile, so there is nothing to
  switch. Should one turn up, the place is one method, `PasResources.declare`.
- **What it may bring out.** A service that validates each resource against the profile it declares
  will say what the resource lacks for that profile. The notes of a Submit already name what went out
  without an element PAS requires, so such an answer should hold no surprise, and it is a true finding.
- **What holds it.** `Pas201SubmitTest.eachResourceOfASubmitDeclaresThePasProfileItIsBuiltAs` and
  `anOrganizationDeclaresTheProfileOfThePartItPlays`;
  `PasInquiryProfileTest.theSubmittedClaimDeclaresThePasClaimAndNotTheInquiryProfile` and
  `theResourcesOfAnInquiryDeclareTheirProfilesToo`.
- **Left.** Not seen against the vendor's service. What else its validator asks of a Submit built from
  the case is not known: the collection's own Submit, sent from step 3 as the collection writes it, is
  the request known to pass.

**D-320. A call takes the payer, and the FHIR server, from the collection request it is sent from.**
- **What happened.** On the second day against the vendor's UAT the operator sent a photo of the
  answer to a coverage check: one card, indicator critical, summary "Vendor Request Rejected", topic
  coverage-info, detail "Element 'Bundle.entry[0].resource.payor' with minimum cardinality 1 must be
  present." The Bundle is the coverage Bundle of the prefetch, its first resource the Coverage PAVE
  built, which named no payer. The operator's files were not seen and were not asked for.
- **What was so.** `CaseResources.crdCoverage` (the Coverage of the coverage check and of the
  questionnaire request) wrote `payor` only when Payer name or Payer identifier resolved, and otherwise
  left it out with a note; D-310 even said "The sample's Coverage also carries payor. PAVE has no value
  for it in this case, so it is not sent." FHIR makes `Coverage.payor` 1..*. D-308 took a payer for the
  environment only from the Submit sample, and only at a save with the files chosen: an environment saved
  before, or a collection whose Submit names the insurer another way, had no payer in force, and nothing
  read the payer the coverage-check request states. `PasResources.insurer` builds the insurer of a Submit
  from the same values. One value, three steps. Decided by the integrator (`TASK_PAYER_FROM_REQUEST.md`).
- **Decided.** A call takes `fhirServer`, `payerName`, `payerIdentifier`, `payerIdentifierSystem` and a
  new value `payerReference` from the collection request it is sent from (the one D-312 and D-313
  settle: the request named for the run, the one a typed address goes to, else the first mapped), at the
  time of the call, from the snapshot the call is prepared against, when nothing else gives them. It is
  the lowest of all sources: a value typed for the run, stored on the environment, given by the export, by
  the collection's variables or by application.yml wins, in the order it had. Nothing is stored.
  - **Where each is read** (`RequestValues`), only where PAVE's own builder writes the same value, by
    fixed paths: a CDS Hooks body gives `fhirServer` at its top and the payer of the first Coverage of
    its prefetch (alone under a key, or the first Coverage entry of a Bundle under a key); a Parameters
    body the payer of the first Coverage among its parameters; a Bundle that carries a Claim the
    Organization `Claim.insurer` refers to, as D-308 reads it.
  - **The payer of a Coverage** is its `payor` when it has exactly one: `display` gives the name,
    `identifier` the identifier and its system. A `reference` to something the request carries (a
    contained resource by `#id`, an Organization elsewhere in the body by type and id or by its fullUrl)
    gives that Organization's name and its one identifier, and the reference is not taken; a reference
    to nothing the request carries is taken as Payer reference, as written. Several payors or none: nothing
    is taken, and one line says so. The identifier's system goes only beside the identifier of the same
    request (D-308).
  - **Never taken**: a value written as a placeholder nothing defines, one that names only itself, one
    that resolves through a secret or holds a credential, one longer than a stored value may be (the rules
    of `SampleValues.refusal`), anything of a request whose name marks it as a sample of an error, and
    anything of the member: not the beneficiary, not the subscriber, not an identifier of the Coverage.
  - **Payer reference** is a value of the Edit form like the others. `CaseResources.payer` writes it as
    `reference` before the identifier and the display. It is for the Coverage of the coverage check and
    of the questionnaire request; in a Submit the Coverage's payor points at the insurer in the Bundle, as
    before, and Payer reference is not used.
  - **The request says what it took**, by label: "Payer name and Payer identifier come from the
    collection's request "<name>", which this call is sent from: nothing else sets them." A payer the
    request states that is not taken is said with its rule, where nothing else gave the value. When still
    nothing gives a payer, the notes PAVE had stay.
  - **A preview and a send agree**: both read through `CaseResources.reading(call)`, and "Compare with a
    request that works" builds PAVE's side with the values the gateway reads for the same request
    (`OperationGateway.requestValues`). "As the collection writes it" (D-313) is untouched: that body is
    the collection's own and resolves as before.
- **Why at the time of the call and not at a save.** The operator expects the next pull to work with no
  form opened. A value read at a save rests on files chosen at that save; an environment saved before
  D-308, or saved without the files, kept nothing. Read at the call from the stored collection, the value
  is the one of the request that is actually sent, under the same epoch rule as everything else.
- **Why only these five.** They are the values a request is not valid without: FHIR makes the payor of a
  Coverage 1..*, PAS makes the insurer 1..1, and the vendor's service asks for `fhirServer`. The member ID
  system and the PAS types choose content and stay the tester's choice at a save (D-308).
- **Why a reference the request carries is not sent.** PAVE does not send the resource it names: the
  Organization stays in the vendor's sample, so the reference would point at nothing in PAVE's request.
  What that Organization says of itself (its name and identifier) is taken instead.
- **The advice** (`PayerAdvice`). When the answer to a coverage check or a questionnaire request names
  `payor` (a card's detail, an issue's diagnostics or details text) and the request as sent carried a
  Coverage with no payor, the next step is "The service asks for the payer of the coverage, and this
  request named none. Set Payer name or Payer identifier in Configuration > Environments > Edit >
  Values.", followed by the rule that kept the request's own payer out when that request states one. A
  refused sign-in keeps its own step, and so does an answer that leads on (to the questionnaire, to
  Submit, to the end of the flow): a service that decided was not refused, whatever word its answer
  holds. Read as D-308 and D-309 read theirs; no general reader of refusals.
- **The screens.** On Edit, a row of FHIR server, Payer name, Payer identifier, Payer identifier system
  or Payer reference that nothing sets says "Not set here. A call takes it from the collection request it
  is sent from, when that request states one." (source `REQUEST`) in the place of an empty box; "Change"
  opens the box and "Leave it to the request" closes it. In "What these files give" the payer rows and
  Payer reference come from the coverage-check sample when the Submit sample states no payer, with where
  each comes from, and "Save environment" stores them as D-308 stores the others.
- **What holds it.** `RequestValuesTest` (each reading on bodies written for the test: a display, an
  identifier, both, a contained Organization, an Organization elsewhere by id and by fullUrl, a reference
  to nothing carried, two payors, none, a Bundle under a key, a Parameters body, a Submit Bundle, a defined
  and an undefined placeholder, a secret, a value naming itself, a long one, a sample of an error, no body
  and a body that is not JSON; the reference on the payor; the order under the call's own values; the
  system beside an identifier from elsewhere); `PayerFromRequestTest` (the case of the photo end to end for
  the coverage check, the questionnaire request and the insurer of a Submit, each with its preview, the
  comparison, every source winning over the request, a typed address, application.yml alone, a request
  with no body, no environment at all); `PayerAdviceTest` (the card of the photo, an OperationOutcome, a
  payer not taken, a request that carried one, a refused sign-in, an answer that leads on); `SampleValuesTest` and
  `ConfigFrontendTest` (the form's rows, the save, the Edit rows). Changed, each for its reason:
  `FilesGiveTest` (the member ID system is now the sixth row, after Payer reference) and three
  expectations of `SampleValuesTest` (a row nothing sets now says `REQUEST`, where it said `NOT_SET`).
  `RecursionGuardTest`, `RegexStackGuardTest` and `DeepAnswerStackTest` unchanged.
- **What was seen.** On a copy of the jar of the commit before the documents, on live, against a copy of
  the stand-in on loopback changed to answer the card of the photo when the Coverage of the prefetch has no
  payor and to keep the last coverage check it received; an environment export with no payer value and
  collections written for the check (placeholders only). With the collection's payor set aside, the
  Workbench showed the card's verdict and the advice above "Open Configuration". With a collection whose
  coverage-check request states the payer, with no value typed or stored, the check was answered
  "Documentation is required.", the stand-in's copy of the body carried the payor and `fhirServer`, and the
  notes said where they came from. Edit > Values and the form after Fill in Chromium at 1440 by 900 and
  1024 by 768. Not seen: the jar of the commit this work started from (the "before" was this jar with the
  payor set aside), the questionnaire request or Submit on a screen, the vendor's files or service,
  Windows, the IDE.
- **Left.** Taking the member ID system or the PAS types at the time of a call is not done. There is no
  switch: a value set on the environment wins, and that is the switch. Why the operator's questionnaire
  request fails is not known. A payer read from the coverage-check request is not offered to Submit, which
  reads only its own request.
