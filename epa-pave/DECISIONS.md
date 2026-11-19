# DECISIONS

Every ambiguity resolved during the build, and how. Newest entries are at the bottom of each
section. Where two readings were possible, the one that is safer if wrong was chosen.

"The build prompt" below means the document this tool was built from. Section numbers refer to it.

## Things the operator should read first

| Id | What | Why it matters |
| --- | --- | --- |
| D-001 | The reference implementation `onyx-crd-harness-1.2.0.zip` was not available | Everything here was written fresh. Nothing was reused from it |
| D-004 | `build.gradle` as given in section 2 does not resolve. One line was added | A changed build file nobody is told about is a mystery for the next build |
| D-005 | The Spring Boot 4.0.7 BOM manages JUnit Jupiter 6.0.3, and the stack table says JUnit 5 | The pin in the stack table does not match what resolves |
| D-008 | `hl7.org` and `build.fhir.org` are blocked from the build environment | Implementation guide details could not be checked against the published guides |
| D-053 | The root package and the Gradle group are `org.p32h.interop.epa...`, not `com.thehiddenbrain.interop.epa...` | Changed on the operator's instruction during the build. It overrides the identity table in section 2 |

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

**D-024. An environment's kind is required at upload and never inferred.**
A Postman export does not say whether it is production. The kind is supplied at upload. An
environment whose name or urls contain "prod" cannot be uploaded as sandbox or uat without an
explicit acknowledgement, because mislabelling production is the one mistake the guardrail cannot
recover from.

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

**D-098. The Onyx token goes only to an Onyx host.**
A vendor collection can hold a request to another service. The Onyx access token is attached to a
raw request only when its host is one that an Onyx operation resolves to in the configuration in
force, and only when the request carries no Authorization header of its own. Otherwise no token
is requested at all.

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

**D-117. API responses are never stored by the browser, and pages load from this tool only.**
Every `/api/` response carries `Cache-Control: no-store`: a lookup answers with a member's name and
date of birth, and "no member data is persisted" has to include the browser's disk cache. Every
response carries a Content-Security-Policy that allows scripts, styles and connections from this
tool only, so vendor text that ever reached the page as markup could neither run nor call out,
and "no request leaves the machine for an asset" is enforced by the browser as well as by a test.
Static files are served `no-cache`, so a tester who edits one sees the edit on refresh.

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

