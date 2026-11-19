package org.p32h.interop.epa.pave.model.crd;

import org.p32h.interop.epa.pave.model.Shown;

/** One action, either on a card suggestion or as a system action. */
public record ActionView(String foundAt, Shown type, Shown description, Shown resourceType, Shown resourceId) {
}
