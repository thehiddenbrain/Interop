package org.p32h.interop.epa.pave.service.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.CodeInput;

/**
 * One document a tester adds to a prior authorization request.
 *
 * <p>How it travels is not the tester's choice per document: it follows the attachment mode the
 * installation is configured with, because which of the two the vendor uses is still an open item.
 *
 * @param title       what the document is, in the tester's words. Shown in the request notes
 * @param contentType the media type of the document, for example text/plain or application/pdf
 * @param text        the document itself, as text. Either this or {@code url}, never both
 * @param url         where the document can be fetched from. Either this or {@code text}
 * @param type        what kind of document it is, as a code, such as a LOINC document code.
 *                    Optional. US Core 3.1.1 DocumentReference, which PAS 2.0.1 asks a document to
 *                    meet, requires one, and the request notes say so when it is missing
 * @param category    the category of the document, as a code, such as clinical-note. Optional,
 *                    and required by US Core 3.1.1 DocumentReference in the same way
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasAttachment(String title, String contentType, String text, String url, CodeInput type, CodeInput category) {

    /** A document with no type and no category. */
    public PasAttachment(String title, String contentType, String text, String url) {
        this(title, contentType, text, url, null, null);
    }

    /** Redacted, because the text is a clinical document. */
    @Override
    public String toString() {
        return "PasAttachment[contentType=" + contentType + ", content redacted]";
    }
}
