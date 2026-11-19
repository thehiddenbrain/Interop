package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares what a tester declared they expected with what a run produced.
 *
 * <p>An expectation is one or more terms separated by semicolons. A term is either an outcome
 * code, such as {@code NO_AUTH_REQUIRED}, or a standard code assertion written
 * {@code name=code}, such as {@code pa-needed=no-auth}. Every term must hold for the run to pass.
 *
 * <p>The rules that keep a comparison from passing by accident:
 * <ul>
 *   <li>A blank expectation is UNVERIFIED. It is never a pass.</li>
 *   <li>The actual side is always an explicit outcome code. "No determination" is an outcome with
 *       its own code, so it can only pass when the tester declared exactly that.</li>
 *   <li>A code assertion with a blank value is malformed and is rejected, so "expected nothing"
 *       can never match "got nothing".</li>
 *   <li>A code assertion fails when the response did not carry that element at all.</li>
 * </ul>
 */
public final class Expectations {

    private Expectations() {
    }

    /** What is wrong with a declared expectation. Empty when it is blank or well formed. */
    public static List<String> problems(String expected, OutcomeCode[] vocabulary) {
        List<String> problems = new ArrayList<>();
        for (String term : terms(expected)) {
            int equals = term.indexOf('=');
            if (equals >= 0) {
                String name = term.substring(0, equals).strip();
                String code = term.substring(equals + 1).strip();
                if (name.isEmpty() || code.isEmpty()) {
                    problems.add("\"" + term + "\" is not a complete code assertion. Write it as name=code, for example pa-needed=no-auth.");
                }
            } else if (findOutcome(term, vocabulary) == null) {
                problems.add("\"" + term + "\" is not an outcome this operation can produce. Use one of: " + names(vocabulary) + ".");
            }
        }
        return problems;
    }

    /**
     * Assesses one run.
     *
     * @param expected   what the tester declared, possibly null or blank
     * @param actual     the outcome of the run
     * @param codes      the standard codes the response carried, by element name. May be null
     * @param vocabulary every outcome the operation can produce, for reading the expectation
     */
    public static ExpectationView assess(String expected, OutcomeCode actual, Map<String, List<String>> codes,
                                         OutcomeCode[] vocabulary) {
        if (actual == null) {
            throw new IllegalArgumentException("A run always has an outcome.");
        }
        List<String> terms = terms(expected);
        if (terms.isEmpty()) {
            return new ExpectationView(null, null, actual.name(), actual.label(), TestResult.UNVERIFIED,
                    "No expectation was declared, so this run is unverified.");
        }
        List<String> problems = problems(expected, vocabulary);
        if (!problems.isEmpty()) {
            return new ExpectationView(expected.strip(), expected.strip(), actual.name(), actual.label(), TestResult.FAIL,
                    "The expectation could not be checked. " + String.join(" ", problems));
        }

        List<String> labels = new ArrayList<>();
        List<String> met = new ArrayList<>();
        List<String> unmet = new ArrayList<>();
        for (String term : terms) {
            int equals = term.indexOf('=');
            if (equals >= 0) {
                String name = term.substring(0, equals).strip();
                String code = term.substring(equals + 1).strip();
                labels.add(name + "=" + code);
                List<String> returned = codes == null ? null : codes.get(name);
                if (returned == null || returned.isEmpty()) {
                    unmet.add("expected " + name + "=" + code + " but the response did not carry " + name);
                } else if (returned.contains(code)) {
                    met.add(name + "=" + code + " was returned");
                } else {
                    unmet.add("expected " + name + "=" + code + " but " + name + " was " + String.join(", ", returned));
                }
            } else {
                OutcomeCode wanted = findOutcome(term, vocabulary);
                labels.add(wanted.label());
                if (wanted.name().equals(actual.name())) {
                    met.add("the outcome was \"" + actual.label() + "\" as expected");
                } else {
                    unmet.add("expected \"" + wanted.label() + "\" but the outcome was \"" + actual.label() + "\"");
                }
            }
        }
        boolean pass = unmet.isEmpty();
        String explanation = sentence(pass ? met : unmet);
        return new ExpectationView(expected.strip(), String.join("; ", labels), actual.name(), actual.label(),
                pass ? TestResult.PASS : TestResult.FAIL, explanation);
    }

    private static List<String> terms(String expected) {
        List<String> out = new ArrayList<>();
        if (Texts.blank(expected)) {
            return out;
        }
        for (String part : expected.split("[;\\r\\n]")) {
            if (Texts.present(part)) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static OutcomeCode findOutcome(String term, OutcomeCode[] vocabulary) {
        String wanted = term.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        if (vocabulary != null) {
            for (OutcomeCode candidate : vocabulary) {
                if (candidate.name().equals(wanted)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String names(OutcomeCode[] vocabulary) {
        List<String> out = new ArrayList<>();
        if (vocabulary != null) {
            for (OutcomeCode code : vocabulary) {
                out.add(code.name());
            }
        }
        return String.join(", ", out);
    }

    private static String sentence(List<String> parts) {
        String joined = String.join("; ", parts);
        return joined.substring(0, 1).toUpperCase(Locale.ROOT) + joined.substring(1) + ".";
    }
}
