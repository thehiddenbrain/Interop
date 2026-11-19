package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A postal address as a source system returned it. Any part may be null. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PostalAddress(String line1, String line2, String city, String state, String postalCode) {

    /** Redacted, because an address is member data and a record's default toString would print it into a log. */
    @Override
    public String toString() {
        return "PostalAddress[redacted]";
    }
}
