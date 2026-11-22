package org.p32h.interop.epa.pave.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class MemberIdRuleTest {

    // An 11-character placeholder in the S123456789 style, typed as on the card and packed.
    private static final String CARD = "S12345678   01";
    private static final String PACKED = "S1234567801";
    // The FHIR id grammar: what a resource id may hold. A member ID form must never be offered as one.
    private static final Pattern FHIR_ID = Pattern.compile("[A-Za-z0-9\\-.]{1,64}");

    @Test
    void packedRemovesEverySpaceAndNeverHasANote() {
        assertThat(MemberIdRule.PACKED.apply(CARD)).isEqualTo(PACKED);
        assertThat(MemberIdRule.PACKED.apply("S123456789")).isEqualTo("S123456789");
        assertThat(MemberIdRule.PACKED.note(CARD)).isNull();
        assertThat(MemberIdRule.PACKED.note("S1")).isNull();
    }

    @Test
    void spacedWritesNineCharactersThreeSpacesAndTheLastTwo() {
        assertThat(MemberIdRule.SPACED.apply(PACKED)).isEqualTo("S12345678   01");
        assertThat(MemberIdRule.SPACED.apply("S12345678 01")).isEqualTo("S12345678   01");
        assertThat(MemberIdRule.SPACED.apply(CARD)).isEqualTo(CARD);
        assertThat(MemberIdRule.SPACED.note(PACKED)).isNull();
    }

    @Test
    void splitSendsTheFirstNineCharactersOfAnElevenCharacterId() {
        assertThat(MemberIdRule.SPLIT.apply(PACKED)).isEqualTo("S12345678");
        assertThat(MemberIdRule.SPLIT.apply(CARD)).isEqualTo("S12345678");
        assertThat(MemberIdRule.SPLIT.note(CARD)).isNull();
    }

    @Test
    void anIdOfAnotherLengthIsSentPackedWithANoteNamingTheRuleAndTheLength() {
        assertThat(MemberIdRule.SPACED.apply("S1234 56789")).isEqualTo("S123456789");
        assertThat(MemberIdRule.SPLIT.apply("S123456789")).isEqualTo("S123456789");
        assertThat(MemberIdRule.SPACED.note("S1234 56789"))
                .isEqualTo("The spaced rule applies to an 11-character ID; this one has 10, so it was sent packed.");
        assertThat(MemberIdRule.SPLIT.note("S12345678012"))
                .isEqualTo("The split rule applies to an 11-character ID; this one has 12, so it was sent packed.");
    }

    @Test
    void aBlankIdIsSentBlankWithNoNote() {
        for (MemberIdRule rule : MemberIdRule.values()) {
            assertThat(rule.apply(null)).isEmpty();
            assertThat(rule.apply("   ")).isEmpty();
            assertThat(rule.note(null)).isNull();
            assertThat(rule.note("  ")).isNull();
        }
    }

    @Test
    void formsNameTheRuleAndCarryTheNote() {
        MemberIdForms spaced = MemberIds.forms(PACKED, MemberIdRule.SPACED);
        assertThat(spaced.typed()).isEqualTo(PACKED);
        assertThat(spaced.sent()).isEqualTo("S12345678   01");
        assertThat(spaced.changed()).isTrue();
        assertThat(spaced.rule()).isEqualTo("spaced");
        assertThat(spaced.note()).isNull();

        MemberIdForms split = MemberIds.forms("S123456789", MemberIdRule.SPLIT);
        assertThat(split.sent()).isEqualTo("S123456789");
        assertThat(split.changed()).isFalse();
        assertThat(split.rule()).isEqualTo("split");
        assertThat(split.note()).isEqualTo("The split rule applies to an 11-character ID; this one has 10, so it was sent packed.");

        assertThat(MemberIds.sent(CARD, MemberIdRule.SPLIT)).isEqualTo("S12345678");
        assertThat(MemberIds.sent(CARD, null)).isEqualTo(PACKED);
    }

    @Test
    void theFormsBuiltBeforeRulesAreThePackedRule() {
        assertThat(MemberIds.forms(CARD)).isEqualTo(MemberIds.forms(CARD, MemberIdRule.PACKED));
        assertThat(MemberIds.forms(CARD)).isEqualTo(new MemberIdForms(CARD, PACKED, true));
        assertThat(MemberIds.forms(CARD, null).rule()).isEqualTo("packed");
    }

    @Test
    void theWireShapeIsTypedSentChangedRuleAndNoteOnlyWhenThereIsOne() {
        JsonNode applied = JsonSafe.mapper().valueToTree(MemberIds.forms(PACKED, MemberIdRule.SPLIT));
        assertThat(applied.propertyNames()).containsExactly("typed", "sent", "changed", "rule");
        assertThat(applied.get("rule").asString()).isEqualTo("split");

        JsonNode noted = JsonSafe.mapper().valueToTree(MemberIds.forms("S1", MemberIdRule.SPACED));
        assertThat(noted.propertyNames()).containsExactly("typed", "sent", "changed", "rule", "note");
    }

    @Test
    void noFormOfACardIdIsAFhirResourceId() {
        // The spaced form holds spaces, which a resource id cannot: the patient keeps its own id under every rule.
        assertThat(FHIR_ID.matcher(MemberIdRule.SPACED.apply(PACKED)).matches()).isFalse();
        assertThat(Arrays.stream(MemberIdRule.class.getDeclaredMethods()).map(java.lang.reflect.Method::getName))
                .noneMatch(name -> name.toLowerCase(java.util.Locale.ROOT).contains("resource"));
    }

    @Test
    void parseReadsTheNameAndNothingElse() {
        assertThat(MemberIdRule.parse("SPACED")).isEqualTo(MemberIdRule.SPACED);
        assertThat(MemberIdRule.parse(" split ")).isEqualTo(MemberIdRule.SPLIT);
        assertThat(MemberIdRule.parse("packed")).isEqualTo(MemberIdRule.PACKED);
        assertThat(MemberIdRule.parse("PACKD")).isNull();
        assertThat(MemberIdRule.parse("")).isNull();
        assertThat(MemberIdRule.parse(null)).isNull();
    }

    @Test
    void wordsAreTheOptionsTheFormOffers() {
        assertThat(MemberIdRule.PACKED.word()).isEqualTo("packed");
        assertThat(MemberIdRule.PACKED.words()).isEqualTo("packed: spaces removed before sending");
        assertThat(MemberIdRule.SPACED.words()).isEqualTo("spaced: nine characters, three spaces, then the last two, as on the card");
        assertThat(MemberIdRule.SPLIT.words()).isEqualTo("split: the first nine characters of an 11-character ID");
    }

    @Test
    void theFormsNeverPrintTheId() {
        assertThat(MemberIds.forms(PACKED, MemberIdRule.SPACED).toString()).doesNotContain("S12345678").contains("spaced");
    }
}
