package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.SampleCase;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.crd.CrdService;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;

/**
 * The case each bundled scenario file names for one click on the mock scenario table. A sample is
 * only worth the click when it can be sent as it stands and when, on CRD, the bundled code list
 * agrees with what the scenario answers: otherwise a run that went right would show a disagreement
 * the tool itself put there.
 */
class SampleCaseTest {

    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York"));
    /** The CRD files whose answer takes a position the code list can be compared with, so nothing may be left uncompared. */
    private static final Set<String> COMPARED = Set.of("crd-01-pa-required", "crd-02-no-auth", "crd-03-pa-satisfied", "crd-04-doc-dtr",
            "crd-10-delegated-vendor");

    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final ProcedureCodeService codeList = new ProcedureCodeService(CodelistProperties.defaults());

    @Test
    void everyBundledFileNamesASampleWhoseNpiPassesTheCheckAndWhoseCodesAreInTheBundledList() {
        for (String protocol : List.of("crd", "dtr", "pas")) {
            ScenarioRepository.Loaded loaded = scenarios.load(protocol);
            assertThat(loaded.problems()).as(protocol).isEmpty();
            for (Scenario scenario : loaded.scenarios()) {
                SampleCase sample = scenario.summary().sampleCase();
                assertThat(sample).as(scenario.source()).isNotNull();
                assertThat(Npi.isValid(sample.npi())).as(scenario.source()).isTrue();
                assertThat(sample.procedureCodes()).as(scenario.source()).isNotEmpty();
                for (String code : sample.procedureCodes()) {
                    assertThat(codeList.rowsFor(code, null, null)).as(scenario.source() + " " + code).isNotEmpty();
                }
                // A file that a sentinel NPI selects keeps that NPI in its sample, so the click does not pick another file.
                if (scenario.sentinelNpi() != null) {
                    assertThat(sample.npi()).as(scenario.source()).isEqualTo(scenario.sentinelNpi());
                } else {
                    // The known valid example, never a number that could belong to a real provider.
                    assertThat(sample.npi()).as(scenario.source()).isEqualTo("1234567893");
                }
            }
        }
    }

    @Test
    void onCrdEachSampleSelectsItsOwnFileAndTheCodeListDoesNotDisagreeWithTheAnswer() {
        for (Scenario scenario : scenarios.load("crd").scenarios()) {
            SampleCase sample = scenario.summary().sampleCase();
            InMemoryLedger ledger = new InMemoryLedger();
            RunEnvelope<CrdDetail> result = service(ledger).run(CrdRunRequest.builder()
                    .member(MemberDetail.builder().memberId(scenario.sentinelMemberId()).lineOfBusiness(lineOfBusiness(scenario.sentinelMemberId()))
                            .build())
                    .provider(ProviderDetail.builder().npi(sample.npi()).build())
                    .procedureCodes(sample.procedureCodes().stream().map(code -> new CodeInput(code, null, null)).toList())
                    .dateOfService("2026-03-20").build(), RunOrigin.manual(), null);

            assertThat(ledger.records()).as(scenario.source()).hasSize(1);
            assertThat(ledger.records().get(0).scenarioId()).as(scenario.source()).isEqualTo(scenario.id());
            assertThat(result.findings()).as(scenario.source())
                    .noneMatch(finding -> finding.kind() == FindingKind.CODE_LIST_DISAGREES_ON_AUTH
                            || finding.kind() == FindingKind.CODE_LIST_DISAGREES_ON_VENDOR);
            if (COMPARED.contains(scenario.id())) {
                assertThat(result.findings()).as(scenario.source()).noneMatch(finding -> finding.kind() == FindingKind.CODE_LIST_NOT_COMPARED);
            }
        }
    }

    private CrdService service(InMemoryLedger ledger) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), CLOCK);
        return new CrdService(epa, FakeGateway.unconfigured(), new MockOnyxCrdClient(scenarios, new MockExchanges(APP)), codeList, support);
    }

    /**
     * The line of business the member lookup fills in for the sentinel, as the fixture's expect block
     * claims it (the fixtures' own test holds the lookup to that claim); null when there is no fixture.
     */
    private static String lineOfBusiness(String memberId) {
        try (InputStream in = SampleCaseTest.class.getResourceAsStream("/fixtures/members/" + memberId.toLowerCase(Locale.ROOT) + ".json")) {
            if (in == null) {
                return null;
            }
            JsonNode expect = JsonSafe.child(JsonSafe.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8)).node(), "expect");
            return JsonSafe.presentText(expect, "lineOfBusiness");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
