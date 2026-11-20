package org.p32h.interop.epa.pave.model;

import java.util.Arrays;
import java.util.List;

/**
 * One next action as the browser needs it: the code a ledger line stores and its words.
 *
 * <p>The words are {@link NextAction#label()}, the same the chain view and the suite rows use, so
 * the History tab can say what a run's next step was without keeping a copy of its own.
 */
public record NextActionOption(String code, String label) {

    public static NextActionOption of(NextAction action) {
        return new NextActionOption(action.name(), action.label());
    }

    /** Every next action, in the order of the enum. */
    public static List<NextActionOption> all() {
        return Arrays.stream(NextAction.values()).map(NextActionOption::of).toList();
    }
}
