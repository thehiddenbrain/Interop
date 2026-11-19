package org.p32h.interop.epa.pave.service.config;

/**
 * The configuration store could not write to its directory while the tool was running, for
 * example because the disk filled up. Nothing was changed: the stored configuration and the
 * configuration in force are both what they were before the attempt.
 *
 * <p>The message names the file and nothing else. It is safe to show.
 */
public class ConfigStoreException extends RuntimeException {

    public ConfigStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
