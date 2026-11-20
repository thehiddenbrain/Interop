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
| D-008, D-130 | `hl7.org` and `build.fhir.org` are blocked from the build environment. Parts of the guides were found readable from other sources late in the build | CRD, PAS and DTR were written from the build prompt and from memory, and so were their mock scenarios. What an independent comparison with the guides then found, and what was done about it, is under D-130 |
| D-053 | The root package and the Gradle group are `org.p32h.interop.epa...`, not `com.thehiddenbrain.interop.epa...` | Changed on the operator's instruction during the build. It overrides the identity table in section 2 |
| D-136, D-140 | The operator named 2.0.1 as the version of the Da Vinci guides to hold the tool to | All three protocols were compared with 2.0.1 by auditors who wrote none of the code and brought in line under four rules (D-140); what is still open is in `PROGRESS.md`. Which version the vendor sandbox follows is still not known |
| D-143 | The production guardrail judges where a request goes, not only which environment is active | The guardrail audit got writes through to production four ways before this; how it was closed, and what it still cannot see (D-145) |
| D-141 | A value nobody supplied is never invented, and a request says what it left out | A service that refuses such a request is not at fault, and the result says so |

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
  sandbox follows. Both shapes are read.
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

**D-152. Replay refuses a line whose correlation value was not kept, and the redaction test finds records by itself.**
Replay treats a refused correlation value as a missing input only where a case sends that value (a
claim id for an inquiry, a questionnaire for a package fetch); elsewhere it is not compared, as
before. An authorization number equal to the member ID is still dropped: writing it would add
nothing the member field does not already hold. `RedactionTest` finds every record under `model`,
`service/suite` and `service/ledger` and fails for one that keeps the generated `toString` while a
component can print text, deciding from the class file itself, because the build file is frozen and
the usual library for building such objects is not on the test class path.

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
- Once the services pass where a run came from (D-182), the gateway words the refusal itself ("a
  replay never carries the production unlock, so its writes to production are always blocked").
  Until then the verdict gains one supporting line that says so, and that line is left out when the
  refusal already says it, so it is never said twice.
- The gateway names the tab a write can be sent from as a noun phrase only ("the Config tab" for a
  request sent from the collection as it is). The verification found the sentence for another
  session holding the unlock garbled by the old phrase; it is now pinned by a gateway test.
- The two halves were built on two branches at the same time: the origin in the protocol services on
  one, the next action, the headline and the supporting line on the other. Each was checked on the
  live profile against a stand-in playing production, which received nothing, but the gateway's own
  sentence for a replay could not appear on either branch. The two are merged and nobody has seen
  them together. Corrects D-023 and D-131 as noted there.

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
The README still describes the body and the 422s as they were; correcting it is open work.

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

**D-191. Who owns which file of the frontend, and what a tab may ask of the frame.**
The parts were built at the same moment by different agents, so each owned files and was forbidden
the rest. The ownership stays the map for a maintainer.
- The frame owns `index.html`, `js/app.js`, `js/api.js`, `js/result.js`, `js/legend.js`,
  `js/workbench.js`, `js/marks.js`, `js/shown.js`, `js/dom.js`, `js/forms.js`, `js/handover.js`,
  `js/case-context.js`, `css/pave.css` and `StaticFrontendTest`. While others build on them, a change
  to a shared file only adds: no exported function, option, class name or custom property is renamed,
  removed or changed in meaning.
- Each protocol tab owns `js/tab-<tab>.js`, its own modules (`js/crd-*`, `js/dtr-*`, `js/pas-*`) and
  `css/<tab>.css`. The case owns `js/lookup.js`, `js/code-picker.js`, `js/own-request.js` and
  `js/run-values.js` with `css/case.css`; History owns `js/tab-history.js`, `js/history-*`,
  `js/suites.js` and `js/chain.js`; Config owns `js/tab-config.js` and `js/config-*`. A tab's style
  rules go in its own stylesheet, using the custom properties and classes of `pave.css`.
- What a tab may ask of the frame, added by the frame pass: `createWorkbench` takes
  `spec.operations`, which withholds the form while the server says the tab's first operation is
  unavailable and brings it back by itself after the next configuration change; it is off unless a tab
  passes it, and no tab passes it at the revision of D-201 (the DTR and PAS tabs read
  `/api/config/effective` themselves and say what is missing; the CRD tab still shows a form that
  cannot send on live with nothing configured). `bench.showResult` honours a tab's own
  `nextStepControl`, so a tab draws the control for an action it takes itself. `bench.track` keeps a
  set and lets a result's controls go with the result; `bench.untrack` exists. `app.operationRow`,
  `app.operationsRead`, `app.whenConfigChanges`, `app.ledgerHealth` and `app.refreshMode({quiet})`
  exist. `handover.js` exports `sameCase`. `additionalFieldsSection` takes the wire as a third
  argument (D-196).
- The shared client sends PUT and DELETE (`api.putJson`, `api.del`), so the Config tab now offers
  changing a mapping, taking a request off an operation and removing an environment or a collection.
  The frame pass drove those flows in a browser on mock and on a production environment, with the
  mouse and the keyboard. The server never answers 409 to them, so that path was never seen.
- Asked of the frame and not done: `bench.clearRefusal` (an earlier 409 refusal stays above a later
  comparison), a way for the case to mark a detail "from the pasted request" and for the lookup strip
  to show a line the page gives, a collapsed form of the chain bar at laptop height, and an action box
  that scrolls with the form below about 800 pixels of height. Each part reported these; the pass that
  joins the tabs is to take them up (D-200).

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
  that step's buttons. Each step says whether it was recorded.
- The PAS tab: no inquiry button after a submission that failed in the environment, because choosing
  between "ask for the status first" and "safe to send again" would read outcome codes; where the
  server's next step is to check the environment and a claim id is known, "Choose the Inquire call"
  switches to the inquiry already filled and sends nothing. Notifications are two short lines each,
  with the server's outcome, the decision when present and the matched run, trimmed to what the
  receiver holds. A preview's notes are introduced by "If it were sent as it stands, the server
  notes:", because the server words them in the past tense. The tester reference and the note show on
  the result.
- The CRD tab: everything of sections 11, 21 and 28.3 is drawn by the CRD detail and the shared result
  view; the pass found nothing missing scenario by scenario.

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
