package com.thehiddenbrain.interop.epa.pave.model;

/** One header as shown in the wire view. A credential value is already redacted by the time it gets here. */
public record WireHeader(String name, String value) {
}
