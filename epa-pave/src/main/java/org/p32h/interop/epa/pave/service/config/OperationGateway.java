package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.service.error.ConfigurationChangedException;
import org.p32h.interop.epa.pave.service.error.BadInputException;
import org.p32h.interop.epa.pave.service.error.EndpointNotConfiguredException;
import org.p32h.interop.epa.pave.service.error.NotFoundException;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;
import org.p32h.interop.epa.pave.service.error.UnresolvedVariablesException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one door every outbound call goes through.
 *
 * <p>It answers two questions against a single snapshot of the configuration: where does this
 * operation go, and may it go there. The result is a {@link PreparedCall}, which is the only thing
 * a client will accept. Protocol services never see urls, environments or unlock state directly.
 */
public interface OperationGateway {

    /**
     * Resolves the target for an operation and applies the production guardrail, both against one
     * snapshot of the configuration.
     *
     * <h4>Where the call goes</h4>
     * In order:
     * <ol>
     *   <li>The collection request named by {@code intent.request()}. It must exist, and unless the
     *       intent's operation is {@code UNMAPPED} it must be mapped to that operation. A read
     *       button can therefore never be pointed at a write request.</li>
     *   <li>Otherwise the default request for the operation: the first request mapped to it, in
     *       collection order, whose name does not contain "error".</li>
     *   <li>Otherwise the fallback layer: the base url for the operation's protocol from
     *       {@code application.yml} plus
     *       {@link org.p32h.interop.epa.pave.model.EpaOperation#fallbackPath()}. For the
     *       two lookups the template is {@code lookup.member.url} or {@code lookup.provider.url},
     *       and the headers configured for that lookup are resolved and returned on the call.</li>
     *   <li>For CDS Hooks discovery with none of the above: the url of a mapped CRD hook request cut
     *       at {@code /cds-services}.</li>
     * </ol>
     * An operation that is not
     * {@linkplain org.p32h.interop.epa.pave.model.EpaOperation#outbound() outbound} has no target.
     * Asking for one is a programming error and throws {@link IllegalArgumentException}: a sample
     * notification is delivered to this tool's own receiver and nowhere else, and a questionnaire
     * response completed in the tool is sent nowhere at all.
     *
     * <h4>The method</h4>
     * The method of the collection request, or the operation's default for a fallback target.
     * {@code intent.method()} is honoured only for {@code UNMAPPED}.
     *
     * <h4>Tokens in the url</h4>
     * After {@code {{placeholder}}} resolution, each single-brace {@code {name}} token and each
     * Postman path variable {@code :name} in the url is replaced by
     * {@code intent.urlValues().get(name)}, percent-encoded so that it stays one path segment or
     * one query value whatever characters it holds. A token with no value is listed in
     * {@link org.p32h.interop.epa.pave.model.ResolvedUrl#unresolved()} and treated as an
     * unresolved placeholder, with one exception: {@code SUBSCRIPTION_GET} from the fallback layer
     * with no {@code id} drops the trailing segment and lists subscriptions. The template keeps the
     * token; the display and send forms hold the substituted url.
     *
     * <p><b>A url value that was supplied is never ignored.</b> If the chosen url has no token for
     * it, the call is refused with {@link BadInputException}, because sending it would address
     * something other than what the caller named. One case is bridged, since a vendor collection
     * usually writes the subscription id as a placeholder: for {@code SUBSCRIPTION_GET},
     * {@code SUBSCRIPTION_UPDATE} and {@code SUBSCRIPTION_DELETE}, when {@code id} is supplied and
     * the url has no {@code {id}} or {@code :id} token, the id is bound to the
     * {@code {{placeholder}}} that forms the path segment after {@code Subscription/}, ahead of
     * every other layer. If that segment is a literal other than the id, the call is refused with
     * {@link BadInputException} naming both. A stale id left in an uploaded environment can
     * therefore never redirect a delete to another subscription.
     *
     * <h4>The guardrail</h4>
     * The same on both profiles. The call is a write when any of these is a write for the method
     * that will actually be sent, by
     * {@link org.p32h.interop.epa.pave.model.EpaOperation#writesWith(String)}: the
     * intent's operation, the operation the request is mapped to, and the operation that detection
     * reads from the request's own name and from its url, both as a template and as resolved. An
     * operator correction can therefore never turn a detected write into a read. Three further rules
     * make it stricter still:
     * <ul>
     *   <li>A collection request about which neither its name nor its url says anything is judged
     *       as an unmapped request, whatever it is mapped to.</li>
     *   <li>A collection request that reads as a read by its name only, with a url that says
     *       nothing and a method that can change something, is judged the same way. A name is
     *       something its author typed; a POST to the base of a FHIR server is a transaction
     *       whatever it is called. This is asked only of a url that is complete.</li>
     *   <li>A header that asks the far side to act with another method is judged by the method it
     *       asks for.</li>
     * </ul>
     * The url is judged in one normal form, and that form is what is sent: percent-escapes of
     * unreserved characters decoded and dot segments removed, as a server does before it acts.
     *
     * <p>A call that is a write is then held to these, in order. The destination decides, not the
     * environment that happens to be active:
     * <ul>
     *   <li>A write whose url climbs above the start of its path with {@code ..} is refused.</li>
     *   <li>A value typed for one run may not move a write. The scheme, host, port and the path up
     *       to where the first value typed for the run starts must be written by the configuration
     *       in force on its own, and that value may start only after a slash that follows the host,
     *       or in the query. Otherwise the write is refused, and the way through is to change the
     *       environment. A value typed for the run may still fill a claim id, a topic, a path segment
     *       after the base or a query value; a read may still be pointed anywhere.</li>
     *   <li>A write to an address that belongs to a production environment, one stored now or one
     *       stored here before and deleted since, is a write to that production environment
     *       whatever is active. It goes only when that environment is the active one, and is then
     *       held to the rule below; otherwise it is refused, the floor first, and the refusal names
     *       the production environment.</li>
     *   <li>A write to an environment of kind {@code prod} is refused unless the configuration
     *       floor allows production writes and {@code intent.unlock()} is an unlock this gateway
     *       minted for that environment and still holds. A relock does not move the epoch; a call
     *       prepared under an unlock stops answering its epoch once the unlock is gone. The
     *       refusal is worded from {@code intent.origin()}: a replay or a suite is told that it
     *       never carries the unlock, not to unlock.</li>
     *   <li>A write whose complete url reads as production, the text "prod" in it once escapes are
     *       decoded or in its host, is refused under an environment that is not declared
     *       production. The uploader's confirmation that the environment is not production covers
     *       that environment's own addresses only: never a url application.yml contributed to, and
     *       never what a value typed for the run put into it. There is nothing to unlock in this
     *       case: the way through is to declare the environment for what it is.</li>
     *   <li>On the live profile a write with no active environment is refused, because the tool
     *       cannot tell whether a fallback url is production. Reads still go to the fallback url.
     *       On the mock profile nothing is sent, so a write with no environment is allowed.</li>
     * </ul>
     *
     * <h4>The configuration the caller saw</h4>
     * When {@code intent.expectedEpoch()} is set and is not the epoch of the snapshot this call is
     * prepared against, the call is refused with {@link ConfigurationChangedException}, on both
     * profiles and for reads as well as writes. The environment strip is drawn per browser tab and
     * the active environment is one value for the whole server: without this, a tab still showing a
     * sandbox would send to whatever another tab had activated since.
     *
     * <h4>Mock and live</h4>
     * On the mock profile a target that is not configured or not resolved is not an error, because
     * nothing is sent; the returned call says what was and was not resolved. On the live profile
     * it is an error raised before anything is sent.
     *
     * @throws ConfigurationChangedException  when the intent names an epoch that is no longer current
     * @throws ProdWriteBlockedException      when the guardrail refuses the call
     * @throws NotFoundException              when {@code intent.request()} names a request that does not exist
     * @throws BadInputException              when {@code intent.request()} is not mapped to the intent's operation
     * @throws UnresolvedVariablesException   on live, when a placeholder or url token has no value
     * @throws EndpointNotConfiguredException on live, when there is no url for the operation at all
     */
    PreparedCall prepare(CallIntent intent);

    /**
     * Resolves placeholders in text that belongs to no request, such as an authentication setting,
     * against the active configuration: the run values given, the active environment and the
     * application.yml layer. There is no collection layer here, because the text belongs to no
     * collection. Text that belongs to a call, such as its body, is resolved with
     * {@link PreparedCall#resolve(String)} so it shares that call's snapshot. Never throws for an
     * unresolved placeholder; the result lists what could not be resolved.
     */
    ResolvedText resolve(String template, Map<String, String> runValues);

    /** The active environment, or {@link EnvironmentRef#none()}. */
    EnvironmentRef activeEnvironment();

    /** Whether a write would be sent right now for a session holding the given unlock, which may be null. */
    WriteAccess writeAccess(ProdUnlock unlock);

    /**
     * A counter that moves on every change to the stored configuration: activating, uploading,
     * replacing or removing an environment or a collection, and correcting a mapping. Every unlock
     * and every prepared call is tied to the epoch it was made in, and anything cached per
     * environment, such as an access token, is keyed on it.
     */
    long epoch();

    /**
     * The url each outbound operation of these protocols goes to under the configuration in force
     * by itself: no value typed for any run, no unlock. A placeholder or a token with no value is
     * left as written, so the scheme, host and port of an operation whose path still waits for an
     * identifier are known. Secrets are masked. For deciding where a credential the configuration
     * holds, such as the access token, may be sent. An operation that names no url is left out.
     *
     * <p>This default asks {@link #prepare} and so leaves out an operation whose url is incomplete.
     * The gateway of this tool overrides it and leaves out only an operation with no url at all.
     */
    default List<String> configuredUrls(Set<Protocol> protocols) {
        List<String> urls = new ArrayList<>();
        for (EpaOperation operation : EpaOperation.values()) {
            if (!operation.outbound() || !protocols.contains(operation.protocol())) {
                continue;
            }
            ResolvedUrl url;
            try {
                url = prepare(CallIntent.of(operation)).url();
            } catch (ProdWriteBlockedException refusal) {
                // A write that is blocked still has a target, and it is one the configuration names.
                url = refusal.target();
            } catch (RuntimeException e) {
                // Not configured, or not resolved. That operation names no url.
                continue;
            }
            if (url != null && url.display() != null) {
                urls.add(url.display());
            }
        }
        return urls;
    }

    /**
     * Everything this tool could have sent to a service, as the masking of a response takes it:
     * the value of every secret variable of the active environment (an operating system value one
     * refers to included) and of application.yml, the access token held for the service, the
     * client secret it is fetched with, and the credentials of the address the service is given to
     * call back. For masking what a service sends without being asked, such as a notification,
     * by the same rule the wire view of a call uses.
     *
     * <p>The values are held in a type that only masks text and never hands a value back. This
     * default knows of none.
     */
    default org.p32h.interop.epa.pave.model.SentSecrets secretsGivenToServices() {
        return org.p32h.interop.epa.pave.model.SentSecrets.none();
    }
}
