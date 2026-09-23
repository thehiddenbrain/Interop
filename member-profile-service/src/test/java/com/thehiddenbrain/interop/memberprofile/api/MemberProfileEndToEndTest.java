package com.thehiddenbrain.interop.memberprofile.api;

import com.thehiddenbrain.interop.memberprofile.memberdomain.MemberDomainClient;
import com.thehiddenbrain.interop.memberprofile.memberdomain.MemberDomainException;
import com.thehiddenbrain.interop.memberprofile.memberdomain.MemberDomainMember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs the Flyway migrations and the real repositories against PostgreSQL (MEMBER_PROFILE_DB_URL etc.),
 * with MemberDomain stubbed. Skipped when MEMBER_PROFILE_TEST_DB is not set.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "MEMBER_PROFILE_TEST_DB", matches = "true")
class MemberProfileEndToEndTest {

    @Autowired MockMvc mvc;
    @MockitoBean MemberDomainClient memberDomain;

    @Test
    void hphcSubscriberWithYoungChild() throws Exception {
        when(memberDomain.findMember("HP0000001")).thenReturn(Optional.of(new MemberDomainMember(
                "HP0000001", "Alexa", "Miller", "Alexa M Miller", null, "01", "HPHC", "M", "HPHC",
                null, 42, true, "****1234", "ACTIVE",
                Map.of("dependentType", "01", "memberCategory", "B2I", "customerCategory", "GROUP",
                        "basicMedicalDrugCoverageIndicator", "N"),
                List.of(new MemberDomainMember.FamilyMember("HP0000002", "Liam Miller", "03", null, 7)))));

        mvc.perform(get("/api/v1/members/HP0000001/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.memberId").value("HP0000001"))
                .andExpect(jsonPath("$.member.company").value("HPHC"))
                .andExpect(jsonPath("$.member.isImpersonating").value(false))
                .andExpect(jsonPath("$.segmentation.onlineBillPay").value(true))
                .andExpect(jsonPath("$.segmentation.optumRxCoverage").value(false))
                .andExpect(jsonPath("$.segmentation.*", hasSize(7)))
                .andExpect(jsonPath("$.selfPermissions.benefits").value(contains(1)))
                .andExpect(jsonPath("$.selfPermissions['benefits.idCard']").value(contains(1, 3)))
                .andExpect(jsonPath("$.familyPermissions", hasSize(1)))
                .andExpect(jsonPath("$.familyPermissions[0].memberId").value("HP0000002"))
                .andExpect(jsonPath("$.familyPermissions[0].age").value(7))
                .andExpect(jsonPath("$.familyPermissions[0].permissions.benefits").value(contains(1)))
                .andExpect(jsonPath("$.familyPermissions[0].permissions['benefits.idCard']").value(contains(1, 3)))
                .andExpect(jsonPath("$.familyPermissions[0].permissions['claims.claim']").value(contains(1, 3)))
                .andExpect(jsonPath("$.familyPermissions[0].consentRequired").doesNotExist())
                .andExpect(jsonPath("$.actionCodes.1").value("View"))
                .andExpect(jsonPath("$.actionCodes.4").value("Delete"))
                .andExpect(jsonPath("$.explain").doesNotExist());
    }

    @Test
    void thpSubscriberWithTeenNeedsConsentForClaims() throws Exception {
        when(memberDomain.findMember("TH0000001")).thenReturn(Optional.of(new MemberDomainMember(
                "TH0000001", "Sam", "Lee", "Sam Lee", "Tufts Medicare Preferred", "01", "THP", "M", "THP",
                LocalDate.now().minusYears(70), null, true, "****9876", "ACTIVE",
                Map.of("sourceSystemId", 2001, "planTypeCode", "MR", "planCode", "10EG1234", "coverageActive", true,
                        "coverageStarted", true, "groupProductEffectiveForCoverage", true,
                        "hasPharmacyRider", true, "product", "MAPD", "coverageGroupTypeCode", "2", "hasActivePdp", false),
                List.of(new MemberDomainMember.FamilyMember("TH0000002", "Kim Lee", "03", LocalDate.now().minusYears(15), null)))));

        mvc.perform(get("/api/v1/members/TH0000001/profile").param("explain", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.member.age").value(70))
                .andExpect(jsonPath("$.segmentation.onlineBillPay").value(true))
                .andExpect(jsonPath("$.segmentation.optumRxCoverage").value(true))
                .andExpect(jsonPath("$.segmentation.allTuftsMedicarePreferred").value(true))
                .andExpect(jsonPath("$.segmentation.interoperability").value(true))
                .andExpect(jsonPath("$.segmentation.tmpOtcMa").value(false))
                .andExpect(jsonPath("$.segmentation.planOfCare").value(false))
                .andExpect(jsonPath("$.segmentation.allPublicPlansMa").value(false))
                .andExpect(jsonPath("$.familyPermissions[0].age").value(15))
                .andExpect(jsonPath("$.familyPermissions[0].permissions['benefits.idCard']").value(contains(1, 3)))
                .andExpect(jsonPath("$.familyPermissions[0].permissions['claims.claim']").doesNotExist())
                .andExpect(jsonPath("$.familyPermissions[0].consentRequired").value(contains("claims.authorization", "claims.claim")))
                .andExpect(jsonPath("$.explain.actorRelationship").value("Subscriber"))
                .andExpect(jsonPath("$.explain.segmentation[?(@.segment=='interoperability' && @.matched==true)].ruleGroup").value(contains(4)));
    }

    @Test
    void unknownMemberIs404() throws Exception {
        when(memberDomain.findMember(anyString())).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/members/NOBODY/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("MEMBER_NOT_FOUND"));
    }

    @Test
    void memberDomainOutageIs502() throws Exception {
        when(memberDomain.findMember(anyString())).thenThrow(new MemberDomainException("down", null));

        mvc.perform(get("/api/v1/members/HP0000001/profile"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("MEMBER_DOMAIN_UNAVAILABLE"));
    }

    @Test
    void badMemberIdIs400() throws Exception {
        mvc.perform(get("/api/v1/members/{id}/profile", "a b;drop"))
                .andExpect(status().isBadRequest());
    }
}
