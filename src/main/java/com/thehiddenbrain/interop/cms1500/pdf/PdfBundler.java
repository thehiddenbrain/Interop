package com.thehiddenbrain.interop.cms1500.pdf;

import com.thehiddenbrain.interop.cms1500.attachments.Attachment;
import com.thehiddenbrain.interop.cms1500.contract.AttachmentType;
import com.thehiddenbrain.interop.cms1500.contract.BundledAttachment;
import com.thehiddenbrain.interop.cms1500.contract.ErrorCode;
import com.thehiddenbrain.interop.cms1500.domain.ClaimException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

/**
 * Appends the claim's attachments behind the filled form and writes the result atomically as
 * {@code <claimNumber>.pdf}: PDFs are merged page for page, images become one page per frame.
 */
@Component
public class PdfBundler {

    public record Outcome(int formPages, int totalPages, List<BundledAttachment> attachments) {
    }

    /**
     * @param form        the filled, flattened claim form (not closed by this method)
     * @param attachments files to append, in order
     * @param target      final path of the bundle
     * @param overwrite   replace an existing bundle; otherwise {@link ErrorCode#BUNDLE_EXISTS}
     */
    public Outcome bundle(String claimNumber, PDDocument form, List<Attachment> attachments,
                          Path target, boolean overwrite) {
        Path dir = target.toAbsolutePath().getParent();
        Path temp = null;
        try (PDDocument bundle = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            PDFMergerUtility merger = new PDFMergerUtility();
            merger.appendDocument(bundle, form);
            int formPages = bundle.getNumberOfPages();

            List<BundledAttachment> bundled = new ArrayList<>();
            for (Attachment attachment : attachments) {
                int before = bundle.getNumberOfPages();
                appendAttachment(claimNumber, bundle, merger, attachment);
                BundledAttachment b = new BundledAttachment();
                b.setIndex(attachment.index());
                b.setFileName(attachment.fileName());
                b.setType(attachment.isPdf() ? AttachmentType.PDF : AttachmentType.IMAGE);
                b.setPages(bundle.getNumberOfPages() - before);
                bundled.add(b);
            }

            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle("Claim " + claimNumber);
            info.setSubject("CMS-1500 claim form and attachments");
            info.setCreator("cms1500-claim-service");
            info.setCreationDate(Calendar.getInstance());
            bundle.setDocumentInformation(info);

            Files.createDirectories(dir);
            // not Files.createTempFile: that forces 0600 permissions, which would leave the bundle unreadable
            // for other users of the shared drive; a normally created file follows the process umask instead
            temp = dir.resolve("." + claimNumber + "." + UUID.randomUUID() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                bundle.save(out);
            }
            if (overwrite) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } else {
                // no ATOMIC_MOVE here: on Linux an atomic rename silently replaces an existing target,
                // whereas a plain move refuses with FileAlreadyExistsException (it is still a rename on one volume)
                Files.move(temp, target);
            }
            temp = null;
            return new Outcome(formPages, bundle.getNumberOfPages(), List.copyOf(bundled));
        } catch (FileAlreadyExistsException e) {
            throw new ClaimException(ErrorCode.BUNDLE_EXISTS, claimNumber,
                    "bundle already exists and overwrite is disabled: " + target, e);
        } catch (IOException e) {
            throw new ClaimException(ErrorCode.STORAGE_ERROR, claimNumber,
                    "cannot write bundle " + target + ": " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // best effort cleanup of the partial file
                }
            }
        }
    }

    private static void appendAttachment(String claimNumber, PDDocument bundle, PDFMergerUtility merger,
                                         Attachment attachment) {
        try {
            if (attachment.isPdf()) {
                try (PDDocument source = Loader.loadPDF(attachment.path().toFile(),
                        IOUtils.createTempFileOnlyStreamCache())) {
                    if (source.isEncrypted()) {
                        source.setAllSecurityToBeRemoved(true);
                    }
                    if (source.getNumberOfPages() == 0) {
                        throw new IOException("PDF has no pages");
                    }
                    merger.appendDocument(bundle, source);
                }
            } else {
                ImagePages.append(bundle, attachment.path(), attachment.extension());
            }
        } catch (InvalidPasswordException e) {
            throw new ClaimException(ErrorCode.ATTACHMENT_UNREADABLE, claimNumber,
                    "attachment " + attachment.fileName() + " is password protected", e);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ClaimException ce) {
                throw ce;
            }
            throw new ClaimException(ErrorCode.ATTACHMENT_UNREADABLE, claimNumber,
                    "attachment " + attachment.fileName() + " cannot be read: " + e.getMessage(), e);
        }
    }
}
