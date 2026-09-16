package com.thehiddenbrain.interop.patientaccess.api;

import com.thehiddenbrain.interop.patientaccess.environment.EnvironmentService;
import com.thehiddenbrain.interop.patientaccess.search.MemberSearchRequest;
import com.thehiddenbrain.interop.patientaccess.search.MemberSearchResult;
import com.thehiddenbrain.interop.patientaccess.search.MemberSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/environments/{id}/members", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Member search", description = "Find members by member id, name / birth date / gender, patient id or any Patient search parameter")
public class SearchController {

    private final EnvironmentService environments;
    private final MemberSearchService search;

    public SearchController(EnvironmentService environments, MemberSearchService search) {
        this.environments = environments;
        this.search = search;
    }

    @Operation(summary = "Search members", description = "Runs the IG searches that apply to the fields given and returns the patients plus every query sent.")
    @PostMapping(value = "/search", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberSearchResult search(@PathVariable String id, @RequestBody MemberSearchRequest request) {
        return search.search(environments.require(id), request);
    }
}
