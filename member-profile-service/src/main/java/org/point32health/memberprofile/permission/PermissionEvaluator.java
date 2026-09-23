package org.point32health.memberprofile.permission;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Turns the actor's permission rows into the permissions map for one viewed member.
 * <ul>
 *   <li>Rows must match the viewed member's age band.</li>
 *   <li>Rows for the exact viewing relationship win. Only when a permission key has no exact row does the
 *       "All other family members" catch-all row for that key apply (the precedence the workbook asks for).
 *       The catch-all is about other family members, so it never applies to Self.</li>
 *   <li>A consent-required row grants only when consent is on file for (viewed member, permission family);
 *       otherwise the key is reported under consentRequired with no actions. Once consent is on file the
 *       row grants its action codes, or View when the row lists none (the workbook's "no default actions,
 *       actions become available after consent").</li>
 *   <li>A masked-data row adds its key to masked, whether it grants now or is waiting for consent, so the
 *       UI knows the data will be masked either way.</li>
 *   <li>Parent keys are computed: View [1] when at least one child key has an action.</li>
 * </ul>
 * {@link #index(List)} groups the actor's rows once per request; {@link Index#evaluate} then costs one scan
 * of the rows for the viewed member's viewing relationship.
 */
@Component
public class PermissionEvaluator {

    /** The actor's rules grouped by viewing relationship, built once per request. */
    public Index index(List<PermissionRule> actorRules) {
        EnumMap<ViewingRelationship, List<PermissionRule>> byViewing = new EnumMap<>(ViewingRelationship.class);
        for (PermissionRule rule : actorRules) {
            if (rule.isParentKey()) continue;
            byViewing.computeIfAbsent(rule.viewing(), v -> new ArrayList<>()).add(rule);
        }
        return new Index(byViewing);
    }

    /** View is what a consent-required row without explicit action codes grants once consent is on file. */
    private static final List<Integer> VIEW_ONLY = List.of(1);

    /** The actor's rules grouped by viewing relationship. Build once per request, evaluate once per viewed member. */
    public static final class Index {

        private final EnumMap<ViewingRelationship, List<PermissionRule>> byViewing;

        private Index(EnumMap<ViewingRelationship, List<PermissionRule>> byViewing) {
            this.byViewing = byViewing;
        }

        /**
         * @param viewing        how the actor is related to the viewed member ({@link ViewingRelationship#SELF} for the actor's own data)
         * @param viewedAge      the viewed member's age in full years
         * @param viewedMemberId used to look up consent on file
         * @param consentsOnFile keys from {@link ConsentRepository#activeConsentsFor}
         * @param explain        record which rows were selected and why (costs allocations; off on the login path)
         */
        public PermissionResult evaluate(ViewingRelationship viewing, int viewedAge, String viewedMemberId,
                                         Set<String> consentsOnFile, boolean explain) {
            Map<String, List<PermissionRule>> selected = new HashMap<>();
            // catch-all first, then exact rows replace them key by key; a member's own view never uses the catch-all
            if (viewing != ViewingRelationship.ALL_OTHER && viewing != ViewingRelationship.SELF) {
                collect(byViewing.get(ViewingRelationship.ALL_OTHER), viewedAge, selected);
            }
            Map<String, List<PermissionRule>> exact = new HashMap<>();
            collect(byViewing.get(viewing), viewedAge, exact);
            selected.putAll(exact);

            Map<String, TreeSet<Integer>> actions = new TreeMap<>();
            TreeSet<String> consentRequired = new TreeSet<>();
            TreeSet<String> masked = new TreeSet<>();
            List<PermissionResult.RuleTrace> trace = explain ? new ArrayList<>() : null;

            for (List<PermissionRule> rules : selected.values()) {
                for (PermissionRule rule : rules) {
                    String outcome;
                    if (rule.consentRequired()
                            && !consentsOnFile.contains(ConsentRepository.consentKey(viewedMemberId, rule.permissionFamily()))) {
                        consentRequired.add(rule.permissionKey());
                        if (rule.maskedData()) masked.add(rule.permissionKey());
                        outcome = "consent required, not on file";
                    } else {
                        List<Integer> granted = rule.actionCodes().isEmpty() && rule.consentRequired() ? VIEW_ONLY : rule.actionCodes();
                        if (granted.isEmpty()) {
                            outcome = "no actions";
                        } else {
                            actions.computeIfAbsent(rule.permissionKey(), k -> new TreeSet<>()).addAll(granted);
                            if (rule.maskedData()) masked.add(rule.permissionKey());
                            outcome = "granted " + granted + (rule.maskedData() ? ", masked" : "");
                        }
                    }
                    if (explain) {
                        trace.add(new PermissionResult.RuleTrace(rule.id(), rule.permissionKey(), rule.viewing().label(),
                                rule.minimumAge(), rule.maximumAge(), rule.accessStatus(), outcome));
                    }
                }
            }

            Map<String, List<Integer>> permissions = new TreeMap<>();
            for (var entry : actions.entrySet()) {
                permissions.put(entry.getKey(), List.copyOf(entry.getValue()));
                permissions.putIfAbsent(PermissionRule.familyOf(entry.getKey()), List.of(1));
            }
            return new PermissionResult(permissions, List.copyOf(consentRequired), List.copyOf(masked),
                    trace == null ? null : List.copyOf(trace));
        }

        private static void collect(List<PermissionRule> rules, int age, Map<String, List<PermissionRule>> into) {
            if (rules == null) return;
            for (PermissionRule rule : rules) {
                if (rule.appliesToAge(age)) {
                    into.computeIfAbsent(rule.permissionKey(), k -> new ArrayList<>(2)).add(rule);
                }
            }
        }
    }
}
