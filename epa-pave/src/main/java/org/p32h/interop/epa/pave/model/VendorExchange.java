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
     */
    public List<AdditionalField> bodyFields() {
        List<AdditionalField> out = new ArrayList<>();
        if (json == null && responseBody != null) {
            out.add(AdditionalField.unparsedBody(responseBody));
        }
        bodyNotes.forEach(note -> out.add(AdditionalField.bodyNote(note)));
        return out;
    }

    /** The response half of the wire view. */
    public WireResponse wireResponse() {
        return new WireResponse(httpStatus, responseHeaders, responseBody, responseBytes, json != null, parseError);
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
