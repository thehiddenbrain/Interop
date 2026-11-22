package org.p32h.interop.epa.pave.service.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.CodeInput;

/**
 * One document a tester adds to a prior authorization request.
 *
 * <p>How it travels is not the tester's choice per document: it follows the attachment mode the
 * installation is configured with, because which of the two the vendor uses is still an open item.
 *
 * <p>A file the tester chose on the screen arrives as {@code data} with its {@code size}: the page
 * reads it as base64 when the button is pressed. Da Vinci PAS 2.2.1 requires the content inline and
 * forbids a url; {@code url} is still read, because a body sent from curl may carry one, and a test
 * of a request that breaks that rule is a test a tester may want to run.
 *
 * @param title       what the document is, in the tester's words. Shown in the request notes
 * @param contentType the media type of the document, for example text/plain or application/pdf
 * @param text        the document itself, as text. One of this, {@code url} or {@code data}
 * @param url         where the document can be fetched from. One of this, {@code text} or {@code data}
 * @param type        what kind of document it is, as a code, such as a LOINC document code.
 *                    Optional. US Core DocumentReference requires one, and the request notes say so
 *                    when it is missing
 * @param category    the category of the document, as a code, such as clinical-note. Optional,
 *                    and required by US Core DocumentReference in the same way
 * @param data        the file's bytes as base64. One of this, {@code text} or {@code url}
 * @param size        how many bytes {@code data} decodes to, as the page counted them. Kept beside
 *                    the data so a file cut short on the way is noticed rather than sent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasAttachment(String title, String contentType, String text, String url, CodeInput type, CodeInput category, String data,
                            Long size) {

    /** A document with no type and no category. */
    public PasAttachment(String title, String contentType, String text, String url) {
        this(title, contentType, text, url, null, null, null, null);
    }

    /** A document given as text or a url, with its type and category. */
    public PasAttachment(String title, String contentType, String text, String url, CodeInput type, CodeInput category) {
        this(title, contentType, text, url, type, category, null, null);
    }

    /** A file the tester chose, as the page reads it: base64 bytes and their count. */
    public static PasAttachment file(String title, String contentType, String data, Long size) {
        return new PasAttachment(title, contentType, null, null, null, null, data, size);
    }

    /** Redacted, because the text and the data are a clinical document. */
    @Override
    public String toString() {
        return "PasAttachment[contentType=" + contentType + ", size=" + size + ", content redacted]";
    }
}
