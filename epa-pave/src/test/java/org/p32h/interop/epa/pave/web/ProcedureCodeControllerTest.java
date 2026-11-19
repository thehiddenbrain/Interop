package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * The two code list endpoints as a browser sees them: the status and the shape of each answer.
 * The real service is behind the controller, loaded from a file written by the test.
 */
class ProcedureCodeControllerTest {

    private static final String LIST = "Code,Description,PA Required,Line of Business,Delegated Vendor,Effective Date,Internal Ref\n"
            + "95810,\"Sleep study, attended\",Yes,Medicare,eviCore,1/1/2026,r-1\n"
            + "95810,\"Sleep study, attended\",No,Commercial,,1/1/2026,r-2\n"
            + "95811,Sleep study with titration,See policy,Medicare,,,r-3\n"
            + "E2601,\"Seat cushion, under 22\"\" wide\",,,,,r-4\n"
            + "E0601,CPAP device,No,,,,r-5\n"
            + "E0470,Bi-level device,Yes,Medicare Advantage,,,r-6\n";

    @TempDir
    Path directory;

    private MockMvc mvc(String list, int limit, Map<String, String> settings) throws IOException {
        Path file = Files.writeString(directory.resolve("codes.csv"), list, StandardCharsets.UTF_8);
        Map<String, String> all = new HashMap<>(settings);
        all.put("file", file.toString());
        ProcedureCodeService service = new ProcedureCodeService(CodelistProperties.of(all));
        return TestMvc.standalone(new ProcedureCodeController(service, AppProperties.of(Map.of("autocomplete-limit", String.valueOf(limit)))));
    }

    private MockMvc mvc() throws IOException {
        return mvc(LIST, 25, Map.of());
    }

    private static JsonNode json(MockMvc mvc, MockHttpServletRequestBuilder request) throws Exception {
        return JsonSafe.parse(mvc.perform(request).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString()).node();
    }

    private static List<String> codes(JsonNode answer) {
        List<String> out = new ArrayList<>();
        answer.get("codes").forEach(code -> out.add(code.get("code").stringValue()));
        return out;
    }

    // ---- GET /api/procedure-codes ----

    @Test
    void theTypeAheadAnswersWithTheQueryTheLineTheLimitAndTheCodes() throws Exception {
        JsonNode answer = json(mvc(), get("/api/procedure-codes").param("q", " 958 ").param("lob", "Medicare"));

        assertThat(answer.propertyNames()).containsExactly("query", "lineOfBusiness", "narrowed", "limit", "matched", "codes");
        assertThat(answer.get("query").stringValue()).isEqualTo("958");
        assertThat(answer.get("lineOfBusiness").stringValue()).isEqualTo("Medicare");
        assertThat(answer.get("narrowed").booleanValue()).isTrue();
        assertThat(answer.get("limit").intValue()).isEqualTo(25);
        assertThat(answer.get("matched").intValue()).isEqualTo(2);
        // The Commercial row for the same code is not this member's row.
        assertThat(codes(answer)).containsExactly("95810", "95811");

        JsonNode first = answer.get("codes").get(0);
        assertThat(first.get("description").stringValue()).isEqualTo("Sleep study, attended");
        assertThat(first.get("authRequired").stringValue()).isEqualTo("REQUIRED");
        assertThat(first.get("authRequiredSource").stringValue()).isEqualTo("Yes");
        assertThat(first.get("lineOfBusiness").stringValue()).isEqualTo("Medicare");
        assertThat(first.get("delegatedVendor").stringValue()).isEqualTo("eviCore");
        assertThat(first.get("effectiveDate").stringValue()).isEqualTo("2026-01-01");
    }

    @Test
    void aCodeTheListSaysNothingAboutIsServedAsUnknownAndNeverAsNoOrAsNothing() throws Exception {
        JsonNode answer = json(mvc(), get("/api/procedure-codes").param("q", "E"));
        // The three codes that start with E, then the rows that only have an e in their description.
        assertThat(codes(answer)).containsExactly("E2601", "E0601", "E0470", "95810", "95810", "95811");

        // The list does not say. The picker shows no tag for this, so it must be able to tell it from "No PA".
        JsonNode silent = answer.get("codes").get(0);
        assertThat(silent.get("authRequired").stringValue()).isEqualTo("UNKNOWN");
        assertThat(silent.propertyNames()).containsExactlyInAnyOrder("code", "description", "authRequired");
        assertThat(silent.get("description").stringValue()).isEqualTo("Seat cushion, under 22\" wide");
        // And "No PA" is a statement of the list, with the cell it was read from.
        JsonNode no = answer.get("codes").get(1);
        assertThat(no.get("authRequired").stringValue()).isEqualTo("NOT_REQUIRED");
        assertThat(no.get("authRequiredSource").stringValue()).isEqualTo("No");
        assertThat(no.has("delegatedVendor")).isFalse();

        // Text the flag reader does not know is unknown too, and the cell is there to show why.
        JsonNode unread = json(mvc(), get("/api/procedure-codes").param("q", "95811")).get("codes").get(0);
        assertThat(unread.get("authRequired").stringValue()).isEqualTo("UNKNOWN");
        assertThat(unread.get("authRequiredSource").stringValue()).isEqualTo("See policy");
    }

    @Test
    void aBlankQueryOffersTheFirstRowsUpToTheLimit() throws Exception {
        MockMvc mvc = mvc(LIST, 4, Map.of());
        for (MockHttpServletRequestBuilder request : List.of(get("/api/procedure-codes"), get("/api/procedure-codes").param("q", ""),
                get("/api/procedure-codes").param("q", "   ").param("lob", " "))) {
            JsonNode answer = json(mvc, request);
            assertThat(codes(answer)).containsExactly("95810", "95810", "95811", "E2601");
            assertThat(answer.get("limit").intValue()).isEqualTo(4);
            // Six rows in all, so the picker can say that it shows four of six.
            assertThat(answer.get("matched").intValue()).isEqualTo(6);
            assertThat(answer.get("narrowed").booleanValue()).isFalse();
            // Nothing was asked for, so nothing is echoed and nothing needs explaining.
            assertThat(answer.propertyNames()).containsExactly("narrowed", "limit", "matched", "codes");
        }
    }

    @Test
    void theLimitIsTheConfiguredOneAndCodeMatchesSurviveItBeforeDescriptionMatches() throws Exception {
        JsonNode answer = json(mvc(LIST, 2, Map.of()), get("/api/procedure-codes").param("q", "e0"));
        assertThat(answer.get("limit").intValue()).isEqualTo(2);
        assertThat(codes(answer)).containsExactly("E0601", "E0470");
        JsonNode sleep = json(mvc(LIST, 2, Map.of()), get("/api/procedure-codes").param("q", "sleep"));
        assertThat(codes(sleep)).containsExactly("95810", "95810");
        assertThat(sleep.get("matched").intValue()).isEqualTo(3);
    }

    @Test
    void aLineTheListDoesNotNameIsNotNarrowedAndTheAnswerSaysWhy() throws Exception {
        JsonNode answer = json(mvc(), get("/api/procedure-codes").param("q", "958").param("lob", "Dual Eligible"));
        assertThat(answer.get("narrowed").booleanValue()).isFalse();
        assertThat(answer.get("lineOfBusiness").stringValue()).isEqualTo("Dual Eligible");
        assertThat(codes(answer)).containsExactly("95810", "95810", "95811");
        assertThat(answer.get("notice").stringValue()).isEqualTo("The code list has no row for the line of business \"Dual Eligible\", so the "
                + "codes are not narrowed to it. The list names: Medicare, Commercial and Medicare Advantage. If one of those is the same "
                + "line under another name, add it to codelist.line-of-business-aliases.");
        assertThat(answer.propertyNames()).containsExactly("query", "lineOfBusiness", "narrowed", "notice", "limit", "matched", "codes");
    }

    @Test
    void rowsHeldBackForAnotherLineAreSaidSoAnEmptyAnswerIsNotADeadEnd() throws Exception {
        JsonNode answer = json(mvc(), get("/api/procedure-codes").param("q", "E0470").param("lob", "Commercial"));
        assertThat(answer.get("narrowed").booleanValue()).isTrue();
        assertThat(answer.get("codes").isEmpty()).isTrue();
        assertThat(answer.get("matched").intValue()).isZero();
        assertThat(answer.has("notice")).isFalse();
        assertThat(answer.get("otherLines").stringValue()).isEqualTo("The list has 1 row for \"E0470\" under another line of business "
                + "(Medicare Advantage). It is not offered for \"Commercial\".");
        assertThat(answer.propertyNames()).containsExactly("query", "lineOfBusiness", "narrowed", "otherLines", "limit", "matched", "codes");
    }

    @Test
    void anAliasConfiguredForTheMembersLineNarrowsThePicker() throws Exception {
        MockMvc mvc = mvc(LIST, 25, Map.of("line-of-business-aliases.MCR[0]", "Medicare"));
        JsonNode answer = json(mvc, get("/api/procedure-codes").param("lob", "MCR"));
        assertThat(answer.get("narrowed").booleanValue()).isTrue();
        assertThat(codes(answer)).containsExactly("95810", "95811", "E2601", "E0601");
        assertThat(answer.has("notice")).isFalse();
    }

    @Test
    void anyTextIsALegalQueryAndOneThatMatchesNothingIsAnEmptyAnswerNotAnError() throws Exception {
        for (String query : new String[] {"zzz", "22\" wide", "%", "<script>", "a".repeat(500), "95810,95811", "\u00E9"}) {
            JsonNode answer = json(mvc(), get("/api/procedure-codes").param("q", query));
            assertThat(answer.get("matched").intValue()).as(query).isEqualTo(answer.get("codes").size());
            assertThat(answer.get("query").stringValue()).isEqualTo(query);
        }
        // The one of those that is in a description finds its row.
        assertThat(codes(json(mvc(), get("/api/procedure-codes").param("q", "22\" wide")))).containsExactly("E2601");
    }

    // ---- GET /api/procedure-codes/self-check ----

    @Test
    void theSelfCheckSaysWhatTheToolMadeOfTheList() throws Exception {
        JsonNode check = json(mvc(), get("/api/procedure-codes/self-check"));

        assertThat(check.propertyNames()).containsExactlyInAnyOrder("source", "placeholder", "format", "rowCount", "statingAuthRequirement",
                "namingVendor", "linesOfBusiness", "recognisedColumns", "unrecognisedColumns", "warnings");
        assertThat(check.get("source").stringValue()).isEqualTo(directory.resolve("codes.csv").toString());
        assertThat(check.get("placeholder").booleanValue()).isFalse();
        assertThat(check.get("format").stringValue()).isEqualTo("csv");
        assertThat(check.get("rowCount").intValue()).isEqualTo(6);
        // Four rows say yes or no. The blank one and "See policy" do not.
        assertThat(check.get("statingAuthRequirement").intValue()).isEqualTo(4);
        assertThat(check.get("namingVendor").intValue()).isEqualTo(1);
        // In the order the list first names them, and the columns in the order they are looked for.
        assertThat(JsonSafe.compact(check.get("linesOfBusiness"))).isEqualTo("{\"Medicare\":2,\"Commercial\":1,\"Medicare Advantage\":1}");
        assertThat(JsonSafe.compact(check.get("recognisedColumns"))).isEqualTo("{\"code\":\"Code\",\"description\":\"Description\","
                + "\"authRequired\":\"PA Required\",\"lineOfBusiness\":\"Line of Business\",\"delegatedVendor\":\"Delegated Vendor\","
                + "\"effectiveDate\":\"Effective Date\"}");
        // The column nobody recognised is reported, not dropped.
        assertThat(JsonSafe.compact(check.get("unrecognisedColumns"))).isEqualTo("[\"Internal Ref\"]");
        assertThat(check.get("warnings").size()).isEqualTo(1);
        assertThat(check.get("warnings").get(0).stringValue()).startsWith("The prior authorization column holds text this tool does not "
                + "read as a yes or a no: \"See policy\" (1 row).");
    }

    @Test
    void outOfTheBoxTheSelfCheckSaysThePlaceholderIsInUse() throws Exception {
        ProcedureCodeService bundled = new ProcedureCodeService(CodelistProperties.defaults());
        MockMvc mvc = TestMvc.standalone(new ProcedureCodeController(bundled, AppProperties.defaults()));

        mvc.perform(get("/api/procedure-codes/self-check")).andExpect(status().isOk())
                .andExpect(jsonPath("$.placeholder").value(true))
                .andExpect(jsonPath("$.source").value("classpath:codelist/placeholder-codes.csv"))
                .andExpect(jsonPath("$.unrecognisedColumns").isEmpty())
                .andExpect(jsonPath("$.warnings.length()").value(1));
        // The default limit, and a code the scenarios use.
        JsonNode answer = json(mvc, get("/api/procedure-codes").param("q", "95810").param("lob", "Medicare"));
        assertThat(answer.get("limit").intValue()).isEqualTo(25);
        assertThat(answer.get("narrowed").booleanValue()).isTrue();
        assertThat(answer.get("codes").get(0).get("authRequired").stringValue()).isEqualTo("REQUIRED");
        assertThat(answer.get("codes").get(0).get("delegatedVendor").stringValue()).isEqualTo("eviCore");
    }

    // ---- errors leave in the one shared shape ----

    @Test
    void aMethodTheEndpointsDoNotTakeIsRefusedInTheSharedErrorShape() throws Exception {
        for (String path : new String[] {"/api/procedure-codes", "/api/procedure-codes/self-check"}) {
            mvc().perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.kind").value("VALIDATION"))
                    .andExpect(jsonPath("$.title").value("This endpoint does not accept POST."));
        }
    }
}
