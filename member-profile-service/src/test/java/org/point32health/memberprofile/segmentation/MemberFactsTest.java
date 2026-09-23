package org.point32health.memberprofile.segmentation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * {@link MemberFacts}: the member attributes normalized once per request. Text is trimmed and upper-cased,
 * booleans accept the spellings of the source systems, numbers come from JSON numbers or numeric strings,
 * and an absent or null fact reads as null for every accessor.
 */
class MemberFactsTest {

    /** A map that, unlike {@link Map#of}, accepts null values. */
    private static Map<String, Object> map(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    @Nested
    class Construction {

        @Test
        void nullAttributesGiveEmptyFacts() {
            MemberFacts facts = MemberFacts.of(null);
            assertThat(facts.raw()).isEmpty();
            assertThat(facts.text("sourceSystemId")).isNull();
            assertThat(facts.raw("sourceSystemId")).isNull();
            assertThat(facts.bool("sourceSystemId")).isNull();
            assertThat(facts.number("sourceSystemId")).isNull();
        }

        @Test
        void emptyAttributesGiveEmptyFacts() {
            MemberFacts facts = MemberFacts.of(Map.of());
            assertThat(facts.raw()).isEmpty();
            assertThat(facts.text("anything")).isNull();
            assertThat(facts.bool("anything")).isNull();
            assertThat(facts.number("anything")).isNull();
        }

        @Test
        void rawKeepsTheOriginalAttributesUntouched() {
            Map<String, Object> input = map("sourceSystemId", 2001, "planCode", " std saver ", "coverageActive", Boolean.TRUE);
            MemberFacts facts = MemberFacts.of(input);
            assertThat(facts.raw()).containsExactlyInAnyOrderEntriesOf(input);
            assertThat(facts.raw("sourceSystemId")).isSameAs(input.get("sourceSystemId"));
            assertThat(facts.raw("planCode")).isEqualTo(" std saver ");   // not normalized: the explain trace shows what MemberDomain sent
            assertThat(facts.raw("coverageActive")).isEqualTo(Boolean.TRUE);
        }

        @Test
        void nullValuedAttributeIsPresentInRawButAbsentForEveryTypedAccessor() {
            MemberFacts facts = MemberFacts.of(map("planCode", null));
            assertThat(facts.raw()).containsKey("planCode");
            assertThat(facts.raw("planCode")).isNull();
            assertThat(facts.text("planCode")).isNull();
            assertThat(facts.bool("planCode")).isNull();
            assertThat(facts.number("planCode")).isNull();
        }

        @Test
        void fieldNamesAreLookedUpExactly() {
            MemberFacts facts = MemberFacts.of(map("sourceSystemId", 2001));
            assertThat(facts.text("sourceSystemId")).isEqualTo("2001");
            assertThat(facts.text("SOURCESYSTEMID")).isNull();
            assertThat(facts.text("sourcesystemid")).isNull();
            assertThat(facts.text(" sourceSystemId")).isNull();
        }

        @Test
        void factsAreIndependentOfEachOther() {
            MemberFacts facts = MemberFacts.of(map("a", "x", "b", "y"));
            assertThat(facts.text("a")).isEqualTo("X");
            assertThat(facts.text("b")).isEqualTo("Y");
            assertThat(facts.text("c")).isNull();
        }
    }

    @Nested
    class Text {

        static Stream<Arguments> normalizedText() {
            return Stream.of(
                    arguments("thppma", "THPPMA"),
                    arguments("THPPMA", "THPPMA"),
                    arguments("ThPpMa", "THPPMA"),
                    arguments("  thppma", "THPPMA"),
                    arguments("thppma   ", "THPPMA"),
                    arguments("\t thppma \n", "THPPMA"),
                    arguments("Std Saver", "STD SAVER"),          // inner whitespace is kept
                    arguments("  std  saver  ", "STD  SAVER"),
                    arguments("nh_39_week", "NH_39_WEEK"),
                    arguments("10eg1234", "10EG1234"),
                    arguments("", ""),
                    arguments("   ", ""));
        }

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @MethodSource("normalizedText")
        void textIsTrimmedAndUpperCased(String value, String expected) {
            assertThat(MemberFacts.of(map("f", value)).text("f")).isEqualTo(expected);
        }

        static Stream<Arguments> nonStringValues() {
            return Stream.of(
                    arguments(2001, "2001"),
                    arguments(2001L, "2001"),
                    arguments((short) 7, "7"),
                    arguments(2.5, "2.5"),
                    arguments(new BigDecimal("18.50"), "18.50"),
                    arguments(Boolean.TRUE, "TRUE"),
                    arguments(Boolean.FALSE, "FALSE"),
                    arguments('x', "X"));
        }

        @ParameterizedTest(name = "{0} -> \"{1}\"")
        @MethodSource("nonStringValues")
        void nonStringValuesUseTheirStringForm(Object value, String expected) {
            assertThat(MemberFacts.of(map("f", value)).text("f")).isEqualTo(expected);
        }

        static Stream<Arguments> normalizeInputs() {
            return Stream.of(
                    arguments(" sco ", "SCO"),
                    arguments("pdp", "PDP"),
                    arguments("STD SAVER", "STD SAVER"),
                    arguments("  Mixed Case Value  ", "MIXED CASE VALUE"),
                    arguments("", ""));
        }

        @ParameterizedTest(name = "normalize(\"{0}\") = \"{1}\"")
        @MethodSource("normalizeInputs")
        void normalizeIsTheSharedNormalizationUsedForRuleValuesToo(String value, String expected) {
            assertThat(MemberFacts.normalize(value)).isEqualTo(expected);
        }
    }

    @Nested
    class Booleans {

        static Stream<Arguments> truthy() {
            return Stream.of(
                    arguments(Boolean.TRUE), arguments("true"), arguments("TRUE"), arguments("True"), arguments("tRuE"),
                    arguments("Y"), arguments("y"), arguments("YES"), arguments("yes"), arguments("Yes"),
                    arguments("1"), arguments(1), arguments(1L),
                    arguments(" true "), arguments("  y"), arguments("yes  "), arguments("\t1\n"));
        }

        static Stream<Arguments> falsy() {
            return Stream.of(
                    arguments(Boolean.FALSE), arguments("false"), arguments("FALSE"), arguments("False"), arguments("fAlSe"),
                    arguments("N"), arguments("n"), arguments("NO"), arguments("no"), arguments("No"),
                    arguments("0"), arguments(0), arguments(0L),
                    arguments(" false "), arguments("  n"), arguments("no  "), arguments("\t0\n"));
        }

        static Stream<Arguments> notBoolean() {
            return Stream.of(
                    arguments("maybe"), arguments(""), arguments("   "), arguments(2), arguments("2"), arguments(-1),
                    arguments("T"), arguments("F"), arguments("on"), arguments("off"), arguments("yes please"),
                    arguments("truee"), arguments("null"), arguments(10), arguments("01"));
        }

        @ParameterizedTest(name = "{0} is TRUE")
        @MethodSource("truthy")
        void acceptedTrueSpellings(Object value) {
            assertThat(MemberFacts.of(map("f", value)).bool("f")).isEqualTo(Boolean.TRUE);
        }

        @ParameterizedTest(name = "{0} is FALSE")
        @MethodSource("falsy")
        void acceptedFalseSpellings(Object value) {
            assertThat(MemberFacts.of(map("f", value)).bool("f")).isEqualTo(Boolean.FALSE);
        }

        @ParameterizedTest(name = "{0} is not a boolean")
        @MethodSource("notBoolean")
        void rejectedSpellingsAreNull(Object value) {
            assertThat(MemberFacts.of(map("f", value)).bool("f")).isNull();
        }

        @Test
        void absentAndNullFactsAreNull() {
            assertThat(MemberFacts.of(Map.of()).bool("f")).isNull();
            assertThat(MemberFacts.of(map("f", null)).bool("f")).isNull();
        }

        @Test
        void jsonBooleanIsReturnedAsIs() {
            assertThat(MemberFacts.of(map("f", Boolean.TRUE)).bool("f")).isSameAs(Boolean.TRUE);
            assertThat(MemberFacts.of(map("f", Boolean.FALSE)).bool("f")).isSameAs(Boolean.FALSE);
        }
    }

    @Nested
    class Numbers {

        static Stream<Arguments> numeric() {
            return Stream.of(
                    arguments(5, "5"),
                    arguments(0, "0"),
                    arguments(-3, "-3"),
                    arguments(9_000_000_000L, "9000000000"),
                    arguments((short) 12, "12"),
                    arguments((byte) 3, "3"),
                    arguments(2.5, "2.5"),
                    arguments(1.5f, "1.5"),
                    arguments(new BigInteger("123456789012345678901234567890"), "123456789012345678901234567890"),
                    arguments(new BigDecimal("18.01"), "18.01"),
                    arguments("12", "12"),
                    arguments(" 12 ", "12"),
                    arguments("\t42\n", "42"),
                    arguments("-3.75", "-3.75"),
                    arguments("0012", "12"),
                    arguments("+7", "7"),
                    arguments("1e3", "1000"),
                    arguments("1E-2", "0.01"),
                    arguments(".5", "0.5"));
        }

        @ParameterizedTest(name = "{0} = {1}")
        @MethodSource("numeric")
        void numericValuesCompareAsBigDecimal(Object value, String expected) {
            assertThat(MemberFacts.of(map("f", value)).number("f")).isEqualByComparingTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "", "   ", "1,000", "12abc", "abc12", "1 2", "0x10", "true", "NaN", "Infinity", "$5", "1_000"})
        void nonNumericStringsAreNull(String value) {
            assertThat(MemberFacts.of(map("f", value)).number("f")).isNull();
        }

        @Test
        void booleanFactsAreNotNumbers() {
            assertThat(MemberFacts.of(map("f", Boolean.TRUE)).number("f")).isNull();
            assertThat(MemberFacts.of(map("f", Boolean.FALSE)).number("f")).isNull();
        }

        @Test
        void absentAndNullFactsAreNull() {
            assertThat(MemberFacts.of(Map.of()).number("f")).isNull();
            assertThat(MemberFacts.of(map("f", null)).number("f")).isNull();
        }

        @Test
        void bigDecimalFactIsReturnedWithoutConversion() {
            BigDecimal value = new BigDecimal("18.50");
            assertThat(MemberFacts.of(map("f", value)).number("f")).isSameAs(value);
        }

        @Test
        void doubleFactsKeepTheirPrecision() {
            assertThat(MemberFacts.of(map("f", 0.1)).number("f")).isEqualByComparingTo("0.1");
            assertThat(MemberFacts.of(map("f", 18.0)).number("f")).isEqualByComparingTo("18");
        }

        static Stream<Arguments> parseNumberInputs() {
            return Stream.of(
                    arguments("18", "18"),
                    arguments(" 18.5 ", "18.5"),
                    arguments("-2", "-2"),
                    arguments("1e2", "100"),
                    arguments("007", "7"));
        }

        @ParameterizedTest(name = "parseNumber(\"{0}\") = {1}")
        @MethodSource("parseNumberInputs")
        void parseNumberIsTheSharedNumericParsingUsedForRuleValuesToo(String value, String expected) {
            assertThat(MemberFacts.parseNumber(value)).isEqualByComparingTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"abc", "", " ", "1,000", "12abc", "0x10", "NaN"})
        void parseNumberReturnsNullInsteadOfThrowing(String value) {
            assertThat(MemberFacts.parseNumber(value)).isNull();
        }
    }

    @Nested
    class MixedAccess {

        @Test
        void theSameFactCanBeReadAsTextBooleanAndNumber() {
            MemberFacts facts = MemberFacts.of(map("flag", "1"));
            assertThat(facts.text("flag")).isEqualTo("1");
            assertThat(facts.bool("flag")).isEqualTo(Boolean.TRUE);
            assertThat(facts.number("flag")).isEqualByComparingTo("1");
        }

        @Test
        void aRealisticMemberDomainAttributeMapNormalizesEveryEntry() {
            Map<String, Object> attributes = new HashMap<>();
            attributes.put("sourceSystemId", 2001);
            attributes.put("planTypeCode", " mr ");
            attributes.put("planCode", "10eg1234");
            attributes.put("coverageActive", true);
            attributes.put("hasActivePdp", "N");
            attributes.put("product", null);

            MemberFacts facts = MemberFacts.of(attributes);

            assertThat(facts.text("sourceSystemId")).isEqualTo("2001");
            assertThat(facts.number("sourceSystemId")).isEqualByComparingTo("2001");
            assertThat(facts.text("planTypeCode")).isEqualTo("MR");
            assertThat(facts.text("planCode")).isEqualTo("10EG1234");
            assertThat(facts.bool("coverageActive")).isEqualTo(Boolean.TRUE);
            assertThat(facts.text("coverageActive")).isEqualTo("TRUE");
            assertThat(facts.bool("hasActivePdp")).isEqualTo(Boolean.FALSE);
            assertThat(facts.text("product")).isNull();
            assertThat(facts.raw()).hasSize(6);
        }
    }
}
