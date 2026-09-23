package org.point32health.memberprofile.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.point32health.memberprofile.common.ApiError;
import org.point32health.memberprofile.common.ErrorCode;
import org.point32health.memberprofile.common.MemberProfileException;
import org.point32health.memberprofile.service.MemberProfileService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link RestExceptionHandler} through the web slice: every failure leaves the API as the {@code ApiError}
 * shape of catalog section 1, {@code { status: "ERROR", code, message, details[] }}, with the HTTP status
 * derived from the {@link ErrorCode}.
 * <ul>
 *   <li>Wrong verb is 405, wrong media type 415, unknown path 404, unreadable body 400: all
 *       {@code MALFORMED_REQUEST}.</li>
 *   <li>Bean validation failures are 400 {@code VALIDATION_ERROR} with one detail per violated field.</li>
 *   <li>A {@link MemberProfileException} maps to its code's status and keeps its message and details.</li>
 *   <li>Anything unexpected is 500 {@code INTERNAL_ERROR} with a fixed message; internals never leak.</li>
 * </ul>
 */
@WebMvcTest(MemberProfileController.class)
class RestExceptionHandlerTest {

    static final String PATH = "/api/v1/member-profile";
    static final String VALID_BODY = "{\"memberId\":\"HP0000001\"}";

    @Autowired MockMvc mvc;
    @MockitoBean MemberProfileService service;

    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request);
    }

    /** The invariant part of every error: JSON, status ERROR, the code, details always an array, nothing else. */
    private static ResultActions expectApiError(ResultActions actions, HttpStatus status, ErrorCode code) throws Exception {
        return actions
                .andExpect(status().is(status.value()))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value(code.name()))
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.stackTrace").doesNotExist())
                .andExpect(jsonPath("$.exception").doesNotExist())
                .andExpect(jsonPath("$.member").doesNotExist());
    }

    // ------------------------------------------------------------------------------- wrong verb

    @Nested
    class WrongVerb {

        @Test
        void getIs405MalformedRequest() throws Exception {
            expectApiError(perform(get(PATH)), HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED)
                    .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.message()))
                    .andExpect(jsonPath("$.details", empty()));
            verifyNoInteractions(service);
        }

        @Test
        void getWithTheMemberIdAsQueryParameterIsStill405() throws Exception {
            expectApiError(perform(get(PATH).param("memberId", "HP0000001")), HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED);
            verifyNoInteractions(service);
        }

        @Test
        void putIs405MalformedRequest() throws Exception {
            expectApiError(perform(put(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED)
                    .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.message()));
            verifyNoInteractions(service);
        }

        @Test
        void deleteIs405MalformedRequest() throws Exception {
            expectApiError(perform(delete(PATH)), HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED)
                    .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.message()));
            verifyNoInteractions(service);
        }

        @Test
        void patchIs405MalformedRequest() throws Exception {
            expectApiError(perform(patch(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED)
                    .andExpect(jsonPath("$.message").value(ErrorCode.METHOD_NOT_ALLOWED.message()));
            verifyNoInteractions(service);
        }

        @Test
        void headIs405() throws Exception {
            perform(head(PATH)).andExpect(status().isMethodNotAllowed());
            verifyNoInteractions(service);
        }
    }

    // ------------------------------------------------------------------------------- wrong media type

    @Nested
    class WrongMediaType {

        @ParameterizedTest(name = "Content-Type {0}")
        @ValueSource(strings = {"text/plain", "application/xml", "application/x-www-form-urlencoded", "text/html", "application/octet-stream"})
        void nonJsonContentTypeIs415MalformedRequest(String contentType) throws Exception {
            expectApiError(perform(post(PATH).contentType(contentType).content(VALID_BODY)),
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE)
                    .andExpect(jsonPath("$.message").value("unsupported content type; send application/json"))
                    .andExpect(jsonPath("$.details", empty()));
            verifyNoInteractions(service);
        }
    }

    // ------------------------------------------------------------------------------- unreadable body

    @Nested
    class UnreadableBody {

        private ResultActions expectMalformed(String body) throws Exception {
            ResultActions actions = expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body)),
                    HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST)
                    .andExpect(jsonPath("$.message").value("request body is not valid"))
                    .andExpect(jsonPath("$.details").isArray())
                    .andExpect(jsonPath("$.details", not(empty())))
                    .andExpect(jsonPath("$.details[0].field").isString())
                    .andExpect(jsonPath("$.details[0].message").isString());
            verifyNoInteractions(service);
            return actions;
        }

        @Test
        void truncatedJsonIs400WithTheBodyAsField() throws Exception {
            expectMalformed("{\"memberId\": ")
                    .andExpect(jsonPath("$.details[0].field").value("body"));
        }

        @Test
        void unquotedGarbageIs400() throws Exception {
            expectMalformed("not json at all")
                    .andExpect(jsonPath("$.details[0].field").value("body"));
        }

        @Test
        void trailingCommaIs400() throws Exception {
            expectMalformed("{\"memberId\":\"HP0000001\",}");
        }

        @Test
        void jsonArrayInsteadOfObjectIs400() throws Exception {
            expectMalformed("[\"HP0000001\"]");
        }

        @Test
        void jsonStringInsteadOfObjectIs400() throws Exception {
            expectMalformed("\"HP0000001\"");
        }

        @Test
        void emptyBodyIs400() throws Exception {
            expectMalformed("")
                    .andExpect(jsonPath("$.details[0].field").value("body"));
        }

        @Test
        void jsonNullBodyIs400() throws Exception {
            expectMalformed("null")
                    .andExpect(jsonPath("$.details[0].field").value("body"));
        }

        @Test
        void nonBooleanImpersonatingIs400NamingTheField() throws Exception {
            expectMalformed("{\"memberId\":\"HP0000001\",\"impersonating\":\"maybe\"}")
                    .andExpect(jsonPath("$.details[0].field").value("impersonating"))
                    .andExpect(jsonPath("$.details[0].message", containsString("not a valid Boolean")));
        }

        @Test
        void nonBooleanExplainIs400NamingTheField() throws Exception {
            expectMalformed("{\"memberId\":\"HP0000001\",\"explain\":\"yes please\"}")
                    .andExpect(jsonPath("$.details[0].field").value("explain"));
        }

        @Test
        void objectAsMemberIdIs400NamingTheField() throws Exception {
            expectMalformed("{\"memberId\":{\"id\":\"HP0000001\"}}")
                    .andExpect(jsonPath("$.details[0].field").value("memberId"));
        }

        @Test
        void arrayAsMemberIdIs400NamingTheField() throws Exception {
            expectMalformed("{\"memberId\":[\"HP0000001\"]}")
                    .andExpect(jsonPath("$.details[0].field").value("memberId"));
        }

        /** Jackson's default scalar coercion turns a JSON number into the string the pattern then validates. */
        @Test
        void numericMemberIdIsCoercedToTextAndValidatedAsSuch() throws Exception {
            when(service.profile(any())).thenReturn(MemberProfileControllerTest.sampleResponse(false));
            perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":12345}"))
                    .andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------------------- validation shape

    @Nested
    class ValidationShape {

        @Test
        void validationErrorCarriesOneDetailPerViolationAllOnMemberId() throws Exception {
            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"\"}")),
                    HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR)
                    .andExpect(jsonPath("$.message").value("request is not valid"))
                    .andExpect(jsonPath("$.details", not(empty())))
                    .andExpect(jsonPath("$.details[0].field").value("memberId"))
                    .andExpect(jsonPath("$.details[0].message").isString());
            verifyNoInteractions(service);
        }

        @Test
        void patternViolationExplainsTheAllowedCharacters() throws Exception {
            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"memberId\":\"HP 1\"}")),
                    HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR)
                    .andExpect(jsonPath("$.details[0].field").value("memberId"))
                    .andExpect(jsonPath("$.details[0].message").value("must be letters, digits, '_' or '-'"));
        }
    }

    // ------------------------------------------------------------------------------- service failures

    @Nested
    class ServiceFailures {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.class)
        void everyErrorCodeMapsToItsHttpStatusAndKeepsItsMessage(ErrorCode code) throws Exception {
            when(service.profile(any())).thenThrow(new MemberProfileException(code, "boom " + code));

            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)), code.status(), code)
                    .andExpect(jsonPath("$.message").value(code.message()))
                    .andExpect(jsonPath("$.details", empty()));
        }

        @Test
        void memberNotFoundIs404() throws Exception {
            when(service.profile(any())).thenThrow(MemberProfileException.memberNotFound("HP0000001"));

            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.NOT_FOUND, ErrorCode.MEMBER_NOT_FOUND)
                    .andExpect(jsonPath("$.message").value(ErrorCode.MEMBER_NOT_FOUND.message()));
        }

        @Test
        void memberDataIncompleteIs422() throws Exception {
            when(service.profile(any())).thenThrow(MemberProfileException.memberDataIncomplete("HP0000001", "memberTypeCode", "memberTypeCode (company) is missing"));

            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.MEMBER_DATA_INCOMPLETE)
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.message").value(ErrorCode.MEMBER_DATA_INCOMPLETE.message()))
                    .andExpect(jsonPath("$.details[0].field").value("memberTypeCode"));
        }

        @Test
        void memberDomainErrorIs502() throws Exception {
            when(service.profile(any())).thenThrow(new MemberProfileException(ErrorCode.MEMBER_DOMAIN_ERROR, "MemberDomain answered 500"));
            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.BAD_GATEWAY, ErrorCode.MEMBER_DOMAIN_ERROR).andExpect(status().is(502));
        }

        @Test
        void memberDomainUnreachableIs504() throws Exception {
            when(service.profile(any())).thenThrow(new MemberProfileException(ErrorCode.MEMBER_DOMAIN_UNREACHABLE, "timed out"));
            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.GATEWAY_TIMEOUT, ErrorCode.MEMBER_DOMAIN_UNREACHABLE).andExpect(status().is(504));
        }

        @Test
        void ruleDataInvalidIs500WithItsOwnCode() throws Exception {
            when(service.profile(any())).thenThrow(MemberProfileException.ruleDataInvalid("segment_rule 7 has unknown comparison_operator 'LIKE'"));
            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.RULE_DATA_INVALID)
                    .andExpect(jsonPath("$.message").value(ErrorCode.RULE_DATA_INVALID.message()));
        }

        @Test
        void detailsOfAMemberProfileExceptionArePassedThrough() throws Exception {
            when(service.profile(any())).thenThrow(new MemberProfileException(ErrorCode.MEMBER_DATA_INCOMPLETE, "incomplete",
                    List.of(new ApiError.Detail("familyMembers[0].age", "missing"), new ApiError.Detail("memberTypeCode", "missing")), null));

            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.MEMBER_DATA_INCOMPLETE)
                    .andExpect(jsonPath("$.details.length()").value(2))
                    .andExpect(jsonPath("$.details[0].field").value("familyMembers[0].age"))
                    .andExpect(jsonPath("$.details[0].message").value("missing"))
                    .andExpect(jsonPath("$.details[1].field").value("memberTypeCode"));
        }

        @Test
        void unexpectedRuntimeExceptionIs500InternalErrorWithoutTheMessage() throws Exception {
            when(service.profile(any())).thenThrow(new IllegalStateException("jdbc://user:secret@db/member_profile exploded"));

            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
                    .andExpect(jsonPath("$.message").value("unexpected error"))
                    .andExpect(jsonPath("$.details", empty()))
                    .andExpect(content().string(not(containsString("secret"))))
                    .andExpect(content().string(not(containsString("exploded"))))
                    .andExpect(content().string(not(containsString("IllegalStateException"))));
        }

        @Test
        void nullPointerExceptionIs500InternalError() throws Exception {
            when(service.profile(any())).thenThrow(new NullPointerException("member.attributes()"));

            expectApiError(perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR)
                    .andExpect(jsonPath("$.message").value("unexpected error"))
                    .andExpect(content().string(not(containsString("attributes"))));
        }
    }

    // ------------------------------------------------------------------------------- unknown path

    @Nested
    class UnknownPath {

        @ParameterizedTest(name = "POST {0}")
        @ValueSource(strings = {"/api/v1/nope", "/api/v2/member-profile", "/api/v1/member-profile/HP0000001", "/member-profile"})
        void postToAnUnknownPathIs404MalformedRequest(String path) throws Exception {
            expectApiError(perform(post(path).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY)),
                    HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND)
                    .andExpect(jsonPath("$.message", containsString("no such endpoint")))
                    .andExpect(jsonPath("$.details", empty()));
            verifyNoInteractions(service);
        }

        @Test
        void getOfAnUnknownPathIs404MalformedRequest() throws Exception {
            expectApiError(perform(get("/api/v1/member-profile/HP0000001")), HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND)
                    .andExpect(jsonPath("$.message").value(ErrorCode.NOT_FOUND.message()));
            verifyNoInteractions(service);
        }
    }

    // ------------------------------------------------------------------------------- handler methods directly

    @Nested
    class HandlerMethods {

        private final RestExceptionHandler handler = new RestExceptionHandler();

        record Holder(List<Integer> items) {
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(ErrorCode.class)
        void memberProfileExceptionRespondsWithTheCodeStatusAndTheExceptionsApiError(ErrorCode code) {
            MemberProfileException e = new MemberProfileException(code, "msg " + code,
                    List.of(new ApiError.Detail("f", "m")), null);

            ResponseEntity<ApiError> response = handler.memberProfile(e);

            assertThat(response.getStatusCode()).isEqualTo(code.status());
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
            assertThat(response.getBody()).isEqualTo(e.toApiError());
            assertThat(response.getBody().status()).isEqualTo("ERROR");
            assertThat(response.getBody().details()).containsExactly(new ApiError.Detail("f", "m"));
        }

        @Test
        void unexpectedExceptionNeverEchoesItsMessage() {
            ResponseEntity<ApiError> response = handler.unexpected(new RuntimeException("password=hunter2"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(response.getBody()).isEqualTo(ApiError.of(ErrorCode.INTERNAL_ERROR, "unexpected error"));
            assertThat(response.getBody().message()).doesNotContain("hunter2");
        }

        @Test
        void unreadableBodyNamesTheNestedPathIncludingArrayIndexes() {
            Throwable thrown = catchThrowable(() -> JsonMapper.builder().build().readValue("{\"items\":[1,\"x\"]}", Holder.class));
            assertThat(thrown).isInstanceOf(DatabindException.class);
            DatabindException cause = (DatabindException) thrown;

            ResponseEntity<ApiError> response = handler.unreadable(
                    new HttpMessageNotReadableException("JSON parse error", cause, new MockHttpInputMessage(new byte[0])));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().code()).isEqualTo(ErrorCode.MALFORMED_REQUEST);
            assertThat(response.getBody().details()).hasSize(1);
            assertThat(response.getBody().details().get(0).field()).isEqualTo("items.[1]");
            assertThat(response.getBody().details().get(0).message()).contains("not a valid").doesNotContain("'x'");
        }

        @Test
        void unreadableBodyWithoutAJacksonCauseFallsBackToTheBodyField() {
            ResponseEntity<ApiError> response = handler.unreadable(
                    new HttpMessageNotReadableException("Required request body is missing", new MockHttpInputMessage(new byte[0])));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().details()).hasSize(1);
            assertThat(response.getBody().details().get(0).field()).isEqualTo("body");
            assertThat(response.getBody().details().get(0).message()).isEqualTo("missing or not readable as JSON");
        }
    }
}
