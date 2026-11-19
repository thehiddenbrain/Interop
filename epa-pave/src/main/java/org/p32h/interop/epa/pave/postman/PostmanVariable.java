package org.p32h.interop.epa.pave.postman;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * One variable of a Postman environment or of a collection, as the export states it.
 *
 * @param key     the variable name, exactly as written apart from surrounding white space
 * @param value   the value as written. Null when the export gives none. Never serialised: a value
 *                can be a credential, and whatever shows a variable builds its own masked row
 * @param enabled false when the export marks the variable disabled. Postman ignores a disabled
 *                variable and so does this tool
 * @param type    the Postman type, for example default or secret, or null when the export gives none
 */
public record PostmanVariable(String key, @JsonIgnore String value, boolean enabled, String type) {

    /** The type Postman gives a variable whose value it hides on screen. */
    public static final String SECRET_TYPE = "secret";

    /** True when Postman itself typed the variable as a secret. */
    public boolean secretType() {
        return type != null && SECRET_TYPE.equalsIgnoreCase(type.strip());
    }

    /** True when the variable must be masked wherever it is shown: Postman typed it secret, or its name reads as a credential. */
    public boolean secret() {
        return secretType() || Secrets.looksSecret(key);
    }

    public boolean hasValue() {
        return Texts.present(value);
    }

    /** Prints the name and never the value. */
    @Override
    public String toString() {
        return "PostmanVariable[" + key + (enabled ? "" : ", disabled") + (secretType() ? ", secret" : "") + "]";
    }
}
