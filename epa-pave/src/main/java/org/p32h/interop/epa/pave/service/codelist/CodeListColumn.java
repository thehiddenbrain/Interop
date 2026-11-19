package org.p32h.interop.epa.pave.service.codelist;

import java.util.List;
import java.util.function.Function;
import org.p32h.interop.epa.pave.config.CodelistProperties;

/**
 * The columns this tool knows how to read from a code list, in the order they are looked for.
 *
 * <p>Each is named as the self-check names it, which is also the name of the field it fills on a
 * row, and each knows where its candidate headers are configured.
 */
enum CodeListColumn {
    CODE("code", "code", CodelistProperties.Columns::code),
    DESCRIPTION("description", "description", CodelistProperties.Columns::description),
    CODE_TYPE("codeType", "code-type", CodelistProperties.Columns::codeType),
    SYSTEM("system", "system", CodelistProperties.Columns::system),
    AUTH_REQUIRED("authRequired", "auth-required", CodelistProperties.Columns::authRequired),
    LINE_OF_BUSINESS("lineOfBusiness", "line-of-business", CodelistProperties.Columns::lineOfBusiness),
    DELEGATED_VENDOR("delegatedVendor", "delegated-vendor", CodelistProperties.Columns::delegatedVendor),
    EFFECTIVE_DATE("effectiveDate", "effective-date", CodelistProperties.Columns::effectiveDate),
    END_DATE("endDate", "end-date", CodelistProperties.Columns::endDate),
    NOTES("notes", "notes", CodelistProperties.Columns::notes);

    private final String field;
    private final String setting;
    private final Function<CodelistProperties.Columns, List<String>> candidates;

    CodeListColumn(String field, String setting, Function<CodelistProperties.Columns, List<String>> candidates) {
        this.field = field;
        this.setting = setting;
        this.candidates = candidates;
    }

    /** The name in the self-check, and the field of a row this column fills. */
    String field() {
        return field;
    }

    /** The property that lists this column's candidate headers, as application.yml writes it. */
    String setting() {
        return "codelist.columns." + setting;
    }

    /** The header names this column may appear under, in the order they are tried. Never null. */
    List<String> candidates(CodelistProperties.Columns columns) {
        List<String> configured = columns == null ? null : candidates.apply(columns);
        return configured == null ? List.of() : configured;
    }
}
