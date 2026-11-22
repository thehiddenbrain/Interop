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
import tools.jackson.databind.JsonNode;

class ModeControllerTest {

    private final FakeGateway gateway = FakeGateway.unconfigured();
    private final MockEnvironment environment = new MockEnvironment();

    private JsonNode mode(String mode) throws Exception {
        return JsonSafe.parse(TestMvc.standalone(new ModeController(EpaProperties.of(Map.of("mode", mode)), gateway, environment))
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
    void itNamesTheInstantThisProcessStartedTheSameOnEveryAnswerAndAfterAChange() throws Exception {
        JsonNode first = mode("mock");
        String startedAt = first.get("startedAt").stringValue();
        // An ISO-8601 instant in UTC, as Instant.toString writes it.
        assertThat(java.time.Instant.parse(startedAt).toString()).isEqualTo(startedAt);
        assertThat(java.time.Instant.parse(startedAt)).isBeforeOrEqualTo(java.time.Instant.now());
        gateway.environment("Onyx Production", EnvironmentKind.PROD).changeConfiguration();
        JsonNode live = mode("live");
        assertThat(live.get("epoch").longValue()).isNotEqualTo(first.get("epoch").longValue());
        assertThat(live.get("startedAt").stringValue()).isEqualTo(startedAt);
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
    void itSaysWhereItListensAndWhatThatMeansForAServiceThatWouldCallTheReceiver() throws Exception {
        environment.setProperty("server.address", "127.0.0.1");
        JsonNode local = mode("mock");
        assertThat(local.get("bindAddress").stringValue()).isEqualTo("127.0.0.1");
        assertThat(local.get("bindNote").stringValue()).isEqualTo("This tool listens on this machine only (127.0.0.1), so nothing on "
                + "another machine, a vendor's service included, can call its notification receiver. To let a service call it, set "
                + "server.address to an address that service can reach, and read the README section on the receiver first.");

        environment.setProperty("server.address", "10.20.30.40");
        JsonNode one = mode("live");
        assertThat(one.get("bindAddress").stringValue()).isEqualTo("10.20.30.40");
        assertThat(one.get("bindNote").stringValue()).isEqualTo("This tool listens on the address 10.20.30.40 only. Whether a service on "
                + "another machine, a vendor's included, can reach its notification receiver there depends on the network between them, "
                + "which this tool cannot see.");

        // With no address configured the server listens on every address, and the answer does not invent one.
        JsonNode every = JsonSafe.parse(TestMvc.standalone(new ModeController(EpaProperties.defaults(), gateway, new MockEnvironment()))
                .perform(get("/api/mode")).andReturn().getResponse().getContentAsString()).node();
        assertThat(every.has("bindAddress")).isFalse();
        assertThat(every.get("bindNote").stringValue()).startsWith("This tool listens on every network address of this machine.");
        assertThat(ModeController.bindNote("0.0.0.0")).isEqualTo(every.get("bindNote").stringValue());
        assertThat(ModeController.bindNote("::1")).startsWith("This tool listens on this machine only (::1)");
        assertThat(ModeController.bindNote("localhost")).startsWith("This tool listens on this machine only (localhost)");
    }

    @Test
    void thePortIsTheOneTheServerIsListeningOn() throws Exception {
        // Started on a port of the system's choosing, the configured value is 0 and the real one is published at startup.
        environment.setProperty("server.port", "0");
        environment.setProperty("local.server.port", "49152");
        assertThat(mode("mock").get("port").intValue()).isEqualTo(49152);
        // With neither set, the documented default.
        assertThat(JsonSafe.parse(TestMvc.standalone(new ModeController(EpaProperties.defaults(), gateway, new MockEnvironment()))
                .perform(get("/api/mode")).andReturn().getResponse().getContentAsString()).node().get("port").intValue()).isEqualTo(8057);
    }
}
