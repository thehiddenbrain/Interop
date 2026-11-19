package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * What a chain case expects, step by step.
 *
 * <p>A chain makes up to three calls and each has its own outcomes, so an expectation for a chain
 * says which step each of its terms is about: {@code crd: AUTH_REQUIRED; pas: APPROVED}. A term
 * that names no step is refused. Guessing the step would let "NO_AUTH_REQUIRED" be checked against
 * whichever step happened to run last.
 *
 * @param byStep   the expectation of each step that has one, written as that step's own service
 *                 reads it
 * @param problems what is wrong with the expectation as written. Empty when it is blank or well
 *                 formed
 */
public record ChainExpectation(Map<Protocol, String> byStep, List<String> problems) {

    private static final Map<String, Protocol> STEPS = Map.of("crd", Protocol.CRD, "dtr", Protocol.DTR, "pas", Protocol.PAS);

    public ChainExpectation {
        byStep = byStep == null ? Map.of() : java.util.Collections.unmodifiableMap(new EnumMap<>(byStep));
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    /** The expectation a step runs with, or null when the case says nothing about that step. */
    public String of(Protocol step) {
        return byStep.get(step);
    }

    /**
     * Reads a chain's expectation and checks each step's part against the outcomes that step can
     * produce, so a misspelt outcome is found before the first call is made and not after it.
     */
    public static ChainExpectation parse(String expected, OutcomeCatalogue outcomes) {
        Map<Protocol, List<String>> terms = new EnumMap<>(Protocol.class);
        List<String> problems = new ArrayList<>();
        if (Texts.present(expected)) {
            for (String part : expected.split("[;\\r\\n]")) {
                if (Texts.blank(part)) {
                    continue;
                }
                String term = part.strip();
                int colon = term.indexOf(':');
                Protocol step = colon < 0 ? null : STEPS.get(term.substring(0, colon).strip().toLowerCase(Locale.ROOT));
                String rest = step == null ? "" : term.substring(colon + 1).strip();
                if (step == null || rest.isEmpty()) {
                    problems.add("\"" + term + "\" does not say which step of the chain it is about. Start it with crd:, dtr: or pas:, "
                            + "for example crd: AUTH_REQUIRED; pas: APPROVED.");
                    continue;
                }
                terms.computeIfAbsent(step, s -> new ArrayList<>()).add(rest);
            }
        }
        Map<Protocol, String> byStep = new EnumMap<>(Protocol.class);
        terms.forEach((step, parts) -> {
            String joined = String.join("; ", parts);
            byStep.put(step, joined);
            for (String problem : outcomes.expectationProblems(step, joined)) {
                problems.add("For the " + step.name() + " step: " + problem);
            }
        });
        return new ChainExpectation(byStep, problems);
    }
}
