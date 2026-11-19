package org.p32h.interop.epa.pave.client.pas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.MockSelection;
import org.p32h.interop.epa.pave.client.support.MockExchanges;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.service.config.TestPreparedCalls;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import org.p32h.interop.epa.pave.service.scenario.Scenario;
import org.p32h.interop.epa.pave.service.scenario.ScenarioRepository;
import tools.jackson.databind.JsonNode;

/**
 * The bundled PAS scenario files as files, and the two mock clients that answer from them: what
 * loads, which sentinel selects which, which step answers which call, and what the mock does to a
 * canned answer so that it is an answer to the claim that was sent.
 */
class PasScenarioFilesTest {

    private static final AppProperties APP = AppProperties.of(Map.of("mock-latency-enabled", "false"));
    private final ScenarioRepository scenarios = new ScenarioRepository(APP);
    private final MockOnyxPasClient pas = new MockOnyxPasClient(scenarios, new MockExchanges(APP));
    private final MockSubscriptionClient subscriptions = new MockSubscriptionClient(scenarios, new MockExchanges(APP));

    /** A request bundle carrying a claim with the given identifier and one item for each code. */
    static String request(String claimId, String... procedureCodes) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < procedureCodes.length; i++) {
            items.append(i == 0 ? "" : ",").append("{\"sequence\":").append(i + 1)
                    .append(",\"productOrService\":{\"coding\":[{\"code\":\"").append(procedureCodes[i]).append("\"}]}}");
        }
        String identifier = claimId == null ? "" : "\"identifier\":[{\"value\":\"" + claimId + "\"}],";
        return "{\"resourceType\":\"Bundle\",\"type\":\"collection\",\"entry\":[{\"resource\":{\"resourceType\":\"Claim\",\"id\":\"claim-1\","
                + identifier + "\"use\":\"preauthorization\",\"item\":[" + items + "]}}]}";
    }

    private VendorExchange submit(String scenarioId, String memberId, String body) {
        return pas.submit(TestPreparedCalls.unconfigured(EpaOperation.PAS_SUBMIT), ResolvedText.plain(body),
                MockSelection.of(scenarioId, memberId, "1234567893"));
    }

    private VendorExchange inquire(String scenarioId, String memberId, String body) {
        return pas.inquire(TestPreparedCalls.unconfigured(EpaOperation.PAS_INQUIRE), ResolvedText.plain(body),
                MockSelection.of(scenarioId, memberId, "1234567893"));
    }

    private static JsonNode claimResponse(VendorExchange exchange) {
        return exchange.json().get("entry").get(0).get("resource");
    }

    @Test
    void theTwentyScenariosLoadInOrderWithTheirSentinels() {
        ScenarioRepository.Loaded loaded = scenarios.load(MockOnyxPasClient.SCENARIOS);
        assertThat(loaded.problems()).isEmpty();
        Map<Integer, String> sentinels = new LinkedHashMap<>();
        loaded.scenarios().forEach(s -> sentinels.put(s.number(), s.sentinelMemberId()));
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(1, "P32H-PAS-APPROVED");
        expected.put(2, "P32H-PAS-PENDED");
        expected.put(3, "P32H-PAS-DENIED");
        expected.put(4, "P32H-PAS-PARTIAL");
        expected.put(5, "P32H-PAS-INQUIRE");
        expected.put(6, "P32H-PAS-NOTIFY");
        expected.put(7, "P32H-PAS-ATTACH-REQ");
        expected.put(8, "P32H-PAS-ATTACH-OK");
        expected.put(9, "P32H-PAS-BAD400");
        // A subscription call and a notification carry no member, so these have no sentinel member.
        expected.put(10, null);
        expected.put(11, null);
        expected.put(12, null);
        expected.put(13, "P32H-PAS-DOWN");
        expected.put(14, "P32H-PAS-NO-DECISION");
        expected.put(15, "P32H-PAS-NOMEM");
        expected.put(16, "P32H-PAS-TIMEOUT");
        expected.put(17, "P32H-PAS-ERR500");
        expected.put(18, "P32H-PAS-UNKNOWN-CODE");
        expected.put(19, null);
        expected.put(20, null);
        assertThat(sentinels).containsExactlyEntriesOf(expected);
    }

    @Test
    void everyScenarioIsCompleteSaysItIsSyntheticAndMakesAClaimForEachCallItAnswers() {
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            assertThat(scenario.id()).as("id").startsWith("pas-");
            assertThat(scenario.label()).as(scenario.id()).isNotBlank();
            assertThat(scenario.description()).as(scenario.id()).isNotBlank();
            assertThat(scenario.basis()).as(scenario.id()).startsWith("Synthetic.");
            if (scenario.main() != null) {
                assertThat(scenario.expect()).as(scenario.id() + " claims an outcome for a submission").isNotNull();
                assertThat(scenario.sentinelMemberId()).as(scenario.id() + " can be selected by a member").startsWith("P32H-PAS-");
            }
            scenario.steps().forEach((name, step) ->
                    assertThat(step.expect()).as(scenario.id() + " claims an outcome for the step " + name).isNotNull());
        }
    }

    @Test
    void noTwoScenariosShareASentinelAndNoneLooksLikeARealMemberId() {
        Set<String> seen = new HashSet<>();
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.sentinelMemberId() != null) {
                assertThat(seen.add(scenario.sentinelMemberId())).as(scenario.sentinelMemberId() + " is used once").isTrue();
                assertThat(scenario.sentinelMemberId()).matches("P32H-PAS-[A-Z0-9-]+");
            }
            assertThat(scenario.sentinelNpi()).as(scenario.id()).isNull();
        }
    }

    @Test
    void theScenariosThatModelUndefinedVendorBehaviourSayWhatTheyAssume() {
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (List.of(7, 8, 10, 15, 19).contains(scenario.number())) {
                assertThat(scenario.assumption()).as(scenario.id()).isNotBlank();
            }
        }
    }

    @Test
    void theScenariosTheSpecificationRequiresAreAllThere() {
        assertThat(scenarios.list(MockOnyxPasClient.SCENARIOS)).extracting(Scenario::label).contains("Approved", "Pended", "Denied",
                "Partial approval", "Submitted, then inquired", "Submitted, then notified by subscription", "Attachment required",
                "Attachment accepted", "Invalid bundle rejected with an OperationOutcome", "A subscription created and deleted",
                "A notification correlating to a known run", "A notification matching nothing", "Transport failure",
                "A ClaimResponse that carries no decision");
    }

    @Test
    void aSentinelMemberSelectsItsScenarioForASubmissionAndTheDefaultIsSaidToBeOne() {
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.sentinelMemberId() == null) {
                continue;
            }
            VendorExchange exchange = submit(null, scenario.sentinelMemberId(), request("claim-test-1", "95810"));
            assertThat(exchange.scenarioId()).as(scenario.sentinelMemberId()).isEqualTo(scenario.id());
            assertThat(exchange.scenarioNote()).contains("sentinel member ID " + scenario.sentinelMemberId());
            assertThat(exchange.request().sent()).isFalse();
        }
        VendorExchange ordinary = submit(null, "S123456789", request("claim-test-1", "95810"));
        assertThat(ordinary.scenarioId()).isEqualTo("pas-01-approved");
        assertThat(ordinary.scenarioNote()).contains("the default");
    }

    @Test
    void anInquiryIsAnsweredFromItsOwnStepAndNeverWithTheSubmissionsResponse() {
        String body = request("claim-test-1", "95810");
        VendorExchange submitted = submit(null, "P32H-PAS-INQUIRE", body);
        VendorExchange inquired = inquire(null, "P32H-PAS-INQUIRE", body);
        assertThat(submitted.responseBody()).contains("\"A4\"").doesNotContain("\"A1\"");
        assertThat(inquired.responseBody()).contains("\"A1\"").doesNotContain("\"A4\"");
        assertThat(inquired.request().note()).contains("step \"inquire\"");
        assertThat(inquired.request().method()).isEqualTo("POST");

        // A scenario that defines no inquiry does not answer one.
        assertThatThrownBy(() -> inquire(null, "P32H-PAS-BAD400", body)).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fieldErrors()).singleElement().satisfies(error -> {
                    assertThat(error.field()).isEqualTo("scenarioId");
                    assertThat(error.message()).contains("pas-09-invalid-bundle").contains("\"inquire\"");
                }));
        // And a scenario that describes only subscription calls does not answer a submission.
        assertThatThrownBy(() -> submit("pas-10-subscription-lifecycle", null, body)).isInstanceOf(ValidationException.class);
    }

    @Test
    void theAnswerNamesTheClaimThatWasSentSoEachSubmissionGetsItsOwnClaimResponse() {
        VendorExchange first = submit("pas-02-pended", null, request("claim-test-1", "95810"));
        VendorExchange second = submit("pas-02-pended", null, request("claim-test-2", "95810"));
        assertThat(claimResponse(first).get("id").asString()).isEqualTo("claimresponse-claim-test-1");
        assertThat(claimResponse(second).get("id").asString()).isEqualTo("claimresponse-claim-test-2");
        assertThat(claimResponse(first).at("/request/identifier/value").asString()).isEqualTo("claim-test-1");
        assertThat(first.json().get("entry").get(0).get("fullUrl").asString()).endsWith("/ClaimResponse/claimresponse-claim-test-1");
        assertThat(first.responseBody()).doesNotContain(MockOnyxPasClient.CLAIM_ID_TOKEN);
        // The inquiry about the same claim names the same ClaimResponse.
        VendorExchange inquired = inquire("pas-02-pended", null, request("claim-test-1", "95810"));
        assertThat(claimResponse(inquired).get("id").asString()).isEqualTo("claimresponse-claim-test-1");
        // Which scenario answered is still stated, and the note does not claim anything was repeated.
        assertThat(first.scenarioId()).isEqualTo("pas-02-pended");
        assertThat(first.scenarioNote()).isEqualTo("chosen in the scenario selector");
    }

    @Test
    void aClaimIdentifierIsPutIntoTheAnswerAsDataAndCannotBreakIt() {
        // Quotes and a backslash in the identifier of a tester's own body must not unbalance the JSON.
        String awkward = request("claim-\\\"quoted\\\"-\\\\", "95810");
        VendorExchange exchange = submit("pas-01-approved", null, awkward);
        assertThat(exchange.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(claimResponse(exchange).at("/request/identifier/value").asString()).isEqualTo("claim-\"quoted\"-\\");
    }

    @Test
    void aRequestWithNoClaimIdentifierIsStillAnsweredAndTheAnswerSaysNoClaimWasNamed() {
        VendorExchange noIdentifier = submit("pas-01-approved", null, request(null, "95810"));
        assertThat(claimResponse(noIdentifier).at("/request/identifier/value").asString()).isEqualTo("unidentified-claim");
        VendorExchange notJson = submit("pas-01-approved", null, "{\"resourceType\": ");
        assertThat(claimResponse(notJson).get("id").asString()).isEqualTo("claimresponse-unidentified-claim");
    }

    @Test
    void theMockAnswersEveryItemItIsSentAndSaysThatItRepeatedTheAnswer() {
        VendorExchange exchange = submit("pas-01-approved", null, request("claim-test-1", "95810", "95811", "E0601"));
        JsonNode items = claimResponse(exchange).get("item");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).get("itemSequence").asInt()).isEqualTo(1);
        assertThat(items.get(1).get("itemSequence").asInt()).isEqualTo(2);
        assertThat(items.get(2).get("itemSequence").asInt()).isEqualTo(3);
        assertThat(items.get(2).toString()).contains("\"A1\"");
        assertThat(exchange.scenarioNote()).isEqualTo("chosen in the scenario selector. The scenario file answers one item, and that "
                + "answer is repeated here for each of the 3 items sent");

        // One item is answered from the file exactly as written.
        VendorExchange single = submit("pas-01-approved", null, request("claim-test-1", "95810"));
        assertThat(claimResponse(single).get("item")).hasSize(1);
        assertThat(single.scenarioNote()).isEqualTo("chosen in the scenario selector");
    }

    @Test
    void aFileThatAnswersSeveralItemsOrNoneIsLeftAsWritten() {
        // The partial approval holds two items with different decisions. That difference is its point.
        VendorExchange partial = submit("pas-04-partial", null, request("claim-test-1", "95810", "95811", "E0601"));
        assertThat(claimResponse(partial).get("item")).hasSize(2);
        assertThat(partial.scenarioNote()).doesNotContain("repeated");
        // The pended scenario states its decision for the response as a whole and lists no item.
        VendorExchange pended = submit("pas-02-pended", null, request("claim-test-1", "95810", "95811"));
        assertThat(claimResponse(pended).has("item")).isFalse();
        assertThat(pended.scenarioNote()).doesNotContain("repeated");
    }

    @Test
    void theVendorSignalsAreReadFromAMockAnswerAsTheyAreOnLive() {
        assertThat(submit(null, "P32H-PAS-NOMEM", request("claim-test-1", "95810")).signals()).containsExactly(VendorSignal.MEMBER_NOT_FOUND);
        assertThat(submit(null, "P32H-PAS-APPROVED", request("claim-test-1", "95810")).signals()).isEmpty();
        assertThat(submit(null, "P32H-PAS-BAD400", request("claim-test-1", "95810")).signals()).isEmpty();
    }

    @Test
    void theFaultScenariosAreClassifiedAsTheSameStatusWouldBeOnLive() {
        String body = request("claim-test-1", "95810");
        assertThat(submit(null, "P32H-PAS-BAD400", body).failureType()).isEqualTo(OnyxFailureType.BAD_REQUEST);
        assertThat(submit(null, "P32H-PAS-ERR500", body).failureType()).isEqualTo(OnyxFailureType.SERVER_ERROR);
        VendorExchange timeout = submit(null, "P32H-PAS-TIMEOUT", body);
        assertThat(timeout.failureType()).isEqualTo(OnyxFailureType.TIMEOUT);
        assertThat(timeout.httpStatus()).isNull();
        assertThat(submit(null, "P32H-PAS-DOWN", body).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
        assertThat(inquire(null, "P32H-PAS-DOWN", body).failureType()).isEqualTo(OnyxFailureType.UNAVAILABLE);
    }

    // ---- the subscription client ----

    private static MockSelection about(String scenarioId, String subscriptionId) {
        return new MockSelection(scenarioId, null, null,
                subscriptionId == null ? Map.of() : Map.of(MockSubscriptionClient.HINT_SUBSCRIPTION_ID, subscriptionId));
    }

    @Test
    void aSubscriptionCallWithNoChosenScenarioIsAnsweredByTheFirstThatDefinesItsStepAndSaysSo() {
        VendorExchange created = subscriptions.create(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_CREATE),
                ResolvedText.plain("{\"resourceType\":\"Subscription\"}"), about(null, null));
        assertThat(created.scenarioId()).isEqualTo("pas-10-subscription-lifecycle");
        assertThat(created.scenarioNote()).isEqualTo("the default for a subscription call, because no scenario was chosen");
        assertThat(created.httpStatus()).isEqualTo(201);
        assertThat(created.json().get("id").asString()).isEqualTo("subscription-placeholder-0001");
        assertThat(created.request().note()).contains("step \"subscription-create\"");
        assertThat(created.request().method()).isEqualTo("POST");
        assertThat(created.request().body()).isEqualTo("{\"resourceType\":\"Subscription\"}");
    }

    @Test
    void aReadAndAListAreToldApartByWhetherTheCallIsAboutOneSubscription() {
        VendorExchange one = subscriptions.get(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_GET),
                about(null, "subscription-placeholder-0001"));
        assertThat(one.json().get("resourceType").asString()).isEqualTo("Subscription");
        assertThat(one.request().note()).contains("step \"subscription-read\"");
        assertThat(one.request().body()).isNull();
        VendorExchange list = subscriptions.get(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_GET), about(null, null));
        assertThat(list.json().get("resourceType").asString()).isEqualTo("Bundle");
        assertThat(list.json().get("type").asString()).isEqualTo("searchset");
        assertThat(list.request().note()).contains("step \"subscription-list\"");
        // A selection that is absent altogether is a list too.
        assertThat(subscriptions.get(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_GET), null).json().get("type").asString())
                .isEqualTo("searchset");
    }

    @Test
    void anUpdateAndADeleteAreAnsweredFromTheirOwnStepsAndADeleteHasNoBody() {
        VendorExchange updated = subscriptions.update(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_UPDATE),
                ResolvedText.plain("{\"resourceType\":\"Subscription\",\"status\":\"off\"}"), about(null, "subscription-placeholder-0001"));
        assertThat(updated.json().get("status").asString()).isEqualTo("off");
        assertThat(updated.request().method()).isEqualTo("PUT");
        VendorExchange deleted = subscriptions.delete(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_DELETE),
                about(null, "subscription-placeholder-0001"));
        assertThat(deleted.httpStatus()).isEqualTo(204);
        assertThat(deleted.responseBody()).isNull();
        // No content is the normal answer to a delete, so it is not a malformed response.
        assertThat(deleted.failureType()).isEqualTo(OnyxFailureType.NONE);
        assertThat(deleted.request().method()).isEqualTo("DELETE");
    }

    @Test
    void aChosenScenarioAnswersASubscriptionCallAndOneThatDoesNotCoverItIsRefused() {
        VendorExchange notFound = subscriptions.get(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_GET),
                about("pas-19-subscription-not-found", "subscription-placeholder-0001"));
        assertThat(notFound.httpStatus()).isEqualTo(404);
        assertThat(notFound.scenarioNote()).isEqualTo("chosen in the scenario selector");
        assertThat(OnyxPasConventions.subscriptionNotFound(notFound)).isTrue();

        assertThatThrownBy(() -> subscriptions.create(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_CREATE),
                ResolvedText.plain("{}"), about("pas-01-approved", null))).isInstanceOfSatisfying(ValidationException.class,
                e -> assertThat(e.fieldErrors().get(0).message()).contains("pas-01-approved").contains("\"subscription-create\""));
        assertThatThrownBy(() -> subscriptions.create(TestPreparedCalls.unconfigured(EpaOperation.SUBSCRIPTION_CREATE),
                ResolvedText.plain("{}"), about("pas-99-nope", null))).isInstanceOf(ValidationException.class);
    }

    @Test
    void everyNotifyStepHoldsABundleThatIsANotification() {
        int seen = 0;
        for (Scenario scenario : scenarios.list(MockOnyxPasClient.SCENARIOS)) {
            if (scenario.step(MockOnyxPasClient.STEP_NOTIFY) == null) {
                continue;
            }
            seen++;
            JsonNode body = JsonSafe.parse(scenario.step(MockOnyxPasClient.STEP_NOTIFY).body()).node();
            assertThat(body.get("resourceType").asString()).as(scenario.id()).isEqualTo("Bundle");
            assertThat(OnyxPasConventions.subscriptionStatus(body.get("entry").get(0).get("resource"))).as(scenario.id()).isTrue();
        }
        assertThat(seen).isEqualTo(5);
    }
}
