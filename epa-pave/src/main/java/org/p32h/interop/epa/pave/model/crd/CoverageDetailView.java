package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Shown;

/**
 * One entry of the coverage information detail list.
 *
 * @param code          what the detail is about
 * @param value         its value
 * @param qualification text that qualifies the detail, the third part CRD 2.0.1 defines
 * @param category      the category the CRD 2.2.0 ballot adds to a detail. CRD 2.0.1 has no such part
 */
public record CoverageDetailView(Shown code, Shown value, Shown qualification, Shown category) {
}
