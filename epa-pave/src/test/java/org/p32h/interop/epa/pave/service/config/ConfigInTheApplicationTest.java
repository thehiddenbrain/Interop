package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The configuration layer as the running application wires it, on the default profile, which is
 * mock, with an empty store: what a tester has in front of them on a fresh checkout.
 */
@SpringBootTest
class ConfigInTheApplicationTest {

    @TempDir
    static Path directory;

    /** The store is kept out of the project directory, where the default would put it. */
    @DynamicPropertySource
    static void storeDirectory(DynamicPropertyRegistry registry) {
        registry.add("config-store.directory", () -> directory.resolve("config-store").toString());
    }

    @Autowired WebApplicationContext context;
    @Autowired OperationGateway gateway;

    @Test
    void thereIsExactlyOneGatewayAndItIsTheOneThatReadsTheStore() {
        assertThat(context.getBeansOfType(OperationGateway.class)).hasSize(1);
        assertThat(gateway).isInstanceOf(ConfigStoreGateway.class);
        assertThat(context.getBeansOfType(ConfigStore.class)).hasSize(1);
        assertThat(context.getBeansOfType(ConfigController.class)).hasSize(1);
        // The directory was created at startup.
        assertThat(Files.isDirectory(directory.resolve("config-store").resolve(ConfigStore.ENVIRONMENTS))).isTrue();
    }

    @Test
    void outOfTheBoxACallIsPreparedAndSaysItHasNoTarget() {
        PreparedCall call = gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        assertThat(call.url().configured()).isFalse();
        assertThat(call.environment().active()).isFalse();
        assertThat(call.epoch()).isEqualTo(gateway.epoch());
        // A write runs too: on mock nothing is sent, and the out-of-the-box install must be able to try a submit.
        assertThat(gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).method()).isEqualTo("POST");
    }

    @Test
    void whatTheModeEndpointIsBuiltFromIsAnsweredWithoutThrowing() {
        assertThat(gateway.epoch()).isPositive();
        assertThat(gateway.activeEnvironment().active()).isFalse();
        WriteAccess access = gateway.writeAccess(null);
        assertThat(access.production()).isFalse();
        assertThat(access.writesAllowed()).isTrue();
        assertThat(access.floorAllows()).isFalse();
        assertThat(gateway.resolve("{{anything}}", null).resolved()).isFalse();
    }

    @Test
    void theConfigEndpointsAreServedWithTheApplicationsOwnJsonAndErrorHandling() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();

        mvc.perform(get("/api/config/effective")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("mock"))
                .andExpect(jsonPath("$.environment.active").value(false))
                .andExpect(jsonPath("$.allowProdWrites").value(false))
                .andExpect(jsonPath("$.operations", hasSize(EpaOperation.values().length)))
                // A null is left out by the application's mapper too, never written as null.
                .andExpect(jsonPath("$.operations[0].target").doesNotExist())
                .andExpect(jsonPath("$.operations[0].unavailable").doesNotExist());
        mvc.perform(get("/api/config/environments")).andExpect(status().isOk());
        mvc.perform(get("/api/config/collections")).andExpect(status().isOk());

        // A misspelt property is refused by the mapper the application really uses.
        mvc.perform(post("/api/config/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"operation\": \"PAS_INQUIRE\", \"runValues\": {}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("runValues"));
        mvc.perform(post("/api/config/writes").contentType(MediaType.APPLICATION_JSON).content("{\"action\": \"unlock\", \"environment\": \"x\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("There is nothing to unlock: no environment is active."));
        mvc.perform(get("/api/config/collections/{id}/requests/{requestId}", "none", "none")).andExpect(status().isNotFound());

    }

    @Test
    void anUploadThroughTheApplicationChangesWhereACallGoesWithNoRestart() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
        long before = gateway.epoch();

        // Through the application's own multipart handling. The file is stored under the temporary directory.
        mvc.perform(multipart("/api/config/collections").file(new MockMultipartFile("file", PostmanFiles.VENDOR_COLLECTION, "application/json",
                        PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collection.requests", hasSize(13)));
        String collectionId = context.getBean(ConfigStore.class).snapshot().collections().get(0).id();
        try {
            assertThat(gateway.epoch()).isGreaterThan(before);
            assertThat(gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).requestName()).isEqualTo("inquiry Happy Path");
            mvc.perform(get("/api/config/effective")).andExpect(jsonPath("$.operations[7].requests[0].request.name").value("inquiry Happy Path"));
        } finally {
            // The other tests of this class share the store, and expect it empty.
            mvc.perform(delete("/api/config/collections/{id}", collectionId)).andExpect(status().isOk());
        }
        assertThat(gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).requestName()).isNull();
    }
}
