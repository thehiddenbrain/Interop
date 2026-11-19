package org.p32h.interop.epa.pave.model.pas;

import org.p32h.interop.epa.pave.model.Shown;

/** One process note of a ClaimResponse. Its text is the payer's wording: shown, never interpreted. */
public record ProcessNoteView(Shown number, Shown type, Shown text) {
}
