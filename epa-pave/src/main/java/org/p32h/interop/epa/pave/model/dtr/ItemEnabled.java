package org.p32h.interop.epa.pave.model.dtr;

/**
 * Whether an item of a questionnaire is enabled by the answers given.
 *
 * <p>{@code UNDETERMINED} means a condition that decides it could not be evaluated. Such an item is
 * still shown, and when it is required and has no answer it still stops the response from being
 * called complete: leaving it out would let a response pass because a question was not looked at.
 */
public enum ItemEnabled {
    ENABLED,
    DISABLED,
    UNDETERMINED
}
