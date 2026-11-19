package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One field of a lookup result as the confirmation strip shows it.
 *
 * @param name        the tool's own name for the field, for example dateOfBirth
 * @param label       the name in plain words
 * @param value       the value with its presence, so "not returned" differs from "empty"
 * @param sourceField the field in the API response the value was read from, or null when the
 *                    response did not carry any spelling the mapping knows
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LookupField(String name, String label, Shown value, String sourceField) {

    /** Prints the field name and presence, never the value, which is member data. */
    @Override
    public String toString() {
        return "LookupField[" + name + ", " + (value == null ? null : value.presence()) + "]";
    }
}
