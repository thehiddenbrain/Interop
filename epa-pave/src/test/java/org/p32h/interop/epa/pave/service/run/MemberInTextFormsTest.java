package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.MemberDetail;

/**
 * Ordinary ways of writing what the request carries, found by the independent check of the member
 * data fixes (N3): the date of birth with the month's name, the member ID in fullwidth characters,
 * and the member ID's digits without the letter in front of them.
 */
class MemberInTextFormsTest {

    private static final MemberDetail MEMBER = MemberDetail.builder().memberId("S123456789").firstName("Zzfirstname")
            .lastName("Zzlastname-Placeholder").dateOfBirth("1961-07-23").build();

    private static List<String> kinds(String text) {
        return MemberInText.kinds(text, MEMBER);
    }

    @Test
    void theDateOfBirthWrittenWithTheMonthsNameIsFound() {
        for (String note : List.of("born July 23, 1961", "born 23 Jul 1961", "born July 23 1961", "born 23-July-61", "dob 23Jul1961",
                "born Jul-23-1961", "born JULY 23RD, 1961", "born the 23rd of July 1961", "born jul 23 61")) {
            assertThat(kinds(note)).as(note).containsExactly("the member's date of birth");
        }
    }

    @Test
    void anotherDateWithTheSameMonthsNameOrTheSameDayIsNotTakenForIt() {
        for (String note : List.of("seen July 24, 1961", "seen 23 Jun 1961", "seen 23 Jul 1962", "July 23, 2026 retest", "sent 23 Jul",
                "ticket 123 Jul 1961")) {
            assertThat(kinds(note)).as(note).isEmpty();
        }
    }

    @Test
    void theMemberIdInFullwidthCharactersOrItsDigitsWithoutTheLetterAreFound() {
        for (String note : List.of("S１２３４５６７８９", "Ｓ123456789", "123456789",
                "member 123-456-789", "id 12345 6789 called")) {
            assertThat(kinds(note)).as(note).containsExactly("this run's member ID");
        }
        // Fewer than six digits of it say nothing about who the member is.
        assertThat(kinds("ticket 56789 and 6789")).isEmpty();
        assertThat(MemberInText.digitRun("S123456789")).isEqualTo("123456789");
        assertThat(MemberInText.digitRun("S12345")).isNull();
        assertThat(MemberInText.digitRun("123456789")).isNull();
    }

    @Test
    void aNoteOnTheRunFormIsRefusedForEachOfTheseForms() {
        Map<String, String> refused = MemberInText.problems("born July 23, 1961", "QA Ｓ１２３４５６７８"
                + "９", "123456789: covered=covered", MEMBER, "Nothing was sent.");
        assertThat(refused).containsOnlyKeys("note", "testerRef", "expected");
        assertThat(refused.get("note")).isEqualTo("The note holds the member's date of birth. The ledger keeps it as written and may not "
                + "hold member data, so leave it out. Nothing was sent.");
        assertThat(String.join(" ", refused.values())).doesNotContain("1961").doesNotContain("123456789");
    }

    @Test
    void aNoteThatOnlyALedgerTokenCanRecogniseIsFoundInFullwidthAndAsItsDigits() {
        // The ledger compares tokens; the identifier itself stands in for one here.
        java.util.function.Predicate<String> isTheId = "S123456789"::equals;
        assertThat(MemberInText.holdsId("member S１２３４５６７８９", "6789", isTheId)).isTrue();
        assertThat(MemberInText.holdsId("member 123456789 called", "6789", isTheId)).isTrue();
        assertThat(MemberInText.holdsId("member 123 456 789 called", "6789", isTheId)).isTrue();
        assertThat(MemberInText.holdsId("ticket 4471, 6789", "6789", isTheId)).isFalse();
        assertThat(MemberInText.holdsId("member 223456789 called", "6789", isTheId)).isFalse();
    }
}
