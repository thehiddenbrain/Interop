package com.thehiddenbrain.interop.cms1500.support;

import com.thehiddenbrain.interop.cms1500.pdf.Cms1500FormFiller;
import com.thehiddenbrain.interop.cms1500.pdf.FormText;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public final class TestTemplate {

    private static byte[] bytes;

    private TestTemplate() {
    }

    public static synchronized byte[] bytes() {
        if (bytes == null) {
            try (InputStream in = Objects.requireNonNull(
                    TestTemplate.class.getResourceAsStream("/forms/cms1500-02-12.pdf"), "template on test classpath")) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return bytes;
    }

    public static Cms1500FormFiller filler() {
        return new Cms1500FormFiller(bytes(), new FormText(true, true), "");
    }
}
