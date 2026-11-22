package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.RequestShapes;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.service.crd.CrdRequestBuilder;
import org.p32h.interop.epa.pave.service.crd.CrdRunRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrPackageRequest;
import org.p32h.interop.epa.pave.service.dtr.DtrRequestBuilder;
import org.p32h.interop.epa.pave.testsupport.FakeGateway;
import tools.jackson.databind.JsonNode;

/**
 * The environment's member ID rule (R-10) decides the member ID in every CRD and DTR request the
 * tool builds, and never a resource id: the Patient keeps patient-1 or the vendor's patient id.
 * The builders are held here; service/config/MemberIdRuleThroughTheServicesTest holds the services, the wire view
 * and the ledger.
 */
public class MemberIdRuleInRequestsTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-18T14:05:09Z"), ZoneOffset.UTC);
    private static final Function<String, ResolvedText> NOTHING_SET = template -> new FakeGateway().resolve(template, Map.of());

    /** An 11-character placeholder ID typed in the card form, so packed, spaced and split each send something different. */
    public static final String CARD_FORM = "S12345678 01";
    public static final String PACKED = "S1234567801";
    public static final String SPACED = "S12345678   01";
    public static final String SPLIT = "S12345678";
    /** A placeholder of another length: spaced and split cannot apply and it goes packed, with the note. */
    public static final String NINE = "S1234 5678";

    public static String expected(MemberIdRule rule) {
        return switch (rule) {
            case PACKED -> PACKED;
            case SPACED -> SPACED;
            case SPLIT -> SPLIT;
        };
    }

    private static MemberDetail member(String memberId) {
        return MemberDetail.builder().memberId(memberId).firstName("Given").lastName("Placeholder-Surname").dateOfBirth("1970-01-31")
                .gender("female").coverageStatus("active").build();
    }

    private static ProviderDetail provider() {
        return ProviderDetail.builder().npi("1234567893").firstName("Pat").lastName("Provider").build();
    }

    private static JsonNode crd(String memberId, MemberIdRule rule) {
        CrdRunRequest request = CrdRunRequest.builder().member(member(memberId)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20").build();
        return JsonSafe.parse(new CrdRequestBuilder(null, CLOCK).build(request, "hook-instance-1", NOTHING_SET, RequestShapes.DEFAULTS, rule)
                .json()).node();
    }

    private static JsonNode dtr(String memberId, MemberIdRule rule) {
        DtrPackageRequest request = DtrPackageRequest.builder().member(member(memberId)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20").build();
        return JsonSafe.parse(new DtrRequestBuilder(null).buildPackage(request, NOTHING_SET, rule).json()).node();
    }

    /** Every member ID identifier (type MB) in a request, in document order, found with a stack and not by recursion. */
    public static List<String> memberIdentifiers(JsonNode root) {
        List<String> found = new ArrayList<>();
        java.util.ArrayDeque<JsonNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node.isObject()) {
                JsonNode coding = node.path("type").path("coding").path(0);
                if ("MB".equals(JsonSafe.text(coding, "code")) && JsonSafe.text(node, "value") != null) {
                    found.add(JsonSafe.text(node, "value"));
                }
            }
            List<JsonNode> children = new ArrayList<>();
            node.forEach(children::add);
            for (int i = children.size() - 1; i >= 0; i--) {
                stack.push(children.get(i));
            }
        }
        return found;
    }

    /** Every resource id in a request, so no form of the member ID can pass for one. */
    public static List<String> resourceIds(JsonNode root) {
        List<String> found = new ArrayList<>();
        java.util.ArrayDeque<JsonNode> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            JsonNode node = stack.pop();
            if (node.isObject() && JsonSafe.text(node, "resourceType") != null && JsonSafe.text(node, "id") != null) {
                found.add(JsonSafe.text(node, "id"));
            }
            node.forEach(stack::push);
        }
        return found;
    }

    @ParameterizedTest
    @EnumSource(MemberIdRule.class)
    void theCrdRequestSendsTheMemberIdAsTheRuleGivesItOnThePatientAndTheCoverage(MemberIdRule rule) {
        JsonNode body = crd(CARD_FORM, rule);
        // The Patient in patientBundle and the Coverage in coverageBundle each carry it once.
        assertThat(memberIdentifiers(body)).containsExactly(expected(rule), expected(rule));
        assertThat(body.at("/context/patientId").asString()).isEqualTo("patient-1");
        assertThat(resourceIds(body)).doesNotContain(PACKED, SPACED, SPLIT, CARD_FORM);
    }

    @ParameterizedTest
    @EnumSource(MemberIdRule.class)
    void theDtrPackageSendsTheMemberIdAsTheRuleGivesItOnTheCoverageAndThePatient(MemberIdRule rule) {
        JsonNode body = dtr(CARD_FORM, rule);
        assertThat(memberIdentifiers(body)).containsExactly(expected(rule), expected(rule));
        assertThat(resourceIds(body)).doesNotContain(PACKED, SPACED, SPLIT, CARD_FORM);
    }

    @Test
    void anIdOfAnotherLengthIsSentPackedUnderEveryRule() {
        for (MemberIdRule rule : MemberIdRule.values()) {
            assertThat(memberIdentifiers(crd(NINE, rule))).as(rule.word()).containsExactly("S12345678", "S12345678");
            assertThat(memberIdentifiers(dtr(NINE, rule))).as(rule.word()).containsExactly("S12345678", "S12345678");
        }
    }

    @Test
    void theOlderEntryPointsAndANullRuleSendPacked() {
        CrdRunRequest request = CrdRunRequest.builder().member(member(CARD_FORM)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20").build();
        JsonNode older = JsonSafe.parse(new CrdRequestBuilder(null, CLOCK).build(request, "hook-instance-1", NOTHING_SET, RequestShapes.DEFAULTS)
                .json()).node();
        assertThat(memberIdentifiers(older)).containsExactly(PACKED, PACKED);
        assertThat(memberIdentifiers(crd(CARD_FORM, null))).containsExactly(PACKED, PACKED);
        DtrPackageRequest dtrRequest = DtrPackageRequest.builder().member(member(CARD_FORM)).provider(provider())
                .procedureCodes(List.of(new CodeInput("95810", null, "Sleep study, attended"))).dateOfService("2026-03-20").build();
        assertThat(memberIdentifiers(JsonSafe.parse(new DtrRequestBuilder(null).buildPackage(dtrRequest, NOTHING_SET).json()).node()))
                .containsExactly(PACKED, PACKED);
    }

    @Test
    void theFormsTheWireShowsNameTheRuleAndNoteAnIdItCouldNotApplyTo() {
        MemberIdForms spaced = CaseResources.memberIdForms(member(CARD_FORM), MemberIdRule.SPACED);
        assertThat(spaced.typed()).isEqualTo(CARD_FORM);
        assertThat(spaced.sent()).isEqualTo(SPACED);
        assertThat(spaced.changed()).isTrue();
        assertThat(spaced.rule()).isEqualTo("spaced");
        assertThat(spaced.note()).isNull();

        MemberIdForms split = CaseResources.memberIdForms(member(NINE), MemberIdRule.SPLIT);
        assertThat(split.sent()).isEqualTo("S12345678");
        assertThat(split.rule()).isEqualTo("split");
        assertThat(split.note()).isEqualTo("The split rule applies to an 11-character ID; this one has 9, so it was sent packed.");

        assertThat(CaseResources.memberIdForms(member(PACKED), null).rule()).isEqualTo("packed");
        // No ID, nothing to show: the wire view leaves the line out rather than show an empty one.
        assertThat(CaseResources.memberIdForms(member(" "), MemberIdRule.SPACED)).isNull();
        assertThat(CaseResources.memberIdForms(null, MemberIdRule.SPACED)).isNull();
    }
}
