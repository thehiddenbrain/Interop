package org.p32h.interop.epa.pave.service.config;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.RequestShape;

/**
 * What one environment states for itself on screen (plan 5.1): how it signs in, its timeouts, an
 * address per operation, the member ID rule, request shapes and values. Each part may be left
 * unstated, and then whatever applied before applies: {@code onyx.auth}, application.yml's
 * timeouts, the collection's mapped request, the packed rule, the evidence of the vendor's sample,
 * the export's variables. Nothing here is a default filled in for the tester.
 *
 * <p>Holds a client secret or a bearer token and the values typed, so {@link #toString()} names
 * the parts stated and never a value.
 *
 * @param signIn       how it signs in, or null when not stated
 * @param timeouts     its own timeouts, never null; {@link Timeouts#notStated()} when it states none
 * @param addresses    a url template per outbound operation it types, never null
 * @param memberIdRule the rule its member IDs are sent by, or null when not stated
 * @param shapes       the value chosen per request shape, by option name, never null
 * @param values       variable name to value, never null, in the order typed
 */
public record EnvironmentSettings(SignInSettings signIn, Timeouts timeouts, Map<EpaOperation, String> addresses,
                                  MemberIdRule memberIdRule, Map<RequestShape, String> shapes, Map<String, String> values) {

    private static final EnvironmentSettings NONE = new EnvironmentSettings(null, null, null, null, null, null);

    public EnvironmentSettings {
        timeouts = timeouts == null ? Timeouts.notStated() : timeouts;
        addresses = addresses == null || addresses.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(addresses));
        shapes = shapes == null || shapes.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(shapes));
        values = values == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** An environment that states nothing: everything applies as it did before settings existed. */
    public static EnvironmentSettings none() {
        return NONE;
    }

    /** Whether the environment states nothing at all. */
    public boolean empty() {
        return signIn == null && timeouts.empty() && addresses.isEmpty() && memberIdRule == null && shapes.isEmpty()
                && values.isEmpty();
    }

    /** Names what is stated and never a value: a value can be a secret, or a url with credentials in it. */
    @Override
    public String toString() {
        return "EnvironmentSettings[signIn=" + signIn + ", timeouts=" + timeouts + ", addresses=" + addresses.keySet()
                + ", memberIdRule=" + memberIdRule + ", shapes=" + shapes.keySet() + ", values=" + values.keySet() + "]";
    }
}
