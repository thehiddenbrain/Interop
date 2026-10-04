package org.point32health.memberid.api;

import org.point32health.memberid.support.Masking;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends. Dates travel as strings so that every problem can be reported together instead of
 * one at a time from the JSON parser.
 */
@Schema(description = "Member id resolution request")
public record ResolveRequest(
        @Schema(description = "The member id exactly as the EMR supplied it. Sent to MMI unchanged (surrounding whitespace removed); no shape, length or character check",
                example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd. Defaults to today when omitted.", example = "2026-10-15") String dateOfService,
        @Schema(description = "UM vendor code or alias (case-insensitive)", example = "EVICORE", requiredMode = Schema.RequiredMode.REQUIRED) String vendor,
        @Schema(description = "Optional patient hints used only to verify or pick among the records MMI returned") Patient patient) {

    public record Patient(
            @Schema(description = "Patient date of birth, yyyy-MM-dd. Never sent to MMI, never logged, never echoed.", example = "1950-03-15") String dateOfBirth) {

        @Override
        public String toString() {
            return "Patient[dateOfBirth=" + (dateOfBirth == null || dateOfBirth.isBlank() ? "absent" : "supplied") + "]";
        }
    }

    @Override
    public String toString() {
        return "ResolveRequest[memberId=" + Masking.memberId(memberId) + ", dateOfService=" + dateOfService + ", vendor=<len "
                + (vendor == null ? 0 : vendor.length()) + ">, patient=" + patient + "]";
    }
}
