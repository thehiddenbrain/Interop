package org.p32h.interop.epa.pave.model.compare;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import org.p32h.interop.epa.pave.model.Shown;

/**
 * One value of the case that the form does not have and that could not be taken from the pasted
 * request either: it is not there, or what is there was not used. No value is made up in its
 * place. The tool's request is built as it is from a form that leaves the value out, which for
 * most values means without it. Where this tool works a value out by a rule of its own when none
 * is given, as it does for the system of a procedure code, the note says so.
 *
 * @param field      a stable name for the value, as the form names it
 * @param label      the same in plain words
 * @param lookedIn   where in the pasted request it was looked for
 * @param note       why nothing was used, or what this tool does when the value is not given, when
 *                   there is more to say than "it is not there"
 * @param candidates the values that were found when they do not agree, each with its place, so the
 *                   tester can choose one and type it on the form. Empty otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotFound(String field, String label, String lookedIn, String note, List<Candidate> candidates) {

    /**
     * One value found in the pasted request that was not used because another disagrees with it.
     *
     * @param path    where it sits in the pasted request
     * @param pointer its JSON Pointer there
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Candidate(String path, String pointer, Shown value) {

        /** Prints the place and never the value, which can be a member ID. */
        @Override
        public String toString() {
            return "Candidate[" + path + "]";
        }
    }

    public NotFound {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    /** Prints which value and how many candidates, never a candidate's value. */
    @Override
    public String toString() {
        return "NotFound[" + field + ", candidates=" + candidates.size() + "]";
    }
}
