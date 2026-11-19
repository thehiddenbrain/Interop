package org.p32h.interop.epa.pave.service.config;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.p32h.interop.epa.pave.config.ConfigStoreProperties;
import org.p32h.interop.epa.pave.config.EpaProperties;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.config.OnyxProperties;
import org.p32h.interop.epa.pave.model.EnvironmentKind;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.postman.PostmanFiles;
import org.p32h.interop.epa.pave.postman.PostmanRequest;
import org.p32h.interop.epa.pave.postman.VariableResolver;

/**
 * A real store on a temporary directory with a real gateway on top of it, for the tests of the
 * configuration layer. Nothing here is a fake: the tests exercise the code that runs.
 *
 * <p>The clock is fixed, generated identifiers count up from one, and operating system variables
 * come from {@link #operatingSystem}, so no test depends on the machine it runs on.
 */
public final class ConfigFixture {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-05T15:04:05Z"), ZoneOffset.UTC);

    public final Path directory;
    public final ConfigStore store;
    public final ConfigStoreGateway gateway;
    public final ConfigService service;
    /** Stands in for the operating system environment. */
    public final Map<String, String> operatingSystem;

    private ConfigFixture(Path directory, String mode, Map<String, String> storeSettings, Map<String, String> onyxSettings,
                          Map<String, String> lookupSettings, Map<String, String> operatingSystem) {
        this.directory = directory;
        this.operatingSystem = operatingSystem;
        Map<String, String> storeProperties = new LinkedHashMap<>(storeSettings);
        storeProperties.put("directory", directory.toString());
        ConfigStoreProperties configStore = ConfigStoreProperties.of(storeProperties);
        OnyxProperties onyx = OnyxProperties.of(onyxSettings);
        LookupProperties lookup = LookupProperties.of(lookupSettings);
        EpaProperties epa = EpaProperties.of(Map.of("mode", mode));
        AtomicInteger guids = new AtomicInteger();
        VariableResolver resolver = new VariableResolver(operatingSystem::get, CLOCK,
                () -> "00000000-0000-4000-8000-" + String.format("%012d", guids.incrementAndGet()));
        this.store = new ConfigStore(configStore, CLOCK);
        this.gateway = new ConfigStoreGateway(store, onyx, lookup, epa, CLOCK, resolver);
        this.service = new ConfigService(store, gateway, configStore, onyx, lookup, epa);
    }

    /** Builds a fixture step by step. */
    public static final class Builder {
        private final Path directory;
        private String mode = EpaProperties.MOCK;
        private final Map<String, String> store = new LinkedHashMap<>();
        private final Map<String, String> onyx = new LinkedHashMap<>();
        private final Map<String, String> lookup = new LinkedHashMap<>();
        private Map<String, String> operatingSystem = new HashMap<>();

        private Builder(Path directory) {
            this.directory = directory;
        }

        public Builder live() {
            this.mode = EpaProperties.LIVE;
            return this;
        }

        public Builder mode(String value) {
            this.mode = value;
            return this;
        }

        /** Sets the configuration floor that lets production writes be unlocked at all. */
        public Builder allowProdWrites() {
            store.put("allow-prod-writes", "true");
            return this;
        }

        public Builder activeEnvironment(String name) {
            store.put("active-environment", name);
            return this;
        }

        /** A property under {@code onyx}, for example {@code pas.url} or {@code variables.baseUrl}. */
        public Builder onyx(String property, String value) {
            onyx.put(property, value);
            return this;
        }

        /** A property under {@code lookup}, for example {@code member.url}. */
        public Builder lookup(String property, String value) {
            lookup.put(property, value);
            return this;
        }

        /** Shares one stand-in operating system between two fixtures, as two runs on one machine would. */
        public Builder operatingSystem(Map<String, String> shared) {
            this.operatingSystem = shared;
            return this;
        }

        public ConfigFixture build() {
            return new ConfigFixture(directory, mode, store, onyx, lookup, operatingSystem);
        }
    }

    public static Builder on(Path directory) {
        return new Builder(directory);
    }

    /** The mock profile with nothing configured. */
    public static ConfigFixture mock(Path directory) {
        return on(directory).build();
    }

    /** The live profile with nothing configured. */
    public static ConfigFixture live(Path directory) {
        return on(directory).live().build();
    }

    /** Uploads one of the test exports as an environment of the given kind, confirming any production sign. */
    public StoredEnvironment environment(String resource, EnvironmentKind kind) {
        return store.uploadEnvironment(resource, PostmanFiles.bytes(resource), kind, true).environment();
    }

    /** Uploads one of the test exports as an environment and makes it the active one. */
    public StoredEnvironment activate(String resource, EnvironmentKind kind) {
        StoredEnvironment environment = environment(resource, kind);
        store.activate(environment.name());
        return environment;
    }

    public StoredCollection collection(String resource) {
        return store.uploadCollection(resource, PostmanFiles.bytes(resource)).collection();
    }

    /** One request of a collection written in a test, built up call by call. */
    public static final class Item {
        private final String name;
        private final String method;
        private final String url;
        private final List<String> headers = new ArrayList<>();
        private final List<String> script = new ArrayList<>();
        private String body;

        private Item(String name, String method, String url) {
            this.name = name;
            this.method = method;
            this.url = url;
        }

        public Item header(String headerName, String value) {
            headers.add("{\"key\": " + quoted(headerName) + ", \"value\": " + quoted(value) + "}");
            return this;
        }

        public Item body(String raw) {
            this.body = raw;
            return this;
        }

        /** Adds one line to the request's own pre-request script. */
        public Item script(String line) {
            script.add(quoted(line));
            return this;
        }

        String json() {
            StringBuilder request = new StringBuilder("{\"method\": " + quoted(method) + ", \"header\": [" + String.join(", ", headers) + "], "
                    + "\"url\": " + quoted(url));
            if (body != null) {
                request.append(", \"body\": {\"mode\": \"raw\", \"raw\": ").append(quoted(body)).append("}");
            }
            String events = script.isEmpty() ? "" : "\"event\": [{\"listen\": \"prerequest\", \"script\": {\"exec\": ["
                    + String.join(", ", script) + "]}}], ";
            return "{\"name\": " + quoted(name) + ", " + events + "\"request\": " + request + "}, \"response\": []}";
        }
    }

    public static Item item(String name, String method, String url) {
        return new Item(name, method, url);
    }

    /** The item list of a collection: these requests, at the top level, in this order. */
    public static String items(Item... items) {
        return "[" + String.join(", ", java.util.Arrays.stream(items).map(Item::json).toList()) + "]";
    }

    /** The variable list of a collection: names and values in turn. */
    public static String variables(String... keysAndValues) {
        List<String> entries = new ArrayList<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            entries.add("{\"key\": " + quoted(keysAndValues[i]) + ", \"value\": " + quoted(keysAndValues[i + 1]) + "}");
        }
        return "[" + String.join(", ", entries) + "]";
    }

    private static String quoted(String text) {
        return JsonSafe.mapper().writeValueAsString(text);
    }

    /** A collection written in the test itself. */
    public StoredCollection collectionOf(String name, String itemsJson) {
        return collectionOf(name, itemsJson, "[]");
    }

    public StoredCollection collectionOf(String name, String itemsJson, String variablesJson) {
        String json = "{\"info\": {\"name\": " + quoted(name) + ", \"schema\": "
                + "\"https://schema.getpostman.com/json/collection/v2.1.0/collection.json\"}, \"variable\": " + variablesJson
                + ", \"item\": " + itemsJson + "}";
        return store.uploadCollection(name + ".json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8)).collection();
    }

    /** An environment written in the test itself: name, kind, then keys and values in turn. */
    public StoredEnvironment environmentOf(String name, EnvironmentKind kind, String... keysAndValues) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            values.append(i == 0 ? "" : ", ").append("{\"key\": ").append(quoted(keysAndValues[i])).append(", \"value\": ")
                    .append(quoted(keysAndValues[i + 1])).append(", \"enabled\": true}");
        }
        String json = "{\"name\": " + quoted(name) + ", \"values\": [" + values + "]}";
        return store.uploadEnvironment(name + ".json", json.getBytes(java.nio.charset.StandardCharsets.UTF_8), kind, true).environment();
    }

    /** As {@link #environmentOf}, and made the active one. */
    public StoredEnvironment activeEnvironmentOf(String name, EnvironmentKind kind, String... keysAndValues) {
        StoredEnvironment environment = environmentOf(name, kind, keysAndValues);
        store.activate(environment.name());
        return environment;
    }

    /** A reference to the request with the given name in a stored collection. */
    public static RequestRef ref(StoredCollection collection, String requestName) {
        for (StoredCollection.Mapped mapped : collection.requests()) {
            PostmanRequest request = mapped.request();
            if (request.name().equals(requestName)) {
                return new RequestRef(collection.id(), request.id());
            }
        }
        throw new IllegalArgumentException("No request named " + requestName);
    }

    /** Corrects the mapping of the named request and returns the collection as it is afterwards. */
    public StoredCollection remap(StoredCollection collection, String requestName, EpaOperation operation) {
        return store.correctMapping(collection.id(), ref(collection, requestName).requestId(), operation).collection();
    }
}
