package org.p32h.interop.epa.pave.model.pas;

/** Where the decision for one item was stated, so a decision that was inherited is never shown as the item's own. */
public enum DecisionSource {
    /** A review action on the item itself. */
    ITEM,
    /** A review action on the response as a whole, which is the decision for every item that states none of its own. */
    RESPONSE,
    /** No review action anywhere. Nothing was decided for this item. */
    NONE
}
