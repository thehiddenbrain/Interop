package org.p32h.interop.epa.pave.service.suite;

import java.util.ArrayList;
import java.util.List;

/**
 * A stand-in for the CSV reader in tests of the suite rules: one row per line, cells split on
 * commas, no quoting. What a real CSV file needs is tested where the real reader is.
 */
final class TestCsv implements CsvRows {

    @Override
    public Table read(String text) {
        List<List<String>> lines = new ArrayList<>();
        for (String line : text.replace("﻿", "").split("\\R", -1)) {
            lines.add(List.of(line.split(",", -1)));
        }
        if (lines.isEmpty()) {
            return new Table(List.of(), List.of(), List.of());
        }
        return new Table(lines.get(0), lines.subList(1, lines.size()), List.of());
    }
}
