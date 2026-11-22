package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A saved test member: a sandbox member the tester keeps at hand to fill the Member card. Every
 * value is as the tester gave it; nothing is looked up or derived, and a value not given stays absent.
 *
 * @param id              minted by this tool when the entry was added; never a member identifier
 * @param label           what the member plays in a test, in the tester's words
 * @param vendorPatientId the vendor's own id for this patient, sent as the Patient id when given
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"id", "label", "memberId", "firstName", "lastName", "dateOfBirth", "gender", "lineOfBusiness", "plan",
        "subscriberId", "coverageStatus", "coverageStart", "coverageEnd", "vendorPatientId"})
public record TestMember(String id, String label, String memberId, String firstName, String lastName, String dateOfBirth,
        String gender, String lineOfBusiness, String plan, String subscriberId, String coverageStatus, String coverageStart,
        String coverageEnd, String vendorPatientId) implements TestListEntry {

    /** The columns of a members CSV file, in the order the template writes them. */
    public static final List<String> COLUMNS = List.of("label", "memberId", "firstName", "lastName", "dateOfBirth", "gender",
            "lineOfBusiness", "plan", "subscriberId", "coverageStatus", "coverageStart", "coverageEnd", "vendorPatientId");

    /** The values by column name, absent ones left out. */
    Map<String, String> values() {
        Map<String, String> values = new LinkedHashMap<>();
        put(values, "label", label);
        put(values, "memberId", memberId);
        put(values, "firstName", firstName);
        put(values, "lastName", lastName);
        put(values, "dateOfBirth", dateOfBirth);
        put(values, "gender", gender);
        put(values, "lineOfBusiness", lineOfBusiness);
        put(values, "plan", plan);
        put(values, "subscriberId", subscriberId);
        put(values, "coverageStatus", coverageStatus);
        put(values, "coverageStart", coverageStart);
        put(values, "coverageEnd", coverageEnd);
        put(values, "vendorPatientId", vendorPatientId);
        return values;
    }

    static void put(Map<String, String> values, String name, String value) {
        if (value != null) {
            values.put(name, value);
        }
    }

    /** The id only: every other value is member data, which never reaches a log. */
    @Override
    public String toString() {
        return "TestMember[id=" + id + "]";
    }
}
