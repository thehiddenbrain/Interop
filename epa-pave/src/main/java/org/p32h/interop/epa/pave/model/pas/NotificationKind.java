package org.p32h.interop.epa.pave.model.pas;

/** What kind of body arrived at the notification receiver, before anything is read from it. */
public enum NotificationKind {
    /** A bundle that carries a subscription status, which is the form a notification takes. */
    NOTIFICATION_BUNDLE,
    /** A subscription status on its own, not wrapped in a bundle. */
    STATUS_ONLY,
    /** JSON that is neither. Shown in full as additional fields. */
    OTHER_JSON,
    /** A body that could not be parsed as JSON. It is shown exactly as received. */
    NOT_JSON,
    /** A request with no body. */
    NO_BODY
}
