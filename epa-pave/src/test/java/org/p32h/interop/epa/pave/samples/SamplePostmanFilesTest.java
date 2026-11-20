package org.p32h.interop.epa.pave.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.service.config.ConfigFixture;
import org.p32h.interop.epa.pave.service.config.ConfigViews.CollectionUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.EnvironmentUploaded;
import org.p32h.interop.epa.pave.service.config.ConfigViews.RequestSummary;
import org.p32h.interop.epa.pave.service.config.ConfigViews.VariableView;

/**
 * The sample Postman files under {@code samples/postman} go through the same store and parser an
 * upload goes through, and the collection maps every request the way section 10 of the build
 * prompt says the vendor's does. The README tells a first-time tester to upload these two files,
 * so they must keep working.
 */
class SamplePostmanFilesTest {

    static final Path ENVIRONMENT = Path.of("samples/postman/pave-sample-sandbox.postman_environment.json");
    static final Path COLLECTION = Path.of("samples/postman/pave-sample.postman_collection.json");

    /** The thirteen request names of the vendor's sandbox collection and the operation each must map to. */
    static final Map<String, EpaOperation> EXPECTED = expected();

    private static Map<String, EpaOperation> expected() {
        Map<String, EpaOperation> map = new LinkedHashMap<>();
        map.put("order-sign Happy Path", EpaOperation.CRD_ORDER_SIGN);
        map.put("order-sign Error", EpaOperation.CRD_ORDER_SIGN);
        map.put("$questionnaire-package Happy path", EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        map.put("$questionnaire-package Error", EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        map.put("next-question Happy Path", EpaOperation.DTR_NEXT_QUESTION);
        map.put("submit Happy Path", EpaOperation.PAS_SUBMIT);
        map.put("inquiry Happy Path", EpaOperation.PAS_INQUIRE);
        map.put("subscription-get Happy Path", EpaOperation.SUBSCRIPTION_GET);
        map.put("subscription-get Error", EpaOperation.SUBSCRIPTION_GET);
        map.put("subscription-create Happy Path", EpaOperation.SUBSCRIPTION_CREATE);
        map.put("subscription-update Happy Path", EpaOperation.SUBSCRIPTION_UPDATE);
        map.put("subscription-delete Happy Path", EpaOperation.SUBSCRIPTION_DELETE);
        map.put("subscription-notification Happy Path", EpaOperation.SUBSCRIPTION_NOTIFICATION);
        return map;
    }

    @Test
    void theEnvironmentUploadsAsASandboxWithNoSecretWarningAndItsSecretReadFromTheOperatingSystem(@TempDir Path store) throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(store);

        EnvironmentUploaded uploaded = fixture.service.uploadEnvironment(ENVIRONMENT.getFileName().toString(),
                Files.readAllBytes(ENVIRONMENT), EnvironmentKind.SANDBOX, false);

        assertThat(uploaded.environment().name()).isEqualTo("PAVE Sample Sandbox");
        assertThat(uploaded.environment().kind()).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(uploaded.environment().productionSigns()).isEmpty();
        // A secret held as {{env:...}} is not a secret sitting in the file, so the upload warns of nothing.
        assertThat(uploaded.warnings()).isEmpty();
        VariableView secret = variable(uploaded, "clientSecret");
        assertThat(secret.secret()).isTrue();
        assertThat(secret.readsFrom()).contains("PAVE_ONYX_CLIENT_SECRET");
        // A switched-off variable is listed and is not in force, as Postman ignores it.
        assertThat(variable(uploaded, "retiredBaseUrl").inForce()).isFalse();
        assertThat(variable(uploaded, "baseUrl").inForce()).isTrue();
    }

    @Test
    void everyRequestOfTheCollectionIsInAFolderAndMapsToTheOperationSectionTenNames(@TempDir Path store) throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(store);

        CollectionUploaded uploaded = fixture.service.uploadCollection(COLLECTION.getFileName().toString(), Files.readAllBytes(COLLECTION));

        List<RequestSummary> requests = uploaded.collection().requests();
        assertThat(uploaded.collection().schema()).contains("v2.1");
        assertThat(uploaded.warnings()).isEmpty();
        assertThat(requests).hasSize(13);
        Map<String, EpaOperation> mapped = requests.stream()
                .collect(Collectors.toMap(RequestSummary::name, RequestSummary::effectiveOperation, (a, b) -> a, LinkedHashMap::new));
        assertThat(mapped).containsExactlyInAnyOrderEntriesOf(EXPECTED);
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.folderPath()).as("folder of %s", request.name()).isNotEmpty();
            assertThat(request.detectedOperation()).as("detection of %s", request.name()).isEqualTo(EXPECTED.get(request.name()));
            assertThat(request.detectionConflict()).as("conflict on %s", request.name()).isFalse();
        });
    }

    @Test
    void exactlyOneRequestCarriesAPreRequestScriptAndSavedExamplesAreRead(@TempDir Path store) throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(store);

        List<RequestSummary> requests = fixture.service.uploadCollection(COLLECTION.getFileName().toString(), Files.readAllBytes(COLLECTION))
                .collection().requests();

        assertThat(requests).filteredOn(RequestSummary::scripted).extracting(RequestSummary::name)
                .containsExactly("order-sign Happy Path");
        assertThat(requests).filteredOn(r -> r.exampleCount() > 0).extracting(RequestSummary::name)
                .containsExactlyInAnyOrder("order-sign Happy Path", "order-sign Error", "$questionnaire-package Error", "submit Happy Path");
    }

    @Test
    void withBothFilesActiveTheCrdUrlResolvesToThePlaceholderHost(@TempDir Path store) throws Exception {
        ConfigFixture fixture = ConfigFixture.mock(store);
        fixture.service.uploadEnvironment(ENVIRONMENT.getFileName().toString(), Files.readAllBytes(ENVIRONMENT), EnvironmentKind.SANDBOX, false);
        fixture.service.uploadCollection(COLLECTION.getFileName().toString(), Files.readAllBytes(COLLECTION));
        fixture.service.activate("PAVE Sample Sandbox", null);

        var resolved = fixture.service.resolve(EpaOperation.CRD_ORDER_SIGN, null, Map.of(), Map.of(), null);

        assertThat(resolved.url().unresolved()).isEmpty();
        assertThat(resolved.requestName()).isEqualTo("order-sign Happy Path");
        assertThat(resolved.urlShown()).isEqualTo("https://sandbox.onyx.example.invalid/cds-services/placeholder-order-sign-service");
    }

    private static VariableView variable(EnvironmentUploaded uploaded, String name) {
        return uploaded.environment().variables().stream().filter(v -> v.name().equals(name)).findFirst().orElseThrow();
    }
}
