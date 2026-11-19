package com.thehiddenbrain.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.thehiddenbrain.interop.epa.pave.model.EnvironmentKind;
import com.thehiddenbrain.interop.epa.pave.model.EnvironmentRef;
import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import com.thehiddenbrain.interop.epa.pave.model.FieldError;
import com.thehiddenbrain.interop.epa.pave.model.TestResult;
import com.thehiddenbrain.interop.epa.pave.service.error.BadInputException;
import com.thehiddenbrain.interop.epa.pave.service.error.EndpointNotConfiguredException;
import com.thehiddenbrain.interop.epa.pave.service.error.NotFoundException;
import com.thehiddenbrain.interop.epa.pave.service.error.ProdWriteBlockedException;
import com.thehiddenbrain.interop.epa.pave.service.error.UnresolvedVariablesException;
import com.thehiddenbrain.interop.epa.pave.service.error.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    record Body(String memberId) {
    }

    @RestController
    static class ThrowingController {

        @GetMapping("/throw/{what}")
        public String raise(@PathVariable String what) {
            switch (what) {
                case "validation" -> throw new ValidationException(List.of(new FieldError("provider.npi",
                        "This is not a valid NPI: the check digit does not match."), new FieldError("member.memberId", "Enter a member ID.")));
                case "bad-input" -> throw new BadInputException("This file is not a Postman collection export.", "Export the collection as v2.1 and upload that file.");
                case "unresolved" -> throw new UnresolvedVariablesException(EpaOperation.CRD_ORDER_SIGN, List.of("baseUrl", "crdServiceId"),
                        "Onyx Sandbox", "{{baseUrl}}/cds-services/{{crdServiceId}}");
                case "unresolved-no-env" -> throw new UnresolvedVariablesException(EpaOperation.PAS_INQUIRE, List.of("baseUrl"), null, "{{baseUrl}}/Claim/$inquire");
                case "not-configured" -> throw new EndpointNotConfiguredException(EpaOperation.PAS_SUBMIT, "There is no url for PAS submit.");
                case "blocked" -> throw new ProdWriteBlockedException(EpaOperation.PAS_SUBMIT, new EnvironmentRef("Onyx Production", EnvironmentKind.PROD),
                        "PAS submit is blocked: \"Onyx Production\" is a production environment and writes are locked.",
                        "Unlock production writes on the Config tab, or switch to a sandbox environment.");
                case "not-found" -> throw new NotFoundException("There is no run with id run-404.");
                default -> throw new IllegalStateException("Placeholder-Surname born 1970-01-01 must never reach the browser or the log");
            }
        }

        @PostMapping("/echo")
        public Body echo(@RequestBody Body body) {
            return body;
        }

        @GetMapping("/typed")
        public String typed(@RequestParam String result, @RequestParam(required = false) Integer limit,
                            @RequestParam(required = false) String from) {
            Params.date("from", from);
            return Params.required("result", result, TestResult::parse).wire();
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test
    void aValidationFailureIs422WithEveryFieldNamed() throws Exception {
        mvc.perform(get("/throw/validation")).andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("The form has 2 problem(s). Nothing was sent."))
                .andExpect(jsonPath("$.fieldErrors.length()").value(2))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("provider.npi"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("This is not a valid NPI: the check digit does not match."))
                .andExpect(jsonPath("$.action").isNotEmpty());
    }

    @Test
    void anUnusableUploadIs422WithWhatToDoInstead() throws Exception {
        mvc.perform(get("/throw/bad-input")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("This file is not a Postman collection export."))
                .andExpect(jsonPath("$.action").value("Export the collection as v2.1 and upload that file."));
    }

    @Test
    void anUnresolvedPlaceholderNamesTheVariableAndTheEnvironmentBeforeAnythingIsSent() throws Exception {
        mvc.perform(get("/throw/unresolved")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("CONFIGURATION"))
                .andExpect(jsonPath("$.title").value("CRD order-sign cannot be sent: the placeholders {{baseUrl}}, {{crdServiceId}} have no value, "
                        + "and the active environment is \"Onyx Sandbox\". Nothing was sent."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("variables.baseUrl"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("variables.crdServiceId"));
        mvc.perform(get("/throw/unresolved-no-env")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("PAS inquire cannot be sent: the placeholder {{baseUrl}} has no value, "
                        + "and no environment is active. Nothing was sent."));
    }

    @Test
    void aMissingEndpointIs409() throws Exception {
        mvc.perform(get("/throw/not-configured")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.kind").value("CONFIGURATION"))
                .andExpect(jsonPath("$.title").value("There is no url for PAS submit. Nothing was sent."));
    }

    @Test
    void aBlockedProductionWriteIs403AndSaysNothingWasSent() throws Exception {
        mvc.perform(get("/throw/blocked")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.kind").value("BLOCKED"))
                .andExpect(jsonPath("$.title").value("PAS submit is blocked: \"Onyx Production\" is a production environment and writes are locked. Nothing was sent."))
                .andExpect(jsonPath("$.action").value("Unlock production writes on the Config tab, or switch to a sandbox environment."));
    }

    @Test
    void anUnknownIdIs404() throws Exception {
        mvc.perform(get("/throw/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("There is no run with id run-404."));
    }

    @Test
    void aNullOrMalformedBodyIs422() throws Exception {
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"));
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": \"S123456789\", oops"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The request body is missing or is not valid JSON. Nothing was sent."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("S123456789"))));
    }

    @Test
    void aBadQueryParameterIs422NamingTheParameter() throws Exception {
        mvc.perform(get("/typed").param("result", "pass")).andExpect(status().isOk()).andExpect(content().string("pass"));
        mvc.perform(get("/typed").param("result", "PASS").param("from", "2026-01-31")).andExpect(status().isOk());
        mvc.perform(get("/typed").param("result", "pass").param("limit", "many")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"));
        mvc.perform(get("/typed")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("result"));
        mvc.perform(get("/typed").param("result", "passed")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("result"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Result must be pass, fail or unverified."));
        mvc.perform(get("/typed").param("result", "pass").param("from", "31/01/2026")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Enter the date as YYYY-MM-DD, for example 2026-01-31."));
        mvc.perform(get("/typed").param("result", "pass").param("from", "2026-02-30")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    void wrongMethodAndWrongContentTypeKeepTheSharedShape() throws Exception {
        mvc.perform(post("/typed")).andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.kind").value("VALIDATION"));
        mvc.perform(post("/echo").contentType(MediaType.TEXT_PLAIN).content("x")).andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.kind").value("VALIDATION"));
    }

    @Test
    void anUnexpectedExceptionGivesAReferenceAndNoDetail() throws Exception {
        String body = mvc.perform(get("/throw/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.kind").value("INTERNAL"))
                .andExpect(jsonPath("$.reference").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Placeholder-Surname").doesNotContain("1970-01-01").doesNotContain("IllegalStateException")
                .doesNotContain("at com.");
    }

    @Test
    void theLogDescriptionCarriesTypesAndFramesButNoMessages() {
        Exception error = new IllegalStateException("Placeholder-Surname born 1970-01-01",
                new IllegalArgumentException("member S123456789"));
        String described = ApiExceptionHandler.describe(error);
        assertThat(described).contains("java.lang.IllegalStateException").contains("java.lang.IllegalArgumentException")
                .contains("at ").doesNotContain("Placeholder-Surname").doesNotContain("S123456789").doesNotContain("1970-01-01");
    }

    @Test
    void controllerArgumentNamesAreAvailableAtRuntime() throws Exception {
        // The behaviour the -parameters gate in build.gradle protects. Without the flag this request
        // fails with "Name for argument of type [java.lang.String] not specified".
        assertThat(ThrowingController.class.getMethod("raise", String.class).getParameters()[0].isNamePresent()).isTrue();
        assertThat(Map.of("checked", true)).isNotEmpty();
    }
}
