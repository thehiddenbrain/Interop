package org.p32h.interop.epa.pave.service.codelist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Where the list comes from, how its format is decided, and which files stop the application from
 * starting. Constructing the service is starting it: the list is read in the constructor.
 */
class ProcedureCodeServiceLoadingTest {

    private static final String CSV = "Code,Description,PA Required\nE0601,CPAP device,No\n95810,\"Sleep study, attended\",Yes\n";

    @TempDir
    Path directory;

    private static ProcedureCodeService withSettings(Map<String, String> settings) {
        return new ProcedureCodeService(CodelistProperties.of(settings));
    }

    private static List<String> codes(ProcedureCodeService service) {
        return service.search("", null, 1000).stream().map(ProcedureCode::code).toList();
    }

    // ---- the placeholder ----

    @Test
    void withNoFileConfiguredTheBundledPlaceholderIsLoadedAndSaysItIsOne() {
        CodeListSelfCheck check = new ProcedureCodeService(CodelistProperties.defaults()).selfCheck();
        assertThat(check.placeholder()).isTrue();
        assertThat(check.source()).isEqualTo("classpath:codelist/placeholder-codes.csv");
        assertThat(check.format()).isEqualTo("csv");
        assertThat(check.rowCount()).isPositive();
        // Said in words as well as by the flag, so nobody takes these rows for a P32H export.
        assertThat(check.warnings()).first().asString().startsWith("The bundled placeholder list is in use, not a P32H export.");
        // A blank setting, however it is written, is the same as none.
        assertThat(withSettings(Map.of("file", "   ")).selfCheck().placeholder()).isTrue();
    }

    @Test
    void thePlaceholderIsReadWithTheSettingsItWasWrittenFor() {
        // An operator set these for their own export, then blanked codelist.file. The bundled list
        // has commas and its own headers, and must still load.
        CodeListSelfCheck check = withSettings(Map.of("delimiter", "|", "columns.code[0]", "Proc Cd", "columns.description[0]", "Text",
                "format", "json")).selfCheck();
        assertThat(check.placeholder()).isTrue();
        assertThat(check.format()).isEqualTo("csv");
        assertThat(check.rowCount()).isEqualTo(new ProcedureCodeService(CodelistProperties.defaults()).selfCheck().rowCount());
        assertThat(check.recognisedColumns()).containsEntry("code", "Procedure Code").containsEntry("description", "Description");
        assertThat(check.unrecognisedColumns()).isEmpty();
    }

    // ---- a real path ----

    @Test
    void aConfiguredPathIsLoadedAndIsNotAPlaceholder() {
        Path file = TestCodeLists.write(directory, "export.csv", CSV);
        ProcedureCodeService service = TestCodeLists.load(file);

        CodeListSelfCheck check = service.selfCheck();
        assertThat(check.placeholder()).isFalse();
        assertThat(check.source()).isEqualTo(file.toAbsolutePath().normalize().toString());
        assertThat(check.format()).isEqualTo("csv");
        assertThat(check.rowCount()).isEqualTo(2);
        assertThat(check.statingAuthRequirement()).isEqualTo(2);
        assertThat(check.recognisedColumns()).containsExactly(Map.entry("code", "Code"), Map.entry("description", "Description"),
                Map.entry("authRequired", "PA Required"));
        assertThat(check.unrecognisedColumns()).isEmpty();
        assertThat(check.warnings()).isEmpty();
        // The rows are in the order of the file, and the comma inside the quoted description did not split it.
        assertThat(service.search("", null, 10)).extracting(ProcedureCode::code, ProcedureCode::description, ProcedureCode::authRequired)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("E0601", "CPAP device", AuthRequirement.NOT_REQUIRED),
                        org.assertj.core.groups.Tuple.tuple("95810", "Sleep study, attended", AuthRequirement.REQUIRED));
    }

    @Test
    void anExportAsASpreadsheetSavesItLoadsWithNothingToReport() {
        // "CSV UTF-8" from a spreadsheet: a byte order mark, CRLF between rows, quotes only where a
        // cell needs them, dates as the spreadsheet shows them, empty columns to the right of the
        // data, and empty rows below it.
        String export = "\uFEFFProcedure Code,Code Description,Code Type,Prior Auth Required,LOB,Delegated Entity,Eff Date,Term Date,Comments,,\r\n"
                + "95810,\"Sleep study, attended\",CPT,Y,Medicare,eviCore,1/1/2026,12/31/9999,,,\r\n"
                + "E2601,\"Cushion, under 22\"\" wide\",HCPCS,N,Commercial,,1/1/2026,,\"general use\r\nsee the policy\",,\r\n"
                + "J3490,Drug with no code of its own,HCPCS,,,,,,,,\r\n"
                + ",,,,,,,,,,\r\n"
                + ",,,,,,,,,,\r\n";
        ProcedureCodeService service = TestCodeLists.csv(directory, export);

        CodeListSelfCheck check = service.selfCheck();
        assertThat(check.rowCount()).isEqualTo(3);
        assertThat(check.statingAuthRequirement()).isEqualTo(2);
        assertThat(check.namingVendor()).isEqualTo(1);
        assertThat(check.linesOfBusiness()).containsExactly(Map.entry("Medicare", 1), Map.entry("Commercial", 1));
        assertThat(check.recognisedColumns()).containsExactly(Map.entry("code", "Procedure Code"), Map.entry("description", "Code Description"),
                Map.entry("codeType", "Code Type"), Map.entry("authRequired", "Prior Auth Required"), Map.entry("lineOfBusiness", "LOB"),
                Map.entry("delegatedVendor", "Delegated Entity"), Map.entry("effectiveDate", "Eff Date"), Map.entry("endDate", "Term Date"),
                Map.entry("notes", "Comments"));
        assertThat(check.unrecognisedColumns()).isEmpty();
        // The one thing said is the cell with a line break in it, which was meant here and is named in case it was not.
        assertThat(check.warnings()).containsExactly("1 row has a cell that holds a line break: line 3. If that was not meant, a double "
                + "quote on that line is not closed and the lines after it were read into the cell.");

        ProcedureCode sleep = service.rowsFor("95810", "Medicare", java.time.LocalDate.of(2026, 3, 20)).get(0);
        assertThat(sleep.description()).isEqualTo("Sleep study, attended");
        assertThat(sleep.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        assertThat(sleep.endDate()).isEqualTo("9999-12-31");
        ProcedureCode cushion = service.rowsFor("E2601", null, null).get(0);
        assertThat(cushion.description()).isEqualTo("Cushion, under 22\" wide");
        assertThat(cushion.notes()).isEqualTo("general use\r\nsee the policy");
        assertThat(service.rowsFor("J3490", null, null).get(0).authRequired()).isEqualTo(AuthRequirement.UNKNOWN);
    }

    @Test
    void whiteSpaceAroundTheConfiguredPathIsNotPartOfIt() {
        Path file = TestCodeLists.write(directory, "export.csv", CSV);
        assertThat(withSettings(Map.of("file", "  " + file + "  ")).selfCheck().rowCount()).isEqualTo(2);
    }

    @Test
    void aPathWithNoFileBehindItFailsAtStartupAndNamesThePath() {
        Path missing = directory.resolve("not-there.csv");
        assertThatThrownBy(() -> TestCodeLists.load(missing)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The code list file " + missing + " does not exist. Set codelist.file to the path of the export, or leave "
                        + "it blank to use the bundled placeholder list.");
        // Also when the format is stated, so the name was never needed to decide it.
        assertThatThrownBy(() -> TestCodeLists.load(directory.resolve("not-there.dat"), Map.of("format", "csv")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("not-there.dat").hasMessageContaining("does not exist");
    }

    @Test
    void aRelativePathIsNamedInFullSoItIsClearWhereTheToolLooked() {
        assertThatThrownBy(() -> withSettings(Map.of("file", "no-such-directory/codes.csv"))).isInstanceOf(IllegalStateException.class)
                // Where the tool looked, and the value as configured, so it can be found in application.yml.
                .hasMessageStartingWith("The code list file " + Path.of("no-such-directory/codes.csv").toAbsolutePath().normalize()
                        + " (codelist.file is \"no-such-directory/codes.csv\") does not exist.");
    }

    @Test
    void aPathThisSystemCannotUseFailsAtStartupAndShowsWhatWasConfigured() {
        // No file system takes a NUL character in a name, so this is refused before anything is looked for.
        assertThatThrownBy(() -> withSettings(Map.of("file", "codes\0.csv"))).isInstanceOf(UnusableCodeListException.class)
                .hasMessage("codelist.file is \"codes<U+0000>.csv\", which is not a path this system can use.");
    }

    @Test
    void everyRefusalIsOfTheOneTypeWhoseMessagesTheLoaderWroteItself() {
        // The service logs the message of this type as it stands. It may do that only because no
        // message of the type is ever taken from a file system or a parser.
        assertThatThrownBy(() -> TestCodeLists.load(directory.resolve("not-there.csv"))).isExactlyInstanceOf(UnusableCodeListException.class);
        assertThatThrownBy(() -> withSettings(Map.of("format", "xml"))).isExactlyInstanceOf(UnusableCodeListException.class);
        assertThatThrownBy(() -> withSettings(Map.of("delimiter", ""))).isExactlyInstanceOf(UnusableCodeListException.class);
        assertThatThrownBy(() -> TestCodeLists.load(TestCodeLists.write(directory, "broken.json", "[{"))).isExactlyInstanceOf(UnusableCodeListException.class);
        assertThatThrownBy(() -> TestCodeLists.load(TestCodeLists.write(directory, "nocode.csv", "A,B\n1,2\n"))).isExactlyInstanceOf(UnusableCodeListException.class);
        assertThat(new UnusableCodeListException("x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aBundledListThatIsNotInTheApplicationIsSaidToBeMissing() {
        assertThat(CodeListLoader.bundled(CodeListLoader.PLACEHOLDER)).isNotEmpty();
        assertThatThrownBy(() -> CodeListLoader.bundled("codelist/no-such-list.csv")).isInstanceOf(UnusableCodeListException.class)
                .hasMessage("The bundled placeholder code list is missing from the application (codelist/no-such-list.csv). "
                        + "Set codelist.file to a code list, or rebuild the application.");
    }

    @Test
    void aDirectoryWhereTheFileShouldBeFailsAtStartup() throws IOException {
        Path folder = Files.createDirectory(directory.resolve("lists.csv"));
        assertThatThrownBy(() -> TestCodeLists.load(folder)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(folder.toString()).hasMessageContaining("is a directory, not a file");
    }

    @Test
    void aPathThatCannotBeReadFailsAtStartupAndNamesThePath() {
        // A path that runs through a file as if it were a directory: there is nothing to read there,
        // and on some systems that is reported as an error of its own and not as a missing file.
        Path file = TestCodeLists.write(directory, "export.csv", CSV);
        Path through = file.resolve("codes.csv");
        assertThatThrownBy(() -> TestCodeLists.load(through)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(through.toString())
                .hasMessageMatching("(?s).*(could not be read|does not exist).*");
    }

    @Test
    void aFileFarTooLargeToBeACodeListFailsAtStartupWithoutBeingRead() throws IOException {
        Path huge = directory.resolve("huge.csv");
        try (RandomAccessFile file = new RandomAccessFile(huge.toFile(), "rw")) {
            // Sets the length without writing the bytes.
            file.setLength(CodeListLoader.MAX_BYTES + 1);
        }
        assertThatThrownBy(() -> TestCodeLists.load(huge)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(huge.toString()).hasMessageContaining("more than this tool reads as a code list");
    }

    // ---- the format ----

    @Test
    void theFormatIsTakenFromTheExtensionInAnyCase() {
        assertThat(TestCodeLists.load(TestCodeLists.write(directory, "a.csv", CSV)).selfCheck().format()).isEqualTo("csv");
        assertThat(TestCodeLists.load(TestCodeLists.write(directory, "B.CSV", CSV)).selfCheck().format()).isEqualTo("csv");
        String json = "[{\"code\":\"E0601\"}]";
        assertThat(TestCodeLists.load(TestCodeLists.write(directory, "c.json", json)).selfCheck().format()).isEqualTo("json");
        assertThat(TestCodeLists.load(TestCodeLists.write(directory, "D.Json", json)).selfCheck().format()).isEqualTo("json");
    }

    @Test
    void anExtensionThatIsNeitherFailsAtStartupAndSaysToSetTheFormat() {
        for (String name : new String[] {"export.txt", "export.xlsx", "export", "export.csv.bak", "csv"}) {
            Path file = TestCodeLists.write(directory, name, CSV);
            assertThatThrownBy(() -> TestCodeLists.load(file)).as(name).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(file.toString())
                    .hasMessageContaining("ends in neither .csv nor .json")
                    .hasMessageContaining("Set codelist.format to csv or json.");
        }
    }

    @Test
    void aStatedFormatOverridesTheExtension() {
        Path text = TestCodeLists.write(directory, "export.txt", CSV);
        assertThat(codes(TestCodeLists.load(text, Map.of("format", "csv")))).containsExactly("E0601", "95810");
        assertThat(TestCodeLists.load(text, Map.of("format", " CSV ")).selfCheck().format()).isEqualTo("csv");

        Path data = TestCodeLists.write(directory, "export.dat", "[{\"code\":\"E0601\"},{\"code\":\"95810\"}]");
        ProcedureCodeService json = TestCodeLists.load(data, Map.of("format", "json"));
        assertThat(json.selfCheck().format()).isEqualTo("json");
        assertThat(codes(json)).containsExactly("E0601", "95810");

        // The override wins over an extension that says otherwise: this file is JSON whatever it is called.
        Path misnamed = TestCodeLists.write(directory, "really-json.csv", "[{\"code\":\"E0601\"}]");
        assertThat(codes(TestCodeLists.load(misnamed, Map.of("format", "json")))).containsExactly("E0601");
        // And a CSV file read as JSON is refused as not being JSON. It is not quietly read as CSV after all.
        assertThatThrownBy(() -> TestCodeLists.load(TestCodeLists.write(directory, "really-csv.json", CSV), Map.of("format", "json")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("really-csv.json").hasMessageContaining("is not valid JSON");
    }

    @Test
    void aFormatThatIsNotOneOfTheThreeFailsAtStartupWhetherOrNotAFileIsConfigured() {
        Path file = TestCodeLists.write(directory, "export.csv", CSV);
        for (String format : new String[] {"xml", "excel", "", "  ", "csv,json"}) {
            assertThatThrownBy(() -> TestCodeLists.load(file, Map.of("format", format))).as("[" + format + "]")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("codelist.format is ").hasMessageEndingWith("It must be auto, csv or json.");
            // Found now, on the placeholder, and not on the day a real file is configured.
            assertThatThrownBy(() -> withSettings(Map.of("format", format))).as("[" + format + "]").isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("codelist.format is ");
        }
        assertThatThrownBy(() -> withSettings(Map.of("format", "xml"))).hasMessage("codelist.format is \"xml\". It must be auto, csv or json.");
    }

    // ---- the delimiter ----

    @Test
    void theDelimiterIsConfigurableAndACommaIsThenAnOrdinaryCharacter() {
        String piped = "Code|Description|PA Required\nE0260|Hospital bed, semi-electric|Yes\n";
        ProcedureCodeService service = TestCodeLists.csv(directory, piped, Map.of("delimiter", "|"));
        assertThat(service.search("E0260", null, 5)).singleElement().satisfies(row -> {
            assertThat(row.description()).isEqualTo("Hospital bed, semi-electric");
            assertThat(row.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
        });
        assertThat(codes(TestCodeLists.csv(directory, piped.replace('|', ';'), Map.of("delimiter", ";")))).containsExactly("E0260");
        assertThat(codes(TestCodeLists.csv(directory, piped.replace('|', ';'), Map.of("delimiter", " ; ")))).containsExactly("E0260");
    }

    @Test
    void aTabCanBeWrittenAsAWordBecauseOneCannotBeSeenInAConfigurationFile() {
        String tabbed = "Code\tDescription\tPA Required\nE0260\tHospital bed, semi-electric\tYes\nE0601\t\tNo\n";
        for (String setting : new String[] {"tab", "TAB", " Tab ", "\\t", "\t"}) {
            ProcedureCodeService service = TestCodeLists.csv(directory, tabbed, Map.of("delimiter", setting));
            assertThat(codes(service)).as("[" + setting + "]").containsExactly("E0260", "E0601");
            // The empty cell between two tabs is an empty description. It did not pull the flag one place to the left.
            assertThat(service.rowsFor("E0601", null, null)).singleElement().satisfies(row -> {
                assertThat(row.description()).isNull();
                assertThat(row.authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
            });
        }
        assertThat(CodeListLoader.delimiter(",")).isEqualTo(',');
        assertThat(CodeListLoader.delimiter("tab")).isEqualTo('\t');
    }

    @Test
    void aDelimiterThatIsNotOneCharacterFailsAtStartupWhetherOrNotAFileIsConfigured() {
        for (String delimiter : new String[] {"", "  ", ",,", "||", "comma", "\"", "\n"}) {
            assertThatThrownBy(() -> withSettings(Map.of("delimiter", delimiter))).as("[" + delimiter + "]")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageStartingWith("codelist.delimiter is ")
                    .hasMessageContaining("It must be the one character that separates the cells of the file");
        }
    }

    // ---- what the bytes are ----

    @Test
    void aByteOrderMarkIsNotPartOfTheFirstHeader() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "\uFEFF" + CSV);
        assertThat(service.selfCheck().recognisedColumns()).containsEntry("code", "Code");
        assertThat(service.selfCheck().unrecognisedColumns()).isEmpty();
        assertThat(service.selfCheck().warnings()).isEmpty();
        assertThat(codes(TestCodeLists.json(directory, "\uFEFF[{\"code\":\"E0601\"}]"))).containsExactly("E0601");
    }

    @Test
    void aFileThatIsNotUtf8IsLoadedAnywayAndTheSelfCheckSaysToExportItAgain() {
        // What a spreadsheet writes as plain "CSV": one byte for the accented letter and one for the dash.
        String text = "Code,Description,PA Required\nE0601,CPAP device,No\n95810,Sleep study caf\u00E9 \u2013 attended,Yes\nG0399,Home study,Yes\n";
        Path file = TestCodeLists.write(directory, "ansi.csv", text.getBytes(java.nio.charset.Charset.forName("windows-1252")));
        ProcedureCodeService service = TestCodeLists.load(file);

        // Every row is there, and what was written in plain letters and digits is intact.
        assertThat(service.selfCheck().rowCount()).isEqualTo(3);
        assertThat(service.rowsFor("95810", null, null)).singleElement().satisfies(row -> {
            assertThat(row.authRequired()).isEqualTo(AuthRequirement.REQUIRED);
            // The two bytes that are not UTF-8 are marked. They are not guessed at.
            assertThat(row.description()).isEqualTo("Sleep study caf\uFFFD \uFFFD attended");
        });
        assertThat(service.rowsFor("E0601", null, null).get(0).description()).isEqualTo("CPAP device");
        assertThat(service.selfCheck().warnings()).singleElement().asString()
                .isEqualTo("The file is not valid UTF-8. 2 characters could not be read and are shown as the replacement mark U+FFFD, on line 3. "
                        + "Text in plain letters and digits is not affected. Export the list again as UTF-8 (in a spreadsheet, CSV UTF-8) "
                        + "and restart.");
    }

    @Test
    void theSameTextSavedAsUtf8LoadsWithNoWarning() {
        String text = "Code,Description,PA Required\n95810,Sleep study caf\u00E9 \u2013 attended,Yes\n";
        ProcedureCodeService service = TestCodeLists.load(TestCodeLists.write(directory, "utf8.csv", text.getBytes(StandardCharsets.UTF_8)));
        assertThat(service.rowsFor("95810", null, null).get(0).description()).isEqualTo("Sleep study caf\u00E9 \u2013 attended");
        assertThat(service.selfCheck().warnings()).isEmpty();
    }

    @Test
    void aUtf16FileFailsAtStartupAndSaysWhatItIs() {
        for (java.nio.charset.Charset charset : new java.nio.charset.Charset[] {StandardCharsets.UTF_16LE, StandardCharsets.UTF_16BE}) {
            // As a spreadsheet writes "Unicode text": a byte order mark, then two bytes for every character.
            Path file = TestCodeLists.write(directory, "unicode.csv", ("\uFEFF" + CSV).getBytes(charset));
            assertThatThrownBy(() -> TestCodeLists.load(file)).as(charset.name()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(file.toString())
                    .hasMessageContaining("starts with a UTF-16 or UTF-32 byte order mark, and this tool reads UTF-8");
        }
    }

    // ---- files with nothing in them, or nothing this tool can use ----

    @Test
    void anEmptyFileFailsAtStartup() {
        for (String text : new String[] {"", "\uFEFF", "\n\n", "  \r\n,,,\r\n"}) {
            Path file = TestCodeLists.write(directory, "empty.csv", text);
            assertThatThrownBy(() -> TestCodeLists.load(file)).as("[" + text + "]").isInstanceOf(IllegalStateException.class)
                    .hasMessage("The code list file " + file + " is empty. Its first row that is not blank must name the columns.");
        }
    }

    @Test
    void aFileWithNoRecognisableCodeColumnFailsAtStartupWithTheHeadersItDidFind() {
        Path file = TestCodeLists.write(directory, "export.csv", "Proc,Short Description,Needs Auth,\nE0601,CPAP device,No,\n");
        assertThatThrownBy(() -> TestCodeLists.load(file)).isInstanceOf(IllegalStateException.class)
                .hasMessage("The code list file " + file + " has no column this tool recognises as the procedure code. "
                        + "Headers found: \"Proc\", \"Short Description\", \"Needs Auth\", (blank). "
                        + "The first row of the file that is not blank must be the header row. "
                        + "If one of these holds the code, name it under codelist.columns.code in application.yml. A list set there replaces the built-in names for the column.");
    }

    @Test
    void aFileSeparatedByAnotherCharacterThanTheOneConfiguredSaysSoInTheFailure() {
        // The commonest reason a good export has no column for the code: its whole header row was read as one cell.
        Path semicolons = TestCodeLists.write(directory, "semicolons.csv", "Code;Description;PA Required\nE0601;CPAP device;No\n");
        assertThatThrownBy(() -> TestCodeLists.load(semicolons)).isInstanceOf(UnusableCodeListException.class)
                .hasMessage("The code list file " + semicolons + " has no column this tool recognises as the procedure code. "
                        + "Headers found: \"Code;Description;PA Required\". "
                        + "The header row was read as one cell, and it holds a semicolon. If that is what separates the cells of this "
                        + "file, set codelist.delimiter to \";\". "
                        + "The first row of the file that is not blank must be the header row. "
                        + "If one of these holds the code, name it under codelist.columns.code in application.yml. A list set there replaces the built-in names for the column.");
        // Doing what the message says is what makes the file load.
        assertThat(codes(TestCodeLists.load(semicolons, Map.of("delimiter", ";")))).containsExactly("E0601");

        Path tabs = TestCodeLists.write(directory, "tabs.csv", "Code\tDescription\nE0601\tCPAP device\n");
        assertThatThrownBy(() -> TestCodeLists.load(tabs)).isInstanceOf(UnusableCodeListException.class)
                .hasMessageContaining("Headers found: \"Code<U+0009>Description\". The header row was read as one cell, and it holds a tab. "
                        + "If that is what separates the cells of this file, set codelist.delimiter to tab. ");
        assertThat(codes(TestCodeLists.load(tabs, Map.of("delimiter", "tab")))).containsExactly("E0601");

        // The other way round: commas in a file read with a bar between cells.
        Path commas = TestCodeLists.write(directory, "commas.csv", "Code,Description\nE0601,CPAP device\n");
        assertThatThrownBy(() -> TestCodeLists.load(commas, Map.of("delimiter", "|"))).isInstanceOf(UnusableCodeListException.class)
                .hasMessageContaining("it holds a comma. If that is what separates the cells of this file, set codelist.delimiter to \",\". ");
        // One header that simply is not a known one gets no such hint.
        Path other = TestCodeLists.write(directory, "other.csv", "Procedure\nE0601\n");
        assertThatThrownBy(() -> TestCodeLists.load(other)).isInstanceOf(UnusableCodeListException.class)
                .hasMessageNotContaining("read as one cell");
    }

    @Test
    void addingTheHeaderToTheCandidatesIsWhatMakesThatFileLoad() {
        Path file = TestCodeLists.write(directory, "export.csv", "Proc,Short Description,Needs Auth\nE0601,CPAP device,No\n");
        ProcedureCodeService service = TestCodeLists.load(file, Map.of("columns.code[0]", "proc", "columns.auth-required[0]", "needs auth"));
        assertThat(service.selfCheck().recognisedColumns()).containsExactly(Map.entry("code", "Proc"),
                Map.entry("description", "Short Description"), Map.entry("authRequired", "Needs Auth"));
        assertThat(service.rowsFor("E0601", null, null).get(0).authRequired()).isEqualTo(AuthRequirement.NOT_REQUIRED);
    }

    @Test
    void aTitleLineAboveTheHeaderRowIsTakenForTheHeaderRowAndShownInTheFailure() {
        // The rule is fixed and is not a search for something that looks like a header.
        Path file = TestCodeLists.write(directory, "export.csv", "Prior authorization code list 2026\n\nCode,Description\nE0601,CPAP device\n");
        assertThatThrownBy(() -> TestCodeLists.load(file)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Headers found: \"Prior authorization code list 2026\".");
    }

    @Test
    void aFileThatIsAHeaderRowAndNothingElseLoadsEmptyAndSaysSo() {
        ProcedureCodeService service = TestCodeLists.csv(directory, "Code,Description,PA Required\n");
        assertThat(service.selfCheck().rowCount()).isZero();
        assertThat(service.selfCheck().recognisedColumns()).containsKeys("code", "description", "authRequired");
        assertThat(service.selfCheck().warnings()).containsExactly("The list has no rows with a code. The picker will offer nothing, and no "
                + "code can be compared with the service.");
        assertThat(service.search("", null, 10)).isEmpty();
        assertThat(service.rowsFor("E0601", null, null)).isEmpty();
    }

    // ---- wiring ----

    @Test
    void itIsABeanOnBothProfiles() {
        // What the code list says does not depend on where the requests go, so the service carries no profile.
        assertThat(ProcedureCodeService.class.isAnnotationPresent(Service.class)).isTrue();
        assertThat(ProcedureCodeService.class.isAnnotationPresent(Profile.class)).isFalse();
    }
}
