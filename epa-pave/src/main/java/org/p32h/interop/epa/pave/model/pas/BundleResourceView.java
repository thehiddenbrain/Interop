package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * A resource a bundle carried beside the ones this tool reads, such as an echoed Patient. It is
 * shown with its type and id and everything it holds, beside the entry it came in, so it does not
 * fill the list of fields nobody anticipated.
 *
 * @param fields everything the resource holds besides its type and id
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BundleResourceView(String foundAt, Shown resourceType, Shown id, Shown fullUrl, List<AdditionalField> fields) {

    public BundleResourceView {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    /** Prints where it was found. Everything else comes from a response. */
    @Override
    public String toString() {
        return "BundleResourceView[" + foundAt + "]";
    }
}
