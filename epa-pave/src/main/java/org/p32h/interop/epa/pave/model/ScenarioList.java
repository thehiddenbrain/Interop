package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What every protocol's scenarios endpoint answers: the mock scenarios a tester can choose from,
 * and the vocabulary for declaring an expectation.
 *
 * <p>The scenario part is filled on the mock profile only. The vocabulary part is filled on both,
 * because a tester on live declares expectations too.
 *
 * @param available false on the live profile, where there is no selector to offer
 * @param reason    why there is none, or null
 * @param problems  scenario files that could not be used, by name, with the reason
 * @param outcomes  every outcome the protocol can produce, followed by the outcomes that are about
 *                  the tool itself. The history list reads labels and tones from here
 * @param elements  the element names a code assertion may use, in the response's spelling
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScenarioList(boolean available, String reason, List<ScenarioSummary> scenarios, List<String> problems,
                           List<OutcomeOption> outcomes, List<String> elements) {

    public ScenarioList {
        scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
        problems = problems == null ? List.of() : List.copyOf(problems);
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
        elements = elements == null ? List.of() : List.copyOf(elements);
    }
}
