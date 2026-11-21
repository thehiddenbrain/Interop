package org.p32h.interop.epa.pave.service.codelist;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.p32h.interop.epa.pave.config.CodelistProperties;

/** Writes a code list into a test's temporary directory and loads it the way the application does. */
final class TestCodeLists {

    private TestCodeLists() {
    }

    static Path write(Path directory, String name, String content) {
        return write(directory, name, content.getBytes(StandardCharsets.UTF_8));
    }

    static Path write(Path directory, String name, byte[] bytes) {
        try {
            return Files.write(directory.resolve(name), bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Loads a file with the default settings plus the ones given, named as application.yml names them. */
    static ProcedureCodeService load(Path file, Map<String, String> settings) {
        Map<String, String> all = new HashMap<>(settings);
        all.put("file", file.toString());
        return new ProcedureCodeService(CodelistProperties.of(all));
    }

    static ProcedureCodeService load(Path file) {
        return load(file, Map.of());
    }

    static ProcedureCodeService csv(Path directory, String content) {
        return load(write(directory, "codes.csv", content));
    }

    static ProcedureCodeService csv(Path directory, String content, Map<String, String> settings) {
        return load(write(directory, "codes.csv", content), settings);
    }

    static ProcedureCodeService json(Path directory, String content) {
        return load(write(directory, "codes.json", content));
    }
}
