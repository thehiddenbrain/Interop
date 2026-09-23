package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.permission.PermissionResult.RuleTrace;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.point32health.memberprofile.permission.ViewingRelationship.ADULT_DEPENDENT;
import static org.point32health.memberprofile.permission.ViewingRelationship.ALL_OTHER;
import static org.point32health.memberprofile.permission.ViewingRelationship.CHILD;
import static org.point32health.memberprofile.permission.ViewingRelationship.SELF;
import static org.point32health.memberprofile.permission.ViewingRelationship.SPOUSE;
import static org.point32health.memberprofile.permission.ViewingRelationship.SUBSCRIBER;

/**
 * Replays {@code V4__seed_family_permission_rules.sql} through {@link PermissionEvaluator} without a database.
 * {@link #SEED} is a faithful copy of the migration: one {@link PermissionRule} per active row, ids equal to
 * the identity values the INSERT produces (position in the VALUES list, 1-based), the ten inactive
 * NOT_APPLICABLE rows omitted because {@link PermissionRuleRepository} never loads them, the active
 * "Derived from child permissions" parent rows kept because the repository does load them and the evaluator
 * must ignore them. {@link #activeRulesFor} mirrors the repository query (actor plus the wanted viewing
 * relationships, catch-all always included).
 * <p>
 * Every seeded scenario of catalog section 4 is asserted with an exact permission map, the consentRequired
 * and masked lists, and the presence of a parent key only when one of its children has an action.
 */
class SeededFamilyPermissionRulesTest {

    private static final String FULL_ACCESS = "FULL_ACCESS";
    private static final String NO_ACCESS = "NO_ACCESS";
    private static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";
    private static final String MASKED_ACCESS = "MASKED_ACCESS";

    private static final List<Integer> VIEW = List.of(1);
    private static final List<Integer> VIEW_EDIT = List.of(1, 2);
    private static final List<Integer> VIEW_DOWNLOAD = List.of(1, 3);

    private static final ActorRelationship SUBSCRIBER_ACTOR = ActorRelationship.SUBSCRIBER;
    private static final ActorRelationship SPOUSE_ACTOR = ActorRelationship.SPOUSE;
    private static final ActorRelationship EX_SPOUSE_ACTOR = ActorRelationship.EX_SPOUSE;
    private static final ActorRelationship ADULT_CHILD_ACTOR = ActorRelationship.ADULT_CHILD;
    private static final ActorRelationship TEENAGER_ACTOR = ActorRelationship.CHILD_TEENAGER;
    private static final ActorRelationship MINOR_ACTOR = ActorRelationship.CHILD_MINOR;

    private static final String ACTOR_ID = "HP0000001";
    private static final String VIEWED_ID = "HP0000002";
    private static final String OTHER_CHILD_ID = "HP0000003";
    private static final Set<String> NO_CONSENT = Set.of();
    private static final Set<String> CLAIMS_CONSENT_FOR_VIEWED = Set.of(ConsentRepository.consentKey(VIEWED_ID, "claims"));

    private static final String REL = "profile.raceEthnicityLanguage";
    private static final String SOGI = "profile.sexualOrientationGenderIdentity";

    private static final List<String> PARENT_KEYS = List.of("benefits", "claims", "demographic", "documents", "forms", "profile");
    private static final Set<String> CATALOG_KEYS = Set.of(
            "benefits", "benefits.accumulator", "benefits.activePolicy", "benefits.coverage", "benefits.idCard", "benefits.spendingAccount",
            "claims", "claims.authorization", "claims.claim", "claims.referral",
            "demographic", "demographic.contract", "demographic.memberOther", "demographic.memberSelf",
            "documents", "documents.letter", "documents.planDocument", "documents.taxDocument",
            "forms", "forms.capeCodHealthcare", "forms.coordinationOfBenefits", "forms.designationOfRepresentative", "forms.medicalReimbursement",
            "profile", "profile.raceEthnicityLanguage", "profile.sexualOrientationGenderIdentity");
    /** Identity values of the NOT_APPLICABLE rows in V4, which are inactive and therefore not in {@link #SEED}. */
    private static final Set<Long> INACTIVE_IDS = Set.of(33L, 36L, 45L, 48L, 54L, 57L, 60L, 61L, 62L, 63L);

    // ------------------------------------------------------------------------------- the V4 rows

    private static PermissionRule row(long id, String key, ActorRelationship actor, ViewingRelationship viewing,
                                      Integer minimumAge, Integer maximumAge, List<Integer> codes, String status,
                                      boolean consentRequired, boolean maskedData) {
        return new PermissionRule(id, PermissionRule.familyOf(key), key, actor, viewing, minimumAge, maximumAge,
                codes, status, consentRequired, maskedData);
    }

    private static PermissionRule full(long id, String key, ActorRelationship actor, ViewingRelationship viewing,
                                       Integer minimumAge, Integer maximumAge, List<Integer> codes) {
        return row(id, key, actor, viewing, minimumAge, maximumAge, codes, FULL_ACCESS, false, false);
    }

    private static PermissionRule none(long id, String key, ActorRelationship actor, ViewingRelationship viewing,
                                       Integer minimumAge, Integer maximumAge) {
        return row(id, key, actor, viewing, minimumAge, maximumAge, List.of(), NO_ACCESS, false, false);
    }

    /** Every active row of V4__seed_family_permission_rules.sql, in file order, with its identity value. */
    static final List<PermissionRule> SEED = List.of(
            // ---- Benefits sheet row 2: Subscriber viewing Self, Any age ----
            full(1, "benefits", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),                  // parent row, derived from children
            full(2, "benefits.coverage", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),
            full(3, "benefits.idCard", SUBSCRIBER_ACTOR, SELF, null, null, VIEW_DOWNLOAD),
            full(4, "benefits.accumulator", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),
            full(5, "benefits.spendingAccount", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),
            full(6, "benefits.activePolicy", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),
            // ---- Benefits row 3: Subscriber viewing Spouse, Any age ----
            full(7, "benefits.coverage", SUBSCRIBER_ACTOR, SPOUSE, null, null, VIEW),
            full(8, "benefits.idCard", SUBSCRIBER_ACTOR, SPOUSE, null, null, VIEW_DOWNLOAD),
            full(9, "benefits.accumulator", SUBSCRIBER_ACTOR, SPOUSE, null, null, VIEW),
            full(10, "benefits.spendingAccount", SUBSCRIBER_ACTOR, SPOUSE, null, null, VIEW),
            full(11, "benefits.activePolicy", SUBSCRIBER_ACTOR, SPOUSE, null, null, VIEW),
            // ---- Benefits row 4: Subscriber viewing Child 0-12 ----
            full(12, "benefits.coverage", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW),
            full(13, "benefits.idCard", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW_DOWNLOAD),
            full(14, "benefits.accumulator", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW),
            full(15, "benefits.spendingAccount", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW),
            full(16, "benefits.activePolicy", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW),
            // ---- Benefits row 5: Subscriber viewing Child 13-17 (no spendingAccount row) ----
            full(17, "benefits.coverage", SUBSCRIBER_ACTOR, CHILD, 13, 17, VIEW),
            full(18, "benefits.idCard", SUBSCRIBER_ACTOR, CHILD, 13, 17, VIEW_DOWNLOAD),
            full(19, "benefits.accumulator", SUBSCRIBER_ACTOR, CHILD, 13, 17, VIEW),
            full(20, "benefits.activePolicy", SUBSCRIBER_ACTOR, CHILD, 13, 17, VIEW),
            // ---- Benefits row 6: Subscriber viewing Adult dependent, 18 or older ----
            full(21, "benefits.coverage", SUBSCRIBER_ACTOR, ADULT_DEPENDENT, 18, null, VIEW),
            full(22, "benefits.idCard", SUBSCRIBER_ACTOR, ADULT_DEPENDENT, 18, null, VIEW),
            // ---- Claims_RefAuth rows 2, 4, 5, 6: Subscriber ----
            full(23, "claims.claim", SUBSCRIBER_ACTOR, SELF, null, null, VIEW_DOWNLOAD),
            full(24, "claims.authorization", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),
            full(25, "claims.referral", SUBSCRIBER_ACTOR, SELF, null, null, VIEW),
            full(26, "claims.claim", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW_DOWNLOAD),
            full(27, "claims.authorization", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW),
            full(28, "claims.referral", SUBSCRIBER_ACTOR, CHILD, 0, 12, VIEW),
            row(29, "claims.claim", SUBSCRIBER_ACTOR, CHILD, 13, 17, VIEW, MASKED_ACCESS, true, true),
            row(30, "claims.authorization", SUBSCRIBER_ACTOR, CHILD, 13, 17, VIEW, CONSENT_REQUIRED, true, false),
            none(31, "claims.claim", SUBSCRIBER_ACTOR, ALL_OTHER, null, null),
            // ---- REL_SOGI row 9: Spouse viewing Child 0-11 (id 33, SOGI, is NOT_APPLICABLE and inactive) ----
            full(32, REL, SPOUSE_ACTOR, CHILD, 0, 11, VIEW_EDIT),
            // ---- REL_SOGI row 10: Spouse viewing Child 13-17 (id 36, SOGI, inactive) ----
            none(34, "profile", SPOUSE_ACTOR, CHILD, 13, 17),                                // parent row
            none(35, REL, SPOUSE_ACTOR, CHILD, 13, 17),
            // ---- REL_SOGI row 11: Ex-Spouse viewing Self, Any age ----
            full(37, "profile", EX_SPOUSE_ACTOR, SELF, null, null, VIEW),                    // parent row
            full(38, REL, EX_SPOUSE_ACTOR, SELF, null, null, VIEW_EDIT),
            full(39, SOGI, EX_SPOUSE_ACTOR, SELF, null, null, VIEW_EDIT),
            // ---- REL_SOGI row 12: Ex-Spouse viewing Subscriber, Any age ----
            none(40, "profile", EX_SPOUSE_ACTOR, SUBSCRIBER, null, null),                    // parent row
            none(41, REL, EX_SPOUSE_ACTOR, SUBSCRIBER, null, null),
            none(42, SOGI, EX_SPOUSE_ACTOR, SUBSCRIBER, null, null),
            // ---- REL_SOGI row 13: Ex-Spouse viewing Child 13-17 (id 45, SOGI, inactive) ----
            none(43, "profile", EX_SPOUSE_ACTOR, CHILD, 13, 17),                             // parent row
            none(44, REL, EX_SPOUSE_ACTOR, CHILD, 13, 17),
            // ---- REL_SOGI row 14: Ex-Spouse viewing Child 0-12 (id 48, SOGI, inactive) ----
            none(46, "profile", EX_SPOUSE_ACTOR, CHILD, 0, 12),                              // parent row
            none(47, REL, EX_SPOUSE_ACTOR, CHILD, 0, 12),
            // ---- REL_SOGI row 15: Adult Child viewing Self, 18 or older ----
            full(49, "profile", ADULT_CHILD_ACTOR, SELF, 18, null, VIEW),                    // parent row
            full(50, REL, ADULT_CHILD_ACTOR, SELF, 18, null, VIEW_EDIT),
            full(51, SOGI, ADULT_CHILD_ACTOR, SELF, 18, null, VIEW_EDIT),
            // ---- REL_SOGI row 16: Adult Child viewing All other family members (id 54, SOGI, inactive) ----
            none(52, "profile", ADULT_CHILD_ACTOR, ALL_OTHER, null, null),                   // parent row
            none(53, REL, ADULT_CHILD_ACTOR, ALL_OTHER, null, null),
            // ---- REL_SOGI row 17: Child (teenager) viewing Self 13-17 (id 57, SOGI, inactive) ----
            full(55, "profile", TEENAGER_ACTOR, SELF, 13, 17, VIEW),                         // parent row
            full(56, REL, TEENAGER_ACTOR, SELF, 13, 17, VIEW_EDIT),
            // ---- REL_SOGI row 18: Child (teenager) viewing All other family members (id 60, SOGI, inactive) ----
            none(58, "profile", TEENAGER_ACTOR, ALL_OTHER, null, null),                      // parent row
            none(59, REL, TEENAGER_ACTOR, ALL_OTHER, null, null)
            // ---- REL_SOGI row 19: Child (minor) viewing Self 0-12: ids 61-63 are all NOT_APPLICABLE, inactive ----
    );

    // ------------------------------------------------------------------------------- expected maps

    private static final Map<String, List<Integer>> SUBSCRIBER_SELF = Map.ofEntries(
            Map.entry("benefits", VIEW),
            Map.entry("benefits.accumulator", VIEW),
            Map.entry("benefits.activePolicy", VIEW),
            Map.entry("benefits.coverage", VIEW),
            Map.entry("benefits.idCard", VIEW_DOWNLOAD),
            Map.entry("benefits.spendingAccount", VIEW),
            Map.entry("claims", VIEW),
            Map.entry("claims.authorization", VIEW),
            Map.entry("claims.claim", VIEW_DOWNLOAD),
            Map.entry("claims.referral", VIEW));

    private static final Map<String, List<Integer>> SUBSCRIBER_VIEWING_SPOUSE = Map.ofEntries(
            Map.entry("benefits", VIEW),
            Map.entry("benefits.accumulator", VIEW),
            Map.entry("benefits.activePolicy", VIEW),
            Map.entry("benefits.coverage", VIEW),
            Map.entry("benefits.idCard", VIEW_DOWNLOAD),
            Map.entry("benefits.spendingAccount", VIEW));

    private static final Map<String, List<Integer>> SUBSCRIBER_VIEWING_CHILD_MINOR = Map.ofEntries(
            Map.entry("benefits", VIEW),
            Map.entry("benefits.accumulator", VIEW),
            Map.entry("benefits.activePolicy", VIEW),
            Map.entry("benefits.coverage", VIEW),
            Map.entry("benefits.idCard", VIEW_DOWNLOAD),
            Map.entry("benefits.spendingAccount", VIEW),
            Map.entry("claims", VIEW),
            Map.entry("claims.authorization", VIEW),
            Map.entry("claims.claim", VIEW_DOWNLOAD),
            Map.entry("claims.referral", VIEW));

    private static final Map<String, List<Integer>> SUBSCRIBER_VIEWING_TEEN_WITHOUT_CONSENT = Map.ofEntries(
            Map.entry("benefits", VIEW),
            Map.entry("benefits.accumulator", VIEW),
            Map.entry("benefits.activePolicy", VIEW),
            Map.entry("benefits.coverage", VIEW),
            Map.entry("benefits.idCard", VIEW_DOWNLOAD));

    private static final Map<String, List<Integer>> SUBSCRIBER_VIEWING_TEEN_WITH_CONSENT = Map.ofEntries(
            Map.entry("benefits", VIEW),
            Map.entry("benefits.accumulator", VIEW),
            Map.entry("benefits.activePolicy", VIEW),
            Map.entry("benefits.coverage", VIEW),
            Map.entry("benefits.idCard", VIEW_DOWNLOAD),
            Map.entry("claims", VIEW),
            Map.entry("claims.authorization", VIEW),
            Map.entry("claims.claim", VIEW));

    private static final Map<String, List<Integer>> SUBSCRIBER_VIEWING_ADULT_DEPENDENT = Map.ofEntries(
            Map.entry("benefits", VIEW),
            Map.entry("benefits.coverage", VIEW),
            Map.entry("benefits.idCard", VIEW));

    private static final Map<String, List<Integer>> PROFILE_FULL = Map.ofEntries(
            Map.entry("profile", VIEW),
            Map.entry(REL, VIEW_EDIT),
            Map.entry(SOGI, VIEW_EDIT));

    private static final Map<String, List<Integer>> PROFILE_REL_ONLY = Map.ofEntries(
            Map.entry("profile", VIEW),
            Map.entry(REL, VIEW_EDIT));

    // ------------------------------------------------------------------------------- helpers

    private final PermissionEvaluator evaluator = new PermissionEvaluator();
    private final RelationshipResolver resolver = new RelationshipResolver();

    /** What PermissionRuleRepository.activeRulesFor returns for this actor and family: the catch-all is always included. */
    static List<PermissionRule> activeRulesFor(ActorRelationship actor, ViewingRelationship... viewings) {
        EnumSet<ViewingRelationship> wanted = EnumSet.of(ALL_OTHER);
        wanted.addAll(Arrays.asList(viewings));
        return SEED.stream().filter(r -> r.actor() == actor && wanted.contains(r.viewing())).toList();
    }

    private PermissionResult evaluate(ActorRelationship actor, ViewingRelationship viewing, int viewedAge) {
        return evaluate(actor, viewing, viewedAge, NO_CONSENT, false);
    }

    private PermissionResult evaluate(ActorRelationship actor, ViewingRelationship viewing, int viewedAge,
                                      Set<String> consents, boolean explain) {
        return evaluator.index(activeRulesFor(actor, viewing)).evaluate(viewing, viewedAge, VIEWED_ID, consents, explain);
    }

    private static void assertEmpty(PermissionResult result) {
        assertThat(result.permissions()).isEmpty();
        assertThat(result.consentRequired()).isEmpty();
        assertThat(result.masked()).isEmpty();
    }

    private static void assertNoConsentOrMasking(PermissionResult result) {
        assertThat(result.consentRequired()).isEmpty();
        assertThat(result.masked()).isEmpty();
    }

    // =============================================================================== the copy of the seed

    @Test
    void seedCarriesEveryActiveRowOfV4WithItsIdentityValue() {
        assertThat(SEED).hasSize(53);
        assertThat(SEED).extracting(PermissionRule::id).doesNotHaveDuplicates();
        Set<Long> ids = SEED.stream().map(PermissionRule::id).collect(Collectors.toSet());
        for (long id = 1; id <= 63; id++) {
            assertThat(ids.contains(id)).as("row %d", id).isEqualTo(!INACTIVE_IDS.contains(id));
        }
    }

    @Test
    void seedRowsSatisfyTheSchemaConstraints() {
        Set<String> statuses = Set.of(FULL_ACCESS, NO_ACCESS, CONSENT_REQUIRED, MASKED_ACCESS);
        for (PermissionRule rule : SEED) {
            assertThat(CATALOG_KEYS).as("row %d key", rule.id()).contains(rule.permissionKey());
            assertThat(rule.permissionFamily()).as("row %d family", rule.id()).isEqualTo(PermissionRule.familyOf(rule.permissionKey()));
            assertThat(statuses).as("row %d status", rule.id()).contains(rule.accessStatus());
            if (rule.minimumAge() != null && rule.maximumAge() != null) {
                assertThat(rule.minimumAge()).as("row %d band", rule.id()).isLessThanOrEqualTo(rule.maximumAge());
            }
            assertThat(rule.actionCodes()).as("row %d codes", rule.id()).isSorted().doesNotHaveDuplicates();
            assertThat(rule.actionCodes().isEmpty()).as("row %d NO_ACCESS has no codes", rule.id())
                    .isEqualTo(NO_ACCESS.equals(rule.accessStatus()));
        }
    }

    @Test
    void onlyTheTeenClaimsRowsNeedConsentAndOnlyTheClaimRowIsMasked() {
        assertThat(SEED.stream().filter(PermissionRule::consentRequired).map(PermissionRule::id)).containsExactly(29L, 30L);
        assertThat(SEED.stream().filter(PermissionRule::maskedData).map(PermissionRule::id)).containsExactly(29L);
    }

    @Test
    void repositoryFilterAlwaysAddsTheCatchAllAndNothingFromOtherActors() {
        List<PermissionRule> rules = activeRulesFor(SUBSCRIBER_ACTOR, SPOUSE);

        assertThat(rules).extracting(PermissionRule::id).containsExactly(7L, 8L, 9L, 10L, 11L, 31L);
        assertThat(rules).allMatch(r -> r.actor() == SUBSCRIBER_ACTOR);
        assertThat(activeRulesFor(MINOR_ACTOR, SELF)).isEmpty();
    }

    // =============================================================================== Subscriber (Benefits, Claims_RefAuth)

    @ParameterizedTest(name = "self aged {0}")
    @ValueSource(ints = {0, 18, 42, 99})
    void subscriberViewingSelfAtAnyAge(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, SELF, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_SELF);
        assertNoConsentOrMasking(result);
    }

    @Test
    void subscriberViewingSelfMatchesTheApiResponseExample() {
        Map<String, List<Integer>> permissions = evaluate(SUBSCRIBER_ACTOR, SELF, 42).permissions();

        assertThat(permissions).containsEntry("benefits", VIEW).containsEntry("benefits.idCard", VIEW_DOWNLOAD);
        assertThat(permissions).containsEntry("claims", VIEW).containsEntry("claims.claim", VIEW_DOWNLOAD);
        assertThat(List.copyOf(permissions.keySet())).isSorted();
        assertThat(permissions).doesNotContainKeys("demographic", "documents", "forms", "profile");
    }

    @ParameterizedTest(name = "spouse aged {0}")
    @ValueSource(ints = {18, 40, 75})
    void subscriberViewingSpouse(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, SPOUSE, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_SPOUSE);
        assertThat(result.permissions()).doesNotContainKeys("claims", "claims.claim");   // catch-all NO_ACCESS
        assertNoConsentOrMasking(result);
    }

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {0, 7, 12})
    void subscriberViewingChildZeroToTwelve(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_CHILD_MINOR);
        assertNoConsentOrMasking(result);
    }

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {13, 15, 17})
    void subscriberViewingChildThirteenToSeventeenWithoutConsent(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITHOUT_CONSENT);
        assertThat(result.permissions()).doesNotContainKeys("benefits.spendingAccount", "claims", "claims.claim",
                "claims.authorization", "claims.referral");
        assertThat(result.consentRequired()).containsExactly("claims.authorization", "claims.claim");
        assertThat(result.masked()).containsExactly("claims.claim");           // masked is reported while consent is pending
    }

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {13, 15, 17})
    void subscriberViewingChildThirteenToSeventeenWithConsentForClaims(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, age, CLAIMS_CONSENT_FOR_VIEWED, false);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITH_CONSENT);
        assertThat(result.permissions()).doesNotContainKeys("benefits.spendingAccount", "claims.referral");
        assertThat(result.consentRequired()).isEmpty();
        assertThat(result.masked()).containsExactly("claims.claim");
    }

    @Test
    void subscriberViewingTeenConsentOnFileForAnotherChildDoesNotApply() {
        Set<String> otherChildsConsent = Set.of(ConsentRepository.consentKey(OTHER_CHILD_ID, "claims"));

        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, 15, otherChildsConsent, false);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITHOUT_CONSENT);
        assertThat(result.consentRequired()).containsExactly("claims.authorization", "claims.claim");
        assertThat(result.masked()).containsExactly("claims.claim");
    }

    @Test
    void subscriberViewingTeenConsentOnFileForAnotherFamilyDoesNotUnlockClaims() {
        Set<String> benefitsConsent = Set.of(ConsentRepository.consentKey(VIEWED_ID, "benefits"));

        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, 15, benefitsConsent, false);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITHOUT_CONSENT);
        assertThat(result.consentRequired()).containsExactly("claims.authorization", "claims.claim");
    }

    @Test
    void subscriberViewingTeenConsentIsPerViewedChild() {
        PermissionEvaluator.Index index = evaluator.index(activeRulesFor(SUBSCRIBER_ACTOR, CHILD));

        PermissionResult consented = index.evaluate(CHILD, 15, VIEWED_ID, CLAIMS_CONSENT_FOR_VIEWED, false);
        PermissionResult notConsented = index.evaluate(CHILD, 16, OTHER_CHILD_ID, CLAIMS_CONSENT_FOR_VIEWED, false);

        assertThat(consented.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITH_CONSENT);
        assertThat(notConsented.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITHOUT_CONSENT);
        assertThat(notConsented.consentRequired()).containsExactly("claims.authorization", "claims.claim");
    }

    @ParameterizedTest(name = "adult dependent aged {0}")
    @ValueSource(ints = {18, 30, 70})
    void subscriberViewingAdultDependent(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, ADULT_DEPENDENT, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_ADULT_DEPENDENT);
        assertThat(result.permissions()).doesNotContainKeys("claims", "claims.claim");   // catch-all NO_ACCESS
        assertNoConsentOrMasking(result);
    }

    @ParameterizedTest(name = "other family member aged {0}")
    @ValueSource(ints = {0, 17, 50})
    void subscriberViewingAllOtherFamilyMembersHasNoAccess(int age) {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, ALL_OTHER, age);

        assertEmpty(result);
    }

    @Test
    void subscriberViewingSubscriberHasNoSeededRows() {
        // only the catch-all applies, and it is NO_ACCESS
        assertEmpty(evaluate(SUBSCRIBER_ACTOR, SUBSCRIBER, 40));
    }

    @Test
    void subscriberFamilyThroughTheRelationshipResolver() {
        List<PermissionRule> rules = activeRulesFor(SUBSCRIBER_ACTOR, SPOUSE, CHILD, ADULT_DEPENDENT);
        PermissionEvaluator.Index index = evaluator.index(rules);

        assertThat(index.evaluate(resolver.viewing(FamilyRelationship.SPOUSE, 40), 40, "SP", NO_CONSENT, false).permissions())
                .containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_SPOUSE);
        assertThat(index.evaluate(resolver.viewing(FamilyRelationship.CHILD, 7), 7, "C7", NO_CONSENT, false).permissions())
                .containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_CHILD_MINOR);
        assertThat(index.evaluate(resolver.viewing(FamilyRelationship.CHILD, 15), 15, "C15", NO_CONSENT, false).permissions())
                .containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_TEEN_WITHOUT_CONSENT);
        assertThat(index.evaluate(resolver.viewing(FamilyRelationship.CHILD, 18), 18, "C18", NO_CONSENT, false).permissions())
                .containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_ADULT_DEPENDENT);
        assertThat(index.evaluate(resolver.viewing(FamilyRelationship.EX_SPOUSE, 40), 40, "EX", NO_CONSENT, false).permissions())
                .containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_ADULT_DEPENDENT);
        assertThat(index.evaluate(resolver.viewing(FamilyRelationship.EX_SPOUSE, 17), 17, "EX17", NO_CONSENT, false).permissions())
                .isEmpty();
        assertThat(index.evaluate(resolver.viewing(null, 30), 30, "UNK", NO_CONSENT, false).permissions())
                .containsExactlyInAnyOrderEntriesOf(SUBSCRIBER_VIEWING_ADULT_DEPENDENT);
        assertThat(index.evaluate(resolver.viewing(null, 10), 10, "UNK10", NO_CONSENT, false).permissions()).isEmpty();
    }

    @Test
    void subscriberViewingTeenTraceNamesTheSeededRows() {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, 15, NO_CONSENT, true);

        assertThat(result.trace()).extracting(RuleTrace::ruleId).containsExactlyInAnyOrder(17L, 18L, 19L, 20L, 29L, 30L);
        assertThat(result.trace()).filteredOn(t -> t.ruleId() == 29L).singleElement()
                .isEqualTo(new RuleTrace(29L, "claims.claim", "Child", 13, 17, MASKED_ACCESS, "consent required, not on file"));
        assertThat(result.trace()).filteredOn(t -> t.ruleId() == 30L).singleElement()
                .isEqualTo(new RuleTrace(30L, "claims.authorization", "Child", 13, 17, CONSENT_REQUIRED, "consent required, not on file"));
        assertThat(result.trace()).filteredOn(t -> t.ruleId() == 18L).singleElement()
                .isEqualTo(new RuleTrace(18L, "benefits.idCard", "Child", 13, 17, FULL_ACCESS, "granted [1, 3]"));
    }

    @Test
    void subscriberViewingTeenWithConsentTraceShowsGrantAndMask() {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, CHILD, 15, CLAIMS_CONSENT_FOR_VIEWED, true);

        assertThat(result.trace()).filteredOn(t -> t.ruleId() == 29L).singleElement()
                .extracting(RuleTrace::outcome).isEqualTo("granted [1], masked");
        assertThat(result.trace()).filteredOn(t -> t.ruleId() == 30L).singleElement()
                .extracting(RuleTrace::outcome).isEqualTo("granted [1]");
    }

    @Test
    void subscriberViewingSpouseTraceShowsTheCatchAllNoAccessRow() {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, SPOUSE, 40, NO_CONSENT, true);

        assertThat(result.trace()).extracting(RuleTrace::ruleId).containsExactlyInAnyOrder(7L, 8L, 9L, 10L, 11L, 31L);
        assertThat(result.trace()).filteredOn(t -> t.ruleId() == 31L).singleElement()
                .isEqualTo(new RuleTrace(31L, "claims.claim", "All other family members", null, null, NO_ACCESS, "no actions"));
    }

    @Test
    void subscriberViewingSelfTraceSkipsTheParentRowAndTheReplacedCatchAll() {
        PermissionResult result = evaluate(SUBSCRIBER_ACTOR, SELF, 42, NO_CONSENT, true);

        assertThat(result.trace()).extracting(RuleTrace::ruleId)
                .containsExactlyInAnyOrder(2L, 3L, 4L, 5L, 6L, 23L, 24L, 25L)
                .doesNotContain(1L, 31L);
    }

    @Test
    void subscriberWithoutExplainGetsNoTrace() {
        assertThat(evaluate(SUBSCRIBER_ACTOR, CHILD, 15).trace()).isNull();
    }

    // =============================================================================== Spouse (REL_SOGI rows 9-10)

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {0, 5, 11})
    void spouseViewingChildZeroToEleven(int age) {
        PermissionResult result = evaluate(SPOUSE_ACTOR, CHILD, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(PROFILE_REL_ONLY);
        assertThat(result.permissions()).doesNotContainKey(SOGI);   // NOT_APPLICABLE, inactive
        assertNoConsentOrMasking(result);
    }

    @Test
    void spouseViewingChildAgedTwelveFallsBetweenTheSeededBands() {
        // REL_SOGI row 9 is 0-11 and row 10 is 13-17: the seed carries no row for a twelve-year-old
        assertEmpty(evaluate(SPOUSE_ACTOR, CHILD, 12));
    }

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {13, 15, 17})
    void spouseViewingChildThirteenToSeventeenHasNoAccess(int age) {
        PermissionResult result = evaluate(SPOUSE_ACTOR, CHILD, age);

        assertEmpty(result);
        assertThat(result.permissions()).doesNotContainKey("profile");   // parent row is NO_ACCESS and ignored anyway
    }

    @Test
    void spouseHasNoSeededRowsForOtherViewingRelationships() {
        assertEmpty(evaluate(SPOUSE_ACTOR, SELF, 40));
        assertEmpty(evaluate(SPOUSE_ACTOR, SUBSCRIBER, 40));
        assertEmpty(evaluate(SPOUSE_ACTOR, ADULT_DEPENDENT, 20));
        assertEmpty(evaluate(SPOUSE_ACTOR, ALL_OTHER, 20));
    }

    // =============================================================================== Ex-Spouse (REL_SOGI rows 11-14)

    @ParameterizedTest(name = "self aged {0}")
    @ValueSource(ints = {20, 45, 80})
    void exSpouseViewingSelf(int age) {
        PermissionResult result = evaluate(EX_SPOUSE_ACTOR, SELF, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(PROFILE_FULL);
        assertNoConsentOrMasking(result);
    }

    @Test
    void exSpouseViewingSubscriberHasNoAccess() {
        assertEmpty(evaluate(EX_SPOUSE_ACTOR, SUBSCRIBER, 45));
    }

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {13, 15, 17})
    void exSpouseViewingChildThirteenToSeventeenHasNoAccess(int age) {
        assertEmpty(evaluate(EX_SPOUSE_ACTOR, CHILD, age));
    }

    @ParameterizedTest(name = "child aged {0}")
    @ValueSource(ints = {0, 6, 12})
    void exSpouseViewingChildZeroToTwelveHasNoAccess(int age) {
        assertEmpty(evaluate(EX_SPOUSE_ACTOR, CHILD, age));
    }

    @Test
    void exSpouseViewingSpouseOrAdultDependentHasNoSeededRows() {
        assertEmpty(evaluate(EX_SPOUSE_ACTOR, SPOUSE, 40));
        assertEmpty(evaluate(EX_SPOUSE_ACTOR, ADULT_DEPENDENT, 20));
        assertEmpty(evaluate(EX_SPOUSE_ACTOR, ALL_OTHER, 20));
    }

    // =============================================================================== Adult Child (REL_SOGI rows 15-16)

    @ParameterizedTest(name = "self aged {0}")
    @ValueSource(ints = {18, 25, 40})
    void adultChildViewingSelf(int age) {
        PermissionResult result = evaluate(ADULT_CHILD_ACTOR, SELF, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(PROFILE_FULL);
        assertNoConsentOrMasking(result);
    }

    @ParameterizedTest(name = "other family member aged {0}")
    @ValueSource(ints = {5, 17, 40})
    void adultChildViewingAllOtherFamilyMembersHasNoAccess(int age) {
        assertEmpty(evaluate(ADULT_CHILD_ACTOR, ALL_OTHER, age));
    }

    @Test
    void adultChildViewingNamedRelationshipsFallsBackToTheNoAccessCatchAll() {
        assertEmpty(evaluate(ADULT_CHILD_ACTOR, SUBSCRIBER, 50));
        assertEmpty(evaluate(ADULT_CHILD_ACTOR, SPOUSE, 50));
        assertEmpty(evaluate(ADULT_CHILD_ACTOR, CHILD, 10));
        assertEmpty(evaluate(ADULT_CHILD_ACTOR, ADULT_DEPENDENT, 20));
    }

    // =============================================================================== Child (teenager) (REL_SOGI rows 17-18)

    @ParameterizedTest(name = "self aged {0}")
    @ValueSource(ints = {13, 15, 17})
    void teenagerViewingSelf(int age) {
        PermissionResult result = evaluate(TEENAGER_ACTOR, SELF, age);

        assertThat(result.permissions()).containsExactlyInAnyOrderEntriesOf(PROFILE_REL_ONLY);
        assertThat(result.permissions()).doesNotContainKey(SOGI);   // "Not collected for members under age 18"
        assertNoConsentOrMasking(result);
    }

    @ParameterizedTest(name = "other family member aged {0}")
    @ValueSource(ints = {5, 17, 40})
    void teenagerViewingAllOtherFamilyMembersHasNoAccess(int age) {
        assertEmpty(evaluate(TEENAGER_ACTOR, ALL_OTHER, age));
    }

    @Test
    void teenagerViewingNamedRelationshipsFallsBackToTheNoAccessCatchAll() {
        assertEmpty(evaluate(TEENAGER_ACTOR, SUBSCRIBER, 50));
        assertEmpty(evaluate(TEENAGER_ACTOR, SPOUSE, 50));
        assertEmpty(evaluate(TEENAGER_ACTOR, CHILD, 10));
        assertEmpty(evaluate(TEENAGER_ACTOR, ADULT_DEPENDENT, 20));
    }

    // =============================================================================== Child (minor) (REL_SOGI row 19)

    @ParameterizedTest(name = "self aged {0}")
    @ValueSource(ints = {0, 6, 12})
    void minorViewingSelfHasNothingBecauseEveryRowIsNotApplicable(int age) {
        assertEmpty(evaluate(MINOR_ACTOR, SELF, age));
    }

    @Test
    void minorViewingSelfWithExplainHasAnEmptyTrace() {
        PermissionResult result = evaluate(MINOR_ACTOR, SELF, 6, NO_CONSENT, true);

        assertEmpty(result);
        assertThat(result.trace()).isNotNull().isEmpty();
    }

    // =============================================================================== properties over the whole seed

    @Test
    void parentsAppearOnlyWhenAChildHasAnActionAndAlwaysAsViewOnly() {
        for (ActorRelationship actor : ActorRelationship.values()) {
            for (ViewingRelationship viewing : ViewingRelationship.values()) {
                for (int age = 0; age <= 80; age++) {
                    for (Set<String> consents : List.of(NO_CONSENT, CLAIMS_CONSENT_FOR_VIEWED)) {
                        Map<String, List<Integer>> permissions = evaluate(actor, viewing, age, consents, false).permissions();
                        for (String parent : PARENT_KEYS) {
                            boolean childHasAction = permissions.keySet().stream().anyMatch(k -> k.startsWith(parent + "."));
                            assertThat(permissions.containsKey(parent))
                                    .as("%s viewing %s aged %d consents %s: parent %s", actor, viewing, age, consents, parent)
                                    .isEqualTo(childHasAction);
                            if (childHasAction) {
                                assertThat(permissions.get(parent)).as("%s parent is view-only", parent).isEqualTo(VIEW);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void sexualOrientationGenderIdentityIsNeverReportedForAViewedChild() {
        // every SOGI row whose viewing relationship is Child is NOT_APPLICABLE ("Not collected for members under
        // age 18") and therefore inactive, whatever the actor and whatever the child's age
        for (ActorRelationship actor : ActorRelationship.values()) {
            for (int age = 0; age <= 80; age++) {
                PermissionResult result = evaluate(actor, CHILD, age);
                assertThat(result.permissions()).as("%s viewing a child aged %d", actor, age).doesNotContainKey(SOGI);
                assertThat(result.consentRequired()).doesNotContain(SOGI);
            }
        }
    }

    @Test
    void sexualOrientationGenderIdentityIsNeverReportedToAnActorUnderEighteenViewingSelf() {
        // REL_SOGI rows 17 and 19: the teenager's and the minor's own SOGI rows are NOT_APPLICABLE and inactive
        for (ActorRelationship actor : List.of(TEENAGER_ACTOR, MINOR_ACTOR)) {
            for (int age = 0; age < 18; age++) {
                assertThat(evaluate(actor, SELF, age).permissions()).as("%s aged %d", actor, age).doesNotContainKey(SOGI);
            }
        }
    }

    @Test
    void sexualOrientationGenderIdentityIsOnlyGrantedToAdultsViewingThemselves() {
        // the only active SOGI grants in the seed are rows 39 (Ex-Spouse, Self, Any) and 51 (Adult Child, Self, 18+)
        for (ActorRelationship actor : ActorRelationship.values()) {
            for (ViewingRelationship viewing : ViewingRelationship.values()) {
                for (int age = 0; age <= 80; age++) {
                    boolean granted = evaluate(actor, viewing, age).permissions().containsKey(SOGI);
                    boolean expected = viewing == SELF
                            && (actor == EX_SPOUSE_ACTOR || (actor == ADULT_CHILD_ACTOR && age >= 18));
                    assertThat(granted).as("%s viewing %s aged %d", actor, viewing, age).isEqualTo(expected);
                }
            }
        }
    }

    @Test
    void consentIsOnlyEverRequiredForTheTeenClaimsRows() {
        for (ActorRelationship actor : ActorRelationship.values()) {
            for (ViewingRelationship viewing : ViewingRelationship.values()) {
                for (int age = 0; age <= 80; age++) {
                    PermissionResult result = evaluate(actor, viewing, age);
                    boolean teenClaims = actor == SUBSCRIBER_ACTOR && viewing == CHILD && age >= 13 && age <= 17;
                    if (teenClaims) {
                        assertThat(result.consentRequired()).containsExactly("claims.authorization", "claims.claim");
                        assertThat(result.masked()).containsExactly("claims.claim");
                    } else {
                        assertThat(result.consentRequired()).as("%s viewing %s aged %d", actor, viewing, age).isEmpty();
                        assertThat(result.masked()).as("%s viewing %s aged %d", actor, viewing, age).isEmpty();
                    }
                }
            }
        }
    }

    @Test
    void everyResultHasSortedKeysAndSortedDistinctActionCodes() {
        for (ActorRelationship actor : ActorRelationship.values()) {
            for (ViewingRelationship viewing : ViewingRelationship.values()) {
                for (int age : new int[] {0, 7, 12, 13, 15, 17, 18, 42}) {
                    Map<String, List<Integer>> permissions = evaluate(actor, viewing, age, CLAIMS_CONSENT_FOR_VIEWED, false).permissions();
                    assertThat(List.copyOf(permissions.keySet())).isSorted();
                    for (Map.Entry<String, List<Integer>> entry : permissions.entrySet()) {
                        assertThat(entry.getValue()).as("%s", entry.getKey()).isNotEmpty().isSorted().doesNotHaveDuplicates();
                        assertThat(CATALOG_KEYS).contains(entry.getKey());
                    }
                }
            }
        }
    }

    @Test
    void narrowingTheRulesToTheFamilysViewingRelationshipsDoesNotChangeAnyResult() {
        // the repository's viewing filter is only an optimisation: the index selects by viewing anyway
        for (ActorRelationship actor : ActorRelationship.values()) {
            List<PermissionRule> everything = activeRulesFor(actor, ViewingRelationship.values());
            PermissionEvaluator.Index wide = evaluator.index(everything);
            for (ViewingRelationship viewing : ViewingRelationship.values()) {
                PermissionEvaluator.Index narrow = evaluator.index(activeRulesFor(actor, viewing));
                for (int age : new int[] {0, 11, 12, 13, 17, 18, 40}) {
                    PermissionResult fromNarrow = narrow.evaluate(viewing, age, VIEWED_ID, CLAIMS_CONSENT_FOR_VIEWED, true);
                    PermissionResult fromWide = wide.evaluate(viewing, age, VIEWED_ID, CLAIMS_CONSENT_FOR_VIEWED, true);
                    String scenario = actor + " viewing " + viewing + " aged " + age;
                    assertThat(fromNarrow.permissions()).as(scenario).isEqualTo(fromWide.permissions());
                    assertThat(fromNarrow.consentRequired()).as(scenario).isEqualTo(fromWide.consentRequired());
                    assertThat(fromNarrow.masked()).as(scenario).isEqualTo(fromWide.masked());
                    assertThat(fromNarrow.trace()).as(scenario).containsExactlyInAnyOrderElementsOf(fromWide.trace());
                }
            }
        }
    }
}
