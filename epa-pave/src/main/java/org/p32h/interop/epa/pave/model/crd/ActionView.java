package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One action, either on a card suggestion or as a system action.
 *
 * <p>An action is mostly the resource it carries. The parts this tool understands have their own
 * slots, and the coverage information on the resource is read into its own view. Everything else
 * the resource holds is listed in {@code resource}, beside the action it belongs to, so an echoed
 * order does not fill the list of fields nobody anticipated.
 *
 * @param foundAt           where in the response the action sat
 * @param resourceId        the action's own resourceId field, which a delete action uses
 * @param resourceLogicalId the id of the resource the action carries. Kept apart from
 *                          {@code resourceId} so that when both are sent both are shown
 * @param resource          the rest of the resource the action carries
 */
public record ActionView(String foundAt, Shown type, Shown description, Shown resourceType, Shown resourceId,
                         Shown resourceLogicalId, List<AdditionalField> resource) {

    public ActionView {
        resource = resource == null ? List.of() : List.copyOf(resource);
    }
}
