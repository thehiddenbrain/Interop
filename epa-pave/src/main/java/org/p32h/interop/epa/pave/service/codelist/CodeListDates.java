package org.p32h.interop.epa.pave.service.codelist;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.IsoDates;

/**
 * Reads an effective or end date from a code list cell.
 *
 * <p>Three ways of writing a date are read, each strictly, so a day that does not exist is not a
 * date: {@code 2026-01-31}, {@code 1/31/2026} (with or without leading zeros) and
 * {@code 20260131}. With slashes the month is first, as a spreadsheet in the United States writes
 * it. Nothing else is read. A two digit year is not given a century, a cell with the day first is
 * not turned round, and a serial number from a spreadsheet is not counted out: each of those would
 * be a guess, and a guessed date decides which row of the list applies.
 *
 * <p>Any of the three may be followed by a time of day, which is how a database or a spreadsheet
 * writes a column that holds a date and a time: {@code 1/31/2026 0:00},
 * {@code 2026-01-31 00:00:00}, {@code 2026-01-31T00:00:00Z}. The cell is then read as that date.
 * The time has to be a time, or the cell is not read at all: a date with something unreadable
 * after it is not half read. Neither the time nor a zone is applied. The date is the one that is
 * written, and {@link #zoned} lets the loader say so where a zone is given, since midnight in one
 * zone is the evening before in another.
 */
final class CodeListDates {

    private static final Pattern SLASHED = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}");
    private static final Pattern COMPACT = Pattern.compile("\\d{8}");
    private static final DateTimeFormatter MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter YEAR_MONTH_DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    /** A date in one of the three forms and, after white space or the letter T, whatever follows it. */
    private static final Pattern DATE_THEN_REST = Pattern.compile("(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{4}|\\d{8})(?:(?:\\s+|[Tt])(\\S.*))?");

    /**
     * A time of day: hours and minutes, then optionally seconds with a fraction, AM or PM, and a
     * zone written as Z, as an offset from UTC, or as UTC or GMT. The numbers are checked apart
     * from the shape, so 25:00 has the shape and is still not a time.
     */
    private static final Pattern TIME_OF_DAY = Pattern.compile("(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,]\\d{1,9})?)?"
            + "(?:\\s*(AM|PM))?(?:\\s*(Z|UTC|GMT|[+-]\\d{2}(?::?\\d{2})?))?", Pattern.CASE_INSENSITIVE);

    /** No place on earth keeps a clock further than this many hours from UTC, with room to spare. */
    private static final int MOST_OFFSET_HOURS = 18;

    private CodeListDates() {
    }

    /** The date, or null when the cell is blank, is not one of the three forms, or holds something after the date that is not a time of day. */
    static LocalDate read(String cell) {
        Parts parts = Parts.of(cell);
        return parts == null ? null : parts.date();
    }

    /** True when the cell is read as a date and is written with a time zone, which {@link #read} does not apply. */
    static boolean zoned(String cell) {
        Parts parts = Parts.of(cell);
        return parts != null && parts.zoned();
    }

    /** A cell that was read: its date, and whether a zone stood after its time. */
    private record Parts(LocalDate date, boolean zoned) {

        static Parts of(String cell) {
            String text = CodeListText.clean(cell);
            if (text == null) {
                return null;
            }
            Matcher split = DATE_THEN_REST.matcher(text);
            if (!split.matches()) {
                return null;
            }
            LocalDate date = oneOfTheThreeForms(split.group(1));
            if (date == null) {
                return null;
            }
            if (split.group(2) == null) {
                return new Parts(date, false);
            }
            Matcher time = TIME_OF_DAY.matcher(split.group(2));
            if (!time.matches() || !realTime(time)) {
                return null;
            }
            return new Parts(date, time.group(5) != null);
        }
    }

    /** The date when the text is one of the three forms and names a day that exists, otherwise null. */
    private static LocalDate oneOfTheThreeForms(String text) {
        if (SLASHED.matcher(text).matches()) {
            return parse(text, MONTH_DAY_YEAR);
        }
        if (COMPACT.matcher(text).matches()) {
            return parse(text, YEAR_MONTH_DAY);
        }
        return IsoDates.parse(text);
    }

    /** Whether the numbers of something shaped like a time are those of a time. Hour 24 is not: it would be the day after. */
    private static boolean realTime(Matcher time) {
        int hour = Integer.parseInt(time.group(1));
        boolean twelveHourClock = time.group(4) != null;
        if (twelveHourClock ? (hour < 1 || hour > 12) : hour > 23) {
            return false;
        }
        if (Integer.parseInt(time.group(2)) > 59 || (time.group(3) != null && Integer.parseInt(time.group(3)) > 59)) {
            return false;
        }
        String zone = time.group(5);
        if (zone == null || !(zone.startsWith("+") || zone.startsWith("-"))) {
            return true;
        }
        String digits = zone.substring(1).replace(":", "");
        int minutes = digits.length() > 2 ? Integer.parseInt(digits.substring(2)) : 0;
        return Integer.parseInt(digits.substring(0, 2)) <= MOST_OFFSET_HOURS && minutes <= 59;
    }

    private static LocalDate parse(String text, DateTimeFormatter format) {
        try {
            return LocalDate.parse(text, format);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
