package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the FHIR resources every protocol sends for one test case: the member as a Patient and a
 * Coverage, the provider as a Practitioner and a PractitionerRole, and each procedure code as a
 * ServiceRequest.
 *
 * <p>CRD, DTR and PAS all build from here, and that is a matter of correctness, not tidiness. In a
 * chained run the payer matches the questionnaire request to the coverage answer it gave, so DTR
 * has to send the same order and coverage CRD sent, and PAS has to build its items from the same
 * codes and coding systems. Three copies of these builders would drift.
 *
 * <p>Nothing is filled in that was not supplied. Where an element would need a value this tool
 * does not have, such as the identifier system for a member ID or the status of the coverage, the
 * element is left out and a note says so. The notes are shown beside the request, so a rejection
 * caused by a missing element has a visible cause. The local ids tie the resources of one request
 * together; the member ID travels as an identifier, never as a FHIR id.
 */
public final class CaseResources {

    public static final String PATIENT_ID = "patient-1";
    public static final String COVERAGE_ID = "coverage-1";
    public static final String PRACTITIONER_ID = "practitioner-1";
    public static final String PRACTITIONER_ROLE_ID = "practitioner-role-1";
    public static final String INSURER_ID = "organization-insurer-1";
    public static final String PATIENT_REF = "Patient/" + PATIENT_ID;
    public static final String COVERAGE_REF = "Coverage/" + COVERAGE_ID;
    public static final String PRACTITIONER_REF = "Practitioner/" + PRACTITIONER_ID;
    public static final String PRACTITIONER_ROLE_REF = "PractitionerRole/" + PRACTITIONER_ROLE_ID;
    public static final String INSURER_REF = "Organization/" + INSURER_ID;

    /** Coding systems, used when the tester and the code list do not state one and the code's format decides it. */
    public static final String CPT = "http://www.ama-assn.org/go/cpt";
    public static final String HCPCS = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    public static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
    public static final String IDENTIFIER_TYPES = "http://terminology.hl7.org/CodeSystem/v2-0203";

    /** Placeholder names the built resources read. Each is optional; an unset one leaves its element out. */
    public static final String VAR_FHIR_SERVER = "fhirServer";
    public static final String VAR_MEMBER_ID_SYSTEM = "memberIdSystem";
    public static final String VAR_PAYER_NAME = "payerName";
    public static final String VAR_PAYER_ID_SYSTEM = "payerIdentifierSystem";
    public static final String VAR_PAYER_ID = "payerIdentifier";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ProcedureCodeDirectory codeList;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public CaseResources(ProcedureCodeDirectory codeList) {
        this.codeList = codeList;
    }

    /** The local id of the order at a position, counting from one. */
    public static String serviceRequestId(int position) {
        return "service-request-" + position;
    }

    public static String serviceRequestRef(int position) {
        return "ServiceRequest/" + serviceRequestId(position);
    }

    /**
     * The value of a placeholder, or null when no layer supplies one.
     *
     * @param variables resolves text holding placeholders, as a prepared call does
     */
    public static String value(Function<String, ResolvedText> variables, String name) {
        ResolvedText resolved = variables == null ? null : variables.apply("{{" + name + "}}");
        return resolved != null && resolved.resolved() ? Texts.trimToNull(resolved.value()) : null;
    }

    public ObjectNode serviceRequest(String id, CodeInput code, MemberDetail member, String dateOfService, List<String> notes) {
        ObjectNode resource = JSON.objectNode();
        resource.put("resourceType", "ServiceRequest");
        resource.put("id", id);
        resource.put("status", "draft");
        resource.put("intent", "order");
        resource.set("code", codeableConcept(code, member == null ? null : member.lineOfBusiness(), notes));
        resource.putObject("subject").put("reference", PATIENT_REF);
        if (Texts.present(dateOfService)) {
            resource.put("occurrenceDateTime", dateOfService.strip());
        }
        resource.putObject("requester").put("reference", PRACTITIONER_ROLE_REF);
        resource.putArray("insurance").addObject().put("reference", COVERAGE_REF);
        return resource;
    }

    /** A procedure code as a CodeableConcept with one coding, as an order and a claim item both carry it. */
    public ObjectNode codeableConcept(CodeInput code, String lineOfBusiness, List<String> notes) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        String system = codingSystem(code, lineOfBusiness, notes);
        if (system != null) {
            coding.put("system", system);
        }
        coding.put("code", code.code().strip());
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }

    /**
     * The coding system for a code: the one the tester stated, else the one the code list states,
     * else the one the code's format decides. The last is a rule about published code formats, not
     * a guess about this code, and it is still reported so the tester can see where the system
     * came from.
     */
    public String codingSystem(CodeInput code, String lineOfBusiness, List<String> notes) {
        if (Texts.present(code.system())) {
            return code.system().strip();
        }
        String value = code.code().strip();
        if (codeList != null) {
            for (ProcedureCode row : codeList.rowsFor(value, lineOfBusiness, null)) {
                if (Texts.present(row.system())) {
                    return row.system().strip();
                }
            }
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.matches("\\d{5}") || upper.matches("\\d{4}[FTU]")) {
            notes.add("The coding system for " + value + " was taken from its format: CPT.");
            return CPT;
        }
        if (upper.matches("[A-V]\\d{4}")) {
            notes.add("The coding system for " + value + " was taken from its format: HCPCS Level II.");
            return HCPCS;
        }
        notes.add("No coding system could be worked out for " + value + ", so it was sent without one.");
        return null;
    }

    public ObjectNode patient(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes) {
        ObjectNode patient = JSON.objectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", PATIENT_ID);
        patient.putArray("identifier").add(memberIdentifier(member, variables, notes, true));
        if (Texts.present(member.lastName()) || Texts.present(member.firstName())) {
            ObjectNode name = patient.putArray("name").addObject();
            if (Texts.present(member.lastName())) {
                name.put("family", member.lastName().strip());
            }
            if (Texts.present(member.firstName())) {
                name.putArray("given").add(member.firstName().strip());
            }
        } else {
            notes.add("No member name was sent, because none was supplied.");
        }
        if (Texts.present(member.dateOfBirth())) {
            patient.put("birthDate", member.dateOfBirth().strip());
        } else {
            notes.add("No date of birth was sent, because none was supplied.");
        }
        String gender = gender(member.gender());
        if (gender != null) {
            patient.put("gender", gender);
        } else if (Texts.present(member.gender())) {
            notes.add("The gender \"" + member.gender().strip() + "\" is not one this tool can map to a FHIR code, so none was sent.");
        }
        return patient;
    }

    private static ObjectNode memberIdentifier(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes,
                                               boolean note) {
        ObjectNode identifier = JSON.objectNode();
        identifier.putObject("type").putArray("coding").addObject().put("system", IDENTIFIER_TYPES).put("code", "MB");
        String system = value(variables, VAR_MEMBER_ID_SYSTEM);
        if (system != null) {
            identifier.put("system", system);
        } else if (note) {
            notes.add("The member ID was sent without an identifier system: the variable " + VAR_MEMBER_ID_SYSTEM + " is not set.");
        }
        // The no-space form, which is the only form the service accepts.
        identifier.put("value", MemberIds.noSpace(member.memberId()));
        return identifier;
    }

    public ObjectNode coverage(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes) {
        ObjectNode coverage = JSON.objectNode();
        coverage.put("resourceType", "Coverage");
        coverage.put("id", COVERAGE_ID);
        String status = coverageStatus(member.coverageStatus());
        if (status != null) {
            coverage.put("status", status);
        } else {
            // A coverage status the source did not return is not "active". It is left out.
            notes.add(Texts.present(member.coverageStatus())
                    ? "The coverage status \"" + member.coverageStatus().strip() + "\" is not one this tool can map to a FHIR code, so none was sent."
                    : "No coverage status was sent, because none was supplied.");
        }
        coverage.putArray("identifier").add(memberIdentifier(member, variables, notes, false));
        if (Texts.present(member.subscriberId())) {
            coverage.put("subscriberId", member.subscriberId().strip());
        }
        coverage.putObject("beneficiary").put("reference", PATIENT_REF);
        ObjectNode payor = payer(variables);
        if (payor.isEmpty()) {
            notes.add("No payer was named on the coverage: neither " + VAR_PAYER_ID + " nor " + VAR_PAYER_NAME + " is set.");
        } else {
            coverage.putArray("payor").add(payor);
        }
        if (Texts.present(member.planName())) {
            coverage.putArray("class").addObject()
                    .put("value", member.planName().strip())
                    .putObject("type").putArray("coding").addObject()
                    .put("system", "http://terminology.hl7.org/CodeSystem/coverage-class").put("code", "plan");
        }
        return coverage;
    }

    /**
     * The payer as a logical reference: an identifier, a display name, or both, from the payer
     * placeholders. Empty when neither is set, and the caller then leaves the element out.
     */
    public static ObjectNode payer(Function<String, ResolvedText> variables) {
        ObjectNode payor = JSON.objectNode();
        String payerId = value(variables, VAR_PAYER_ID);
        if (payerId != null) {
            ObjectNode identifier = payor.putObject("identifier");
            String payerSystem = value(variables, VAR_PAYER_ID_SYSTEM);
            if (payerSystem != null) {
                identifier.put("system", payerSystem);
            }
            identifier.put("value", payerId);
        }
        String payerName = value(variables, VAR_PAYER_NAME);
        if (payerName != null) {
            payor.put("display", payerName);
        }
        return payor;
    }

    /**
     * The payer as an Organization resource, for a bundle that must carry the insurer as a
     * resource. Returns null, with a note, when neither payer placeholder is set: an insurer with
     * no name and no identifier would say nothing.
     */
    public static ObjectNode insurer(Function<String, ResolvedText> variables, List<String> notes) {
        String payerId = value(variables, VAR_PAYER_ID);
        String payerName = value(variables, VAR_PAYER_NAME);
        if (payerId == null && payerName == null) {
            notes.add("No insurer organization was sent: neither " + VAR_PAYER_ID + " nor " + VAR_PAYER_NAME + " is set.");
            return null;
        }
        ObjectNode organization = JSON.objectNode();
        organization.put("resourceType", "Organization");
        organization.put("id", INSURER_ID);
        if (payerId != null) {
            ObjectNode identifier = organization.putArray("identifier").addObject();
            String payerSystem = value(variables, VAR_PAYER_ID_SYSTEM);
            if (payerSystem != null) {
                identifier.put("system", payerSystem);
            }
            identifier.put("value", payerId);
        }
        if (payerName != null) {
            organization.put("name", payerName);
        }
        return organization;
    }

    public static ObjectNode practitionerRole() {
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", PRACTITIONER_ROLE_ID);
        role.putObject("practitioner").put("reference", PRACTITIONER_REF);
        return role;
    }

    public static ObjectNode practitioner(ProviderDetail provider) {
        ObjectNode practitioner = JSON.objectNode();
        practitioner.put("resourceType", "Practitioner");
        practitioner.put("id", PRACTITIONER_ID);
        practitioner.putArray("identifier").addObject().put("system", NPI_SYSTEM).put("value", provider.npi().strip());
        if (Texts.present(provider.lastName()) || Texts.present(provider.firstName())) {
            ObjectNode name = practitioner.putArray("name").addObject();
            if (Texts.present(provider.lastName())) {
                name.put("family", provider.lastName().strip());
            }
            if (Texts.present(provider.firstName())) {
                name.putArray("given").add(provider.firstName().strip());
            }
        } else if (Texts.present(provider.organizationName())) {
            practitioner.putArray("name").addObject().put("text", provider.organizationName().strip());
        }
        return practitioner;
    }

    /** A FHIR administrative gender code for the common spellings, or null when the text is not one of them. */
    public static String gender(String text) {
        if (Texts.blank(text)) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "m", "male" -> "male";
            case "f", "female" -> "female";
            case "o", "other" -> "other";
            case "u", "unknown" -> "unknown";
            default -> null;
        };
    }

    /**
     * A FHIR coverage status code, only when the text is one. A source system's own wording, such
     * as "inactive" or "termed", is not translated: which FHIR code it corresponds to would be a guess.
     */
    public static String coverageStatus(String text) {
        if (Texts.blank(text)) {
            return null;
        }
        return switch (text.strip().toLowerCase(Locale.ROOT)) {
            case "active" -> "active";
            case "cancelled", "canceled" -> "cancelled";
            case "draft" -> "draft";
            case "entered-in-error" -> "entered-in-error";
            default -> null;
        };
    }
}
