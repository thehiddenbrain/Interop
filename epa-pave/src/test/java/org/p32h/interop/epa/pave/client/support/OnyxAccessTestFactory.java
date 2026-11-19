package org.p32h.interop.epa.pave.client.support;

import java.time.Clock;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.springframework.web.client.RestClient;

/**
 * Builds the live Onyx sender around a RestClient bound to a mock server, for tests of the live
 * protocol clients. It sits in this package to reach the constructors that take a client directly.
 */
public final class OnyxAccessTestFactory {

    private OnyxAccessTestFactory() {
    }

    public static OnyxAccess create(ExchangeExecutor executor, OnyxProperties onyx, OperationGateway gateway, RestClient client, Clock clock) {
        return new OnyxAccess(executor, new OnyxTokenService(onyx, gateway, client, clock), gateway, client);
    }

    /** The token service alone, on the same bound client, for a live client that decides for itself when to ask for a token. */
    public static OnyxTokenService tokens(OnyxProperties onyx, OperationGateway gateway, RestClient client, Clock clock) {
        return new OnyxTokenService(onyx, gateway, client, clock);
    }
}
