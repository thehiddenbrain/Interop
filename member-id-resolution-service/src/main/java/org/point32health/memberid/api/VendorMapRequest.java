package org.point32health.memberid.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.point32health.memberid.support.Masking;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends when it wants the member id in every vendor's format. Only the member id matters. Everything
 * else is optional and lenient: a {@code vendor} is accepted and ignored (so the {@code /resolve} payload can be
 * sent as is), unknown properties are ignored, and a date that cannot be used is ignored rather than rejected
 * (the response lists it under {@code ignoredFields}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Vendor map request: the member id; everything else is optional and never causes a rejection")
public record VendorMapRequest(
        @Schema(description = "The member id exactly as the EMR supplied it. Looked up unchanged (surrounding whitespace removed); no shape, length or character check",
                example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd. Defaults to today when omitted (dateOfServiceDefaulted is true). An unusable value is ignored: it also defaults to today and ignoredFields names it.",
                example = "2026-10-15") String dateOfService,
        @Schema(type = "string", description = "Accepted and ignored, whatever its JSON type: this operation answers for every vendor. Lets Onyx send the /resolve payload unchanged.",
                example = "EVICORE") Object vendor,
        @Schema(description = "Optional patient hints used only to pick among the records the lookup returned; an unusable value is ignored") Patient patient) {

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
        return "VendorMapRequest[memberId=" + Masking.memberId(memberId) + ", dateOfService=" + dateOfService + ", vendor="
                + (vendor == null ? "absent" : "supplied") + ", patient=" + patient + "]";
    }
}
