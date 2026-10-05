package org.point32health.memberid.api;

import org.point32health.memberid.support.Masking;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends. Dates travel as strings so that every problem can be reported together instead of
 * one at a time from the JSON parser.
 */
@Schema(description = "Member id resolution request")
public record ResolveRequest(
        @Schema(description = "The member id exactly as the EMR supplied it. Looked up unchanged (surrounding whitespace removed); no shape, length or character check",
                example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd; the first date when the service covers a period. Defaults to today when omitted. A sent date is the date judged; any future date is judged on the coverage on record.", example = "2026-10-15") String dateOfService,
        @Schema(description = "Optional last date of service, yyyy-MM-dd, when the service covers a period: coverage must hold on every day from dateOfService to it. Not before dateOfService; needs dateOfService.", example = "2026-10-20") String dateOfServiceEnd,
        @Schema(description = "UM vendor code or alias (case-insensitive)", example = "EVICORE", requiredMode = Schema.RequiredMode.REQUIRED) String vendor,
        @Schema(description = "Optional patient hints used only to verify or pick among the records the lookup returned") Patient patient) {

    public record Patient(
            @Schema(description = "Patient date of birth, yyyy-MM-dd. Used only inside this service; never logged, never echoed.", example = "1950-03-15") String dateOfBirth) {

        @Override
        public String toString() {
            return "Patient[dateOfBirth=" + (dateOfBirth == null || dateOfBirth.isBlank() ? "absent" : "supplied") + "]";
        }
    }

    @Override
    public String toString() {
        return "ResolveRequest[memberId=" + Masking.memberId(memberId) + ", dateOfService=" + dateOfService + ", dateOfServiceEnd=" + dateOfServiceEnd + ", vendor=<len "
                + (vendor == null ? 0 : vendor.length()) + ">, patient=" + patient + "]";
    }
}
