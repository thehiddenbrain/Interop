package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One adjudication of an item, with the review actions it carries and every other extension on it.
 *
 * @param reviewActions each review action on this adjudication. Normally none or one
 * @param extensions    every other extension on the adjudication
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjudicationView(String foundAt, Shown category, Shown reason, Shown amount, Shown value,
                               List<ReviewActionView> reviewActions, List<ExtensionView> extensions) {

    public AdjudicationView {
        reviewActions = reviewActions == null ? List.of() : List.copyOf(reviewActions);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }
}
