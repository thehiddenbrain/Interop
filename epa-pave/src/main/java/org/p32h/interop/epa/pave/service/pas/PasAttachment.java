package org.p32h.interop.epa.pave.service.pas;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PasAttachment(String title, String contentType, String text, String url) {

    /** Redacted, because the text is a clinical document. */
    @Override
    public String toString() {
        return "PasAttachment[contentType=" + contentType + ", content redacted]";
    }
}
