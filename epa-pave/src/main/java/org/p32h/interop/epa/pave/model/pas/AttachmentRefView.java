package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * One reference a ClaimResponse makes to a request for supporting documentation, and its status.
 *
 * @param reference the reference as returned
 * @param carried   true when the response also carries the resource the reference names, so its
 *                  status could be read. False means the status is not known, which is not the
 *                  same as the request being closed
 * @param status    the status of that resource. Absent when the resource was not carried
 * @param open      true unless the carried resource says the request for documentation is closed.
 *                  A reference whose status cannot be seen counts as open
 * @param wanted    what the payer asks for, as returned
 * @param rest      everything else the carried resource holds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AttachmentRefView(String foundAt, Shown reference, boolean carried, Shown status, boolean open, Shown category,
                                List<Shown> wanted, List<AdditionalField> rest) {

    public AttachmentRefView {
        wanted = wanted == null ? List.of() : List.copyOf(wanted);
        rest = rest == null ? List.of() : List.copyOf(rest);
    }

    /** Prints where it was found. Everything else comes from a response. */
    @Override
    public String toString() {
        return "AttachmentRefView[" + foundAt + "]";
    }
}
