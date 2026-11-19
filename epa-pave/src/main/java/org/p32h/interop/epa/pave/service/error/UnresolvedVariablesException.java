package org.p32h.interop.epa.pave.service.error;

import org.p32h.interop.epa.pave.model.EpaOperation;
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
    private final java.util.Set<String> urlTokens;

    public UnresolvedVariablesException(EpaOperation operation, List<String> variables, String environmentName, String template) {
        this(operation, variables, environmentName, template, Map.of());
    }

    public UnresolvedVariablesException(EpaOperation operation, List<String> variables, String environmentName, String template,
                                        Map<String, String> reasons) {
        this(operation, variables, environmentName, template, reasons, java.util.Set.of());
    }

    /**
     * @param reasons for a variable that is defined but still has no value, why: for example that
     *                it is blank in the environment, or that the operating system variable it
     *                points at is not set. A variable with no entry is simply not defined anywhere
     * @param urlTokens the names among {@code variables} that are not placeholders at all but
     *                  parts of the url written as {@code {name}} or {@code :name}, which are
     *                  filled from the identifier a call is for and cannot be defined in an
     *                  environment
     */
    public UnresolvedVariablesException(EpaOperation operation, List<String> variables, String environmentName, String template,
                                        Map<String, String> reasons, java.util.Collection<String> urlTokens) {
        super("Unresolved placeholders: " + variables);
        this.operation = operation;
        this.variables = variables == null ? List.of() : List.copyOf(variables);
        this.environmentName = environmentName;
        this.template = template;
        this.reasons = reasons == null ? Map.of() : Map.copyOf(reasons);
        this.urlTokens = urlTokens == null ? java.util.Set.of() : java.util.Set.copyOf(urlTokens);
    }

    /** The names that are parts of the url filled from an identifier, not placeholders an environment can define. */
    public java.util.Set<String> urlTokens() {
        return urlTokens;
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
