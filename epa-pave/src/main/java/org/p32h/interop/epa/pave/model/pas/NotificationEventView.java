package org.p32h.interop.epa.pave.model.pas;

import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One event a notification reports.
 *
 * @param focus             the resource the event is about, as the notification refers to it. In an
 *                          id-only notification this reference is all there is
 * @param additionalContext further resources the notification names for the event
 */
public record NotificationEventView(Shown eventNumber, Shown timestamp, Shown focus, List<Shown> additionalContext) {

    public NotificationEventView {
        additionalContext = additionalContext == null ? List.of() : List.copyOf(additionalContext);
    }
}
