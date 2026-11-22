package org.p32h.interop.epa.pave.service.config;

import org.p32h.interop.epa.pave.model.Texts;

/**
 * One header a lookup saved on screen sends (R-11, plan 9.2). The value may hold
 * {@code {{placeholders}}}, an operating system value among them, and is resolved against the
 * snapshot the call is prepared from.
 *
 * @param name   the header's name
 * @param value  the value as saved, placeholders intact
 * @param secret true when the screen saved it as a secret. Such a value is never shown and is held,
 *               so a service that repeats it is masked; a header that is a credential by its own
 *               name is treated so whatever this says
 */
public record HeaderSetting(String name, String value, boolean secret) {

    public HeaderSetting {
        if (Texts.blank(name)) {
            throw new IllegalArgumentException("A header needs a name.");
        }
        name = name.strip();
        value = value == null ? "" : value;
    }

    /** Names the header and never its value, which may be a key. */
    @Override
    public String toString() {
        return "HeaderSetting[" + name + (secret ? ", secret" : "") + "]";
    }
}
