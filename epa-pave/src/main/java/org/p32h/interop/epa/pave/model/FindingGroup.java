package org.p32h.interop.epa.pave.model;

/**
 * The heading a finding is shown under, so that a tester sees at once whether it is about the
 * request this tool sent, the response held to the guide, the code list, a notification or the
 * time a call took.
 *
 * <p>The grouping lives here and not in a screen, so that a kind added to {@link FindingKind} is
 * placed under a heading where the kind is added: {@link #of(FindingKind)} is a switch with no
 * default, and the compiler refuses a kind it does not place.
 */
public enum FindingGroup {
    REQUEST("About the request this tool sent, not the service",
            "What went out, or what this tool did not do. A refusal by the service that follows from it is not a fault of the service."),
    GUIDE("About the response, held to the guide",
            "The response does not meet the version of the guide this tool is held to. The finding names the guide and the version."),
    INFORMATION("Information about the response",
            "Something to know, which the guide does not call wrong: content a later version defines, or something the response does "
                    + "not state."),
    CODE_LIST("The code list beside the service",
            "The two positions side by side, or why they could not be compared. Either side can be the wrong one."),
    NOTIFICATION("About a notification that arrived",
            "A notification this tool could not tie to a run, or could not check. It is kept and shown."),
    TIMING("About the time the call took",
            "The call completed and took longer than the threshold.");

    private final String heading;
    private final String meaning;

    FindingGroup(String heading, String meaning) {
        this.heading = heading;
        this.meaning = meaning;
    }

    /** The heading a finding of this group is shown under. */
    public String heading() {
        return heading;
    }

    /** What a finding under this heading means, for the colour key. */
    public String meaning() {
        return meaning;
    }

    /** The group of a kind, or null for no kind. */
    public static FindingGroup of(FindingKind kind) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case REQUEST_INCOMPLETE, NO_CALLBACK_URL, PRE_REQUEST_SCRIPT_NOT_RUN -> REQUEST;
            case REQUIRED_ELEMENT_MISSING, CODE_OUTSIDE_VALUE_SET, GUIDE_RULE_BROKEN -> GUIDE;
            case OTHER_VERSION_CONTENT, NOT_STATED -> INFORMATION;
            case CODE_LIST_DISAGREES_ON_AUTH, CODE_LIST_DISAGREES_ON_VENDOR, CODE_LIST_NOT_COMPARED -> CODE_LIST;
            case UNMATCHED_NOTIFICATION, UNCHECKED_NOTIFICATION -> NOTIFICATION;
            case SLOW_RESPONSE -> TIMING;
        };
    }
}
