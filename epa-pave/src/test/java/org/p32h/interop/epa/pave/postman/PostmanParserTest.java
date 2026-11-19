package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.service.error.BadInputException;

class PostmanParserTest {

    private static PostmanCollection vendor() {
        return PostmanParser.collection(PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION));
    }

    private static PostmanCollection nested() {
        return PostmanParser.collection(PostmanFiles.text(PostmanFiles.NESTED_COLLECTION));
    }

    private static PostmanRequest named(PostmanCollection collection, String name) {
        return collection.requests().stream().filter(request -> request.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------------------------------

    @Test
    void anEnvironmentIsReadWithEveryVariableItHolds() {
        PostmanEnvironment environment = PostmanParser.environment(PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));

        assertThat(environment.name()).isEqualTo("Onyx Sandbox");
        assertThat(environment.postmanId()).isEqualTo("5b7c1f0e-3c5a-4d0b-9f43-2a6d1e8c7b90");
        assertThat(environment.scope()).isEqualTo("environment");
        // Disabled ones are listed too, so the screen can show that they exist and are off.
        assertThat(environment.variables()).extracting(PostmanVariable::key).containsExactly("baseUrl", "baseUrl", "crdUrl",
                "crdOrderSignServiceId", "authHost", "tokenUrl", "clientId", "clientSecret", "tenantCode", "subscriptionId",
                "callbackUrl", "retiredBaseUrl");
        assertThat(environment.notes()).isEmpty();
    }

    @Test
    void aDisabledVariableIsIgnoredExactlyAsPostmanIgnoresIt() {
        PostmanEnvironment environment = PostmanParser.environment(PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));
        Map<String, PostmanVariable> inForce = environment.inForce();

        assertThat(inForce).doesNotContainKey("retiredBaseUrl");
        // The file defines baseUrl twice and the second definition is switched off. It must not win.
        assertThat(inForce.get("baseUrl").value()).isEqualTo("https://sandbox.onyx.example.invalid/fhir");
        assertThat(inForce).hasSize(10);
    }

    @Test
    void bothWaysPostmanMarksAVariableOffAreHonoured() {
        PostmanEnvironment environment = PostmanParser.environment("""
                {"name": "Flags", "values": [
                  {"key": "a", "value": "1", "enabled": false},
                  {"key": "b", "value": "2", "disabled": true},
                  {"key": "c", "value": "3", "enabled": "false"},
                  {"key": "d", "value": "4"},
                  {"key": "e", "value": "5", "enabled": true, "disabled": false}
                ]}""");

        assertThat(environment.inForce().keySet()).containsExactly("d", "e");
        assertThat(environment.variables()).filteredOn(variable -> !variable.enabled()).extracting(PostmanVariable::key)
                .containsExactly("a", "b", "c");
    }

    @Test
    void aSecretIsKnownByItsPostmanTypeOrByItsName() {
        PostmanEnvironment environment = PostmanParser.environment(PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));
        Map<String, PostmanVariable> inForce = environment.inForce();

        assertThat(inForce.get("clientSecret").secretType()).isTrue();
        // tenantCode does not read as a credential. Only the type Postman gave it says it is one.
        assertThat(Secrets.looksSecret("tenantCode")).isFalse();
        assertThat(inForce.get("tenantCode").secretType()).isTrue();
        assertThat(inForce.get("tenantCode").secret()).isTrue();
        assertThat(inForce.get("baseUrl").secret()).isFalse();
        assertThat(new PostmanVariable("apiKey", "placeholder", true, "default").secret()).isTrue();
    }

    @Test
    void aBlankValueIsKeptAsDefinedAndBlank() {
        PostmanEnvironment environment = PostmanParser.environment(PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));

        PostmanVariable callback = environment.inForce().get("callbackUrl");
        assertThat(callback).isNotNull();
        assertThat(callback.value()).isEmpty();
        assertThat(callback.hasValue()).isFalse();
    }

    @Test
    void theLastEnabledDefinitionOfARepeatedNameIsTheOneInForceAndTheUploadIsTold() {
        PostmanEnvironment environment = PostmanParser.environment("""
                {"name": "Twice", "values": [
                  {"key": "baseUrl", "value": "https://first.example.invalid", "enabled": true},
                  {"key": "baseUrl", "value": "https://second.example.invalid", "enabled": true},
                  {"value": "no key here"},
                  {"key": "  ", "value": "blank key"}
                ]}""");

        assertThat(environment.inForce().get("baseUrl").value()).isEqualTo("https://second.example.invalid");
        assertThat(environment.notes()).containsExactly("2 variable entries have no name and were ignored.",
                "The variable \"baseUrl\" is defined 2 times. The last definition is the one in force, as in Postman.");
    }

    @Test
    void aValueThatIsNotTextIsReadAsItsJsonText() {
        PostmanEnvironment environment = PostmanParser.environment("""
                {"name": "Kinds", "values": [
                  {"key": "port", "value": 8443},
                  {"key": "flag", "value": true},
                  {"key": "nothing", "value": null},
                  {"key": "absent"},
                  {"key": "object", "value": {"a": 1}}
                ]}""");
        Map<String, PostmanVariable> inForce = environment.inForce();

        assertThat(inForce.get("port").value()).isEqualTo("8443");
        assertThat(inForce.get("flag").value()).isEqualTo("true");
        assertThat(inForce.get("nothing").value()).isNull();
        assertThat(inForce.get("absent").value()).isNull();
        assertThat(inForce.get("object").value()).isEqualTo("{\"a\":1}");
    }

    @Test
    void anEnvironmentAsThePostmanApiWrapsItIsRead() {
        PostmanEnvironment environment = PostmanParser.environment(
                "{\"environment\": " + PostmanFiles.text(PostmanFiles.UAT_ENVIRONMENT) + "}");

        assertThat(environment.name()).isEqualTo("Onyx UAT");
        assertThat(environment.inForce()).containsKey("baseUrl");
    }

    @Test
    void aFileSavedWithAByteOrderMarkIsStillRead() {
        PostmanEnvironment environment = PostmanParser.environment((char) 0xFEFF + PostmanFiles.text(PostmanFiles.UAT_ENVIRONMENT));

        assertThat(environment.name()).isEqualTo("Onyx UAT");
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    @Test
    void everyRequestIsFoundInsideItsFolder() {
        PostmanCollection collection = vendor();

        assertThat(collection.name()).isEqualTo("Onyx Sandbox ePA");
        assertThat(collection.schema()).contains("v2.1.0");
        assertThat(collection.postmanId()).isEqualTo("0c3f9a52-6e1b-4d87-a2f4-5b9d0e7c1a36");
        // Nothing sits at the top level of this file. A parser that read only the top level would find no request at all.
        assertThat(collection.requests()).hasSize(13);
        assertThat(collection.requests()).allSatisfy(request -> assertThat(request.folderPath()).hasSize(1));
        assertThat(collection.requests()).extracting(PostmanRequest::name).containsExactly(
                "$questionnaire-package Happy path", "$questionnaire-package Error", "next-question Happy Path",
                "submit Happy Path", "inquiry Happy Path", "order-sign Happy Path", "order-sign Error",
                "subscription-get Happy Path", "subscription-get Error", "subscription-create Happy Path",
                "subscription-update Happy Path", "subscription-delete Happy Path", "subscription-notification Happy Path");
        assertThat(named(collection, "submit Happy Path").folderPath()).containsExactly("PAS");
        assertThat(named(collection, "submit Happy Path").qualifiedName()).isEqualTo("PAS / submit Happy Path");
    }

    @Test
    void theMethodTheUrlTheHeadersAndTheBodyAreTakenAsWritten() {
        PostmanRequest submit = named(vendor(), "submit Happy Path");

        assertThat(submit.method()).isEqualTo("POST");
        assertThat(submit.url()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(submit.headers()).extracting(PostmanHeader::name).containsExactly("Content-Type", "Accept");
        assertThat(submit.headers().get(0).value()).isEqualTo("application/fhir+json");
        assertThat(submit.bodyMode()).isEqualTo("raw");
        assertThat(submit.bodyLanguage()).isEqualTo("json");
        // Verbatim: the line breaks, the indentation and the placeholders of the vendor's sample.
        assertThat(submit.body()).startsWith("{\n  \"resourceType\": \"Bundle\",\n  \"type\": \"collection\",")
                .contains("\"value\": \"{{$guid}}\"").contains("\"timestamp\": \"{{$isoTimestamp}}\"").endsWith("\n}");
        assertThat(JsonSafe.parse(submit.body()).ok()).isTrue();

        PostmanRequest get = named(vendor(), "subscription-get Happy Path");
        assertThat(get.method()).isEqualTo("GET");
        assertThat(get.url()).isEqualTo("{{baseUrl}}/Subscription/{{subscriptionId}}");
        assertThat(get.body()).isNull();
        assertThat(get.bodyMode()).isNull();
    }

    @Test
    void aDisabledHeaderKeepsItsFlagAndIsNotAmongTheHeadersSent() {
        PostmanRequest orderSign = named(vendor(), "order-sign Happy Path");

        assertThat(orderSign.headers()).extracting(PostmanHeader::name).containsExactly("Content-Type", "Accept", "X-Request-Trace");
        assertThat(orderSign.headers().get(2).disabled()).isTrue();
        assertThat(orderSign.headers().get(2).value()).isEqualTo("{{traceId}}");
        assertThat(orderSign.sentHeaders()).extracting(PostmanHeader::name).containsExactly("Content-Type", "Accept");
    }

    @Test
    void aRequestThreeFoldersDownIsFoundWithItsFolderPath() {
        PostmanRequest deep = named(nested(), "deep order-select");

        assertThat(deep.folderPath()).containsExactly("Level one", "Level two", "Level three");
        assertThat(deep.method()).isEqualTo("POST");
        assertThat(deep.description()).isEqualTo("A request three folders down.");
        assertThat(nested().requests()).extracting(PostmanRequest::name).containsExactly("deep order-select", "scripted inquiry",
                "duplicate", "duplicate", "discovery as a bare url", "subscription by path variable", "token request");
    }

    @Test
    void aUrlWithNoRawFormIsRebuiltFromItsPartsWithoutItsDisabledQueryParameters() {
        PostmanRequest deep = named(nested(), "deep order-select");

        assertThat(deep.url()).isEqualTo("https://deep.example.invalid:8443/cds-services/{{crdOrderSelectServiceId}}?trace={{traceId}}");
        assertThat(deep.notes()).containsExactly("The url has no raw form in the file, so it was rebuilt from its parts.");
    }

    @Test
    void aRequestWrittenAsABareUrlIsAGet() {
        PostmanRequest bare = named(nested(), "discovery as a bare url");

        assertThat(bare.method()).isEqualTo("GET");
        assertThat(bare.url()).isEqualTo("https://plain.example.invalid/cds-services");
        assertThat(bare.folderPath()).isEmpty();
        assertThat(bare.headers()).isEmpty();
    }

    @Test
    void aSavedPathVariableIsKeptForDisplayAndTheUrlKeepsItsColonForm() {
        PostmanRequest byPath = named(nested(), "subscription by path variable");

        assertThat(byPath.url()).isEqualTo("{{baseUrl}}/Subscription/:id");
        assertThat(byPath.pathVariables()).containsExactly(new PostmanRequest.PathVariable("id", "saved-subscription-7"));
        assertThat(byPath.method()).isEqualTo("DELETE");
        // The older form that writes every header in one block of text.
        assertThat(byPath.headers()).extracting(PostmanHeader::name).containsExactly("Accept", "X-Trace");
        assertThat(byPath.headers().get(1).value()).isEqualTo("{{traceId}}");
    }

    @Test
    void aBodyThatIsNotRawIsNotLoadedAndTheRequestSaysSo() {
        PostmanRequest token = named(nested(), "token request");

        assertThat(token.bodyMode()).isEqualTo("urlencoded");
        assertThat(token.body()).isNull();
        assertThat(token.notes()).containsExactly(
                "The body is in Postman's urlencoded mode. This tool loads raw bodies only, so this one is not loaded.");
        assertThat(token.url()).isEqualTo("{{tokenUrl}}");
    }

    @Test
    void anEntryThatIsNeitherAFolderNorARequestIsCountedAndReported() {
        assertThat(nested().notes()).containsExactly("1 entry is neither a folder nor a request and is not listed.");
        assertThat(vendor().notes()).isEmpty();
    }

    @Test
    void collectionVariablesAreReadWithTheirFlags() {
        PostmanCollection collection = nested();

        assertThat(collection.variables()).extracting(PostmanVariable::key)
                .containsExactly("baseUrl", "apiVersion", "collectionKey", "retired", "legacyNamed");
        assertThat(collection.inForce().keySet()).containsExactly("baseUrl", "apiVersion", "collectionKey", "legacyNamed");
        assertThat(collection.inForce().get("collectionKey").secretType()).isTrue();
        // An older export names a collection variable by its id.
        assertThat(collection.inForce().get("legacyNamed").value()).isEqualTo("named-by-id");
    }

    // ------------------------------------------------------------------------------------------
    // Scripts: read, flagged, never run
    // ------------------------------------------------------------------------------------------

    @Test
    void aRequestWithItsOwnPreRequestScriptIsFlagged() {
        PostmanRequest submit = named(vendor(), "submit Happy Path");

        assertThat(submit.scripted()).isTrue();
        assertThat(submit.scripts()).hasSize(1);
        assertThat(submit.scripts().get(0).from()).isEqualTo("this request");
        assertThat(submit.scripts().get(0).listen()).isEqualTo(PostmanScript.PRE_REQUEST);
        assertThat(submit.scripts().get(0).source()).isEqualTo(
                "// Stamps the bundle before it is sent.\npm.variables.set(\"submittedAt\", new Date().toISOString());");
    }

    @Test
    void theEmptyScriptsPostmanWritesIntoMostExportsAreNotFlagged() {
        PostmanCollection collection = vendor();

        // The collection and "order-sign Happy Path" both carry Postman's empty prerequest and test events.
        assertThat(named(collection, "order-sign Happy Path").scripted()).isFalse();
        assertThat(named(collection, "order-sign Happy Path").scripts()).isEmpty();
        assertThat(collection.requests()).filteredOn(PostmanRequest::scripted).extracting(PostmanRequest::name)
                .containsExactly("submit Happy Path");
    }

    @Test
    void aScriptOnAFolderOrOnTheCollectionIsInheritedByEveryRequestBeneathIt() {
        PostmanCollection collection = nested();

        PostmanRequest deep = named(collection, "deep order-select");
        assertThat(deep.scripted()).isTrue();
        assertThat(deep.scripts()).extracting(PostmanScript::from).containsExactly("the collection", "the folder \"Level two\"");

        PostmanRequest inquiry = named(collection, "scripted inquiry");
        assertThat(inquiry.scripts()).extracting(PostmanScript::from)
                .containsExactly("the collection", "the folder \"Level two\"", "this request", "this request");
        assertThat(inquiry.scripts()).extracting(PostmanScript::listen)
                .containsExactly("prerequest", "prerequest", "prerequest", "test");
        assertThat(inquiry.testScripted()).isTrue();

        // Outside "Level two" only the collection's script applies.
        PostmanRequest bare = named(collection, "discovery as a bare url");
        assertThat(bare.scripted()).isTrue();
        assertThat(bare.scripts()).extracting(PostmanScript::from).containsExactly("the collection");
        assertThat(bare.testScripted()).isFalse();
    }

    @Test
    void aScriptIsKeptAsTextAndIsNeverRun() {
        // If any of this were evaluated the test would not finish, or the build would stop.
        PostmanCollection collection = PostmanParser.collection("""
                {"info": {"name": "Hostile", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": [{"name": "trap", "event": [{"listen": "prerequest", "script": {"exec": [
                     "while (true) {}",
                     "java.lang.System.exit(1);",
                     "throw new Error('ran');"]}}],
                   "request": {"method": "GET", "url": "https://example.invalid/x"}}]}""");

        PostmanRequest trap = collection.requests().get(0);
        assertThat(trap.scripted()).isTrue();
        assertThat(trap.scripts().get(0).source()).isEqualTo("while (true) {}\njava.lang.System.exit(1);\nthrow new Error('ran');");
    }

    @Test
    void aScriptThatHoldsOnlyCommentsOrNothingIsNotAScript() {
        PostmanCollection collection = PostmanParser.collection("""
                {"info": {"name": "Quiet", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
                 "item": [
                   {"name": "comment only", "event": [{"listen": "prerequest", "script": {"exec": ["// nothing here", "   "]}}],
                    "request": {"method": "GET", "url": "https://example.invalid/a"}},
                   {"name": "one string", "event": [{"listen": "prerequest", "script": {"exec": "pm.variables.set('a', 1);"}}],
                    "request": {"method": "GET", "url": "https://example.invalid/b"}},
                   {"name": "test only", "event": [{"listen": "test", "script": {"exec": ["pm.test('x', () => {});"]}}],
                    "request": {"method": "GET", "url": "https://example.invalid/c"}}
                 ]}""");

        assertThat(collection.requests().get(0).scripted()).isFalse();
        assertThat(collection.requests().get(1).scripted()).isTrue();
        // A script that runs after the response is not a pre-request script.
        assertThat(collection.requests().get(2).scripted()).isFalse();
        assertThat(collection.requests().get(2).testScripted()).isTrue();
    }

    @Test
    void theAuthenticationHelperPostmanWouldApplyIsReportedWithoutItsValues() {
        PostmanCollection collection = nested();

        PostmanAuth inherited = named(collection, "deep order-select").auth();
        assertThat(inherited.type()).isEqualTo("bearer");
        assertThat(inherited.from()).isEqualTo("the collection");
        assertThat(inherited.variables()).containsExactly("accessToken");
        // A request that switches authentication off does not inherit the collection's helper.
        assertThat(named(collection, "subscription by path variable").auth()).isNull();
        assertThat(named(vendor(), "submit Happy Path").auth()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Saved example responses
    // ------------------------------------------------------------------------------------------

    @Test
    void savedExamplesAreKeptWithTheirStatusHeadersAndBody() {
        PostmanRequest deep = named(nested(), "deep order-select");

        assertThat(deep.examples()).hasSize(2);
        PostmanExample noCards = deep.examples().get(0);
        assertThat(noCards.name()).isEqualTo("No cards");
        assertThat(noCards.status()).isEqualTo("OK");
        assertThat(noCards.code()).isEqualTo(200);
        assertThat(noCards.headers()).extracting(PostmanHeader::name).containsExactly("Content-Type", "X-Vendor-Trace");
        assertThat(noCards.body()).isEqualTo("{\n  \"cards\": []\n}");
        PostmanExample down = deep.examples().get(1);
        assertThat(down.code()).isEqualTo(503);
        assertThat(down.status()).isEqualTo("Service Unavailable");
        assertThat(down.body()).isNull();

        assertThat(named(vendor(), "order-sign Happy Path").examples()).extracting(PostmanExample::name)
                .containsExactly("Authorization required");
        assertThat(named(vendor(), "$questionnaire-package Error").examples().get(0).code()).isEqualTo(400);
        assertThat(named(vendor(), "inquiry Happy Path").examples()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // Ids
    // ------------------------------------------------------------------------------------------

    @Test
    void theSameFileGivesTheSameIdsEveryTime() {
        List<String> first = vendor().requests().stream().map(PostmanRequest::id).toList();
        List<String> second = vendor().requests().stream().map(PostmanRequest::id).toList();

        assertThat(first).isEqualTo(second).doesNotHaveDuplicates().allMatch(id -> id.matches("r-[0-9a-f]{12}"));
    }

    @Test
    void anIdDependsOnTheFolderAndTheNameAndOnNothingElse() {
        String original = PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION);
        PostmanCollection edited = PostmanParser.collection(original.replace("{{baseUrl}}/Claim/$submit", "{{pasUrl}}/Claim/$submit")
                .replace("Stamps the bundle", "Edited by the vendor"));
        PostmanCollection renamed = PostmanParser.collection(original.replace("\"submit Happy Path\"", "\"submit Happy Path v2\""));
        PostmanCollection moved = PostmanParser.collection(original.replace("\"name\": \"PAS\"", "\"name\": \"Prior auth\""));

        String id = named(vendor(), "submit Happy Path").id();
        // A new export with a changed url and script keeps the id, so a mapping correction survives it.
        assertThat(named(edited, "submit Happy Path").url()).isEqualTo("{{pasUrl}}/Claim/$submit");
        assertThat(named(edited, "submit Happy Path").id()).isEqualTo(id);
        assertThat(named(renamed, "submit Happy Path v2").id()).isNotEqualTo(id);
        assertThat(named(moved, "submit Happy Path").id()).isNotEqualTo(id);
        // The other requests are untouched by a change to one.
        assertThat(named(renamed, "inquiry Happy Path").id()).isEqualTo(named(vendor(), "inquiry Happy Path").id());
    }

    @Test
    void twoRequestsWithOneNameInOneFolderGetDifferentIds() {
        List<PostmanRequest> twins = nested().requests().stream().filter(request -> request.name().equals("duplicate")).toList();

        assertThat(twins).hasSize(2);
        assertThat(twins.get(0).id()).isNotEqualTo(twins.get(1).id());
        assertThat(twins.get(0).url()).endsWith("first=1");
        assertThat(twins.get(1).url()).endsWith("second=1");
        // Each id finds its own request, and an id nobody has finds none.
        PostmanCollection collection = nested();
        assertThat(collection.request(twins.get(0).id()).url()).endsWith("first=1");
        assertThat(collection.request(twins.get(1).id()).url()).endsWith("second=1");
        assertThat(collection.request("r-000000000000")).isNull();
        assertThat(collection.request(null)).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // Files that are refused, each with what the file is and what to upload in its place
    // ------------------------------------------------------------------------------------------

    @Test
    void aV1CollectionIsRefused() {
        assertThatThrownBy(() -> PostmanParser.collection(PostmanFiles.text(PostmanFiles.V1_COLLECTION)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("This is a Postman collection in the old v1 format, which this tool does not read.");
                    assertThat(e.action()).contains("Collection v2.1");
                });
    }

    @Test
    void aCollectionThatIsNotSchemaV21IsRefusedNamingTheFormatItIsIn() {
        assertThatThrownBy(() -> PostmanParser.collection(PostmanFiles.text(PostmanFiles.V2_0_COLLECTION)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("This is a Postman collection in format v2.0.0. This tool reads format v2.1 only.");
                    assertThat(e.action()).contains("Collection v2.1");
                });
        String noSchema = PostmanFiles.text(PostmanFiles.V2_0_COLLECTION)
                .replace("\"schema\": \"https://schema.getpostman.com/json/collection/v2.0.0/collection.json\"", "\"description\": \"none\"");
        assertThatThrownBy(() -> PostmanParser.collection(noSchema)).isInstanceOf(BadInputException.class)
                .hasMessageContaining("does not say which format it is in");
    }

    @Test
    void aTruncatedFileIsRefusedAsIncomplete() {
        assertThatThrownBy(() -> PostmanParser.collection(PostmanFiles.text(PostmanFiles.TRUNCATED_COLLECTION)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).startsWith("This file is not complete: the JSON stops part way through");
                    assertThat(e.action()).isEqualTo("Export the collection from Postman again and upload the whole file.");
                });
    }

    @Test
    void aFileCutOffAnywhereIsRefusedAsIncompleteWhereverTheCutFalls() {
        String whole = PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION);
        int lastBrace = whole.lastIndexOf('}');
        int refused = 0;
        // Inside a string, between tokens, after a comma, in an escape: each is a different parser state.
        // Every position of the first part of the file is tried, and a sample of the rest.
        for (int cut = 1; cut < lastBrace; cut += cut < 2500 ? 1 : 7) {
            String partial = whole.substring(0, cut);
            assertThatThrownBy(() -> PostmanParser.collection(partial)).as("cut at " + cut).isInstanceOf(BadInputException.class)
                    .hasMessageStartingWith("This file is not complete");
            refused++;
        }
        assertThat(refused).isGreaterThan(4000);
        // A cut inside the word true is the one place the parser does not report the end of the text.
        int literal = whole.indexOf("\"disabled\": true") + "\"disabled\": ".length();
        for (int into = 1; into <= 3; into++) {
            String partial = whole.substring(0, literal + into);
            assertThat(partial).endsWith("true".substring(0, into));
            assertThatThrownBy(() -> PostmanParser.collection(partial)).as("cut " + into + " into true")
                    .isInstanceOf(BadInputException.class).hasMessageStartingWith("This file is not complete");
        }

        String environment = PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT);
        assertThatThrownBy(() -> PostmanParser.environment(environment.substring(0, environment.length() / 2)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).startsWith("This file is not complete");
                    assertThat(e.action()).isEqualTo("Export the environment from Postman again and upload the whole file.");
                });
    }

    @Test
    void aFileThatIsNotJsonIsRefusedWithoutQuotingIt() {
        assertThatThrownBy(() -> PostmanParser.collection("<html><body>placeholder-secret-in-a-page</body></html>"))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    // Where the text stopped being JSON, and nothing of the text itself.
                    assertThat(e.getMessage()).matches("This file is not a Postman collection export: it is not valid JSON "
                            + "\\(line 1, column \\d+\\)\\.");
                    assertThat(e.getMessage() + e.action()).doesNotContain("placeholder-secret-in-a-page").doesNotContain("html");
                    assertThat(e.action()).contains("Collection v2.1");
                });
        assertThatThrownBy(() -> PostmanParser.environment("{\"name\": \"x\", \"values\": [}"))
                .isInstanceOf(BadInputException.class).hasMessageStartingWith("This file is not a Postman environment export: it is not valid JSON");
    }

    @Test
    void anEmptyFileIsRefused() {
        assertThatThrownBy(() -> PostmanParser.collection("   \n")).isInstanceOf(BadInputException.class)
                .hasMessage("The uploaded file is empty.");
        assertThatThrownBy(() -> PostmanParser.environment("")).isInstanceOf(BadInputException.class)
                .hasMessage("The uploaded file is empty.");
        assertThatThrownBy(() -> PostmanParser.decode(new byte[0])).isInstanceOf(BadInputException.class)
                .hasMessage("The uploaded file is empty.");
        assertThatThrownBy(() -> PostmanParser.decode(null)).isInstanceOf(BadInputException.class);
    }

    @Test
    void aFileThatIsNotUtf8TextIsRefused() {
        byte[] notText = {(byte) 0x50, (byte) 0x4b, (byte) 0x03, (byte) 0x04, (byte) 0xff, (byte) 0xfe, (byte) 0x00, (byte) 0x9c};

        assertThatThrownBy(() -> PostmanParser.decode(notText)).isInstanceOf(BadInputException.class)
                .hasMessage("The uploaded file is not text in UTF-8, so it is not a Postman export.");
        // Text outside ASCII that is valid UTF-8 is read as it is. The letter is written as a number to keep this source ASCII.
        String accented = "{\"name\": \"caf" + (char) 0xE9 + "\"}";
        assertThat(PostmanParser.decode(accented.getBytes(StandardCharsets.UTF_8))).isEqualTo(accented);
    }

    @Test
    void aCollectionUploadedAsAnEnvironmentIsRefusedAndToldWhereItBelongs() {
        assertThatThrownBy(() -> PostmanParser.environment(PostmanFiles.text(PostmanFiles.VENDOR_COLLECTION)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("This file is a Postman collection, not an environment.");
                    assertThat(e.action()).startsWith("Upload it under Collections.");
                });
        // The old format is still a collection, and says so here too.
        assertThatThrownBy(() -> PostmanParser.environment(PostmanFiles.text(PostmanFiles.V1_COLLECTION)))
                .isInstanceOf(BadInputException.class).hasMessage("This file is a Postman collection, not an environment.");
    }

    @Test
    void anEnvironmentUploadedAsACollectionIsRefusedAndToldWhereItBelongs() {
        assertThatThrownBy(() -> PostmanParser.collection(PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("This file is a Postman environment, not a collection.");
                    assertThat(e.action()).startsWith("Upload it under Environments.");
                });
    }

    @Test
    void jsonThatIsNeitherIsRefusedForWhatItLacks() {
        assertThatThrownBy(() -> PostmanParser.environment("[1, 2, 3]")).isInstanceOf(BadInputException.class)
                .hasMessage("This JSON file is not a Postman environment export: it has no list of values.");
        assertThatThrownBy(() -> PostmanParser.environment("{\"resourceType\": \"Bundle\"}")).isInstanceOf(BadInputException.class)
                .hasMessage("This JSON file is not a Postman environment export: it has no list of values.");
        assertThatThrownBy(() -> PostmanParser.collection("{\"resourceType\": \"Bundle\"}")).isInstanceOf(BadInputException.class)
                .hasMessage("This JSON file is not a Postman collection export: it has no info block with a list of items.");
        assertThatThrownBy(() -> PostmanParser.collection("\"just text\"")).isInstanceOf(BadInputException.class);
    }

    @Test
    void anExportWithNoNameIsRefusedBecauseTheNameIsItsIdentity() {
        assertThatThrownBy(() -> PostmanParser.environment("{\"values\": []}")).isInstanceOf(BadInputException.class)
                .hasMessageStartingWith("This environment export has no name");
        assertThatThrownBy(() -> PostmanParser.environment("{\"name\": \"   \", \"values\": []}")).isInstanceOf(BadInputException.class)
                .hasMessageStartingWith("This environment export has no name");
        assertThatThrownBy(() -> PostmanParser.collection(
                "{\"info\": {\"schema\": \"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"item\": []}"))
                .isInstanceOf(BadInputException.class).hasMessageStartingWith("This collection export has no name");
    }

    @Test
    void aRepeatedKeyInTheFileIsReported() {
        PostmanEnvironment environment = PostmanParser.environment("{\"name\": \"First\", \"name\": \"Second\", \"values\": []}");

        assertThat(environment.name()).isEqualTo("Second");
        assertThat(environment.notes()).containsExactly(
                "The file repeats a key inside one object. Only the last value of a repeated key was read.");
    }

    // ------------------------------------------------------------------------------------------
    // Nothing that holds a value prints or serialises it
    // ------------------------------------------------------------------------------------------

    @Test
    void noPrintedOrSerialisedFormOfTheModelHoldsAValue() {
        PostmanEnvironment environment = PostmanParser.environment(PostmanFiles.text(PostmanFiles.SANDBOX_ENVIRONMENT));
        PostmanCollection collection = nested();
        PostmanRequest deep = named(collection, "deep order-select");

        for (Object value : new Object[] {environment, environment.variables(), environment.inForce(), collection, collection.variables(),
                deep, deep.headers(), deep.examples(), deep.scripts()}) {
            assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain("placeholder-sandbox-secret")
                    .doesNotContain("placeholder-tenant-value").doesNotContain("placeholder-collection-key").doesNotContain("\"cards\"")
                    .doesNotContain("folderStamp").doesNotContain("trace-placeholder").doesNotContain("sandbox.onyx.example.invalid");
        }
        // A variable or a header that reached a response by mistake would still not carry its value.
        String json = JsonSafe.mapper().writeValueAsString(List.of(environment.variables(), collection.variables(), deep.headers()));
        assertThat(json).doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value")
                .doesNotContain("placeholder-collection-key").doesNotContain("sandbox.onyx.example.invalid").contains("clientSecret");
    }
}
