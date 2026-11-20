package org.p32h.interop.epa.pave.service.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.postman.PostmanEnvironment;
import org.p32h.interop.epa.pave.postman.PostmanVariable;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.UrlNormal;
import org.p32h.interop.epa.pave.postman.VariableLayer;
import org.p32h.interop.epa.pave.postman.VariableResolver;

/**
 * The destinations an environment's own values point at: the scheme, host, port and base path of
 * every variable in force whose value, resolved against that environment alone, reads as a url.
 *
 * <p>For a production environment these are the addresses a write must be unlocked for. For any
 * other environment they are compared with those, so that production cannot be uploaded again
 * under a sandbox's kind. Nothing from outside the environment is used: not the operating system,
 * whose values are secrets, and not a value typed for a run.
 */
final class ProductionDestinations {

    /** Resolves nested placeholders within one environment. It reads no operating system variable and generates nothing. */
    private static final VariableResolver WITHIN = new VariableResolver(name -> null, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), () -> "");

    private ProductionDestinations() {
    }

    static List<UrlNormal.Destination> of(PostmanEnvironment environment) {
        Set<UrlNormal.Destination> found = new LinkedHashSet<>();
        if (environment == null) {
            return List.of();
        }
        Map<String, PostmanVariable> inForce = environment.inForce();
        List<VariableLayer> layers = List.of(VariableLayer.environment(environment.name(), inForce));
        for (PostmanVariable variable : inForce.values()) {
            if (!variable.hasValue()) {
                continue;
            }
            Resolution resolved = WITHIN.resolve(variable.value(), layers);
            String value = resolved.send();
            if (value == null || !ProductionSigns.looksLikeUrl(value)) {
                continue;
            }
            UrlNormal.Destination destination = UrlNormal.destination(value);
            if (destination != null) {
                found.add(destination);
            }
        }
        return new ArrayList<>(found);
    }
}
