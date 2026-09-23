package org.point32health.memberprofile.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.point32health.memberprofile.config.AppConfig;
import org.point32health.memberprofile.permission.PermissionResult;
import org.point32health.memberprofile.segmentation.ComparisonOperator;
import org.point32health.memberprofile.segmentation.MemberFacts;
import org.point32health.memberprofile.segmentation.Segment;
import org.point32health.memberprofile.segmentation.SegmentationEvaluator;
import org.point32health.memberprofile.segmentation.SegmentationResult;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The exact JSON of {@link MemberProfileResponse}, the agreed payload of catalog section 1:
 * <ul>
 *   <li>{@code segmentation}, {@code permissions} and {@code familyPermissions} are nested under {@code member};</li>
 *   <li>the flag is spelled {@code isImpersonating};</li>
 *   <li>{@code segmentation} has the seven keys in contract order;</li>
 *   <li>{@code actionCodeDescriptions} keys are the action codes as strings;</li>
 *   <li>{@code explain} is absent unless requested; {@code consentRequired} and {@code masked} are absent when
 *       empty; null identity fields are absent with the service's Jackson configuration.</li>
 * </ul>
 * The exact-shape test uses a plain Jackson 3 {@code JsonMapper}; the null-handling tests use the mapper Boot
 * builds from {@code application.yaml}'s {@code spring.jackson.*} plus {@link AppConfig}'s customizer, so they
 * assert what the running service writes.
 */
class ResponseJsonShapeTest {

    static final JsonMapper PLAIN = JsonMapper.builder().build();

    static final String EXPECTED_FULL_PAYLOAD = "{"
            + "\"member\":{"
            + "\"memberId\":\"HP0000001\","
            + "\"fullName\":\"Alexa M Miller\","
            + "\"firstName\":\"Alexa\","
            + "\"lastName\":\"Miller\","
            + "\"planName\":\"HMO Blue\","
            + "\"relationshipCode\":\"01\","
            + "\"memberTypeCode\":\"HPHC\","
            + "\"userTypeCode\":\"M\","
            + "\"isImpersonating\":false,"
            + "\"age\":42,"
            + "\"activePolicy\":false,"
            + "\"accountNumber\":\"****1234\","
            + "\"policyStatus\":\"ACTIVE\","
            + "\"segmentation\":{\"onlineBillPay\":true,\"optumRxCoverage\":false,\"allPublicPlansMa\":false,"
            + "\"allTuftsMedicarePreferred\":false,\"tmpOtcMa\":false,\"planOfCare\":false,\"interoperability\":false},"
            + "\"permissions\":{\"benefits\":[1],\"benefits.idCard\":[1,3]},"
            + "\"familyPermissions\":[{"
            + "\"memberId\":\"HP0000002\","
            + "\"fullName\":\"Liam Miller\","
            + "\"relationshipCode\":\"03\","
            + "\"age\":7,"
            + "\"permissions\":{\"benefits\":[1],\"benefits.coverage\":[1],\"benefits.idCard\":[1,3]},"
            + "\"consentRequired\":[\"claims.claim\"],"
            + "\"masked\":[\"claims.claim\"]"
            + "}]"
            + "},"
            + "\"actionCodeDescriptions\":{\"1\":\"View\",\"2\":\"Edit\",\"3\":\"Download\",\"4\":\"Delete\"}"
            + "}";

    // ------------------------------------------------------------------------------- fixtures

    /** The seven flags as the real evaluator emits them: contract order, onlineBillPay true here. */
    static Map<String, Boolean> segmentation() {
        SegmentationResult result = new SegmentationEvaluator().evaluate(List.of(), MemberFacts.of(Map.of()), false);
        Map<String, Boolean> flags = new LinkedHashMap<>(result.flags());
        flags.put(Segment.ONLINE_BILL_PAY.key(), true);
        return flags;
    }

    static Map<Integer, String> actionCodes() {
        return new TreeMap<>(Map.of(1, "View", 2, "Edit", 3, "Download", 4, "Delete"));
    }

    static MemberProfileResponse.FamilyPermission child(List<String> consentRequired, List<String> masked) {
        return new MemberProfileResponse.FamilyPermission("HP0000002", "Liam Miller", "03", 7,
                new TreeMap<>(Map.of("benefits", List.of(1), "benefits.coverage", List.of(1), "benefits.idCard", List.of(1, 3))),
                consentRequired, masked);
    }

    static MemberProfileResponse.Member member(String planName, boolean impersonating, List<MemberProfileResponse.FamilyPermission> family) {
        return new MemberProfileResponse.Member("HP0000001", "Alexa M Miller", "Alexa", "Miller", planName, "01", "HPHC", "M",
                impersonating, 42, false, "****1234", "ACTIVE", segmentation(),
                new TreeMap<>(Map.of("benefits", List.of(1), "benefits.idCard", List.of(1, 3))), family);
    }

    static MemberProfileResponse fullyPopulated() {
        return new MemberProfileResponse(member("HMO Blue", false, List.of(child(List.of("claims.claim"), List.of("claims.claim")))),
                actionCodes(), null);
    }

    static MemberProfileResponse.Explain explain() {
        List<SegmentationResult.GroupTrace> groups = List.of(new SegmentationResult.GroupTrace("onlineBillPay", 1, true, List.of(
                new SegmentationResult.ConditionTrace(1, "dependentType", ComparisonOperator.EQUALS, "01", "01", true),
                new SegmentationResult.ConditionTrace(3, "customerCategory", ComparisonOperator.NOT_EQUALS, "NH_39_WEEK", "GROUP", true))));
        Map<String, List<PermissionResult.RuleTrace>> permissions = new LinkedHashMap<>();
        permissions.put("HP0000001", List.of(new PermissionResult.RuleTrace(10, "benefits.idCard", "Self", null, null, "FULL_ACCESS", "granted [1, 3]")));
        permissions.put("HP0000002", List.of(new PermissionResult.RuleTrace(12, "claims.claim", "Child", 13, 17, "MASKED_ACCESS", "consent required, not on file")));
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("dependentType", "01");
        facts.put("sourceSystemId", 2001);
        facts.put("coverageActive", true);
        return new MemberProfileResponse.Explain("Subscriber", facts, groups, permissions,
                new MemberProfileResponse.Timings(12, 3, 16));
    }

    // ------------------------------------------------------------------------------- exact shape

    @Nested
    class ExactShape {

        @Test
        void fullyPopulatedResponseSerializesToTheAgreedPayloadByteForByte() {
            assertThat(PLAIN.writeValueAsString(fullyPopulated())).isEqualTo(EXPECTED_FULL_PAYLOAD);
        }

        @Test
        void segmentationPermissionsAndFamilyPermissionsAreNestedUnderMember() {
            JsonNode root = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated()));

            assertThat(root.propertyNames()).containsExactly("member", "actionCodeDescriptions");
            assertThat(root.path("member").has("segmentation")).isTrue();
            assertThat(root.path("member").has("permissions")).isTrue();
            assertThat(root.path("member").has("familyPermissions")).isTrue();
            assertThat(root.has("segmentation")).isFalse();
            assertThat(root.has("permissions")).isFalse();
            assertThat(root.has("familyPermissions")).isFalse();
        }

        @Test
        void memberPropertiesFollowTheAgreedOrder() {
            JsonNode member = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated())).path("member");

            assertThat(member.propertyNames()).containsExactly("memberId", "fullName", "firstName", "lastName", "planName",
                    "relationshipCode", "memberTypeCode", "userTypeCode", "isImpersonating", "age", "activePolicy",
                    "accountNumber", "policyStatus", "segmentation", "permissions", "familyPermissions");
        }

        @Test
        void impersonatingFlagIsSpelledIsImpersonating() {
            JsonNode member = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated())).path("member");

            assertThat(member.has("isImpersonating")).isTrue();
            assertThat(member.has("impersonating")).isFalse();
            assertThat(member.path("isImpersonating").isBoolean()).isTrue();
            assertThat(member.path("isImpersonating").asBoolean()).isFalse();

            JsonNode impersonated = PLAIN.readTree(PLAIN.writeValueAsString(
                    new MemberProfileResponse(member("HMO Blue", true, List.of()), actionCodes(), null))).path("member");
            assertThat(impersonated.path("isImpersonating").asBoolean()).isTrue();
        }

        @Test
        void segmentationHasExactlyTheSevenKeysInContractOrderAsBooleans() {
            JsonNode segmentation = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated())).path("member").path("segmentation");

            assertThat(segmentation.propertyNames()).containsExactly("onlineBillPay", "optumRxCoverage", "allPublicPlansMa",
                    "allTuftsMedicarePreferred", "tmpOtcMa", "planOfCare", "interoperability");
            segmentation.properties().forEach(e -> assertThat(e.getValue().isBoolean()).as(e.getKey()).isTrue());
        }

        @Test
        void permissionsMapKeysToSortedActionCodeArrays() {
            JsonNode permissions = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated())).path("member").path("permissions");

            assertThat(permissions.propertyNames()).containsExactly("benefits", "benefits.idCard");
            assertThat(permissions.path("benefits").isArray()).isTrue();
            assertThat(permissions.path("benefits.idCard").toString()).isEqualTo("[1,3]");
        }

        @Test
        void actionCodeDescriptionKeysAreStrings() {
            JsonNode actions = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated())).path("actionCodeDescriptions");

            assertThat(actions.propertyNames()).containsExactly("1", "2", "3", "4");
            assertThat(actions.path("1").asString()).isEqualTo("View");
            assertThat(actions.path("4").asString()).isEqualTo("Delete");
            assertThat(PLAIN.writeValueAsString(fullyPopulated())).contains("\"actionCodeDescriptions\":{\"1\":\"View\"");
        }

        @Test
        void familyPermissionEntryCarriesIdentityAgeAndTheThreeLists() {
            JsonNode family = PLAIN.readTree(PLAIN.writeValueAsString(fullyPopulated())).path("member").path("familyPermissions");

            assertThat(family.isArray()).isTrue();
            assertThat(family.size()).isEqualTo(1);
            assertThat(family.get(0).propertyNames()).containsExactly("memberId", "fullName", "relationshipCode", "age",
                    "permissions", "consentRequired", "masked");
        }
    }

    // ------------------------------------------------------------------------------- absent fields

    @Nested
    class AbsentFields {

        @Test
        void explainIsAbsentWhenNull() {
            String json = PLAIN.writeValueAsString(fullyPopulated());
            assertThat(json).doesNotContain("explain");
            assertThat(PLAIN.readTree(json).has("explain")).isFalse();
        }

        @Test
        void emptyConsentRequiredAndMaskedAreAbsent() {
            MemberProfileResponse response = new MemberProfileResponse(
                    member("HMO Blue", false, List.of(child(List.of(), List.of()))), actionCodes(), null);
            JsonNode entry = PLAIN.readTree(PLAIN.writeValueAsString(response)).path("member").path("familyPermissions").get(0);

            assertThat(entry.propertyNames()).containsExactly("memberId", "fullName", "relationshipCode", "age", "permissions");
            assertThat(entry.has("consentRequired")).isFalse();
            assertThat(entry.has("masked")).isFalse();
        }

        @Test
        void nullConsentRequiredAndMaskedAreAbsentToo() {
            MemberProfileResponse response = new MemberProfileResponse(
                    member("HMO Blue", false, List.of(child(null, null))), actionCodes(), null);
            JsonNode entry = PLAIN.readTree(PLAIN.writeValueAsString(response)).path("member").path("familyPermissions").get(0);

            assertThat(entry.has("consentRequired")).isFalse();
            assertThat(entry.has("masked")).isFalse();
        }

        @Test
        void nonEmptyConsentRequiredOrMaskedIsPresent() {
            MemberProfileResponse response = new MemberProfileResponse(
                    member("HMO Blue", false, List.of(child(List.of("claims.authorization", "claims.claim"), List.of()))), actionCodes(), null);
            JsonNode entry = PLAIN.readTree(PLAIN.writeValueAsString(response)).path("member").path("familyPermissions").get(0);

            assertThat(entry.path("consentRequired").toString()).isEqualTo("[\"claims.authorization\",\"claims.claim\"]");
            assertThat(entry.has("masked")).isFalse();
        }

        /**
         * A family member with no allowed action at all (for example the Ex-Spouse viewing the Subscriber's profile
         * family only) still carries {@code "permissions": {}}; only consentRequired and masked are dropped when empty.
         */
        @Test
        void familyPermissionsWithNoActionAtAllStillCarryAnEmptyPermissionsMap() {
            MemberProfileResponse.FamilyPermission empty = new MemberProfileResponse.FamilyPermission(
                    "HP0000003", "Kim Miller", "03", 15, Map.of(), List.of(), List.of());
            MemberProfileResponse response = new MemberProfileResponse(member("HMO Blue", false, List.of(empty)), actionCodes(), null);
            JsonNode entry = PLAIN.readTree(PLAIN.writeValueAsString(response)).path("member").path("familyPermissions").get(0);

            assertThat(entry.propertyNames()).containsExactly("memberId", "fullName", "relationshipCode", "age", "permissions");
            assertThat(entry.path("permissions").isObject()).isTrue();
            assertThat(entry.path("permissions").isEmpty()).isTrue();
        }

        @Test
        void emptyFamilyPermissionsListIsStillWrittenOnTheMember() {
            MemberProfileResponse response = new MemberProfileResponse(member("HMO Blue", false, List.of()), actionCodes(), null);
            JsonNode member = PLAIN.readTree(PLAIN.writeValueAsString(response)).path("member");

            assertThat(member.has("familyPermissions")).isTrue();
            assertThat(member.path("familyPermissions").isArray()).isTrue();
            assertThat(member.path("familyPermissions").size()).isZero();
        }

        @Test
        void emptySelfPermissionsMapIsStillWrittenOnTheMember() {
            MemberProfileResponse.Member m = new MemberProfileResponse.Member("HP0000009", "Kid", "K", "Id", null, "03", "HPHC", "M",
                    false, 7, true, null, null, segmentation(), Map.of(), List.of());
            JsonNode member = PLAIN.readTree(PLAIN.writeValueAsString(new MemberProfileResponse(m, actionCodes(), null))).path("member");

            assertThat(member.has("permissions")).isTrue();
            assertThat(member.path("permissions").isObject()).isTrue();
            assertThat(member.path("permissions").size()).isZero();
        }
    }

    // ------------------------------------------------------------------------------- Boot-configured mapper

    @Nested
    class WithTheServicesJacksonConfiguration {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withBean(org.point32health.memberprofile.config.MemberProfileProperties.class, () -> new org.point32health.memberprofile.config.MemberProfileProperties(
                        new org.point32health.memberprofile.config.MemberProfileProperties.MemberDomain("http://localhost:8090/api/v1", "/members/{memberId}", java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(3)),
                        new org.point32health.memberprofile.config.MemberProfileProperties.Security(new org.point32health.memberprofile.config.MemberProfileProperties.Security.ApiKey(false, "X-Api-Key", "")),
                        new org.point32health.memberprofile.config.MemberProfileProperties.Explain(true),
                        new org.point32health.memberprofile.config.MemberProfileProperties.Http(8192, java.time.Duration.ofSeconds(5)),
                        "America/New_York"))
                .withUserConfiguration(AppConfig.class)
                .withPropertyValues("spring.jackson.default-property-inclusion=non_null");

        @Test
        void nullIdentityFieldsAreWrittenAsNullDespiteTheGlobalNonNullSetting() {
            runner.run(ctx -> {
                JsonMapper mapper = ctx.getBean(JsonMapper.class);
                MemberProfileResponse.Member m = new MemberProfileResponse.Member("HP0000001", "Alexa M Miller", "Alexa", "Miller",
                        null, "01", "HPHC", "M", false, 42, null, null, null, segmentation(), Map.of(), List.of());
                String json = mapper.writeValueAsString(new MemberProfileResponse(m, actionCodes(), null));

                JsonNode root = mapper.readTree(json);
                assertThat(root.has("explain")).isFalse();                       // top level stays NON_NULL
                JsonNode member = root.path("member");
                for (String field : List.of("planName", "activePolicy", "accountNumber", "policyStatus")) {
                    assertThat(member.has(field)).as(field).isTrue();
                    assertThat(member.get(field).isNull()).as(field).isTrue();
                }
                assertThat(member.propertyNames()).containsExactly("memberId", "fullName", "firstName", "lastName", "planName",
                        "relationshipCode", "memberTypeCode", "userTypeCode", "isImpersonating", "age", "activePolicy",
                        "accountNumber", "policyStatus", "segmentation", "permissions", "familyPermissions");
            });
        }

        @Test
        void fullyPopulatedPayloadIsIdenticalToThePlainMapperOutput() {
            runner.run(ctx -> assertThat(ctx.getBean(JsonMapper.class).writeValueAsString(fullyPopulated()))
                    .isEqualTo(EXPECTED_FULL_PAYLOAD));
        }

        @Test
        void forwardSlashesInValuesAreNotEscaped() {
            runner.run(ctx -> {
                MemberProfileResponse.Member m = new MemberProfileResponse.Member("HP0000001", "A/B", "A", "B", "HMO/PPO", "01", "HPHC", "M",
                        false, 42, true, "****1234", "ACTIVE", segmentation(), Map.of(), List.of());
                String json = ctx.getBean(JsonMapper.class).writeValueAsString(new MemberProfileResponse(m, actionCodes(), null));
                assertThat(json).contains("\"planName\":\"HMO/PPO\"").doesNotContain("\\/");
            });
        }
    }

    // ------------------------------------------------------------------------------- explain

    @Nested
    class ExplainShape {

        @Test
        void explainIsPresentAsTheThirdTopLevelPropertyWhenRequested() {
            MemberProfileResponse response = new MemberProfileResponse(fullyPopulated().member(), actionCodes(), explain());
            JsonNode root = PLAIN.readTree(PLAIN.writeValueAsString(response));

            assertThat(root.propertyNames()).containsExactly("member", "actionCodeDescriptions", "explain");
            JsonNode explain = root.path("explain");
            assertThat(explain.propertyNames()).containsExactly("actorRelationship", "memberFacts", "segmentation", "permissions", "timings");
            assertThat(explain.path("actorRelationship").asString()).isEqualTo("Subscriber");
        }

        @Test
        void memberFactsKeepTheirJsonTypes() {
            JsonNode facts = PLAIN.readTree(PLAIN.writeValueAsString(
                    new MemberProfileResponse(fullyPopulated().member(), actionCodes(), explain()))).path("explain").path("memberFacts");

            assertThat(facts.path("dependentType").isString()).isTrue();
            assertThat(facts.path("sourceSystemId").isNumber()).isTrue();
            assertThat(facts.path("sourceSystemId").asInt()).isEqualTo(2001);
            assertThat(facts.path("coverageActive").isBoolean()).isTrue();
        }

        @Test
        void segmentationTraceListsEveryConditionWithOperatorAsText() {
            JsonNode groups = PLAIN.readTree(PLAIN.writeValueAsString(
                    new MemberProfileResponse(fullyPopulated().member(), actionCodes(), explain()))).path("explain").path("segmentation");

            assertThat(groups.isArray()).isTrue();
            JsonNode group = groups.get(0);
            assertThat(group.propertyNames()).containsExactly("segment", "ruleGroup", "matched", "conditions");
            assertThat(group.path("segment").asString()).isEqualTo("onlineBillPay");
            assertThat(group.path("ruleGroup").asInt()).isEqualTo(1);
            assertThat(group.path("matched").asBoolean()).isTrue();
            JsonNode condition = group.path("conditions").get(0);
            assertThat(condition.propertyNames()).containsExactly("ruleId", "apiField", "operator", "ruleValue", "actualValue", "passed");
            assertThat(condition.path("operator").asString()).isEqualTo("EQUALS");
            assertThat(condition.path("actualValue").asString()).isEqualTo("01");
            assertThat(condition.path("passed").asBoolean()).isTrue();
        }

        @Test
        void permissionsTraceIsKeyedByMemberIdWithRuleRowsAndOutcome() {
            JsonNode permissions = PLAIN.readTree(PLAIN.writeValueAsString(
                    new MemberProfileResponse(fullyPopulated().member(), actionCodes(), explain()))).path("explain").path("permissions");

            assertThat(permissions.propertyNames()).containsExactly("HP0000001", "HP0000002");
            JsonNode row = permissions.path("HP0000002").get(0);
            assertThat(row.propertyNames()).containsExactly("ruleId", "permissionKey", "viewingRelationship",
                    "minimumAge", "maximumAge", "accessStatus", "outcome");
            assertThat(row.path("ruleId").asLong()).isEqualTo(12);
            assertThat(row.path("minimumAge").asInt()).isEqualTo(13);
            assertThat(row.path("maximumAge").asInt()).isEqualTo(17);
            assertThat(row.path("accessStatus").asString()).isEqualTo("MASKED_ACCESS");
            assertThat(row.path("outcome").asString()).isEqualTo("consent required, not on file");
        }

        @Test
        void timingsCarryTheThreeMillisecondCounters() {
            JsonNode timings = PLAIN.readTree(PLAIN.writeValueAsString(
                    new MemberProfileResponse(fullyPopulated().member(), actionCodes(), explain()))).path("explain").path("timings");

            assertThat(timings.propertyNames()).containsExactly("memberDomainMs", "rulesMs", "totalMs");
            assertThat(timings.path("memberDomainMs").asLong()).isEqualTo(12);
            assertThat(timings.path("rulesMs").asLong()).isEqualTo(3);
            assertThat(timings.path("totalMs").asLong()).isEqualTo(16);
        }

        @Test
        void aResponseWithManyFamilyMembersKeepsRosterOrder() {
            List<MemberProfileResponse.FamilyPermission> family = new ArrayList<>();
            for (int i = 2; i <= 6; i++) {
                family.add(new MemberProfileResponse.FamilyPermission("HP000000" + i, "Member " + i, "03", i,
                        Map.of("benefits", List.of(1)), List.of(), List.of()));
            }
            JsonNode entries = PLAIN.readTree(PLAIN.writeValueAsString(
                    new MemberProfileResponse(member("HMO Blue", false, family), actionCodes(), null))).path("member").path("familyPermissions");

            List<String> ids = new ArrayList<>();
            entries.forEach(e -> ids.add(e.path("memberId").asString()));
            assertThat(ids).containsExactly("HP0000002", "HP0000003", "HP0000004", "HP0000005", "HP0000006");
        }
    }

    @Test
    void nonNullInclusionOnTheTopLevelRecordIsWhatHidesExplain() {
        // Guard for the annotation the absence of explain depends on.
        JsonInclude include = MemberProfileResponse.class.getAnnotation(JsonInclude.class);
        assertThat(include).isNotNull();
        assertThat(include.value()).isEqualTo(JsonInclude.Include.NON_NULL);
        JsonInclude family = MemberProfileResponse.FamilyPermission.class.getAnnotation(JsonInclude.class);
        assertThat(family).isNotNull();
        assertThat(family.value()).isEqualTo(JsonInclude.Include.ALWAYS);
        assertThat(MemberProfileResponse.Member.class.getAnnotation(JsonInclude.class).value()).isEqualTo(JsonInclude.Include.ALWAYS);
    }
}
