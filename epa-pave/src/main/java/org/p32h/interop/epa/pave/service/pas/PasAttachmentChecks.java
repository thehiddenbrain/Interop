package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The checks the documents of a submission pass before anything is built, each refused at its
 * own field so the Submit step can point at the file that needs another look.
 *
 * <p>A file arrives as base64 with the count of bytes the page read. The two are checked against
 * each other because a file cut short on the way would otherwise reach the service as a document
 * nobody wrote. The kinds and limits are the ones {@code GET /api/pas/options} serves, from
 * {@link PasOptions}, so the page and the server refuse the same files.
 *
 * <p>No file content appears in a message, and neither does a title: a refusal names the entry by
 * its place in the list.
 */
final class PasAttachmentChecks {

    private PasAttachmentChecks() {
    }

    static void check(List<PasAttachment> attachments, RunInputs inputs) {
        long total = 0;
        for (int i = 0; i < attachments.size(); i++) {
            PasAttachment attachment = attachments.get(i);
            String field = "attachments[" + i + "]";
            if (attachment == null) {
                inputs.require(false, field, "This attachment is empty. Remove it, or choose its file again.");
                continue;
            }
            inputs.require(Texts.present(attachment.title()), field + ".title", "Give the attachment a title.");
            inputs.require(Texts.present(attachment.contentType()), field + ".contentType",
                    "State the content type of the attachment, for example text/plain or application/pdf.");
            int given = (Texts.present(attachment.data()) ? 1 : 0) + (Texts.present(attachment.text()) ? 1 : 0)
                    + (Texts.present(attachment.url()) ? 1 : 0);
            inputs.require(given == 1, field, "An attachment carries its file as data, its text, or a url, and exactly one of the three.");
            if (Texts.present(attachment.data())) {
                total += file(attachment, field, inputs);
            } else {
                inputs.require(attachment.size() == null, field + ".size", "A size belongs beside the data of a file. Leave it out for a "
                        + "text or a url.");
                if (Texts.present(attachment.text())) {
                    total += attachment.text().getBytes(StandardCharsets.UTF_8).length;
                }
            }
        }
        inputs.require(total <= PasOptions.TOTAL_BYTES, "attachments", "These attachments hold " + total + " bytes together. Submit sends "
                + "at most 10 MB in all (" + PasOptions.TOTAL_BYTES + " bytes): leave a file out.");
    }

    /** @return the bytes the file holds, or 0 when its data cannot be read, so the total is not refused for the same fault twice */
    private static long file(PasAttachment attachment, String field, RunInputs inputs) {
        long bytes = decodedLength(attachment.data().strip());
        if (bytes < 0) {
            inputs.require(false, field + ".data", "This file's content is not valid base64, so it cannot be sent. Choose the file again.");
            return 0;
        }
        if (attachment.size() == null) {
            inputs.require(false, field + ".size", "State how many bytes the file holds beside its data, so a file cut short on the way is "
                    + "noticed.");
        } else {
            inputs.require(attachment.size() == bytes, field + ".size", "The file's data holds " + bytes + " bytes, not the "
                    + attachment.size() + " stated: it may have been cut short on the way. Choose the file again.");
        }
        if (Texts.present(attachment.contentType())) {
            inputs.require(PasOptions.sendsContentType(attachment.contentType()), field + ".contentType", "Submit does not send a file "
                    + "of this kind. It sends PDF, PNG, JPEG, TIFF, GIF, plain text and Word documents.");
        }
        inputs.require(bytes <= PasOptions.PER_FILE_BYTES, field + ".data", "This file holds " + bytes + " bytes. Submit sends at most "
                + "5 MB a file (" + PasOptions.PER_FILE_BYTES + " bytes).");
        return bytes;
    }

    /**
     * How many bytes strict base64 decodes to, or -1 when the text is not strict base64: the
     * standard alphabet, a length that is a multiple of four, and at most two padding characters,
     * at the end only. Read character by character, so no pattern runs over a text of up to 7 MB.
     */
    static long decodedLength(String text) {
        int length = text.length();
        if (length == 0 || length % 4 != 0) {
            return -1;
        }
        int padding = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c == '=') {
                if (i < length - 2) {
                    return -1;
                }
                padding++;
            } else if (padding > 0 || !alphabet(c)) {
                return -1;
            }
        }
        return (long) length / 4 * 3 - padding;
    }

    private static boolean alphabet(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
    }
}
