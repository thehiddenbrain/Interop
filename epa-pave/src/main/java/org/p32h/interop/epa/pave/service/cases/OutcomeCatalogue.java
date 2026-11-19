package org.p32h.interop.epa.pave.service.cases;

import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
     * What is wrong with a declared expectation for this protocol. Empty when it is blank, well
     * formed, or the protocol is not registered, in which case the service that runs it decides.
     */
    public List<String> expectationProblems(Protocol protocol, String expected) {
        Entry entry = protocol == null ? null : entries.get(protocol);
        if (entry == null) {
            return List.of();
        }
        return Expectations.problems(expected, entry.vocabulary(), entry.elements());
    }
}
