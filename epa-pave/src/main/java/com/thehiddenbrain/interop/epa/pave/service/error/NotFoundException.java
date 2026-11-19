package com.thehiddenbrain.interop.epa.pave.service.error;

/** The thing asked for does not exist in this tool, such as a run id or an environment name. Answered with HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
