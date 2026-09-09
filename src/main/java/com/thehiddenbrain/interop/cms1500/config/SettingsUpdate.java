package com.thehiddenbrain.interop.cms1500.config;

import java.util.List;

/**
 * A change to the live settings. Every field is optional: {@code null} leaves the current value
 * unchanged. The same shape, fully populated, is what the settings file stores.
 */
public record SettingsUpdate(
        String template,
        String attachmentsRoot,
        List<String> allowedExtensions,
        Cms1500Properties.UnsupportedPolicy unsupported,
        Cms1500Properties.WhenNonePolicy whenNone,
        String outputRoot,
        Boolean overwrite,
        Boolean uppercase,
        Boolean stripDiagnosisPeriods,
        String continuationMarker) {

    /** Full snapshot of the given settings. */
    public static SettingsUpdate snapshot(Cms1500Properties p) {
        return new SettingsUpdate(p.template(), p.attachments().root(), p.attachments().allowedExtensions(),
                p.attachments().unsupported(), p.attachments().whenNone(), p.output().root(), p.output().overwrite(),
                p.form().uppercase(), p.form().stripDiagnosisPeriods(), p.form().continuationMarker());
    }
}
