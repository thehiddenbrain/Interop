package org.p32h.interop.epa.pave.model;

/**
 * What the tester should do after a run. Each value is something the screen can offer as a step.
 *
 * <p>Each value carries its instruction in plain words. It is the one wording of the code: the chain
 * view, the suite rows and the vocabulary the scenarios endpoints serve to the History tab all read
 * it from here, so no screen keeps a copy that can say something else.
 */
public enum NextAction {
    /** Answer the payer's questionnaire. */
    START_DTR("Answer the payer's questionnaire (DTR)."),
    /**
     * Answer the next adaptive question. The label is the name of the DTR tab's button that does
     * it, so the next step and the screen give the action one name.
     */
    ANSWER_NEXT_QUESTION("Send answers and get the next question"),
    /** Answer the questionnaire that was loaded and complete the response, named as the DTR tab's button is. */
    SUBMIT_RESPONSE("Complete the response"),
    /** Submit the prior authorization request. */
    START_PAS("Submit the prior authorization request (PAS)."),
    /** Ask for the status of a pended authorization. */
    CHECK_STATUS("Ask for the status of the pended authorization (PAS)."),
    /** The journey is complete at this step. That is itself a finding worth recording. */
    FLOW_ENDS("Nothing more to do. The journey is complete at this step."),
    /**
     * There is nothing to do after this call, and no journey ended with it: a subscription was
     * read, listed, updated or deleted, or a handshake or a heartbeat arrived. Kept apart from
     * {@link #FLOW_ENDS} so that a call which decided nothing about a case is never read as the
     * end of that case's journey. It belongs to no stage of a journey and asks nothing of a person.
     */
    NOTHING_FURTHER("Nothing further to do after this call. No journey ended with it."),
    /** The member, provider or code is not known to the service. Check the test data. */
    FIX_TEST_DATA("Check the test data. The member, provider or code is not known to the service."),
    /** The request was rejected as invalid. Check what was sent. */
    FIX_REQUEST("Check what was sent. The service did not accept the request."),
    /** The payer asked for more information before it can answer. Supply it and send again. */
    SUPPLY_INFORMATION("Supply the information the payer asked for and send again."),
    /**
     * No usable answer came back. Check the environment. On the mock profile this follows a fault a
     * scenario played, where nothing was sent and no service failed, and the chain view and the
     * suite rows show this label for it as well, so it says both.
     */
    CHECK_ENVIRONMENT("Check the environment. No usable answer came back: on the live profile the service failed or could not be "
            + "reached, and on the mock profile a scenario played that, with nothing sent."),
    /** The tool is not configured to send this. Open Config. */
    OPEN_CONFIG("Open Config. The tool is not configured to send this."),
    /** The production guardrail refused. Unlock writes or switch environment. */
    UNLOCK_OR_SWITCH("Unlock production writes or switch environment. The guardrail refused to send."),
    /**
     * The production guardrail refused a write that came from a replay or a suite. Neither ever
     * carries the production unlock, so unlocking cannot help it: the write has to be sent by itself
     * from its own tab, or the tool pointed at something that is not production. The label does not
     * say why: the refusal beside it does, and saying it twice reads as two reasons.
     */
    SEND_ON_ITS_OWN("Send this write on its own from its tab, with production writes unlocked there, or point the tool at an "
            + "environment that is not production."),
    /**
     * The service answered and said nothing about prior authorization either way. A business tester
     * can act on that without reading the response: it is a result to record and to report to the
     * people who look after the service. The words blame nobody and say nothing about whether
     * authorization is needed, because the answer does not.
     */
    REPORT_NO_DETERMINATION("Record the run as \"no determination returned\" and report it to the people who look after the "
            + "service. The answer does not say whether prior authorization is needed."),
    /** Look at the response detail: the tool cannot say what should happen next. */
    REVIEW("Look at the response detail. The tool cannot say what should happen next.");

    private final String label;

    NextAction(String label) {
        this.label = label;
    }

    /** The instruction in plain words, as every screen shows it. */
    public String label() {
        return label;
    }
}
