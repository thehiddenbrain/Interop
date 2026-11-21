package org.p32h.interop.epa.pave.model.chain;

/**
 * Where one stage of a chain stands. Kept apart from the outcome of the stage's last run, because
 * four of these states describe a stage with no run at all, and an outcome cannot say why a run is
 * missing.
 */
public enum ChainStageState {
    /** The stage ran and handed on to a later stage, or ended the journey. */
    DONE,
    /** The stage has run and has more to do before it hands on: a questionnaire half answered, an authorization pended. */
    IN_PROGRESS,
    /** The stage ran and its last run needs a person to fix something before the journey can go on. */
    NEEDS_ATTENTION,
    /** The stage has not run and is what the journey calls for now. */
    NEXT,
    /** The stage has not run because an earlier stage said the journey does not need it. */
    NOT_NEEDED,
    /** The stage has not run because an earlier stage has not finished. */
    NOT_REACHED,
    /** The stage has no run in this chain although a later stage has: the journey was started further along. */
    NOT_RUN
}
