package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;

/**
 * The contract every batch builds against for the settings a call carries (plan 9.2, D1c): what a
 * prepared call and the gateway answer before any environment states a setting, and the records
 * that hold the settings.
 */
class CallSettingsContractTest {

    @TempDir
    Path directory;

    @Test
    void aPreparedCallCarriesApplicationYmlTimeoutsForItsOperationAndNoSignInOfItsOwn() {
        ConfigFixture config = ConfigFixture.on(directory)
                .onyx("connect-timeout-ms", "4000").onyx("read-timeout-ms", "120000")
                .lookup("member.connect-timeout-ms", "1500").lookup("member.read-timeout-ms", "2500")
                .build();

        PreparedCall crd = config.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(crd.timeouts()).isEqualTo(Timeouts.application(4000, 120000));
        assertThat(crd.timeouts().connectFrom()).isEqualTo("application.yml");
        assertThat(crd.timeouts().readFrom()).isEqualTo("application.yml");
        // No environment states a sign-in yet, so onyx.auth decides, as before.
        assertThat(crd.signIn()).isNull();

        PreparedCall member = config.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP));
        assertThat(member.timeouts()).isEqualTo(Timeouts.application(1500, 2500));
    }

    @Test
    void theGatewayBudgetsByTheLongestTimeoutAnyCallMayUse() {
        ConfigFixture config = ConfigFixture.on(directory)
                .onyx("connect-timeout-ms", "4000").onyx("read-timeout-ms", "20000")
                .lookup("provider.connect-timeout-ms", "6000").lookup("provider.read-timeout-ms", "5000")
                .build();

        assertThat(config.gateway.activeTimeouts()).isEqualTo(Timeouts.application(6000, 20000));
    }

    @Test
    void aGatewayThatKnowsNoConfigurationAnswersThePropertiesOwnDefaults() {
        OperationGateway bare = new OperationGateway() {
            @Override
            public PreparedCall prepare(CallIntent intent) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.p32h.interop.epa.pave.model.ResolvedText resolve(String template, java.util.Map<String, String> runValues) {
                throw new UnsupportedOperationException();
            }

            @Override
            public org.p32h.interop.epa.pave.model.EnvironmentRef activeEnvironment() {
                return org.p32h.interop.epa.pave.model.EnvironmentRef.none();
            }

            @Override
            public WriteAccess writeAccess(ProdUnlock unlock) {
                throw new UnsupportedOperationException();
            }

            @Override
            public long epoch() {
                return 0;
            }
        };
        OnyxProperties defaults = OnyxProperties.defaults();

        assertThat(bare.activeTimeouts()).isEqualTo(Timeouts.APPLICATION);
        assertThat(Timeouts.APPLICATION.connectMs()).isEqualTo(defaults.connectTimeoutMs());
        assertThat(Timeouts.APPLICATION.readMs()).isEqualTo(defaults.readTimeoutMs());
    }

    @Test
    void anEnvironmentsTimeoutWinsWhereItStatesOneAndKeepsItsSource() {
        Timeouts stated = new Timeouts(null, 60000, null, Timeouts.FROM_ENVIRONMENT);

        Timeouts inForce = stated.over(Timeouts.application(5000, 120000));

        assertThat(inForce).isEqualTo(new Timeouts(5000, 60000, "application.yml", "this environment"));
        assertThat(Timeouts.notStated().empty()).isTrue();
        assertThat(stated.empty()).isFalse();
        assertThat(Timeouts.notStated().connectOr(5000)).isEqualTo(5000);
        assertThat(stated.readOr(1)).isEqualTo(60000);
    }

    @Test
    void signInSettingsNeverPrintASecretAndAlwaysStateTheirMethod() {
        SignInSettings credentials = SignInSettings.clientCredentials("https://vendor.example.invalid/token", "placeholder-client",
                "placeholder-client-secret-value", "", null);
        SignInSettings bearer = SignInSettings.bearerToken("placeholder-bearer-token-value");

        assertThat(credentials.toString()).doesNotContain("placeholder-client-secret-value").contains("clientSecret redacted");
        assertThat(bearer.toString()).doesNotContain("placeholder-bearer-token-value").contains("bearerToken redacted");
        assertThat(SignInSettings.none().toString()).contains("clientSecret not set");
        // Basic is how this tool always sent client credentials, so an unstated choice keeps it.
        assertThat(credentials.clientAuthentication()).isEqualTo(SignInSettings.ClientAuthentication.BASIC);
        assertThatThrownBy(() -> new SignInSettings(null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownMethodOrClientAuthenticationIsNoValueRatherThanAnotherOne() {
        assertThat(SignInSettings.Method.parse(" client_credentials ")).isEqualTo(SignInSettings.Method.CLIENT_CREDENTIALS);
        assertThat(SignInSettings.Method.parse("PASSWORD")).isNull();
        assertThat(SignInSettings.Method.parse(null)).isNull();
        assertThat(SignInSettings.ClientAuthentication.parse("body")).isEqualTo(SignInSettings.ClientAuthentication.BODY);
        assertThat(SignInSettings.ClientAuthentication.parse("header")).isNull();
    }
}
