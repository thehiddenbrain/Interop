package org.p32h.interop.epa.pave.client.support;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.Protocol;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.config.CallIntent;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.p32h.interop.epa.pave.service.error.ProdWriteBlockedException;

/**
 * Says whether a host is one the Onyx access token may be sent to.
 *
 * <p>A request sent raw can go anywhere its collection points: a vendor's collection may hold
 * requests to other services. The Onyx token is a credential for Onyx, and attaching it to a
 * request for another host would hand it to whoever runs that host. So the token goes only to a
 * host that one of the Onyx operations resolves to in the configuration in force right now.
 *
 * <p>Nothing is cached. The answer is worked out from the gateway each time, so it always reflects
 * the active environment.
 */
public final class OnyxHosts {

    private static final Set<Protocol> ONYX = Set.of(Protocol.CRD, Protocol.DTR, Protocol.PAS, Protocol.SUBSCRIPTION);

    private OnyxHosts() {
    }

    /** The lower-case hosts the Onyx operations resolve to. Empty when none is configured. */
    public static Set<String> of(OperationGateway gateway) {
        Set<String> hosts = new LinkedHashSet<>();
        for (EpaOperation operation : EpaOperation.values()) {
            if (!operation.outbound() || !ONYX.contains(operation.protocol())) {
                continue;
            }
            try {
                add(hosts, gateway.prepare(CallIntent.of(operation)).url());
            } catch (ProdWriteBlockedException refusal) {
                // A write that is blocked still has a target, and that target is an Onyx host.
                add(hosts, refusal.target());
            } catch (RuntimeException e) {
                // Not configured, or not resolved. That operation names no host.
            }
        }
        return hosts;
    }

    /** The lower-case host of a url, or null when it has none. */
    public static String host(String url) {
        if (Texts.blank(url)) {
            return null;
        }
        try {
            String host = URI.create(url.strip()).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void add(Set<String> hosts, ResolvedUrl url) {
        String host = url == null ? null : host(url.value());
        if (host != null) {
            hosts.add(host);
        }
    }
}
