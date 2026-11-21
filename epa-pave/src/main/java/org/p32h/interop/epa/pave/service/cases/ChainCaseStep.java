package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.LedgerReceipt;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.RunStamps;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.pas.PasService;
import org.p32h.interop.epa.pave.service.run.RunRequest;
import org.p32h.interop.epa.pave.service.run.RunSupport;

/**
 * Runs a whole journey from one case: CRD first, then whatever CRD says comes next, as far as a
 * tool can go without a person.
 *
 * <p>It follows what each step states as its next step and nothing else:
 * <ul>
 *   <li>CRD says the flow ends: the chain ends, and that is the result.</li>
 *   <li>CRD says to submit: PAS is submitted with the same member, provider and codes, and with
 *       what CRD handed on.</li>
 *   <li>CRD says documentation is needed: the questionnaire package is fetched. If the package
 *       then says to submit, PAS is submitted. If it says questions have to be answered, the
 *       chain stops there and says so: the answers are a person's, and a tool that made them up
 *       would be testing its own invention.</li>
 *   <li>Anything else, such as a member the service does not know or a service that failed: the
 *       chain stops at that step and repeats what the step said.</li>
 * </ul>
 * Every call joins one chain id, so the journey is one row in history, and each step after the
 * first is handed the run id of the step before it, so its ledger line names the step it followed.
 *
 * <p>The member and the provider are resolved once, by the runner, and every step is given the
 * same ones. A case never carries a production unlock, so a submission against a production
 * environment is blocked, and the case is reported as blocked with the steps before it kept.
 *
 * <p>When a step after the first cannot be sent, because the configuration changed, the step's
 * inputs were refused, or this tool failed, the steps already sent stay in the result and the
 * case says which were sent and which was not. Nothing that went out is ever reported as not sent.
 *
 * <p>The test result of a chain is stated for the case as a whole: passed when every step the
 * case expected something of ran and matched; failed when one did not match, or when the chain
 * never reached a step the case expected an answer from; unverified when the case expected
 * nothing. A chain that stops early can therefore not pass by leaving a step out. No single step's
 * ledger line can hold that result, so it is written on a line of its own that names the last
 * step, and the ledger reads it back with that step. A chain stopped part way, before a step that
 * was due, writes that line too, as unverified: nothing was compared, and without it History would
 * show the steps that went out as if they were the whole case.
 */
public final class ChainCaseStep implements CaseStep {

    private final CrdService crd;
    private final DtrService dtr;
    private final PasService pas;
    private final OutcomeCatalogue outcomes;
    private final RunSupport support;

    /**
     * @param support writes the line that records the result of the case as a whole. Null in a
     *                build with no ledger, where the steps' own lines are all there is
     */
    public ChainCaseStep(CrdService crd, DtrService dtr, PasService pas, OutcomeCatalogue outcomes, RunSupport support) {
        this.crd = crd;
        this.dtr = dtr;
        this.pas = pas;
        this.outcomes = outcomes;
        this.support = support;
    }

    @Override
    public CaseRun run(CaseContext context) {
        CaseSpec spec = context.spec();
        // Everything that can be refused is refused before the first call, so a chain is never half run for a typing mistake.
        List<FieldError> errors = new ArrayList<>();
        ChainExpectation expectation = ChainExpectation.parse(spec.expected(), outcomes);
        expectation.problems().forEach(problem -> errors.add(new FieldError("expected", problem)));
        if (Texts.present(spec.scenarioId())) {
            errors.add(new FieldError("scenarioId", SCENARIO_REFUSED));
        }
        if (spec.operation() != null && !startsAChain(spec.operation())) {
            errors.add(new FieldError("operation", OPERATION_REFUSED));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String hook = spec.operation() == EpaOperation.CRD_ORDER_SELECT ? CrdRunRequest.ORDER_SELECT : CrdRunRequest.ORDER_SIGN;

        List<RunEnvelope<?>> done = new ArrayList<>();
        // NEW_CHAIN and not blank: a blank chain id means a run that belongs to no chain, and the
        // steps this journey runs after the first one need a chain to join.
        String chainId = Texts.orElse(Texts.trimToNull(spec.chainId()), RunRequest.NEW_CHAIN);
        // The first call is not caught here: nothing has been sent before it, so the runner reports it as for any case.
        RunEnvelope<?> first = crd.run(CrdCaseStep.request(context, hook, expectation.of(Protocol.CRD), chainId), context.origin(), null);
        done.add(first);
        String stopped;
        try {
            stopped = follow(context, expectation, first, done);
        } catch (Interrupted interrupted) {
            // Nothing was compared, so the case is unverified. Its line still says the case ran, so History does not
            // show the steps that went out as if they were the whole of it.
            return new CaseRun(done, null, null, interrupted.interruption, recordCaseResult(context, done, TestResult.UNVERIFIED));
        }
        return finish(context, expectation, done, stopped);
    }

    /** The CRD part of the chain's expectation, which the first call is sent with and checked against. */
    @Override
    public String firstExpectation(CaseSpec spec) {
        return ChainExpectation.parse(spec.expected(), outcomes).of(Protocol.CRD);
    }

    /** The sentence for a chain row that chooses a scenario. */
    static final String SCENARIO_REFUSED = "A chain cannot choose a scenario. Each of its steps is answered by a scenario of its "
            + "own protocol, picked by the member ID. Run the step by itself to choose its scenario.";
    /** The sentence for a chain row that names an operation it cannot start with. */
    static final String OPERATION_REFUSED = "A chain starts with a CRD hook, order-sign or order-select.";

    /** True for the operations a chain can start with. */
    public static boolean startsAChain(EpaOperation operation) {
        return operation == EpaOperation.CRD_ORDER_SIGN || operation == EpaOperation.CRD_ORDER_SELECT;
    }

    /** Makes the calls that follow CRD and returns why the chain stopped where it did. */
    private String follow(CaseContext context, ChainExpectation expectation, RunEnvelope<?> crdResult, List<RunEnvelope<?>> done) {
        if (cannotGoOn(crdResult)) {
            return null;
        }
        NextStepView next = crdResult.nextStep();
        String chainId = crdResult.chainId();
        if (next.action() == NextAction.START_PAS) {
            return submit(context, expectation, chainId, handedOn(next.carry(), crdResult), done);
        }
        if (next.action() == NextAction.START_DTR) {
            Map<String, List<String>> carry = handedOn(next.carry(), crdResult);
            RunEnvelope<?> fetched = step(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE, context.spec().configEpoch() != null, done, () -> dtr.runPackage(
                    DtrCaseStep.packageRequest(context, expectation.of(Protocol.DTR), chainId, List.of(), carry), context.origin(), null));
            if (cannotGoOn(fetched)) {
                return null;
            }
            NextStepView afterPackage = fetched.nextStep();
            if (afterPackage.action() == NextAction.START_PAS) {
                return submit(context, expectation, chainId, handedOn(afterPackage.carry(), fetched), done);
            }
            if (afterPackage.action() == NextAction.ANSWER_NEXT_QUESTION || afterPackage.action() == NextAction.SUBMIT_RESPONSE) {
                return "The chain stopped at DTR: the questionnaire has to be answered by a person. Continue this chain on the DTR tab. "
                        + "The step said: " + afterPackage.headline();
            }
            return stoppedAt("DTR", afterPackage);
        }
        return stoppedAt("CRD", next);
    }

    private String submit(CaseContext context, ChainExpectation expectation, String chainId, Map<String, List<String>> carry,
                          List<RunEnvelope<?>> done) {
        RunEnvelope<?> submitted = step(EpaOperation.PAS_SUBMIT, context.spec().configEpoch() != null, done, () -> pas.runSubmit(
                PasCaseStep.submission(context, expectation.of(Protocol.PAS), chainId, carry), context.origin(), null));
        if (cannotGoOn(submitted)) {
            return null;
        }
        return submitted.nextStep().action() == NextAction.FLOW_ENDS ? null : stoppedAt("PAS", submitted.nextStep());
    }

    /**
     * Makes one step after the first. Whatever it throws ends the chain there, with the steps
     * already sent kept and named: they went out, and saying otherwise would be false.
     */
    private static RunEnvelope<?> step(EpaOperation operation, boolean pinned, List<RunEnvelope<?>> done, Supplier<RunEnvelope<?>> call) {
        RunEnvelope<?> result;
        try {
            result = call.get();
        } catch (RuntimeException e) {
            CaseRun.Interruption cause = CaseFaults.classify(e, operation.label(), pinned);
            String reason = cause.faultReference() != null
                    ? CaseFaults.sent(done) + "; this tool then failed on " + operation.label() + ", so whether that request left is not "
                    + "known, and nothing can be said about the service from it. Look for it in History before running the case again. "
                    + "Reference " + cause.faultReference() + "."
                    : CaseFaults.sent(done) + "; " + operation.label() + " was not sent. " + cause.reason();
            throw new Interrupted(new CaseRun.Interruption(cause.status(), reason, cause.faultReference(), cause.faultCause()));
        }
        done.add(result);
        return result;
    }

    /**
     * What a step hands on, with the run id of that step added when it gave none, so that the
     * next step's ledger line names the step it followed. A DTR package fetch already hands on
     * its own run id, which the questionnaire steps after it need, and it is left as it is.
     */
    private static Map<String, List<String>> handedOn(Map<String, List<String>> carry, RunEnvelope<?> from) {
        Map<String, List<String>> out = new LinkedHashMap<>(carry == null ? Map.of() : carry);
        List<String> given = out.get(NextStepView.CARRY_RUN_ID);
        if ((given == null || given.stream().noneMatch(Texts::present)) && Texts.present(from.runId())) {
            out.put(NextStepView.CARRY_RUN_ID, List.of(from.runId()));
        }
        return out;
    }

    /** A step the guardrail refused, or one this tool failed on: its own result says what happened, and nothing follows it. */
    private static boolean cannotGoOn(RunEnvelope<?> result) {
        return RunSupport.isBlocked(result) || RunSupport.isToolError(result) || result.nextStep() == null;
    }

    private static String stoppedAt(String step, NextStepView next) {
        return next.action() == NextAction.FLOW_ENDS ? "The flow ended at " + step + ". The step said: " + next.headline()
                : "The chain stopped at " + step + ". The step said: " + next.headline();
    }

    private CaseRun finish(CaseContext context, ChainExpectation expectation, List<RunEnvelope<?>> done, String stopped) {
        List<String> failures = new ArrayList<>();
        List<String> notReached = new ArrayList<>();
        boolean unverified = false;
        for (Protocol step : expectation.byStep().keySet()) {
            ExpectationView compared = null;
            for (RunEnvelope<?> result : done) {
                // The last call of a step is the one that states it.
                if (result.protocol() == step) {
                    compared = result.expectation();
                }
            }
            if (compared == null) {
                notReached.add(step.name());
            } else if (compared.result() == TestResult.FAIL) {
                failures.add("The " + step.name() + " step is not what the row expected: " + compared.explanation());
            } else if (compared.result() == TestResult.UNVERIFIED) {
                unverified = true;
            }
        }
        List<String> reason = new ArrayList<>(failures);
        if (!notReached.isEmpty()) {
            reason.add("The case expected an answer from " + String.join(" and ", notReached) + ", and the chain did not get that far.");
        }
        if (stopped != null) {
            reason.add(stopped);
        }
        TestResult overall = expectation.byStep().isEmpty() ? TestResult.UNVERIFIED
                : !failures.isEmpty() || !notReached.isEmpty() ? TestResult.FAIL
                : unverified ? TestResult.UNVERIFIED : TestResult.PASS;
        List<String> notRecorded = recordCaseResult(context, done, overall);
        return new CaseRun(done, reason.isEmpty() ? null : String.join(" ", reason), overall, null, notRecorded);
    }

    /**
     * Writes the result of the case as a whole on a line of its own that names the last step's
     * run, so that a ledger query for failed runs finds a chain that failed although each of its
     * steps passed or never ran. Returns the ledger's message when the line was not kept.
     */
    private List<String> recordCaseResult(CaseContext context, List<RunEnvelope<?>> done, TestResult overall) {
        RunEnvelope<?> last = done.get(done.size() - 1);
        // A blocked case has no result: its last step was not sent, so nothing was compared.
        if (support == null || RunSupport.isBlocked(last)) {
            return List.of();
        }
        CaseSpec spec = context.spec();
        RunRecord line = RunRecord.caseLine(last.runId(), RunStamps.timestamp(support.clock()), last.chainId(),
                context.origin().suiteRunId(), context.origin().caseId(), Texts.trimToNull(spec.expected()), overall);
        LedgerReceipt receipt = support.record(line);
        return receipt.recorded() ? List.of() : List.of(caseNotRecorded(receipt.message()));
    }

    /** How every message about a case line that was not kept begins, so it is never counted as a run that was not kept. */
    public static final String CASE_NOT_RECORDED = "The result of this case as a whole was NOT recorded in the ledger, so History "
            + "cannot show it";
    /** How the ledger begins its own message about a case line, which is then not repeated inside this one. */
    private static final String LEDGER_CASE_PREFIX = "The result of this case was NOT recorded: ";

    /** The message for a case line the ledger did not keep, saying it once and then why. */
    static String caseNotRecorded(String ledgerMessage) {
        String why = Texts.trimToNull(ledgerMessage);
        if (why == null) {
            return CASE_NOT_RECORDED + ". The ledger gave no reason.";
        }
        if (why.startsWith(LEDGER_CASE_PREFIX)) {
            return CASE_NOT_RECORDED + ": " + why.substring(LEDGER_CASE_PREFIX.length());
        }
        return CASE_NOT_RECORDED + ". The ledger said: " + why;
    }

    /** True for a message about a case line, as opposed to one about a run. */
    public static boolean aboutTheCaseLine(String message) {
        return message != null && message.startsWith(CASE_NOT_RECORDED);
    }

    /** Ends the chain at a step that could not be sent. */
    private static final class Interrupted extends RuntimeException {

        private final transient CaseRun.Interruption interruption;

        Interrupted(CaseRun.Interruption interruption) {
            super(null, null, false, false);
            this.interruption = interruption;
        }
    }
}
