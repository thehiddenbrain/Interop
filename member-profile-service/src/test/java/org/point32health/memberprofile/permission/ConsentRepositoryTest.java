package org.point32health.memberprofile.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ConsentRepository} against the real database ({@code family_permission.family_consent}). The table
 * is empty after the migrations, so every test writes its own rows inside a transaction that is rolled back.
 * Skipped unless {@code MEMBER_PROFILE_TEST_DB=true}.
 * <p>
 * Catalog section 4 rule 3: a CONSENT_REQUIRED row grants only when there is an unrevoked consent row for
 * (actor, viewed member, permission family). The repository returns the set of {@code viewed|family} keys
 * the evaluator looks up.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
@Transactional
class ConsentRepositoryTest {

    private static final String ACTOR = "TH0000001";
    private static final String TEEN = "TH0000002";
    private static final String OTHER_CHILD = "TH0000003";
    private static final String OTHER_ACTOR = "TH0000009";

    @Autowired ConsentRepository repository;
    @Autowired JdbcClient jdbc;

    private void consent(String actor, String viewed, String family, boolean revoked) {
        jdbc.sql("""
                INSERT INTO family_permission.family_consent
                  (actor_member_id, viewed_member_id, permission_family, granted_by, revoked_at)
                VALUES (:actor, :viewed, :family, 'test', CASE WHEN :revoked THEN now() ELSE NULL END)
                """)
                .param("actor", actor)
                .param("viewed", viewed)
                .param("family", family)
                .param("revoked", revoked)
                .update();
    }

    // ------------------------------------------------------------------------------------------ key format

    @Test
    void consentKeyIsViewedMemberIdPipeFamily() {
        assertThat(ConsentRepository.consentKey("HP0000002", "claims")).isEqualTo("HP0000002|claims");
        assertThat(ConsentRepository.consentKey(TEEN, "benefits")).isEqualTo(TEEN + "|benefits");
    }

    @Test
    void consentKeyDistinguishesMemberAndFamily() {
        assertThat(ConsentRepository.consentKey("A", "claims"))
                .isNotEqualTo(ConsentRepository.consentKey("B", "claims"))
                .isNotEqualTo(ConsentRepository.consentKey("A", "benefits"));
    }

    // ------------------------------------------------------------------------------------------ reads

    @Test
    void noRowsGiveAnEmptySet() {
        long rows = jdbc.sql("SELECT count(*) FROM family_permission.family_consent WHERE actor_member_id = :actor")
                .param("actor", ACTOR).query(Long.class).single();
        assertThat(rows).isZero();

        assertThat(repository.activeConsentsFor(ACTOR)).isNotNull().isEmpty();
    }

    @Test
    void grantedRowIsPresentUnderItsKey() {
        consent(ACTOR, TEEN, "claims", false);

        Set<String> consents = repository.activeConsentsFor(ACTOR);

        assertThat(consents).containsExactly(ConsentRepository.consentKey(TEEN, "claims"));
        assertThat(consents).containsExactly(TEEN + "|claims");
    }

    @Test
    void revokedRowIsAbsent() {
        consent(ACTOR, TEEN, "claims", true);

        assertThat(repository.activeConsentsFor(ACTOR)).isEmpty();
    }

    @Test
    void revokedRowDoesNotHideAFreshGrantForTheSamePair() {
        consent(ACTOR, TEEN, "claims", true);
        consent(ACTOR, TEEN, "claims", false);

        assertThat(repository.activeConsentsFor(ACTOR)).containsExactly(TEEN + "|claims");
    }

    @Test
    void rowsOfAnotherActorAreAbsent() {
        consent(OTHER_ACTOR, TEEN, "claims", false);

        assertThat(repository.activeConsentsFor(ACTOR)).isEmpty();
        assertThat(repository.activeConsentsFor(OTHER_ACTOR)).containsExactly(TEEN + "|claims");
    }

    @Test
    void consentIsPerViewedMemberAndFamily() {
        consent(ACTOR, TEEN, "claims", false);
        consent(ACTOR, TEEN, "benefits", true);
        consent(ACTOR, OTHER_CHILD, "benefits", false);
        consent(OTHER_ACTOR, OTHER_CHILD, "claims", false);

        Set<String> consents = repository.activeConsentsFor(ACTOR);

        assertThat(consents).containsExactlyInAnyOrder(TEEN + "|claims", OTHER_CHILD + "|benefits");
        assertThat(consents).doesNotContain(TEEN + "|benefits", OTHER_CHILD + "|claims");
    }

    @Test
    void duplicateGrantsCollapseToOneKey() {
        consent(ACTOR, TEEN, "claims", false);
        consent(ACTOR, TEEN, "claims", false);

        assertThat(repository.activeConsentsFor(ACTOR)).hasSize(1).containsExactly(TEEN + "|claims");
    }

    @Test
    void actorIdIsMatchedExactly() {
        consent(ACTOR, TEEN, "claims", false);

        assertThat(repository.activeConsentsFor(ACTOR.toLowerCase())).isEmpty();
        assertThat(repository.activeConsentsFor(ACTOR + " ")).isEmpty();
        assertThat(repository.activeConsentsFor("")).isEmpty();
    }

    @ParameterizedTest(name = "family {0}")
    @CsvSource({"benefits", "claims", "demographic", "documents", "forms", "profile"})
    void everyPermissionFamilyCanBeConsented(String family) {
        consent(ACTOR, TEEN, family, false);

        assertThat(repository.activeConsentsFor(ACTOR)).containsExactly(ConsentRepository.consentKey(TEEN, family));
    }

    @Test
    void theKeyMatchesWhatTheEvaluatorLooksUp() {
        consent(ACTOR, TEEN, "claims", false);
        PermissionRule consentRow = new PermissionRule(30L, "claims", "claims.authorization", ActorRelationship.SUBSCRIBER,
                ViewingRelationship.CHILD, 13, 17, java.util.List.of(1), "CONSENT_REQUIRED", true, false);

        Set<String> consents = repository.activeConsentsFor(ACTOR);

        assertThat(consents).contains(ConsentRepository.consentKey(TEEN, consentRow.permissionFamily()));
        assertThat(consents).doesNotContain(ConsentRepository.consentKey(OTHER_CHILD, consentRow.permissionFamily()));
    }
}
