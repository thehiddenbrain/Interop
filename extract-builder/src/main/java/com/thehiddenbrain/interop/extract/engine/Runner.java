package com.thehiddenbrain.interop.extract.engine;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.catalog.SourceData;
import com.thehiddenbrain.interop.extract.config.AppProperties;
import com.thehiddenbrain.interop.extract.definition.Definition;
import com.thehiddenbrain.interop.extract.definition.Spec;
import com.thehiddenbrain.interop.extract.runtime.Run;
import com.thehiddenbrain.interop.extract.transform.RuleContext;
import com.thehiddenbrain.interop.extract.transform.Values;
import com.thehiddenbrain.interop.extract.transform.rules.LookupRule;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single execution path. A run in SAMPLE, PREVIEW or PRODUCTION mode compiles the frozen spec, queries the
 * source, pushes every row through the pipeline and writes the file. The modes differ only in row limit, masking,
 * the watermark window and where the file goes.
 */
@Service
public class Runner {

    private static final Pattern NAME_TOKEN = Pattern.compile("\\{([A-Z_]+)(?::([^}]+))?}");

    public static class Options {
        public String mode = "SAMPLE";
        public boolean masking = true;
        public int rowLimit = 0;
        public List<String> cohort = List.of();
        public boolean synthetic;
        public LocalDate runDate = LocalDate.now();
        public LocalDate businessDate = LocalDate.now();
        public LocalDateTime windowFrom;
        public LocalDateTime windowTo;
        public Path targetDir;
        public int fileSeq = 1;
        public String fileNamePattern;
        public String dateSource = "BUSINESS_DATE";
    }

    private final Compiler compiler;
    private final QueryPlanner planner;
    private final SourceData source;
    private final CatalogService catalog;
    private final AppProperties props;

    public Runner(Compiler compiler, QueryPlanner planner, SourceData source, CatalogService catalog, AppProperties props) {
        this.compiler = compiler;
        this.planner = planner;
        this.source = source;
        this.catalog = catalog;
        this.props = props;
    }

    public Compiled compile(Definition def, Spec spec) {
        return compiler.compile(def, spec);
    }

    /** Execute into {@code run}, filling every field the UI and the audit need. Never throws for analyst-facing problems. */
    public void execute(Run run, Definition def, Definition.Version v, Options o) {
        long t0 = System.currentTimeMillis();
        run.status = "RUNNING";
        run.startedAt = now();
        run.mode = o.mode;
        run.masked = o.masking;
        run.synthetic = o.synthetic;
        run.businessDate = o.businessDate.toString();
        run.windowFrom = o.windowFrom == null ? null : o.windowFrom.toString();
        run.windowTo = o.windowTo == null ? null : o.windowTo.toString();
        run.specHash = v.specHash;
        run.catalogChecksum = catalog.checksum();
        run.engineVersion = props.getEngineVersion();

        Compiled c = compiler.compile(def, v.spec);
        run.sqlText = c.sqlText;
        if (!c.ok()) {
            fail(run, "The layout does not compile: " + String.join("; ", c.errors().stream().map(p -> p.where() + ": " + p.message()).toList()), t0);
            return;
        }
        RuleContext ctx = new RuleContext();
        ctx.runDate = o.runDate;
        ctx.businessDate = o.businessDate;
        ctx.runTime = LocalDateTime.now();
        ctx.vendorCode = def.vendorCode;
        ctx.fileSeq = o.fileSeq;
        ctx.versionNo = v.versionNo;
        ctx.masking = o.masking;
        ctx.maskSalt = "mask:" + def.id;
        Map<String, RuleContext.LookupTable> tables = new HashMap<>();
        for (String lookupId : c.lookupsUsed) {
            tables.put(lookupId, loadLookup(lookupId));
            run.lookupSnapshots.put(lookupId, source.lookupSnapshot(lookupId));
        }
        ctx.lookups = tables::get;

        QueryPlanner.Options qo = new QueryPlanner.Options();
        qo.runDate = o.runDate;
        qo.rowLimit = o.rowLimit;
        qo.cohort = o.cohort == null ? List.of() : o.cohort;
        qo.synthetic = o.synthetic;
        qo.windowFrom = o.windowFrom;
        qo.windowTo = o.windowTo;
        QueryPlanner.Result q;
        try {
            q = planner.execute(c, qo);
        } catch (RuntimeException e) {
            fail(run, "Query failed: " + e.getMessage(), t0);
            return;
        }
        run.scanned = q.scanned();
        run.matched = q.matched();
        run.boundParams = q.boundParams();

        Pipeline pipeline = new Pipeline(c, ctx);
        List<String[]> records = new ArrayList<>(q.rows().size());
        long rowNo = 0;
        try {
            for (Map<String, Object> row : q.rows()) {
                rowNo++;
                records.add(pipeline.apply(rowNo, row));
            }
        } catch (LookupRule.PipelineFailure e) {
            collectWarnings(run, c, ctx, pipeline);
            fail(run, "Stopped at row " + rowNo + ": " + e.getMessage(), t0);
            return;
        } catch (RuntimeException e) {
            collectWarnings(run, c, ctx, pipeline);
            fail(run, "Rule error at row " + rowNo + " in field " + ctx.currentFieldId + ": " + e.getMessage(), t0);
            return;
        }
        collectWarnings(run, c, ctx, pipeline);
        run.maskedFields = new ArrayList<>(pipeline.maskedFields());
        run.rowCount = records.size();

        FileWriter writer = new FileWriter(c, ctx);
        run.fileName = fileName(def, v, o, c.spec.fileFormat);
        String header = writer.headerLine();
        List<String> lines = new ArrayList<>();
        String headerRecord = writer.expandTemplate(c.spec.fileFormat.headerRecord, records.size(), pipeline.stats());
        if (headerRecord != null) lines.add(headerRecord);
        if (header != null) lines.add(header);
        for (int i = 0; i < Math.min(records.size(), 12); i++) lines.add(writer.formatRecord(records.get(i)));
        String trailer = writer.expandTemplate(c.spec.fileFormat.trailerRecord, records.size(), pipeline.stats());
        if (trailer != null && records.size() <= 12) lines.add(trailer);
        run.previewLines = lines;
        List<Map<String, String>> previewRows = new ArrayList<>();
        for (int i = 0; i < Math.min(records.size(), 50); i++) {
            Map<String, String> m = new LinkedHashMap<>();
            String[] r = records.get(i);
            for (int j = 0; j < c.fields.size(); j++) m.put(c.fields.get(j).header(), r[j]);
            previewRows.add(m);
        }
        run.previewRows = previewRows;

        if ("PREVIEW".equals(o.mode)) {
            run.status = "WRITTEN";
            run.finishedAt = now();
            run.durationMs = System.currentTimeMillis() - t0;
            return;
        }
        try {
            FileWriter.Written w = writer.write(o.targetDir.resolve(run.fileName), records, pipeline.stats());
            run.filePath = w.path().toString();
            run.sha256 = w.sha256();
            run.bytes = w.bytes();
        } catch (RuntimeException e) {
            fail(run, "Could not write the file: " + e.getMessage(), t0);
            return;
        }
        run.status = "WRITTEN";
        run.finishedAt = now();
        run.durationMs = System.currentTimeMillis() - t0;
    }

    private void collectWarnings(Run run, Compiled c, RuleContext ctx, Pipeline pipeline) {
        Map<String, String> headers = new HashMap<>();
        for (Compiled.Field f : c.fields) headers.put(f.id(), f.header());
        List<Run.Warning> warnings = new ArrayList<>();
        for (Map.Entry<String, Integer> e : ctx.warningCounts().entrySet()) {
            String[] parts = e.getKey().split("\\|", 2);
            String code = parts.length > 1 ? parts[1] : parts[0];
            String severity = code.equals("LOOKUP_MISS") || code.equals("TRUNCATED") || code.equals("INVALID_DATE") ? "MEDIUM" : "LOW";
            warnings.add(new Run.Warning(headers.getOrDefault(parts[0], parts[0]), code, e.getValue(), ctx.warningExamples().get(e.getKey()), severity));
        }
        run.warnings = warnings;
        Map<String, Run.FieldStat> stats = new LinkedHashMap<>();
        for (Compiled.Field f : c.fields) {
            Pipeline.FieldStats s = pipeline.stats().get(f.id());
            Run.FieldStat st = new Run.FieldStat();
            st.header = f.header();
            st.rows = s.rows;
            st.nulls = s.nulls;
            st.truncated = s.truncated;
            st.maxLength = s.maxLength;
            st.sum = s.sum == null ? null : s.sum.toPlainString();
            st.nullRatePct = s.rows == 0 ? 0 : Math.round(1000.0 * s.nulls / s.rows) / 10.0;
            stats.put(f.header(), st);
        }
        run.fieldStats = stats;
    }

    private void fail(Run run, String message, long t0) {
        run.status = "FAILED";
        run.error = message;
        run.finishedAt = now();
        run.durationMs = System.currentTimeMillis() - t0;
    }

    public String fileName(Definition def, Definition.Version v, Options o, Spec.FileFormat fmt) {
        String pattern = o.fileNamePattern;
        if (pattern == null || pattern.isBlank()) {
            pattern = v.productionConfig != null && v.productionConfig.fileName != null && v.productionConfig.fileName.pattern != null
                    ? v.productionConfig.fileName.pattern : "{VENDOR}_{SUBJECT}_{DATE:yyyyMMdd}";
        }
        LocalDate date = "RUN_TIME".equals(o.dateSource) ? o.runDate : o.businessDate;
        Matcher m = NAME_TOKEN.matcher(pattern);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = m.group(1);
            String arg = m.group(2);
            String rep = switch (token) {
                case "VENDOR" -> def.vendorCode;
                case "SUBJECT" -> subjectCode(def);
                case "NAME" -> def.slug == null ? "feed" : def.slug.toUpperCase().replace('-', '_');
                case "DATE" -> date.format(DateTimeFormatter.ofPattern(arg == null ? "yyyyMMdd" : arg));
                case "TIME" -> LocalDateTime.now().format(DateTimeFormatter.ofPattern(arg == null ? "HHmmss" : arg));
                case "SEQ" -> String.format("%0" + (arg == null ? 3 : Integer.parseInt(arg)) + "d", o.fileSeq);
                case "VERSION" -> "v" + v.versionNo;
                default -> m.group(0);
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        String name = sb.toString().replaceAll("[^A-Za-z0-9._-]", "_");
        if (!name.contains(".")) name = name + "." + (fmt == null || fmt.extension == null || fmt.extension.isBlank() ? "txt" : fmt.extension);
        return name;
    }

    private static String subjectCode(Definition def) {
        return switch (def.subjectArea == null ? "" : def.subjectArea) {
            case "MEMBER" -> "ELIG";
            case "CLAIM" -> "CLAIMS";
            case "PROVIDER" -> "PROV";
            default -> "EXTRACT";
        };
    }

    private RuleContext.LookupTable loadLookup(String lookupId) {
        CatalogModel.Lookup l = catalog.lookup(lookupId);
        Map<String, List<Map<String, Object>>> index = new HashMap<>();
        int size = 0;
        for (Map<String, Object> row : source.lookupRows(lookupId)) {
            StringBuilder key = new StringBuilder();
            for (String k : l.keyColumns()) key.append(Values.text(row.get(k))).append('\u0001');
            Map<String, Object> withDefault = new LinkedHashMap<>(row);
            withDefault.put("__default", l.defaultReturn());
            index.computeIfAbsent(key.toString(), x -> new ArrayList<>()).add(withDefault);
            size++;
        }
        int total = size;
        return new RuleContext.LookupTable() {
            @Override
            public Map<String, Object> find(List<Object> keys, LocalDate asOf) {
                StringBuilder key = new StringBuilder();
                for (Object k : keys) key.append(Values.text(k)).append('\u0001');
                List<Map<String, Object>> rows = index.get(key.toString());
                if (rows == null || rows.isEmpty()) return null;
                if (asOf != null && rows.get(0).containsKey("effective_date")) {
                    Map<String, Object> best = null;
                    for (Map<String, Object> r : rows) {
                        LocalDate eff = Values.date(r.get("effective_date"), null);
                        if (eff != null && eff.isAfter(asOf)) continue;
                        if (best == null || Values.compare(eff, best.get("effective_date")) > 0) best = r;
                    }
                    return best;
                }
                return rows.get(0);
            }

            @Override
            public int size() { return total; }
        };
    }

    public static String now() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
