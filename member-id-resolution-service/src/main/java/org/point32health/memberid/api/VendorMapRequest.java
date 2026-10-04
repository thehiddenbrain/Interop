package org.point32health.memberid.api;

import org.point32health.memberid.support.Masking;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What Onyx sends when it does not (yet) know which vendor will receive the authorization: the member id and,
 * optionally, the date of service and the patient's date of birth. No vendor.
 */
@Schema(description = "Vendor map request: the member id, no vendor")
public record VendorMapRequest(
        @Schema(description = "The member id exactly as the EMR supplied it. Sent to MMI unchanged (surrounding whitespace removed); no shape, length or character check",
                example = "123456789", requiredMode = Schema.RequiredMode.REQUIRED) String memberId,
        @Schema(description = "Date of service, yyyy-MM-dd. Defaults to today when omitted.", example = "2026-10-15") String dateOfService,
        @Schema(description = "Optional patient hints used only to verify or pick among the records MMI returned") ResolveRequest.Patient patient) {

    @Override
    public String toString() {
        return "VendorMapRequest[memberId=" + Masking.memberId(memberId) + ", dateOfService=" + dateOfService + ", patient=" + patient + "]";
    }
}
