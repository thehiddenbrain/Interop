package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One adjudication of an item, with the review action it carries and every other extension on it.
 *
 * @param reviewAction the review action on this adjudication, or null when it carries none
 * @param extensions   every other extension on the adjudication
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjudicationView(String foundAt, Shown category, Shown reason, Shown amount, Shown value,
                               ReviewActionView reviewAction, List<ExtensionView> extensions) {

    public AdjudicationView {
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }
}
