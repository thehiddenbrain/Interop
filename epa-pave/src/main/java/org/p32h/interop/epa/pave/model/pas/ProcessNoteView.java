package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;

/** One process note of a ClaimResponse. Its text is the payer's wording: shown, never interpreted. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessNoteView(Shown number, Shown type, Shown text) {
}
