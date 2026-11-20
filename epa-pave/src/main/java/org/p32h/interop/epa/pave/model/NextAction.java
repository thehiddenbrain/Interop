package org.p32h.interop.epa.pave.model;

/** What the tester should do after a run. Each value is something the screen can offer as a step. */
public enum NextAction {
    /** Answer the payer's questionnaire. */
    START_DTR,
    /** Answer the next adaptive question. */
    ANSWER_NEXT_QUESTION,
    /** Answer the questionnaire that was loaded and submit the response. */
    SUBMIT_RESPONSE,
    /** Submit the prior authorization request. */
    START_PAS,
    /** Ask for the status of a pended authorization. */
    CHECK_STATUS,
    /** The journey is complete at this step. That is itself a finding worth recording. */
    FLOW_ENDS,
    /**
     * There is nothing to do after this call, and no journey ended with it: a subscription was
     * read, listed, updated or deleted, or a handshake or a heartbeat arrived. Kept apart from
     * {@link #FLOW_ENDS} so that a call which decided nothing about a case is never read as the
     * end of that case's journey. It belongs to no stage of a journey and asks nothing of a person.
     */
    NOTHING_FURTHER,
    /** The member, provider or code is not known to the service. Check the test data. */
    FIX_TEST_DATA,
    /** The request was rejected as invalid. Check what was sent. */
    FIX_REQUEST,
    /** The payer asked for more information before it can answer. Supply it and send again. */
    SUPPLY_INFORMATION,
    /** The service failed or could not be reached. Check the environment. */
    CHECK_ENVIRONMENT,
    /** The tool is not configured to send this. Open Config. */
    OPEN_CONFIG,
    /** The production guardrail refused. Unlock writes or switch environment. */
    UNLOCK_OR_SWITCH,
    /** Look at the response detail: the tool cannot say what should happen next. */
    REVIEW
}
