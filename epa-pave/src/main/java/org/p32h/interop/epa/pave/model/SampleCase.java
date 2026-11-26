package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The provider and the procedure codes a mock scenario file names for its own case, so that one
 * click on the mock scenario table gives a case that can be sent. They are written in the file,
 * not worked out, and only the mock profile serves a scenario list at all.
 *
 * @param npi            an NPI that passes the tool's own check
 * @param procedureCodes the codes, in the order the file gives them
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SampleCase(String npi, List<String> procedureCodes) {

    public SampleCase {
        procedureCodes = procedureCodes == null ? List.of() : List.copyOf(procedureCodes);
    }
}
