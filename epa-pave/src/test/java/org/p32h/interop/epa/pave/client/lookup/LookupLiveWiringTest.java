package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/**
 * The application started with the live profile: the lookups are wired to the live clients, no
 * synthetic member can be served, and with nothing configured a lookup is disabled and not an
 * error. Nothing here touches a network, because nothing is configured to be sent anywhere.
 */
@SpringBootTest(properties = "spring.profiles.active=live")
class LookupLiveWiringTest {

    /**
     * The live clients take their target from the gateway. A stand-in with nothing configured is
     * supplied here, and is marked primary so that it is the one injected whether or not another
     * gateway is in the context as well.
     */
    @TestConfiguration
    static class StandInGateway {

        @Bean
        @Primary
        FakeGateway standInGateway() {
            return FakeGateway.unconfigured();
        }
    }

    @Autowired ApplicationContext context;
    @Autowired Environment environment;
    @Autowired MemberLookupClient memberClient;
    @Autowired ProviderLookupClient providerClient;
    @Autowired MemberLookup members;
    @Autowired ProviderLookup providers;
    @Autowired FakeGateway gateway;

    @Test
    void theLiveClientsAreTheOnesInTheContextAndNoSyntheticFixtureIsLoaded() {
        assertThat(environment.matchesProfiles("live")).isTrue();
        assertThat(memberClient).isExactlyInstanceOf(HttpMemberLookupClient.class);
        assertThat(providerClient).isExactlyInstanceOf(HttpProviderLookupClient.class);
        assertThat(context.getBeanNamesForType(MemberLookupClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ProviderLookupClient.class)).hasSize(1);
        // On live an invented member must never be handed to a tester as an answer from the source of truth.
        assertThat(context.getBeanNamesForType(MockMemberLookupClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(MockProviderLookupClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(LookupFixtures.class)).isEmpty();
    }

    @Test
    void withNothingConfiguredALookupIsDisabledAndTheSentinelsAreNotAnswered() {
        int asked = gateway.intents().size();
        MemberLookupResult member = members.lookup("P32H-PA-REQ");
        assertThat(member.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(member.message()).isEqualTo("Member lookup is not configured, so there is no url to ask: set lookup.member.url, and "
                + "until then type the member details by hand.");
        assertThat(member.error()).isNull();
        assertThat(member.member()).isNull();
        assertThat(member.memberId().sent()).isEqualTo("P32H-PA-REQ");

        ProviderLookupResult provider = providers.lookup("1234567893");
        assertThat(provider.status()).isEqualTo(LookupStatus.DISABLED);
        assertThat(provider.error()).isNull();
        assertThat(provider.provider()).isNull();
        // Each lookup asked the gateway for its target, once.
        assertThat(gateway.intents()).hasSize(asked + 2);
    }
}
