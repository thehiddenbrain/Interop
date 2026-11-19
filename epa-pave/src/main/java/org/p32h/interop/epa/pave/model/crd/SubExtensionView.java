package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Shown;

/** A coverage information sub-extension the tool has no dedicated slot for, shown by its url and value. */
public record SubExtensionView(String url, Shown value) {
}
