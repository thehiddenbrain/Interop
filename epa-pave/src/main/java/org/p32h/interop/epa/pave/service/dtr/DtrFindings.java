package org.p32h.interop.epa.pave.service.dtr;

import org.p32h.interop.epa.pave.model.Finding;
import org.p32h.interop.epa.pave.model.FindingKind;
import org.p32h.interop.epa.pave.model.dtr.DtrCode;
import org.p32h.interop.epa.pave.client.dtr.OnyxDtrConventions;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.dtr.PackageBundleView;
import org.p32h.interop.epa.pave.model.dtr.DtrDetail;
import org.p32h.interop.epa.pave.model.dtr.DtrOutcome;
import org.p32h.interop.epa.pave.model.dtr.DtrResponseKind;
import org.p32h.interop.epa.pave.model.dtr.ExtensionView;
import org.p32h.interop.epa.pave.model.dtr.EnableWhenView;
import org.p32h.interop.epa.pave.model.dtr.ItemView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireResponseView;
import org.p32h.interop.epa.pave.model.dtr.QuestionnaireView;
import java.util.ArrayList;
import java.util.List;

/**
 * What on a DTR result a person needs to look at, apart from the outcome: a coded value outside
 * the value set FHIR binds its element to, a shape of a later version, and a package bundle that
 * breaks the DTR 2.0.1 invariant dtrb-1.
 *
 * <p>Such a code is already shown where it sits, as "Unrecognised code: X". It is raised here as
 * well because each one changes what this tool can do with the questionnaire: an item type it does
 * not know cannot be answered from plain text, an operator it does not know leaves a condition
 * unevaluated, and a status it does not know cannot end the adaptive loop. A finding is not a
 * failure of the call, and it never changes the outcome or the test result.
 *
 * <p>The value sets are the FHIR R4 ones held in {@link DtrCodes}, which says what they were
 * compared with. Each finding states the value set it was checked against, so a reader can see what
 * the tool took the specification to say.
 *
 * <p>A shape that DTR 2.0.1, the version this tool is held to, does not define and the 2.2.0 ballot
 * does is raised as {@link FindingKind#OTHER_VERSION_CONTENT}: read, named with the version that
 * defines it, and not called wrong, because which version the service follows is the service's to say.
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
            if (detail.sent()) {
                // Only what the service returned: a response this tool built, or one it was posted back, is not the service speaking here.
                laterVersion(findings, response);
            }
        }
        if (reading.outcome() == DtrOutcome.NO_QUESTIONNAIRE && detail.responseKind() == DtrResponseKind.PARAMETERS && detail.bundles().isEmpty()) {
            findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT, "A package response with no package bundle follows a later version of DTR",
                    "Under DTR 2.0.1, the version this tool is held to, a $questionnaire-package response holds at least one PackageBundle "
                            + "(dtr-qpackage-output-parameters, PackageBundle 1..*). The DTR guide from STU 2.1.0 on, and the 2.2.0 ballot, "
                            + "allow none, as the answer that no questionnaire needs to be completed. This answer follows that later form.",
                    "DTR 2.0.1", null, "The service returned a Parameters resource with no Bundle in it."));
        }
        dtrb1(findings, detail);
        return findings;
    }

    /**
     * A package bundle whose first entry is not a Questionnaire breaks the DTR 2.0.1 invariant
     * dtrb-1, which DTR-QPackageBundle and the PackageBundle of dtr-qpackage-output-parameters both
     * state. It is a rule of the guide, not a missing element, so it is raised as one, and history
     * can be searched for it. Only a bundle the package is made of is judged: a bundle carried
     * inside one is not a package bundle.
     */
    private static void dtrb1(List<Finding> findings, DtrDetail detail) {
        List<String> starts = detail.questionnaires().stream().map(QuestionnaireView::foundAt).toList();
        for (PackageBundleView bundle : detail.bundles()) {
            String at = bundle.foundAt();
            boolean inside = detail.bundles().stream().map(PackageBundleView::foundAt)
                    .anyMatch(other -> !java.util.Objects.equals(other, at) && Texts.orElse(at, "").startsWith(within(other)));
            if (inside || starts.contains(within(at) + "entry[0].resource")) {
                continue;
            }
            String where = within(at).isEmpty() ? "the Bundle the response is" : at;
            findings.add(new Finding(FindingKind.GUIDE_RULE_BROKEN, "A package bundle does not start with its questionnaire (dtrb-1)",
                    "DTR 2.0.1 requires the first resource of a package bundle to be a Questionnaire (invariant dtrb-1, on DTR-QPackageBundle "
                            + "and on the PackageBundle of dtr-qpackage-output-parameters). " + (bundle.entryCount() == 0
                            ? "This bundle holds no entry." : "The first entry of this bundle is not a Questionnaire.")
                            + " What the bundle holds is shown as it came.",
                    where, null, null));
        }
    }

    /** What the place of anything inside the bundle at this place starts with. The response itself is written "(root)". */
    private static String within(String bundleAt) {
        return !Texts.present(bundleAt) || "(root)".equals(bundleAt) ? "" : bundleAt + ".";
    }

    /** The two QuestionnaireResponse shapes the 2.2.0 ballot defines and 2.0.1 does not. */
    private static void laterVersion(List<Finding> findings, QuestionnaireResponseView response) {
        boolean qrCoverage = response.extensions().stream().map(ExtensionView::url)
                .anyMatch(url -> url.hasValue() && OnyxDtrConventions.EXT_QR_COVERAGE.equals(url.value()));
        if (qrCoverage) {
            findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT, "The response names its coverage in qr-coverage, a DTR 2.2.0-ballot extension",
                    "DTR 2.0.1, the version this tool is held to, defines no qr-coverage extension: it names the coverage in a qr-context "
                            + "extension, beside the order, and requires at least two qr-context. The DTR 2.2.0 ballot defines qr-coverage "
                            + "for the coverage and keeps qr-context for orders. The response was read as it came.",
                    response.foundAt(), null, "The service returned the extension " + OnyxDtrConventions.EXT_QR_COVERAGE + "."));
        }
        for (Shown profile : response.profiles()) {
            if (profile.hasValue() && profile.value().endsWith("/StructureDefinition/" + OnyxDtrConventions.PROFILE_QR_ADAPT_LATER)) {
                findings.add(new Finding(FindingKind.OTHER_VERSION_CONTENT, "The response declares a DTR 2.2.0-ballot profile",
                        "The profile " + profile.value() + " is defined by the DTR 2.2.0 ballot and not by DTR 2.0.1, the version this tool "
                                + "is held to, whose adaptive responses declare dtr-questionnaireresponse. The response was read as it came.",
                        response.foundAt(), null, "The service declared the profile " + profile.value() + "."));
            }
        }
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
