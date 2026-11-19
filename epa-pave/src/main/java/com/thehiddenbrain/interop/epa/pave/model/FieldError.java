package com.thehiddenbrain.interop.epa.pave.model;

/**
 * One problem with one submitted field.
 *
 * @param field   the field as the form names it, for example {@code provider.npi}
 * @param message what is wrong and what to enter instead
 */
public record FieldError(String field, String message) {
}
