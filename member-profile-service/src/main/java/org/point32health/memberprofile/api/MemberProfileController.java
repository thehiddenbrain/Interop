package org.point32health.memberprofile.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.point32health.memberprofile.service.MemberProfileService;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The login-time call. POST only: the member id is in the body, and the answer is never cached. */
@RestController
@RequestMapping(value = "/api/v1/member-profile", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Member profile", description = "Member identity, segmentation flags and family permissions for one member")
public class MemberProfileController {

    private final MemberProfileService service;

    public MemberProfileController(MemberProfileService service) {
        this.service = service;
    }

    @Operation(summary = "Member identity, segmentation flags and family permissions for one member",
            description = "Called by the League portal and mobile app at login. Evaluated on demand: the member from "
                    + "MemberDomain, the rules from this service's tables. Nothing is cached.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The profile"),
            @ApiResponse(responseCode = "400", description = "Malformed body or invalid member id"),
            @ApiResponse(responseCode = "404", description = "MemberDomain does not know the member"),
            @ApiResponse(responseCode = "422", description = "Member data cannot be evaluated (no company, no age, unknown relationship)"),
            @ApiResponse(responseCode = "502", description = "MemberDomain answered with an error"),
            @ApiResponse(responseCode = "504", description = "MemberDomain unreachable or timed out")})
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<MemberProfileResponse> profile(@Valid @RequestBody MemberProfileRequest request) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.profile(request));
    }
}
