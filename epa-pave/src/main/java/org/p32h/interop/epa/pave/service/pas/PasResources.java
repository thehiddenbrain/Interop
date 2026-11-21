package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The resources of a PAS request bundle, each in the shape of its PAS 2.0.1 profile: the
 * beneficiary, the coverage, the insurer, the requestor (a practitioner in a role, or an
 * organization), the order behind each item and an attached document.
 *
 * <p>PAS has profiles of its own for every one of these, so they are built here and not taken
 * from {@link CaseResources}, which builds the shapes CRD and DTR send. The local ids are the
 * ones CRD and DTR use, because the questionnaire answers DTR hands on name the member, the
 * coverage, the practitioner and the order by those ids, and the PAS bundle has to carry what
 * they name.
 *
 * <p>Nothing is filled in that was not supplied. A constant of the guide, such as a code system
 * address or the type code that says an organization is the payer, is sent as the guide gives it.
 * A fact about the member, the provider, the payer or the order comes from the form, the lookup
 * or a variable, and when it is missing the element is left out and a note says which profile of
 * which version requires it and what supplies it.
 */
final class PasResources {

    /** Placeholder names these resources read, beyond the ones {@link CaseResources} names. Each is optional. */
    static final String VAR_HCPCS_SYSTEM = "pasHcpcsSystem";
    static final String VAR_RELATIONSHIP = "coverageRelationship";
    static final String VAR_REQUESTOR_TYPE = "pasRequestorType";

    static final String INSURER_ID = CaseResources.INSURER_ID;
    static final String INSURER_REF = CaseResources.INSURER_REF;
    static final String REQUESTOR_ID = "organization-requestor-1";
    static final String REQUESTOR_REF = "Organization/" + REQUESTOR_ID;

    /**
     * HCPCS Level II as PAS 2.0.1 names it in the value set X12278RequestedServiceType, which is
     * bound, required, to Claim.item.productOrService: http, and no s. CRD 2.0.1 and the ballots
     * write other forms, so the variable {@link #VAR_HCPCS_SYSTEM} can override it.
     */
    static final String HCPCS = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";

    /** The code systems PAS 2.0.1 X12278RequestedServiceType takes its codes from. */
    private static final Set<String> REQUESTED_SERVICE_SYSTEMS = Set.of("https://codesystem.x12.org/005010/1365", CaseResources.CPT, HCPCS,
            "http://terminology.hl7.org/CodeSystem/icd9cm", "http://www.cms.gov/Medicare/Coding/ICD10", "http://hl7.org/fhir/sid/ndc");

    private static final String RELATIONSHIPS = "http://terminology.hl7.org/CodeSystem/subscriber-relationship";

    /** The same labels {@link CaseResources} knows, mapped to the uris PAS 2.0.1 names. */
    private static final Map<String, String> LABELS = Map.of("cpt", CaseResources.CPT, "cpt4", CaseResources.CPT, "hcpcs", "HCPCS",
            "hcpcslevelii", "HCPCS", "hcpcsii", "HCPCS");

    private static final Pattern URI_SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]+:/*[^\\s/]\\S*");

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final ProcedureCodeDirectory codeList;

    PasResources(ProcedureCodeDirectory codeList) {
        this.codeList = codeList;
    }

    // ---------------------------------------------------------------------------------------------
    // The member and the coverage
    // ---------------------------------------------------------------------------------------------

    /** The member as PASBeneficiary, whose parent is US Core 3.1.1 Patient. */
    ObjectNode patient(MemberDetail member, Function<String, ResolvedText> variables, PasNotes notes) {
        ObjectNode patient = JSON.objectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", CaseResources.PATIENT_ID);
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
            notes.missing("PASBeneficiary name", "No member name was sent, because none was supplied. PAS 2.0.1 PASBeneficiary, on US Core "
                    + "3.1.1 Patient, requires one: look the member up, or enter the name on the form.");
        }
        if (Texts.present(member.dateOfBirth())) {
            patient.put("birthDate", member.dateOfBirth().strip());
        } else {
            notes.add("No date of birth was sent, because none was supplied.");
        }
        String gender = CaseResources.gender(member.gender());
        if (gender != null) {
            patient.put("gender", gender);
        } else {
            notes.missing("PASBeneficiary gender", (Texts.present(member.gender())
                    ? "The gender \"" + member.gender().strip() + "\" is not one this tool can map to a FHIR code, so none was sent."
                    : "No gender was sent, because none was supplied.")
                    + " PAS 2.0.1 PASBeneficiary, on US Core 3.1.1 Patient, requires one: look the member up, or enter it on the form.");
        }
        return patient;
    }

    private static ObjectNode memberIdentifier(MemberDetail member, Function<String, ResolvedText> variables, PasNotes notes, boolean note) {
        ObjectNode identifier = JSON.objectNode();
        identifier.putObject("type").putArray("coding").addObject().put("system", CaseResources.IDENTIFIER_TYPES).put("code", "MB");
        String system = CaseResources.value(variables, CaseResources.VAR_MEMBER_ID_SYSTEM);
        if (system != null) {
            identifier.put("system", system);
        } else if (note) {
            notes.missing("PASBeneficiary identifier.system", "The member ID was sent without an identifier system: the variable "
                    + CaseResources.VAR_MEMBER_ID_SYSTEM + " is not set. PAS 2.0.1 PASBeneficiary, on US Core 3.1.1 Patient, requires a "
                    + "system on every identifier.");
        }
        // The no-space form, which is the only form the service accepts.
        identifier.put("value", MemberIds.noSpace(member.memberId()));
        return identifier;
    }

    /**
     * The coverage as PASCoverage.
     *
     * <p>PASCoverage fixes the status to active, and base FHIR requires a status. The build
     * specification forbids calling a coverage active that nobody said was active, so a status
     * that was not supplied is left out, and one that was supplied is sent as it is, whatever
     * the profile allows.
     *
     * @param insurerSent true when the insurer Organization travels in the bundle, so the coverage can point at it
     */
    ObjectNode coverage(MemberDetail member, Function<String, ResolvedText> variables, boolean insurerSent, PasNotes notes) {
        ObjectNode coverage = JSON.objectNode();
        coverage.put("resourceType", "Coverage");
        coverage.put("id", CaseResources.COVERAGE_ID);
        String status = CaseResources.coverageStatus(member.coverageStatus());
        if (status == null) {
            notes.missing("PASCoverage status", (Texts.present(member.coverageStatus())
                    ? "The coverage status \"" + member.coverageStatus().strip() + "\" is not one this tool can map to a FHIR code, so none was sent."
                    : "No coverage status was sent, because none was supplied.")
                    + " PAS 2.0.1 PASCoverage requires a status and allows only \"active\". Looking the member up, or stating the "
                    + "coverage status on the form, supplies it.");
        } else {
            coverage.put("status", status);
            if (!"active".equals(status)) {
                notes.add("The coverage status was sent as \"" + status + "\", as it was supplied. PAS 2.0.1 PASCoverage allows only "
                        + "\"active\", so the service may refuse the request for that alone.");
            }
        }
        coverage.putArray("identifier").add(memberIdentifier(member, variables, notes, false));
        String relationship = CaseResources.value(variables, VAR_RELATIONSHIP);
        if (Texts.present(member.subscriberId())) {
            coverage.put("subscriberId", member.subscriberId().strip());
        }
        if ("self".equalsIgnoreCase(Texts.orElse(relationship, ""))) {
            // The member is the subscriber: that is what the relationship self says, so the
            // subscriber is the member already in the bundle and nothing is assumed.
            coverage.putObject("subscriber").put("reference", CaseResources.PATIENT_REF);
        } else {
            notes.add("No subscriber was named on the coverage: nothing supplied says who the subscriber is. PAS 2.0.1 PASCoverage marks "
                    + "it must-support. Setting the variable " + VAR_RELATIONSHIP + " to self names the member as the subscriber.");
        }
        coverage.putObject("beneficiary").put("reference", CaseResources.PATIENT_REF);
        if (relationship != null) {
            ObjectNode concept = coverage.putObject("relationship");
            concept.putArray("coding").addObject().put("system", RELATIONSHIPS).put("code", relationship.toLowerCase(Locale.ROOT));
            if ("self".equalsIgnoreCase(relationship)) {
                // X12 1069 code 18 is "Self", the code PAS 2.0.1 asks for beside the FHIR one.
                concept.withArray("coding").addObject().put("system", OnyxPasConventions.X12_RELATIONSHIP).put("code", "18");
            } else {
                notes.add("The relationship was sent as \"" + relationship + "\" with no X12 code beside it: this tool knows the X12 code "
                        + "for self only.");
            }
        } else {
            notes.add("No relationship of the member to the subscriber was sent: the variable " + VAR_RELATIONSHIP + " is not set. "
                    + "PAS 2.0.1 PASCoverage marks it must-support, and the 2.2.0 ballot requires it.");
        }
        if (insurerSent) {
            coverage.putArray("payor").addObject().put("reference", INSURER_REF);
        } else {
            notes.missing("PASCoverage payor", "No payer was named on the coverage: neither " + CaseResources.VAR_PAYER_ID + " nor "
                    + CaseResources.VAR_PAYER_NAME + " is set. FHIR R4 Coverage requires a payor, and PAS 2.0.1 PASCoverage points it at "
                    + "the insurer Organization.");
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
     * The payer as PASInsurer, or null, with a note, when no variable names it.
     *
     * <p>Two values are not taken from a variable. The type PR says the organization is the
     * payer, which is the role it has in this request. And US Core 3.1.1 Organization requires
     * {@code active}: this is the organization the request is addressed to for a decision, so it
     * is sent as an organization in active use.
     */
    static ObjectNode insurer(Function<String, ResolvedText> variables, String claimProfile, PasNotes notes) {
        String payerId = CaseResources.value(variables, CaseResources.VAR_PAYER_ID);
        String payerName = CaseResources.value(variables, CaseResources.VAR_PAYER_NAME);
        if (payerId == null && payerName == null) {
            notes.missing(claimProfile + " insurer", "No insurer organization was sent: neither " + CaseResources.VAR_PAYER_ID + " nor "
                    + CaseResources.VAR_PAYER_NAME + " is set. PAS 2.0.1 " + claimProfile + " requires the insurer (PASClaimBase, which "
                    + "PASClaim and PASClaimInquiry share, makes it 1..1).");
            return null;
        }
        ObjectNode organization = JSON.objectNode();
        organization.put("resourceType", "Organization");
        organization.put("id", INSURER_ID);
        organization.put("active", true);
        if (payerId != null) {
            ObjectNode identifier = organization.putArray("identifier").addObject();
            String payerSystem = CaseResources.value(variables, CaseResources.VAR_PAYER_ID_SYSTEM);
            if (payerSystem != null) {
                identifier.put("system", payerSystem);
            }
            identifier.put("value", payerId);
        }
        organization.putArray("type").addObject().putArray("coding").addObject().put("system", OnyxPasConventions.X12_ENTITY_TYPES)
                .put("code", "PR").put("display", "Payer");
        if (payerName != null) {
            organization.put("name", payerName);
        } else {
            notes.missing("PASInsurer name", "The insurer organization was sent without a name: the variable " + CaseResources.VAR_PAYER_NAME
                    + " is not set. PAS 2.0.1 PASInsurer, on US Core 3.1.1 Organization, requires one.");
        }
        return organization;
    }

    // ---------------------------------------------------------------------------------------------
    // The requestor
    // ---------------------------------------------------------------------------------------------

    /** Whether the provider is sent as a person in a role, which is the case whenever a person's name was supplied, or no name at all. */
    static boolean requestorIsAnOrganization(ProviderDetail provider) {
        return Texts.blank(provider.lastName()) && Texts.blank(provider.firstName()) && Texts.present(provider.organizationName());
    }

    /** The reference the claim's provider carries. */
    static String requestorReference(ProviderDetail provider) {
        return requestorIsAnOrganization(provider) ? REQUESTOR_REF : CaseResources.PRACTITIONER_ROLE_REF;
    }

    /** The requestor as PASPractitionerRole with its PASPractitioner, or as PASRequestor when only an organization name was supplied. */
    static List<ObjectNode> requestor(ProviderDetail provider, Function<String, ResolvedText> variables, PasNotes notes) {
        if (requestorIsAnOrganization(provider)) {
            ObjectNode organization = JSON.objectNode();
            organization.put("resourceType", "Organization");
            organization.put("id", REQUESTOR_ID);
            // The organization is the one making this request now, so it is in active use.
            organization.put("active", true);
            organization.putArray("identifier").addObject().put("system", CaseResources.NPI_SYSTEM).put("value", provider.npi().strip());
            String type = CaseResources.value(variables, VAR_REQUESTOR_TYPE);
            if (type != null) {
                organization.putArray("type").addObject().putArray("coding").addObject().put("system", OnyxPasConventions.X12_ENTITY_TYPES)
                        .put("code", type);
            } else {
                notes.add("The requesting organization was sent with no type: the variable " + VAR_REQUESTOR_TYPE + " is not set. PAS 2.0.1 "
                        + "PASRequestor marks it must-support, with codes from X12 entity identifier codes, such as 1P for a provider.");
            }
            organization.put("name", provider.organizationName().strip());
            notes.add("The provider was sent as the requesting organization " + REQUESTOR_REF + ", because only an organization name was "
                    + "supplied. PAS 2.0.1 lets PASRequestor stand as the claim's provider.");
            return List.of(organization);
        }
        ObjectNode practitioner = JSON.objectNode();
        practitioner.put("resourceType", "Practitioner");
        practitioner.put("id", CaseResources.PRACTITIONER_ID);
        practitioner.putArray("identifier").addObject().put("system", CaseResources.NPI_SYSTEM).put("value", provider.npi().strip());
        if (Texts.present(provider.lastName()) || Texts.present(provider.firstName())) {
            ObjectNode name = practitioner.putArray("name").addObject();
            if (Texts.present(provider.lastName())) {
                name.put("family", provider.lastName().strip());
            }
            if (Texts.present(provider.firstName())) {
                name.putArray("given").add(provider.firstName().strip());
            }
        }
        if (Texts.blank(provider.lastName())) {
            notes.missing("PASPractitioner name.family", "No family name of the provider was sent, because none was supplied. PAS 2.0.1 "
                    + "PASPractitioner, on US Core 3.1.1 Practitioner, requires one: look the provider up, or enter the name on the form.");
        }
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", CaseResources.PRACTITIONER_ROLE_ID);
        role.putObject("practitioner").put("reference", CaseResources.PRACTITIONER_REF);
        notes.add("The practitioner role names no organization: nothing supplied says which organization the provider acts for. PAS "
                + "2.0.1 PASPractitionerRole marks it must-support.");
        return List.of(practitioner, role);
    }

    // ---------------------------------------------------------------------------------------------
    // The order behind each item
    // ---------------------------------------------------------------------------------------------

    /**
     * The order behind one item as PASServiceRequest, under the id CRD and DTR gave it. Its
     * status is draft, as CRD sends it, because it is the same order. PASServiceRequest allows
     * only a Timing as the occurrence, so the date of service travels as the one event of a Timing.
     */
    ObjectNode serviceRequest(int position, CodeInput code, MemberDetail member, String dateOfService, String requester,
                              Function<String, ResolvedText> variables, PasNotes notes) {
        ObjectNode resource = JSON.objectNode();
        resource.put("resourceType", "ServiceRequest");
        resource.put("id", CaseResources.serviceRequestId(position));
        resource.put("status", "draft");
        resource.put("intent", "order");
        // The notes about the code system were made for the item, which carries the same concept.
        ObjectNode concept = codeableConcept(code, member.lineOfBusiness(), variables, "PASClaim", new PasNotes());
        resource.set("code", concept);
        if (!concept.path("coding").path(0).has("system")) {
            notes.missing("PASServiceRequest code system (code " + code.code().strip() + ")", "The order ServiceRequest/"
                    + CaseResources.serviceRequestId(position) + " carries the code " + code.code().strip() + " with no system, as its "
                    + "item does. PAS 2.0.1 PASServiceRequest binds code, required, to the value set X12278RequestedServiceType.");
        }
        resource.putObject("subject").put("reference", CaseResources.PATIENT_REF);
        if (Texts.present(dateOfService)) {
            resource.putObject("occurrenceTiming").putArray("event").add(dateOfService.strip());
        }
        resource.putObject("requester").put("reference", requester);
        resource.putArray("insurance").addObject().put("reference", CaseResources.COVERAGE_REF);
        return resource;
    }

    /**
     * A procedure code as a CodeableConcept with one coding, as an item and its order carry it.
     *
     * <p>The coding system is the one the tester stated, else the one the code list states, else
     * the one the format of the code decides, as {@link CaseResources#codingSystem} explains. HCPCS
     * Level II is written as PAS 2.0.1 names it. A system outside the value set PAS binds the
     * element to is still sent, with a note, because the tester or the code list said so.
     */
    ObjectNode codeableConcept(CodeInput code, String lineOfBusiness, Function<String, ResolvedText> variables, String claimProfile,
                               PasNotes notes) {
        String hcpcs = Texts.orElse(CaseResources.value(variables, VAR_HCPCS_SYSTEM), HCPCS);
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        String value = code.code().strip();
        String system = codingSystem(code, lineOfBusiness, hcpcs, notes);
        if (system != null) {
            coding.put("system", system);
            if (!REQUESTED_SERVICE_SYSTEMS.contains(system)) {
                notes.add("The code " + value + " was sent with the system " + system + ", which is not one of the systems of the value set "
                        + "X12278RequestedServiceType that PAS 2.0.1 binds, required, to the item's code. The service may refuse it.");
            }
        } else {
            notes.missing(claimProfile + " item.productOrService system (code " + value + ")", "The code " + value + " was sent with no "
                    + "system. PAS 2.0.1 binds the item's code, required, to the value set X12278RequestedServiceType, so the service may "
                    + "refuse it. State its system with the code.");
        }
        coding.put("code", value);
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }

    private String codingSystem(CodeInput code, String lineOfBusiness, String hcpcs, PasNotes notes) {
        if (Texts.present(code.system())) {
            return code.system().strip();
        }
        String value = code.code().strip();
        String byFormat = byFormat(value, hcpcs);
        String stated = statedByTheCodeList(value, lineOfBusiness);
        if (stated != null) {
            String labelled = LABELS.get(stated.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""));
            if (labelled != null) {
                String uri = "HCPCS".equals(labelled) ? hcpcs : labelled;
                notes.add("The code list gives the coding system for " + value + " as the label \"" + stated + "\", which is not a uri. The uri "
                        + "PAS 2.0.1 names for " + name(uri) + " was sent in its place: " + uri + "."
                        + (byFormat != null && !byFormat.equals(uri) ? " By its format alone the code would have been sent as " + name(byFormat) + "." : ""));
                return uri;
            }
            if (URI_SHAPE.matcher(stated).matches()) {
                return stated;
            }
            notes.add("The code list gives the coding system for " + value + " as \"" + stated + "\". That is not a uri, and it is not a "
                    + "label this tool knows, so it was not sent as the system.");
        }
        if (byFormat == null) {
            return null;
        }
        notes.add("The coding system for " + value + " was taken from its format: " + name(byFormat)
                + (CaseResources.CPT.equals(byFormat) ? "." : ", " + byFormat + "."));
        return byFormat;
    }

    private String statedByTheCodeList(String code, String lineOfBusiness) {
        if (codeList != null) {
            for (ProcedureCode row : codeList.rowsFor(code, lineOfBusiness, null)) {
                if (Texts.present(row.system())) {
                    return row.system().strip();
                }
            }
        }
        return null;
    }

    private static String byFormat(String code, String hcpcs) {
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.matches("\\d{5}") || upper.matches("\\d{4}[FTU]")) {
            return CaseResources.CPT;
        }
        return upper.matches("[A-V]\\d{4}") ? hcpcs : null;
    }

    private static String name(String system) {
        return CaseResources.CPT.equals(system) ? "CPT" : "HCPCS Level II";
    }

    // ---------------------------------------------------------------------------------------------
    // A document
    // ---------------------------------------------------------------------------------------------

    /**
     * An attached document as a DocumentReference. PAS 2.0.1 asks that a resource with a US Core
     * profile meet it, and US Core 3.1.1 DocumentReference requires a type and a category. Both
     * come from the tester; neither is chosen here.
     */
    static ObjectNode documentReference(String id, String title, PasAttachment attachment, int position, PasNotes notes) {
        ObjectNode document = JSON.objectNode();
        document.put("resourceType", "DocumentReference");
        document.put("id", id);
        document.put("status", OnyxPasConventions.DOCUMENT_STATUS);
        if (attachment.type() != null && Texts.present(attachment.type().code())) {
            document.set("type", coded(attachment.type()));
        } else {
            notes.missing("US Core 3.1.1 DocumentReference type (attachment " + position + ")", "Attachment " + position + " was sent with no "
                    + "document type, because none was given. PAS 2.0.1 asks that a document meet US Core 3.1.1 DocumentReference, which "
                    + "requires one: give the attachment a type, such as a LOINC document code.");
        }
        if (attachment.category() != null && Texts.present(attachment.category().code())) {
            document.putArray("category").add(coded(attachment.category()));
        } else {
            notes.missing("US Core 3.1.1 DocumentReference category (attachment " + position + ")", "Attachment " + position + " was sent "
                    + "with no category, because none was given. PAS 2.0.1 asks that a document meet US Core 3.1.1 DocumentReference, which "
                    + "requires one: give the attachment a category, such as clinical-note.");
        }
        document.putObject("subject").put("reference", CaseResources.PATIENT_REF);
        document.put("description", title);
        ObjectNode content = document.putArray("content").addObject().putObject("attachment");
        content.put("contentType", attachment.contentType().strip());
        content.put("title", title);
        if (Texts.present(attachment.text())) {
            content.put("data", Base64.getEncoder().encodeToString(attachment.text().getBytes(StandardCharsets.UTF_8)));
        } else {
            content.put("url", attachment.url().strip());
        }
        return document;
    }

    private static ObjectNode coded(CodeInput code) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        if (Texts.present(code.system())) {
            coding.put("system", code.system().strip());
        }
        coding.put("code", code.code().strip());
        if (Texts.present(code.description())) {
            coding.put("display", code.description().strip());
        }
        return concept;
    }
}
