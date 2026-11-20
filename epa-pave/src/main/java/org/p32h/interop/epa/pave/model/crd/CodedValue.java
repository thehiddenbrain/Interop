package org.p32h.interop.epa.pave.model.crd;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Presence;

/**
 * A coded element from a response with what the tool knows about the code.
 *
 * @param code       the code as returned, or null when the element was not returned
 * @param display    the meaning of the code, or "Unrecognised code: X" when it is outside the value set
 * @param recognised false when the code is not one the tool knows. It is still shown
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodedValue(Presence presence, String code, String display, boolean recognised) {

    public static CodedValue notReturned() {
        return new CodedValue(Presence.ABSENT, null, null, true);
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
