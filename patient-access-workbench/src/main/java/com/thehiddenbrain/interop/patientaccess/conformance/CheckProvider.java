package com.thehiddenbrain.interop.patientaccess.conformance;

import java.util.List;

/** A bean contributing several checks at once (e.g. one search-parameter check per IG-declared parameter). */
public interface CheckProvider {

    List<Check> checks();
}
