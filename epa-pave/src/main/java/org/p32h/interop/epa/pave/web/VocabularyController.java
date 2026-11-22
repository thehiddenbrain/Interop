package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.model.AnsweredBy;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.NextActionOption;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.OutcomeOption;
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
 * test result labels and who-answered chips. The page looks a code up here and keeps no copy, so a
 * word changed on the server changes on every screen. The same on both profiles: nothing in it
 * depends on mock or live, and nothing in it is member data.
 */
@RestController
public class VocabularyController {

    /** One step of the journey as the stepper names it. */
    @JsonPropertyOrder({"step", "name", "protocol"})
    public record StepWords(String step, String name, String protocol) {
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

    @JsonPropertyOrder({"steps", "stepStates", "outcomes", "nextActions", "testResults", "answeredBy"})
    public record Vocabulary(List<StepWords> steps, Map<String, StateChip> stepStates, Map<String, List<OutcomeOption>> outcomes,
                             List<NextActionOption> nextActions, List<ResultWords> testResults, List<AnsweredWords> answeredBy) {
    }

    /** Built once: every word comes from an enum, so the answer cannot change while PAVE runs. */
    static final Vocabulary WORDS = build();

    @GetMapping("/api/vocabulary")
    public Vocabulary vocabulary() {
        return WORDS;
    }

    private static Vocabulary build() {
        List<StepWords> steps = ChainStep.Step.inOrder().stream()
                .map(step -> new StepWords(step.name(), step.words(), step.protocol().name())).toList();
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
        return new Vocabulary(steps, stepStates, outcomes, NextActionOption.all(), results, answered);
    }

    private static List<OutcomeOption> options(OutcomeCode[] values) {
        return OutcomeOption.listOf(values);
    }
}
