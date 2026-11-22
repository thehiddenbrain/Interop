package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.ModeSwitch;
import org.p32h.interop.epa.pave.service.ledger.RunReplay;
import org.p32h.interop.epa.pave.service.suite.SuiteService;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
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

    /** Stands in for the restart: records what would have been done, and changes nothing. */
    private static final class Restarts implements ModeSwitch.Restarts {
        boolean fromMain = true;
        boolean filesChanged;
        String claimed;
        final List<String> restarted = new ArrayList<>();

        @Override
        public boolean canSwitch() {
            return fromMain;
        }

        @Override
        public boolean filesChanged() {
            return filesChanged;
        }

        @Override
        public boolean claim(String mode) {
            if (claimed != null) {
                return false;
            }
            claimed = mode;
            return true;
        }

        @Override
        public void release(String mode) {
            if (mode.equals(claimed)) {
                claimed = null;
            }
        }

        @Override
        public void restart(String from, String to) {
            restarted.add(from + " to " + to);
        }
    }

    private final Restarts restarts = new Restarts();
    private boolean suiteRunning;
    private boolean replayRunning;

    private MockHttpServletResponse switchTo(String mode, String body) throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        SuiteService suites = Mockito.mock(SuiteService.class);
        Mockito.when(suites.running()).thenReturn(suiteRunning);
        RunReplay replays = Mockito.mock(RunReplay.class);
        Mockito.when(replays.running()).thenReturn(replayRunning);
        beans.addBean("suites", suites);
        beans.addBean("replays", replays);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        ModeSwitch modeSwitch = new ModeSwitch(epa, beans.getBeanProvider(SuiteService.class), beans.getBeanProvider(RunReplay.class), restarts);
        return TestMvc.standalone(new ModeController(epa, gateway, environment, null, modeSwitch, TestMvc.mapper()))
                .perform(post("/api/mode").contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JsonSafe.parse(response.getContentAsString()).node();
    }

    @Test
    void aSwitchIsAnsweredWithWhatIsInForceAndTheModeItGoesToBeforeTheRestartBegins() throws Exception {
        MockHttpServletResponse answer = switchTo("mock", "{\"mode\":\"live\"}");
        assertThat(answer.getStatus()).isEqualTo(202);
        assertThat(answer.isCommitted()).as("the answer is sent before the restart").isTrue();
        JsonNode view = json(answer);
        assertThat(view.get("mode").stringValue()).isEqualTo("mock");
        assertThat(view.get("switchingTo").stringValue()).isEqualTo("live");
        assertThat(view.get("held").get("productionUnlock").booleanValue()).isFalse();
        assertThat(view.get("held").get("pastedToken").booleanValue()).isFalse();
        assertThat(view.get("startedAt").stringValue()).isNotBlank();
        assertThat(restarts.restarted).containsExactly("mock to live");
        // Written any way round, the mode is read the same.
        restarts.claimed = null;
        assertThat(switchTo("live", "{\"mode\":\" MOCK \"}").getStatus()).isEqualTo(202);
        assertThat(restarts.restarted).containsExactly("mock to live", "live to mock");
    }

    @Test
    void aModeThatIsNeitherIsRefusedByNameAndNothingRestarts() throws Exception {
        for (String body : List.of("{\"mode\":\"sandbox\"}", "{\"mode\":\"\"}", "{}")) {
            MockHttpServletResponse answer = switchTo("mock", body);
            assertThat(answer.getStatus()).as(body).isEqualTo(422);
            JsonNode error = json(answer);
            assertThat(error.get("title").stringValue()).isEqualTo("PAVE did not switch.");
            assertThat(error.get("fieldErrors").get(0).get("field").stringValue()).isEqualTo("mode");
            assertThat(error.get("fieldErrors").get(0).get("message").stringValue()).isEqualTo("Choose mock or live.");
        }
        // A property the request does not have is refused by name, as every request body is read.
        assertThat(switchTo("mock", "{\"profile\":\"live\"}").getStatus()).isEqualTo(422);
        assertThat(restarts.restarted).isEmpty();
        assertThat(restarts.claimed).isNull();
    }

    @Test
    void eachThingInTheWayIsRefusedWithWhatToDoAndNothingRestarts() throws Exception {
        assertRefused(switchTo("live", "{\"mode\":\"live\"}"), "PAVE is already on live.", "Choose the other mode to switch.");

        restarts.filesChanged = true;
        assertRefused(switchTo("mock", "{\"mode\":\"live\"}"), "PAVE's files changed on disk since it started, so it cannot switch in "
                + "place: a switch loads no class anew.", "Stop PAVE and start it again.");
        restarts.filesChanged = false;

        suiteRunning = true;
        assertRefused(switchTo("mock", "{\"mode\":\"live\"}"), "A suite is still running, so PAVE did not switch.",
                "Wait for the suite to finish, or stop it in Audit > Suites, then switch.");
        suiteRunning = false;

        replayRunning = true;
        assertRefused(switchTo("mock", "{\"mode\":\"live\"}"), "Runs are still being replayed, so PAVE did not switch.",
                "Wait for the replay to finish, then switch.");
        replayRunning = false;
        assertThat(restarts.claimed).as("a refused switch gives its claim back").isNull();

        restarts.claimed = "live";
        assertRefused(switchTo("mock", "{\"mode\":\"live\"}"), "PAVE is already switching.", "Wait a few seconds until it answers again.");
        restarts.claimed = null;

        restarts.fromMain = false;
        assertRefused(switchTo("mock", "{\"mode\":\"live\"}"), "This copy of PAVE was not started by its own main class, so it cannot "
                + "switch itself.", "Stop it and start it on the other profile.");
        assertThat(restarts.restarted).isEmpty();
    }

    private static void assertRefused(MockHttpServletResponse answer, String title, String action) throws Exception {
        assertThat(answer.getStatus()).as(title).isEqualTo(409);
        JsonNode error = json(answer);
        assertThat(error.get("title").stringValue()).isEqualTo(title);
        assertThat(error.get("action").stringValue()).isEqualTo(action);
    }

    @Test
    void withoutTheSwitchWiredAPostIsRefused() throws Exception {
        MockHttpServletResponse answer = TestMvc.standalone(new ModeController(EpaProperties.defaults(), gateway, environment))
                .perform(post("/api/mode").contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"live\"}")).andReturn().getResponse();
        assertThat(answer.getStatus()).isEqualTo(409);
    }

    @Test
    void theAnswerSaysWhatThisSessionHoldsThatASwitchDrops() throws Exception {
        JsonNode view = mode("mock");
        assertThat(view.get("held").get("productionUnlock").booleanValue()).isFalse();
        assertThat(view.get("held").get("pastedToken").booleanValue()).isFalse();
        assertThat(view.has("switchingTo")).isFalse();
    }
}
