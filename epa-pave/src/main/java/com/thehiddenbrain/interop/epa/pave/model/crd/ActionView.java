package com.thehiddenbrain.interop.epa.pave.model.crd;

import com.thehiddenbrain.interop.epa.pave.model.Shown;

/** One action, either on a card suggestion or as a system action. */
public record ActionView(String foundAt, Shown type, Shown description, Shown resourceType, Shown resourceId) {
}
