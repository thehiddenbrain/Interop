package org.p32h.interop.epa.pave.service.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.run.BeforeSending;

/**
 * The line before sending (D-321): where the collection request a step is sent from carries a
 * diagnosis or a servicing provider and the case names none, the step says so before anything is
 * sent. Read from the request the step would follow, by presence only. Each sample is written for
 * the test with placeholders.
 */
class BeforeSendingTest {

    static final String HOOK = "{\"hook\": \"order-sign\", \"context\": {\"draftOrders\": {\"resourceType\": \"Bundle\", \"entry\": "
            + "[{\"resource\": {\"resourceType\": \"ServiceRequest\", \"reasonCode\": [{\"coding\": [{\"code\": \"SAMPLE-DX\"}]}], "
            + "\"performer\": [{\"reference\": \"Practitioner/prac-001\"}]}}]}}}";
    static final String HOOK_DIAGNOSIS_ONLY = HOOK.replace(", \"performer\": [{\"reference\": \"Practitioner/prac-001\"}]", "");
    static final String PACKAGE = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"order\", \"resource\": {\"resourceType\": "
            + "\"ServiceRequest\", \"performer\": [{\"reference\": \"Practitioner/prac-001\"}]}}]}";
    static final String SUBMIT = "{\"resourceType\": \"Bundle\", \"entry\": [{\"resource\": {\"resourceType\": \"Claim\", \"diagnosis\": "
            + "[{\"sequence\": 1}], \"careTeam\": [{\"provider\": {\"reference\": \"Practitioner/prac-001\"}}]}}]}";

    @TempDir
    Path directory;

    private ConfigFixture fixture(ConfigFixture.Item... items) {
        ConfigFixture fixture = ConfigFixture.mock(directory);
        fixture.activeEnvironmentOf("Vendor UAT", EnvironmentKind.UAT, "baseUrl", "https://vendor.example.invalid");
        if (items.length > 0) {
            fixture.collectionOf("Vendor", ConfigFixture.items(items));
        }
        return fixture;
    }

    private static ConfigFixture.Item hook(String name, String body) {
        ConfigFixture.Item item = ConfigFixture.item(name, "POST", "https://vendor.example.invalid/epa/cds-services/order-sign");
        return body == null ? item : item.body(body);
    }

    private static final List<CodeInput> DIAGNOSIS = List.of(CodeInput.of("G47.33"));
    private static final ProviderDetail SERVICING = ProviderDetail.builder().npi("9999999995").lastName("Servicing").build();

    private static BeforeSending.Ask ask(String operation, List<CodeInput> diagnosis, ProviderDetail servicing) {
        return new BeforeSending.Ask(operation, null, Map.of(), diagnosis, servicing);
    }

    @Test
    void eachStepSaysWhatItsRequestCarriesAndTheCaseNamesNot() {
        ConfigFixture fixture = fixture(hook("CRD - Coverage check", HOOK),
                ConfigFixture.item("DTR - Fetch Questionnaire", "POST", "https://vendor.example.invalid/r4/Questionnaire/$questionnaire-package")
                        .body(PACKAGE),
                ConfigFixture.item("PAS - Claim Submit", "POST", "https://vendor.example.invalid/r4/Claim/$submit").body(SUBMIT));

        // The sample carries both and the case neither.
        assertThat(BeforeSending.ask(fixture.gateway, ask("CRD_ORDER_SIGN", List.of(), null)).line()).isEqualTo(BeforeSending.BOTH);
        assertThat(BeforeSending.ask(fixture.gateway, ask("PAS_SUBMIT", List.of(), null)).line()).isEqualTo(BeforeSending.BOTH);
        // One of the two.
        assertThat(BeforeSending.ask(fixture.gateway, ask("CRD_ORDER_SIGN", DIAGNOSIS, null)).line()).isEqualTo(BeforeSending.SERVICING);
        assertThat(BeforeSending.ask(fixture.gateway, ask("PAS_SUBMIT", List.of(), SERVICING)).line()).isEqualTo(BeforeSending.DIAGNOSIS);
        // The questionnaire request's sample names a performer and no diagnosis: the diagnosis is not asked for.
        BeforeSending.Said dtr = BeforeSending.ask(fixture.gateway, ask("DTR_QUESTIONNAIRE_PACKAGE", List.of(), null));
        assertThat(dtr.line()).isEqualTo(BeforeSending.SERVICING);
        assertThat(dtr.sampleDiagnosis()).isFalse();
        assertThat(dtr.sampleServicing()).isTrue();
        // The case names them: nothing to say.
        for (String step : List.of("CRD_ORDER_SIGN", "DTR_QUESTIONNAIRE_PACKAGE", "PAS_SUBMIT")) {
            assertThat(BeforeSending.ask(fixture.gateway, ask(step, DIAGNOSIS, SERVICING)).line()).isNull();
        }
        // A blank code and an empty provider name nothing.
        assertThat(BeforeSending.ask(fixture.gateway, ask("CRD_ORDER_SIGN", List.of(CodeInput.of(" ")), ProviderDetail.builder().build()))
                .line()).isEqualTo(BeforeSending.BOTH);
    }

    @Test
    void theWordsAreTheDecisionsWordForWord() {
        assertThat(BeforeSending.BOTH).isEqualTo("The collection's request for this step carries a diagnosis and a servicing provider. "
                + "This case names neither. Add Diagnosis codes on the Service card and a Servicing provider on the Provider card, or send "
                + "as it is.");
    }

    @Test
    void theRequestChosenForTheStepIsTheOneRead() {
        ConfigFixture fixture = fixture(hook("CRD - Coverage check", HOOK_DIAGNOSIS_ONLY), hook("CRD - Other service", HOOK));
        RequestRef other = fixture.ref(0, "CRD - Other service");

        assertThat(BeforeSending.ask(fixture.gateway, ask("CRD_ORDER_SIGN", List.of(), null)).line()).isEqualTo(BeforeSending.DIAGNOSIS);
        assertThat(BeforeSending.ask(fixture.gateway, new BeforeSending.Ask("CRD_ORDER_SIGN", other, Map.of(), List.of(), null)).line())
                .isEqualTo(BeforeSending.BOTH);
    }

    @Test
    void nothingIsSaidWithNoCollectionNoBodyABodyThatIsNotJsonASampleOfAnErrorOrAStaleChoice() {
        assertThat(BeforeSending.ask(fixture().gateway, ask("CRD_ORDER_SIGN", List.of(), null)))
                .isEqualTo(new BeforeSending.Said(null, false, false));
        assertThat(BeforeSending.ask(ConfigFixture.mock(directory.resolve("bare")).gateway, ask("PAS_SUBMIT", List.of(), null)).line())
                .isNull();

        ConfigFixture noBody = fixture(hook("CRD - Coverage check", null));
        assertThat(BeforeSending.ask(noBody.gateway, ask("CRD_ORDER_SIGN", List.of(), null)).line()).isNull();

        ConfigFixture notJson = ConfigFixture.mock(directory.resolve("notjson"));
        notJson.collectionOf("Vendor", ConfigFixture.items(hook("CRD - Coverage check", "this is not { json")));
        assertThat(BeforeSending.ask(notJson.gateway, ask("CRD_ORDER_SIGN", List.of(), null)).line()).isNull();

        // A sample of an error is not followed (D-309), even when it is chosen.
        ConfigFixture error = ConfigFixture.mock(directory.resolve("error"));
        error.collectionOf("Vendor", ConfigFixture.items(hook("CRD - error no patient", HOOK)));
        RequestRef chosen = error.ref(0, "CRD - error no patient");
        assertThat(BeforeSending.ask(error.gateway, new BeforeSending.Ask("CRD_ORDER_SIGN", chosen, Map.of(), List.of(), null)).line())
                .isNull();

        ConfigFixture stale = fixture(hook("CRD - Coverage check", HOOK));
        assertThat(BeforeSending.ask(stale.gateway, new BeforeSending.Ask("CRD_ORDER_SIGN", new RequestRef("gone", "gone"), Map.of(),
                List.of(), null)).line()).isNull();
    }

    @Test
    void anOperationThatIsNotAStepIsRefusedAtItsField() {
        ConfigFixture fixture = fixture();
        for (String operation : java.util.Arrays.asList("PAS_INQUIRE", "member lookup", "", null)) {
            assertThatThrownBy(() -> BeforeSending.ask(fixture.gateway, ask(operation, List.of(), null)))
                    .isInstanceOfSatisfying(ValidationException.class,
                            refused -> assertThat(refused.fieldErrors().get(0).field()).isEqualTo("operation"));
        }
    }
}
