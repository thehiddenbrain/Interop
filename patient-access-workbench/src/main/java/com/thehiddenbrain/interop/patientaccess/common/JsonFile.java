package com.thehiddenbrain.interop.patientaccess.common;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * A JSON document on disk written atomically (temp file + move) so a crash never leaves a half-written
 * file. Unknown properties are ignored when reading so files written by a newer version still load.
 */
public final class JsonFile {

    public static final ObjectMapper MAPPER = Json.MAPPER;

    private final Path path;

    public JsonFile(Path path) {
        this.path = path;
    }

    public Path path() {
        return path;
    }

    public boolean exists() {
        return Files.isRegularFile(path);
    }

    public <T> Optional<T> read(TypeReference<T> type) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(MAPPER.readValue(path.toFile(), type));
        } catch (JacksonException e) {
            throw new WorkbenchException(ErrorCode.STORAGE_ERROR, "cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    public <T> Optional<T> read(Class<T> type) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(MAPPER.readValue(path.toFile(), type));
        } catch (JacksonException e) {
            throw new WorkbenchException(ErrorCode.STORAGE_ERROR, "cannot read " + path + ": " + e.getMessage(), e);
        }
    }

    public void write(Object value) {
        try {
            Files.createDirectories(path.toAbsolutePath().getParent());
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), value);
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | JacksonException e) {
            throw new WorkbenchException(ErrorCode.STORAGE_ERROR, "cannot write " + path + ": " + e.getMessage(), e);
        }
    }

    public void delete() {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new WorkbenchException(ErrorCode.STORAGE_ERROR, "cannot delete " + path + ": " + e.getMessage(), e);
        }
    }
}
