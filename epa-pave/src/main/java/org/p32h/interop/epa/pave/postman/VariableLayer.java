package org.p32h.interop.epa.pave.postman;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import org.p32h.interop.epa.pave.model.VariableSource;

/**
 * One place a placeholder value can come from. Resolution asks the layers in order and takes the
 * first that holds a value: what was typed for this run, then the active environment, then the
 * variables of the request's own collection, then application.yml.
 *
 * @param source  which layer this is, as it is reported beside a resolved value
 * @param label   the layer in words, for a sentence that says where a variable is defined
 * @param entries the variables the layer defines. An entry with a blank value means "defined here,
 *                and blank", which does not resolve but is worth saying
 */
public record VariableLayer(VariableSource source, String label, Map<String, Entry> entries) {

    /**
     * @param value      the value as written, or null
     * @param secretType true when Postman typed the variable as a secret
     */
    public record Entry(String value, boolean secretType) {

        /** Prints no value. */
        @Override
        public String toString() {
            return "Entry[" + (secretType ? "secret" : "value") + "]";
        }
    }

    public VariableLayer {
        entries = entries == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** What the tester typed on the form for one run. */
    public static VariableLayer runValues(Map<String, String> values) {
        return plain(VariableSource.RUN_VALUE, "the values typed for this run", values);
    }

    public static VariableLayer environment(String name, Map<String, PostmanVariable> inForce) {
        return postman(VariableSource.ENVIRONMENT, "the environment \"" + Names.shown(name) + "\"", inForce);
    }

    public static VariableLayer collection(String name, Map<String, PostmanVariable> inForce) {
        return postman(VariableSource.COLLECTION, "the collection \"" + Names.shown(name) + "\"", inForce);
    }

    /** The {@code onyx.variables} map. */
    public static VariableLayer applicationYml(Map<String, String> values) {
        return plain(VariableSource.APPLICATION_YML, "application.yml (onyx.variables)", values);
    }

    /**
     * A value the call itself names, such as the id of the subscription to delete. It is reported
     * as a run value, since it was supplied for this run, and it is asked before every other layer.
     */
    public static VariableLayer bound(String label, Map<String, String> values) {
        return plain(VariableSource.RUN_VALUE, label, values);
    }

    private static VariableLayer plain(VariableSource source, String label, Map<String, String> values) {
        // Sorted, so nothing downstream depends on the iteration order of an unordered map.
        Map<String, Entry> entries = new TreeMap<>();
        if (values != null) {
            values.forEach((key, value) -> entries.put(key, new Entry(value, false)));
        }
        return new VariableLayer(source, label, entries);
    }

    private static VariableLayer postman(VariableSource source, String label, Map<String, PostmanVariable> inForce) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        if (inForce != null) {
            inForce.forEach((key, variable) -> entries.put(key, new Entry(variable.value(), variable.secretType())));
        }
        return new VariableLayer(source, label, entries);
    }

    /** Prints the names it defines, never a value. */
    @Override
    public String toString() {
        return "VariableLayer[" + source + ", " + entries.keySet() + "]";
    }
}
