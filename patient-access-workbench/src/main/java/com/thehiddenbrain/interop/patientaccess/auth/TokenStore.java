package com.thehiddenbrain.interop.patientaccess.auth;

import tools.jackson.core.type.TypeReference;
import com.thehiddenbrain.interop.patientaccess.common.JsonFile;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tokens per environment, kept (encrypted) in {@code <data-dir>/tokens.json} so a login survives a restart. */
@Component
public class TokenStore {

    private static final TypeReference<List<AccessToken>> LIST = new TypeReference<>() {
    };

    private final JsonFile file;
    private final Map<String, AccessToken> byEnvironment = new LinkedHashMap<>();

    @Autowired
    public TokenStore(WorkbenchProperties properties) {
        this(properties.dataDirPath().resolve("tokens.json"));
    }

    public TokenStore(Path path) {
        this.file = new JsonFile(path);
        file.read(LIST).ifPresent(list -> list.forEach(t -> byEnvironment.put(t.environmentId(), t)));
    }

    public synchronized Optional<AccessToken> find(String environmentId) {
        return Optional.ofNullable(byEnvironment.get(environmentId));
    }

    public synchronized void put(AccessToken token) {
        byEnvironment.put(token.environmentId(), token);
        file.write(List.copyOf(byEnvironment.values()));
    }

    public synchronized void remove(String environmentId) {
        if (byEnvironment.remove(environmentId) != null) {
            file.write(List.copyOf(byEnvironment.values()));
        }
    }
}
