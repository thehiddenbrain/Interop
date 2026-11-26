package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.RequestPreview;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The coverage check sent as the collection writes it (D-313): the stored body of the request in
 * use is what goes, the environment's values put in and Postman's dynamic values made afresh at
 * each send; what it needs and this tool cannot give is refused before anything is sent, each
 * placeholder named with why; the answer is read as any coverage check's; the ledger holds no body.
 */
class AsWrittenCoverageCheckTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String WRITTEN = "{\"hook\": \"order-sign\", \"hookInstance\": \"{{$guid}}\", \"fhirServer\": \"{{crdUrl}}/fhir\", "
            + "\"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"type\": \"collection\", \"entry\": []}}, "
            + "\"prefetch\": {\"patient\": {\"resourceType\": \"Patient\", \"identifier\": [{\"value\": \"S123456789\"}]}}, "
            + "\"extension\": {\"attempt\": \"{{$randomInt}}\"}}";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();

    private ConfigFixture fixture(String body) {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("Generate Token", "POST", "{{crdUrl}}/token").script("pm.environment.set(\"accessToken\", 'x');"),
                ConfigFixture.item("order-sign Medical", "POST", "{{crdUrl}}/epa/cds-services/order-sign").body(body)));
        return fixture;
    }

    private CrdService crd(ConfigFixture fixture) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(support.epa(), fixture.gateway, new MockOnyxCrdClient(new ScenarioRepository(APP), new MockExchanges(APP)),
                new FakeCodeList(), support);
    }

    private static CrdRunRequest asWritten() {
        return CrdRunRequest.builder().hook("order-sign").asWritten(true).build();
    }

    private static JsonNode sent(RunEnvelope<?> result) {
        return JsonSafe.parse(result.wire().request().body()).node();
    }

    @Test
    void theStoredBodyIsSentWithTheValuesPutInAndTheDynamicValuesMadeAfreshAtEachSend() {
        ConfigFixture fixture = fixture(WRITTEN);
        CrdService service = crd(fixture);

        RunEnvelope<CrdDetail> first = service.run(asWritten(), RunOrigin.manual(), null);
        RunEnvelope<CrdDetail> second = service.run(asWritten(), RunOrigin.manual(), null);

        JsonNode one = sent(first);
        JsonNode two = sent(second);
        assertThat(one.get("prefetch").get("patient").get("identifier").get(0).get("value").asString()).isEqualTo("S123456789");
        assertThat(one.get("fhirServer").asString()).doesNotContain("{{").endsWith("/fhir");
        String instance = one.get("hookInstance").asString();
        assertThat(instance).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(two.get("hookInstance").asString()).isNotEqualTo(instance);
        assertThat(Integer.parseInt(one.get("extension").get("attempt").asString())).isBetween(0, 1000);
        // Nothing of PAVE's own arrangement went: no named Bundles.
        assertThat(one.get("prefetch").has("patientBundle")).isFalse();

        // The answer is read as any coverage check's, and the notes say what was sent.
        assertThat(first.verdict()).isNotNull();
        assertThat(first.nextStep()).isNotNull();
        assertThat(first.detail().requestNotes()).anyMatch(note -> note.startsWith("The body is the collection's own, from the request \"order-sign Medical\""))
                .anyMatch(note -> note.startsWith("Made afresh for this send, as Postman does: {{$guid}}, {{$randomInt}}"))
                .noneMatch(note -> note.contains("you supplied"));

        // The ledger line says a body of its own went, and holds no body.
        RunRecord line = ledger.records().get(0);
        assertThat(line.unrecordedInputs()).contains(RunRecord.INPUT_BODY);
        String recorded = JsonSafe.mapper().writeValueAsString(line);
        assertThat(recorded).doesNotContain("hookInstance").doesNotContain(instance).doesNotContain("prefetch");
    }

    @Test
    void whatTheBodyNeedsAndPaveCannotGiveIsRefusedBeforeTheSendEachPlaceholderNamedWithWhy() {
        String needing = "{\"hook\": \"order-sign\", \"hookInstance\": \"{{$guid}}\", \"token\": \"{{accessToken}}\", "
                + "\"who\": \"{{$randomFirstName}}\", \"site\": \"{{noSuchValueAnywhere}}\"}";
        CrdService service = crd(fixture(needing));

        assertThatThrownBy(() -> service.run(asWritten(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(UnresolvedVariablesException.class, refusal -> {
                    assertThat(refusal.variables()).containsExactly("accessToken", "$randomFirstName", "noSuchValueAnywhere");
                    assertThat(refusal.reasons().get("accessToken")).startsWith("a script of the collection sets {{accessToken}}, and PAVE "
                            + "never runs a script.");
                    assertThat(refusal.reasons().get("$randomFirstName")).startsWith("Postman makes {{$randomFirstName}} itself, and PAVE "
                            + "makes only {{$guid}}, {{$randomUUID}}, {{$timestamp}}, {{$isoTimestamp}}, {{$randomInt}}.");
                    // Defined nowhere: the refusal's own words, which point at Values for this run.
                    assertThat(refusal.reasons()).doesNotContainKey("noSuchValueAnywhere");
                });
        assertThat(ledger.records()).isEmpty();

        // A value typed for the run is put in, and the run goes.
        RunEnvelope<CrdDetail> typed = service.run(CrdRunRequest.builder().hook("order-sign").asWritten(true)
                .variables(Map.of("accessToken", "typed-token-placeholder", "$randomFirstName", "Pat", "noSuchValueAnywhere", "here")).build(),
                RunOrigin.manual(), null);
        assertThat(sent(typed).get("site").asString()).isEqualTo("here");

        // The preview names the same, and sends nothing.
        RequestPreview preview = service.preview(asWritten(), null);
        assertThat(preview.notes()).anyMatch(note -> note.startsWith("No value for {{accessToken}}: a script of the collection sets"));
    }

    @Test
    void aBodyAsWrittenIsRefusedWhereThereIsNoneAndBesideABodyOfTheTestersOwn() {
        ConfigFixture fixture = fixture(WRITTEN);
        CrdService service = crd(fixture);

        assertThatThrownBy(() -> service.run(CrdRunRequest.builder().hook("order-sign").asWritten(true).rawBody("{}").build(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, refusal -> assertThat(refusal.fieldErrors().get(0).field()).isEqualTo("asWritten"));

        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN,
                        "https://typed.vendor.example.invalid/cds-services/order-sign"), null, null, null)), (stored, after) -> null);
        assertThatThrownBy(() -> service.run(asWritten(), RunOrigin.manual(), null))
                .isInstanceOfSatisfying(ValidationException.class, refusal -> assertThat(refusal.fieldErrors().get(0).message())
                        .startsWith("This step goes to the address typed in the settings, which carries no body of a collection request."));
        // Naming the request sends its body all the same, to its own address.
        RunEnvelope<CrdDetail> named = service.run(CrdRunRequest.builder().hook("order-sign").asWritten(true)
                .request(fixture.ref(0, "order-sign Medical")).build(), RunOrigin.manual(), null);
        assertThat(named.wire().request().url()).contains("/epa/cds-services/order-sign");
        assertThat(sent(named).get("prefetch").has("patient")).isTrue();
        assertThat(ledger.records()).hasSize(1);
    }
}
