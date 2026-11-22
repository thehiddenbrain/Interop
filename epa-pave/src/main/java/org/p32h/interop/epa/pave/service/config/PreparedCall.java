package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.SentSecrets;
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
 * <p>The type proves a call was prepared. That the request sent is that call rests on one more
 * thing: every outbound request leaves through {@code client/support/ExchangeExecutor}, which
 * sends the url and method that were judged and nothing else. A test walks the source tree and
 * fails if any file outside {@code client/support} performs an HTTP exchange of its own.
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

    /**
     * The raw body saved with the collection request this call came from, as a template for
     * {@link #resolve}: a credential typed into it stands as a placeholder of its own, which
     * {@link #resolve} supplies and every display form masks. Null when the call did not come from
     * a collection request, or that request has no raw body.
     */
    String savedBody();

    /**
     * Every secret this tool holds for the configuration this call was prepared in, whether or not
     * the call carries it: the secret variables of the environment, of the collection the request
     * came from and of application.yml with the values given for this run, the client secret, the
     * credentials of the address to call back and of the lookup configuration, and the access
     * token held. A service can repeat any of them, such as the client secret in an error answer to
     * a call sent without sign-in, so the answer to this call is masked with them as well as with
     * the secrets it carried. The values never leave the type that holds them.
     */
    SentSecrets heldSecrets();

    /**
     * How this call signs in, as the environment it was prepared under states it, with its values
     * resolved against the same snapshot. Null when the environment states no method, and then
     * {@code onyx.auth} applies as it always has.
     */
    default SignInSettings signIn() {
        return null;
    }

    /**
     * The connect and read timeouts in force for this call, never null and both values present,
     * each with its source. This default is the onyx properties' own defaults; the gateway of this
     * tool answers with the values application.yml gives for the operation (a lookup's own for a
     * lookup) and, once an environment states its own, with those.
     */
    default Timeouts timeouts() {
        return Timeouts.APPLICATION;
    }
}
