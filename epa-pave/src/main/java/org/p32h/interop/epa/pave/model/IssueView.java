package org.p32h.interop.epa.pave.model;

import java.util.List;

/**
 * One issue from a FHIR OperationOutcome, shown as the service sent it. An OperationOutcome is
 * rendered issue by issue and never reduced to a status code and a sentence.
 *
 * <p>{@code expression} and {@code location} are lists, and a list is empty when the issue did
 * not hold the element, when it held null and when it held an empty array. Two flags cannot tell
 * three apart, so the screen reads the presence of each element, which says which of them it was.
 * The flags stay for a reader that only asks whether the element was there.
 *
 * @param expressionPresent  whether the issue held an {@code expression} element at all, whatever its value
 * @param locationPresent    whether the issue held a {@code location} element at all, whatever its value
 * @param expressionPresence what the issue held for {@code expression}: not returned, null, empty or a value
 * @param locationPresence   what the issue held for {@code location}: not returned, null, empty or a value
 * @param detailsPresence    what the issue held for {@code details}: not returned, null, empty or a value. The text
 *                           and the codings alone are not returned all three times, so the screen reads this
 */
public record IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                        List<Shown> expression, List<Shown> location, boolean expressionPresent, boolean locationPresent,
                        Presence expressionPresence, Presence locationPresence, Presence detailsPresence) {

    public IssueView {
        detailsCodes = detailsCodes == null ? List.of() : List.copyOf(detailsCodes);
        expression = expression == null ? List.of() : List.copyOf(expression);
        location = location == null ? List.of() : List.copyOf(location);
        // A presence the caller did not know is worked out the way the tool builds its own issues,
        // and the flag always agrees with the presence, so the two can never say different things.
        if (expressionPresence == null) {
            expressionPresence = held(expressionPresent, expression);
        }
        if (locationPresence == null) {
            locationPresence = held(locationPresent, location);
        }
        expressionPresent = expressionPresence != Presence.ABSENT;
        locationPresent = locationPresence != Presence.ABSENT;
        if (detailsPresence == null) {
            detailsPresence = !detailsCodes.isEmpty() || detailsText != null && detailsText.presence() != Presence.ABSENT
                    ? Presence.VALUE : Presence.ABSENT;
        }
    }

    /** An issue whose details are known only from its text and codings. */
    public IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                     List<Shown> expression, List<Shown> location, boolean expressionPresent, boolean locationPresent,
                     Presence expressionPresence, Presence locationPresence) {
        this(severity, code, diagnostics, detailsText, detailsCodes, expression, location, expressionPresent, locationPresent,
                expressionPresence, locationPresence, null);
    }

    /** An issue read from a response whose two lists are known only to be held or not. */
    public IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                     List<Shown> expression, List<Shown> location, boolean expressionPresent, boolean locationPresent) {
        this(severity, code, diagnostics, detailsText, detailsCodes, expression, location, expressionPresent, locationPresent,
                null, null, null);
    }

    private static Presence held(boolean present, List<Shown> entries) {
        if (!present) {
            return Presence.ABSENT;
        }
        return entries == null || entries.isEmpty() ? Presence.EMPTY : Presence.VALUE;
    }

    /**
     * An issue built by this tool rather than read from a response, where an element is held
     * exactly when it has entries.
     */
    public IssueView(Shown severity, Shown code, Shown diagnostics, Shown detailsText, List<Shown> detailsCodes,
                     List<Shown> expression, List<Shown> location) {
        this(severity, code, diagnostics, detailsText, detailsCodes, expression, location,
                expression != null && !expression.isEmpty(), location != null && !location.isEmpty());
    }
}
