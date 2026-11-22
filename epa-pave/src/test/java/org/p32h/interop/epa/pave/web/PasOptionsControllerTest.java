package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.service.pas.PasOptions;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/pas/options} as the page reads it: every list in the order and with the words the
 * plan settled, the attachment kinds one per extension, and the limits in bytes.
 */
class PasOptionsControllerTest {

    private final MockMvc mvc = TestMvc.standalone(new PasOptionsController(() -> RequestShapes.DEFAULTS));

    private JsonNode options() throws Exception {
        String body = mvc.perform(get("/api/pas/options")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonSafe.parse(body).node();
    }

    private static List<String> pairs(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode option : list) {
            out.add(option.get("code").asString() + " " + option.get("label").asString());
        }
        return out;
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.propertyNames().forEach(out::add);
        return out;
    }

    @Test
    void theLabelNamesTheGuideTheActiveEnvironmentChose() throws Exception {
        RequestShapes chose221 = RequestShapes.DEFAULTS.with(RequestShape.PAS_GUIDE, RequestShapes.Choice.ofEnvironment("2.2.1"));
        String body = TestMvc.standalone(new PasOptionsController(() -> chose221)).perform(get("/api/pas/options"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonSafe.parse(body).node().get("version").asString()).isEqualTo("Da Vinci PAS 2.2.1");
    }

    @Test
    void theAnswerNamesTheGuideAndHoldsEveryListInItsSettledOrder() throws Exception {
        JsonNode options = options();
        assertThat(names(options)).containsExactly("version", "certificationTypes", "requestTypes", "serviceTypes", "levelsOfService",
                "placesOfService", "units", "attachments");
        assertThat(options.get("version").asString()).as("an environment that states no PAS guide is held against 2.0.1")
                .isEqualTo("Da Vinci PAS 2.0.1");
        assertThat(pairs(options.get("certificationTypes"))).containsExactly("I Initial", "S Revised", "4 Extension", "R Renewal",
                "3 Cancel", "1 Appeal, immediate", "2 Appeal, standard");
        assertThat(pairs(options.get("requestTypes"))).containsExactly("HS Health services review", "AR Admission review",
                "SC Specialty care review", "IN Initial medical services reservation");
        assertThat(pairs(options.get("serviceTypes"))).containsExactly("1 Medical care", "2 Surgical", "3 Consultation",
                "42 Home health care");
        assertThat(pairs(options.get("levelsOfService"))).containsExactly("E Elective", "U Urgent", "03 Emergency");
        assertThat(pairs(options.get("placesOfService"))).containsExactly("11 Office", "12 Home", "19 Off campus outpatient hospital",
                "21 Inpatient hospital", "22 On campus outpatient hospital", "23 Emergency room, hospital",
                "24 Ambulatory surgical center", "81 Independent laboratory");
        assertThat(pairs(options.get("units"))).containsExactly("FL Units", "DY Days", "VS Visits", "HS Hours", "MN Months");
        // A code such as 03 keeps its leading zero: it is a code, not a number.
        assertThat(options.at("/levelsOfService/2/code").isString()).isTrue();
    }

    @Test
    void theAttachmentKindsAreOnePerExtensionWithTheirMediaTypeAndTheLimitsAreInBytes() throws Exception {
        JsonNode attachments = options().get("attachments");
        assertThat(names(attachments)).containsExactly("kinds", "perFileBytes", "totalBytes");
        List<String> kinds = new ArrayList<>();
        for (JsonNode kind : attachments.get("kinds")) {
            assertThat(names(kind)).containsExactly("extension", "contentType", "label");
            kinds.add(kind.get("extension").asString() + " " + kind.get("contentType").asString() + " " + kind.get("label").asString());
        }
        assertThat(kinds).containsExactly("pdf application/pdf PDF", "png image/png PNG image", "jpg image/jpeg JPEG image",
                "jpeg image/jpeg JPEG image", "tif image/tiff TIFF image", "tiff image/tiff TIFF image", "gif image/gif GIF image",
                "txt text/plain Text", "doc application/msword Word document",
                "docx application/vnd.openxmlformats-officedocument.wordprocessingml.document Word document");
        assertThat(attachments.get("perFileBytes").asLong()).isEqualTo(5_242_880L);
        assertThat(attachments.get("totalBytes").asLong()).isEqualTo(10_485_760L);
    }

    @Test
    void theHelpersTheServiceUsesReadTheSameLists() {
        assertThat(PasOptions.sendsContentType("application/pdf")).isTrue();
        assertThat(PasOptions.sendsContentType(" Image/PNG ")).isTrue();
        assertThat(PasOptions.sendsContentType("application/zip")).isFalse();
        assertThat(PasOptions.sendsContentType(null)).isFalse();
        assertThat(PasOptions.label(PasOptions.CURRENT.units(), "DY")).isEqualTo("Days");
        assertThat(PasOptions.label(PasOptions.CURRENT.placesOfService(), "99")).isNull();
        assertThat(PasOptions.label(PasOptions.CURRENT.placesOfService(), null)).isNull();
    }

    @Test
    void onlyAReadIsServed() throws Exception {
        mvc.perform(post("/api/pas/options")).andExpect(status().isMethodNotAllowed());
    }
}
