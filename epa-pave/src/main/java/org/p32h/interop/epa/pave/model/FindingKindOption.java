package org.p32h.interop.epa.pave.model;

import java.util.Arrays;
import java.util.List;

/**
 * One finding kind as the browser needs it: its code and the heading it is shown under, so the
 * colour key and the result view keep no copy of the grouping.
 *
 * @param code         the {@link FindingKind} name, as a finding carries it
 * @param group        the {@link FindingGroup} name
 * @param groupLabel   the heading of that group
 * @param groupMeaning what a finding under that heading means, for the colour key
 */
public record FindingKindOption(String code, String group, String groupLabel, String groupMeaning) {

    public static FindingKindOption of(FindingKind kind) {
        FindingGroup group = FindingGroup.of(kind);
        return new FindingKindOption(kind.name(), group.name(), group.heading(), group.meaning());
    }

    /** Every finding kind, in the order of the enum. */
    public static List<FindingKindOption> all() {
        return Arrays.stream(FindingKind.values()).map(FindingKindOption::of).toList();
    }
}
