package com.thehiddenbrain.interop.patientaccess.demo;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/**
 * FHIR date-search semantics for the demo server. Every value, searched or stored, becomes a half-open
 * {@code [start, end)} range at its own precision (a year covers the whole year, a Period its start to end),
 * and the search prefix decides how the two ranges must relate. Times without a zone count as UTC.
 */
final class DemoDates {

    static final Set<String> PREFIXES = Set.of("eq", "ne", "gt", "lt", "ge", "le", "sa", "eb", "ap");

    /** Range with open ends expressed as Instant.MIN / MAX so comparisons need no null checks. */
    record Range(Instant start, Instant end) {
        Range {
            start = start == null ? Instant.MIN : start;
            end = end == null ? Instant.MAX : end;
        }
    }

    private DemoDates() {
    }

    /** Splits {@code ge2026-01-01} into prefix and value; a value without prefix is {@code eq}. */
    static String[] splitPrefix(String value) {
        String v = value == null ? "" : value.trim();
        if (v.length() > 2 && PREFIXES.contains(v.substring(0, 2)) && !Character.isDigit(v.charAt(0))) {
            return new String[]{v.substring(0, 2), v.substring(2)};
        }
        return new String[]{"eq", v};
    }

    /** Parses a FHIR date / dateTime / instant / partial date; throws IllegalArgumentException when malformed. */
    static Range parse(String value) {
        String v = value == null ? "" : value.trim();
        try {
            if (v.matches("\\d{4}")) {
                LocalDate start = LocalDate.of(Integer.parseInt(v), 1, 1);
                return new Range(day(start), day(start.plusYears(1)));
            }
            if (v.matches("\\d{4}-\\d{2}")) {
                LocalDate start = LocalDate.parse(v + "-01");
                return new Range(day(start), day(start.plusMonths(1)));
            }
            if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate start = LocalDate.parse(v);
                return new Range(day(start), day(start.plusDays(1)));
            }
            if (v.length() > 11 && v.charAt(10) == 'T') {
                Instant start;
                try {
                    start = OffsetDateTime.parse(v).toInstant();
                } catch (DateTimeParseException noOffset) {
                    start = LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
                }
                String time = v.substring(11).replaceAll("([+-]\\d{2}:\\d{2}|Z)$", "");
                ChronoUnit precision = time.contains(".") ? ChronoUnit.MILLIS : time.length() >= 8 ? ChronoUnit.SECONDS : ChronoUnit.MINUTES;
                return new Range(start, start.plus(1, precision));
            }
        } catch (DateTimeParseException | ArithmeticException e) {
            throw new IllegalArgumentException("'" + value + "' is not a valid FHIR date: " + e.getMessage());
        }
        throw new IllegalArgumentException("'" + value + "' is not a valid FHIR date (expected YYYY, YYYY-MM, YYYY-MM-DD or a dateTime)");
    }

    /** Range of a stored value: a date/dateTime string or a Period object; null when absent or unparseable. */
    static Range of(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            if (node.isTextual()) {
                return parse(node.asText());
            }
            if (node.isObject() && (node.has("start") || node.has("end"))) {
                Range start = node.hasNonNull("start") ? parse(node.get("start").asText()) : null;
                Range end = node.hasNonNull("end") ? parse(node.get("end").asText()) : null;
                return new Range(start == null ? null : start.start(), end == null ? null : end.end());
            }
        } catch (IllegalArgumentException malformedData) {
            return null;
        }
        return null;
    }

    static boolean matches(String prefix, Range param, Range target) {
        boolean contains = !target.start().isBefore(param.start()) && !target.end().isAfter(param.end());
        return switch (prefix) {
            case "eq" -> contains;
            case "ne" -> !contains;
            case "gt" -> target.end().isAfter(param.end());
            case "lt" -> target.start().isBefore(param.start());
            case "ge" -> target.end().isAfter(param.start());
            case "le" -> target.start().isBefore(param.end());
            case "sa" -> !target.start().isBefore(param.end());
            case "eb" -> !target.end().isAfter(param.start());
            case "ap" -> target.start().isBefore(param.end()) && target.end().isAfter(param.start());
            default -> false;
        };
    }

    private static Instant day(LocalDate d) {
        return d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
