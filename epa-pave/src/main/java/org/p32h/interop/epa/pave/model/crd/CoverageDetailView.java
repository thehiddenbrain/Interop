package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Shown;

/** One entry of the coverage information detail list: a code, its value and an optional qualifier. */
public record CoverageDetailView(Shown code, Shown value, Shown qualifier) {
}
