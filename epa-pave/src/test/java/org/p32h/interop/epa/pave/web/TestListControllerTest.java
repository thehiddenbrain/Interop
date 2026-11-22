package org.p32h.interop.epa.pave.web;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.TestListProperties;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.service.config.TestListStore;
import org.p32h.interop.epa.pave.service.lists.TestListService;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The saved test lists over HTTP: each change, each refusal at its field, the CSV import and the templates. */
class TestListControllerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC);
    private static final String MEMBERS = "/api/lists/members";
    private static final String PROVIDERS = "/api/lists/providers";

    @TempDir
    Path directory;

    private MockMvc mvc(boolean enabled) {
        TestListStore store = new TestListStore(ConfigStoreProperties.of(Map.of("directory", directory.toString())), TestMvc.mapper());
        TestListService service = new TestListService(TestListProperties.of(Map.of("enabled", String.valueOf(enabled))), store, CLOCK);
        return TestMvc.standalone(new TestListController(service));
    }

    private MockMvc mvc() {
        return mvc(true);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String member(String memberId) {
        return "{\"label\": \"Needs prior authorization\", \"memberId\": \"" + memberId + "\", \"firstName\": \"Sample\", "
                + "\"lastName\": \"Placeholder-One\", \"dateOfBirth\": \"1980-01-31\", \"gender\": \"female\", \"lineOfBusiness\": "
                + "\"Commercial\", \"plan\": \"Gold\", \"subscriberId\": \"" + memberId + "\", \"coverageStatus\": \"active\", "
                + "\"coverageStart\": \"2026-01-01\", \"coverageEnd\": \"2026-12-31\", \"vendorPatientId\": \"patient-7\"}";
    }

    private static String idOf(ResultActions result) throws Exception {
        return JsonSafe.mapper().readTree(result.andReturn().getResponse().getContentAsString()).get("id").asString();
    }

    private static ResultActions refusedAt(ResultActions result, String field, String message) throws Exception {
        return result.andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1))).andExpect(jsonPath("$.fieldErrors[0].field").value(field))
                .andExpect(jsonPath("$.fieldErrors[0].message").value(message));
    }

    @Test
    void aMemberIsAddedReadReplacedAndRemoved() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get(MEMBERS)).andExpect(status().isOk()).andExpect(content().json("{\"enabled\": true, \"members\": [], \"count\": 0}"))
                .andExpect(jsonPath("$.problem").doesNotExist());

        ResultActions added = mvc.perform(json(post(MEMBERS), member("S123456789"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", matchesPattern("m-[0-9a-f]{12}"))).andExpect(jsonPath("$.memberId").value("S123456789"))
                .andExpect(jsonPath("$.vendorPatientId").value("patient-7"));
        String id = idOf(added);
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.members[0].id").value(id))
                .andExpect(jsonPath("$.members[0].coverageEnd").value("2026-12-31"));

        // A replace takes the whole entry: a value left out is gone, never kept from before.
        mvc.perform(json(put(MEMBERS + "/" + id), "{\"label\": \"Renamed\", \"memberId\": \"S123456789\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id)).andExpect(jsonPath("$.label").value("Renamed"))
                .andExpect(jsonPath("$.plan").doesNotExist());
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.members[0].label").value("Renamed"));

        mvc.perform(delete(MEMBERS + "/" + id)).andExpect(status().isOk()).andExpect(content().json("{\"removed\": true}"));
        mvc.perform(delete(MEMBERS + "/" + id)).andExpect(status().isOk()).andExpect(content().json("{\"removed\": false}"));
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(0));
        mvc.perform(json(put(MEMBERS + "/" + id), member("S123456789"))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND")).andExpect(jsonPath("$.title", startsWith("This saved member is no longer")));
    }

    @Test
    void aProviderIsAddedReadReplacedAndRemoved() throws Exception {
        MockMvc mvc = mvc();
        String id = idOf(mvc.perform(json(post(PROVIDERS), "{\"label\": \"Vendor practitioner\", \"vendorPractitionerId\": \"P123\", "
                + "\"organization\": \"Sample Clinic\", \"placeOfService\": \"22\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id", matchesPattern("p-[0-9a-f]{12}"))).andExpect(jsonPath("$.npi").doesNotExist()));
        mvc.perform(get(PROVIDERS)).andExpect(content().json("{\"enabled\": true, \"count\": 1, \"providers\": [{\"id\": \"" + id
                + "\", \"vendorPractitionerId\": \"P123\", \"placeOfService\": \"22\"}]}"));
        mvc.perform(json(put(PROVIDERS + "/" + id), "{\"npi\": \"1234567893\", \"fax\": \"617-555-0102\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.npi").value("1234567893")).andExpect(jsonPath("$.vendorPractitionerId").doesNotExist());
        mvc.perform(delete(PROVIDERS + "/" + id)).andExpect(content().json("{\"removed\": true}"));
        mvc.perform(get(PROVIDERS)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void eachMemberFieldIsRefusedAtItsFieldAndNothingIsSaved() throws Exception {
        MockMvc mvc = mvc();
        String base = member("S123456789");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("\"memberId\": \"S123456789\"", "\"memberId\": \"  \""))), "memberId",
                "Enter the member ID as it is on the card.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("\"memberId\": \"S123456789\"", "\"memberId\": \"S" + "1".repeat(30) + "\""))),
                "memberId", "A member ID is at most 30 characters. Check that nothing else was pasted with it.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("1980-01-31", "01/31/1980"))), "dateOfBirth",
                "Write the date of birth as yyyy-MM-dd, for example 1980-01-31.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("1980-01-31", "2026-02-30"))), "dateOfBirth",
                "Write the date of birth as yyyy-MM-dd, for example 1980-01-31.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("1980-01-31", "2026-11-23"))), "dateOfBirth",
                "The date of birth is after today.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("\"female\"", "\"f\""))), "gender",
                "Gender is female, male, other or unknown, or left blank.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("2026-01-01", "Jan 1"))), "coverageStart",
                "Write the coverage start as yyyy-MM-dd, for example 1980-01-31.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("2026-12-31", "2026-13-01"))), "coverageEnd",
                "Write the coverage end as yyyy-MM-dd, for example 1980-01-31.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("patient-7", "patient/7"))), "vendorPatientId",
                "The vendor's patient id is letters, digits, - and . only, at most 64 characters.");
        refusedAt(mvc.perform(json(post(MEMBERS), base.replace("patient-7", "p".repeat(65)))), "vendorPatientId",
                "The vendor's patient id is letters, digits, - and . only, at most 64 characters.");
        mvc.perform(json(post(MEMBERS), base.replace("1980-01-31", "x"))).andExpect(jsonPath("$.title")
                .value("The member has 1 problem. Nothing was saved."));
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(0));

        // Gender is matched without case and kept as the select offers it; blank is not stated.
        mvc.perform(json(post(MEMBERS), base.replace("\"female\"", "\"Female\""))).andExpect(jsonPath("$.gender").value("female"));
        mvc.perform(json(post(MEMBERS), member("S123456788").replace("\"female\"", "\" \""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.gender").doesNotExist());
    }

    @Test
    void eachProviderFieldIsRefusedAtItsField() throws Exception {
        MockMvc mvc = mvc();
        refusedAt(mvc.perform(json(post(PROVIDERS), "{\"label\": \"No identifier\", \"firstName\": \"Sample\"}")), "npi",
                "Enter an NPI or a vendor practitioner id.");
        refusedAt(mvc.perform(json(post(PROVIDERS), "{\"npi\": \"1234567890\"}")), "npi",
                "The last digit of this NPI does not match the other nine. Check the number for a typing mistake.");
        refusedAt(mvc.perform(json(post(PROVIDERS), "{\"npi\": \"12345\", \"vendorPractitionerId\": \"P123\"}")), "npi",
                "An NPI is 10 digits. Check the number for a typing mistake.");
        refusedAt(mvc.perform(json(post(PROVIDERS), "{\"vendorPractitionerId\": \"P 123\"}")), "vendorPractitionerId",
                "The vendor's practitioner id is letters, digits, - and . only, at most 64 characters.");
        mvc.perform(get(PROVIDERS)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void oneMemberIdNpiOrVendorPractitionerIdNamesOneEntry() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(json(post(MEMBERS), member("S123456789"))).andExpect(status().isOk());
        // The same member ID written with spaces is the same member.
        refusedAt(mvc.perform(json(post(MEMBERS), member("S123 456 789"))), "memberId",
                "A saved member has this member ID already. Edit that entry instead.");
        String other = idOf(mvc.perform(json(post(MEMBERS), member("S123456788"))));
        refusedAt(mvc.perform(json(put(MEMBERS + "/" + other), member("S123456789"))), "memberId",
                "A saved member has this member ID already. Edit that entry instead.");
        // An entry may keep its own member ID when it is replaced.
        mvc.perform(json(put(MEMBERS + "/" + other), member("S123456788"))).andExpect(status().isOk());

        mvc.perform(json(post(PROVIDERS), "{\"npi\": \"1234567893\", \"vendorPractitionerId\": \"P123\"}")).andExpect(status().isOk());
        refusedAt(mvc.perform(json(post(PROVIDERS), "{\"npi\": \"1234567893\"}")), "npi",
                "A saved provider has this NPI already. Edit that entry instead.");
        refusedAt(mvc.perform(json(post(PROVIDERS), "{\"vendorPractitionerId\": \"P123\"}")), "vendorPractitionerId",
                "A saved provider has this vendor practitioner id already. Edit that entry instead.");
    }

    @Test
    void aMisspeltPropertyOrAnIdInTheBodyIsRefusedByName() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(json(post(MEMBERS), "{\"memberID\": \"S123456789\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("memberID"));
        mvc.perform(json(post(MEMBERS), "{\"id\": \"m-000000000000\", \"memberId\": \"S123456789\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("id"));
        mvc.perform(json(post(PROVIDERS), "{\"NPI\": \"1234567893\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("NPI"));
        mvc.perform(json(post(MEMBERS + "/import"), "{\"csv\": \"memberId\", \"dryrun\": true}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("dryrun"));
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(0));
    }

    private static String importBody(String csv, boolean dryRun) {
        return "{\"csv\": " + JsonSafe.mapper().writeValueAsString(csv) + ", \"dryRun\": " + dryRun + "}";
    }

    @Test
    void anImportShowsColumnsAndEachRowOnADryRunAndSavesOnlyWhenApplied() throws Exception {
        MockMvc mvc = mvc();
        String csv = "Label,Member ID,last_name,Date of birth,Favourite colour,plan\r\n"
                + "First,S123456789,Placeholder-One,1980-01-31,blue,Gold\r\n"
                + "Bad date,S123456788,Placeholder-Two,31/01/1980,red,Gold\r\n"
                + "\r\n"
                + "Again,S123 456 789,Placeholder-One,1980-01-31,green,Gold\r\n"
                + "No ID,,Placeholder-Three,,,\r\n"
                + "Comma,S123456787,Placeholder, Four,1980-01-31,blue,Gold\r\n"
                + "Second,S123456786,Placeholder-Five,1981-02-01,,\r\n";

        mvc.perform(json(post(MEMBERS + "/import"), importBody(csv, true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.columnsUsed").value(org.hamcrest.Matchers.contains("label", "memberId", "lastName", "dateOfBirth",
                        "plan")))
                .andExpect(jsonPath("$.columnsIgnored").value(org.hamcrest.Matchers.contains("Favourite colour")))
                .andExpect(jsonPath("$.rows", hasSize(6)))
                .andExpect(jsonPath("$.rows[0].line").value(2)).andExpect(jsonPath("$.rows[0].status").value("ADD"))
                .andExpect(jsonPath("$.rows[0].label").value("First")).andExpect(jsonPath("$.rows[0].reason").doesNotExist())
                .andExpect(jsonPath("$.rows[1].status").value("REFUSED"))
                .andExpect(jsonPath("$.rows[1].reason").value("Write the date of birth as yyyy-MM-dd, for example 1980-01-31."))
                .andExpect(jsonPath("$.rows[2].line").value(5)).andExpect(jsonPath("$.rows[2].status").value("REFUSED"))
                .andExpect(jsonPath("$.rows[2].reason").value("Line 2 is the same member. Only the first of the two is imported."))
                .andExpect(jsonPath("$.rows[3].status").value("REFUSED"))
                .andExpect(jsonPath("$.rows[3].reason").value("Enter the member ID as it is on the card."))
                .andExpect(jsonPath("$.rows[4].status").value("REFUSED"))
                .andExpect(jsonPath("$.rows[4].reason", startsWith("This row has more values than the header has columns.")))
                .andExpect(jsonPath("$.rows[5].status").value("ADD"))
                .andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.replaced").value(0)).andExpect(jsonPath("$.refused").value(4))
                .andExpect(jsonPath("$.applied").value(false));
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(0));

        mvc.perform(json(post(MEMBERS + "/import"), importBody(csv, false))).andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(2)).andExpect(jsonPath("$.applied").value(true));
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(2)).andExpect(jsonPath("$.members[0].label").value("First"))
                .andExpect(jsonPath("$.members[0].lastName").value("Placeholder-One")).andExpect(jsonPath("$.members[0].plan").value("Gold"))
                .andExpect(jsonPath("$.members[1].memberId").value("S123456786")).andExpect(jsonPath("$.members[1].plan").doesNotExist());
    }

    @Test
    void aReimportReplacesByMemberIdAndKeepsWhatTheFileLacks() throws Exception {
        MockMvc mvc = mvc();
        String id = idOf(mvc.perform(json(post(MEMBERS), member("S123456789"))));
        // The file has no plan column and a blank coverage end: the plan is kept, the coverage end cleared.
        String csv = "memberId,label,coverageEnd\r\nS123 456 789,Renamed,\r\n";
        mvc.perform(json(post(MEMBERS + "/import"), importBody(csv, false))).andExpect(jsonPath("$.rows[0].status").value("REPLACE"))
                .andExpect(jsonPath("$.rows[0].label").value("Renamed")).andExpect(jsonPath("$.replaced").value(1));
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(1)).andExpect(jsonPath("$.members[0].id").value(id))
                .andExpect(jsonPath("$.members[0].label").value("Renamed")).andExpect(jsonPath("$.members[0].memberId").value("S123 456 789"))
                .andExpect(jsonPath("$.members[0].plan").value("Gold")).andExpect(jsonPath("$.members[0].vendorPatientId").value("patient-7"))
                .andExpect(jsonPath("$.members[0].coverageEnd").doesNotExist());
    }

    @Test
    void aReimportReplacesByNpiOrByVendorPractitionerIdAndKeepsWhatTheFileLacks() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(json(post(PROVIDERS), "{\"label\": \"By NPI\", \"npi\": \"1234567893\", \"specialty\": \"Sleep Medicine\", "
                + "\"placeOfService\": \"11\"}")).andExpect(status().isOk());
        mvc.perform(json(post(PROVIDERS), "{\"label\": \"By vendor id\", \"vendorPractitionerId\": \"P123\", \"phone\": \"617-555-0103\"}"))
                .andExpect(status().isOk());
        String csv = "npi,vendorPractitionerId,phone\r\n1234567893,,617-555-0101\r\n,P123,617-555-0109\r\n,P124,617-555-0104\r\n";
        mvc.perform(json(post(PROVIDERS + "/import"), importBody(csv, false)))
                .andExpect(jsonPath("$.rows[*].status").value(org.hamcrest.Matchers.contains("REPLACE", "REPLACE", "ADD")));
        mvc.perform(get(PROVIDERS)).andExpect(jsonPath("$.count").value(3))
                .andExpect(jsonPath("$.providers[0].label").value("By NPI")).andExpect(jsonPath("$.providers[0].specialty").value("Sleep Medicine"))
                .andExpect(jsonPath("$.providers[0].placeOfService").value("11")).andExpect(jsonPath("$.providers[0].phone").value("617-555-0101"))
                .andExpect(jsonPath("$.providers[1].label").value("By vendor id")).andExpect(jsonPath("$.providers[1].phone").value("617-555-0109"))
                .andExpect(jsonPath("$.providers[2].vendorPractitionerId").value("P124"));

        // A row whose NPI is one saved provider's and whose vendor id is another's is refused, never guessed.
        mvc.perform(json(post(PROVIDERS + "/import"), importBody("npi,vendorPractitionerId\r\n1234567893,P124\r\n", true)))
                .andExpect(jsonPath("$.rows[0].status").value("REFUSED"))
                .andExpect(jsonPath("$.rows[0].reason", startsWith("Its NPI and its vendor practitioner id belong to two different")));
    }

    @Test
    void anImportWithoutTheIdentifyingColumnOrWithoutDryRunIsRefusedWhole() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(json(post(MEMBERS + "/import"), importBody("label,plan\r\nFirst,Gold\r\n", true))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The file has no memberId column, so no row can be matched to a saved member."));
        mvc.perform(json(post(PROVIDERS + "/import"), importBody("label\r\nFirst\r\n", true))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The file has no npi or vendorPractitionerId column, so no row can be matched to a saved "
                        + "provider."));
        refusedAt(mvc.perform(json(post(MEMBERS + "/import"), "{\"csv\": \"memberId\\r\\nS123456789\\r\\n\"}")), "dryRun",
                "Say whether this is a dry run: true shows what each row would do, false imports.");
        refusedAt(mvc.perform(json(post(MEMBERS + "/import"), "{\"csv\": \" \", \"dryRun\": true}")), "csv",
                "Choose a CSV file with a header row.");
        mvc.perform(get(MEMBERS)).andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void theTemplatesHoldTheHeaderAndThreeRowsOfPlaceholders() throws Exception {
        MockMvc mvc = mvc();
        String members = mvc.perform(get(MEMBERS + "/template.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", startsWith("text/csv")))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"test-members-template.csv\""))
                .andReturn().getResponse().getContentAsString();
        String[] lines = members.split("\r\n");
        org.assertj.core.api.Assertions.assertThat(lines).hasSize(4);
        org.assertj.core.api.Assertions.assertThat(lines[0]).isEqualTo("label,memberId,firstName,lastName,dateOfBirth,gender,"
                + "lineOfBusiness,plan,subscriberId,coverageStatus,coverageStart,coverageEnd,vendorPatientId");
        org.assertj.core.api.Assertions.assertThat(lines[1]).contains(",S123456789,");
        org.assertj.core.api.Assertions.assertThat(lines[2]).contains(",S123456788,");
        org.assertj.core.api.Assertions.assertThat(lines[3]).contains(",S123456787,");

        String providers = mvc.perform(get(PROVIDERS + "/template.csv")).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"test-providers-template.csv\""))
                .andReturn().getResponse().getContentAsString();
        String[] rows = providers.split("\r\n");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(4);
        org.assertj.core.api.Assertions.assertThat(rows[0])
                .isEqualTo("label,npi,vendorPractitionerId,firstName,lastName,organization,specialty,phone,fax,placeOfService");
        org.assertj.core.api.Assertions.assertThat(rows[1]).contains(",1234567893,,");
        org.assertj.core.api.Assertions.assertThat(rows[2]).contains(",,P123,");
        org.assertj.core.api.Assertions.assertThat(rows[3]).contains(",,P124,");

        // Each template imports as it stands: three entries added, none refused.
        mvc.perform(json(post(MEMBERS + "/import"), importBody(members, true))).andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.refused").value(0)).andExpect(jsonPath("$.columnsIgnored", hasSize(0)));
        mvc.perform(json(post(PROVIDERS + "/import"), importBody(providers, true))).andExpect(jsonPath("$.added").value(3))
                .andExpect(jsonPath("$.refused").value(0));
    }

    @Test
    void switchedOffTheListsAnswerEmptyAndRefuseEveryChange() throws Exception {
        MockMvc mvc = mvc(false);
        mvc.perform(get(MEMBERS)).andExpect(status().isOk()).andExpect(content().json("{\"enabled\": false, \"members\": [], \"count\": 0}"));
        mvc.perform(get(PROVIDERS)).andExpect(content().json("{\"enabled\": false, \"providers\": [], \"count\": 0}"));
        String off = "Saved test lists are switched off on this installation (test-lists.enabled).";
        mvc.perform(json(post(MEMBERS), member("S123456789"))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value(off))
                .andExpect(jsonPath("$.action").value("Set test-lists.enabled to true in application.yml, then start PAVE again."));
        mvc.perform(json(post(PROVIDERS + "/import"), importBody("npi\r\n1234567893\r\n", true))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value(off));
        mvc.perform(delete(MEMBERS + "/m-000000000000")).andExpect(status().isUnprocessableContent());
        org.assertj.core.api.Assertions.assertThat(directory.resolve("test-lists")).doesNotExist();
    }
}
