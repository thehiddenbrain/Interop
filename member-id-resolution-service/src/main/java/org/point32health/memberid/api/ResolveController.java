package org.point32health.memberid.api;

import org.point32health.memberid.service.ResolutionService;
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
@Tag(name = "Member ID resolution", description = "Resolve an EMR-supplied member id through MMI for a UM vendor")
public class ResolveController {

    private final ResolutionService service;

    public ResolveController(ResolutionService service) {
        this.service = service;
    }

    @Operation(summary = "Resolve a member id for a vendor and a date of service",
            description = "Validates the id with MMI, returns it as stored and in the vendor's format, and says whether coverage is active on the date of service. "
                    + "POST because the member id is PHI and must not appear in URLs; the operation is a pure read and may be repeated.")
    @ApiResponse(responseCode = "200", description = "outcome ACTIVE | INACTIVE | NOT_FOUND | AMBIGUOUS",
            content = @Content(schema = @Schema(implementation = ResolveResponse.class)))
    @ApiResponse(responseCode = "400", description = "INVALID_REQUEST or UNKNOWN_VENDOR", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "422", description = "DOB_MISMATCH: the date of birth matches no record for this id", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "502", description = "MMI_ERROR or MMI_INVALID_RESPONSE", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @ApiResponse(responseCode = "503", description = "MMI_UNAVAILABLE", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    @PostMapping(value = "/api/v1/member-ids/resolve", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResolveResponse resolve(@RequestBody ResolveRequest request) {
        return service.resolve(request, CorrelationFilter.current());
    }
}
