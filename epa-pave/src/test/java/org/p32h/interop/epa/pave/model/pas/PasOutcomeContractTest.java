package org.p32h.interop.epa.pave.model.pas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.CrdOutcome;
import org.p32h.interop.epa.pave.model.OutcomeCode;
import org.p32h.interop.epa.pave.model.OutcomeOption;
import org.p32h.interop.epa.pave.model.RunOutcome;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.Tone;

/** What the three PAS vocabularies promise the ledger, the expectation check and the screen. */
class PasOutcomeContractTest {

    @Test
    void pasCarriesARealDenialAndTonesItAsOne() {
        // CRD has no member for a denial and must never have one. PAS has, and it has to read as one.
        assertThat(PasOutcome.DENIED.tone()).isEqualTo(Tone.STOP);
        assertThat(PasOutcome.DENIED.fault()).isFalse();
        assertThat(PasOutcome.DENIED.label().toLowerCase(Locale.ROOT)).contains("denied");
        assertThat(Arrays.stream(CrdOutcome.values()).map(Enum::name)).doesNotContain("DENIED");
    }

    @Test
    void noDecisionIsItsOwnOutcomeAndIsNeitherApprovedNorPendedNorDenied() {
        Set<String> labels = new HashSet<>();
        Set<Tone> tones = new HashSet<>();
        for (PasOutcome outcome : List.of(PasOutcome.NO_DECISION, PasOutcome.APPROVED, PasOutcome.PENDED, PasOutcome.DENIED)) {
            labels.add(outcome.label());
            tones.add(outcome.tone());
        }
        // Four outcomes, four labels, four tones: none of them can be shown as another.
        assertThat(labels).hasSize(4);
        assertThat(tones).containsExactlyInAnyOrder(Tone.NO_ANSWER, Tone.CLEAR, Tone.PENDING, Tone.STOP);
        assertThat(PasOutcome.NO_DECISION.fault()).isFalse();
        assertThat(PasOutcome.NO_DECISION.finalDecision()).isFalse();
        assertThat(PasOutcome.PENDED.finalDecision()).isFalse();
    }

    @Test
    void thePasVocabularyIsExactlyThisListAndAddingToItIsADeliberateAct() {
        assertThat(Arrays.stream(PasOutcome.values()).map(Enum::name)).containsExactly("APPROVED", "APPROVED_WITH_CHANGES",
                "PARTIALLY_APPROVED", "DENIED", "PENDED", "ATTACHMENT_REQUIRED", "CONTACT_PAYER", "CANCELLED", "NO_ACTION_REQUIRED",
                "MIXED", "UNRECOGNISED_CODE", "REQUEST_ERRORS", "NO_DECISION", "MEMBER_NOT_FOUND", "PROVIDER_NOT_FOUND",
                "AUTHORIZATION_NOT_FOUND", "REJECTED", "SERVICE_ERROR", "TIMEOUT", "UNREACHABLE", "MALFORMED_RESPONSE");
        assertThat(Arrays.stream(PasOutcome.values()).filter(PasOutcome::finalDecision)).containsExactly(PasOutcome.APPROVED,
                PasOutcome.APPROVED_WITH_CHANGES, PasOutcome.PARTIALLY_APPROVED, PasOutcome.DENIED);
    }

    @Test
    void notFoundIsAFindingAboutDataAndAFaultIsAFindingAboutTheEnvironment() {
        for (OutcomeCode notFound : List.of(PasOutcome.MEMBER_NOT_FOUND, PasOutcome.PROVIDER_NOT_FOUND,
                PasOutcome.AUTHORIZATION_NOT_FOUND, SubscriptionOutcome.NOT_FOUND, NotificationOutcome.UNMATCHED_NOTIFICATION)) {
            assertThat(notFound.tone()).as(notFound.name()).isEqualTo(Tone.DATA);
            assertThat(notFound.fault()).as(notFound.name()).isFalse();
        }
        for (PasOutcome fault : EnumSet.of(PasOutcome.REJECTED, PasOutcome.SERVICE_ERROR, PasOutcome.TIMEOUT, PasOutcome.UNREACHABLE,
                PasOutcome.MALFORMED_RESPONSE)) {
            assertThat(fault.fault()).as(fault.name()).isTrue();
            assertThat(fault.tone()).as(fault.name()).isEqualTo(Tone.FAULT);
        }
        assertThat(Arrays.stream(PasOutcome.values()).filter(PasOutcome::fault)).hasSize(5);
        assertThat(Arrays.stream(SubscriptionOutcome.values()).filter(SubscriptionOutcome::fault)).hasSize(5);
    }

    @Test
    void theFaultsAndNotFoundOutcomesReadTheSameInEveryProtocolSoHistoryReadsAlike() {
        for (String shared : List.of("REJECTED", "SERVICE_ERROR", "TIMEOUT", "UNREACHABLE", "MALFORMED_RESPONSE")) {
            CrdOutcome crd = CrdOutcome.valueOf(shared);
            assertThat(PasOutcome.valueOf(shared).label()).as(shared).isEqualTo(crd.label());
            assertThat(SubscriptionOutcome.valueOf(shared).label()).as(shared).isEqualTo(crd.label());
            assertThat(PasOutcome.valueOf(shared).tone()).isEqualTo(crd.tone());
            assertThat(SubscriptionOutcome.valueOf(shared).tone()).isEqualTo(crd.tone());
        }
        for (String shared : List.of("MEMBER_NOT_FOUND", "PROVIDER_NOT_FOUND")) {
            assertThat(PasOutcome.valueOf(shared).label()).as(shared).isEqualTo(CrdOutcome.valueOf(shared).label());
        }
    }

    @Test
    void aCodeSharedByTwoOfTheVocabulariesMeansOneThingSoTheServedListCanHoldItOnce() {
        // The scenarios endpoint serves the three vocabularies as one list. A code that appeared in
        // two of them with two labels would make the history list depend on which came first.
        List<OutcomeOption> all = OutcomeOption.listOf(PasOutcome.values(), SubscriptionOutcome.values(), NotificationOutcome.values(),
                RunOutcome.values());
        for (OutcomeOption option : all) {
            assertThat(all.stream().filter(other -> other.code().equals(option.code())).map(OutcomeOption::label).distinct())
                    .as(option.code()).hasSize(1);
            assertThat(all.stream().filter(other -> other.code().equals(option.code())).map(OutcomeOption::tone).distinct())
                    .as(option.code()).hasSize(1);
        }
    }

    @Test
    void everyOutcomeHasALabelAndATone() {
        for (OutcomeCode[] vocabulary : List.of(PasOutcome.values(), SubscriptionOutcome.values(), NotificationOutcome.values())) {
            for (OutcomeCode outcome : vocabulary) {
                assertThat(Texts.present(outcome.label())).as(outcome.name()).isTrue();
                assertThat(outcome.tone()).as(outcome.name()).isNotNull();
            }
        }
    }

    @Test
    void anUnmatchedNotificationAndAnUnreadableOneAreToldApart() {
        assertThat(NotificationOutcome.UNMATCHED_NOTIFICATION.fault()).isFalse();
        assertThat(NotificationOutcome.UNREADABLE_NOTIFICATION.fault()).isTrue();
        assertThat(NotificationOutcome.UNMATCHED_NOTIFICATION.label()).isNotEqualTo(NotificationOutcome.UNREADABLE_NOTIFICATION.label());
    }

    @Test
    void aSubscriptionNobodyHereCreatedAndALedgerThatCouldNotBeAskedAreNeverTheSameOutcome() {
        // The first is a finding about who created a subscription. The second is one about this
        // installation, and reporting it as the first would send a tester looking for another client.
        assertThat(NotificationOutcome.UNMATCHED_NOTIFICATION.tone()).isEqualTo(Tone.DATA);
        assertThat(NotificationOutcome.UNCHECKED_NOTIFICATION.tone()).isEqualTo(Tone.FAULT);
        assertThat(NotificationOutcome.UNCHECKED_NOTIFICATION.fault()).isTrue();
        assertThat(NotificationOutcome.UNCHECKED_NOTIFICATION.label()).isNotEqualTo(NotificationOutcome.UNMATCHED_NOTIFICATION.label())
                .contains("could not be asked");
        assertThat(Arrays.stream(NotificationOutcome.values()).map(Enum::name)).containsExactly("NOTIFICATION_RECEIVED", "HANDSHAKE_RECEIVED",
                "HEARTBEAT_RECEIVED", "STATUS_RECEIVED", "UNRECOGNISED_TYPE", "UNMATCHED_NOTIFICATION", "UNCHECKED_NOTIFICATION",
                "UNREADABLE_NOTIFICATION");
    }
}
