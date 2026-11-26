package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One error a ClaimResponse reported, with its code and the element it points at.
 *
 * @param itemSequence the requested item the error is about, when it names one
 * @param code         the error code with its display, as returned
 * @param codes        the code values, for a declared expectation to assert on
 * @param element      the element of the request the error points at, when the response says
 * @param path         the same as a path expression, when the response gives one
 * @param extensions   every other extension on the error
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasErrorView(String foundAt, Shown itemSequence, Shown detailSequence, Shown subDetailSequence, Shown code,
                           List<String> codes, Shown element, Shown path, Shown followupAction, List<ExtensionView> extensions) {

    public PasErrorView {
        codes = codes == null ? List.of() : List.copyOf(codes);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Prints where it was found. Everything else comes from a response. */
    @Override
    public String toString() {
        return "PasErrorView[" + foundAt + "]";
    }
}
