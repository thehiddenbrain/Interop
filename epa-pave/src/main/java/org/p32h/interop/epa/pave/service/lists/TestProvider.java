package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A saved test provider. It has an NPI, a vendor practitioner id, or both; every value is as the
 * tester gave it.
 *
 * @param id                   minted by this tool when the entry was added
 * @param vendorPractitionerId the vendor's own id for this practitioner, sent as the Practitioner id when given
 * @param placeOfService       the place of service this provider usually works in, which fills the Service card
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "label", "npi", "vendorPractitionerId", "firstName", "lastName", "organization", "specialty", "phone", "fax",
        "placeOfService"})
public record TestProvider(String id, String label, String npi, String vendorPractitionerId, String firstName, String lastName,
        String organization, String specialty, String phone, String fax, String placeOfService) implements TestListEntry {

    /** The columns of a providers CSV file, in the order the template writes them. */
    public static final List<String> COLUMNS = List.of("label", "npi", "vendorPractitionerId", "firstName", "lastName", "organization",
            "specialty", "phone", "fax", "placeOfService");

    Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        TestMember.put(values, "label", label);
        TestMember.put(values, "npi", npi);
        TestMember.put(values, "vendorPractitionerId", vendorPractitionerId);
        TestMember.put(values, "firstName", firstName);
        TestMember.put(values, "lastName", lastName);
        TestMember.put(values, "organization", organization);
        TestMember.put(values, "specialty", specialty);
        TestMember.put(values, "phone", phone);
        TestMember.put(values, "fax", fax);
        TestMember.put(values, "placeOfService", placeOfService);
        return values;
    }

    /** The id only, like a saved member: one rule for every list keeps a log free of whatever a list holds. */
    @Override
    public String toString() {
        return "TestProvider[id=" + id + "]";
    }
}
