package org.point32health.memberprofile.permission;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Whose information the actor is viewing, as the rule table spells it in {@code viewing_relationship}. */
public enum ViewingRelationship {
    SELF("Self"),
    SUBSCRIBER("Subscriber"),
    SPOUSE("Spouse"),
    /** A dependent child under 18. */
    CHILD("Child"),
    /** Any dependent aged 18 or older, whatever the relationship. */
    ADULT_DEPENDENT("Adult dependent (any relationship)"),
    /** The catch-all row, used only for a permission key that has no row for the exact relationship. */
    ALL_OTHER("All other family members");

    private static final Map<String, ViewingRelationship> BY_LABEL = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ViewingRelationship::label, Function.identity()));

    private final String label;

    ViewingRelationship(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<ViewingRelationship> fromLabel(String label) {
        return Optional.ofNullable(BY_LABEL.get(label));
    }
}
