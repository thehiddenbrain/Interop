package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;

/**
 * The examples in the comments under {@code codelist:} in application.yml, read out of that file
 * and bound the way the application binds the file.
 *
 * <p>An operator copies such an example and changes the names in it. An example in a form that
 * does not bind, or that binds to something else than the comment says, would send them looking
 * for a fault in their export. Nothing else in the build would notice, because a comment is never
 * read by anything.
 */
class CodeListSettingsExamplesTest {

    private static String applicationYml() throws IOException {
        try (InputStream in = CodeListSettingsExamplesTest.class.getClassLoader().getResourceAsStream("application.yml")) {
            assertThat(in).as("application.yml is on the class path").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The lines under {@code codelist:} that are examples: a comment mark, then three spaces or
     * more. With the mark taken off they are YAML again, at the depth they would have in the file.
     */
    private static String examples() throws IOException {
        StringBuilder out = new StringBuilder("codelist:\n");
        boolean inside = false;
        for (String line : applicationYml().split("\r?\n")) {
            if (line.equals("codelist:")) {
                inside = true;
            } else if (inside && !line.isBlank() && !line.startsWith(" ")) {
                // The next top-level key: the code list block has ended.
                break;
            } else if (inside && line.startsWith("  #   ")) {
                out.append(line.substring("  # ".length())).append('\n');
            }
        }
        return out.toString();
    }

    private static CodelistProperties bound(String yaml) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("examples",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        return new Binder(ConfigurationPropertySources.from(sources)).bindOrCreate("codelist", CodelistProperties.class);
    }

    @Test
    void theExamplesAreTheOnesThisTestExpectsToFind() throws IOException {
        // If the comments are reworded so that no example is left, the tests below would pass on nothing.
        assertThat(examples()).isEqualTo("codelist:\n"
                + "  columns:\n"
                + "    code: [\"Proc Cd\", \"code\"]\n"
                + "    auth-required: [\"PA Required (Medicare)\"]\n"
                + "  line-of-business-aliases:\n"
                + "    MCR: [Medicare]\n"
                + "    \"[Medicaid/CHIP (MassHealth)]\": [Medicaid]\n");
    }

    @Test
    void theColumnsExampleBindsAndReplacesTheBuiltInNamesOfThoseColumnsOnly() throws IOException {
        CodelistProperties properties = bound(examples());
        // Exactly the names in the example: the built-in ones for these two columns are gone, as the comment says.
        assertThat(properties.columns().code()).containsExactly("Proc Cd", "code");
        assertThat(properties.columns().authRequired()).containsExactly("PA Required (Medicare)");
        // A column the example does not mention keeps its built-in names.
        assertThat(properties.columns().description()).isEqualTo(CodelistProperties.defaults().columns().description());
        // And the two names find their headers, the bracketed one as it is written and no other.
        ColumnMapping mapping = ColumnMapping.match(List.of("PROC_CD", "PA Required (Commercial)", "PA Required (Medicare)"), properties.columns());
        assertThat(mapping.position(CodeListColumn.CODE)).isZero();
        assertThat(mapping.position(CodeListColumn.AUTH_REQUIRED)).isEqualTo(2);
        assertThat(mapping.unrecognised()).containsExactly("PA Required (Commercial)");
    }

    @Test
    void theAliasesExampleBindsWithTheKeyThatHoldsASlashAndBracketsKeptWhole() throws IOException {
        CodelistProperties properties = bound(examples());
        assertThat(properties.lineOfBusinessAliases()).containsOnlyKeys("MCR", "Medicaid/CHIP (MassHealth)");
        assertThat(properties.lineOfBusinessAliases().get("MCR")).containsExactly("Medicare");
        assertThat(properties.lineOfBusinessAliases().get("Medicaid/CHIP (MassHealth)")).containsExactly("Medicaid");
        // So a member whose line is named that way reaches the rows the list files under Medicaid.
        LineMatcher matcher = new LineMatcher(properties.lineOfBusinessAliases());
        assertThat(matcher.wanted("Medicaid/CHIP (MassHealth)")).contains("medicaid");
        assertThat(matcher.wanted("mcr")).contains("medicare");
    }

    @Test
    void writtenWithoutTheSquareBracketsThatKeyLosesItsSlashAndBracketsAndMatchesNothing() throws IOException {
        // What the comment warns of. The same alias with the key in quotes only.
        CodelistProperties properties = bound("codelist:\n  line-of-business-aliases:\n    \"Medicaid/CHIP (MassHealth)\": [Medicaid]\n");
        assertThat(properties.lineOfBusinessAliases()).doesNotContainKey("Medicaid/CHIP (MassHealth)");
        assertThat(properties.lineOfBusinessAliases()).hasSize(1);
        LineMatcher matcher = new LineMatcher(properties.lineOfBusinessAliases());
        assertThat(matcher.wanted("Medicaid/CHIP (MassHealth)")).doesNotContain("medicaid");
    }

    @Test
    void theOtherStatementsOfTheCommentsHold() throws IOException {
        String comments = applicationYml();
        // "The one character that separates the cells of a CSV file, or the word tab."
        assertThat(comments).contains("# The one character that separates the cells of a CSV file, or the word tab.");
        assertThat(CodeListLoader.delimiter("tab")).isEqualTo('\t');
        assertThat(CodeListLoader.delimiter(";")).isEqualTo(';');
        // "While this is blank the tool loads the placeholder list that ships with it."
        assertThat(comments).contains("While this is blank the tool loads the placeholder list that ships with it.");
        assertThat(bound("codelist:\n  file: \"\"\n").file()).isBlank();
        assertThat(new ProcedureCodeService(bound("codelist:\n  file: \"\"\n")).selfCheck().placeholder()).isTrue();
    }
}
