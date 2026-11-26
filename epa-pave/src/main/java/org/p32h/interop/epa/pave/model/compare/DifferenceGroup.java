package org.p32h.interop.epa.pave.model.compare;

/**
 * What a difference is about. Every difference belongs to exactly one group and the screen
 * presents the groups apart, in the order they are declared here: the first is the answer to "is
 * this tool building the request the way the sample does", and the last is the honest remainder.
 */
public enum DifferenceGroup {
    BUILD("How the request is built", "in how the request is built", "in how the request is built",
            "One request has an element the other does not, or the two hold a different fixed value, type, key or shape. "
                    + "This is where the way this tool builds a request differs from your request."),
    CASE("The case", "in the case they describe", "in the case they describe",
            "The two requests describe a different member, provider, order or date, or write the same one differently."),
    LABELS("Labels", "in labels", "in labels",
            "Ids, and the references that follow them. A label can differ with no difference in meaning, as long as every "
                    + "reference in a request uses that request's own id."),
    EVERY_CALL("Every call", "that differs on every call", "that differ on every call",
            "Differs by design whenever a request is sent. Not a finding."),
    NOT_CLASSIFIED("Not classified", "that this tool does not classify", "that this tool does not classify",
            "This tool has no description of the element, or none of its role, so it does not say which group the "
                    + "difference belongs to.");

    private final String label;
    private final String one;
    private final String several;
    private final String description;

    DifferenceGroup(String label, String one, String several, String description) {
        this.label = label;
        this.one = one;
        this.several = several;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    /** Whether a screen opens this group at first: every group but the one whose differences are there by design on every call. */
    public boolean startsOpen() {
        return this != EVERY_CALL;
    }

    /** How a count of this group reads inside the headline, for example "2 in labels". */
    public String counted(int count) {
        return count + " " + (count == 1 ? one : several);
    }
}
