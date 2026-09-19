package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.store.Ids;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.rules.LookupRule;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streams records to a file as delimited or fixed-width text with optional header and trailer records. Writes to a
 * .part file and renames on success so a reader never sees a half-written file.
 */
public class FileWriter {

    private static final Pattern TOKEN = Pattern.compile("\\{([A-Z_]+)(?::([^}]+))?}");

    public record Written(Path path, long records, long bytes, String sha256, List<String> firstLines) {}

    private final Compiled compiled;
    private final Spec.FileFormat fmt;
    private final RuleContext ctx;

    public FileWriter(Compiled compiled, RuleContext ctx) {
        this.compiled = compiled;
        this.fmt = compiled.spec.fileFormat == null ? new Spec.FileFormat() : compiled.spec.fileFormat;
        this.ctx = ctx;
    }

    public String lineEnding() { return "LF".equalsIgnoreCase(fmt.lineEnding) ? "\n" : "\r\n"; }

    public String headerLine() {
        if (!fmt.headerRow) return null;
        List<String> headers = compiled.fields.stream().map(Compiled.Field::header).toList();
        if ("FIXED_WIDTH".equals(fmt.type)) {
            StringBuilder sb = new StringBuilder();
            for (Compiled.Field f : compiled.fields) sb.append(pad(f.header(), f.spec()));
            return sb.toString();
        }
        return String.join(fmt.delimiter, headers.stream().map(this::quote).toList());
    }

    /** Render one record as it appears in the file. */
    public String formatRecord(String[] record) {
        if ("FIXED_WIDTH".equals(fmt.type)) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (Compiled.Field f : compiled.fields) sb.append(pad(record[i++] == null ? "" : record[i - 1], f.spec()));
            return sb.toString();
        }
        List<String> cells = new ArrayList<>(record.length);
        for (String v : record) cells.add(quote(cell(v)));
        return String.join(fmt.delimiter, cells);
    }

    private String cell(String v) {
        if (v == null) return fmt.nullText == null ? "" : fmt.nullText;
        if (fmt.delimiter != null && !fmt.delimiter.isEmpty() && v.contains(fmt.delimiter) && !"ALL".equals(fmt.quoteMode) && !"MINIMAL".equals(fmt.quoteMode)) {
            switch (fmt.delimiterInValue == null ? "STRIP" : fmt.delimiterInValue) {
                case "FAIL": throw new LookupRule.PipelineFailure("A value contains the delimiter \"" + fmt.delimiter + "\": " + v);
                case "REPLACE": return v.replace(fmt.delimiter, " ");
                default: return v.replace(fmt.delimiter, "");
            }
        }
        return v.replace("\r", "").replace("\n", " ");
    }

    private String quote(String v) {
        String mode = fmt.quoteMode == null ? "NONE" : fmt.quoteMode;
        if (mode.equals("ALL")) return "\"" + v.replace("\"", "\"\"") + "\"";
        if (mode.equals("MINIMAL") && (v.contains(fmt.delimiter) || v.contains("\"") || v.contains("\n"))) return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    static String pad(String v, Spec.Field f) {
        int width = f.width == null ? Math.max(1, v == null ? 1 : v.length()) : f.width;
        String s = v == null ? "" : v;
        if (s.length() > width) return s.substring(0, width);
        String padChar = f.padChar == null || f.padChar.isEmpty() ? " " : f.padChar.substring(0, 1);
        String fill = padChar.repeat(width - s.length());
        return "RIGHT".equalsIgnoreCase(f.align) ? fill + s : s + fill;
    }

    /** Expand a header or trailer template. Tokens: RECORD_COUNT, SUM:HEADER, FILE_SEQ, RUN_DATE:pattern, RUN_TIME:pattern, VENDOR, VERSION. */
    public String expandTemplate(String template, long records, Map<String, Pipeline.FieldStats> stats) {
        if (template == null || template.isBlank()) return null;
        Matcher m = TOKEN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String arg = m.group(2);
            String rep;
            switch (token) {
                case "RECORD_COUNT" -> rep = Long.toString(records);
                case "SUM" -> {
                    BigDecimal sum = BigDecimal.ZERO;
                    for (Compiled.Field f : compiled.fields) {
                        if (f.header().equalsIgnoreCase(arg)) {
                            Pipeline.FieldStats st = stats.get(f.id());
                            if (st != null && st.sum != null) sum = st.sum;
                        }
                    }
                    rep = sum.toPlainString();
                }
                case "FILE_SEQ" -> rep = Integer.toString(ctx.fileSeq);
                case "RUN_DATE" -> rep = ctx.runDate.format(DateTimeFormatter.ofPattern(arg == null ? "yyyyMMdd" : arg));
                case "BUSINESS_DATE" -> rep = ctx.businessDate.format(DateTimeFormatter.ofPattern(arg == null ? "yyyyMMdd" : arg));
                case "RUN_TIME" -> rep = ctx.runTime.format(DateTimeFormatter.ofPattern(arg == null ? "HHmmss" : arg));
                case "VENDOR" -> rep = ctx.vendorCode;
                case "VERSION" -> rep = Integer.toString(ctx.versionNo);
                default -> rep = m.group(0);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Write all records. Header and trailer templates are expanded with the final counts. */
    public Written write(Path target, List<String[]> records, Map<String, Pipeline.FieldStats> stats) {
        Charset cs = Charset.forName(fmt.encoding == null || fmt.encoding.isBlank() ? "UTF-8" : fmt.encoding);
        String nl = lineEnding();
        Path part = target.resolveSibling(target.getFileName() + ".part");
        List<String> first = new ArrayList<>();
        try {
            Files.createDirectories(target.getParent());
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            try (OutputStream os = Files.newOutputStream(part)) {
                String headerRecord = expandTemplate(fmt.headerRecord, records.size(), stats);
                if (headerRecord != null) bytes += emit(os, md, headerRecord + nl, cs, first);
                String header = headerLine();
                if (header != null) bytes += emit(os, md, header + nl, cs, first);
                for (String[] r : records) bytes += emit(os, md, formatRecord(r) + nl, cs, first);
                String trailer = expandTemplate(fmt.trailerRecord, records.size(), stats);
                if (trailer != null) bytes += emit(os, md, trailer + nl, cs, first);
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Written(target, records.size(), bytes, java.util.HexFormat.of().formatHex(md.digest()), first);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + target, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long emit(OutputStream os, MessageDigest md, String line, Charset cs, List<String> first) throws IOException {
        byte[] b = line.getBytes(cs);
        os.write(b);
        md.update(b);
        if (first.size() < 12) first.add(line.replace("\r", "").replace("\n", ""));
        return b.length;
    }

    public static String sha256OfFile(Path p) {
        try {
            return Ids.sha256(Files.readAllBytes(p));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
