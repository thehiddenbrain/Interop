package org.p32h.interop.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends to {@code POST /v1/interop/resolve}. The caller identifies itself and the call ({@code clientId},
 * {@code clientType}, {@code requestId}) and names the member id; everything else is optional. Unknown properties, a
 * {@code vendor} included, are ignored. Dates travel as strings so every problem can be reported together. A date that
 * is sent is always the date evaluated: an unusable one is a 400, never replaced by today. The date of birth is never
 * logged: {@link #toString()} only says whether it was sent.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Member resolution request: clientId, clientType, requestId and memberId are required; unknown properties are ignored; a sent date must be usable")
public record MemberResolutionRequest(
        @Schema(description = "The calling system, as registered with the plan: letters, digits, '.', '_' or '-', at most 50 characters",
                example = "ONYX", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 50, pattern = "^[A-Za-z0-9._-]{1,50}$") String clientId,
        @Schema(description = "EXT for a caller outside the plan, INT for an internal one", example = "EXT",
                allowableValues = {"EXT", "INT"}, requiredMode = Schema.RequiredMode.REQUIRED) String clientType,
        @Schema(description = "The caller's id for this call, unique per request; echoed in the response and in every error response. Letters, digits, '.', '_', ':' or '-', at most 64 characters",
                example = "3f6c2a9e-8b1d-4e7a-9c5f-2d4b6a8e0c13", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 64,
                pattern = "^[A-Za-z0-9._:-]{1,64}$") String requestId,
        @Schema(description = "The member id exactly as the EMR supplied it. Looked up unchanged (surrounding whitespace removed); no shape, length or character check. The example is a masked pattern: a THP Medicare card number is a letter and 8 digits",
                example = "S########", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd; the first date when the service covers a period. Defaults to today when omitted (dateOfServiceDefaulted is true). A sent value is the date evaluated: any future date is judged on the coverage on record; not a real date, or more than 10 years back, is a 400.",
                example = "2026-10-15") String dateOfService,
        @Schema(description = "Optional last date of service, yyyy-MM-dd, when the service covers a period: coverage must hold on every day from dateOfService to it. Not before dateOfService; needs dateOfService.", example = "2026-10-20") String dateOfServiceEnd,
        @Schema(description = "Optional patient date of birth, yyyy-MM-dd, used only to verify the member or to tell apart the members on a plan who share the id. Never logged, never echoed.",
                example = "1950-03-15") String dateOfBirth) {

    @Override
    public String toString() {
        return "MemberResolutionRequest[clientId=" + clientId + ", clientType=" + clientType + ", requestId=" + requestId
                + ", memberId=" + memberId + ", dateOfService=" + dateOfService + ", dateOfServiceEnd="
                + dateOfServiceEnd + ", dateOfBirth=" + (dateOfBirth == null || dateOfBirth.isBlank() ? "absent" : "supplied") + "]";
    }
}
