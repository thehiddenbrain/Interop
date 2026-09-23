package org.point32health.memberprofile.permission;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What a member is on the policy, as {@code family_permission.relationship_code} maps MemberDomain's
 * relationship codes ({@code 01}, {@code 03}, ...). The actor and viewing relationship words the rules use
 * are derived from this plus age by {@link RelationshipResolver}.
 */
public enum FamilyRelationship {
    SUBSCRIBER("Subscriber"),
    SPOUSE("Spouse"),
    EX_SPOUSE("Ex-Spouse"),
    CHILD("Child");

    private static final Map<String, FamilyRelationship> BY_LABEL = Stream.of(values())
            .collect(Collectors.toUnmodifiableMap(FamilyRelationship::label, Function.identity()));

    private final String label;

    FamilyRelationship(String label) {
        this.label = label;
    }

    /** The text stored in {@code relationship_code.relationship}. */
    public String label() {
        return label;
    }

    public static Optional<FamilyRelationship> fromLabel(String label) {
        return label == null ? Optional.empty() : Optional.ofNullable(BY_LABEL.get(label));
    }
}
