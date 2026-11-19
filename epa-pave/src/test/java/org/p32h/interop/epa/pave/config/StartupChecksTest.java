package org.p32h.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupChecksTest {

    private static StartupChecks checks(String mode, String target, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new StartupChecks(EpaProperties.of(Map.of("mode", mode, "target", target)), environment);
    }

    @Test
    void startsWhenModeAndProfileAgree() {
        assertThatCode(() -> checks("mock", "onyx", "mock").afterPropertiesSet()).doesNotThrowAnyException();
        assertThatCode(() -> checks("live", "nehen", "live").afterPropertiesSet()).doesNotThrowAnyException();
    }

    @Test
    void refusesABadgeThatDisagreesWithTheWiring() {
        assertThatThrownBy(() -> checks("live", "onyx", "mock").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("epa.mode is 'live'");
        assertThatThrownBy(() -> checks("mock", "onyx", "live").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("epa.mode is 'mock'");
    }

    @Test
    void refusesBothProfilesOrNeither() {
        assertThatThrownBy(() -> checks("mock", "onyx", "mock", "live").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Exactly one");
        assertThatThrownBy(() -> checks("mock", "onyx", "staging").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Exactly one");
    }

    @Test
    void refusesAnUnknownTarget() {
        assertThatThrownBy(() -> checks("mock", "availity", "mock").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("epa.target");
    }
}
