package com.thehiddenbrain.interop.extract.transform;

import com.thehiddenbrain.interop.extract.transform.rules.ArithmeticRule;
import com.thehiddenbrain.interop.extract.transform.rules.CleanTextRule;
import com.thehiddenbrain.interop.extract.transform.rules.CoalesceRule;
import com.thehiddenbrain.interop.extract.transform.rules.ConcatRule;
import com.thehiddenbrain.interop.extract.transform.rules.ConstantRule;
import com.thehiddenbrain.interop.extract.transform.rules.DateMathRule;
import com.thehiddenbrain.interop.extract.transform.rules.FormatDateRule;
import com.thehiddenbrain.interop.extract.transform.rules.FormatNumberRule;
import com.thehiddenbrain.interop.extract.transform.rules.MapValuesRule;
import com.thehiddenbrain.interop.extract.transform.rules.MaskRule;
import com.thehiddenbrain.interop.extract.transform.rules.SequenceRule;
import com.thehiddenbrain.interop.extract.transform.rules.SubstringRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The closed rule set is the heart of the product: every transformation an analyst can pick must behave
 * exactly as its help text promises. These tests pin those promises without Spring.
 */
class RulesTest {

    private static RuleContext ctx() {
        RuleContext c = new RuleContext();
        c.runDate = LocalDate.of(2026, 9, 19);
        c.businessDate = LocalDate.of(2026, 9, 18);
        c.vendorCode = "ACMEDENTAL";
        c.fileSeq = 7;
        c.versionNo = 2;
        c.currentFieldId = "f1";
        return c;
    }

    private static List<Object> in(Object... values) { return Arrays.asList(values); }

    private static Map<String, Object> p(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("FORMAT_DATE renders dates in the vendor's pattern and parses text with an input pattern")
    void formatDate() {
        FormatDateRule r = new FormatDateRule();
        assertEquals("19850307", r.apply(in(LocalDate.of(1985, 3, 7)), p("pattern", "yyyyMMdd"), ctx()));
        assertEquals("03/07/1985", r.apply(in(LocalDate.of(1985, 3, 7)), p("pattern", "MM/dd/yyyy"), ctx()));
        assertEquals("07.03.1985", r.apply(in(LocalDate.of(1985, 3, 7)), p("pattern", "CUSTOM", "customPattern", "dd.MM.yyyy"), ctx()));
        assertEquals("1985-03-07", r.apply(in("03071985"), p("pattern", "yyyy-MM-dd", "inputPattern", "MMddyyyy"), ctx()));
        assertNull(r.apply(in((Object) null), p("pattern", "yyyyMMdd"), ctx()));
    }

    @Test
    @DisplayName("FORMAT_DATE on garbage warns and blanks, or passes through when asked")
    void formatDateInvalid() {
        FormatDateRule r = new FormatDateRule();
        RuleContext c = ctx();
        assertNull(r.apply(in("not a date"), p("pattern", "yyyyMMdd"), c));
        assertEquals(1, c.warningCounts().get("f1|INVALID_DATE"));
        assertEquals("not a date", r.apply(in("not a date"), p("pattern", "yyyyMMdd", "onInvalid", "PASSTHROUGH"), ctx()));
    }

    @Test
    @DisplayName("CONCAT joins inputs with a separator or a template, dropping blanks cleanly")
    void concat() {
        ConcatRule r = new ConcatRule();
        assertEquals("Rivera Jordan", r.apply(in("Rivera", "Jordan"), p("separator", " "), ctx()));
        assertEquals("Rivera", r.apply(in("Rivera", ""), p("separator", " ", "skipBlank", true), ctx()));
        assertEquals("Rivera, Jordan", r.apply(in("Rivera", "Jordan"), p("template", "{1}, {2}"), ctx()));
        assertEquals("Rivera", r.apply(in("Rivera", null), p("template", "{1}, {2}"), ctx()));
        assertEquals("ACM1001", r.apply(in("1001"), p("template", "ACM{1}"), ctx()));
        assertEquals("", r.apply(in(null, null), p("template", "{1}, {2}"), ctx()));
    }

    @Test
    @DisplayName("MAP_VALUES translates codes case-insensitively, falls back to the default, or passes through")
    void mapValues() {
        MapValuesRule r = new MapValuesRule();
        Map<String, Object> map = p("M", "1", "F", "2");
        assertEquals("1", r.apply(in("m"), p("map", map, "defaultValue", "0"), ctx()));
        assertEquals("2", r.apply(in("F"), p("map", map, "defaultValue", "0"), ctx()));
        assertEquals("0", r.apply(in("U"), p("map", map, "defaultValue", "0"), ctx()));
        assertNull(r.apply(in("U"), p("map", map), ctx()));
        assertEquals("U", r.apply(in("U"), p("map", map, "passthroughUnmapped", true), ctx()));
        assertNull(r.apply(in("m"), p("map", map, "caseInsensitive", false), ctx()));
    }

    @Test
    @DisplayName("MAP_VALUES ranges bucket numbers: from is inclusive, to is exclusive")
    void mapRanges() {
        MapValuesRule r = new MapValuesRule();
        List<Map<String, Object>> ranges = List.of(p("from", "0", "to", "18", "value", "CHILD"), p("from", "18", "to", "65", "value", "ADULT"), p("from", "65", "to", "", "value", "SENIOR"));
        assertEquals("CHILD", r.apply(in(new BigDecimal("17")), p("ranges", ranges), ctx()));
        assertEquals("ADULT", r.apply(in(new BigDecimal("18")), p("ranges", ranges), ctx()));
        assertEquals("SENIOR", r.apply(in(new BigDecimal("80")), p("ranges", ranges), ctx()));
    }

    @Test
    @DisplayName("SUBSTRING by position, from the end, and by token")
    void substring() {
        SubstringRule r = new SubstringRule();
        assertEquals("606", r.apply(in("60614-1234"), p("start", 1, "length", 3), ctx()));
        assertEquals("1234", r.apply(in("60614-1234"), p("fromEnd", true, "length", 4), ctx()));
        assertEquals("60614-1234", r.apply(in("60614-1234"), p("start", 20), ctx()));
        assertNull(r.apply(in("60614-1234"), p("start", 20, "onShort", "BLANK"), ctx()));
        assertEquals("Jordan", r.apply(in("Rivera, Jordan"), p("mode", "TOKEN", "delimiter", ",", "tokenIndex", 2), ctx()));
    }

    @Test
    @DisplayName("CLEAN_TEXT strips, cases, replaces and folds accents")
    void cleanText() {
        CleanTextRule r = new CleanTextRule();
        assertEquals("3125550100", r.apply(in("(312) 555-0100"), p("strip", "NON_DIGITS"), ctx()));
        assertEquals("RIVERA", r.apply(in("  rivera "), p("textCase", "UPPER"), ctx()));
        assertEquals("Jose Nunez", r.apply(in("José Núñez"), p("asciiOnly", true), ctx()));
        assertEquals("Mary Anne O'neil", r.apply(in("MARY  ANNE o'neil"), p("textCase", "TITLE"), ctx()));
        assertEquals("N/A", r.apply(in("NULL"), p("find", "NULL", "replaceWith", "N/A"), ctx()));
    }

    @Test
    @DisplayName("FORMAT_NUMBER handles decimals, implied decimals, zero padding, negatives and booleans")
    void formatNumber() {
        FormatNumberRule r = new FormatNumberRule();
        assertEquals("123.40", r.apply(in(new BigDecimal("123.4")), p("decimals", 2), ctx()));
        assertEquals("12340", r.apply(in(new BigDecimal("123.4")), p("decimals", 2, "impliedDecimal", true), ctx()));
        assertEquals("0000012340", r.apply(in(new BigDecimal("123.4")), p("decimals", 2, "impliedDecimal", true, "leadingZeros", 10), ctx()));
        assertEquals("(5.00)", r.apply(in(new BigDecimal("-5")), p("negativeStyle", "PARENS"), ctx()));
        assertEquals("5.00-", r.apply(in(new BigDecimal("-5")), p("negativeStyle", "TRAILING_MINUS"), ctx()));
        assertEquals("Y", r.apply(in(Boolean.TRUE), p("booleanStyle", "YN"), ctx()));
        assertEquals("0", r.apply(in(Boolean.FALSE), p("booleanStyle", "10"), ctx()));
    }

    @Test
    @DisplayName("DATE_MATH adds, snaps to month bounds and computes ages against the run date")
    void dateMath() {
        DateMathRule r = new DateMathRule();
        LocalDate d = LocalDate.of(2026, 2, 10);
        assertEquals(LocalDate.of(2026, 2, 13), r.apply(in(d), p("op", "ADD_DAYS", "n", 3), ctx()));
        assertEquals(LocalDate.of(2026, 3, 10), r.apply(in(d), p("op", "ADD_MONTHS", "n", 1), ctx()));
        assertEquals(LocalDate.of(2026, 2, 1), r.apply(in(d), p("op", "START_OF_MONTH"), ctx()));
        assertEquals(LocalDate.of(2026, 2, 28), r.apply(in(d), p("op", "END_OF_MONTH"), ctx()));
        assertEquals(new BigDecimal("41"), r.apply(in(LocalDate.of(1985, 3, 7)), p("op", "AGE_AT", "relativeTo", "RUN_DATE"), ctx()));
    }

    @Test
    @DisplayName("ARITHMETIC against a constant or another element of the row, with divide-by-zero policy")
    void arithmetic() {
        ArithmeticRule r = new ArithmeticRule();
        assertEquals(new BigDecimal("250.00"), r.apply(in(new BigDecimal("2.5")), p("op", "MULTIPLY", "operandKind", "CONSTANT", "operandValue", "100"), ctx()));
        RuleContext c = ctx();
        c.row = Map.of("claim.allowed_amount", new BigDecimal("80"));
        assertEquals(new BigDecimal("20.00"), r.apply(in(new BigDecimal("100")), p("op", "SUBTRACT", "operandElement", "claim.allowed_amount"), c));
        assertEquals(new BigDecimal("80.00"), r.apply(in(new BigDecimal("80")), p("op", "PERCENT_OF", "operandKind", "CONSTANT", "operandValue", "100"), ctx()));
        assertNull(r.apply(in(new BigDecimal("1")), p("op", "DIVIDE", "operandKind", "CONSTANT", "operandValue", "0"), ctx()));
        assertEquals(new BigDecimal("0.00"), r.apply(in(new BigDecimal("1")), p("op", "DIVIDE", "operandKind", "CONSTANT", "operandValue", "0", "divideByZero", "ZERO"), ctx()));
    }

    @Test
    @DisplayName("COALESCE returns the first non-blank input")
    void coalesce() {
        CoalesceRule r = new CoalesceRule();
        assertEquals("mobile", r.apply(in(null, "   ", "mobile"), p(), ctx()));
        assertEquals("   ", r.apply(in(null, "   ", "mobile"), p("treatBlankAsNull", false), ctx()));
        assertNull(r.apply(in(null, null), p(), ctx()));
    }

    @Test
    @DisplayName("CONSTANT supplies literals and run-time tokens")
    void constant() {
        ConstantRule r = new ConstantRule();
        assertEquals("D", r.apply(in(), p("token", "LITERAL", "value", "D"), ctx()));
        assertEquals("20260919", r.apply(in(), p("token", "RUN_DATE"), ctx()));
        assertEquals("09/18/2026", r.apply(in(), p("token", "BUSINESS_DATE", "pattern", "MM/dd/yyyy"), ctx()));
        assertEquals("ACMEDENTAL", r.apply(in(), p("token", "VENDOR_CODE"), ctx()));
        assertEquals("0007", r.apply(in(), p("token", "FILE_SEQ", "padWidth", 4), ctx()));
        assertEquals(new BigDecimal("12.5"), r.apply(in(), p("value", "12.5", "valueType", "NUMBER"), ctx()));
    }

    @Test
    @DisplayName("SEQUENCE counts through the file and restarts per group")
    void sequence() {
        SequenceRule r = new SequenceRule();
        RuleContext c = ctx();
        assertEquals("001", r.apply(in(), p("padWidth", 3), c));
        assertEquals("002", r.apply(in(), p("padWidth", 3), c));
        RuleContext g = ctx();
        g.currentFieldId = "f9";
        g.row = Map.of("claim.claim_id", "C1");
        assertEquals("1", r.apply(in(), p("scope", "GROUP", "groupElement", "claim.claim_id"), g));
        assertEquals("2", r.apply(in(), p("scope", "GROUP", "groupElement", "claim.claim_id"), g));
        g.row = Map.of("claim.claim_id", "C2");
        assertEquals("1", r.apply(in(), p("scope", "GROUP", "groupElement", "claim.claim_id"), g));
    }

    @Test
    @DisplayName("MASK styles keep what the vendor needs and hide the rest")
    void mask() {
        MaskRule r = new MaskRule();
        assertEquals("*****6789", r.apply(in("123456789"), p("style", "LAST4"), ctx()));
        assertEquals("12*******", r.apply(in("123456789"), p("style", "FIRST_N", "keep", 2), ctx()));
        assertEquals("#########", r.apply(in("123456789"), p("style", "FIXED", "maskChar", "#"), ctx()));
        assertEquals(16, ((String) r.apply(in("123456789"), p("style", "HASH", "hashLength", 16), ctx())).length());
        assertNull(r.apply(in("123456789"), p("style", "NULL"), ctx()));
        String fp = (String) r.apply(in("A12-345"), p("style", "FORMAT_PRESERVING"), ctx());
        assertEquals(7, fp.length());
        assertEquals('-', fp.charAt(3));
        assertTrue(Character.isLetter(fp.charAt(0)) && Character.isDigit(fp.charAt(1)));
    }

    @Test
    @DisplayName("Masking is deterministic per salt, format preserving and reversible through the context")
    void masking() {
        assertEquals(Masking.formatPreserving("M0012345", "salt-a"), Masking.formatPreserving("M0012345", "salt-a"));
        assertNotEquals(Masking.formatPreserving("M0012345", "salt-a"), Masking.formatPreserving("M0012345", "salt-b"));
        assertNotEquals("M0012345", Masking.formatPreserving("M0012345", "salt-a"));
        LocalDate shifted = Masking.shiftDate(LocalDate.of(1985, 3, 7), "salt", 180);
        assertTrue(Math.abs(java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(1985, 3, 7), shifted)) <= 180);
        String pseudonym = Masking.keepFirst("Rivera", "salt");
        assertNotEquals("Rivera", pseudonym);
        assertTrue(Character.isUpperCase(pseudonym.charAt(0)));

        RuleContext c = ctx();
        c.masking = true;
        Object masked = c.mask("M0012345");
        assertNotEquals("M0012345", masked);
        assertTrue(c.wasMasked(masked));
        assertEquals("M0012345", c.original(masked));
    }
}
