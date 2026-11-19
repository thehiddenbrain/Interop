package org.p32h.interop.epa.pave.client.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.testsupport.TestExchanges;

/**
 * The recognition rules kept in the conventions file. Each is an assumption about the vendor, so
 * each is pinned here in both directions: what it recognises, and what it deliberately does not.
 */
class OnyxPasConventionsTest {

    private static VendorExchange response(int status, String body) {
        return TestExchanges.of(EpaOperation.PAS_SUBMIT, status, body, true);
    }

    private static String notFound(String expression) {
        return "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\",\"expression\":[\""
                + expression + "\"]}]}";
    }

    private static String claimResponseError(String system, String code) {
        return "{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"ClaimResponse\",\"outcome\":\"error\",\"error\":["
                + "{\"code\":{\"coding\":[{" + (system == null ? "" : "\"system\":\"" + system + "\",") + "\"code\":\"" + code + "\"}]}}]}}]}";
    }

    @Test
    void theReviewActionIsRecognisedByItsUrlAndItsPartsByTheNamesOfBothVersionsOfTheGuide() {
        assertThat(OnyxPasConventions.reviewAction(OnyxPasConventions.REVIEW_ACTION)).isTrue();
        assertThat(OnyxPasConventions.reviewAction("https://vendor.example.invalid/fhir/StructureDefinition/extension-reviewAction")).isTrue();
        assertThat(OnyxPasConventions.reviewAction(OnyxPasConventions.REVIEW_ACTION_CODE)).isFalse();
        assertThat(OnyxPasConventions.reviewAction("reviewAction")).isFalse();
        assertThat(OnyxPasConventions.reviewAction(null)).isFalse();

        assertThat(OnyxPasConventions.reviewPart(OnyxPasConventions.REVIEW_ACTION_CODE)).isEqualTo(OnyxPasConventions.ReviewPart.CODE);
        assertThat(OnyxPasConventions.reviewPart("code")).isEqualTo(OnyxPasConventions.ReviewPart.CODE);
        assertThat(OnyxPasConventions.reviewPart("number")).isEqualTo(OnyxPasConventions.ReviewPart.NUMBER);
        assertThat(OnyxPasConventions.reviewPart("reasonCode")).isEqualTo(OnyxPasConventions.ReviewPart.REASON);
        assertThat(OnyxPasConventions.reviewPart("secondSurgicalOpinionNeeded")).isEqualTo(OnyxPasConventions.ReviewPart.SECOND_SURGICAL_OPINION);
        // Anything else is kept and shown. It is never taken for the decision.
        assertThat(OnyxPasConventions.reviewPart("decision")).isEqualTo(OnyxPasConventions.ReviewPart.OTHER);
        assertThat(OnyxPasConventions.reviewPart("Code")).isEqualTo(OnyxPasConventions.ReviewPart.OTHER);
        assertThat(OnyxPasConventions.reviewPart(null)).isEqualTo(OnyxPasConventions.ReviewPart.OTHER);
    }

    @Test
    void theExtensionsOfAnErrorAreRecognisedByUrl() {
        assertThat(OnyxPasConventions.errorPart(OnyxPasConventions.ERROR_ELEMENT)).isEqualTo(OnyxPasConventions.ErrorPart.ELEMENT);
        assertThat(OnyxPasConventions.errorPart(OnyxPasConventions.ERROR_PATH)).isEqualTo(OnyxPasConventions.ErrorPart.PATH);
        assertThat(OnyxPasConventions.errorPart(OnyxPasConventions.ERROR_FOLLOWUP_ACTION)).isEqualTo(OnyxPasConventions.ErrorPart.FOLLOWUP_ACTION);
        assertThat(OnyxPasConventions.errorPart("https://example.org/other")).isEqualTo(OnyxPasConventions.ErrorPart.OTHER);
        assertThat(OnyxPasConventions.errorPart(null)).isEqualTo(OnyxPasConventions.ErrorPart.OTHER);
    }

    @Test
    void aRequestForDocumentationIsClosedOnlyWhenItsStatusIsSeenAndSaysSo() {
        assertThat(OnyxPasConventions.documentationRequestClosed("completed")).isTrue();
        assertThat(OnyxPasConventions.documentationRequestClosed(" Revoked ")).isTrue();
        assertThat(OnyxPasConventions.documentationRequestClosed("entered-in-error")).isTrue();
        assertThat(OnyxPasConventions.documentationRequestClosed("active")).isFalse();
        assertThat(OnyxPasConventions.documentationRequestClosed("on-hold")).isFalse();
        assertThat(OnyxPasConventions.documentationRequestClosed("a status nobody defined")).isFalse();
        // A status that cannot be seen is not a closed one.
        assertThat(OnyxPasConventions.documentationRequestClosed(null)).isFalse();
    }

    @Test
    void anUnknownMemberOrProviderIsRecognisedFromAnOperationOutcomeThatNamesTheElement() {
        assertThat(OnyxPasConventions.signals(response(404, notFound("Claim.patient")))).containsExactly(VendorSignal.MEMBER_NOT_FOUND);
        assertThat(OnyxPasConventions.signals(response(404, notFound("Coverage.subscriberId")))).containsExactly(VendorSignal.MEMBER_NOT_FOUND);
        assertThat(OnyxPasConventions.signals(response(404, notFound("Practitioner.identifier")))).containsExactly(VendorSignal.PROVIDER_NOT_FOUND);
        assertThat(OnyxPasConventions.signals(response(404, notFound("Claim.provider")))).containsExactly(VendorSignal.PROVIDER_NOT_FOUND);
        // The same outcome carried inside a bundle is read too.
        assertThat(OnyxPasConventions.signals(response(200, "{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":" + notFound("Patient.identifier")
                + "}]}"))).containsExactly(VendorSignal.MEMBER_NOT_FOUND);
    }

    @Test
    void anIssueThatNamesNeitherOrBothOrIsNotNotFoundIsLeftUnclassified() {
        assertThat(OnyxPasConventions.signals(response(404, notFound("Bundle.entry[0]")))).isEmpty();
        assertThat(OnyxPasConventions.signals(response(404, notFound("Claim.identifier")))).isEmpty();
        assertThat(OnyxPasConventions.signals(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"not-found\","
                + "\"expression\":[\"Claim.patient\",\"Claim.provider\"]}]}"))).isEmpty();
        assertThat(OnyxPasConventions.signals(response(400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"invalid\","
                + "\"expression\":[\"Claim.patient\"]}]}"))).isEmpty();
        // Wording is never read: a diagnostic that says the member was not found decides nothing.
        assertThat(OnyxPasConventions.signals(response(404, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"not-found\","
                + "\"diagnostics\":\"The member was not found\"}]}"))).isEmpty();
        assertThat(OnyxPasConventions.signals(response(200, "{\"cards\":[]}"))).isEmpty();
        assertThat(OnyxPasConventions.signals(TestExchanges.timeout(EpaOperation.PAS_SUBMIT))).isEmpty();
        assertThat(OnyxPasConventions.signals(null)).isEmpty();
    }

    @Test
    void anUnknownMemberOrProviderIsAlsoRecognisedFromTheRejectReasonCodeOfAClaimResponseError() {
        String x12 = "https://codesystem.x12.org/005010/901";
        for (String code : List.of("67", "75", "77")) {
            assertThat(OnyxPasConventions.signals(response(200, claimResponseError(x12, code)))).as(code)
                    .containsExactly(VendorSignal.MEMBER_NOT_FOUND);
        }
        assertThat(OnyxPasConventions.signals(response(200, claimResponseError(x12, "51")))).containsExactly(VendorSignal.PROVIDER_NOT_FOUND);
        // Another reject reason is an error like any other, and a bare number under no system or
        // another system is not taken for one of these codes.
        assertThat(OnyxPasConventions.signals(response(200, claimResponseError(x12, "33")))).isEmpty();
        assertThat(OnyxPasConventions.signals(response(200, claimResponseError(null, "75")))).isEmpty();
        assertThat(OnyxPasConventions.signals(response(200, claimResponseError("https://example.org/p32h-placeholder/errors", "75")))).isEmpty();
    }

    @Test
    void anInquiryAboutARequestThePayerDoesNotKnowIsRecognisedOnlyWhenTheIssueNamesTheClaimAlone() {
        assertThat(OnyxPasConventions.authorizationNotFound(response(404, notFound("Claim.identifier")))).isTrue();
        assertThat(OnyxPasConventions.authorizationNotFound(response(404, notFound("ClaimResponse")))).isTrue();
        // An issue that names the member is a finding about the member, not about the request.
        assertThat(OnyxPasConventions.authorizationNotFound(response(404, notFound("Claim.patient")))).isFalse();
        assertThat(OnyxPasConventions.authorizationNotFound(response(404, notFound("Bundle.entry[0]")))).isFalse();
        assertThat(OnyxPasConventions.authorizationNotFound(response(400, "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"invalid\","
                + "\"expression\":[\"Claim.identifier\"]}]}"))).isFalse();
        assertThat(OnyxPasConventions.authorizationNotFound(response(404, "<html>Not Found</html>"))).isFalse();
        assertThat(OnyxPasConventions.authorizationNotFound(null)).isFalse();
    }

    @Test
    void anUnknownSubscriptionIsToldApartFromAWrongUrl() {
        VendorExchange coded = TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 404,
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"severity\":\"error\",\"code\":\"not-found\"}]}", true);
        assertThat(OnyxPasConventions.subscriptionNotFound(coded)).isTrue();
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 404,
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"deleted\"}]}", true))).isTrue();
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 410, null, true))).isTrue();
        // A bare 404 is what a wrong url answers too, so it is not read as an unknown id.
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 404, null, true))).isFalse();
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 404, "<html>Not Found</html>", true)))
                .isFalse();
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 400,
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"invalid\"}]}", true))).isFalse();
        // A success that happens to carry such an outcome found the subscription.
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.of(EpaOperation.SUBSCRIPTION_GET, 200,
                "{\"resourceType\":\"OperationOutcome\",\"issue\":[{\"code\":\"not-found\"}]}", true))).isFalse();
        assertThat(OnyxPasConventions.subscriptionNotFound(TestExchanges.timeout(EpaOperation.SUBSCRIPTION_GET))).isFalse();
    }

    @Test
    void theIdOfACreatedSubscriptionIsReadFromTheLocationHeaderWhenTheBodyDoesNotCarryIt() {
        VendorExchange created = Exchanges.fromResponse(TestExchanges.of(EpaOperation.SUBSCRIPTION_CREATE, 201, null, false).request(), 201,
                List.of(new WireHeader("Content-Type", "application/fhir+json"),
                        new WireHeader("location", "https://onyx.example.invalid/fhir/Subscription/subscription-7/_history/1")), null, 5L, false);
        assertThat(OnyxPasConventions.createdSubscriptionId(created)).isEqualTo("subscription-7");
        assertThat(OnyxPasConventions.createdSubscriptionId(TestExchanges.of(EpaOperation.SUBSCRIPTION_CREATE, 201, null, false))).isNull();
        assertThat(OnyxPasConventions.createdSubscriptionId(null)).isNull();
    }

    @Test
    void theIdAReferenceNamesIsReadFromARelativeOrAbsoluteReferenceOfTheRightTypeOnly() {
        assertThat(OnyxPasConventions.idAfter("Subscription/sub-1", "Subscription")).isEqualTo("sub-1");
        assertThat(OnyxPasConventions.idAfter("https://onyx.example.invalid/fhir/Subscription/sub-1", "Subscription")).isEqualTo("sub-1");
        assertThat(OnyxPasConventions.idAfter("https://onyx.example.invalid/fhir/Subscription/sub-1/_history/3", "Subscription")).isEqualTo("sub-1");
        assertThat(OnyxPasConventions.idAfter("Subscription/sub-1/$status?x=1", "Subscription")).isEqualTo("sub-1");
        assertThat(OnyxPasConventions.idAfter("ClaimResponse/cr-1", "ClaimResponse")).isEqualTo("cr-1");
        assertThat(OnyxPasConventions.idAfter("ClaimResponse/cr-1", "Subscription")).isNull();
        assertThat(OnyxPasConventions.idAfter("Subscription/", "Subscription")).isNull();
        assertThat(OnyxPasConventions.idAfter("sub-1", "Subscription")).isNull();
        assertThat(OnyxPasConventions.idAfter(" ", "Subscription")).isNull();
        assertThat(OnyxPasConventions.idAfter(null, "Subscription")).isNull();
    }

    @Test
    void aSubscriptionStatusIsRecognisedInItsR4FormAndInItsLaterForm() {
        assertThat(OnyxPasConventions.subscriptionStatus(JsonSafe.parse("{\"resourceType\":\"SubscriptionStatus\"}").node())).isTrue();
        assertThat(OnyxPasConventions.subscriptionStatus(JsonSafe.parse("{\"resourceType\":\"Parameters\",\"meta\":{\"profile\":["
                + "\"http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/backport-subscription-status-r4\"]}}").node())).isTrue();
        assertThat(OnyxPasConventions.subscriptionStatus(JsonSafe.parse("{\"resourceType\":\"Parameters\",\"parameter\":["
                + "{\"name\":\"subscription\",\"valueReference\":{\"reference\":\"Subscription/s\"}}]}").node())).isTrue();
        // Any other Parameters resource, and any other resource, is not a status.
        assertThat(OnyxPasConventions.subscriptionStatus(JsonSafe.parse("{\"resourceType\":\"Parameters\",\"parameter\":["
                + "{\"name\":\"return\",\"valueString\":\"x\"}]}").node())).isFalse();
        assertThat(OnyxPasConventions.subscriptionStatus(JsonSafe.parse("{\"resourceType\":\"ClaimResponse\"}").node())).isFalse();
        assertThat(OnyxPasConventions.subscriptionStatus(JsonSafe.parse("[1]").node())).isFalse();
    }

    @Test
    void theIdentifiersTheToolMintsForARequestAreDerivedFromTheClaimSoTheyNeverCollide() {
        assertThat(OnyxPasConventions.attachmentControlNumber("claim-1", 2)).isEqualTo("claim-1-A2");
        assertThat(OnyxPasConventions.documentReferenceId(1)).isEqualTo("document-reference-1");
        assertThat(OnyxPasConventions.questionnaireResponseId(3)).isEqualTo("questionnaire-response-3");
    }

    @Test
    void thePlaceholderTopicCannotBeMistakenForARealOne() {
        assertThat(OnyxPasConventions.PLACEHOLDER_TOPIC).contains("example.org").contains("placeholder");
    }

    @Test
    void theClaimOfARequestBodyIsReadWithItsItemsAndABodyThatHoldsNoneIsUnknown() {
        SentClaim sent = SentClaim.fromBody(PasScenarioFilesTest.request("claim-test-1", "95810", "E0601"));
        assertThat(sent.claimId()).isEqualTo("claim-test-1");
        assertThat(sent.items()).containsExactly(new SentClaim.Item(1, "95810"), new SentClaim.Item(2, "E0601"));
        assertThat(sent.codes()).containsExactly("95810", "E0601");
        assertThat(sent.items().get(0).label()).isEqualTo("95810");
        assertThat(new SentClaim.Item(3, null).label()).isEqualTo("item 3");
        assertThat(new SentClaim.Item(null, " ").label()).isEqualTo("unidentified-item");
        // A bare Claim is read as well as one in a bundle.
        assertThat(SentClaim.fromBody("{\"resourceType\":\"Claim\",\"identifier\":[{\"system\":\"s\"},{\"value\":\"claim-2\"}]}").claimId())
                .isEqualTo("claim-2");
        for (String body : new String[] {null, " ", "{\"resourceType\": ", "[1,2]", "{\"resourceType\":\"Bundle\",\"entry\":[]}",
                "{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"Patient\"}}]}"}) {
            assertThat(SentClaim.fromBody(body)).as("[" + body + "]").isEqualTo(SentClaim.unknown());
        }
    }
}
