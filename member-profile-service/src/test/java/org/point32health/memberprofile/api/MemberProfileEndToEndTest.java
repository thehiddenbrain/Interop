package org.point32health.memberprofile.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.memberdomain.MemberDomainClient;
import org.point32health.memberprofile.memberdomain.MemberDomainMember;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the Flyway migrations and the real repositories against PostgreSQL (MEMBER_PROFILE_DB_URL etc.),
 * with MemberDomain stubbed. Skipped unless MEMBER_PROFILE_TEST_DB=true.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
class MemberProfileEndToEndTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @MockitoBean MemberDomainClient memberDomain;

    private static String body(String memberId, String extra) {
        return "{\"memberId\":\"" + memberId + "\"" + extra + "}";
    }

    @Test
    void hphcSubscriberWithYoungChild() throws Exception {
        when(memberDomain.findMember("HP0000001")).thenReturn(Optional.of(new MemberDomainMember(
                "HP0000001", "Alexa", "Miller", "Alexa M Miller", null, "01", "HPHC", "M",
                null, 42, true, "****1234", "ACTIVE",
                Map.of("dependentType", "01", "memberCategory", "B2I", "customerCategory", "GROUP",
                        "basicMedicalDrugCoverageIndicator", "N"),
                List.of(new MemberDomainMember.FamilyMember("HP0000002", "Liam Miller", "03", null, 7)))));

        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content(body("HP0000001", "")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.member.memberId").value("HP0000001"))
                .andExpect(jsonPath("$.member.memberTypeCode").value("HPHC"))
                .andExpect(jsonPath("$.member.isImpersonating").value(false))
                .andExpect(jsonPath("$.member.age").value(42))
                .andExpect(jsonPath("$.member.segmentation.onlineBillPay").value(true))
                .andExpect(jsonPath("$.member.segmentation.optumRxCoverage").value(false))
                .andExpect(jsonPath("$.member.segmentation.*", hasSize(7)))
                .andExpect(jsonPath("$.member.permissions.benefits").value(contains(1)))
                .andExpect(jsonPath("$.member.permissions['benefits.idCard']").value(contains(1, 3)))
                .andExpect(jsonPath("$.member.familyPermissions", hasSize(1)))
                .andExpect(jsonPath("$.member.familyPermissions[0].memberId").value("HP0000002"))
                .andExpect(jsonPath("$.member.familyPermissions[0].relationshipCode").value("03"))
                .andExpect(jsonPath("$.member.familyPermissions[0].age").value(7))
                .andExpect(jsonPath("$.member.familyPermissions[0].permissions.benefits").value(contains(1)))
                .andExpect(jsonPath("$.member.familyPermissions[0].permissions['benefits.idCard']").value(contains(1, 3)))
                .andExpect(jsonPath("$.member.familyPermissions[0].permissions['claims.claim']").value(contains(1, 3)))
                .andExpect(jsonPath("$.member.familyPermissions[0].consentRequired").doesNotExist())
                .andExpect(jsonPath("$.actionCodeDescriptions.1").value("View"))
                .andExpect(jsonPath("$.actionCodeDescriptions.4").value("Delete"))
                .andExpect(jsonPath("$.explain").doesNotExist())
                .andExpect(jsonPath("$.segmentation").doesNotExist());
    }

    @Test
    void thpSubscriberWithTeenNeedsConsentForClaims() throws Exception {
        when(memberDomain.findMember("TH0000001")).thenReturn(Optional.of(new MemberDomainMember(
                "TH0000001", "Sam", "Lee", "Sam Lee", "Tufts Medicare Preferred", "01", "THP", "M",
                LocalDate.now().minusYears(70), null, true, "****9876", "ACTIVE",
                Map.of("sourceSystemId", 2001, "planTypeCode", "MR", "planCode", "10EG1234", "coverageActive", true,
                        "coverageStarted", true, "groupProductEffectiveForCoverage", true,
                        "hasPharmacyRider", true, "product", "MAPD", "coverageGroupTypeCode", "2", "hasActivePdp", false),
                List.of(new MemberDomainMember.FamilyMember("TH0000002", "Kim Lee", "03", LocalDate.now().minusYears(15), null)))));

        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON)
                        .content(body("TH0000001", ",\"impersonating\":true,\"explain\":true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.age").value(70))
                .andExpect(jsonPath("$.member.isImpersonating").value(true))
                .andExpect(jsonPath("$.member.segmentation.onlineBillPay").value(true))
                .andExpect(jsonPath("$.member.segmentation.optumRxCoverage").value(true))
                .andExpect(jsonPath("$.member.segmentation.allTuftsMedicarePreferred").value(true))
                .andExpect(jsonPath("$.member.segmentation.interoperability").value(true))
                .andExpect(jsonPath("$.member.segmentation.tmpOtcMa").value(false))
                .andExpect(jsonPath("$.member.segmentation.planOfCare").value(false))
                .andExpect(jsonPath("$.member.segmentation.allPublicPlansMa").value(false))
                .andExpect(jsonPath("$.member.familyPermissions[0].age").value(15))
                .andExpect(jsonPath("$.member.familyPermissions[0].permissions['benefits.idCard']").value(contains(1, 3)))
                .andExpect(jsonPath("$.member.familyPermissions[0].permissions['claims.claim']").doesNotExist())
                .andExpect(jsonPath("$.member.familyPermissions[0].consentRequired").value(contains("claims.authorization", "claims.claim")))
                .andExpect(jsonPath("$.explain.actorRelationship").value("Subscriber"))
                .andExpect(jsonPath("$.explain.timings.totalMs").exists())
                .andExpect(jsonPath("$.explain.segmentation[?(@.segment=='interoperability' && @.matched==true)].ruleGroup").value(contains(4)));
    }

    @Test
    void unknownMemberIs404() throws Exception {
        when(memberDomain.findMember(anyString())).thenReturn(Optional.empty());

        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content(body("NOBODY", "")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void memberDomainOutageIs504() throws Exception {
        when(memberDomain.findMember(anyString())).thenThrow(new MemberProfileException(ErrorCode.MEMBER_DOMAIN_UNREACHABLE, "down"));

        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content(body("HP0000001", "")))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("MEMBER_DOMAIN_UNREACHABLE"));
    }

    @Test
    void getIsNotAllowedAndBadIdsAreRejected() throws Exception {
        mvc.perform(get("/api/v1/member-profile"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content(body("a b;drop", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[0].field").value("memberId"));
        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content("{\"memberId\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void consentOnFileUnlocksTheTeenClaimsRowsEndToEnd() throws Exception {
        when(memberDomain.findMember("TH0000001")).thenReturn(Optional.of(new MemberDomainMember(
                "TH0000001", "Sam", "Lee", "Sam Lee", null, "01", "THP", "M", null, 70, true, "****9876", "ACTIVE", Map.of(),
                List.of(new MemberDomainMember.FamilyMember("TH0000002", "Kim Lee", "03", null, 15)))));
        jdbc.sql("INSERT INTO family_permission.family_consent (actor_member_id, viewed_member_id, permission_family, granted_by) "
                + "VALUES ('TH0000001', 'TH0000002', 'claims', 'e2e')").update();
        try {
            mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content(body("TH0000001", "")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.member.familyPermissions[0].permissions.claims").value(contains(1)))
                    .andExpect(jsonPath("$.member.familyPermissions[0].permissions['claims.claim']").value(contains(1)))
                    .andExpect(jsonPath("$.member.familyPermissions[0].permissions['claims.authorization']").value(contains(1)))
                    .andExpect(jsonPath("$.member.familyPermissions[0].consentRequired").doesNotExist())
                    .andExpect(jsonPath("$.member.familyPermissions[0].masked").value(contains("claims.claim")));
        } finally {
            jdbc.sql("DELETE FROM family_permission.family_consent WHERE actor_member_id = 'TH0000001'").update();
        }
    }

    @Test
    void exSpouseActorSeesOwnProfileButNothingOfTheSubscriber() throws Exception {
        when(memberDomain.findMember("TH0000004")).thenReturn(Optional.of(new MemberDomainMember(
                "TH0000004", "Pat", "Lee", "Pat Lee", null, "04", "THP", "M", null, 45, true, "****1111", "ACTIVE", Map.of(),
                List.of(new MemberDomainMember.FamilyMember("TH0000001", "Sam Lee", "01", null, 70)))));

        mvc.perform(post("/api/v1/member-profile").contentType(MediaType.APPLICATION_JSON).content(body("TH0000004", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.permissions.profile").value(contains(1)))
                .andExpect(jsonPath("$.member.permissions['profile.raceEthnicityLanguage']").value(contains(1, 2)))
                .andExpect(jsonPath("$.member.permissions['profile.sexualOrientationGenderIdentity']").value(contains(1, 2)))
                .andExpect(jsonPath("$.member.familyPermissions[0].memberId").value("TH0000001"))
                .andExpect(jsonPath("$.member.familyPermissions[0].permissions").isEmpty())
                .andExpect(jsonPath("$.member.segmentation.*", hasSize(7)))
                .andExpect(jsonPath("$.member.segmentation.onlineBillPay").value(false));
    }
}
