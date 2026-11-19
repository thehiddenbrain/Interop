package com.thehiddenbrain.interop.epa.pave.service.error;

/**
 * Something supplied to the tool cannot be used as it stands, such as an uploaded file that is not
 * a Postman export. Answered with HTTP 422. The message says what is wrong; the action says what
 * to do instead.
 */
public class BadInputException extends RuntimeException {

    private final String action;

    public BadInputException(String title, String action) {
        super(title);
        this.action = action;
    }

    public String action() {
        return action;
    }
}
