package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The rule by which a line of business is matched, on its own: which lines a cell names, and what a requested line stands for. */
class LineMatcherTest {

    private static final LineMatcher NO_ALIASES = new LineMatcher(Map.of());

    @Test
    void aCellNamesEachLineBetweenItsSeparatorsAsWritten() {
        assertThat(LineMatcher.names("Medicare")).containsExactly("Medicare");
        assertThat(LineMatcher.names(" Medicare , Medicaid ")).containsExactly("Medicare", "Medicaid");
        assertThat(LineMatcher.names("Medicare;Medicaid/Commercial|One Care")).containsExactly("Medicare", "Medicaid", "Commercial", "One Care");
        // A name keeps its own spaces, hyphens and case. Only what it is compared by ignores them.
        assertThat(LineMatcher.names("Medicare Advantage, dual-eligible")).containsExactly("Medicare Advantage", "dual-eligible");
        assertThat(LineMatcher.keys("Medicare Advantage, dual-eligible, MEDICARE ADVANTAGE")).containsExactly("medicareadvantage", "dualeligible");
    }

    @Test
    void aCellThatHoldsNoNameNamesNoLine() {
        for (String cell : new String[] {null, "", "   ", "-", "--", " - ", "/", ",", ";;", "|", " , - , _ ", "."}) {
            assertThat(LineMatcher.names(cell)).as("[" + cell + "]").isEmpty();
            assertThat(LineMatcher.keys(cell)).as("[" + cell + "]").isEmpty();
        }
        // An empty part beside a name is not a line either.
        assertThat(LineMatcher.names("Medicare,,-,")).containsExactly("Medicare");
    }

    @Test
    void aRequestedLineStandsForItselfAndIsNeverSplit() {
        assertThat(NO_ALIASES.wanted("Medicare")).containsExactly("medicare");
        assertThat(NO_ALIASES.wanted(" Medi-Care ")).containsExactly("medicare");
        // One name, slash and all. It matches a row only if an alias says what the list calls it.
        assertThat(NO_ALIASES.wanted("Medicare/Medicaid")).containsExactly("medicare/medicaid");
        assertThat(NO_ALIASES.wanted("Medicaid, CHIP")).containsExactly("medicaid,chip");
        for (String nothing : new String[] {null, "", "  ", "-", "_."}) {
            assertThat(NO_ALIASES.wanted(nothing)).as("[" + nothing + "]").isEmpty();
        }
    }

    @Test
    void anAliasAddsTheNamesTheListUsesAndRunsOneWayOnly() {
        LineMatcher matcher = new LineMatcher(Map.of("MCR", List.of("Medicare", "Medicare Advantage"), "Dual Eligible", List.of("Medicare/Medicaid")));
        assertThat(matcher.wanted("mcr")).containsExactly("mcr", "medicare", "medicareadvantage");
        // The alias is found whatever the case and spacing of the name the member API sends.
        assertThat(matcher.wanted("DUAL-ELIGIBLE")).containsExactly("dualeligible", "medicare", "medicaid");
        // The list's own name is not turned back into the member API's.
        assertThat(matcher.wanted("Medicare")).containsExactly("medicare");
        // An alias is not followed a second time: "Medicare Advantage" is a list name here, not a key.
        LineMatcher chained = new LineMatcher(Map.of("A", List.of("B"), "B", List.of("C")));
        assertThat(chained.wanted("A")).containsExactly("a", "b");
    }

    @Test
    void anAliasThatNamesNothingChangesNothing() {
        java.util.Map<String, List<String>> odd = new java.util.HashMap<>();
        odd.put("-", List.of("Medicare"));
        odd.put("MCR", List.of("-", " "));
        odd.put("Empty", null);
        LineMatcher matcher = new LineMatcher(odd);
        assertThat(matcher.wanted("-")).isEmpty();
        assertThat(matcher.wanted("MCR")).containsExactly("mcr");
        assertThat(matcher.wanted("Empty")).containsExactly("empty");
        assertThat(new LineMatcher(null).wanted("Medicare")).containsExactly("medicare");
    }
}
