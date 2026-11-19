package org.p32h.interop.epa.pave.testsupport;

import org.p32h.interop.epa.pave.model.AuthRequirement;
import org.p32h.interop.epa.pave.model.CodeListSelfCheck;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A code list held in memory, for tests of the code that consults one.
 *
 * <p>It stands for a real export: its self-check says it is not the placeholder, unless a test
 * marks it as one with {@link #asPlaceholder()}. A fake that called itself the placeholder would
 * make every test of a comparison a test of the one state in which the tool may refuse to compare.
 *
 * <p>Lines of business are compared ignoring case only, and dates are not filtered. The real
 * matching and date rules are tested against the real code list service.
 */
public final class FakeCodeList implements ProcedureCodeDirectory {

    private final List<ProcedureCode> rows = new ArrayList<>();
    private RuntimeException failure;
    private boolean placeholder;
    private int selfChecks;

    public FakeCodeList row(String code, AuthRequirement auth, String lineOfBusiness, String vendor) {
        rows.add(ProcedureCode.builder().code(code).authRequired(auth).lineOfBusiness(lineOfBusiness).delegatedVendor(vendor).build());
        return this;
    }

    public FakeCodeList row(ProcedureCode row) {
        rows.add(row);
        return this;
    }

    public FakeCodeList failing(RuntimeException exception) {
        this.failure = exception;
        return this;
    }

    /** Makes this list say it is the synthetic placeholder that ships with the tool, as the real one does when no export is configured. */
    public FakeCodeList asPlaceholder() {
        this.placeholder = true;
        return this;
    }

    /** How many times the self-check has been asked for, so a test can show it is asked once for a check and not once for a code. */
    public int selfChecks() {
        return selfChecks;
    }

    @Override
    public List<ProcedureCode> search(String query, String lineOfBusiness, int limit) {
        return rows.stream().filter(r -> query == null || r.code().startsWith(query)).limit(limit).toList();
    }

    @Override
    public List<ProcedureCode> rowsFor(String code, String lineOfBusiness, LocalDate onDate) {
        if (failure != null) {
            throw failure;
        }
        return rows.stream().filter(r -> r.code().equals(code))
                .filter(r -> lineOfBusiness == null || lineOfBusiness.isBlank() || r.lineOfBusiness() == null
                        || r.lineOfBusiness().equalsIgnoreCase(lineOfBusiness.strip()))
                .toList();
    }

    @Override
    public boolean statesLine(String lineOfBusiness) {
        if (failure != null) {
            throw failure;
        }
        return lineOfBusiness != null && rows.stream().anyMatch(r -> r.lineOfBusiness() != null
                && r.lineOfBusiness().equalsIgnoreCase(lineOfBusiness.strip()));
    }

    @Override
    public CodeListSelfCheck selfCheck() {
        selfChecks++;
        return new CodeListSelfCheck("memory", placeholder, "csv", rows.size(), 0, 0, Map.of(), Map.of(), List.of(), List.of());
    }
}
