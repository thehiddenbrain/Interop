package org.p32h.interop.epa.pave.service.config;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.p32h.interop.epa.pave.model.EnvironmentRef;
import org.p32h.interop.epa.pave.model.ResolvedHeader;
import org.p32h.interop.epa.pave.model.ResolvedUrl;
import org.p32h.interop.epa.pave.postman.PostmanHeader;
import org.p32h.interop.epa.pave.postman.Resolution;
import org.p32h.interop.epa.pave.postman.UrlForms;

/**
 * Everything the gateway worked out about one intended call, against one snapshot: where it would
 * go, with which method and headers, whether it is a write, and whether it may be sent.
 *
 * <p>A plan never fails for a target that is missing, unresolved or refused: it says so. That is
 * what lets the same working-out serve two callers. {@code prepare} turns the plan into a
 * {@link PreparedCall} or into the exception that fits, and the Config tab shows the plan as it
 * is, so the screen cannot say something different from what a send would do.
 *
 * @param snapshot     the configuration everything here was worked out against
 * @param intent       what was asked for
 * @param target       the collection request or the fallback url that was chosen, or the reason
 *                     there is none
 * @param method       the HTTP method that would be sent
 * @param url          the url as the frozen contract describes it
 * @param filled       the url with whatever resolved filled in, for display beside the template.
 *                     Null when there is no url
 * @param tokens       the names of the {@code {name}} and {@code :name} parts of the url, whether
 *                     or not a value was supplied for them
 * @param missingPlaceholders the {@code {{placeholders}}} the url needs that have no value. The
 *                     url's own list of what is unresolved holds these and the tokens that were
 *                     given no value; a screen that draws the two differently needs them apart
 * @param urlVariables how each placeholder of the url resolved. Null when there is no url
 * @param reasons      why an unresolved name has no value, for the names where there is more to
 *                     say than "it is not defined"
 * @param headers      the headers that would be sent, each with how it resolved
 * @param write        true when the call counts as a write for the method that would be sent
 * @param writeBecause why it counts as a write when the operation itself is not one, or null
 * @param refusal      why the guardrail refuses the call, or null when it does not
 * @param resolver     resolves text that belongs to this call, such as its body, against the same
 *                     snapshot and the variables of the same collection
 * @param originByRunValue true when a value typed for this run chose the scheme, host or port the
 *                     call goes to, so it is not an address the configuration names. Such a call
 *                     may carry no secret of the configuration
 */
record CallPlan(ConfigSnapshot snapshot, CallIntent intent, Target target, String method, ResolvedUrl url, UrlForms.Filled filled,
                Set<String> tokens, List<String> missingPlaceholders, Resolution urlVariables, Map<String, String> reasons,
                List<Header> headers, boolean write, String writeBecause, Refusal refusal, Function<String, Resolution> resolver,
                boolean originByRunValue) {

    /** Where a target came from. */
    enum Source {
        /** A request of an uploaded collection. */
        REQUEST,
        /** A url built from application.yml. */
        FALLBACK,
        /** The CDS Hooks discovery url, cut out of the url of a mapped hook request. */
        DERIVED,
        /** Nothing. The operation has nowhere to go. */
        NONE
    }

    /**
     * @param source     where the target came from
     * @param collection the collection whose variables apply: the one the request belongs to, or null
     * @param request    the collection request the url came from, or null
     * @param template   the url as configured, placeholders and tokens intact. Null when there is none
     * @param resolved   the template that is resolved. The same as {@code template} except when a
     *                   subscription is listed, where the trailing id segment is left off
     * @param headers    the headers to send, as written: those of the collection request, or those
     *                   configured for a lookup whose url came from application.yml. Otherwise empty
     * @param described  where the template came from, in words
     * @param absent     why there is no url, in words. Null when there is one
     */
    record Target(Source source, StoredCollection collection, StoredCollection.Mapped request, String template, String resolved,
                  List<PostmanHeader> headers, String described, String absent) {

        Target {
            headers = headers == null ? List.of() : List.copyOf(headers);
        }

        static Target none(String absent) {
            return new Target(Source.NONE, null, null, null, null, List.of(), absent, absent);
        }

        boolean configured() {
            return template != null;
        }

        /** The name of the collection request the call would be sent with, or null when the url did not come from one as it stands. */
        String requestName() {
            return source == Source.REQUEST ? request.request().name() : null;
        }

        boolean scripted() {
            return source == Source.REQUEST && request.request().scripted();
        }

        /**
         * The raw body saved with the collection request, with each credential typed into it
         * standing as a placeholder of its own, or null when the call did not come from a request
         * or the request has no raw body.
         */
        String savedBody() {
            if (source != Source.REQUEST || org.p32h.interop.epa.pave.model.Texts.blank(request.request().body())) {
                return null;
            }
            return org.p32h.interop.epa.pave.postman.LiteralCredentials.inBody(request.request().body()).template();
        }
    }

    /**
     * @param header   the header as the call carries it
     * @param template the value as written, placeholders intact
     * @param how      how the value resolved, with the reason for anything that did not
     */
    record Header(ResolvedHeader header, String template, Resolution how) {

        /** Prints the name only. The template can be a credential typed literally into a collection. */
        @Override
        public String toString() {
            return "Header[" + header.name() + "]";
        }
    }

    /**
     * @param reason what was blocked, on which environment, and why
     * @param action what the tester can do about it
     */
    /**
     * @param brief  one short sentence for a row of a table, such as the operations on the Config
     *               tab, where the full reason would be repeated on every write row
     * @param reason the full reason
     * @param action what a person can do
     */
    record Refusal(String brief, String reason, String action) {
    }

    EnvironmentRef environment() {
        return snapshot.activeRef();
    }

    List<ResolvedHeader> resolvedHeaders() {
        return headers.stream().map(Header::header).toList();
    }

    /**
     * Why the call could not be sent as things stand, leaving the guardrail aside: there is no url,
     * or the url still needs a value. Null when there is a complete url.
     */
    String incomplete() {
        if (!url.configured()) {
            return target.absent();
        }
        if (!url.resolved()) {
            String active = snapshot.active() == null ? "no environment is active" : "the active environment is \"" + snapshot.activeName() + "\"";
            return "The url still needs a value for " + String.join(", ", url.unresolved()) + ", and " + active + ".";
        }
        return null;
    }

    /** Prints the operation, the method and the url template. Never a resolved form, which can hold an identifier or a secret. */
    @Override
    public String toString() {
        return "CallPlan[" + intent.operation() + " " + method + " " + url.template() + "]";
    }
}
