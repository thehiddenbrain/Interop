package org.p32h.interop.epa.pave.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.Npi;

/**
 * F-NPI-TEST of the second fixture audit: a ten-digit number that passes the NPI check could be a
 * real provider's, and nobody here can look it up. Every such number in the main and test sources
 * and in the samples is one of the project's placeholders: {@code 1234567893}, or one of the
 * {@code 99999999xx} and {@code 10000000xx} numbers made with {@link Npi#withCheckDigit}. Numbers
 * that fail the check are left alone: several tests type them on purpose as typing mistakes.
 */
class NoNpiOutsideThePlaceholderSetTest {

    /** Ten digits beginning with 1 or 2, the first digit of every issued NPI, standing alone: not inside a longer number, word or decimal. */
    static final Pattern TEN_DIGITS = Pattern.compile("(?<![0-9A-Za-z.\\-])[12][0-9]{9}(?![0-9])");

    static boolean placeholder(String npi) {
        return npi.equals("1234567893") || npi.startsWith("99999999") || npi.startsWith("10000000");
    }

    /** A number made in code from its first nine digits, as the tests make theirs. */
    static final Pattern MADE = Pattern.compile("withCheckDigit\\(\\s*\"([0-9]{9})\"\\s*\\)");

    /** Every number in a text, written out or made with the check digit, that passes the NPI check and is not a placeholder. */
    static List<String> outsideTheSet(String text) {
        List<String> found = new ArrayList<>();
        Matcher number = TEN_DIGITS.matcher(text);
        while (number.find()) {
            if (Npi.isValid(number.group()) && !placeholder(number.group())) {
                found.add(number.group());
            }
        }
        Matcher made = MADE.matcher(text);
        while (made.find()) {
            String npi = Npi.withCheckDigit(made.group(1));
            if (!placeholder(npi)) {
                found.add(npi);
            }
        }
        return found;
    }

    @Test
    void everyNpiThatPassesTheCheckInMainTestAndSamplesIsAPlaceholder() throws IOException {
        List<String> wrong = new ArrayList<>();
        int read = 0;
        for (Path root : List.of(Path.of("src/main"), Path.of("src/test"), Path.of("samples"))) {
            assertThat(root).as("run from the project directory").isDirectory();
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text;
                    try {
                        text = Files.readString(file, StandardCharsets.UTF_8);
                    } catch (MalformedInputException e) {
                        continue;
                    }
                    read++;
                    outsideTheSet(text).forEach(npi -> wrong.add(file + ": " + npi));
                }
            }
        }
        assertThat(read).isGreaterThan(500);
        assertThat(wrong).isEmpty();
    }

    @Test
    void theScanCatchesAnNpiThatPassesTheCheckAndIsNotAPlaceholder() {
        // Made here from two parts, so this file holds none for the scan above to find.
        String nine = "1987" + "65432";
        String realLooking = Npi.withCheckDigit(nine);
        assertThat(Npi.isValid(realLooking)).isTrue();
        assertThat(outsideTheSet("{\"system\": \"http://hl7.org/fhir/sid/us-npi\", \"value\": \"" + realLooking + "\"}"))
                .containsExactly(realLooking);
        assertThat(outsideTheSet(".doesNotContain(\"" + realLooking + "\")")).containsExactly(realLooking);
        // A test that makes its NPI from nine digits is read the same way: this is how the comparer tests held one.
        assertThat(outsideTheSet("static final String NPI = Npi.withCheckDigit(\"" + nine + "\");")).containsExactly(realLooking);
        assertThat(outsideTheSet("Npi.withCheckDigit(\"100000008\")")).isEmpty();
        // The placeholders pass the check and are left; a number that fails the check is a typing mistake on purpose.
        String placeholders = "1234567893 9999999995 1000000087";
        assertThat(outsideTheSet(placeholders)).isEmpty();
        assertThat(outsideTheSet("1234567890 1234567894")).isEmpty();
        // Part of a longer number, a decimal or a word is not an NPI.
        assertThat(outsideTheSet("x" + realLooking + " 0." + realLooking + " " + realLooking + "1")).isEmpty();
    }
}
