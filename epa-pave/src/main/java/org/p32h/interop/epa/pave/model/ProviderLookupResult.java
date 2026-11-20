package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The answer to one provider lookup.
 *
 * @param attention reasons a found provider still needs a look, such as being out of network
 * @param message   one plain sentence for the confirmation strip
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderLookupResult(LookupStatus status, String npi, ProviderDetail provider, List<String> attention,
                                   List<LookupField> fields, List<AdditionalField> additionalFields, WireView wire,
                                   ErrorView error, String message) {

    public ProviderLookupResult {
        if (status == null) {
            throw new IllegalArgumentException("A lookup result always says how the lookup ended.");
        }
        attention = attention == null ? List.of() : List.copyOf(attention);
        fields = fields == null ? List.of() : List.copyOf(fields);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /** Prints the status only. The provider, the fields and the wire view come from a response. */
    @Override
    public String toString() {
        return "ProviderLookupResult[" + status + "]";
    }
}
