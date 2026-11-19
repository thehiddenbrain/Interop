package org.p32h.interop.epa.pave.config;

import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The procedure code list.
 *
 * @param file      path to the export. Blank uses the bundled placeholder list
 * @param format    auto, csv or json. Auto decides from the file extension
 * @param delimiter the CSV field delimiter
 * @param columns   for each column the tool knows, the header names it may appear under
 * @param lineOfBusinessAliases a line of business as the member API names it, mapped to the names
 *                  the list uses for it. Empty by default: with no alias a line matches only itself
 */
@ConfigurationProperties("codelist")
public record CodelistProperties(
        @DefaultValue("") String file,
        @DefaultValue("auto") String format,
        @DefaultValue(",") String delimiter,
        @DefaultValue Columns columns,
        Map<String, List<String>> lineOfBusinessAliases) {

    public CodelistProperties {
        lineOfBusinessAliases = lineOfBusinessAliases == null ? Map.of() : Map.copyOf(lineOfBusinessAliases);
    }

    /**
     * Candidate header names per column. The header row of the real export is not known in advance,
     * so each column is found by trying these in order; the first one present wins. Matching ignores
     * case, spaces, underscores, hyphens, dots, slashes, question marks and colons, so "PA Required?"
     * and "CPT/HCPCS Code" are found. A bracketed part at the end of a header, as in
     * "Auth Required (Y/N)", is ignored as well, but only for a column that no header matches as
     * written, and never to choose between two headers that differ only in that part. Only the code
     * column is required.
     */
    public record Columns(
            @DefaultValue({"code", "procedure code", "proc code", "cpt", "hcpcs", "cpt/hcpcs", "cpt hcpcs code",
                    "service code", "billing code", "cpt code", "hcpcs code", "procedure"}) List<String> code,
            @DefaultValue({"description", "desc", "code description", "procedure description", "short description",
                    "long description"}) List<String> description,
            @DefaultValue({"code type", "codetype", "type", "code set"}) List<String> codeType,
            @DefaultValue({"system", "code system", "coding system"}) List<String> system,
            @DefaultValue({"auth required", "pa required", "prior auth required", "prior authorization required",
                    "prior auth", "prior authorization", "authorization required", "requires pa", "requires auth",
                    "pa", "auth"}) List<String> authRequired,
            @DefaultValue({"line of business", "lob", "business line", "product", "plan type"}) List<String> lineOfBusiness,
            @DefaultValue({"delegated vendor", "um vendor", "vendor", "delegate", "delegated to", "delegated entity"})
            List<String> delegatedVendor,
            @DefaultValue({"effective date", "eff date", "effective", "start date", "begin date"}) List<String> effectiveDate,
            @DefaultValue({"end date", "term date", "termination date", "expiration date", "thru date"}) List<String> endDate,
            @DefaultValue({"notes", "note", "comments", "comment", "remarks"}) List<String> notes) {
    }

    public static CodelistProperties defaults() {
        return of(Map.of());
    }

    public static CodelistProperties of(Map<String, String> overrides) {
        return ConfigDefaults.bind(CodelistProperties.class, "codelist", overrides);
    }
}
