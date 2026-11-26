package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import org.junit.jupiter.api.Test;

class EnumContractTest {

    @Test
    void crdHasNoMemberThatReadsAsAClinicalRefusal() {
        // CRD never denies on clinical grounds, so the outcome vocabulary must not be able to say so.
        for (CrdOutcome outcome : CrdOutcome.values()) {
            String name = outcome.name().toLowerCase(Locale.ROOT);
            String label = outcome.label().toLowerCase(Locale.ROOT);
            assertThat(name).as(outcome.name()).doesNotContain("deni").doesNotContain("deny").doesNotContain("refus");
            assertThat(label).as(outcome.name()).doesNotContain("denied").doesNotContain("denial").doesNotContain("deny")
                    .doesNotContain("refus").doesNotContain("medical necessity");
        }
    }

    @Test
    void theCrdVocabularyIsExactlyThisListAndAddingToItIsADeliberateAct() {
        // A list of forbidden words cannot catch every way of saying no. Pinning the names means a
        // new outcome cannot arrive without someone editing the test that states the rule:
        // CRD reports coverage and authorization requirements, and never a clinical decision.
        assertThat(Arrays.stream(CrdOutcome.values()).map(Enum::name)).containsExactly("AUTH_REQUIRED", "NO_AUTH_REQUIRED",
                "AUTH_SATISFIED", "PERFORMER_AUTH", "DOCUMENTATION_REQUIRED", "INFORMATION_NEEDED", "CONDITIONAL", "NOT_COVERED",
                "COVERAGE_INACTIVE", "MEMBER_NOT_FOUND", "PROVIDER_NOT_FOUND", "AUTH_NOT_STATED", "UNRECOGNISED_CODE", "MIXED",
                "NO_DETERMINATION", "REJECTED", "SERVICE_ERROR", "TIMEOUT", "UNREACHABLE", "MALFORMED_RESPONSE");
        for (CrdOutcome outcome : CrdOutcome.values()) {
            assertThat(outcome.label().toLowerCase(java.util.Locale.ROOT)).as(outcome.name()).doesNotContain("medically necessary")
                    .doesNotContain("declin").doesNotContain("not approved");
        }
    }

    @Test
    void anEnumIsReadFromItsNameInAnyCaseAndNeverFromANumber() {
        assertThat(Protocol.parse(" crd ")).isEqualTo(Protocol.CRD);
        assertThat(Protocol.parse("Subscription")).isEqualTo(Protocol.SUBSCRIPTION);
        assertThatThrownBy(() -> Protocol.parse("fax")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Protocol must be one of crd, dtr, pas, subscription, lookup or raw.");
        assertThat(EpaOperation.parse("crd_order_sign")).isEqualTo(EpaOperation.CRD_ORDER_SIGN);
        assertThat(EpaOperation.parse("pas-submit")).isEqualTo(EpaOperation.PAS_SUBMIT);
        assertThatThrownBy(() -> EpaOperation.parse("PAS_APPEAL")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRD_ORDER_SIGN").hasMessageNotContaining("PAS_APPEAL");
        assertThat(JsonSafe.mapper().readValue("\"dtr\"", Protocol.class)).isEqualTo(Protocol.DTR);
        assertThat(JsonSafe.mapper().readValue("\"pas_inquire\"", EpaOperation.class)).isEqualTo(EpaOperation.PAS_INQUIRE);
        // 2 must not come back as whichever constant happens to be declared third.
        assertThatThrownBy(() -> JsonSafe.mapper().readValue("2", Protocol.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonSafe.mapper().readValue("6", EpaOperation.class)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> JsonSafe.mapper().readValue("1", Tone.class)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void theAuthorizationFlagIsReadByItsMeaningAndAnythingElseIsUnknown() {
        for (String yes : new String[] {"yes", "Y", "TRUE", "1", "required", "Required", "PA", "pa required", "PA_REQUIRED",
                "prior-auth-required", "auth needed", " yes "}) {
            assertThat(AuthRequirement.parse(yes)).as(yes).isEqualTo(AuthRequirement.REQUIRED);
        }
        for (String no : new String[] {"no", "N", "false", "0", "not required", "NOT_REQUIRED", "No PA", "none", "not-needed"}) {
            assertThat(AuthRequirement.parse(no)).as(no).isEqualTo(AuthRequirement.NOT_REQUIRED);
        }
        for (String unknown : new String[] {null, "", "  ", "maybe", "see policy", "2", "unknown", "x", "tbd"}) {
            assertThat(AuthRequirement.parse(unknown)).as("[" + unknown + "]").isEqualTo(AuthRequirement.UNKNOWN);
        }
        // So a caller can tell a value it could not read from one that says "unknown".
        assertThat(AuthRequirement.recognises("see policy")).isFalse();
        assertThat(AuthRequirement.recognises("unknown")).isTrue();
        assertThat(AuthRequirement.recognises("yes")).isTrue();
        assertThat(AuthRequirement.recognises(" ")).isFalse();
        // In a JSON list a number is read by its meaning, not by its position in the enum, where 1 would be "not required".
        assertThat(JsonSafe.mapper().readValue("1", AuthRequirement.class)).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(JsonSafe.mapper().readValue("0", AuthRequirement.class)).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(JsonSafe.mapper().readValue("\"NOT_REQUIRED\"", AuthRequirement.class)).isEqualTo(AuthRequirement.NOT_REQUIRED);
        assertThat(JsonSafe.mapper().readValue("\"yes\"", AuthRequirement.class)).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(JsonSafe.mapper().readValue(JsonSafe.mapper().writeValueAsString(AuthRequirement.UNKNOWN), AuthRequirement.class))
                .isEqualTo(AuthRequirement.UNKNOWN);
    }

    @Test
    void noAuthAndNoDeterminationAreSeparateOutcomesWithSeparateMeanings() {
        assertThat(CrdOutcome.NO_AUTH_REQUIRED).isNotEqualTo(CrdOutcome.NO_DETERMINATION);
        assertThat(CrdOutcome.NO_AUTH_REQUIRED.label()).isNotEqualTo(CrdOutcome.NO_DETERMINATION.label());
        assertThat(CrdOutcome.NO_AUTH_REQUIRED.tone()).isEqualTo(Tone.CLEAR);
        assertThat(CrdOutcome.NO_DETERMINATION.tone()).isEqualTo(Tone.NO_ANSWER);
        assertThat(CrdOutcome.NO_DETERMINATION.fault()).isFalse();
    }

    @Test
    void notFoundIsAFindingAboutDataAndAFaultIsAFindingAboutTheEnvironment() {
        assertThat(CrdOutcome.MEMBER_NOT_FOUND.tone()).isEqualTo(Tone.DATA);
        assertThat(CrdOutcome.PROVIDER_NOT_FOUND.tone()).isEqualTo(Tone.DATA);
        assertThat(CrdOutcome.MEMBER_NOT_FOUND.fault()).isFalse();
        for (CrdOutcome fault : EnumSet.of(CrdOutcome.REJECTED, CrdOutcome.SERVICE_ERROR, CrdOutcome.TIMEOUT,
                CrdOutcome.UNREACHABLE, CrdOutcome.MALFORMED_RESPONSE)) {
            assertThat(fault.fault()).as(fault.name()).isTrue();
            assertThat(fault.tone()).as(fault.name()).isEqualTo(Tone.FAULT);
        }
        assertThat(Arrays.stream(CrdOutcome.values()).filter(CrdOutcome::fault)).hasSize(5);
    }

    @Test
    void everyOutcomeHasALabel() {
        for (CrdOutcome outcome : CrdOutcome.values()) {
            assertThat(Texts.present(outcome.label())).as(outcome.name()).isTrue();
            assertThat(outcome.tone()).as(outcome.name()).isNotNull();
        }
    }

    @Test
    void exactlyTheFourChangingOperationsAreWrites() {
        assertThat(Arrays.stream(EpaOperation.values()).filter(EpaOperation::write))
                .containsExactlyInAnyOrder(EpaOperation.PAS_SUBMIT, EpaOperation.SUBSCRIPTION_CREATE,
                        EpaOperation.SUBSCRIPTION_UPDATE, EpaOperation.SUBSCRIPTION_DELETE);
    }

    @Test
    void aReadSentWithItsOwnMethodIsARead() {
        // PAS inquire and the DTR operations are POSTs that change nothing, so they are allowed on production.
        assertThat(EpaOperation.PAS_INQUIRE.writesWith("POST")).isFalse();
        assertThat(EpaOperation.PAS_INQUIRE.writesWith(" post ")).isFalse();
        assertThat(EpaOperation.CRD_ORDER_SIGN.writesWith("POST")).isFalse();
        assertThat(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE.writesWith("POST")).isFalse();
        assertThat(EpaOperation.DTR_NEXT_QUESTION.writesWith("POST")).isFalse();
        assertThat(EpaOperation.SUBSCRIPTION_GET.writesWith("GET")).isFalse();
        assertThat(EpaOperation.MEMBER_LOOKUP.writesWith("GET")).isFalse();
        assertThat(EpaOperation.CRD_DISCOVERY.writesWith("HEAD")).isFalse();
    }

    @Test
    void aWriteIsAWriteWhateverItsMethod() {
        for (EpaOperation operation : EpaOperation.values()) {
            if (operation.write()) {
                for (String method : new String[] {"GET", "POST", "PUT", "DELETE", "", null}) {
                    assertThat(operation.writesWith(method)).as(operation + " with " + method).isTrue();
                }
            }
        }
    }

    @Test
    void aReadOperationSentWithAMethodThatChangesStateIsAWrite() {
        // The guardrail is decided on what is sent, not on the name the caller gave it. Naming
        // "Subscription get" and sending a DELETE must not pass as a read.
        assertThat(EpaOperation.SUBSCRIPTION_GET.writesWith("DELETE")).isTrue();
        assertThat(EpaOperation.SUBSCRIPTION_GET.writesWith("PUT")).isTrue();
        assertThat(EpaOperation.SUBSCRIPTION_GET.writesWith("POST")).isTrue();
        assertThat(EpaOperation.PAS_INQUIRE.writesWith("PUT")).isTrue();
        assertThat(EpaOperation.PAS_INQUIRE.writesWith("DELETE")).isTrue();
        assertThat(EpaOperation.CRD_DISCOVERY.writesWith("POST")).isTrue();
        assertThat(EpaOperation.MEMBER_LOOKUP.writesWith("POST")).isTrue();
        assertThat(EpaOperation.CRD_ORDER_SIGN.writesWith("PATCH")).isTrue();
        // With no method stated, a mapped read is judged by its own flag.
        assertThat(EpaOperation.SUBSCRIPTION_GET.writesWith(null)).isFalse();
        assertThat(EpaOperation.PAS_INQUIRE.writesWith(" ")).isFalse();
    }

    @Test
    void everyOnyxOperationHasAFallbackPathAndTheOthersHaveNone() {
        assertThat(EpaOperation.CRD_ORDER_SIGN.fallbackPath()).isEqualTo("/cds-services/{{crdOrderSignServiceId}}");
        assertThat(EpaOperation.CRD_ORDER_SELECT.fallbackPath()).isEqualTo("/cds-services/{{crdOrderSelectServiceId}}");
        assertThat(EpaOperation.CRD_DISCOVERY.fallbackPath()).isEqualTo("/cds-services");
        assertThat(EpaOperation.DTR_QUESTIONNAIRE_PACKAGE.fallbackPath()).isEqualTo("/Questionnaire/$questionnaire-package");
        assertThat(EpaOperation.DTR_NEXT_QUESTION.fallbackPath()).isEqualTo("/Questionnaire/$next-question");
        assertThat(EpaOperation.PAS_SUBMIT.fallbackPath()).isEqualTo("/Claim/$submit");
        assertThat(EpaOperation.PAS_INQUIRE.fallbackPath()).isEqualTo("/Claim/$inquire");
        assertThat(EpaOperation.SUBSCRIPTION_CREATE.fallbackPath()).isEqualTo("/Subscription");
        assertThat(EpaOperation.SUBSCRIPTION_GET.fallbackPath()).isEqualTo("/Subscription/{id}");
        assertThat(EpaOperation.SUBSCRIPTION_UPDATE.fallbackPath()).isEqualTo("/Subscription/{id}");
        assertThat(EpaOperation.SUBSCRIPTION_DELETE.fallbackPath()).isEqualTo("/Subscription/{id}");
        // A notification is inbound, and a questionnaire response is completed inside the tool,
        // so there is nowhere for the tool to send either.
        assertThat(EpaOperation.SUBSCRIPTION_NOTIFICATION.fallbackPath()).isNull();
        assertThat(EpaOperation.DTR_RESPONSE.fallbackPath()).isNull();
        assertThat(EpaOperation.MEMBER_LOOKUP.fallbackPath()).isNull();
        assertThat(EpaOperation.PROVIDER_LOOKUP.fallbackPath()).isNull();
        assertThat(EpaOperation.UNMAPPED.fallbackPath()).isNull();
        // The service id is not known, so it is never a literal in a path.
        assertThat(EpaOperation.CRD_ORDER_SIGN.fallbackPath()).contains("{{");
    }

    @Test
    void exactlyTwoOperationsNeverLeaveTheTool() {
        for (EpaOperation operation : EpaOperation.values()) {
            boolean stays = operation == EpaOperation.SUBSCRIPTION_NOTIFICATION || operation == EpaOperation.DTR_RESPONSE;
            assertThat(operation.outbound()).as(operation.name()).isEqualTo(!stays);
        }
        assertThat(EpaOperation.DTR_RESPONSE.protocol()).isEqualTo(Protocol.DTR);
        assertThat(EpaOperation.DTR_RESPONSE.write()).isFalse();
    }

    @Test
    void anOperationThatNeverLeavesTheToolIsJudgedAsAWriteIfARequestIsEverPointedAtOne() {
        // An operator can remap a vendor's sample request to anything. A request detected as a
        // notification and sent as a POST must not pass as a read: nothing is known about what
        // posting it to a vendor url would do.
        for (EpaOperation operation : new EpaOperation[] {EpaOperation.SUBSCRIPTION_NOTIFICATION, EpaOperation.DTR_RESPONSE}) {
            for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "", null}) {
                assertThat(operation.writesWith(method)).as(operation + " with " + method).isTrue();
            }
            for (String method : new String[] {"GET", "HEAD", "OPTIONS"}) {
                assertThat(operation.writesWith(method)).as(operation + " with " + method).isFalse();
            }
        }
    }

    @Test
    void anUnmappedRequestIsAWriteUnlessItsMethodCannotChangeState() {
        assertThat(EpaOperation.UNMAPPED.writesWith("GET")).isFalse();
        assertThat(EpaOperation.UNMAPPED.writesWith("get")).isFalse();
        assertThat(EpaOperation.UNMAPPED.writesWith("HEAD")).isFalse();
        assertThat(EpaOperation.UNMAPPED.writesWith("OPTIONS")).isFalse();
        for (String method : new String[] {"POST", "PUT", "PATCH", "DELETE", "post", "", null, "PURGE"}) {
            assertThat(EpaOperation.UNMAPPED.writesWith(method)).as("method=" + method).isTrue();
        }
    }

    @Test
    void everyOperationKnowsItsProtocolLabelAndMethod() {
        for (EpaOperation operation : EpaOperation.values()) {
            assertThat(operation.protocol()).as(operation.name()).isNotNull();
            assertThat(Texts.present(operation.label())).as(operation.name()).isTrue();
            assertThat(Texts.present(operation.defaultMethod())).as(operation.name()).isTrue();
        }
    }

    @Test
    void wireSpellingsAreLowerCaseAndReadBackInAnyCase() {
        assertThat(TestResult.PASS.wire()).isEqualTo("pass");
        assertThat(TestResult.UNVERIFIED.wire()).isEqualTo("unverified");
        assertThat(TestResult.parse(" FAIL ")).isEqualTo(TestResult.FAIL);
        assertThat(EnvironmentKind.PROD.wire()).isEqualTo("prod");
        assertThat(EnvironmentKind.parse("Sandbox")).isEqualTo(EnvironmentKind.SANDBOX);
        assertThat(ScenarioBehavior.parse(" Timeout ")).isEqualTo(ScenarioBehavior.TIMEOUT);
        assertThat(ScenarioBehavior.UNAVAILABLE.wire()).isEqualTo("unavailable");
    }

    @Test
    void anUnknownOrBlankSpellingIsRejectedAndNeverDefaulted() {
        // A blank kind must not quietly become sandbox: that is the unsafe direction for the guardrail.
        assertThatThrownBy(() -> EnvironmentKind.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnvironmentKind.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EnvironmentKind.parse("production")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestResult.parse("passed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScenarioBehavior.parse("hang")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void enumsRoundTripThroughJson() {
        assertThat(JsonSafe.mapper().writeValueAsString(TestResult.UNVERIFIED)).isEqualTo("\"unverified\"");
        assertThat(JsonSafe.mapper().readValue("\"PROD\"", EnvironmentKind.class)).isEqualTo(EnvironmentKind.PROD);
        assertThat(JsonSafe.mapper().writeValueAsString(EnvironmentKind.UAT)).isEqualTo("\"uat\"");
        assertThat(JsonSafe.mapper().readValue("\"respond\"", ScenarioBehavior.class)).isEqualTo(ScenarioBehavior.RESPOND);
    }
}
