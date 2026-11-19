package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.postman.Placeholders;

/**
 * How the value of a header is shown as it was written, before anything is resolved.
 *
 * <p>A secret that arrives through a variable is masked by the name of the variable. A credential
 * typed straight into a collection, or into a lookup header in application.yml, has no variable
 * name, so it is recognised by the name of the header it sits in.
 */
final class HeaderMasking {

    private HeaderMasking() {
    }

    /**
     * The value of a header as written, in the form that may be shown. A header that is a
     * credential by its own name is shown as written only when nothing in it was typed literally:
     * it is all placeholders, or a well known scheme word followed by placeholders. Anything else
     * could be a credential someone typed in, and is redacted.
     */
    static String shownTemplate(String headerName, String template) {
        if (template == null || !Secrets.credentialHeader(headerName) || Placeholders.referencesOnly(template)) {
            return template;
        }
        String redacted = Secrets.redactCredential(headerName, template);
        String text = template.strip();
        int space = text.indexOf(' ');
        // The redaction keeps the first word only when it is a well known scheme, which is how one is recognised here.
        if (!redacted.equals(Secrets.REDACTED) && space > 0 && Placeholders.referencesOnly(text.substring(space + 1))) {
            return template;
        }
        return redacted;
    }

    /** True when a header that is a credential by its own name holds something that was typed literally. */
    static boolean literalCredential(String headerName, String template) {
        return Texts.present(template) && !template.equals(shownTemplate(headerName, template));
    }
}
