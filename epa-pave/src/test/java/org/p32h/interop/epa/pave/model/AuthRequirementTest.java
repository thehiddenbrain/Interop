package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The spellings of the authorization flag. How the real code list writes it is not known, so the
 * reader takes the spellings a list is likely to use, and takes them in pairs: a list written
 * "Prior Auth" and "No Prior Auth" must not have only one of its two answers read.
 */
class AuthRequirementTest {

    /** Everything read as a yes before the list of spellings grew. None of it may stop being read. */
    private static final String[] YES_BEFORE = {"required", "yes", "y", "true", "t", "1", "pa", "pa required", "auth required",
            "authorization required", "prior auth required", "prior authorization required", "needed", "auth needed"};

    /** Everything read as a no before the list of spellings grew. */
    private static final String[] NO_BEFORE = {"not required", "no", "n", "false", "f", "0", "no pa", "pa not required", "no auth",
            "auth not required", "authorization not required", "no prior auth", "prior auth not required",
            "prior authorization not required", "not needed", "none"};

    /** The five ways the lists name the thing itself. */
    private static final String[] NOUNS = {"pa", "auth", "authorization", "prior auth", "prior authorization"};

    private static void assertReads(AuthRequirement expected, String... cells) {
        for (String cell : cells) {
            assertThat(AuthRequirement.parse(cell)).as("[" + cell + "]").isEqualTo(expected);
            assertThat(AuthRequirement.recognises(cell)).as("[" + cell + "] is recognised").isTrue();
        }
    }

    private static void assertNotRead(String... cells) {
        for (String cell : cells) {
            assertThat(AuthRequirement.parse(cell)).as("[" + cell + "]").isEqualTo(AuthRequirement.UNKNOWN);
            assertThat(AuthRequirement.recognises(cell)).as("[" + cell + "] is not recognised").isFalse();
        }
    }

    @Test
    void everythingThatWasReadBeforeIsStillRead() {
        assertReads(AuthRequirement.REQUIRED, YES_BEFORE);
        assertReads(AuthRequirement.NOT_REQUIRED, NO_BEFORE);
        // In any case, and with underscores or hyphens where the spaces are.
        assertReads(AuthRequirement.REQUIRED, "YES", "Pa Required", "PRIOR_AUTH_REQUIRED", "prior-authorization-required", " y ");
        assertReads(AuthRequirement.NOT_REQUIRED, "NO", "Not Required", "NOT_REQUIRED", "no-prior-auth", " none ");
    }

    @Test
    void theSpellingsARealListIsLikelyToUseAreRead() {
        assertReads(AuthRequirement.REQUIRED, "prior auth", "prior authorization", "auth", "authorization", "requires pa", "requires auth",
                "requires prior auth", "requires prior authorization", "pa needed");
        assertReads(AuthRequirement.NOT_REQUIRED, "no pa required", "no auth required", "no authorization required", "no prior auth required",
                "no prior authorization required", "pa not needed");
        // As a spreadsheet column would hold them.
        assertReads(AuthRequirement.REQUIRED, "Prior Auth", "Prior Authorization", "Auth", "Requires PA", "PA Needed");
        assertReads(AuthRequirement.NOT_REQUIRED, "No PA Required", "No Prior Auth Required", "No Authorization Required", "PA Not Needed");
    }

    @Test
    void aListThatWritesTheThingAndNoThingHasBothOfItsAnswersRead() {
        // The failure this guards against: "No Prior Auth" was read and "Prior Auth" was not, so
        // such a list had every one of its no rows read and none of its yes rows.
        for (String noun : NOUNS) {
            assertReads(AuthRequirement.REQUIRED, noun);
            assertReads(AuthRequirement.NOT_REQUIRED, "no " + noun);
        }
    }

    @Test
    void requiredAndItsTwoNegationsAreReadForEveryWayOfNamingTheThing() {
        for (String noun : NOUNS) {
            assertReads(AuthRequirement.REQUIRED, noun + " required");
            assertReads(AuthRequirement.NOT_REQUIRED, noun + " not required", "no " + noun + " required");
        }
        assertReads(AuthRequirement.REQUIRED, "required");
        assertReads(AuthRequirement.NOT_REQUIRED, "not required");
    }

    @Test
    void neededIsReadWithItsNegationWhereverOneOfTheTwoIsRead() {
        assertReads(AuthRequirement.REQUIRED, "needed", "pa needed", "auth needed");
        assertReads(AuthRequirement.NOT_REQUIRED, "not needed", "pa not needed", "auth not needed");
    }

    @Test
    void nothingElseWasAddedSoAnythingElseIsStillUnknownAndNeverAGuess() {
        // Near the spellings that are read, and not among them. Each stays unknown, is listed by
        // the self-check, and waits for somebody to decide what it means.
        assertNotRead("requires authorization", "does not require pa", "not requires pa", "no pa needed", "no auth needed",
                "authorization needed", "prior auth needed", "prior authorization not needed", "no required", "pa?", "yes*", "y/n",
                "optional", "sometimes", "see policy", "maybe", "2", "-", "n/a", "tbd", "not", "no no");
        // A list that writes "unknown" has said so itself: unknown, and recognised as that.
        assertThat(AuthRequirement.parse("unknown")).isEqualTo(AuthRequirement.UNKNOWN);
        assertThat(AuthRequirement.recognises("unknown")).isTrue();
        for (String blank : new String[] {null, "", "   ", "\t"}) {
            assertThat(AuthRequirement.parse(blank)).isEqualTo(AuthRequirement.UNKNOWN);
            assertThat(AuthRequirement.recognises(blank)).isFalse();
        }
    }

    @Test
    void theSpacesASpreadsheetWritesAreSpacesWhereverTheyStand() {
        // A no-break space, a figure space and a narrow no-break space. On screen each of these
        // cells reads exactly like the plain one.
        for (String space : new String[] {"\u00A0", "\u2007", "\u202F"}) {
            String name = "U+" + Integer.toHexString(space.charAt(0)).toUpperCase(java.util.Locale.ROOT);
            // Inside the value.
            assertThat(AuthRequirement.parse("Not" + space + "Required")).as(name).isEqualTo(AuthRequirement.NOT_REQUIRED);
            assertThat(AuthRequirement.parse("PA" + space + "Required")).as(name).isEqualTo(AuthRequirement.REQUIRED);
            assertThat(AuthRequirement.parse("No" + space + "Prior" + space + "Auth")).as(name).isEqualTo(AuthRequirement.NOT_REQUIRED);
            // At the ends of the cell, alone and beside ordinary spaces.
            assertThat(AuthRequirement.parse(space + "Yes" + space)).as(name).isEqualTo(AuthRequirement.REQUIRED);
            assertThat(AuthRequirement.parse(" " + space + " no " + space)).as(name).isEqualTo(AuthRequirement.NOT_REQUIRED);
            // Several in a row, and mixed with a space, a tab, an underscore or a hyphen, are one gap.
            assertThat(AuthRequirement.parse("prior" + space + space + "auth " + space + "_-\trequired")).as(name).isEqualTo(AuthRequirement.REQUIRED);
            // The same cells are recognised, so the self-check no longer lists them as unread.
            assertThat(AuthRequirement.recognises("Not" + space + "Required")).as(name).isTrue();
            assertThat(AuthRequirement.recognises(space + "unknown" + space)).as(name).isTrue();
            // A cell that holds nothing but such a space holds nothing.
            assertThat(AuthRequirement.parse(space)).as(name).isEqualTo(AuthRequirement.UNKNOWN);
            assertThat(AuthRequirement.recognises(space + " " + space)).as(name).isFalse();
        }
        // The three together.
        assertThat(AuthRequirement.parse("\u00A0not\u2007\u202Frequired\u00A0")).isEqualTo(AuthRequirement.NOT_REQUIRED);
    }

    @Test
    void aCharacterThatIsNotASpaceStillKeepsACellFromBeingRead() {
        // A zero width space and a byte order mark are not spaces. Nothing is guessed about a cell
        // that holds one inside it: it stays unknown and the self-check shows the character.
        assertNotRead("Not\u200BRequired", "No\uFEFFPA", "Not.Required", "Not/Required");
    }
}
