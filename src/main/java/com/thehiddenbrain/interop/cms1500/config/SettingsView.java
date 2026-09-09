package com.thehiddenbrain.interop.cms1500.config;

import java.util.List;

/** What the settings API returns: the live values plus what the file system says about the folders. */
public record SettingsView(
        String template,
        AttachmentsView attachments,
        OutputView output,
        FormView form,
        String overridesFile,
        boolean overridden,
        String note) {

    public record AttachmentsView(String root, boolean rootExists, List<String> allowedExtensions,
                                  Cms1500Properties.UnsupportedPolicy unsupported,
                                  Cms1500Properties.WhenNonePolicy whenNone) {
    }

    public record OutputView(String root, boolean rootExists, boolean writable, boolean overwrite) {
    }

    public record FormView(boolean uppercase, boolean stripDiagnosisPeriods, String continuationMarker) {
    }
}
