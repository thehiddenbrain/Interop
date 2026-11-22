package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Map;
import org.p32h.interop.epa.pave.model.Texts;

/** A saved test provider as the form or a CSV row gives it, without the id this tool mints. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"label", "npi", "vendorPractitionerId", "firstName", "lastName", "organization", "specialty", "phone", "fax",
        "placeOfService"})
public record TestProviderInput(String label, String npi, String vendorPractitionerId, String firstName, String lastName,
        String organization, String specialty, String phone, String fax, String placeOfService) {

    /** Spaces at the ends are not part of a value, and a blank value is no value. */
    public TestProviderInput {
        label = Texts.trimToNull(label);
        npi = Texts.trimToNull(npi);
        vendorPractitionerId = Texts.trimToNull(vendorPractitionerId);
        firstName = Texts.trimToNull(firstName);
        lastName = Texts.trimToNull(lastName);
        organization = Texts.trimToNull(organization);
        specialty = Texts.trimToNull(specialty);
        phone = Texts.trimToNull(phone);
        fax = Texts.trimToNull(fax);
        placeOfService = Texts.trimToNull(placeOfService);
    }

    static TestProviderInput of(Map<String, String> values) {
        return new TestProviderInput(values.get("label"), values.get("npi"), values.get("vendorPractitionerId"), values.get("firstName"),
                values.get("lastName"), values.get("organization"), values.get("specialty"), values.get("phone"), values.get("fax"),
                values.get("placeOfService"));
    }

    TestProvider withId(String id) {
        return new TestProvider(id, label, npi, vendorPractitionerId, firstName, lastName, organization, specialty, phone, fax,
                placeOfService);
    }

    @Override
    public String toString() {
        return "TestProviderInput[values=" + withId(null).values().size() + "]";
    }
}
