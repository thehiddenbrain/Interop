package org.p32h.interop.epa.pave.config;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Refuses to start on a configuration that would make the screen lie.
 *
 * <p>The mode badge is driven by {@code epa.mode} and the client wiring by the Spring profile. If
 * the two could disagree, a tester could see "live" while every answer came from a scenario file,
 * or the reverse. So the application starts only when exactly one of the mock and live profiles
 * is active and {@code epa.mode} names the same one.
 */
@Component
public class StartupChecks implements InitializingBean {

    private static final List<String> TARGETS = List.of("onyx", "nehen");

    /** The two ways supporting documentation can travel with a prior authorization request. */
    public static final String ATTACHMENT_FHIR = "fhir";
    public static final String ATTACHMENT_X12_275 = "x12-275";

    private final EpaProperties epa;
    private final OnyxProperties onyx;
    private final LookupProperties lookup;
    private final TrackingProperties tracking;
    private final AppProperties app;
    private final Environment environment;

    public StartupChecks(EpaProperties epa, OnyxProperties onyx, LookupProperties lookup, TrackingProperties tracking, AppProperties app,
                         Environment environment) {
        this.epa = epa;
        this.onyx = onyx;
        this.lookup = lookup;
        this.tracking = tracking;
        this.app = app;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean mockProfile = environment.matchesProfiles(EpaProperties.MOCK);
        boolean liveProfile = environment.matchesProfiles(EpaProperties.LIVE);
        String active = Arrays.toString(environment.getActiveProfiles());
        if (mockProfile == liveProfile) {
            throw new IllegalStateException("Exactly one of the Spring profiles 'mock' and 'live' must be active, but the active "
                    + "profiles are " + active + ". Start with no profile for mock, or with --spring.profiles.active=live.");
        }
        String profile = mockProfile ? EpaProperties.MOCK : EpaProperties.LIVE;
        if (!profile.equalsIgnoreCase(epa.mode())) {
            throw new IllegalStateException("epa.mode is '" + epa.mode() + "' but the active Spring profile is '" + profile
                    + "'. Do not set epa.mode by hand: it follows the profile. Start with --spring.profiles.active=live for live.");
        }
        if (epa.target() == null || !TARGETS.contains(epa.target().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalStateException("epa.target is '" + epa.target() + "'. It must be one of " + TARGETS + ".");
        }
        // Free text here would be found at the first submission, if at all. A misspelt mode is
        // refused now, where the person who typed it is looking.
        String mode = onyx.pas().attachmentMode();
        if (!ATTACHMENT_FHIR.equals(mode) && !ATTACHMENT_X12_275.equals(mode)) {
            throw new IllegalStateException("onyx.pas.attachment-mode is '" + mode + "'. It must be '" + ATTACHMENT_FHIR + "' or '"
                    + ATTACHMENT_X12_275 + "'.");
        }
        // A timeout of zero does not mean "no timeout" to the HTTP client. A read timeout of zero
        // reports every call as timed out, and a connect timeout of zero fails while the live beans
        // are built, neither with a word about which setting did it. Checked on both profiles, so a
        // bad value is found on the mock profile, before anyone switches to live.
        positive("onyx.connect-timeout-ms", onyx.connectTimeoutMs());
        positive("onyx.read-timeout-ms", onyx.readTimeoutMs());
        positive("lookup.member.connect-timeout-ms", lookup.member().connectTimeoutMs());
        positive("lookup.member.read-timeout-ms", lookup.member().readTimeoutMs());
        positive("lookup.provider.connect-timeout-ms", lookup.provider().connectTimeoutMs());
        positive("lookup.provider.read-timeout-ms", lookup.provider().readTimeoutMs());
        positive("app.autocomplete-limit", app.autocompleteLimit());
        if (tracking.slowThresholdMs() < 0) {
            throw new IllegalStateException("tracking.slow-threshold-ms is " + tracking.slowThresholdMs()
                    + ". It must be zero, to use the Onyx read timeout, or greater.");
        }
    }

    private static void positive(String property, long value) {
        if (value <= 0) {
            throw new IllegalStateException(property + " is " + value + ". It must be greater than zero.");
        }
    }
}
