package org.p32h.interop.epa.pave.service.ledger;

import org.p32h.interop.epa.pave.service.error.NotFoundException;
import java.util.regex.Pattern;

/**
 * How a run id that came in from outside is spoken of.
 *
 * <p>An id arrives in a url or a request body, so it can be any text at all. It is repeated in a
 * message only when it has the shape of an id: letters, digits, dots, hyphens and underscores, and
 * no longer than an id is. Anything else is referred to without being repeated.
 */
public final class RunIds {

    private static final Pattern PLAIN = Pattern.compile("[A-Za-z0-9._-]{1,80}");

    private RunIds() {
    }

    /** True when the text can be repeated in a message as it stands. */
    public static boolean plain(String id) {
        return id != null && PLAIN.matcher(id).matches();
    }

    /**
     * The answer to a request for a run the ledger does not hold. The id is never repeated: it
     * came from the address or the body of the request, and the answer is no place to echo it.
     */
    public static NotFoundException notFound(String runId) {
        return new NotFoundException("There is no run with that id.",
                "Copy the run id from the list in Audit, or from the result that gave it, and try again.");
    }
}
