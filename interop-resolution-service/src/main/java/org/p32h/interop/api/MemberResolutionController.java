package org.p32h.interop.api;

import org.p32h.interop.service.ResolutionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The one operation, {@code POST /v1/interop/resolve}. */
@RestController
@Tag(name = "Member resolution", description = "Resolve an EMR-supplied member id against the plan's member records for a date of service")
public class MemberResolutionController {

    public static final String PATH = "/v1/interop/resolve";

    private final ResolutionService service;

    public MemberResolutionController(ResolutionService service) {
        this.service = service;
    }

    @Operation(summary = "Resolve a member id for a date of service",
            description = "Finds the member behind the id the EMR typed, says whether coverage is active on the date of service (or on every day "
                    + "of a period of service), and returns the id as resolved and in every configured UM vendor's format (memberId.forVendors), each entry with the payer for that vendor's request (payerId, payerName). "
                    + "When several members on the plan match and no dateOfBirth settles it, the outcome is AMBIGUOUS and the message says "
                    + "what to resend; the other members are never listed. clientId, clientType (EXT or INT), requestId and memberId are required; unknown properties are ignored. A sent date is the date evaluated; an "
                    + "unusable date is a 400. POST because the member id is PHI and must not appear in URLs; the operation is a pure read "
                    + "and may be repeated.",
            parameters = @Parameter(in = ParameterIn.HEADER, name = "X-Correlation-Id", required = false,
                    description = "Optional transport-level id for the call, 1 to 64 characters of letters, digits, '.', '_', ':' or '-'; echoed in the response header and in error bodies as correlationId, or generated when missing or invalid",
                    schema = @Schema(type = "string", maxLength = 64, pattern = "^[A-Za-z0-9._:-]{1,64}$")))
    @ApiResponse(responseCode = "200", description = "outcome ACTIVE | INACTIVE | NOT_FOUND | AMBIGUOUS; memberId.forVendors, each vendor with its payer, present for ACTIVE and INACTIVE",
            content = @Content(schema = @Schema(implementation = MemberResolutionResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST for a missing or unusable clientId, clientType or requestId, a missing memberId, an unusable date or a body that is not JSON; MEMBER_LOOKUP_REJECTED when the member lookup answered 400 (details[0].code HTTP_400, the lookup's text in the message)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "DOB_MISMATCH: the date of birth matches no record for this id", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "404", description = "INVALID_REQUEST / ROUTE_NOT_FOUND: wrong path; a connector defect (a member that is not found is a 200 with outcome NOT_FOUND)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "405", description = "INVALID_REQUEST / METHOD_NOT_ALLOWED: the operation is POST only", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "406", description = "INVALID_REQUEST / NOT_ACCEPTABLE: the service answers application/json only", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "415", description = "INVALID_REQUEST / UNSUPPORTED_MEDIA_TYPE: the request body must be application/json", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "MEMBER_LOOKUP_ERROR or MEMBER_LOOKUP_INVALID_RESPONSE (the member lookup); MEMBER_PLAN_ERROR or MEMBER_PLAN_INVALID_RESPONSE (the member plan lookup)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "MEMBER_LOOKUP_UNAVAILABLE or MEMBER_PLAN_UNAVAILABLE; retry after the Retry-After seconds",
            headers = @Header(name = "Retry-After", description = "Seconds to wait before retrying", schema = @Schema(type = "integer", example = "10")),
            content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "500", description = "INTERNAL_ERROR: the service itself failed; retry once later, then alert the service owners with the correlationId and requestId", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping(value = PATH, consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public MemberResolutionResponse resolve(@RequestBody MemberResolutionRequest request) {
        return service.resolve(request, CorrelationFilter.current());
    }
}
