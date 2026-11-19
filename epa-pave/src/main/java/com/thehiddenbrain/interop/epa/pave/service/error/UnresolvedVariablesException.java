package com.thehiddenbrain.interop.epa.pave.service.error;

import com.thehiddenbrain.interop.epa.pave.model.EpaOperation;
import java.util.List;

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

    public UnresolvedVariablesException(EpaOperation operation, List<String> variables, String environmentName, String template) {
        super("Unresolved placeholders: " + variables);
        this.operation = operation;
        this.variables = variables == null ? List.of() : List.copyOf(variables);
        this.environmentName = environmentName;
        this.template = template;
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
}
