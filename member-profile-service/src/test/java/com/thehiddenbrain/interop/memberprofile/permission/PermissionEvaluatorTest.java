package com.thehiddenbrain.interop.memberprofile.permission;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PermissionEvaluatorTest {

    private final PermissionEvaluator evaluator = new PermissionEvaluator();

    @Test
    void actionsAreUnionedAndParentKeyIsDerived() {
        List<PermissionRule> rules = List.of(
                rule(1, "benefits.idCard", "Child", 0, 12, List.of(1, 3), false, false),
                rule(2, "benefits.coverage", "Child", 0, 12, List.of(1), false, false),
                rule(3, "benefits", "Child", 0, 12, List.of(), false, false));   // parent row is ignored

        PermissionResult result = evaluator.evaluate(rules, "Child", 7, "M2", Set.of());

        assertThat(result.permissions())
                .containsEntry("benefits", List.of(1))
                .containsEntry("benefits.idCard", List.of(1, 3))
                .containsEntry("benefits.coverage", List.of(1));
        assertThat(result.consentRequired()).isEmpty();
        assertThat(result.masked()).isEmpty();
    }

    @Test
    void rowsOutsideTheAgeBandDoNotApply() {
        List<PermissionRule> rules = List.of(
                rule(1, "benefits.idCard", "Child", 0, 12, List.of(1, 3), false, false),
                rule(2, "benefits.idCard", "Child", 13, 17, List.of(1), false, false));

        assertThat(evaluator.evaluate(rules, "Child", 15, "M2", Set.of()).permissions())
                .containsEntry("benefits.idCard", List.of(1));
        assertThat(evaluator.evaluate(rules, "Child", 18, "M2", Set.of()).permissions()).isEmpty();
    }

    @Test
    void exactViewingRelationshipWinsOverCatchAllPerKey() {
        List<PermissionRule> rules = List.of(
                rule(1, "claims.claim", "Child", null, null, List.of(1, 3), false, false),
                rule(2, "claims.claim", PermissionRule.CATCH_ALL_VIEWING, null, null, List.of(), false, false),
                rule(3, "claims.referral", PermissionRule.CATCH_ALL_VIEWING, null, null, List.of(1), false, false));

        PermissionResult result = evaluator.evaluate(rules, "Child", 7, "M2", Set.of());

        assertThat(result.permissions())
                .containsEntry("claims.claim", List.of(1, 3))      // exact row, not the empty catch-all
                .containsEntry("claims.referral", List.of(1))     // catch-all used: no exact row for this key
                .containsEntry("claims", List.of(1));
    }

    @Test
    void consentRequiredWithoutConsentGrantsNothingAndIsReported() {
        List<PermissionRule> rules = List.of(
                rule(1, "claims.claim", "Child", 13, 17, List.of(1), true, true));

        PermissionResult result = evaluator.evaluate(rules, "Child", 15, "M2", Set.of());

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.claim");
        assertThat(result.masked()).isEmpty();
    }

    @Test
    void consentOnFileGrantsActionsAndKeepsMasking() {
        List<PermissionRule> rules = List.of(
                rule(1, "claims.claim", "Child", 13, 17, List.of(1), true, true));

        PermissionResult result = evaluator.evaluate(rules, "Child", 15, "M2",
                Set.of(ConsentRepository.consentKey("M2", "claims")));

        assertThat(result.permissions()).containsEntry("claims.claim", List.of(1)).containsEntry("claims", List.of(1));
        assertThat(result.consentRequired()).isEmpty();
        assertThat(result.masked()).containsExactly("claims.claim");
    }

    @Test
    void consentForAnotherMemberDoesNotCount() {
        List<PermissionRule> rules = List.of(
                rule(1, "claims.claim", "Child", 13, 17, List.of(1), true, false));

        PermissionResult result = evaluator.evaluate(rules, "Child", 15, "M2",
                Set.of(ConsentRepository.consentKey("M3", "claims")));

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.claim");
    }

    private static PermissionRule rule(long id, String key, String viewing, Integer min, Integer max,
                                       List<Integer> actions, boolean consent, boolean masked) {
        return new PermissionRule(id, PermissionEvaluator.family(key), key, "Subscriber", viewing, min, max,
                actions, actions.isEmpty() ? "NO_ACCESS" : "FULL_ACCESS", consent, masked);
    }
}
