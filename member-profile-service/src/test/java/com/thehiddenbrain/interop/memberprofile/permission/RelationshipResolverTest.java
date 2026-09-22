package com.thehiddenbrain.interop.memberprofile.permission;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RelationshipResolverTest {

    private final RelationshipResolver resolver = new RelationshipResolver();
    private final Map<String, String> codes = Map.of("01", "Subscriber", "02", "Spouse", "03", "Child", "04", "Ex-Spouse");

    @Test
    void actorRelationshipDependsOnCodeAndAge() {
        assertThat(resolver.actorRelationship("01", 42, codes)).isEqualTo("Subscriber");
        assertThat(resolver.actorRelationship("02", 40, codes)).isEqualTo("Spouse");
        assertThat(resolver.actorRelationship("04", 40, codes)).isEqualTo("Ex-Spouse");
        assertThat(resolver.actorRelationship("03", 7, codes)).isEqualTo("Child (minor)");
        assertThat(resolver.actorRelationship("03", 13, codes)).isEqualTo("Child (teenager)");
        assertThat(resolver.actorRelationship("03", 17, codes)).isEqualTo("Child (teenager)");
        assertThat(resolver.actorRelationship("03", 18, codes)).isEqualTo("Adult Child");
    }

    @Test
    void unknownActorCodeIsAnError() {
        assertThatThrownBy(() -> resolver.actorRelationship("99", 30, codes))
                .isInstanceOf(RelationshipResolver.UnknownRelationshipException.class);
    }

    @Test
    void viewingRelationshipIsRelativeToTheActor() {
        assertThat(resolver.viewingRelationship("A", "A", "01", 42, codes)).isEqualTo("Self");
        assertThat(resolver.viewingRelationship("A", "B", "01", 42, codes)).isEqualTo("Subscriber");
        assertThat(resolver.viewingRelationship("A", "B", "02", 40, codes)).isEqualTo("Spouse");
        assertThat(resolver.viewingRelationship("A", "B", "03", 7, codes)).isEqualTo("Child");
        assertThat(resolver.viewingRelationship("A", "B", "03", 17, codes)).isEqualTo("Child");
        assertThat(resolver.viewingRelationship("A", "B", "03", 18, codes)).isEqualTo("Adult dependent (any relationship)");
        assertThat(resolver.viewingRelationship("A", "B", "04", 40, codes)).isEqualTo("Adult dependent (any relationship)");
        assertThat(resolver.viewingRelationship("A", "B", "99", 10, codes)).isEqualTo(PermissionRule.CATCH_ALL_VIEWING);
    }
}
