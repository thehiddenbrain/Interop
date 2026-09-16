package com.thehiddenbrain.interop.patientaccess.vendor;

import com.thehiddenbrain.interop.patientaccess.config.WorkbenchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The vendor presets from {@code vendors.yaml}: the file next to the jar ({@code paw.vendors-file}) when it
 * exists, otherwise the copy bundled in the jar. Read once at startup; a broken file stops the start with
 * the file name and the parse error rather than silently falling back to the bundled copy.
 */
@Component
public class VendorCatalog {

    private static final Logger log = LoggerFactory.getLogger(VendorCatalog.class);
    private static final String BUNDLED = "vendors.yaml";

    /** Unknown keys in the file fail loudly: a typo in a preset must not turn into a silently ignored field. */
    private static final ObjectMapper YAML = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    record VendorsFile(List<VendorPreset> vendors) {
    }

    private final List<VendorPreset> presets;
    private final String source;

    @Autowired
    public VendorCatalog(WorkbenchProperties properties) {
        Path external = properties.vendorsFilePath();
        if (Files.isRegularFile(external)) {
            this.presets = load(external);
            this.source = external.toString();
        } else {
            this.presets = loadBundled();
            this.source = "bundled " + BUNDLED;
        }
        log.info("vendor presets: {} from {}", presets.stream().map(VendorPreset::key).toList(), source);
    }

    VendorCatalog(List<VendorPreset> presets, String source) {
        this.presets = List.copyOf(presets);
        this.source = source;
    }

    public List<VendorPreset> presets() {
        return presets;
    }

    public Optional<VendorPreset> preset(String key) {
        return presets.stream().filter(p -> p.key().equals(key)).findFirst();
    }

    /** Where the presets came from (the external file path or "bundled vendors.yaml"). */
    public String source() {
        return source;
    }

    static List<VendorPreset> load(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return parse(in, file.toString());
        } catch (IOException e) {
            throw new IllegalStateException("cannot read vendors file " + file + ": " + e.getMessage(), e);
        }
    }

    static List<VendorPreset> loadBundled() {
        try (InputStream in = new ClassPathResource(BUNDLED).getInputStream()) {
            return parse(in, "bundled " + BUNDLED);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read bundled " + BUNDLED, e);
        }
    }

    static List<VendorPreset> parse(InputStream in, String what) {
        VendorsFile parsed;
        try {
            parsed = YAML.readValue(in, VendorsFile.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalStateException("vendors file " + what + " is not valid: " + e.getMessage(), e);
        }
        List<VendorPreset> list = parsed == null || parsed.vendors() == null ? List.of() : parsed.vendors();
        Set<String> keys = new HashSet<>();
        for (VendorPreset p : list) {
            if (!keys.add(p.key())) {
                throw new IllegalStateException("vendors file " + what + " lists the key '" + p.key() + "' twice");
            }
        }
        return List.copyOf(list);
    }
}
