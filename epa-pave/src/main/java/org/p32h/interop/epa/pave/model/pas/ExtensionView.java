package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One extension as returned, by its url and value. An extension made of further extensions lists
 * them as parts, so a vendor extension this tool has never seen is still shown where it sat.
 *
 * @param value the extension's own value. Absent for an extension that carries only parts
 * @param parts the extensions nested inside it, in the order returned
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExtensionView(String url, Shown value, List<ExtensionView> parts) {

    public ExtensionView {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }
}
