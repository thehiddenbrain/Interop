package com.thehiddenbrain.interop.epa.pave.web;

import com.thehiddenbrain.interop.epa.pave.model.Texts;
import com.thehiddenbrain.interop.epa.pave.service.error.ValidationException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.function.Function;

/**
 * Reads request parameters that are not plain text.
 *
 * <p>Controllers take such parameters as strings and convert them here, not through Spring's
 * conversion service. Which converters are registered differs between the running application and
 * a standalone MockMvc test, so a parameter that binds in one can fail in the other. Converting
 * explicitly makes the two behave the same, and gives every bad value the same 422 naming the
 * parameter.
 */
public final class Params {

    // Strict, so 2026-02-30 is rejected instead of being rolled forward to a date nobody typed.
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private Params() {
    }

    /** An optional value parsed with the type's own parser. Null when the parameter is blank. */
    public static <T> T optional(String name, String value, Function<String, T> parser) {
        if (Texts.blank(value)) {
            return null;
        }
        try {
            return parser.apply(value.strip());
        } catch (IllegalArgumentException e) {
            throw ValidationException.of(name, Texts.orElse(e.getMessage(), "The value given for " + name + " is not valid."));
        }
    }

    /** A value that must be present, parsed with the type's own parser. */
    public static <T> T required(String name, String value, Function<String, T> parser) {
        if (Texts.blank(value)) {
            throw ValidationException.of(name, "The parameter " + name + " is required.");
        }
        return optional(name, value, parser);
    }

    /** An optional ISO date. Null when blank. */
    public static LocalDate date(String name, String value) {
        if (Texts.blank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip(), ISO_DATE);
        } catch (DateTimeParseException e) {
            throw ValidationException.of(name, "Enter the date as YYYY-MM-DD, for example 2026-01-31.");
        }
    }

    /** True when the text is a real calendar date written as YYYY-MM-DD. */
    public static boolean isIsoDate(String value) {
        if (Texts.blank(value)) {
            return false;
        }
        try {
            LocalDate.parse(value.strip(), ISO_DATE);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
