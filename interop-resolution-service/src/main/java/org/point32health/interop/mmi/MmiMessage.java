package org.point32health.interop.mmi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record MmiMessage(String messageType, String statusCode, String messageCode, String message) {

    /** The free text may contain PHI; it is logged at DEBUG only. */
    @Override
    public String toString() {
        return "MmiMessage[type=" + messageType + ", status=" + statusCode + ", code=" + messageCode + "]";
    }
}
