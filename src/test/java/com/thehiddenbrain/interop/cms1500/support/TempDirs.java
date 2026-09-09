package com.thehiddenbrain.interop.cms1500.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Per-test-class attachment and output folders wired into the Spring context. */
public final class TempDirs {

    public final Path attachments;
    public final Path output;

    private TempDirs(Path attachments, Path output) {
        this.attachments = attachments;
        this.output = output;
    }

    public static TempDirs create(String prefix) {
        try {
            Path base = Files.createTempDirectory(prefix);
            return new TempDirs(Files.createDirectories(base.resolve("attachments")),
                    Files.createDirectories(base.resolve("bundles")));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public void register(DynamicPropertyRegistry registry) {
        registry.add("cms1500.attachments.root", attachments::toString);
        registry.add("cms1500.output.root", output::toString);
    }
}
