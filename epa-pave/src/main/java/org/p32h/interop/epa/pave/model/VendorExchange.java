package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * One call to an external system and what came back, before any interpretation.
 *
 * <p>Clients return this for every outcome. A vendor fault is described here, never thrown, so the
 * tester always gets to see the request that caused it.
 *
 * <p>The constructor refuses combinations that would describe something that cannot have happened,
 * such as "no failure" with no response. Without that check an exchange built with nothing set
 * would read as a successful empty answer, and an empty answer is exactly what must never be
 * invented.
 *
 * @param request         the request as built
 * @param httpStatus      the HTTP status, or null when no response arrived
 * @param responseBody    the body as received, decoded to text, or null when there was none
 * @param responseBytes   how many bytes the body was on the wire, counted before decoding. Null
 *                        when no response arrived
 * @param json            the body parsed as JSON, or null when it is absent or not JSON
 * @param parseError      why a body that was present could not be parsed, or null
 * @param failureType     NONE unless the call failed at the transport or HTTP level. Never null
 * @param failureDetail   a short description of a transport failure. Never contains request data
 * @param signals         vendor-specific findings recognised by the client layer
 * @param scenarioId      the mock scenario that answered, or null when the answer came from a real service
 * @param scenarioNote    how that scenario came to be the one that answered, in plain words, or null
 * @param bodyNotes       things a tester should know about the body as a whole, such as a repeated key
 */
public record VendorExchange(
        WireRequest request,
        Integer httpStatus,
        List<WireHeader> responseHeaders,
        String responseBody,
        Long responseBytes,
        @JsonIgnore JsonNode json,
        String parseError,
        long elapsedMillis,
        OnyxFailureType failureType,
        String failureDetail,
        Set<VendorSignal> signals,
        String scenarioId,
        String scenarioNote,
        List<String> bodyNotes) {

    public VendorExchange {
        if (failureType == null) {
            throw new IllegalArgumentException("An exchange must say whether the call failed.");
        }
        boolean noResponse = failureType == OnyxFailureType.TIMEOUT || failureType == OnyxFailureType.UNAVAILABLE;
        if (noResponse && (httpStatus != null || responseBody != null)) {
            throw new IllegalArgumentException("A call that got no response cannot carry a status or a body.");
        }
        if (!noResponse && httpStatus == null) {
            throw new IllegalArgumentException("A call that got a response must carry its status.");
        }
        if (failureType == OnyxFailureType.MALFORMED_RESPONSE && json != null) {
            throw new IllegalArgumentException("A malformed response cannot also carry a parsed body.");
        }
        if (parseError != null && json != null) {
            throw new IllegalArgumentException("A body that could not be parsed cannot also carry a parsed form.");
        }
        if (httpStatus != null) {
            boolean success = httpStatus >= 200 && httpStatus < 300;
            if (failureType == OnyxFailureType.NONE && (!success || parseError != null)) {
                throw new IllegalArgumentException("Only a success status with a body that was read, or with no body, is free of failure.");
            }
            if (failureType == OnyxFailureType.SERVER_ERROR && httpStatus < 500) {
                throw new IllegalArgumentException("A server error needs a status of 500 or more.");
            }
            if (failureType == OnyxFailureType.BAD_REQUEST && (success || httpStatus >= 500)) {
                throw new IllegalArgumentException("A request that was not accepted needs a status that is neither a success nor 5xx.");
            }
            if (failureType == OnyxFailureType.MALFORMED_RESPONSE && !success) {
                throw new IllegalArgumentException("Only a success status can be a malformed response. Any other status is its own failure.");
            }
        }
        if (noResponse) {
            responseBytes = null;
        } else if (responseBytes == null) {
            responseBytes = responseBody == null ? 0L : (long) responseBody.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        responseHeaders = responseHeaders == null ? List.of() : List.copyOf(responseHeaders);
        signals = signals == null || signals.isEmpty() ? Set.of() : Set.copyOf(signals);
        bodyNotes = bodyNotes == null ? List.of() : List.copyOf(bodyNotes);
    }

    /**
     * True when a success status arrived and nothing was wrong with what came with it. The parsed
     * body is still null when the operation may answer with no content and did.
     */
    public boolean ok() {
        return failureType == OnyxFailureType.NONE;
    }

    public boolean has(VendorSignal signal) {
        return signals.contains(signal);
    }

    public VendorExchange withSignals(Set<VendorSignal> newSignals) {
        EnumSet<VendorSignal> merged = EnumSet.noneOf(VendorSignal.class);
        merged.addAll(signals);
        if (newSignals != null) {
            merged.addAll(newSignals);
        }
        return new VendorExchange(request, httpStatus, responseHeaders, responseBody, responseBytes, json, parseError, elapsedMillis,
                failureType, failureDetail, merged, scenarioId, scenarioNote, bodyNotes);
    }

    /** The same exchange marked with the mock scenario that produced it and how it was chosen. */
    public VendorExchange withScenario(String id, String note) {
        return new VendorExchange(request, httpStatus, responseHeaders, responseBody, responseBytes, json, parseError, elapsedMillis,
                failureType, failureDetail, signals, id, note, bodyNotes);
    }

    /** The same exchange with the parsed body replaced, for a client that normalises a vendor shape. */
    public VendorExchange withJson(JsonNode newJson) {
        return new VendorExchange(request, httpStatus, responseHeaders, responseBody, responseBytes, newJson, parseError, elapsedMillis,
                failureType, failureDetail, signals, scenarioId, scenarioNote, bodyNotes);
    }

    /** The same exchange reported with a different duration, for a test of the slow threshold. */
    public VendorExchange withElapsedMillis(long millis) {
        return new VendorExchange(request, httpStatus, responseHeaders, responseBody, responseBytes, json, parseError, millis,
                failureType, failureDetail, signals, scenarioId, scenarioNote, bodyNotes);
    }

    /**
     * The fields every mapper adds to its additional fields before anything it found itself: the
     * body as received when it could not be parsed, and each note about the body. They are what
     * keeps an unreadable body, or a quirk of a readable one, out of the wire view alone.
     *
     * <p>The body is the same text the wire view shows, so it is masked in the same way: a place
     * that repeats a secret the request sent is shown as that secret's mask, and a note says so.
     */
    public List<AdditionalField> bodyFields() {
        List<AdditionalField> out = new ArrayList<>();
        if (json == null && responseBody != null) {
            SentSecrets.Masked shown = sentSecrets().mask(responseBody);
            out.add(AdditionalField.unparsedBody(shown.text()));
            if (shown.places() > 0) {
                out.add(AdditionalField.bodyNote(masked(shown.places(), "in the body as shown here")));
            }
        }
        bodyNotes.forEach(note -> out.add(AdditionalField.bodyNote(note)));
        return out;
    }

    /**
     * The response half of the wire view: the headers and the body as received, except that every
     * place which repeats a secret the request sent is shown as that secret's mask. The view then
     * says how many places were masked, so that it never passes for the exact bytes received when
     * it is not. The byte count stays the count of what was received.
     *
     * <p>Only what is shown is masked. {@link #responseBody()}, {@link #json()} and
     * {@link #responseHeaders()} stay as they were received, because a mapper has to read what the
     * service really said.
     */
    public WireResponse wireResponse() {
        SentSecrets secrets = sentSecrets();
        int inHeaders = 0;
        List<WireHeader> shownHeaders = new ArrayList<>();
        for (WireHeader header : responseHeaders) {
            SentSecrets.Masked value = secrets.mask(header.value());
            inHeaders += value.places();
            shownHeaders.add(value.places() == 0 ? header : new WireHeader(header.name(), value.text()));
        }
        SentSecrets.Masked body = secrets.mask(responseBody);
        return new WireResponse(httpStatus, shownHeaders, body.text(), responseBytes, json != null, parseError,
                inHeaders + body.places(), maskNote(inHeaders, body.places(), httpStatus == null ? 0 : secrets.tooShort()));
    }

    private SentSecrets sentSecrets() {
        return request == null ? SentSecrets.none() : request.secrets();
    }

    /** What the wire view says about masking, or null when there is nothing to say. */
    private static String maskNote(int inHeaders, int inBody, int tooShort) {
        int places = inHeaders + inBody;
        StringBuilder note = new StringBuilder();
        if (places > 0) {
            String where = inBody == 0 ? "in the headers of this response" : inHeaders == 0 ? "in the body of this response"
                    : "in this response, " + inHeaders + " in the headers and " + inBody + " in the body,";
            note.append(masked(places, where)).append(" A masked place is shown as [secret ...], naming the variable, the header or the ")
                    .append("token whose value it repeats. What is shown here is therefore not exactly what was received. The byte count is ")
                    .append("the count of what was received.");
        }
        if (tooShort > 0) {
            note.append(places > 0 ? " This request also sent " : "This request sent ")
                    .append(tooShort == 1 ? "1 secret" : tooShort + " secrets").append(" shorter than ").append(SentSecrets.SHORTEST_SEARCHED)
                    .append(" characters. A secret that short is not searched for, because it would be found by accident, so this ")
                    .append("response is not masked where it repeats ").append(tooShort == 1 ? "that one." : "one of those.");
        }
        return note.length() == 0 ? null : note.toString();
    }

    /** The sentence that says how many places were masked and why, worded for one place as well as for several. */
    private static String masked(int places, String where) {
        return places == 1 ? "1 place " + where + " is masked because it repeats a secret this request sent."
                : places + " places " + where + " are masked because they repeat a secret this request sent.";
    }

    public WireView wire(MemberIdForms memberId) {
        return new WireView(request, wireResponse(), memberId);
    }

    /** Redacted, because both bodies carry member data. */
    @Override
    public String toString() {
        return "VendorExchange[status=" + httpStatus + ", failure=" + failureType + ", elapsedMillis=" + elapsedMillis + "]";
    }
}
