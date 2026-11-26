package org.p32h.interop.epa.pave.service.error;

/** The thing asked for does not exist in this tool, such as a run id or an environment name. Answered with HTTP 404. */
public class NotFoundException extends RuntimeException {

    private final String action;

    public NotFoundException(String message) {
        this(message, null);
    }

    /**
     * @param action what the person can do about it, or null for the general advice to check the
     *               identifier. Given where the identifier was not typed but chosen from a list
     *               that has since changed, so that checking it would help nobody
     */
    public NotFoundException(String message, String action) {
        super(message);
        this.action = action;
    }

    /** What the person can do, or null when the general advice applies. */
    public String action() {
        return action;
    }

    /**
     * Something of the configuration that was chosen from a list the store no longer matches: it
     * was removed, or uploaded again, in another window or by another person since the list was
     * read. Nobody typed an identifier, so the advice is to read the list again.
     *
     * @param what  what is no longer held, as the start of the sentence, such as "The environment"
     * @param which what list it was chosen from, such as "environments"
     */
    public static NotFoundException noLongerHeld(String what, String which) {
        return new NotFoundException(what + " is no longer held by this tool. It may have been removed, or uploaded again, in another "
                + "window since the list was read.", "Read the list of " + which + " again and choose from it as it is now.");
    }
}
