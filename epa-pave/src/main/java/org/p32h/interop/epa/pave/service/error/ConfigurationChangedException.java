package org.p32h.interop.epa.pave.service.error;

/**
 * The configuration changed between preparing a call and sending it. Nothing was sent. Answered
 * with HTTP 409.
 *
 * <p>A prepared call carries the url, the credentials and the guardrail decision of the environment
 * it was prepared in. If the environment was switched or relocked in the meantime, sending it would
 * act on a decision that no longer holds, so it is refused and the tester sends again.
 */
public class ConfigurationChangedException extends RuntimeException {

    public ConfigurationChangedException() {
        super("The configuration changed while this request was being prepared.");
    }
}
