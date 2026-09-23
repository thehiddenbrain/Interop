package org.point32health.memberprofile.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MemberProfileRequest}: the body of the login call.
 * <ul>
 *   <li>The compact constructor turns absent or null flags into {@code false}, so the service only ever sees
 *       booleans ({@code isImpersonating()}, {@code isExplain()}).</li>
 *   <li>The bean-validation constraints spell out catalog section 1: not blank, at most 30 characters, only
 *       {@code [A-Za-z0-9_-]}; every violation is reported on {@code memberId}.</li>
 *   <li>Jackson 3 deserializes it from the documented JSON, with or without the optional flags.</li>
 * </ul>
 */
class MemberProfileRequestTest {

    static final String THIRTY = "HP0000000000000000000000000001";

    static ValidatorFactory factory;
    static Validator validator;
    static final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    static void validator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void close() {
        factory.close();
    }

    private static Set<ConstraintViolation<MemberProfileRequest>> violations(String memberId) {
        return validator.validate(new MemberProfileRequest(memberId, null, null));
    }

    // ------------------------------------------------------------------------------- normalization

    @Nested
    class FlagNormalization {

        @Test
        void nullFlagsBecomeFalse() {
            MemberProfileRequest request = new MemberProfileRequest("HP0000001", null, null);
            assertThat(request.impersonating()).isFalse();
            assertThat(request.explain()).isFalse();
            assertThat(request.isImpersonating()).isFalse();
            assertThat(request.isExplain()).isFalse();
        }

        @Test
        void trueFlagsStayTrue() {
            MemberProfileRequest request = new MemberProfileRequest("HP0000001", true, true);
            assertThat(request.isImpersonating()).isTrue();
            assertThat(request.isExplain()).isTrue();
        }

        @Test
        void falseFlagsStayFalse() {
            MemberProfileRequest request = new MemberProfileRequest("HP0000001", false, false);
            assertThat(request.isImpersonating()).isFalse();
            assertThat(request.isExplain()).isFalse();
        }

        @Test
        void flagsAreIndependentOfEachOther() {
            assertThat(new MemberProfileRequest("HP0000001", true, null).isImpersonating()).isTrue();
            assertThat(new MemberProfileRequest("HP0000001", true, null).isExplain()).isFalse();
            assertThat(new MemberProfileRequest("HP0000001", null, true).isImpersonating()).isFalse();
            assertThat(new MemberProfileRequest("HP0000001", null, true).isExplain()).isTrue();
        }

        @Test
        void aNullFlagRequestEqualsTheExplicitFalseRequest() {
            assertThat(new MemberProfileRequest("HP0000001", null, null))
                    .isEqualTo(new MemberProfileRequest("HP0000001", false, false))
                    .hasSameHashCodeAs(new MemberProfileRequest("HP0000001", false, false));
        }

        @Test
        void memberIdIsKeptVerbatimNotTrimmedOrUpperCased() {
            // Normalization would hide a validation failure; the id must arrive clean.
            assertThat(new MemberProfileRequest(" hp1 ", null, null).memberId()).isEqualTo(" hp1 ");
        }
    }

    // ------------------------------------------------------------------------------- validation

    @Nested
    class MemberIdConstraints {

        @ParameterizedTest(name = "memberId ''{0}''")
        @ValueSource(strings = {"HP0000001", "TH0000001", "abc-DEF_123", "1", "_", "-", "a", "A-B_c-9"})
        void idsWithinTheAllowedAlphabetAreValid(String memberId) {
            assertThat(violations(memberId)).isEmpty();
        }

        @Test
        void thirtyCharactersIsTheLongestValidId() {
            assertThat(THIRTY).hasSize(30);
            assertThat(violations(THIRTY)).isEmpty();
        }

        @Test
        void thirtyOneCharactersIsTooLong() {
            Set<ConstraintViolation<MemberProfileRequest>> violations = violations(THIRTY + "X");
            assertThat(violations).hasSize(1);
            assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("memberId");
        }

        @ParameterizedTest(name = "memberId ''{0}''")
        @NullSource
        @ValueSource(strings = {"", " ", "   ", "\t", "\n"})
        void missingOrBlankIdIsInvalid(String memberId) {
            Set<ConstraintViolation<MemberProfileRequest>> violations = violations(memberId);
            assertThat(violations).isNotEmpty();
            assertThat(violations).allSatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("memberId"));
        }

        @ParameterizedTest(name = "memberId ''{0}''")
        @ValueSource(strings = {"a b", "HP;drop", "HP.1", "HP/1", "HP\\1", "HP@1", "HP#1", "HP+1", "HP%201", "HP=1",
                "ünïcode", "HPé", "<script>", "HP0000001'", " HP0000001", "HP0000001 ", "HP\n1", "🙂"})
        void idsWithCharactersOutsideTheAlphabetAreInvalid(String memberId) {
            Set<ConstraintViolation<MemberProfileRequest>> violations = violations(memberId);
            assertThat(violations).isNotEmpty();
            assertThat(violations).allSatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("memberId"));
            assertThat(violations).anySatisfy(v -> assertThat(v.getMessage()).isEqualTo("must be letters, digits, '_' or '-'"));
        }

        @Test
        void tooLongAndBadCharactersAreReportedTogether() {
            Set<ConstraintViolation<MemberProfileRequest>> violations = violations(THIRTY + " !");
            assertThat(violations).hasSize(2);
            assertThat(violations).allSatisfy(v -> assertThat(v.getPropertyPath().toString()).isEqualTo("memberId"));
        }

        @Test
        void flagsHaveNoConstraints() {
            assertThat(validator.validate(new MemberProfileRequest("HP0000001", true, true))).isEmpty();
            assertThat(validator.validate(new MemberProfileRequest("HP0000001", null, null))).isEmpty();
        }
    }

    // ------------------------------------------------------------------------------- JSON

    @Nested
    class JsonBinding {

        @Test
        void minimalBodyBindsWithFalseFlags() {
            MemberProfileRequest request = json.readValue("{\"memberId\":\"HP0000001\"}", MemberProfileRequest.class);
            assertThat(request).isEqualTo(new MemberProfileRequest("HP0000001", false, false));
        }

        @Test
        void fullBodyBinds() {
            MemberProfileRequest request = json.readValue(
                    "{\"memberId\":\"HP0000001\",\"impersonating\":true,\"explain\":true}", MemberProfileRequest.class);
            assertThat(request.memberId()).isEqualTo("HP0000001");
            assertThat(request.isImpersonating()).isTrue();
            assertThat(request.isExplain()).isTrue();
        }

        @Test
        void explicitNullFlagsBindAsFalse() {
            MemberProfileRequest request = json.readValue(
                    "{\"memberId\":\"HP0000001\",\"impersonating\":null,\"explain\":null}", MemberProfileRequest.class);
            assertThat(request.isImpersonating()).isFalse();
            assertThat(request.isExplain()).isFalse();
        }

        @Test
        void explicitFalseFlagsBindAsFalse() {
            MemberProfileRequest request = json.readValue(
                    "{\"memberId\":\"HP0000001\",\"impersonating\":false,\"explain\":false}", MemberProfileRequest.class);
            assertThat(request.isImpersonating()).isFalse();
            assertThat(request.isExplain()).isFalse();
        }

        @Test
        void propertyOrderDoesNotMatter() {
            MemberProfileRequest request = json.readValue(
                    "{\"explain\":true,\"impersonating\":false,\"memberId\":\"HP0000001\"}", MemberProfileRequest.class);
            assertThat(request).isEqualTo(new MemberProfileRequest("HP0000001", false, true));
        }

        @Test
        void missingMemberIdBindsAsNullSoValidationCanReportIt() {
            MemberProfileRequest request = json.readValue("{\"impersonating\":true}", MemberProfileRequest.class);
            assertThat(request.memberId()).isNull();
            assertThat(violations(request.memberId())).isNotEmpty();
        }

        /** Jackson 3 default: unknown properties are ignored (FAIL_ON_UNKNOWN_PROPERTIES off). */
        @Test
        void unknownPropertiesAreIgnored() {
            MemberProfileRequest request = json.readValue(
                    "{\"memberId\":\"HP0000001\",\"userId\":\"u1\",\"explain\":true}", MemberProfileRequest.class);
            assertThat(request).isEqualTo(new MemberProfileRequest("HP0000001", false, true));
        }
    }
}
