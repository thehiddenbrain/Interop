package com.thehiddenbrain.interop.epa.pave.model.crd;

import com.thehiddenbrain.interop.epa.pave.model.Shown;

/** One link on a CDS Hooks card. A link of type smart is how CRD hands a tester on to DTR. */
public record CardLinkView(Shown label, Shown url, Shown type, Shown appContext) {
}
