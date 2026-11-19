package com.thehiddenbrain.interop.epa.pave.service.crd;

import com.thehiddenbrain.interop.epa.pave.model.CodeInput;
import com.thehiddenbrain.interop.epa.pave.model.JsonSafe;
import com.thehiddenbrain.interop.epa.pave.model.MemberDetail;
import com.thehiddenbrain.interop.epa.pave.model.MemberIds;
import com.thehiddenbrain.interop.epa.pave.model.ProcedureCode;
import com.thehiddenbrain.interop.epa.pave.model.ProviderDetail;
import com.thehiddenbrain.interop.epa.pave.model.ResolvedText;
import com.thehiddenbrain.interop.epa.pave.model.Texts;
import com.thehiddenbrain.interop.epa.pave.service.ProcedureCodeDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds a CDS Hooks request for order-sign or order-select from the form.
 *
 * <p>The mapping the specification fixes: the member becomes a Patient, the NPI becomes a
 * PractitionerRole and a Practitioner, and each procedure code becomes the code of its own
 * ServiceRequest in the draft orders bundle. The context carries that bundle, the patient id and
 * the user id.
 *
 * <p>Nothing is filled in that was not supplied. Where a FHIR element would need a value this tool
 * does not have, such as the identifier system for a member ID or the status of the coverage, the
 * element is left out and a note says so. The notes are shown beside the request, so a rejection
 * caused by a missing element has a visible cause. Local ids such as {@code patient-1} tie the
 * resources of one request together; the member ID travels as an identifier, never as a FHIR id.
 */
public final class CrdRequestBuilder {

    /** Coding systems, used when the tester and the code list do not state one and the code's format decides it. */
    static final String CPT = "http://www.ama-assn.org/go/cpt";
    static final String HCPCS = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
    static final String IDENTIFIER_TYPES = "http://terminology.hl7.org/CodeSystem/v2-0203";

    /** Placeholder names the built request reads. Each is optional; an unset one leaves its element out. */
    public static final String VAR_FHIR_SERVER = "fhirServer";
    public static final String VAR_MEMBER_ID_SYSTEM = "memberIdSystem";
    public static final String VAR_PAYER_NAME = "payerName";
    public static final String VAR_PAYER_ID_SYSTEM = "payerIdentifierSystem";
    public static final String VAR_PAYER_ID = "payerIdentifier";

    /**
     * The request body and what was left out of it.
     *
     * @param json  the request as pretty JSON
     * @param notes one plain sentence for each thing left out or worked out
     */
    public record Built(String json, List<String> notes) {
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ProcedureCodeDirectory codeList;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public CrdRequestBuilder(ProcedureCodeDirectory codeList) {
        this.codeList = codeList;
    }

    /**
     * @param hookInstance the unique id of this hook invocation
     * @param variables    resolves a placeholder name to its value, or to an unresolved result
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        String hook = hook(request);
        MemberDetail member = request.member();
        ProviderDetail provider = request.provider();

        ObjectNode root = JSON.objectNode();
        root.put("hook", hook);
        root.put("hookInstance", hookInstance);
        String fhirServer = value(variables, VAR_FHIR_SERVER);
        if (fhirServer != null) {
            root.put("fhirServer", fhirServer);
        } else {
            notes.add("No fhirServer was sent: the variable " + VAR_FHIR_SERVER + " is not set. This tool is not an EHR, so the service "
                    + "must work from the prefetch it is given.");
        }

        ObjectNode context = root.putObject("context");
        context.put("userId", "PractitionerRole/practitioner-role-1");
        context.put("patientId", "patient-1");

        ObjectNode bundle = JSON.objectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "collection");
        ArrayNode entries = bundle.putArray("entry");
        ArrayNode selections = JSON.arrayNode();
        int index = 1;
        for (CodeInput code : request.procedureCodes()) {
            String id = "service-request-" + index++;
            entries.addObject().set("resource", serviceRequest(id, code, member, request.dateOfService(), notes));
            selections.add("ServiceRequest/" + id);
        }
        if (CrdRunRequest.ORDER_SELECT.equals(hook)) {
            context.set("selections", selections);
        }
        context.set("draftOrders", bundle);

        if (!Boolean.FALSE.equals(request.includePrefetch())) {
            ObjectNode prefetch = root.putObject("prefetch");
            prefetch.set("patient", patient(member, variables, notes));
            prefetch.set("coverage", coverage(member, variables, notes));
            prefetch.set("practitionerRole", practitionerRole());
            prefetch.set("practitioner", practitioner(provider));
            notes.add("The prefetch keys patient, coverage, practitionerRole and practitioner are this tool's own choice. The keys the "
                    + "live service declares are not known: compare them with its discovery response.");
        }
        return new Built(JsonSafe.pretty(root), List.copyOf(notes));
    }

    /** The hook name this request is for. Blank means order-sign. */
    public static String hook(CrdRunRequest request) {
        return Texts.present(request.hook()) ? request.hook().strip().toLowerCase(Locale.ROOT) : CrdRunRequest.ORDER_SIGN;
    }

    private ObjectNode serviceRequest(String id, CodeInput code, MemberDetail member, String dateOfService, List<String> notes) {
        ObjectNode resource = JSON.objectNode();
        resource.put("resourceType", "ServiceRequest");
        resource.put("id", id);
        resource.put("status", "draft");
        resource.put("intent", "order");
        ObjectNode coding = resource.putObject("code").putArray("coding").addObject();
        String system = system(code, member == null ? null : member.lineOfBusiness(), notes);
        if (system != null) {
            coding.put("system", system);
        }
        coding.put("code", code.code().strip());
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        resource.putObject("subject").put("reference", "Patient/patient-1");
        if (Texts.present(dateOfService)) {
            resource.put("occurrenceDateTime", dateOfService.strip());
        }
        resource.putObject("requester").put("reference", "PractitionerRole/practitioner-role-1");
        resource.putArray("insurance").addObject().put("reference", "Coverage/coverage-1");
        return resource;
    }

    /**
     * The coding system for a code: the one the tester stated, else the one the code list states,
     * else the one the code's format decides. The last is a rule about published code formats, not
     * a guess about this code, and it is still reported so the tester can see where the system
     * came from.
     */
    private String system(CodeInput code, String lineOfBusiness, List<String> notes) {
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

    private static ObjectNode patient(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes) {
        ObjectNode patient = JSON.objectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", "patient-1");
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

    private static ObjectNode coverage(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes) {
        ObjectNode coverage = JSON.objectNode();
        coverage.put("resourceType", "Coverage");
        coverage.put("id", "coverage-1");
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
        coverage.putObject("beneficiary").put("reference", "Patient/patient-1");
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

    private static ObjectNode practitionerRole() {
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", "practitioner-role-1");
        role.putObject("practitioner").put("reference", "Practitioner/practitioner-1");
        return role;
    }

    private static ObjectNode practitioner(ProviderDetail provider) {
        ObjectNode practitioner = JSON.objectNode();
        practitioner.put("resourceType", "Practitioner");
        practitioner.put("id", "practitioner-1");
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
    static String gender(String text) {
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
    static String coverageStatus(String text) {
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

    private static String value(Function<String, ResolvedText> variables, String name) {
        ResolvedText resolved = variables == null ? null : variables.apply("{{" + name + "}}");
        return resolved != null && resolved.resolved() ? Texts.trimToNull(resolved.value()) : null;
    }
}
