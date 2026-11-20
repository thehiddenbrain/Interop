package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Something on a response a person needs to look at. Shown apart from the determination, because
 * a finding is not a failure of the call. Where the code list and the service disagree, either
 * side can be the one that is wrong, so the finding states both and blames neither.
 *
 * @param subject      what the finding is about, such as a procedure code or a sub-extension name
 * @param codeListSays the code list's position, when the finding compares the two
 * @param serviceSays  the service's position, when the finding compares the two
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(FindingKind kind, String title, String detail, String subject, String codeListSays,
                      String serviceSays) {
    /** Prints the kind only. The detail and the two sides can hold text from a response. */
    @Override
    public String toString() {
        return "Finding[" + kind + "]";
    }
}
