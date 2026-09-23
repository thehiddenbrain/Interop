package org.point32health.memberprofile.config;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.api.MemberProfileController;
import org.point32health.memberprofile.api.MemberProfileResponse;
import org.point32health.memberprofile.api.RestExceptionHandler;
import org.point32health.memberprofile.service.MemberProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The two servlet filters in front of the API: the service-to-service API key and the request body cap. */
class ApiKeyAndBodySizeFilterTest {

    static final String PATH = "/api/v1/member-profile";
    static final String BODY = "{\"memberId\":\"HP0000001\"}";

    static MemberProfileResponse response() {
        MemberProfileResponse.Member m = new MemberProfileResponse.Member("HP0000001", "A M", "A", "M", null, "01", "HPHC", "M",
                false, 42, true, "****1234", "ACTIVE", Map.of(), Map.of(), List.of());
        return new MemberProfileResponse(m, Map.of(1, "View"), null);
    }

    @Nested
    @WebMvcTest(MemberProfileController.class)
    @Import({ApiKeyAuthFilter.class, RequestSizeLimitFilter.class, RestExceptionHandler.class})
    @EnableConfigurationProperties(MemberProfileProperties.class)
    @TestPropertySource(properties = {
            "member-profile.security.api-key.enabled=true",
            "member-profile.security.api-key.value=test-key-123",
            "member-profile.http.max-body-bytes=256"})
    class Enabled {

        @Autowired MockMvc mvc;
        @MockitoBean MemberProfileService service;

        @Test
        void correctKeyReachesTheController() throws Exception {
            when(service.profile(any())).thenReturn(response());

            mvc.perform(post(PATH).header("X-Api-Key", "test-key-123").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.member.memberId").value("HP0000001"));
        }

        @Test
        void missingKeyIs401ApiErrorAndNeverReachesTheService() throws Exception {
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("WWW-Authenticate", "ApiKey header=\"X-Api-Key\""))
                    .andExpect(jsonPath("$.status").value("ERROR"))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.message").value("authentication required"));
            verifyNoInteractions(service);
        }

        @ParameterizedTest
        @ValueSource(strings = {"wrong", "test-key-12", "test-key-1234", "TEST-KEY-123", " test-key-123", ""})
        void wrongKeyIs401(String presented) throws Exception {
            mvc.perform(post(PATH).header("X-Api-Key", presented).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
            verifyNoInteractions(service);
        }

        @Test
        void keyIsCheckedBeforeMethodAndBodyValidation() throws Exception {
            mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{bad")).andExpect(status().isUnauthorized());
        }

        @Test
        void pathsOutsideTheApiAreNotFiltered() throws Exception {
            mvc.perform(get("/actuator/health")).andExpect(status().isNotFound()); // no actuator in the slice, but not 401
        }

        @Test
        void declaredContentLengthAboveTheLimitIs413() throws Exception {
            String big = "{\"memberId\":\"HP0000001\",\"padding\":\"" + "x".repeat(300) + "\"}";
            mvc.perform(post(PATH).header("X-Api-Key", "test-key-123").contentType(MediaType.APPLICATION_JSON).content(big))
                    .andExpect(status().isPayloadTooLarge())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                    .andExpect(jsonPath("$.message").value("request body too large"));
            verifyNoInteractions(service);
        }

        @Test
        void bodyWithinTheLimitPasses() throws Exception {
            when(service.profile(any())).thenReturn(response());
            String ok = "{\"memberId\":\"HP0000001\",\"padding\":\"" + "x".repeat(100) + "\"}";

            mvc.perform(post(PATH).header("X-Api-Key", "test-key-123").contentType(MediaType.APPLICATION_JSON).content(ok))
                    .andExpect(status().isOk());
        }
    }

    @Nested
    @WebMvcTest(MemberProfileController.class)
    @Import({ApiKeyAuthFilter.class, RequestSizeLimitFilter.class, RestExceptionHandler.class})
    @EnableConfigurationProperties(MemberProfileProperties.class)
    @TestPropertySource(properties = {"member-profile.security.api-key.enabled=false"})
    class Disabled {

        @Autowired MockMvc mvc;
        @MockitoBean MemberProfileService service;

        @Test
        void noKeyIsNeededInDevelopment() throws Exception {
            when(service.profile(any())).thenReturn(response());

            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isOk());
        }

        @Test
        void aBodyOverTheDefaultLimitIsStillRefused() throws Exception {
            String big = "{\"memberId\":\"HP0000001\",\"padding\":\"" + "x".repeat(9000) + "\"}";
            mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(big))
                    .andExpect(status().isPayloadTooLarge());
        }
    }

    @Test
    void enabledWithoutAKeyRefusesToStart() {
        MemberProfileProperties props = new MemberProfileProperties(
                new MemberProfileProperties.MemberDomain("http://x", "/m/{memberId}", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new MemberProfileProperties.Security(new MemberProfileProperties.Security.ApiKey(true, "X-Api-Key", "   ")),
                new MemberProfileProperties.Explain(false), new MemberProfileProperties.Http(8192, Duration.ofSeconds(5)), "UTC");

        assertThatThrownBy(() -> new ApiKeyAuthFilter().apiKeyFilter(props, JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MEMBER_PROFILE_API_KEY");
    }

    @Test
    void propertyDefaultsMatchDevelopment() {
        MemberProfileProperties.Security.ApiKey key = new MemberProfileProperties.Security.ApiKey(false, "X-Api-Key", "");
        assertThat(key.enabled()).isFalse();
        assertThat(key.header()).isEqualTo("X-Api-Key");
    }
}
