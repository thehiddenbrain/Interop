package org.point32health.memberprofile.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.point32health.memberprofile.service.MemberProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.empty;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MemberProfileController} as a web slice: the HTTP contract of catalog section 1 with the service mocked.
 * <ul>
 *   <li>POST is the only verb; the body carries the member id and the two optional flags.</li>
 *   <li>A success is 200, {@code application/json}, {@code Cache-Control: no-store}, and the service's response
 *       passed through untouched.</li>
 *   <li>{@code impersonating} and {@code explain} are normalized to booleans before the service sees them.</li>
 *   <li>The member id is validated (blank, too long, characters outside {@code [A-Za-z0-9_-]}) before the
 *       service is called; violations are 400 {@code VALIDATION_ERROR} with {@code details[].field = memberId}.</li>
 * </ul>
 * Error mapping of exceptions and protocol mistakes is covered in {@link RestExceptionHandlerTest}.
 */
@WebMvcTest(MemberProfileController.class)
class MemberProfileControllerTest {

    static final String PATH = "/api/v1/member-profile";
    static final String THIRTY_CHARS = "HP0000000000000000000000000001";

    @Autowired MockMvc mvc;
    @MockitoBean MemberProfileService service;

    /** A response with every branch of the agreed payload populated, as the service would hand it over. */
    static MemberProfileResponse sampleResponse(boolean impersonating) {
        Map<String, Boolean> segmentation = new LinkedHashMap<>();
        segmentation.put("onlineBillPay", true);
        segmentation.put("optumRxCoverage", false);
        segmentation.put("allPublicPlansMa", false);
        segmentation.put("allTuftsMedicarePreferred", false);
        segmentation.put("tmpOtcMa", false);
        segmentation.put("planOfCare", false);
        segmentation.put("interoperability", false);
        Map<String, List<Integer>> self = new TreeMap<>(Map.of("benefits", List.of(1), "benefits.idCard", List.of(1, 3)));
        Map<String, List<Integer>> child = new TreeMap<>(Map.of(
                "benefits", List.of(1), "benefits.coverage", List.of(1), "benefits.idCard", List.of(1, 3)));
        MemberProfileResponse.Member member = new MemberProfileResponse.Member(
                "HP0000001", "Alexa M Miller", "Alexa", "Miller", null, "01", "HPHC", "M", impersonating, 42,
                false, "****1234", "ACTIVE", segmentation, self,
                List.of(new MemberProfileResponse.FamilyPermission("HP0000002", "Liam Miller", "03", 7, child,
                        List.of("claims.claim"), List.of("claims.claim"))));
        Map<Integer, String> actions = new TreeMap<>(Map.of(1, "View", 2, "Edit", 3, "Download", 4, "Delete"));
        return new MemberProfileResponse(member, actions, null);
    }

    @BeforeEach
    void stubService() {
        when(service.profile(any())).thenAnswer(inv -> sampleResponse(((MemberProfileRequest) inv.getArgument(0)).isImpersonating()));
    }

    private MvcResult postJson(String body) throws Exception {
        return mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    // ------------------------------------------------------------------------------- happy path

    @Nested
    class HappyPath {

        @Test
        void postReturns200JsonNoStoreWithTheServiceResponsePassedThrough() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"HP0000001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.member.memberId").value("HP0000001"))
                    .andExpect(jsonPath("$.member.fullName").value("Alexa M Miller"))
                    .andExpect(jsonPath("$.member.firstName").value("Alexa"))
                    .andExpect(jsonPath("$.member.lastName").value("Miller"))
                    .andExpect(jsonPath("$.member.relationshipCode").value("01"))
                    .andExpect(jsonPath("$.member.memberTypeCode").value("HPHC"))
                    .andExpect(jsonPath("$.member.userTypeCode").value("M"))
                    .andExpect(jsonPath("$.member.isImpersonating").value(false))
                    .andExpect(jsonPath("$.member.age").value(42))
                    .andExpect(jsonPath("$.member.activePolicy").value(false))
                    .andExpect(jsonPath("$.member.accountNumber").value("****1234"))
                    .andExpect(jsonPath("$.member.policyStatus").value("ACTIVE"))
                    .andExpect(jsonPath("$.member.segmentation.*", hasSize(7)))
                    .andExpect(jsonPath("$.member.segmentation.onlineBillPay").value(true))
                    .andExpect(jsonPath("$.member.segmentation.interoperability").value(false))
                    .andExpect(jsonPath("$.member.permissions.benefits").value(contains(1)))
                    .andExpect(jsonPath("$.member.permissions['benefits.idCard']").value(contains(1, 3)))
                    .andExpect(jsonPath("$.member.familyPermissions", hasSize(1)))
                    .andExpect(jsonPath("$.member.familyPermissions[0].memberId").value("HP0000002"))
                    .andExpect(jsonPath("$.member.familyPermissions[0].fullName").value("Liam Miller"))
                    .andExpect(jsonPath("$.member.familyPermissions[0].relationshipCode").value("03"))
                    .andExpect(jsonPath("$.member.familyPermissions[0].age").value(7))
                    .andExpect(jsonPath("$.member.familyPermissions[0].permissions['benefits.coverage']").value(contains(1)))
                    .andExpect(jsonPath("$.member.familyPermissions[0].consentRequired").value(contains("claims.claim")))
                    .andExpect(jsonPath("$.member.familyPermissions[0].masked").value(contains("claims.claim")))
                    .andExpect(jsonPath("$.actionCodeDescriptions.1").value("View"))
                    .andExpect(jsonPath("$.actionCodeDescriptions.2").value("Edit"))
                    .andExpect(jsonPath("$.actionCodeDescriptions.3").value("Download"))
                    .andExpect(jsonPath("$.actionCodeDescriptions.4").value("Delete"))
                    .andExpect(jsonPath("$.explain").doesNotExist())
                    .andExpect(jsonPath("$.status").doesNotExist());
        }

        @Test
        void nullPlanNameIsWrittenAsNullSoTheShapeIsStable() throws Exception {
            // Member is @JsonInclude(ALWAYS): the agreed payload shows "planName": null as a present property.
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"HP0000001\"}"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("\"planName\":null")));
        }

        @Test
        void theServiceReceivesTheMemberIdExactlyAsSent() throws Exception {
            postJson("{\"memberId\":\"abc-DEF_123\"}");

            ArgumentCaptor<MemberProfileRequest> captor = ArgumentCaptor.forClass(MemberProfileRequest.class);
            verify(service).profile(captor.capture());
            assertThat(captor.getValue().memberId()).isEqualTo("abc-DEF_123");
        }

        @Test
        void bodyIsAcceptedWithAJsonContentTypeCarryingACharset() throws Exception {
            mvc.perform(post(PATH).contentType("application/json;charset=UTF-8").content("{\"memberId\":\"HP0000001\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        void impersonatingTrueIsEchoedInTheResponse() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"memberId\":\"HP0000001\",\"impersonating\":true}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.member.isImpersonating").value(true));
        }
    }

    // ------------------------------------------------------------------------------- optional flags

    @Nested
    class OptionalFlags {

        private MemberProfileRequest requestSeenByService(String body) throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
            ArgumentCaptor<MemberProfileRequest> captor = ArgumentCaptor.forClass(MemberProfileRequest.class);
            verify(service).profile(captor.capture());
            return captor.getValue();
        }

        @Test
        void absentFlagsAreFalse() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"memberId\":\"HP0000001\"}");
            assertThat(seen.isImpersonating()).isFalse();
            assertThat(seen.isExplain()).isFalse();
            assertThat(seen.impersonating()).isFalse();
            assertThat(seen.explain()).isFalse();
        }

        @Test
        void nullFlagsAreFalse() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"memberId\":\"HP0000001\",\"impersonating\":null,\"explain\":null}");
            assertThat(seen.isImpersonating()).isFalse();
            assertThat(seen.isExplain()).isFalse();
        }

        @Test
        void falseFlagsAreFalse() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"memberId\":\"HP0000001\",\"impersonating\":false,\"explain\":false}");
            assertThat(seen.isImpersonating()).isFalse();
            assertThat(seen.isExplain()).isFalse();
        }

        @Test
        void trueFlagsAreTrue() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"memberId\":\"HP0000001\",\"impersonating\":true,\"explain\":true}");
            assertThat(seen.isImpersonating()).isTrue();
            assertThat(seen.isExplain()).isTrue();
        }

        @Test
        void flagsAreIndependent() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"memberId\":\"HP0000001\",\"impersonating\":false,\"explain\":true}");
            assertThat(seen.isImpersonating()).isFalse();
            assertThat(seen.isExplain()).isTrue();
        }

        @Test
        void propertyOrderInTheBodyDoesNotMatter() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"explain\":true,\"impersonating\":true,\"memberId\":\"HP0000001\"}");
            assertThat(seen.memberId()).isEqualTo("HP0000001");
            assertThat(seen.isImpersonating()).isTrue();
            assertThat(seen.isExplain()).isTrue();
        }

        /**
         * Jackson 3 disables FAIL_ON_UNKNOWN_PROPERTIES by default and this service does not turn it back on,
         * so an unknown property is ignored rather than rejected. Pinned so a change is a conscious decision.
         */
        @Test
        void unknownJsonPropertyIsIgnored() throws Exception {
            MemberProfileRequest seen = requestSeenByService("{\"memberId\":\"HP0000001\",\"somethingElse\":\"x\",\"nested\":{\"a\":1}}");
            assertThat(seen.memberId()).isEqualTo("HP0000001");
        }
    }

    // ------------------------------------------------------------------------------- member id validation

    @Nested
    class MemberIdValidation {

        private void assertValidationErrorOnMemberId(String body) throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.status").value("ERROR"))
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                    .andExpect(jsonPath("$.message").value("request is not valid"))
                    .andExpect(jsonPath("$.details", not(empty())))
                    .andExpect(jsonPath("$.details[*].field", everyItem(is("memberId"))))
                    .andExpect(jsonPath("$.details[0].message").isString());
            verifyNoInteractions(service);
        }

        @Test
        void missingMemberIdIsAValidationError() throws Exception {
            assertValidationErrorOnMemberId("{}");
        }

        @Test
        void nullMemberIdIsAValidationError() throws Exception {
            assertValidationErrorOnMemberId("{\"memberId\":null}");
        }

        @Test
        void emptyMemberIdIsAValidationError() throws Exception {
            assertValidationErrorOnMemberId("{\"memberId\":\"\"}");
        }

        @Test
        void blankMemberIdIsAValidationError() throws Exception {
            assertValidationErrorOnMemberId("{\"memberId\":\"   \"}");
        }

        @Test
        void thirtyOneCharacterMemberIdIsTooLong() throws Exception {
            assertValidationErrorOnMemberId("{\"memberId\":\"" + THIRTY_CHARS + "X\"}");
        }

        @Test
        void veryLongMemberIdIsTooLong() throws Exception {
            assertValidationErrorOnMemberId("{\"memberId\":\"" + "A".repeat(500) + "\"}");
        }

        @ParameterizedTest(name = "memberId ''{0}''")
        @ValueSource(strings = {"a b", "HP;drop", "HP.1", "HP/1", "HP@1", "HP#1", "HP 1", "HP+1", "HP%201", "ünïcode", "HP\\u00e9", "<script>"})
        void memberIdWithCharactersOutsideTheAllowedSetIsAValidationError(String memberId) throws Exception {
            assertValidationErrorOnMemberId("{\"memberId\":\"" + memberId + "\"}");
        }

        @Test
        void memberIdWithLeadingOrTrailingWhitespaceIsAValidationError() throws Exception {
            // The pattern has no trimming: the id must arrive clean.
            assertValidationErrorOnMemberId("{\"memberId\":\" HP0000001\"}");
            assertValidationErrorOnMemberId("{\"memberId\":\"HP0000001 \"}");
        }

        @ParameterizedTest(name = "memberId ''{0}''")
        @ValueSource(strings = {"HP0000001", "TH0000001", "abc-DEF_123", "1", "_", "-", "a"})
        void memberIdWithinTheAllowedSetIsAccepted(String memberId) throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"" + memberId + "\"}"))
                    .andExpect(status().isOk());
        }

        @Test
        void thirtyCharacterMemberIdIsAccepted() throws Exception {
            assertThat(THIRTY_CHARS).hasSize(30);
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"" + THIRTY_CHARS + "\"}"))
                    .andExpect(status().isOk());
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "'{\"memberId\":\"\"}', VALIDATION_ERROR",
                "'{\"memberId\":\"ok-id\"}', OK"})
        void validationOutcomeDrivesWhetherTheServiceIsCalled(String body, String outcome) throws Exception {
            MvcResult result = postJson(body);
            if (outcome.equals("OK")) {
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                verify(service).profile(any());
            } else {
                assertThat(result.getResponse().getStatus()).isEqualTo(400);
                verifyNoInteractions(service);
            }
        }
    }
}
