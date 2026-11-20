package org.p32h.interop.epa.pave.model.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Presence;

/**
 * A coded element from a response with what the tool knows about the code.
 *
 * @param code       the code as returned, or null when the element was not returned
 * @param display    the meaning of the code, or "Unrecognised code: X" when no version the tool reads defines it
 * @param recognised false when the code is not one the tool knows. It is still shown
 * @param version    the version of the guide whose value set holds the code, such as "CRD 2.0.1" or a
 *                   later version named as such. Null when the code was not returned, or when no
 *                   version this tool can read defines it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodedValue(Presence presence, String code, String display, boolean recognised, String version) {

    /** A value whose code no versioned value set is held for, such as a PAS code. */
    public CodedValue(Presence presence, String code, String display, boolean recognised) {
        this(presence, code, display, recognised, null);
    }

    public static CodedValue notReturned() {
        return new CodedValue(Presence.ABSENT, null, null, true, null);
    }

    public boolean is(String expectedCode) {
        return code != null && code.equals(expectedCode);
    }

    /** Prints whether a code was there and known. The code and its display come from a response. */
    @Override
    public String toString() {
        return "CodedValue[" + presence + ", " + (recognised ? "recognised" : "not recognised") + "]";
    }
}
