package com.thehiddenbrain.interop.patientaccess.conformance;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All registered checks, grouped. Checks are Spring beans (see the {@code checks} package). */
@Component
public class ConformanceSuite {

    public record GroupInfo(String key, String label, String description, int checks, boolean needsPatient) {
    }

    public record CheckInfo(String id, String group, String title, String description, Severity severity, String citation, boolean needsPatient) {
    }

    /** Display order and labels of the groups. */
    public static final Map<String, String[]> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("discovery", new String[]{"Discovery", "CapabilityStatement: FHIR version, formats, declared resources, search parameters and profiles"});
        GROUPS.put("smart", new String[]{"SMART discovery", "/.well-known/smart-configuration and the OAuth declaration in the CapabilityStatement"});
        GROUPS.put("security", new String[]{"Security", "Unauthorized requests are rejected; the token works"});
        GROUPS.put("patient", new String[]{"Patient", "Read and the IG search parameters for Patient, profile conformance"});
        GROUPS.put("coverage", new String[]{"Coverage", "Coverage search by patient, C4BB Coverage profile, payor resolution"});
        GROUPS.put("eob", new String[]{"Claims (EOB)", "C4BB ExplanationOfBenefit searches, _include, profiles, must-support content, references"});
        GROUPS.put("priorauth", new String[]{"Prior authorization", "CMS-0057-F prior authorization data through PDex PriorAuthorization EOBs"});
        GROUPS.put("clinical", new String[]{"Clinical (US Core)", "US Core resource searches by patient and the required parameter combinations"});
        GROUPS.put("provenance", new String[]{"Provenance", "Provenance via _revinclude"});
        GROUPS.put("paging", new String[]{"Paging", "_count, next links and consistent totals"});
        GROUPS.put("errors", new String[]{"Error handling", "OperationOutcome on bad requests, 404 for unknown resources"});
        GROUPS.put("formulary", new String[]{"Formulary", "Da Vinci US Drug Formulary resources (optional)"});
        GROUPS.put("performance", new String[]{"Performance", "Response times (informational)"});
    }

    private final Map<String, Check> checks = new LinkedHashMap<>();

    public ConformanceSuite(Collection<Check> registered, Collection<CheckProvider> providers) {
        List<Check> sorted = new ArrayList<>(registered);
        for (CheckProvider p : providers) {
            sorted.addAll(p.checks());
        }
        List<String> order = new ArrayList<>(GROUPS.keySet());
        sorted.sort((a, b) -> {
            int ga = order.indexOf(a.group());
            int gb = order.indexOf(b.group());
            if (ga != gb) {
                return Integer.compare(ga < 0 ? 99 : ga, gb < 0 ? 99 : gb);
            }
            return a.id().compareTo(b.id());
        });
        for (Check c : sorted) {
            if (checks.put(c.id(), c) != null) {
                throw new IllegalStateException("duplicate check id " + c.id());
            }
        }
    }

    public Collection<Check> all() {
        return checks.values();
    }

    public List<Check> forGroups(Collection<String> groups) {
        List<Check> out = new ArrayList<>();
        for (Check c : checks.values()) {
            if (groups == null || groups.isEmpty() || groups.contains(c.group())) {
                out.add(c);
            }
        }
        return out;
    }

    public List<GroupInfo> groups() {
        List<GroupInfo> out = new ArrayList<>();
        for (Map.Entry<String, String[]> g : GROUPS.entrySet()) {
            List<Check> in = forGroups(List.of(g.getKey()));
            out.add(new GroupInfo(g.getKey(), g.getValue()[0], g.getValue()[1], in.size(), in.stream().anyMatch(Check::needsPatient)));
        }
        return out;
    }

    public List<CheckInfo> checkInfos() {
        List<CheckInfo> out = new ArrayList<>();
        for (Check c : checks.values()) {
            out.add(new CheckInfo(c.id(), c.group(), c.title(), c.description(), c.severity(), c.citation(), c.needsPatient()));
        }
        return out;
    }
}
