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
 *
 * <p>A refusal that knows what changed carries that as its {@link #title()} and what to do about
 * it as its {@link #action()}, and the error shape shows both. A refusal that does not know is
 * shown in the tool's general words for a changed configuration.
 */
public class ConfigurationChangedException extends RuntimeException {

    private final String title;
    private final String action;

    public ConfigurationChangedException() {
        super("The configuration changed while this request was being prepared.");
        this.title = null;
        this.action = null;
    }

    public ConfigurationChangedException(String message) {
        super(message);
        this.title = message;
        this.action = null;
    }

    /**
     * A refusal that says what happened and what to do. The message is the two sentences
     * together, for the places that report it as one text, such as a case result.
     */
    public ConfigurationChangedException(String title, String action) {
        super(title + " " + action);
        this.title = title;
        this.action = action;
    }

    /** The configuration changed since the screen that sent the request was drawn. */
    public static ConfigurationChangedException sinceTheScreenWasDrawn() {
        return new ConfigurationChangedException("The configuration changed after this screen was loaded, so the environment shown "
                + "may not be the one in force. Nothing was sent.", "Reload to see the current environment, then send again.");
    }

    /** Production writes were locked again between preparing a write and sending it. */
    public static ConfigurationChangedException writesLockedAgain() {
        return new ConfigurationChangedException("Production writes were locked again before this write was sent, so it was not sent.",
                "Unlock them on the Config tab by typing the environment name, then send it again.");
    }

    /** What happened, in the refusal's own words, or null when it was raised without them. */
    public String title() {
        return title;
    }

    /** What the tester should do, in the refusal's own words, or null when it carries none. */
    public String action() {
        return action;
    }
}
