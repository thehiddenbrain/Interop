package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.variables;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.config.ConfigViews.AvailableRequest;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Effective;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.HeaderView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.MappingChanged;
import org.p32h.interop.epa.pave.service.config.ConfigViews.OperationRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestDetail;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestSummary;
import org.p32h.interop.epa.pave.service.config.ConfigViews.Resolved;
import org.p32h.interop.epa.pave.service.config.ConfigViews.ResolvedHeaderView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.SettingRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.TargetView;
import org.p32h.interop.epa.pave.service.config.ConfigViews.TokenRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableRow;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/** What the Config tab is told: every answer is built from masked rows, and none of them holds the value of a secret. */
class ConfigServiceTest {

    private static final String SANDBOX_FHIR = "https://sandbox.onyx.example.invalid/fhir";

    @TempDir
    Path directory;

    private ConfigFixture sandboxWithVendorCollection() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    private static VariableView variable(EnvironmentView environment, String name) {
        return environment.variables().stream().filter(each -> each.name().equals(name)).findFirst().orElseThrow();
    }

    private static RequestSummary request(CollectionView collection, String name) {
        return collection.requests().stream().filter(each -> each.name().equals(name)).findFirst().orElseThrow();
    }

    private static OperationRow row(Effective effective, EpaOperation operation) {
        return effective.operations().stream().filter(each -> each.operation() == operation).findFirst().orElseThrow();
    }

    private static VariableRow variable(Resolved resolved, String name) {
        return resolved.variables().stream().filter(each -> each.name().equals(name)).findFirst().orElseThrow();
    }

    private static String json(Object value) {
        return JsonSafe.mapper().writeValueAsString(value);
    }

    // ------------------------------------------------------------------------------------------
    // Environments
    // ------------------------------------------------------------------------------------------

    @Test
    void anUploadedEnvironmentComesBackWithItsSecretsMaskedAndAWarningForEachOne() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        EnvironmentUploaded uploaded = fixture.service.uploadEnvironment("sandbox.json", PostmanFiles.bytes(PostmanFiles.SANDBOX_ENVIRONMENT),
                EnvironmentKind.SANDBOX, false);

        EnvironmentView environment = uploaded.environment();
        assertThat(environment.id()).matches("onyx-sandbox-[0-9a-f]{8}");
        assertThat(environment.name()).isEqualTo("Onyx Sandbox");
        assertThat(environment.kind()).isEqualTo(EnvironmentKind.SANDBOX);
        // Uploading does not activate.
        assertThat(environment.active()).isFalse();
        assertThat(environment.originalFileName()).isEqualTo("sandbox.json");
        assertThat(environment.uploadedAt()).isEqualTo("2026-01-05T15:04:05Z");
        assertThat(environment.file()).startsWith(directory.resolve("environments").toString()).endsWith(".json");
        assertThat(environment.acknowledgedNotProd()).isFalse();
        assertThat(environment.productionSigns()).isEmpty();
        assertThat(uploaded.replaced()).isFalse();
        assertThat(uploaded.epoch()).isEqualTo(fixture.gateway.epoch());
        assertThat(environment.variables()).extracting(VariableView::name).containsExactly("baseUrl", "baseUrl", "crdUrl",
                "crdOrderSignServiceId", "authHost", "tokenUrl", "clientId", "clientSecret", "tenantCode", "subscriptionId", "callbackUrl",
                "retiredBaseUrl");

        VariableView secret = variable(environment, "clientSecret");
        assertThat(secret.secret()).isTrue();
        assertThat(secret.set()).isTrue();
        assertThat(secret.type()).isEqualTo("secret");
        // It says that one is set, never what it is.
        assertThat(secret.display()).isEqualTo("[secret clientSecret]");
        assertThat(variable(environment, "tenantCode").display()).isEqualTo("[secret tenantCode]");
        VariableView plain = variable(environment, "clientId");
        assertThat(plain.secret()).isFalse();
        assertThat(plain.display()).isEqualTo("pave-sandbox-client");
        // A value that refers to another variable is shown as written.
        assertThat(variable(environment, "tokenUrl").display()).isEqualTo("{{authHost}}/oauth2/token");
        VariableView blank = variable(environment, "callbackUrl");
        assertThat(blank.set()).isFalse();
        assertThat(blank.display()).isNull();
        assertThat(blank.enabled()).isTrue();

        assertThat(uploaded.warnings()).hasSize(2);
        assertThat(uploaded.warnings().get(0)).startsWith("The variable \"clientSecret\" holds a secret in plain text, and it now sits in the file "
                + environment.file() + ".");
        assertThat(uploaded.warnings().get(1)).startsWith("The variable \"tenantCode\" holds a secret in plain text");
        assertThat(json(uploaded) + uploaded).doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value");
    }

    @Test
    void aVariableThatIsSwitchedOffOrReplacedIsListedAndIsNeverSaidToBeUsed() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        EnvironmentView environment = fixture.service.environments().environments().get(0);

        // The file defines baseUrl twice, and the second definition is switched off.
        List<VariableView> baseUrls = environment.variables().stream().filter(each -> each.name().equals("baseUrl")).toList();
        assertThat(baseUrls).hasSize(2);
        assertThat(baseUrls.get(0).enabled()).isTrue();
        assertThat(baseUrls.get(0).inForce()).isTrue();
        assertThat(baseUrls.get(0).used()).isTrue();
        assertThat(baseUrls.get(0).display()).isEqualTo(SANDBOX_FHIR);
        assertThat(baseUrls.get(1).enabled()).isFalse();
        assertThat(baseUrls.get(1).inForce()).isFalse();
        assertThat(baseUrls.get(1).used()).isFalse();
        assertThat(baseUrls.get(1).usedBy()).isEmpty();
        assertThat(variable(environment, "retiredBaseUrl").inForce()).isFalse();
        assertThat(variable(environment, "retiredBaseUrl").used()).isFalse();

        // Two enabled definitions of one name: the last is the one in force, as in Postman.
        fixture.service.uploadEnvironment("twice.json", ("{\"name\": \"Twice\", \"values\": ["
                + "{\"key\": \"baseUrl\", \"value\": \"https://first.example.invalid\"},"
                + "{\"key\": \"baseUrl\", \"value\": \"https://second.example.invalid\"}]}").getBytes(), EnvironmentKind.SANDBOX, false);
        EnvironmentView twice = fixture.service.environments().environments().get(1);
        assertThat(twice.variables()).extracting(VariableView::inForce).containsExactly(false, true);
        assertThat(twice.variables()).extracting(VariableView::used).containsExactly(false, true);
    }

    @Test
    void eachVariableSaysWhetherTheToolUsesItAndWhere() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("auth.token-url", "{{tokenUrl}}").onyx("auth.client-secret", "{{clientSecret}}")
                .build();
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);

        // With no collection uploaded, only application.yml and the urls the tool builds refer to anything.
        EnvironmentView before = fixture.service.environments().environments().get(0);
        assertThat(variable(before, "baseUrl").used()).isFalse();
        assertThat(variable(before, "tokenUrl").usedBy()).containsExactly("application.yml: onyx.auth.token-url");
        assertThat(variable(before, "clientSecret").usedBy()).containsExactly("application.yml: onyx.auth.client-secret");
        // The value of tokenUrl refers to authHost, so authHost is used by way of it.
        assertThat(variable(before, "authHost").usedBy()).containsExactly("the variable \"tokenUrl\" of this environment");
        assertThat(variable(before, "crdOrderSignServiceId").usedBy())
                .containsExactly("the url this tool builds for CRD order-sign when no request is mapped to it");
        assertThat(variable(before, "clientId").used()).isFalse();
        assertThat(variable(before, "tenantCode").used()).isFalse();

        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        EnvironmentView after = fixture.service.environments().environments().get(0);
        VariableView baseUrl = variable(after, "baseUrl");
        assertThat(baseUrl.used()).isTrue();
        // Eleven requests refer to it. Five are named and the rest are counted.
        assertThat(baseUrl.usedBy()).hasSize(6).startsWith("the request \"$questionnaire-package Happy path\" of the collection \"Onyx Sandbox ePA\"")
                .endsWith("and 6 more");
        assertThat(variable(after, "crdUrl").usedBy()).containsExactly("the request \"order-sign Happy Path\" of the collection \"Onyx Sandbox ePA\"",
                "the request \"order-sign Error\" of the collection \"Onyx Sandbox ePA\"");
        // Used in a url by three requests and in a body by two of them and by the notification.
        assertThat(variable(after, "subscriptionId").used()).isTrue();
        assertThat(variable(after, "callbackUrl").used()).isTrue();
        // A header that is switched off in the collection uses nothing, so {{traceId}} would not count. Nothing refers to these.
        assertThat(variable(after, "clientId").used()).isFalse();
        assertThat(variable(after, "tenantCode").used()).isFalse();
    }

    @Test
    void aVariableOfACollectionIsUsedOnlyByThatCollection() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("crd.url", "https://{{tenant}}.example.invalid").build();
        fixture.collectionOf("Alpha", items(item("order-sign Happy Path", "POST", "https://alpha.example.invalid/cds-services/order-sign-{{tenant}}")),
                variables("tenant", "alpha", "unusedHere", "x"));
        // This collection defines the variable and none of its requests refers to it.
        fixture.collectionOf("Beta", items(item("order-select Happy Path", "POST", "https://beta.example.invalid/cds-services/order-select-b")),
                variables("tenant", "beta"));

        List<CollectionView> collections = fixture.service.collections().collections();

        VariableView alpha = collections.get(0).variables().get(0);
        assertThat(alpha.name()).isEqualTo("tenant");
        assertThat(alpha.display()).isEqualTo("alpha");
        assertThat(alpha.usedBy()).containsExactly("the request \"order-sign Happy Path\" of the collection \"Alpha\"");
        assertThat(collections.get(0).variables().get(1).used()).isFalse();
        // Neither the other collection's request nor application.yml ever sees this collection's variable.
        assertThat(collections.get(1).variables().get(0).used()).isFalse();
    }

    @Test
    void aSecretThatPointsAtTheOperatingSystemShowsWhereItIsReadFromAndNeverAValue() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "os-value-9034");

        EnvironmentUploaded uploaded = fixture.service.uploadEnvironment("production.json",
                PostmanFiles.bytes(PostmanFiles.PRODUCTION_ENVIRONMENT), EnvironmentKind.PROD, false);

        VariableView secret = variable(uploaded.environment(), "clientSecret");
        assertThat(secret.secret()).isTrue();
        assertThat(secret.set()).isTrue();
        // A reference is not a secret, so it is shown as written, with where it leads.
        assertThat(secret.display()).isEqualTo("{{env:PAVE_ONYX_CLIENT_SECRET}}");
        assertThat(secret.readsFrom()).isEqualTo("the operating system variable PAVE_ONYX_CLIENT_SECRET");
        assertThat(uploaded.warnings()).isEmpty();
        assertThat(uploaded.environment().productionSigns()).containsExactly("its name is \"Onyx Production\"");
        assertThat(json(uploaded)).doesNotContain("os-value-9034");
    }

    @Test
    void theListSaysWhichEnvironmentIsActiveAndSwitchingAnswersWithWhatIsNowInForce() {
        ConfigFixture fixture = ConfigFixture.on(directory).allowProdWrites().build();
        fixture.environment(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredEnvironment production = fixture.environment(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        assertThat(fixture.service.environments().active()).isNull();
        assertThat(fixture.service.environments().environments()).extracting(EnvironmentView::active).containsExactly(false, false);

        ConfigViews.Activated activated = fixture.service.activate("Onyx Sandbox", null);
        assertThat(activated.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(activated.environment().kind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(activated.epoch()).isEqualTo(fixture.gateway.epoch());
        assertThat(activated.writeAccess().writesAllowed()).isTrue();
        assertThat(fixture.service.environments().active()).isEqualTo("Onyx Sandbox");
        assertThat(fixture.service.environments().environments()).extracting(EnvironmentView::active).containsExactly(true, false);
        assertThat(fixture.service.environments().epoch()).isEqualTo(fixture.gateway.epoch());

        // By its id as well as by its name.
        ConfigViews.Activated toProduction = fixture.service.activate(production.id(), null);
        assertThat(toProduction.environment().production()).isTrue();
        assertThat(toProduction.writeAccess().production()).isTrue();
        assertThat(toProduction.writeAccess().writesAllowed()).isFalse();
        // A switch drops the unlock, and the answer to the session that held it says so.
        ProdUnlock unlock = fixture.service.unlock("Onyx Production");
        assertThat(fixture.service.writeAccess(unlock).writesAllowed()).isTrue();
        fixture.service.activate("Onyx Sandbox", unlock);
        assertThat(fixture.service.activate("Onyx Production", unlock).writeAccess().writesAllowed()).isFalse();

        assertThatThrownBy(() -> fixture.service.activate("No such environment", null)).isInstanceOf(NotFoundException.class)
                .hasMessage("The environment is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read.");
        assertThat(fixture.service.removeEnvironment("Onyx Production").wasActive()).isTrue();
        assertThat(fixture.service.environments().active()).isNull();
        assertThatThrownBy(() -> fixture.service.removeEnvironment("Onyx Production")).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Collections
    // ------------------------------------------------------------------------------------------

    @Test
    void anUploadedCollectionListsEveryRequestWithItsDetectedMapping() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);

        CollectionUploaded uploaded = fixture.service.uploadCollection("vendor.json", PostmanFiles.bytes(PostmanFiles.VENDOR_COLLECTION));

        CollectionView collection = uploaded.collection();
        assertThat(collection.id()).matches("onyx-sandbox-epa-[0-9a-f]{8}");
        assertThat(collection.name()).isEqualTo("Onyx Sandbox ePA");
        assertThat(collection.schema()).contains("v2.1.0");
        assertThat(collection.originalFileName()).isEqualTo("vendor.json");
        assertThat(collection.requests()).hasSize(13);
        assertThat(uploaded.replaced()).isFalse();
        // The vendor collection saves example responses, which can hold member data; nothing else is warned about.
        assertThat(uploaded.warnings()).singleElement().satisfies(warning -> assertThat(warning).startsWith("2 requests, the first being "
                + "\"DTR / $questionnaire-package Error\", carry a saved example response"));
        assertThat(uploaded.epoch()).isEqualTo(fixture.gateway.epoch());

        RequestSummary submit = request(collection, "submit Happy Path");
        assertThat(submit.id()).matches("r-[0-9a-f]{12}");
        assertThat(submit.folderPath()).containsExactly("PAS");
        assertThat(submit.method()).isEqualTo("POST");
        assertThat(submit.urlTemplate()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(submit.detectedOperation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(submit.detectionBasis()).isEqualTo("The name contains \"submit\", and the url has a $submit segment.");
        assertThat(submit.detectionConflict()).isFalse();
        assertThat(submit.correctedOperation()).isNull();
        assertThat(submit.effectiveOperation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(submit.effectiveLabel()).isEqualTo("PAS submit");
        assertThat(submit.guardedAsWrite()).isTrue();
        // Marked wherever it appears: Postman would have run a script before this request, and this tool does not.
        assertThat(submit.scripted()).isTrue();
        assertThat(submit.hasBody()).isTrue();
        assertThat(submit.exampleCount()).isZero();

        RequestSummary orderSign = request(collection, "order-sign Happy Path");
        assertThat(orderSign.scripted()).isFalse();
        assertThat(orderSign.exampleCount()).isEqualTo(1);
        assertThat(orderSign.guardedAsWrite()).isFalse();
        RequestSummary get = request(collection, "subscription-get Happy Path");
        assertThat(get.hasBody()).isFalse();
        assertThat(get.guardedAsWrite()).isFalse();
        assertThat(get.effectiveLabel()).isEqualTo("Subscription get");
        // The sample notification is listed as what it is. Sent raw as the POST it is, it would count as a write.
        RequestSummary notification = request(collection, "subscription-notification Happy Path");
        assertThat(notification.effectiveOperation()).isEqualTo(EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(notification.guardedAsWrite()).isTrue();
        assertThat(collection.requests()).filteredOn(RequestSummary::guardedAsWrite).extracting(RequestSummary::name).containsExactly(
                "submit Happy Path", "subscription-create Happy Path", "subscription-update Happy Path", "subscription-delete Happy Path",
                "subscription-notification Happy Path");
        assertThat(fixture.service.collections().collections()).hasSize(1);
        assertThat(fixture.service.collections().epoch()).isEqualTo(fixture.gateway.epoch());
    }

    @Test
    void scriptsAuthenticationHelpersAndWhatTheParserSetAsideAreReportedOnEachRequest() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        CollectionUploaded uploaded = fixture.service.uploadCollection("nested.json", PostmanFiles.bytes(PostmanFiles.NESTED_COLLECTION));

        CollectionView collection = uploaded.collection();
        assertThat(collection.notes()).containsExactly("1 entry is neither a folder nor a request and is not listed.");
        RequestSummary deep = request(collection, "deep order-select");
        assertThat(deep.folderPath()).containsExactly("Level one", "Level two", "Level three");
        // Inherited from the collection and from a folder two levels up.
        assertThat(deep.scripted()).isTrue();
        assertThat(deep.testScripted()).isFalse();
        assertThat(deep.authHelper()).isEqualTo("bearer, set on the collection");
        assertThat(deep.exampleCount()).isEqualTo(2);
        assertThat(deep.notes()).containsExactly("The url has no raw form in the file, so it was rebuilt from its parts.");
        assertThat(request(collection, "scripted inquiry").testScripted()).isTrue();
        assertThat(request(collection, "subscription by path variable").authHelper()).isNull();
        assertThat(request(collection, "subscription by path variable").guardedAsWrite()).isTrue();
        // The collection's secret variable is masked, and the warning names it.
        VariableView key = collection.variables().get(2);
        assertThat(key.name()).isEqualTo("collectionKey");
        assertThat(key.secret()).isTrue();
        assertThat(key.display()).isEqualTo("[secret collectionKey]");
        assertThat(key.usedBy()).containsExactly("the request \"deep order-select\" of the collection \"Nested sample\"");
        assertThat(collection.variables().get(3).enabled()).isFalse();
        assertThat(uploaded.warnings().get(0)).startsWith("The variable \"collectionKey\" holds a secret in plain text");
        assertThat(json(uploaded) + uploaded).doesNotContain("placeholder-collection-key");
    }

    @Test
    void whetherARequestCountsAsAWriteIsTheGatewaysJudgementUnderTheActiveEnvironment() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.environmentOf("Inquires", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid/fhir", "op", "$inquire");
        fixture.environmentOf("Submits", EnvironmentKind.SANDBOX, "baseUrl", "https://x.example.invalid/fhir", "op", "$submit");
        fixture.collectionOf("By variable", items(item("status inquiry", "POST", "{{baseUrl}}/Claim/{{op}}")));

        fixture.store.activate("Inquires");
        assertThat(fixture.service.collections().collections().get(0).requests().get(0).guardedAsWrite()).isFalse();
        // The same request, and the url it resolves to now reads as the submit.
        fixture.store.activate("Submits");
        assertThat(fixture.service.collections().collections().get(0).requests().get(0).guardedAsWrite()).isTrue();
    }

    @Test
    void oneRequestInFullHasItsHeadersItsBodyVerbatimAndItsSavedExamplesWithTheirBodies() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        StoredCollection nested = fixture.collection(PostmanFiles.NESTED_COLLECTION);

        RequestDetail orderSign = fixture.service.request(vendor.id(), ref(vendor, "order-sign Happy Path").requestId());
        assertThat(orderSign.collectionId()).isEqualTo(vendor.id());
        assertThat(orderSign.collectionName()).isEqualTo("Onyx Sandbox ePA");
        assertThat(orderSign.request().name()).isEqualTo("order-sign Happy Path");
        assertThat(orderSign.headers()).containsExactly(new HeaderView("Content-Type", "application/json", false),
                new HeaderView("Accept", "application/json", false), new HeaderView("X-Request-Trace", "{{traceId}}", true));
        assertThat(orderSign.bodyMode()).isEqualTo("raw");
        assertThat(orderSign.bodyLanguage()).isEqualTo("json");
        // Verbatim: the vendor's sample, with its line breaks and its placeholders, ready to load into a form.
        assertThat(orderSign.body()).isEqualTo(vendor.request(orderSign.request().id()).request().body())
                .startsWith("{\n  \"hook\": \"order-sign\",\n  \"hookInstance\": \"{{$guid}}\",");
        assertThat(orderSign.examples()).hasSize(1);
        assertThat(orderSign.examples().get(0).name()).isEqualTo("Authorization required");
        assertThat(orderSign.examples().get(0).code()).isEqualTo(200);
        assertThat(orderSign.examples().get(0).status()).isEqualTo("OK");
        assertThat(orderSign.examples().get(0).body()).startsWith("{\n  \"cards\": [").contains("Prior authorization is required");
        assertThat(orderSign.examples().get(0).headers()).containsExactly(new HeaderView("Content-Type", "application/json", false));
        assertThat(orderSign.scripts()).isEmpty();
        assertThat(orderSign.auth()).isNull();

        RequestDetail deep = fixture.service.request(nested.id(), ref(nested, "deep order-select").requestId());
        assertThat(deep.description()).isEqualTo("A request three folders down.");
        assertThat(deep.scripts()).extracting(ConfigViews.ScriptView::from).containsExactly("the collection", "the folder \"Level two\"");
        assertThat(deep.scripts().get(1).source()).isEqualTo("pm.variables.set(\"folderStamp\", Date.now());");
        assertThat(deep.auth().type()).isEqualTo("bearer");
        assertThat(deep.auth().variables()).containsExactly("accessToken");
        assertThat(deep.auth().note()).startsWith("This tool does not apply Postman's authentication helpers.");
        assertThat(deep.examples()).extracting(ConfigViews.ExampleView::code).containsExactly(200, 503);
        assertThat(deep.examples().get(1).body()).isNull();
        RequestDetail byPath = fixture.service.request(nested.id(), ref(nested, "subscription by path variable").requestId());
        assertThat(byPath.pathVariables()).containsExactly(new ConfigViews.PathVariableView("id", "saved-subscription-7"));

        assertThatThrownBy(() -> fixture.service.request("no-such-collection", "r-000000000000")).isInstanceOf(NotFoundException.class)
                .hasMessage("The collection is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read.");
        assertThatThrownBy(() -> fixture.service.request(vendor.id(), "r-000000000000")).isInstanceOf(NotFoundException.class)
                .hasMessage("That request of the collection \"Onyx Sandbox ePA\" is no longer held by this tool. It may have been removed, or uploaded again, in another window since the list was read.");
    }

    @Test
    void aCredentialTypedLiterallyIntoAHeaderIsNeverShownAndAPlaceholderIs() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection collection = fixture.collectionOf("Credentials", items(item("inquiry Happy Path", "POST", "https://x.example.invalid/Claim/$inquire")
                .header("Authorization", "Bearer literal-token-typed-in").header("X-Api-Key", "{{collectionKey}}")
                .header("Proxy-Authorization", "Basic {{encodedPair}}").header("Cookie", "session=literal-session")
                .header("X-Trace", "plain-trace")));

        RequestDetail detail = fixture.service.request(collection.id(), collection.requests().get(0).request().id());

        assertThat(detail.headers()).containsExactly(new HeaderView("Authorization", "Bearer [redacted]", false),
                // Made of a placeholder, or of a well known scheme and placeholders: nothing in it is a value.
                new HeaderView("X-Api-Key", "{{collectionKey}}", false), new HeaderView("Proxy-Authorization", "Basic {{encodedPair}}", false),
                new HeaderView("Cookie", "[redacted]", false), new HeaderView("X-Trace", "plain-trace", false));
        assertThat(json(detail) + detail).doesNotContain("literal-token-typed-in").doesNotContain("literal-session");
    }

    @Test
    void aMappingCorrectionAnswersWithWhatChangedAndWithWhatItCannotChange() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection vendor = fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        String inquiry = ref(vendor, "inquiry Happy Path").requestId();
        String submit = ref(vendor, "submit Happy Path").requestId();

        MappingChanged corrected = fixture.service.correctMapping(vendor.id(), inquiry, EpaOperation.UNMAPPED);
        assertThat(corrected.changed()).isTrue();
        assertThat(corrected.collectionId()).isEqualTo(vendor.id());
        assertThat(corrected.epoch()).isEqualTo(fixture.gateway.epoch());
        assertThat(corrected.request().detectedOperation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(corrected.request().correctedOperation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(corrected.request().effectiveOperation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(corrected.notes()).containsExactly("The request is now not mapped to any operation, so it is sent only as written, from the "
                + "Config tab. Detection proposed PAS inquire.");

        MappingChanged again = fixture.service.correctMapping(vendor.id(), inquiry, EpaOperation.UNMAPPED);
        assertThat(again.changed()).isFalse();
        assertThat(again.epoch()).isEqualTo(corrected.epoch());
        assertThat(again.notes()).containsExactly("The request already was not mapped to any operation. Nothing changed.");

        MappingChanged back = fixture.service.correctMapping(vendor.id(), inquiry, EpaOperation.PAS_INQUIRE);
        assertThat(back.request().correctedOperation()).isNull();
        assertThat(back.notes()).containsExactly("That is what detection proposed, so the correction was removed and the mapping follows detection again.");

        // Mapping the submit request to a read does not make it one, and the answer says so.
        MappingChanged dressedUp = fixture.service.correctMapping(vendor.id(), submit, EpaOperation.PAS_INQUIRE);
        assertThat(dressedUp.request().effectiveOperation()).isEqualTo(EpaOperation.PAS_INQUIRE);
        assertThat(dressedUp.request().guardedAsWrite()).isTrue();
        assertThat(dressedUp.notes()).hasSize(2);
        assertThat(dressedUp.notes().get(1)).isEqualTo("This request still counts as a write, because of its name, its url or its method. "
                + "Mapping it to PAS inquire does not change that: on a production environment it stays blocked until writes are unlocked.");

        assertThatThrownBy(() -> fixture.service.correctMapping(vendor.id(), submit, EpaOperation.DTR_RESPONSE)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> fixture.service.correctMapping(vendor.id(), "r-000000000000", EpaOperation.PAS_SUBMIT))
                .isInstanceOf(NotFoundException.class);
        assertThat(fixture.service.removeCollection(vendor.id()).removed()).isEqualTo("Onyx Sandbox ePA");
        assertThatThrownBy(() -> fixture.service.removeCollection(vendor.id())).isInstanceOf(NotFoundException.class);
    }

    // ------------------------------------------------------------------------------------------
    // Everything in force
    // ------------------------------------------------------------------------------------------

    @Test
    void everythingInForceHasARowForEveryOperationWithWhatTheOperationSaysAboutItself() {
        Effective effective = ConfigFixture.mock(directory).service.effective(null);

        assertThat(effective.operations()).extracting(OperationRow::operation).containsExactly(EpaOperation.values());
        for (OperationRow row : effective.operations()) {
            EpaOperation operation = row.operation();
            // The browser keeps no copy of these, so each row carries them.
            assertThat(row.label()).as(operation.name()).isEqualTo(operation.label());
            assertThat(row.protocol()).as(operation.name()).isEqualTo(operation.protocol());
            assertThat(row.write()).as(operation.name()).isEqualTo(operation.write());
            assertThat(row.outbound()).as(operation.name()).isEqualTo(operation.outbound());
        }
        assertThat(row(effective, EpaOperation.PAS_SUBMIT).write()).isTrue();
        assertThat(row(effective, EpaOperation.PAS_INQUIRE).write()).isFalse();
        assertThat(row(effective, EpaOperation.MEMBER_LOOKUP).protocol()).isEqualTo(Protocol.LOOKUP);
        assertThat(effective.mode()).isEqualTo("mock");
        assertThat(effective.environment().active()).isFalse();
        assertThat(effective.problems()).isEmpty();
        assertThat(effective.allowProdWrites()).isFalse();
        assertThat(effective.storeDirectory()).isEqualTo(directory.toString());
        assertThat(effective.writeAccess().writesAllowed()).isTrue();
    }

    @Test
    void beforeAnythingIsUploadedEachOperationSaysPlainlyWhatItIsMissing() {
        Effective mock = ConfigFixture.mock(directory.resolve("mock")).service.effective(null);
        Effective live = ConfigFixture.live(directory.resolve("live")).service.effective(null);

        OperationRow mockSign = row(mock, EpaOperation.CRD_ORDER_SIGN);
        assertThat(mockSign.target()).isNull();
        assertThat(mockSign.method()).isEqualTo("POST");
        assertThat(mockSign.requests()).isEmpty();
        // On mock a missing url is not a reason the operation cannot run, and the row says so.
        assertThat(mockSign.unavailable()).isNull();
        assertThat(mockSign.note()).isEqualTo("No collection request is mapped to CRD order-sign, and onyx.crd.url is not set. On the mock "
                + "profile nothing is sent, so this still runs.");
        assertThat(mockSign.blocked()).isNull();

        OperationRow liveSign = row(live, EpaOperation.CRD_ORDER_SIGN);
        assertThat(liveSign.unavailable()).isEqualTo("No collection request is mapped to CRD order-sign, and onyx.crd.url is not set.");
        assertThat(liveSign.note()).isNull();
        // On live a write with no environment is refused whatever its target, and the row says that too.
        assertThat(row(live, EpaOperation.PAS_SUBMIT).blocked()).startsWith("PAS submit is blocked: no environment is active");
        assertThat(row(mock, EpaOperation.PAS_SUBMIT).blocked()).isNull();
        assertThat(live.mode()).isEqualTo("live");

        for (Effective effective : List.of(mock, live)) {
            // An operation that is not outbound has no target and says why.
            OperationRow notification = row(effective, EpaOperation.SUBSCRIPTION_NOTIFICATION);
            assertThat(notification.target()).isNull();
            assertThat(notification.method()).isNull();
            assertThat(notification.unavailable()).startsWith("A notification is sent to this tool by the far side. The tool never sends one, "
                    + "so it has no target.");
            OperationRow response = row(effective, EpaOperation.DTR_RESPONSE);
            assertThat(response.target()).isNull();
            assertThat(response.unavailable()).isEqualTo("This step is completed inside the tool. Nothing is sent for it, so it has no target.");
            OperationRow unmapped = row(effective, EpaOperation.UNMAPPED);
            assertThat(unmapped.target()).isNull();
            assertThat(unmapped.unavailable()).isNull();
            assertThat(unmapped.note()).startsWith("A request this tool could not map has no target of its own.");
        }
    }

    @Test
    void afterAnUploadEachOperationShowsItsTargetAndTheRequestsMappedToIt() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        StoredCollection vendor = fixture.store.snapshot().collections().get(0);

        Effective effective = fixture.service.effective(null);

        assertThat(effective.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(effective.environment().kind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(effective.epoch()).isEqualTo(fixture.gateway.epoch());
        OperationRow orderSign = row(effective, EpaOperation.CRD_ORDER_SIGN);
        assertThat(orderSign.method()).isEqualTo("POST");
        assertThat(orderSign.unavailable()).isNull();
        assertThat(orderSign.note()).isNull();
        TargetView target = orderSign.target();
        assertThat(target.template()).isEqualTo("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}");
        assertThat(target.display()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services/order-sign-crd");
        assertThat(target.resolved()).isTrue();
        assertThat(target.unresolved()).isEmpty();
        assertThat(target.tokens()).isEmpty();
        assertThat(target.source()).isEqualTo("the request \"CRD / order-sign Happy Path\" of the collection \"Onyx Sandbox ePA\"");
        assertThat(target.requestName()).isEqualTo("order-sign Happy Path");
        assertThat(target.request()).isEqualTo(ref(vendor, "order-sign Happy Path"));
        assertThat(target.scripted()).isFalse();
        // Both requests mapped to the operation are offered, and the one a call uses unasked is marked.
        assertThat(orderSign.requests()).extracting(available -> available.request().name()).containsExactly("order-sign Happy Path", "order-sign Error");
        assertThat(orderSign.requests()).extracting(AvailableRequest::usedByDefault).containsExactly(true, false);
        assertThat(orderSign.requests().get(0).collectionId()).isEqualTo(vendor.id());
        assertThat(orderSign.requests().get(0).collectionName()).isEqualTo("Onyx Sandbox ePA");
        assertThat(orderSign.defaultRule()).isEqualTo("Of the 2 requests mapped to CRD order-sign, a run that names none uses \"CRD / "
                + "order-sign Happy Path\" of the collection \"Onyx Sandbox ePA\", because it comes first in the order the collections and "
                + "their requests are listed. To have another of them used, take off CRD order-sign each request listed above that one, "
                + "apart from a sample of an error.");

        assertThat(row(effective, EpaOperation.PAS_SUBMIT).target().scripted()).isTrue();
        // Nothing in the vendor's collection is an order-select, and that is visible, not silently absent.
        OperationRow orderSelect = row(effective, EpaOperation.CRD_ORDER_SELECT);
        assertThat(orderSelect.target()).isNull();
        assertThat(orderSelect.requests()).isEmpty();
        assertThat(orderSelect.note()).startsWith("No collection request is mapped to CRD order-select, and onyx.crd.url is not set.");
        // Discovery is derived from the hook, and says where it came from.
        assertThat(row(effective, EpaOperation.CRD_DISCOVERY).target().display()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services");
        assertThat(row(effective, EpaOperation.CRD_DISCOVERY).target().requestName()).isNull();
        assertThat(row(effective, EpaOperation.CRD_DISCOVERY).target().source()).endsWith("cut after /cds-services");
        // The sample notification is listed under the operation it is, which has no target.
        OperationRow notification = row(effective, EpaOperation.SUBSCRIPTION_NOTIFICATION);
        assertThat(notification.requests()).extracting(available -> available.request().name()).containsExactly("subscription-notification Happy Path");
        assertThat(notification.requests().get(0).usedByDefault()).isFalse();
        assertThat(notification.target()).isNull();
        assertThat(row(effective, EpaOperation.UNMAPPED).requests()).isEmpty();
    }

    @Test
    void aRowWithSeveralRequestsSaysWhichOneARunUsesWhyAndHowToHaveAnotherUsed() {
        ConfigFixture fixture = ConfigFixture.mock(directory.resolve("rule"));
        fixture.collectionOf("Several", ConfigFixture.items(
                ConfigFixture.item("order-sign Error", "POST", "https://crd.example.invalid/cds-services/order-sign-crd"),
                ConfigFixture.item("order-sign Happy Path", "POST", "https://crd.example.invalid/cds-services/order-sign-crd"),
                ConfigFixture.item("order-sign Other", "POST", "https://crd.example.invalid/cds-services/order-sign-crd"),
                ConfigFixture.item("order-select Happy Path", "POST", "https://crd.example.invalid/cds-services/order-select-crd"),
                ConfigFixture.item("inquiry Error", "POST", "https://pas.example.invalid/Claim/$inquire"),
                ConfigFixture.item("inquiry Error Two", "POST", "https://pas.example.invalid/Claim/$inquire")));
        Effective effective = fixture.service.effective(null);

        OperationRow sign = row(effective, EpaOperation.CRD_ORDER_SIGN);
        assertThat(sign.requests()).extracting(AvailableRequest::usedByDefault).containsExactly(false, true, false);
        assertThat(sign.defaultRule()).isEqualTo("Of the 3 requests mapped to CRD order-sign, a run that names none uses \"order-sign "
                + "Happy Path\" of the collection \"Several\", because it comes first in the order the collections and their requests are "
                + "listed, after 1 request whose name contains \"error\" and which is used only when chosen. To have another of them used, "
                + "take off CRD order-sign each request listed above that one, apart from a sample of an error.");
        // One request: nothing to choose between, so nothing is said.
        assertThat(row(effective, EpaOperation.CRD_ORDER_SELECT).requests()).hasSize(1);
        assertThat(row(effective, EpaOperation.CRD_ORDER_SELECT).defaultRule()).isNull();
        assertThat(row(effective, EpaOperation.PAS_SUBMIT).defaultRule()).isNull();
        // Only samples of an error: none is used unasked, and taking one off would not change that.
        OperationRow inquire = row(effective, EpaOperation.PAS_INQUIRE);
        assertThat(inquire.requests()).extracting(AvailableRequest::usedByDefault).containsExactly(false, false);
        assertThat(inquire.defaultRule()).isEqualTo("None of the 2 requests mapped to PAS inquire is used when a run names none: the name "
                + "of each contains \"error\", so each is taken for a sample of an error, which is used only when it is chosen. To have one "
                + "used by every run, map to PAS inquire a request whose name does not contain \"error\".");

        // Taking the one in use off moves the run to the next one, and the sentence follows.
        String collectionId = effective.operations().stream().filter(r -> r.operation() == EpaOperation.CRD_ORDER_SIGN).findFirst()
                .orElseThrow().requests().get(1).collectionId();
        fixture.service.correctMapping(collectionId, sign.requests().get(1).request().id(), EpaOperation.UNMAPPED);
        OperationRow after = row(fixture.service.effective(null), EpaOperation.CRD_ORDER_SIGN);
        assertThat(after.requests()).extracting(AvailableRequest::usedByDefault).containsExactly(false, true);
        assertThat(after.defaultRule()).startsWith("Of the 2 requests mapped to CRD order-sign, a run that names none uses \"order-sign Other\"");
    }

    @Test
    void aHeaderThatStillNeedsAValueMakesTheOperationUnavailableOnLiveAndIsNotCalledRunnableOnMock() {
        String items = ConfigFixture.items(ConfigFixture.item("order-sign Happy Path", "POST", "{{baseUrl}}/cds-services/order-sign-crd")
                .header("Authorization", "Bearer {{client_secret}}").header("X-Trace", "{{traceId}}"));
        ConfigFixture mock = ConfigFixture.mock(directory.resolve("mock"));
        mock.collectionOf("Headers", items);
        mock.activeEnvironmentOf("Review Prod", EnvironmentKind.PROD, "baseUrl", "https://crd.example.invalid");
        ConfigFixture live = ConfigFixture.live(directory.resolve("live"));
        live.collectionOf("Headers", items);
        live.activeEnvironmentOf("Review Prod", EnvironmentKind.PROD, "baseUrl", "https://crd.example.invalid");

        // The url is complete, so only the headers can say the run would be refused.
        OperationRow liveSign = row(live.service.effective(null), EpaOperation.CRD_ORDER_SIGN);
        assertThat(liveSign.target().resolved()).isTrue();
        assertThat(liveSign.unavailable()).isEqualTo("The header Authorization still needs a value for {{client_secret}}, the header X-Trace "
                + "still needs a value for {{traceId}}, and the active environment is \"Review Prod\". Give each of {{client_secret}}, {{traceId}} "
                + "a value in an environment on the Config tab, or type them among the values for this run.");
        // On mock a header with no value is refused too, so the row never says it still runs.
        OperationRow mockSign = row(mock.service.effective(null), EpaOperation.CRD_ORDER_SIGN);
        assertThat(mockSign.unavailable()).isNull();
        assertThat(mockSign.note()).isEqualTo("The header Authorization still needs a value for {{client_secret}}, the header X-Trace "
                + "still needs a value for {{traceId}}, and the active environment is \"Review Prod\". On the mock profile nothing is sent, "
                + "but a header with no value is refused before a scenario answers, so a run is refused here too. Give each of "
                + "{{client_secret}}, {{traceId}} a value in an environment on the Config tab, or type them among the values for this run.");
        // The preview of the request says the same.
        assertThat(live.service.resolve(EpaOperation.CRD_ORDER_SIGN, null, Map.of(), Map.of(), null).unavailable())
                .isEqualTo(liveSign.unavailable());
        assertThat(mock.service.resolve(EpaOperation.CRD_ORDER_SIGN, null, Map.of(), Map.of(), null).notes()).contains(mockSign.note());
        // A value for one run supplies a header as it supplies the url.
        assertThat(live.service.resolve(EpaOperation.CRD_ORDER_SIGN, null, Map.of("client_secret", "placeholder-value", "traceId", "t-1"),
                Map.of(), null).unavailable()).isNull();
    }

    @Test
    void aTargetThatStillNeedsAValueSaysWhichAndATokenIsNotCountedAsMissing() {
        ConfigFixture mock = ConfigFixture.on(directory.resolve("mock")).lookup("member.url", "https://lookup.example.invalid/members/{id}").build();
        mock.collection(PostmanFiles.VENDOR_COLLECTION);
        ConfigFixture live = ConfigFixture.on(directory.resolve("live")).live().lookup("member.url", "https://lookup.example.invalid/members/{id}")
                .build();
        live.collection(PostmanFiles.VENDOR_COLLECTION);

        // No environment is active, so {{baseUrl}} has no value.
        OperationRow mockInquire = row(mock.service.effective(null), EpaOperation.PAS_INQUIRE);
        assertThat(mockInquire.target().resolved()).isFalse();
        assertThat(mockInquire.target().unresolved()).containsExactly("baseUrl");
        // Whatever did resolve is filled in and the rest is left as written.
        assertThat(mockInquire.target().display()).isEqualTo("{{baseUrl}}/Claim/$inquire");
        assertThat(mockInquire.note()).isEqualTo("The url still needs a value for {{baseUrl}}, and no environment is active. On the mock "
                + "profile nothing is sent, so this still runs.");
        assertThat(mockInquire.unavailable()).isNull();
        OperationRow liveInquire = row(live.service.effective(null), EpaOperation.PAS_INQUIRE);
        assertThat(liveInquire.unavailable()).isEqualTo("The url still needs a value for {{baseUrl}}, and no environment is active. Give {{baseUrl}} "
                + "a value in an environment on the Config tab, or type it among the values for this run.");
        assertThat(liveInquire.note()).isNull();
        // The service id comes from the collection's own variable, so only the base is missing.
        TargetView orderSign = row(live.service.effective(null), EpaOperation.CRD_ORDER_SIGN).target();
        assertThat(orderSign.unresolved()).containsExactly("crdUrl");
        assertThat(orderSign.display()).isEqualTo("{{crdUrl}}/cds-services/collection-order-sign-service");

        // A lookup url is complete as configured. Its {id} is filled when a lookup is made.
        for (ConfigFixture fixture : List.of(mock, live)) {
            OperationRow lookup = row(fixture.service.effective(null), EpaOperation.MEMBER_LOOKUP);
            assertThat(lookup.target().tokens()).containsExactly("id");
            assertThat(lookup.target().unresolved()).isEmpty();
            assertThat(lookup.target().resolved()).isTrue();
            assertThat(lookup.target().display()).isEqualTo("https://lookup.example.invalid/members/{id}");
            assertThat(lookup.target().source()).isEqualTo("application.yml: lookup.member.url");
            assertThat(lookup.unavailable()).isNull();
            assertThat(lookup.note()).isNull();
            assertThat(lookup.method()).isEqualTo("GET");
        }
    }

    @Test
    void onProductionEachWriteSaysItIsBlockedAndTheUnlockOfThisSessionIsWhatChangesThat() {
        for (String profile : List.of(EpaProperties.MOCK, EpaProperties.LIVE)) {
            ConfigFixture fixture = ConfigFixture.on(directory.resolve(profile)).mode(profile).allowProdWrites().build();
            fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);

            Effective locked = fixture.service.effective(null);
            assertThat(locked.environment().production()).isTrue();
            assertThat(locked.allowProdWrites()).isTrue();
            assertThat(locked.writeAccess().production()).isTrue();
            assertThat(locked.writeAccess().writesAllowed()).isFalse();
            for (OperationRow row : locked.operations()) {
                if (row.write()) {
                    assertThat(row.blocked()).as(profile + " " + row.operation()).contains("\"Onyx Production\" is a production environment and "
                            + "production writes are locked for this session.").contains("unlock production writes on the Config tab");
                    // The target is still shown, so a tester can look before deciding whether to unlock.
                    assertThat(row.target()).as(profile + " " + row.operation()).isNotNull();
                } else {
                    assertThat(row.blocked()).as(profile + " " + row.operation()).isNull();
                }
            }

            ProdUnlock unlock = fixture.service.unlock("Onyx Production");
            Effective unlocked = fixture.service.effective(unlock);
            assertThat(unlocked.writeAccess().writesAllowed()).as(profile).isTrue();
            assertThat(unlocked.operations()).as(profile).allMatch(row -> row.blocked() == null);
            // Another session, which holds no unlock, is told the opposite at the same moment.
            assertThat(row(fixture.service.effective(null), EpaOperation.PAS_SUBMIT).blocked()).as(profile).isNotNull();

            fixture.service.lock();
            assertThat(row(fixture.service.effective(unlock), EpaOperation.PAS_SUBMIT).blocked()).as(profile)
                    .contains("An earlier unlock no longer applies");
            assertThat(fixture.service.writeAccess(unlock).writesAllowed()).as(profile).isFalse();
        }
    }

    @Test
    void aStartupProblemIsReportedWithEverythingInForce() {
        ConfigFixture fixture = ConfigFixture.on(directory).activeEnvironment("Onyx Sandbox").build();

        String problem = "config-store.active-environment names \"Onyx Sandbox\", which is not in the configuration store, so it was not "
                + "activated at startup. Upload an environment with exactly that name and activate it, or clear the property.";
        assertThat(fixture.service.effective(null).problems()).containsExactly(problem);
        assertThat(fixture.service.environments().problems()).containsExactly(problem);
        assertThat(fixture.service.effective(null).environment().active()).isFalse();
    }

    @Test
    void everythingInForceIsBuiltFromMaskedRowsAndNeverFromAPropertiesRecord() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("auth.enabled", "true").onyx("auth.token-url", "{{tokenUrl}}")
                .onyx("auth.client-id", "pave-client").onyx("auth.client-secret", "hunter2-in-yml").onyx("variables.apiKey", "hunter2-variable")
                .onyx("variables.memberKey", "{{env:PAVE_MEMBER_KEY}}").onyx("variables.region", "east").onyx("pas.url", "{{baseUrl}}")
                .lookup("member.url", "https://lookup.example.invalid/members/{id}?key={{apiKey}}")
                .lookup("member.headers.X-Api-Key", "hunter2-header").lookup("member.headers.Authorization", "Bearer {{memberKey}}")
                .lookup("member.headers.X-Region", "{{region}}").build();
        fixture.operatingSystem.put("PAVE_MEMBER_KEY", "hunter2-from-os");
        fixture.operatingSystem.put("PAVE_ONYX_CLIENT_SECRET", "hunter2-client-secret");
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Keyed", items(item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire?secret={{clientSecret}}")));

        Effective effective = fixture.service.effective(null);

        Map<String, SettingRow> settings = new java.util.LinkedHashMap<>();
        effective.settings().forEach(setting -> settings.put(setting.property(), setting));
        assertThat(settings.get("onyx.auth.client-secret")).isEqualTo(new SettingRow("onyx.auth.client-secret",
                "[secret onyx.auth.client-secret]", true, true));
        assertThat(settings.get("onyx.auth.client-id")).isEqualTo(new SettingRow("onyx.auth.client-id", "pave-client", true, false));
        assertThat(settings.get("onyx.auth.token-url").value()).isEqualTo("{{tokenUrl}}");
        assertThat(settings.get("onyx.variables.apiKey")).isEqualTo(new SettingRow("onyx.variables.apiKey", "[secret onyx.variables.apiKey]", true, true));
        // A reference to the operating system is shown as written, and marked secret because what it leads to is one.
        assertThat(settings.get("onyx.variables.memberKey")).isEqualTo(new SettingRow("onyx.variables.memberKey", "{{env:PAVE_MEMBER_KEY}}", true, true));
        assertThat(settings.get("onyx.variables.region")).isEqualTo(new SettingRow("onyx.variables.region", "east", true, false));
        assertThat(settings.get("lookup.member.headers.X-Api-Key")).isEqualTo(new SettingRow("lookup.member.headers.X-Api-Key", "[redacted]", true, true));
        assertThat(settings.get("lookup.member.headers.Authorization").value()).isEqualTo("Bearer {{memberKey}}");
        assertThat(settings.get("lookup.member.headers.X-Region")).isEqualTo(new SettingRow("lookup.member.headers.X-Region", "{{region}}", true, false));
        // A setting that is not set says so, and is not shown as an empty value.
        assertThat(settings.get("onyx.crd.url")).isEqualTo(new SettingRow("onyx.crd.url", null, false, false));
        assertThat(settings.get("config-store.allow-prod-writes").value()).isEqualTo("false");
        assertThat(settings.get("config-store.directory").value()).isEqualTo(directory.toString());
        assertThat(settings.keySet()).contains("onyx.pas.url", "onyx.dtr.url", "onyx.subscription.url", "onyx.subscription.callback-url",
                "onyx.connect-timeout-ms", "onyx.read-timeout-ms", "onyx.auth.enabled", "onyx.auth.scopes", "onyx.pas.attachment-mode",
                "lookup.member.url", "lookup.member.enabled", "lookup.provider.url", "lookup.provider.use-onyx-auth",
                "config-store.active-environment");

        // A target is shown with its secrets masked, by the name of the variable.
        assertThat(row(effective, EpaOperation.PAS_INQUIRE).target().display()).isEqualTo(SANDBOX_FHIR + "/Claim/$inquire?secret=[secret clientSecret]");
        assertThat(row(effective, EpaOperation.MEMBER_LOOKUP).target().display())
                .isEqualTo("https://lookup.example.invalid/members/{id}?key=[secret apiKey]");

        String everything = json(effective) + effective;
        assertThat(everything).doesNotContain("hunter2").doesNotContain("placeholder-sandbox-secret").doesNotContain("placeholder-tenant-value");
        // The serialised form of the properties records is nowhere in it: the rows are this tool's own.
        assertThat(json(effective)).doesNotContain(json(OnyxProperties.defaults().auth())).doesNotContain("\"connectTimeoutMs\"")
                .doesNotContain(json(LookupProperties.defaults().member()));
    }

    // ------------------------------------------------------------------------------------------
    // Ready to send: the checklist at the top of In force now
    // ------------------------------------------------------------------------------------------

    private static final String OAUTH2_COLLECTION = """
            {"info": {"name": "Signed sample", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
             "auth": {"type": "oauth2", "oauth2": [{"key": "grant_type", "value": "client_credentials"}]},
             "item": [{"name": "order-sign Happy Path", "request": {"method": "POST",
                        "url": {"raw": "https://sandbox.onyx.example.invalid/cds-services/order-sign"},
                        "header": [{"key": "Content-Type", "value": "application/json"}],
                        "body": {"mode": "raw", "raw": "{}"}}, "response": []},
                      {"name": "inquiry Happy Path", "request": {"method": "POST",
                        "url": {"raw": "https://sandbox.onyx.example.invalid/fhir/Claim/$inquire"},
                        "header": [{"key": "Content-Type", "value": "application/fhir+json"}],
                        "body": {"mode": "raw", "raw": "{}"}}, "response": []}]}
            """;

    @Test
    void signInSettingsThatNothingUsesAreNamedAndTheSecretWithNoValueSaysWhereItReadsFrom() {
        ConfigFixture fixture = ConfigFixture.live(directory);
        fixture.activeEnvironmentOf("Plain sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX_FHIR,
                "crdUrl", "https://sandbox.onyx.example.invalid", "crdOrderSignServiceId", "order-sign",
                "tokenUrl", "https://auth.sandbox.onyx.example.invalid/oauth2/token", "clientId", "zz-known-client-id",
                "clientSecret", "{{env:PAVE_UNSET_X}}", "subscriptionId", "placeholder-subscription-1",
                "callbackUrl", "https://pave-receiver.example.invalid/api/pas/notifications");
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        Effective effective = fixture.service.effective(null);

        ConfigViews.Readiness readiness = effective.readiness();
        assertThat(readiness.environmentLine()).isEqualTo("Plain sandbox (sandbox), active.");
        assertThat(readiness.environmentActive()).isTrue();
        assertThat(readiness.collections()).isEqualTo(1);
        assertThat(readiness.signIn().enabled()).isFalse();
        assertThat(readiness.signIn().environmentSignInVariables()).containsExactly("tokenUrl", "clientId", "clientSecret");
        assertThat(readiness.signIn().requestHelpers()).isEmpty();
        assertThat(readiness.signIn().problem()).isNull();
        assertThat(readiness.signIn().howToSwitchOn()).isEqualTo("--onyx.auth.enabled=true --onyx.auth.token-url={{tokenUrl}} "
                + "--onyx.auth.client-id={{clientId}} --onyx.auth.client-secret={{clientSecret}}");
        assertThat(readiness.missingSecrets()).containsExactly(new ConfigViews.MissingSecret("clientSecret", "PAVE_UNSET_X"));
        // The counts are the table's: outbound rows, and those of them nothing stands in the way of.
        List<OperationRow> outbound = effective.operations().stream()
                .filter(each -> each.outbound() && each.operation() != EpaOperation.UNMAPPED).toList();
        List<OperationRow> sendable = outbound.stream().filter(each -> each.unavailable() == null && each.blocked() == null).toList();
        assertThat(readiness.outbound()).isEqualTo(outbound.size());
        assertThat(readiness.sendable()).isEqualTo(sendable.size()).isPositive();
        // Each operation that can be sent says why it goes without signing in, and no other row does.
        assertThat(sendable).allSatisfy(each -> assertThat(each.withoutSignIn())
                .isEqualTo("The active environment holds sign-in settings that nothing uses."));
        assertThat(effective.operations()).filteredOn(each -> !sendable.contains(each)).allSatisfy(each -> assertThat(each.withoutSignIn()).isNull());
        // Names only: no value of any of them reaches the answer.
        assertThat(json(effective) + effective).doesNotContain("zz-known-client-id").doesNotContain("auth.sandbox.onyx");
    }

    @Test
    void withSignInSwitchedOnTheFirstSettingWithNoValueIsNamedWithTheResolversReason() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().onyx("auth.enabled", "true").onyx("auth.token-url", "{{tokenUrl}}")
                .onyx("auth.client-id", "{{clientId}}").onyx("auth.client-secret", "{{clientSecret}}").build();
        fixture.activeEnvironmentOf("Plain sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX_FHIR,
                "tokenUrl", "https://auth.sandbox.onyx.example.invalid/oauth2/token", "clientId", "zz-known-client-id",
                "clientSecret", "{{env:PAVE_UNSET_X}}");

        Effective effective = fixture.service.effective(null);

        ConfigViews.SignIn signIn = effective.readiness().signIn();
        assertThat(signIn.enabled()).isTrue();
        assertThat(signIn.problem()).startsWith("onyx.auth.client-secret has no value: ").contains("PAVE_UNSET_X");
        // While sign-in is on, nothing is said to go without it.
        assertThat(signIn.environmentSignInVariables()).isEmpty();
        assertThat(signIn.howToSwitchOn()).isNull();
        assertThat(effective.operations()).allSatisfy(each -> assertThat(each.withoutSignIn()).isNull());
        assertThat(json(effective) + effective).doesNotContain("zz-known-client-id");
    }

    @Test
    void aPostmanSignInHelperOnTheRequestUsedIsNamedOnItsRowAndCountedInTheChecklist() {
        for (boolean enabled : new boolean[] {false, true}) {
            Path own = directory.resolve(enabled ? "on" : "off");
            ConfigFixture fixture = ConfigFixture.on(own).live().onyx("auth.enabled", String.valueOf(enabled)).build();
            fixture.activeEnvironmentOf("Bare sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX_FHIR);
            fixture.store.uploadCollection("signed.json", OAUTH2_COLLECTION.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Effective effective = fixture.service.effective(null);

            ConfigViews.SignIn signIn = effective.readiness().signIn();
            if (!enabled) {
                assertThat(signIn.environmentSignInVariables()).isEmpty();
                assertThat(signIn.requestHelpers()).containsExactly("oauth2, set on the collection");
                assertThat(signIn.operationsWithHelper()).isEqualTo(2);
                assertThat(signIn.howToSwitchOn()).contains("--onyx.auth.token-url=<your token url>");
                for (EpaOperation operation : List.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.PAS_INQUIRE)) {
                    assertThat(row(effective, operation).withoutSignIn())
                            .startsWith("The request used carries a Postman sign-in helper (oauth2");
                }
            } else {
                assertThat(signIn.requestHelpers()).isEmpty();
                assertThat(effective.operations()).allSatisfy(each -> assertThat(each.withoutSignIn()).isNull());
            }
        }
    }

    @Test
    void onMockNothingIsSaidAboutSigningInBecauseNothingIsSent() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Plain sandbox", EnvironmentKind.SANDBOX, "baseUrl", SANDBOX_FHIR, "clientId", "zz-known-client-id",
                "clientSecret", "{{env:PAVE_UNSET_X}}");

        Effective effective = fixture.service.effective(null);

        assertThat(effective.readiness().signIn()).isNull();
        assertThat(effective.readiness().missingSecrets()).isEmpty();
        assertThat(effective.operations()).allSatisfy(each -> assertThat(each.withoutSignIn()).isNull());
        assertThat(json(effective)).doesNotContain("\"signIn\"").doesNotContain("withoutSignIn");
    }

    @Test
    void withNothingActiveTheChecklistSaysSo() {
        Effective effective = ConfigFixture.live(directory).service.effective(null);

        assertThat(effective.readiness().environmentLine()).isEqualTo("none is active.");
        assertThat(effective.readiness().environmentActive()).isFalse();
        assertThat(effective.readiness().collections()).isZero();
        assertThat(effective.readiness().missingSecrets()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // The preview before sending
    // ------------------------------------------------------------------------------------------

    @Test
    void aPreviewShowsTheUrlBesideItsTemplateTheVariablesItNeedsAndTheVendorsSampleBody() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        StoredCollection vendor = fixture.store.snapshot().collections().get(0);

        Resolved resolved = fixture.service.resolve(EpaOperation.PAS_SUBMIT, null, Map.of(), Map.of(), null);

        assertThat(resolved.operation()).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThat(resolved.label()).isEqualTo("PAS submit");
        assertThat(resolved.method()).isEqualTo("POST");
        assertThat(resolved.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(resolved.epoch()).isEqualTo(fixture.gateway.epoch());
        assertThat(resolved.url().template()).isEqualTo("{{baseUrl}}/Claim/$submit");
        assertThat(resolved.url().display()).isEqualTo(SANDBOX_FHIR + "/Claim/$submit");
        assertThat(resolved.urlShown()).isEqualTo(SANDBOX_FHIR + "/Claim/$submit");
        assertThat(resolved.requestName()).isEqualTo("submit Happy Path");
        assertThat(resolved.request()).isEqualTo(ref(vendor, "submit Happy Path"));
        assertThat(resolved.headers()).containsExactly(
                new ResolvedHeaderView("Content-Type", "application/fhir+json", "application/fhir+json", true, List.of()),
                new ResolvedHeaderView("Accept", "application/fhir+json", "application/fhir+json", true, List.of()));
        // The vendor's sample body exactly as the collection holds it, and what it resolves to.
        assertThat(resolved.bodyTemplate()).isEqualTo(vendor.request(resolved.request().requestId()).request().body());
        assertThat(resolved.bodyMode()).isEqualTo("raw");
        assertThat(resolved.body()).contains("\"value\": \"00000000-0000-4000-8000-000000000001\"")
                .contains("\"timestamp\": \"2026-01-05T15:04:05.000Z\"").doesNotContain("{{");
        assertThat(resolved.bodyUnresolved()).isEmpty();
        // Every variable the request needs, each with where it is used and where its value came from.
        assertThat(resolved.variables()).extracting(VariableRow::name).containsExactly("baseUrl", "$guid", "$isoTimestamp");
        VariableRow baseUrl = variable(resolved, "baseUrl");
        assertThat(baseUrl.source()).isEqualTo(VariableSource.ENVIRONMENT);
        assertThat(baseUrl.display()).isEqualTo(SANDBOX_FHIR);
        assertThat(baseUrl.resolved()).isTrue();
        assertThat(baseUrl.reason()).isNull();
        assertThat(baseUrl.usedIn()).containsExactly("the url");
        VariableRow guid = variable(resolved, "$guid");
        assertThat(guid.source()).isEqualTo(VariableSource.GENERATED);
        assertThat(guid.usedIn()).containsExactly("the body");
        assertThat(guid.reason()).startsWith("Generated by this tool. Every use gets a new value");
        // The script is flagged, and its text is there to read. It is never run.
        assertThat(resolved.scripted()).isTrue();
        assertThat(resolved.scripts()).hasSize(1);
        assertThat(resolved.scripts().get(0).source()).contains("pm.variables.set(\"submittedAt\"");
        assertThat(resolved.write()).isTrue();
        assertThat(resolved.blocked()).isNull();
        assertThat(resolved.unavailable()).isNull();
        assertThat(resolved.urlTokens()).isEmpty();
        assertThat(resolved.examples()).isEmpty();
    }

    @Test
    void aPreviewOfANamedRequestUsesTheValuesTypedForTheRunAndOffersTheSavedExamples() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        StoredCollection vendor = fixture.store.snapshot().collections().get(0);

        Resolved resolved = fixture.service.resolve(EpaOperation.CRD_ORDER_SIGN, ref(vendor, "order-sign Happy Path"),
                Map.of("crdUrl", "https://typed.example.invalid", "unusedValue", "x"), null, null);

        assertThat(resolved.url().display()).isEqualTo("https://typed.example.invalid/cds-services/order-sign-crd");
        assertThat(variable(resolved, "crdUrl").source()).isEqualTo(VariableSource.RUN_VALUE);
        assertThat(variable(resolved, "crdUrl").display()).isEqualTo("https://typed.example.invalid");
        assertThat(variable(resolved, "crdOrderSignServiceId").source()).isEqualTo(VariableSource.ENVIRONMENT);
        // baseUrl is used in the body only, as the fhirServer.
        assertThat(variable(resolved, "baseUrl").usedIn()).containsExactly("the body");
        assertThat(resolved.body()).contains("\"fhirServer\": \"" + SANDBOX_FHIR + "\"");
        assertThat(resolved.write()).isFalse();
        assertThat(resolved.scripted()).isFalse();
        assertThat(resolved.examples()).hasSize(1);
        assertThat(resolved.examples().get(0).name()).isEqualTo("Authorization required");
        assertThat(resolved.examples().get(0).body()).contains("Prior authorization is required");
    }

    @Test
    void whatCannotBeResolvedIsMarkedBeforeAnythingIsSent() {
        ConfigFixture mock = ConfigFixture.mock(directory.resolve("mock"));
        mock.collection(PostmanFiles.NESTED_COLLECTION);
        ConfigFixture live = ConfigFixture.live(directory.resolve("live"));
        live.collection(PostmanFiles.NESTED_COLLECTION);

        for (ConfigFixture fixture : List.of(mock, live)) {
            // No environment is active. The collection supplies baseUrl, and nothing supplies the trace id of the header.
            Resolved resolved = fixture.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, null, Map.of("id", "sub 1"), null);

            assertThat(resolved.url().display()).isEqualTo("https://collection.example.invalid/fhir/Subscription/sub%201");
            assertThat(variable(resolved, "baseUrl").source()).isEqualTo(VariableSource.COLLECTION);
            assertThat(resolved.headers()).containsExactly(
                    new ResolvedHeaderView("Accept", "application/fhir+json", "application/fhir+json", true, List.of()),
                    new ResolvedHeaderView("X-Trace", "{{traceId}}", null, false, List.of("traceId")));
            VariableRow trace = variable(resolved, "traceId");
            assertThat(trace.resolved()).isFalse();
            assertThat(trace.source()).isEqualTo(VariableSource.UNRESOLVED);
            assertThat(trace.display()).isNull();
            assertThat(trace.reason()).isEqualTo("No layer defines it.");
            assertThat(trace.usedIn()).containsExactly("the header X-Trace");
            // The value the collection saved for :id is offered as a suggestion. What is sent is what the call supplies.
            assertThat(resolved.urlTokens()).containsExactly(new TokenRow("id", true, "saved-subscription-7"));
            assertThat(resolved.write()).isTrue();
        }
        // On live a write with no environment is refused, and the preview says so without hiding the target.
        assertThat(live.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, null, Map.of("id", "sub 1"), null).blocked())
                .startsWith("Subscription delete with the request \"subscription by path variable\" is blocked: no environment is active");
        assertThat(mock.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, null, Map.of("id", "sub 1"), null).blocked()).isNull();

        // A url that still needs a value: said on both profiles, as a reason on live and as a note on mock. The header X-Trace
        // has no value either, and a run is refused for that on mock too, so the note does not say the run is still answered.
        Resolved mockToken = mock.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, null, null, null);
        assertThat(mockToken.urlTokens()).containsExactly(new TokenRow("id", false, "saved-subscription-7"));
        assertThat(mockToken.url().unresolved()).containsExactly("id");
        assertThat(mockToken.urlShown()).isEqualTo("https://collection.example.invalid/fhir/Subscription/:id");
        assertThat(mockToken.unavailable()).isNull();
        assertThat(mockToken.notes()).containsExactly("The url still needs a value for id, the header X-Trace still needs a value for "
                + "{{traceId}}, and no environment is active. On the mock profile nothing is sent, but a header with no value is refused "
                + "before a scenario answers, so a run is refused here too. Give {{traceId}} a value in an environment on the Config tab, "
                + "or type it among the values for this run.");
        Resolved liveToken = live.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, null, null, null);
        assertThat(liveToken.unavailable()).isEqualTo("The url still needs a value for id, the header X-Trace still needs a value for "
                + "{{traceId}}, and no environment is active. Give {{traceId}} a value in an environment on the Config tab, or type it among "
                + "the values for this run.");
        assertThat(liveToken.notes()).isEmpty();
        // With the header given a value for the run, the url alone is short. On mock a run from the tab is still answered by
        // a scenario, and the same request sent as written is refused, as on live: the note says both.
        assertThat(mock.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, Map.of("traceId", "t-1"), null, null).notes())
                .containsExactly("The url still needs a value for id, and no environment is active. On the mock profile a run from its "
                        + "tab is still answered from a scenario, since nothing is sent. Sent as written from the Config tab, it is refused "
                        + "until every part of its url has a value, on the mock profile as on live, and nothing is sent.");
        assertThat(live.service.resolve(EpaOperation.SUBSCRIPTION_DELETE, null, Map.of("traceId", "t-1"), null, null).unavailable())
                .isEqualTo("The url still needs a value for id, and no environment is active.");
    }

    @Test
    void aPreviewOfAnOperationWithNoTargetSaysWhy() {
        Resolved mock = ConfigFixture.mock(directory.resolve("mock")).service.resolve(EpaOperation.CRD_ORDER_SIGN, null, null, null, null);
        Resolved live = ConfigFixture.live(directory.resolve("live")).service.resolve(EpaOperation.CRD_ORDER_SIGN, null, null, null, null);

        for (Resolved resolved : List.of(mock, live)) {
            assertThat(resolved.url().configured()).isFalse();
            assertThat(resolved.url().source()).isEqualTo("No collection request is mapped to CRD order-sign, and onyx.crd.url is not set.");
            assertThat(resolved.urlShown()).isNull();
            assertThat(resolved.request()).isNull();
            assertThat(resolved.requestName()).isNull();
            assertThat(resolved.bodyTemplate()).isNull();
            assertThat(resolved.body()).isNull();
            assertThat(resolved.headers()).isEmpty();
            assertThat(resolved.variables()).isEmpty();
        }
        assertThat(live.unavailable()).isEqualTo("No collection request is mapped to CRD order-sign, and onyx.crd.url is not set.");
        assertThat(mock.unavailable()).isNull();
        assertThat(mock.notes()).containsExactly("No collection request is mapped to CRD order-sign, and onyx.crd.url is not set. On the mock "
                + "profile nothing is sent, so the run is still answered from a scenario.");
    }

    @Test
    void aPreviewNeverShowsASecretNotEvenOneTypedForTheRun() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        StoredCollection collection = fixture.collectionOf("Keyed", items(item("inquiry Happy Path", "POST",
                "{{baseUrl}}/Claim/$inquire?tenant={{tenantCode}}&key={{apiKey}}").header("Authorization", "Bearer {{accessToken}}")
                .header("X-Tenant", "{{tenantCode}}").body("{\"secret\": \"{{clientSecret}}\", \"key\": \"{{apiKey}}\"}")));

        Resolved resolved = fixture.service.resolve(EpaOperation.PAS_INQUIRE, ref(collection, "inquiry Happy Path"),
                Map.of("apiKey", "hunter2-typed", "accessToken", "hunter2-token"), Map.of(), null);

        assertThat(resolved.url().display()).isEqualTo(SANDBOX_FHIR + "/Claim/$inquire?tenant=[secret tenantCode]&key=[secret apiKey]");
        assertThat(resolved.urlShown()).isEqualTo(resolved.url().display());
        assertThat(resolved.headers()).containsExactly(
                // Shown as written, since nothing in it is a value, and resolved to its scheme and nothing more.
                new ResolvedHeaderView("Authorization", "Bearer {{accessToken}}", "Bearer [redacted]", true, List.of()),
                new ResolvedHeaderView("X-Tenant", "{{tenantCode}}", "[secret tenantCode]", true, List.of()));
        assertThat(resolved.body()).isEqualTo("{\"secret\": \"[secret clientSecret]\", \"key\": \"[secret apiKey]\"}");
        VariableRow typed = variable(resolved, "apiKey");
        assertThat(typed.secret()).isTrue();
        assertThat(typed.source()).isEqualTo(VariableSource.RUN_VALUE);
        assertThat(typed.display()).isEqualTo("[secret apiKey]");
        assertThat(typed.usedIn()).containsExactly("the url", "the body");
        assertThat(variable(resolved, "tenantCode").usedIn()).containsExactly("the url", "the header X-Tenant");
        assertThat(variable(resolved, "clientSecret").display()).isEqualTo("[secret clientSecret]");
        assertThat(json(resolved) + resolved + resolved.variables()).doesNotContain("hunter2").doesNotContain("placeholder-sandbox-secret")
                .doesNotContain("placeholder-tenant-value");
    }

    @Test
    void aPreviewOnProductionSaysTheWriteIsBlockedAndStillShowsWhereItWouldGo() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().allowProdWrites().build();
        fixture.activate(PostmanFiles.PRODUCTION_ENVIRONMENT, EnvironmentKind.PROD);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        Resolved locked = fixture.service.resolve(EpaOperation.PAS_SUBMIT, null, null, null, null);
        assertThat(locked.write()).isTrue();
        assertThat(locked.blocked()).startsWith("PAS submit with the request \"submit Happy Path\" is blocked: \"Onyx Production\" is a "
                + "production environment and production writes are locked for this session.").endsWith("switch to a sandbox or uat environment.");
        assertThat(locked.url().display()).isEqualTo("https://api.onyx.example.invalid/fhir/Claim/$submit");
        assertThat(locked.environment().production()).isTrue();

        Resolved unlocked = fixture.service.resolve(EpaOperation.PAS_SUBMIT, null, null, null, fixture.service.unlock("Onyx Production"));
        assertThat(unlocked.blocked()).isNull();
        assertThat(unlocked.write()).isTrue();
        // A read on production is not blocked, and says it is not a write.
        Resolved read = fixture.service.resolve(EpaOperation.PAS_INQUIRE, null, null, null, null);
        assertThat(read.write()).isFalse();
        assertThat(read.blocked()).isNull();
    }

    @Test
    void aPreviewIsRefusedForWhatIsWrongWithTheRequestItself() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        StoredCollection vendor = fixture.store.snapshot().collections().get(0);

        assertThatThrownBy(() -> fixture.service.resolve(null, null, null, null, null)).isInstanceOf(ValidationException.class);
        for (EpaOperation operation : Arrays.stream(EpaOperation.values()).filter(each -> !each.outbound()).toList()) {
            assertThatThrownBy(() -> fixture.service.resolve(operation, null, null, null, null)).as(operation.name())
                    .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.fieldErrors().get(0).message())
                            .isEqualTo(operation.label() + " never leaves the tool, so there is no request to resolve for it."));
        }
        assertThatThrownBy(() -> fixture.service.resolve(EpaOperation.PAS_INQUIRE, new RequestRef(vendor.id(), "r-000000000000"), null, null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> fixture.service.resolve(EpaOperation.PAS_INQUIRE, ref(vendor, "submit Happy Path"), null, null, null))
                .isInstanceOf(BadInputException.class).hasMessageContaining("is mapped to PAS submit");
        assertThatThrownBy(() -> fixture.service.resolve(EpaOperation.PAS_INQUIRE, null, null, Map.of("id", "x"), null))
                .isInstanceOf(BadInputException.class).hasMessageStartingWith("This call supplies a value for \"id\"");
        // A raw preview of any request is allowed, and nothing about a preview changes the configuration.
        long epoch = fixture.gateway.epoch();
        assertThat(fixture.service.resolve(EpaOperation.UNMAPPED, ref(vendor, "submit Happy Path"), null, null, null).method()).isEqualTo("POST");
        assertThat(fixture.gateway.epoch()).isEqualTo(epoch);
    }
}
