package org.p32h.interop.epa.pave.service.lists;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Locale;
import java.util.Map;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * A saved test member as the form or a CSV row gives it, without the id this tool mints. A
 * property the record does not have is refused by name, an {@code id} among them: the id of an
 * entry to replace is in the address.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"label", "memberId", "firstName", "lastName", "dateOfBirth", "gender", "lineOfBusiness", "plan", "subscriberId",
        "coverageStatus", "coverageStart", "coverageEnd", "vendorPatientId"})
public record TestMemberInput(String label, String memberId, String firstName, String lastName, String dateOfBirth, String gender,
        String lineOfBusiness, String plan, String subscriberId, String coverageStatus, String coverageStart, String coverageEnd,
        String vendorPatientId) {

    /**
     * Spaces at the ends are not part of a value, and a blank value is no value. Gender is
     * compared without case so that "Female" from a spreadsheet is the "female" the Member card's
     * select offers; anything else stays as written.
     */
    public TestMemberInput {
        label = Texts.trimToNull(label);
        memberId = Texts.trimToNull(memberId);
        firstName = Texts.trimToNull(firstName);
        lastName = Texts.trimToNull(lastName);
        dateOfBirth = Texts.trimToNull(dateOfBirth);
        gender = Texts.trimToNull(gender);
        if (gender != null && TestListRules.GENDERS.contains(gender.toLowerCase(Locale.ROOT))) {
            gender = gender.toLowerCase(Locale.ROOT);
        }
        lineOfBusiness = Texts.trimToNull(lineOfBusiness);
        plan = Texts.trimToNull(plan);
        subscriberId = Texts.trimToNull(subscriberId);
        coverageStatus = Texts.trimToNull(coverageStatus);
        coverageStart = Texts.trimToNull(coverageStart);
        coverageEnd = Texts.trimToNull(coverageEnd);
        vendorPatientId = Texts.trimToNull(vendorPatientId);
    }

    static TestMemberInput of(Map<String, String> values) {
        return new TestMemberInput(values.get("label"), values.get("memberId"), values.get("firstName"), values.get("lastName"),
                values.get("dateOfBirth"), values.get("gender"), values.get("lineOfBusiness"), values.get("plan"),
                values.get("subscriberId"), values.get("coverageStatus"), values.get("coverageStart"), values.get("coverageEnd"),
                values.get("vendorPatientId"));
    }

    TestMember withId(String id) {
        return new TestMember(id, label, memberId, firstName, lastName, dateOfBirth, gender, lineOfBusiness, plan, subscriberId,
                coverageStatus, coverageStart, coverageEnd, vendorPatientId);
    }

    /** Nothing but the count of values given: every value is member data. */
    @Override
    public String toString() {
        return "TestMemberInput[values=" + withId(null).values().size() + "]";
    }
}
