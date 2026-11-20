package org.p32h.interop.epa.pave.service.crd;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.client.crd.MockOnyxCrdClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.crd.CrdDetail;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A key repeated inside one object: the tool reads the last value, as before, and says everything
 * it did not read. Every value is listed among the returned fields in the order sent, a key the
 * designed view read raises a finding with each value and chooses none, and the CRD mock plays a
 * scenario body that repeats a key exactly as written.
 */
class CrdRepeatedKeyTest {

    private static final String EXT = "http://hl7.org/fhir/us/davinci-crd/StructureDefinition/ext-coverage-information";
    private static final String BODY = "{\"cards\":[{\"summary\":\"ZZ-FIRST\",\"summary\":\"Prior authorization is required\"}],"
            + "\"systemActions\":[{\"type\":\"update\",\"resource\":{\"resourceType\":\"ServiceRequest\",\"id\":\"service-request-1\","
            + "\"extension\":[{\"url\":\"" + EXT + "\",\"extension\":[{\"url\":\"covered\",\"valueCode\":\"covered\"},"
            + "{\"url\":\"pa-needed\",\"valueCode\":\"no-auth\",\"valueCode\":\"auth-needed\"}]}]}}],\"zzVendor\":1,\"zzVendor\":2.50}";
    private static final String PA_NEEDED = "systemActions[0].resource.extension[0].extension[1].valueCode";

    private final CrdResponseMapper mapper = new CrdResponseMapper();

    @Test
    void theParserKeepsEveryValueOfARepeatedKeyWithItsPlace() {
        JsonSafe.Parsed parsed = JsonSafe.parse(BODY);
        assertThat(parsed.repeatedKeys()).isTrue();
        assertThat(parsed.repeated()).extracting(JsonSafe.RepeatedKey::path)
                .containsExactly("cards[0].summary", PA_NEEDED, "zzVendor");
        assertThat(parsed.repeated().get(1).values()).containsExactly("\"no-auth\"", "\"auth-needed\"");
        assertThat(parsed.repeated().get(1).pointer()).isEqualTo("/systemActions/0/resource/extension/0/extension/1/valueCode");
        // A number is kept as written.
        assertThat(parsed.repeated().get(2).values()).containsExactly("1", "2.50");
        assertThat(JsonSafe.parse("{\"a\":1}").repeated()).isEmpty();
    }

    @Test
    void everyValueIsListedAndTheAnswerIsStillTheLastValue() {
        CrdReading reading = mapper.read(TestExchanges.crd(200, BODY));
        assertThat(reading.outcome()).isEqualTo(CrdOutcome.AUTH_REQUIRED);
        List<AdditionalField> fields = reading.additionalFields();
        assertThat(fields).filteredOn(f -> f.path().startsWith(PA_NEEDED + " (value")).extracting(AdditionalField::path, AdditionalField::value)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(PA_NEEDED + " (value 1 of 2)", "\"no-auth\""),
                        org.assertj.core.groups.Tuple.tuple(PA_NEEDED + " (value 2 of 2)", "\"auth-needed\""));
        assertThat(fields).extracting(AdditionalField::value).contains("\"ZZ-FIRST\"");
        // One note about the repeated keys, and it no longer says only the last value is shown.
        assertThat(fields).filteredOn(f -> f.kind() == AdditionalField.Kind.NOTE).extracting(AdditionalField::value)
                .containsExactly(TrackedJson.REPEATED_KEY_NOTE);
    }

    @Test
    void aKeyTheViewReadRaisesAFindingWithEveryValueAndOneItDidNotReadDoesNot() {
        var exchange = TestExchanges.crd(200, BODY);
        CrdReading reading = mapper.read(exchange);
        List<Finding> findings = new CrdConformanceChecker(new FakeCodeList(), EpaProperties.of(Map.of("mode", "mock")))
                .check(reading, exchange, null, null);
        List<Finding> repeated = findings.stream().filter(f -> f.title().contains("times in one object")).toList();
        assertThat(repeated).extracting(Finding::kind).containsOnly(FindingKind.GUIDE_RULE_BROKEN);
        assertThat(repeated).extracting(Finding::title)
                .containsExactly("cards[0].summary is sent 2 times in one object", PA_NEEDED + " is sent 2 times in one object");
        assertThat(repeated.get(1).detail()).contains("first \"no-auth\", then \"auth-needed\"").contains("cannot be told")
                .doesNotContain("wrong");
    }

    @Test
    void theMockPlaysABodyThatRepeatsAKeyExactlyAsWritten(@TempDir Path dir) throws IOException {
        Path bundled = Path.of("src/main/resources/scenarios");
        try (Stream<Path> files = Files.walk(bundled)) {
            for (Path file : files.toList()) {
                Path target = dir.resolve(bundled.relativize(file).toString());
                if (Files.isDirectory(file)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(file, target);
                }
            }
        }
        String scenario = "{\"id\":\"crd-90-repeated-key\",\"number\":90,\"label\":\"Repeated key\",\"description\":\"A key sent twice.\","
                + "\"behavior\":\"respond\",\"httpStatus\":200,\"delayMillis\":0,\"basis\":\"Synthetic.\",\"responseText\":"
                + JsonSafe.mapper().writeValueAsString(BODY) + "}";
        Files.writeString(dir.resolve("crd/crd-90-repeated-key.json"), scenario);
        AppProperties app = AppProperties.of(Map.of("mock-latency-enabled", "false", "scenario-dir", dir.toString()));
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", new InMemoryLedger());
        EpaProperties epa = EpaProperties.of(Map.of("mode", "mock"));
        RunSupport support = new RunSupport(epa, TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), Clock.fixed(Instant.parse("2026-03-10T15:00:00Z"), ZoneId.of("America/New_York")));
        CrdService service = new CrdService(epa, FakeGateway.unconfigured(),
                new MockOnyxCrdClient(new ScenarioRepository(app), new MockExchanges(app)), new FakeCodeList(), support);
        RunEnvelope<CrdDetail> result = service.run(CrdRunRequest.builder()
                .member(MemberDetail.builder().memberId("S123456789").lineOfBusiness("Medicare").build())
                .provider(ProviderDetail.builder().npi("1234567893").build())
                .procedureCodes(List.of(new CodeInput("95810", null, null)))
                .dateOfService("2026-03-20").scenarioId("crd-90-repeated-key").build(), RunOrigin.manual(), null);
        assertThat(result.wire().response().body()).isEqualTo(BODY);
        assertThat(result.additionalFields()).extracting(AdditionalField::value).contains("\"no-auth\"", "\"ZZ-FIRST\"");
        assertThat(result.findings()).extracting(Finding::title).contains(PA_NEEDED + " is sent 2 times in one object");
    }
}
