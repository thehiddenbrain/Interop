package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.AnsweredBy;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.NextActionOption;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.OutcomeOption;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.model.Tone;
import org.p32h.interop.epa.pave.model.chain.ChainStep;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.pas.NotificationOutcome;
import org.p32h.interop.epa.pave.model.pas.PasOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.raw.RawOutcome;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The short words of the screens in one answer (plan 7.1): the journey's steps, the chips of a step
 * with no run, every outcome's short label and tone by protocol, every next action's button, the
 * test result labels, who-answered chips, and every operation with its step and the queries that
 * list a step's runs. The page looks a code up here and keeps no copy, so a
 * word changed on the server changes on every screen. The same on both profiles: nothing in it
 * depends on mock or live, and nothing in it is member data.
 */
@RestController
public class VocabularyController {

    /**
     * One step of the journey as the stepper names it, with the filters of GET /api/runs that list
     * exactly its runs: one query per filter, each a protocol (lowercase, as the filter takes it)
     * or an operation. A protocol stands for a step only when every operation of that protocol is
     * the step's, so Audit never lists a run of another step under this one.
     */
    @JsonPropertyOrder({"step", "name", "protocol", "filters"})
    public record StepWords(String step, String name, String protocol, List<RunFilter> filters) {
    }

    /** One query of GET /api/runs: a protocol or an operation, the other left out. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"protocol", "operation"})
    public record RunFilter(String protocol, String operation) {
    }

    /**
     * An operation as a ledger line names it: its words, its protocol, the step its runs belong to
     * (left out for a lookup or a request sent as written, which belong to none) and whether it
     * always changes something on the far side, which Audit says before a replay sends it again.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"code", "label", "protocol", "step", "write"})
    public record OperationWords(String code, String label, String protocol, String step, boolean write) {
    }

    /** The chip of a step with no run. No tone means the page draws it dashed. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record StateChip(String text, Tone tone) {
    }

    /** A test result and its label. */
    @JsonPropertyOrder({"code", "label"})
    public record ResultWords(String code, String label) {
    }

    /** Who answered a run, and the chip a result shows for it. */
    @JsonPropertyOrder({"code", "chip"})
    public record AnsweredWords(String code, String chip) {
    }

    @JsonPropertyOrder({"steps", "stepStates", "outcomes", "nextActions", "testResults", "answeredBy", "operations"})
    public record Vocabulary(List<StepWords> steps, Map<String, StateChip> stepStates, Map<String, List<OutcomeOption>> outcomes,
                             List<NextActionOption> nextActions, List<ResultWords> testResults, List<AnsweredWords> answeredBy,
                             List<OperationWords> operations) {
    }

    /** Built once: every word comes from an enum, so the answer cannot change while PAVE runs. */
    static final Vocabulary WORDS = build();

    @GetMapping("/api/vocabulary")
    public Vocabulary vocabulary() {
        return WORDS;
    }

    private static Vocabulary build() {
        List<StepWords> steps = ChainStep.Step.inOrder().stream()
                .map(step -> new StepWords(step.name(), step.words(), step.protocol().name(), filters(step))).toList();
        Map<String, StateChip> stepStates = new LinkedHashMap<>();
        stepStates.put("NOT_RUN", new StateChip("Not run", null));
        stepStates.put("NOT_REACHED", new StateChip("Not run", null));
        stepStates.put("NOT_NEEDED", new StateChip("Not needed", null));
        stepStates.put("NEXT", new StateChip("Next", Tone.NEUTRAL));
        // The tool's own outcomes are a group of their own: every protocol can produce them, and the page
        // looks a code up in its protocol's group first, then here.
        Map<String, List<OutcomeOption>> outcomes = new LinkedHashMap<>();
        outcomes.put("CRD", options(CrdOutcome.values()));
        outcomes.put("DTR", options(DtrOutcome.values()));
        outcomes.put("PAS", options(PasOutcome.values()));
        outcomes.put("SUBSCRIPTION", options(SubscriptionOutcome.values()));
        outcomes.put("NOTIFICATION", options(NotificationOutcome.values()));
        outcomes.put("RAW", options(RawOutcome.values()));
        outcomes.put("RUN", options(RunOutcome.values()));
        List<ResultWords> results = Arrays.stream(TestResult.values()).map(result -> new ResultWords(result.name(), result.label())).toList();
        // Only who-answered values that carry a chip are listed: an answer of the service is the plain case and shows none.
        List<AnsweredWords> answered = new ArrayList<>();
        for (AnsweredBy by : AnsweredBy.values()) {
            if (by.chip() != null) {
                answered.add(new AnsweredWords(by.name(), by.chip()));
            }
        }
        List<OperationWords> operations = new ArrayList<>();
        for (EpaOperation operation : EpaOperation.values()) {
            ChainStep.Step step = ChainStep.Step.of(operation);
            operations.add(new OperationWords(operation.name(), operation.label(), operation.protocol().name(),
                    step == null ? null : step.name(), operation.write()));
        }
        return new Vocabulary(steps, stepStates, outcomes, NextActionOption.all(), results, answered, operations);
    }

    /**
     * The queries that list a step's runs, worked out from the one rule of which operation belongs
     * to which step (ChainStep.Step.of), so Audit and the journey can never disagree about it.
     */
    static List<RunFilter> filters(ChainStep.Step step) {
        List<RunFilter> out = new ArrayList<>();
        for (Protocol protocol : Protocol.values()) {
            List<EpaOperation> ofProtocol = Arrays.stream(EpaOperation.values()).filter(each -> each.protocol() == protocol).toList();
            List<EpaOperation> ofStep = ofProtocol.stream().filter(each -> ChainStep.Step.of(each) == step).toList();
            if (ofStep.isEmpty()) {
                continue;
            }
            if (ofStep.size() == ofProtocol.size()) {
                out.add(new RunFilter(protocol.name().toLowerCase(Locale.ROOT), null));
            } else {
                for (EpaOperation each : ofStep) {
                    out.add(new RunFilter(null, each.name()));
                }
            }
        }
        return List.copyOf(out);
    }

    private static List<OutcomeOption> options(OutcomeCode[] values) {
        return OutcomeOption.listOf(values);
    }
}
