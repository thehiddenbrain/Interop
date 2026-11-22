package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import java.util.Locale;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * Where a lookup saved on screen goes and what it carries (R-11, plan 5.2 and 9.2), handed to the
 * gateway with {@link CallIntent#withLookup}. The gateway resolves it against the same snapshot as
 * any call and holds its secret header values. A lookup is a read whatever its method: a POST is
 * how some payers take a member ID out of the url, so it is not counted a write for its method.
 *
 * @param method             GET or POST. Nothing else: a lookup that could be sent with another
 *                           method would be a way to change something under the name of a read
 * @param urlTemplate        the address as saved, with {@code {id}} where a GET puts the identifier
 * @param headers            the extra headers to send, as saved
 * @param environmentSignIn  true when the lookup signs in with the active environment's sign-in.
 *                           Only then does the call carry that sign-in, so the environment's token
 *                           goes to a lookup only when the tester chose so
 * @param connectMs          the connect timeout, or null for application.yml's lookup timeout
 * @param readMs             the read timeout, or null for application.yml's lookup timeout
 */
public record LookupTarget(String method, String urlTemplate, List<HeaderSetting> headers, boolean environmentSignIn, Integer connectMs,
                           Integer readMs) {

    public LookupTarget {
        String normal = method == null ? "" : method.strip().toUpperCase(Locale.ROOT);
        if (!normal.equals("GET") && !normal.equals("POST")) {
            throw new IllegalArgumentException("A lookup is sent with GET or POST.");
        }
        method = normal;
        if (Texts.blank(urlTemplate)) {
            throw new IllegalArgumentException("A lookup needs an address.");
        }
        urlTemplate = urlTemplate.strip();
        headers = headers == null ? List.of() : List.copyOf(headers);
        checkTimeout(connectMs);
        checkTimeout(readMs);
    }

    private static void checkTimeout(Integer ms) {
        if (ms != null && (ms < Timeouts.MIN_MS || ms > Timeouts.MAX_MS)) {
            throw new IllegalArgumentException("A lookup timeout is between " + Timeouts.MIN_MS + " and " + Timeouts.MAX_MS + " ms.");
        }
    }

    /** The timeouts the lookup states, each with its source; null where it states none. */
    Timeouts timeouts() {
        return new Timeouts(connectMs, readMs, connectMs == null ? null : Timeouts.FROM_LOOKUP, readMs == null ? null : Timeouts.FROM_LOOKUP);
    }

    /**
     * Names the method, the header names and the sign-in choice. Never the address, which may carry
     * a credential in its query, nor a header value.
     */
    @Override
    public String toString() {
        return "LookupTarget[" + method + ", headers=" + headers + ", environmentSignIn=" + environmentSignIn + ", connectMs=" + connectMs
                + ", readMs=" + readMs + "]";
    }
}
