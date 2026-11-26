package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.config.ConfigViews.AvailableRequest;
import org.p32h.interop.epa.pave.service.config.ConfigViews.OperationRow;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The request a Workbench step uses (D-313). A vendor's collection holds several requests for one
 * step, each for a service of its own, and the first in the file was used without a word: the
 * list a step offers names every request that fits with the one in force and the samples of an
 * error marked; a request named for a run goes to its own address with its own headers even while
 * an address is typed, and the result says which; "Use for every run" makes the chosen one stand
 * for the operation.
 */
class StepRequestTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final String TYPED = "https://typed.vendor.example.invalid/typed/cds-services/order-sign";

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();

    /** Two collections: the first for another service, with a sample of an error; the second for the tester's. */
    private ConfigFixture twoCollections() {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        fixture.collectionOf("Other vendor", ConfigFixture.items(
                ConfigFixture.item("order-sign Other service", "POST", "{{crdUrl}}/other/cds-services/order-sign")
                        .header("X-Service", "other").body("{\"hook\": \"order-sign\"}"),
                ConfigFixture.item("order-sign error missing patient", "POST", "{{crdUrl}}/other/cds-services/order-sign")
                        .body("{\"hook\": \"order-sign\"}")));
        fixture.collectionOf("Vendor", ConfigFixture.items(
                ConfigFixture.item("order-sign Second service", "POST", "{{crdUrl}}/second/cds-services/order-sign")
                        .header("X-Service", "second").body("{\"hook\": \"order-sign\"}")));
        return fixture;
    }

    private static void typeAddress(ConfigFixture fixture) {
        StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
        fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                stored -> new EnvironmentSettings(null, null, Map.of(EpaOperation.CRD_ORDER_SIGN, TYPED), null, null, null)),
                (stored, after) -> null);
    }

    private static OperationRow row(ConfigFixture fixture, EpaOperation operation) {
        return fixture.service.effective(null).operations().stream().filter(each -> each.operation() == operation).findFirst().orElseThrow();
    }

    private static List<String> names(OperationRow row) {
        return row.requests().stream().map(each -> each.collectionName() + ": " + each.request().name()).toList();
    }

    private CrdService crd(ConfigFixture fixture) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(support.epa(), fixture.gateway, new MockOnyxCrdClient(new ScenarioRepository(APP), new MockExchanges(APP)),
                new FakeCodeList(), support);
    }

    private static CrdRunRequest.Builder theCase() {
        return CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId("S123456789").firstName("Given").lastName("Placeholder-Surname").dateOfBirth("1970-01-31")
                        .gender("female").coverageStatus("active").build())
                .provider(ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20");
    }

    @Test
    void theListOfAStepHoldsEveryRequestThatFitsInBothCollectionsWithTheOneInForceAndTheErrorSampleMarked() {
        ConfigFixture fixture = twoCollections();
        OperationRow row = row(fixture, EpaOperation.CRD_ORDER_SIGN);

        assertThat(names(row)).containsExactly("Other vendor: order-sign Other service", "Other vendor: order-sign error missing patient",
                "Vendor: order-sign Second service");
        assertThat(row.requests()).extracting(AvailableRequest::usedByDefault).containsExactly(true, false, false);
        assertThat(row.requests()).extracting(AvailableRequest::errorSample).containsExactly(null, true, null);
        assertThat(row.target().typed()).isNull();
        assertThat(row.target().requestName()).isEqualTo("order-sign Other service");

        // An address typed in the settings is in force, said as such; no request is the one a run uses unasked.
        typeAddress(fixture);
        OperationRow typed = row(fixture, EpaOperation.CRD_ORDER_SIGN);
        assertThat(typed.target().typed()).isTrue();
        assertThat(typed.target().display()).isEqualTo(TYPED);
        assertThat(typed.requests()).extracting(AvailableRequest::usedByDefault).containsOnly(false);
        assertThat(typed.requests()).hasSize(3);
    }

    @Test
    void aRunThatNamesARequestWhileAnAddressIsTypedGoesToTheRequestsOwnAddressWithItsHeadersAndSaysWhichItWas() {
        ConfigFixture fixture = twoCollections();
        typeAddress(fixture);
        var second = fixture.ref(1, "order-sign Second service");

        RunEnvelope<CrdDetail> result = crd(fixture).run(theCase().request(second).build(), RunOrigin.manual(), null);

        assertThat(result.wire().request().url()).contains("/second/cds-services/order-sign").doesNotContain("typed.vendor");
        assertThat(result.wire().request().headers()).extracting(WireHeader::name, WireHeader::value).contains(
                org.assertj.core.groups.Tuple.tuple("X-Service", "second"));
        assertThat(result.wire().request().from()).contains("order-sign Second service").contains("Vendor");
        assertThat(result.detail().requestNotes()).first().asString()
                .startsWith("Sent with the request chosen for this run: the request ").contains("order-sign Second service");
        // The ledger names the request it went with, never its body.
        assertThat(ledger.only().targetSource()).isEqualTo("order-sign Second service");

        // A run that names none goes to the typed address, which carries no header of a request, and has no such note.
        RunEnvelope<CrdDetail> plain = crd(fixture).run(theCase().build(), RunOrigin.manual(), null);
        assertThat(plain.wire().request().url()).isEqualTo(TYPED);
        assertThat(plain.wire().request().headers()).extracting(WireHeader::name).doesNotContain("X-Service");
        assertThat(plain.wire().request().from()).startsWith("the address typed for");
        assertThat(plain.detail().requestNotes()).noneMatch(note -> note.startsWith("Sent with the request chosen"));
    }

    /**
     * "Use for every run" is the form's pick: each request that fits before the chosen one is taken
     * off the operation, a sample of an error left as it is, then the chosen one is mapped to it.
     */
    @Test
    void usingAChosenRequestForEveryRunMakesItStandForTheOperationAndInForceNowSaysSo() {
        ConfigFixture fixture = twoCollections();
        StoredCollection other = fixture.store.snapshot().collections().get(0);
        StoredCollection vendor = fixture.store.snapshot().collections().get(1);

        fixture.service.correctMapping(other.id(), ConfigFixture.ref(other, "order-sign Other service").requestId(), EpaOperation.UNMAPPED);
        fixture.service.correctMapping(vendor.id(), ConfigFixture.ref(vendor, "order-sign Second service").requestId(),
                EpaOperation.CRD_ORDER_SIGN);

        OperationRow row = row(fixture, EpaOperation.CRD_ORDER_SIGN);
        assertThat(names(row)).containsExactly("Other vendor: order-sign error missing patient", "Vendor: order-sign Second service");
        assertThat(row.requests()).extracting(AvailableRequest::usedByDefault).containsExactly(false, true);
        assertThat(row.target().requestName()).isEqualTo("order-sign Second service");
        assertThat(row.target().source()).contains("order-sign Second service");
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).url().display()).contains("/second/");

        // A typed address still wins for a run that names no request, until it is taken away.
        typeAddress(fixture);
        assertThat(fixture.gateway.prepare(CallIntent.of(EpaOperation.CRD_ORDER_SIGN)).url().display()).isEqualTo(TYPED);
    }
}
