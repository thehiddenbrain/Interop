package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.dtr.DtrCode;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.EnableWhenView;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireResponseView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import java.util.ArrayList;
import java.util.List;

/**
 * What on a DTR result a person needs to look at, apart from the outcome: a coded value outside
 * the value set FHIR binds its element to.
 *
 * <p>Such a code is already shown where it sits, as "Unrecognised code: X". It is raised here as
 * well because each one changes what this tool can do with the questionnaire: an item type it does
 * not know cannot be answered from plain text, an operator it does not know leaves a condition
 * unevaluated, and a status it does not know cannot end the adaptive loop. A finding is not a
 * failure of the call, and it never changes the outcome or the test result.
 *
 * <p>The value sets are the FHIR R4 ones as the builder knows them. The published guides could not
 * be consulted when this was written, so each finding states the value set it was checked against.
 */
final class DtrFindings {

    private DtrFindings() {
    }

    static List<Finding> check(DtrReading reading) {
        List<Finding> findings = new ArrayList<>();
        DtrDetail detail = reading.detail();
        for (QuestionnaireView questionnaire : detail.questionnaires()) {
            outside(findings, questionnaire.subject(), "Questionnaire.status", DtrCodes.QUESTIONNAIRE_STATUS, questionnaire.status());
            items(findings, questionnaire.subject(), questionnaire.items());
        }
        for (QuestionnaireResponseView response : detail.questionnaireResponses()) {
            outside(findings, response.foundAt(), "QuestionnaireResponse.status", DtrCodes.RESPONSE_STATUS, response.status());
        }
        return findings;
    }

    private static void items(List<Finding> findings, String questionnaire, List<ItemView> items) {
        for (ItemView item : items) {
            // Named by where the item sits, not by its linkId: an item can lack a linkId or share
            // one with another item, and the place is the one name that is always there and unique.
            String where = questionnaire + ", " + item.path();
            outside(findings, where, "item.type", DtrCodes.ITEM_TYPE, item.type());
            outside(findings, where, "item.enableBehavior", DtrCodes.ENABLE_BEHAVIOR, item.enableBehavior());
            for (EnableWhenView condition : item.enableWhen()) {
                outside(findings, where, "item.enableWhen.operator", DtrCodes.OPERATOR, condition.operator());
            }
            items(findings, questionnaire, item.items());
        }
    }

    private static void outside(List<Finding> findings, String where, String element, String valueSet, DtrCode value) {
        if (value != null && value.code() != null && !value.recognised()) {
            findings.add(new Finding(FindingKind.CODE_OUTSIDE_VALUE_SET,
                    "Non-conformant code in " + element,
                    "The value \"" + value.code() + "\" is not in the value set FHIR R4 defines for " + element + ", which this tool "
                            + "knows as: " + String.join(", ", DtrCodes.codes(valueSet)) + ".", where, null,
                    "The service returned " + element + "=" + value.code() + "."));
        }
    }
}
