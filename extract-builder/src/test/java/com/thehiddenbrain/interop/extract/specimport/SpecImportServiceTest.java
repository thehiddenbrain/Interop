package com.thehiddenbrain.interop.extract.specimport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The vendor spec importer must read the words analysts actually see in vendor documents. */
@SpringBootTest(properties = {"extract.axway-simulator=false"})
class SpecImportServiceTest {

    @Autowired SpecImportService importer;
    @Autowired ObjectMapper mapper;

    private JsonNode fields(String text) {
        return mapper.valueToTree(importer.propose(text, null, "ACMEDENTAL")).get("fields");
    }

    @Test
    @DisplayName("A 'Member Name ... LAST, FIRST' line becomes last + first combined with a template")
    void fullNameLastFirst() {
        JsonNode f = fields("Member Name|Full name as LAST, FIRST|AN|40").get(0);
        assertEquals("MEMBER_NAME", f.get("header").asText());
        assertEquals(2, f.get("inputs").size());
        assertEquals("member.last_name", f.get("inputs").get(0).get("element").asText());
        assertEquals("{1}, {2}", f.get("combiner").get("params").get("template").asText());
        assertEquals(40, f.get("maxLength").asInt());
    }

    @Test
    @DisplayName("'Patient Name, first last' keeps the vendor's order; 'First Name' and 'Plan Name' stay single elements")
    void nameVariants() {
        JsonNode fl = fields("Patient Name,First Last with a space,AN,60").get(0);
        assertEquals("{2} {1}", fl.get("combiner").get("params").get("template").asText());
        JsonNode first = fields("First Name,Member first name,AN,25").get(0);
        assertEquals(1, first.get("inputs").size());
        assertEquals("member.first_name", first.get("inputs").get(0).get("element").asText());
        JsonNode plan = fields("Plan Name,Benefit plan description,AN,60").get(0);
        assertEquals(1, plan.get("inputs").size());
        assertTrue(plan.get("inputs").get(0).get("element").asText().contains("plan"), plan.toString());
    }

    @Test
    @DisplayName("Dates, crosswalks, code maps and constants are proposed from the vendor's wording")
    void hints() {
        JsonNode fs = fields(String.join("\n", List.of(
                "Vendor Member ID,Member number assigned by Acme; use the crosswalk,AN,12",
                "DOB,Date of birth MM/DD/YYYY,AN,10",
                "Gender,1=Male 2=Female 0=Unknown,N,1",
                "Record Type,Always D,AN,1")));
        assertEquals("LOOKUP", fs.get(0).get("rules").get(0).get("type").asText());
        assertEquals("MM/dd/yyyy", fs.get(1).get("rules").get(0).get("params").get("pattern").asText());
        assertEquals("MAP_VALUES", fs.get(2).get("rules").get(0).get("type").asText());
        assertEquals("CONSTANT", fs.get(3).get("rules").get(0).get("type").asText());
        assertEquals("D", fs.get(3).get("rules").get(0).get("params").get("value").asText());
        Map<String, Object> out = importer.propose("Member ID\nZip", "MEMBER", null);
        assertEquals(2L, out.get("matched"));
    }
}
