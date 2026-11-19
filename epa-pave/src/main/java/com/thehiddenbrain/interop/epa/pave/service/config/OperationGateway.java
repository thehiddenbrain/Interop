package com.thehiddenbrain.interop.epa.pave.service.config;

import com.thehiddenbrain.interop.epa.pave.model.EnvironmentRef;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.service.error.EndpointNotConfiguredException;
import com.thehiddenbrain.interop.epa.pave.service.error.ProdWriteBlockedException;
import com.thehiddenbrain.interop.epa.pave.service.error.UnresolvedVariablesException;
import java.util.Map;

/**
 * The one door every outbound call goes through.
 *
 * <p>It answers two questions against a single snapshot of the configuration: where does this
 * operation go, and may it go there. The result is a {@link PreparedCall}, which is the only thing
 * a client will accept. Protocol services never see urls, environments or unlock state directly.
 */
public interface OperationGateway {

    /**
     * Resolves the target for an operation and applies the production guardrail.
     *
     * <p>On the mock profile a target that is not configured or not resolved is not an error,
     * because nothing is sent; the returned call says what was and was not resolved. On the live
     * profile it is an error raised before anything is sent. The guardrail applies on both
     * profiles, so it can be exercised on mock exactly as it behaves on live.
     *
     * @throws ProdWriteBlockedException      when the operation is a write, the active environment
     *                                        is production, and writes are not unlocked
     * @throws UnresolvedVariablesException   on live, when a placeholder has no value in any layer
     * @throws EndpointNotConfiguredException on live, when there is no url for the operation at all
     */
    PreparedCall prepare(CallIntent intent);

    /**
     * Resolves placeholders in arbitrary text, such as a request body or an authentication setting,
     * against the active configuration. Never throws for an unresolved placeholder; the result
     * lists what could not be resolved.
     */
    ResolvedText resolve(String template, Map<String, String> runValues);

    /** The active environment, or {@link EnvironmentRef#none()}. */
    EnvironmentRef activeEnvironment();

    /** Whether a write would be sent right now for a session holding the given unlock, which may be null. */
    WriteAccess writeAccess(ProdUnlock unlock);

    /**
     * A counter that moves on every time the active environment or its contents change. Anything
     * cached per environment, such as an access token, is keyed on it.
     */
    long epoch();
}
