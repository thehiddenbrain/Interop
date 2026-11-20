package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One event a notification reports.
 *
 * @param focus             the resource the event is about, as the notification refers to it. In an
 *                          id-only notification this reference is all there is
 * @param additionalContext further resources the notification names for the event
 * @param additionalContextPresence what the event held for its further resources: not returned,
 *                          null, empty or a value. An empty list alone cannot say which
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationEventView(Shown eventNumber, Shown timestamp, Shown focus, List<Shown> additionalContext,
                                    Presence additionalContextPresence) {

    public NotificationEventView {
        additionalContext = additionalContext == null ? List.of() : List.copyOf(additionalContext);
        if (additionalContextPresence == null) {
            additionalContextPresence = additionalContext.isEmpty() ? Presence.ABSENT : Presence.VALUE;
        }
    }

    /** An event whose further resources are known only from the list: held exactly when it has entries. */
    public NotificationEventView(Shown eventNumber, Shown timestamp, Shown focus, List<Shown> additionalContext) {
        this(eventNumber, timestamp, focus, additionalContext, null);
    }
}
