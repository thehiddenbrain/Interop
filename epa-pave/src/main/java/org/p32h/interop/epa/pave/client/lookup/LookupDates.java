package org.p32h.interop.epa.pave.client.lookup;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * Reads a date a lookup API may write in one of four ways: {@code 1970-01-31}, an ISO date-time
 * such as {@code 1970-01-31T00:00:00Z}, {@code 01/31/1970} and {@code 19700131}.
 *
 * <p>Anything else is not read. In particular a date with the day first, a two digit year and a
 * count of seconds are left alone, because reading any of them would be a guess, and a date of
 * birth that is wrong by a guess fails further down with nothing to show why.
 */
final class LookupDates {

    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /**
     * The date part is taken exactly as written. It is not moved to another time zone: the zone
     * the service meant the date in is not known, and moving it could change the day.
     */
    private static final Pattern ISO_DATE_TIME = Pattern.compile(
            "(\\d{4})-(\\d{2})-(\\d{2})[Tt ]([01]\\d|2[0-4]):([0-5]\\d)(?::([0-5]\\d|60)(?:[.,]\\d{1,9})?)?"
                    + "(?:[Zz]|[+-]\\d{2}(?::?\\d{2})?)?(?:\\[[^\\]]+])?");

    /**
     * Month first, the way dates are written in the United States, where this programme runs. A
     * text whose first number is over 12 is therefore not a date here, and is left as received.
     */
    private static final Pattern MONTH_DAY_YEAR = Pattern.compile("(\\d{1,2})/(\\d{1,2})/(\\d{4})");

    private static final Pattern COMPACT = Pattern.compile("(\\d{4})(\\d{2})(\\d{2})");

    private LookupDates() {
    }

    /** The date, or null when the text is blank, is not in one of the four forms, or is not a real calendar date. */
    static LocalDate parse(String text) {
        if (Texts.blank(text)) {
            return null;
        }
        String value = text.strip();
        Matcher matcher = ISO_DATE.matcher(value);
        if (matcher.matches()) {
            return date(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = ISO_DATE_TIME.matcher(value);
        if (matcher.matches()) {
            return date(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        matcher = MONTH_DAY_YEAR.matcher(value);
        if (matcher.matches()) {
            return date(matcher.group(3), matcher.group(1), matcher.group(2));
        }
        matcher = COMPACT.matcher(value);
        if (matcher.matches()) {
            return date(matcher.group(1), matcher.group(2), matcher.group(3));
        }
        return null;
    }

    /** The date as {@code yyyy-MM-dd}, or null when {@link #parse(String)} gives none. */
    static String toIso(String text) {
        LocalDate date = parse(text);
        return date == null ? null : date.toString();
    }

    private static LocalDate date(String year, String month, String day) {
        try {
            // LocalDate.of refuses a day that does not exist, so 02/30 is not rolled forward to a date nobody wrote.
            return LocalDate.of(Integer.parseInt(year), Integer.parseInt(month), Integer.parseInt(day));
        } catch (DateTimeException e) {
            return null;
        }
    }
}
