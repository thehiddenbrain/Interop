package com.thehiddenbrain.interop.epa.pave.config;

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

    private final EpaProperties epa;
    private final Environment environment;

    public StartupChecks(EpaProperties epa, Environment environment) {
        this.epa = epa;
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
    }
}
