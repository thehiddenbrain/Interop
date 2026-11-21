package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AnsweredBy;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Timing;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VerdictView;
import org.p32h.interop.epa.pave.model.WireRequest;
import org.p32h.interop.epa.pave.model.WireView;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.config.PreparedCall;
import org.p32h.interop.epa.pave.service.error.Faults;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * What every protocol service does the same way around a call: stamp the run, time it against the
 * threshold, fill the parts of the ledger record that do not depend on the protocol, write the
 * record, and describe a fault.
 *
 * <p>Keeping these here means CRD, DTR and PAS cannot drift apart on what a ledger line holds or
 * on how a timeout is worded.
 */
@Component
public class RunSupport {

    private static final Logger log = LoggerFactory.getLogger(RunSupport.class);

    private final EpaProperties epa;
    private final TrackingProperties tracking;
    private final OnyxProperties onyx;
    private final ObjectProvider<RunLedger> ledger;
    private final Clock clock;

    public RunSupport(EpaProperties epa, TrackingProperties tracking, OnyxProperties onyx, ObjectProvider<RunLedger> ledger, Clock clock) {
        this.epa = epa;
        this.tracking = tracking;
        this.onyx = onyx;
        this.ledger = ledger;
        this.clock = clock;
    }

    public EpaProperties epa() {
        return epa;
    }

    public Clock clock() {
        return clock;
    }

    /** A completed call slower than this is flagged. The Onyx read timeout unless tracking sets its own. */
    public long slowThresholdMillis() {
        return tracking.slowThresholdMs() > 0 ? tracking.slowThresholdMs() : onyx.readTimeoutMs();
    }

    public Timing timing(VendorExchange exchange) {
        // Only a call that completed can be "slow". One that timed out has its own outcome.
        long threshold = slowThresholdMillis();
        boolean completed = exchange.httpStatus() != null;
        return new Timing(exchange.elapsedMillis(), threshold, completed && threshold > 0 && exchange.elapsedMillis() > threshold);
    }

    /**
     * A ledger draft with everything that does not depend on the protocol filled in: the run id,
     * the timestamp, the target, the profile, the environment, the scenario that answered, the
     * status, the timing and the failure type. The caller adds the outcome, the result and its inputs.
     */
    public RunRecord.Builder draft(EpaOperation operation, PreparedCall call, VendorExchange exchange, RunOrigin origin) {
        RunOrigin from = origin == null ? RunOrigin.manual() : origin;
        Timing timing = timing(exchange);
        return RunRecord.builder()
                .runId(RunStamps.newRunId())
                .timestamp(RunStamps.timestamp(clock))
                .protocol(operation.protocol())
                .operation(operation)
                .target(epa.target())
                .profile(epa.mode())
                .environment(call.environment())
                .targetSource(call.requestName())
                .scenarioId(exchange.scenarioId())
                .httpStatus(exchange.httpStatus())
                .elapsedMillis(exchange.elapsedMillis())
                .errorType(exchange.failureType())
                .slow(timing.slow())
                .suiteRunId(from.suiteRunId())
                .caseId(from.caseId())
                .replayOfRunId(from.replayOfRunId());
    }

    /**
     * A ledger draft for a line that records no outbound call: a notification that arrived, or a
     * step completed inside the tool such as a questionnaire response. It has no target, no status
     * and no timing, because nothing was sent.
     */
    public RunRecord.Builder localDraft(EpaOperation operation, EnvironmentRef environment, RunOrigin origin) {
        RunOrigin from = origin == null ? RunOrigin.manual() : origin;
        return RunRecord.builder()
                .runId(RunStamps.newRunId())
                .timestamp(RunStamps.timestamp(clock))
                .protocol(operation.protocol())
                .operation(operation)
                .target(epa.target())
                .profile(epa.mode())
                .environment(environment == null ? EnvironmentRef.none() : environment)
                .suiteRunId(from.suiteRunId())
                .caseId(from.caseId())
                .replayOfRunId(from.replayOfRunId());
    }

    /**
     * A ledger draft for a write the production guardrail refused. Nothing was sent, and the
     * attempt is still recorded: a refused production write is something an owner wants to see.
     * A service does not use this directly: {@link #blocked} builds the draft, records it and
     * returns the result the tester sees.
     */
    public RunRecord.Builder blockedDraft(EpaOperation operation, ProdWriteBlockedException refusal, RunOrigin origin) {
        return localDraft(operation, refusal.environment(), origin)
                .targetSource(refusal.requestName())
                .outcome(RunOutcome.BLOCKED)
                .errorType(RunRecord.ERROR_BLOCKED)
                .result(TestResult.UNVERIFIED);
    }

    /**
     * Fills the parts of a ledger draft that come from the request, the same way for every
     * protocol: the codes, the line of business, the expectation, the note, the tester reference,
     * the chain, the member ID in the form that was sent, the NPI, the date of service and the
     * inputs the line cannot reproduce. The member's name, date of birth and address are in the
     * request and are not read here, because the record has nowhere to put them.
     */
    public RunRecord.Builder fill(RunRecord.Builder draft, RunRequest request) {
        return draft
                .procedureCodes(codes(request))
                .lineOfBusiness(request.member() == null ? null : Texts.trimToNull(request.member().lineOfBusiness()))
                .expected(Texts.trimToNull(request.expected()))
                .note(Texts.trimToNull(request.note()))
                .testerRef(Texts.trimToNull(request.testerRef()))
                .chainId(chainId(request))
                .memberId(Texts.trimToNull(memberId(request).sent()))
                .npi(npi(request))
                .dateOfService(Texts.trimToNull(request.dateOfService()))
                .unrecordedInputs(unrecordedInputs(request));
    }

    /**
     * The chain a run belongs to: none for a blank id, a newly minted id when the request asks for
     * one, otherwise the id given. The id is minted here and nowhere else, and it reaches the
     * tester on the result, so the browser never invents one.
     */
    public static String chainId(RunRequest request) {
        String chainId = Texts.trimToNull(request.chainId());
        return RunRequest.NEW_CHAIN.equals(chainId) ? RunStamps.newChainId() : chainId;
    }

    /** The member ID of a request as typed and as sent. Both empty when the request names no member. */
    public static MemberIdForms memberId(RunRequest request) {
        return MemberIds.forms(request.member() == null ? null : request.member().memberId());
    }

    /** The NPI of a request, or null when it names no provider. */
    public static String npi(RunRequest request) {
        return request.provider() == null ? null : Texts.trimToNull(request.provider().npi());
    }

    /** The procedure codes of a request, without blanks. */
    public static List<String> codes(RunRequest request) {
        return request.procedureCodes() == null ? List.of()
                : request.procedureCodes().stream().filter(Objects::nonNull).map(CodeInput::code).filter(Texts::present)
                .map(String::strip).toList();
    }

    /** True when the tester supplied their own body, to be sent in place of one the tool would build. */
    public static boolean ownBody(RunRequest request) {
        return Texts.present(request.rawBody());
    }

    /** The things a run sent that its ledger line will not hold, so replay can refuse it honestly. */
    public static List<String> unrecordedInputs(RunRequest request) {
        List<String> inputs = new ArrayList<>();
        if (ownBody(request)) {
            inputs.add(RunRecord.INPUT_BODY);
        }
        if (request.memberOverrides() != null && !request.memberOverrides().isEmpty()) {
            inputs.add(RunRecord.INPUT_MEMBER_DETAILS);
        }
        if (request.providerOverrides() != null && !request.providerOverrides().isEmpty()) {
            inputs.add(RunRecord.INPUT_PROVIDER_DETAILS);
        }
        if (request.variables() != null && !request.variables().isEmpty()) {
            inputs.add(RunRecord.INPUT_RUN_VALUES);
        }
        if (request.request() != null) {
            inputs.add(RunRecord.INPUT_CHOSEN_REQUEST);
        }
        if (request.carry() != null && !request.carry().isEmpty()) {
            // Whatever an earlier step handed on is not on this line, so the line alone cannot rebuild the request.
            inputs.add(RunRecord.INPUT_PRIOR_STEP);
        }
        return inputs;
    }

    /**
     * The result of a write the production guardrail refused, built once here so that every
     * protocol answers it the same way. The rule: the service catches the refusal and returns this.
     * The attempt is recorded, the result carries the ledger receipt so the tester is told whether
     * it was, and the controller answers 403 with the result as the body. The verdict says nothing
     * was sent, the test result is unverified because there is nothing to compare, and the wire
     * view shows where the request would have gone, marked as not sent.
     *
     * @param <D> the protocol's detail type. A blocked result has no detail
     */
    public <D> RunEnvelope<D> blocked(EpaOperation operation, ProdWriteBlockedException refusal, RunRequest request,
                                      RunOrigin origin) {
        return blocked(operation, refusal, request, origin, Map.of());
    }

    /**
     * As {@link #blocked(EpaOperation, ProdWriteBlockedException, RunRequest, RunOrigin)}, for a
     * run that names what it was aimed at: the subscription an update or a delete was for, the
     * claim a submission carried, the run it follows from. An owner who reads the ledger for
     * refused production writes can then see what each one would have changed.
     *
     * <p>Only what the request itself names belongs here. Nothing was sent, so nothing a service
     * would have assigned exists, and an identifier this tool would have minted on sending is not
     * minted for a request that did not go.
     *
     * @param correlation what the refused run was about, keyed by the CORR constants of
     *                    {@link RunRecord}. The record checks the keys and the values itself, as it
     *                    does for every line. Null or empty for a run that names nothing
     * @param <D>         the protocol's detail type. A blocked result has no detail
     */
    public <D> RunEnvelope<D> blocked(EpaOperation operation, ProdWriteBlockedException refusal, RunRequest request,
                                      RunOrigin origin, Map<String, String> correlation) {
        NextAction action = carriesNoUnlock(origin) ? NextAction.SEND_ON_ITS_OWN : NextAction.UNLOCK_OR_SWITCH;
        RunRecord record = fill(blockedDraft(operation, refusal, origin), request).correlation(correlation == null ? Map.of() : correlation)
                .nextAction(action).build();
        LedgerReceipt receipt = record(record);
        ResolvedUrl target = refusal.target();
        MemberIdForms memberId = memberId(request);
        WireRequest attempted = new WireRequest(refusal.method(), target == null ? null : target.display(),
                target == null ? null : target.template(), List.of(), null, false,
                "Blocked by the production guardrail. Nothing left this machine.");
        return RunEnvelope.<D>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(operation.protocol())
                .operation(operation)
                .environment(refusal.environment())
                .profile(epa.mode())
                .verdict(VerdictView.of(RunOutcome.BLOCKED, "Nothing was sent. " + refusal.getMessage(), noUnlockLine(origin, refusal)))
                .expectation(Expectations.notCompared(request.expected(), RunOutcome.BLOCKED,
                        "Nothing was sent, so there is nothing to compare with what was expected."))
                .nextStep(NextStepView.of(action, headline(action, refusal), stepReason(action, operation)))
                .wire(new WireView(attempted, null, memberId.typed().isEmpty() ? null : memberId))
                .error(ErrorView.of(ErrorKind.BLOCKED, refusal.getMessage() + " Nothing was sent.", refusal.action()))
                .ledger(receipt)
                .build();
    }

    /**
     * The headline of a blocked run's next step. For a replay or a suite row it is the words of
     * its own action: whatever the refusal says, the gateway may not have been told where the call
     * came from, and then its step opens with the unlock a screen needs, which this run can never use.
     */
    private static String headline(NextAction action, ProdWriteBlockedException refusal) {
        if (action == NextAction.SEND_ON_ITS_OWN) {
            return action.label();
        }
        return Texts.orElse(refusal.action(), "Unlock writes for this session, or switch to an environment that is not production.");
    }

    /**
     * What the next step of a blocked run achieves. The verdict and the error panel already give
     * the refusal in full, so the reason never repeats it. A request sent as written is mapped to
     * no operation, so its reason speaks of the request and of no tab but the one it came from.
     */
    static String stepReason(NextAction action, EpaOperation operation) {
        String subject = operation == EpaOperation.UNMAPPED ? "the request, exactly as written," : "the write";
        if (action == NextAction.SEND_ON_ITS_OWN) {
            return "Sent on its own from a screen, " + subject + " goes out under the production unlock of that browser session.";
        }
        return "With production writes unlocked in this browser session, where the installation allows them, " + subject
                + " goes to the production environment; with an environment that is not production active, it goes there instead.";
    }

    /**
     * For a replay or a suite row whose refusal does not already say so, the one line that does: it
     * never carries the production unlock. The gateway says it itself when it was told the origin,
     * and saying it twice would read as two reasons.
     */
    private static List<String> noUnlockLine(RunOrigin origin, ProdWriteBlockedException refusal) {
        if (!carriesNoUnlock(origin) || Texts.orElse(refusal.getMessage(), "").contains(NEVER_CARRIES)) {
            return List.of();
        }
        String carrier = Texts.present(origin.replayOfRunId()) ? "a replay" : "a suite row";
        return List.of("This run is " + carrier + ", and " + carrier + " " + NEVER_CARRIES + ", so unlocking writes on the Config "
                + "tab does not let it through.");
    }

    /** The words in which the gateway says a replay or a suite has no unlock, so the line above is not said twice. */
    private static final String NEVER_CARRIES = "never carries the production unlock";

    /**
     * True for a run that came from a replay or a suite. Neither ever carries the production unlock,
     * so telling it to unlock would send the tester round in a circle.
     */
    static boolean carriesNoUnlock(RunOrigin origin) {
        return origin != null && (Texts.present(origin.replayOfRunId()) || Texts.present(origin.suiteRunId()));
    }

    /**
     * The result of a call this tool did not send because it could not sign in, the same for every
     * protocol. Each service returns it as soon as its client answers, before anything reads the
     * exchange: the outcome rules would otherwise read the missing response as the service being
     * down, which says something false about an environment no request reached.
     *
     * <p>Its words follow the cause. A setting not given, or a token endpoint that refused the
     * client, is the tester's configuration, with the Config tab as the way on; a token endpoint
     * that could not be reached, failed or gave no usable token is a fault in the environment at the
     * token endpoint. Neither draws an address called: nothing reached the service.
     *
     * @param draft the draft as filled so far. Its outcome, result and next action are set here
     * @param <D>   the protocol's detail type. A result of this kind has no detail
     */
    public <D> RunEnvelope<D> notSignedIn(EpaOperation operation, PreparedCall call, VendorExchange exchange, RunRecord.Builder draft,
                                          RunRequest request) {
        boolean endpoint = exchange.failureType() == OnyxFailureType.TOKEN_ENDPOINT_FAULT;
        String detail = Texts.orElse(exchange.failureDetail(), "No access token was obtained. The request was not sent.");
        NextStepView step = endpoint
                ? NextStepView.of(NextAction.CHECK_ENVIRONMENT, "Next: check the token endpoint, then send again.",
                "Nothing reached the service, so this says nothing about it, the member or the code. The token endpoint is "
                        + "onyx.auth.token-url on the Config tab.")
                : NextStepView.of(NextAction.OPEN_CONFIG, "Next: correct the sign-in settings, then send again.",
                "Nothing reached the service, so this says nothing about it, the member or the code. The line above says which "
                        + "setting, or which answer of the token endpoint, stopped it.");
        VerdictView verdict = endpoint
                ? VerdictView.of(RunOutcome.NOT_SIGNED_IN, "Nothing was sent: the token endpoint gave this tool no access token.",
                List.of(detail, "This is a fault in the environment at the token endpoint, not at the service: no request reached the "
                        + "service."))
                : VerdictView.of(RunOutcome.NOT_SIGNED_IN, "Nothing was sent: this tool could not sign in.",
                List.of(detail, "This is about this tool's sign-in settings, not about the service: no request reached it."));
        RunRecord record = draft.outcome(RunOutcome.NOT_SIGNED_IN).errorType(exchange.failureType()).result(TestResult.UNVERIFIED)
                .nextAction(step.action()).build();
        LedgerReceipt receipt = record(record);
        MemberIdForms memberId = memberId(request);
        return RunEnvelope.<D>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(operation.protocol())
                .operation(operation)
                .environment(call.environment())
                .profile(epa.mode())
                // Stated, because the envelope reads a refusal from the guardrail's tone, which this outcome does not take.
                .answeredBy(AnsweredBy.NOT_SENT)
                .verdict(verdict)
                .expectation(Expectations.notCompared(request.expected(), RunOutcome.NOT_SIGNED_IN,
                        "Nothing was sent, so there is nothing to compare with what was expected."))
                .nextStep(step)
                .wire(new WireView(exchange.request(), null, memberId.typed().isEmpty() ? null : memberId))
                .error(notSignedInError(exchange))
                .ledger(receipt)
                .build();
    }

    /**
     * What to do after a run that was not sent because this tool could not sign in, in one sentence, from the error type the
     * ledger line keeps. The next action alone names an environment that failed or a tool not configured, neither of which
     * is true of a token endpoint that answered 400 or of a setting not given. Also read by the suite results.
     */
    public static String notSignedInInstruction(String errorType) {
        return OnyxFailureType.TOKEN_ENDPOINT_FAULT.name().equals(errorType)
                ? "Check the token endpoint (onyx.auth.token-url): it gave this tool no access token, so nothing was sent to the service."
                : "Correct the sign-in settings on the Config tab: nothing was sent.";
    }

    /** True when an exchange is a call this tool did not send because it could not sign in. */
    public static boolean notSignedIn(VendorExchange exchange) {
        return exchange != null && (exchange.failureType() == OnyxFailureType.NOT_SIGNED_IN
                || exchange.failureType() == OnyxFailureType.TOKEN_ENDPOINT_FAULT);
    }

    /** The error panel of a call that was not signed in for: the tester's settings, or the token endpoint. */
    private static ErrorView notSignedInError(VendorExchange exchange) {
        String detail = Texts.orElse(exchange.failureDetail(), "No access token was obtained. The request was not sent.");
        if (exchange.failureType() == OnyxFailureType.TOKEN_ENDPOINT_FAULT) {
            // UNREACHABLE is the panel's kind for an environment to report; the heading and the detail say it was the token endpoint.
            return ErrorView.of(ErrorKind.UNREACHABLE, detail, "Note the time and the run id and report it to the people who look after "
                    + "the token endpoint. Try again later.").withHeading("Not sent: the token endpoint gave no access token");
        }
        return ErrorView.of(ErrorKind.CONFIGURATION, detail, "Check onyx.auth.token-url, onyx.auth.client-id and "
                + "onyx.auth.client-secret, and the variables of the active environment they name.")
                .withHeading("Not sent: this tool could not sign in");
    }

    /**
     * True when a call went out with no Authorization header while signing in is off. Read from the
     * setting and from the request as it went on the wire, so a collection request that carried its
     * own Authorization header, or one a value for one run gave one, is not said to have had none.
     */
    public boolean sentWithoutSignIn(VendorExchange exchange) {
        if (onyx.auth().enabled() || exchange == null || exchange.request() == null || !exchange.request().sent()) {
            return false;
        }
        return exchange.request().headers() == null || exchange.request().headers().stream()
                .noneMatch(header -> header != null && "Authorization".equalsIgnoreCase(Texts.orElse(header.name(), "").strip()));
    }

    /**
     * The next step after a 401 or a 403 to a request that carried no Authorization header because
     * signing in is off: that, and not a wrong credential, is the likely cause. Any other step is
     * returned as it is, so the words of a verdict stay true when a header did go out.
     */
    public NextStepView unsignedNextStep(NextStepView step, VendorExchange exchange) {
        Integer status = exchange == null ? null : exchange.httpStatus();
        if (step == null || status == null || (status != 401 && status != 403) || !sentWithoutSignIn(exchange)) {
            return step;
        }
        return new NextStepView(NextAction.OPEN_CONFIG, "Next: switch signing in on, then send again.",
                "This tool sent no Authorization header: signing in is off and the request carried none of its own. Ready to send, on "
                        + "the Config tab, gives the start-up arguments that switch it on.", step.carry(), step.carryLabels());
    }

    /** True when a result is the one {@link #blocked} builds, which a controller answers with 403. */
    public static boolean isBlocked(RunEnvelope<?> envelope) {
        return envelope != null && envelope.error() != null && envelope.error().kind() == ErrorKind.BLOCKED;
    }

    /**
     * The result of a run in which the service was called and this tool then failed to read its
     * answer. The rule, the same for every protocol: the service wraps only the reading and wording
     * of the response in a try, and on a failure returns this. The failure is logged under a
     * reference, the run is recorded as a failure of the tool, and the result still carries the
     * request that went out, the response as received and the ledger receipt. A tester whose
     * submission reached the payer must never be told only "internal error".
     *
     * @param draft the draft as filled so far. Its outcome, result and next action are set here
     * @param <D>   the protocol's detail type. A result of this kind has no detail
     */
    public <D> RunEnvelope<D> toolError(EpaOperation operation, PreparedCall call, VendorExchange exchange, RunRecord.Builder draft,
                                        RunRequest request, RuntimeException error) {
        String reference = UUID.randomUUID().toString();
        // The exception types and frames only. Never its message, which can quote a body or a url.
        log.error("A run failed inside this tool after the service was called, reference {}: {}", reference, Faults.describe(error));
        RunRecord record = draft.outcome(RunOutcome.TOOL_ERROR).result(TestResult.UNVERIFIED).nextAction(NextAction.REVIEW).build();
        LedgerReceipt receipt = record(record);
        MemberIdForms memberId = memberId(request);
        List<String> supporting = new ArrayList<>();
        String scenario = scenarioLine(exchange);
        if (scenario != null) {
            supporting.add(scenario);
        }
        supporting.add(exchange.httpStatus() == null ? "No response arrived from the service."
                : "The service answered with HTTP " + exchange.httpStatus() + ". Its answer is shown below " + asReceived(exchange) + ".");
        return RunEnvelope.<D>builder()
                .runId(record.runId())
                .chainId(record.chainId())
                .protocol(operation.protocol())
                .operation(operation)
                .environment(call.environment())
                .profile(epa.mode())
                .verdict(VerdictView.of(RunOutcome.TOOL_ERROR, "The request was sent, and this tool then failed while reading the answer.",
                        supporting))
                .expectation(Expectations.notCompared(request.expected(), RunOutcome.TOOL_ERROR,
                        "This tool failed before it could compare the answer with what was expected."))
                .nextStep(NextStepView.of(NextAction.REVIEW, "Next: read the response as received, below, and report the reference and the cause.",
                        "This is a defect in this tool, not a finding about the service, the member or the code."))
                .additionalFields(exchange.bodyFields())
                .wire(exchange.wire(memberId.typed().isEmpty() ? null : memberId))
                .error(ErrorView.of(ErrorKind.INTERNAL, "This tool hit an internal error after the service had been called.",
                                Faults.REPORT_ACTION + " The request was sent: do not assume it had no effect.")
                        .withReference(reference).withCause(Faults.cause(error)).withHttpStatus(exchange.httpStatus()))
                .ledger(receipt)
                .timing(timing(exchange))
                .build();
    }

    /** True when a result is the one {@link #toolError} builds, which a controller answers with 500. */
    public static boolean isToolError(RunEnvelope<?> envelope) {
        return envelope != null && envelope.error() != null && envelope.error().kind() == ErrorKind.INTERNAL;
    }

    /**
     * The line that says which mock scenario answered and how it came to be the one, or null when
     * the answer came from a real service. Every protocol puts it first under the verdict. Without
     * it a default scenario's answer reads as an answer about the member and code on the form.
     */
    public static String scenarioLine(VendorExchange exchange) {
        if (exchange == null || Texts.blank(exchange.scenarioId())) {
            return null;
        }
        return "Mock scenario " + exchange.scenarioId() + " answered"
                + (Texts.present(exchange.scenarioNote()) ? ": " + exchange.scenarioNote() : "") + ".";
    }

    /**
     * Writes the record and returns what the ledger did with it. Never throws: a run whose record
     * could not be written still returns its result, and the receipt tells the screen to say so.
     */
    public LedgerReceipt record(RunRecord draft) {
        RunLedger target = ledger.getIfAvailable();
        if (target == null) {
            return new LedgerReceipt(false, withoutMemberData(draft), "This run was NOT recorded: no ledger is available in this build.",
                    null);
        }
        try {
            return target.record(draft);
        } catch (RuntimeException e) {
            return new LedgerReceipt(false, withoutMemberData(draft), "This run was NOT recorded: the ledger failed while writing it ("
                    + e.getClass().getSimpleName() + ").", null);
        }
    }

    /**
     * The draft with everything the tracking policy governs removed. A receipt always carries the
     * record, and normally the ledger has applied the policy to it. When no ledger did, this is the
     * one form that is right under any policy: the real member ID must not ride along in a receipt
     * just because the write failed.
     */
    private static RunRecord withoutMemberData(RunRecord draft) {
        Map<String, String> correlation = new LinkedHashMap<>(draft.correlation());
        correlation.remove(RunRecord.CORR_AUTHORIZATION_REF);
        return draft.toBuilder().memberId(null).memberToken(null).memberIdLast4(null).npi(null).dateOfService(null)
                .correlation(correlation).build();
    }

    /** The finding for a run that used a collection request with a pre-request script, or null when it did not. */
    public Finding scriptFinding(PreparedCall call) {
        if (!call.scripted()) {
            return null;
        }
        return new Finding(FindingKind.PRE_REQUEST_SCRIPT_NOT_RUN,
                "The collection request has a pre-request script that was not run",
                "This tool never runs Postman scripts. Any value the script would have computed, such as a token, a date or a "
                        + "generated id, was not computed. If the request failed for no visible reason, look here first.",
                call.requestName(), null, null);
    }

    /** The finding for a call that completed but took longer than the threshold, or null. */
    public Finding slowFinding(Timing timing) {
        if (timing == null || !timing.slow()) {
            return null;
        }
        return new Finding(FindingKind.SLOW_RESPONSE,
                "The response was slow",
                "The call completed in " + timing.elapsedMillis() + " ms, which is longer than the threshold of "
                        + timing.thresholdMillis() + " ms.", null, null, null);
    }

    /** The heading of a timeout a mock scenario played, which no environment caused. */
    public static final String PLAYED_TIMEOUT_HEADING = "A timeout played by a mock scenario, not a fault in the environment";

    /**
     * Describes a vendor fault for the error panel, the same way for every protocol. Null when the
     * call did not fail at the transport or HTTP level.
     *
     * @param issues the issues of an OperationOutcome the service returned, or empty
     */
    public ErrorView faultView(VendorExchange exchange, List<IssueView> issues) {
        OnyxFailureType failure = exchange.failureType();
        String status = exchange.httpStatus() == null ? "" : " with HTTP " + exchange.httpStatus();
        int code = exchange.httpStatus() == null ? 0 : exchange.httpStatus();
        ErrorView view = switch (failure) {
            case NONE -> null;
            // The same status can mean the body, the credentials or the address is at fault, and
            // the tester's next move differs for each, so the advice follows the status.
            case BAD_REQUEST -> ErrorView.of(ErrorKind.VENDOR_REJECTED, "The service did not accept the request as sent, answering"
                            + status + ".",
                    (code == 401 || code == 403) && sentWithoutSignIn(exchange)
                            ? "This tool sent no Authorization header: signing in is off (onyx.auth.enabled is false) and the request "
                            + "carried none of its own. Ready to send, on the Config tab, says how to switch signing in on. The request "
                            + "that was sent is in the wire view."
                            : code == 401 || code == 403
                            ? "This status means the credentials were not accepted, or none were sent. Check the client id, the "
                            + "secret and the token url on the Config tab. The request that was sent is in the wire view."
                            : code == 404
                            ? "This status usually means the url or the service id is wrong. Check it on the Config tab. The request "
                            + "that was sent is in the wire view."
                            : "Read the issues it returned, correct the request and send again. The request that was sent is in the "
                            + "wire view.");
            case SERVER_ERROR -> ErrorView.of(ErrorKind.VENDOR_ERROR, "The service failed" + status + ".",
                    "This is a fault in the environment, not in the request. Note the time and the run id and report it.");
            // No advice to send again here. This panel is the same for a read and for a write, and
            // a write that timed out may have reached the service: the next step, which knows what
            // was sent, says whether to send again or to ask first.
            case TIMEOUT -> epa.mock() && exchange.playedTimeout()
                    // A scenario plays a timeout at once, so the time beside it is not how long a
                    // service was waited for. This panel is the one place the answer says what was
                    // played: the verdict and the next step speak only of what a real timeout would mean.
                    ? ErrorView.of(ErrorKind.TIMEOUT, "A mock scenario played a timeout. Nothing was sent and no environment failed: "
                            + "the response time shown, " + exchange.elapsedMillis() + " ms, is the scenario's own delay.",
                    "Had this been a real service, this tool would have waited up to " + onyx.readTimeoutMs() + " ms for an answer "
                            + "(onyx.read-timeout-ms) before giving up. The next step says what a tester would do then.")
                    .withHeading(PLAYED_TIMEOUT_HEADING)
                    : ErrorView.of(ErrorKind.TIMEOUT,
                    "No response arrived within the read timeout" + (epa.mock() ? "" : " of " + onyx.readTimeoutMs() + " ms") + ".",
                    "The service may be slow or down. If it repeats, report the environment. The next step says whether to send again.");
            case UNAVAILABLE -> ErrorView.of(ErrorKind.UNREACHABLE,
                    Texts.orElse(exchange.failureDetail(), "The service could not be reached."),
                    "Check the url on the Config tab, the network and any proxy or trust store settings.");
            // A run never reaches this: each service returns notSignedIn first. The case keeps the switch exhaustive.
            case NOT_SIGNED_IN, TOKEN_ENDPOINT_FAULT -> notSignedInError(exchange);
            case MALFORMED_RESPONSE -> exchange.responseBody() == null
                    ? ErrorView.of(ErrorKind.MALFORMED_RESPONSE, "The service answered" + status + " with no body.",
                    "A success with nothing in it is not an answer. Report it as a defect in the service.")
                    : ErrorView.of(ErrorKind.MALFORMED_RESPONSE,
                    ("The service answered" + status + " with a body this tool could not read. "
                            + Texts.orElse(exchange.parseError(), "")).strip(),
                    "The body is shown " + asReceived(exchange) + ". Report it as a defect in the service.");
        };
        if (view == null) {
            return null;
        }
        // On the mock profile nothing leaves this machine: every fault of the service is one a
        // scenario played, so it is headed as played and its action reports no environment. A
        // played timeout is worded above, with the scenario's own delay.
        if (epa.mock() && failure != OnyxFailureType.TIMEOUT) {
            view = played(view, failure, code, exchange);
        }
        return view.withHttpStatus(exchange.httpStatus()).withIssues(issues == null ? List.of() : issues);
    }

    /** The heading of a rejection a mock scenario played. */
    public static final String PLAYED_REJECTION_HEADING = "A rejection played by a mock scenario, not by a service";
    /** The heading of a failure of the service a mock scenario played. */
    public static final String PLAYED_FAILURE_HEADING = "A failure played by a mock scenario, not a fault in the environment";
    /** The heading of an unreachable service a mock scenario played. */
    public static final String PLAYED_UNREACHABLE_HEADING = "An unreachable service played by a mock scenario, not a fault in the environment";
    /** The heading of an answer a mock scenario played that cannot be read. */
    public static final String PLAYED_UNREADABLE_HEADING = "An unreadable answer played by a mock scenario, not a fault in the environment";

    /** The same fault, headed as played by a mock scenario, with an action that says what it would mean from a real service. */
    private static ErrorView played(ErrorView view, OnyxFailureType failure, int code, VendorExchange exchange) {
        String nothingLeft = "Nothing left this machine: a mock scenario played this answer to show how this tool reads it. ";
        return switch (failure) {
            case BAD_REQUEST -> new ErrorView(view.kind(), view.title(), nothingLeft + (code == 401 || code == 403
                    ? "From a real service this status would mean the credentials were not accepted, or none were sent."
                    : code == 404 ? "From a real service this status would usually mean the url or the service id is wrong."
                    : "From a real service, read the issues it returned and correct the request."), view.httpStatus(), view.fieldErrors(),
                    view.issues(), view.reference(), PLAYED_REJECTION_HEADING, view.cause());
            case SERVER_ERROR -> new ErrorView(view.kind(), view.title(), nothingLeft + "From a real service it would be a fault in the "
                    + "environment, to report with the time and the run id.", view.httpStatus(), view.fieldErrors(), view.issues(),
                    view.reference(), PLAYED_FAILURE_HEADING, view.cause());
            case UNAVAILABLE -> new ErrorView(view.kind(), view.title(), nothingLeft + "With a real service, check the url on the Config "
                    + "tab, the network and any proxy or trust store settings.", view.httpStatus(), view.fieldErrors(), view.issues(),
                    view.reference(), PLAYED_UNREACHABLE_HEADING, view.cause());
            case MALFORMED_RESPONSE -> new ErrorView(view.kind(), view.title(), nothingLeft + (exchange.responseBody() == null
                    ? "From a real service, a success with nothing in it would be a defect in the service to report."
                    : "The body is shown " + asReceived(exchange) + ". From a real service it would be a defect in the service to report."),
                    view.httpStatus(), view.fieldErrors(), view.issues(), view.reference(), PLAYED_UNREADABLE_HEADING, view.cause());
            default -> view;
        };
    }

    /**
     * How a response is shown, in the words a sentence about it can use: exactly as received,
     * unless a place in it repeats a secret this tool holds and is masked. "Exactly" is then not
     * true, and the wire view says how many places were masked.
     */
    private static String asReceived(VendorExchange exchange) {
        return exchange.wireResponse().maskedPlaces() == 0 ? "exactly as received"
                : "as received, apart from the places the wire view says are masked";
    }
}
