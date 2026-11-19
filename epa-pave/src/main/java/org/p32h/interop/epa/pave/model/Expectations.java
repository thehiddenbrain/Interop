package org.p32h.interop.epa.pave.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Compares what a tester declared they expected with what a run produced.
 *
 * <p>An expectation is one or more terms separated by semicolons. A term is one of:
 * <ul>
 *   <li>an outcome code, such as {@code NO_AUTH_REQUIRED}</li>
 *   <li>a standard code assertion written {@code name=code}, such as {@code pa-needed=no-auth}</li>
 *   <li>a code assertion about one answer only, written {@code subject: name=code}, such as
 *       {@code 95810: pa-needed=auth-needed}, for a response that answers several things</li>
 * </ul>
 * Every term must hold for the run to pass.
 *
 * <p>The rules that keep a comparison from passing by accident:
 * <ul>
 *   <li>A blank expectation is UNVERIFIED. It is never a pass.</li>
 *   <li>The actual side is always an explicit outcome code. "No determination" is an outcome with
 *       its own code, so it can only pass when the tester declared exactly that.</li>
 *   <li>A code assertion with a blank value is malformed and is rejected, so "expected nothing"
 *       can never match "got nothing".</li>
 *   <li>A code assertion names an element the operation is known to return. A misspelt name is
 *       rejected before sending, because afterwards it could only be reported as "the response did
 *       not carry it", which would be false.</li>
 *   <li>The codes of different answers are never pooled. A code assertion with no subject holds
 *       only when every answer carries the code. One answer that matches does not speak for
 *       another that differs, or for something that was asked about and got no answer.</li>
 *   <li>What was asked about is given to the comparison, which works out for itself what went
 *       unanswered. A caller cannot make a comparison pass by leaving the unanswered part out.</li>
 * </ul>
 */
public final class Expectations {

    private Expectations() {
    }

    /**
     * One answer a response gave: what it is about and the standard codes it carried.
     *
     * @param subject what the answer is about, such as a procedure code. Null when the response
     *                holds one answer about the whole request and nothing more specific is known
     * @param codes   the standard codes the answer carried, by element name
     */
    public record Answer(String subject, Map<String, List<String>> codes) {

        public Answer {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            if (codes != null) {
                codes.forEach((name, values) -> copy.put(name, values == null ? List.of() : List.copyOf(values)));
            }
            codes = java.util.Collections.unmodifiableMap(copy);
        }

        public static Answer of(String subject, Map<String, List<String>> codes) {
            return new Answer(subject, codes);
        }
    }

    /** One parsed term. {@code name} is null for an outcome term. */
    private record Term(String raw, String subject, String name, String code) {

        boolean assertion() {
            return name != null;
        }

        String label() {
            return assertion() ? (subject == null ? "" : subject + ": ") + name + "=" + code : raw;
        }
    }

    /**
     * What is wrong with a declared expectation. Empty when it is blank or well formed.
     *
     * @param vocabulary every outcome the operation can produce
     * @param elements   the element names a code assertion may use for this operation, in the
     *                   spelling the response uses. Null or empty when the operation has none
     */
    public static List<String> problems(String expected, OutcomeCode[] vocabulary, Collection<String> elements) {
        List<String> problems = new ArrayList<>();
        for (Term term : terms(expected)) {
            if (!term.assertion()) {
                if (findOutcome(term.raw(), vocabulary) == null) {
                    problems.add("\"" + term.raw() + "\" is not an outcome this operation can produce. Use one of: " + names(vocabulary) + ".");
                }
                continue;
            }
            if (term.name().isEmpty() || term.code().isEmpty() || (term.subject() != null && term.subject().isEmpty())) {
                problems.add("\"" + term.raw() + "\" is not a complete code assertion. Write it as name=code, for example "
                        + example(elements) + ".");
            } else if (elements == null || elements.isEmpty()) {
                problems.add("\"" + term.raw() + "\" cannot be checked: this operation returns no coded elements to assert. "
                        + "Use an outcome code.");
            } else if (element(term.name(), elements) == null) {
                problems.add("\"" + term.name() + "\" is not an element this operation returns. Use one of: "
                        + String.join(", ", elements) + ". Separate terms with a semicolon.");
            }
        }
        return problems;
    }

    /**
     * Assesses one run.
     *
     * @param expected   what the tester declared, possibly null or blank
     * @param actual     the outcome of the run
     * @param asked      the subjects that were asked about, such as the procedure codes of the
     *                   request. Any of them that no answer is for is treated as unanswered, and an
     *                   unanswered subject satisfies no code assertion. May be null or empty when
     *                   what was asked is not known
     * @param answers    each answer the response gave, with its codes. May be null or empty
     * @param vocabulary every outcome the operation can produce, for reading the expectation
     * @param elements   the element names a code assertion may use for this operation
     */
    public static ExpectationView assess(String expected, OutcomeCode actual, List<String> asked, List<Answer> answers,
                                         OutcomeCode[] vocabulary, Collection<String> elements) {
        if (actual == null) {
            throw new IllegalArgumentException("A run always has an outcome.");
        }
        List<Term> terms = terms(expected);
        if (terms.isEmpty()) {
            return new ExpectationView(null, null, actual.name(), actual.label(), TestResult.UNVERIFIED,
                    "No expectation was declared, so this run is unverified.");
        }
        List<String> problems = problems(expected, vocabulary, elements);
        if (!problems.isEmpty()) {
            return new ExpectationView(expected.strip(), expected.strip(), actual.name(), actual.label(), TestResult.FAIL,
                    "The expectation could not be checked. " + String.join(" ", problems));
        }
        List<Answer> given = answers == null ? List.of() : answers;
        List<String> unanswered = new ArrayList<>();
        if (asked != null) {
            for (String subject : asked) {
                boolean answered = given.stream().anyMatch(a -> a.subject() != null && a.subject().equalsIgnoreCase(subject));
                if (subject != null && !answered && !unanswered.contains(subject)) {
                    unanswered.add(subject);
                }
            }
        }

        List<String> labels = new ArrayList<>();
        List<String> met = new ArrayList<>();
        List<String> unmet = new ArrayList<>();
        for (Term term : terms) {
            if (term.assertion()) {
                Term normalised = new Term(term.raw(), term.subject(), element(term.name(), elements), term.code());
                labels.add(normalised.label());
                assertion(normalised, given, unanswered, met, unmet);
            } else {
                OutcomeCode wanted = findOutcome(term.raw(), vocabulary);
                labels.add(wanted.label());
                if (wanted.name().equals(actual.name())) {
                    met.add("the outcome was \"" + actual.label() + "\" as expected");
                } else {
                    unmet.add("expected \"" + wanted.label() + "\" but the outcome was \"" + actual.label() + "\"");
                }
            }
        }
        boolean pass = unmet.isEmpty();
        return new ExpectationView(expected.strip(), String.join("; ", labels), actual.name(), actual.label(),
                pass ? TestResult.PASS : TestResult.FAIL, sentence(pass ? met : unmet));
    }

    /**
     * A run that was not sent, so there is nothing to compare. Never a pass and never a fail: the
     * service said nothing, and a declared expectation was neither met nor missed.
     *
     * @param why one plain sentence saying why nothing was compared
     */
    public static ExpectationView notCompared(String expected, OutcomeCode actual, String why) {
        String declared = Texts.trimToNull(expected);
        return new ExpectationView(declared, declared, actual.name(), actual.label(), TestResult.UNVERIFIED, why);
    }

    private static void assertion(Term term, List<Answer> answers, List<String> unanswered, List<String> met, List<String> unmet) {
        String wanted = term.name() + "=" + term.code();
        List<Answer> scope = answers;
        List<String> silent = unanswered;
        if (term.subject() != null) {
            scope = answers.stream().filter(a -> a.subject() != null && a.subject().equalsIgnoreCase(term.subject())).toList();
            silent = unanswered.stream().filter(s -> s.equalsIgnoreCase(term.subject())).toList();
            if (scope.isEmpty() && silent.isEmpty()) {
                unmet.add("expected " + term.label() + " but the response has no answer for " + term.subject());
                return;
            }
        } else if (answers.isEmpty() && unanswered.isEmpty()) {
            unmet.add("expected " + wanted + " but the response did not carry " + term.name());
            return;
        }
        List<String> differing = new ArrayList<>();
        for (Answer answer : scope) {
            List<String> returned = answer.codes().get(term.name());
            String who = answer.subject() == null ? "the response" : "the answer for " + answer.subject();
            if (returned == null || returned.isEmpty()) {
                differing.add(who + " did not carry " + term.name());
            } else if (!returned.contains(term.code())) {
                differing.add(who + " carried " + term.name() + "=" + String.join(", ", returned));
            }
        }
        for (String subject : silent) {
            differing.add("no answer was returned for " + subject);
        }
        if (!differing.isEmpty()) {
            unmet.add("expected " + term.label() + " but " + String.join(", and ", differing));
        } else if (scope.size() == 1) {
            met.add((scope.get(0).subject() == null ? "the response" : "the answer for " + scope.get(0).subject())
                    + " carried " + wanted);
        } else {
            met.add("every answer carried " + wanted + " (" + String.join(", ", scope.stream().map(Answer::subject).toList()) + ")");
        }
    }

    private static List<Term> terms(String expected) {
        List<Term> out = new ArrayList<>();
        if (Texts.blank(expected)) {
            return out;
        }
        for (String part : expected.split("[;\\r\\n]")) {
            if (Texts.blank(part)) {
                continue;
            }
            String raw = part.strip();
            int equals = raw.indexOf('=');
            if (equals < 0) {
                out.add(new Term(raw, null, null, null));
                continue;
            }
            String left = raw.substring(0, equals).strip();
            String code = raw.substring(equals + 1).strip();
            // The last colon, so a subject that is itself a urn or a url keeps its own colons.
            int colon = left.lastIndexOf(':');
            String subject = colon < 0 ? null : left.substring(0, colon).strip();
            String name = colon < 0 ? left : left.substring(colon + 1).strip();
            out.add(new Term(raw, subject, name, code));
        }
        return out;
    }

    /** The listed spelling of an element name, matched ignoring case, or null when it is not listed. */
    private static String element(String name, Collection<String> elements) {
        if (elements != null) {
            for (String candidate : elements) {
                if (candidate.equalsIgnoreCase(name)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static String example(Collection<String> elements) {
        return elements == null || elements.isEmpty() ? "name=code" : elements.iterator().next() + "=code";
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

    /** Every part starts with an ordinary word, never with an element name, so capitalising the first letter is safe. */
    private static String sentence(List<String> parts) {
        String joined = String.join("; ", parts);
        return joined.substring(0, 1).toUpperCase(Locale.ROOT) + joined.substring(1) + ".";
    }
}
