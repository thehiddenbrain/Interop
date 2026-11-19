package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

class ModeControllerTest {

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final MockEnvironment environment = new MockEnvironment();

    /** Registered through an annotated subclass, for the reason given in CrdControllerTest. */
    @RestController
    static class Wired extends ModeController {
        Wired(EpaProperties epa, OperationGateway gateway, Environment environment) {
            super(epa, gateway, environment);
        }
    }

    private JsonNode mode(String mode) throws Exception {
        return JsonSafe.parse(TestMvc.standalone(new Wired(EpaProperties.of(Map.of("mode", mode)), gateway, environment))
                .perform(get("/api/mode")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).node();
    }

    @Test
    void itSaysWhatThisToolIsAndWhatItIsPointedAt() throws Exception {
        environment.setProperty("server.port", "8057");
        JsonNode view = mode("mock");
        assertThat(view.get("name").stringValue()).isEqualTo("PAVE");
        assertThat(view.get("version").stringValue()).isEqualTo("1.0.0");
        assertThat(view.get("port").intValue()).isEqualTo(8057);
        assertThat(view.get("mode").stringValue()).isEqualTo("mock");
        assertThat(view.get("target").stringValue()).isEqualTo("onyx");
        // No environment is active out of the box, and the strip reads a flag for that, not a missing name.
        assertThat(view.get("environment").get("active").booleanValue()).isFalse();
        assertThat(view.get("environment").has("name")).isFalse();
        assertThat(view.get("epoch").longValue()).isZero();
        assertThat(view.get("writeAccess").get("production").booleanValue()).isFalse();
        assertThat(view.get("writeAccess").get("writesAllowed").booleanValue()).isTrue();
        assertThat(view.get("writeAccess").get("reason").stringValue()).isNotBlank();
    }

    @Test
    void theActiveEnvironmentAndTheEpochFollowTheConfiguration() throws Exception {
        gateway.environment("Onyx Production", EnvironmentKind.PROD).changeConfiguration();
        JsonNode view = mode("live");
        assertThat(view.get("mode").stringValue()).isEqualTo("live");
        assertThat(view.get("environment").get("name").stringValue()).isEqualTo("Onyx Production");
        assertThat(view.get("environment").get("kind").stringValue()).isEqualTo("prod");
        assertThat(view.get("environment").get("active").booleanValue()).isTrue();
        assertThat(view.get("epoch").longValue()).isEqualTo(1);
        // Production, and no unlock in this session: a write would not be sent, and the reason says so.
        assertThat(view.get("writeAccess").get("production").booleanValue()).isTrue();
        assertThat(view.get("writeAccess").get("writesAllowed").booleanValue()).isFalse();
    }

    @Test
    void thePortIsTheOneTheServerIsListeningOn() throws Exception {
        // Started on a port of the system's choosing, the configured value is 0 and the real one is published at startup.
        environment.setProperty("server.port", "0");
        environment.setProperty("local.server.port", "49152");
        assertThat(mode("mock").get("port").intValue()).isEqualTo(49152);
        // With neither set, the documented default.
        assertThat(JsonSafe.parse(TestMvc.standalone(new Wired(EpaProperties.defaults(), gateway, new MockEnvironment()))
                .perform(get("/api/mode")).andReturn().getResponse().getContentAsString()).node().get("port").intValue()).isEqualTo(8057);
    }
}
