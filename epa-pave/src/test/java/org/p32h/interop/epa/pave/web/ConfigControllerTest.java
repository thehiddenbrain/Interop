package org.p32h.interop.epa.pave.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.RequestRef;
import org.p32h.interop.epa.pave.testsupport.TestMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Every Config endpoint for its status and its shape: the happy path, a validation failure and a not-found. */
class ConfigControllerTest {

    private static final String PRODUCTION = "Onyx Production";

    @TempDir
    Path directory;

    private static MockMvc mvc(ConfigFixture fixture) {
        return TestMvc.standalone(new ConfigController(fixture.service));
    }

    private static MockMultipartFile file(String resource) {
        return new MockMultipartFile("file", resource, "application/json", PostmanFiles.bytes(resource));
    }

    private static MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "application/json", content.getBytes(StandardCharsets.UTF_8));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String body(MockMvc mvc, RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    /** The production environment active, the floor open and the vendor collection uploaded. */
    private ConfigFixture production() {
        ConfigFixture fixture = ConfigFixture.on(directory).allowProdWrites().build();
        fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    private ConfigFixture sandboxWithVendorCollection() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    // ------------------------------------------------------------------------------------------
    // POST /api/config/environments
    // ------------------------------------------------------------------------------------------

    @Test
    void uploadingAnEnvironmentStoresItAndAnswersWithItsSecretsMaskedAndAWarningForEach() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);

        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.SANDBOX_ENVIRONMENT)).param("kind", "sandbox"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.name").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.environment.kind").value("sandbox"))
                .andExpect(jsonPath("$.environment.active").value(false))
                .andExpect(jsonPath("$.environment.originalFileName").value(PostmanFiles.SANDBOX_ENVIRONMENT))
                .andExpect(jsonPath("$.environment.variables", hasSize(12)))
                .andExpect(jsonPath("$.environment.variables[7].name").value("clientSecret"))
                .andExpect(jsonPath("$.environment.variables[7].secret").value(true))
                .andExpect(jsonPath("$.environment.variables[7].set").value(true))
                .andExpect(jsonPath("$.environment.variables[7].display").value("[secret clientSecret]"))
                .andExpect(jsonPath("$.environment.variables[6].display").value("pave-sandbox-client"))
                // A blank variable has no display at all. It is left out, not written as null.
                .andExpect(jsonPath("$.environment.variables[10].name").value("callbackUrl"))
                .andExpect(jsonPath("$.environment.variables[10].display").doesNotExist())
                .andExpect(jsonPath("$.replaced").value(false))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.warnings", hasSize(2)))
                .andExpect(jsonPath("$.warnings[0]", startsWith("The variable \"clientSecret\" holds a secret in plain text, and it now sits in the file ")));

        assertThat(fixture.gateway.epoch()).isGreaterThan(0);
        // The kind is read in any capitals, and an upload of the same name replaces.
        String again = body(mvc, multipart("/api/config/environments").file(file(PostmanFiles.SANDBOX_ENVIRONMENT)).param("kind", "SANDBOX"));
        assertThat(again).contains("\"replaced\":true").doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value");
    }

    @Test
    void anUploadWithoutAKindOrWithOneThatIsNotAKindIsA422ThatNamesTheField() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);

        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.SANDBOX_ENVIRONMENT)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("kind"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("State what kind of environment this is: sandbox, uat or prod. It is never guessed."));
        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.SANDBOX_ENVIRONMENT)).param("kind", "  "))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("kind"));
        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.SANDBOX_ENVIRONMENT)).param("kind", "staging"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("kind"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The value given here is not valid. It must be sandbox, uat or prod."));
        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.SANDBOX_ENVIRONMENT)).param("kind", "sandbox")
                        .param("acknowledgeNotProd", "maybe"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("acknowledgeNotProd"));
        // The file part itself is required.
        mvc.perform(multipart("/api/config/environments").param("kind", "sandbox"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("file"));

        assertThat(fixture.service.environments().environments()).isEmpty();
    }

    @Test
    void aFileThatIsNotAnEnvironmentExportIsA422ThatSaysWhatItIsAndWhatToUploadInstead() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);

        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.VENDOR_COLLECTION)).param("kind", "sandbox"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("This file is a Postman collection, not an environment."))
                .andExpect(jsonPath("$.action", startsWith("Upload it under Collections.")));
        mvc.perform(multipart("/api/config/environments").file(file("page.html", "<html>placeholder-secret-in-a-page</html>")).param("kind", "uat"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title", startsWith("This file is not a Postman environment export: it is not valid JSON")));
        String sandbox = PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT);
        mvc.perform(multipart("/api/config/environments").file(file("cut.json", sandbox.substring(0, sandbox.length() / 2))).param("kind", "uat"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title", startsWith("This file is not complete: the JSON stops part way through")));
        mvc.perform(multipart("/api/config/environments").file(file("empty.json", "")).param("kind", "uat"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.title").value("The uploaded file is empty."));
        // Nothing of a refused file is echoed back.
        assertThat(body(mvc, multipart("/api/config/environments").file(file("page.html", "<html>placeholder-secret-in-a-page</html>"))
                .param("kind", "uat"))).doesNotContain("placeholder-secret-in-a-page");
        assertThat(fixture.service.environments().environments()).isEmpty();
    }

    @Test
    void anEnvironmentThatReadsAsProductionIsRefusedAsASandboxUnlessTheUploadAcknowledgesIt() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);

        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.PRODUCTION_ENVIRONMENT)).param("kind", "sandbox"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("This environment looks like production and was declared as sandbox. It was not stored."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("acknowledgeNotProd"))
                .andExpect(jsonPath("$.fieldErrors[0].message", containsString("its name is \"Onyx Production\"")));
        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.PRODUCTION_ENVIRONMENT)).param("kind", "sandbox")
                        .param("acknowledgeNotProd", "false"))
                .andExpect(status().isUnprocessableContent());
        assertThat(fixture.service.environments().environments()).isEmpty();

        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.PRODUCTION_ENVIRONMENT)).param("kind", "sandbox")
                        .param("acknowledgeNotProd", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.kind").value("sandbox"))
                .andExpect(jsonPath("$.environment.acknowledgedNotProd").value(true))
                .andExpect(jsonPath("$.environment.productionSigns[0]").value("its name is \"Onyx Production\""));
        // Declared as production it needs no acknowledgement. Replacing what is now stored as a sandbox asks nothing either.
        mvc.perform(multipart("/api/config/environments").file(file(PostmanFiles.PRODUCTION_ENVIRONMENT)).param("kind", "prod"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.kind").value("prod"))
                .andExpect(jsonPath("$.environment.acknowledgedNotProd").value(false))
                .andExpect(jsonPath("$.replaced").value(true))
                // Its secret points at the operating system, so nothing is in the file to warn about.
                .andExpect(jsonPath("$.warnings", hasSize(0)))
                .andExpect(jsonPath("$.environment.variables[4].display").value("{{env:PAVE_ONYX_CLIENT_SECRET}}"))
                .andExpect(jsonPath("$.environment.variables[4].readsFrom").value("the operating system variable PAVE_ONYX_CLIENT_SECRET"));
    }

    // ------------------------------------------------------------------------------------------
    // GET /api/config/environments, activate and delete
    // ------------------------------------------------------------------------------------------

    @Test
    void theEnvironmentListHasTheKindTheActiveFlagAndEachVariableMaskedAndMarkedAsUsedOrNot() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);
        mvc.perform(get("/api/config/environments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.environments", hasSize(0)))
                .andExpect(jsonPath("$.active").doesNotExist())
                .andExpect(jsonPath("$.problems", hasSize(0)));

        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        mvc.perform(get("/api/config/environments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.environments", hasSize(2)))
                .andExpect(jsonPath("$.environments[0].name").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.environments[0].kind").value("sandbox"))
                .andExpect(jsonPath("$.environments[0].active").value(true))
                .andExpect(jsonPath("$.environments[1].kind").value("uat"))
                .andExpect(jsonPath("$.environments[1].active").value(false))
                .andExpect(jsonPath("$.environments[0].id", startsWith("onyx-sandbox-")))
                .andExpect(jsonPath("$.environments[0].variables[0].name").value("baseUrl"))
                .andExpect(jsonPath("$.environments[0].variables[0].used").value(true))
                .andExpect(jsonPath("$.environments[0].variables[0].inForce").value(true))
                .andExpect(jsonPath("$.environments[0].variables[0].usedBy[0]", containsString("$questionnaire-package Happy path")))
                // The second definition of baseUrl is switched off in the file.
                .andExpect(jsonPath("$.environments[0].variables[1].enabled").value(false))
                .andExpect(jsonPath("$.environments[0].variables[1].used").value(false))
                .andExpect(jsonPath("$.environments[0].variables[6].name").value("clientId"))
                .andExpect(jsonPath("$.environments[0].variables[6].used").value(false))
                .andExpect(jsonPath("$.environments[0].variables[8].name").value("tenantCode"))
                .andExpect(jsonPath("$.environments[0].variables[8].secret").value(true))
                .andExpect(jsonPath("$.environments[0].variables[8].display").value("[secret tenantCode]"));
        assertThat(body(mvc, get("/api/config/environments"))).doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value");
    }

    @Test
    void activatingSwitchesTheEnvironmentAtRuntimeAndAnUnknownNameIsA404() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        String uatId = fixture.environmentId("Onyx UAT");
        MockMvc mvc = mvc(fixture);

        // A name with a space in it, as the path carries it.
        mvc.perform(post("/api/config/environments/{name}/activate", "Onyx Sandbox")).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.name").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.environment.kind").value("sandbox"))
                .andExpect(jsonPath("$.environment.active").value(true))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.writeAccess.production").value(false))
                .andExpect(jsonPath("$.writeAccess.writesAllowed").value(true));
        assertThat(fixture.gateway.activeEnvironment().name()).isEqualTo("Onyx Sandbox");
        // By the id, which is safe in a path whatever the name holds.
        mvc.perform(post("/api/config/environments/{name}/activate", uatId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.name").value("Onyx UAT"));
        assertThat(fixture.gateway.activeEnvironment().kind()).isEqualTo(EnvironmentKind.UAT);

        mvc.perform(post("/api/config/environments/{name}/activate", "No such environment")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("The environment is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read."))
                .andExpect(jsonPath("$.action").value("Read the list of environments again and choose from it as it is now."));
        assertThat(fixture.gateway.activeEnvironment().name()).isEqualTo("Onyx UAT");
    }

    @Test
    void removingAnEnvironmentAnswersWithWhatWasRemovedAndAnUnknownNameIsA404() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        MockMvc mvc = mvc(fixture);

        mvc.perform(delete("/api/config/environments/{name}", "Onyx Sandbox")).andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.wasActive").value(true))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.warnings", hasSize(0)));
        assertThat(fixture.gateway.activeEnvironment().active()).isFalse();
        mvc.perform(delete("/api/config/environments/{name}", "Onyx Sandbox")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    @Test
    void uploadingACollectionAnswersWithEveryRequestAndItsDetectedMapping() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);

        mvc.perform(multipart("/api/config/collections").file(file(PostmanFiles.VENDOR_COLLECTION))).andExpect(status().isOk())
                .andExpect(jsonPath("$.collection.name").value("Onyx Sandbox ePA"))
                .andExpect(jsonPath("$.collection.id", startsWith("onyx-sandbox-epa-")))
                .andExpect(jsonPath("$.collection.requests", hasSize(13)))
                .andExpect(jsonPath("$.collection.requests[3].name").value("submit Happy Path"))
                .andExpect(jsonPath("$.collection.requests[3].folderPath[0]").value("PAS"))
                .andExpect(jsonPath("$.collection.requests[3].method").value("POST"))
                .andExpect(jsonPath("$.collection.requests[3].urlTemplate").value("{{baseUrl}}/Claim/$submit"))
                .andExpect(jsonPath("$.collection.requests[3].detectedOperation").value("PAS_SUBMIT"))
                .andExpect(jsonPath("$.collection.requests[3].detectionBasis").value("The name contains \"submit\", and the url has a $submit segment."))
                .andExpect(jsonPath("$.collection.requests[3].correctedOperation").doesNotExist())
                .andExpect(jsonPath("$.collection.requests[3].effectiveOperation").value("PAS_SUBMIT"))
                .andExpect(jsonPath("$.collection.requests[3].effectiveLabel").value("PAS submit"))
                .andExpect(jsonPath("$.collection.requests[3].scripted").value(true))
                .andExpect(jsonPath("$.collection.requests[3].hasBody").value(true))
                .andExpect(jsonPath("$.collection.requests[3].exampleCount").value(0))
                .andExpect(jsonPath("$.collection.requests[3].guardedAsWrite").value(true))
                .andExpect(jsonPath("$.collection.requests[12].detectedOperation").value("SUBSCRIPTION_NOTIFICATION"))
                .andExpect(jsonPath("$.replaced").value(false))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()));

        // A collection with a secret variable answers with a warning and never with the value.
        String nested = body(mvc, multipart("/api/config/collections").file(file(PostmanFiles.NESTED_COLLECTION)));
        assertThat(nested).contains("The variable \\\"collectionKey\\\" holds a secret in plain text").contains("[secret collectionKey]")
                .doesNotContain("placeholder-collection-key");
    }

    @Test
    void aFileThatIsNotAV21CollectionIsA422ThatSaysWhatItIs() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        MockMvc mvc = mvc(fixture);

        mvc.perform(multipart("/api/config/collections").file(file(PostmanFiles.V1_COLLECTION))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.title").value("This is a Postman collection in the old v1 format, which this tool does not read."))
                .andExpect(jsonPath("$.action", containsString("Collection v2.1")));
        mvc.perform(multipart("/api/config/collections").file(file(PostmanFiles.V2_0_COLLECTION))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("This is a Postman collection in format v2.0.0. This tool reads format v2.1 only."));
        mvc.perform(multipart("/api/config/collections").file(file(PostmanFiles.TRUNCATED_COLLECTION))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title", startsWith("This file is not complete")));
        mvc.perform(multipart("/api/config/collections").file(file(PostmanFiles.SANDBOX_ENVIRONMENT))).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("This file is a Postman environment, not a collection."))
                .andExpect(jsonPath("$.action", startsWith("Upload it under Environments.")));
        mvc.perform(multipart("/api/config/collections")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("file"));
        // Posted as something other than an upload.
        mvc.perform(json(post("/api/config/collections"), "{}")).andExpect(status().isUnprocessableContent());

        assertThat(fixture.service.collections().collections()).isEmpty();
    }

    @Test
    void theCollectionListHasEachRequestWithItsMappingAndItsFlags() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        fixture.collection(PostmanFiles.NESTED_COLLECTION);
        MockMvc mvc = mvc(fixture);

        mvc.perform(get("/api/config/collections")).andExpect(status().isOk())
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.collections", hasSize(2)))
                .andExpect(jsonPath("$.collections[0].name").value("Onyx Sandbox ePA"))
                .andExpect(jsonPath("$.collections[0].requests", hasSize(13)))
                .andExpect(jsonPath("$.collections[0].requests[5].id", startsWith("r-")))
                .andExpect(jsonPath("$.collections[0].requests[5].name").value("order-sign Happy Path"))
                .andExpect(jsonPath("$.collections[0].requests[5].folderPath[0]").value("CRD"))
                .andExpect(jsonPath("$.collections[0].requests[5].method").value("POST"))
                .andExpect(jsonPath("$.collections[0].requests[5].urlTemplate").value("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}"))
                .andExpect(jsonPath("$.collections[0].requests[5].detectedOperation").value("CRD_ORDER_SIGN"))
                .andExpect(jsonPath("$.collections[0].requests[5].detectionBasis", startsWith("The name contains \"order-sign\"")))
                .andExpect(jsonPath("$.collections[0].requests[5].effectiveOperation").value("CRD_ORDER_SIGN"))
                .andExpect(jsonPath("$.collections[0].requests[5].scripted").value(false))
                .andExpect(jsonPath("$.collections[0].requests[5].hasBody").value(true))
                .andExpect(jsonPath("$.collections[0].requests[5].exampleCount").value(1))
                .andExpect(jsonPath("$.collections[0].requests[7].hasBody").value(false))
                .andExpect(jsonPath("$.collections[1].requests[0].name").value("deep order-select"))
                .andExpect(jsonPath("$.collections[1].requests[0].folderPath", hasSize(3)))
                .andExpect(jsonPath("$.collections[1].requests[0].scripted").value(true))
                .andExpect(jsonPath("$.collections[1].requests[0].exampleCount").value(2))
                // Unmapped is not an error. The request is listed, and can be sent raw.
                .andExpect(jsonPath("$.collections[1].requests[2].name").value("duplicate"))
                .andExpect(jsonPath("$.collections[1].requests[2].effectiveOperation").value("UNMAPPED"))
                .andExpect(jsonPath("$.collections[1].requests[2].effectiveLabel").value("Unmapped request"));
    }

    @Test
    void oneRequestInFullHasItsHeadersItsRawBodyAndItsSavedExamplesAndAnUnknownIdIsA404() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        RequestRef orderSign = fixture.ref(0, "order-sign Happy Path");
        MockMvc mvc = mvc(fixture);

        mvc.perform(get("/api/config/collections/{id}/requests/{requestId}", orderSign.collectionId(), orderSign.requestId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionId").value(orderSign.collectionId()))
                .andExpect(jsonPath("$.collectionName").value("Onyx Sandbox ePA"))
                .andExpect(jsonPath("$.request.id").value(orderSign.requestId()))
                .andExpect(jsonPath("$.request.name").value("order-sign Happy Path"))
                .andExpect(jsonPath("$.headers", hasSize(3)))
                .andExpect(jsonPath("$.headers[0].name").value("Content-Type"))
                .andExpect(jsonPath("$.headers[0].value").value("application/json"))
                .andExpect(jsonPath("$.headers[0].disabled").value(false))
                .andExpect(jsonPath("$.headers[2].name").value("X-Request-Trace"))
                .andExpect(jsonPath("$.headers[2].disabled").value(true))
                .andExpect(jsonPath("$.bodyMode").value("raw"))
                // Verbatim, placeholders and line breaks included.
                .andExpect(jsonPath("$.body", startsWith("{\n  \"hook\": \"order-sign\",\n  \"hookInstance\": \"{{$guid}}\",")))
                .andExpect(jsonPath("$.examples", hasSize(1)))
                .andExpect(jsonPath("$.examples[0].name").value("Authorization required"))
                .andExpect(jsonPath("$.examples[0].status").value("OK"))
                .andExpect(jsonPath("$.examples[0].code").value(200))
                .andExpect(jsonPath("$.examples[0].headers[0].name").value("Content-Type"))
                .andExpect(jsonPath("$.examples[0].body", containsString("Prior authorization is required")));

        mvc.perform(get("/api/config/collections/{id}/requests/{requestId}", "no-such-collection", orderSign.requestId()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("The collection is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read."));
        mvc.perform(get("/api/config/collections/{id}/requests/{requestId}", orderSign.collectionId(), "r-000000000000"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("That request of the collection \"Onyx Sandbox ePA\" is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read."));
    }

    @Test
    void aMappingCorrectionIsStoredAndIsStillThereAfterARestart() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        RequestRef inquiry = fixture.ref(0, "inquiry Happy Path");
        MockMvc mvc = mvc(fixture);

        mvc.perform(json(put("/api/config/collections/{id}/mapping", inquiry.collectionId()),
                        "{\"requestId\": \"" + inquiry.requestId() + "\", \"operation\": \"UNMAPPED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectionId").value(inquiry.collectionId()))
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.request.id").value(inquiry.requestId()))
                .andExpect(jsonPath("$.request.detectedOperation").value("PAS_INQUIRE"))
                .andExpect(jsonPath("$.request.correctedOperation").value("UNMAPPED"))
                .andExpect(jsonPath("$.request.effectiveOperation").value("UNMAPPED"))
                .andExpect(jsonPath("$.notes[0]").value("The request is now not mapped to any operation, so it is sent only as written, "
                        + "from the Config tab. Detection proposed PAS inquire."));
        mvc.perform(get("/api/config/collections")).andExpect(jsonPath("$.collections[0].requests[4].correctedOperation").value("UNMAPPED"));

        // A second store on the same directory is what a restart is.
        MockMvc restarted = mvc(ConfigFixture.mock(directory));
        restarted.perform(get("/api/config/collections")).andExpect(status().isOk())
                .andExpect(jsonPath("$.collections[0].requests[4].name").value("inquiry Happy Path"))
                .andExpect(jsonPath("$.collections[0].requests[4].correctedOperation").value("UNMAPPED"))
                .andExpect(jsonPath("$.collections[0].requests[4].effectiveOperation").value("UNMAPPED"));

        // The operation is read by its name in any case, and choosing what detection proposed removes the correction.
        restarted.perform(json(put("/api/config/collections/{id}/mapping", inquiry.collectionId()),
                        "{\"requestId\": \"" + inquiry.requestId() + "\", \"operation\": \"pas-inquire\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(true))
                .andExpect(jsonPath("$.request.correctedOperation").doesNotExist())
                .andExpect(jsonPath("$.request.effectiveOperation").value("PAS_INQUIRE"));
    }

    @Test
    void aMappingCorrectionThatCannotBeUsedIsA422AndAnUnknownIdIsA404() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        RequestRef inquiry = fixture.ref(0, "inquiry Happy Path");
        MockMvc mvc = mvc(fixture);
        String url = "/api/config/collections/" + inquiry.collectionId() + "/mapping";
        long epoch = fixture.gateway.epoch();

        mvc.perform(json(put(url), "{}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(2)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("requestId"))
                .andExpect(jsonPath("$.fieldErrors[1].field").value("operation"));
        mvc.perform(json(put(url), "{\"requestId\": \"" + inquiry.requestId() + "\", \"operation\": \"PAS_APPROVE\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("operation"))
                .andExpect(jsonPath("$.fieldErrors[0].message", startsWith("The value given here is not valid. It must be one of CRD_ORDER_SIGN, ")));
        // A number is never taken for a position in the list of operations.
        mvc.perform(json(put(url), "{\"requestId\": \"" + inquiry.requestId() + "\", \"operation\": 6}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("operation"));
        // No collection request can stand for a step that is completed inside the tool.
        mvc.perform(json(put(url), "{\"requestId\": \"" + inquiry.requestId() + "\", \"operation\": \"DTR_RESPONSE\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("operation"))
                .andExpect(jsonPath("$.fieldErrors[0].message", startsWith("DTR questionnaire response is a step completed inside this tool.")));
        // A misspelt property is named, not dropped.
        mvc.perform(json(put(url), "{\"requestID\": \"" + inquiry.requestId() + "\", \"operation\": \"UNMAPPED\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The request has a field this tool does not know. Nothing was changed."))
                .andExpect(jsonPath("$.action").value("Correct the marked field and try again."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("requestID"));
        mvc.perform(json(put(url), "not json")).andExpect(status().isUnprocessableContent());
        mvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableContent());

        mvc.perform(json(put("/api/config/collections/no-such-collection/mapping"),
                        "{\"requestId\": \"" + inquiry.requestId() + "\", \"operation\": \"UNMAPPED\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.kind").value("NOT_FOUND"));
        mvc.perform(json(put(url), "{\"requestId\": \"r-000000000000\", \"operation\": \"UNMAPPED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("That request of the collection \"Onyx Sandbox ePA\" is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read."));
        // None of the refused corrections changed anything.
        assertThat(fixture.gateway.epoch()).isEqualTo(epoch);
    }

    @Test
    void removingACollectionAnswersWithWhatWasRemovedAndAnUnknownIdIsA404() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        String id = fixture.ref(0, "inquiry Happy Path").collectionId();
        MockMvc mvc = mvc(fixture);

        mvc.perform(delete("/api/config/collections/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.removed").value("Onyx Sandbox ePA"))
                .andExpect(jsonPath("$.wasActive").value(false))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()));
        mvc.perform(get("/api/config/collections")).andExpect(jsonPath("$.collections", hasSize(0)));
        mvc.perform(delete("/api/config/collections/{id}", id)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"))
                .andExpect(jsonPath("$.title").value("The collection is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read."))
                .andExpect(jsonPath("$.action").value("Read the list of collections again and choose from it as it is now."));
    }

    // ------------------------------------------------------------------------------------------
    // POST /api/config/writes
    // ------------------------------------------------------------------------------------------

    @Test
    void unlockingStoresTheUnlockInThisBrowserSessionAndLockingClearsIt() throws Exception {
        ConfigFixture fixture = production();
        MockMvc mvc = mvc(fixture);
        MockHttpSession session = new MockHttpSession();
        MockHttpSession otherSession = new MockHttpSession();
        mvc.perform(get("/api/config/effective").session(session)).andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));

        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Production\"}").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.production").value(true))
                .andExpect(jsonPath("$.writesAllowed").value(true))
                .andExpect(jsonPath("$.floorAllows").value(true))
                .andExpect(jsonPath("$.reason").value("Production writes are unlocked for this session on \"Onyx Production\". Any change to the "
                        + "configuration locks them again. Locking them again, from this or any other browser, ends the unlock of every session."));

        // The unlock is in the session, where the other controllers read it, and the gateway honours it.
        MockHttpServletRequest sameBrowser = new MockHttpServletRequest();
        sameBrowser.setSession(session);
        assertThat(SessionUnlock.from(sameBrowser)).isNotNull();
        assertThat(SessionUnlock.from(sameBrowser).environmentName()).isEqualTo(PRODUCTION);
        mvc.perform(get("/api/config/effective").session(session)).andExpect(jsonPath("$.writeAccess.writesAllowed").value(true));
        // Another browser session is still locked.
        mvc.perform(get("/api/config/effective").session(otherSession)).andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));
        mvc.perform(get("/api/config/effective")).andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));

        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"lock\"}").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.production").value(true))
                .andExpect(jsonPath("$.writesAllowed").value(false))
                .andExpect(jsonPath("$.reason").value("\"Onyx Production\" is production and writes are locked for this session. Unlock them "
                        + "by typing the environment name."));
        mvc.perform(get("/api/config/effective").session(session)).andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));
        // Locking when nothing is unlocked is not an error.
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"LOCK\"}").session(otherSession)).andExpect(status().isOk())
                .andExpect(jsonPath("$.writesAllowed").value(false));
    }

    @Test
    void switchingEnvironmentDropsTheSessionsUnlockAndSwitchingBackDoesNotRestoreIt() throws Exception {
        ConfigFixture fixture = production();
        fixture.environment(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);
        MockMvc mvc = mvc(fixture);
        MockHttpSession session = new MockHttpSession();
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Production\"}").session(session))
                .andExpect(jsonPath("$.writesAllowed").value(true));

        mvc.perform(post("/api/config/environments/{name}/activate", "Onyx UAT").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.writeAccess.production").value(false));
        mvc.perform(post("/api/config/environments/{name}/activate", PRODUCTION).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.kind").value("prod"))
                .andExpect(jsonPath("$.writeAccess.production").value(true))
                .andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));
        mvc.perform(get("/api/config/effective").session(session)).andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));
    }

    @Test
    void anUnlockIsRefusedWith403WhenAnOwnerHasLockedTheInstallation() throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        MockMvc mvc = mvc(fixture);
        MockHttpSession session = new MockHttpSession();

        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Production\"}").session(session))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.kind").value("BLOCKED"))
                .andExpect(jsonPath("$.title", startsWith("Production writes cannot be unlocked: an owner has locked this installation against them.")))
                // An unlock sends nothing, so its refusal says what did not happen to it.
                .andExpect(jsonPath("$.title", endsWith(" Nothing was changed.")))
                .andExpect(jsonPath("$.action", startsWith("No unlock is possible from this screen.")));

        mvc.perform(get("/api/config/effective").session(session))
                .andExpect(jsonPath("$.allowProdWrites").value(false))
                .andExpect(jsonPath("$.writeAccess.floorAllows").value(false))
                .andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));
    }

    @Test
    void anUnlockOrALockThatCannotBeUsedIsA422() throws Exception {
        ConfigFixture fixture = production();
        MockMvc mvc = mvc(fixture);
        MockHttpSession session = new MockHttpSession();

        // The name must be the active production environment's, exactly.
        for (String typed : new String[] {"onyx production", "Onyx Production ", "Onyx UAT", ""}) {
            mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"" + typed + "\"}").session(session))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.kind").value("VALIDATION"))
                    .andExpect(jsonPath("$.fieldErrors[0].field").value("environment"));
        }
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\"}").session(session)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("environment"));
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"open\", \"environment\": \"Onyx Production\"}").session(session))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("action"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("The action must be unlock or lock."));
        mvc.perform(json(post("/api/config/writes"), "{\"environment\": \"Onyx Production\"}").session(session))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("action"));
        // A misspelt property is named. Dropped in silence, this would read as an unlock with no name.
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"enviroment\": \"Onyx Production\"}").session(session))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("enviroment"));
        // The unlock is minted by the gateway. Nothing a browser posts can stand in for it.
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Production\", \"unlock\": "
                        + "{\"environmentName\": \"Onyx Production\", \"epoch\": " + fixture.gateway.epoch() + "}}").session(session))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("unlock"));
        mvc.perform(post("/api/config/writes").contentType(MediaType.APPLICATION_JSON).session(session)).andExpect(status().isUnprocessableContent());

        // None of that unlocked anything.
        mvc.perform(get("/api/config/effective").session(session)).andExpect(jsonPath("$.writeAccess.writesAllowed").value(false));
    }

    @Test
    void thereIsNothingToUnlockWithoutAnActiveProductionEnvironment() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).allowProdWrites().build();
        MockMvc mvc = mvc(fixture);

        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Sandbox\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("There is nothing to unlock: no environment is active."));
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Sandbox\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("There is nothing to unlock: \"Onyx Sandbox\" is a sandbox environment, and writes to it are "
                        + "not locked."));
        // Locking is always possible, and says how things stand.
        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"lock\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.production").value(false))
                .andExpect(jsonPath("$.writesAllowed").value(true));
    }

    // ------------------------------------------------------------------------------------------
    // GET /api/config/effective
    // ------------------------------------------------------------------------------------------

    @Test
    void everythingInForceIsAnsweredWithARowForEveryOperationAndNoSecret() throws Exception {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("auth.client-secret", "hunter2-in-yml").onyx("variables.apiKey", "hunter2-variable")
                .lookup("member.url", "https://lookup.example.invalid/members/{id}").lookup("member.headers.X-Api-Key", "hunter2-header").build();
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        MockMvc mvc = mvc(fixture);

        mvc.perform(get("/api/config/effective")).andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("mock"))
                .andExpect(jsonPath("$.environment.name").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.environment.kind").value("sandbox"))
                .andExpect(jsonPath("$.environment.active").value(true))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.problems", hasSize(0)))
                .andExpect(jsonPath("$.allowProdWrites").value(false))
                .andExpect(jsonPath("$.writeAccess.production").value(false))
                .andExpect(jsonPath("$.writeAccess.writesAllowed").value(true))
                .andExpect(jsonPath("$.writeAccess.floorAllows").value(false))
                .andExpect(jsonPath("$.writeAccess.reason").value("\"Onyx Sandbox\" is a sandbox environment, so writes are not blocked."))
                .andExpect(jsonPath("$.storeDirectory").value(directory.toString()))
                .andExpect(jsonPath("$.operations", hasSize(EpaOperation.values().length)))
                // The enum serialises as a bare name, so each row carries what the browser needs to know about it.
                .andExpect(jsonPath("$.operations[0].operation").value("CRD_ORDER_SIGN"))
                .andExpect(jsonPath("$.operations[0].label").value("CRD order-sign"))
                .andExpect(jsonPath("$.operations[0].protocol").value("CRD"))
                .andExpect(jsonPath("$.operations[0].write").value(false))
                .andExpect(jsonPath("$.operations[0].outbound").value(true))
                .andExpect(jsonPath("$.operations[0].method").value("POST"))
                .andExpect(jsonPath("$.operations[0].target.template").value("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}"))
                .andExpect(jsonPath("$.operations[0].target.display").value("https://sandbox.onyx.example.invalid/cds-services/order-sign-crd"))
                .andExpect(jsonPath("$.operations[0].target.resolved").value(true))
                .andExpect(jsonPath("$.operations[0].target.unresolved", hasSize(0)))
                .andExpect(jsonPath("$.operations[0].target.source").value("the request \"CRD / order-sign Happy Path\" of the collection \"Onyx Sandbox ePA\""))
                .andExpect(jsonPath("$.operations[0].target.requestName").value("order-sign Happy Path"))
                .andExpect(jsonPath("$.operations[0].target.request.requestId", startsWith("r-")))
                .andExpect(jsonPath("$.operations[0].target.scripted").value(false))
                .andExpect(jsonPath("$.operations[0].requests", hasSize(2)))
                .andExpect(jsonPath("$.operations[0].requests[0].usedByDefault").value(true))
                .andExpect(jsonPath("$.operations[0].requests[1].request.name").value("order-sign Error"))
                .andExpect(jsonPath("$.operations[0].unavailable").doesNotExist())
                // An operation with no mapped request and no fallback url says so in words, on its own row.
                .andExpect(jsonPath("$.operations[1].operation").value("CRD_ORDER_SELECT"))
                .andExpect(jsonPath("$.operations[1].target").doesNotExist())
                .andExpect(jsonPath("$.operations[1].note", startsWith("No collection request is mapped to CRD order-select, and onyx.crd.url is not set.")))
                // An operation that is not outbound has no target and says why.
                .andExpect(jsonPath("$.operations[5].operation").value("DTR_RESPONSE"))
                .andExpect(jsonPath("$.operations[5].outbound").value(false))
                .andExpect(jsonPath("$.operations[5].target").doesNotExist())
                .andExpect(jsonPath("$.operations[5].unavailable").value("This step is completed inside the tool. Nothing is sent for it, so it has no target."))
                .andExpect(jsonPath("$.operations[6].operation").value("PAS_SUBMIT"))
                .andExpect(jsonPath("$.operations[6].write").value(true))
                .andExpect(jsonPath("$.operations[6].protocol").value("PAS"))
                .andExpect(jsonPath("$.operations[6].target.scripted").value(true))
                .andExpect(jsonPath("$.operations[6].blocked").doesNotExist())
                .andExpect(jsonPath("$.operations[13].operation").value("MEMBER_LOOKUP"))
                .andExpect(jsonPath("$.operations[13].protocol").value("LOOKUP"))
                .andExpect(jsonPath("$.operations[13].target.tokens[0]").value("id"))
                // A lookup row says whether the lookup is switched on, so no screen builds a setting key from its name.
                .andExpect(jsonPath("$.operations[13].lookupEnabled").value(true))
                .andExpect(jsonPath("$.operations[14].operation").value("PROVIDER_LOOKUP"))
                .andExpect(jsonPath("$.operations[14].lookupEnabled").value(true))
                .andExpect(jsonPath("$.operations[0].lookupEnabled").doesNotExist())
                .andExpect(jsonPath("$.operations[6].blockedShort").doesNotExist())
                .andExpect(jsonPath("$.settings[?(@.property == 'onyx.auth.client-secret')].value").value("[secret onyx.auth.client-secret]"))
                .andExpect(jsonPath("$.settings[?(@.property == 'onyx.auth.client-secret')].secret").value(true))
                .andExpect(jsonPath("$.settings[?(@.property == 'lookup.member.headers.X-Api-Key')].value").value("[redacted]"))
                .andExpect(jsonPath("$.settings[?(@.property == 'lookup.member.url')].value").value("https://lookup.example.invalid/members/{id}"));

        assertThat(body(mvc, get("/api/config/effective"))).doesNotContain("hunter2").doesNotContain("placeholder-sandbox-secret")
                .doesNotContain("placeholder-tenant-value").doesNotContain("clientSecret\":\"");
    }

    @Test
    void everythingInForceReportsAStartupProblemAndWhatProductionBlocks() throws Exception {
        ConfigFixture missing = ConfigFixture.on(directory.resolve("missing")).activeEnvironment("Onyx Sandbox").build();
        mvc(missing).perform(get("/api/config/effective")).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.active").value(false))
                .andExpect(jsonPath("$.environment.name").doesNotExist())
                .andExpect(jsonPath("$.problems", hasSize(1)))
                .andExpect(jsonPath("$.problems[0]", startsWith("config-store.active-environment names \"Onyx Sandbox\", which is not in the "
                        + "configuration store")));

        ConfigFixture production = ConfigFixture.on(directory.resolve("production")).allowProdWrites().build();
        production.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        production.collection(PostmanFiles.VENDOR_COLLECTION);
        mvc(production).perform(get("/api/config/effective")).andExpect(status().isOk())
                .andExpect(jsonPath("$.environment.kind").value("prod"))
                .andExpect(jsonPath("$.allowProdWrites").value(true))
                .andExpect(jsonPath("$.writeAccess.production").value(true))
                .andExpect(jsonPath("$.writeAccess.writesAllowed").value(false))
                .andExpect(jsonPath("$.operations[6].blocked", startsWith("PAS submit with the request \"submit Happy Path\" is blocked: "
                        + "\"Onyx Production\" is a production environment and production writes are locked for this session.")))
                // A blocked row has a short sentence of its own; the full reason is once in writeAccess.reason.
                .andExpect(jsonPath("$.operations[6].blockedShort").value("Blocked: production writes are locked for this session."))
                .andExpect(jsonPath("$.writeAccess.reason").value("\"Onyx Production\" is production and writes are locked for this session. "
                        + "Unlock them by typing the environment name."))
                .andExpect(jsonPath("$.operations[6].target.display").value("https://api.onyx.example.invalid/fhir/Claim/$submit"))
                .andExpect(jsonPath("$.operations[7].operation").value("PAS_INQUIRE"))
                .andExpect(jsonPath("$.operations[7].blocked").doesNotExist())
                .andExpect(jsonPath("$.operations[7].blockedShort").doesNotExist());

        // A switched-off lookup says so on its row.
        ConfigFixture off = ConfigFixture.on(directory.resolve("off")).lookup("provider.enabled", "false").build();
        mvc(off).perform(get("/api/config/effective")).andExpect(status().isOk())
                .andExpect(jsonPath("$.operations[13].lookupEnabled").value(true))
                .andExpect(jsonPath("$.operations[14].lookupEnabled").value(false));
    }

    // ------------------------------------------------------------------------------------------
    // POST /api/config/resolve
    // ------------------------------------------------------------------------------------------

    @Test
    void aPreviewAnswersWithTheUrlTheHeadersTheVariablesTheBodyAndTheScriptFlag() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        MockMvc mvc = mvc(fixture);

        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.operation").value("PAS_SUBMIT"))
                .andExpect(jsonPath("$.label").value("PAS submit"))
                .andExpect(jsonPath("$.method").value("POST"))
                .andExpect(jsonPath("$.environment.name").value("Onyx Sandbox"))
                .andExpect(jsonPath("$.epoch").value(fixture.gateway.epoch()))
                .andExpect(jsonPath("$.url.template").value("{{baseUrl}}/Claim/$submit"))
                .andExpect(jsonPath("$.url.display").value("https://sandbox.onyx.example.invalid/fhir/Claim/$submit"))
                .andExpect(jsonPath("$.url.source").value("the request \"PAS / submit Happy Path\" of the collection \"Onyx Sandbox ePA\""))
                // The form to send is never serialised. Only the form to show is.
                .andExpect(jsonPath("$.url.value").doesNotExist())
                .andExpect(jsonPath("$.urlShown").value("https://sandbox.onyx.example.invalid/fhir/Claim/$submit"))
                .andExpect(jsonPath("$.requestName").value("submit Happy Path"))
                .andExpect(jsonPath("$.request.requestId", startsWith("r-")))
                .andExpect(jsonPath("$.headers", hasSize(2)))
                .andExpect(jsonPath("$.headers[0].name").value("Content-Type"))
                .andExpect(jsonPath("$.headers[0].display").value("application/fhir+json"))
                .andExpect(jsonPath("$.headers[0].resolved").value(true))
                .andExpect(jsonPath("$.variables", hasSize(3)))
                .andExpect(jsonPath("$.variables[0].name").value("baseUrl"))
                .andExpect(jsonPath("$.variables[0].source").value("ENVIRONMENT"))
                // Each source code is served with its words, so the screen keeps no copy of them.
                .andExpect(jsonPath("$.variables[0].sourceLabel").value("The active environment"))
                .andExpect(jsonPath("$.url.variables[0].source").value("ENVIRONMENT"))
                .andExpect(jsonPath("$.url.variables[0].sourceLabel").value("The active environment"))
                .andExpect(jsonPath("$.variables[0].secret").value(false))
                .andExpect(jsonPath("$.variables[0].display").value("https://sandbox.onyx.example.invalid/fhir"))
                .andExpect(jsonPath("$.variables[0].resolved").value(true))
                .andExpect(jsonPath("$.variables[0].usedIn[0]").value("the url"))
                .andExpect(jsonPath("$.variables[1].name").value("$guid"))
                .andExpect(jsonPath("$.variables[1].source").value("GENERATED"))
                .andExpect(jsonPath("$.variables[1].sourceLabel").value("Made up by the tool for this call"))
                .andExpect(jsonPath("$.bodyTemplate", containsString("\"value\": \"{{$guid}}\"")))
                .andExpect(jsonPath("$.body", containsString("\"value\": \"00000000-0000-4000-8000-000000000001\"")))
                .andExpect(jsonPath("$.bodyUnresolved", hasSize(0)))
                .andExpect(jsonPath("$.scripted").value(true))
                .andExpect(jsonPath("$.scripts[0].listen").value("prerequest"))
                .andExpect(jsonPath("$.write").value(true))
                .andExpect(jsonPath("$.blocked").doesNotExist())
                .andExpect(jsonPath("$.examples", hasSize(0)));
    }

    @Test
    void aPreviewOfANamedRequestTakesValuesTypedForTheRunAndNeverShowsASecret() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        fixture.collection(PostmanFiles.NESTED_COLLECTION);
        RequestRef orderSign = fixture.ref(0, "order-sign Happy Path");
        RequestRef deep = fixture.ref(1, "deep order-select");
        RequestRef byPath = fixture.ref(1, "subscription by path variable");
        MockMvc mvc = mvc(fixture);

        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"crd_order_sign\", \"request\": {\"collectionId\": \""
                        + orderSign.collectionId() + "\", \"requestId\": \"" + orderSign.requestId() + "\"}, \"variables\": "
                        + "{\"crdUrl\": \"https://typed.example.invalid\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url.display").value("https://typed.example.invalid/cds-services/order-sign-crd"))
                .andExpect(jsonPath("$.variables[0].name").value("crdUrl"))
                .andExpect(jsonPath("$.variables[0].source").value("RUN_VALUE"))
                .andExpect(jsonPath("$.write").value(false))
                // The saved example is offered for comparison with what comes back.
                .andExpect(jsonPath("$.examples[0].name").value("Authorization required"))
                .andExpect(jsonPath("$.examples[0].body", containsString("Prior authorization is required")));

        String secret = body(mvc, json(post("/api/config/resolve"), "{\"operation\": \"CRD_ORDER_SELECT\", \"request\": {\"collectionId\": \""
                + deep.collectionId() + "\", \"requestId\": \"" + deep.requestId() + "\"}, \"variables\": {\"crdOrderSelectServiceId\": "
                + "\"order-select-crd\", \"traceId\": \"t-1\", \"collectionKey\": \"hunter2-typed\"}}"));
        assertThat(secret).contains("\"display\":\"[secret collectionKey]\"").contains("\"scripted\":true").doesNotContain("hunter2")
                .doesNotContain("placeholder-collection-key");

        // A value for the {id} or :id part of a url is supplied apart from the placeholders.
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"SUBSCRIPTION_DELETE\", \"request\": {\"collectionId\": \""
                        + byPath.collectionId() + "\", \"requestId\": \"" + byPath.requestId() + "\"}, \"urlValues\": {\"id\": \"sub 1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url.display").value("https://sandbox.onyx.example.invalid/fhir/Subscription/sub%201"))
                .andExpect(jsonPath("$.urlTokens[0].name").value("id"))
                .andExpect(jsonPath("$.urlTokens[0].supplied").value(true))
                .andExpect(jsonPath("$.urlTokens[0].savedValue").value("saved-subscription-7"))
                .andExpect(jsonPath("$.headers[1].name").value("X-Trace"))
                .andExpect(jsonPath("$.headers[1].resolved").value(false))
                .andExpect(jsonPath("$.headers[1].unresolved[0]").value("traceId"))
                .andExpect(jsonPath("$.variables[?(@.name == 'traceId')].resolved").value(false));
    }

    @Test
    void aPreviewThatCannotBeMadeIsA422AndAnUnknownRequestIsA404() throws Exception {
        ConfigFixture fixture = sandboxWithVendorCollection();
        RequestRef submit = fixture.ref(0, "submit Happy Path");
        MockMvc mvc = mvc(fixture);

        mvc.perform(json(post("/api/config/resolve"), "{}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.kind").value("VALIDATION"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("operation"))
                .andExpect(jsonPath("$.fieldErrors[0].message").value("State which operation to resolve."));
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_APPROVE\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("operation"));
        // An operation that never leaves the tool has no request to preview.
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"SUBSCRIPTION_NOTIFICATION\"}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].message").value("Subscription notification never leaves the tool, so there is no request "
                        + "to resolve for it."));
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\", \"request\": {\"collectionId\": \"" + submit.collectionId()
                        + "\"}}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("request"));
        // A misspelt property is named, at the top and inside the request reference.
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\", \"runValues\": {\"baseUrl\": \"x\"}}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The request has a field this tool does not know. The request is not shown."))
                .andExpect(jsonPath("$.action").value("Correct the marked field and show the request again."))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("runValues"));
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\", \"request\": {\"collectionID\": \"x\", \"requestId\": \"y\"}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.fieldErrors[0].field").value("collectionID"));
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\", \"variables\": [\"not\", \"a\", \"map\"]}"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post("/api/config/resolve").contentType(MediaType.APPLICATION_JSON)).andExpect(status().isUnprocessableContent());
        // A read pointed at the write request, and a url value with nowhere to go.
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_INQUIRE\", \"request\": {\"collectionId\": \"" + submit.collectionId()
                        + "\", \"requestId\": \"" + submit.requestId() + "\"}}")).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.title").value("The request \"submit Happy Path\" is mapped to PAS submit, so it cannot be sent as PAS inquire."));
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_INQUIRE\", \"urlValues\": {\"id\": \"S123456789\"}}"))
                .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.title", startsWith("This call supplies a value for \"id\"")));

        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\", \"request\": {\"collectionId\": \"" + submit.collectionId()
                        + "\", \"requestId\": \"r-000000000000\"}}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.kind").value("NOT_FOUND"));
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\", \"request\": {\"collectionId\": \"no-such-collection\", "
                        + "\"requestId\": \"" + submit.requestId() + "\"}}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("The collection this request was chosen from is not uploaded. Reload and choose again."))
                .andExpect(jsonPath("$.action").value("Read the list of collections again, for example by reloading the page, and choose "
                        + "the request from it as it is now."));
    }

    @Test
    void aPreviewOnProductionSaysTheWriteIsBlockedForThisSessionUntilItIsUnlocked() throws Exception {
        ConfigFixture fixture = production();
        MockMvc mvc = mvc(fixture);
        MockHttpSession session = new MockHttpSession();

        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\"}").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.write").value(true))
                .andExpect(jsonPath("$.blocked", startsWith("PAS submit with the request \"submit Happy Path\" is blocked")))
                .andExpect(jsonPath("$.url.display").value("https://api.onyx.example.invalid/fhir/Claim/$submit"));

        mvc.perform(json(post("/api/config/writes"), "{\"action\": \"unlock\", \"environment\": \"Onyx Production\"}").session(session))
                .andExpect(status().isOk());
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\"}").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.write").value(true)).andExpect(jsonPath("$.blocked").doesNotExist());
        // A session without the unlock still sees it blocked.
        mvc.perform(json(post("/api/config/resolve"), "{\"operation\": \"PAS_SUBMIT\"}")).andExpect(jsonPath("$.blocked").exists());
    }
}
