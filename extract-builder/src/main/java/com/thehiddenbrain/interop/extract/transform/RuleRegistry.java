package com.thehiddenbrain.interop.extract.transform;

import com.thehiddenbrain.interop.extract.config.ApiErrors;
import org.springframework.stereotype.Service;

import java.util.*;

/** The closed set of rules, discovered as Spring beans. */
@Service
public class RuleRegistry {

    private final Map<String, Rule> rules = new LinkedHashMap<>();

    public RuleRegistry(List<Rule> beans) {
        List<Rule> ordered = new ArrayList<>(beans);
        List<String> order = List.of("CONSTANT", "CONCAT", "COALESCE", "LOOKUP", "MAP_VALUES", "CONDITIONAL", "FORMAT_DATE", "DATE_MATH",
                "ARITHMETIC", "FORMAT_NUMBER", "CLEAN_TEXT", "SUBSTRING", "SEQUENCE", "MASK");
        ordered.sort(Comparator.comparingInt(r -> {
            int i = order.indexOf(r.type());
            return i < 0 ? 100 : i;
        }));
        for (Rule r : ordered) rules.put(r.type(), r);
    }

    public Rule get(String type) {
        Rule r = rules.get(type);
        if (r == null) throw new ApiErrors.BadRequest("Unknown rule type: " + type);
        return r;
    }

    public Optional<Rule> find(String type) {
        return Optional.ofNullable(rules.get(type));
    }

    public Collection<Rule> all() {
        return rules.values();
    }

    public List<Map<String, Object>> describe() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Rule r : rules.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", r.type());
            m.put("label", r.label());
            m.put("description", r.description());
            m.put("category", r.category());
            m.put("accepts", r.accepts());
            m.put("params", r.params());
            out.add(m);
        }
        return out;
    }
}
