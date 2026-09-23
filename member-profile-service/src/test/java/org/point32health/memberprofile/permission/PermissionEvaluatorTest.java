package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.permission.PermissionResult.RuleTrace;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.point32health.memberprofile.permission.ViewingRelationship.ADULT_DEPENDENT;
import static org.point32health.memberprofile.permission.ViewingRelationship.ALL_OTHER;
import static org.point32health.memberprofile.permission.ViewingRelationship.CHILD;
import static org.point32health.memberprofile.permission.ViewingRelationship.SELF;
import static org.point32health.memberprofile.permission.ViewingRelationship.SPOUSE;
import static org.point32health.memberprofile.permission.ViewingRelationship.SUBSCRIBER;

/**
 * {@link PermissionEvaluator} against hand-built rows, one evaluation rule of catalog section 4 at a time:
 * <ol>
 *   <li>only rows whose age band contains the viewed member's age apply;</li>
 *   <li>exact viewing-relationship rows win, the catch-all row is used only for a key with no exact row;</li>
 *   <li>consent-required rows grant only with consent on file for (viewed member, family), otherwise the key
 *       is listed under consentRequired;</li>
 *   <li>masked rows add their key to masked when they grant;</li>
 *   <li>parent keys are computed from children, parent rows in the input are ignored;</li>
 * </ol>
 * plus action-code union/sort/de-duplication, the explain trace and index reuse across viewed members.
 * The actor relationship on a row is irrelevant here: the repository has already filtered by actor.
 */
class PermissionEvaluatorTest {

    private static final String VIEWED = "HP0000002";
    private static final Set<String> NO_CONSENT = Set.of();
    private static final List<Integer> VIEW = List.of(1);
    private static final List<Integer> VIEW_DOWNLOAD = List.of(1, 3);

    private final PermissionEvaluator evaluator = new PermissionEvaluator();
    /** Row ids are assigned in creation order within a test, so the first row built is id 1. */
    private final AtomicLong ids = new AtomicLong();

    // ------------------------------------------------------------------------------- row factories

    private PermissionRule row(String key, ViewingRelationship viewing, Integer minimumAge, Integer maximumAge,
                               List<Integer> codes, String status, boolean consentRequired, boolean maskedData) {
        return new PermissionRule(ids.incrementAndGet(), PermissionRule.familyOf(key), key, ActorRelationship.SUBSCRIBER,
                viewing, minimumAge, maximumAge, codes, status, consentRequired, maskedData);
    }

    private PermissionRule grant(String key, ViewingRelationship viewing, Integer minimumAge, Integer maximumAge, Integer... codes) {
        return row(key, viewing, minimumAge, maximumAge, List.of(codes), "FULL_ACCESS", false, false);
    }

    private PermissionRule noAccess(String key, ViewingRelationship viewing, Integer minimumAge, Integer maximumAge) {
        return row(key, viewing, minimumAge, maximumAge, List.of(), "NO_ACCESS", false, false);
    }

    private PermissionRule consent(String key, ViewingRelationship viewing, Integer minimumAge, Integer maximumAge, Integer... codes) {
        return row(key, viewing, minimumAge, maximumAge, List.of(codes), "CONSENT_REQUIRED", true, false);
    }

    private PermissionRule maskedConsent(String key, ViewingRelationship viewing, Integer minimumAge, Integer maximumAge, Integer... codes) {
        return row(key, viewing, minimumAge, maximumAge, List.of(codes), "MASKED_ACCESS", true, true);
    }

    private PermissionRule masked(String key, ViewingRelationship viewing, Integer minimumAge, Integer maximumAge, Integer... codes) {
        return row(key, viewing, minimumAge, maximumAge, List.of(codes), "MASKED_ACCESS", false, true);
    }

    /** A "Derived from child permissions" row as the workbook carries it for a family-level key. */
    private PermissionRule parent(String key, ViewingRelationship viewing, Integer... codes) {
        return row(key, viewing, null, null, List.of(codes), codes.length == 0 ? "NO_ACCESS" : "FULL_ACCESS", false, false);
    }

    private static Set<String> consentFor(String viewedMemberId, String family) {
        return Set.of(ConsentRepository.consentKey(viewedMemberId, family));
    }

    // ------------------------------------------------------------------------------- evaluation shortcuts

    private PermissionResult evaluate(List<PermissionRule> rules, ViewingRelationship viewing, int age) {
        return evaluate(rules, viewing, age, NO_CONSENT);
    }

    private PermissionResult evaluate(List<PermissionRule> rules, ViewingRelationship viewing, int age, Set<String> consents) {
        return evaluator.index(rules).evaluate(viewing, age, VIEWED, consents, false);
    }

    private PermissionResult explain(List<PermissionRule> rules, ViewingRelationship viewing, int age, Set<String> consents) {
        return evaluator.index(rules).evaluate(viewing, age, VIEWED, consents, true);
    }

    // =============================================================================== 1. age bands

    @Test
    void minimumAgeIsInclusive() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 13).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("benefits", VIEW, "benefits.idCard", VIEW));
    }

    @Test
    void ageBelowMinimumDoesNotApply() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 12).permissions()).isEmpty();
    }

    @Test
    void maximumAgeIsInclusive() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 17).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("benefits", VIEW, "benefits.idCard", VIEW));
    }

    @Test
    void ageAboveMaximumDoesNotApply() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 18).permissions()).isEmpty();
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 12, 13, 17, 18, 120})
    void nullBoundsApplyToAnyAge(int age) {
        List<PermissionRule> rules = List.of(grant("benefits.coverage", SPOUSE, null, null, 1));

        assertThat(evaluate(rules, SPOUSE, age).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("benefits", VIEW, "benefits.coverage", VIEW));
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {18, 19, 99})
    void nullMaximumIsOpenEnded(int age) {
        List<PermissionRule> rules = List.of(grant("benefits.coverage", ADULT_DEPENDENT, 18, null, 1));

        assertThat(evaluate(rules, ADULT_DEPENDENT, age).permissions()).containsEntry("benefits.coverage", VIEW);
    }

    @Test
    void nullMaximumStillEnforcesTheMinimum() {
        List<PermissionRule> rules = List.of(grant("benefits.coverage", ADULT_DEPENDENT, 18, null, 1));

        assertThat(evaluate(rules, ADULT_DEPENDENT, 17).permissions()).isEmpty();
    }

    @ParameterizedTest(name = "age {0}")
    @ValueSource(ints = {0, 5, 11})
    void nullMinimumIsOpenBelow(int age) {
        List<PermissionRule> rules = List.of(grant("profile.raceEthnicityLanguage", CHILD, null, 11, 1, 2));

        assertThat(evaluate(rules, CHILD, age).permissions()).containsEntry("profile.raceEthnicityLanguage", List.of(1, 2));
    }

    @Test
    void nullMinimumStillEnforcesTheMaximum() {
        List<PermissionRule> rules = List.of(grant("profile.raceEthnicityLanguage", CHILD, null, 11, 1, 2));

        assertThat(evaluate(rules, CHILD, 12).permissions()).isEmpty();
    }

    @Test
    void onlyTheRowsOfTheViewedMembersBandApplyWhenAKeyHasSeveralBands() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", CHILD, 0, 12, 1, 3),
                grant("benefits.idCard", CHILD, 13, 17, 1),
                grant("benefits.idCard", CHILD, 18, null, 1, 2));

        assertThat(evaluate(rules, CHILD, 7).permissions()).containsEntry("benefits.idCard", VIEW_DOWNLOAD);
        assertThat(evaluate(rules, CHILD, 12).permissions()).containsEntry("benefits.idCard", VIEW_DOWNLOAD);
        assertThat(evaluate(rules, CHILD, 13).permissions()).containsEntry("benefits.idCard", VIEW);
        assertThat(evaluate(rules, CHILD, 17).permissions()).containsEntry("benefits.idCard", VIEW);
        assertThat(evaluate(rules, CHILD, 18).permissions()).containsEntry("benefits.idCard", List.of(1, 2));
    }

    @Test
    void aGapBetweenBandsYieldsNoPermissionForThatAge() {
        // the workbook's REL_SOGI sheet has a 0-11 band followed by a 13-17 band: age 12 matches neither
        List<PermissionRule> rules = List.of(
                grant("profile.raceEthnicityLanguage", CHILD, 0, 11, 1, 2),
                noAccess("profile.raceEthnicityLanguage", CHILD, 13, 17));

        assertThat(evaluate(rules, CHILD, 11).permissions()).containsEntry("profile.raceEthnicityLanguage", List.of(1, 2));
        assertThat(evaluate(rules, CHILD, 12).permissions()).isEmpty();
        assertThat(evaluate(rules, CHILD, 13).permissions()).isEmpty();
    }

    // =============================================================================== 2. exact over catch-all

    @Test
    void catchAllRowIsUsedForAKeyWithNoExactRow() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", SPOUSE, null, null, 1, 3),
                grant("claims.claim", ALL_OTHER, null, null, 1));

        assertThat(evaluate(rules, SPOUSE, 40).permissions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "benefits", VIEW, "benefits.idCard", VIEW_DOWNLOAD,
                "claims", VIEW, "claims.claim", VIEW));
    }

    @Test
    void catchAllRowIsIgnoredForAKeyThatHasAnExactRow() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", ALL_OTHER, null, null, 1, 2, 3, 4),
                grant("claims.claim", SPOUSE, null, null, 1));

        // the exact row replaces the catch-all; its wider actions are not unioned in
        assertThat(evaluate(rules, SPOUSE, 40).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.claim", VIEW));
    }

    @Test
    void exactNoAccessRowSuppressesACatchAllGrant() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", ALL_OTHER, null, null, 1),
                noAccess("claims.claim", SPOUSE, null, null));

        assertThat(evaluate(rules, SPOUSE, 40).permissions()).isEmpty();
    }

    @Test
    void exactGrantOverridesACatchAllNoAccessRow() {
        List<PermissionRule> rules = List.of(
                noAccess("claims.claim", ALL_OTHER, null, null),
                grant("claims.claim", CHILD, 0, 12, 1, 3));

        assertThat(evaluate(rules, CHILD, 7).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.claim", VIEW_DOWNLOAD));
    }

    @Test
    void catchAllRowAppliesWhenTheKeysExactRowsAreOutsideTheAgeBand() {
        // rule 1 filters by age before rule 2 decides precedence: an exact row that does not apply to this
        // age leaves the key without an exact row, so the catch-all steps in
        List<PermissionRule> rules = List.of(
                grant("claims.claim", ALL_OTHER, null, null, 1),
                grant("claims.claim", CHILD, 0, 12, 1, 3));

        assertThat(evaluate(rules, CHILD, 7).permissions()).containsEntry("claims.claim", VIEW_DOWNLOAD);
        assertThat(evaluate(rules, CHILD, 15).permissions()).containsEntry("claims.claim", VIEW);
    }

    @Test
    void catchAllRowMustItselfMatchTheAgeBand() {
        List<PermissionRule> rules = List.of(grant("claims.claim", ALL_OTHER, 0, 12, 1));

        assertThat(evaluate(rules, SPOUSE, 40).permissions()).isEmpty();
        assertThat(evaluate(rules, SPOUSE, 10).permissions()).containsEntry("claims.claim", VIEW);
    }

    @Test
    void precedenceIsDecidedKeyByKey() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", ALL_OTHER, null, null, 1),
                grant("claims.referral", ALL_OTHER, null, null, 1),
                grant("claims.claim", SPOUSE, null, null, 1, 3));

        // claims.claim comes from the exact row, claims.referral from the catch-all
        assertThat(evaluate(rules, SPOUSE, 40).permissions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "claims", VIEW, "claims.claim", VIEW_DOWNLOAD, "claims.referral", VIEW));
    }

    @Test
    void viewingAllOtherFamilyMembersUsesTheCatchAllRowsAsItsOwn() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", ALL_OTHER, null, null, 1),
                noAccess("claims.referral", ALL_OTHER, null, null));

        assertThat(evaluate(rules, ALL_OTHER, 30).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.claim", VIEW));
    }

    @Test
    void viewingAllOtherFamilyMembersIgnoresRowsForNamedRelationships() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", SPOUSE, null, null, 1, 3),
                grant("benefits.coverage", CHILD, null, null, 1),
                grant("benefits.accumulator", SELF, null, null, 1),
                grant("benefits.activePolicy", SUBSCRIBER, null, null, 1),
                grant("benefits.spendingAccount", ADULT_DEPENDENT, null, null, 1),
                grant("claims.claim", ALL_OTHER, null, null, 1));

        assertThat(evaluate(rules, ALL_OTHER, 30).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.claim", VIEW));
    }

    @Test
    void rowsForOtherViewingRelationshipsAreIgnored() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", SPOUSE, null, null, 1, 3),
                grant("benefits.idCard", CHILD, 0, 12, 1));

        assertThat(evaluate(rules, SPOUSE, 40).permissions()).containsEntry("benefits.idCard", VIEW_DOWNLOAD);
        assertThat(evaluate(rules, CHILD, 7).permissions()).containsEntry("benefits.idCard", VIEW);
        assertThat(evaluate(rules, SELF, 40).permissions()).isEmpty();
        assertThat(evaluate(rules, SUBSCRIBER, 40).permissions()).isEmpty();
        assertThat(evaluate(rules, ADULT_DEPENDENT, 40).permissions()).isEmpty();
    }

    @Test
    void selfPermissionsComeOnlyFromSelfRows() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", SELF, null, null, 1, 3),
                grant("benefits.coverage", SPOUSE, null, null, 1),
                noAccess("claims.claim", ALL_OTHER, null, null));

        assertThat(evaluate(rules, SELF, 42).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("benefits", VIEW, "benefits.idCard", VIEW_DOWNLOAD));
    }

    // =============================================================================== union, sort, de-duplicate

    @Test
    void actionCodesAreUnionedAcrossApplicableRowsForTheSameKey() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", CHILD, null, null, 1),
                grant("claims.claim", CHILD, 13, 17, 3));

        assertThat(evaluate(rules, CHILD, 15).permissions()).containsEntry("claims.claim", VIEW_DOWNLOAD);
        assertThat(evaluate(rules, CHILD, 7).permissions()).containsEntry("claims.claim", VIEW);
    }

    @Test
    void actionCodesAreSortedAscending() {
        List<PermissionRule> rules = List.of(grant("documents.letter", SELF, null, null, 4, 1, 3));

        assertThat(evaluate(rules, SELF, 42).permissions()).containsEntry("documents.letter", List.of(1, 3, 4));
    }

    @Test
    void actionCodesAreSortedAcrossRowsToo() {
        List<PermissionRule> rules = List.of(
                grant("documents.letter", SELF, null, null, 3),
                grant("documents.letter", SELF, 18, null, 1),
                grant("documents.letter", SELF, 40, 50, 2));

        assertThat(evaluate(rules, SELF, 42).permissions()).containsEntry("documents.letter", List.of(1, 2, 3));
    }

    @Test
    void duplicateActionCodesCollapse() {
        List<PermissionRule> rules = List.of(
                grant("documents.letter", SELF, null, null, 1, 3),
                grant("documents.letter", SELF, null, null, 3, 1),
                grant("documents.taxDocument", SELF, null, null, 1, 1, 1));

        assertThat(evaluate(rules, SELF, 42).permissions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "documents", VIEW, "documents.letter", VIEW_DOWNLOAD, "documents.taxDocument", VIEW));
    }

    @Test
    void unionIncludesAConsentRowOnceConsentIsOnFile() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", CHILD, 13, 17, 1),
                consent("claims.claim", CHILD, 13, 17, 3));

        PermissionResult without = evaluate(rules, CHILD, 15);
        assertThat(without.permissions()).containsEntry("claims.claim", VIEW);
        assertThat(without.consentRequired()).containsExactly("claims.claim");

        PermissionResult with = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));
        assertThat(with.permissions()).containsEntry("claims.claim", VIEW_DOWNLOAD);
        assertThat(with.consentRequired()).isEmpty();
    }

    // =============================================================================== 3. consent

    @Test
    void consentRequiredRowWithoutConsentListsTheKeyWithNoActions() {
        List<PermissionRule> rules = List.of(consent("claims.authorization", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15);

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.authorization");
        assertThat(result.masked()).isEmpty();
    }

    @Test
    void consentRequiredRowGrantsItsActionsWhenConsentIsOnFile() {
        List<PermissionRule> rules = List.of(consent("claims.authorization", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));

        assertThat(result.permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.authorization", VIEW));
        assertThat(result.consentRequired()).isEmpty();
    }

    @Test
    void consentForAnotherMemberDoesNotCount() {
        List<PermissionRule> rules = List.of(consent("claims.authorization", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15, consentFor("HP0000099", "claims"));

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.authorization");
    }

    @Test
    void consentForAnotherFamilyDoesNotCount() {
        List<PermissionRule> rules = List.of(consent("claims.authorization", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15, consentFor(VIEWED, "benefits"));

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.authorization");
    }

    @Test
    void revokedConsentIsSimplyAbsentFromTheSetAndBehavesLikeNoConsent() {
        // ConsentRepository filters revoked_at IS NULL, so a revoked consent never reaches the evaluator
        List<PermissionRule> rules = List.of(consent("claims.authorization", CHILD, 13, 17, 1));

        PermissionResult beforeRevocation = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));
        PermissionResult afterRevocation = evaluate(rules, CHILD, 15, NO_CONSENT);

        assertThat(beforeRevocation.permissions()).containsEntry("claims.authorization", VIEW);
        assertThat(afterRevocation.permissions()).isEmpty();
        assertThat(afterRevocation.consentRequired()).containsExactly("claims.authorization");
    }

    @Test
    void consentIsMatchedOnViewedMemberIdAndPermissionFamily() {
        List<PermissionRule> rules = List.of(consent("claims.claim", CHILD, 13, 17, 1));

        assertThat(ConsentRepository.consentKey("HP0000002", "claims")).isEqualTo("HP0000002|claims");
        assertThat(evaluate(rules, CHILD, 15, Set.of("HP0000002|claims")).permissions()).containsEntry("claims.claim", VIEW);
        assertThat(evaluate(rules, CHILD, 15, Set.of("HP0000002|claims.claim")).permissions()).isEmpty();
        assertThat(evaluate(rules, CHILD, 15, Set.of("hp0000002|claims")).permissions()).isEmpty();
    }

    @Test
    void oneConsentPerFamilyUnlocksEveryConsentRowOfThatFamily() {
        List<PermissionRule> rules = List.of(
                consent("claims.claim", CHILD, 13, 17, 1),
                consent("claims.authorization", CHILD, 13, 17, 1),
                consent("documents.letter", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "claims", VIEW, "claims.claim", VIEW, "claims.authorization", VIEW));
        assertThat(result.consentRequired()).containsExactly("documents.letter");
    }

    @Test
    void consentRequiredListIsSortedAndDistinct() {
        List<PermissionRule> rules = List.of(
                consent("claims.referral", CHILD, 13, 17, 1),
                consent("claims.claim", CHILD, null, null, 1),
                consent("claims.claim", CHILD, 13, 17, 3),
                consent("benefits.spendingAccount", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 15).consentRequired())
                .containsExactly("benefits.spendingAccount", "claims.claim", "claims.referral");
    }

    @Test
    void consentRequiredKeysDoNotProduceAParent() {
        List<PermissionRule> rules = List.of(
                consent("claims.claim", CHILD, 13, 17, 1),
                consent("claims.authorization", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15);

        assertThat(result.permissions()).doesNotContainKey("claims").isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.authorization", "claims.claim");
    }

    @Test
    void consentRowWithoutActionsGrantsViewOnceConsentIsOnFile() {
        List<PermissionRule> rules = List.of(consent("claims.claim", CHILD, 13, 17));

        PermissionResult result = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));

        assertThat(result.permissions()).containsExactly(Map.entry("claims", List.of(1)), Map.entry("claims.claim", List.of(1)));
        assertThat(result.consentRequired()).isEmpty();
    }

    @Test
    void consentRowOutsideTheAgeBandIsNotListed() {
        List<PermissionRule> rules = List.of(consent("claims.claim", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 7).consentRequired()).isEmpty();
        assertThat(evaluate(rules, CHILD, 18).consentRequired()).isEmpty();
    }

    // =============================================================================== 4. masked

    @Test
    void maskedRowAddsItsKeyToMaskedWhenItGrants() {
        List<PermissionRule> rules = List.of(masked("claims.claim", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.claim", VIEW));
        assertThat(result.masked()).containsExactly("claims.claim");
        assertThat(result.consentRequired()).isEmpty();
    }

    @Test
    void maskedRowNeedingConsentIsReportedMaskedWhileConsentIsPending() {
        List<PermissionRule> rules = List.of(maskedConsent("claims.claim", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15);

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).containsExactly("claims.claim");
        assertThat(result.masked()).containsExactly("claims.claim");
    }

    @Test
    void maskedRowNeedingConsentIsMaskedOnceConsentIsOnFile() {
        List<PermissionRule> rules = List.of(maskedConsent("claims.claim", CHILD, 13, 17, 1));

        PermissionResult result = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(Map.of("claims", VIEW, "claims.claim", VIEW));
        assertThat(result.masked()).containsExactly("claims.claim");
        assertThat(result.consentRequired()).isEmpty();
    }

    @Test
    void maskedRowWithNoActionsMasksNothing() {
        List<PermissionRule> rules = List.of(masked("claims.claim", CHILD, 13, 17));

        PermissionResult result = evaluate(rules, CHILD, 15);

        assertThat(result.permissions()).isEmpty();
        assertThat(result.masked()).isEmpty();
    }

    @Test
    void maskedRowOutsideTheAgeBandMasksNothing() {
        List<PermissionRule> rules = List.of(masked("claims.claim", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 7).masked()).isEmpty();
    }

    @Test
    void maskedListIsSortedAndDistinct() {
        List<PermissionRule> rules = List.of(
                masked("claims.referral", CHILD, 13, 17, 1),
                masked("claims.claim", CHILD, null, null, 1),
                masked("claims.claim", CHILD, 13, 17, 3),
                masked("benefits.spendingAccount", CHILD, 13, 17, 1));

        assertThat(evaluate(rules, CHILD, 15).masked())
                .containsExactly("benefits.spendingAccount", "claims.claim", "claims.referral");
    }

    @Test
    void anUnmaskedRowForTheSameKeyDoesNotLiftTheMask() {
        List<PermissionRule> rules = List.of(
                masked("claims.claim", CHILD, 13, 17, 1),
                grant("claims.claim", CHILD, 13, 17, 3));

        PermissionResult result = evaluate(rules, CHILD, 15);

        assertThat(result.permissions()).containsEntry("claims.claim", VIEW_DOWNLOAD);
        assertThat(result.masked()).containsExactly("claims.claim");
    }

    // =============================================================================== 5. parent keys

    @Test
    void parentRowsInTheInputAreIgnoredEvenWhenTheyCarryActions() {
        List<PermissionRule> rules = List.of(
                parent("benefits", SELF, 1, 2, 3, 4),
                parent("claims", SELF, 1));

        assertThat(evaluate(rules, SELF, 42).permissions()).isEmpty();
    }

    @Test
    void parentIsDerivedAsViewWhenAnyChildHasAnAction() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", SELF, null, null, 1, 3));

        assertThat(evaluate(rules, SELF, 42).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("benefits", VIEW, "benefits.idCard", VIEW_DOWNLOAD));
    }

    @Test
    void parentIsNotDerivedWhenNoChildHasAnAction() {
        List<PermissionRule> rules = List.of(
                parent("profile", CHILD, 1),
                noAccess("profile.raceEthnicityLanguage", CHILD, 13, 17),
                noAccess("profile.sexualOrientationGenderIdentity", CHILD, 13, 17));

        assertThat(evaluate(rules, CHILD, 15).permissions()).isEmpty();
    }

    @Test
    void parentIsViewOnlyEvenWhenChildrenHaveEveryAction() {
        List<PermissionRule> rules = List.of(grant("documents.letter", SELF, null, null, 1, 2, 3, 4));

        assertThat(evaluate(rules, SELF, 42).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("documents", VIEW, "documents.letter", List.of(1, 2, 3, 4)));
    }

    @Test
    void parentRowActionsNeverLeakIntoTheDerivedParent() {
        List<PermissionRule> rules = List.of(
                parent("benefits", SELF, 1, 2),
                grant("benefits.coverage", SELF, null, null, 1));

        assertThat(evaluate(rules, SELF, 42).permissions()).containsEntry("benefits", VIEW);
    }

    @Test
    void aNoAccessParentRowDoesNotSuppressTheDerivedParent() {
        List<PermissionRule> rules = List.of(
                parent("profile", SELF),
                grant("profile.raceEthnicityLanguage", SELF, null, null, 1, 2));

        assertThat(evaluate(rules, SELF, 42).permissions())
                .containsExactlyInAnyOrderEntriesOf(Map.of("profile", VIEW, "profile.raceEthnicityLanguage", List.of(1, 2)));
    }

    @Test
    void oneParentPerFamilyThatHasAGrantedChild() {
        List<PermissionRule> rules = List.of(
                grant("benefits.coverage", SELF, null, null, 1),
                grant("benefits.idCard", SELF, null, null, 1, 3),
                grant("claims.claim", SELF, null, null, 1, 3),
                noAccess("documents.letter", SELF, null, null),
                parent("forms", SELF, 1));

        assertThat(evaluate(rules, SELF, 42).permissions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "benefits", VIEW, "benefits.coverage", VIEW, "benefits.idCard", VIEW_DOWNLOAD,
                "claims", VIEW, "claims.claim", VIEW_DOWNLOAD));
    }

    // =============================================================================== empty input

    @Test
    void emptyRuleListYieldsAnEmptyResult() {
        PermissionResult result = evaluate(List.of(), SELF, 42);

        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).isEmpty();
        assertThat(result.masked()).isEmpty();
        assertThat(result.trace()).isNull();
    }

    @Test
    void emptyRuleListWithExplainYieldsAnEmptyTrace() {
        PermissionResult result = explain(List.of(), CHILD, 7, NO_CONSENT);

        assertThat(result.permissions()).isEmpty();
        assertThat(result.trace()).isNotNull().isEmpty();
    }

    @Test
    void rulesForOtherViewingRelationshipsOnlyYieldAnEmptyResult() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", SPOUSE, null, null, 1, 3));

        PermissionResult result = explain(rules, CHILD, 7, NO_CONSENT);

        assertThat(result.permissions()).isEmpty();
        assertThat(result.trace()).isEmpty();
    }

    // =============================================================================== explain trace

    @Test
    void traceIsNullWhenExplainIsNotRequested() {
        List<PermissionRule> rules = List.of(grant("benefits.idCard", SELF, null, null, 1, 3));

        assertThat(evaluate(rules, SELF, 42).trace()).isNull();
    }

    @Test
    void traceReportsEverySelectedRowWithItsOutcome() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", SELF, null, null, 1, 3),          // id 1
                masked("demographic.memberSelf", SELF, null, null, 1),  // id 2
                consent("claims.authorization", SELF, 18, null, 1),     // id 3
                noAccess("claims.referral", SELF, null, null));         // id 4

        PermissionResult result = explain(rules, SELF, 42, NO_CONSENT);

        assertThat(result.trace()).containsExactlyInAnyOrder(
                new RuleTrace(1L, "claims.claim", "Self", null, null, "FULL_ACCESS", "granted [1, 3]"),
                new RuleTrace(2L, "demographic.memberSelf", "Self", null, null, "MASKED_ACCESS", "granted [1], masked"),
                new RuleTrace(3L, "claims.authorization", "Self", 18, null, "CONSENT_REQUIRED", "consent required, not on file"),
                new RuleTrace(4L, "claims.referral", "Self", null, null, "NO_ACCESS", "no actions"));
        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "claims", VIEW, "claims.claim", VIEW_DOWNLOAD,
                "demographic", VIEW, "demographic.memberSelf", VIEW));
        assertThat(result.consentRequired()).containsExactly("claims.authorization");
        assertThat(result.masked()).containsExactly("demographic.memberSelf");
    }

    @Test
    void traceShowsAMaskedConsentRowAsGrantedAndMaskedOnceConsentIsOnFile() {
        List<PermissionRule> rules = List.of(maskedConsent("claims.claim", CHILD, 13, 17, 1));

        PermissionResult result = explain(rules, CHILD, 15, consentFor(VIEWED, "claims"));

        assertThat(result.trace()).containsExactly(
                new RuleTrace(1L, "claims.claim", "Child", 13, 17, "MASKED_ACCESS", "granted [1], masked"));
    }

    @Test
    void traceExcludesCatchAllRowsReplacedByExactRows() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", ALL_OTHER, null, null, 1, 2, 3, 4),  // id 1, replaced
                grant("claims.claim", SPOUSE, null, null, 1));             // id 2

        PermissionResult result = explain(rules, SPOUSE, 40, NO_CONSENT);

        assertThat(result.trace()).extracting(RuleTrace::ruleId).containsExactly(2L);
    }

    @Test
    void traceIncludesTheCatchAllRowWhenItIsUsed() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", SPOUSE, null, null, 1, 3),  // id 1
                noAccess("claims.claim", ALL_OTHER, null, null));    // id 2

        PermissionResult result = explain(rules, SPOUSE, 40, NO_CONSENT);

        assertThat(result.trace()).containsExactlyInAnyOrder(
                new RuleTrace(1L, "benefits.idCard", "Spouse", null, null, "FULL_ACCESS", "granted [1, 3]"),
                new RuleTrace(2L, "claims.claim", "All other family members", null, null, "NO_ACCESS", "no actions"));
    }

    @Test
    void traceExcludesRowsOutsideTheAgeBand() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", CHILD, 0, 12, 1, 3),  // id 1
                grant("benefits.idCard", CHILD, 13, 17, 1));   // id 2

        assertThat(explain(rules, CHILD, 7, NO_CONSENT).trace()).extracting(RuleTrace::ruleId).containsExactly(1L);
        assertThat(explain(rules, CHILD, 15, NO_CONSENT).trace()).extracting(RuleTrace::ruleId).containsExactly(2L);
        assertThat(explain(rules, CHILD, 18, NO_CONSENT).trace()).isEmpty();
    }

    @Test
    void traceExcludesParentRows() {
        List<PermissionRule> rules = List.of(
                parent("benefits", SELF, 1),                          // id 1
                grant("benefits.idCard", SELF, null, null, 1, 3));    // id 2

        assertThat(explain(rules, SELF, 42, NO_CONSENT).trace()).extracting(RuleTrace::ruleId).containsExactly(2L);
    }

    @Test
    void traceCarriesTheRowsOwnAgeBandAndStatus() {
        List<PermissionRule> rules = List.of(grant("benefits.coverage", ADULT_DEPENDENT, 18, null, 1));

        RuleTrace trace = explain(rules, ADULT_DEPENDENT, 30, NO_CONSENT).trace().get(0);

        assertThat(trace.ruleId()).isEqualTo(1L);
        assertThat(trace.permissionKey()).isEqualTo("benefits.coverage");
        assertThat(trace.viewingRelationship()).isEqualTo("Adult dependent (any relationship)");
        assertThat(trace.minimumAge()).isEqualTo(18);
        assertThat(trace.maximumAge()).isNull();
        assertThat(trace.accessStatus()).isEqualTo("FULL_ACCESS");
        assertThat(trace.outcome()).isEqualTo("granted [1]");
    }

    @Test
    void explainDoesNotChangeTheDecision() {
        List<PermissionRule> rules = List.of(
                grant("benefits.idCard", CHILD, 13, 17, 1, 3),
                maskedConsent("claims.claim", CHILD, 13, 17, 1),
                consent("claims.authorization", CHILD, 13, 17, 1),
                noAccess("claims.referral", ALL_OTHER, null, null));

        PermissionResult plain = evaluate(rules, CHILD, 15);
        PermissionResult explained = explain(rules, CHILD, 15, NO_CONSENT);

        assertThat(explained.permissions()).isEqualTo(plain.permissions());
        assertThat(explained.consentRequired()).isEqualTo(plain.consentRequired());
        assertThat(explained.masked()).isEqualTo(plain.masked());
        assertThat(explained.trace()).hasSize(4);
    }

    // =============================================================================== index reuse and shape

    @Test
    void oneIndexServesEveryViewedMemberOfTheFamily() {
        PermissionEvaluator.Index index = evaluator.index(List.of(
                grant("benefits.idCard", SELF, null, null, 1, 3),
                grant("benefits.idCard", SPOUSE, null, null, 1, 3),
                grant("benefits.idCard", CHILD, 0, 12, 1, 3),
                grant("benefits.idCard", CHILD, 13, 17, 1),
                grant("benefits.idCard", ADULT_DEPENDENT, 18, null, 1),
                grant("claims.claim", SELF, null, null, 1, 3),
                noAccess("claims.claim", ALL_OTHER, null, null)));

        assertThat(index.evaluate(SELF, 42, "HP01", NO_CONSENT, false).permissions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("benefits", VIEW, "benefits.idCard", VIEW_DOWNLOAD, "claims", VIEW, "claims.claim", VIEW_DOWNLOAD));
        assertThat(index.evaluate(SPOUSE, 40, "HP02", NO_CONSENT, false).permissions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("benefits", VIEW, "benefits.idCard", VIEW_DOWNLOAD));
        assertThat(index.evaluate(CHILD, 7, "HP03", NO_CONSENT, false).permissions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("benefits", VIEW, "benefits.idCard", VIEW_DOWNLOAD));
        assertThat(index.evaluate(CHILD, 15, "HP04", NO_CONSENT, false).permissions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("benefits", VIEW, "benefits.idCard", VIEW));
        assertThat(index.evaluate(ADULT_DEPENDENT, 19, "HP05", NO_CONSENT, false).permissions()).containsExactlyInAnyOrderEntriesOf(
                Map.of("benefits", VIEW, "benefits.idCard", VIEW));
        assertThat(index.evaluate(ALL_OTHER, 30, "HP06", NO_CONSENT, false).permissions()).isEmpty();
        assertThat(index.evaluate(SUBSCRIBER, 50, "HP07", NO_CONSENT, false).permissions()).isEmpty();
    }

    @Test
    void consentIsCheckedPerViewedMemberOnTheSameIndex() {
        PermissionEvaluator.Index index = evaluator.index(List.of(consent("claims.claim", CHILD, 13, 17, 1)));
        Set<String> consents = consentFor("HP0000002", "claims");

        PermissionResult consented = index.evaluate(CHILD, 15, "HP0000002", consents, false);
        PermissionResult notConsented = index.evaluate(CHILD, 16, "HP0000003", consents, false);

        assertThat(consented.permissions()).containsEntry("claims.claim", VIEW);
        assertThat(consented.consentRequired()).isEmpty();
        assertThat(notConsented.permissions()).isEmpty();
        assertThat(notConsented.consentRequired()).containsExactly("claims.claim");
    }

    @Test
    void evaluationDoesNotMutateTheIndex() {
        PermissionEvaluator.Index index = evaluator.index(List.of(
                grant("benefits.idCard", CHILD, 0, 12, 1, 3),
                maskedConsent("claims.claim", CHILD, 13, 17, 1),
                noAccess("claims.claim", ALL_OTHER, null, null)));

        PermissionResult first = index.evaluate(CHILD, 15, VIEWED, NO_CONSENT, true);
        index.evaluate(CHILD, 7, VIEWED, NO_CONSENT, false);
        index.evaluate(SPOUSE, 40, VIEWED, consentFor(VIEWED, "claims"), true);
        PermissionResult again = index.evaluate(CHILD, 15, VIEWED, NO_CONSENT, true);

        assertThat(again).isEqualTo(first);
    }

    @Test
    void permissionsAreOrderedByKey() {
        List<PermissionRule> rules = List.of(
                grant("claims.claim", SELF, null, null, 1),
                grant("benefits.idCard", SELF, null, null, 1),
                grant("benefits.coverage", SELF, null, null, 1),
                grant("profile.raceEthnicityLanguage", SELF, null, null, 1));

        assertThat(evaluate(rules, SELF, 42).permissions().keySet()).containsExactly(
                "benefits", "benefits.coverage", "benefits.idCard", "claims", "claims.claim",
                "profile", "profile.raceEthnicityLanguage");
    }

    @Test
    void theIndexIgnoresTheActorRelationshipOfARow() {
        // filtering by actor is the repository's job; a row that reached the evaluator is the actor's
        PermissionRule spouseActorRow = new PermissionRule(1L, "benefits", "benefits.idCard", ActorRelationship.SPOUSE,
                SELF, null, null, VIEW_DOWNLOAD, "FULL_ACCESS", false, false);

        assertThat(evaluate(List.of(spouseActorRow), SELF, 42).permissions()).containsEntry("benefits.idCard", VIEW_DOWNLOAD);
    }

    // ------------------------------------------------------------------------------- catch-all and access_status

    @Test
    void aGrantingCatchAllRowNeverAppliesToSelf() {
        List<PermissionRule> rules = List.of(row("claims.claim", ALL_OTHER, null, null, List.of(1, 3), "FULL_ACCESS", false, false));

        assertThat(evaluate(rules, SELF, 42).permissions()).isEmpty();
        assertThat(explain(rules, SELF, 42, NO_CONSENT).trace()).isEmpty();
        assertThat(evaluate(rules, CHILD, 7).permissions()).containsEntry("claims.claim", List.of(1, 3));
    }

    @Test
    void notApplicableAndReviewRequiredRowsNeverGrantEvenWhenActiveWithActions() {
        List<PermissionRule> rules = List.of(
                row("claims.claim", CHILD, null, null, List.of(1, 3), "NOT_APPLICABLE", false, false),
                row("claims.referral", CHILD, null, null, List.of(1), "REVIEW_REQUIRED", false, false),
                row("claims.authorization", CHILD, null, null, List.of(1), "FULL_ACCESS", false, false));

        PermissionResult result = explain(rules, CHILD, 7, NO_CONSENT);

        assertThat(result.permissions()).containsExactly(Map.entry("claims", List.of(1)), Map.entry("claims.authorization", List.of(1)));
        assertThat(result.trace()).extracting(PermissionResult.RuleTrace::permissionKey).containsExactly("claims.authorization");
    }

    @Test
    void administrativeConsentBehavesLikeConsentRequiredAndRevocableGrantsLikeFullAccess() {
        List<PermissionRule> rules = List.of(
                row("claims.claim", CHILD, 13, 17, List.of(1), "CONSENT_REQUIRED_ADMIN", true, false),
                row("claims.referral", CHILD, 13, 17, List.of(1), "REVOCABLE_ACCESS", false, false));

        PermissionResult pending = evaluate(rules, CHILD, 15);
        assertThat(pending.permissions()).containsExactly(Map.entry("claims", List.of(1)), Map.entry("claims.referral", List.of(1)));
        assertThat(pending.consentRequired()).containsExactly("claims.claim");

        PermissionResult granted = evaluate(rules, CHILD, 15, consentFor(VIEWED, "claims"));
        assertThat(granted.permissions()).containsEntry("claims.claim", List.of(1)).containsEntry("claims.referral", List.of(1));
        assertThat(granted.consentRequired()).isEmpty();
    }
}
