package org.point32health.interop.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/** One problem inside an error response. {@code field} is the JSON path of the offending request field, when there is one. */
public record ErrorDetail(@JsonInclude(JsonInclude.Include.NON_NULL) String field, String code, String message) {
}
