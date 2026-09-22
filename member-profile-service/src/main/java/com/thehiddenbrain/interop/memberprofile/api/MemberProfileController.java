package com.thehiddenbrain.interop.memberprofile.api;

import com.thehiddenbrain.interop.memberprofile.service.MemberProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "Member profile")
public class MemberProfileController {

    private final MemberProfileService service;

    public MemberProfileController(MemberProfileService service) {
        this.service = service;
    }

    @Operation(summary = "Member identity, segmentation flags and family permissions for one member",
            description = "Called by the League portal and mobile app at login. Evaluated on demand: member data "
                    + "from MemberDomain, rules from this service's tables. Never cached.")
    @GetMapping("/api/v1/members/{memberId}/profile")
    public ResponseEntity<MemberProfileResponse> profile(
            @Parameter(description = "Member id as known to MemberDomain")
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,30}") String memberId,
            @Parameter(description = "True when a CSR is impersonating the member")
            @RequestHeader(name = "X-Impersonating", defaultValue = "false") boolean impersonating,
            @Parameter(description = "Include the rule-by-rule evaluation trace; for support and testing")
            @RequestParam(defaultValue = "false") boolean explain) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(service.profile(memberId, impersonating, explain));
    }
}
