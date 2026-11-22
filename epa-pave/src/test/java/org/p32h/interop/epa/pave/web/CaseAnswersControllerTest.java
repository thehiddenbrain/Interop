package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /api/dtr/case-answers} as the running application reads it: every field binds under
 * its documented name, a misspelt one is a 422 that names it (at the top and inside the member, the
 * provider and a code), and the answer has the shape the page reads.
 */
class CaseAnswersControllerTest {

    private static final String QUESTIONNAIRE = "{\\\"resourceType\\\":\\\"Questionnaire\\\",\\\"item\\\":["
            + "{\\\"linkId\\\":\\\"dob\\\",\\\"text\\\":\\\"Date of birth\\\",\\\"type\\\":\\\"date\\\"},"
            + "{\\\"linkId\\\":\\\"npi\\\",\\\"text\\\":\\\"Ordering provider NPI\\\",\\\"type\\\":\\\"string\\\"},"
            + "{\\\"linkId\\\":\\\"codes\\\",\\\"text\\\":\\\"CPT codes\\\",\\\"type\\\":\\\"string\\\",\\\"repeats\\\":true},"
            + "{\\\"linkId\\\":\\\"pos\\\",\\\"text\\\":\\\"Place of service\\\",\\\"type\\\":\\\"string\\\"},"
            + "{\\\"linkId\\\":\\\"los\\\",\\\"text\\\":\\\"Level of service\\\",\\\"type\\\":\\\"string\\\"},"
            + "{\\\"linkId\\\":\\\"start\\\",\\\"text\\\":\\\"When did it start?\\\",\\\"type\\\":\\\"date\\\"}]}";

    private static final String BODY = "{\"questionnaire\":\"" + QUESTIONNAIRE + "\","
            + "\"member\":{\"memberId\":\"S123456789\",\"dateOfBirth\":\"1950-01-31\"},"
            + "\"provider\":{\"npi\":\"1234567893\"},"
            + "\"procedureCodes\":[{\"code\":\"95810\",\"quantity\":1,\"unit\":\"VS\"}],"
            + "\"dateOfService\":\"2026-03-20\",\"placeOfService\":\"11\",\"levelOfService\":\"E\"}";

    private final MockMvc mvc = TestMvc.standalone(new CaseAnswersController());

    @Test
    void theAnswersComeBackInTheShapeThePageReads() throws Exception {
        String body = mvc.perform(post("/api/dtr/case-answers").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode answers = JsonSafe.parse(body).node().get("answers");

        assertThat(answers).hasSize(6);
        assertThat(answers.get(0).toString()).isEqualTo(
                "{\"linkId\":\"dob\",\"values\":[\"1950-01-31\"],\"field\":\"member.dateOfBirth\",\"mark\":\"from the case\"}");
        assertThat(answers.get(1).get("values").toString()).isEqualTo("[\"1234567893\"]");
        assertThat(answers.get(2).get("values").toString()).isEqualTo("[\"95810\"]");
        assertThat(answers.get(3).get("values").toString()).isEqualTo("[\"Office (11)\"]");
        assertThat(answers.get(4).get("values").toString()).isEqualTo("[\"Elective (E)\"]");
        assertThat(answers.get(5).toString()).isEqualTo(
                "{\"linkId\":\"start\",\"values\":[\"2026-03-20\"],\"field\":\"dateOfService\",\"mark\":\"default, change if needed\"}");
    }

    @Test
    void nothingAnsweredIsAnEmptyList() throws Exception {
        String body = "{\"questionnaire\":\"{\\\"resourceType\\\":\\\"Questionnaire\\\",\\\"item\\\":[{\\\"linkId\\\":\\\"a\\\","
                + "\\\"text\\\":\\\"Comments\\\",\\\"type\\\":\\\"text\\\"}]}\"}";
        mvc.perform(post("/api/dtr/case-answers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.answers").isArray()).andExpect(jsonPath("$.answers").isEmpty());
    }

    @Test
    void aMisspeltFieldIsRefusedNamingIt() throws Exception {
        refusedNaming(BODY.replace("\"dateOfService\"", "\"dateOfServise\""), "dateOfServise");
        refusedNaming(BODY.replace("\"questionnaire\"", "\"questionaire\""), "questionaire");
        refusedNaming(BODY.replace("\"dateOfBirth\"", "\"dateOfBrith\""), "dateOfBrith");
        refusedNaming(BODY.replace("\"npi\"", "\"npii\""), "npii");
        refusedNaming(BODY.replace("\"quantity\"", "\"quantiy\""), "quantiy");
    }

    @Test
    void aQuestionnaireThatCannotBeReadAndADateThatIsNotOneAreRefusedAtTheirField() throws Exception {
        refusedNaming("{\"questionnaire\":\"not json\"}", "questionnaire");
        refusedNaming("{\"member\":{\"memberId\":\"S123456789\"}}", "questionnaire");
        refusedNaming(BODY.replace("2026-03-20", "20/03/2026"), "dateOfService");
    }

    private void refusedNaming(String body, String field) throws Exception {
        String answer = mvc.perform(post("/api/dtr/case-answers").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value(field)).andReturn().getResponse().getContentAsString();
        // The refusal never repeats the case: no member ID and no date of birth.
        assertThat(answer).doesNotContain("S123456789", "1950-01-31");
    }
}
