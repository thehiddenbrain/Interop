package org.p32h.interop.epa.pave.service.error;

/**
 * The tool is already doing the one thing of this kind it does at a time, such as running a suite.
 * Answered with HTTP 409. The message says what is in the way; the action says what to do.
 */
public class BusyException extends RuntimeException {

    private final String action;

    public BusyException(String title, String action) {
        super(title);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
