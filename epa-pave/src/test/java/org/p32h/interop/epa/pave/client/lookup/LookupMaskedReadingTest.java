package org.p32h.interop.epa.pave.client.lookup;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.model.SentSecrets;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireRequest;

/**
 * A lookup reads its answer with the masks in place. When a secret this tool holds is also a word
 * the result is read by (the coverage status active, say), the result says, under its message,
 * that it was read from a mask; with a secret the answer does not repeat it says nothing of the
 * kind. Every secret here is a placeholder made up for the test.
 */
class LookupMaskedReadingTest {

    private static final String URL = "https://example.org/p32h-placeholder/lookup/S123456789";
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final String MEMBER = "{\"memberId\":\"S123456789\",\"firstName\":\"Avery\",\"lastName\":\"Placeholder\","
            + "\"dob\":\"1970-01-31\",\"lob\":\"Medicare\",\"coverageStatus\":\"active\"}";
    private static final String PROVIDER = "{\"npi\":\"1234567893\",\"firstName\":\"Pat\",\"lastName\":\"Provider\","
            + "\"networkStatus\":\"in-network\"}";

    private static VendorExchange answered(String body, SentSecrets secrets) {
        return Exchanges.fromResponse(new WireRequest("GET", URL, URL, List.of(), null, true, null, secrets), 200, List.of(), body, 5L, true);
    }

    @Test
    void aMemberFoundWithItsCoverageStatusReadAsAMaskSaysSoUnderItsMessage() {
        MemberLookupResult masked = MemberResponseMapping.map(answered(MEMBER, SentSecrets.builder().variable("statusWord", "active").build()),
                MemberIds.forms("S123456789"), TODAY);
        assertThat(masked.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(masked.maskedReading()).isEqualTo("Read from a mask, not from what the service sent: coverageStatus was read as "
                + "[secret statusWord]. The variable or setting the mask names holds a value that the answers of this service use as an "
                + "ordinary word, so the reading above may be wrong. Look at that value in the Config tab.");
        assertThat(JsonSafe.mapper().writeValueAsString(masked)).contains("\"maskedReading\"");

        MemberLookupResult plain = MemberResponseMapping.map(answered(MEMBER,
                SentSecrets.builder().variable("clientSecret", "placeholder-secret-value-7f3a").build()), MemberIds.forms("S123456789"), TODAY);
        assertThat(plain.message()).isEqualTo("Member found, with coverage reported as active.");
        assertThat(plain.maskedReading()).isNull();
        assertThat(JsonSafe.mapper().writeValueAsString(plain)).doesNotContain("maskedReading");
    }

    @Test
    void aProviderWhoseNetworkStatusOrWhoseWrapperIsReadAsAMaskSaysSoAndOtherwiseNot() {
        ProviderLookupResult byStatus = ProviderResponseMapping.map(answered(PROVIDER,
                SentSecrets.builder().variable("networkWord", "in-network").build()), "1234567893");
        assertThat(byStatus.maskedReading()).startsWith("Read from a mask, not from what the service sent: networkStatus was read as "
                + "[secret networkWord].");

        // A name the record is found under, masked: the record is not found where it is.
        ProviderLookupResult byWrapper = ProviderResponseMapping.map(answered("{\"provider\":" + PROVIDER + "}",
                SentSecrets.builder().variable("wrapWord", "provider").build()), "1234567893");
        assertThat(byWrapper.maskedReading()).startsWith("Read from a mask, not from what the service sent: the name of a value this tool "
                + "reads by was read as [secret wrapWord].");

        ProviderLookupResult plain = ProviderResponseMapping.map(answered(PROVIDER,
                SentSecrets.builder().variable("clientSecret", "placeholder-secret-value-7f3a").build()), "1234567893");
        assertThat(plain.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(plain.maskedReading()).isNull();
    }

    /**
     * The names a lookup result is read by come from the tables of its mapping. Every table of
     * spellings in a mapping is among the fields it reads, so a field added to the mapping and
     * forgotten here would fail this test rather than leave its masks unsaid.
     */
    @Test
    void everyTableOfSpellingsOfAMappingIsAmongTheFieldsItsMaskedReadingLooksAt() throws IllegalAccessException {
        assertThat(MemberResponseMapping.READ).containsExactlyInAnyOrderElementsOf(spellingTables(MemberResponseMapping.class));
        assertThat(ProviderResponseMapping.READ).containsExactlyInAnyOrderElementsOf(spellingTables(ProviderResponseMapping.class));
        assertThat(MemberResponseMapping.READ).containsAll(MemberResponseMapping.DECIDED_BY);
        assertThat(ProviderResponseMapping.READ).containsAll(ProviderResponseMapping.DECIDED_BY);
        assertThat(MemberResponseMapping.WRAPPED_IN).containsAll(MemberResponseMapping.WRAPPERS).containsAll(MemberResponseMapping.ADDRESS_OBJECTS);
        assertThat(ProviderResponseMapping.WRAPPED_IN).containsAll(ProviderResponseMapping.WRAPPERS)
                .containsAll(ProviderResponseMapping.ADDRESS_OBJECTS);
    }

    @SuppressWarnings("unchecked")
    private static List<List<Spelling>> spellingTables(Class<?> mapping) throws IllegalAccessException {
        List<List<Spelling>> out = new ArrayList<>();
        for (Field field : mapping.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == List.class
                    && field.getGenericType() instanceof ParameterizedType type && type.getActualTypeArguments()[0] == Spelling.class) {
                field.setAccessible(true);
                out.add((List<Spelling>) field.get(null));
            }
        }
        return out;
    }
}
