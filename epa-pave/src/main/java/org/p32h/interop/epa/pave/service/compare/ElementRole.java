package org.p32h.interop.epa.pave.service.compare;

import org.p32h.interop.epa.pave.model.compare.DifferenceGroup;

/**
 * The part an element plays in a request. It is a property of the element, not of a difference: a
 * coding system url is part of how a request is built even when only its value differs, and a
 * member's family name is part of the case.
 */
public enum ElementRole {
    /** A new value is generated whenever a request is sent. */
    EVERY_CALL(DifferenceGroup.EVERY_CALL),
    /** An id, or something that only repeats one. */
    LABEL(DifferenceGroup.LABELS),
    /** Describes the member, the provider, the order or its date. */
    CASE(DifferenceGroup.CASE),
    /** Fixed by how the sender builds requests, whatever the case. */
    BUILD(DifferenceGroup.BUILD);

    private final DifferenceGroup group;

    ElementRole(DifferenceGroup group) {
        this.group = group;
    }

    /** The group a difference in the value of such an element belongs to. */
    public DifferenceGroup group() {
        return group;
    }
}
