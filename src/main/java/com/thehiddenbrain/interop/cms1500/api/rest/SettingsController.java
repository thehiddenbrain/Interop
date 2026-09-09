package com.thehiddenbrain.interop.cms1500.api.rest;

import com.thehiddenbrain.interop.cms1500.config.RuntimeSettings;
import com.thehiddenbrain.interop.cms1500.config.SettingsUpdate;
import com.thehiddenbrain.interop.cms1500.config.SettingsView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live settings for the test UI. Only present when {@code cms1500.ui.enabled} is true. */
@RestController
@RequestMapping("/api/v1/settings")
@ConditionalOnProperty(prefix = "cms1500.ui", name = "enabled", havingValue = "true", matchIfMissing = true)
@Tag(name = "Settings", description = "Folders and policies the service runs with (test UI)")
public class SettingsController {

    private final RuntimeSettings settings;

    public SettingsController(RuntimeSettings settings) {
        this.settings = settings;
    }

    @Operation(summary = "Current settings and whether the folders exist")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public SettingsView get() {
        return settings.view();
    }

    @Operation(summary = "Change settings; only the fields sent are changed, the rest stay as they are",
            description = "Applied immediately and stored in the settings file so they survive a restart. "
                    + "A template that cannot be loaded or lacks form fields is rejected without changing anything.")
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public SettingsView update(@RequestBody SettingsUpdate update) {
        settings.update(update);
        return settings.view();
    }

    @Operation(summary = "Drop the stored overrides and return to the application.yaml values")
    @DeleteMapping(value = "/overrides", produces = MediaType.APPLICATION_JSON_VALUE)
    public SettingsView reset() {
        settings.reset();
        return settings.view();
    }
}
