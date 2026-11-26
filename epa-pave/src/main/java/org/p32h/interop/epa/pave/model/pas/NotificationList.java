package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What the receiver holds, as the notifications list answers it.
 *
 * <p>The receiver keeps payloads in memory and keeps a bounded number of them. The counts here let
 * the screen say when older ones are no longer held, so an empty or short list is never taken to
 * mean nothing arrived. The ledger line of every notification remains either way.
 *
 * @param notifications the notifications asked for, newest first
 * @param held          how many the receiver holds now
 * @param received      how many have arrived since the application started
 * @param evicted       how many are no longer held because the bound was reached
 * @param capacity      the most the receiver holds at once
 * @param latest        the stamp of the newest notification held, to pass as {@code since} on the
 *                      next poll. Null when none is held
 * @param note          what a tester should know about how notifications are kept
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NotificationList(List<NotificationView> notifications, int held, long received, long evicted, int capacity, String latest,
                               String note) {

    public NotificationList {
        notifications = notifications == null ? List.of() : List.copyOf(notifications);
    }

    /** Redacted, because each notification holds a payload. */
    @Override
    public String toString() {
        return "NotificationList[held=" + held + ", received=" + received + "]";
    }
}
