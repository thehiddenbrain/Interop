package com.thehiddenbrain.interop.patientaccess.patient;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thehiddenbrain.interop.patientaccess.support.Fixtures;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PatientSummaryTest {

    @Test
    void summarisesTheC4bbExamplePatient() {
        PatientSummary p = PatientSummary.of(Fixtures.demo("c4bb-Patient-Patient1"), "Patient by member id");
        assertThat(p.id()).isEqualTo("Patient1");
        assertThat(p.display()).isEqualTo("Johnny Example1");
        assertThat(p.family()).isEqualTo("Example1");
        assertThat(p.given()).isEqualTo("Johnny");
        assertThat(p.birthDate()).isEqualTo("1986-01-01");
        assertThat(p.gender()).isEqualTo("male");
        assertThat(p.active()).isTrue();
        assertThat(p.lastUpdated()).isEqualTo("2020-07-07T13:26:22.0314215+00:00");
        assertThat(p.profiles()).containsExactly("http://hl7.org/fhir/us/carin-bb/StructureDefinition/C4BB-Patient|2.1.0");
        assertThat(p.foundBy()).isEqualTo("Patient by member id");
        assertThat(p.identifiers()).hasSize(2);
        PatientSummary.IdentifierView member = p.identifiers().get(0);
        assertThat(member.typeCode()).isEqualTo("MB");
        assertThat(member.system()).isEqualTo("https://www.xxxhealthplan.com/fhir/memberidentifier");
        assertThat(member.value()).isEqualTo("1234-234-1243-12345678901");
        assertThat(member.typeText()).isNull();
        assertThat(p.identifiers().get(1).typeCode()).isEqualTo("um");
    }

    @Test
    void prefersTheOfficialNameAndCopesWithMissingData() {
        ObjectNode patient = Fixtures.resource("Patient", "p");
        patient.putArray("name")
                .add(Fixtures.MAPPER.createObjectNode().put("use", "nickname").put("text", "Johnny"))
                .add(Fixtures.MAPPER.createObjectNode().put("use", "official").put("family", "Appleseed").set("given", Fixtures.MAPPER.createArrayNode().add("John").add("A")));
        ObjectNode id = patient.putArray("identifier").addObject().put("value", "v1");
        id.putObject("type").put("text", "Member number").putArray("coding").addObject().put("code", "MB").put("display", "Member Number");
        PatientSummary p = PatientSummary.of(patient, null);
        assertThat(p.display()).isEqualTo("John A Appleseed");
        assertThat(p.given()).isEqualTo("John A");
        assertThat(p.active()).isNull();
        assertThat(p.birthDate()).isNull();
        assertThat(p.identifiers()).singleElement().satisfies(i -> {
            assertThat(i.system()).isNull();
            assertThat(i.typeText()).isEqualTo("Member number");
            assertThat(i.typeCode()).isEqualTo("MB");
        });

        PatientSummary anonymous = PatientSummary.of(Fixtures.resource("Patient", "anon"), null);
        assertThat(anonymous.display()).isEqualTo("(no name)");
        assertThat(anonymous.identifiers()).isEmpty();
        assertThat(anonymous.profiles()).isEmpty();

        ObjectNode textOnly = Fixtures.resource("Patient", "t");
        textOnly.putArray("name").addObject().put("text", "Dr. Text Only");
        assertThat(PatientSummary.of(textOnly, null).display()).isEqualTo("Dr. Text Only");
        ObjectNode displayFallback = Fixtures.resource("Patient", "d");
        displayFallback.putArray("identifier").addObject().putObject("type").putArray("coding").addObject().put("code", "MR").put("display", "Medical record");
        assertThat(PatientSummary.of(displayFallback, null).identifiers().get(0).typeText()).isEqualTo("Medical record");
    }
}
