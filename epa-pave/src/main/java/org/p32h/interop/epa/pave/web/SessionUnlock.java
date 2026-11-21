package org.p32h.interop.epa.pave.web;

import org.p32h.interop.epa.pave.service.config.ProdUnlock;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/**
 * Keeps the production write unlock in the HTTP session and nowhere else.
 *
 * <p>The session is held in memory by the server, so the unlock is per browser session, is never
 * written to disk and cannot survive a restart. Controllers that send a write read the unlock from
 * here and pass it to the gateway, which decides whether it is still valid.
 */
public final class SessionUnlock {

    private static final String ATTRIBUTE = SessionUnlock.class.getName() + ".unlock";

    private SessionUnlock() {
    }

    /** The session's unlock, or null. Does not create a session. */
    public static ProdUnlock from(HttpServletRequest request) {
        HttpSession session = request == null ? null : request.getSession(false);
        Object value = session == null ? null : session.getAttribute(ATTRIBUTE);
        return value instanceof ProdUnlock unlock ? unlock : null;
    }

    public static void store(HttpServletRequest request, ProdUnlock unlock) {
        request.getSession(true).setAttribute(ATTRIBUTE, unlock);
    }

    public static void clear(HttpServletRequest request) {
        HttpSession session = request == null ? null : request.getSession(false);
        if (session != null) {
            session.removeAttribute(ATTRIBUTE);
        }
    }
}
