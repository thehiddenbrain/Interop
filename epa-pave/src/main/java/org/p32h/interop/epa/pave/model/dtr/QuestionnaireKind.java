package org.p32h.interop.epa.pave.model.dtr;

/**
 * How a questionnaire is meant to be answered. The two kinds are exercised differently, so the
 * result of a package fetch says which one each questionnaire is, and says so when it cannot tell.
 */
public enum QuestionnaireKind {

    /** Answered one step at a time: the service chooses each next question from the answers so far. */
    ADAPTIVE("adaptive", "Adaptive: the service chooses each next question from the answers so far"),
    /** A conventional form that carries all its items, with enableWhen deciding which of them show. */
    STATIC("static", "Static: a conventional form that carries all its items"),
    /** Nothing on the questionnaire settles it. The tool does not pick one. */
    UNDETERMINED("undetermined", "This tool cannot tell whether it is adaptive or static");

    private final String code;
    private final String label;

    QuestionnaireKind(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /** The code written to the ledger and compared with a declared expectation. */
    public String code() {
        return code;
    }

    public String label() {
        return label;
    }
}
