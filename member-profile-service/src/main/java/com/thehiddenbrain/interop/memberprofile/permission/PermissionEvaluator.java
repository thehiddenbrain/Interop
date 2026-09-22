package com.thehiddenbrain.interop.memberprofile.permission;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Selects the permission rows that apply to one (actor, viewed member) pair and turns them into
 * the permissions map.
 * <ul>
 *   <li>Rows must match the viewed member's age band.</li>
 *   <li>Rows for the exact viewing relationship win. Only when a permission key has no exact row
 *       does the "All other family members" catch-all row for that key apply.</li>
 *   <li>A consent-required row grants its actions only when consent is on file for
 *       (viewed member, permission family); otherwise the key is reported under consentRequired.</li>
 *   <li>A masked-data row adds its key to masked.</li>
 *   <li>Parent keys are computed: View [1] when at least one child key has an action.</li>
 * </ul>
 */
@Component
public class PermissionEvaluator {

    public PermissionResult evaluate(List<PermissionRule> actorRules,
                                     String viewingRelationship,
                                     int viewedAge,
                                     String viewedMemberId,
                                     Set<String> consentsOnFile) {
        Map<String, List<PermissionRule>> exact = new LinkedHashMap<>();
        Map<String, List<PermissionRule>> catchAll = new LinkedHashMap<>();
        for (PermissionRule rule : actorRules) {
            if (rule.isParentKey() || !rule.appliesToAge(viewedAge)) continue;
            if (rule.viewingRelationship().equals(viewingRelationship)) {
                exact.computeIfAbsent(rule.permissionKey(), k -> new ArrayList<>()).add(rule);
            } else if (rule.isCatchAll()) {
                catchAll.computeIfAbsent(rule.permissionKey(), k -> new ArrayList<>()).add(rule);
            }
        }

        Map<String, TreeSet<Integer>> actions = new TreeMap<>();
        TreeSet<String> consentRequired = new TreeSet<>();
        TreeSet<String> masked = new TreeSet<>();
        List<PermissionResult.RuleTrace> trace = new ArrayList<>();

        Map<String, List<PermissionRule>> selected = new LinkedHashMap<>(catchAll);
        selected.putAll(exact);
        for (var entry : selected.entrySet()) {
            for (PermissionRule rule : entry.getValue()) {
                String outcome;
                if (rule.consentRequired()
                        && !consentsOnFile.contains(ConsentRepository.consentKey(viewedMemberId, rule.permissionFamily()))) {
                    consentRequired.add(rule.permissionKey());
                    outcome = "consent required, not on file";
                } else if (rule.actionCodes().isEmpty()) {
                    outcome = "no actions";
                } else {
                    actions.computeIfAbsent(rule.permissionKey(), k -> new TreeSet<>()).addAll(rule.actionCodes());
                    if (rule.maskedData()) masked.add(rule.permissionKey());
                    outcome = "granted " + rule.actionCodes() + (rule.maskedData() ? " masked" : "");
                }
                trace.add(new PermissionResult.RuleTrace(rule.id(), rule.permissionKey(), rule.viewingRelationship(),
                        rule.minimumAge(), rule.maximumAge(), rule.accessStatus(), outcome));
            }
        }

        Map<String, List<Integer>> permissions = new TreeMap<>();
        for (var entry : actions.entrySet()) {
            String key = entry.getKey();
            permissions.put(key, List.copyOf(entry.getValue()));
            permissions.putIfAbsent(family(key), List.of(1));
        }
        return new PermissionResult(permissions, List.copyOf(consentRequired), List.copyOf(masked), trace);
    }

    static String family(String permissionKey) {
        int dot = permissionKey.indexOf('.');
        return dot < 0 ? permissionKey : permissionKey.substring(0, dot);
    }
}
