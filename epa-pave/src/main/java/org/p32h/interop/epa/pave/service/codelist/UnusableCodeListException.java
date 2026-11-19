package org.p32h.interop.epa.pave.service.codelist;

/**
 * The configured code list cannot be used, so the application must not start with it.
 *
 * <p>Every message of this type is written by the loader itself: what is wrong, which file or
 * setting it is about, and what to change. None is taken from another exception. That is what
 * makes it safe to log as it stands, which a message from a file system or a JSON parser is not.
 */
public final class UnusableCodeListException extends IllegalStateException {

    public UnusableCodeListException(String message) {
        super(message);
    }
}
