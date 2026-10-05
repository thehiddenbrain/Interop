package org.p32h.interop.api;

import org.p32h.interop.service.ResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Member ID resolution", description = "Resolve an EMR-supplied member id against the plan's member records: for one UM vendor (/resolve) or for every vendor at once (/vendor-map)")
public class ResolveController {

    private final ResolutionService service;

    public ResolveController(ResolutionService service) {
        this.service = service;
    }

    @Operation(summary = "Resolve a member id for a vendor and a date of service",
            description = "Verifies the id against the plan's member records, returns it as stored and in the vendor's format, and says whether coverage is active on the date of service. "
                    + "POST because the member id is PHI and must not appear in URLs; the operation is a pure read and may be repeated.")
    @ApiResponse(responseCode = "200", description = "outcome ACTIVE | INACTIVE | NOT_FOUND | AMBIGUOUS",
            content = @Content(schema = @Schema(implementation = ResolveResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST or UNKNOWN_VENDOR (this service's validation), or MEMBER_LOOKUP_REJECTED when the member lookup answered 400 (details[0].code HTTP_400, the lookup's text in the message)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "DOB_MISMATCH: the date of birth matches no record for this id", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "MEMBER_LOOKUP_ERROR or MEMBER_LOOKUP_INVALID_RESPONSE", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "MEMBER_LOOKUP_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping(value = "/api/v1/member-ids/resolve", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResolveResponse resolve(@RequestBody ResolveRequest request) {
        return service.resolve(request, CorrelationFilter.current());
    }

    @Operation(summary = "Vendor map: the member id in every vendor's format",
            description = "The same verification as /resolve, without naming a vendor: returns the id as stored, one entry per configured "
                    + "vendor with the id in that vendor's format, and whether coverage is active on the date of service. For when Onyx does not yet "
                    + "know which vendor will receive the authorization. Only memberId is required; a vendor or unknown property is "
                    + "accepted and ignored. A sent date is the date evaluated; an unusable date is a 400. Pure read; may be repeated.")
    @ApiResponse(responseCode = "200", description = "outcome ACTIVE | INACTIVE | NOT_FOUND | AMBIGUOUS; vendorMemberIds present for ACTIVE and INACTIVE",
            content = @Content(schema = @Schema(implementation = VendorMapResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST for a missing memberId, an unusable date or a body that is not JSON; MEMBER_LOOKUP_REJECTED when the member lookup answered 400 (details[0].code HTTP_400, the lookup's text in the message)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "DOB_MISMATCH: the date of birth matches no record for this id", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "MEMBER_LOOKUP_ERROR or MEMBER_LOOKUP_INVALID_RESPONSE", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "MEMBER_LOOKUP_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping(value = "/api/v1/member-ids/vendor-map", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public VendorMapResponse vendorMap(@RequestBody VendorMapRequest request) {
        return service.vendorMap(request, CorrelationFilter.current());
    }
}
