package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PermissionRule} on its own: the age band test that decides whether a row applies to a viewed member
 * (catalog section 4: bands Any = null/null, 0-11, 0-12, 13-17 and "18 or older" = 18/null, both bounds
 * inclusive), the parent-key detection the evaluator uses to skip "Derived from child permissions" rows, and
 * the family prefix that consents are matched on.
 */
class PermissionRuleTest {

    private static final List<String> PARENT_KEYS =
            List.of("benefits", "claims", "demographic", "documents", "forms", "profile");

    /** A row whose only interesting attribute is its age band. */
    private static PermissionRule band(Integer minimumAge, Integer maximumAge) {
        return new PermissionRule(1L, "benefits", "benefits.idCard", ActorRelationship.SUBSCRIBER,
                ViewingRelationship.CHILD, minimumAge, maximumAge, List.of(1), "FULL_ACCESS", false, false);
    }

    /** A row whose only interesting attribute is its permission key. */
    private static PermissionRule keyed(String permissionKey) {
        return new PermissionRule(2L, PermissionRule.familyOf(permissionKey), permissionKey,
                ActorRelationship.SUBSCRIBER, ViewingRelationship.SELF, null, null, List.of(1), "FULL_ACCESS", false, false);
    }

    // ------------------------------------------------------------------------------------------ appliesToAge

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 1, 12, 13, 17, 18, 64, 120})
    void anyBandAppliesToEveryAge(int age) {
        assertThat(band(null, null).appliesToAge(age)).isTrue();
    }

    @Test
    void minimumAgeIsInclusive() {
        assertThat(band(0, 12).appliesToAge(0)).isTrue();
        assertThat(band(13, 17).appliesToAge(13)).isTrue();
        assertThat(band(18, null).appliesToAge(18)).isTrue();
    }

    @Test
    void ageBelowMinimumDoesNotApply() {
        assertThat(band(13, 17).appliesToAge(12)).isFalse();
        assertThat(band(18, null).appliesToAge(17)).isFalse();
        assertThat(band(1, 12).appliesToAge(0)).isFalse();
    }

    @Test
    void maximumAgeIsInclusive() {
        assertThat(band(0, 11).appliesToAge(11)).isTrue();
        assertThat(band(0, 12).appliesToAge(12)).isTrue();
        assertThat(band(13, 17).appliesToAge(17)).isTrue();
    }

    @Test
    void ageAboveMaximumDoesNotApply() {
        assertThat(band(0, 11).appliesToAge(12)).isFalse();
        assertThat(band(0, 12).appliesToAge(13)).isFalse();
        assertThat(band(13, 17).appliesToAge(18)).isFalse();
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {18, 19, 45, 120})
    void nullMaximumIsOpenEnded(int age) {
        assertThat(band(18, null).appliesToAge(age)).isTrue();
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 5, 11})
    void nullMinimumIsOpenBelow(int age) {
        assertThat(band(null, 11).appliesToAge(age)).isTrue();
    }

    @Test
    void nullMinimumStillEnforcesTheMaximum() {
        assertThat(band(null, 11).appliesToAge(12)).isFalse();
        assertThat(band(null, 11).appliesToAge(90)).isFalse();
    }

    @Test
    void nullMaximumStillEnforcesTheMinimum() {
        assertThat(band(18, null).appliesToAge(17)).isFalse();
        assertThat(band(18, null).appliesToAge(0)).isFalse();
    }

    @Test
    void singleAgeBandMatchesOnlyThatAge() {
        PermissionRule five = band(5, 5);
        assertThat(five.appliesToAge(5)).isTrue();
        assertThat(five.appliesToAge(4)).isFalse();
        assertThat(five.appliesToAge(6)).isFalse();
    }

    @ParameterizedTest(name = "band [{0}, {1}] age {2} applies={3}")
    @CsvSource(nullValues = "NULL", value = {
            "NULL, NULL, 0, true",
            "NULL, NULL, 200, true",
            "0, 11, 0, true",
            "0, 11, 11, true",
            "0, 11, 12, false",
            "0, 12, 0, true",
            "0, 12, 12, true",
            "0, 12, 13, false",
            "13, 17, 12, false",
            "13, 17, 13, true",
            "13, 17, 17, true",
            "13, 17, 18, false",
            "18, NULL, 17, false",
            "18, NULL, 18, true",
            "18, NULL, 99, true"})
    void workbookAgeBands(Integer minimumAge, Integer maximumAge, int age, boolean expected) {
        assertThat(band(minimumAge, maximumAge).appliesToAge(age)).isEqualTo(expected);
    }

    // ------------------------------------------------------------------------------------------ isParentKey

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"benefits", "claims", "demographic", "documents", "forms", "profile"})
    void familyLevelKeysAreParentKeys(String key) {
        assertThat(keyed(key).isParentKey()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "benefits.accumulator", "benefits.activePolicy", "benefits.coverage", "benefits.idCard", "benefits.spendingAccount",
            "claims.authorization", "claims.claim", "claims.referral",
            "demographic.contract", "demographic.memberOther", "demographic.memberSelf",
            "documents.letter", "documents.planDocument", "documents.taxDocument",
            "forms.capeCodHealthcare", "forms.coordinationOfBenefits", "forms.designationOfRepresentative",
            "forms.medicalReimbursement",
            "profile.raceEthnicityLanguage", "profile.sexualOrientationGenderIdentity"})
    void everyCatalogChildKeyIsNotAParentKey(String key) {
        assertThat(keyed(key).isParentKey()).isFalse();
    }

    // ------------------------------------------------------------------------------------------ familyOf

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "benefits.idCard, benefits",
            "benefits.spendingAccount, benefits",
            "claims.claim, claims",
            "demographic.memberSelf, demographic",
            "documents.taxDocument, documents",
            "forms.designationOfRepresentative, forms",
            "profile.sexualOrientationGenderIdentity, profile"})
    void familyOfChildKeyIsThePrefixBeforeTheDot(String key, String family) {
        assertThat(PermissionRule.familyOf(key)).isEqualTo(family);
    }

    @Test
    void familyOfParentKeyIsTheKeyItself() {
        for (String parent : PARENT_KEYS) {
            assertThat(PermissionRule.familyOf(parent)).isEqualTo(parent);
        }
    }

    @Test
    void familyOfStopsAtTheFirstDot() {
        assertThat(PermissionRule.familyOf("a.b.c")).isEqualTo("a");
    }

    @Test
    void familyOfAgreesWithTheRowsPermissionFamily() {
        // mirrors the ck_family_matches_key constraint: permission_family = split_part(permission_key, '.', 1)
        PermissionRule rule = keyed("claims.referral");
        assertThat(PermissionRule.familyOf(rule.permissionKey())).isEqualTo(rule.permissionFamily()).isEqualTo("claims");
    }

    // ------------------------------------------------------------------------------------------ record

    @Test
    void accessorsExposeEveryColumnOfTheRow() {
        PermissionRule rule = new PermissionRule(29L, "claims", "claims.claim", ActorRelationship.SUBSCRIBER,
                ViewingRelationship.CHILD, 13, 17, List.of(1), "MASKED_ACCESS", true, true);

        assertThat(rule.id()).isEqualTo(29L);
        assertThat(rule.permissionFamily()).isEqualTo("claims");
        assertThat(rule.permissionKey()).isEqualTo("claims.claim");
        assertThat(rule.actor()).isEqualTo(ActorRelationship.SUBSCRIBER);
        assertThat(rule.viewing()).isEqualTo(ViewingRelationship.CHILD);
        assertThat(rule.minimumAge()).isEqualTo(13);
        assertThat(rule.maximumAge()).isEqualTo(17);
        assertThat(rule.actionCodes()).containsExactly(1);
        assertThat(rule.accessStatus()).isEqualTo("MASKED_ACCESS");
        assertThat(rule.consentRequired()).isTrue();
        assertThat(rule.maskedData()).isTrue();
        assertThat(rule.isParentKey()).isFalse();
    }

    @Test
    void equalityIsValueBased() {
        PermissionRule a = band(13, 17);
        PermissionRule b = band(13, 17);
        PermissionRule differentBand = band(0, 12);

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentBand);
    }

    @Test
    void emptyActionCodesMeansNoDefaultAccess() {
        PermissionRule noAccess = new PermissionRule(31L, "claims", "claims.claim", ActorRelationship.SUBSCRIBER,
                ViewingRelationship.ALL_OTHER, null, null, List.of(), "NO_ACCESS", false, false);

        assertThat(noAccess.actionCodes()).isEmpty();
        assertThat(noAccess.appliesToAge(50)).isTrue();
    }
}
