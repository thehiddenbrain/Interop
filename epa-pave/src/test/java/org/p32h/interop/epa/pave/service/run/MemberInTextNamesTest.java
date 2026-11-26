package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.PostalAddress;

/**
 * Names and the address in free text, found by the independent check of the member data fixes.
 * N6: a member whose name is an ordinary word had honest notes refused. N5: one part of a
 * hyphenated surname, and the city with the postal code, were not recognised.
 */
class MemberInTextNamesTest {

    private static List<String> kinds(String text, MemberDetail member) {
        return MemberInText.kinds(text, member);
    }

    @Test
    void aShortNameThatIsAnOrdinaryWordIsLookedForOnlyBesideTheOtherName() {
        MemberDetail mayAn = MemberDetail.builder().memberId("S123456789").firstName("May").lastName("An").build();
        // The checker's note.
        assertThat(kinds("Retried after an outage; may need a second look", mayAn)).isEmpty();
        assertThat(kinds("do not close, all good", MemberDetail.builder().firstName("Al").lastName("Do").build())).isEmpty();
        assertThat(kinds("called May An about it", mayAn)).containsExactly("the member's first name", "the member's last name");
        assertThat(kinds("called An, May", mayAn)).containsExactly("the member's first name", "the member's last name");
        // A name of four letters or more is still looked for alone.
        MemberDetail mark = MemberDetail.builder().firstName("Mark").lastName("Zzlastname").build();
        assertThat(kinds("mark the run for review", mark)).containsExactly("the member's first name");
        assertThat(kinds("spoke to zzlastname", mark)).containsExactly("the member's last name");
    }

    @Test
    void onePartOfAHyphenatedOrTwoWordSurnameIsFoundWhenItHasFourLettersOrMore() {
        MemberDetail hyphenated = MemberDetail.builder().firstName("Zzfirstname").lastName("Zzlastname-Placeholder").build();
        assertThat(kinds("Zzlastname", hyphenated)).containsExactly("the member's last name");
        assertThat(kinds("placeholder again", hyphenated)).containsExactly("the member's last name");
        MemberDetail twoWords = MemberDetail.builder().lastName("De Zzlastname").build();
        assertThat(kinds("for zzlastname", twoWords)).containsExactly("the member's last name");
        // A part shorter than four letters says nothing alone.
        assertThat(kinds("de facto", twoWords)).isEmpty();
    }

    @Test
    void anExpectationThatIsExactlyOneOfTheToolsOutcomeCodesIsNeverRefusedAsAName() {
        MemberDetail timeout = MemberDetail.builder().memberId("P32H-TIMEOUT").firstName("Lane").lastName("Placeholder-Timeout").build();
        MemberDetail inactive = MemberDetail.builder().memberId("P32H-INACTIVE").firstName("Gray").lastName("Placeholder-Inactive").build();
        // The words alone are the surname, as before.
        assertThat(kinds("TIMEOUT", timeout)).containsExactly("the member's last name");
        assertThat(kinds("COVERAGE_INACTIVE", inactive)).containsExactly("the member's last name");
        // As the expectation, exactly a code of this tool: not member data.
        assertThat(MemberInText.problems(null, null, "TIMEOUT", timeout, "Nothing was sent.")).isEmpty();
        assertThat(MemberInText.problems(null, null, " COVERAGE_INACTIVE ", inactive, "Nothing was sent.")).isEmpty();
        assertThat(MemberInText.problemsInBody(null, null, "TIMEOUT", List.of(timeout), "Nothing was sent.")).isEmpty();
        // Spelt as the comparison of an expectation still reads it as the code: in lower case, or with a space for the underscore.
        assertThat(MemberInText.problems(null, null, "timeout", timeout, "Nothing was sent.")).isEmpty();
        assertThat(MemberInText.problems(null, null, "Timeout", timeout, "Nothing was sent.")).isEmpty();
        assertThat(MemberInText.problems(null, null, "coverage_inactive", inactive, "Nothing was sent.")).isEmpty();
        assertThat(MemberInText.problems(null, null, "Coverage inactive", inactive, "Nothing was sent.")).isEmpty();
        assertThat(MemberInText.problemsInBody(null, null, "timeout", List.of(timeout), "Nothing was sent.")).isEmpty();
        // Anything else is checked as before: a word that is no code, a code with more beside it, and the other fields.
        assertThat(MemberInText.problems(null, null, "Inactive", inactive, "Nothing was sent.")).containsKey("expected");
        assertThat(MemberInText.problems(null, null, "inactive coverage", inactive, "Nothing was sent.")).containsKey("expected");
        assertThat(MemberInText.problems(null, null, "TIMEOUT; Timeout", timeout, "Nothing was sent.")).containsKey("expected");
        assertThat(MemberInText.problems("TIMEOUT", "TIMEOUT", "TIMEOUT", timeout, "Nothing was sent.")).containsOnlyKeys("note", "testerRef");
        assertThat(MemberInText.problems(null, null, "AUTH_REQUIRED", timeout, "Nothing was sent.")).isEmpty();
    }

    @Test
    void theCityWithThePostalCodeIsTheAddressAndEitherAloneIsNot() {
        MemberDetail member = MemberDetail.builder().memberId("S123456789")
                .address(new PostalAddress("1 Placeholder Street", null, "Zzcityville", "MA", "00077-1234")).build();
        assertThat(kinds("Zzcityville 00077", member)).containsExactly("the member's address");
        assertThat(kinds("lives in zzcityville, MA 00077", member)).containsExactly("the member's address");
        assertThat(kinds("Zzcityville office", member)).isEmpty();
        assertThat(kinds("code 00077 retried", member)).isEmpty();
    }
}
