package org.point32health.memberid.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.point32health.memberid.support.Masking;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends when it wants the member id in every vendor's format. Only the member id is required. A
 * {@code vendor} is accepted and ignored (so the {@code /resolve} payload can be sent as is) and unknown properties
 * are ignored. A date that is sent is always the date evaluated: an unusable one is a 400, never replaced by today
 * (owner feedback 20).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Vendor map request: the member id is required; a vendor and unknown properties are ignored; a sent date must be usable")
public record VendorMapRequest(
        @Schema(description = "The member id exactly as the EMR supplied it. Looked up unchanged (surrounding whitespace removed); no shape, length or character check",
                example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd. Defaults to today when omitted (dateOfServiceDefaulted is true). A sent value is the date evaluated: any future date is judged on the coverage on record; not a real date, or more than 10 years back, is a 400.",
                example = "2026-10-15") String dateOfService,
        @Schema(description = "Optional last date of service, yyyy-MM-dd, when the service covers a period: coverage must hold on every day from dateOfService to it. Not before dateOfService; needs dateOfService.", example = "2026-10-20") String dateOfServiceEnd,
        @Schema(type = "string", description = "Accepted and ignored, whatever its JSON type: this operation answers for every vendor. Lets Onyx send the /resolve payload unchanged.",
                example = "EVICORE") Object vendor,
        @Schema(description = "Optional patient hints used only to pick among the records the lookup returned") Patient patient) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    @Schema(name = "VendorMapPatient") // distinct from ResolveRequest.Patient in the OpenAPI document
    public record Patient(
            @Schema(description = "Patient date of birth, yyyy-MM-dd. Used only inside this service; never logged, never echoed.", example = "1950-03-15") String dateOfBirth) {

        @Override
        public String toString() {
            return "Patient[dateOfBirth=" + (dateOfBirth == null || dateOfBirth.isBlank() ? "absent" : "supplied") + "]";
        }
    }

    @Override
    public String toString() {
        return "VendorMapRequest[memberId=" + Masking.memberId(memberId) + ", dateOfService=" + dateOfService + ", dateOfServiceEnd=" + dateOfServiceEnd + ", vendor="
                + (vendor == null ? "absent" : "supplied") + ", patient=" + patient + "]";
    }
}
