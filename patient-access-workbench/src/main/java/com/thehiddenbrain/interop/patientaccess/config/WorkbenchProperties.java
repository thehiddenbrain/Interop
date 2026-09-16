package com.thehiddenbrain.interop.patientaccess.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/** Everything under {@code paw.*} in application.yaml. Records with defaults so the app also starts without a yaml. */
@ConfigurationProperties(prefix = "paw")
public record WorkbenchProperties(
        @DefaultValue("./data") String dataDir,
        @DefaultValue("") String masterKey,
        @DefaultValue("") String publicBaseUrl,
        @DefaultValue Ui ui,
        @DefaultValue Demo demo,
        @DefaultValue Security security,
        @DefaultValue Http http,
        @DefaultValue History history,
        @DefaultValue Search search,
        @DefaultValue Conformance conformance,
        @DefaultValue Validation validation) {

    public record Ui(@DefaultValue("true") boolean enabled) {
    }

    /**
     * The in-process demo Patient Access API ({@code /demo/fhir} + {@code /demo/auth}). {@code requireToken}
     * off lets a plain browser hit the FHIR endpoints; the client id / secret are what the demo token
     * endpoint accepts for client credentials.
     */
    public record Demo(@DefaultValue("true") boolean enabled,
                       @DefaultValue("true") boolean requireToken,
                       @DefaultValue("demo-client") String clientId,
                       @DefaultValue("demo-secret") String clientSecret) {

        /** Marks the canonical constructor for Spring's binding; the record also has the short one below. */
        @ConstructorBinding
        public Demo {
        }

        /** Only the on/off switch, with the default credentials (used by callers that predate the other fields). */
        public Demo(boolean enabled) {
            this(enabled, true, "demo-client", "demo-secret");
        }
    }

    public record Security(@DefaultValue Basic basic) {
        public record Basic(@DefaultValue("false") boolean enabled,
                            @DefaultValue("workbench") String username,
                            @DefaultValue("") String password) {
        }
    }

    public record Http(@DefaultValue("10s") Duration connectTimeout,
                       @DefaultValue("60s") Duration readTimeout,
                       @DefaultValue("patient-access-workbench") String userAgent,
                       @DefaultValue("1") int maxRetries) {
    }

    public record History(@DefaultValue("1000") int maxEntries,
                          @DefaultValue("262144") int maxBodyBytes,
                          @DefaultValue("true") boolean persist) {
    }

    public record Search(@DefaultValue("50") int pageSize,
                         @DefaultValue("20") int maxPages) {
    }

    public record Conformance(@DefaultValue("5") int maxPages,
                              @DefaultValue("4") int concurrency,
                              @DefaultValue("3000") long slowWarnMs,
                              @DefaultValue("10000") long slowFailMs) {
    }

    public record Validation(@DefaultValue("./packages") String packagesDir) {
    }

    public Path dataDirPath() {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    public Path packagesDirPath() {
        return Path.of(validation.packagesDir()).toAbsolutePath().normalize();
    }
}
