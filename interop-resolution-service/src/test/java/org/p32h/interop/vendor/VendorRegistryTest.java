package org.p32h.interop.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VendorRegistryTest {

    @Test
    void looksUpByCodeAndAliasCaseInsensitively() {
        VendorRegistry r = new VendorRegistry(Map.of(
                "evicore", new VendorConfig("eviCore", List.of("EVI"), VendorIdFormat.COMPACT_11),
                "MHK", new VendorConfig(null, List.of("medhok"), VendorIdFormat.SPACED_14)));
        assertThat(r.find("EviCore").orElseThrow().code()).isEqualTo("EVICORE");
        assertThat(r.find(" evi ").orElseThrow().format()).isEqualTo(VendorIdFormat.COMPACT_11);
        assertThat(r.find("MedHOK").orElseThrow().code()).isEqualTo("MHK");
        assertThat(r.find("AIMX")).isEmpty();
        assertThat(r.find(null)).isEmpty();
        assertThat(r.knownCodes()).containsExactly("EVICORE", "MHK");
    }

    @Test
    void rejectsDuplicateAliasAcrossVendors() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of(
                "A1", new VendorConfig(null, List.of("SHARED"), VendorIdFormat.COMPACT_11),
                "B1", new VendorConfig(null, List.of("shared"), VendorIdFormat.SPACED_14))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("SHARED");
    }

    @Test
    void rejectsMissingFormatAndBadKey() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of("X1", new VendorConfig(null, null, null))))
                .hasMessageContaining("format is required");
        assertThatThrownBy(() -> new VendorRegistry(Map.of("bad key!", new VendorConfig(null, null, VendorIdFormat.COMPACT_11))))
                .hasMessageContaining("must match");
        assertThatThrownBy(() -> new VendorRegistry(Map.of())).hasMessageContaining("at least one vendor");
    }
}
