package org.p32h.interop.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/** One problem inside an error response. {@code field} is the JSON path of the offending request field, when there is one. */
@Schema(description = "One problem inside an error response")
public record ErrorDetail(
        @Schema(description = "The request field the problem is about, when there is one", example = "dateOfService") @JsonInclude(JsonInclude.Include.NON_NULL) String field,
        @Schema(description = "The detail code that refines error.code, for example MEMBER_ID_MISSING, DATE_OF_SERVICE_INVALID, HTTP_400, READ_TIMEOUT", example = "DATE_OF_SERVICE_INVALID",
                requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(description = "One sentence for a person", requiredMode = Schema.RequiredMode.REQUIRED) String message) {
}
