package org.p32h.interop.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.p32h.interop.support.Masking;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends to {@code POST /v1/interop/resolve}. Only the member id is required; unknown properties, a
 * {@code vendor} included, are ignored (owner feedback 9). Dates travel as strings so every problem can be reported
 * together. A date that is sent is always the date evaluated: an unusable one is a 400, never replaced by today
 * (owner feedback 20).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Member resolution request: the member id is required; unknown properties are ignored; a sent date must be usable")
public record MemberResolutionRequest(
        @Schema(description = "The member id exactly as the EMR supplied it. Looked up unchanged (surrounding whitespace removed); no shape, length or character check. The example is a masked pattern: a THP Medicare card number is a letter and 8 digits",
                example = "S########", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd; the first date when the service covers a period. Defaults to today when omitted (dateOfServiceDefaulted is true). A sent value is the date evaluated: any future date is judged on the coverage on record; not a real date, or more than 10 years back, is a 400.",
                example = "2026-10-15") String dateOfService,
        @Schema(description = "Optional last date of service, yyyy-MM-dd, when the service covers a period: coverage must hold on every day from dateOfService to it. Not before dateOfService; needs dateOfService.", example = "2026-10-20") String dateOfServiceEnd,
        @Schema(description = "Optional patient hints used only to verify or pick among the records the lookup returned") Patient patient) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Patient(
            @Schema(description = "Patient date of birth, yyyy-MM-dd. Used only inside this service; never logged, never echoed.", example = "1950-03-15") String dateOfBirth) {

        @Override
        public String toString() {
            return "Patient[dateOfBirth=" + (dateOfBirth == null || dateOfBirth.isBlank() ? "absent" : "supplied") + "]";
        }
    }

    @Override
    public String toString() {
        return "MemberResolutionRequest[memberId=" + Masking.memberId(memberId) + ", dateOfService=" + dateOfService + ", dateOfServiceEnd="
                + dateOfServiceEnd + ", patient=" + patient + "]";
    }
}
