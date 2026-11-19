package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One procedure code as the tester chose it.
 *
 * @param code        the code. A code that is not in the code list can still be sent
 * @param system      the coding system url, or null to have it worked out from the code list or the code's format
 * @param description shown beside the code in the request, or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeInput(String code, String system, String description) {

    public static CodeInput of(String code) {
        return new CodeInput(code, null, null);
    }
}
