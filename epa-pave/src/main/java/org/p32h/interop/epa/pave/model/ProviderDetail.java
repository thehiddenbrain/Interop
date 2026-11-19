package org.p32h.interop.epa.pave.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A provider as the directory returned them, or as the tester typed them. Every field except the
 * NPI may be null, and a null is never replaced with a default: a network status the directory
 * did not return is not "in network".
 *
 * <p>Construct with {@link #builder()}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProviderDetail(
        String npi,
        String firstName,
        String lastName,
        String organizationName,
        String specialty,
        String taxonomyCode,
        String networkStatus,
        String phone,
        PostalAddress address) {

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder().npi(npi).firstName(firstName).lastName(lastName).organizationName(organizationName)
                .specialty(specialty).taxonomyCode(taxonomyCode).networkStatus(networkStatus).phone(phone).address(address);
    }

    /** The name to show for this provider: the person when there is one, otherwise the organization. Null when neither is known. */
    public String displayName() {
        String person = Texts.firstPresent(
                Texts.present(firstName) && Texts.present(lastName) ? firstName.strip() + " " + lastName.strip() : null,
                lastName, firstName);
        return Texts.firstPresent(person, organizationName);
    }

    public static final class Builder {
        private String npi;
        private String firstName;
        private String lastName;
        private String organizationName;
        private String specialty;
        private String taxonomyCode;
        private String networkStatus;
        private String phone;
        private PostalAddress address;

        private Builder() {
        }

        public Builder npi(String value) {
            this.npi = value;
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

        public Builder organizationName(String value) {
            this.organizationName = value;
            return this;
        }

        public Builder specialty(String value) {
            this.specialty = value;
            return this;
        }

        public Builder taxonomyCode(String value) {
            this.taxonomyCode = value;
            return this;
        }

        public Builder networkStatus(String value) {
            this.networkStatus = value;
            return this;
        }

        public Builder phone(String value) {
            this.phone = value;
            return this;
        }

        public Builder address(PostalAddress value) {
            this.address = value;
            return this;
        }

        public ProviderDetail build() {
            return new ProviderDetail(npi, firstName, lastName, organizationName, specialty, taxonomyCode, networkStatus, phone, address);
        }
    }
}
