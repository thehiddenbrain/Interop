package com.thehiddenbrain.interop.patientaccess.conformance;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.support.IValidationSupport;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.LenientErrorHandler;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ValidationOptions;
import ca.uhn.fhir.validation.ValidationResult;
import tools.jackson.databind.JsonNode;
import com.thehiddenbrain.interop.patientaccess.common.ErrorCode;
import com.thehiddenbrain.interop.patientaccess.common.WorkbenchException;
import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService;
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport;
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain;
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.utilities.npm.NpmPackage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Full HL7 profile validation with the HAPI validator. Base R4 validation always works; validation
 * against C4BB / PDex / US Core profiles needs the IG npm packages (*.tgz) and their dependencies in
 * {@code paw.validation.packages-dir}. The validator is built lazily on first use because loading the
 * packages takes seconds and a few hundred MB.
 */
@Component
public class FullValidator {

    private static final Logger log = LoggerFactory.getLogger(FullValidator.class);

    public record Issue(String severity, String location, String message) {
    }

    public record Result(boolean valid, int errors, int warnings, int infos, List<Issue> issues, List<String> packages, String profile) {
    }

    public record Status(boolean ready, Path packagesDir, List<String> packages, String note) {
    }

    private final WorkbenchProperties properties;
    private final FhirContext ctx = FhirContext.forR4Cached();
    private volatile FhirValidator validator;
    private volatile List<String> loadedPackages = List.of();
    private volatile String note;

    public FullValidator(WorkbenchProperties properties) {
        this.properties = properties;
    }

    public Status status() {
        Path dir = properties.packagesDirPath();
        List<String> files = packageFiles(dir).stream().map(p -> p.getFileName().toString()).toList();
        return new Status(validator != null, dir, validator != null ? loadedPackages : files,
                validator != null ? note : (files.isEmpty() ? "no *.tgz packages in " + dir + "; only base FHIR R4 validation is available" : "validator not built yet (built on first use)"));
    }

    /** Validates a resource, against {@code profile} when given, else against meta.profile / the base definition. */
    public Result validate(JsonNode resource, String profile) {
        if (resource == null || !resource.hasNonNull("resourceType")) {
            throw new WorkbenchException(ErrorCode.VALIDATION_ERROR, "a FHIR resource with resourceType is required");
        }
        FhirValidator v = validator();
        ValidationOptions options = new ValidationOptions();
        if (profile != null && !profile.isBlank()) {
            options.addProfile(profile.trim());
        }
        ValidationResult result;
        try {
            result = v.validateWithResult(resource.toString(), options);
        } catch (RuntimeException e) {
            throw new WorkbenchException(ErrorCode.INTERNAL_ERROR, "validation failed: " + e.getMessage(), e);
        }
        List<Issue> issues = new ArrayList<>();
        int errors = 0;
        int warnings = 0;
        int infos = 0;
        for (SingleValidationMessage m : result.getMessages()) {
            String sev = m.getSeverity() == null ? "information" : m.getSeverity().getCode();
            switch (sev) {
                case "fatal", "error" -> errors++;
                case "warning" -> warnings++;
                default -> infos++;
            }
            issues.add(new Issue(sev, m.getLocationString(), m.getMessage()));
        }
        return new Result(errors == 0, errors, warnings, infos, issues, loadedPackages, profile);
    }

    private FhirValidator validator() {
        FhirValidator v = validator;
        if (v == null) {
            synchronized (this) {
                v = validator;
                if (v == null) {
                    v = build();
                    validator = v;
                }
            }
        }
        return v;
    }

    private FhirValidator build() {
        long start = System.currentTimeMillis();
        List<IValidationSupport> chain = new ArrayList<>();
        chain.add(ctx.getValidationSupport()); // DefaultProfileValidationSupport: base R4 definitions
        List<String> loaded = new ArrayList<>();
        PrePopulatedValidationSupport packages = new PrePopulatedValidationSupport(ctx);
        IParser parser = ctx.newJsonParser().setParserErrorHandler(new LenientErrorHandler(false));
        for (Path file : packageFiles(properties.packagesDirPath())) {
            try (InputStream in = Files.newInputStream(file)) {
                NpmPackage pkg = NpmPackage.fromPackage(in);
                int count = 0;
                for (String name : pkg.listResources("StructureDefinition", "ValueSet", "CodeSystem")) {
                    try (InputStream res = pkg.loadResource(name)) {
                        IBaseResource r = parser.parseResource(res);
                        packages.addResource(r);
                        count++;
                    } catch (Exception e) {
                        log.debug("skipping {} in {}: {}", name, file.getFileName(), e.getMessage());
                    }
                }
                loaded.add(pkg.name() + "#" + pkg.version() + " (" + count + " definitions)");
            } catch (IOException | RuntimeException e) {
                loaded.add(file.getFileName() + " (failed: " + e.getMessage() + ")");
                log.warn("cannot load IG package {}: {}", file, e.getMessage());
            }
        }
        chain.add(packages);
        chain.add(new InMemoryTerminologyServerValidationSupport(ctx));
        chain.add(new CommonCodeSystemsTerminologyService(ctx));
        chain.add(new SnapshotGeneratingValidationSupport(ctx));
        ValidationSupportChain support = new ValidationSupportChain(chain);
        FhirInstanceValidator module = new FhirInstanceValidator(support);
        module.setAnyExtensionsAllowed(true);
        module.setErrorForUnknownProfiles(true);
        module.setNoTerminologyChecks(false);
        FhirValidator v = ctx.newValidator();
        v.registerValidatorModule(module);
        loadedPackages = loaded;
        note = "validator built in " + (System.currentTimeMillis() - start) + " ms with " + loaded.size() + " package(s)";
        log.info(note);
        return v;
    }

    static List<Path> packageFiles(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".tgz")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
