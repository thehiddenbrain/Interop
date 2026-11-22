package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A member as the source of truth returned them, or as the tester typed them.
 *
 * <p>Every field except the member ID may be null. A null means the source did not say, and it is
 * never replaced with a default: a coverage status the API did not return is not "active", and a
 * line of business that was not returned is not assumed.
 *
 * <p>Construct with {@link #builder()}. A positional constructor call breaks in every test the
 * moment a component is added.
 *
 * @param memberId       the no-space form, which is the form sent to the service
 * @param dateOfBirth    ISO date, yyyy-MM-dd
 * @param lineOfBusiness as the source system names it, for example Medicare, Medicaid or Commercial
 * @param vendorPatientId the vendor's own id for this patient, used as the Patient's resource id when
 *                        given; never derived from the member ID, whose forms can hold spaces
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MemberDetail(
        String memberId,
        String firstName,
        String lastName,
        String dateOfBirth,
        String gender,
        String lineOfBusiness,
        String planName,
        String coverageStatus,
        String coverageStart,
        String coverageEnd,
        String subscriberId,
        PostalAddress address,
        String vendorPatientId) {

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .memberId(memberId).firstName(firstName).lastName(lastName).dateOfBirth(dateOfBirth).gender(gender)
                .lineOfBusiness(lineOfBusiness).planName(planName).coverageStatus(coverageStatus)
                .coverageStart(coverageStart).coverageEnd(coverageEnd).subscriberId(subscriberId).address(address)
                .vendorPatientId(vendorPatientId);
    }

    /** Redacted, because a record's default toString would print member data into a log. */
    @Override
    public String toString() {
        return "MemberDetail[redacted]";
    }

    public static final class Builder {
        private String memberId;
        private String firstName;
        private String lastName;
        private String dateOfBirth;
        private String gender;
        private String lineOfBusiness;
        private String planName;
        private String coverageStatus;
        private String coverageStart;
        private String coverageEnd;
        private String subscriberId;
        private PostalAddress address;
        private String vendorPatientId;

        private Builder() {
        }

        public Builder memberId(String value) {
            this.memberId = value;
            return this;
        }

        public Builder firstName(String value) {
            this.firstName = value;
            return this;
        }

        public Builder lastName(String value) {
            this.lastName = value;
            return this;
        }

        public Builder dateOfBirth(String value) {
            this.dateOfBirth = value;
            return this;
        }

        public Builder gender(String value) {
            this.gender = value;
            return this;
        }

        public Builder lineOfBusiness(String value) {
            this.lineOfBusiness = value;
            return this;
        }

        public Builder planName(String value) {
            this.planName = value;
            return this;
        }

        public Builder coverageStatus(String value) {
            this.coverageStatus = value;
            return this;
        }

        public Builder coverageStart(String value) {
            this.coverageStart = value;
            return this;
        }

        public Builder coverageEnd(String value) {
            this.coverageEnd = value;
            return this;
        }

        public Builder subscriberId(String value) {
            this.subscriberId = value;
            return this;
        }

        public Builder address(PostalAddress value) {
            this.address = value;
            return this;
        }

        public Builder vendorPatientId(String value) {
            this.vendorPatientId = value;
            return this;
        }

        public MemberDetail build() {
            return new MemberDetail(memberId, firstName, lastName, dateOfBirth, gender, lineOfBusiness, planName,
                    coverageStatus, coverageStart, coverageEnd, subscriberId, address, vendorPatientId);
        }
    }
}
