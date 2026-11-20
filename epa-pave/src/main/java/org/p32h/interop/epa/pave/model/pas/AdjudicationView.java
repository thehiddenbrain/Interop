package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Presence;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One adjudication of an item, with the review actions it carries and every other extension on it.
 *
 * @param reviewActions each review action on this adjudication. Normally none or one
 * @param extensions    every other extension on the adjudication
 * @param lists         whether {@code extension} was not returned, null, empty or held something
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdjudicationView(String foundAt, Shown category, Shown reason, Shown amount, Shown value,
                               List<ReviewActionView> reviewActions, List<ExtensionView> extensions,
                               Map<String, Presence> lists) {

    public AdjudicationView {
        lists = lists == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(lists));

        reviewActions = reviewActions == null ? List.of() : List.copyOf(reviewActions);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** Prints where it was found. Everything else comes from a response. */
    @Override
    public String toString() {
        return "AdjudicationView[" + foundAt + "]";
    }
}
