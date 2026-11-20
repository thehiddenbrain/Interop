package org.p32h.interop.epa.pave.service.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.p32h.interop.epa.pave.config.CodelistProperties;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.codelist.ProcedureCodeService;
import org.p32h.interop.epa.pave.testsupport.FakeCodeList;
import tools.jackson.databind.JsonNode;

/**
 * The coding system that goes out with a procedure code.
 *
 * <p>A FHIR coding system is a uri. A code list may hold a name where the system belongs, such as
 * CPT, and sent as it stands that name would be the system of every order: a request the service
 * may refuse, or worse, accept. What the list holds is therefore read before it is sent. What the
 * tester types is not: a wrong system is a negative case a tester is entitled to send.
 */
class CaseResourcesTest {

    private static final String CPT = "http://www.ama-assn.org/go/cpt";
    private static final String HCPCS = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";

    @TempDir
    Path directory;

    /** What was sent as the system, and what the request says about it. */
    private record Worked(String system, List<String> notes) {
    }

    private static Worked work(ProcedureCodeDirectory list, CodeInput code) {
        List<String> notes = new ArrayList<>();
        String system = new CaseResources(list).codingSystem(code, "Medicare", notes);
        return new Worked(system, notes);
    }

    /** A code list whose one row for the code states this where the coding system belongs. */
    private static FakeCodeList listStating(String code, String system) {
        return new FakeCodeList().row(ProcedureCode.builder().code(code).system(system).build());
    }

    private static Worked fromTheList(String code, String system) {
        return work(listStating(code, system), CodeInput.of(code));
    }

    // ---- what the code list states ----

    @Test
    void aUriFromTheCodeListIsUsedAsItIsAndNothingNeedsSaying() {
        for (String uri : new String[] {CPT, HCPCS, "urn:oid:2.16.840.1.113883.6.12", "urn:from-the-code-list",
                // Not one this tool uses itself. The list says so, and the list is sent.
                "http://example.invalid/fhir/CodeSystem/local-procedure-codes", "HTTPS://EXAMPLE.INVALID/Codes"}) {
            Worked worked = fromTheList("95810", uri);
            assertThat(worked.system()).as(uri).isEqualTo(uri);
            assertThat(worked.notes()).as(uri).isEmpty();
        }
        // The white space around the cell is not part of the uri.
        assertThat(fromTheList("95810", "  " + CPT + " ").system()).isEqualTo(CPT);
    }

    @Test
    void aLabelThisToolKnowsIsReplacedByTheUriItUsesAndTheRequestSaysSo() {
        for (String label : new String[] {"CPT", "cpt", "Cpt", "CPT-4", "cpt-4", "CPT4", "CPT 4", " cpt  -  4 "}) {
            Worked worked = fromTheList("95810", label);
            assertThat(worked.system()).as(label).isEqualTo(CPT);
            assertThat(worked.notes()).as(label).containsExactly("The code list gives the coding system for 95810 as the label \""
                    + label.strip() + "\", which is not a uri. The uri this tool uses for CPT was sent in its place: " + CPT + ".");
        }
        for (String label : new String[] {"HCPCS", "hcpcs", "Hcpcs", "HCPCS Level II", "hcpcs level ii", "HCPCS  LEVEL  II", "HCPCS II",
                "hcpcs ii", "HCPCS-II", "HCPCSII"}) {
            Worked worked = fromTheList("E0601", label);
            assertThat(worked.system()).as(label).isEqualTo(HCPCS);
            assertThat(worked.notes()).as(label).containsExactly("The code list gives the coding system for E0601 as the label \""
                    + label + "\", which is not a uri. The uri this tool uses for HCPCS Level II was sent in its place: " + HCPCS + ".");
        }
    }

    @Test
    void aLabelIsKnownByItsLettersAndDigitsSoPunctuationAroundItDoesNotHideIt() {
        for (String label : new String[] {"(CPT)", "CPT.", "C.P.T.", "CPT/4", "CPT_4",
                // This one has the shape of a uri, a scheme and something after the colon. It is a label and is read as one.
                "CPT:4"}) {
            Worked worked = fromTheList("95810", label);
            assertThat(worked.system()).as(label).isEqualTo(CPT);
            assertThat(worked.notes()).as(label).singleElement().asString().contains("as the label \"" + label + "\", which is not a uri.");
        }
        for (String label : new String[] {"HCPCS (Level II)", "HCPCS, Level II", "HCPCS:II", "HCPCS/II"}) {
            assertThat(fromTheList("E0601", label).system()).as(label).isEqualTo(HCPCS);
        }
    }

    @Test
    void aLabelIsFollowedAlsoWhereTheFormatOfTheCodeReadsOtherwiseAndTheRequestSaysWhatTheFormatWouldHaveGiven() {
        // A list that labels every code HCPCS, a five digit one included. The list is followed,
        // since it is the list's statement, and the request says what the format alone would have
        // sent, because a service that refuses the request will be refusing this.
        Worked fiveDigits = fromTheList("95810", "HCPCS");
        assertThat(fiveDigits.system()).isEqualTo(HCPCS);
        assertThat(fiveDigits.notes()).containsExactly("The code list gives the coding system for 95810 as the label \"HCPCS\", which is "
                + "not a uri. The uri this tool uses for HCPCS Level II was sent in its place: " + HCPCS + ". By its format alone the code "
                + "would have been sent as CPT.");
        Worked letterFirst = fromTheList("G0299", "CPT");
        assertThat(letterFirst.system()).isEqualTo(CPT);
        assertThat(letterFirst.notes()).singleElement().asString().endsWith("By its format alone the code would have been sent as HCPCS Level II.");
        // A code whose format says nothing has nothing to compare the label with.
        assertThat(fromTheList("XYZ", "CPT").notes()).singleElement().asString().endsWith("was sent in its place: " + CPT + ".");
    }

    @Test
    void anythingElseTheListHoldsIsNotSentAsASystemAndTheFormatOfTheCodeDecides() {
        for (String held : new String[] {"AMA", "Procedure", "CPT/HCPCS", "HCPCS Level I", "HCPCS Level 2", "ICD-10-PCS", "n/a", "-", "Y",
                // An identifier that is not written as a uri, and things that look like one and are not.
                "2.16.840.1.113883.6.12", "www.ama-assn.org/go/cpt", "http://", "http ://example.invalid", "C:\\lists\\cpt",
                "see http://www.ama-assn.org/go/cpt"}) {
            Worked fiveDigits = fromTheList("95810", held);
            // Never the cell itself: "system": "AMA" is what this is here to prevent.
            assertThat(fiveDigits.system()).as(held).isEqualTo(CPT);
            assertThat(fiveDigits.notes()).as(held).containsExactly(
                    "The code list gives the coding system for 95810 as \"" + held + "\". That is not a uri, and it is not a label this "
                            + "tool knows, so it was not sent as the system.",
                    "The coding system for 95810 was taken from its format: CPT.");

            Worked letterFirst = fromTheList("E0601", held);
            assertThat(letterFirst.system()).as(held).isEqualTo(HCPCS);
            assertThat(letterFirst.notes()).as(held).hasSize(2).last().isEqualTo("The coding system for E0601 was taken from its format: "
                    + "HCPCS Level II.");

            // Where the format decides nothing either, no system is sent, and both facts are stated.
            Worked noFormat = fromTheList("XYZ", held);
            assertThat(noFormat.system()).as(held).isNull();
            assertThat(noFormat.notes()).as(held).containsExactly(
                    "The code list gives the coding system for XYZ as \"" + held + "\". That is not a uri, and it is not a label this "
                            + "tool knows, so it was not sent as the system.",
                    "No coding system could be worked out for XYZ, so it was sent without one.");
        }
    }

    @Test
    void aLongCellIsCutInTheNoteSoADescriptionThatSlidIntoTheWrongColumnDoesNotFillTheScreen() {
        Worked worked = fromTheList("95810", "x".repeat(200));
        assertThat(worked.system()).isEqualTo(CPT);
        assertThat(worked.notes().get(0)).isEqualTo("The code list gives the coding system for 95810 as \"" + "x".repeat(60) + "...\". That "
                + "is not a uri, and it is not a label this tool knows, so it was not sent as the system.");
    }

    @Test
    void theFirstRowOfTheListThatStatesASystemIsTheOneThatIsRead() {
        FakeCodeList list = new FakeCodeList()
                .row(ProcedureCode.builder().code("95810").build())
                .row(ProcedureCode.builder().code("95810").system("  ").build())
                .row(ProcedureCode.builder().code("95810").system("CPT").build())
                .row(ProcedureCode.builder().code("95810").system("urn:a-later-row").build());
        Worked worked = work(list, CodeInput.of("95810"));
        assertThat(worked.system()).isEqualTo(CPT);
        assertThat(worked.notes()).singleElement().asString().startsWith("The code list gives the coding system for 95810 as the label \"CPT\"");
    }

    @Test
    void withNoSystemInTheListTheFormatOfTheCodeDecidesAsBefore() {
        FakeCodeList silent = new FakeCodeList().row(ProcedureCode.builder().code("95810").build());
        for (ProcedureCodeDirectory list : new ProcedureCodeDirectory[] {null, new FakeCodeList(), silent}) {
            assertThat(work(list, CodeInput.of("95810"))).isEqualTo(new Worked(CPT, List.of("The coding system for 95810 was taken from its "
                    + "format: CPT.")));
            assertThat(work(list, CodeInput.of("0042T")).system()).isEqualTo(CPT);
            assertThat(work(list, CodeInput.of(" g0299 "))).isEqualTo(new Worked(HCPCS, List.of("The coding system for g0299 was taken from "
                    + "its format: HCPCS Level II.")));
            assertThat(work(list, CodeInput.of("XYZ"))).isEqualTo(new Worked(null, List.of("No coding system could be worked out for XYZ, "
                    + "so it was sent without one.")));
        }
    }

    // ---- what the tester states ----

    @Test
    void aSystemTheTesterStatesIsSentExactlyAsTypedWhateverItIs() {
        // Looking a row up in this list throws: what the tester typed is not checked against anything.
        FakeCodeList list = listStating("95810", CPT).failing(new IllegalStateException("the list was consulted"));
        for (String typed : new String[] {"CPT", "cpt-4", "HCPCS Level II", "not a system at all", "2.16.840.1.113883.6.12", "http://",
                "urn:stated-by-the-tester", "http://example.invalid/the-wrong-system"}) {
            Worked worked = work(list, new CodeInput("95810", typed, null));
            // A label stays a label and a wrong uri stays wrong. That is the negative case the tester asked for.
            assertThat(worked.system()).as(typed).isEqualTo(typed);
            assertThat(worked.notes()).as(typed).isEmpty();
        }
        assertThat(work(list, new CodeInput("95810", "  CPT ", null)).system()).isEqualTo("CPT");
    }

    // ---- the whole path: a file with a system column, read by the real code list, built into a resource ----

    @Test
    void aRealListWhoseSystemColumnHoldsLabelsNeverPutsALabelOnTheWire() throws IOException {
        Path file = Files.writeString(directory.resolve("codes.csv"), "Code,Description,Code System,PA Required\n"
                + "95810,Sleep study,CPT,Yes\n"
                + "E0601,CPAP device,HCPCS Level II,No\n"
                + "70553,MRI brain,http://www.ama-assn.org/go/cpt,Yes\n"
                + "G0299,Skilled nursing,Medicare codes,Yes\n"
                + "J1745,Infliximab,,Yes\n", StandardCharsets.UTF_8);
        ProcedureCodeService list = new ProcedureCodeService(CodelistProperties.of(Map.of("file", file.toString())));
        // The list serves the cell as it was written. Reading it is this class's job, not the list's.
        assertThat(list.selfCheck().recognisedColumns()).containsEntry("system", "Code System");
        assertThat(list.rowsFor("95810", null, null).get(0).system()).isEqualTo("CPT");

        CaseResources resources = new CaseResources(list);
        List<String> notes = new ArrayList<>();
        Map<String, String> sent = new java.util.LinkedHashMap<>();
        for (String code : new String[] {"95810", "E0601", "70553", "G0299", "J1745"}) {
            // As it goes into an order and into a claim item: the coding of a CodeableConcept.
            JsonNode coding = resources.codeableConcept(CodeInput.of(code), "Medicare", notes).get("coding").get(0);
            assertThat(coding.get("code").stringValue()).isEqualTo(code);
            sent.put(code, coding.get("system").stringValue());
        }
        assertThat(sent).containsExactly(Map.entry("95810", CPT), Map.entry("E0601", HCPCS), Map.entry("70553", CPT),
                Map.entry("G0299", HCPCS), Map.entry("J1745", HCPCS));
        assertThat(notes).containsExactly(
                "The code list gives the coding system for 95810 as the label \"CPT\", which is not a uri. The uri this tool uses for CPT "
                        + "was sent in its place: " + CPT + ".",
                "The code list gives the coding system for E0601 as the label \"HCPCS Level II\", which is not a uri. The uri this tool "
                        + "uses for HCPCS Level II was sent in its place: " + HCPCS + ".",
                "The code list gives the coding system for G0299 as \"Medicare codes\". That is not a uri, and it is not a label this "
                        + "tool knows, so it was not sent as the system.",
                "The coding system for G0299 was taken from its format: HCPCS Level II.",
                "The coding system for J1745 was taken from its format: HCPCS Level II.");
    }
}
