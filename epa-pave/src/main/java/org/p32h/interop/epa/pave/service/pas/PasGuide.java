package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.RequestShape;
import org.p32h.interop.epa.pave.model.RequestShapes;

/**
 * The version of Da Vinci PAS that Submit and the inquiry are held against: what the request
 * carries, what counts as left out, and what "To complete before sending" lists.
 *
 * <p>It is the environment's setting {@link RequestShape#PAS_GUIDE}, 2.0.1 unless the environment
 * chooses 2.2.1, because the payer this tool was built for runs 2.0.1. Each version is read from the
 * guide's own source; a requirement of one is never claimed under the other.
 */
public enum PasGuide {
    V201(RequestShape.PAS_201, "US Core 3.1.1"),
    V221(RequestShape.PAS_221, "US Core 7.0.0");

    private final String version;
    private final String usCore;

    PasGuide(String version, String usCore) {
        this.version = version;
        this.usCore = usCore;
    }

    /** The version alone, such as 2.0.1. */
    public String version() {
        return version;
    }

    /** The guide as a note names it, such as "PAS 2.0.1". */
    public String label() {
        return "PAS " + version;
    }

    /** The guide by its full name, as the options and the request notes name it. */
    public String fullName() {
        return "Da Vinci PAS " + version;
    }

    /** The US Core version the PAS profiles of this version are built on. */
    public String usCore() {
        return usCore;
    }

    /** True for 2.2.1, whose profiles require more than 2.0.1's. */
    public boolean is221() {
        return this == V221;
    }

    /** The guide a call's shapes name; the default, 2.0.1, when they name none. */
    public static PasGuide of(RequestShapes shapes) {
        String value = shapes == null ? RequestShape.PAS_GUIDE.defaultValue() : shapes.value(RequestShape.PAS_GUIDE);
        return RequestShape.PAS_221.equals(value) ? V221 : V201;
    }
}
