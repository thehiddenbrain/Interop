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
                        Map.of("thp", new PayerConfig("TUFTS", "Tufts Health Plan"), "HPHC", new PayerConfig("HPHC", "Harvard Pilgrim"),
                                "thp_pp", new PayerConfig("THPP", "Tufts Health Public Plans"))),
                "MHK", new VendorConfig(null, VendorIdFormat.SPACED_14, null)), DEFAULT);
        Vendor evicore = r.all().get(0);
        assertThat(evicore.payerFor("THP", "COM", "COM")).isEqualTo(new Payer("TUFTS", "Tufts Health Plan"));
        assertThat(evicore.payerFor("THP", null, null)).as("no line of business at all: the company's entry").isEqualTo(new Payer("TUFTS", "Tufts Health Plan"));
        assertThat(evicore.payerFor("THP", "MA-TOGETHER", " pp ")).as("the entry for the company and the line of business on the record comes before the company's")
                .isEqualTo(new Payer("THPP", "Tufts Health Public Plans"));
        assertThat(evicore.payerFor(" hphc ", "COM", "COM")).as("the company as the lookup reports it, case and blanks aside").isEqualTo(new Payer("HPHC", "Harvard Pilgrim"));
        assertThat(evicore.payerFor("HPHC", "PP", "PP")).as("no entry for that line of business of the company: the company's").isEqualTo(new Payer("HPHC", "Harvard Pilgrim"));
        assertThat(evicore.payerFor("OTHER", "PP", "PP")).as("a company without an entry gets the default").isEqualTo(DEFAULT);
        assertThat(evicore.payerFor(null, "PP", "PP")).isEqualTo(DEFAULT);
        assertThat(evicore.hasOwnPayerFor("hphc", null, null)).isTrue();
        assertThat(evicore.hasOwnPayerFor("OTHER", "PP", "PP")).isFalse();
        assertThat(evicore.hasOwnPayerFor(null, null, null)).isFalse();
        assertThat(r.all().get(1).payerFor("THP", "PP", "PP")).as("a vendor without entries gets the default").isEqualTo(DEFAULT);
    }

    @Test
    void theLineOfBusinessOfTheAnswerComesBeforeTheOneOnTheRecord() {
        VendorRegistry r = new VendorRegistry(Map.of(
                "EVOLENT", new VendorConfig(null, VendorIdFormat.COMPACT_11,
                        Map.of("THP", new PayerConfig("33", "Tufts Health Plan"), "THP_PP", new PayerConfig("32", "Tufts Health Public Plans"),
                                "thp_d-snp", new PayerConfig("33", "Tufts Health Plan, D-SNP")))), DEFAULT);
        Vendor evolent = r.all().get(0);
        assertThat(evolent.payerFor("THP", "D-SNP", "PP")).as("a D-SNP member, whose member record carries PP")
                .isEqualTo(new Payer("33", "Tufts Health Plan, D-SNP"));
        assertThat(evolent.payerFor("THP", " d-snp ", "PP")).as("case and blanks aside").isEqualTo(new Payer("33", "Tufts Health Plan, D-SNP"));
        assertThat(evolent.payerFor("THP", "MA-TOGETHER", "PP")).as("another Public Plans member keeps the entry for PP")
                .isEqualTo(new Payer("32", "Tufts Health Public Plans"));
        assertThat(evolent.payerFor("THP", "PP", "PP")).as("nothing derived: the answer carries the record's value")
                .isEqualTo(new Payer("32", "Tufts Health Public Plans"));
        assertThat(evolent.payerFor("THP", "MA-HMO", "MCR")).as("no entry for either: the company's").isEqualTo(new Payer("33", "Tufts Health Plan"));
        assertThat(evolent.payerFor("HPHC", "D-SNP", "PP")).as("the entry belongs to THP: no entry for this company at all").isEqualTo(DEFAULT);
        assertThat(evolent.hasOwnPayerFor("THP", "D-SNP", null)).isTrue();
        // a table with the line-of-business entry alone: the vendor has its own payer for that member and for no other
        Vendor dsnpOnly = new VendorRegistry(Map.of("CARELON", new VendorConfig(null, VendorIdFormat.COMPACT_11,
                Map.of("THP_D-SNP", new PayerConfig("DSNP", "D-SNP payer")))), DEFAULT).all().get(0);
        assertThat(dsnpOnly.hasOwnPayerFor("THP", "D-SNP", "PP")).as("found through the line of business of the answer").isTrue();
        assertThat(dsnpOnly.hasOwnPayerFor("THP", "MA-TOGETHER", "PP")).isFalse();
        assertThat(dsnpOnly.payerFor("THP", "MA-TOGETHER", "PP")).isEqualTo(DEFAULT);
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
