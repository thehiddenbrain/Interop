package org.p32h.interop.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VendorRegistryTest {

    @Test
    void listsEveryVendorOnceSortedByUpperCaseCode() {
        VendorRegistry r = new VendorRegistry(Map.of(
                "mhk", new VendorConfig("MHK (MedHOK)", VendorIdFormat.SPACED_14),
                "EVICORE", new VendorConfig(null, VendorIdFormat.COMPACT_11)));
        assertThat(r.all()).extracting(Vendor::code).containsExactly("EVICORE", "MHK");
        assertThat(r.all().get(0).displayName()).as("the code stands in for a missing display name").isEqualTo("EVICORE");
        assertThat(r.all().get(1).format()).isEqualTo(VendorIdFormat.SPACED_14);
    }

    @Test
    void rejectsTheSameCodeTwiceIgnoringCase() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of(
                "evicore", new VendorConfig(null, VendorIdFormat.COMPACT_11),
                "EVICORE", new VendorConfig(null, VendorIdFormat.SPACED_14))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EVICORE");
    }

    @Test
    void rejectsMissingFormatAndBadKey() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of("X1", new VendorConfig(null, null))))
                .hasMessageContaining("format is required");
        assertThatThrownBy(() -> new VendorRegistry(Map.of("bad key!", new VendorConfig(null, VendorIdFormat.COMPACT_11))))
                .hasMessageContaining("must match");
        assertThatThrownBy(() -> new VendorRegistry(Map.of())).hasMessageContaining("at least one vendor");
    }
}
