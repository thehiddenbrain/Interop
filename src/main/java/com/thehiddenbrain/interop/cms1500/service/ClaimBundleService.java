package com.thehiddenbrain.interop.cms1500.service;

import com.thehiddenbrain.interop.cms1500.attachments.Attachment;
import com.thehiddenbrain.interop.cms1500.attachments.AttachmentLocator;
import com.thehiddenbrain.interop.cms1500.config.Cms1500Properties;
import com.thehiddenbrain.interop.cms1500.config.RuntimeSettings;
import com.thehiddenbrain.interop.cms1500.contract.ClaimBundleResult;
import com.thehiddenbrain.interop.cms1500.contract.Cms1500Claim;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.contract.ResultStatus;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import com.thehiddenbrain.interop.cms1500.domain.ClaimValidationException;
import com.thehiddenbrain.interop.cms1500.domain.ClaimValidator;
import com.thehiddenbrain.interop.cms1500.domain.Patterns;
import com.thehiddenbrain.interop.cms1500.pdf.PdfBundler;
import com.thehiddenbrain.interop.cms1500.pdf.TemplateSource;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The one workflow both APIs run: validate the claim, fill the form, find the attachments,
 * write {@code <claimNumber>.pdf} to the output folder and describe what was produced.
 * Also the read-only helpers the test UI uses (validate only, preview the form, list files).
 */
@Service
public class ClaimBundleService {

    private static final Logger log = LoggerFactory.getLogger(ClaimBundleService.class);
    private static final int MAX_LISTED_BUNDLES = 500;

    private final RuntimeSettings settings;
    private final ClaimValidator validator;
    private final TemplateSource templates;
    private final AttachmentLocator locator;
    private final PdfBundler bundler;

    public ClaimBundleService(RuntimeSettings settings, ClaimValidator validator, TemplateSource templates,
                              AttachmentLocator locator, PdfBundler bundler) {
        this.settings = settings;
        this.validator = validator;
        this.templates = templates;
        this.locator = locator;
        this.bundler = bundler;
    }

    public ClaimBundleResult generate(Cms1500Claim claim) {
        requireClaim(claim);
        Cms1500Properties props = settings.current();
        ClaimValidator.Result validation = validator.validate(claim);
        if (!validation.valid()) {
            throw new ClaimValidationException(claim.getClaimNumber(), validation.errors());
        }
        String claimNumber = claim.getClaimNumber();
        List<String> warnings = new ArrayList<>(validation.warnings());

        Path target = bundlePath(props, claimNumber);
        boolean existed = Files.exists(target);
        if (existed && !props.output().overwrite()) {
            throw new ClaimException(ErrorCode.BUNDLE_EXISTS, claimNumber,
                    "bundle already exists and overwrite is disabled: " + target);
        }

        List<Attachment> attachments = selectAttachments(props, claimNumber, warnings);

        try (PDDocument form = templates.current().fill(claim)) {
            PdfBundler.Outcome outcome = bundler.bundle(claimNumber, form, attachments, target, props.output().overwrite());
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

    /** Runs the same validation as {@link #generate} without touching the file system. */
    public ValidationReport validate(Cms1500Claim claim) {
        requireClaim(claim);
        ClaimValidator.Result result = validator.validate(claim);
        return new ValidationReport(result.valid(), result.errors(), result.warnings());
    }

    /**
     * The filled, flattened form pages for whatever the claim contains, as PDF bytes. Nothing is
     * validated or written: this is for looking at the layout while composing a claim.
     */
    public byte[] previewForm(Cms1500Claim claim) {
        requireClaim(claim);
        try (PDDocument form = templates.current().fill(claim)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            form.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.INTERNAL_ERROR, claim.getClaimNumber(),
                    "cannot fill CMS-1500 form: " + e.getMessage(), e);
        }
    }

    /** Bundles present in the output folder, newest first. */
    public List<BundleInfo> listBundles() {
        Path root = settings.current().output().rootPath();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .map(f -> {
                        String name = f.getFileName().toString();
                        String claimNumber = name.substring(0, name.length() - 4);
                        if (!Patterns.CLAIM_NUMBER.matcher(claimNumber).matches()) {
                            return null;
                        }
                        try {
                            return new BundleInfo(claimNumber, name, Files.size(f),
                                    OffsetDateTime.ofInstant(Files.getLastModifiedTime(f).toInstant(), ZoneId.systemDefault()));
                        } catch (IOException e) {
                            return null;
                        }
                    })
                    .filter(b -> b != null)
                    .sorted(Comparator.comparing(BundleInfo::modifiedAt).reversed())
                    .limit(MAX_LISTED_BUNDLES)
                    .toList();
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.STORAGE_ERROR, null, "cannot list " + root + ": " + e.getMessage(), e);
        }
    }

    /** Files in the attachments folder that follow the claim's naming convention, supported or not. */
    public List<AttachmentInfo> listAttachments(String claimNumber) {
        Cms1500Properties props = settings.current();
        Set<String> allowed = props.attachments().allowedExtensionSet();
        List<AttachmentInfo> infos = new ArrayList<>();
        for (Attachment a : locator.locate(props.attachments().rootPath(), claimNumber)) {
            boolean supported = allowed.contains(a.extension());
            String type = !supported ? "UNSUPPORTED" : a.isPdf() ? "PDF" : "IMAGE";
            long size;
            try {
                size = Files.size(a.path());
            } catch (IOException e) {
                size = -1;
            }
            infos.add(new AttachmentInfo(a.index(), a.fileName(), a.extension(), type, supported, size));
        }
        return infos;
    }

    private List<Attachment> selectAttachments(Cms1500Properties props, String claimNumber, List<String> warnings) {
        Cms1500Properties.Attachments cfg = props.attachments();
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
        return bundlePath(settings.current(), claimNumber);
    }

    private static Path bundlePath(Cms1500Properties props, String claimNumber) {
        if (claimNumber == null || !Patterns.CLAIM_NUMBER.matcher(claimNumber).matches()) {
            throw new ClaimException(ErrorCode.VALIDATION_ERROR, claimNumber, "invalid claim number");
        }
        Path root = props.output().rootPath();
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

    private static void requireClaim(Cms1500Claim claim) {
        if (claim == null) {
            throw new ClaimException(ErrorCode.MALFORMED_REQUEST, null, "claim is required");
        }
    }
}
