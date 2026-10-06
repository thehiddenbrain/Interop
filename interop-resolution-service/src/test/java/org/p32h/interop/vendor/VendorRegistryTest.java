package org.p32h.interop.vendor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.p32h.interop.config.MemberIdProperties.PayerConfig;
import org.p32h.interop.config.MemberIdProperties.VendorConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VendorRegistryTest {

    private static final Payer DEFAULT = new Payer("Point32Health", "Point32Health");

    @Test
    void listsEveryVendorOnceSortedByUpperCaseCode() {
        VendorRegistry r = new VendorRegistry(Map.of(
                "mhk", new VendorConfig("MHK (MedHOK)", VendorIdFormat.SPACED_14, null),
                "EVICORE", new VendorConfig(null, VendorIdFormat.COMPACT_11, null)), DEFAULT);
        assertThat(r.all()).extracting(Vendor::code).containsExactly("EVICORE", "MHK");
        assertThat(r.all().get(0).displayName()).as("the code stands in for a missing display name").isEqualTo("EVICORE");
        assertThat(r.all().get(1).format()).isEqualTo(VendorIdFormat.SPACED_14);
    }

    @Test
    void payerIsTheVendorsOwnForTheCompanyElseTheDefault() {
        VendorRegistry r = new VendorRegistry(Map.of(
                "EVICORE", new VendorConfig(null, VendorIdFormat.COMPACT_11,
                        Map.of("thp", new PayerConfig("TUFTS", "Tufts Health Plan"), "HPHC", new PayerConfig("HPHC", "Harvard Pilgrim"))),
                "MHK", new VendorConfig(null, VendorIdFormat.SPACED_14, null)), DEFAULT);
        Vendor evicore = r.all().get(0);
        assertThat(evicore.payerFor("THP")).isEqualTo(new Payer("TUFTS", "Tufts Health Plan"));
        assertThat(evicore.payerFor(" hphc ")).as("the company as the lookup reports it, case and blanks aside").isEqualTo(new Payer("HPHC", "Harvard Pilgrim"));
        assertThat(evicore.payerFor("OTHER")).as("a company without an entry gets the default").isEqualTo(DEFAULT);
        assertThat(evicore.payerFor(null)).isEqualTo(DEFAULT);
        assertThat(evicore.hasOwnPayerFor("hphc")).isTrue();
        assertThat(evicore.hasOwnPayerFor("OTHER")).isFalse();
        assertThat(evicore.hasOwnPayerFor(null)).isFalse();
        assertThat(r.all().get(1).payerFor("THP")).as("a vendor without entries gets the default").isEqualTo(DEFAULT);
    }

    @Test
    void rejectsAPayerWithoutIdOrNameAndABadCompany() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of("EVICORE", new VendorConfig(null, VendorIdFormat.COMPACT_11,
                Map.of("THP", new PayerConfig("TUFTS", " ")))), DEFAULT))
                .hasMessageContaining("member-id.vendors.EVICORE.payer.THP needs an id and a name");
        assertThatThrownBy(() -> new VendorRegistry(Map.of("EVICORE", new VendorConfig(null, VendorIdFormat.COMPACT_11,
                Map.of("T H P", new PayerConfig("TUFTS", "Tufts")))), DEFAULT))
                .hasMessageContaining("the company must match");
        assertThatThrownBy(() -> new VendorRegistry(Map.of("EVICORE", new VendorConfig(null, VendorIdFormat.COMPACT_11, null)),
                new Payer("", "Point32Health"))).hasMessageContaining("payer.id and payer.name are required");
    }

    @Test
    void rejectsTheSameCodeTwiceIgnoringCase() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of(
                "evicore", new VendorConfig(null, VendorIdFormat.COMPACT_11, null),
                "EVICORE", new VendorConfig(null, VendorIdFormat.SPACED_14, null)), DEFAULT))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EVICORE");
    }

    @Test
    void rejectsMissingFormatAndBadKey() {
        assertThatThrownBy(() -> new VendorRegistry(Map.of("X1", new VendorConfig(null, null, null)), DEFAULT))
                .hasMessageContaining("format is required");
        assertThatThrownBy(() -> new VendorRegistry(Map.of("bad key!", new VendorConfig(null, VendorIdFormat.COMPACT_11, null)), DEFAULT))
                .hasMessageContaining("must match");
        assertThatThrownBy(() -> new VendorRegistry(Map.of(), DEFAULT)).hasMessageContaining("at least one vendor");
    }
}
