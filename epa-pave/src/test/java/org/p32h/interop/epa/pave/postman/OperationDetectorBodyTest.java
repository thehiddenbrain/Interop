package org.p32h.interop.epa.pave.postman;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.EpaOperation;

/**
 * What a request's body says is read when its name, its url and its folders leave it unmapped
 * (D-307): a vendor names a CDS Hooks service after itself, not after the hook, and its request
 * stood unmapped though its body says which hook it is. Each request here was unmapped by the
 * rules before; the existing detector tests run unchanged and hold that nothing they map moves.
 */
class OperationDetectorBodyTest {

    private static final String HOOK_BODY = "{\"hook\": \"order-sign\", \"hookInstance\": \"d1577c69-dfbe-44ad-ba6d-3e05e953b2ea\"}";

    @Test
    void aCdsHooksBodyThatStatesItsHookMapsAServiceWhoseIdDoesNotSay() {
        String url = "{{base}}/cds-services/epa-crd-service";
        assertThat(OperationDetector.detect("Prior Auth Check", "POST", url, List.of("CRD")).operation()).isEqualTo(EpaOperation.UNMAPPED);

        OperationDetector.Detection detection = OperationDetector.detect("Prior Auth Check", "POST", url, List.of("CRD"), HOOK_BODY);
        assertThat(detection.operation()).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(detection.basis()).isEqualTo("The url addresses a CDS Hooks service whose id does not say which hook it is, and the body "
                + "states the hook order-sign, which reads as " + EpaOperation.CRD_ORDER_SIGN.label() + ".");
        assertThat(OperationDetector.detect("Check", "POST", url, List.of(), "{\"hook\": \"order-select\"}").operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SELECT);
    }

    @Test
    void aHookIsReadFromABodyThatAPlaceholderKeepsFromBeingJson() {
        String body = "{\n  \"hook\" : \"order-sign\",\n  \"context\": {\"count\": {{count}}}\n}";
        assertThat(OperationDetector.detect("Check", "POST", "{{base}}/cds-services/x", List.of(), body).operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SIGN);
    }

    @Test
    void aHookThisToolDoesNotRunLeavesTheRequestUnmapped() {
        assertThat(OperationDetector.detect("Check", "POST", "{{base}}/cds-services/x", List.of(), "{\"hook\": \"patient-view\"}")
                .operation()).isEqualTo(EpaOperation.UNMAPPED);
    }

    @Test
    void aBundleThatCarriesAClaimSaysPasButNotWhichOfTheTwo() {
        String claim = "{\"resourceType\": \"Bundle\", \"entry\": [{\"resource\": {\"resourceType\": \"Claim\"}}]}";
        OperationDetector.Detection detection = OperationDetector.detect("Send", "POST", "{{pasEndpoint}}", List.of(), claim);
        assertThat(detection.operation()).isEqualTo(EpaOperation.UNMAPPED);
        assertThat(detection.basis()).isEqualTo("Neither the name nor the url says which operation it is, and the body is a Bundle that "
                + "carries a Claim, which goes to $submit or $inquire, and nothing says which. Choose one.");
        // Sent to $submit, the url decides as before, and the body agrees.
        assertThat(OperationDetector.detect("Send", "POST", "{{base}}/Claim/$submit", List.of(), claim).operation())
                .isEqualTo(EpaOperation.PAS_SUBMIT);
    }

    @Test
    void aParametersBodyThatNamesACoverageIsTheQuestionnairePackage() {
        String parameters = "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"coverage\"}, {\"name\": \"order\"}]}";
        assertThat(OperationDetector.detect("Fetch forms", "POST", "{{dtrEndpoint}}", List.of()).operation())
                .isEqualTo(EpaOperation.UNMAPPED);
        OperationDetector.Detection detection = OperationDetector.detect("Fetch forms", "POST", "{{dtrEndpoint}}", List.of(), parameters);
        assertThat(detection.operation()).isEqualTo(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE);
        assertThat(OperationDetector.detect("Fetch forms", "POST", "{{dtrEndpoint}}", List.of(),
                "{\"resourceType\": \"Parameters\", \"parameter\": [{\"name\": \"questionnaire\"}]}").operation())
                .isEqualTo(EpaOperation.UNMAPPED);
    }

    @Test
    void theBodyNeverOverridesTheNameTheUrlOrTheFolder() {
        // The name says order-select; the body's hook is not read.
        assertThat(OperationDetector.detect("order-select check", "POST", "{{base}}/cds-services/x", List.of(), HOOK_BODY).operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        // The url says $inquire; a hook body sent there does not move it.
        assertThat(OperationDetector.detect("Send", "POST", "{{base}}/Claim/$inquire", List.of(), HOOK_BODY).operation())
                .isEqualTo(EpaOperation.PAS_INQUIRE);
        // The folder says order-select.
        assertThat(OperationDetector.detect("Check", "POST", "{{base}}/cds-services/x", List.of("order-select"), HOOK_BODY).operation())
                .isEqualTo(EpaOperation.CRD_ORDER_SELECT);
        // A body that is no JSON and holds no hook says nothing.
        assertThat(OperationDetector.detect("Check", "POST", "{{base}}/cds-services/x", List.of(), "grant_type=client_credentials")
                .operation()).isEqualTo(EpaOperation.UNMAPPED);
    }

    @Test
    void theGuardrailsSignalsAreNotReadFromTheBody() {
        assertThat(OperationDetector.signals("Check", "POST", "{{base}}/cds-services/x")).containsExactly(EpaOperation.CRD_ORDER_SIGN,
                EpaOperation.CRD_ORDER_SELECT);
    }
}
