package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.List;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.postman.UrlNormal;

/**
 * A token url is a destination too (I-28). A sign-in by client credentials is a POST that
 * carries the client's secret, so a token url that is, or lies beneath, an address a production
 * environment uses for a call would write to that production environment with no unlock.
 *
 * <p>A token url that is a production environment's own token url is allowed: production and the
 * sandbox often sign in at one shared service, and a token url is not the address of a call.
 * The production destinations themselves leave token urls out for the same reason (see
 * {@link ProductionDestinations}), so only a token url that reaches into a call's address is
 * refused.
 */
final class TokenUrlGuard {

    private TokenUrlGuard() {
    }

    /**
     * The production environments, stored or remembered, whose call addresses this token url is or
     * lies beneath, leaving out {@code exempt} (the active production environment, whose own
     * addresses are its own). Empty when it is none of them, or when it is the token url a stored
     * production environment signs in with.
     */
    static List<String> owners(ConfigSnapshot snapshot, UrlNormal.Destination tokenUrl, String exempt) {
        List<String> owners = new ArrayList<>(snapshot.productionOwners(tokenUrl));
        owners.remove(exempt);
        if (owners.isEmpty() || sharedWithProduction(snapshot, tokenUrl)) {
            return List.of();
        }
        return owners;
    }

    /** True when a stored production environment signs in at this very token url. */
    private static boolean sharedWithProduction(ConfigSnapshot snapshot, UrlNormal.Destination tokenUrl) {
        for (StoredEnvironment environment : snapshot.environments()) {
            SignInSettings signIn = environment.settings() == null ? null : environment.settings().signIn();
            if (environment.kind() != EnvironmentKind.PROD || signIn == null || signIn.tokenUrl() == null) {
                continue;
            }
            for (ProductionDestinations.Found own : ProductionDestinations.resolvedText(environment.scanned(), signIn.tokenUrl(),
                    snapshot.context())) {
                if (own.destination().covers(tokenUrl) && tokenUrl.covers(own.destination())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The refusal of a sign-in at such a token url when it would be sent. Quotes the scheme, host and port only. */
    static String atSending(UrlNormal.Destination tokenUrl, List<String> owners) {
        String where = new UrlNormal.Destination(tokenUrl.scheme(), tokenUrl.host(), tokenUrl.port(), "").shown();
        return "Nothing was sent: the token url, at " + where + ", is an address of the production environment" + (owners.size() > 1
                ? "s " : " ") + ConfigStore.namedAll(owners) + ", or lies beneath one, and a sign-in sends the client secret to it. "
                + "A token url is never an address a production environment uses for a call. Set the token url under Sign-in in "
                + "Configuration > Environments.";
    }
}
