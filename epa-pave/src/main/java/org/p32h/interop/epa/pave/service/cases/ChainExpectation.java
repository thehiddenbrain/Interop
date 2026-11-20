package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>Each step's part is checked against what that step can produce inside a chain, which is less
 * than its protocol can produce: a chain fetches a questionnaire package and stops where a person
 * has to answer it, and it submits and never inquires. An outcome only those other calls produce
 * could never be met, so it is refused before anything is sent.
 *
 * @param byStep   the expectation of each step that has one, written as that step's own service
 *                 reads it
 * @param problems what is wrong with the expectation as written. Empty when it is blank or well
 *                 formed
 */
public record ChainExpectation(Map<Protocol, String> byStep, List<String> problems) {

    private static final Map<String, Protocol> STEPS = Map.of("crd", Protocol.CRD, "dtr", Protocol.DTR, "pas", Protocol.PAS);

    /** Outcomes of answering a questionnaire, which a chain never does. */
    private static final Set<String> NOT_FROM_A_PACKAGE = Set.of("NEXT_QUESTION", "NO_QUESTION_RETURNED", "UNRECOGNISED_STATUS",
            "RESPONSE_COMPLETED", "NOTHING_REQUIRED", "RESPONSE_NOT_COMPLETE", "REQUIRED_UNANSWERED");
    /** Outcomes of an inquiry about an earlier submission, which a chain never makes. */
    private static final Set<String> NOT_FROM_A_SUBMISSION = Set.of("AUTHORIZATION_NOT_FOUND");

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
            boolean anyTerm = false;
            for (String part : expected.split("[;\\r\\n]")) {
                if (Texts.blank(part)) {
                    continue;
                }
                anyTerm = true;
                String term = part.strip();
                int colon = term.indexOf(':');
                Protocol step = colon < 0 ? null : STEPS.get(term.substring(0, colon).strip().toLowerCase(Locale.ROOT));
                if (step == null) {
                    problems.add("\"" + term + "\" does not say which step of the chain it is about. Start it with crd:, dtr: or pas:, "
                            + "for example crd: AUTH_REQUIRED; pas: APPROVED.");
                    continue;
                }
                String rest = term.substring(colon + 1).strip();
                if (rest.isEmpty()) {
                    problems.add("\"" + term + "\" names the " + step.name() + " step and no outcome. Write the outcome after the colon, "
                            + "for example " + step.name().toLowerCase(Locale.ROOT) + ": " + example(step) + ".");
                    continue;
                }
                terms.computeIfAbsent(step, s -> new ArrayList<>()).add(rest);
            }
            if (!anyTerm) {
                problems.add(OutcomeCatalogue.NO_TERMS);
            }
        }
        Map<Protocol, String> byStep = new EnumMap<>(Protocol.class);
        terms.forEach((step, parts) -> {
            String joined = String.join("; ", parts);
            byStep.put(step, joined);
            Set<String> notHere = notInAChain(step);
            // A term only another call produces gets its own sentence below, and is not also called "not an outcome".
            String checked = String.join("; ", parts.stream().filter(p -> !onlyElsewhere(notHere, p)).toList());
            List<String> found = outcomes.expectationProblems(step, checked, notHere);
            for (String problem : found) {
                problems.add("For the " + step.name() + " step: " + problem);
            }
            if (!found.isEmpty() && parts.stream().anyMatch(p -> p.contains(",") || p.contains("|"))) {
                problems.add("For the " + step.name() + " step: separate the terms of a chain's expectation with a semicolon, for example "
                        + "crd: AUTH_REQUIRED; pas: APPROVED.");
            }
            for (String part : parts) {
                String code = OutcomeCatalogue.bareOutcome(part);
                if (code != null && notHere.contains(code)) {
                    problems.add("For the " + step.name() + " step: " + code + (step == Protocol.DTR
                            ? " comes from answering a questionnaire, and a chain never answers one: it fetches the package and stops "
                            + "where a person has to answer it."
                            : " comes from an inquiry about an earlier submission, and a chain submits and does not inquire."));
                }
            }
        });
        return new ChainExpectation(byStep, problems);
    }

    /** True for a bare outcome only another call produces. A code assertion is never one. */
    private static boolean onlyElsewhere(Set<String> notHere, String term) {
        String code = OutcomeCatalogue.bareOutcome(term);
        return code != null && notHere.contains(code);
    }

    /** The outcomes of a step's protocol that the step cannot produce inside a chain. */
    private static Set<String> notInAChain(Protocol step) {
        return switch (step) {
            case DTR -> NOT_FROM_A_PACKAGE;
            case PAS -> NOT_FROM_A_SUBMISSION;
            default -> Set.of();
        };
    }

    private static String example(Protocol step) {
        return switch (step) {
            case DTR -> "PACKAGE_LOADED";
            case PAS -> "APPROVED";
            default -> "AUTH_REQUIRED";
        };
    }
}
