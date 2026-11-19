package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The environment a call was made against.
 *
 * @param name the environment name, or null when no environment is active
 * @param kind the kind, or null when no environment is active
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvironmentRef(String name, EnvironmentKind kind) {

    private static final EnvironmentRef NONE = new EnvironmentRef(null, null);

    /**
     * No environment is active. Mock needs none. On live a read can still go to a fallback url, but
     * a write is refused: with no environment there is no kind, so the tool cannot tell whether the
     * target is production.
     */
    public static EnvironmentRef none() {
        return NONE;
    }

    public boolean active() {
        return Texts.present(name);
    }

    public boolean production() {
        return kind == EnvironmentKind.PROD;
    }
}
