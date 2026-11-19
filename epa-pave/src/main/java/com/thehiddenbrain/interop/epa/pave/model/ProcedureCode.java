package com.thehiddenbrain.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One row of the procedure code list.
 *
 * <p>Only the code is guaranteed. Every other field is null when the list does not have that
 * column or the cell is blank. {@code authRequired} is UNKNOWN in that case and whenever the cell
 * holds something that is not a recognised spelling of yes or no: the list not saying is never
 * turned into "no".
 *
 * <p>Construct with {@link #builder()}.
 *
 * @param authRequiredSource the cell text the requirement was read from, so a tester can see why a
 *                           value came out UNKNOWN
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcedureCode(
        String code,
        String description,
        String codeType,
        String system,
        AuthRequirement authRequired,
        String authRequiredSource,
        String lineOfBusiness,
        String delegatedVendor,
        String effectiveDate,
        String endDate,
        String notes) {

    public ProcedureCode {
        if (authRequired == null) {
            authRequired = AuthRequirement.UNKNOWN;
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String code;
        private String description;
        private String codeType;
        private String system;
        private AuthRequirement authRequired = AuthRequirement.UNKNOWN;
        private String authRequiredSource;
        private String lineOfBusiness;
        private String delegatedVendor;
        private String effectiveDate;
        private String endDate;
        private String notes;

        private Builder() {
        }

        public Builder code(String value) {
            this.code = value;
            return this;
        }

        public Builder description(String value) {
            this.description = value;
            return this;
        }

        public Builder codeType(String value) {
            this.codeType = value;
            return this;
        }

        public Builder system(String value) {
            this.system = value;
            return this;
        }

        public Builder authRequired(AuthRequirement value) {
            this.authRequired = value;
            return this;
        }

        public Builder authRequiredSource(String value) {
            this.authRequiredSource = value;
            return this;
        }

        public Builder lineOfBusiness(String value) {
            this.lineOfBusiness = value;
            return this;
        }

        public Builder delegatedVendor(String value) {
            this.delegatedVendor = value;
            return this;
        }

        public Builder effectiveDate(String value) {
            this.effectiveDate = value;
            return this;
        }

        public Builder endDate(String value) {
            this.endDate = value;
            return this;
        }

        public Builder notes(String value) {
            this.notes = value;
            return this;
        }

        public ProcedureCode build() {
            return new ProcedureCode(code, description, codeType, system, authRequired, authRequiredSource, lineOfBusiness,
                    delegatedVendor, effectiveDate, endDate, notes);
        }
    }
}
