package org.p32h.interop.epa.pave.service.lookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.client.lookup.LookupFixtures;
import org.p32h.interop.epa.pave.client.lookup.MockProviderLookupClient;
import org.p32h.interop.epa.pave.client.lookup.ProviderLookupClient;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.FieldError;
import org.p32h.interop.epa.pave.model.LookupStatus;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ProviderLookupResult;
import org.p32h.interop.epa.pave.service.error.ValidationException;

/** Provider lookup as a service: the check digit is validated before the directory is asked anything. */
class ProviderLookupServiceTest {

    private static final String VALID = Npi.withCheckDigit("123456789");

    private static final class RecordingClient implements ProviderLookupClient {
        private final List<String> asked = new ArrayList<>();

        @Override
        public ProviderLookupResult lookup(String npi) {
            asked.add(npi);
            return new ProviderLookupResult(LookupStatus.FOUND, npi, ProviderDetail.builder().npi(npi).lastName("Placeholder").build(),
                    List.of(), List.of(), List.of(), null, null, "Provider found.");
        }
    }

    private final RecordingClient client = new RecordingClient();
    private final ProviderLookupService service = new ProviderLookupService(client);

    @Test
    void aValidNpiIsLookedUpAndTheAnswerIsPassedOn() {
        ProviderLookupResult result = service.lookup(VALID);
        assertThat(client.asked).containsExactly("1234567893");
        assertThat(result.status()).isEqualTo(LookupStatus.FOUND);
        assertThat(result.npi()).isEqualTo("1234567893");
        assertThat(result.provider().lastName()).isEqualTo("Placeholder");
    }

    @Test
    void spacesAroundAPastedNpiAreNotPartOfIt() {
        service.lookup("  1234567893\t");
        assertThat(client.asked).containsExactly("1234567893");
    }

    @Test
    void anNpiWhoseCheckDigitIsWrongIsATypingMistakeAndTheDirectoryIsNotAsked() {
        // Every other last digit of a valid NPI is a wrong one.
        for (char last = '0'; last <= '9'; last++) {
            String npi = "123456789" + last;
            if (npi.equals(VALID)) {
                continue;
            }
            assertThatThrownBy(() -> service.lookup(npi)).as(npi).isInstanceOfSatisfying(ValidationException.class, e ->
                    assertThat(e.fieldErrors()).containsExactly(new FieldError("npi", "The check digit of this NPI does not match, so it "
                            + "is probably a typing mistake. The directory was not asked.")));
        }
        // Two digits swapped, the commonest slip of all.
        assertThatThrownBy(() -> service.lookup("1234567983")).isInstanceOf(ValidationException.class)
                .hasMessage("This is not an NPI that can be looked up. Nothing was sent.");
        assertThat(client.asked).isEmpty();
    }

    @Test
    void anythingThatIsNotTenDigitsIsRefusedWithoutAskingTheDirectory() {
        for (String npi : List.of("123456789", "12345678931", "12345 67893", "123-456-7893", "123456789X", "NPI1234567893",
                "１234567893", "1234567893.0")) {
            assertThatThrownBy(() -> service.lookup(npi)).as(npi).isInstanceOfSatisfying(ValidationException.class, e -> {
                assertThat(e.fieldErrors()).hasSize(1);
                assertThat(e.fieldErrors().get(0).field()).isEqualTo("npi");
                assertThat(e.fieldErrors().get(0).message()).isEqualTo("An NPI is exactly ten digits. This is probably a typing "
                        + "mistake, so the directory was not asked.");
                // What was typed is not repeated back in the message.
                assertThat(e.fieldErrors().get(0).message()).doesNotContain(npi);
            });
        }
        for (String blank : Arrays.asList(null, "", "   ")) {
            assertThatThrownBy(() -> service.lookup(blank)).isInstanceOfSatisfying(ValidationException.class, e ->
                    assertThat(e.fieldErrors()).containsExactly(new FieldError("npi", "Enter an NPI. Nothing was looked up.")));
        }
        assertThat(client.asked).isEmpty();
    }

    @Test
    void theNotFoundSentinelIsAValidNpiSoItIsAskedAndAnsweredAsNotFound() {
        ProviderLookupService onMock = new ProviderLookupService(new MockProviderLookupClient(new LookupFixtures(), LookupProperties.defaults()));
        assertThat(Npi.isValid("9999999995")).isTrue();
        ProviderLookupResult notFound = onMock.lookup("9999999995");
        assertThat(notFound.status()).isEqualTo(LookupStatus.NOT_FOUND);
        assertThat(notFound.error()).isNull();
        // Not found comes from the directory. A wrong check digit never gets that far, and is a different answer.
        assertThatThrownBy(() -> onMock.lookup("9999999999")).isInstanceOf(ValidationException.class);
        assertThat(onMock.lookup("1234567893").status()).isEqualTo(LookupStatus.FOUND);
    }

    @Test
    void aClientThatReturnsNothingIsADefectAndIsNotPassedOffAsAnAnswer() {
        ProviderLookupService broken = new ProviderLookupService(npi -> null);
        assertThatThrownBy(() -> broken.lookup(VALID)).isInstanceOf(IllegalStateException.class);
    }
}
