package com.thehiddenbrain.interop.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Boots the whole application against a throw-away copy of the data folder and walks the product's
 * scenarios through the REST API: catalog, layout building, sampling with masking, approval, go-live,
 * scheduled delivery, quality holds, compliance, spec import and the feed API.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExtractBuilderIntegrationTest {

    @TempDir
    static Path tempData;

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    static String definitionId;
    static String sampleRunId;

    @DynamicPropertySource
    static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("extract.data-dir", () -> tempData.toString());
        registry.add("extract.axway-simulator", () -> "false");
    }

    @BeforeAll
    static void copyDataFolder() throws IOException {
        Path source = Path.of("data");
        for (String name : new String[]{"catalog.json", "calendars.json"}) Files.copy(source.resolve(name), tempData.resolve(name), StandardCopyOption.REPLACE_EXISTING);
        for (String dir : new String[]{"source", "seed"}) copyTree(source.resolve(dir), tempData.resolve(dir));
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /* ---------- helpers ---------- */

    private JsonNode call(MockHttpServletRequestBuilder req, String user, int expectedStatus) throws Exception {
        if (user != null) req.header("X-Demo-User", user);
        MvcResult r = mvc.perform(req).andReturn();
        String body = r.getResponse().getContentAsString();
        assertEquals(expectedStatus, r.getResponse().getStatus(), () -> "unexpected status, body: " + body);
        return body.isEmpty() ? mapper.nullNode() : mapper.readTree(body);
    }

    private JsonNode getJson(String path, String user) throws Exception { return call(get(path), user, 200); }

    private JsonNode postJson(String path, String user, String body, int status) throws Exception {
        MockHttpServletRequestBuilder req = post(path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        return call(req, user, status);
    }

    private JsonNode putJson(String path, String user, String body, int status) throws Exception {
        return call(put(path).contentType(MediaType.APPLICATION_JSON).content(body), user, status);
    }

    private static String versionPath(String defId, int no) { return "/api/v1/definitions/" + defId + "/versions/" + no; }

    /* ---------- tests ---------- */

    @Test
    @Order(1)
    @DisplayName("Catalog and rule registry load from the JSON files")
    void catalogLoads() throws Exception {
        JsonNode catalog = getJson("/api/v1/catalog", "analyst");
        assertTrue(catalog.get("elements").size() > 80, "catalog elements");
        assertTrue(catalog.get("joinPaths").size() >= 3, "join paths");
        JsonNode rules = getJson("/api/v1/catalog/rules", "analyst");
        assertEquals(14, rules.size(), "closed rule set");
        assertTrue(getJson("/api/v1/health", null).get("status").asText().equalsIgnoreCase("UP"));
    }

    @Test
    @Order(2)
    @DisplayName("The demo seed produces live feeds, a pending approval and a held file")
    void seedState() throws Exception {
        JsonNode defs = getJson("/api/v1/definitions", "analyst");
        JsonNode acme = find(defs, "id", "d-acme-elig");
        assertNotNull(acme);
        assertEquals("PRODUCTION", statusOf(acme, 2));
        assertEquals("RETIRED", statusOf(acme, 1));
        assertEquals("PENDING_APPROVAL", statusOf(find(defs, "id", "d-bluebird-roster"), 2));
        JsonNode bluebirdRuns = getJson("/api/v1/definitions/d-bluebird-roster/runs", "analyst");
        assertTrue(stream(bluebirdRuns).anyMatch(r -> "HELD".equals(r.get("status").asText())), "Bluebird run is held by the quality gate");
        JsonNode dashboard = getJson("/api/v1/dashboard", "analyst");
        assertTrue(dashboard.get("live").asInt() >= 3, "live feeds on the dashboard");
    }

    @Test
    @Order(3)
    @DisplayName("Analyst builds a layout, samples it masked, and sends it for approval")
    void buildAndSample() throws Exception {
        JsonNode created = postJson("/api/v1/definitions", "analyst",
                "{\"name\":\"Test Vision eligibility\",\"vendorCode\":\"NWVISION\",\"subjectArea\":\"MEMBER\",\"grain\":\"member\",\"description\":\"integration test\"}", 200);
        definitionId = created.get("id").asText();
        assertEquals("DRAFT", statusOf(created, 1));

        String spec = """
                {"fields":[
                  {"id":"f1","position":1,"header":"MEMBER_ID","inputs":[{"element":"member.member_id"}],"rules":[]},
                  {"id":"f2","position":2,"header":"MEMBER_NAME","inputs":[{"element":"member.last_name"},{"element":"member.first_name"}],
                   "combiner":{"type":"CONCAT","params":{"template":"{1}, {2}"}},"rules":[{"type":"CLEAN_TEXT","params":{"textCase":"UPPER"}}],"maxLength":30},
                  {"id":"f3","position":3,"header":"DOB","inputs":[{"element":"member.birth_date"}],"rules":[{"type":"FORMAT_DATE","params":{"pattern":"MM/dd/yyyy"}}]},
                  {"id":"f4","position":4,"header":"GENDER","inputs":[{"element":"member.gender_code"}],"rules":[{"type":"MAP_VALUES","params":{"map":{"M":"1","F":"2"},"defaultValue":"0"}}]},
                  {"id":"f5","position":5,"header":"PLAN","inputs":[{"element":"coverage.plan_code"}],"rules":[]},
                  {"id":"f6","position":6,"header":"RECORD_TYPE","inputs":[],"rules":[{"type":"CONSTANT","params":{"token":"LITERAL","value":"D"}}]}
                ],
                "joins":[{"path":"member->coverage","select":"CURRENT_AS_OF_RUN_DATE"}],
                "filters":[{"template":"coverage.active_as_of","params":{}}],
                "sort":[{"element":"member.member_id","direction":"ASC"}],
                "scope":{"mode":"FULL"},
                "fileFormat":{"type":"DELIMITED","delimiter":"|","headerRow":true,"lineEnding":"CRLF","encoding":"UTF-8","extension":"txt"},
                "sample":{"maxRows":50}}
                """;
        JsonNode saved = putJson(versionPath(definitionId, 1) + "/spec", "analyst", spec, 200);
        assertTrue(saved.get("validation").get("ok").asBoolean(), () -> "validation: " + saved.get("validation"));
        assertEquals(1, saved.get("validation").get("joins").size(), "the coverage join is compiled in");
        assertTrue(saved.get("validation").get("sqlText").asText().contains("LEFT JOIN"), "SQL text shows the join");

        JsonNode preview = postJson(versionPath(definitionId, 1) + "/preview?rows=5", "analyst", null, 200);
        assertEquals(5, preview.get("previewRows").size());
        assertTrue(preview.get("maskedFields").toString().contains("MEMBER_ID"), "PHI is masked in previews");
        JsonNode firstRow = preview.get("previewRows").get(0);
        assertEquals("D", firstRow.get("RECORD_TYPE").asText());
        assertTrue(firstRow.get("DOB").asText().matches("\\d{2}/\\d{2}/\\d{4}"), "date formatted as MM/dd/yyyy: " + firstRow.get("DOB"));
        assertTrue(firstRow.get("GENDER").asText().matches("[012]"), "gender mapped");
        assertEquals(firstRow.get("MEMBER_NAME").asText().toUpperCase(), firstRow.get("MEMBER_NAME").asText(), "name upper-cased");

        JsonNode sample = postJson(versionPath(definitionId, 1) + "/sample", "analyst", "{\"maxRows\":50}", 200);
        sampleRunId = sample.get("id").asText();
        assertEquals("WRITTEN", sample.get("status").asText());
        assertEquals(50, sample.get("rowCount").asInt());
        assertTrue(sample.get("masked").asBoolean());
        assertTrue(Files.exists(Path.of(sample.get("filePath").asText())), "sample file on disk");
        assertEquals("SAMPLED", getJson(versionPath(definitionId, 1), "analyst").get("status").asText());

        String content = mvc.perform(get("/api/v1/runs/" + sampleRunId + "/content").header("X-Demo-User", "analyst")).andReturn().getResponse().getContentAsString();
        assertTrue(content.startsWith("MEMBER_ID|MEMBER_NAME|DOB|GENDER|PLAN|RECORD_TYPE"), "header row written: " + content.substring(0, Math.min(80, content.length())));

        JsonNode sent = postJson("/api/v1/runs/" + sampleRunId + "/send-test-route", "analyst", null, 200);
        assertNotNull(sent.get("deliveryPath"));
        assertTrue(Files.exists(Path.of(sent.get("deliveryPath").asText())), "sample landed in the vendor's test folder");

        JsonNode pending = postJson(versionPath(definitionId, 1) + "/request-approval", "analyst", "{\"note\":\"vendor accepted the sample\",\"vendorAcceptance\":\"email 2026-09-19\"}", 200);
        assertEquals("PENDING_APPROVAL", pending.get("status").asText());
    }

    @Test
    @Order(4)
    @DisplayName("Approval needs an approver; the requester cannot approve their own layout")
    void approval() throws Exception {
        postJson(versionPath(definitionId, 1) + "/approve", "analyst", "{\"note\":\"looks good\"}", 403);
        JsonNode approved = postJson(versionPath(definitionId, 1) + "/approve", "approver", "{\"note\":\"looks good\"}", 200);
        assertEquals("APPROVED", approved.get("status").asText());
        assertEquals("Dev Patel", approved.get("approvedBy").asText());
        putJson(versionPath(definitionId, 1) + "/spec", "analyst", "{\"fields\":[]}", 409);
    }

    @Test
    @Order(5)
    @DisplayName("Dry run passes, go-live schedules the feed, and run-now delivers to the drop folder")
    void goLiveAndRun() throws Exception {
        String config = "{\"schedule\":{\"preset\":\"WEEKDAYS\",\"time\":\"02:00\",\"timezone\":\"America/Chicago\",\"cron\":\"0 0 2 * * MON-FRI\",\"calendar\":\"plan-2026\"},\"delivery\":{\"route\":\"PROD\"},\"quality\":{\"enabled\":true}}";
        JsonNode dry = postJson(versionPath(definitionId, 1) + "/dry-run", "approver", config, 200);
        assertTrue(dry.get("ok").asBoolean(), () -> "dry run: " + dry);
        assertTrue(dry.get("checks").size() >= 6);
        assertTrue(dry.get("nextRuns").size() > 0);

        JsonNode live = postJson(versionPath(definitionId, 1) + "/productionalize", "approver", config, 200);
        assertEquals("PRODUCTION", live.get("status").asText());
        JsonNode next = getJson("/api/v1/definitions/" + definitionId + "/next-runs", "analyst");
        assertTrue(next.size() >= 5, "scheduler knows the next runs");

        JsonNode run = postJson("/api/v1/definitions/" + definitionId + "/run-now", "approver", null, 200);
        assertEquals("DELIVERED", run.get("status").asText(), () -> "run: " + run);
        assertTrue(run.get("rowCount").asInt() > 100);
        assertFalse(run.get("masked").asBoolean(), "production files carry real values");
        Path delivered = Path.of(run.get("deliveryPath").asText());
        assertTrue(Files.exists(delivered), "file in the drop folder");
        assertTrue(delivered.toString().contains("NWVISION"), "file name carries the vendor token");
        assertTrue(Files.exists(delivered.resolveSibling(delivered.getFileName() + ".done")), "control file next to the data file");
        String firstLine = Files.readAllLines(delivered).get(0);
        assertEquals("MEMBER_ID|MEMBER_NAME|DOB|GENDER|PLAN|RECORD_TYPE", firstLine);

        JsonNode runs = getJson("/api/v1/definitions/" + definitionId + "/runs", "analyst");
        assertTrue(stream(runs).anyMatch(r -> "PRODUCTION".equals(r.get("mode").asText()) && "DELIVERED".equals(r.get("status").asText())));
        JsonNode audit = getJson("/api/v1/audit?limit=200", "admin");
        assertTrue(stream(audit).anyMatch(a -> "PRODUCTIONALIZED".equals(a.get("action").asText())), "go-live is audited");
    }

    @Test
    @Order(6)
    @DisplayName("Frozen versions reject edits; a new version starts from the live layout and the diff explains the change")
    void versioningAndDiff() throws Exception {
        putJson(versionPath("d-acme-elig", 2) + "/spec", "analyst", "{\"fields\":[]}", 409);
        JsonNode diff = getJson(versionPath("d-acme-elig", 3) + "/diff", "analyst");
        assertTrue(stream(diff.get("fields")).anyMatch(f -> "changed".equals(f.get("change").asText()) && "MEMBER_NAME".equals(f.get("header").asText())), "v3 changes MEMBER_NAME over the live v2: " + diff);
        assertTrue(diff.get("summary").asText().contains("1 changed"), diff.get("summary").asText());
        assertEquals(2, diff.get("baseVersion").asInt());
        JsonNode v2 = postJson("/api/v1/definitions/" + definitionId + "/versions", "analyst", "{\"changeNote\":\"add middle name\"}", 200);
        assertEquals(2, v2.get("versionNo").asInt());
        assertEquals("DRAFT", v2.get("status").asText());
        assertEquals(6, v2.get("spec").get("fields").size(), "new version copies the live layout");
        postJson("/api/v1/definitions/" + definitionId + "/versions", "analyst", "{\"changeNote\":\"second open draft\"}", 409);
        call(delete(versionPath(definitionId, 2)), "analyst", 200);
    }

    @Test
    @Order(7)
    @DisplayName("The compiler explains problems in a broken layout")
    void validationProblems() throws Exception {
        JsonNode v = postJson(versionPath("d-cascade-provider", 1) + "/validate", "analyst", null, 200);
        assertFalse(v.get("ok").asBoolean());
        assertTrue(stream(v.get("problems")).anyMatch(p -> "ERROR".equals(p.get("severity").asText())));
        postJson(versionPath("d-cascade-provider", 1) + "/sample", "analyst", "{}", 400);
    }

    @Test
    @Order(8)
    @DisplayName("A held file can only be released by an approver, then it is delivered")
    void releaseHeld() throws Exception {
        JsonNode runs = getJson("/api/v1/definitions/d-bluebird-roster/runs", "analyst");
        JsonNode held = stream(runs).filter(r -> "HELD".equals(r.get("status").asText())).findFirst().orElseThrow();
        assertTrue(held.get("qualityFindings").size() > 0, "held run explains why");
        postJson("/api/v1/runs/" + held.get("id").asText() + "/release", "analyst", null, 403);
        JsonNode released = postJson("/api/v1/runs/" + held.get("id").asText() + "/release", "approver", null, 200);
        assertEquals("DELIVERED", released.get("status").asText());
        assertEquals("Dev Patel", released.get("releasedBy").asText());
    }

    @Test
    @Order(9)
    @DisplayName("Compliance reports flag PHI feeds without a BAA and react to partner changes")
    void complianceAndPartners() throws Exception {
        JsonNode harbor = getJson("/api/v1/compliance/vendors/HARBORBH", "approver");
        assertFalse(harbor.get("baaOnFile").asBoolean());
        assertEquals("HIGH", harbor.get("risk").asText());
        ObjectNode partner = (ObjectNode) getJson("/api/v1/partners/HARBORBH", "admin");
        partner.put("baaOnFile", true);
        partner.put("baaSignedDate", "2026-09-19");
        putJson("/api/v1/partners/HARBORBH", "analyst", partner.toString(), 403);
        putJson("/api/v1/partners/HARBORBH", "admin", partner.toString(), 200);
        JsonNode after = getJson("/api/v1/compliance/vendors/HARBORBH", "approver");
        assertTrue(after.get("baaOnFile").asBoolean());
        assertNotEquals("HIGH", after.get("risk").asText());
        String csv = mvc.perform(get("/api/v1/compliance/export.csv").header("X-Demo-User", "approver")).andReturn().getResponse().getContentAsString();
        assertTrue(csv.contains("HARBORBH"));
        JsonNode impact = getJson("/api/v1/catalog/impact/member.member_id", "analyst");
        assertTrue(impact.get("liveFeeds").asInt() >= 3, "member id is used by every live feed");
    }

    @Test
    @Order(10)
    @DisplayName("A vendor spec is turned into a proposed layout")
    void specImport() throws Exception {
        String body = mapper.writeValueAsString(mapper.createObjectNode()
                .put("text", "Member ID\nLast Name\nFirst Name\nDate of Birth (MMDDYYYY)\nGender\nPlan Code\nZIP (5 digits)")
                .put("vendorCode", "ACMEDENTAL").put("subjectArea", "MEMBER"));
        JsonNode proposal = postJson("/api/v1/spec-import/propose", "analyst", body, 200);
        assertEquals(7, proposal.get("lines").asInt());
        assertTrue(proposal.get("matched").asInt() >= 6, () -> "matched: " + proposal.get("summary"));
        JsonNode dob = stream(proposal.get("fields")).filter(f -> f.get("header").asText().contains("BIRTH") || f.get("header").asText().contains("DATE")).findFirst().orElseThrow();
        assertTrue(dob.get("rules").toString().contains("MMddyyyy"), "date pattern hint honoured: " + dob);
    }

    @Test
    @Order(11)
    @DisplayName("The feed API serves the same definition as JSON to a keyed caller only")
    void feedApi() throws Exception {
        call(get("/api/v1/feeds/ACMEDENTAL/acmedental-eligibility?size=5"), null, 403);
        call(get("/api/v1/feeds/ACMEDENTAL/acmedental-eligibility?size=5").header("X-Api-Key", "wrong"), null, 403);
        JsonNode feed = call(get("/api/v1/feeds/ACMEDENTAL/acmedental-eligibility?size=5").header("X-Api-Key", "veb_acme_demo_key_2026"), null, 200);
        assertEquals(5, feed.get("rows").size());
        assertTrue(feed.get("rows").get(0).has("MEMBER_ID") || feed.get("rows").get(0).size() > 3, "rows carry the vendor's headers: " + feed.get("rows").get(0));
    }

    @Test
    @Order(12)
    @DisplayName("Schedules preview in the partner's timezone and settings are admin-only")
    void schedulesAndSettings() throws Exception {
        JsonNode next = postJson("/api/v1/definitions/schedule-preview?count=6", "analyst",
                "{\"preset\":\"WEEKLY\",\"dayOfWeek\":\"MON\",\"time\":\"05:30\",\"timezone\":\"America/New_York\",\"calendar\":\"plan-2026\",\"holidayPolicy\":\"NEXT_BUSINESS_DAY\"}", 200);
        assertEquals(6, next.size());
        JsonNode settings = getJson("/api/v1/settings", "admin");
        putJson("/api/v1/settings", "analyst", settings.toString(), 403);
        putJson("/api/v1/settings", "admin", settings.toString(), 200);
    }

    /* ---------- json helpers ---------- */

    private static JsonNode find(JsonNode array, String key, String value) {
        return stream(array).filter(n -> value.equals(n.path(key).asText())).findFirst().orElse(null);
    }

    private static String statusOf(JsonNode definition, int versionNo) {
        return stream(definition.get("versions")).filter(v -> v.get("versionNo").asInt() == versionNo).findFirst().map(v -> v.get("status").asText()).orElse(null);
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false);
    }
}
