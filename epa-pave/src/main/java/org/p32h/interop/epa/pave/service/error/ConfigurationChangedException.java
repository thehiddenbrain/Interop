package org.p32h.interop.epa.pave.service.error;

/**
 * The configuration is not the one the request was made under. Nothing was sent. Answered with
 * HTTP 409.
 *
 * <p>Two things raise it. A prepared call carries the url, the credentials and the guardrail
 * decision of the environment it was prepared in; if the environment was switched or relocked
 * before it was sent, sending it would act on a decision that no longer holds. And a request can
 * say which configuration its screen was drawn from; if another tab or another tester has changed
 * the configuration since, the screen is showing one environment while the server would send to
 * another. Either way the tester reloads and sends again.
 */
public class ConfigurationChangedException extends RuntimeException {

    public ConfigurationChangedException() {
        super("The configuration changed while this request was being prepared.");
    }

    public ConfigurationChangedException(String message) {
        super(message);
    }

    /** The configuration changed since the screen that sent the request was drawn. */
    public static ConfigurationChangedException sinceTheScreenWasDrawn() {
        return new ConfigurationChangedException("The configuration changed after this screen was loaded, so the environment shown "
                + "may not be the one in force. Reload to see the current environment, then send again.");
    }
}
