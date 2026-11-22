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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.RunRecord;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrService;
import org.p32h.interop.epa.pave.service.run.MemberIdRuleInRequestsTest;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The member ID rule an environment states reaches every CRD and DTR request through the real
 * gateway (R-10): the body sends the rule's form, the wire view names the form and the rule, a
 * preview says the same, and the ledger keeps the packed form, so one member stays one key.
 */
class MemberIdRuleThroughTheServicesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));

    @TempDir
    Path directory;

    private final InMemoryLedger ledger = new InMemoryLedger();
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);

    private ConfigFixture fixture(MemberIdRule rule) {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activate(PostmanFiles.SANDBOX_ENVIRONMENT, EnvironmentKind.SANDBOX);
        if (rule != null) {
            StoredEnvironment sandbox = fixture.store.snapshot().byName("Onyx Sandbox");
            fixture.store.saveSettings(new ConfigStore.SettingsSave(sandbox.id(), null, null, false, null, null,
                    stored -> new EnvironmentSettings(null, null, null, rule, null, null)), (stored, after) -> null);
        }
        return fixture;
    }

    private RunSupport support() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        return new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
    }

    private CrdService crd(ConfigFixture fixture) {
        RunSupport support = support();
        return new CrdService(support.epa(), fixture.gateway, new MockOnyxCrdClient(scenarios, new MockExchanges(APP)), new FakeCodeList(),
                support);
    }

    private DtrService dtr(ConfigFixture fixture) {
        RunSupport support = support();
        return new DtrService(support.epa(), fixture.gateway, new MockOnyxDtrClient(scenarios, new MockExchanges(APP)), new FakeCodeList(),
                support);
    }

    private static MemberDetail member(String memberId) {
        return MemberDetail.builder().memberId(memberId).firstName("Given").lastName("Placeholder-Surname").dateOfBirth("1970-01-31")
                .gender("female").coverageStatus("active").build();
    }

    private static ProviderDetail provider() {
        return ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    }

    private static CrdRunRequest crdRequest(String memberId) {
        return CrdRunRequest.builder().member(member(memberId)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20").build();
    }

    private static DtrPackageRequest dtrRequest(String memberId) {
        return DtrPackageRequest.builder().member(member(memberId)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20").build();
    }

    private static JsonNode body(RunEnvelope<?> result) {
        return JsonSafe.parse(result.wire().request().body()).node();
    }

    @ParameterizedTest
    @EnumSource(MemberIdRule.class)
    void aCrdRunSendsTheRulesFormNamesItOnTheWireAndKeepsThePackedFormInTheLedger(MemberIdRule rule) {
        RunEnvelope<CrdDetail> result = crd(fixture(rule)).run(crdRequest(MemberIdRuleInRequestsTest.CARD_FORM), RunOrigin.manual(), null);

        String sent = MemberIdRuleInRequestsTest.expected(rule);
        assertThat(MemberIdRuleInRequestsTest.memberIdentifiers(body(result))).containsExactly(sent, sent);
        MemberIdForms wire = result.wire().memberId();
        assertThat(wire.typed()).isEqualTo(MemberIdRuleInRequestsTest.CARD_FORM);
        assertThat(wire.sent()).isEqualTo(sent);
        assertThat(wire.rule()).isEqualTo(rule.word());
        assertThat(wire.note()).isNull();
        RunRecord line = ledger.only();
        assertThat(line.memberId()).isEqualTo(MemberIdRuleInRequestsTest.PACKED);
    }

    @ParameterizedTest
    @EnumSource(MemberIdRule.class)
    void aDtrPackageRunSendsTheRulesFormNamesItOnTheWireAndKeepsThePackedFormInTheLedger(MemberIdRule rule) {
        RunEnvelope<DtrDetail> result = dtr(fixture(rule)).runPackage(dtrRequest(MemberIdRuleInRequestsTest.CARD_FORM), RunOrigin.manual(),
                null);

        String sent = MemberIdRuleInRequestsTest.expected(rule);
        assertThat(MemberIdRuleInRequestsTest.memberIdentifiers(body(result))).containsExactly(sent, sent);
        assertThat(result.wire().memberId().sent()).isEqualTo(sent);
        assertThat(result.wire().memberId().rule()).isEqualTo(rule.word());
        assertThat(ledger.only().memberId()).isEqualTo(MemberIdRuleInRequestsTest.PACKED);
    }

    @Test
    void anIdOfAnotherLengthGoesPackedWithTheNoteOnTheWire() {
        RunEnvelope<CrdDetail> result = crd(fixture(MemberIdRule.SPLIT)).run(crdRequest(MemberIdRuleInRequestsTest.NINE), RunOrigin.manual(),
                null);
        assertThat(MemberIdRuleInRequestsTest.memberIdentifiers(body(result))).containsExactly("S12345678", "S12345678");
        assertThat(result.wire().memberId().rule()).isEqualTo("split");
        assertThat(result.wire().memberId().note())
                .isEqualTo("The split rule applies to an 11-character ID; this one has 9, so it was sent packed.");
    }

    @Test
    void aPreviewShowsTheRulesFormAndRecordsNothing() {
        ConfigFixture fixture = fixture(MemberIdRule.SPACED);
        var crdPreview = crd(fixture).preview(crdRequest(MemberIdRuleInRequestsTest.CARD_FORM), null);
        assertThat(crdPreview.memberId().sent()).isEqualTo(MemberIdRuleInRequestsTest.SPACED);
        assertThat(crdPreview.memberId().rule()).isEqualTo("spaced");
        assertThat(MemberIdRuleInRequestsTest.memberIdentifiers(JsonSafe.parse(crdPreview.body()).node()))
                .containsExactly(MemberIdRuleInRequestsTest.SPACED, MemberIdRuleInRequestsTest.SPACED);
        var dtrPreview = dtr(fixture).previewPackage(dtrRequest(MemberIdRuleInRequestsTest.CARD_FORM), null);
        assertThat(dtrPreview.memberId().sent()).isEqualTo(MemberIdRuleInRequestsTest.SPACED);
        assertThat(ledger.records()).isEmpty();
    }

    @Test
    void anEnvironmentThatStatesNoRuleSendsPacked() {
        RunEnvelope<CrdDetail> result = crd(fixture(null)).run(crdRequest(MemberIdRuleInRequestsTest.CARD_FORM), RunOrigin.manual(), null);
        assertThat(MemberIdRuleInRequestsTest.memberIdentifiers(body(result)))
                .containsExactly(MemberIdRuleInRequestsTest.PACKED, MemberIdRuleInRequestsTest.PACKED);
        assertThat(result.wire().memberId().rule()).isEqualTo("packed");
    }

    @Test
    void aTestMemberIdStillPicksItsMockScenarioUnderTheSplitRule() {
        // The sentinel is not 11 characters, but the point is the choice: it is made by the packed form, never the sent one.
        RunEnvelope<CrdDetail> result = crd(fixture(MemberIdRule.SPLIT)).run(crdRequest("P32H-PA-REQ"), RunOrigin.manual(), null);
        assertThat(result.verdict().outcomeCode()).isEqualTo("AUTH_REQUIRED");
    }
}
