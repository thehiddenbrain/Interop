package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.ValidationException;
import java.time.LocalDate;
import java.time.ZoneId;
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

    private Params() {
    }

    /**
     * An optional value parsed with the type's own parser. Null when the parameter is blank.
     *
     * <p>The parser's own message is never passed on. A message this tool did not write, such as
     * the one a number parser or an enum lookup throws, names Java types and quotes the input.
     *
     * @param accepted what the parameter accepts, in words, for the sentence shown to the tester
     */
    public static <T> T optional(String name, String value, Function<String, T> parser, String accepted) {
        if (Texts.blank(value)) {
            return null;
        }
        try {
            return parser.apply(value.strip());
        } catch (IllegalArgumentException e) {
            throw ValidationException.of(name, "The value given here is not valid. It must be " + accepted + ".");
        }
    }

    /** An optional whole number. Null when blank. */
    public static Integer integer(String name, String value) {
        return optional(name, value, Integer::valueOf, "a whole number");
    }

    /** A value that must be present, parsed with the type's own parser. */
    public static <T> T required(String name, String value, Function<String, T> parser, String accepted) {
        if (Texts.blank(value)) {
            throw ValidationException.of(name, "A value is required here.");
        }
        return optional(name, value, parser, accepted);
    }

    /**
     * An IANA zone id such as America/New_York, or null when the value is absent or blank. A zone
     * this Java does not know is a 422 that names the parameter.
     */
    public static ZoneId zone(String name, String value) {
        if (Texts.blank(value)) {
            return null;
        }
        String id = value.strip();
        // Region ids only. An offset such as +05:00 is a clock without daylight saving and is not what a person means by their day.
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw ValidationException.of(name, "The zone must be an IANA zone id, such as America/New_York or Europe/London.");
        }
        return ZoneId.of(id);
    }

    /** An optional ISO date. Null when blank. */
    public static LocalDate date(String name, String value) {
        if (Texts.blank(value)) {
            return null;
        }
        LocalDate date = IsoDates.parse(value);
        if (date == null) {
            throw ValidationException.of(name, "Enter the date as YYYY-MM-DD, for example 2026-01-31.");
        }
        return date;
    }
}
