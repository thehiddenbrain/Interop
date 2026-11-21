package org.p32h.interop.epa.pave.client.lookup;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;

/**
 * Reads the fixture files for the audits, on its own and not through the loader under test, so an
 * audit sees every file that is in the directory and not only the ones the loader chose to keep.
 */
final class FixtureFiles {

    private FixtureFiles() {
    }

    /** Every file the pattern matches, by file name, parsed. A file that is not JSON fails the read. */
    static Map<String, JsonNode> read(String pattern) {
        Map<String, JsonNode> files = new TreeMap<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
                try (InputStream in = resource.getInputStream()) {
                    JsonSafe.Parsed parsed = JsonSafe.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    if (!parsed.ok()) {
                        throw new IllegalStateException(resource.getFilename() + ": " + parsed.error());
                    }
                    files.put(resource.getFilename(), parsed.node());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("The files under " + pattern + " could not be read.", e);
        }
        return files;
    }
}
