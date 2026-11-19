package com.thehiddenbrain.interop.epa.pave.model;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Reads a date written as YYYY-MM-DD, strictly. */
public final class IsoDates {

    // Strict, so 2026-02-30 is rejected instead of being rolled forward to a date nobody typed.
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    private IsoDates() {
    }

    /** The date, or null when the text is blank or is not a real calendar date written as YYYY-MM-DD. */
    public static LocalDate parse(String text) {
        if (Texts.blank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip(), ISO_DATE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static boolean valid(String text) {
        return parse(text) != null;
    }
}
