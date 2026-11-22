package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.PaveStart;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.web.RawHttp.Answer;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * The switch between mock and live, made on the real application started the way {@code main}
 * starts it: on a port and in folders of its own, switched over HTTP, and looked at over HTTP
 * after each switch. Whatever happens, the context running at the end is closed, so no context
 * and no bound port is left in the test JVM.
 */
class ModeSwitchInPlaceTest {

    private static final Duration COME_BACK = Duration.ofSeconds(90);

    @TempDir
    Path home;

    private int port;

    @AfterEach
    void closeWhatRuns() throws InterruptedException {
        try {
            // A switch still under way ends first, so that what it started is the context closed here.
            Instant until = Instant.now().plus(COME_BACK);
            while (PaveStart.switchingTo() != null && Instant.now().isBefore(until)) {
                Thread.sleep(100);
            }
            ConfigurableApplicationContext running = PaveStart.current();
            if (running != null) {
                running.close();
            }
        } finally {
            PaveStart.reset();
        }
        if (port > 0) {
            assertThat(portFree(port)).as("the port is let go").isTrue();
        }
    }

    @Test
    void itSwitchesToLiveAndBackOnTheSamePortAndFoldersAndTakesTheStoreAgain() throws Exception {
        port = freePort();
        PaveStart.useForTests(null, () -> {
            throw new AssertionError("the JVM would have been ended");
        });
        PaveStart.first(new String[] {"--server.port=" + port, "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--config-store.directory=" + home.resolve("store"), "--tracking.directory=" + home.resolve("history")});

        JsonNode mock = mode();
        assertThat(mock.get("mode").stringValue()).isEqualTo("mock");
        assertThat(mock.has("switchingTo")).isFalse();
        assertThat(mock.get("held").get("productionUnlock").booleanValue()).isFalse();
        assertThat(mock.get("held").get("pastedToken").booleanValue()).isFalse();
        assertThat(crdScenarios().get("available").booleanValue()).isTrue();

        // Refused, and nothing restarted: the mode already in force, and a mode that is neither.
        Answer same = post("/api/mode", "{\"mode\":\"mock\"}");
        assertThat(same.status()).isEqualTo(409);
        assertThat(same.title()).isEqualTo("PAVE is already on mock.");
        Answer neither = post("/api/mode", "{\"mode\":\"sandbox\"}");
        assertThat(neither.status()).isEqualTo(422);
        assertThat(neither.json().get("fieldErrors").get(0).get("message").stringValue()).isEqualTo("Choose mock or live.");
        assertThat(mode().get("startedAt").stringValue()).isEqualTo(mock.get("startedAt").stringValue());

        // Refused while a suite runs.
        StringBuilder suite = new StringBuilder("caseId,protocol,memberId,npi,procedureCodes,dateOfService,expected\n");
        for (int i = 1; i <= 120; i++) {
            suite.append("long-").append(i).append(",crd,P32H-PA-REQ,1234567893,95810,2026-03-02,AUTH_REQUIRED\n");
        }
        Answer started = post("/api/suites", "{\"fileName\":\"long.csv\",\"text\":" + JsonSafe.mapper().writeValueAsString(suite.toString()) + "}");
        assertThat(started.status()).isEqualTo(202);
        String suiteRunId = started.json().get("suiteRunId").stringValue();
        Answer whileSuite = post("/api/mode", "{\"mode\":\"live\"}");
        assertThat(whileSuite.status()).isEqualTo(409);
        assertThat(whileSuite.title()).isEqualTo("A suite is still running, so PAVE did not switch.");
        assertThat(whileSuite.json().get("action").stringValue())
                .isEqualTo("Wait for the suite to finish, or stop it in Audit > Suites, then switch.");
        assertThat(post("/api/suites/" + suiteRunId + "/stop", "").status()).isEqualTo(200);
        Instant until = Instant.now().plus(COME_BACK);
        while (!"STOPPED".equals(get("/api/suites/" + suiteRunId).json().get("state").stringValue()) && Instant.now().isBefore(until)) {
            Thread.sleep(100);
        }

        // To live.
        Answer toLive = post("/api/mode", "{\"mode\":\"live\"}");
        assertThat(toLive.status()).isEqualTo(202);
        assertThat(toLive.json().get("mode").stringValue()).isEqualTo("mock");
        assertThat(toLive.json().get("switchingTo").stringValue()).isEqualTo("live");
        assertThat(toLive.json().get("held").get("productionUnlock").booleanValue()).isFalse();
        JsonNode live = cameBack(mock.get("startedAt").stringValue());
        assertThat(live.get("mode").stringValue()).isEqualTo("live");
        assertThat(live.get("port").intValue()).isEqualTo(port);
        assertThat(live.has("switchingTo")).isFalse();
        // What only mock offers says so on live.
        JsonNode scenarios = crdScenarios();
        assertThat(scenarios.get("available").booleanValue()).isFalse();
        assertThat(scenarios.get("scenarios").size()).isZero();
        // The configuration store's lock was taken again: a setting is saved, in the same folder.
        byte[] export;
        try (InputStream in = getClass().getResourceAsStream("/postman/onyx-sandbox.postman_environment.json")) {
            export = in.readAllBytes();
        }
        Answer saved = post("/api/config/environments", "{\"fileName\":\"onyx-sandbox.postman_environment.json\",\"kind\":\"sandbox\",\"text\":"
                + JsonSafe.mapper().writeValueAsString(new String(export, StandardCharsets.UTF_8)) + "}");
        assertThat(saved.status()).as(saved.body()).isEqualTo(200);
        assertThat(Files.exists(home.resolve("store").resolve(".lock"))).isTrue();
        assertThat(get("/api/config/environments").json().get("environments").size()).isEqualTo(1);

        // And back to mock, where the same store still holds what was saved on live.
        assertThat(post("/api/mode", "{\"mode\":\"mock\"}").status()).isEqualTo(202);
        JsonNode back = cameBack(live.get("startedAt").stringValue());
        assertThat(back.get("mode").stringValue()).isEqualTo("mock");
        assertThat(back.get("port").intValue()).isEqualTo(port);
        assertThat(crdScenarios().get("available").booleanValue()).isTrue();
        assertThat(get("/api/config/environments").json().get("environments").size()).isEqualTo(1);
        assertThat(Files.isDirectory(home.resolve("history"))).isTrue();
    }

    @Test
    void aStartThatFailsOnMockComesBackOnLive() throws Exception {
        port = freePort();
        PaveStart.useForTests(null, () -> {
            throw new AssertionError("the JVM would have been ended");
        });
        // A damaged scenario file: live never reads it, and mock refuses to start on it.
        Path scenarios = home.resolve("scenarios");
        Files.createDirectories(scenarios.resolve("crd"));
        Files.writeString(scenarios.resolve("crd").resolve("damaged.json"), "{\"id\": \"crd-damaged\", ");
        PaveStart.first(new String[] {"--server.port=" + port, "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--spring.profiles.active=live", "--app.scenario-dir=" + scenarios, "--config-store.directory=" + home.resolve("store"),
                "--tracking.directory=" + home.resolve("history")});
        JsonNode live = mode();
        assertThat(live.get("mode").stringValue()).isEqualTo("live");

        assertThat(post("/api/mode", "{\"mode\":\"mock\"}").status()).isEqualTo(202);
        JsonNode back = cameBack(live.get("startedAt").stringValue());
        assertThat(back.get("mode").stringValue()).isEqualTo("live");
        assertThat(back.get("port").intValue()).isEqualTo(port);
    }

    /** Waits until a start other than the one named answers, and gives what it answers. */
    private JsonNode cameBack(String startedBefore) throws Exception {
        Instant until = Instant.now().plus(COME_BACK);
        while (Instant.now().isBefore(until)) {
            try {
                Answer answer = get("/api/mode");
                if (answer.status() == 200 && !startedBefore.equals(answer.json().get("startedAt").stringValue())
                        && PaveStart.switchingTo() == null) {
                    return answer.json();
                }
            } catch (IOException | AssertionError notYet) {
                // Closed, or not yet listening: the switch is under way.
            }
            Thread.sleep(200);
        }
        throw new AssertionError("PAVE did not come back within " + COME_BACK);
    }

    private JsonNode mode() throws IOException {
        Answer answer = get("/api/mode");
        assertThat(answer.status()).isEqualTo(200);
        return answer.json();
    }

    private JsonNode crdScenarios() throws IOException {
        Answer answer = get("/api/crd/scenarios");
        assertThat(answer.status()).isEqualTo(200);
        return answer.json();
    }

    private Answer get(String address) throws IOException {
        return RawHttp.get(port, "GET " + address + " HTTP/1.1", "Host: localhost:" + port, "Accept: application/json");
    }

    private Answer post(String address, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return RawHttp.send(port, RawHttp.head("POST " + address + " HTTP/1.1", "Host: localhost:" + port,
                "Content-Type: application/json", "Accept: application/json", "Content-Length: " + body.length), body);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket(port, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort() == port;
        } catch (IOException taken) {
            return false;
        }
    }
}
