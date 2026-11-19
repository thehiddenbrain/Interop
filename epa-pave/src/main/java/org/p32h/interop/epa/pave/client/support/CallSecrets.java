package org.p32h.interop.epa.pave.client.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.model.Secrets;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VariableSource;
import org.p32h.interop.epa.pave.model.VariableUse;
import org.p32h.interop.epa.pave.service.config.PreparedCall;

/**
 * Works out which secret values one request carries, so that the response to it can be shown
 * without them.
 *
 * <p>The rule is the one the request side already follows: a value is secret because of the name
 * it goes by, never because of what it looks like. Three kinds of name make a value secret here,
 * and each is something the request side masks or redacts for the same reason:
 * <ul>
 *   <li>a secret variable resolved into the url, a header or the body. The request shows
 *       {@code [secret name]} in its place</li>
 *   <li>the access token the request carries as its bearer credential</li>
 *   <li>the value of a header that is a credential by its own name, which covers one typed
 *       straight into a collection and so held in no variable</li>
 * </ul>
 * Whatever the request as shown hides is therefore looked for in the response.
 *
 * <p>Nothing here logs, and no value leaves this class except inside a {@link SentSecrets}.
 */
final class CallSecrets {

    private CallSecrets() {
    }

    /**
     * @param body    the body the request carries, or null when it carries none
     * @param headers the headers the request is sent with, as {@link Exchanges#headers} merged them
     * @param token   the access token among those headers, or null when the request carries none
     */
    static SentSecrets of(PreparedCall call, ResolvedText body, Map<String, ResolvedHeader> headers, String token) {
        SentSecrets.Builder found = SentSecrets.builder();
        // Variables first. A value that is known under a variable's name keeps that name, since it
        // is the one the request shows for it.
        ResolvedUrl url = call.url();
        if (url != null) {
            variables(found, call, url.variables(), url.display(), url.value());
        }
        if (body != null) {
            variables(found, call, body.variables(), body.display(), body.value());
        }
        for (ResolvedHeader header : headers.values()) {
            variables(found, call, List.of(), header.display(), header.value());
        }
        found.accessToken(token);
        for (ResolvedHeader header : headers.values()) {
            if (header.resolved() && Texts.present(header.name()) && Secrets.credentialHeader(header.name())) {
                found.header(header.name(), Secrets.credential(header.name(), header.value()));
            }
        }
        return found.build();
    }

    /**
     * Adds the value of every secret variable that one text of the request holds.
     *
     * <p>The names come from two places: the variables the text is known to have used, and the
     * masks that stand in its shown form, because a header keeps no list of its variables. Each
     * value is then asked for by name from the call, which resolves against the same configuration
     * and the same layers the text itself was resolved against.
     *
     * <p>One case is settled without asking. When exactly one mask is left unexplained, the shown
     * form and the sent form differ only there, so what the sent form holds in that place is the
     * value. That covers a text whose two forms were not made by this call's own resolver. Two or
     * more unexplained masks cannot be told apart this way and are left alone.
     *
     * @param uses  the variables the text used, as far as that is known
     * @param shown the text with its secrets masked, or null when it did not resolve
     * @param sent  the text as it is sent, or null when it did not resolve
     */
    private static void variables(SentSecrets.Builder found, PreparedCall call, List<VariableUse> uses, String shown, String sent) {
        // A text that is shown exactly as it is sent hides nothing, so it holds nothing this tool
        // knows to be secret. That is every body the tool builds itself. It is also a body into
        // which a tester pasted a request copied from the wire view, masks and all: a mask that
        // was typed in is ordinary text, and no variable was resolved there.
        if (shown == null || sent == null || shown.equals(sent)) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (VariableUse use : uses) {
            if (use.secret() && use.source() != VariableSource.UNRESOLVED) {
                names.add(use.name());
            }
        }
        names.addAll(Secrets.maskedNames(shown));
        String rest = shown;
        List<String> unexplained = new ArrayList<>();
        for (String name : names) {
            String mask = Secrets.mask(name);
            String value = valueOf(call, name);
            if (value != null && sent.contains(value)) {
                found.variable(name, value);
                rest = rest.replace(mask, value);
            } else if (rest.contains(mask)) {
                unexplained.add(name);
            }
        }
        if (unexplained.size() != 1) {
            return;
        }
        String mask = Secrets.mask(unexplained.get(0));
        int at = rest.indexOf(mask);
        if (at != rest.lastIndexOf(mask)) {
            return;
        }
        String before = rest.substring(0, at);
        String after = rest.substring(at + mask.length());
        if (sent.length() >= before.length() + after.length() && sent.startsWith(before) && sent.endsWith(after)) {
            String value = sent.substring(before.length(), sent.length() - after.length());
            // The same text in both forms is a mask that was typed into the request, not one that stands for a value.
            if (!value.equals(mask)) {
                found.variable(unexplained.get(0), value);
            }
        }
    }

    /** The value the call resolves a variable to, when the call itself counts that variable as a secret. Otherwise null. */
    private static String valueOf(PreparedCall call, String name) {
        ResolvedText resolved = call.resolve("{{" + name + "}}");
        if (resolved == null || !resolved.resolved()) {
            return null;
        }
        boolean secret = resolved.variables().stream().anyMatch(use -> use.secret() && name.equals(use.name()));
        return secret ? resolved.value() : null;
    }
}
