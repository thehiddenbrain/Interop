package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.config.TrailProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.ledger.RunTrail;
import org.p32h.interop.epa.pave.testsupport.HandClock;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** The trail's endpoints (9.2): what it holds, one run kept or not, the download, emptying it and an id that is not a run id. */
class TrailControllerTest {

    private static final String KEPT = "run-00000000-0000-0000-0000-000000000001";
    private static final String GONE = "run-00000000-0000-0000-0000-000000000002";

    @TempDir
    Path directory;

    private final HandClock clock = new HandClock(Instant.parse("2026-11-22T14:06:08.123Z"));

    private RunTrail trail(boolean enabled) {
        return new RunTrail(TrailProperties.of(Map.of("enabled", String.valueOf(enabled))),
                TrackingProperties.of(Map.of("directory", directory.toString())), clock);
    }

    private static ObjectNode entry(String runId) {
        ObjectNode entry = JsonSafe.mapper().createObjectNode();
        entry.put("runId", runId).put("protocol", "CRD").put("operation", "CRD_ORDER_SIGN");
        entry.putObject("case").putObject("member").put("memberId", "S123456789");
        entry.putObject("envelope").put("runId", runId);
        return entry;
    }

    private static JsonNode json(MockHttpServletResponse response) throws Exception {
        return JsonSafe.parse(response.getContentAsString(StandardCharsets.UTF_8)).node();
    }

    @Test
    void theSummaryCountsWhatIsKeptAndNamesTheFolder() throws Exception {
        RunTrail trail = trail(true);
        MockMvc mvc = TestMvc.standalone(new TrailController(trail));
        JsonNode empty = json(mvc.perform(get("/api/trail")).andReturn().getResponse());
        assertThat(empty.get("enabled").asBoolean()).isTrue();
        assertThat(empty.get("count").asInt()).isZero();
        assertThat(empty.get("maxEntries").asInt()).isEqualTo(500);
        assertThat(empty.get("bytes").asLong()).isZero();
        assertThat(empty.get("folder").asString()).isEqualTo(directory.resolve("trail").toAbsolutePath().normalize().toString());
        assertThat(empty.has("damaged")).isFalse();

        trail.keep(KEPT, entry(KEPT));
        JsonNode one = json(mvc.perform(get("/api/trail")).andReturn().getResponse());
        assertThat(one.get("count").asInt()).isEqualTo(1);
        assertThat(one.get("bytes").asLong()).isPositive();
        assertThat(JsonSafe.properties(one).stream().map(Map.Entry::getKey).toList())
                .containsExactly("enabled", "count", "maxEntries", "bytes", "folder");
    }

    @Test
    void aKeptRunIsAnsweredWithItsEntryAndOneNotKeptWithTheLineBoth200() throws Exception {
        RunTrail trail = trail(true);
        trail.keep(KEPT, entry(KEPT));
        MockMvc mvc = TestMvc.standalone(new TrailController(trail));

        MockHttpServletResponse kept = mvc.perform(get("/api/trail/" + KEPT)).andReturn().getResponse();
        assertThat(kept.getStatus()).isEqualTo(200);
        JsonNode body = json(kept);
        assertThat(body.get("runId").asString()).isEqualTo(KEPT);
        assertThat(body.get("kept").asBoolean()).isTrue();
        assertThat(body.get("keptAt").asString()).isEqualTo("2026-11-22T14:06:08.123Z");
        assertThat(body.get("entry")).isEqualTo(entry(KEPT));
        assertThat(body.has("line")).isFalse();

        MockHttpServletResponse gone = mvc.perform(get("/api/trail/" + GONE)).andReturn().getResponse();
        assertThat(gone.getStatus()).isEqualTo(200);
        JsonNode notKept = json(gone);
        assertThat(notKept.get("kept").asBoolean()).isFalse();
        assertThat(notKept.get("line").asString()).isEqualTo("Not kept: the trail holds the last 500 runs.");
        assertThat(notKept.has("entry")).isFalse();
        assertThat(notKept.has("keptAt")).isFalse();

        JsonNode off = json(TestMvc.standalone(new TrailController(trail(false))).perform(get("/api/trail/" + KEPT)).andReturn().getResponse());
        assertThat(off.get("kept").asBoolean()).isFalse();
        assertThat(off.get("line").asString()).isEqualTo("The trail is switched off on this installation (trail.enabled).");
    }

    @Test
    void theDownloadIsTheEntryAsAFileNamedForTheRunAndNotKeptIs404() throws Exception {
        RunTrail trail = trail(true);
        trail.keep(KEPT, entry(KEPT));
        MockMvc mvc = TestMvc.standalone(new TrailController(trail));

        MockHttpServletResponse file = mvc.perform(get("/api/trail/" + KEPT + "/download")).andReturn().getResponse();
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentType()).startsWith("application/json");
        assertThat(file.getHeader("Content-Disposition")).isEqualTo("attachment; filename=\"run-" + KEPT + ".json\"");
        assertThat(file.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(json(file)).isEqualTo(entry(KEPT));

        MockHttpServletResponse missing = mvc.perform(get("/api/trail/" + GONE + "/download")).andReturn().getResponse();
        assertThat(missing.getStatus()).isEqualTo(404);
        assertThat(json(missing).get("title").asString()).isEqualTo("Not kept: the trail holds the last 500 runs.");
    }

    @Test
    void emptyingRemovesEveryRunAndSaysHowMany() throws Exception {
        RunTrail trail = trail(true);
        trail.keep(KEPT, entry(KEPT));
        clock.advance(java.time.Duration.ofMillis(1));
        trail.keep(GONE, entry(GONE));
        MockMvc mvc = TestMvc.standalone(new TrailController(trail));
        JsonNode removed = json(mvc.perform(delete("/api/trail")).andReturn().getResponse());
        assertThat(removed.get("removed").asInt()).isEqualTo(2);
        assertThat(json(mvc.perform(get("/api/trail")).andReturn().getResponse()).get("count").asInt()).isZero();
        assertThat(json(mvc.perform(get("/api/trail/" + KEPT)).andReturn().getResponse()).get("kept").asBoolean()).isFalse();
        assertThat(json(mvc.perform(delete("/api/trail")).andReturn().getResponse()).get("removed").asInt()).isZero();
    }

    @Test
    void anIdThatIsNotARunIdIsRefusedWithoutBeingRepeated() throws Exception {
        MockMvc mvc = TestMvc.standalone(new TrailController(trail(true)));
        for (String id : new String[] {"run-0000", "chain-00000000-0000-0000-0000-000000000001", "S123456789", "run-..-..-store.json"}) {
            MockHttpServletResponse refused = mvc.perform(get("/api/trail/" + id)).andReturn().getResponse();
            assertThat(refused.getStatus()).as(id).isEqualTo(422);
            JsonNode body = json(refused);
            assertThat(body.get("fieldErrors").get(0).get("field").asString()).isEqualTo("runId");
            assertThat(body.get("fieldErrors").get(0).get("message").asString())
                    .isEqualTo("This is not a run id. Copy the run id from Audit, or from the result that gave it.");
            assertThat(refused.getContentAsString()).doesNotContain(id);
            assertThat(mvc.perform(get("/api/trail/" + id + "/download")).andReturn().getResponse().getStatus()).isEqualTo(422);
        }
    }
}
