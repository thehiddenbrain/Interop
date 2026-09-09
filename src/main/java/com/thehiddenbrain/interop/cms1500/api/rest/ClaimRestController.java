package com.thehiddenbrain.interop.cms1500.api.rest;

import com.thehiddenbrain.interop.cms1500.contract.ClaimBundleResult;
import com.thehiddenbrain.interop.cms1500.contract.ClaimFault;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.service.ClaimBundleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/v1/claims")
@Tag(name = "CMS-1500 claims", description = "Fill the CMS-1500 form and bundle it with the claim's attachments")
public class ClaimRestController {

    private final ClaimBundleService service;

    public ClaimRestController(ClaimBundleService service) {
        this.service = service;
    }

    @Operation(summary = "Fill the CMS-1500 and write <claimNumber>.pdf to the shared drive",
            description = "Validates the claim, fills the NUCC 02/12 form (extra pages for more than six service "
                    + "lines), appends the attachments named <claimNumber>_<n>.<ext> from the attachments folder "
                    + "and writes the bundle to the output folder. The response says where the bundle is.")
    @ApiResponse(responseCode = "200", description = "Bundle generated")
    @ApiResponse(responseCode = "400", description = "Malformed request or validation errors",
            content = @Content(schema = @Schema(implementation = ClaimFault.class)))
    @ApiResponse(responseCode = "409", description = "Bundle exists and overwrite is disabled",
            content = @Content(schema = @Schema(implementation = ClaimFault.class)))
    @ApiResponse(responseCode = "422", description = "Attachment problem (missing, unsupported, unreadable)",
            content = @Content(schema = @Schema(implementation = ClaimFault.class)))
    @PostMapping(value = "/cms1500", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ClaimBundleResult generate(@RequestBody Cms1500Claim claim) {
        return service.generate(claim);
    }

    @Operation(summary = "Download the generated bundle for a claim")
    @ApiResponse(responseCode = "200", description = "The bundle PDF",
            content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE))
    @ApiResponse(responseCode = "404", description = "No bundle generated for this claim number",
            content = @Content(schema = @Schema(implementation = ClaimFault.class)))
    @GetMapping(value = "/{claimNumber}/bundle", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<Resource> download(@PathVariable String claimNumber) {
        Path bundle = service.existingBundle(claimNumber);
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .contentLength(Files.size(bundle))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(bundle.getFileName().toString()).build().toString())
                    .body(new FileSystemResource(bundle));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
