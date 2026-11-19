package com.thehiddenbrain.interop.epa.pave.service.error;

import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import java.util.List;
import java.util.Map;

/**
 * A url, header or body still contains a placeholder that no layer can supply. Nothing was sent.
 *
 * <p>This is raised before sending on purpose. A request to a url that still contains
 * {@code {{baseUrl}}} fails in a way that looks like a network problem and is not one. Answered
 * with HTTP 422, because typing the value on the form is one way to fix it.
 */
public class UnresolvedVariablesException extends RuntimeException {

    private final EpaOperation operation;
    private final List<String> variables;
    private final String environmentName;
    private final String template;
    private final Map<String, String> reasons;

    public UnresolvedVariablesException(EpaOperation operation, List<String> variables, String environmentName, String template) {
        this(operation, variables, environmentName, template, Map.of());
    }

    /**
     * @param reasons for a variable that is defined but still has no value, why: for example that
     *                it is blank in the environment, or that the operating system variable it
     *                points at is not set. A variable with no entry is simply not defined anywhere
     */
    public UnresolvedVariablesException(EpaOperation operation, List<String> variables, String environmentName, String template,
                                        Map<String, String> reasons) {
        super("Unresolved placeholders: " + variables);
        this.operation = operation;
        this.variables = variables == null ? List.of() : List.copyOf(variables);
        this.environmentName = environmentName;
        this.template = template;
        this.reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
    }

    public EpaOperation operation() {
        return operation;
    }

    public List<String> variables() {
        return variables;
    }

    /** The active environment, or null when none is active. */
    public String environmentName() {
        return environmentName;
    }

    /** The template that could not be resolved, placeholders intact. */
    public String template() {
        return template;
    }

    /** Why a defined variable has no value, by variable name. Empty for a variable that is not defined at all. */
    public Map<String, String> reasons() {
        return reasons;
    }
}
