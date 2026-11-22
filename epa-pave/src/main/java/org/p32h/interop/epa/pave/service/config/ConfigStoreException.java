package org.p32h.interop.epa.pave.service.config;

/**
 * The configuration store could not write to its directory while the tool was running, for
 * example because the disk filled up. Nothing was changed: the stored configuration and the
 * configuration in force are both what they were before the attempt.
 *
 * <p>The message names a file or a folder of the store, at most a count of files, and what to do.
 * It never holds content or the message of the exception underneath, so it is safe to show.
 */
public class ConfigStoreException extends RuntimeException {

    private final boolean thisComputer;

    /** A write that failed for a reason the store could not tell. The answer treats it as a failure of this tool. */
    public ConfigStoreException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private ConfigStoreException(String message, Throwable cause, boolean thisComputer) {
        super(message, cause);
        this.thisComputer = thisComputer;
    }

    /**
     * A write that failed for a reason the store recognised as one of this computer's files: a file
     * marked read-only, a file another program holds open, a full disk, a store folder changed
     * under the running tool. The message says what to do about it.
     */
    public static ConfigStoreException ofThisComputer(String message, Throwable cause) {
        return new ConfigStoreException(message, cause, true);
    }

    /**
     * True when the cause lies with this computer's files and the message says how to put it right.
     * Such an answer is headed as a problem with this computer's files, not as a failure of this
     * tool: the tester can mend it there and then, and a reference for a report would only send
     * them looking for a defect that is not there.
     */
    public boolean thisComputer() {
        return thisComputer;
    }
}
