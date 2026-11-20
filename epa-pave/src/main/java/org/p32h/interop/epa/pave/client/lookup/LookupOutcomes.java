package org.p32h.interop.epa.pave.client.lookup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.ErrorKind;
import org.p32h.interop.epa.pave.model.ErrorView;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.OnyxFailureType;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.TrackedJson;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;

/**
 * Says how a lookup ended whenever it did not end with a record: not found, disabled, or one of
 * the faults.
 *
 * <p>The three are never given the same words. An identifier the far side does not recognise is a
 * finding about the test data, and has no error panel. A lookup that is switched off or not
 * configured is not an error either: the tester types the details by hand. A far side that is
 * down, refuses the call or answers with something that cannot be read is a finding about the
 * environment or about the mapping, and says which.
 */
final class LookupOutcomes {

    /**
     * @param message one sentence for the confirmation strip
     * @param error   the error panel for a fault. Null for not found, which is an answer, and for
     *                disabled, which is a setting: neither is a fault
     */
    record Ending(LookupStatus status, String message, ErrorView error) {
    }

    private LookupOutcomes() {
    }

    /**
     * How the call ended when it did not end with a body to read. Null when it did, in which case
     * the record is looked for next.
     *
     * @param tracked the response, so that an OperationOutcome is read issue by issue and what was
     *                read is not listed a second time among the returned fields
     */
    static Ending ofCall(VendorExchange exchange, LookupWords words, TrackedJson tracked) {
        OnyxFailureType failure = exchange.failureType();
        if (failure == OnyxFailureType.NONE) {
            return null;
        }
        Integer status = exchange.httpStatus();
        if (Exchanges.INCOMPLETE_BODY.equals(exchange.parseError())) {
            // Even with a 404 status. An answer that stopped part way is not taken as an answer.
            return fault(kindOf(failure), words.api() + " started to answer with HTTP " + status + " and the answer did not arrive in full.",
                    "The part that arrived is shown as received under the returned fields. Leave the field again to retry, and report "
                            + "the environment if it repeats. Until then, " + words.byHand() + ".", exchange, tracked);
        }
        return switch (failure) {
            case NONE -> null;
            case BAD_REQUEST -> rejected(exchange, status == null ? 0 : status, words, tracked);
            case SERVER_ERROR -> fault(ErrorKind.VENDOR_ERROR, words.api() + " failed with HTTP " + status + ".",
                    "This is a fault in the environment, not in the " + words.identifier() + ". " + words.byHandSentence()
                            + " Report the environment if it repeats.", exchange, tracked);
            case TIMEOUT -> fault(ErrorKind.TIMEOUT, words.api() + " did not answer inside the read timeout.",
                    "A lookup gives up quickly so that it never holds up the form. " + words.byHandSentence()
                            + " Leave the field again to retry.", exchange, tracked);
            case UNAVAILABLE -> {
                String message = words.api() + " could not be reached.";
                ErrorView error = new ErrorView(ErrorKind.UNREACHABLE, (message + " " + Texts.orElse(exchange.failureDetail(), "")).strip(),
                        "Check " + words.property() + ".url, the network and any proxy or trust store settings. Until then, "
                                + words.byHand() + ".", null, List.of(), List.of(), null);
                yield new Ending(LookupStatus.FAULT, message, error);
            }
            // No body at all and a body of nothing but white space are told apart in the words only. Neither holds a record.
            case MALFORMED_RESPONSE -> Texts.blank(exchange.responseBody())
                    ? fault(ErrorKind.MALFORMED_RESPONSE, words.api() + " answered HTTP " + status + " with "
                            + (exchange.responseBody() == null ? "an empty body" : "a blank body") + ", so there is nothing to read a "
                            + words.record() + " from.",
                    "An answer with nothing in it does not say whether the " + words.identifier() + " is unknown, so this is not "
                            + "reported as not found. If this API answers an unknown " + words.identifier() + " this way, the mapping "
                            + "has to be told so. Until then, " + words.byHand() + ".", exchange, tracked)
                    : fault(ErrorKind.MALFORMED_RESPONSE, words.api() + " answered HTTP " + status + " with a body that is not JSON.",
                    "The body is shown exactly as received under the returned fields. " + words.byHandSentence()
                            + " Report it as a defect in the service.", exchange, tracked);
        };
    }

    private static Ending rejected(VendorExchange exchange, int status, LookupWords words, TrackedJson tracked) {
        if (status == 404) {
            return new Ending(LookupStatus.NOT_FOUND, words.api() + " does not know this " + words.identifier() + " (it answered HTTP 404).",
                    null);
        }
        if (status == 401 || status == 403) {
            boolean credentialSent = exchange.request() != null && exchange.request().headers().stream()
                    .map(WireHeader::name).anyMatch(name -> "authorization".equalsIgnoreCase(name));
            return fault(ErrorKind.VENDOR_REJECTED, words.api() + " refused the lookup with HTTP " + status
                            + ", which means the credentials were not accepted or none were sent.",
                    "The lookup went out " + (credentialSent ? "with" : "without") + " an Authorization header. The headers that were "
                            + "sent are in the wire view. Check " + words.property() + ".headers, and if this API takes the Onyx access "
                            + "token, " + words.property() + ".use-onyx-auth and the onyx.auth settings. Until then, " + words.byHand()
                            + ".", exchange, tracked);
        }
        if (status >= 300 && status < 400) {
            return fault(ErrorKind.VENDOR_REJECTED, words.api() + " answered the lookup with a redirect, HTTP " + status
                            + ", which this tool does not follow.",
                    "A redirect means " + words.property() + ".url is not the address this API answers at. Correct it. Until then, "
                            + words.byHand() + ".", exchange, tracked);
        }
        return fault(ErrorKind.VENDOR_REJECTED, words.api() + " did not accept the lookup and answered HTTP " + status + ".",
                "What it returned is listed under the returned fields. If the " + words.identifier() + " is written correctly, check that "
                        + words.property() + ".url has the form this API expects. Until then, " + words.byHand() + ".", exchange, tracked);
    }

    /**
     * The lookup is switched off in the configuration. Not an error: the tester types the details
     * by hand. The same on both profiles, so the state can be seen on mock.
     */
    static Ending switchedOff(LookupWords words) {
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is switched off (" + words.property() + ".enabled is false): "
                + words.byHand() + ".", null);
    }

    /** No url is configured for the lookup. Not an error: the tester types the details by hand. */
    static Ending notConfigured(LookupWords words) {
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is not configured, so there is no url to ask: set " + words.property()
                + ".url, and until then " + words.byHand() + ".", null);
    }

    /**
     * The lookup is configured, and a placeholder it needs has no value. Reported as disabled and
     * not as a fault: sent anyway, the request would fail in a way that looks like a network
     * problem and is not one.
     *
     * @param variables   the placeholders with no value
     * @param where       what holds them, for example "its url" or "the header X-Api-Key"
     * @param environment the active environment, or null when none is active
     * @param reasons     for a placeholder that is defined and still has no value, why
     */
    static Ending incomplete(LookupWords words, List<String> variables, String where, String environment, Map<String, String> reasons) {
        List<String> named = new ArrayList<>();
        for (String variable : variables) {
            String reason = reasons == null ? null : Texts.trimToNull(reasons.get(variable));
            named.add("{{" + variable + "}}" + (reason == null ? "" : " (" + reason.replaceAll("[.\\s]+$", "") + ")"));
        }
        String placeholders = named.isEmpty() ? "a placeholder" : (named.size() == 1 ? "the placeholder " : "the placeholders ")
                + String.join(", ", named);
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is configured but not completely, because " + placeholders + " in "
                + where + (named.size() > 1 ? " have" : " has") + " no value, and "
                + (Texts.present(environment) ? "the active environment is \"" + environment.strip() + "\"" : "no environment is active")
                + ": " + words.byHand() + ".", null);
    }

    /** The url template has nowhere to put the identifier. */
    static Ending noPlaceForTheIdentifier(LookupWords words) {
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is configured, but its url has no {id} token for the "
                + words.identifier() + " to go in: correct " + words.property() + ".url, and until then " + words.byHand() + ".", null);
    }

    /** The url resolved, and what it resolved to cannot be called. */
    static Ending urlCannotBeCalled(LookupWords words) {
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is configured, but " + words.property()
                + ".url does not resolve to a url that can be called: correct it, and until then " + words.byHand() + ".", null);
    }

    /** A header or the method of the request is not valid HTTP, so the request was refused before sending. */
    static Ending notValidHttp(LookupWords words) {
        return new Ending(LookupStatus.DISABLED, words.lookup() + " is configured, but a header set for it, or its method, is not valid "
                + "HTTP, so nothing was sent: correct " + words.property() + ".headers, and until then " + words.byHand() + ".", null);
    }

    /**
     * The production guardrail refused the request. It is not switched off and not missing, so it
     * is not reported as either: the lookup was refused, and the refusal says why. The refusal
     * names a header, a variable or an environment, never a value, so it can be repeated here.
     */
    static Ending refusedByTheGuardrail(LookupWords words, String refusal, String action) {
        String message = words.lookup() + " was refused by the production guardrail and not sent: " + Texts.orElse(refusal, "").strip();
        return new Ending(LookupStatus.FAULT, message, ErrorView.of(ErrorKind.BLOCKED, message,
                Texts.orElse(action, "").strip() + (Texts.present(action) ? " " : "") + "Until then, " + words.byHand() + "."));
    }

    /**
     * No access token could be obtained, so nothing was sent. A fault, since the lookup is
     * configured to work and did not.
     *
     * @param reason what the token service said, which never holds a credential
     */
    static Ending noAccessToken(LookupWords words, String reason) {
        String message = words.lookup() + " was not sent, because no access token could be obtained for it.";
        return new Ending(LookupStatus.FAULT, message, new ErrorView(ErrorKind.UNREACHABLE, (message + " " + Texts.orElse(reason, "")).strip(),
                "Check the onyx.auth settings and the credentials in the active environment, or set " + words.property()
                        + ".use-onyx-auth to false if this API does not take the Onyx access token. Until then, " + words.byHand() + ".",
                null, List.of(), List.of(), null));
    }

    /** The configuration changed between preparing the request and sending it, so it was not sent. */
    static Ending configurationChanged(LookupWords words) {
        String message = words.lookup() + " was not sent, because the configuration changed while it was being prepared.";
        return new Ending(LookupStatus.FAULT, message, ErrorView.of(ErrorKind.CONFIGURATION, message,
                "Check which environment is active, then leave the field again to repeat the lookup."));
    }

    /** The response holds no record: an empty object, an empty list, or a wrapper holding nothing. */
    static Ending noRecord(LookupWords words) {
        return new Ending(LookupStatus.NOT_FOUND, words.api() + " answered, and its answer holds no " + words.record() + " for this "
                + words.identifier() + ".", null);
    }

    /** A record came back in which nothing has a value. */
    static Ending hollowRecord(LookupWords words) {
        return new Ending(LookupStatus.NOT_FOUND, words.api() + " answered with a record that holds no values, which this tool reads as "
                + "not found.", null);
    }

    static Ending several(LookupWords words, int count, VendorExchange exchange) {
        return unreadable(words.api() + " returned " + count + " records for one " + words.identifier()
                        + ", and this tool does not choose between them.",
                "A lookup by identifier expects one record. Every record that came back is listed under the returned fields. "
                        + words.byHandSentence(), exchange);
    }

    static Ending notARecord(LookupWords words, VendorExchange exchange) {
        return unreadable(words.api() + " answered with JSON that this tool cannot read as one " + words.record() + " record.",
                "What it returned is listed under the returned fields. " + words.byHandSentence(), exchange);
    }

    /** A record came back with values in it, and none of them under a name the mapping knows. */
    static Ending unrecognised(LookupWords words, VendorExchange exchange) {
        return unreadable(words.api() + " answered, but this tool could read nothing about a " + words.record() + " from its answer.",
                "Everything it returned is listed under the returned fields. The " + words.identifier() + " on its own is not taken "
                        + "as a " + words.record() + ". The response contract of this API is not final, and those fields are the evidence "
                        + "needed to finish the mapping. Until then, " + words.byHand() + ".", exchange);
    }

    private static Ending unreadable(String title, String action, VendorExchange exchange) {
        return new Ending(LookupStatus.FAULT, title,
                new ErrorView(ErrorKind.MALFORMED_RESPONSE, title, action, exchange.httpStatus(), List.of(), List.of(), null));
    }

    private static Ending fault(ErrorKind kind, String title, String action, VendorExchange exchange, TrackedJson tracked) {
        List<IssueView> issues = List.of();
        if (exchange.json() != null && OperationOutcomes.is(exchange.json())) {
            // The resource type was read to decide this, so it is not listed again as a field nobody interpreted.
            tracked.root().child("resourceType").consume();
            issues = OperationOutcomes.issues(tracked.root());
        }
        return new Ending(LookupStatus.FAULT, title, new ErrorView(kind, title, action, exchange.httpStatus(), List.of(), issues, null));
    }

    private static ErrorKind kindOf(OnyxFailureType failure) {
        return switch (failure) {
            case SERVER_ERROR -> ErrorKind.VENDOR_ERROR;
            case BAD_REQUEST -> ErrorKind.VENDOR_REJECTED;
            case TIMEOUT -> ErrorKind.TIMEOUT;
            case UNAVAILABLE -> ErrorKind.UNREACHABLE;
            case NONE, MALFORMED_RESPONSE -> ErrorKind.MALFORMED_RESPONSE;
        };
    }
}
