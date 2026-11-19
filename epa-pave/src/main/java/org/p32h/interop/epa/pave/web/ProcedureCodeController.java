package org.p32h.interop.epa.pave.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;
import org.p32h.interop.epa.pave.config.AppProperties;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.codelist.CodeSearch;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The procedure code picker and the self-check of the list behind it.
 *
 * <p>Both endpoints only read the list that was loaded at startup. Any text is a legal query, so
 * neither has a validation error of its own: a query that matches nothing is answered with an
 * empty list and the reason, where there is one.
 */
@RestController
@RequestMapping("/api/procedure-codes")
public class ProcedureCodeController {

    private final ProcedureCodeService codes;
    private final AppProperties app;

    public ProcedureCodeController(ProcedureCodeService codes, AppProperties app) {
        this.codes = codes;
        this.app = app;
    }

    /**
     * What the type-ahead answers.
     *
     * @param query          the query as it was searched, without the white space around it. Left out when none was given
     * @param lineOfBusiness the line of business that was asked for. Left out when none was given
     * @param narrowed       true when the codes were narrowed to that line and to the rows that state no line
     * @param notice         a sentence saying why they were not narrowed although a line was given. Left out otherwise
     * @param otherLines     a sentence naming the other lines of business the list has matching
     *                       rows under, when the code that was typed is listed under other lines
     *                       only, or narrowing left nothing to offer. Left out otherwise. Without
     *                       it an empty answer would read as "this code is not in the list"
     * @param limit          the most codes one answer holds, {@code app.autocomplete-limit}
     * @param matched        how many rows matched in all, after any narrowing. More than {@code codes}
     *                       holds when the limit cut the answer, so the picker can say "25 of 61"
     * @param codes          the rows, best match first. Each always carries {@code authRequired}:
     *                       {@code UNKNOWN} means the list does not say, which the picker shows as
     *                       no tag at all and never as "No PA"
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"query", "lineOfBusiness", "narrowed", "notice", "otherLines", "limit", "matched", "codes"})
    public record Answer(String query, String lineOfBusiness, boolean narrowed, String notice, String otherLines, int limit, int matched,
                         List<ProcedureCode> codes) {
    }

    /**
     * @param q   matched against the start of the code and anywhere in the description, ignoring case. Blank offers the first rows
     * @param lob the member's line of business, when a member has been resolved
     */
    @GetMapping
    public Answer search(@RequestParam(name = "q", required = false) String q, @RequestParam(name = "lob", required = false) String lob) {
        int limit = app.autocompleteLimit();
        CodeSearch found = codes.find(q, lob, limit);
        return new Answer(found.query(), Texts.trimToNull(lob), found.narrowed(), found.notice(), found.otherLines(), limit,
                found.matched(), found.codes());
    }

    /** What the tool made of the code list. The first thing to call after dropping in a new export. */
    @GetMapping("/self-check")
    public CodeListSelfCheck selfCheck() {
        return codes.selfCheck();
    }
}
