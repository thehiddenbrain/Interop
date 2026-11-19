package org.p32h.interop.epa.pave.service.ledger;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One thing that came back different when a run was sent again.
 *
 * @param field the field of the ledger record that differs, by its name on the record
 * @param was   the value on the original run. Left out when the original had none
 * @param now   the value on the run that was sent again. Left out when it has none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReplayDifference(String field, String was, String now) {
}
