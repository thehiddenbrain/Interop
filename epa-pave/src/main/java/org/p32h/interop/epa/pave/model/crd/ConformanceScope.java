package org.p32h.interop.epa.pave.model.crd;

import java.util.List;

/**
 * Which rules of the guide the conformance check of a CRD response applies, and which it does not
 * and why. Shown with the findings, so that a result with no finding is not read as a pass of
 * rules nobody checked.
 *
 * @param version    the version of the guide the rules come from, for example "CRD 2.0.1"
 * @param applied    each rule the check applies, in plain language, with its key where the guide gives one
 * @param notApplied each rule of that version the check does not apply, with the reason
 */
public record ConformanceScope(String version, List<String> applied, List<String> notApplied) {

    public ConformanceScope {
        applied = applied == null ? List.of() : List.copyOf(applied);
        notApplied = notApplied == null ? List.of() : List.copyOf(notApplied);
    }
}
