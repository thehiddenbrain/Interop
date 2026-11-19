package org.p32h.interop.epa.pave.service.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.lookup.HttpMemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.HttpProviderLookupClient;
import org.p32h.interop.epa.pave.client.lookup.LookupFixtures;
import org.p32h.interop.epa.pave.client.lookup.MemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.MockMemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.MockProviderLookupClient;
import org.p32h.interop.epa.pave.client.lookup.ProviderLookupClient;
import org.p32h.interop.epa.pave.service.MemberLookup;
import org.p32h.interop.epa.pave.service.ProviderLookup;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.LookupController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The application as a tester starts it on a fresh checkout, with no profile and no edited file:
 * the lookups are wired to the mock clients and their synthetic fixtures, and answer.
 */
@SpringBootTest
class LookupWiringTest {

    @Autowired ApplicationContext context;
    @Autowired MemberLookupClient memberClient;
    @Autowired ProviderLookupClient providerClient;
    @Autowired MemberLookup members;
    @Autowired ProviderLookup providers;
    @Autowired LookupController controller;
    @Autowired LookupFixtures fixtures;

    @Test
    void theMockClientsAreTheOnesInTheContextAndTheLiveOnesAreNot() {
        assertThat(memberClient).isExactlyInstanceOf(MockMemberLookupClient.class);
        assertThat(providerClient).isExactlyInstanceOf(MockProviderLookupClient.class);
        assertThat(context.getBeanNamesForType(MemberLookupClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ProviderLookupClient.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(HttpMemberLookupClient.class)).isEmpty();
        assertThat(context.getBeanNamesForType(HttpProviderLookupClient.class)).isEmpty();
        assertThat(members).isExactlyInstanceOf(MemberLookupService.class);
        assertThat(providers).isExactlyInstanceOf(ProviderLookupService.class);
        assertThat(context.getBeanNamesForType(MemberLookup.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ProviderLookup.class)).hasSize(1);
        assertThat(fixtures.members()).isNotEmpty();
        assertThat(fixtures.providers()).isNotEmpty();
    }

    @Test
    void nothingTheLookupsNeedOnTheMockProfileComesFromTheGateway() {
        // The mock path never asks the gateway for anything, so the lookups work on a default start
        // whether or not a gateway is configured, and before one exists.
        for (Class<?> type : new Class<?>[] {MockMemberLookupClient.class, MockProviderLookupClient.class, LookupFixtures.class,
                MemberLookupService.class, ProviderLookupService.class, LookupController.class}) {
            for (var constructor : type.getDeclaredConstructors()) {
                assertThat(constructor.getParameterTypes()).as(type.getSimpleName()).doesNotContain(OperationGateway.class);
            }
        }
    }

    @Test
    void theEndpointsAnswerFromTheSyntheticFixturesThroughTheWiredController() throws Exception {
        MockMvc mvc = TestMvc.standalone(controller);
        mvc.perform(get("/api/members/{memberId}", "P32H-PA-REQ")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.member.lineOfBusiness").value("Medicare"))
                .andExpect(jsonPath("$.wire.request.sent").value(false));
        mvc.perform(get("/api/members/{memberId}", "S12345678   01")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.memberId.sent").value("S1234567801"));
        mvc.perform(get("/api/members/{memberId}", "P32H-NOMEM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mvc.perform(get("/api/providers/{npi}", "1234567893")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FOUND"))
                .andExpect(jsonPath("$.provider.specialty").value("Sleep Medicine"));
        mvc.perform(get("/api/providers/{npi}", "9999999995")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));
        mvc.perform(get("/api/providers/{npi}", "1234567890")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("npi"));
    }
}
