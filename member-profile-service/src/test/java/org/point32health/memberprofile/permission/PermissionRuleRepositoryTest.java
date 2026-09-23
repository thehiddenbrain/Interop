package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.point32health.memberprofile.permission.ActorRelationship.ADULT_CHILD;
import static org.point32health.memberprofile.permission.ActorRelationship.CHILD_MINOR;
import static org.point32health.memberprofile.permission.ActorRelationship.CHILD_TEENAGER;
import static org.point32health.memberprofile.permission.ActorRelationship.EX_SPOUSE;
import static org.point32health.memberprofile.permission.ActorRelationship.SPOUSE;
import static org.point32health.memberprofile.permission.ActorRelationship.SUBSCRIBER;
import static org.point32health.memberprofile.permission.ViewingRelationship.ADULT_DEPENDENT;
import static org.point32health.memberprofile.permission.ViewingRelationship.ALL_OTHER;
import static org.point32health.memberprofile.permission.ViewingRelationship.CHILD;
import static org.point32health.memberprofile.permission.ViewingRelationship.SELF;

/**
 * {@link PermissionRuleRepository} against the real PostgreSQL database and the V4 seed (catalog section 4).
 * Skipped unless {@code MEMBER_PROFILE_TEST_DB=true}.
 * <p>
 * Covers the query (actor filter, viewing filter with the catch-all always included, inactive rows excluded,
 * order), the row mapping ({@code SMALLINT[]} to a sorted {@code List<Integer>} including the empty array,
 * nullable age bounds, every column) and the unknown-label handling. A row with an unknown
 * {@code actor_relationship} cannot reach the mapper through {@code activeRulesFor}: the check constraint
 * rejects it, and even without the constraint the {@code WHERE actor_relationship = :actor} clause only ever
 * matches a known label. Both facts are asserted; the mapper's RULE_DATA_INVALID branches are covered
 * without a database, by handing the repository a stubbed {@link JdbcClient}. Tests that write rows run in
 * a transaction that is rolled back, so the seed is untouched.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
class PermissionRuleRepositoryTest {

    private static final Set<ViewingRelationship> ALL_VIEWINGS = EnumSet.allOf(ViewingRelationship.class);
    private static final String NOT_APPLICABLE = "NOT_APPLICABLE";

    private static final String INSERT_RULE = """
            INSERT INTO family_permission.family_permission_rule
              (permission_family, permission_key, actor_relationship, viewing_relationship, age_description,
               minimum_age, maximum_age, action_codes, access_status, consent_required, administrative_consent,
               revocable, masked_data, is_active, source_sheet, source_row, source_access_text, notes)
            VALUES (:family, :key, :actor, :viewing, 'test', :minimumAge, :maximumAge,
                    CAST(:actionCodes AS smallint[]), :status, :consentRequired, FALSE, FALSE, :masked, :active,
                    'Test', 0, NULL, NULL)
            RETURNING permission_rule_id
            """;

    @Autowired PermissionRuleRepository repository;
    @Autowired JdbcClient jdbc;

    // ------------------------------------------------------------------------------------------ helpers

    private long insertRule(String key, String actor, String viewing, Integer minimumAge, Integer maximumAge,
                            String actionCodes, String status, boolean consentRequired, boolean masked, boolean active) {
        return jdbc.sql(INSERT_RULE)
                .param("family", PermissionRule.familyOf(key))
                .param("key", key)
                .param("actor", actor)
                .param("viewing", viewing)
                .param("minimumAge", minimumAge, Types.SMALLINT)
                .param("maximumAge", maximumAge, Types.SMALLINT)
                .param("actionCodes", actionCodes)
                .param("status", status)
                .param("consentRequired", consentRequired)
                .param("masked", masked)
                .param("active", active)
                .query(Long.class)
                .single();
    }

    private List<PermissionRule> allRulesFor(ActorRelationship actor) {
        return repository.activeRulesFor(actor, ALL_VIEWINGS);
    }

    private PermissionRule ruleWithId(ActorRelationship actor, long id) {
        return allRulesFor(actor).stream().filter(r -> r.id() == id).findFirst()
                .orElseThrow(() -> new AssertionError("row " + id + " not returned for " + actor));
    }

    private static PermissionRule find(List<PermissionRule> rules, String key, ViewingRelationship viewing, Integer minimumAge) {
        return rules.stream()
                .filter(r -> r.permissionKey().equals(key) && r.viewing() == viewing
                        && (minimumAge == null ? r.minimumAge() == null : minimumAge.equals(r.minimumAge())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + key + " / " + viewing + " / min " + minimumAge));
    }

    // =========================================================================================== the query

    @Nested
    class Query {

        @Test
        void catchAllRowsAreIncludedWithoutBeingAskedFor() {
            List<PermissionRule> rules = repository.activeRulesFor(SUBSCRIBER, Set.of(SELF));

            assertThat(rules).hasSize(10);
            assertThat(rules).extracting(PermissionRule::viewing).containsOnly(SELF, ALL_OTHER);
            assertThat(rules).filteredOn(r -> r.viewing() == ALL_OTHER)
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.id()).isEqualTo(31L);
                        assertThat(r.permissionKey()).isEqualTo("claims.claim");
                        assertThat(r.accessStatus()).isEqualTo("NO_ACCESS");
                        assertThat(r.actionCodes()).isEmpty();
                    });
        }

        @Test
        void emptyViewingSetStillReturnsTheCatchAll() {
            List<PermissionRule> rules = repository.activeRulesFor(SUBSCRIBER, List.of());

            assertThat(rules).singleElement().satisfies(r -> {
                assertThat(r.viewing()).isEqualTo(ALL_OTHER);
                assertThat(r.permissionKey()).isEqualTo("claims.claim");
            });
        }

        @Test
        void askingForTheCatchAllExplicitlyDoesNotDuplicateIt() {
            List<PermissionRule> implicit = repository.activeRulesFor(SUBSCRIBER, Set.of(SELF));
            List<PermissionRule> explicit = repository.activeRulesFor(SUBSCRIBER, Set.of(SELF, ALL_OTHER));
            List<PermissionRule> repeated = repository.activeRulesFor(SUBSCRIBER, List.of(SELF, SELF, ALL_OTHER));

            assertThat(explicit).isEqualTo(implicit);
            assertThat(repeated).isEqualTo(implicit);
            assertThat(explicit).extracting(PermissionRule::id).doesNotHaveDuplicates();
        }

        @Test
        void onlyRequestedViewingRelationshipsAreReturned() {
            List<PermissionRule> child = repository.activeRulesFor(SUBSCRIBER, Set.of(CHILD));

            assertThat(child).hasSize(15);           // 14 Child rows + the catch-all
            assertThat(child).extracting(PermissionRule::viewing).containsOnly(CHILD, ALL_OTHER);
            assertThat(child).filteredOn(r -> r.viewing() == CHILD).hasSize(14);

            List<PermissionRule> adult = repository.activeRulesFor(SUBSCRIBER, Set.of(ADULT_DEPENDENT));
            assertThat(adult).filteredOn(r -> r.viewing() == ADULT_DEPENDENT)
                    .extracting(PermissionRule::permissionKey).containsExactly("benefits.coverage", "benefits.idCard");
        }

        @Test
        void subscriberRowsPerViewingRelationshipMatchTheSeed() {
            List<PermissionRule> rules = allRulesFor(SUBSCRIBER);

            assertThat(rules).hasSize(31);
            assertThat(rules).filteredOn(r -> r.viewing() == SELF).hasSize(9);
            assertThat(rules).filteredOn(r -> r.viewing() == ViewingRelationship.SPOUSE).hasSize(5);
            assertThat(rules).filteredOn(r -> r.viewing() == CHILD).hasSize(14);
            assertThat(rules).filteredOn(r -> r.viewing() == ADULT_DEPENDENT).hasSize(2);
            assertThat(rules).filteredOn(r -> r.viewing() == ALL_OTHER).hasSize(1);
            assertThat(rules).filteredOn(r -> r.viewing() == ViewingRelationship.SUBSCRIBER).isEmpty();
        }

        @ParameterizedTest(name = "{0} has {1} active rows")
        @CsvSource({"SUBSCRIBER, 31", "SPOUSE, 3", "EX_SPOUSE, 10", "ADULT_CHILD, 5", "CHILD_TEENAGER, 4", "CHILD_MINOR, 0"})
        void rowsOfOtherActorsAreExcluded(ActorRelationship actor, int expected) {
            List<PermissionRule> rules = allRulesFor(actor);

            assertThat(rules).hasSize(expected);
            assertThat(rules).allSatisfy(r -> assertThat(r.actor()).isEqualTo(actor));
        }

        @Test
        void everyActorTogetherReadsTheFiftyThreeActiveSeedRowsOnce() {
            List<Long> ids = EnumSet.allOf(ActorRelationship.class).stream()
                    .flatMap(a -> allRulesFor(a).stream()).map(PermissionRule::id).sorted().toList();

            assertThat(ids).hasSize(53).doesNotHaveDuplicates();
            long activeInTable = jdbc.sql("SELECT count(*) FROM family_permission.family_permission_rule WHERE is_active")
                    .query(Long.class).single();
            assertThat(activeInTable).isEqualTo(53);
        }

        @Test
        void everySubscriberRowComesBackWhateverTheOrder() {
            // No ORDER BY on purpose: the evaluator groups and sorts itself, so the index scan is returned as is.
            List<PermissionRule> rules = allRulesFor(SUBSCRIBER);

            assertThat(rules).hasSize(31);
            assertThat(rules).extracting(PermissionRule::permissionKey).contains("benefits", "claims.referral", "claims.claim");
        }

        @Test
        void childMinorHasThreeRowsInTheTableButAllAreNotApplicable() {
            long inTable = jdbc.sql("SELECT count(*) FROM family_permission.family_permission_rule WHERE actor_relationship = 'Child (minor)'")
                    .query(Long.class).single();
            long active = jdbc.sql("SELECT count(*) FROM family_permission.family_permission_rule WHERE actor_relationship = 'Child (minor)' AND is_active")
                    .query(Long.class).single();

            assertThat(inTable).isEqualTo(3);
            assertThat(active).isZero();
            assertThat(allRulesFor(CHILD_MINOR)).isEmpty();
        }
    }

    // =========================================================================================== row mapping

    @Nested
    class RowMapping {

        @Test
        void everyColumnOfAMaskedConsentRowIsMapped() {
            PermissionRule row = find(allRulesFor(SUBSCRIBER), "claims.claim", CHILD, 13);

            assertThat(row).isEqualTo(new PermissionRule(29L, "claims", "claims.claim", SUBSCRIBER, CHILD, 13, 17,
                    List.of(1), "MASKED_ACCESS", true, true));
            assertThat(row.isParentKey()).isFalse();
            assertThat(row.appliesToAge(13)).isTrue();
            assertThat(row.appliesToAge(18)).isFalse();
        }

        @Test
        void consentRequiredRowWithoutMaskingIsMapped() {
            PermissionRule row = find(allRulesFor(SUBSCRIBER), "claims.authorization", CHILD, 13);

            assertThat(row).isEqualTo(new PermissionRule(30L, "claims", "claims.authorization", SUBSCRIBER, CHILD, 13, 17,
                    List.of(1), "CONSENT_REQUIRED", true, false));
        }

        @Test
        void fullAccessRowIsMappedWithFalseFlags() {
            PermissionRule row = find(allRulesFor(SUBSCRIBER), "benefits.idCard", SELF, null);

            assertThat(row).isEqualTo(new PermissionRule(3L, "benefits", "benefits.idCard", SUBSCRIBER, SELF, null, null,
                    List.of(1, 3), "FULL_ACCESS", false, false));
        }

        @Test
        void parentRowsAreReadAndFlaggedAsParentKeys() {
            List<PermissionRule> rules = allRulesFor(SUBSCRIBER);

            assertThat(rules).filteredOn(PermissionRule::isParentKey)
                    .singleElement()
                    .satisfies(r -> {
                        assertThat(r.id()).isEqualTo(1L);
                        assertThat(r.permissionKey()).isEqualTo("benefits");
                        assertThat(r.permissionFamily()).isEqualTo("benefits");
                    });
            assertThat(allRulesFor(EX_SPOUSE)).filteredOn(PermissionRule::isParentKey)
                    .extracting(PermissionRule::permissionKey).containsOnly("profile").hasSize(4);
        }

        @Test
        void permissionFamilyAlwaysEqualsTheKeyPrefix() {
            for (ActorRelationship actor : ActorRelationship.values()) {
                assertThat(allRulesFor(actor)).allSatisfy(r ->
                        assertThat(r.permissionFamily()).isEqualTo(PermissionRule.familyOf(r.permissionKey())));
            }
        }

        @Test
        void actionCodesAreASortedList() {
            List<PermissionRule> rules = allRulesFor(SUBSCRIBER);

            assertThat(find(rules, "benefits.idCard", SELF, null).actionCodes()).containsExactly(1, 3);
            assertThat(find(rules, "benefits.idCard", CHILD, 0).actionCodes()).containsExactly(1, 3);
            assertThat(find(rules, "benefits.coverage", SELF, null).actionCodes()).containsExactly(1);
            assertThat(find(rules, "claims.claim", SELF, null).actionCodes()).containsExactly(1, 3);
            assertThat(find(allRulesFor(EX_SPOUSE), "profile.raceEthnicityLanguage", SELF, null).actionCodes()).containsExactly(1, 2);
            assertThat(rules).allSatisfy(r -> assertThat(r.actionCodes()).isSorted().doesNotHaveDuplicates());
        }

        @Test
        void emptyArrayMapsToAnEmptyList() {
            PermissionRule catchAll = find(allRulesFor(SUBSCRIBER), "claims.claim", ALL_OTHER, null);
            assertThat(catchAll.actionCodes()).isNotNull().isEmpty();

            List<PermissionRule> exSpouse = allRulesFor(EX_SPOUSE);
            assertThat(exSpouse).filteredOn(r -> r.accessStatus().equals("NO_ACCESS"))
                    .hasSize(7)
                    .allSatisfy(r -> assertThat(r.actionCodes()).isEmpty());
        }

        @Test
        @Transactional
        void unsortedArrayIsSortedOnRead() {
            long id = insertRule("documents.letter", "Subscriber", "Self", null, null, "{3,1,4,2}", "FULL_ACCESS", false, false, true);

            assertThat(ruleWithId(SUBSCRIBER, id).actionCodes()).containsExactly(1, 2, 3, 4);
        }

        @Test
        @Transactional
        void singleAndEmptyArraysAreMapped() {
            long single = insertRule("documents.letter", "Subscriber", "Self", null, null, "{4}", "FULL_ACCESS", false, false, true);
            long empty = insertRule("documents.taxDocument", "Subscriber", "Self", null, null, "{}", "NO_ACCESS", false, false, true);

            assertThat(ruleWithId(SUBSCRIBER, single).actionCodes()).containsExactly(4);
            assertThat(ruleWithId(SUBSCRIBER, empty).actionCodes()).isEmpty();
        }

        @Test
        void actionCodeListIsUnmodifiable() {
            PermissionRule row = find(allRulesFor(SUBSCRIBER), "benefits.idCard", SELF, null);

            assertThatThrownBy(() -> row.actionCodes().add(2)).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void anyAgeBandMapsToNullBounds() {
            List<PermissionRule> self = allRulesFor(SUBSCRIBER).stream().filter(r -> r.viewing() == SELF).toList();

            assertThat(self).hasSize(9).allSatisfy(r -> {
                assertThat(r.minimumAge()).isNull();
                assertThat(r.maximumAge()).isNull();
                assertThat(r.appliesToAge(0)).isTrue();
                assertThat(r.appliesToAge(120)).isTrue();
            });
        }

        @Test
        void childBandsMapToInclusiveBounds() {
            List<PermissionRule> subscriber = allRulesFor(SUBSCRIBER);

            assertThat(find(subscriber, "benefits.coverage", CHILD, 0)).extracting(PermissionRule::minimumAge, PermissionRule::maximumAge)
                    .containsExactly(0, 12);
            assertThat(find(subscriber, "benefits.coverage", CHILD, 13)).extracting(PermissionRule::minimumAge, PermissionRule::maximumAge)
                    .containsExactly(13, 17);
            assertThat(find(allRulesFor(SPOUSE), "profile.raceEthnicityLanguage", CHILD, 0))
                    .extracting(PermissionRule::minimumAge, PermissionRule::maximumAge).containsExactly(0, 11);
        }

        @Test
        void openEndedBandMapsToNullMaximum() {
            PermissionRule adult = find(allRulesFor(SUBSCRIBER), "benefits.coverage", ADULT_DEPENDENT, 18);

            assertThat(adult.minimumAge()).isEqualTo(18);
            assertThat(adult.maximumAge()).isNull();
            assertThat(adult.appliesToAge(17)).isFalse();
            assertThat(adult.appliesToAge(99)).isTrue();

            PermissionRule adultChildSelf = find(allRulesFor(ADULT_CHILD), "profile.raceEthnicityLanguage", SELF, 18);
            assertThat(adultChildSelf.maximumAge()).isNull();
        }

        @Test
        @Transactional
        void nullMinimumWithAMaximumIsMapped() {
            long id = insertRule("documents.letter", "Subscriber", "Child", null, 5, "{1}", "FULL_ACCESS", false, false, true);

            PermissionRule row = ruleWithId(SUBSCRIBER, id);
            assertThat(row.minimumAge()).isNull();
            assertThat(row.maximumAge()).isEqualTo(5);
            assertThat(row.appliesToAge(0)).isTrue();
            assertThat(row.appliesToAge(6)).isFalse();
        }
    }

    // =========================================================================================== filtering

    @Nested
    class Filtering {

        @ParameterizedTest
        @EnumSource(ActorRelationship.class)
        void inactiveNotApplicableRowsAreNeverReturned(ActorRelationship actor) {
            assertThat(allRulesFor(actor)).noneMatch(r -> r.accessStatus().equals(NOT_APPLICABLE));
        }

        @Test
        void theTenInactiveSeedRowsAreAllNotApplicable() {
            List<String> statuses = jdbc.sql("SELECT access_status FROM family_permission.family_permission_rule WHERE NOT is_active")
                    .query(String.class).list();

            assertThat(statuses).hasSize(10).containsOnly(NOT_APPLICABLE);
        }

        @Test
        @Transactional
        void inactiveRowInsertedNowIsExcludedWhileAnActiveOneIsRead() {
            long inactive = insertRule("documents.letter", "Subscriber", "Self", null, null, "{1}", "NOT_APPLICABLE", false, false, false);
            long active = insertRule("documents.planDocument", "Subscriber", "Self", null, null, "{1}", "FULL_ACCESS", false, false, true);

            List<PermissionRule> rules = repository.activeRulesFor(SUBSCRIBER, Set.of(SELF));

            assertThat(rules).extracting(PermissionRule::id).contains(active).doesNotContain(inactive);
            assertThat(rules).hasSize(11);
        }

        @Test
        @Transactional
        void deactivatingASeededRowRemovesItFromTheNextRead() {
            // no caching: the next request sees the change (rolled back after the test)
            int updated = jdbc.sql("UPDATE family_permission.family_permission_rule SET is_active = FALSE WHERE permission_rule_id = 3").update();
            assertThat(updated).isEqualTo(1);

            assertThat(repository.activeRulesFor(SUBSCRIBER, Set.of(SELF)))
                    .hasSize(9)
                    .extracting(PermissionRule::permissionKey).doesNotContain("benefits.idCard");
        }
    }

    // =========================================================================================== unknown labels

    @Nested
    class UnknownLabels {

        @Test
        @Transactional
        void unknownActorLabelIsRejectedByTheCheckConstraint() {
            assertThatThrownBy(() -> insertRule("claims.claim", "Grandparent", "Self", null, null, "{1}", "FULL_ACCESS", false, false, true))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_actor_relationship");
        }

        @Test
        @Transactional
        void unknownViewingLabelIsRejectedByTheCheckConstraint() {
            assertThatThrownBy(() -> insertRule("claims.claim", "Subscriber", "Cousin", null, null, "{1}", "FULL_ACCESS", false, false, true))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_viewing_relationship");
        }

        @Test
        @Transactional
        void withoutTheConstraintAnUnknownActorRowIsSimplyNeverSelected() {
            // the query filters on the enum labels, so such a row cannot reach the mapper's RULE_DATA_INVALID branch
            jdbc.sql("ALTER TABLE family_permission.family_permission_rule DROP CONSTRAINT ck_actor_relationship").update();
            long id = insertRule("claims.claim", "Grandparent", "Self", null, null, "{1}", "FULL_ACCESS", false, false, true);

            for (ActorRelationship actor : ActorRelationship.values()) {
                assertThat(allRulesFor(actor)).extracting(PermissionRule::id).doesNotContain(id);
            }
        }

        @Test
        @Transactional
        void withoutTheConstraintAnUnknownViewingRowIsSimplyNeverSelected() {
            jdbc.sql("ALTER TABLE family_permission.family_permission_rule DROP CONSTRAINT ck_viewing_relationship").update();
            long id = insertRule("claims.claim", "Subscriber", "Cousin", null, null, "{1}", "FULL_ACCESS", false, false, true);

            assertThat(allRulesFor(SUBSCRIBER)).hasSize(31).extracting(PermissionRule::id).doesNotContain(id);
        }

        /** The mapper's defensive branches, reached by stubbing {@link JdbcClient} so the row mapper sees a hand-made row. */
        @Nested
        class MapperWithoutADatabase {

            private static final long ROW_ID = 999L;

            @SuppressWarnings({"unchecked", "rawtypes"})
            private PermissionRuleRepository repositoryReturning(ResultSet row) {
                JdbcClient client = mock(JdbcClient.class);
                JdbcClient.StatementSpec spec = mock(JdbcClient.StatementSpec.class);
                JdbcClient.MappedQuerySpec<PermissionRule> mapped = mock(JdbcClient.MappedQuerySpec.class);
                AtomicReference<RowMapper<PermissionRule>> mapper = new AtomicReference<>();
                when(client.sql(anyString())).thenReturn(spec);
                when(spec.param(anyString(), any())).thenReturn(spec);
                when(spec.query(any(RowMapper.class))).thenAnswer(invocation -> {
                    mapper.set((RowMapper<PermissionRule>) invocation.getArgument(0));
                    return mapped;
                });
                when(mapped.list()).thenAnswer(invocation -> List.of(mapper.get().mapRow(row, 0)));
                return new PermissionRuleRepository(client);
            }

            private ResultSet row(String actorLabel, String viewingLabel, Integer minimumAge, Integer maximumAge, Object[] codes) throws Exception {
                ResultSet rs = mock(ResultSet.class);
                when(rs.getLong("permission_rule_id")).thenReturn(ROW_ID);
                when(rs.getString("permission_family")).thenReturn("claims");
                when(rs.getString("permission_key")).thenReturn("claims.claim");
                when(rs.getString("actor_relationship")).thenReturn(actorLabel);
                when(rs.getString("viewing_relationship")).thenReturn(viewingLabel);
                when(rs.getObject("minimum_age", Integer.class)).thenReturn(minimumAge);
                when(rs.getObject("maximum_age", Integer.class)).thenReturn(maximumAge);
                if (codes != null) {                       // unstubbed, getArray returns null: the "no array" case
                    Array array = mock(Array.class);
                    when(array.getArray()).thenReturn(codes);
                    when(rs.getArray("action_codes")).thenReturn(array);
                }
                when(rs.getString("access_status")).thenReturn("FULL_ACCESS");
                when(rs.getBoolean("consent_required")).thenReturn(false);
                when(rs.getBoolean("masked_data")).thenReturn(false);
                return rs;
            }

            @Test
            void unknownActorLabelRaisesRuleDataInvalid() throws Exception {
                PermissionRuleRepository repo = repositoryReturning(row("Grandparent", "Self", null, null, new Short[]{1}));

                assertThatThrownBy(() -> repo.activeRulesFor(SUBSCRIBER, Set.of(SELF)))
                        .isInstanceOfSatisfying(MemberProfileException.class, e -> {
                            assertThat(e.getCode()).isEqualTo(ErrorCode.RULE_DATA_INVALID);
                            assertThat(e.getMessage()).contains("family_permission_rule " + ROW_ID)
                                    .contains("unknown actor_relationship 'Grandparent'");
                        });
            }

            @Test
            void unknownViewingLabelRaisesRuleDataInvalid() throws Exception {
                PermissionRuleRepository repo = repositoryReturning(row("Subscriber", "Cousin", null, null, new Short[]{1}));

                assertThatThrownBy(() -> repo.activeRulesFor(SUBSCRIBER, Set.of(SELF)))
                        .isInstanceOfSatisfying(MemberProfileException.class, e -> {
                            assertThat(e.getCode()).isEqualTo(ErrorCode.RULE_DATA_INVALID);
                            assertThat(e.getMessage()).contains("unknown viewing_relationship 'Cousin'");
                        });
            }

            @Test
            void knownLabelsMapToTheEnumsAndTheArrayIsSorted() throws Exception {
                PermissionRuleRepository repo = repositoryReturning(row("Child (teenager)", "All other family members", 13, 17, new Short[]{3, 1}));

                assertThat(repo.activeRulesFor(CHILD_TEENAGER, Set.of(SELF))).singleElement().isEqualTo(
                        new PermissionRule(ROW_ID, "claims", "claims.claim", CHILD_TEENAGER, ALL_OTHER, 13, 17, List.of(1, 3), "FULL_ACCESS", false, false));
            }

            @Test
            void nullArrayAndNullElementsMapToAnEmptyOrShorterList() throws Exception {
                assertThat(repositoryReturning(row("Subscriber", "Self", null, null, null)).activeRulesFor(SUBSCRIBER, Set.of(SELF)))
                        .singleElement().extracting(PermissionRule::actionCodes).isEqualTo(List.of());
                assertThat(repositoryReturning(row("Subscriber", "Self", null, null, new Object[0])).activeRulesFor(SUBSCRIBER, Set.of(SELF)))
                        .singleElement().extracting(PermissionRule::actionCodes).isEqualTo(List.of());
                assertThat(repositoryReturning(row("Subscriber", "Self", null, null, new Integer[]{2, null, 1})).activeRulesFor(SUBSCRIBER, Set.of(SELF)))
                        .singleElement().extracting(PermissionRule::actionCodes).isEqualTo(List.of(1, 2));
            }
        }
    }

    // ------------------------------------------------------------------------------- golden run on the real seed

    /** The catalog's section 4 scenarios evaluated on rows read from the database, not from an in-memory copy. */
    @Nested
    class SeededGoldenRun {

        private final PermissionEvaluator evaluator = new PermissionEvaluator();

        private PermissionResult run(ActorRelationship actor, ViewingRelationship viewing, int age, Set<String> consents) {
            List<PermissionRule> rows = repository.activeRulesFor(actor, java.util.EnumSet.of(viewing));
            return evaluator.index(rows).evaluate(viewing, age, "VIEWED", consents, false);
        }

        @Test
        void spouseViewingYoungChildSeesRaceEthnicityLanguageOnly() {
            PermissionResult r = run(ActorRelationship.SPOUSE, ViewingRelationship.CHILD, 5, Set.of());
            assertThat(r.permissions()).containsExactly(Map.entry("profile", List.of(1)), Map.entry("profile.raceEthnicityLanguage", List.of(1, 2)));
        }

        @Test
        void spouseViewingTeenSeesNothingOfTheProfileFamily() {
            assertThat(run(ActorRelationship.SPOUSE, ViewingRelationship.CHILD, 15, Set.of()).permissions()).isEmpty();
        }

        @Test
        void exSpouseAndAdultChildSeeTheirOwnProfileFully() {
            for (ActorRelationship actor : List.of(ActorRelationship.EX_SPOUSE, ActorRelationship.ADULT_CHILD)) {
                PermissionResult r = run(actor, ViewingRelationship.SELF, 30, Set.of());
                assertThat(r.permissions()).as(actor.label()).containsExactly(Map.entry("profile", List.of(1)),
                        Map.entry("profile.raceEthnicityLanguage", List.of(1, 2)), Map.entry("profile.sexualOrientationGenderIdentity", List.of(1, 2)));
            }
            assertThat(run(ActorRelationship.EX_SPOUSE, ViewingRelationship.SUBSCRIBER, 50, Set.of()).permissions()).isEmpty();
        }

        @Test
        void teenSeesOwnRaceEthnicityLanguageButNoSogiAndMinorSeesNothing() {
            PermissionResult teen = run(ActorRelationship.CHILD_TEENAGER, ViewingRelationship.SELF, 15, Set.of());
            assertThat(teen.permissions()).containsExactly(Map.entry("profile", List.of(1)), Map.entry("profile.raceEthnicityLanguage", List.of(1, 2)));
            assertThat(run(ActorRelationship.CHILD_MINOR, ViewingRelationship.SELF, 7, Set.of()).permissions()).isEmpty();
        }

        @Test
        void subscriberViewingTeenWithConsentOnFileForClaims() {
            Set<String> consent = Set.of(ConsentRepository.consentKey("VIEWED", "claims"));
            PermissionResult r = run(ActorRelationship.SUBSCRIBER, ViewingRelationship.CHILD, 15, consent);

            assertThat(r.permissions()).containsEntry("claims", List.of(1)).containsEntry("claims.claim", List.of(1))
                    .containsEntry("claims.authorization", List.of(1)).doesNotContainKey("claims.referral")
                    .containsEntry("benefits.idCard", List.of(1, 3)).doesNotContainKey("benefits.spendingAccount");
            assertThat(r.consentRequired()).isEmpty();
            assertThat(r.masked()).containsExactly("claims.claim");
        }
    }
}
