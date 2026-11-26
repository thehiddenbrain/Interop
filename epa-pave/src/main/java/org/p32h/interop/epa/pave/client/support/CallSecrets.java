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
 * Works out which secret values one request carries, and adds every other one this tool holds,
 * so that the response to it can be shown without them.
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
 * Whatever the request as shown hides is therefore looked for in the response, and so is every
 * other secret this tool holds for the configuration the call was prepared in
 * ({@link PreparedCall#heldSecrets()}): a service can repeat a secret the request did not carry.
 *
 * <p>Nothing here logs, and no value leaves this class except inside a {@link SentSecrets}.
 */
final class CallSecrets {

    private CallSecrets() {
    }

    /**
     * The secrets the response to a call is masked with: those the request carries, then those held.
     *
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
            variables(found, call, url.variables(), url.display(), url.value(), false);
        }
        if (body != null) {
            variables(found, call, body.variables(), body.display(), body.value(), false);
        }
        for (ResolvedHeader header : headers.values()) {
            // A header that is a credential by its own name is added whole below, so whatever its
            // shown form hides is known even when no variable in it can be named.
            variables(found, call, List.of(), header.display(), header.value(), credential(header));
        }
        found.accessToken(token);
        for (ResolvedHeader header : headers.values()) {
            if (credential(header)) {
                found.header(header.name(), Secrets.credential(header.name(), header.value()));
            }
        }
        // Last, so a value the request carried keeps the name the request shows it under.
        found.held(call.heldSecrets());
        return found.build();
    }

    private static boolean credential(ResolvedHeader header) {
        return header.resolved() && Texts.present(header.name()) && Secrets.credentialHeader(header.name());
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
     * value. That covers a text whose two forms were not made by this call's own resolver.
     *
     * <p>Whatever is still hidden after that is not known: two or more masks the call cannot
     * explain, or one that stands in several places. The response cannot be searched for such a
     * value, so the collection is marked as incomplete and the wire view says so. Showing the
     * response as if it had been checked would be the one failure this class exists to prevent.
     *
     * @param uses       the variables the text used, as far as that is known
     * @param shown      the text with its secrets masked, or null when it did not resolve
     * @param sent       the text as it is sent, or null when it did not resolve
     * @param takenWhole true when the whole of what the text hides is added as a secret elsewhere,
     *                   so that nothing it hides can be left unknown
     */
    private static void variables(SentSecrets.Builder found, PreparedCall call, List<VariableUse> uses, String shown, String sent,
                                  boolean takenWhole) {
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
        // Every value that was found now stands where its mask stood. When that gives the text as
        // it is sent, nothing it hides is unknown: a mask still standing reads the same in both
        // forms, so it is a mask someone typed into the request and not one that stands for a value.
        if (rest.equals(sent)) {
            return;
        }
        if (unexplained.size() == 1) {
            String mask = Secrets.mask(unexplained.get(0));
            int at = rest.indexOf(mask);
            if (at >= 0 && at == rest.lastIndexOf(mask)) {
                String before = rest.substring(0, at);
                String after = rest.substring(at + mask.length());
                if (sent.length() >= before.length() + after.length() && sent.startsWith(before) && sent.endsWith(after)) {
                    found.variable(unexplained.get(0), sent.substring(before.length(), sent.length() - after.length()));
                    return;
                }
            }
        }
        // The shown form hides something, and what it hides could not be established. It errs
        // towards saying so: a mask typed into a text that also resolves the variable of the same
        // name ends here too, although nothing is unknown there.
        if (!takenWhole) {
            found.incomplete();
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
