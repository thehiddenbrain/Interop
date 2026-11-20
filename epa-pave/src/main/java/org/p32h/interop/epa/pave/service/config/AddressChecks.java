package org.p32h.interop.epa.pave.service.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.p32h.interop.epa.pave.model.EnvironmentKind;

/**
 * What a tester should know about the addresses of one stored environment before anything is
 * sent from it, in the server's words: an address the HTTP client would not read as this tool
 * does, and a non-production environment on the same server as a production one.
 *
 * <p>Said at upload, on activation and wherever the environment is listed, so the Config tab shows
 * it before a request goes. Variable names and environment names only, never a value.
 */
final class AddressChecks {

    private AddressChecks() {
    }

    /** Every warning about the addresses of this environment under this snapshot. Empty when there is nothing to say. */
    static List<String> of(StoredEnvironment environment, ConfigSnapshot snapshot) {
        List<String> warnings = new ArrayList<>();
        if (environment == null) {
            return warnings;
        }
        ProductionDestinations.Context urlForming = snapshot.context();
        for (String name : ProductionDestinations.unreadable(environment.content(), urlForming)) {
            warnings.add("The variable \"" + ConfigStore.oneLine(name) + "\" holds an address whose scheme, host and port this tool "
                    + "cannot read the way the HTTP client reads them, for example a port above 65535 or a host written in a form the "
                    + "client does not accept. A request that can change something is refused when it would go there. Write the "
                    + "address as scheme://host:port/path and upload the environment again.");
        }
        for (String name : ProductionDestinations.encodedSeparators(environment.content(), urlForming)) {
            warnings.add("The variable \"" + ConfigStore.oneLine(name) + "\" holds an address whose path has an encoded slash, "
                    + "backslash, semicolon or dot segment in it, such as %2F, or a percent-escape of a byte above 0x7F, such as %C0. "
                    + "A server in front of the service can decode it before "
                    + "it reads the path, so no request is sent to that address. Write the path without it and upload the environment "
                    + "again.");
        }
        if (environment.kind() != EnvironmentKind.PROD) {
            Set<String> sharing = new LinkedHashSet<>();
            Set<String> unreadHost = new LinkedHashSet<>();
            for (ProductionDestinations.Found found : ProductionDestinations.of(environment.content(), urlForming)) {
                for (ConfigSnapshot.KnownDestination known : snapshot.remembered()) {
                    if (known.environment().equals(environment.name()) || !known.destination().sameOrigin(found.destination())) {
                        continue;
                    }
                    // A production address that could not be read stands for its whole host, so
                    // "the same scheme, host and port" would not be true of it.
                    if (!known.destination().readable()) {
                        unreadHost.add(known.environment());
                    } else if (!known.destination().overlaps(found.destination())) {
                        sharing.add(known.environment());
                    }
                }
            }
            for (String production : unreadHost) {
                warnings.add("This environment is on the host of the production environment \"" + ConfigStore.oneLine(production)
                        + "\", an address of which could not be read the way the HTTP client reads it. This tool takes every port and "
                        + "every path of that host as production, so a write from this environment to it is refused. Upload \""
                        + ConfigStore.oneLine(production) + "\" again with its address written plainly, as scheme://host:port/path.");
            }
            for (String production : sharing) {
                warnings.add("This environment is on the same server, by scheme, host and port, as the production environment \""
                        + ConfigStore.oneLine(production) + "\", under another base path. This tool treats it as "
                        + environment.kind().wire() + " because the base path differs, and sends writes to it without an unlock. If "
                        + "that server routes this path to production, upload this environment with the kind prod.");
            }
        }
        return warnings;
    }
}
