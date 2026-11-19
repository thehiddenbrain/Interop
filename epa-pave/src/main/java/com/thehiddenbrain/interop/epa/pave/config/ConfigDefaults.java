package com.thehiddenbrain.interop.epa.pave.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Builds a configuration properties record the same way Spring does, outside a Spring context.
 *
 * <p>Tests obtain every properties record through here and never call a record constructor. A
 * constructor call breaks in every test the moment a component is added to the record; a bind by
 * property name does not. The defaults also come from the {@code @DefaultValue} annotations on the
 * records themselves, so there is one statement of each default and nothing to drift.
 */
public final class ConfigDefaults {

    private ConfigDefaults() {
    }

    /**
     * Binds a record from its defaults plus the given overrides.
     *
     * @param prefix    the properties prefix, for example tracking
     * @param overrides property names relative to the prefix, in the form application.yml uses,
     *                  for example {@code record-member-id}
     */
    public static <T> T bind(Class<T> type, String prefix, Map<String, String> overrides) {
        Map<String, Object> source = new HashMap<>();
        if (overrides != null) {
            overrides.forEach((key, value) -> source.put(prefix + "." + key, value));
        }
        return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate(prefix, type);
    }
}
