package com.thehiddenbrain.interop.epa.pave.model;

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
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberLookupResult(LookupStatus status, MemberIdForms memberId, MemberDetail member, List<String> attention,
                                 List<LookupField> fields, List<AdditionalField> additionalFields, WireView wire,
                                 ErrorView error, String message) {

    public MemberLookupResult {
        attention = attention == null ? List.of() : List.copyOf(attention);
        fields = fields == null ? List.of() : List.copyOf(fields);
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /** Redacted, because it carries member data. */
    @Override
    public String toString() {
        return "MemberLookupResult[status=" + status + "]";
    }
}
