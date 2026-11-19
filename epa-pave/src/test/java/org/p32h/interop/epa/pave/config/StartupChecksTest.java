package org.p32h.interop.epa.pave.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class StartupChecksTest {

    private static StartupChecks checks(String mode, String target, String... profiles) {
        return checks(mode, target, OnyxProperties.defaults(), profiles);
    }

    private static StartupChecks checks(String mode, String target, OnyxProperties onyx, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new StartupChecks(EpaProperties.of(Map.of("mode", mode, "target", target)), onyx, environment);
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
    void acceptsTheTwoAttachmentModesAndRefusesAnythingElse() {
        for (String mode : new String[] {"fhir", "x12-275"}) {
            assertThatCode(() -> checks("mock", "onyx", OnyxProperties.of(Map.of("pas.attachment-mode", mode)), "mock")
                    .afterPropertiesSet()).as(mode).doesNotThrowAnyException();
        }
        // Free text that binds without complaint would only be found at the first submission, if at all.
        for (String mode : new String[] {"x12_275", "X12-275", "FHIR", "x12", ""}) {
            assertThatThrownBy(() -> checks("mock", "onyx", OnyxProperties.of(Map.of("pas.attachment-mode", mode)), "mock")
                    .afterPropertiesSet()).as("[" + mode + "]").isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("onyx.pas.attachment-mode");
        }
    }

    @Test
    void refusesAnUnknownTarget() {
        assertThatThrownBy(() -> checks("mock", "availity", "mock").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("epa.target");
    }
}
