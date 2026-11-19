package org.p32h.interop.epa.pave.model.dtr;

/**
 * What became of one enableWhen condition when it was evaluated against the answers.
 *
 * <p>There are three states and not two. A condition this tool cannot evaluate is reported as
 * exactly that, with the reason. It is never counted as true and never as false, because either
 * would show or hide a question on the strength of a guess.
 */
public enum ConditionState {
    TRUE,
    FALSE,
    NOT_EVALUATED
}
