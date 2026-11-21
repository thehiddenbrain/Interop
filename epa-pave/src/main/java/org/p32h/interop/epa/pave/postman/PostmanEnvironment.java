package org.p32h.interop.epa.pave.postman;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A Postman environment export.
 *
 * @param postmanId the id Postman gave the environment, or null
 * @param name      the environment name. Never blank: an export without one is refused
 * @param scope     what Postman says the export is, for example environment or globals, or null
 * @param variables every variable in the file, in file order, disabled ones included so they can be listed
 * @param notes     what the parser set aside or had to decide, in words, for the upload result
 */
public record PostmanEnvironment(String postmanId, String name, String scope, List<PostmanVariable> variables, List<String> notes) {

    public PostmanEnvironment {
        variables = variables == null ? List.of() : List.copyOf(variables);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /**
     * The variables resolution uses: enabled ones only, by name. When a name is written more than
     * once the last enabled entry is the one in force, which is the entry Postman uses.
     */
    public Map<String, PostmanVariable> inForce() {
        return inForce(variables);
    }

    static Map<String, PostmanVariable> inForce(List<PostmanVariable> all) {
        Map<String, PostmanVariable> out = new LinkedHashMap<>();
        for (PostmanVariable variable : all) {
            if (variable.enabled()) {
                out.put(variable.key(), variable);
            }
        }
        return out;
    }

    /** Prints the name and a count. The values can be credentials. */
    @Override
    public String toString() {
        return "PostmanEnvironment[" + name + ", " + variables.size() + " variables]";
    }
}
