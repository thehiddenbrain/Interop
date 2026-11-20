package org.p32h.interop.epa.pave.model.compare;

/**
 * How many differences one group holds. Every group is listed, a group with none included, so a
 * screen can say "none" where saying nothing would leave the tester guessing.
 *
 * @param label       the words for the group
 * @param description what belongs in the group, in a sentence or two
 * @param startsOpen  whether a screen opens the group at first. A group whose differences are
 *                    true of every call starts closed, because none of them is a finding
 */
public record GroupCount(DifferenceGroup group, String label, String description, int count, boolean startsOpen) {

    public static GroupCount of(DifferenceGroup group, int count) {
        return new GroupCount(group, group.label(), group.description(), count, group.startsOpen());
    }

    /** The group and its count only: every record of the model that holds text keeps it out of its toString. */
    @Override
    public String toString() {
        return "GroupCount[" + group + ", " + count + "]";
    }
}
