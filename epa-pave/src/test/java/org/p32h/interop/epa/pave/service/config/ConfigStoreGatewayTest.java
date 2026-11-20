package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.item;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.items;
import static org.p32h.interop.epa.pave.service.config.ConfigFixture.ref;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;

/** Where each operation goes: the target, the method, the url and its tokens, the headers, and what the two profiles do with a gap. */
class ConfigStoreGatewayTest {

    private static final String SANDBOX_FHIR = "https://sandbox.onyx.example.invalid/fhir";

    /** Values that would change the shape of a url if they were put into it as written. */
    private static final String[] AWKWARD = {"S12345678   01", "a/b", "100%", "a&b=c", "what?", "frag#ment"};

    @TempDir
    Path directory;

    /** The mock profile with the sandbox environment active and the vendor collection uploaded. */
    private ConfigFixture sandboxWithVendorCollection() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        return fixture;
    }

    private static StoredCollection vendor(ConfigFixture fixture) {
        return fixture.store.snapshot().collections().get(0);
    }

    private static ResolvedHeader header(PreparedCall call, String name) {
        return call.headers().stream().filter(header -> header.name().equals(name)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------
    // Out of the box
    // ------------------------------------------------------------------------------------------

    @Test
    void onMockWithNothingConfiguredACallIsPreparedAndSaysItHasNoTarget() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        assertThat(call.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.url().configured()).isFalse();
        assertThat(call.url().resolved()).isFalse();
        assertThat(call.url().template()).isEmpty();
        assertThat(call.url().value()).isNull();
        assertThat(call.url().display()).isNull();
        assertThat(call.url().source()).isEqualTo("No collection request is mapped to CRD order-sign, and onyx.crd.url is not set.");
        assertThat(call.headers()).isEmpty();
        assertThat(call.environment().active()).isFalse();
        assertThat(call.requestName()).isNull();
        assertThat(call.scripted()).isFalse();
        assertThat(call.epoch()).isEqualTo(fixture.gateway.epoch());
        assertThat(call.resolve("no placeholders").value()).isEqualTo("no placeholders");
        assertThat(call.resolve(null)).isNull();
    }

    @Test
    void onMockWithNothingConfiguredEveryOutboundOperationCanBePreparedWritesIncluded() {
        ConfigFixture fixture = ConfigFixture.mock(directory);

        int prepared = 0;
        for (EpaOperation operation : EpaOperation.values()) {
            if (!operation.outbound()) {
                continue;
            }
            PreparedCall call = fixture.gateway.prepare(CallIntent.of(operation));
            assertThat(call.url().configured()).as(operation.name()).isFalse();
            assertThat(call.method()).as(operation.name()).isEqualTo(operation.defaultMethod());
            assertThat(call.url().source()).as(operation.name()).isNotBlank();
            prepared++;
        }
        assertThat(prepared).isEqualTo(EpaOperation.values().length - 2);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY)).url().source()).isEqualTo(
                "No collection request is mapped to CDS Hooks discovery, onyx.crd.url is not set, and no CRD hook request is mapped to derive it from.");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP)).url().source())
                .isEqualTo("No collection request is mapped to Member lookup, and lookup.member.url is not set.");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED)).url().source())
                .isEqualTo("An unmapped request has no target of its own. It is sent by naming the request.");
    }

    @Test
    void anOperationThatNeverLeavesTheToolHasNoTargetOnEitherProfile() {
        for (ConfigFixture fixture : List.of(ConfigFixture.mock(directory.resolve("mock")), ConfigFixture.live(directory.resolve("live")))) {
            fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            int refused = 0;
            for (EpaOperation operation : EpaOperation.values()) {
                if (operation.outbound()) {
                    continue;
                }
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(operation))).as(operation.name())
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(operation.name());
                // Naming the vendor's own sample of a notification does not give the operation a target either.
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(operation)
                        .withRequest(ref(vendor(fixture), "subscription-notification Happy Path")))).as(operation.name())
                        .isInstanceOf(IllegalArgumentException.class);
                refused++;
            }
            // Today these are the notification and the questionnaire response. The rule is the flag, not the names.
            assertThat(refused).isEqualTo(2);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Where the call goes
    // ------------------------------------------------------------------------------------------

    @Test
    void theDefaultTargetIsTheFirstMappedRequestThatIsNotAnErrorSample() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        PreparedCall orderSign = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(orderSign.requestName()).isEqualTo("order-sign Happy Path");
        assertThat(orderSign.method()).isEqualTo("POST");
        ResolvedUrl url = orderSign.url();
        assertThat(url.template()).isEqualTo("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}");
        // The environment's service id wins over the one the collection defines.
        assertThat(url.value()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services/order-sign-crd");
        assertThat(url.display()).isEqualTo(url.value());
        assertThat(url.resolved()).isTrue();
        assertThat(url.unresolved()).isEmpty();
        assertThat(url.source()).isEqualTo("the request \"CRD / order-sign Happy Path\" of the collection \"Onyx Sandbox ePA\"");
        assertThat(url.variables()).extracting(VariableUse::name).containsExactly("crdUrl", "crdOrderSignServiceId");
        assertThat(url.variables()).extracting(VariableUse::source).containsOnly(VariableSource.ENVIRONMENT);
        assertThat(orderSign.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(orderSign.environment().kind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(orderSign.scripted()).isFalse();
        assertThat(orderSign.epoch()).isEqualTo(fixture.gateway.epoch());

        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)).requestName())
                .isEqualTo("$questionnaire-package Happy path");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)).requestName()).isEqualTo("subscription-get Happy Path");
        PreparedCall submit = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT));
        assertThat(submit.url().value()).isEqualTo(SANDBOX_FHIR + "/Claim/$submit");
        // The vendor's submit request carries a pre-request script, which is never run.
        assertThat(submit.scripted()).isTrue();
    }

    @Test
    void anErrorSampleIsPassedOverEvenWhenItComesFirstAndInAnyCapitals() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.collectionOf("Errors first", items(
                item("order-sign ERROR case", "POST", "https://first.example.invalid/cds-services/order-sign-a"),
                item("order-sign Error", "POST", "https://second.example.invalid/cds-services/order-sign-a"),
                item("order-sign Happy Path", "POST", "https://third.example.invalid/cds-services/order-sign-a"),
                item("order-sign Another", "POST", "https://fourth.example.invalid/cds-services/order-sign-a")));

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        assertThat(call.requestName()).isEqualTo("order-sign Happy Path");
        assertThat(call.url().value()).isEqualTo("https://third.example.invalid/cds-services/order-sign-a");
    }

    @Test
    void whenOnlyErrorSamplesAreMappedTheCallFallsThroughAndSaysWhy() {
        ConfigFixture mock = ConfigFixture.mock(directory.resolve("mock"));
        StoredCollection onlyErrors = mock.collectionOf("Only errors", items(
                item("order-sign Error", "POST", "https://error.example.invalid/cds-services/order-sign-a")));

        PreparedCall call = mock.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(call.url().configured()).isFalse();
        assertThat(call.url().source()).isEqualTo("The only collection requests mapped to CRD order-sign are samples of an error, which "
                + "are used only when one is chosen, and onyx.crd.url is not set.");
        // Chosen by name, it is used.
        assertThat(mock.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN).withRequest(ref(onlyErrors, "order-sign Error")))
                .url().value()).isEqualTo("https://error.example.invalid/cds-services/order-sign-a");

        ConfigFixture withFallback = ConfigFixture.on(directory.resolve("fallback")).onyx("crd.url", "https://yml.example.invalid").build();
        withFallback.collectionOf("Only errors", items(item("order-sign Error", "POST", "https://error.example.invalid/cds-services/x")));
        PreparedCall fallback = withFallback.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(fallback.requestName()).isNull();
        assertThat(fallback.url().template()).isEqualTo("https://yml.example.invalid/cds-services/{{crdOrderSignServiceId}}");
    }

    @Test
    void withSeveralCollectionsTheOneUploadedFirstSuppliesTheDefault() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.collectionOf("First", items(item("inquiry Happy Path", "POST", "https://first.example.invalid/Claim/$inquire")));
        fixture.collectionOf("Second", items(item("inquiry Happy Path", "POST", "https://second.example.invalid/Claim/$inquire")));

        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value())
                .isEqualTo("https://first.example.invalid/Claim/$inquire");
        // Replacing the first collection keeps its place.
        fixture.collectionOf("First", items(item("inquiry Happy Path", "POST", "https://first-again.example.invalid/Claim/$inquire")));
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value())
                .isEqualTo("https://first-again.example.invalid/Claim/$inquire");
    }

    @Test
    void aNamedRequestIsUsedWhenItIsMappedToTheOperation() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)
                .withRequest(ref(vendor(fixture), "order-sign Error")));

        assertThat(call.requestName()).isEqualTo("order-sign Error");
        assertThat(call.url().source()).isEqualTo("the request \"CRD / order-sign Error\" of the collection \"Onyx Sandbox ePA\"");
        assertThat(call.url().value()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services/order-sign-crd");
    }

    @Test
    void aNamedRequestThatDoesNotExistIsNotFound() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        String collectionId = vendor(fixture).id();

        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)
                .withRequest(new RequestRef("no-such-collection", "r-000000000000")))).isInstanceOf(NotFoundException.class)
                .hasMessage("The collection this request was chosen from is not uploaded. Reload and choose again.");
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)
                .withRequest(new RequestRef(collectionId, "r-000000000000")))).isInstanceOf(NotFoundException.class)
                .hasMessage("The collection \"Onyx Sandbox ePA\" has no request with the id that was chosen. Reload and choose again.");
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED)
                .withRequest(new RequestRef(collectionId, null)))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNamedRequestMustBeMappedToTheOperationItIsUsedFor() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        RequestRef submit = ref(vendor(fixture), "submit Happy Path");

        // A read button pointed at the write request.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRequest(submit)))
                .isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("The request \"submit Happy Path\" is mapped to PAS submit, so it cannot be sent as PAS inquire.");
                    assertThat(e.action()).isEqualTo("Choose a request that is mapped to PAS inquire, or correct the mapping on the Config tab.");
                });
        // The other way round is refused too: the mapping is what a button means.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)
                .withRequest(ref(vendor(fixture), "inquiry Happy Path")))).isInstanceOf(BadInputException.class);
        // Only a raw send may name any request.
        PreparedCall raw = fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(ref(vendor(fixture), "inquiry Happy Path")));
        assertThat(raw.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(raw.requestName()).isEqualTo("inquiry Happy Path");
        assertThat(raw.url().value()).isEqualTo(SANDBOX_FHIR + "/Claim/$inquire");
    }

    @Test
    void anOperatorCorrectionChangesWhereAButtonSends() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        StoredCollection collection = fixture.collectionOf("Unclear names", items(
                item("status check", "POST", "https://a.example.invalid/fhir/status"),
                item("inquiry Happy Path", "POST", "https://b.example.invalid/fhir/Claim/$inquire")));
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).requestName()).isEqualTo("inquiry Happy Path");

        fixture.remap(collection, "status check", EpaOperation.PAS_INQUIRE);

        // Now two requests stand for the operation, and the first in the file is the default.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).requestName()).isEqualTo("status check");
        fixture.remap(fixture.store.snapshot().collection(collection.id()), "inquiry Happy Path", EpaOperation.UNMAPPED);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value()).isEqualTo("https://a.example.invalid/fhir/status");
    }

    // ------------------------------------------------------------------------------------------
    // The fallback layer
    // ------------------------------------------------------------------------------------------

    @Test
    void withNoMappedRequestTheUrlIsTheBaseFromApplicationYmlPlusThePathOfTheOperation() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("crd.url", "https://crd.example.invalid/r4")
                .onyx("dtr.url", "https://dtr.example.invalid/fhir").onyx("pas.url", "{{pasBase}}/fhir//")
                .onyx("subscription.url", "https://sub.example.invalid/fhir").onyx("variables.pasBase", "https://pas.example.invalid")
                .onyx("variables.crdOrderSignServiceId", "order-sign-yml").build();

        PreparedCall inquire = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));
        // Trailing slashes on the base are not doubled into the path.
        assertThat(inquire.url().template()).isEqualTo("{{pasBase}}/fhir/Claim/$inquire");
        assertThat(inquire.url().value()).isEqualTo("https://pas.example.invalid/fhir/Claim/$inquire");
        assertThat(inquire.url().source()).isEqualTo("application.yml: onyx.pas.url followed by /Claim/$inquire");
        assertThat(inquire.url().variables()).containsExactly(
                new VariableUse("pasBase", VariableSource.APPLICATION_YML, false, "https://pas.example.invalid"));
        assertThat(inquire.method()).isEqualTo("POST");
        assertThat(inquire.requestName()).isNull();
        assertThat(inquire.scripted()).isFalse();
        assertThat(inquire.headers()).isEmpty();

        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT)).url().value())
                .isEqualTo("https://pas.example.invalid/fhir/Claim/$submit");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).url().value())
                .isEqualTo("https://crd.example.invalid/r4/cds-services/order-sign-yml");
        // The service id of the other hook is not known, and it is not guessed from the first.
        PreparedCall orderSelect = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SELECT));
        assertThat(orderSelect.url().template()).isEqualTo("https://crd.example.invalid/r4/cds-services/{{crdOrderSelectServiceId}}");
        assertThat(orderSelect.url().unresolved()).containsExactly("crdOrderSelectServiceId");
        assertThat(orderSelect.url().value()).isNull();
        PreparedCall discovery = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));
        assertThat(discovery.url().value()).isEqualTo("https://crd.example.invalid/r4/cds-services");
        assertThat(discovery.method()).isEqualTo("GET");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE)).url().value())
                .isEqualTo("https://dtr.example.invalid/fhir/Questionnaire/$questionnaire-package");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.DTR_NEXT_QUESTION)).url().value())
                .isEqualTo("https://dtr.example.invalid/fhir/Questionnaire/$next-question");
        PreparedCall create = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE));
        assertThat(create.url().value()).isEqualTo("https://sub.example.invalid/fhir/Subscription");
        assertThat(create.method()).isEqualTo("POST");
        PreparedCall update = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_UPDATE).withUrlValue("id", "sub-1"));
        assertThat(update.url().value()).isEqualTo("https://sub.example.invalid/fhir/Subscription/sub-1");
        assertThat(update.method()).isEqualTo("PUT");
        PreparedCall delete = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub-1"));
        assertThat(delete.url().value()).isEqualTo("https://sub.example.invalid/fhir/Subscription/sub-1");
        assertThat(delete.method()).isEqualTo("DELETE");
    }

    @Test
    void aMappedRequestWinsOverTheFallbackLayer() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("pas.url", "https://yml.example.invalid/fhir").build();
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));

        assertThat(call.requestName()).isEqualTo("inquiry Happy Path");
        assertThat(call.url().value()).isEqualTo(SANDBOX_FHIR + "/Claim/$inquire");
    }

    @Test
    void aLookupTakesItsUrlAndItsHeadersFromItsOwnSettings() {
        ConfigFixture fixture = ConfigFixture.on(directory).lookup("member.url", "https://lookup.example.invalid/api/members/{id}")
                .lookup("member.headers.X-Tenant", "{{tenant}}").lookup("member.headers.X-Api-Key", "{{env:PAVE_MEMBER_API_KEY}}")
                .lookup("provider.url", "https://lookup.example.invalid/api/providers?npi={id}&active=true")
                .onyx("variables.tenant", "p32h").build();
        fixture.operatingSystem.put("PAVE_MEMBER_API_KEY", "os-value-4410");

        PreparedCall member = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", "S123456789"));
        assertThat(member.method()).isEqualTo("GET");
        assertThat(member.url().template()).isEqualTo("https://lookup.example.invalid/api/members/{id}");
        assertThat(member.url().value()).isEqualTo("https://lookup.example.invalid/api/members/S123456789");
        assertThat(member.url().source()).isEqualTo("application.yml: lookup.member.url");
        assertThat(member.requestName()).isNull();
        assertThat(member.headers()).extracting(ResolvedHeader::name).containsExactly("X-Api-Key", "X-Tenant");
        assertThat(header(member, "X-Tenant").value()).isEqualTo("p32h");
        assertThat(header(member, "X-Tenant").display()).isEqualTo("p32h");
        // The key is read from the operating system, sent as it is, and never shown.
        assertThat(header(member, "X-Api-Key").value()).isEqualTo("os-value-4410");
        assertThat(header(member, "X-Api-Key").display()).isEqualTo("[redacted]");
        assertThat(header(member, "X-Api-Key").resolved()).isTrue();

        PreparedCall provider = fixture.gateway.prepare(CallIntent.of(EpaOperation.PROVIDER_LOOKUP).withUrlValue("id", "1234567893"));
        assertThat(provider.url().value()).isEqualTo("https://lookup.example.invalid/api/providers?npi=1234567893&active=true");
        assertThat(provider.url().source()).isEqualTo("application.yml: lookup.provider.url");
        // The other lookup's headers are not sent with this one.
        assertThat(provider.headers()).isEmpty();
    }

    // ------------------------------------------------------------------------------------------
    // CDS Hooks discovery
    // ------------------------------------------------------------------------------------------

    @Test
    void withNothingElseDiscoveryIsTheUrlOfAMappedHookCutAtCdsServices() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));

        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.url().template()).isEqualTo("{{crdUrl}}/cds-services");
        assertThat(call.url().value()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services");
        assertThat(call.url().source()).isEqualTo("the url of the request \"CRD / order-sign Happy Path\" of the collection "
                + "\"Onyx Sandbox ePA\", cut after /cds-services");
        // Only what the cut url needs is asked for: the service id beyond the cut is not.
        assertThat(call.url().variables()).extracting(VariableUse::name).containsExactly("crdUrl");
        // It is a call this tool put together, so it is not reported as the hook request and carries none of its headers.
        assertThat(call.requestName()).isNull();
        assertThat(call.headers()).isEmpty();
        assertThat(call.scripted()).isFalse();
    }

    @Test
    void theCutIsMadeInWhatResolvedWhenTheHookUrlArrivesInsideAVariable() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Hooks", EnvironmentKind.SANDBOX, "hookUrl", "https://crd.example.invalid/r4/CDS-Services/{{serviceId}}?x=1");
        fixture.collectionOf("One hook", items(item("order-sign Happy Path", "POST", "{{hookUrl}}")));

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));

        assertThat(call.url().template()).isEqualTo("{{hookUrl}}");
        assertThat(call.url().value()).isEqualTo("https://crd.example.invalid/r4/CDS-Services");
        // The service id sits beyond the cut and has no value. The cut url does not need it.
        assertThat(call.url().unresolved()).isEmpty();
        assertThat(call.url().resolved()).isTrue();
    }

    @Test
    void discoveryPrefersAMappedRequestThenTheFallbackUrlThenTheHook() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("crd.url", "https://yml.example.invalid").build();
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY)).url().value()).isEqualTo("https://yml.example.invalid/cds-services");

        fixture.collectionOf("Discovery", items(item("CDS Services discovery", "GET", "https://mapped.example.invalid/cds-services")));
        PreparedCall mapped = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));
        assertThat(mapped.url().value()).isEqualTo("https://mapped.example.invalid/cds-services");
        assertThat(mapped.requestName()).isEqualTo("CDS Services discovery");
    }

    @Test
    void aHookUrlWithNoCdsServicesInItGivesDiscoveryNothingToDeriveFrom() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.collectionOf("Odd hook", items(item("order-select Happy Path", "POST", "https://crd.example.invalid/hooks/order-select")));

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_DISCOVERY));

        assertThat(call.url().configured()).isFalse();
        assertThat(call.url().source()).isEqualTo("No collection request is mapped to CDS Hooks discovery, onyx.crd.url is not set, and the "
                + "url of the hook request \"order-select Happy Path\" shows no /cds-services to derive it from.");
    }

    // ------------------------------------------------------------------------------------------
    // The method
    // ------------------------------------------------------------------------------------------

    @Test
    void theMethodIsTheRequestsOwnOrTheOperationsDefaultAndNeverTheCallersForAMappedOperation() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        // Naming a method on a mapped operation changes nothing.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withMethod("DELETE")).method()).isEqualTo("GET");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withMethod("PUT")).method()).isEqualTo("POST");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_UPDATE).withUrlValue("id", "s").withMethod("GET")).method())
                .isEqualTo("PUT");

        // A raw send takes the caller's method, or the request's own when none is named.
        RequestRef inquiry = ref(vendor(fixture), "inquiry Happy Path");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(inquiry)).method()).isEqualTo("POST");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(inquiry).withMethod(" get ")).method()).isEqualTo("GET");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.UNMAPPED).withRequest(inquiry).withMethod("  ")).method()).isEqualTo("POST");
    }

    @Test
    void aFallbackTargetUsesTheOperationsDefaultMethodWhateverTheCallerNames() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("subscription.url", "https://sub.example.invalid/fhir").build();

        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withMethod("DELETE")).method()).isEqualTo("GET");
    }

    // ------------------------------------------------------------------------------------------
    // Tokens in the url
    // ------------------------------------------------------------------------------------------

    @Test
    void aValueInThePathFormStaysOneSegmentOrIsRefusedWhenItHoldsASeparator() {
        ConfigFixture fixture = ConfigFixture.on(directory).lookup("member.url", "https://lookup.example.invalid/api/members/{id}/coverage").build();

        for (String value : AWKWARD) {
            if (value.contains("/")) {
                // An encoded slash leaves in no path: a server in front of the service may decode it.
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", value)))
                        .as(value).isInstanceOf(BadInputException.class).hasMessageContaining("which came from the url part {id}");
                continue;
            }
            PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", value));

            URI uri = URI.create(call.url().value());
            assertThat(uri.getHost()).as(value).isEqualTo("lookup.example.invalid");
            assertThat(uri.getRawPath().split("/")).as(value).hasSize(5);
            assertThat(uri.getRawPath()).as(value).startsWith("/api/members/").endsWith("/coverage");
            assertThat(uri.getRawQuery()).as(value).isNull();
            assertThat(uri.getRawFragment()).as(value).isNull();
            assertThat(URLDecoder.decode(uri.getRawPath().split("/")[3], StandardCharsets.UTF_8)).as(value).isEqualTo(value);
            // The template keeps the token. The forms to send and to show hold the value.
            assertThat(call.url().template()).isEqualTo("https://lookup.example.invalid/api/members/{id}/coverage");
            assertThat(call.url().display()).as(value).isEqualTo(call.url().value());
        }
    }

    @Test
    void aValueInTheQueryFormStaysOneValueWhateverItHolds() {
        ConfigFixture fixture = ConfigFixture.on(directory)
                .lookup("member.url", "https://lookup.example.invalid/api/members?memberId={id}&active=true").build();

        for (String value : AWKWARD) {
            PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", value));

            URI uri = URI.create(call.url().value());
            assertThat(uri.getRawPath()).as(value).isEqualTo("/api/members");
            assertThat(uri.getRawFragment()).as(value).isNull();
            String[] parameters = uri.getRawQuery().split("&");
            assertThat(parameters).as(value).hasSize(2);
            assertThat(parameters[1]).as(value).isEqualTo("active=true");
            assertThat(URLDecoder.decode(parameters[0].substring("memberId=".length()), StandardCharsets.UTF_8)).as(value).isEqualTo(value);
        }
    }

    @Test
    void aPostmanPathVariableIsFilledFromTheCallAndNotFromTheValueTheCollectionSaved() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.NESTED_COLLECTION);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub 9+x"));

        assertThat(call.requestName()).isEqualTo("subscription by path variable");
        assertThat(call.method()).isEqualTo("DELETE");
        assertThat(call.url().template()).isEqualTo("{{baseUrl}}/Subscription/:id");
        // The collection saved "saved-subscription-7" for :id. That is a sample, and is never what is sent.
        assertThat(call.url().value()).isEqualTo(SANDBOX_FHIR + "/Subscription/sub%209%2Bx");
        // An id that holds a slash is not sent at all: encoded, it is a separator to a server that decodes first.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub 9/x")))
                .isInstanceOf(BadInputException.class);

        PreparedCall withoutId = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE));
        assertThat(withoutId.url().resolved()).isFalse();
        assertThat(withoutId.url().unresolved()).containsExactly("id");
        assertThat(withoutId.url().value()).isNull();
    }

    @Test
    void aTokenWithNoValueIsUnresolvedAndOnLiveTheCallIsRefusedSayingWhy() {
        ConfigFixture mock = ConfigFixture.on(directory.resolve("mock")).lookup("member.url", "https://lookup.example.invalid/members/{id}").build();
        PreparedCall call = mock.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP));
        assertThat(call.url().configured()).isTrue();
        assertThat(call.url().resolved()).isFalse();
        assertThat(call.url().unresolved()).containsExactly("id");
        // A blank value is no value: sent, it would address /members/.
        assertThat(mock.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP).withUrlValue("id", "  ")).url().unresolved()).containsExactly("id");

        ConfigFixture live = ConfigFixture.on(directory.resolve("live")).live()
                .lookup("member.url", "https://lookup.example.invalid/members/{id}").build();
        assertThatThrownBy(() -> live.gateway.prepare(CallIntent.of(EpaOperation.MEMBER_LOOKUP)))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    assertThat(e.operation()).isEqualTo(EpaOperation.MEMBER_LOOKUP);
                    assertThat(e.variables()).containsExactly("id");
                    assertThat(e.template()).isEqualTo("https://lookup.example.invalid/members/{id}");
                    assertThat(e.environmentName()).isNull();
                    assertThat(e.reasons()).containsEntry("id", "The url has a {id} part, which is filled from the identifier the call is "
                            + "for, and no identifier was given.");
                });
    }

    @Test
    void subscriptionGetFromTheFallbackLayerWithNoIdDropsTheIdSegmentAndLists() {
        ConfigFixture fixture = ConfigFixture.on(directory).live().onyx("subscription.url", "https://sub.example.invalid/fhir/").build();

        PreparedCall list = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET));
        // The template still shows where an id would go.
        assertThat(list.url().template()).isEqualTo("https://sub.example.invalid/fhir/Subscription/{id}");
        assertThat(list.url().value()).isEqualTo("https://sub.example.invalid/fhir/Subscription");
        assertThat(list.url().resolved()).isTrue();
        assertThat(list.url().source()).isEqualTo("application.yml: onyx.subscription.url followed by /Subscription/{id}. No id was given, "
                + "so the trailing /{id} is left off and this lists subscriptions");
        assertThat(list.method()).isEqualTo("GET");

        PreparedCall one = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withUrlValue("id", "sub 1"));
        assertThat(one.url().value()).isEqualTo("https://sub.example.invalid/fhir/Subscription/sub%201");
        assertThat(one.url().source()).isEqualTo("application.yml: onyx.subscription.url followed by /Subscription/{id}");
    }

    @Test
    void theListExceptionIsForGetAloneAndForTheFallbackLayerAlone() {
        ConfigFixture fixture = ConfigFixture.on(directory).onyx("subscription.url", "https://sub.example.invalid/fhir").build();

        // A delete or an update with no id never falls back to the address of all subscriptions.
        for (EpaOperation operation : List.of(EpaOperation.SUBSCRIPTION_DELETE, EpaOperation.SUBSCRIPTION_UPDATE)) {
            PreparedCall call = fixture.gateway.prepare(CallIntent.of(operation));
            assertThat(call.url().resolved()).as(operation.name()).isFalse();
            assertThat(call.url().unresolved()).as(operation.name()).containsExactly("id");
            assertThat(call.url().value()).as(operation.name()).isNull();
        }
        // A collection request that takes the id as a token is not shortened either.
        fixture.collectionOf("Tokens", items(item("subscription-get Happy Path", "GET", "https://col.example.invalid/fhir/Subscription/{id}")));
        PreparedCall fromCollection = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET));
        assertThat(fromCollection.url().unresolved()).containsExactly("id");
        assertThat(fromCollection.url().value()).isNull();
    }

    // ------------------------------------------------------------------------------------------
    // A url value that was supplied is never ignored
    // ------------------------------------------------------------------------------------------

    @Test
    void aUrlValueWithNoPlaceInTheUrlIsRefusedOnBothProfilesWithoutQuotingTheValue() {
        for (ConfigFixture fixture : List.of(ConfigFixture.mock(directory.resolve("mock")), ConfigFixture.live(directory.resolve("live")))) {
            fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);

            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withUrlValue("id", "S123456789")))
                    .isInstanceOfSatisfying(BadInputException.class, e -> {
                        assertThat(e.getMessage()).isEqualTo("This call supplies a value for \"id\", and the url it would go to has no {id} "
                                + "or :id to put it in. The url comes from the request \"PAS / inquiry Happy Path\" of the collection "
                                + "\"Onyx Sandbox ePA\" and reads {{baseUrl}}/Claim/$inquire.");
                        assertThat(e.getMessage() + e.action()).doesNotContain("S123456789");
                    });
            // The create url has no place for an id, and the id is not bridged onto anything there.
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_CREATE).withUrlValue("id", "sub-1")))
                    .isInstanceOf(BadInputException.class);
            // A value under another name is not ignored either, even beside one that has its place.
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)
                    .withUrlValues(Map.of("id", "sub-1", "tenant", "p32h")))).isInstanceOf(BadInputException.class)
                    .hasMessageStartingWith("This call supplies a value for \"tenant\"");
        }
    }

    @Test
    void aSubscriptionIdIsBoundToThePlaceholderThatHoldsItsPlaceAheadOfEveryOtherLayer() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        for (EpaOperation operation : List.of(EpaOperation.SUBSCRIPTION_GET, EpaOperation.SUBSCRIPTION_UPDATE, EpaOperation.SUBSCRIPTION_DELETE)) {
            // The environment holds "sample-subscription-1", and a value was typed for the run as well. Neither is where this call goes.
            PreparedCall call = fixture.gateway.prepare(CallIntent.of(operation).withUrlValue("id", "sub 42+a")
                    .withRunValues(Map.of("subscriptionId", "typed-subscription")));

            assertThat(call.url().template()).as(operation.name()).isEqualTo("{{baseUrl}}/Subscription/{{subscriptionId}}");
            // Percent-encoded, as any value that fills a place in a url is.
            assertThat(call.url().value()).as(operation.name()).isEqualTo(SANDBOX_FHIR + "/Subscription/sub%2042%2Ba");
            assertThat(call.url().display()).as(operation.name()).isEqualTo(call.url().value());
            assertThat(call.url().value()).doesNotContain("sample-subscription-1").doesNotContain("typed-subscription");
            VariableUse bound = call.url().variables().stream().filter(use -> use.name().equals("subscriptionId")).findFirst().orElseThrow();
            assertThat(bound.source()).isEqualTo(VariableSource.RUN_VALUE);
            // Text that belongs to the call carries the id as it was given.
            assertThat(call.resolve("{\"id\": \"{{subscriptionId}}\"}").value()).isEqualTo("{\"id\": \"sub 42+a\"}");
        }
        // With no id named, the url is resolved as written.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET)).url().value())
                .isEqualTo(SANDBOX_FHIR + "/Subscription/sample-subscription-1");
    }

    @Test
    void aStaleIdLeftInAnEnvironmentNeverRedirectsADelete() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        // This environment holds "stale-subscription-9". It is uat here so that the write is not blocked.
        fixture.environmentOf("Stale", EnvironmentKind.UAT, "baseUrl", "https://uat.example.invalid/fhir", "subscriptionId", "stale-subscription-9");
        fixture.store.activate("Stale");
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "created-by-this-run"));

        assertThat(call.url().value()).isEqualTo("https://uat.example.invalid/fhir/Subscription/created-by-this-run");
    }

    @Test
    void anIdIsRefusedAgainstAUrlThatAddressesADifferentSubscriptionNamingBoth() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        // Its url ends in the literal "does-not-exist".
        RequestRef errorSample = ref(vendor(fixture), "subscription-get Error");

        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withRequest(errorSample)
                .withUrlValue("id", "sub-42"))).isInstanceOfSatisfying(BadInputException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("The url of the request \"Subscription / subscription-get Error\" of the collection "
                            + "\"Onyx Sandbox ePA\" addresses the subscription \"does-not-exist\", and this call is for the subscription "
                            + "\"sub-42\".");
                    assertThat(e.action()).startsWith("Nothing was sent.");
                });

        // The same literal as the id is no disagreement.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withRequest(errorSample)
                .withUrlValue("id", "does-not-exist")).url().value()).isEqualTo(SANDBOX_FHIR + "/Subscription/does-not-exist");
        // And with no id named, the request is sent as the vendor wrote it.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_GET).withRequest(errorSample)).url().value())
                .isEqualTo(SANDBOX_FHIR + "/Subscription/does-not-exist");
    }

    @Test
    void anIdWithNoSegmentAfterSubscriptionToTakeItIsRefused() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.collectionOf("No id place", items(item("subscription-delete Happy Path", "DELETE", "https://x.example.invalid/fhir/Subscription")));

        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub-1")))
                .isInstanceOf(BadInputException.class).hasMessageStartingWith("This call supplies a value for \"id\"");
    }

    // ------------------------------------------------------------------------------------------
    // Headers
    // ------------------------------------------------------------------------------------------

    @Test
    void theHeadersOfTheCollectionRequestGoWithTheCallAndADisabledOneDoesNot() {
        ConfigFixture fixture = sandboxWithVendorCollection();

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));

        // X-Request-Trace is switched off in the collection.
        assertThat(call.headers()).extracting(ResolvedHeader::name).containsExactly("Content-Type", "Accept");
        assertThat(call.headers()).allMatch(ResolvedHeader::resolved);
        assertThat(header(call, "Content-Type").value()).isEqualTo("application/json");
        assertThat(header(call, "Content-Type").display()).isEqualTo("application/json");
    }

    @Test
    void aHeaderThatStillHoldsAPlaceholderIsReturnedAsUnresolvedAndNeitherDroppedNorSentEmpty() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collection(PostmanFiles.NESTED_COLLECTION);

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub-1"));

        assertThat(call.headers()).extracting(ResolvedHeader::name).containsExactly("Accept", "X-Trace");
        assertThat(header(call, "Accept").resolved()).isTrue();
        ResolvedHeader trace = header(call, "X-Trace");
        assertThat(trace.resolved()).isFalse();
        assertThat(trace.unresolved()).containsExactly("traceId");
        assertThat(trace.value()).isNull();
        assertThat(trace.display()).isNull();
        // The url itself resolved, so the call is prepared, on live as on mock. The sending layer refuses it by the header.
        assertThat(call.url().resolved()).isTrue();

        PreparedCall typed = fixture.gateway.prepare(CallIntent.of(EpaOperation.SUBSCRIPTION_DELETE).withUrlValue("id", "sub-1")
                .withRunValues(Map.of("traceId", "trace-1")));
        assertThat(header(typed, "X-Trace").value()).isEqualTo("trace-1");
    }

    @Test
    void aHeaderIsShownWithItsSecretsMaskedAndACredentialHeaderShowsItsSchemeAtMost() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Headers", items(item("inquiry Happy Path", "POST", "{{baseUrl}}/Claim/$inquire")
                .header("X-Tenant", "{{tenantCode}}").header("X-Note", "for {{clientId}} with {{tenantCode}}")
                .header("Authorization", "Bearer {{accessToken}}").header("X-Api-Key", "literal-key-typed-into-the-collection")
                .header("Cookie", "session={{clientSecret}}")));

        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRunValues(Map.of("accessToken", "typed-token-1")));

        // Postman typed tenantCode as a secret. Its name alone would not have said so.
        assertThat(header(call, "X-Tenant").value()).isEqualTo("placeholder-tenant-value");
        assertThat(header(call, "X-Tenant").display()).isEqualTo("[secret tenantCode]");
        assertThat(header(call, "X-Note").value()).isEqualTo("for pave-sandbox-client with placeholder-tenant-value");
        assertThat(header(call, "X-Note").display()).isEqualTo("for pave-sandbox-client with [secret tenantCode]");
        assertThat(header(call, "Authorization").value()).isEqualTo("Bearer typed-token-1");
        assertThat(header(call, "Authorization").display()).isEqualTo("Bearer [redacted]");
        // Typed literally, so no variable name could have masked it.
        assertThat(header(call, "X-Api-Key").value()).isEqualTo("literal-key-typed-into-the-collection");
        assertThat(header(call, "X-Api-Key").display()).isEqualTo("[redacted]");
        assertThat(header(call, "Cookie").value()).isEqualTo("session=placeholder-sandbox-secret");
        assertThat(header(call, "Cookie").display()).isEqualTo("[redacted]");
        for (ResolvedHeader each : call.headers()) {
            assertThat(each.toString() + each.display()).doesNotContain("placeholder-tenant-value").doesNotContain("typed-token-1")
                    .doesNotContain("literal-key").doesNotContain("placeholder-sandbox-secret");
        }
    }

    // ------------------------------------------------------------------------------------------
    // The configuration the caller saw
    // ------------------------------------------------------------------------------------------

    @Test
    void aCallThatNamesAnEpochThatIsNoLongerCurrentIsRefusedOnBothProfilesForReadsAndWrites() {
        for (ConfigFixture fixture : List.of(ConfigFixture.mock(directory.resolve("mock")), ConfigFixture.live(directory.resolve("live")))) {
            fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
            fixture.collection(PostmanFiles.VENDOR_COLLECTION);
            long drawnAt = fixture.gateway.epoch();

            // The epoch the screen was drawn from, and no epoch at all, are both accepted.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withExpectedEpoch(drawnAt)).epoch()).isEqualTo(drawnAt);
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_SUBMIT).withExpectedEpoch(drawnAt)).epoch()).isEqualTo(drawnAt);
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withExpectedEpoch(null)).epoch()).isEqualTo(drawnAt);

            // Another tab activates another environment.
            fixture.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);

            for (EpaOperation operation : List.of(EpaOperation.PAS_INQUIRE, EpaOperation.PAS_SUBMIT, EpaOperation.CRD_ORDER_SIGN)) {
                assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(operation).withExpectedEpoch(drawnAt))).as(operation.name())
                        .isInstanceOf(ConfigurationChangedException.class)
                        .hasMessage(ConfigurationChangedException.sinceTheScreenWasDrawn().getMessage());
            }
            // An epoch from the future is not current either.
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)
                    .withExpectedEpoch(fixture.gateway.epoch() + 1))).isInstanceOf(ConfigurationChangedException.class);
            // With no epoch named the call is not checked, and goes to what is active now.
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)).url().value())
                    .isEqualTo("https://uat.onyx.example.invalid/fhir/Claim/$inquire");
            assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withExpectedEpoch(fixture.gateway.epoch()))
                    .environment().name()).isEqualTo("Onyx UAT");
        }
    }

    @Test
    void theEpochIsCheckedBeforeAnythingElseIsDecided() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        long stale = fixture.gateway.epoch() - 1;

        // Each of these would be refused for a reason of its own. The stale epoch is what is reported.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withExpectedEpoch(stale)
                .withRequest(new RequestRef("no-such-collection", "r-1")))).isInstanceOf(ConfigurationChangedException.class);
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withExpectedEpoch(stale)
                .withRequest(ref(vendor(fixture), "submit Happy Path")))).isInstanceOf(ConfigurationChangedException.class);
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withExpectedEpoch(stale)
                .withUrlValue("id", "x"))).isInstanceOf(ConfigurationChangedException.class);
    }

    @Test
    void aPreparedCallBelongsToTheSnapshotItWasPreparedAgainst() {
        ConfigFixture fixture = sandboxWithVendorCollection();
        PreparedCall call = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));

        fixture.activate(PostmanFiles.UAT_ENVIRONMENT, EnvironmentKind.UAT);

        // The call is no longer current, which is what the sending layer checks.
        assertThat(call.epoch()).isNotEqualTo(fixture.gateway.epoch());
        // What it resolves still belongs to the environment it was prepared in, never half to the new one.
        assertThat(call.environment().name()).isEqualTo("Onyx Sandbox");
        assertThat(call.resolve("{{baseUrl}}").value()).isEqualTo(SANDBOX_FHIR);
        assertThat(call.url().value()).isEqualTo(SANDBOX_FHIR + "/Claim/$inquire");
    }

    // ------------------------------------------------------------------------------------------
    // Mock and live
    // ------------------------------------------------------------------------------------------

    @Test
    void onLiveATargetThatIsNotConfiguredIsRefusedBeforeAnythingIsSent() {
        ConfigFixture fixture = ConfigFixture.live(directory);

        for (EpaOperation operation : List.of(EpaOperation.CRD_ORDER_SIGN, EpaOperation.CRD_DISCOVERY, EpaOperation.PAS_INQUIRE,
                EpaOperation.SUBSCRIPTION_GET, EpaOperation.MEMBER_LOOKUP, EpaOperation.PROVIDER_LOOKUP, EpaOperation.UNMAPPED)) {
            assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(operation).withMethod("GET"))).as(operation.name())
                    .isInstanceOfSatisfying(EndpointNotConfiguredException.class, e -> assertThat(e.operation()).isEqualTo(operation));
        }
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)))
                .isInstanceOf(EndpointNotConfiguredException.class)
                .hasMessage("No collection request is mapped to PAS inquire, and onyx.pas.url is not set.");
    }

    @Test
    void onLiveAnUnresolvedUrlIsRefusedNamingEveryPlaceholderAndTheActiveEnvironment() {
        ConfigFixture fixture = ConfigFixture.live(directory);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);

        // No environment is active. The service id still resolves, from the collection's own variable.
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    assertThat(e.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
                    assertThat(e.variables()).containsExactly("crdUrl");
                    assertThat(e.environmentName()).isNull();
                    assertThat(e.template()).isEqualTo("{{crdUrl}}/cds-services/{{crdOrderSignServiceId}}");
                    assertThat(e.reasons()).isEmpty();
                });

        fixture.activeEnvironmentOf("Half filled", EnvironmentKind.SANDBOX, "baseUrl", "", "crdUrl", "https://{{crdHost}}:{{crdPort}}");
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    // The variables at the root of the problem, which are the ones a tester can type.
                    assertThat(e.variables()).containsExactly("crdHost", "crdPort");
                    assertThat(e.environmentName()).isEqualTo("Half filled");
                });
        assertThatThrownBy(() -> fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE)))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, e -> {
                    assertThat(e.variables()).containsExactly("baseUrl");
                    assertThat(e.environmentName()).isEqualTo("Half filled");
                    // It is there and blank, and the reason says so, so the tester is not told to add it.
                    assertThat(e.reasons()).containsEntry("baseUrl", "It is defined in the environment \"Half filled\", but its value is blank.");
                });

        // Typed for the run, the same call is prepared.
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE).withRunValues(Map.of("baseUrl", "https://typed.example.invalid")))
                .url().value()).isEqualTo("https://typed.example.invalid/Claim/$inquire");
    }

    @Test
    void onMockTheSameGapsAreNotErrorsAndTheCallSaysWhatWasAndWasNotResolved() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.collection(PostmanFiles.VENDOR_COLLECTION);
        fixture.activeEnvironmentOf("Half filled", EnvironmentKind.SANDBOX, "baseUrl", "", "crdUrl", "https://{{crdHost}}:{{crdPort}}");

        PreparedCall inquire = fixture.gateway.prepare(CallIntent.of(EpaOperation.PAS_INQUIRE));
        assertThat(inquire.url().configured()).isTrue();
        assertThat(inquire.url().resolved()).isFalse();
        assertThat(inquire.url().unresolved()).containsExactly("baseUrl");
        assertThat(inquire.url().value()).isNull();
        assertThat(inquire.url().display()).isNull();
        assertThat(inquire.url().template()).isEqualTo("{{baseUrl}}/Claim/$inquire");
        assertThat(inquire.url().variables()).containsExactly(new VariableUse("baseUrl", VariableSource.UNRESOLVED, false, null));
        assertThat(inquire.requestName()).isEqualTo("inquiry Happy Path");

        PreparedCall orderSign = fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN));
        assertThat(orderSign.url().unresolved()).containsExactly("crdHost", "crdPort");
        // What did resolve is reported beside what did not.
        assertThat(orderSign.url().variables()).filteredOn(use -> use.source() != VariableSource.UNRESOLVED).extracting(VariableUse::name)
                .containsExactly("crdOrderSignServiceId");
    }
}
