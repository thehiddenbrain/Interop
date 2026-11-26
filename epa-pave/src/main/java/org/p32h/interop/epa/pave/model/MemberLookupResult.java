package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The answer to one member lookup.
 *
 * @param status           how the lookup ended. NOT_FOUND and FAULT are different findings
 * @param memberId         the ID as typed and as sent
 * @param member           the member, when found
 * @param attention        reasons a found member still needs a look, such as coverage that is not
 *                         active. Empty when there are none
 * @param fields           every field the mapping looks for, each with what the API returned for it
 * @param additionalFields everything the API returned that the mapping did not recognise
 * @param message          one plain sentence for the confirmation strip
 * @param maskedReading    the line that says what this result concluded was read from a mask and not from what the API
 *                         sent ({@link MaskedReading#line()}), or null when no mask fell on a value it was read by
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberLookupResult(LookupStatus status, MemberIdForms memberId, MemberDetail member, List<String> attention,
                                 List<LookupField> fields, List<AdditionalField> additionalFields, WireView wire,
                                 ErrorView error, String message,
                                 String maskedReading) {

    public MemberLookupResult {
        if (status == null) {
            throw new IllegalArgumentException("A lookup result always says how the lookup ended.");
        }
        attention = attention == null ? List.of() : List.copyOf(attention);
        fields = fields == null ? List.of() : List.copyOf(fields);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
        maskedReading = Texts.present(maskedReading) ? maskedReading : null;
    }

    public MemberLookupResult(LookupStatus status, MemberIdForms memberId, MemberDetail member, List<String> attention,
                              List<LookupField> fields, List<AdditionalField> additionalFields, WireView wire,
                              ErrorView error, String message) {
        this(status, memberId, member, attention, fields, additionalFields, wire, error, message, null);
    }

    /** The same result with the line that says what it concluded was read from a mask, drawn under its message. */
    public MemberLookupResult withMaskedReading(String line) {
        return new MemberLookupResult(status, memberId, member, attention, fields, additionalFields, wire, error, message, line);
    }

    /** The status in words, served beside the code so that no screen keeps its own copy. Never read from a request. */
    @com.fasterxml.jackson.annotation.JsonProperty(value = "statusLabel", access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public String statusLabel() {
        return status == null ? null : status.label();
    }

    /** Redacted, because it carries member data. */
    @Override
    public String toString() {
        return "MemberLookupResult[status=" + status + "]";
    }
}
