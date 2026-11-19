package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.List;
import org.p32h.interop.epa.pave.model.CaseSpec;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ExpectationView;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.NextAction;
import org.p32h.interop.epa.pave.model.NextStepView;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Texts;
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
 * Every call joins one chain id, so the journey is one row in history.
 *
 * <p>The member and the provider are resolved once, by the runner, and every step is given the
 * same ones. A case never carries a production unlock, so a submission against a production
 * environment is blocked, and the case is reported as blocked with the steps before it kept.
 *
 * <p>The test result of a chain is stated for the case as a whole: passed when every step the
 * case expected something of ran and matched; failed when one did not match, or when the chain
 * never reached a step the case expected an answer from; unverified when the case expected
 * nothing. A chain that stops early can therefore not pass by leaving a step out.
 */
public final class ChainCaseStep implements CaseStep {

    private final CrdService crd;
    private final DtrService dtr;
    private final PasService pas;
    private final OutcomeCatalogue outcomes;

    public ChainCaseStep(CrdService crd, DtrService dtr, PasService pas, OutcomeCatalogue outcomes) {
        this.crd = crd;
        this.dtr = dtr;
        this.pas = pas;
        this.outcomes = outcomes;
    }

    @Override
    public CaseRun run(CaseContext context) {
        CaseSpec spec = context.spec();
        // Everything that can be refused is refused before the first call, so a chain is never half run for a typing mistake.
        List<FieldError> errors = new ArrayList<>();
        ChainExpectation expectation = ChainExpectation.parse(spec.expected(), outcomes);
        expectation.problems().forEach(problem -> errors.add(new FieldError("expected", problem)));
        if (Texts.present(spec.scenarioId())) {
            errors.add(new FieldError("scenarioId", "A chain cannot choose a scenario. Each of its steps is answered by a scenario of its "
                    + "own protocol, picked by the member ID. Run the step by itself to choose its scenario."));
        }
        if (spec.operation() != null && spec.operation() != EpaOperation.CRD_ORDER_SIGN && spec.operation() != EpaOperation.CRD_ORDER_SELECT) {
            errors.add(new FieldError("operation", "A chain starts with a CRD hook, order-sign or order-select."));
        }
        if (!errors.isEmpty()) {
            throw new ValidationException(errors);
        }
        String hook = spec.operation() == EpaOperation.CRD_ORDER_SELECT ? CrdRunRequest.ORDER_SELECT : CrdRunRequest.ORDER_SIGN;

        List<RunEnvelope<?>> done = new ArrayList<>();
        // A case that already belongs to a chain joins it. Otherwise this journey starts one.
        String chainId = Texts.orElse(Texts.trimToNull(spec.chainId()), RunRequest.NEW_CHAIN);
        RunEnvelope<?> first = crd.run(CrdCaseStep.request(context, hook, expectation.of(Protocol.CRD), chainId), context.origin(), null);
        done.add(first);
        String stopped = follow(context, expectation, first, done);
        return finish(expectation, done, stopped);
    }

    /** Makes the calls that follow CRD and returns why the chain stopped where it did. */
    private String follow(CaseContext context, ChainExpectation expectation, RunEnvelope<?> crdResult, List<RunEnvelope<?>> done) {
        if (cannotGoOn(crdResult)) {
            return null;
        }
        NextStepView next = crdResult.nextStep();
        String chainId = crdResult.chainId();
        if (next.action() == NextAction.START_PAS) {
            return submit(context, expectation, chainId, next, done);
        }
        if (next.action() == NextAction.START_DTR) {
            RunEnvelope<?> fetched = dtr.runPackage(DtrCaseStep.packageRequest(context, expectation.of(Protocol.DTR), chainId, List.of(),
                    next.carry()), context.origin(), null);
            done.add(fetched);
            if (cannotGoOn(fetched)) {
                return null;
            }
            NextStepView afterPackage = fetched.nextStep();
            if (afterPackage.action() == NextAction.START_PAS) {
                return submit(context, expectation, chainId, afterPackage, done);
            }
            if (afterPackage.action() == NextAction.ANSWER_NEXT_QUESTION || afterPackage.action() == NextAction.SUBMIT_RESPONSE) {
                return "The chain stopped at DTR: the questionnaire has to be answered by a person. Continue this chain on the DTR tab. "
                        + "The step said: " + afterPackage.headline();
            }
            return stoppedAt("DTR", afterPackage);
        }
        return stoppedAt("CRD", next);
    }

    private String submit(CaseContext context, ChainExpectation expectation, String chainId, NextStepView handedOn, List<RunEnvelope<?>> done) {
        RunEnvelope<?> submitted = pas.runSubmit(PasCaseStep.submission(context, expectation.of(Protocol.PAS), chainId, handedOn.carry()),
                context.origin(), null);
        done.add(submitted);
        if (cannotGoOn(submitted)) {
            return null;
        }
        return submitted.nextStep().action() == NextAction.FLOW_ENDS ? null : stoppedAt("PAS", submitted.nextStep());
    }

    /** A step the guardrail refused, or one this tool failed on: its own result says what happened, and nothing follows it. */
    private static boolean cannotGoOn(RunEnvelope<?> result) {
        return RunSupport.isBlocked(result) || RunSupport.isToolError(result) || result.nextStep() == null;
    }

    private static String stoppedAt(String step, NextStepView next) {
        return next.action() == NextAction.FLOW_ENDS ? "The flow ended at " + step + ". The step said: " + next.headline()
                : "The chain stopped at " + step + ". The step said: " + next.headline();
    }

    private static CaseRun finish(ChainExpectation expectation, List<RunEnvelope<?>> done, String stopped) {
        if (expectation.byStep().isEmpty()) {
            return new CaseRun(done, stopped, TestResult.UNVERIFIED);
        }
        boolean failed = false;
        boolean unverified = false;
        List<String> notReached = new ArrayList<>();
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
                failed = true;
            } else if (compared.result() == TestResult.FAIL) {
                failed = true;
            } else if (compared.result() == TestResult.UNVERIFIED) {
                unverified = true;
            }
        }
        String reason = stopped;
        if (!notReached.isEmpty()) {
            String missing = "The case expected an answer from " + String.join(" and ", notReached) + ", and the chain did not get that far.";
            reason = reason == null ? missing : missing + " " + reason;
        }
        return new CaseRun(done, reason, failed ? TestResult.FAIL : unverified ? TestResult.UNVERIFIED : TestResult.PASS);
    }
}
