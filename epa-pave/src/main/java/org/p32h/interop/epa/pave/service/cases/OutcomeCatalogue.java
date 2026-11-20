package org.p32h.interop.epa.pave.service.cases;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Tone;

/**
 * Every outcome each protocol can produce, in one place, for anything that reads a ledger record
 * without having produced it: a suite results table, a chain view, a history list.
 *
 * <p>A ledger record holds an outcome as a code. This turns the code back into plain words. The
 * protocols keep their own enums, because CRD has no denial and PAS has a real one, so the lookup
 * is always by protocol and never across them.
 */
public final class OutcomeCatalogue {

    private record Entry(OutcomeCode[] vocabulary, List<String> elements) {
    }

    private final Map<Protocol, Entry> entries = new EnumMap<>(Protocol.class);

    /**
     * @param vocabulary every outcome the protocol can produce
     * @param elements   the element names a code assertion may use for this protocol, or null
     */
    public OutcomeCatalogue register(Protocol protocol, OutcomeCode[] vocabulary, Collection<String> elements) {
        entries.put(protocol, new Entry(vocabulary.clone(), elements == null ? List.of() : List.copyOf(elements)));
        return this;
    }

    public boolean knows(Protocol protocol) {
        return protocol != null && entries.containsKey(protocol);
    }

    /** The outcome behind a code, or null when the protocol has no such outcome. */
    public OutcomeCode find(Protocol protocol, String code) {
        if (Texts.blank(code)) {
            return null;
        }
        // Blocked and tool error say something about this tool, so every protocol shares them.
        for (OutcomeCode shared : RunOutcome.values()) {
            if (shared.name().equals(code)) {
                return shared;
            }
        }
        Entry entry = protocol == null ? null : entries.get(protocol);
        if (entry != null) {
            for (OutcomeCode candidate : entry.vocabulary()) {
                if (candidate.name().equals(code)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    /**
     * The outcome in plain words. A code this tool does not know is returned as written, never
     * replaced by a guess: a ledger line written by a later version must still be readable.
     */
    public String label(Protocol protocol, String code) {
        OutcomeCode outcome = find(protocol, code);
        return outcome == null ? Texts.trimToNull(code) : outcome.label();
    }

    public Tone tone(Protocol protocol, String code) {
        OutcomeCode outcome = find(protocol, code);
        return outcome == null ? Tone.NEUTRAL : outcome.tone();
    }

    /**
     * What is wrong with a declared expectation for this protocol. Empty when it is blank or well
     * formed. Two things are refused for any protocol, because neither could ever be met: a cell
     * of separators alone, and two different outcomes, since a run has one. Beyond that a protocol
     * that is not registered is left to the service that runs it.
     */
    public List<String> expectationProblems(Protocol protocol, String expected) {
        List<String> problems = new ArrayList<>();
        if (Texts.present(expected)) {
            List<String> terms = Arrays.stream(expected.split("[;\\r\\n]")).filter(Texts::present).map(String::strip).toList();
            if (terms.isEmpty()) {
                // A cell of separators alone reads as an expectation and would compare nothing.
                problems.add(NO_TERMS);
                return problems;
            }
            Set<String> bare = new LinkedHashSet<>();
            for (String term : terms) {
                String code = bareOutcome(term);
                if (code != null) {
                    bare.add(code);
                }
            }
            if (bare.size() > 1) {
                problems.add("The expectation names more than one outcome: " + String.join(" and ", bare) + ". A run has exactly one "
                        + "outcome and every term must hold, so this could never pass. Keep the one you expect.");
            }
        }
        Entry entry = protocol == null ? null : entries.get(protocol);
        if (entry != null) {
            problems.addAll(Expectations.problems(expected, entry.vocabulary(), entry.elements()));
        }
        return problems;
    }

    /** The problem with an expectation that holds separators and nothing else. */
    public static final String NO_TERMS = "The expectation holds no outcome and no code assertion, only separators. Write an outcome "
            + "code, for example AUTH_REQUIRED, or leave the cell empty to run the case unverified.";

    /**
     * The outcome a term names when it is a bare outcome and not a code assertion, written as a
     * code is written, or null for a code assertion. The same reading the comparison applies.
     */
    public static String bareOutcome(String term) {
        if (Texts.blank(term) || term.contains("=")) {
            return null;
        }
        String code = term.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
        return code.isEmpty() ? null : code;
    }
}
