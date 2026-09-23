package org.point32health.memberprofile.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.point32health.memberprofile.api.MemberProfileRequest;
import org.point32health.memberprofile.api.MemberProfileResponse;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberIds;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.config.MemberProfileProperties;
import org.point32health.memberprofile.memberdomain.MemberDomainClient;
import org.point32health.memberprofile.memberdomain.MemberDomainMember;
import org.point32health.memberprofile.memberdomain.MemberDomainMember.FamilyMember;
import org.point32health.memberprofile.permission.ActorRelationship;
import org.point32health.memberprofile.permission.ConsentRepository;
import org.point32health.memberprofile.permission.FamilyRelationship;
import org.point32health.memberprofile.permission.PermissionEvaluator;
import org.point32health.memberprofile.permission.PermissionRule;
import org.point32health.memberprofile.permission.PermissionRuleRepository;
import org.point32health.memberprofile.permission.ReferenceData;
import org.point32health.memberprofile.permission.ReferenceDataRepository;
import org.point32health.memberprofile.permission.RelationshipResolver;
import org.point32health.memberprofile.permission.ViewingRelationship;
import org.point32health.memberprofile.segmentation.ComparisonOperator;
import org.point32health.memberprofile.segmentation.Segment;
import org.point32health.memberprofile.segmentation.Company;
import org.point32health.memberprofile.segmentation.SegmentRule;
import org.point32health.memberprofile.segmentation.SegmentRuleRepository;
import org.point32health.memberprofile.segmentation.SegmentationEvaluator;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.point32health.memberprofile.permission.ViewingRelationship.ADULT_DEPENDENT;
import static org.point32health.memberprofile.permission.ViewingRelationship.ALL_OTHER;
import static org.point32health.memberprofile.permission.ViewingRelationship.CHILD;
import static org.point32health.memberprofile.permission.ViewingRelationship.SELF;
import static org.point32health.memberprofile.permission.ViewingRelationship.SPOUSE;
import static org.point32health.memberprofile.permission.ViewingRelationship.SUBSCRIBER;

/**
 * {@link MemberProfileService} orchestration with MemberDomain and every repository mocked, the evaluators,
 * the relationship resolver and a fixed clock real, and the fan-out run on a synchronous executor so every
 * call happens on the test thread in program order.
 * <ul>
 *   <li>Exactly one MemberDomain call and one call per repository per request (catalog section 6).</li>
 *   <li>Company from {@code memberTypeCode}, age from {@code age} or {@code dateOfBirth}, 422 when missing.</li>
 *   <li>The actor relationship and the viewing set that drive the permission query; the roster entry that is
 *       the member themselves is skipped; unknown family codes fall into age buckets, an unknown actor code
 *       is 422 (catalog section 5).</li>
 *   <li>Consents and segmentation facts are passed through to the evaluators; explain fills the trace and
 *       timings; failures inside executor tasks surface as the original exception.</li>
 * </ul>
 * The catch-all "All other family members" rows are appended to the query by {@code PermissionRuleRepository},
 * not by the service, so the viewing set asserted here holds Self plus the family's relationships only.
 */
class MemberProfileServiceTest {

    static final String MEMBER = "HP0000001";
    static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    static final LocalDate TODAY = LocalDate.of(2026, 9, 23);

    final MemberDomainClient memberDomain = mock(MemberDomainClient.class);
    final SegmentRuleRepository segmentRules = mock(SegmentRuleRepository.class);
    final PermissionRuleRepository permissionRules = mock(PermissionRuleRepository.class);
    final ReferenceDataRepository referenceData = mock(ReferenceDataRepository.class);
    final ConsentRepository consents = mock(ConsentRepository.class);
    /** AsyncTaskExecutor has one abstract method, so spring-core's SyncTaskExecutor serves as one. */
    final AsyncTaskExecutor executor = new SyncTaskExecutor()::execute;

    static final MemberProfileProperties PROPERTIES = new MemberProfileProperties(
            new MemberProfileProperties.MemberDomain("http://localhost:8090/api/v1", "/members/{memberId}", Duration.ofSeconds(2), Duration.ofSeconds(3)),
            new MemberProfileProperties.Security(new MemberProfileProperties.Security.ApiKey(false, "X-Api-Key", "")),
            new MemberProfileProperties.Explain(true),
            new MemberProfileProperties.Http(8192, Duration.ofSeconds(5)),
            "UTC");

    final MemberProfileService service = new MemberProfileService(memberDomain, segmentRules, new SegmentationEvaluator(),
            permissionRules, new PermissionEvaluator(), referenceData, consents, new RelationshipResolver(), executor, CLOCK, PROPERTIES);

    // ------------------------------------------------------------------------------- fixtures

    static ReferenceData reference() {
        Map<String, FamilyRelationship> relationships = new LinkedHashMap<>();
        relationships.put("01", FamilyRelationship.SUBSCRIBER);
        relationships.put("02", FamilyRelationship.SPOUSE);
        relationships.put("03", FamilyRelationship.CHILD);
        relationships.put("04", FamilyRelationship.EX_SPOUSE);
        Map<Integer, String> actions = new LinkedHashMap<>();
        actions.put(1, "View");
        actions.put(2, "Edit");
        actions.put(3, "Download");
        actions.put(4, "Delete");
        return new ReferenceData(relationships, actions);
    }

    static FamilyMember family(String id, String code, Integer age) {
        return new FamilyMember(id, "Family " + id, code, null, age);
    }

    static FamilyMember familyBorn(String id, String code, LocalDate dateOfBirth) {
        return new FamilyMember(id, "Family " + id, code, dateOfBirth, null);
    }

    static MemberDomainMember member(String company, String relationshipCode, Integer age, LocalDate dateOfBirth,
                                     Map<String, Object> attributes, FamilyMember... family) {
        return new MemberDomainMember(MEMBER, "Alexa", "Miller", "Alexa M Miller", "HMO Blue", relationshipCode, company, "M",
                dateOfBirth, age, true, "****1234", "ACTIVE", attributes, List.of(family));
    }

    static MemberDomainMember subscriber(FamilyMember... family) {
        return member("HPHC", "01", 42, null, Map.of(), family);
    }

    static MemberProfileRequest request(boolean impersonating, boolean explain) {
        return new MemberProfileRequest(MEMBER, impersonating, explain);
    }

    static MemberProfileRequest request() {
        return request(false, false);
    }

    /** A permission row for the Subscriber actor; the actor column is irrelevant once the repository has filtered. */
    static PermissionRule rule(long id, String key, ViewingRelationship viewing, Integer min, Integer max, List<Integer> codes,
                               String status, boolean consent, boolean masked) {
        return new PermissionRule(id, PermissionRule.familyOf(key), key, ActorRelationship.SUBSCRIBER, viewing, min, max,
                codes, status, consent, masked);
    }

    static PermissionRule grant(long id, String key, ViewingRelationship viewing, Integer min, Integer max, Integer... codes) {
        return rule(id, key, viewing, min, max, List.of(codes), "FULL_ACCESS", false, false);
    }

    void stubMember(MemberDomainMember member) {
        when(memberDomain.findMember(MEMBER)).thenReturn(Optional.of(member));
    }

    @SuppressWarnings("unchecked")
    Set<ViewingRelationship> viewingsPassedToPermissionQuery() {
        ArgumentCaptor<Collection<ViewingRelationship>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(permissionRules).activeRulesFor(any(), captor.capture());
        return Set.copyOf(captor.getValue());
    }

    ActorRelationship actorPassedToPermissionQuery() {
        ArgumentCaptor<ActorRelationship> captor = ArgumentCaptor.forClass(ActorRelationship.class);
        verify(permissionRules).activeRulesFor(captor.capture(), any());
        return captor.getValue();
    }

    @BeforeEach
    void defaults() {
        when(referenceData.load()).thenReturn(reference());
        when(consents.activeConsentsFor(anyString())).thenReturn(Set.of());
        when(segmentRules.activeRulesFor(any(Company.class))).thenReturn(List.of());
        when(permissionRules.activeRulesFor(any(), any())).thenReturn(List.of());
    }

    // ------------------------------------------------------------------------------- calls per request

    @Nested
    class CallsPerRequest {

        @Test
        void exactlyOneMemberDomainCallAndOneCallPerRepository() {
            stubMember(subscriber(family("HP0000002", "03", 7), family("HP0000003", "02", 40)));

            service.profile(request());

            verify(memberDomain, times(1)).findMember(MEMBER);
            verify(consents, times(1)).activeConsentsFor(MEMBER);
            verify(referenceData, times(1)).load();
            verify(segmentRules, times(1)).activeRulesFor(Company.HPHC);
            verify(permissionRules, times(1)).activeRulesFor(eq(ActorRelationship.SUBSCRIBER), any());
            verifyNoMoreInteractions(memberDomain, consents, referenceData, segmentRules, permissionRules);
        }

        @Test
        void aSecondRequestReadsEverythingAgainNothingIsCached() {
            stubMember(subscriber());

            service.profile(request());
            service.profile(request());

            verify(memberDomain, times(2)).findMember(MEMBER);
            verify(consents, times(2)).activeConsentsFor(MEMBER);
            verify(referenceData, times(2)).load();
            verify(segmentRules, times(2)).activeRulesFor(Company.HPHC);
            verify(permissionRules, times(2)).activeRulesFor(any(), any());
        }

        @Test
        void consentsAndReferenceDataAreIssuedWithTheMemberDomainCallEvenWhenTheMemberIsUnknown() {
            when(memberDomain.findMember(MEMBER)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.profile(request()))
                    .isInstanceOf(MemberProfileException.class)
                    .extracting(e -> ((MemberProfileException) e).getCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);

            verify(consents).activeConsentsFor(MEMBER);
            verify(referenceData).load();
            verify(segmentRules, never()).activeRulesFor(any(Company.class));
            verify(permissionRules, never()).activeRulesFor(any(), any());
        }

        @Test
        void unknownMemberIs404WithTheIdInTheMessage() {
            when(memberDomain.findMember(MEMBER)).thenReturn(Optional.empty());

            MemberProfileException e = (MemberProfileException) catchThrowable(() -> service.profile(request()));

            assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_NOT_FOUND);
            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER)).doesNotContain(MEMBER);
        }
    }

    // ------------------------------------------------------------------------------- company

    @Nested
    class CompanyResolution {

        @ParameterizedTest(name = "memberTypeCode ''{0}'' -> {1}")
        @CsvSource({"HPHC, HPHC", "THP, THP", "hphc, HPHC", "thp, THP", "' THP ', THP", "'  hphc', HPHC", "'Thp   ', THP"})
        void companyIsTheUpperCasedTrimmedMemberTypeCode(String memberTypeCode, Company company) {
            stubMember(member(memberTypeCode, "01", 42, null, Map.of()));

            service.profile(request());

            verify(segmentRules).activeRulesFor(company);
        }

        @ParameterizedTest(name = "memberTypeCode ''{0}''")
        @NullAndEmptySource
        @ValueSource(strings = {" ", "\t"})
        void missingMemberTypeCodeIs422MemberDataIncomplete(String memberTypeCode) {
            stubMember(member(memberTypeCode, "01", 42, null, Map.of()));

            MemberProfileException e = (MemberProfileException) catchThrowable(() -> service.profile(request()));

            assertThat(e).isNotNull();
            assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_DATA_INCOMPLETE);
            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER)).contains("memberTypeCode");
            verify(segmentRules, never()).activeRulesFor(any(Company.class));
            verify(permissionRules, never()).activeRulesFor(any(), any());
        }

        @Test
        void theResponseEchoesTheMemberTypeCodeAsMemberDomainSentIt() {
            stubMember(member("THP", "01", 42, null, Map.of()));

            assertThat(service.profile(request()).member().memberTypeCode()).isEqualTo("THP");
        }
    }

    // ------------------------------------------------------------------------------- age

    @Nested
    class Age {

        @Test
        void ageIsTakenFromAgeWhenPresent() {
            stubMember(member("HPHC", "01", 42, null, Map.of()));

            assertThat(service.profile(request()).member().age()).isEqualTo(42);
        }

        @Test
        void ageWinsOverDateOfBirthWhenBothArePresent() {
            stubMember(member("HPHC", "01", 42, LocalDate.of(2000, 1, 1), Map.of()));

            assertThat(service.profile(request()).member().age()).isEqualTo(42);
        }

        @Test
        void ageIsFullYearsFromDateOfBirthOnTheClock() {
            stubMember(member("HPHC", "01", null, LocalDate.of(1984, 3, 15), Map.of()));

            assertThat(service.profile(request()).member().age()).isEqualTo(42);
        }

        @Test
        void birthdayTodayCountsTheNewYear() {
            stubMember(member("HPHC", "01", null, TODAY.minusYears(42), Map.of()));

            assertThat(service.profile(request()).member().age()).isEqualTo(42);
        }

        @Test
        void birthdayTomorrowDoesNotCountTheNewYearYet() {
            stubMember(member("HPHC", "01", null, TODAY.minusYears(42).plusDays(1), Map.of()));

            assertThat(service.profile(request()).member().age()).isEqualTo(41);
        }

        @Test
        void birthdayYesterdayCountsTheNewYear() {
            stubMember(member("HPHC", "01", null, TODAY.minusYears(42).minusDays(1), Map.of()));

            assertThat(service.profile(request()).member().age()).isEqualTo(42);
        }

        @Test
        void bornTodayIsZero() {
            stubMember(member("HPHC", "01", null, TODAY, Map.of()));

            assertThat(service.profile(request()).member().age()).isZero();
        }

        @Test
        void neitherAgeNorDateOfBirthIs422MemberDataIncomplete() {
            stubMember(member("HPHC", "01", null, null, Map.of()));

            MemberProfileException e = (MemberProfileException) catchThrowable(() -> service.profile(request()));

            assertThat(e).isNotNull();
            assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_DATA_INCOMPLETE);
            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER)).contains("age").contains("dateOfBirth");
        }

        @Test
        void familyMemberAgeComesFromAgeOrDateOfBirthWithTheSameClock() {
            stubMember(subscriber(family("HP0000002", "03", 7), familyBorn("HP0000003", "03", TODAY.minusYears(15).plusDays(1)),
                    familyBorn("HP0000004", "03", TODAY.minusYears(18))));

            List<MemberProfileResponse.FamilyPermission> family = service.profile(request()).member().familyPermissions();

            assertThat(family).extracting(MemberProfileResponse.FamilyPermission::age).containsExactly(7, 14, 18);
        }

        @Test
        void familyMemberWithoutAgeOrDateOfBirthIs422NamingThatMember() {
            stubMember(subscriber(family("HP0000002", "03", null)));

            MemberProfileException e = (MemberProfileException) catchThrowable(() -> service.profile(request()));

            assertThat(e).isNotNull();
            assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_DATA_INCOMPLETE);
            assertThat(e.getMessage()).contains(MemberIds.forLog("HP0000002")).doesNotContain("HP0000002");
        }
    }

    // ------------------------------------------------------------------------------- actor relationship

    @Nested
    class ActorDerivation {

        @ParameterizedTest(name = "code {0}, age {1} -> {2}")
        @CsvSource({
                "01, 42, SUBSCRIBER", "01, 5, SUBSCRIBER",
                "02, 40, SPOUSE", "02, 17, SPOUSE",
                "04, 50, EX_SPOUSE", "04, 16, EX_SPOUSE",
                "03, 18, ADULT_CHILD", "03, 30, ADULT_CHILD",
                "03, 17, CHILD_TEENAGER", "03, 13, CHILD_TEENAGER",
                "03, 12, CHILD_MINOR", "03, 0, CHILD_MINOR"})
        void actorRelationshipFromCodeAndAgeDrivesThePermissionQuery(String code, int age, ActorRelationship expected) {
            stubMember(member("HPHC", code, age, null, Map.of()));

            service.profile(request());

            assertThat(actorPassedToPermissionQuery()).isEqualTo(expected);
        }

        @Test
        void actorAgeFromDateOfBirthCrossesTheAdultBoundaryOnTheBirthday() {
            stubMember(member("HPHC", "03", null, TODAY.minusYears(18), Map.of()));
            service.profile(request());
            assertThat(actorPassedToPermissionQuery()).isEqualTo(ActorRelationship.ADULT_CHILD);
        }

        @Test
        void actorAgeFromDateOfBirthTheDayBeforeTheEighteenthBirthdayIsATeenager() {
            stubMember(member("HPHC", "03", null, TODAY.minusYears(18).plusDays(1), Map.of()));
            service.profile(request());
            assertThat(actorPassedToPermissionQuery()).isEqualTo(ActorRelationship.CHILD_TEENAGER);
        }

        @ParameterizedTest(name = "relationshipCode ''{0}''")
        @NullAndEmptySource
        @ValueSource(strings = {"99", "1", "Subscriber", "0 1"})
        void unknownActorRelationshipCodeIs422MemberDataIncomplete(String code) {
            stubMember(member("HPHC", code, 42, null, Map.of()));

            MemberProfileException e = (MemberProfileException) catchThrowable(() -> service.profile(request()));

            assertThat(e).isNotNull();
            assertThat(e.getCode()).isEqualTo(ErrorCode.MEMBER_DATA_INCOMPLETE);
            assertThat(e.getMessage()).contains(MemberIds.forLog(MEMBER)).contains("relationship code");
            verify(permissionRules, never()).activeRulesFor(any(), any());
        }

        @Test
        void explainReportsTheActorRelationshipLabel() {
            stubMember(member("HPHC", "03", 15, null, Map.of()));

            assertThat(service.profile(request(false, true)).explain().actorRelationship()).isEqualTo("Child (teenager)");
        }
    }

    // ------------------------------------------------------------------------------- viewing set

    @Nested
    class ViewingSet {

        @Test
        void selfIsAlwaysQueriedEvenWithoutFamily() {
            stubMember(subscriber());

            service.profile(request());

            assertThat(viewingsPassedToPermissionQuery()).containsExactly(SELF);
        }

        @Test
        void everyFamilyMembersViewingRelationshipIsQueriedOnceWithSelf() {
            stubMember(subscriber(family("HP0000002", "02", 40), family("HP0000003", "03", 7), family("HP0000004", "03", 16),
                    family("HP0000005", "03", 18), family("HP0000006", "04", 45)));

            service.profile(request());

            assertThat(viewingsPassedToPermissionQuery()).containsExactlyInAnyOrder(SELF, SPOUSE, CHILD, ADULT_DEPENDENT);
        }

        @Test
        void aSubscriberOnTheRosterIsQueriedAsSubscriber() {
            stubMember(member("HPHC", "02", 40, null, Map.of(), family("HP0000002", "01", 44)));

            service.profile(request());

            assertThat(viewingsPassedToPermissionQuery()).containsExactlyInAnyOrder(SELF, SUBSCRIBER);
        }

        @ParameterizedTest(name = "code {0}, age {1} -> {2}")
        @CsvSource({
                "01, 44, SUBSCRIBER", "02, 40, SPOUSE",
                "03, 0, CHILD", "03, 12, CHILD", "03, 13, CHILD", "03, 17, CHILD", "03, 18, ADULT_DEPENDENT", "03, 40, ADULT_DEPENDENT",
                "04, 45, ADULT_DEPENDENT", "04, 17, ALL_OTHER",
                "99, 30, ADULT_DEPENDENT", "99, 18, ADULT_DEPENDENT", "99, 17, ALL_OTHER", "99, 5, ALL_OTHER"})
        void familyMemberViewingRelationshipFromCodeAndAge(String code, int age, ViewingRelationship expected) {
            stubMember(subscriber(family("HP0000002", code, age)));

            service.profile(request());

            assertThat(viewingsPassedToPermissionQuery()).containsExactlyInAnyOrder(SELF, expected);
        }

        @Test
        void unknownFamilyRelationshipCodeDoesNotFailTheRequest() {
            stubMember(subscriber(family("HP0000002", "99", 10), family("HP0000003", null, 30)));

            MemberProfileResponse response = service.profile(request());

            assertThat(response.member().familyPermissions()).extracting(MemberProfileResponse.FamilyPermission::memberId)
                    .containsExactly("HP0000002", "HP0000003");
            assertThat(response.member().familyPermissions().get(0).relationshipCode()).isEqualTo("99");
            assertThat(response.member().familyPermissions().get(1).relationshipCode()).isNull();
            assertThat(viewingsPassedToPermissionQuery()).containsExactlyInAnyOrder(SELF, ALL_OTHER, ADULT_DEPENDENT);
        }

        @Test
        void anUnknownCodeMinorGetsTheCatchAllRowsTheRepositoryReturns() {
            stubMember(subscriber(family("HP0000002", "99", 10)));
            when(permissionRules.activeRulesFor(any(), any())).thenReturn(List.of(
                    grant(1, "benefits.idCard", SELF, null, null, 1, 3),
                    grant(2, "benefits.coverage", ALL_OTHER, null, null, 1),
                    rule(3, "claims.claim", ALL_OTHER, null, null, List.of(), "NO_ACCESS", false, false)));

            MemberProfileResponse.FamilyPermission other = service.profile(request()).member().familyPermissions().get(0);

            assertThat(other.permissions()).containsExactly(Map.entry("benefits", List.of(1)), Map.entry("benefits.coverage", List.of(1)));
        }
    }

    // ------------------------------------------------------------------------------- roster

    @Nested
    class Roster {

        @Test
        void theRosterEntryThatIsTheMemberIsSkipped() {
            stubMember(subscriber(family(MEMBER, "01", null), family("HP0000002", "03", 7)));

            MemberProfileResponse response = service.profile(request());

            assertThat(response.member().familyPermissions()).extracting(MemberProfileResponse.FamilyPermission::memberId)
                    .containsExactly("HP0000002");
            assertThat(viewingsPassedToPermissionQuery()).containsExactlyInAnyOrder(SELF, CHILD);
        }

        @Test
        void theSkippedSelfEntryIsNotAgeCheckedOrRelationshipChecked() {
            // null age, null date of birth and an unknown code on the self entry must not raise 422
            stubMember(subscriber(new FamilyMember(MEMBER, "Alexa M Miller", "zz", null, null)));

            MemberProfileResponse response = service.profile(request());

            assertThat(response.member().familyPermissions()).isEmpty();
            assertThat(viewingsPassedToPermissionQuery()).containsExactly(SELF);
        }

        @Test
        void familyPermissionsKeepRosterOrderAndEchoIdentity() {
            stubMember(subscriber(family("HP0000009", "03", 7), family("HP0000002", "02", 40), family("HP0000005", "04", 50)));

            List<MemberProfileResponse.FamilyPermission> family = service.profile(request()).member().familyPermissions();

            assertThat(family).extracting(MemberProfileResponse.FamilyPermission::memberId).containsExactly("HP0000009", "HP0000002", "HP0000005");
            assertThat(family).extracting(MemberProfileResponse.FamilyPermission::fullName).containsExactly("Family HP0000009", "Family HP0000002", "Family HP0000005");
            assertThat(family).extracting(MemberProfileResponse.FamilyPermission::relationshipCode).containsExactly("03", "02", "04");
            assertThat(family).extracting(MemberProfileResponse.FamilyPermission::age).containsExactly(7, 40, 50);
        }

        @Test
        void anEmptyRosterGivesAnEmptyFamilyPermissionsList() {
            stubMember(subscriber());

            assertThat(service.profile(request()).member().familyPermissions()).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------- permissions

    @Nested
    class Permissions {

        final List<PermissionRule> subscriberRules = List.of(
                grant(1, "benefits.coverage", SELF, null, null, 1),
                grant(2, "benefits.idCard", SELF, null, null, 1, 3),
                grant(3, "claims.claim", SELF, null, null, 1, 3),
                grant(4, "benefits.coverage", CHILD, 0, 12, 1),
                grant(5, "benefits.idCard", CHILD, 0, 12, 1, 3),
                grant(6, "claims.claim", CHILD, 0, 12, 1, 3),
                grant(7, "benefits.coverage", CHILD, 13, 17, 1),
                grant(8, "benefits.idCard", CHILD, 13, 17, 1, 3),
                rule(9, "claims.claim", CHILD, 13, 17, List.of(1), "MASKED_ACCESS", true, true),
                rule(10, "claims.authorization", CHILD, 13, 17, List.of(1), "CONSENT_REQUIRED", true, false),
                grant(11, "benefits.coverage", ADULT_DEPENDENT, 18, null, 1),
                grant(12, "benefits.idCard", ADULT_DEPENDENT, 18, null, 1),
                rule(13, "claims.claim", ALL_OTHER, null, null, List.of(), "NO_ACCESS", false, false));

        @BeforeEach
        void rules() {
            when(permissionRules.activeRulesFor(any(), any())).thenReturn(subscriberRules);
        }

        @Test
        void selfPermissionsUseViewingSelfWithTheActorsOwnAge() {
            stubMember(subscriber());

            Map<String, List<Integer>> self = service.profile(request()).member().permissions();

            assertThat(self).containsExactly(
                    Map.entry("benefits", List.of(1)), Map.entry("benefits.coverage", List.of(1)), Map.entry("benefits.idCard", List.of(1, 3)),
                    Map.entry("claims", List.of(1)), Map.entry("claims.claim", List.of(1, 3)));
        }

        @Test
        void selfRowsOutsideTheActorsAgeBandDoNotApply() {
            when(permissionRules.activeRulesFor(any(), any())).thenReturn(List.of(
                    grant(1, "benefits.coverage", SELF, 0, 12, 1), grant(2, "benefits.idCard", SELF, 18, null, 1)));
            stubMember(subscriber());

            assertThat(service.profile(request()).member().permissions()).containsOnlyKeys("benefits", "benefits.idCard");
        }

        @Test
        void youngChildGetsTheZeroToTwelveRows() {
            stubMember(subscriber(family("HP0000002", "03", 7)));

            MemberProfileResponse.FamilyPermission child = service.profile(request()).member().familyPermissions().get(0);

            assertThat(child.permissions()).containsExactly(
                    Map.entry("benefits", List.of(1)), Map.entry("benefits.coverage", List.of(1)), Map.entry("benefits.idCard", List.of(1, 3)),
                    Map.entry("claims", List.of(1)), Map.entry("claims.claim", List.of(1, 3)));
            assertThat(child.consentRequired()).isEmpty();
            assertThat(child.masked()).isEmpty();
        }

        @Test
        void teenWithoutConsentListsTheClaimsKeysUnderConsentRequired() {
            stubMember(subscriber(family("HP0000002", "03", 15)));

            MemberProfileResponse.FamilyPermission teen = service.profile(request()).member().familyPermissions().get(0);

            assertThat(teen.permissions()).containsOnlyKeys("benefits", "benefits.coverage", "benefits.idCard");
            assertThat(teen.consentRequired()).containsExactly("claims.authorization", "claims.claim");
            assertThat(teen.masked()).containsExactly("claims.claim");
        }

        @Test
        void consentsOnFileArePassedThroughAndUnlockTheConsentRows() {
            when(consents.activeConsentsFor(MEMBER)).thenReturn(Set.of(ConsentRepository.consentKey("HP0000002", "claims")));
            stubMember(subscriber(family("HP0000002", "03", 15), family("HP0000003", "03", 16)));

            List<MemberProfileResponse.FamilyPermission> family = service.profile(request()).member().familyPermissions();

            MemberProfileResponse.FamilyPermission consented = family.get(0);
            assertThat(consented.permissions()).containsExactly(
                    Map.entry("benefits", List.of(1)), Map.entry("benefits.coverage", List.of(1)), Map.entry("benefits.idCard", List.of(1, 3)),
                    Map.entry("claims", List.of(1)), Map.entry("claims.authorization", List.of(1)), Map.entry("claims.claim", List.of(1)));
            assertThat(consented.consentRequired()).isEmpty();
            assertThat(consented.masked()).containsExactly("claims.claim");

            MemberProfileResponse.FamilyPermission notConsented = family.get(1);
            assertThat(notConsented.permissions()).containsOnlyKeys("benefits", "benefits.coverage", "benefits.idCard");
            assertThat(notConsented.consentRequired()).containsExactly("claims.authorization", "claims.claim");
            assertThat(notConsented.masked()).containsExactly("claims.claim");
        }

        @Test
        void consentForAnotherFamilyDoesNotUnlockClaims() {
            when(consents.activeConsentsFor(MEMBER)).thenReturn(Set.of(ConsentRepository.consentKey("HP0000002", "benefits")));
            stubMember(subscriber(family("HP0000002", "03", 15)));

            MemberProfileResponse.FamilyPermission teen = service.profile(request()).member().familyPermissions().get(0);

            assertThat(teen.consentRequired()).containsExactly("claims.authorization", "claims.claim");
        }

        @Test
        void adultDependentGetsTheEighteenPlusRowsAndTheCatchAllForClaims() {
            stubMember(subscriber(family("HP0000002", "03", 19), family("HP0000003", "04", 45)));

            List<MemberProfileResponse.FamilyPermission> family = service.profile(request()).member().familyPermissions();

            for (MemberProfileResponse.FamilyPermission adult : family) {
                assertThat(adult.permissions()).containsExactly(
                        Map.entry("benefits", List.of(1)), Map.entry("benefits.coverage", List.of(1)), Map.entry("benefits.idCard", List.of(1)));
            }
        }

        @Test
        void actionCodeDescriptionsComeFromTheReferenceData() {
            stubMember(subscriber());

            assertThat(service.profile(request()).actionCodeDescriptions())
                    .containsExactly(Map.entry(1, "View"), Map.entry(2, "Edit"), Map.entry(3, "Download"), Map.entry(4, "Delete"));
        }
    }

    // ------------------------------------------------------------------------------- segmentation

    @Nested
    class Segmentation {

        final List<SegmentRule> hphcOnlineBillPay = List.of(
                SegmentRule.of(1, Segment.ONLINE_BILL_PAY, "HPHC", 1, 1, "dependentType", ComparisonOperator.EQUALS, "01"),
                SegmentRule.of(2, Segment.ONLINE_BILL_PAY, "HPHC", 1, 2, "memberCategory", ComparisonOperator.EQUALS, "B2I"),
                SegmentRule.of(3, Segment.ONLINE_BILL_PAY, "HPHC", 1, 3, "customerCategory", ComparisonOperator.NOT_EQUALS, "NH_39_WEEK"),
                SegmentRule.of(4, Segment.OPTUM_RX_COVERAGE, "HPHC", 1, 1, "basicMedicalDrugCoverageIndicator", ComparisonOperator.IS_TRUE, null));

        @Test
        void segmentationIsEvaluatedOnTheMembersAttributes() {
            when(segmentRules.activeRulesFor(Company.HPHC)).thenReturn(hphcOnlineBillPay);
            stubMember(member("HPHC", "01", 42, null, Map.of("dependentType", "01", "memberCategory", "b2i",
                    "customerCategory", "GROUP", "basicMedicalDrugCoverageIndicator", "N")));

            Map<String, Boolean> flags = service.profile(request()).member().segmentation();

            assertThat(flags.keySet()).containsExactly("onlineBillPay", "optumRxCoverage", "allPublicPlansMa",
                    "allTuftsMedicarePreferred", "tmpOtcMa", "planOfCare", "interoperability");
            assertThat(flags.get("onlineBillPay")).isTrue();
            assertThat(flags.get("optumRxCoverage")).isFalse();
            assertThat(flags.values()).filteredOn(v -> v).hasSize(1);
        }

        @Test
        void aFailingConditionTurnsTheSegmentOff() {
            when(segmentRules.activeRulesFor(Company.HPHC)).thenReturn(hphcOnlineBillPay);
            stubMember(member("HPHC", "01", 42, null, Map.of("dependentType", "01", "memberCategory", "B2I",
                    "customerCategory", "NH_39_WEEK", "basicMedicalDrugCoverageIndicator", true)));

            Map<String, Boolean> flags = service.profile(request()).member().segmentation();

            assertThat(flags.get("onlineBillPay")).isFalse();
            assertThat(flags.get("optumRxCoverage")).isTrue();
        }

        @Test
        void noRulesForTheCompanyMeansAllSevenFlagsFalse() {
            stubMember(member("THP", "01", 42, null, Map.of("sourceSystemId", 2001)));

            Map<String, Boolean> flags = service.profile(request()).member().segmentation();

            assertThat(flags).hasSize(7);
            assertThat(flags.values()).containsOnly(false);
        }

        @Test
        void noAttributesAtAllStillGivesSevenFalseFlags() {
            when(segmentRules.activeRulesFor(Company.HPHC)).thenReturn(hphcOnlineBillPay);
            stubMember(new MemberDomainMember(MEMBER, "A", "M", "A M", null, "01", "HPHC", "M", null, 42, null, null, null, null, null));

            Map<String, Boolean> flags = service.profile(request()).member().segmentation();

            assertThat(flags).hasSize(7);
            assertThat(flags.values()).containsOnly(false);
        }
    }

    // ------------------------------------------------------------------------------- response identity

    @Nested
    class ResponseIdentity {

        @Test
        void identityFieldsArePassedThroughFromMemberDomain() {
            stubMember(member("HPHC", "01", 42, null, Map.of()));

            MemberProfileResponse.Member out = service.profile(request()).member();

            assertThat(out.memberId()).isEqualTo(MEMBER);
            assertThat(out.fullName()).isEqualTo("Alexa M Miller");
            assertThat(out.firstName()).isEqualTo("Alexa");
            assertThat(out.lastName()).isEqualTo("Miller");
            assertThat(out.planName()).isEqualTo("HMO Blue");
            assertThat(out.relationshipCode()).isEqualTo("01");
            assertThat(out.memberTypeCode()).isEqualTo("HPHC");
            assertThat(out.userTypeCode()).isEqualTo("M");
            assertThat(out.age()).isEqualTo(42);
            assertThat(out.activePolicy()).isTrue();
            assertThat(out.accountNumber()).isEqualTo("****1234");
            assertThat(out.policyStatus()).isEqualTo("ACTIVE");
        }

        @Test
        void impersonatingTrueIsEchoed() {
            stubMember(subscriber());
            assertThat(service.profile(request(true, false)).member().isImpersonating()).isTrue();
        }

        @Test
        void impersonatingFalseIsEchoed() {
            stubMember(subscriber());
            assertThat(service.profile(request(false, false)).member().isImpersonating()).isFalse();
        }

        @Test
        void impersonatingDoesNotChangeTheEvaluation() {
            stubMember(subscriber(family("HP0000002", "03", 7)));

            MemberProfileResponse plain = service.profile(request(false, false));
            MemberProfileResponse impersonated = service.profile(request(true, false));

            assertThat(impersonated.member().permissions()).isEqualTo(plain.member().permissions());
            assertThat(impersonated.member().familyPermissions()).isEqualTo(plain.member().familyPermissions());
            assertThat(impersonated.member().segmentation()).isEqualTo(plain.member().segmentation());
        }
    }

    // ------------------------------------------------------------------------------- explain

    @Nested
    class Explain {

        @Test
        void explainIsNullUnlessRequested() {
            stubMember(subscriber(family("HP0000002", "03", 7)));

            assertThat(service.profile(request(false, false)).explain()).isNull();
            assertThat(service.profile(request(true, false)).explain()).isNull();
        }

        @Test
        void explainCarriesActorFactsSegmentationTracePermissionTracePerMemberAndTimings() {
            Map<String, Object> attributes = Map.of("dependentType", "01", "memberCategory", "B2I", "customerCategory", "GROUP");
            when(segmentRules.activeRulesFor(Company.HPHC)).thenReturn(List.of(
                    SegmentRule.of(1, Segment.ONLINE_BILL_PAY, "HPHC", 1, 1, "dependentType", ComparisonOperator.EQUALS, "01"),
                    SegmentRule.of(2, Segment.ONLINE_BILL_PAY, "HPHC", 1, 2, "memberCategory", ComparisonOperator.EQUALS, "B2I"),
                    SegmentRule.of(3, Segment.ONLINE_BILL_PAY, "HPHC", 1, 3, "customerCategory", ComparisonOperator.NOT_EQUALS, "NH_39_WEEK"),
                    SegmentRule.of(4, Segment.OPTUM_RX_COVERAGE, "HPHC", 1, 1, "basicMedicalDrugCoverageIndicator", ComparisonOperator.IS_TRUE, null)));
            when(permissionRules.activeRulesFor(any(), any())).thenReturn(List.of(
                    grant(1, "benefits.idCard", SELF, null, null, 1, 3),
                    grant(2, "benefits.idCard", CHILD, 0, 12, 1, 3),
                    rule(3, "claims.claim", CHILD, 13, 17, List.of(1), "MASKED_ACCESS", true, true)));
            stubMember(member("HPHC", "01", 42, null, attributes, family("HP0000002", "03", 7), family("HP0000003", "03", 15)));

            MemberProfileResponse.Explain explain = service.profile(request(false, true)).explain();

            assertThat(explain).isNotNull();
            assertThat(explain.actorRelationship()).isEqualTo("Subscriber");
            assertThat(explain.memberFacts()).isEqualTo(attributes);

            assertThat(explain.segmentation()).hasSize(2);
            assertThat(explain.segmentation().get(0).segment()).isEqualTo("onlineBillPay");
            assertThat(explain.segmentation().get(0).matched()).isTrue();
            assertThat(explain.segmentation().get(0).conditions()).hasSize(3);
            assertThat(explain.segmentation().get(0).conditions()).allSatisfy(c -> assertThat(c.passed()).isTrue());
            assertThat(explain.segmentation().get(1).segment()).isEqualTo("optumRxCoverage");
            assertThat(explain.segmentation().get(1).matched()).isFalse();
            assertThat(explain.segmentation().get(1).conditions().get(0).actualValue()).isNull();

            assertThat(explain.permissions().keySet()).containsExactly(MEMBER, "HP0000002", "HP0000003");
            assertThat(explain.permissions().get(MEMBER)).singleElement().satisfies(t -> {
                assertThat(t.ruleId()).isEqualTo(1);
                assertThat(t.viewingRelationship()).isEqualTo("Self");
                assertThat(t.outcome()).startsWith("granted");
            });
            assertThat(explain.permissions().get("HP0000002")).extracting(t -> t.ruleId()).containsExactly(2L);
            assertThat(explain.permissions().get("HP0000003")).singleElement().satisfies(t -> {
                assertThat(t.ruleId()).isEqualTo(3);
                assertThat(t.accessStatus()).isEqualTo("MASKED_ACCESS");
                assertThat(t.outcome()).isEqualTo("consent required, not on file");
            });

            assertThat(explain.timings()).isNotNull();
            assertThat(explain.timings().memberDomainMs()).isGreaterThanOrEqualTo(0);
            assertThat(explain.timings().rulesMs()).isGreaterThanOrEqualTo(0);
            assertThat(explain.timings().totalMs()).isGreaterThanOrEqualTo(explain.timings().memberDomainMs());
            assertThat(explain.timings().totalMs()).isGreaterThanOrEqualTo(explain.timings().rulesMs());
        }

        @Test
        void explainWithNoFamilyHasOnlyTheMembersOwnPermissionTrace() {
            stubMember(subscriber());

            MemberProfileResponse.Explain explain = service.profile(request(false, true)).explain();

            assertThat(explain.permissions()).containsOnlyKeys(MEMBER);
            assertThat(explain.permissions().get(MEMBER)).isEmpty();
            assertThat(explain.segmentation()).isEmpty();
        }

        @Test
        void explainDoesNotChangeTheFlagsOrPermissions() {
            when(segmentRules.activeRulesFor(Company.HPHC)).thenReturn(List.of(
                    SegmentRule.of(1, Segment.ONLINE_BILL_PAY, "HPHC", 1, 1, "dependentType", ComparisonOperator.EQUALS, "01"),
                    SegmentRule.of(2, Segment.ONLINE_BILL_PAY, "HPHC", 2, 1, "dependentType", ComparisonOperator.EQUALS, "02")));
            when(permissionRules.activeRulesFor(any(), any())).thenReturn(List.of(
                    grant(1, "benefits.idCard", SELF, null, null, 1, 3), grant(2, "benefits.idCard", CHILD, 0, 12, 1)));
            stubMember(member("HPHC", "01", 42, null, Map.of("dependentType", "01"), family("HP0000002", "03", 7)));

            MemberProfileResponse plain = service.profile(request(false, false));
            MemberProfileResponse explained = service.profile(request(false, true));

            assertThat(explained.member()).isEqualTo(plain.member());
            assertThat(explained.actionCodeDescriptions()).isEqualTo(plain.actionCodeDescriptions());
            assertThat(plain.explain()).isNull();
            assertThat(explained.explain()).isNotNull();
        }
    }

    // ------------------------------------------------------------------------------- failures in tasks

    @Nested
    class FailuresInsideExecutorTasks {

        @Test
        void memberDomainFailureSurfacesAsTheOriginalMemberProfileException() {
            MemberProfileException unreachable = new MemberProfileException(ErrorCode.MEMBER_DOMAIN_UNREACHABLE, "timed out");
            when(memberDomain.findMember(MEMBER)).thenThrow(unreachable);

            Throwable thrown = catchThrowable(() -> service.profile(request()));

            assertThat(thrown).isSameAs(unreachable).isNotInstanceOf(CompletionException.class);
        }

        @Test
        void memberDomainErrorSurfacesWithItsCode() {
            when(memberDomain.findMember(MEMBER)).thenThrow(new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR, "502"));

            assertThatThrownBy(() -> service.profile(request()))
                    .isInstanceOf(MemberProfileException.class)
                    .satisfies(e -> assertThat(((MemberProfileException) e).getCode()).isEqualTo(ErrorCode.MEMBER_DOMAIN_ERROR));
        }

        @Test
        void consentRepositoryFailureSurfacesAsTheOriginalRuntimeException() {
            IllegalStateException db = new IllegalStateException("connection pool exhausted");
            when(consents.activeConsentsFor(MEMBER)).thenThrow(db);
            stubMember(subscriber());

            assertThat(catchThrowable(() -> service.profile(request()))).isSameAs(db);
        }

        @Test
        void referenceDataFailureSurfacesAsTheOriginalException() {
            MemberProfileException invalid = MemberProfileException.ruleDataInvalid("relationship_code '05' maps to unknown relationship 'Pet'");
            when(referenceData.load()).thenThrow(invalid);
            stubMember(subscriber());

            assertThat(catchThrowable(() -> service.profile(request()))).isSameAs(invalid);
        }

        @Test
        void segmentRuleFailureSurfacesAsTheOriginalException() {
            MemberProfileException invalid = MemberProfileException.ruleDataInvalid("segment_rule 9 has unknown comparison_operator 'LIKE'");
            when(segmentRules.activeRulesFor(Company.HPHC)).thenThrow(invalid);
            stubMember(subscriber());

            Throwable thrown = catchThrowable(() -> service.profile(request()));

            assertThat(thrown).isSameAs(invalid);
            assertThat(((MemberProfileException) thrown).getCode()).isEqualTo(ErrorCode.RULE_DATA_INVALID);
        }

        @Test
        void permissionRuleFailureSurfacesAsTheOriginalException() {
            MemberProfileException invalid = MemberProfileException.ruleDataInvalid("family_permission_rule 3 has unknown viewing_relationship 'Pet'");
            when(permissionRules.activeRulesFor(any(), any())).thenThrow(invalid);
            stubMember(subscriber());

            assertThat(catchThrowable(() -> service.profile(request()))).isSameAs(invalid);
        }

        @Test
        void aCheckedExceptionInsideATaskBecomesInternalErrorWithItAsCause() {
            IOException io = new IOException("socket closed");
            stubMember(subscriber());
            when(consents.activeConsentsFor(MEMBER)).thenAnswer(inv -> { throw sneaky(io); });

            Throwable thrown = catchThrowable(() -> service.profile(request()));

            assertThat(thrown).isInstanceOf(MemberProfileException.class).isNotInstanceOf(CompletionException.class);
            assertThat(((MemberProfileException) thrown).getCode()).isEqualTo(ErrorCode.INTERNAL_ERROR);
            assertThat(thrown.getMessage()).contains("socket closed");
            assertThat(thrown.getCause()).isSameAs(io);
        }

        @SuppressWarnings("unchecked")
        private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
            throw (E) t;
        }
    }

    // ------------------------------------------------------------------------------- explain gating

    @Nested
    class ExplainGating {

        @Test
        void explainIsRefusedWhenDisabledBeforeAnyCallIsMade() {
            MemberProfileProperties disabled = new MemberProfileProperties(PROPERTIES.memberDomain(), PROPERTIES.security(),
                    new MemberProfileProperties.Explain(false), PROPERTIES.http(), PROPERTIES.timeZone());
            MemberProfileService gated = new MemberProfileService(memberDomain, segmentRules, new SegmentationEvaluator(),
                    permissionRules, new PermissionEvaluator(), referenceData, consents, new RelationshipResolver(), executor, CLOCK, disabled);

            MemberProfileException e = catchThrowableOfType(MemberProfileException.class,
                    () -> gated.profile(new MemberProfileRequest(MEMBER, false, true)));

            assertThat(e.getCode()).isEqualTo(ErrorCode.EXPLAIN_DISABLED);
            verifyNoInteractions(memberDomain, consents, referenceData, segmentRules, permissionRules);
        }

        @Test
        void withoutExplainTheDisabledSettingChangesNothing() {
            MemberProfileProperties disabled = new MemberProfileProperties(PROPERTIES.memberDomain(), PROPERTIES.security(),
                    new MemberProfileProperties.Explain(false), PROPERTIES.http(), PROPERTIES.timeZone());
            MemberProfileService gated = new MemberProfileService(memberDomain, segmentRules, new SegmentationEvaluator(),
                    permissionRules, new PermissionEvaluator(), referenceData, consents, new RelationshipResolver(), executor, CLOCK, disabled);
            stubMember(subscriber());

            MemberProfileResponse response = gated.profile(new MemberProfileRequest(MEMBER, false, false));

            assertThat(response.explain()).isNull();
            assertThat(response.member().memberId()).isEqualTo(MEMBER);
        }
    }
}
