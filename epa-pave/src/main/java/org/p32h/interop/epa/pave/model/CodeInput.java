package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;

/**
 * One procedure code as the tester chose it.
 *
 * <p>The quantity and its unit are sent by Submit only (Da Vinci PAS 2.2.1 PASQuantity). CRD and DTR
 * do not send them, so a quantity typed once on the case card does not change what those two send.
 *
 * @param code        the code. A code that is not in the code list can still be sent
 * @param system      the coding system url, or null to have it worked out from the code list or the code's format
 * @param description shown beside the code in the request, or null
 * @param quantity    how many units are requested, more than 0, or null when not stated. Read as a
 *                    decimal so a value such as 1.5 hours keeps its exact form
 * @param unit        the unit of the quantity, an X12 673 code such as FL or DY, or null when not
 *                    stated. Never assumed from the code
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CodeInput(String code, String system, String description, BigDecimal quantity, String unit) {

    /** A code with no quantity, as every caller wrote it before Submit carried one. */
    public CodeInput(String code, String system, String description) {
        this(code, system, description, null, null);
    }

    public static CodeInput of(String code) {
        return new CodeInput(code, null, null);
    }
}
