package org.p32h.interop.epa.pave.model.dtr;

/**
 * What became of one enableWhen condition when it was evaluated against the answers.
 *
 * <p>There are three states and not two. A condition this tool cannot evaluate is reported as
 * exactly that, with the reason. It is never counted as true and never as false, because either
 * would show or hide a question on the strength of a guess.
 */
public enum ConditionState {
    TRUE("The condition holds"),
    FALSE("The condition does not hold"),
    NOT_EVALUATED("The condition could not be evaluated");

    private final String label;

    ConditionState(String label) {
        this.label = label;
    }

    /** The words for the state, so a screen keeps no copy of them. */
    public String label() {
        return label;
    }
}
