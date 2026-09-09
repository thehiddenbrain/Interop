package com.thehiddenbrain.interop.cms1500.service;

import com.thehiddenbrain.interop.cms1500.attachments.Attachment;
import com.thehiddenbrain.interop.cms1500.attachments.AttachmentLocator;
import com.thehiddenbrain.interop.cms1500.config.Cms1500Properties;
import com.thehiddenbrain.interop.cms1500.contract.ClaimBundleResult;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ResultStatus;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.domain.ClaimValidationException;
import com.thehiddenbrain.interop.cms1500.domain.ClaimValidator;
import com.thehiddenbrain.interop.cms1500.domain.Patterns;
import com.thehiddenbrain.interop.cms1500.pdf.Cms1500FormFiller;
import com.thehiddenbrain.interop.cms1500.pdf.PdfBundler;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The one workflow both APIs run: validate the claim, fill the form, find the attachments,
 * write {@code <claimNumber>.pdf} to the output folder and describe what was produced.
 */
@Service
public class ClaimBundleService {

    private static final Logger log = LoggerFactory.getLogger(ClaimBundleService.class);

    private final Cms1500Properties properties;
    private final ClaimValidator validator;
    private final Cms1500FormFiller filler;
    private final AttachmentLocator locator;
    private final PdfBundler bundler;

    public ClaimBundleService(Cms1500Properties properties, ClaimValidator validator, Cms1500FormFiller filler,
                              AttachmentLocator locator, PdfBundler bundler) {
        this.properties = properties;
        this.validator = validator;
        this.filler = filler;
        this.locator = locator;
        this.bundler = bundler;
    }

    public ClaimBundleResult generate(Cms1500Claim claim) {
        if (claim == null) {
            throw new ClaimException(ErrorCode.MALFORMED_REQUEST, null, "claim is required");
        }
        ClaimValidator.Result validation = validator.validate(claim);
        if (!validation.valid()) {
            throw new ClaimValidationException(claim.getClaimNumber(), validation.errors());
        }
        String claimNumber = claim.getClaimNumber();
        List<String> warnings = new ArrayList<>(validation.warnings());

        Path target = bundlePath(claimNumber);
        boolean existed = Files.exists(target);
        if (existed && !properties.output().overwrite()) {
            throw new ClaimException(ErrorCode.BUNDLE_EXISTS, claimNumber,
                    "bundle already exists and overwrite is disabled: " + target);
        }

        List<Attachment> attachments = selectAttachments(claimNumber, warnings);

        try (PDDocument form = filler.fill(claim)) {
            PdfBundler.Outcome outcome = bundler.bundle(claimNumber, form, attachments, target,
                    properties.output().overwrite());
            log.info("claim {}: bundle {} written ({} pages: {} form + {} attachment(s))", claimNumber, target,
                    outcome.totalPages(), outcome.formPages(), attachments.size());

            ClaimBundleResult result = new ClaimBundleResult();
            result.setStatus(ResultStatus.GENERATED);
            result.setClaimNumber(claimNumber);
            result.setFileName(target.getFileName().toString());
            result.setBundlePath(target.toString());
            result.setTotalPages(outcome.totalPages());
            result.setFormPages(outcome.formPages());
            result.setAttachmentCount(outcome.attachments().size());
            result.getAttachments().addAll(outcome.attachments());
            result.getWarnings().addAll(warnings);
            result.setReplacedExisting(existed);
            result.setGeneratedAt(OffsetDateTime.now());
            return result;
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.INTERNAL_ERROR, claimNumber,
                    "cannot fill CMS-1500 form: " + e.getMessage(), e);
        }
    }

    private List<Attachment> selectAttachments(String claimNumber, List<String> warnings) {
        Cms1500Properties.Attachments cfg = properties.attachments();
        Set<String> allowed = cfg.allowedExtensionSet();
        List<Attachment> candidates = locator.locate(cfg.rootPath(), claimNumber);
        List<Attachment> selected = new ArrayList<>();
        for (Attachment candidate : candidates) {
            if (allowed.contains(candidate.extension())) {
                selected.add(candidate);
            } else if (cfg.unsupported() == Cms1500Properties.UnsupportedPolicy.SKIP) {
                warnings.add("attachment " + candidate.fileName() + " skipped: type ." + candidate.extension()
                        + " is not supported");
            } else {
                throw new ClaimException(ErrorCode.UNSUPPORTED_ATTACHMENT, claimNumber,
                        "attachment " + candidate.fileName() + " has unsupported type ." + candidate.extension()
                                + " (allowed: " + String.join(", ", allowed.stream().sorted().toList()) + ")");
            }
        }
        if (selected.isEmpty()) {
            if (cfg.whenNone() == Cms1500Properties.WhenNonePolicy.FAIL) {
                throw new ClaimException(ErrorCode.NO_ATTACHMENTS, claimNumber,
                        "no attachments named " + claimNumber + "_<n>.<ext> found in " + cfg.rootPath());
            }
            warnings.add("no attachments found for claim " + claimNumber + " in " + cfg.rootPath()
                    + "; the bundle contains the claim form only");
        }
        return selected;
    }

    /** Where the bundle for a claim lives (whether or not it exists yet). */
    public Path bundlePath(String claimNumber) {
        if (claimNumber == null || !Patterns.CLAIM_NUMBER.matcher(claimNumber).matches()) {
            throw new ClaimException(ErrorCode.VALIDATION_ERROR, claimNumber, "invalid claim number");
        }
        Path root = properties.output().rootPath();
        Path target = root.resolve(claimNumber + ".pdf").normalize();
        if (!target.startsWith(root)) {
            throw new ClaimException(ErrorCode.VALIDATION_ERROR, claimNumber, "invalid claim number");
        }
        return target;
    }

    /** The existing bundle for a claim, for download. */
    public Path existingBundle(String claimNumber) {
        Path target = bundlePath(claimNumber);
        if (!Files.isRegularFile(target)) {
            throw new ClaimException(ErrorCode.BUNDLE_NOT_FOUND, claimNumber, "no bundle generated yet for claim " + claimNumber);
        }
        return target;
    }
}
