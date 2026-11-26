package org.p32h.interop.epa.pave.postman;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The Postman exports the tests upload, read from {@code src/test/resources/postman}.
 *
 * <p>Each file has the shape of a real export. None came from a vendor: every host ends in
 * {@code .invalid}, and every identifier and credential in them is a placeholder.
 */
public final class PostmanFiles {

    /** A sandbox environment with a disabled variable, a blank one, a nested one and two secrets. */
    public static final String SANDBOX_ENVIRONMENT = "onyx-sandbox.postman_environment.json";

    /** An environment whose name says production. Its client secret points at the operating system. */
    public static final String PRODUCTION_ENVIRONMENT = "onyx-production.postman_environment.json";

    public static final String UAT_ENVIRONMENT = "onyx-uat.postman_environment.json";

    /** An environment named like a sandbox whose base url says production. */
    public static final String SANDBOX_IN_NAME_ONLY = "sandbox-in-name-only.postman_environment.json";

    /** Thirteen requests with exactly the names of the vendor's collection, each inside a folder. */
    public static final String VENDOR_COLLECTION = "onyx-vendor.postman_collection.json";

    /** Folders three deep, scripts at every level, a url with no raw form, a path variable, twins. */
    public static final String NESTED_COLLECTION = "nested-folders.postman_collection.json";

    public static final String V1_COLLECTION = "v1.postman_collection.json";

    public static final String V2_0_COLLECTION = "v2-0.postman_collection.json";

    /** The vendor collection cut off after its first five thousand bytes. */
    public static final String TRUNCATED_COLLECTION = "truncated.postman_collection.json";

    private PostmanFiles() {
    }

    public static byte[] bytes(String name) {
        try (InputStream in = PostmanFiles.class.getResourceAsStream("/postman/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No test resource postman/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(String name) {
        return new String(bytes(name), StandardCharsets.UTF_8);
    }
}
