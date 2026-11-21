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
 * @param group        the {@link FindingGroup} the kind belongs to, always derived from the kind
 * @param groupLabel   the heading of that group, always derived from the kind
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(FindingKind kind, String title, String detail, String subject, String codeListSays,
                      String serviceSays, FindingGroup group, String groupLabel) {

    public Finding {
        // From the kind and nothing else, so that no finding can carry a heading its kind does not have.
        group = FindingGroup.of(kind);
        groupLabel = group == null ? null : group.heading();
    }

    public Finding(FindingKind kind, String title, String detail, String subject, String codeListSays, String serviceSays) {
        this(kind, title, detail, subject, codeListSays, serviceSays, null, null);
    }

    /** Prints the kind only. The detail and the two sides can hold text from a response. */
    @Override
    public String toString() {
        return "Finding[" + kind + "]";
    }
}
