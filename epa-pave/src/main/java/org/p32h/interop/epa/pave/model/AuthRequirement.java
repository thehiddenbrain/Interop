package org.p32h.interop.epa.pave.model;

/**
 * What the procedure code list says about prior authorization for one code.
 *
 * <p>UNKNOWN means the list does not say. It is never shown as "no".
 */
public enum AuthRequirement {
    REQUIRED,
    NOT_REQUIRED,
    UNKNOWN
}
