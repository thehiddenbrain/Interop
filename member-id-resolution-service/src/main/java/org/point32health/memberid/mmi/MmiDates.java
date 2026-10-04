package org.point32health.memberid.mmi;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** MMI dates are {@code MM/dd/yyyy}. Parsed strictly ({@code uuuu} so STRICT works without an era). */
public final class MmiDates {

    public static final String OPEN_END_SENTINEL = "12/31/9999";
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("MM/dd/uuuu").withResolverStyle(ResolverStyle.STRICT);

    private MmiDates() {
    }

    /** @return the date, or null when the value is null, blank or not a real MM/dd/yyyy date */
    public static LocalDate parseOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.strip(), FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static boolean isOpenEnd(String endDate) {
        return endDate == null || endDate.isBlank() || OPEN_END_SENTINEL.equals(endDate.strip());
    }
}
