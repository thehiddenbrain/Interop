package org.p32h.interop.epa.pave.service.lookup;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/** The date of service a lookup may send (R-11), as the Workbench and the Test the lookup box give it: ISO, or nothing. */
final class LookupDate {

    private LookupDate() {
    }

    /** The date, or null when none was given. A date that is not ISO is refused at its field, never guessed at. */
    static LocalDate parse(String text) {
        if (Texts.blank(text)) {
            return null;
        }
        try {
            return LocalDate.parse(text.strip());
        } catch (DateTimeParseException e) {
            // The text is not repeated: it is the caller's.
            throw new ValidationException("The date of service is not a date. Nothing was looked up.",
                    List.of(new FieldError("dateOfService", "Write the date of service as yyyy-MM-dd, for example 2026-11-22.")));
        }
    }
}
