package com.thehiddenbrain.interop.patientaccess.patient;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.catalog.IgCatalog;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorAuthSummarizerTest {

    private static final PriorAuthSummarizer SUMMARIZER = new PriorAuthSummarizer(new IgCatalog());
    private static final String X12_306 = "https://codesystem.x12.org/005010/306";

    /** A minimal PDex PA EOB with one item and no adjudication; tests add what they need. */
    static ObjectNode paEob(String id, String status, String outcome) {
        ObjectNode eob = Fixtures.resource("ExplanationOfBenefit", id);
        eob.putObject("meta").putArray("profile").add(PriorAuthSummarizer.PDEX_PA_PROFILE);
        eob.put("status", status).put("use", "preauthorization").put("outcome", outcome).put("created", "2025-02-01T00:00:00Z");
        eob.putObject("type").putArray("coding").addObject().put("system", "http://terminology.hl7.org/CodeSystem/claim-type").put("code", "professional");
        eob.putObject("patient").put("reference", "Patient/1");
        eob.putObject("insurer").put("reference", "Organization/payer").put("display", "Payer");
        eob.putObject("provider").put("reference", "Practitioner/p1");
        ObjectNode item = eob.putArray("item").addObject().put("sequence", 1);
        item.putObject("productOrService").putArray("coding").addObject().put("system", "http://www.ama-assn.org/go/cpt").put("code", "97110").put("display", "Therapeutic exercises");
        item.putObject("servicedPeriod").put("start", "2025-02-10").put("end", "2025-03-10");
        item.putObject("quantity").put("value", 12).put("unit", "visits");
        return eob;
    }

    static ObjectNode adjudication(ArrayNode adjudications, String category, String reviewCode, String actionDate) {
        ObjectNode adj = adjudications.addObject();
        adj.putObject("category").putArray("coding").addObject().put("system", PriorAuthSummarizer.CS_PDEX_DISCRIMINATOR).put("code", category);
        ArrayNode ext = adj.putArray("extension");
        if (reviewCode != null) {
            ObjectNode review = ext.addObject().put("url", PriorAuthSummarizer.EXT_REVIEW_ACTION);
            review.putArray("extension").addObject().put("url", PriorAuthSummarizer.EXT_REVIEW_ACTION_CODE)
                    .putObject("valueCodeableConcept").putArray("coding").addObject().put("system", X12_306).put("code", reviewCode);
        }
        if (actionDate != null) {
            ext.addObject().put("url", PriorAuthSummarizer.EXT_WHEN_ADJUDICATED).put("valueDateTime", actionDate);
        }
        return adj;
    }

    @Test
    void summarisesThePdexExampleAsApproved() {
        PriorAuthSummary s = SUMMARIZER.summarize(Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1"));
        assertThat(s.id()).isEqualTo("PDexPriorAuth1");
        assertThat(s.pdexProfileDeclared()).isTrue();
        assertThat(s.use()).isEqualTo("preauthorization");
        assertThat(s.status()).isEqualTo("active");
        assertThat(s.outcome()).isEqualTo("queued");
        assertThat(s.decision()).isEqualTo("APPROVED");
        assertThat(s.decisionBasis()).isEqualTo("review action 'Certified in total'");
        assertThat(s.type()).isEqualTo("Institutional");
        assertThat(s.levelOfService()).isEqualTo("Urgent (U)");
        assertThat(s.identifiers()).containsExactly("PA123412341234123412341234");
        assertThat(s.created()).isEqualTo("2021-09-20T00:00:00+00:00");
        assertThat(s.decisionDate()).isEqualTo("2024-07-23T17:26:23.217+00:00");
        assertThat(s.validFrom()).isEqualTo("2021-10-01");
        assertThat(s.validTo()).isEqualTo("2021-10-31");
        assertThat(s.lastUpdated()).isEqualTo("2024-02-06T09:14:11+00:00");
        assertThat(s.patient()).isEqualTo("Patient/1");
        assertThat(s.insurer()).isEqualTo("Example Health Plan [Organization/Payer1]");
        assertThat(s.provider()).isEqualTo("Another Example Health Plan [Organization/Payer2]");
        assertThat(s.diagnoses()).singleElement().satisfies(d -> assertThat(d).contains("G89.4"));
        assertThat(s.denialReasons()).isEmpty();

        assertThat(s.items()).hasSize(1);
        PriorAuthSummary.Item item = s.items().get(0);
        assertThat(item.sequence()).isEqualTo(1);
        assertThat(item.category()).isEqualTo("Consultation (3)");
        assertThat(item.productOrService()).startsWith("Behavior Only").endsWith("(BB201)");
        assertThat(item.decision()).isEqualTo("Certified in total (A1)");
        assertThat(item.allowedUnits()).isNull();
        assertThat(item.consumedUnits()).isNull();
        assertThat(item.adjudications()).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo("submitted");
            assertThat(a.categoryDisplay()).isEqualTo("Submitted Amount (submitted)");
            assertThat(a.amount()).isEqualTo("300.99 USD");
            assertThat(a.actionDate()).isEqualTo("2024-07-23T17:26:23.217+00:00");
            assertThat(a.reviewAction().code()).isEqualTo("A1");
            assertThat(a.reviewAction().display()).isEqualTo("Certified in total (A1)");
            assertThat(a.reviewAction().number()).isEqualTo("AUTH0001");
        });

        assertThat(s.totals()).singleElement().satisfies(t -> {
            assertThat(t.category()).isEqualTo("eligible");
            assertThat(t.categoryDisplay()).isEqualTo("Eligible (eligible)");
            assertThat(t.amount()).isEqualTo("100 USD");
            assertThat(t.utilization()).isEqualTo("1");
        });
        assertThat(s.issues()).isEmpty();
        assertThat(s.resource()).isNotNull();
    }

    @Test
    void deniedItemsWithDenialReasonsYieldDenied() {
        ObjectNode eob = paEob("denied", "active", "complete");
        ArrayNode adj = (ArrayNode) eob.withArray("item").get(0).withArray("adjudication");
        ObjectNode denial = adjudication(adj, "denialreason", "A3", "2025-02-05");
        denial.putObject("reason").put("text", "Not medically necessary");
        adjudication(adj, "allowedunits", null, null).put("value", 0);

        PriorAuthSummary s = SUMMARIZER.summarize(eob);
        assertThat(s.decision()).isEqualTo("DENIED");
        assertThat(s.decisionBasis()).contains("Not certified");
        assertThat(s.decisionDate()).isEqualTo("2025-02-05");
        assertThat(s.denialReasons()).containsExactly("Not medically necessary");
        PriorAuthSummary.Item item = s.items().get(0);
        assertThat(item.decision()).isEqualTo("Not certified (A3)");
        assertThat(item.denialReasons()).containsExactly("Not medically necessary");
        assertThat(item.allowedUnits()).isEqualTo("0");
        assertThat(item.serviced()).isEqualTo("2025-02-10 – 2025-03-10");
        assertThat(item.quantity()).isEqualTo("12 visits");
        assertThat(item.productOrService()).isEqualTo("Therapeutic exercises (97110)");
        assertThat(item.adjudications().get(0).categoryDisplay()).isEqualTo("Denial Reason");
        assertThat(item.adjudications().get(1).categoryDisplay()).isEqualTo("allowed units");
        assertThat(s.issues()).containsExactly("no authorization period (preAuthRefPeriod or item preAuthPeriod)");
    }

    @Test
    void pendedReviewActionYieldsPending() {
        ObjectNode eob = paEob("pended", "active", "queued");
        adjudication((ArrayNode) eob.withArray("item").get(0).withArray("adjudication"), "submitted", "A4", null);
        PriorAuthSummary s = SUMMARIZER.summarize(eob);
        assertThat(s.decision()).isEqualTo("PENDING");
        assertThat(s.decisionBasis()).contains("Pended");
        assertThat(s.items().get(0).decision()).isEqualTo("Pended (A4)");
        assertThat(s.decisionDate()).isNull();
        assertThat(s.issues()).contains("no decision date (item preAuthIssueDate or adjudication when-adjudicated extension)");

        // no review action at all: outcome queued still means pending
        PriorAuthSummary queued = SUMMARIZER.summarize(paEob("queued", "active", "queued"));
        assertThat(queued.decision()).isEqualTo("PENDING");
        assertThat(queued.decisionBasis()).contains("outcome queued");
    }

    @Test
    void completeOutcomeWithoutReviewActionIsImpliedApproval() {
        ObjectNode eob = paEob("implied", "active", "complete");
        ObjectNode item = (ObjectNode) eob.withArray("item").get(0);
        ArrayNode ext = item.putArray("extension");
        ext.addObject().put("url", PriorAuthSummarizer.EXT_ITEM_PREAUTH_ISSUE).put("valueDate", "2025-02-03");
        ext.addObject().put("url", PriorAuthSummarizer.EXT_ITEM_PREAUTH_PERIOD).putObject("valuePeriod").put("start", "2025-02-10").put("end", "2025-08-10");

        PriorAuthSummary s = SUMMARIZER.summarize(eob);
        assertThat(s.decision()).isEqualTo("APPROVED (implied)");
        assertThat(s.decisionBasis()).contains("outcome complete without denial reasons");
        assertThat(s.decisionDate()).isEqualTo("2025-02-03");
        assertThat(s.validFrom()).isEqualTo("2025-02-10");
        assertThat(s.validTo()).isEqualTo("2025-08-10");
        assertThat(s.items().get(0).decision()).isNull();
        assertThat(s.issues()).containsExactly("no reviewAction extension on any adjudication: the decision (approved / denied / pended) is not expressed explicitly");
    }

    @Test
    void allowedAndConsumedUnitsAreReadFromTheItemAdjudications() {
        ObjectNode eob = paEob("units", "active", "complete");
        ObjectNode item = (ObjectNode) eob.withArray("item").get(0);
        ArrayNode adj = item.withArray("adjudication");
        adjudication(adj, "allowedunits", null, null).put("value", 10);
        adjudication(adj, "consumedunits", null, null).put("value", 3);
        ArrayNode ext = item.putArray("extension");
        ext.addObject().put("url", PriorAuthSummarizer.EXT_ITEM_PREAUTH_ISSUE).put("valueDate", "2025-02-03");
        ext.addObject().put("url", PriorAuthSummarizer.EXT_ITEM_PREAUTH_PERIOD).putObject("valuePeriod").put("start", "2025-02-10").put("end", "2025-08-10");
        ext.addObject().put("url", PriorAuthSummarizer.EXT_ITEM_TRACE).putObject("valueIdentifier").put("value", "TRACE-1");
        ext.addObject().put("url", PriorAuthSummarizer.EXT_AUTH_NUMBER).putObject("valueIdentifier").put("value", "PREV-9");
        ObjectNode total = eob.putArray("total").addObject();
        total.putObject("category").putArray("coding").addObject().put("system", "http://hl7.org/fhir/us/davinci-pdex/CodeSystem/PriorAuthorizationValueCodes").put("code", "utilized");
        total.putObject("amount").put("value", 3).put("currency", "USD");
        total.putArray("extension").addObject().put("url", PriorAuthSummarizer.EXT_UTILIZATION).putObject("valueQuantity").put("value", 3);

        PriorAuthSummary s = SUMMARIZER.summarize(eob);
        // allowed units without a review action count as an approval of the item
        assertThat(s.decision()).isEqualTo("APPROVED");
        assertThat(s.decisionDate()).isEqualTo("2025-02-03");
        assertThat(s.validFrom()).isEqualTo("2025-02-10");
        assertThat(s.validTo()).isEqualTo("2025-08-10");
        PriorAuthSummary.Item item0 = s.items().get(0);
        assertThat(item0.allowedUnits()).isEqualTo("10");
        assertThat(item0.consumedUnits()).isEqualTo("3");
        assertThat(item0.decision()).isEqualTo("Approved units 10 (from allowedunits adjudication)");
        assertThat(item0.preAuthPeriod()).isEqualTo("2025-02-10 – 2025-08-10");
        assertThat(item0.preAuthIssueDate()).isEqualTo("2025-02-03");
        assertThat(item0.traceNumbers()).containsExactly("TRACE-1");
        assertThat(item0.previousAuthorizationNumber()).isEqualTo("PREV-9");
        assertThat(s.totals()).singleElement().satisfies(t -> {
            assertThat(t.category()).isEqualTo("utilized");
            assertThat(t.utilization()).isEqualTo("3");
        });
        assertThat(s.issues()).isEmpty();
    }

    @Test
    void otherDecisionsAndIssuesAreDerived() {
        ObjectNode partial = paEob("partial", "active", "complete");
        ArrayNode adj = (ArrayNode) partial.withArray("item").get(0).withArray("adjudication");
        adjudication(adj, "submitted", "A2", null);
        assertThat(SUMMARIZER.summarize(partial).decision()).isEqualTo("PARTIALLY APPROVED");

        assertThat(SUMMARIZER.summarize(paEob("cancelled", "cancelled", "complete")).decision()).isEqualTo("CANCELLED");
        assertThat(SUMMARIZER.summarize(paEob("error", "active", "error")).decision()).isEqualTo("ERROR");
        assertThat(SUMMARIZER.summarize(paEob("partial-outcome", "active", "partial")).decision()).isEqualTo("PARTIAL (implied)");
        assertThat(SUMMARIZER.summarize(paEob("unknown", "active", null)).decision()).isEqualTo("UNKNOWN");

        ObjectNode claimLevel = paEob("claim-level", "active", "complete");
        claimLevel.remove("item");
        claimLevel.remove("insurer");
        adjudication(claimLevel.putArray("adjudication"), "submitted", "A1", "2025-01-01");
        PriorAuthSummary s = SUMMARIZER.summarize(claimLevel);
        assertThat(s.decision()).isEqualTo("APPROVED");
        assertThat(s.decisionDate()).isEqualTo("2025-01-01");
        assertThat(s.claimAdjudications()).hasSize(1);
        assertThat(s.issues()).contains("no item: the items and services of the authorization are not listed",
                "insurer and provider are required (1..1) by the profile");

        ObjectNode plainClaim = Fixtures.demo("c4bb-ExplanationOfBenefit-EOBInpatient1");
        PriorAuthSummary claim = SUMMARIZER.summarize(plainClaim);
        assertThat(claim.pdexProfileDeclared()).isFalse();
        assertThat(claim.issues()).contains("meta.profile does not declare " + PriorAuthSummarizer.PDEX_PA_PROFILE,
                "use is 'claim', the PDex PriorAuthorization profile fixes it to 'preauthorization'");
        assertThat(claim.issues()).anySatisfy(i -> assertThat(i).startsWith("no reviewAction extension"));
    }

    @Test
    void isPriorAuthLooksAtUseAndProfile() {
        assertThat(PriorAuthSummarizer.isPriorAuth(Fixtures.demo("pdex-ExplanationOfBenefit-PDexPriorAuth1"))).isTrue();
        assertThat(PriorAuthSummarizer.isPriorAuth(Fixtures.demo("c4bb-ExplanationOfBenefit-EOBInpatient1"))).isFalse();
        assertThat(PriorAuthSummarizer.isPriorAuth(Fixtures.resource("ExplanationOfBenefit", "u").put("use", "preauthorization"))).isTrue();
        ObjectNode byProfile = Fixtures.resource("ExplanationOfBenefit", "p").put("use", "claim");
        byProfile.putObject("meta").putArray("profile").add(PriorAuthSummarizer.PDEX_PA_PROFILE + "|2.1.0");
        assertThat(PriorAuthSummarizer.isPriorAuth(byProfile)).isTrue();
        assertThat(PriorAuthSummarizer.isPriorAuth(Fixtures.resource("ExplanationOfBenefit", "c").put("use", "claim"))).isFalse();
        assertThat(PriorAuthSummarizer.isPriorAuth(Fixtures.resource("Claim", "c").put("use", "preauthorization"))).isFalse();
        assertThat(PriorAuthSummarizer.display("A6")).isEqualTo("Modified (A6)");
        assertThat(PriorAuthSummarizer.display("ZZ")).isEqualTo("ZZ");
    }
}
