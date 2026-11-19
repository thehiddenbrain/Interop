package com.thehiddenbrain.interop.epa.pave.model;

/**
 * Where a run came from when it did not come from a tester pressing a button: a suite case or a
 * replay. A manual run has none of these. It is passed by the server-side runners and is never
 * read from a browser request.
 */
public record RunOrigin(String suiteRunId, String caseId, String replayOfRunId) {

    private static final RunOrigin MANUAL = new RunOrigin(null, null, null);

    public static RunOrigin manual() {
        return MANUAL;
    }

    public static RunOrigin of(CaseSpec spec) {
        return spec == null ? MANUAL : new RunOrigin(spec.suiteRunId(), spec.caseId(), spec.replayOfRunId());
    }
}
