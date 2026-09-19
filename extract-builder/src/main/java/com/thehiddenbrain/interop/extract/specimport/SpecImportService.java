package com.thehiddenbrain.interop.extract.specimport;

import com.thehiddenbrain.interop.extract.catalog.CatalogModel;
import com.thehiddenbrain.interop.extract.catalog.CatalogService;
import com.thehiddenbrain.interop.extract.definition.Spec;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a vendor's layout spec (a CSV, TSV or pipe-delimited table, or plain "name - description" lines) and proposes
 * a definition: the best catalog element per line, the date pattern and length it implies, and the rules that
 * follow. Matching is lexical, by alias and token overlap, and every match carries a confidence so the analyst
 * reviews rather than trusts.
 */
@Service
public class SpecImportService {

    private static final Pattern DATE_HINT = Pattern.compile("(CCYYMMDD|YYYYMMDD|MM/DD/YYYY|MM/DD/CCYY|MMDDYYYY|YYYY-MM-DD|DDMMYYYY|MM-DD-YYYY)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LENGTH_HINT = Pattern.compile("\\b(?:len(?:gth)?|size|width|max)?\\s*[:=(]?\\s*(\\d{1,3})\\s*\\)?\\b");
    private static final Set<String> STOP = Set.of("the", "of", "a", "an", "and", "or", "code", "number", "id", "field", "value", "vendor", "member", "patient", "date", "name", "type", "flag", "indicator", "num", "no", "cd", "dt", "nm");

    private final CatalogService catalog;

    public SpecImportService(CatalogService catalog) {
        this.catalog = catalog;
    }

    public record Line(String name, String description, String format, String length, String notes) {}

    public Map<String, Object> propose(String text, String subjectAreaHint, String vendorCode) {
        List<Line> lines = parse(text);
        List<Map<String, Object>> fields = new ArrayList<>();
        Map<String, Integer> areaVotes = new HashMap<>();
        int pos = 0;
        for (Line l : lines) {
            pos++;
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("position", pos);
            f.put("vendorField", l.name());
            f.put("description", l.description());
            f.put("format", l.format());
            String header = l.name().toUpperCase().replaceAll("[^A-Z0-9]+", "_").replaceAll("(^_|_$)", "");
            f.put("header", header.isEmpty() ? "FIELD_" + pos : header);
            String haystack = (l.name() + " " + l.description() + " " + l.format() + " " + l.notes()).toLowerCase();
            Integer maxLength = length(l.length(), l.format(), l.description());
            if (maxLength != null) f.put("maxLength", maxLength);
            String datePattern = datePattern(haystack);
            List<Map<String, Object>> candidates = match(l, subjectAreaHint);
            List<Spec.Input> inputs = new ArrayList<>();
            List<Spec.RuleSpec> rules = new ArrayList<>();
            Spec.RuleSpec combiner = null;
            String reason;
            double confidence;
            if (isConstantLine(haystack)) {
                Spec.RuleSpec c = new Spec.RuleSpec("CONSTANT", Map.of("token", "LITERAL", "value", constantValue(l)));
                rules.add(c);
                reason = "Reads as a constant or filler";
                confidence = 0.7;
            } else if (isFullName(haystack)) {
                String prefix = entityPrefixFor(haystack, subjectAreaHint);
                inputs.add(input(prefix + ".last_name"));
                inputs.add(input(prefix + ".first_name"));
                combiner = new Spec.RuleSpec("CONCAT", Map.of("template", "{1}, {2}", "skipBlank", true));
                rules.add(new Spec.RuleSpec("CLEAN_TEXT", Map.of("textCase", "UPPER", "trim", "COLLAPSE")));
                reason = "A full name: last and first name combined";
                confidence = 0.8;
                areaVotes.merge(prefix.equals("provider") ? "PROVIDER" : "MEMBER", 2, Integer::sum);
            } else if (!candidates.isEmpty()) {
                Map<String, Object> best = candidates.get(0);
                String elementId = (String) best.get("element");
                inputs.add(input(elementId));
                confidence = (double) best.get("score");
                reason = (String) best.get("reason");
                CatalogModel.Element el = catalog.element(elementId);
                areaVotes.merge(catalog.entity(el.entity()).subjectArea(), 1, Integer::sum);
                if (el.type().equals("DATE") || el.type().equals("TIMESTAMP")) {
                    rules.add(new Spec.RuleSpec("FORMAT_DATE", Map.of("pattern", datePattern == null ? "yyyyMMdd" : datePattern)));
                }
                if (el.type().equals("DECIMAL") && (haystack.contains("implied") || haystack.contains("no decimal") || haystack.contains("cents"))) {
                    rules.add(new Spec.RuleSpec("FORMAT_NUMBER", Map.of("decimals", 2, "impliedDecimal", true)));
                }
                if (elementId.endsWith("member_id") && vendorCode != null) {
                    String lookup = catalog.lookups().stream().map(CatalogModel.Lookup::id).filter(id -> id.startsWith(vendorCode.toLowerCase().replaceAll("[^a-z0-9]", "").substring(0, Math.min(4, vendorCode.length())))).findFirst().orElse(null);
                    if (lookup != null && (haystack.contains("vendor") || haystack.contains("your") || haystack.contains("assigned") || haystack.contains("crosswalk"))) {
                        rules.add(new Spec.RuleSpec("LOOKUP", Map.of("lookup", lookup, "onMiss", "FAIL")));
                        reason += "; vendor ID via crosswalk " + lookup;
                    }
                }
                if (elementId.endsWith("gender_code") && (haystack.contains("1") && haystack.contains("2") || haystack.contains("m/f") || haystack.contains("male"))) {
                    if (haystack.matches(".*\\b1\\b.*") && haystack.matches(".*\\b2\\b.*")) rules.add(new Spec.RuleSpec("MAP_VALUES", Map.of("map", Map.of("M", "1", "F", "2"), "defaultValue", "0")));
                }
                if (elementId.endsWith("zip") && maxLength != null && maxLength == 5) rules.add(new Spec.RuleSpec("SUBSTRING", Map.of("mode", "POSITION", "start", 1, "length", 5)));
                if (elementId.contains("phone") && (haystack.contains("digits only") || haystack.contains("10 digit") || haystack.contains("numeric"))) rules.add(new Spec.RuleSpec("CLEAN_TEXT", Map.of("strip", "NON_DIGITS")));
                if (haystack.contains("upper")) rules.add(new Spec.RuleSpec("CLEAN_TEXT", Map.of("textCase", "UPPER")));
            } else {
                reason = "No catalog element matched; pick one or make it a constant";
                confidence = 0.0;
            }
            f.put("inputs", inputs);
            f.put("combiner", combiner);
            f.put("rules", rules);
            f.put("confidence", (int) Math.round(confidence * 100));
            f.put("reason", reason);
            f.put("candidates", candidates.stream().limit(4).toList());
            fields.add(f);
        }
        String area = subjectAreaHint != null && !subjectAreaHint.isBlank() ? subjectAreaHint : areaVotes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("MEMBER");
        long matched = fields.stream().filter(f -> ((Number) f.get("confidence")).intValue() > 0).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subjectArea", area);
        out.put("grain", catalog.subjectArea(area).grains().get(0).entity());
        out.put("lines", lines.size());
        out.put("matched", matched);
        out.put("fields", fields);
        out.put("summary", matched + " of " + lines.size() + " vendor fields matched to the catalog" + (matched < lines.size() ? "; " + (lines.size() - matched) + " need a decision" : ""));
        return out;
    }

    private Spec.Input input(String element) {
        Spec.Input in = new Spec.Input();
        in.element = element;
        return in;
    }

    private String entityPrefixFor(String haystack, String hint) {
        if (haystack.contains("provider") || haystack.contains("physician") || "PROVIDER".equals(hint)) return "provider";
        if (haystack.contains("subscriber") || haystack.contains("policy holder")) return "subscriber";
        return "member";
    }

    private static boolean isFullName(String h) {
        return (h.contains("full name") || h.contains("member name") || h.contains("patient name") || h.contains("subscriber name") || h.contains("provider name") || h.matches(".*\\bname\\b.*"))
                && !h.contains("first") && !h.contains("last") && !h.contains("middle") && !h.contains("plan name") && !h.contains("org") && !h.contains("group name") && !h.contains("facility") && !h.contains("practice");
    }

    private static boolean isConstantLine(String h) {
        return h.contains("filler") || h.contains("constant") || h.contains("always") || h.contains("hard-coded") || h.contains("hardcoded") || h.contains("record type") || h.contains("fixed value") || h.contains("literal");
    }

    private static String constantValue(Line l) {
        Matcher m = Pattern.compile("[\"'“]([^\"'”]+)[\"'”]").matcher(l.description() + " " + l.notes());
        if (m.find()) return m.group(1);
        Matcher a = Pattern.compile("always\\s+([A-Z0-9]+)", Pattern.CASE_INSENSITIVE).matcher(l.description() + " " + l.notes());
        if (a.find()) return a.group(1);
        return "";
    }

    private static String datePattern(String h) {
        Matcher m = DATE_HINT.matcher(h);
        if (!m.find()) return null;
        return switch (m.group(1).toUpperCase()) {
            case "CCYYMMDD", "YYYYMMDD" -> "yyyyMMdd";
            case "MM/DD/YYYY", "MM/DD/CCYY" -> "MM/dd/yyyy";
            case "MMDDYYYY" -> "MMddyyyy";
            case "YYYY-MM-DD" -> "yyyy-MM-dd";
            case "DDMMYYYY" -> "ddMMyyyy";
            case "MM-DD-YYYY" -> "MM-dd-yyyy";
            default -> null;
        };
    }

    private static Integer length(String lengthCol, String format, String description) {
        for (String s : new String[]{lengthCol, format}) {
            if (s == null) continue;
            String t = s.trim();
            if (t.matches("\\d{1,3}")) return Integer.parseInt(t);
            Matcher m = Pattern.compile("(?:X|A|N|9)\\((\\d+)\\)", Pattern.CASE_INSENSITIVE).matcher(t);
            if (m.find()) return Integer.parseInt(m.group(1));
            Matcher c = Pattern.compile("(?:char|varchar|text|alpha|an|string)\\s*\\(?\\s*(\\d+)", Pattern.CASE_INSENSITIVE).matcher(t);
            if (c.find()) return Integer.parseInt(c.group(1));
        }
        if (description != null) {
            Matcher m = Pattern.compile("(?:max(?:imum)?|up to|length)\\s*(?:of)?\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE).matcher(description);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return null;
    }

    private List<Map<String, Object>> match(Line l, String areaHint) {
        String name = l.name().toLowerCase();
        String full = (l.name() + " " + l.description()).toLowerCase();
        Set<String> tokens = tokens(full);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CatalogModel.Element el : catalog.elements()) {
            double score = 0;
            String reason = null;
            String elName = el.name().toLowerCase();
            List<String> aliases = el.aliases() == null ? List.of() : el.aliases();
            if (elName.equals(name) || aliases.contains(name)) {
                score = 0.97;
                reason = "Exact match on \"" + l.name() + "\"";
            } else {
                for (String a : aliases) {
                    if (full.contains(a) && a.length() > 2) {
                        double s = 0.6 + Math.min(0.3, a.length() / 40.0);
                        if (s > score) {
                            score = s;
                            reason = "Alias \"" + a + "\" appears in the vendor's text";
                        }
                    }
                }
                Set<String> elTokens = tokens(elName + " " + String.join(" ", aliases));
                long overlap = tokens.stream().filter(elTokens::contains).count();
                if (overlap > 0) {
                    double s = 0.35 + 0.15 * overlap;
                    if (s > score) {
                        score = Math.min(0.75, s);
                        reason = overlap + " word(s) in common with " + el.name();
                    }
                }
            }
            if (score == 0) continue;
            String area = catalog.entity(el.entity()).subjectArea();
            if (areaHint != null && !areaHint.isBlank() && !areaHint.equals(area)) score *= 0.6;
            if (el.entity().equals("subscriber") && !full.contains("subscriber") && !full.contains("policy")) score *= 0.7;
            if (el.entity().equals("claim_line") && !full.contains("line")) score *= 0.85;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("element", el.id());
            m.put("name", el.name());
            m.put("entity", el.entity());
            m.put("type", el.type());
            m.put("phi", el.isPhi());
            m.put("score", Math.round(score * 100) / 100.0);
            m.put("reason", reason);
            out.add(m);
        }
        out.sort((a, b) -> Double.compare((double) b.get("score"), (double) a.get("score")));
        return out;
    }

    private static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        for (String t : s.toLowerCase().split("[^a-z0-9]+")) if (t.length() > 1 && !STOP.contains(t)) out.add(t);
        return out;
    }

    /** Parse a pasted or uploaded spec: delimited with a header row, or one field per line. */
    public List<Line> parse(String text) {
        List<Line> out = new ArrayList<>();
        if (text == null) return out;
        String[] rows = text.replace("\r", "").split("\n");
        String delim = detectDelimiter(rows);
        int nameIdx = 0, descIdx = 1, fmtIdx = 2, lenIdx = 3, notesIdx = -1;
        boolean headerSkipped = false;
        for (String raw : rows) {
            if (raw.isBlank()) continue;
            String[] cells = delim == null ? new String[]{raw} : raw.split(Pattern.quote(delim), -1);
            for (int i = 0; i < cells.length; i++) cells[i] = cells[i].trim().replaceAll("^\"|\"$", "");
            if (!headerSkipped) {
                headerSkipped = true;
                String joined = String.join(" ", cells).toLowerCase();
                if (cells.length > 1 && (joined.contains("field") || joined.contains("name") || joined.contains("description") || joined.contains("length") || joined.contains("format"))) {
                    for (int i = 0; i < cells.length; i++) {
                        String c = cells[i].toLowerCase();
                        if (c.contains("field") || c.equals("name") || c.contains("column") || c.contains("element")) nameIdx = i;
                        else if (c.contains("desc") || c.contains("definition") || c.contains("comment")) descIdx = i;
                        else if (c.contains("format") || c.contains("type") || c.contains("pattern")) fmtIdx = i;
                        else if (c.contains("len") || c.contains("size") || c.contains("width")) lenIdx = i;
                        else if (c.contains("note") || c.contains("rule") || c.contains("value")) notesIdx = i;
                    }
                    continue;
                }
            }
            if (delim == null) {
                String[] parts = raw.split("\\s+[-–:]\\s+", 2);
                out.add(new Line(parts[0].trim(), parts.length > 1 ? parts[1].trim() : "", "", "", ""));
                continue;
            }
            String name = cell(cells, nameIdx);
            if (name.isEmpty()) continue;
            if (name.matches("\\d+") && cells.length > nameIdx + 1) name = cell(cells, nameIdx + 1);
            out.add(new Line(name, cell(cells, descIdx), cell(cells, fmtIdx), cell(cells, lenIdx), notesIdx >= 0 ? cell(cells, notesIdx) : ""));
        }
        return out;
    }

    private static String cell(String[] cells, int i) {
        return i >= 0 && i < cells.length ? cells[i] : "";
    }

    private static String detectDelimiter(String[] rows) {
        int tabs = 0, commas = 0, pipes = 0, lines = 0;
        for (String r : rows) {
            if (r.isBlank()) continue;
            lines++;
            tabs += r.chars().filter(c -> c == '\t').count() > 0 ? 1 : 0;
            commas += r.chars().filter(c -> c == ',').count() > 0 ? 1 : 0;
            pipes += r.chars().filter(c -> c == '|').count() > 0 ? 1 : 0;
        }
        if (lines == 0) return null;
        if (tabs >= lines * 0.6) return "\t";
        if (pipes >= lines * 0.6) return "|";
        if (commas >= lines * 0.6) return ",";
        return null;
    }
}
