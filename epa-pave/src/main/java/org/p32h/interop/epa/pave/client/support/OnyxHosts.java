package org.p32h.interop.epa.pave.client.support;

import java.util.LinkedHashSet;
import java.util.Set;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.service.config.OperationGateway;

/**
 * Says whether an address is one the Onyx access token may be sent to.
 *
 * <p>A request sent raw can go anywhere its collection points: a vendor's collection may hold
 * requests to other services. The Onyx token is a credential for Onyx, and attaching it to a
 * request for another host would hand it to whoever runs that host. So the token goes only to the
 * scheme, host and port of an address that one of the Onyx operations resolves to in the
 * configuration in force right now.
 *
 * <p>Nothing is cached. The answer is worked out from the gateway each time, so it always reflects
 * the active environment.
 */
public final class OnyxHosts {

    private static final Set<Protocol> ONYX = Set.of(Protocol.CRD, Protocol.DTR, Protocol.PAS, Protocol.SUBSCRIPTION);

    private OnyxHosts() {
    }

    /**
     * The scheme, host and port of every address the configuration in force names for an Onyx
     * operation, worked out with no value typed for any run. Empty when none is configured.
     */
    public static Set<UrlNormal.Destination> destinations(OperationGateway gateway) {
        Set<UrlNormal.Destination> found = new LinkedHashSet<>();
        for (String url : gateway.configuredUrls(ONYX)) {
            UrlNormal.Destination destination = UrlNormal.destination(url);
            if (destination != null) {
                found.add(destination);
            }
        }
        return found;
    }

    /**
     * True when the Onyx access token may go to this url: its scheme, host and port are those of
     * an address the configuration in force names for an Onyx operation by itself. A value typed
     * for a run can point a call anywhere, and the token must not follow it there.
     */
    public static boolean admits(OperationGateway gateway, String url) {
        UrlNormal.Destination target = UrlNormal.destination(url);
        if (target == null || target.scheme() == null) {
            return false;
        }
        for (UrlNormal.Destination onyx : destinations(gateway)) {
            if (onyx.scheme() != null && onyx.port() >= 0 && onyx.scheme().equals(target.scheme()) && onyx.host().equals(target.host())
                    && onyx.port() == target.port()) {
                return true;
            }
        }
        return false;
    }
}
