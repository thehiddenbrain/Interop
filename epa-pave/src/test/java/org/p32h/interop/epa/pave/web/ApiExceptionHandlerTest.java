package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.TestResult;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class ApiExceptionHandlerTest {

    record Body(String memberId, List<String> codes, Boolean flag) {
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
                case "unresolved-with-reason" -> throw new UnresolvedVariablesException(EpaOperation.PAS_SUBMIT, List.of("clientSecret"),
                        "Onyx Sandbox", "{{clientSecret}}", Map.of("clientSecret", "it points at the operating system variable PAVE_ONYX_SECRET, which is not set."));
                case "unresolved-token" -> throw new UnresolvedVariablesException(EpaOperation.SUBSCRIPTION_GET, List.of("id"), "Onyx Sandbox",
                        "{{baseUrl}}/Subscription/{id}", Map.of("id", "The url has a {id} part, which is filled from the identifier the "
                                + "call is for, and no identifier was given."), List.of("id"));
                case "unresolved-both" -> throw new UnresolvedVariablesException(EpaOperation.SUBSCRIPTION_GET, List.of("baseUrl", "id"),
                        "Onyx Sandbox", "{{baseUrl}}/Subscription/{id}", Map.of(), List.of("id"));
                case "store" -> throw new org.p32h.interop.epa.pave.service.config.ConfigStoreException(
                        "The configuration store could not write store.json. Nothing was changed.", new java.io.IOException("No space left on device: /srv/secret-path"));
                case "changed" -> throw new ConfigurationChangedException();
                case "relocked" -> throw ConfigurationChangedException.writesLockedAgain();
                case "redrawn" -> throw ConfigurationChangedException.sinceTheScreenWasDrawn();
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

        // Every parameter that is not plain text is taken as a String and converted with Params,
        // which is the rule for controllers: it behaves the same here and in the running application.
        @GetMapping("/typed")
        public String typed(@RequestParam(required = false) String result, @RequestParam(required = false) String limit,
                            @RequestParam(required = false) String from, @RequestParam(required = false) String protocol) {
            Params.date("from", from);
            Params.integer("limit", limit);
            Params.optional("protocol", protocol, Protocol::parse, "one of crd, dtr, pas, subscription, lookup or raw");
            return Params.required("result", result, TestResult::parse, "pass, fail or unverified").wire();
        }
    }

    private final MockMvc mvc = TestMvc.standalone(new ThrowingController());

    @Test
    void aValidationFailureIs422WithEveryFieldNamed() throws Exception {
        mvc.perform(get("/throw/validation")).andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("The form has 2 problems. Nothing was sent."))
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
    void aPartOfTheUrlFilledFromAnIdentifierIsNotCalledAPlaceholderToDefine() throws Exception {
        // Written {{id}}, it would send the tester to the environment to define something that cannot be defined there.
        mvc.perform(get("/throw/unresolved-token")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Subscription get cannot be sent: the url part {id} has no value, and the active "
                        + "environment is \"Onyx Sandbox\". Nothing was sent."))
                .andExpect(jsonPath("$.action").value("Give the identifier the url is waiting for, then send again."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("variables.id"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("No value for {id}: The url has a {id} part, which is filled from "
                        + "the identifier the call is for, and no identifier was given."));
        // One of each: each is written the way it is written in the url.
        mvc.perform(get("/throw/unresolved-both")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("Subscription get cannot be sent: {{baseUrl}}, {id} have no value, and the active "
                        + "environment is \"Onyx Sandbox\". Nothing was sent."))
                .andExpect(jsonPath("$.action").value("Type a value for each marked variable, or define it in the active environment on "
                        + "the Config tab."));
    }

    @Test
    void aConfigurationStoreThatCouldNotBeWrittenSaysWhichFileAndThatNothingChanged() throws Exception {
        String body = mvc.perform(get("/throw/store")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.kind").value("INTERNAL"))
                .andExpect(jsonPath("$.title").value("The configuration store could not write store.json. Nothing was changed."))
                .andExpect(jsonPath("$.action").value("Check the configuration store directory and its free space, then try again."))
                .andReturn().getResponse().getContentAsString();
        // The cause is not shown: its message is the operating system's and can name anything.
        assertThat(body).doesNotContain("secret-path").doesNotContain("No space left");
    }

    @Test
    void aVariableThatIsDefinedButHasNoValueSaysWhy() throws Exception {
        // "Add it to the environment" would be the wrong advice here: it is in the environment.
        mvc.perform(get("/throw/unresolved-with-reason")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("variables.clientSecret"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("No value for {{clientSecret}}: it points at the operating system "
                        + "variable PAVE_ONYX_SECRET, which is not set."));
    }

    @Test
    void aConfigurationChangeBetweenPreparingAndSendingIs409AndNothingWasSent() throws Exception {
        mvc.perform(get("/throw/changed")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.kind").value("CONFIGURATION"))
                .andExpect(jsonPath("$.title").value("The configuration changed while this request was being prepared. Nothing was sent."))
                .andExpect(jsonPath("$.action").value("Check which environment is active, then send again."));
    }

    @Test
    void aRefusalThatSaysWhatChangedIsShownInItsOwnWords() throws Exception {
        // Writes locked again between preparing and sending: the general words would send the tester to the environment.
        mvc.perform(get("/throw/relocked")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.kind").value("CONFIGURATION"))
                .andExpect(jsonPath("$.title").value("Production writes were locked again before this write was sent, so it was not sent."))
                .andExpect(jsonPath("$.action").value("Unlock them on the Config tab by typing the environment name, then send it again."));
        mvc.perform(get("/throw/redrawn")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("The configuration changed after this screen was loaded, so the environment shown may "
                        + "not be the one in force. Nothing was sent."))
                .andExpect(jsonPath("$.action").value("Reload to see the current environment, then send again."));
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
                .andExpect(jsonPath("$.fieldErrors[0].message")
                        .value("The value given here is not valid. It must be pass, fail or unverified."));
        // The message is this tool's own sentence. What a number parser or an enum lookup throws
        // names Java types and quotes the input, and is never passed on.
        mvc.perform(get("/typed").param("result", "pass").param("limit", "ten")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("limit"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The value given here is not valid. It must be a whole number."));
        String protocol = mvc.perform(get("/typed").param("result", "pass").param("protocol", "fax"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("protocol"))
                .andReturn().getResponse().getContentAsString();
        assertThat(protocol).doesNotContain("No enum constant").doesNotContain("org.p32h").doesNotContain("fax");
        mvc.perform(get("/typed").param("result", "pass").param("protocol", "CRD").param("limit", "10")).andExpect(status().isOk());
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
    @ExtendWith(OutputCaptureExtension.class)
    void anUnexpectedExceptionGivesAReferenceAndNoDetail(CapturedOutput output) throws Exception {
        String body = mvc.perform(get("/throw/boom")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.kind").value("INTERNAL"))
                .andExpect(jsonPath("$.reference").isNotEmpty())
                // The cause is named by type so it can be reported without the server log; its message is not given.
                .andExpect(jsonPath("$.cause").value("java.lang.IllegalStateException"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("Placeholder-Surname").doesNotContain("1970-01-01")
                .doesNotContain("at org.p32h").doesNotContain(".java:");
        // What was actually logged: the reference the browser was given, the exception type, and
        // nothing of the exception's own message, which here stands for member data.
        String reference = JsonSafe.text(JsonSafe.parse(body).node(), "reference");
        assertThat(output.getAll()).contains(reference).contains("java.lang.IllegalStateException")
                .doesNotContain("Placeholder-Surname").doesNotContain("1970-01-01");
    }

    @Test
    void aRequestBodyWithAFieldTheEndpointDoesNotHaveIsRefusedByName() throws Exception {
        // A name that does not match must not bind to nothing in silence.
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"S123456789\",\"membrId\":\"x\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("membrId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("This tool does not know the field \"membrId\". Check its spelling."));
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"S123456789\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aRequestBodyThatRepeatsAKeyIsRefusedByNameAndNeitherValueIsQuoted() throws Exception {
        // The last value would otherwise win in silence, and the request would say two things.
        String refused = mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"S123456789\",\"memberId\":\"S987654321\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("The request repeats the field \"memberId\" inside one object. Nothing was sent."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("memberId"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("This field is given more than once. Keep one, so the request says one thing."))
                .andReturn().getResponse().getContentAsString();
        assertThat(refused).doesNotContain("S123456789").doesNotContain("S987654321");
        // The same key in two different objects is not a repeat.
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"S123456789\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aBodyThatIsJsonOfTheWrongShapeNamesTheFieldAndNeverQuotesTheValue() throws Exception {
        String wrongList = mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\":\"S123456789\",\"codes\":\"Placeholder-Surname\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("codes"))
                .andReturn().getResponse().getContentAsString();
        assertThat(wrongList).contains("but one of its fields does not hold the kind of value").doesNotContain("Placeholder-Surname")
                .doesNotContain("not valid JSON");
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"flag\":\"maybe\"}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("flag"));
        // Text that is not JSON at all keeps the original sentence.
        mvc.perform(post("/echo").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": "))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The request body is missing or is not valid JSON. Nothing was sent."));
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

    @RestController
    static class EdgeController {

        @GetMapping("/upload/{limit}")
        public String upload(@PathVariable String limit) {
            Throwable cause = switch (limit) {
                case "file" -> new org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException("x", 26, 25);
                case "request" -> new org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException("x", 31L << 20, 30L << 20);
                case "header" -> new org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException("x", 513, 512);
                case "parts" -> new org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException("x", 50);
                default -> new IllegalStateException("x");
            };
            throw new org.springframework.web.multipart.MaxUploadSizeExceededException(-1, new IllegalStateException(cause));
        }

        @GetMapping("/query")
        public String query() {
            throw new org.apache.tomcat.util.http.InvalidParameterException("bad escape in S123456789");
        }

        @GetMapping("/body")
        public String body() {
            throw new org.springframework.http.converter.HttpMessageNotReadableException("I/O error",
                    new RequestShapeFilter.BodyTooLargeException(), null);
        }

        @GetMapping("/gone")
        public String gone() throws Exception {
            throw new org.springframework.web.context.request.async.AsyncRequestNotUsableException("gone");
        }
    }

    private final MockMvc edge = TestMvc.standalone(new EdgeController());

    @Test
    void aMethodTheRequestMadeUpIsNotRepeatedAndTheMethodsThatWorkAreNamed() throws Exception {
        mvc.perform(post("/typed")).andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("This endpoint does not accept POST."))
                .andExpect(jsonPath("$.action").value("Use GET."));
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf("S123456789"), "/typed"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.title").value("This endpoint does not accept that method."))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("S123456789");
    }

    @Test
    void eachUploadLimitIsNamedWithWhatWouldHelp() throws Exception {
        edge.perform(get("/upload/file")).andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("The uploaded file is larger than this tool accepts, which is 25MB for one file."))
                .andExpect(jsonPath("$.action").value("Upload a smaller export, or raise spring.servlet.multipart.max-file-size in application.yml."));
        edge.perform(get("/upload/request")).andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("The request is larger than this tool accepts, which is 30MB in all. Nothing was done."))
                .andExpect(jsonPath("$.action").value("Upload one file at a time, or raise spring.servlet.multipart.max-request-size in application.yml."));
        edge.perform(get("/upload/parts")).andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("The upload has more parts than this tool accepts."))
                .andExpect(jsonPath("$.action").value("Send the file with only the fields this upload takes. The limit is server.tomcat.max-part-count."));
        edge.perform(get("/upload/header")).andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("A part of the upload has a header larger than this tool accepts, which is usually a very "
                        + "long file name."))
                .andExpect(jsonPath("$.action").value("Rename the file to a shorter name and upload it again. The limit is "
                        + "server.tomcat.max-part-header-size."));
        edge.perform(get("/upload/other")).andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("The fields sent with the upload are larger than this tool accepts."))
                .andExpect(jsonPath("$.action").value("Send the file with only the fields this upload takes. The limit is "
                        + "server.tomcat.max-http-form-post-size."));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aQueryTheServerCannotReadIsA400AndNotAFaultOfThisTool(CapturedOutput output) throws Exception {
        String body = edge.perform(get("/query")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.reference").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("S123456789");
        assertThat(output.getAll()).doesNotContain("Unhandled").doesNotContain("S123456789");
    }

    @Test
    void aBodyPastTheLimitWhileItWasReadIsA413() throws Exception {
        edge.perform(get("/body")).andExpect(status().is(413))
                .andExpect(jsonPath("$.title").value("The request is larger than this tool accepts, which is 30MB in all. Nothing was done."));
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void aBrowserThatWentAwayIsNotLoggedAsAFault(CapturedOutput output) throws Exception {
        edge.perform(get("/gone")).andExpect(content().string(""));
        assertThat(output.getAll()).doesNotContain("Unhandled");
    }
}
