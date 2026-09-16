package com.thehiddenbrain.interop.patientaccess.search;

import com.thehiddenbrain.interop.patientaccess.patient.PatientSummary;

import java.util.List;

/** Patients found, with every query that was run (so the tester sees exactly what was sent). */
public record MemberSearchResult(List<PatientSummary> patients, List<Query> queries, List<String> warnings) {

    public record Query(String description, String url, Integer status, Integer matches, Integer total, long durationMs,
                        String requestId, String error) {
    }
}
