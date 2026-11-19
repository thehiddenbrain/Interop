package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.lookup.LookupFixtures;
import org.p32h.interop.epa.pave.client.lookup.MemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.MockMemberLookupClient;
import org.p32h.interop.epa.pave.client.lookup.MockProviderLookupClient;
import org.p32h.interop.epa.pave.client.lookup.ProviderLookupClient;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.lookup.MemberLookupService;
import org.p32h.interop.epa.pave.service.lookup.ProviderLookupService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * The lookup endpoints as a browser sees them: 200 for every lookup that was really asked, with
 * how it ended in the body, and 422 only for an identifier refused before any lookup. The real
 * services, mock clients, fixtures and mappings are behind the controller.
 */
class LookupControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    private static final LookupFixtures FIXTURES = new LookupFixtures();

    private final List<String> asked = new ArrayList<>();

    private MockMvc mvc(Map<String, String> properties) {
        LookupProperties lookup = LookupProperties.of(properties);
        MemberLookupClient members = new MockMemberLookupClient(FIXTURES, lookup, CLOCK);
        ProviderLookupClient providers = new MockProviderLookupClient(FIXTURES, lookup);
        // Each client is wrapped only to count the lookups that reach it.
        return TestMvc.standalone(new LookupController(
                new MemberLookupService(memberId -> {
                    asked.add("member");
                    return members.lookup(memberId);
                }),
                new ProviderLookupService(npi -> {
                    asked.add("provider");
                    return providers.lookup(npi);
                })));
    }

    private MockMvc mvc() {
        return mvc(Map.of());
    }

    private JsonNode member(MockMvc mvc, String memberId) throws Exception {
        String body = mvc.perform(get("/api/members/{memberId}", memberId)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(body).node();
    }

    private JsonNode provider(MockMvc mvc, String npi) throws Exception {
        String body = mvc.perform(get("/api/providers/{npi}", npi)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(body).node();
    }

    @Test
    void aMemberWhoIsFoundAnswers200WithTheMemberTheFieldsAndBothFormsOfTheId() throws Exception {
        JsonNode result = member(mvc(), "P32H-PA-REQ");
        assertThat(result.get("status").asString()).isEqualTo("FOUND");
        assertThat(result.get("message").asString()).isEqualTo("Member found, with coverage reported as active.");
        assertThat(result.at("/memberId/typed").asString()).isEqualTo("P32H-PA-REQ");
        assertThat(result.at("/memberId/sent").asString()).isEqualTo("P32H-PA-REQ");
        assertThat(result.at("/memberId/changed").asBoolean()).isFalse();
        assertThat(result.at("/member/memberId").asString()).isEqualTo("P32H-PA-REQ");
        assertThat(result.at("/member/firstName").asString()).isEqualTo("Avery");
        assertThat(result.at("/member/dateOfBirth").asString()).isEqualTo("1950-01-31");
        assertThat(result.at("/member/lineOfBusiness").asString()).isEqualTo("Medicare");
        assertThat(result.at("/member/address/city").asString()).isEqualTo("Sampletown");
        // Each field says what came back for it and where it was read from.
        JsonNode firstName = result.get("fields").get(1);
        assertThat(firstName.get("name").asString()).isEqualTo("firstName");
        assertThat(firstName.get("label").asString()).isEqualTo("First name");
        assertThat(firstName.at("/value/presence").asString()).isEqualTo("VALUE");
        assertThat(firstName.at("/value/value").asString()).isEqualTo("Avery");
        assertThat(firstName.get("sourceField").asString()).isEqualTo("firstName");
        // A field that was not returned says so, and has no value and no source at all.
        JsonNode coverageEnd = result.get("fields").get(11);
        assertThat(coverageEnd.get("name").asString()).isEqualTo("coverageEnd");
        assertThat(coverageEnd.at("/value/presence").asString()).isEqualTo("ABSENT");
        assertThat(coverageEnd.get("value").has("value")).isFalse();
        assertThat(coverageEnd.has("sourceField")).isFalse();
        // No reason to look is an empty list. No fault is no error at all, never an error written as null.
        assertThat(result.get("attention").isArray()).isTrue();
        assertThat(result.get("attention")).isEmpty();
        assertThat(result.has("error")).isFalse();
        assertThat(result.get("additionalFields").isArray()).isTrue();
        // The wire view: nothing was sent on mock, and it says so.
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(result.at("/wire/request/method").asString()).isEqualTo("GET");
        assertThat(result.at("/wire/response/status").asInt()).isEqualTo(200);
        assertThat(result.at("/wire/memberId/sent").asString()).isEqualTo("P32H-PA-REQ");
        assertThat(asked).containsExactly("member");
    }

    @Test
    void anIdTypedWithSpacesShowsWhatWasTypedAndWhatWasSent() throws Exception {
        JsonNode result = member(mvc(), "S12345678   01");
        assertThat(result.get("status").asString()).isEqualTo("FOUND");
        assertThat(result.at("/memberId/typed").asString()).isEqualTo("S12345678   01");
        assertThat(result.at("/memberId/sent").asString()).isEqualTo("S1234567801");
        assertThat(result.at("/memberId/changed").asBoolean()).isTrue();
        assertThat(result.at("/wire/memberId/typed").asString()).isEqualTo("S12345678   01");
        assertThat(result.at("/wire/memberId/sent").asString()).isEqualTo("S1234567801");
        assertThat(result.at("/member/memberId").asString()).isEqualTo("S1234567801");
        assertThat(result.at("/member/lineOfBusiness").asString()).isEqualTo("Medicare");
    }

    @Test
    void aMemberWhoNeedsALookIsFoundWithTheReasons() throws Exception {
        JsonNode result = member(mvc(), "P32H-INACTIVE");
        assertThat(result.get("status").asString()).isEqualTo("FOUND");
        assertThat(result.get("attention")).hasSize(2);
        assertThat(result.get("attention").get(0).asString()).contains("Coverage is not active");
        assertThat(result.get("message").asString()).isEqualTo("Member found, with 2 things to check before using this member.");
        assertThat(result.at("/member/coverageStatus").asString()).isEqualTo("Termed");
        assertThat(result.has("error")).isFalse();
    }

    @Test
    void aMemberWhoIsNotFoundIsStillA200AndIsNotAnError() throws Exception {
        JsonNode result = member(mvc(), "P32H-NOMEM");
        assertThat(result.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(result.get("message").asString()).isEqualTo("The member API does not know this member ID (it answered HTTP 404).");
        assertThat(result.has("member")).isFalse();
        assertThat(result.has("error")).isFalse();
        assertThat(result.get("fields")).isEmpty();
        assertThat(result.at("/wire/response/status").asInt()).isEqualTo(404);
        assertThat(result.at("/memberId/sent").asString()).isEqualTo("P32H-NOMEM");
    }

    @Test
    void aLookupThatFaultsIsStillA200WithTheFaultDescribedInTheBody() throws Exception {
        JsonNode result = member(mvc(), "P32H-LOOKUP-FAULT");
        assertThat(result.get("status").asString()).isEqualTo("FAULT");
        assertThat(result.get("message").asString()).isEqualTo("The member API failed with HTTP 503.");
        assertThat(result.at("/error/kind").asString()).isEqualTo("VENDOR_ERROR");
        assertThat(result.at("/error/httpStatus").asInt()).isEqualTo(503);
        assertThat(result.at("/error/action").asString()).contains("Type the member details by hand.");
        assertThat(result.has("member")).isFalse();
        // What the failing service said is on screen, not only in the wire view.
        assertThat(result.get("additionalFields").get(0).get("path").asString()).isEqualTo("error");
        assertThat(result.get("additionalFields").get(0).get("kind").asString()).isEqualTo("FIELD");
        assertThat(result.at("/wire/response/status").asInt()).isEqualTo(503);

        JsonNode timeout = member(mvc(), "P32H-LOOKUP-TIMEOUT");
        assertThat(timeout.get("status").asString()).isEqualTo("FAULT");
        assertThat(timeout.at("/error/kind").asString()).isEqualTo("TIMEOUT");
        assertThat(timeout.at("/wire/response").has("status")).isFalse();
    }

    @Test
    void aLookupThatIsSwitchedOffIsA200ThatSaysToTypeByHandAndIsNotAnError() throws Exception {
        MockMvc mvc = mvc(Map.of("member.enabled", "false", "provider.enabled", "false"));
        JsonNode member = member(mvc, "P32H-PA-REQ");
        assertThat(member.get("status").asString()).isEqualTo("DISABLED");
        assertThat(member.get("message").asString()).contains("switched off").contains("type the member details by hand");
        assertThat(member.has("error")).isFalse();
        assertThat(member.has("member")).isFalse();
        assertThat(member.has("wire")).isFalse();
        assertThat(member.at("/memberId/typed").asString()).isEqualTo("P32H-PA-REQ");

        JsonNode provider = provider(mvc, "1234567893");
        assertThat(provider.get("status").asString()).isEqualTo("DISABLED");
        assertThat(provider.get("message").asString()).contains("type the provider details by hand");
        assertThat(provider.has("error")).isFalse();
        assertThat(provider.get("npi").asString()).isEqualTo("1234567893");
    }

    @Test
    void aBlankMemberIdIs422AndNothingIsLookedUp() throws Exception {
        mvc().perform(get("/api/members/{memberId}", "   ")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("There is no member ID to look up. Nothing was sent."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("memberId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter a member ID. Nothing was looked up."));
        assertThat(asked).isEmpty();
    }

    @Test
    void aProviderWhoIsFoundAnswers200WithTheProviderAndTheFields() throws Exception {
        JsonNode result = provider(mvc(), "1234567893");
        assertThat(result.get("status").asString()).isEqualTo("FOUND");
        assertThat(result.get("npi").asString()).isEqualTo("1234567893");
        assertThat(result.get("message").asString()).isEqualTo("Provider found, reported as in network.");
        assertThat(result.at("/provider/npi").asString()).isEqualTo("1234567893");
        assertThat(result.at("/provider/lastName").asString()).isEqualTo("Placeholder-Provider");
        assertThat(result.at("/provider/specialty").asString()).isEqualTo("Sleep Medicine");
        assertThat(result.at("/provider/networkStatus").asString()).isEqualTo("In network");
        assertThat(result.get("fields").get(0).get("name").asString()).isEqualTo("npi");
        assertThat(result.get("fields").get(0).get("sourceField").asString()).isEqualTo("npi");
        assertThat(result.get("attention")).isEmpty();
        assertThat(result.has("error")).isFalse();
        // A provider lookup carries no member, so its wire view carries no member ID.
        assertThat(result.at("/wire").has("memberId")).isFalse();
        assertThat(result.at("/wire/request/sent").asBoolean()).isFalse();
        assertThat(asked).containsExactly("provider");
    }

    @Test
    void aProviderWhoIsOutOfNetworkIsFoundWithTheReason() throws Exception {
        JsonNode result = provider(mvc(), "1000000020");
        assertThat(result.get("status").asString()).isEqualTo("FOUND");
        assertThat(result.get("attention")).hasSize(1);
        assertThat(result.get("attention").get(0).asString()).startsWith("This provider is out of network");
        assertThat(result.has("error")).isFalse();
    }

    @Test
    void aProviderWhoIsNotFoundOrADirectoryThatFaultsIsStillA200() throws Exception {
        JsonNode notFound = provider(mvc(), "9999999995");
        assertThat(notFound.get("status").asString()).isEqualTo("NOT_FOUND");
        assertThat(notFound.has("provider")).isFalse();
        assertThat(notFound.has("error")).isFalse();

        JsonNode fault = provider(mvc(), "9999999987");
        assertThat(fault.get("status").asString()).isEqualTo("FAULT");
        assertThat(fault.at("/error/kind").asString()).isEqualTo("VENDOR_ERROR");
        assertThat(fault.at("/error/httpStatus").asInt()).isEqualTo(500);
        // A body that is not JSON is shown as received, and is marked as that.
        assertThat(fault.get("additionalFields").get(0).get("kind").asString()).isEqualTo("RAW_BODY");
        assertThat(fault.get("additionalFields").get(0).get("value").asString()).contains("500 Internal Server Error");
    }

    @Test
    void aMalformedNpiIs422AndTheDirectoryIsNotAsked() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/api/providers/{npi}", "1234567890")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("This is not an NPI that can be looked up. Nothing was sent."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("npi"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The check digit of this NPI does not match, so it is probably a "
                        + "typing mistake. The directory was not asked."));
        mvc.perform(get("/api/providers/{npi}", "12345")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("npi"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.startsWith("An NPI is exactly ten digits.")));
        mvc.perform(get("/api/providers/{npi}", " ")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("npi"));
        assertThat(asked).isEmpty();
    }

    @Test
    void anIdentifierIsNeverCutShortByASemicolonInTheAddress() throws Exception {
        MockMvc mvc = mvc();
        // Unencoded, everything from the semicolon on would be dropped before the lookup, and the
        // member looked up would not be the one that was typed.
        mvc.perform(get(URI.create("/api/members/S123456789;x=1"))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("This lookup cannot be made as it was addressed. Nothing was sent."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("memberId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(org.hamcrest.Matchers.containsString("semicolon")));
        mvc.perform(get(URI.create("/api/providers/1234567893;x=1"))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("npi"));
        assertThat(asked).isEmpty();

        // Percent-encoded, a semicolon is part of the identifier and arrives whole. So does every
        // other character a url would otherwise read as its own.
        Map<String, String> whole = new java.util.LinkedHashMap<>();
        whole.put("/api/members/S123456789%3Bx=1", "S123456789;x=1");
        whole.put("/api/members/S123%25456", "S123%456");
        whole.put("/api/members/S123%23x%3Fy", "S123#x?y");
        whole.put("/api/members/S123+456", "S123+456");
        whole.put("/api/members/S123.456.789", "S123.456.789");
        whole.put("/api/members/S12345678%20%20%2001", "S12345678   01");
        for (Map.Entry<String, String> one : whole.entrySet()) {
            String body = mvc.perform(get(URI.create(one.getKey()))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(JsonSafe.parse(body).node().at("/memberId/typed").asString()).as(one.getKey()).isEqualTo(one.getValue());
        }
    }

    @Test
    void thereIsNoWayToSearchOrToListAndALookupIsOnlyEverARead() throws Exception {
        MockMvc mvc = mvc();
        // One identifier in, one record out. A request for the collection is not answered with anybody.
        assertThat(mvc.perform(get("/api/members")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/providers")).andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/members").param("name", "Placeholder")).andReturn().getResponse().getStatus()).isEqualTo(404);
        mvc.perform(post("/api/members/{memberId}", "S123456789")).andExpect(status().isMethodNotAllowed());
        // A parameter beside the identifier is not a search: it changes nothing about which one record is asked for.
        String body = mvc.perform(get("/api/members/{memberId}", "S123456789").param("name", "Placeholder")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonSafe.parse(body).node().at("/memberId/sent").asString()).isEqualTo("S123456789");
        assertThat(asked).containsExactly("member");
    }
}
