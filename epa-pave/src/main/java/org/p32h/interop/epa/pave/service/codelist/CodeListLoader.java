package org.p32h.interop.epa.pave.service.codelist;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.DelegatedVendors;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.Texts;

/**
 * Loads the procedure code list, once, at startup.
 *
 * <p>Two kinds of trouble are kept apart. A list that cannot be what the operator meant to point
 * at is refused, with an {@link UnusableCodeListException} that names the file and stops the
 * application: a path with no file behind it, a format that cannot be told, a file with no column
 * for the code. A list that can be read but has something odd in it is loaded as it is, and the
 * oddity is written into the self-check as a warning with the rows it concerns.
 *
 * <p>Nothing in a row is corrected. The value of this list is that it can disagree with what the
 * service answers, so a cell is served as it was written, apart from the white space around it.
 * Three kinds of cell are read and not only copied: the authorization flag, the dates, and the
 * cells that say which line of business and which vendor a row is for.
 */
final class CodeListLoader {

    /** The synthetic list bundled with the tool, used when {@code codelist.file} is blank. */
    static final String PLACEHOLDER = "codelist/placeholder-codes.csv";

    /**
     * Several times the size of the real export, which has some seven thousand rows, and low
     * enough that a wrong path fails with a sentence and not by running out of memory.
     */
    static final long MAX_BYTES = 20L * 1024 * 1024;

    private static final char REPLACEMENT = '\uFFFD';

    private static final Pattern SHORT_NUMBER = Pattern.compile("[0-9]{1,4}");

    /** The most characters a cell may have and still be taken for a header when a failure quotes the first row. */
    private static final int HEADER_LENGTH = 40;

    /** Three or more digits of any script, one after the other: what an identifier, a date or a street number holds and a header does not. */
    private static final Pattern DIGITS_IN_A_ROW = Pattern.compile("\\p{Nd}{3,}");

    private CodeListLoader() {
    }

    static LoadedCodeList load(CodelistProperties properties) {
        // Both settings are checked whether or not they are used this time. A misspelt value that
        // only matters once a real file is configured should be found now, by whoever typed it.
        String format = format(properties.format());
        char delimiter = delimiter(properties.delimiter());
        Source source = Texts.blank(properties.file()) ? placeholder() : file(properties.file(), format, delimiter, properties.columns());

        Decoded decoded = decode(source);
        SourceTable table = source.json() ? JsonCodeList.read(decoded.text(), source.subject()) : CsvCodeList.read(decoded.text(), source.delimiter());
        if (table.headers().isEmpty()) {
            throw new UnusableCodeListException(source.subject() + (source.json() ? " has no rows: the list in it holds no object with keys."
                    : " is empty. Its first row that is not blank must name the columns."));
        }
        ColumnMapping columns = ColumnMapping.match(table.headers(), source.columns());
        if (!columns.has(CodeListColumn.CODE)) {
            throw new UnusableCodeListException(source.subject() + " has no column this tool recognises as the procedure code. "
                    + (source.json() ? "Keys" : "Headers") + " found: "
                    + String.join(", ", table.headers().stream().map(cell -> header(cell, source.json())).toList())
                    + ". " + (source.json() ? "" : otherDelimiter(table.headers(), source.delimiter())
                    + "The first row of the file that is not blank must be the header row. ")
                    + "If one of these holds the code, name it under " + CodeListColumn.CODE.setting() + " in application.yml. A list set there replaces "
                    + "the built-in names for the column.");
        }

        Rows rows = new Rows(table, columns);
        List<String> warnings = new ArrayList<>();
        if (source.placeholder()) {
            warnings.add("The bundled placeholder list is in use, not a P32H export. Every row in it is synthetic. Set codelist.file to "
                    + "the real export and restart.");
        }
        if (!decoded.valid()) {
            List<Integer> marked = table.rows().stream().filter(row -> row.cells().stream().anyMatch(cell -> cell != null && cell.indexOf(REPLACEMENT) >= 0))
                    .map(SourceTable.Row::at).toList();
            warnings.add("The file is not valid UTF-8. " + CodeListText.count(decoded.replaced(), "character", "characters")
                    + " could not be read and " + (decoded.replaced() == 1 ? "is" : "are") + " shown as the replacement mark U+FFFD"
                    + (marked.isEmpty() ? "" : ", on " + table.where(marked))
                    + ". Text in plain letters and digits is not affected. Export the list again as UTF-8 (in a spreadsheet, "
                    + "CSV UTF-8) and restart.");
        }
        warnings.addAll(table.warnings());
        warnings.addAll(columns.warnings());
        warnings.addAll(rows.warnings());

        CodeListSelfCheck selfCheck = new CodeListSelfCheck(source.name(), source.placeholder(), table.format(), rows.entries.size(),
                rows.stating, rows.namingVendor, rows.lineCounts(), columns.recognised(), columns.unrecognised(), warnings);
        return new LoadedCodeList(rows.entries, rows.lineNames, selfCheck);
    }

    // ---- settings ----

    private static String format(String configured) {
        String format = configured == null ? "" : configured.strip().toLowerCase(Locale.ROOT);
        if (!format.equals("auto") && !format.equals(SourceTable.CSV) && !format.equals(SourceTable.JSON)) {
            throw new UnusableCodeListException("codelist.format is " + CodeListText.show(configured) + ". It must be auto, csv or json.");
        }
        return format;
    }

    /**
     * The one character between cells. A tab cannot be seen in a configuration file and YAML does
     * not keep one without quoting, so it can also be written as the word tab or as backslash t.
     */
    static char delimiter(String configured) {
        if ("\t".equals(configured)) {
            return '\t';
        }
        String text = configured == null ? "" : configured.strip();
        if (text.equalsIgnoreCase("tab") || text.equals("\\t")) {
            return '\t';
        }
        if (text.length() != 1 || text.charAt(0) == '"') {
            throw new UnusableCodeListException("codelist.delimiter is " + CodeListText.show(configured) + ". It must be the one character "
                    + "that separates the cells of the file, such as a comma, a semicolon or a bar, or the word tab. It cannot be "
                    + "a double quote.");
        }
        return text.charAt(0);
    }

    // ---- where the list comes from ----

    /**
     * @param name    the source as the self-check reports it
     * @param subject how a message names the source, as the start of a sentence
     * @param columns the candidate headers to look for
     */
    private record Source(String name, String subject, boolean placeholder, boolean json, char delimiter, CodelistProperties.Columns columns,
                          byte[] bytes) {
    }

    /**
     * The bundled list is read with the settings it was written for. An operator who has set a
     * delimiter or a header name for their own export and later blanks {@code codelist.file} must
     * get the placeholder back, not a startup failure about a file they never saw.
     */
    private static Source placeholder() {
        return new Source("classpath:" + PLACEHOLDER, "The bundled placeholder code list", true, false, ',',
                CodelistProperties.defaults().columns(), bundled(PLACEHOLDER));
    }

    /** The bytes of a file packaged with the application. */
    static byte[] bundled(String resource) {
        try (InputStream in = CodeListLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new UnusableCodeListException("The bundled placeholder code list is missing from the application (" + resource
                        + "). Set codelist.file to a code list, or rebuild the application.");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UnusableCodeListException("The bundled placeholder code list (" + resource + ") could not be read.");
        }
    }

    private static Source file(String configured, String format, char delimiter, CodelistProperties.Columns columns) {
        Path path;
        try {
            path = Path.of(configured.strip()).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new UnusableCodeListException("codelist.file is " + CodeListText.quote(configured) + ", which is not a path this system can use.");
        }
        String subject = "The code list file " + path;
        // A relative path is resolved against the directory the tool was started in. Both forms
        // are given then: the one to look for on disk, and the one to look for in application.yml.
        String setting = path.toString().equals(configured.strip()) ? "" : " (codelist.file is " + CodeListText.quote(configured) + ")";
        String lower = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean json;
        if (format.equals("auto")) {
            if (!lower.endsWith(".csv") && !lower.endsWith(".json")) {
                throw new UnusableCodeListException(subject + setting + " ends in neither .csv nor .json, so its format cannot be told "
                        + "from its name. Set codelist.format to csv or json.");
            }
            json = lower.endsWith(".json");
        } else {
            json = format.equals(SourceTable.JSON);
        }
        try {
            if (Files.isDirectory(path)) {
                throw new UnusableCodeListException(subject + setting + " is a directory, not a file. Set codelist.file to the export itself.");
            }
            long size = Files.size(path);
            if (size > MAX_BYTES) {
                throw new UnusableCodeListException(subject + setting + " is " + size + " bytes, which is more than this tool reads as a "
                        + "code list (" + MAX_BYTES + " bytes). Check that codelist.file points at the code list.");
            }
            return new Source(path.toString(), subject, false, json, delimiter, columns, Files.readAllBytes(path));
        } catch (NoSuchFileException e) {
            throw new UnusableCodeListException(subject + setting + " does not exist. Set codelist.file to the path of the export, or "
                    + "leave it blank to use the bundled placeholder list.");
        } catch (IOException e) {
            // The exception's own message is not passed on. Which file it was is all that is needed.
            throw new UnusableCodeListException(subject + setting + " could not be read. Check that it is a file and that the account "
                    + "running this tool may read it.");
        }
    }

    // ---- bytes to text ----

    private record Decoded(String text, boolean valid, int replaced) {
    }

    private static Decoded decode(Source source) {
        byte[] bytes = source.bytes();
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) || (bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF))) {
            // Read as UTF-8 this would be every other character a mark and no header anyone could
            // recognise. The byte order mark says what the file is, so say that and stop.
            throw new UnusableCodeListException(source.subject() + " starts with a UTF-16 or UTF-32 byte order mark, and this tool reads "
                    + "UTF-8. Save the list as UTF-8 (in a spreadsheet, CSV UTF-8) and start again.");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            return new Decoded(text, true, 0);
        } catch (CharacterCodingException e) {
            // Loaded anyway: the codes and flags of a list saved in a one byte encoding are plain
            // letters and digits and read the same. Each byte that is not UTF-8 becomes the
            // replacement mark, which shows where the damage is and guesses nothing.
            String text = new String(bytes, StandardCharsets.UTF_8);
            return new Decoded(text, false, (int) text.chars().filter(c -> c == REPLACEMENT).count());
        }
    }

    /**
     * A cell of the first row, or a key, as the failure for a file with no code column shows it.
     *
     * <p>That failure is logged, and it exists for the day the setting points at the wrong file.
     * The wrong file may have no header row at all: its first row may be somebody's record. So a
     * cell is shown only when it reads as a header. One that holds three or more digits in a row,
     * or is longer than a header would be, is replaced by a statement of its length: that says a
     * cell stood there, and that it was no header, without saying what it held. A member ID, a
     * date of birth and a street address all hold such a run of digits. A cell of letters alone,
     * such as a name, cannot be told from a header and is still shown.
     */
    private static String header(String written, boolean json) {
        String header = CodeListText.clean(written);
        if (header == null) {
            return "(blank)";
        }
        int length = header.codePointCount(0, header.length());
        if (length > HEADER_LENGTH || DIGITS_IN_A_ROW.matcher(header).find()) {
            return "(a " + (json ? "key" : "cell") + " that does not read as a header, " + length + " characters)";
        }
        return CodeListText.show(header);
    }

    /**
     * A sentence for the commonest reason a good file has no column for the code: it is separated
     * by another character than the one configured, so its whole header row was read as one cell.
     * Empty when the header row does not look like that.
     */
    private static String otherDelimiter(List<String> headers, char delimiter) {
        if (headers.size() != 1) {
            return "";
        }
        Map<Character, String> others = new LinkedHashMap<>();
        others.put(',', "a comma");
        others.put(';', "a semicolon");
        others.put('\t', "a tab");
        others.put('|', "a bar");
        others.remove(delimiter);
        for (Map.Entry<Character, String> other : others.entrySet()) {
            if (headers.get(0).indexOf(other.getKey()) >= 0) {
                return "The header row was read as one cell, and it holds " + other.getValue() + ". If that is what separates the cells of "
                        + "this file, set codelist.delimiter to " + (other.getKey() == '\t' ? "tab" : "\"" + other.getKey() + "\"") + ". ";
            }
        }
        return "";
    }

    // ---- rows ----

    /** Reads the rows of one table and keeps count of everything a warning will need. */
    private static final class Rows {

        private final SourceTable table;
        private final ColumnMapping columns;
        private final List<LoadedCodeList.Entry> entries = new ArrayList<>();
        private final Map<String, String> lineNames = new LinkedHashMap<>();
        private final Map<String, Integer> lineRows = new LinkedHashMap<>();
        private final List<Integer> noCode = new ArrayList<>();
        private final Map<String, Integer> shortNumbers = new LinkedHashMap<>();
        private final Map<String, Integer> codesWithGaps = new LinkedHashMap<>();
        private final Map<String, Integer> unreadFlags = new LinkedHashMap<>();
        private final Map<String, Integer> notAVendor = new LinkedHashMap<>();
        private final Map<String, Integer> otherVendors = new LinkedHashMap<>();
        private final Map<String, Integer> notALine = new LinkedHashMap<>();
        private final Map<String, Integer> unreadEffective = new LinkedHashMap<>();
        private final Map<String, Integer> unreadEnd = new LinkedHashMap<>();
        private final Map<String, Integer> zonedEffective = new LinkedHashMap<>();
        private final Map<String, Integer> zonedEnd = new LinkedHashMap<>();
        private final List<Integer> endsBeforeItStarts = new ArrayList<>();
        private int stating;
        private int namingVendor;

        Rows(SourceTable table, ColumnMapping columns) {
            this.table = table;
            this.columns = columns;
            table.rows().forEach(this::read);
        }

        /**
         * The cell of a row under a column, or null when the file has no such column, the row has
         * no such cell, or the cell is blank. Never an empty or a blank string, which is why the
         * code below can ask a cell whether it is null and need ask nothing else.
         */
        private String cell(SourceTable.Row row, CodeListColumn column) {
            return CodeListText.clean(row.cell(columns.position(column)));
        }

        private void read(SourceTable.Row row) {
            String code = cell(row, CodeListColumn.CODE);
            if (code == null) {
                noCode.add(row.at());
                return;
            }
            // Two ways a code stops being the code a tester types. Neither is corrected: which
            // code was meant is not this tool's to decide. Both are said, with the codes.
            if (SHORT_NUMBER.matcher(code).matches()) {
                shortNumbers.merge(code, 1, Integer::sum);
            }
            if (CodeListText.holdsSpaceOrUnseen(code)) {
                codesWithGaps.merge(code, 1, Integer::sum);
            }
            // The one reader of the flag, shared with the JSON form of the enum. Whatever it does
            // not read stays UNKNOWN, and the cell is kept beside it so a tester can see why.
            String flag = cell(row, CodeListColumn.AUTH_REQUIRED);
            AuthRequirement required = AuthRequirement.parse(flag);
            if (flag != null && !AuthRequirement.recognises(flag)) {
                unreadFlags.merge(flag, 1, Integer::sum);
            }
            if (required != AuthRequirement.UNKNOWN) {
                stating++;
            }

            // The vendor and the line of business are served only when the cell names one. A dash
            // left in either field would be shown as a vendor called "-" and compared as one.
            String vendor = cell(row, CodeListColumn.DELEGATED_VENDOR);
            if (vendor != null && DelegatedVendors.canonical(vendor) == null) {
                notAVendor.merge(vendor, 1, Integer::sum);
                vendor = null;
            }
            if (vendor != null) {
                namingVendor++;
                if (!DelegatedVendors.known(vendor)) {
                    otherVendors.merge(vendor, 1, Integer::sum);
                }
            }
            String line = cell(row, CodeListColumn.LINE_OF_BUSINESS);
            Set<String> lines = new LinkedHashSet<>();
            for (String name : LineMatcher.names(line)) {
                String key = CodeListText.key(name);
                lineNames.putIfAbsent(key, name);
                if (lines.add(key)) {
                    lineRows.merge(key, 1, Integer::sum);
                }
            }
            if (line != null && lines.isEmpty()) {
                notALine.merge(line, 1, Integer::sum);
                line = null;
            }

            String effectiveCell = cell(row, CodeListColumn.EFFECTIVE_DATE);
            LocalDate effective = CodeListDates.read(effectiveCell);
            if (effectiveCell != null && effective == null) {
                unreadEffective.merge(effectiveCell, 1, Integer::sum);
            }
            if (CodeListDates.zoned(effectiveCell)) {
                zonedEffective.merge(effectiveCell, 1, Integer::sum);
            }
            String endCell = cell(row, CodeListColumn.END_DATE);
            LocalDate end = CodeListDates.read(endCell);
            if (endCell != null && end == null) {
                unreadEnd.merge(endCell, 1, Integer::sum);
            }
            if (CodeListDates.zoned(endCell)) {
                zonedEnd.merge(endCell, 1, Integer::sum);
            }
            if (effective != null && end != null && end.isBefore(effective)) {
                endsBeforeItStarts.add(row.at());
            }

            String description = cell(row, CodeListColumn.DESCRIPTION);
            ProcedureCode served = ProcedureCode.builder().code(code).description(description)
                    .codeType(cell(row, CodeListColumn.CODE_TYPE)).system(cell(row, CodeListColumn.SYSTEM))
                    .authRequired(required).authRequiredSource(flag).lineOfBusiness(line).delegatedVendor(vendor)
                    .effectiveDate(effective == null ? effectiveCell : effective.toString())
                    .endDate(end == null ? endCell : end.toString())
                    .notes(cell(row, CodeListColumn.NOTES)).build();
            entries.add(new LoadedCodeList.Entry(served, code.toLowerCase(Locale.ROOT),
                    description == null ? "" : description.toLowerCase(Locale.ROOT), lines, effective, end));
        }

        /** Each line the list names, as first written, with the number of rows that name it. */
        Map<String, Integer> lineCounts() {
            Map<String, Integer> out = new LinkedHashMap<>();
            lineRows.forEach((key, count) -> out.put(lineNames.get(key), count));
            return out;
        }

        List<String> warnings() {
            List<String> out = new ArrayList<>();
            for (int position : columns.unnamed()) {
                List<Integer> holding = table.rows().stream().filter(row -> CodeListText.clean(row.cell(position)) != null)
                        .map(SourceTable.Row::at).toList();
                if (!holding.isEmpty()) {
                    out.add((table.json() ? "A key that is blank" : "Column " + (position + 1) + " has no name in the header row and")
                            + " holds something on " + table.where(holding) + ". It was not read.");
                }
            }
            if (!columns.has(CodeListColumn.AUTH_REQUIRED)) {
                out.add("No column was recognised as the prior authorization flag, so no row says whether prior authorization is "
                        + "required and nothing can be compared with the service on that point. If the file has such a column, name "
                        + "its header under " + CodeListColumn.AUTH_REQUIRED.setting() + ".");
            }
            if (!noCode.isEmpty()) {
                out.add(CodeListText.count(noCode.size(), "row has", "rows have") + " no code and " + (noCode.size() == 1 ? "was" : "were")
                        + " left out: " + table.where(noCode) + ".");
            }
            if (!shortNumbers.isEmpty()) {
                out.add("The code column holds codes that are all digits and shorter than five digits: " + tallied(shortNumbers)
                        + ". If they are meant to be five digit codes, their leading zeros were lost on the way, which is what a "
                        + "spreadsheet does to a code it takes for a number. They are served as written.");
            }
            if (!codesWithGaps.isEmpty()) {
                out.add("The code column holds codes with a space or an unseen character inside them: " + tallied(codesWithGaps)
                        + ". They are served as written, so a tester who types the code without it will not find the row.");
            }
            if (!unreadFlags.isEmpty()) {
                out.add("The prior authorization column holds text this tool does not read as a yes or a no: " + tallied(unreadFlags)
                        + ". Those rows are served as not stated, never as not required. Decide what each value means, then correct "
                        + "the export or have the spelling added.");
            }
            if (!notAVendor.isEmpty()) {
                out.add("The delegated vendor column holds cells that name no vendor: " + tallied(notAVendor)
                        + ". Those rows are served as naming no vendor.");
            }
            if (!otherVendors.isEmpty()) {
                out.add("The list names vendors that are not among the current delegated vendors (" + String.join(", ", DelegatedVendors.CURRENT)
                        + "): " + tallied(otherVendors) + ". They are served as written, and a service that names a current vendor "
                        + "will be reported as disagreeing with them.");
            }
            if (!notALine.isEmpty()) {
                out.add("The line of business column holds cells that name no line: " + tallied(notALine)
                        + ". Those rows are served as stating no line of business, so they are offered for every line.");
            }
            unreadDates(out, "effective date", unreadEffective);
            unreadDates(out, "end date", unreadEnd);
            zonedDates(out, "effective date", zonedEffective);
            zonedDates(out, "end date", zonedEnd);
            if (!endsBeforeItStarts.isEmpty()) {
                out.add(CodeListText.count(endsBeforeItStarts.size(), "row has", "rows have") + " an end date before the effective date: "
                        + table.where(endsBeforeItStarts) + ". Such a row applies on no date of service.");
            }
            if (entries.isEmpty()) {
                out.add("The list has no rows with a code. The picker will offer nothing, and no code can be compared with the service.");
            }
            return out;
        }

        private static void unreadDates(List<String> out, String column, Map<String, Integer> unread) {
            if (!unread.isEmpty()) {
                int cells = unread.values().stream().mapToInt(Integer::intValue).sum();
                out.add(CodeListText.count(cells, "cell", "cells") + " in the " + column + " column could not be read as a date and "
                        + (cells == 1 ? "is" : "are") + " served as written: " + tallied(unread) + ". Such a row is not left out for any "
                        + "date of service. Dates are read when written as 2026-01-31, 1/31/2026 or 20260131, with or without a time of "
                        + "day after the date.");
            }
        }

        /**
         * Said because the date of such a cell was read as it is written, and midnight in one zone
         * is the evening before in another. A time with no zone leaves no doubt about the day, so
         * nothing is said about that.
         */
        private static void zonedDates(List<String> out, String column, Map<String, Integer> zoned) {
            if (!zoned.isEmpty()) {
                int cells = zoned.values().stream().mapToInt(Integer::intValue).sum();
                out.add(CodeListText.count(cells, "cell", "cells") + " in the " + column + " column " + (cells == 1 ? "is" : "are")
                        + " written with a time zone: " + tallied(zoned) + ". The date was read as it is written. The time and the zone "
                        + "were not applied, so a date written for another zone than the one the list is kept in may be a day off.");
            }
        }

        /** The distinct values with the number of rows each is on, most rows first. */
        private static String tallied(Map<String, Integer> values) {
            List<String> shown = values.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .map(entry -> CodeListText.show(entry.getKey()) + " (" + CodeListText.count(entry.getValue(), "row", "rows") + ")")
                    .toList();
            return CodeListText.join(shown);
        }
    }
}
