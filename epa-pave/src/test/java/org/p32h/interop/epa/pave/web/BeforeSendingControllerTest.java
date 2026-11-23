package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code POST /api/workbench/before-sending} as the running application reads it (D-321): every
 * field binds under its documented name, a misspelt one is a 422 that names it, a step that is not
 * one is refused at its field, and with no collection behind the step the answer says nothing and is
 * still a 200, so a page that asks with nothing configured never sees an error.
 */
class BeforeSendingControllerTest {

    private final MockMvc mvc = TestMvc.standalone(new BeforeSendingController(new FakeGateway()));

    private String answer(String body, int status) throws Exception {
        return mvc.perform(post("/api/workbench/before-sending").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
    }

    @Test
    void withNoCollectionTheAnswerSaysNothing() throws Exception {
        String body = "{\"operation\":\"CRD_ORDER_SIGN\",\"request\":{\"collectionId\":\"c\",\"requestId\":\"r\"},\"variables\":{},"
                + "\"diagnosisCodes\":[{\"code\":\"G47.33\"}],\"servicingProvider\":{\"npi\":\"9999999995\"}}";
        assertThat(answer(body, 200)).isEqualTo("{\"sampleDiagnosis\":false,\"sampleServicing\":false}");
        assertThat(answer("{\"operation\":\"PAS_SUBMIT\"}", 200)).isEqualTo("{\"sampleDiagnosis\":false,\"sampleServicing\":false}");
    }

    @Test
    void aMisspeltPropertyAndAStepThatIsNotOneAreRefusedAtTheirFields() throws Exception {
        assertThat(answer("{\"operation\":\"CRD_ORDER_SIGN\",\"diagnosisCode\":[]}", 422)).contains("diagnosisCode");
        assertThat(answer("{\"operation\":\"RAW\"}", 422)).contains("\"operation\"");
        assertThat(answer("{}", 422)).contains("\"operation\"");
    }
}
