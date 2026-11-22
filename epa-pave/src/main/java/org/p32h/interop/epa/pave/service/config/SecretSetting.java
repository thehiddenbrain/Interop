package org.p32h.interop.epa.pave.service.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.postman.Placeholders;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * A stored secret as a screen may see it (R-04): whether it is set, and the reference it is when
 * it is wholly one, such as {@code {{env:PAVE_ONYX_CLIENT_SECRET}}} or {@code {{clientSecret}}}. A
 * reference names where the secret is read from and is not itself a secret. Any other value is
 * never returned, in whole or in part.
 *
 * @param set       true when a value is stored
 * @param reference the value when it is wholly one placeholder, or absent
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecretSetting(boolean set, String reference) {

    private static final SecretSetting NOT_SET = new SecretSetting(false, null);

    /** What a screen may see of a stored value. */
    public static SecretSetting of(String stored) {
        if (!Texts.present(stored)) {
            return NOT_SET;
        }
        return new SecretSetting(true, reference(stored) ? stored.strip() : null);
    }

    /** True when the value is wholly one placeholder: kept as a reference, and shown as one. */
    static boolean reference(String value) {
        return Placeholders.sole(value) != null;
    }
}
