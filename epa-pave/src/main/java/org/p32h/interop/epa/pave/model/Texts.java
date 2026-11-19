package org.p32h.interop.epa.pave.model;

/**
 * String guards for values that can be absent, null or blank.
 *
 * <p>A guard that checks only {@code != null} on a field that can arrive blank has produced both the
 * literal text "null" on screen and a NullPointerException further down in earlier builds of this
 * tool. Every such check goes through {@link #present(String)}.
 */
public final class Texts {

    private Texts() {
    }

    /** True when the value is neither null nor blank. */
    public static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    /** True when the value is null or blank. */
    public static boolean blank(String value) {
        return !present(value);
    }

    /** The stripped value, or null when it is not present. Never returns an empty string. */
    public static String trimToNull(String value) {
        return present(value) ? value.strip() : null;
    }

    /** The value when present, otherwise the fallback. */
    public static String orElse(String value, String fallback) {
        return present(value) ? value : fallback;
    }

    /** The first present value, stripped, or null when none is present. */
    public static String firstPresent(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (present(value)) {
                return value.strip();
            }
        }
        return null;
    }
}
