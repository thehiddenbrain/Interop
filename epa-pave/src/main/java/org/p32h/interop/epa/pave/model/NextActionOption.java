package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Arrays;
import java.util.List;

/**
 * One next action as the browser needs it: the code a ledger line stores and its words.
 *
 * <p>The words are {@link NextAction#label()}, the same the chain view and the suite rows use, so
 * Audit can say what a run's next step was without keeping a copy of its own.
 *
 * @param button the name of the button that does the action, or null, and then left off the wire,
 *               when no button does it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NextActionOption(String code, String label, String button) {

    /** An option without a button, as the callers written before buttons were named build one. */
    public NextActionOption(String code, String label) {
        this(code, label, null);
    }

    public static NextActionOption of(NextAction action) {
        return new NextActionOption(action.name(), action.label(), action.button());
    }

    /** Every next action, in the order of the enum. */
    public static List<NextActionOption> all() {
        return Arrays.stream(NextAction.values()).map(NextActionOption::of).toList();
    }
}
