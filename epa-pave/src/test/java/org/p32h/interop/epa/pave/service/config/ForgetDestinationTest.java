package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.web.ConfigController;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * "Forget this production address" (R-35, hunt-files 2): offered and done only for an address whose
 * environment is no longer stored as prod, only with that environment's name typed exactly, and
 * never for an address a stored prod environment holds. The refusal's note names Forget.
 */
class ForgetDestinationTest {

    private static final String ADDRESS = "https://sandbox.vendor.example.invalid/fhir/r4";

    @TempDir
    Path directory;

    private static byte[] environment(String name, String baseUrl) {
        return ("{\"name\": \"" + name + "\", \"values\": [{\"key\": \"baseUrl\", \"value\": \"" + baseUrl + "\", \"enabled\": true}]}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void upload(ConfigFixture fixture, String name, String baseUrl, EnvironmentKind kind) {
        fixture.store.uploadEnvironment(name + ".json", environment(name, baseUrl), kind, true);
    }

    @Test
    void onlyAnAddressWhoseEnvironmentIsGoneIsForgettableAndOnlyWithTheExactName() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, "Payer Live", "https://live.vendor.example.invalid/fhir", EnvironmentKind.PROD);
        upload(fixture, "Slipped Sandbox", ADDRESS, EnvironmentKind.PROD);
        fixture.store.removeEnvironment("Slipped Sandbox");

        assertThat(fixture.service.environments().productionDestinations()).containsExactly(
                new ConfigViews.ProductionDestinationView("Payer Live", "https://live.vendor.example.invalid/fhir", true, false),
                new ConfigViews.ProductionDestinationView("Slipped Sandbox", ADDRESS, false, true));

        // A stored prod environment's address is never forgotten, whatever is typed.
        assertThatThrownBy(() -> fixture.service.forgetDestination("https://live.vendor.example.invalid/fhir", "Payer Live"))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message()).isEqualTo("This "
                        + "address belongs to the production environment \"Payer Live\", which is stored. An address of a stored production "
                        + "environment is never forgotten."));
        // A name not typed exactly forgets nothing.
        long epoch = fixture.store.epoch();
        for (String typed : new String[] {"slipped sandbox", "Slipped Sandbox ", "Slipped", "Payer Live"}) {
            assertThatThrownBy(() -> fixture.service.forgetDestination(ADDRESS, typed)).isInstanceOfSatisfying(ValidationException.class,
                    e -> assertThat(e.fieldErrors().get(0).field()).isEqualTo("environment"));
        }
        assertThatThrownBy(() -> fixture.service.forgetDestination(ADDRESS, "")).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fixture.service.forgetDestination("https://elsewhere.example.invalid", "Slipped Sandbox"))
                .isInstanceOf(NotFoundException.class);
        assertThat(fixture.store.epoch()).isEqualTo(epoch);

        ConfigViews.Forgotten forgotten = fixture.service.forgetDestination(ADDRESS, "Slipped Sandbox");

        assertThat(forgotten.forgotten()).isEqualTo(ADDRESS);
        assertThat(forgotten.epoch()).isEqualTo(epoch + 1);
        assertThat(fixture.service.environments().productionDestinations()).extracting(ConfigViews.ProductionDestinationView::environment)
                .containsExactly("Payer Live");
        // Gone from store.json, so a restart does not bring it back; and the sandbox upload now goes through.
        ConfigFixture restarted = ConfigFixture.mock(directory);
        assertThat(restarted.store.snapshot().remembered()).extracting(ConfigSnapshot.KnownDestination::environment)
                .containsExactly("Payer Live");
        upload(restarted, "Slipped Sandbox", ADDRESS, EnvironmentKind.SANDBOX);
    }

    @Test
    void anAddressAStoredProdEnvironmentAlsoHoldsStaysWhenTheRemovedOneIsForgotten() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, "Payer Live A", ADDRESS, EnvironmentKind.PROD);
        upload(fixture, "Payer Live B", ADDRESS, EnvironmentKind.PROD);
        fixture.store.removeEnvironment("Payer Live A");

        assertThatThrownBy(() -> fixture.service.forgetDestination(ADDRESS, "Payer Live A")).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fieldErrors().get(0).message()).startsWith("This address belongs to the production environment "
                        + "\"Payer Live B\", which is stored."));
        assertThat(fixture.store.snapshot().productionOwners(org.p32h.interop.epa.pave.postman.UrlNormal.destination(ADDRESS)))
                .containsExactly("Payer Live A", "Payer Live B");
    }

    @Test
    void theRefusalsNoteNamesForgetAndForgetAnswersOverTheServer() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        upload(fixture, "Slipped Sandbox", ADDRESS, EnvironmentKind.PROD);
        fixture.store.removeEnvironment("Slipped Sandbox");
        assertThatThrownBy(() -> upload(fixture, "Slipped Sandbox", ADDRESS, EnvironmentKind.SANDBOX))
                .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message()).startsWith(
                        "\"Slipped Sandbox\" is no longer stored. If it was uploaded as prod by mistake, forget the address in "
                                + "Configuration > Production writes and upload this environment again. ")
                        .doesNotContain("store.json"));
        MockMvc mvc = TestMvc.standalone(new ConfigController(fixture.service));

        JsonNode list = JsonSafe.parse(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/config/environments")).andReturn().getResponse().getContentAsString()).node();
        assertThat(JsonSafe.bool(JsonSafe.elements(list, "productionDestinations").get(0), "forgettable")).isTrue();

        MockHttpServletResponse wrongName = mvc.perform(post("/api/config/production-destinations/forget")
                .contentType(MediaType.APPLICATION_JSON).content("{\"destination\": \"" + ADDRESS + "\", \"environment\": \"Slipped\"}"))
                .andReturn().getResponse();
        assertThat(wrongName.getStatus()).isEqualTo(422);
        MockHttpServletResponse misspelt = mvc.perform(post("/api/config/production-destinations/forget")
                .contentType(MediaType.APPLICATION_JSON).content("{\"destinaton\": \"" + ADDRESS + "\"}")).andReturn().getResponse();
        assertThat(misspelt.getStatus()).isEqualTo(422);

        MockHttpServletResponse done = mvc.perform(post("/api/config/production-destinations/forget")
                .contentType(MediaType.APPLICATION_JSON).content("{\"destination\": \"" + ADDRESS + "\", \"environment\": \"Slipped Sandbox\"}"))
                .andReturn().getResponse();
        assertThat(done.getStatus()).isEqualTo(200);
        JsonNode answer = JsonSafe.parse(done.getContentAsString()).node();
        assertThat(JsonSafe.text(answer, "forgotten")).isEqualTo(ADDRESS);
        assertThat(JsonSafe.longValue(answer, "epoch")).isEqualTo(fixture.store.epoch());
        assertThat(Files.readString(directory.resolve(ConfigStore.STATE_FILE))).doesNotContain("sandbox.vendor.example.invalid");
    }
}
