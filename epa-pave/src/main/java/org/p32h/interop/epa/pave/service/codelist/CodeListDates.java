package org.p32h.interop.epa.pave.service.codelist;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
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
 */
final class CodeListDates {

    private static final Pattern SLASHED = Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{4}");
    private static final Pattern COMPACT = Pattern.compile("\\d{8}");
    private static final DateTimeFormatter MONTH_DAY_YEAR = DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter YEAR_MONTH_DAY = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private CodeListDates() {
    }

    /** The date, or null when the cell is blank or is not one of the three forms. */
    static LocalDate read(String cell) {
        String text = CodeListText.clean(cell);
        if (text == null) {
            return null;
        }
        if (SLASHED.matcher(text).matches()) {
            return parse(text, MONTH_DAY_YEAR);
        }
        if (COMPACT.matcher(text).matches()) {
            return parse(text, YEAR_MONTH_DAY);
        }
        return IsoDates.parse(text);
    }

    private static LocalDate parse(String text, DateTimeFormatter format) {
        try {
            return LocalDate.parse(text, format);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
