package org.p32h.interop.epa.pave.samples;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.Npi;
import org.p32h.interop.epa.pave.model.Secrets;
import tools.jackson.databind.JsonNode;

/**
 * Everything under {@code samples/} is handed to people who have never seen this project, and may
 * be copied into a real Postman workspace. So nothing in it may be a real host, a real credential
 * or anything that could be a real member. A failure here names the file and the text.
 */
class SamplesHoldOnlyPlaceholdersTest {

    static final Path SAMPLES = Path.of("samples");

    /**
     * Hosts that appear only as the namespace of a standard code system or of the Postman schema.
     * Nothing is ever sent to them; every host a request is sent to must end in .example.invalid.
     */
    static final Set<String> NAMESPACE_HOSTS = Set.of("schema.getpostman.com", "hl7.org", "www.ama-assn.org");

    // A body inside a collection is JSON written as a string, so its quotes arrive escaped with a backslash.
    static final Pattern URL_HOST = Pattern.compile("https?://([^/\"\\\\\\s{}?#:]+)");
    static final Pattern TEN_DIGITS = Pattern.compile("(?<![0-9])[0-9]{10}(?![0-9])");
    /** The shapes a P32H member ID takes: letters followed by six or more digits, or the same with an alphanumeric run. */
    static final Pattern MEMBER_ID_SHAPED = Pattern.compile("(?<![A-Za-z0-9-])[A-Za-z]{1,3}[0-9]{6,}(?:\\s*[0-9]{2})?(?![A-Za-z0-9])");
    static final Pattern PLACEHOLDER_MEMBER_ID = Pattern.compile("S[0-9]{9,10}");
    static final Pattern ONLY_OS_REFERENCE = Pattern.compile("\\{\\{env:PAVE_[A-Z0-9_]+\\}\\}");
    /** The one placeholder NPI the README and the samples use. It passes the Luhn check the tool applies. */
    static final Set<String> PLACEHOLDER_NPIS = Set.of("1234567893");

    @Test
    void thereAreSampleFilesToCheck() throws IOException {
        assertThat(files()).extracting(p -> SAMPLES.relativize(p).toString().replace('\\', '/'))
                .contains("postman/pave-sample-sandbox.postman_environment.json", "postman/pave-sample.postman_collection.json",
                        "codelist/sample-code-list.csv", "suites/sample-suite.csv");
    }

    @Test
    void everyHostIsAPlaceholderOrTheNamespaceOfAStandard() throws IOException {
        List<String> offending = new ArrayList<>();
        for (Path file : files()) {
            Matcher host = URL_HOST.matcher(read(file));
            while (host.find()) {
                String name = host.group(1).toLowerCase(java.util.Locale.ROOT);
                if (!name.endsWith(".example.invalid") && !NAMESPACE_HOSTS.contains(name)) {
                    offending.add(file + ": " + name);
                }
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    void everyMemberIdShapedValueIsAnS123456789StylePlaceholder() throws IOException {
        List<String> offending = new ArrayList<>();
        for (Path file : files()) {
            Matcher id = MEMBER_ID_SHAPED.matcher(read(file));
            while (id.find()) {
                if (!PLACEHOLDER_MEMBER_ID.matcher(id.group()).matches()) {
                    offending.add(file + ": " + id.group());
                }
            }
        }
        assertThat(offending).isEmpty();
    }

    @Test
    void everyTenDigitNumberIsThePlaceholderNpiAndPassesTheToolsOwnCheck() throws IOException {
        List<String> offending = new ArrayList<>();
        for (Path file : files()) {
            Matcher number = TEN_DIGITS.matcher(read(file));
            while (number.find()) {
                if (!PLACEHOLDER_NPIS.contains(number.group()) || !Npi.isValid(number.group())) {
                    offending.add(file + ": " + number.group());
                }
            }
        }
        assertThat(offending).isEmpty();
        assertThat(PLACEHOLDER_NPIS).allMatch(Npi::isValid);
    }

    @Test
    void everySecretVariableReadsFromTheOperatingSystemAndHoldsNoValue() throws IOException {
        JsonNode environment = JsonSafe.parse(read(SamplePostmanFilesTest.ENVIRONMENT)).node();
        List<String> secrets = new ArrayList<>();
        for (JsonNode variable : JsonSafe.elements(environment, "values")) {
            String name = JsonSafe.text(variable, "key");
            String value = JsonSafe.text(variable, "value");
            if ("secret".equals(JsonSafe.text(variable, "type")) || Secrets.looksSecret(name)) {
                secrets.add(name);
                assertThat(value).as("value of the secret %s", name).matches(ONLY_OS_REFERENCE);
            }
        }
        assertThat(secrets).contains("clientSecret");
    }

    @Test
    void noRequestCarriesACredentialHeaderAndEveryPersonIsCalledPlaceholder() throws IOException {
        String collection = read(SamplePostmanFilesTest.COLLECTION);
        JsonNode root = JsonSafe.parse(collection).node();
        List<String> credentialHeaders = new ArrayList<>();
        walk(JsonSafe.child(root, "item"), credentialHeaders);
        assertThat(credentialHeaders).isEmpty();

        Matcher family = Pattern.compile("\\\\?\"family\\\\?\"\\s*:\\s*\\\\?\"([^\"\\\\]*)").matcher(collection);
        int names = 0;
        while (family.find()) {
            names++;
            assertThat(family.group(1)).contains("Placeholder");
        }
        assertThat(names).isPositive();
    }

    private static void walk(JsonNode items, List<String> credentialHeaders) {
        for (JsonNode item : JsonSafe.elements(items)) {
            walk(JsonSafe.child(item, "item"), credentialHeaders);
            for (JsonNode header : JsonSafe.elements(JsonSafe.at(item, "request", "header"))) {
                String key = JsonSafe.text(header, "key");
                if (Secrets.credentialHeader(key)) {
                    credentialHeaders.add(JsonSafe.text(item, "name") + ": " + key);
                }
            }
        }
    }

    private static List<Path> files() throws IOException {
        try (Stream<Path> walk = Files.walk(SAMPLES)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        }
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
