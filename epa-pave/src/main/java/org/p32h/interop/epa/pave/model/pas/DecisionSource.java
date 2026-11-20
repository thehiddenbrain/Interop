package org.p32h.interop.epa.pave.model.pas;

/** Where the decision for one item was stated, so a decision that was inherited is never shown as the item's own. */
public enum DecisionSource {
    /** A review action on the item itself. */
    ITEM("Stated on this item"),
    /** A review action on the response as a whole, which is the decision for every item that states none of its own. */
    RESPONSE("Stated once for the response as a whole, not on this item"),
    /** No review action anywhere. Nothing was decided for this item. */
    NONE("No review action applies to this item");

    private final String label;

    DecisionSource(String label) {
        this.label = label;
    }

    /** Where the decision was stated, in words, so a screen keeps no copy of them. */
    public String label() {
        return label;
    }
}
