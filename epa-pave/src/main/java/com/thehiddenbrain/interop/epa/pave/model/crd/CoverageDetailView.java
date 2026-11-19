package com.thehiddenbrain.interop.epa.pave.model.crd;

import com.thehiddenbrain.interop.epa.pave.model.Shown;

/** One entry of the coverage information detail list: a code and its value. */
public record CoverageDetailView(Shown code, Shown codeDisplay, Shown value, Shown qualifier) {
}
