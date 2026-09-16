package com.thehiddenbrain.interop.patientaccess.environment;

import com.fasterxml.jackson.core.type.TypeReference;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Environments live in {@code <data-dir>/environments.json}. Writes are atomic and guarded by an
 * optimistic {@code version}: an update must carry the version it was based on.
 */
@Component
public class EnvironmentStore {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentStore.class);
    private static final TypeReference<Document> DOC = new TypeReference<>() {
    };

    /** On-disk shape, so the file stays self-describing. */
    public record Document(int formatVersion, List<Environment> environments) {
    }

    private final JsonFile file;
    private final Clock clock;
    private final Map<String, Environment> byId = new LinkedHashMap<>();

    @Autowired
    public EnvironmentStore(WorkbenchProperties properties, Clock clock) {
        this(properties.dataDirPath().resolve("environments.json"), clock);
    }

    public EnvironmentStore(Path path, Clock clock) {
        this.file = new JsonFile(path);
        this.clock = clock;
        file.read(DOC).ifPresent(doc -> {
            for (Environment e : doc.environments()) {
                byId.put(e.id(), e);
            }
            log.info("loaded {} environment(s) from {}", byId.size(), path);
        });
    }

    public Path path() {
        return file.path();
    }

    public synchronized List<Environment> all() {
        List<Environment> list = new ArrayList<>(byId.values());
        list.sort(Comparator.comparing(Environment::name, String.CASE_INSENSITIVE_ORDER));
        return list;
    }

    public synchronized Optional<Environment> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized Environment require(String id) {
        return find(id).orElseThrow(() -> WorkbenchException.notFound("environment", id));
    }

    public synchronized Environment insert(Environment environment) {
        if (byId.containsKey(environment.id())) {
            throw new WorkbenchException(ErrorCode.CONFLICT, "environment id '" + environment.id() + "' already exists");
        }
        Instant now = clock.instant();
        Environment stored = new Environment(environment.id(), environment.name(), environment.vendor(), environment.tier(),
                environment.fhirBaseUrl(), environment.auth(), environment.headers(), environment.identifierSystems(),
                environment.fhir(), environment.igBaseUrls(), environment.implementationGuides(), environment.notes(), environment.enabled(), 1, now, now);
        byId.put(stored.id(), stored);
        persist();
        return stored;
    }

    /** Replaces an environment; {@code expectedVersion} must match the stored version (null skips the check). */
    public synchronized Environment update(Environment environment, Long expectedVersion) {
        Environment current = require(environment.id());
        if (expectedVersion != null && expectedVersion != current.version()) {
            throw new WorkbenchException(ErrorCode.CONFLICT, "environment '" + environment.id() + "' was changed by someone else (version "
                    + current.version() + ", you sent " + expectedVersion + "); reload and retry");
        }
        Environment stored = new Environment(environment.id(), environment.name(), environment.vendor(), environment.tier(),
                environment.fhirBaseUrl(), environment.auth(), environment.headers(), environment.identifierSystems(),
                environment.fhir(), environment.igBaseUrls(), environment.implementationGuides(), environment.notes(), environment.enabled(),
                current.version() + 1, current.createdAt(), clock.instant());
        byId.put(stored.id(), stored);
        persist();
        return stored;
    }

    public synchronized void delete(String id) {
        if (byId.remove(id) == null) {
            throw WorkbenchException.notFound("environment", id);
        }
        persist();
    }

    private void persist() {
        file.write(new Document(1, new ArrayList<>(byId.values())));
    }
}
