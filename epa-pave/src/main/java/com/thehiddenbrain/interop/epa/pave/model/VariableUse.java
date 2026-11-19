package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One placeholder in a template and how it resolved.
 *
 * @param name    the placeholder name without braces
 * @param source  the layer that supplied the value, or UNRESOLVED
 * @param secret  true when the value is a secret and must never be displayed
 * @param display the value to show: the real value, a mask for a secret, or null when unresolved
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VariableUse(String name, VariableSource source, boolean secret, String display) {
}
