package org.p32h.interop.epa.pave.service.dtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.dtr.MockOnyxDtrClient;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.config.TrackingProperties;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.RunEnvelope;
import org.p32h.interop.epa.pave.model.RunOrigin;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.RunLedger;
import org.p32h.interop.epa.pave.service.run.MemberInText;
import org.p32h.interop.epa.pave.service.run.RunSupport;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import org.p32h.interop.epa.pave.testsupport.InMemoryLedger;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * A typed questionnaire address is free text the ledger keeps, so it is held to the member-name
 * check, unless it is exactly one of the addresses this tool's own bundled scenarios name: those and
 * the mock surnames are made of the same placeholder words (round2-left 2).
 */
class DtrQuestionnaireAddressNameTest {

    private static final String SLEEP = "https://example.org/p32h-placeholder/Questionnaire/sleep-study-criteria";
    private static final String NOTHING_SENT = "Nothing was sent.";

    private final InMemoryLedger ledger = new InMemoryLedger();

    private DtrService service() {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("ledger", ledger);
        RunSupport support = new RunSupport(EpaProperties.of(Map.of("mode", "mock")), TrackingProperties.of(Map.of()), OnyxProperties.defaults(),
                beans.getBeanProvider(RunLedger.class), DtrFixtures.CLOCK);
        ScenarioRepository scenarios = new ScenarioRepository(DtrFixtures.APP);
        return new DtrService(support.epa(), FakeGateway.unconfigured(), new MockOnyxDtrClient(scenarios, new MockExchanges(DtrFixtures.APP)),
                new FakeCodeList(), support);
    }

    private RunEnvelope<DtrDetail> fetch(String... addresses) {
        return service().runPackage(DtrFixtures.packageRequest("S123456789").questionnaires(List.of(addresses)).build(), RunOrigin.manual(), null);
    }

    private List<FieldError> refused(String... addresses) {
        int before = ledger.records().size();
        java.util.concurrent.atomic.AtomicReference<List<FieldError>> errors = new java.util.concurrent.atomic.AtomicReference<>();
        assertThatThrownBy(() -> fetch(addresses)).isInstanceOfSatisfying(ValidationException.class, e -> errors.set(e.fieldErrors()));
        // Refused before anything is sent: no ledger line.
        assertThat(ledger.records()).hasSize(before);
        return errors.get();
    }

    @Test
    void theBundledAddressesAreTheOnesTheScenariosNameWithAndWithoutTheirVersion() {
        assertThat(BundledQuestionnaires.addresses()).contains(SLEEP, SLEEP + "|1.0.0",
                "https://example.org/p32h-placeholder/Questionnaire/lumbar-mri-adaptive",
                "https://example.org/p32h-placeholder/Questionnaire/lumbar-mri-adaptive|1.0.0");
        assertThat(BundledQuestionnaires.addresses()).allSatisfy(address -> assertThat(address).startsWith("http").contains("/Questionnaire/"));
        assertThat(BundledQuestionnaires.isBundled("  " + SLEEP + " ")).isTrue();
        // Exactly one of them, and nothing near one.
        assertThat(BundledQuestionnaires.isBundled(SLEEP + "|9.9")).isFalse();
        assertThat(BundledQuestionnaires.isBundled(SLEEP.toUpperCase(java.util.Locale.ROOT))).isFalse();
        assertThat(BundledQuestionnaires.isBundled(null)).isFalse();
    }

    @Test
    void aBundledAddressIsNeverRefusedAsTheMockMembersName() {
        // The mock surname is Placeholder-Surname, and the bundled address holds the word placeholder.
        assertThat(DtrFixtures.SURNAME).startsWith("Placeholder");
        for (String address : List.of(SLEEP, SLEEP + "|1.0.0")) {
            RunEnvelope<DtrDetail> result = fetch(address);
            assertThat(result.runId()).as(address).isNotNull();
        }
        assertThat(ledger.records()).hasSize(2);
    }

    @Test
    void anyOtherTypedAddressThatHoldsTheMembersNameIsRefusedAtItsFieldWithoutQuotingIt() {
        List<FieldError> errors = refused(SLEEP, SLEEP + "|9.9");
        assertThat(errors).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("questionnaires[1]");
            assertThat(error.message()).isEqualTo("The questionnaire address holds the member's last name. The ledger keeps it as written and "
                    + "may not hold member data, so leave it out. Nothing was sent.");
            assertThat(error.message()).doesNotContain("example.org").doesNotContain(DtrFixtures.SURNAME);
        });
    }

    @Test
    void aTypedAddressThatHoldsTheMemberIdOrTheDateOfBirthIsRefusedToo() {
        assertThat(refused("https://example.org/Questionnaire/S123456789-intake")).singleElement().satisfies(error -> {
            assertThat(error.field()).isEqualTo("questionnaires[0]");
            assertThat(error.message()).startsWith("The questionnaire address holds this run's member ID.");
        });
        assertThat(refused("https://example.org/Questionnaire/intake-" + DtrFixtures.BIRTH)).singleElement()
                .satisfies(error -> assertThat(error.message()).startsWith("The questionnaire address holds the member's date of birth."));
    }

    @Test
    void anAddressWithNoMemberDataIsSentAsTyped() {
        RunEnvelope<DtrDetail> result = fetch("https://example.org/Questionnaire/home-oxygen|2.0");
        assertThat(result.wire().request().body()).contains("https://example.org/Questionnaire/home-oxygen|2.0");
    }

    @Test
    void theCheckItselfSaysNothingWithoutAMemberAndNothingForTheToolsOwnAddress() {
        assertThat(MemberInText.problemInQuestionnaireAddress(SLEEP, null, false, NOTHING_SENT)).isNull();
        assertThat(MemberInText.problemInQuestionnaireAddress(SLEEP, DtrFixtures.member("S123456789"), true, NOTHING_SENT)).isNull();
        assertThat(MemberInText.problemInQuestionnaireAddress(" ", DtrFixtures.member("S123456789"), false, NOTHING_SENT)).isNull();
        assertThat(MemberInText.problemInQuestionnaireAddress(SLEEP, DtrFixtures.member("S123456789"), false, NOTHING_SENT))
                .startsWith("The questionnaire address holds the member's last name.");
    }
}
