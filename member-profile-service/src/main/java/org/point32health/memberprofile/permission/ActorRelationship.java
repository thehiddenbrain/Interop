package org.point32health.memberprofile.permission;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** The logged-in member's relationship as the rule table spells it in {@code actor_relationship}. */
public enum ActorRelationship {
    SUBSCRIBER("Subscriber"),
    SPOUSE("Spouse"),
    EX_SPOUSE("Ex-Spouse"),
    /** A child aged 18 or older. */
    ADULT_CHILD("Adult Child"),
    /** A child aged 13 to 17. */
    CHILD_TEENAGER("Child (teenager)"),
    /** A child aged 0 to 12; such members cannot create online accounts. */
    CHILD_MINOR("Child (minor)");

    private static final Map<String, ActorRelationship> BY_LABEL = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(ActorRelationship::label, Function.identity()));

    private final String label;

    ActorRelationship(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static Optional<ActorRelationship> fromLabel(String label) {
        return label == null ? Optional.empty() : Optional.ofNullable(BY_LABEL.get(label));
    }
}
