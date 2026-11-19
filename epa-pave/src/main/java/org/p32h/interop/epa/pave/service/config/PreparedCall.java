package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import java.util.List;

/**
 * A call that has been given a target and has passed the production guardrail.
 *
 * <p>Every client method that sends anything takes one of these. It is a sealed interface whose
 * single implementation is not visible outside this package, so only the gateway can mint one, and
 * it mints one only after resolving the url against a single snapshot of the configuration and
 * checking the guardrail against that same snapshot. There is no way to hand a client a production
 * write that was not authorised. The protection is in the type, not in a check somebody has to
 * remember to call.
 *
 * <p>A prepared call belongs to the configuration it was prepared in. It records that epoch, and
 * the sending layer refuses a call whose epoch is no longer current, so a call kept across an
 * environment switch or a relock cannot be sent later.
 */
public sealed interface PreparedCall permits IssuedCall {

    EpaOperation operation();

    /** The HTTP method that will be sent. The guardrail was evaluated with this method. */
    String method();

    /** Where the call goes. On the mock profile this may be unconfigured, since nothing is sent. */
    ResolvedUrl url();

    /**
     * Headers to send with the call, resolved: those of the collection request, or for a lookup
     * that came from the fallback layer the headers configured for that lookup. Otherwise empty.
     */
    List<ResolvedHeader> headers();

    /** The environment the url was resolved against and the guardrail was checked against. */
    EnvironmentRef environment();

    /** The collection request this target came from, or null when it came from the fallback layer. */
    String requestName();

    /**
     * True when the collection request carries a pre-request script, its own or inherited. The
     * script is never run, so a value Postman would have computed is not computed here, and the
     * result says so.
     */
    boolean scripted();

    /** The configuration epoch this call was prepared in. */
    long epoch();

    /**
     * Resolves placeholders in text that belongs to this call, such as its request body, against
     * the same snapshot the url was resolved from: the run values of the intent, the environment
     * that was active then, the variables of the collection the request came from, and the
     * application.yml layer. Never throws for an unresolved placeholder; the result lists what
     * could not be resolved, and the sending layer refuses to send a body that is not resolved.
     */
    ResolvedText resolve(String template);
}
