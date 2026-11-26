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
        return checks(mode, target, onyx, LookupProperties.defaults(), TrackingProperties.defaults(), AppProperties.defaults(), profiles);
    }

    private static StartupChecks checks(String mode, String target, OnyxProperties onyx, LookupProperties lookup,
                                        TrackingProperties tracking, AppProperties app, String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new StartupChecks(EpaProperties.of(Map.of("mode", mode, "target", target)), onyx, lookup, tracking, app, environment);
    }

    @Test
    void refusesATimeoutOrALimitThatWouldFailEveryCallWithoutSayingWhy() {
        // Zero is not "no timeout" to the HTTP client: a read timeout of zero reports every call as
        // timed out. Refused on the mock profile too, before anyone switches to live.
        for (String property : new String[] {"connect-timeout-ms", "read-timeout-ms"}) {
            for (String value : new String[] {"0", "-1"}) {
                assertThatThrownBy(() -> checks("mock", "onyx", OnyxProperties.of(Map.of(property, value)), "mock").afterPropertiesSet())
                        .as(property + "=" + value).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("onyx." + property + " is " + value);
            }
        }
        for (String property : new String[] {"member.connect-timeout-ms", "member.read-timeout-ms", "provider.connect-timeout-ms",
                "provider.read-timeout-ms"}) {
            assertThatThrownBy(() -> checks("mock", "onyx", OnyxProperties.defaults(), LookupProperties.of(Map.of(property, "0")),
                    TrackingProperties.defaults(), AppProperties.defaults(), "mock").afterPropertiesSet())
                    .as(property).isInstanceOf(IllegalStateException.class).hasMessageContaining("lookup." + property + " is 0");
        }
        assertThatThrownBy(() -> checks("mock", "onyx", OnyxProperties.defaults(), LookupProperties.defaults(),
                TrackingProperties.of(Map.of("slow-threshold-ms", "-5")), AppProperties.defaults(), "mock").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("tracking.slow-threshold-ms is -5");
        assertThatThrownBy(() -> checks("mock", "onyx", OnyxProperties.defaults(), LookupProperties.defaults(),
                TrackingProperties.defaults(), AppProperties.of(Map.of("autocomplete-limit", "0")), "mock").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("app.autocomplete-limit is 0");
        // Zero is allowed for the slow threshold, where it means "use the Onyx read timeout".
        assertThatCode(() -> checks("mock", "onyx", OnyxProperties.defaults(), LookupProperties.defaults(),
                TrackingProperties.of(Map.of("slow-threshold-ms", "0")), AppProperties.defaults(), "mock").afterPropertiesSet())
                .doesNotThrowAnyException();
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
