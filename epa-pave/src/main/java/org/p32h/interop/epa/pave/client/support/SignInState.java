package org.p32h.interop.epa.pave.client.support;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * How PAVE signs in to the active environment's service right now, in the operator's own words
 * (plan 7.5). Served in {@code /api/mode} as {@code signIn} and on Configuration > Access token.
 * Holds no secret and no member data: the words name a method, a place and a time, never a value.
 *
 * @param code       which state, for the screen to choose a tone by
 * @param shortWords the chip's words, at most a few
 * @param detail     one sentence: the method, where it is set, the token's expiry when signed in
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignInState(Code code, String shortWords, String detail) {

    /** The states a sign-in can be in. */
    public enum Code {
        NONE("No sign-in needed"),
        FIRST_CALL("Signs in on first call"),
        SIGNED_IN("Signed in (renews automatically)"),
        BEARER("Access key configured"),
        PASTED("Using your pasted token"),
        FAILED("Sign-in failed"),
        MOCK("No sign-in on mock");

        private final String words;

        Code(String words) {
            this.words = words;
        }

        /** The chip's words for this state. */
        public String words() {
            return words;
        }
    }

    /** A state with its own chip words and the given sentence. */
    static SignInState of(Code code, String detail) {
        return new SignInState(code, code.words(), detail);
    }

    /**
     * The state on the mock profile, where nothing signs in. The token service exists on live only,
     * so a page that shows a sign-in state on mock takes this one.
     */
    public static SignInState mock() {
        return of(Code.MOCK, "The mock profile answers every call itself, so nothing signs in.");
    }
}
