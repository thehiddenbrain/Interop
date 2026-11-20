package org.p32h.interop.epa.pave.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The one place a refusal of what was submitted is worded for the call that was made.
 *
 * <p>A service throws the same refusal whether it was asked to send, to show what it would send,
 * or to compare: the check is the same. But "Nothing was sent" and "send again" belong to a send,
 * and a tester who pressed "Show the request", "Compare" or "Unlock" and reads them looks for a
 * send that never was. So the exception handler hands every refusal (a 4xx) here, and the sentence that says
 * what did not happen, and the step that says what to do again, are chosen from what the endpoint
 * does, never by the code that threw.
 */
final class CallWording {

    /** The generic step of a form refusal, which names a send. */
    static final String SEND_AGAIN = "Correct the marked fields and send again.";

    private static final String NOTHING_SENT = " Nothing was sent.";

    /** What an endpoint does, as far as the words of a refusal are concerned. */
    enum Call {
        SEND(" Nothing was sent.", "send again"),
        PREVIEW(" The request is not shown.", "show the request again"),
        SUITE_PREVIEW(" Nothing was run.", "preview the file again"),
        COMPARE(" Nothing was compared.", "compare again"),
        UNLOCK(" Nothing was changed.", "try again"),
        UPLOAD(" Nothing was stored.", "upload the file again"),
        LOOKUP(" Nothing was looked up.", "look it up again"),
        CHANGE(" Nothing was changed.", "try again"),
        /** An endpoint whose purpose this class does not know: the refusal keeps the words it was thrown with. */
        OTHER(null, null);

        private final String notDone;
        private final String again;

        Call(String notDone, String again) {
            this.notDone = notDone;
            this.again = again;
        }

        /** The sentence that says what did not happen, with a leading space. */
        String notDone() {
            return notDone;
        }

        /** The step done again, such as "compare again". */
        String again() {
            return again;
        }
    }

    private CallWording() {
    }

    /** What the endpoint at this method and path does. */
    static Call of(String method, String path) {
        String verb = method == null ? "" : method.toUpperCase(Locale.ROOT);
        String at = path == null ? "" : path;
        if (at.equals("/api/suites/preview")) {
            return Call.SUITE_PREVIEW;
        }
        if (at.endsWith("/preview") || at.equals("/api/config/resolve")) {
            return Call.PREVIEW;
        }
        if (at.equals("/api/crd/compare")) {
            return Call.COMPARE;
        }
        if (at.equals("/api/config/writes")) {
            return Call.UNLOCK;
        }
        if (verb.equals("POST") && (at.equals("/api/config/environments") || at.equals("/api/config/collections"))) {
            return Call.UPLOAD;
        }
        if (at.startsWith("/api/config/")) {
            return Call.CHANGE;
        }
        if (at.startsWith("/api/members") || at.startsWith("/api/providers")) {
            return Call.LOOKUP;
        }
        boolean protocol = at.startsWith("/api/crd") || at.startsWith("/api/dtr") || at.startsWith("/api/pas/submit")
                || at.startsWith("/api/pas/inquire") || at.startsWith("/api/pas/subscriptions") || at.startsWith("/api/raw");
        if (protocol || at.equals("/api/runs/replay") || (verb.equals("POST") && at.equals("/api/suites"))) {
            return Call.SEND;
        }
        return Call.OTHER;
    }

    /** The call being answered now, read from the request the handler is answering. */
    static Call current() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servlet) {
            HttpServletRequest request = servlet.getRequest();
            String path = request.getRequestURI();
            String context = request.getContextPath();
            if (path != null && context != null && !context.isEmpty() && path.startsWith(context)) {
                path = path.substring(context.length());
            }
            return of(request.getMethod(), path);
        }
        return Call.OTHER;
    }

    /**
     * The refusal worded for the call: the sentence about the send that did not happen is replaced
     * by the one that fits, and a step that sends again by one that repeats this call. A title that
     * says nothing of a send is left as it was thrown: it already says what happened.
     */
    static ErrorView word(ErrorView view, Call call) {
        if (call == Call.OTHER) {
            return view;
        }
        String title = view.title() == null ? null : view.title().replace(NOTHING_SENT, call.notDone());
        String action = view.action();
        if (SEND_AGAIN.equals(action)) {
            action = "Correct the marked " + (view.fieldErrors().size() == 1 ? "field" : "fields") + " and " + call.again() + ".";
        } else if (action != null && call != Call.SEND) {
            action = action.replace("Nothing was sent. ", "").replace("send again", call.again());
        }
        return new ErrorView(view.kind(), title, action, view.httpStatus(), view.fieldErrors(), view.issues(), view.reference());
    }
}
