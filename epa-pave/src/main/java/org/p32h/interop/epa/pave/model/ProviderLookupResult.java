package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The answer to one provider lookup.
 *
 * @param attention reasons a found provider still needs a look, such as being out of network
 * @param message   one plain sentence for the confirmation strip
 * @param maskedReading the line that says what this result concluded was read from a mask and not from what the
 *                  directory sent ({@link MaskedReading#line()}), or null when no mask fell on a value it was read by
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderLookupResult(LookupStatus status, String npi, ProviderDetail provider, List<String> attention,
                                   List<LookupField> fields, List<AdditionalField> additionalFields, WireView wire,
                                   ErrorView error, String message,
                                 String maskedReading) {

    public ProviderLookupResult {
        if (status == null) {
            throw new IllegalArgumentException("A lookup result always says how the lookup ended.");
        }
        attention = attention == null ? List.of() : List.copyOf(attention);
        fields = fields == null ? List.of() : List.copyOf(fields);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
        maskedReading = Texts.present(maskedReading) ? maskedReading : null;
    }

    public ProviderLookupResult(LookupStatus status, String npi, ProviderDetail provider, List<String> attention,
                                List<LookupField> fields, List<AdditionalField> additionalFields, WireView wire,
                                ErrorView error, String message) {
        this(status, npi, provider, attention, fields, additionalFields, wire, error, message, null);
    }

    /** The same result with the line that says what it concluded was read from a mask, drawn under its message. */
    public ProviderLookupResult withMaskedReading(String line) {
        return new ProviderLookupResult(status, npi, provider, attention, fields, additionalFields, wire, error, message, line);
    }

    /** The status in words, served beside the code so that no screen keeps its own copy. Never read from a request. */
    @com.fasterxml.jackson.annotation.JsonProperty(value = "statusLabel", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String statusLabel() {
        return status == null ? null : status.label();
    }

    /** Prints the status only. The provider, the fields and the wire view come from a response. */
    @Override
    public String toString() {
        return "ProviderLookupResult[" + status + "]";
    }
}
