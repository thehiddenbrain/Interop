package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Shown;

/**
 * A coverage information sub-extension the tool has no dedicated slot for, shown by its url and value.
 *
 * @param definedIn which version of the guide defines it, for example "defined in CRD 2.0.1", so a
 *                  later version's element is told apart from one no version defines
 */
public record SubExtensionView(String url, Shown value, String definedIn) {
}
