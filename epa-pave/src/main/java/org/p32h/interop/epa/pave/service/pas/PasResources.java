package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.client.pas.OnyxPasConventions;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The resources of a PAS request bundle, each in the shape of its profile in the PAS guide in force: the
 * beneficiary, the coverage, the insurer, the requestor (a practitioner in a role, or an
 * organization), the servicing provider, the order behind each item and an attached document.
 * Where a PAS profile is built on US Core, its parent is US Core 3.1.1 under PAS 2.0.1 and US Core
 * 7.0.0 under 2.2.1, and a note that names a requirement of the parent names that version.
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
     * HCPCS Level II as PAS 2.2.1 names it in the value set X12278RequestedServiceType, which is
     * bound, required, to Claim.item.productOrService: http, and no s. CRD 2.0.1 and the ballots
     * write other forms, so the variable {@link #VAR_HCPCS_SYSTEM} can override it.
     */
    static final String HCPCS = "http://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";

    /** The code systems PAS 2.2.1 X12278RequestedServiceType takes its codes from. */
    private static final Set<String> REQUESTED_SERVICE_SYSTEMS = Set.of("https://codesystem.x12.org/005010/1365", CaseResources.CPT, HCPCS,
            "http://terminology.hl7.org/CodeSystem/icd9cm", "http://www.cms.gov/Medicare/Coding/ICD10", "http://hl7.org/fhir/sid/ndc");

    private static final String RELATIONSHIPS = "http://terminology.hl7.org/CodeSystem/subscriber-relationship";
    /** Where a value of the environment is typed, ending the sentence that sends the tester there. */
    private static final String UNDER_VALUES = " under Values in Configuration > Environments.";

    /** The same labels {@link CaseResources} knows, mapped to the uris PAS 2.2.1 names. */
    private static final Map<String, String> LABELS = Map.of("cpt", CaseResources.CPT, "cpt4", CaseResources.CPT, "hcpcs", "HCPCS",
            "hcpcslevelii", "HCPCS", "hcpcsii", "HCPCS");

    private static final Pattern URI_SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]+:/*[^\\s/]\\S*");

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** How the canonical url of every PAS profile begins. */
    static final String PAS_PROFILE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/profile-";

    /**
     * Says which PAS profile a resource is built as, right after its id, the way the guide's own examples do.
     *
     * <p>The vendor's service refused a Submit whose Bundle named no profile (D-318). A service that looks for the profile
     * there may look for one on each resource, so each resource this tool builds for PAS says its own (D-319). A
     * DocumentReference says none, because the guide has no profile for it, and a QuestionnaireResponse travels as DTR
     * completed it.
     */
    static void declare(ObjectNode resource, String profile) {
        resource.putObject("meta").putArray("profile").add(PAS_PROFILE + profile);
    }

    private final ProcedureCodeDirectory codeList;

    PasResources(ProcedureCodeDirectory codeList) {
        this.codeList = codeList;
    }

    // ---------------------------------------------------------------------------------------------
    // The member and the coverage
    // ---------------------------------------------------------------------------------------------

    /** The member as PASBeneficiary, whose parent is US Core Patient. */
    ObjectNode patient(MemberDetail member, MemberIdRule rule, Function<String, ResolvedText> variables, PasNotes notes) {
        ObjectNode patient = JSON.objectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", patientId(member));
        declare(patient, "beneficiary");
        patient.putArray("identifier").add(memberIdentifier(member, rule, variables, notes, true));
        if (Texts.present(member.vendorPatientId())) {
            notes.add("The Patient was sent with the id " + patientId(member) + ", the vendor patient id given, so every reference to the "
                    + "member reads " + patientRef(member) + ".");
        } else {
            notes.add("The Patient was sent with the id " + CaseResources.PATIENT_ID + ", this tool's own: no vendor patient id was given. "
                    + "Give it under Edit member fields to send the vendor's own id instead.");
        }
        if (Texts.present(member.lastName()) || Texts.present(member.firstName())) {
            ObjectNode name = patient.putArray("name").addObject();
            if (Texts.present(member.lastName())) {
                name.put("family", member.lastName().strip());
            }
            if (Texts.present(member.firstName())) {
                name.putArray("given").add(member.firstName().strip());
            }
        } else {
            notes.missing("PASBeneficiary name", "No member name was sent, because none was supplied. " + notes.pas() + " PASBeneficiary, on "
                    + notes.usCore() + " Patient, requires one: look the member up, or enter the name on the form.");
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
                    + " " + notes.pas() + " PASBeneficiary, on " + notes.usCore() + " Patient, requires one: look the member up, or enter it on the form.");
        }
        return patient;
    }

    private static ObjectNode memberIdentifier(MemberDetail member, MemberIdRule rule, Function<String, ResolvedText> variables, PasNotes notes,
                                               boolean note) {
        ObjectNode identifier = JSON.objectNode();
        identifier.putObject("type").putArray("coding").addObject().put("system", CaseResources.IDENTIFIER_TYPES).put("code", "MB");
        String system = CaseResources.value(variables, CaseResources.VAR_MEMBER_ID_SYSTEM);
        if (system != null) {
            identifier.put("system", system);
        } else if (note) {
            notes.missing("PASBeneficiary identifier.system", "The member ID was sent without an identifier system: the variable "
                    + CaseResources.VAR_MEMBER_ID_SYSTEM + " is not set. " + notes.pas() + " PASBeneficiary, on " + notes.usCore() + " Patient, requires a "
                    + "system on every identifier.");
        }
        // The form the environment's rule writes. The vendor's sandbox routes only the packed form,
        // which is every environment's rule until another is chosen for it.
        MemberIdRule applied = rule == null ? MemberIdRule.PACKED : rule;
        identifier.put("value", MemberIds.sent(member.memberId(), applied));
        if (note) {
            String unapplied = applied.note(member.memberId());
            if (unapplied != null) {
                notes.add(unapplied);
            } else if (applied != MemberIdRule.PACKED) {
                notes.add("The member ID was sent in the " + applied.word() + " form, by this environment's member ID rule.");
            }
        }
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
    ObjectNode coverage(MemberDetail member, MemberIdRule rule, Function<String, ResolvedText> variables, boolean insurerSent, PasNotes notes) {
        ObjectNode coverage = JSON.objectNode();
        coverage.put("resourceType", "Coverage");
        coverage.put("id", CaseResources.COVERAGE_ID);
        declare(coverage, "coverage");
        String status = CaseResources.coverageStatus(member.coverageStatus());
        if (status == null) {
            notes.missing("PASCoverage status", (Texts.present(member.coverageStatus())
                    ? "The coverage status \"" + member.coverageStatus().strip() + "\" is not one this tool can map to a FHIR code, so none was sent."
                    : "No coverage status was sent, because none was supplied.")
                    + " " + notes.pas() + " PASCoverage requires a status and allows only \"active\". Looking the member up, or stating the "
                    + "coverage status on the form, supplies it.");
        } else {
            coverage.put("status", status);
            if (!"active".equals(status)) {
                notes.add("The coverage status was sent as \"" + status + "\", as it was supplied. " + notes.pas() + " PASCoverage allows only "
                        + "\"active\", so the service may refuse the request for that alone.");
            }
        }
        coverage.putArray("identifier").add(memberIdentifier(member, rule, variables, notes, false));
        String relationship = CaseResources.value(variables, VAR_RELATIONSHIP);
        if (Texts.present(member.subscriberId())) {
            coverage.put("subscriberId", member.subscriberId().strip());
        }
        if ("self".equalsIgnoreCase(Texts.orElse(relationship, ""))) {
            // The member is the subscriber: that is what the relationship self says, so the
            // subscriber is the member already in the bundle and nothing is assumed.
            coverage.putObject("subscriber").put("reference", patientRef(member));
        } else if (!notes.guide().is221()) {
            // PAS 2.0.1 PASCoverage has no rule self-beneficiary: the subscriber is must-support only.
            notes.add("No subscriber was named on the coverage: nothing supplied says who the subscriber is. PAS 2.0.1 PASCoverage marks "
                    + "it must-support. Setting the variable " + VAR_RELATIONSHIP + " to self names the member as the subscriber.");
        } else {
            // PAS 2.2.1 PASCoverage rule self-beneficiary: unless the relationship carries the X12
            // code 18 (self), a subscriber SHALL be present. This tool names a subscriber only for
            // self, so without self the rule is broken and counted.
            notes.missing("PASCoverage subscriber (rule self-beneficiary)", "No subscriber was named on the coverage: nothing supplied says "
                    + "who the subscriber is. " + notes.pas() + " PASCoverage rule self-beneficiary requires one unless the relationship is self. "
                    + "Setting the variable " + VAR_RELATIONSHIP + " to self names the member as the subscriber.");
        }
        coverage.putObject("beneficiary").put("reference", patientRef(member));
        if (relationship != null) {
            ObjectNode concept = coverage.putObject("relationship");
            concept.putArray("coding").addObject().put("system", RELATIONSHIPS).put("code", relationship.toLowerCase(Locale.ROOT));
            if ("self".equalsIgnoreCase(relationship)) {
                // X12 1069 code 18 is "Self", the code PAS 2.2.1 asks for beside the FHIR one.
                concept.withArray("coding").addObject().put("system", OnyxPasConventions.X12_RELATIONSHIP).put("code", "18");
            } else {
                notes.add("The relationship was sent as \"" + relationship + "\" with no X12 code beside it: this tool knows the X12 code "
                        + "for self only.");
            }
        } else {
            notes.add("No relationship of the member to the subscriber was sent: the variable " + VAR_RELATIONSHIP + " is not set. "
                    + notes.pas() + " PASCoverage marks it must-support.");
        }
        if (insurerSent) {
            coverage.putArray("payor").addObject().put("reference", INSURER_REF);
        } else {
            notes.missing("PASCoverage payor", "No payer was named on the coverage: neither " + CaseResources.VAR_PAYER_ID + " nor "
                    + CaseResources.VAR_PAYER_NAME + " is set. FHIR R4 Coverage requires a payor, and " + notes.pas() + " PASCoverage points it at "
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
     * The payer as PASInsurer, or null, with a note, when no value names it. The values are the
     * environment's (payerName, payerIdentifier, payerIdentifierSystem), typed under Values or held
     * by the Postman environment; they reach this method as variables like any other.
     *
     * <p>Two values are not taken from a variable. The type PR says the organization is the
     * payer, which is the role it has in this request. And PAS 2.2.1 PASInsurer fixes
     * {@code active} to true: this is the organization the request is addressed to for a decision,
     * so it is sent as an organization in active use.
     */
    static ObjectNode insurer(Function<String, ResolvedText> variables, String claimProfile, PasNotes notes) {
        String payerId = CaseResources.value(variables, CaseResources.VAR_PAYER_ID);
        String payerName = CaseResources.value(variables, CaseResources.VAR_PAYER_NAME);
        if (payerId == null && payerName == null) {
            notes.toComplete("values.payerName", "Insurer: none. " + notes.pas() + " requires the payer: add Payer name and Payer identifier"
                    + UNDER_VALUES);
            notes.missing(claimProfile + " insurer", "No insurer organization was sent: neither " + CaseResources.VAR_PAYER_ID + " nor "
                    + CaseResources.VAR_PAYER_NAME + " is set. " + notes.pas() + " " + claimProfile + " requires the insurer (PASClaimBase, which "
                    + "PASClaim and PASClaimInquiry share, makes it 1..1): add Payer name and Payer identifier under Values in "
                    + "Configuration > Environments.");
            return null;
        }
        ObjectNode organization = JSON.objectNode();
        organization.put("resourceType", "Organization");
        organization.put("id", INSURER_ID);
        declare(organization, "insurer");
        organization.put("active", true);
        if (payerId != null) {
            ObjectNode identifier = organization.putArray("identifier").addObject();
            String payerSystem = CaseResources.value(variables, CaseResources.VAR_PAYER_ID_SYSTEM);
            if (payerSystem != null) {
                identifier.put("system", payerSystem);
            } else if (notes.guide().is221()) {
                // PAS 2.0.1 PASIdentifier does not require a system; 2.2.1's does.
                notes.missing("PASInsurer identifier.system", "The payer identifier was sent without an identifier system: the variable "
                        + CaseResources.VAR_PAYER_ID_SYSTEM + " is not set. " + notes.pas() + " gives the identifiers of a PAS organization the "
                        + "profile PASIdentifier, which requires a system: add Payer identifier system under Values in Configuration > "
                        + "Environments.");
            }
            identifier.put("value", payerId);
        } else {
            notes.toComplete("values.payerIdentifier", "Payer identifier: none. The insurer is named by its name alone: add Payer "
                    + "identifier" + UNDER_VALUES);
        }
        organization.putArray("type").addObject().putArray("coding").addObject().put("system", OnyxPasConventions.X12_ENTITY_TYPES)
                .put("code", "PR").put("display", "Payer");
        if (payerName != null) {
            organization.put("name", payerName);
        } else {
            notes.toComplete("values.payerName", "Payer name: none. " + notes.pas() + " requires a name on the insurer: add Payer name" + UNDER_VALUES);
            notes.missing("PASInsurer name", "The insurer organization was sent without a name: the variable " + CaseResources.VAR_PAYER_NAME
                    + " is not set. " + notes.pas() + " PASInsurer, on " + notes.usCore() + " Organization, requires one.");
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
            declare(organization, "requestor");
            // The organization is the one making this request now, so it is in active use.
            organization.put("active", true);
            npiIdentifier(organization, provider);
            String type = CaseResources.value(variables, VAR_REQUESTOR_TYPE);
            if (type != null) {
                organization.putArray("type").addObject().putArray("coding").addObject().put("system", OnyxPasConventions.X12_ENTITY_TYPES)
                        .put("code", type);
            } else {
                notes.add("The requesting organization was sent with no type: the variable " + VAR_REQUESTOR_TYPE + " is not set. " + notes.pas() + " "
                        + "PASRequestor marks it must-support, with codes from X12 entity identifier codes, such as 1P for a provider.");
            }
            organization.put("name", provider.organizationName().strip());
            telecom(organization, provider);
            notes.add("The provider was sent as the requesting organization " + REQUESTOR_REF + ", because only an organization name was "
                    + "supplied. " + notes.pas() + " lets PASRequestor stand as the claim's provider.");
            return List.of(organization);
        }
        ObjectNode practitioner = practitioner(practitionerId(provider), provider);
        if (Texts.present(provider.vendorPractitionerId())) {
            notes.add("The Practitioner was sent with the id " + practitionerId(provider) + ", the vendor practitioner id given, so every "
                    + "reference to the provider reads Practitioner/" + practitionerId(provider) + ".");
        }
        if (Texts.blank(provider.npi())) {
            notes.missing("PASPractitioner identifier", "The provider was sent with no identifier, because no NPI was given. The vendor "
                    + "practitioner id is the resource's id, not an identifier. " + notes.pas() + " PASPractitioner, on " + notes.usCore() + " Practitioner, "
                    + "requires one: add the NPI in Edit provider fields.");
        }
        if (Texts.blank(provider.lastName())) {
            notes.missing("PASPractitioner name.family", "No family name of the provider was sent, because none was supplied. " + notes.pas() + " "
                    + "PASPractitioner, on " + notes.usCore() + " Practitioner, requires one: look the provider up, or enter the name on the form.");
        }
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", CaseResources.PRACTITIONER_ROLE_ID);
        declare(role, "practitionerrole");
        role.putObject("practitioner").put("reference", "Practitioner/" + practitionerId(provider));
        if (!telecom(role, provider) && notes.guide().is221()) {
            // PAS 2.0.1 PASPractitionerRole is built on FHIR PractitionerRole, not US Core, so rule pd-1 is not its rule.
            notes.toComplete("provider.phone", "Provider phone or fax: none. PAS rule pd-1 needs one: add it in Edit provider fields.");
            notes.missing("PASPractitionerRole telecom (rule pd-1)", "Provider phone or fax: none. PAS rule pd-1 needs one: add it in Edit "
                    + "provider fields. " + notes.pas() + " PASPractitionerRole, on " + notes.usCore() + " PractitionerRole, needs a phone, a fax or an "
                    + "endpoint on the requesting provider's role.");
        }
        notes.add("The practitioner role names no organization: nothing supplied says which organization the provider acts for. "
                + notes.pas() + " PASPractitionerRole marks it must-support.");
        return List.of(practitioner, role);
    }

    /**
     * The servicing provider, when the tester chose one, as a member of the claim's care team: a
     * PractitionerRole with a Practitioner of its own, or an Organization when only an
     * organization name was given. PASClaimBase, in 2.0.1 and in 2.2.1, lets careTeam.provider be either. The
     * resources have ids of their own, so the servicing provider is never mistaken for the
     * requesting one even when the two are the same person.
     *
     * @return the care team member's reference and its resources, or null when no servicing provider was given
     */
    static Servicing servicing(ProviderDetail provider, PasNotes notes) {
        return servicing(provider, null, notes);
    }

    /**
     * @param requesting the requesting provider, or null. When the servicing provider carries the
     *                   same vendor practitioner id, both roles name the one Practitioner, because a
     *                   bundle may not hold two resources of one type under one id
     */
    static Servicing servicing(ProviderDetail provider, ProviderDetail requesting, PasNotes notes) {
        if (!CaseResources.names(provider)) {
            return null;
        }
        if (requestorIsAnOrganization(provider)) {
            ObjectNode organization = JSON.objectNode();
            organization.put("resourceType", "Organization");
            organization.put("id", OnyxPasConventions.SERVICING_ORGANIZATION_ID);
            // A care team member that is an organization is the guide's plain PAS organization, neither insurer nor requestor.
            declare(organization, "organization");
            // The organization will perform the service, so it is in active use.
            organization.put("active", true);
            npiIdentifier(organization, provider);
            organization.put("name", provider.organizationName().strip());
            telecom(organization, provider);
            notes.add("The servicing provider was sent as the care team member Organization/" + OnyxPasConventions.SERVICING_ORGANIZATION_ID
                    + ", because only an organization name was given for it.");
            return new Servicing("Organization/" + OnyxPasConventions.SERVICING_ORGANIZATION_ID, List.of(organization));
        }
        String vendorId = Texts.trimToNull(provider.vendorPractitionerId());
        String practitionerId = vendorId == null ? OnyxPasConventions.SERVICING_PRACTITIONER_ID : vendorId;
        boolean shared = vendorId != null && requesting != null && !requestorIsAnOrganization(requesting)
                && vendorId.equals(Texts.trimToNull(requesting.vendorPractitionerId()));
        List<ObjectNode> resources = new ArrayList<>();
        if (shared) {
            notes.add("The servicing provider carries the requesting provider's vendor practitioner id " + vendorId + ", so its role names "
                    + "the same Practitioner/" + vendorId + " and no second Practitioner travels in the bundle.");
        } else {
            resources.add(practitioner(practitionerId, provider));
            if (vendorId != null) {
                notes.add("The servicing provider's Practitioner was sent with the id " + vendorId + ", the vendor practitioner id given.");
            }
            if (Texts.blank(provider.npi())) {
                notes.missing("PASPractitioner identifier (servicing provider)", "The servicing provider was sent with no identifier, because "
                        + "no NPI was given for it. " + notes.pas() + " PASPractitioner, on " + notes.usCore() + " Practitioner, requires one.");
            }
            if (Texts.blank(provider.lastName())) {
                notes.missing("PASPractitioner name.family (servicing provider)", "No family name of the servicing provider was sent, because "
                        + "none was given. " + notes.pas() + " PASPractitioner, on " + notes.usCore() + " Practitioner, requires one.");
            }
        }
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", OnyxPasConventions.SERVICING_ROLE_ID);
        declare(role, "practitionerrole");
        role.putObject("practitioner").put("reference", "Practitioner/" + practitionerId);
        if (!telecom(role, provider) && notes.guide().is221()) {
            notes.toComplete("servicingProvider.phone", "Servicing provider phone or fax: none. PAS rule pd-1 needs one: add it to the "
                    + "servicing provider.");
            notes.missing("PASPractitionerRole telecom of the servicing provider (rule pd-1)", "Servicing provider phone or fax: none. PAS "
                    + "rule pd-1 needs one: add it to the servicing provider. " + notes.pas() + " PASPractitionerRole, on " + notes.usCore() + " "
                    + "PractitionerRole, needs a phone, a fax or an endpoint.");
        }
        resources.add(role);
        return new Servicing("PractitionerRole/" + OnyxPasConventions.SERVICING_ROLE_ID, resources);
    }

    /** A servicing provider as the care team names it, and the resources the bundle carries for it. */
    record Servicing(String reference, List<ObjectNode> resources) {
    }

    /** A provider as PASPractitioner: the NPI when given, and the name as given. */
    private static ObjectNode practitioner(String id, ProviderDetail provider) {
        ObjectNode practitioner = JSON.objectNode();
        practitioner.put("resourceType", "Practitioner");
        practitioner.put("id", id);
        declare(practitioner, "practitioner");
        npiIdentifier(practitioner, provider);
        if (Texts.present(provider.lastName()) || Texts.present(provider.firstName())) {
            ObjectNode name = practitioner.putArray("name").addObject();
            if (Texts.present(provider.lastName())) {
                name.put("family", provider.lastName().strip());
            }
            if (Texts.present(provider.firstName())) {
                name.putArray("given").add(provider.firstName().strip());
            }
        }
        return practitioner;
    }

    /** The NPI as an identifier, when one was given. No identifier array at all otherwise, because an empty one is invalid FHIR. */
    private static void npiIdentifier(ObjectNode resource, ProviderDetail provider) {
        if (Texts.present(provider.npi())) {
            resource.putArray("identifier").addObject().put("system", CaseResources.NPI_SYSTEM).put("value", provider.npi().strip());
        }
    }

    /**
     * The provider's phone and fax as telecom, the contact US Core rule pd-1 asks of a role.
     *
     * @return true when at least one was given and written
     */
    private static boolean telecom(ObjectNode resource, ProviderDetail provider) {
        boolean phone = Texts.present(provider.phone());
        boolean fax = Texts.present(provider.fax());
        if (!phone && !fax) {
            return false;
        }
        ArrayNode telecom = resource.putArray("telecom");
        if (phone) {
            telecom.addObject().put("system", "phone").put("value", provider.phone().strip());
        }
        if (fax) {
            telecom.addObject().put("system", "fax").put("value", provider.fax().strip());
        }
        return true;
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
        declare(resource, "servicerequest");
        resource.put("status", "draft");
        resource.put("intent", "order");
        // The notes about the code system were made for the item, which carries the same concept.
        ObjectNode concept = codeableConcept(code, member.lineOfBusiness(), variables, "PASClaim", new PasNotes());
        resource.set("code", concept);
        if (!concept.path("coding").path(0).has("system")) {
            notes.missing("PASServiceRequest code system (code " + code.code().strip() + ")", "The order ServiceRequest/"
                    + CaseResources.serviceRequestId(position) + " carries the code " + code.code().strip() + " with no system, as its "
                    + "item does. " + notes.pas() + " PASServiceRequest binds code, required, to the value set X12278RequestedServiceType.");
        }
        // The same quantity as its item: it is one order, and PASServiceRequest takes a PASQuantity too.
        ObjectNode quantity = quantity(code);
        if (quantity != null) {
            resource.set("quantityQuantity", quantity);
        }
        resource.putObject("subject").put("reference", patientRef(member));
        if (Texts.present(dateOfService)) {
            resource.putObject("occurrenceTiming").putArray("event").add(dateOfService.strip());
        }
        resource.putObject("requester").put("reference", requester);
        resource.putArray("insurance").addObject().put("reference", CaseResources.COVERAGE_REF);
        return resource;
    }

    /**
     * The quantity of a code as PASQuantity: the value as typed, and the X12 673 unit as code and
     * system when a unit was chosen. Null when no quantity was given, because PASQuantity requires a
     * value and none is assumed; a unit given without a quantity is then not sent, and the item's
     * note says so.
     */
    static ObjectNode quantity(CodeInput code) {
        if (code.quantity() == null) {
            return null;
        }
        ObjectNode quantity = JSON.objectNode();
        quantity.put("value", code.quantity());
        if (Texts.present(code.unit())) {
            quantity.put("system", OnyxPasConventions.X12_UNITS);
            quantity.put("code", code.unit().strip());
        }
        return quantity;
    }

    /**
     * A procedure code as a CodeableConcept with one coding, as an item and its order carry it.
     *
     * <p>The coding system is the one the tester stated, else the one the code list states, else
     * the one the format of the code decides, as {@link CaseResources#codingSystem} explains. HCPCS
     * Level II is written as PAS 2.2.1 names it. A system outside the value set PAS binds the
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
                        + "X12278RequestedServiceType that " + notes.pas() + " binds, required, to the item's code. The service may refuse it.");
            }
        } else {
            notes.missing(claimProfile + " item.productOrService system (code " + value + ")", "The code " + value + " was sent with no "
                    + "system. " + notes.pas() + " binds the item's code, required, to the value set X12278RequestedServiceType, so the service may "
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
                        + notes.pas() + " names for " + name(uri) + " was sent in its place: " + uri + "."
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
     * An attached document as PASDocumentReference under 2.2.1, whose parent is US Core 7.0.0
     * DocumentReference, and as US Core 3.1.1 DocumentReference under 2.0.1, which has no profile of its own for it.
     * US Core requires a type and a category; both come from the tester, and neither is chosen here.
     *
     * <p>PAS 2.2.1 requires the content inline as {@code data} and forbids a {@code url}; 2.0.1 allows either. A file the
     * page read arrives as base64 with its size, and goes as it came. A text goes as the base64 of
     * its UTF-8 bytes, with their count. A url, still accepted from a body sent from curl, is sent as
     * a url, because sending a request that breaks the rule is a test a tester may mean to run; it is
     * counted among what the request went out without, and the note says so.
     */
    static ObjectNode documentReference(String id, String title, PasAttachment attachment, int position, PasNotes notes) {
        return documentReference(id, title, attachment, position, CaseResources.PATIENT_REF, notes);
    }

    /** @param patientRef the reference the bundle's Patient is named by, so the document is about the member the claim is */
    static ObjectNode documentReference(String id, String title, PasAttachment attachment, int position, String patientRef, PasNotes notes) {
        ObjectNode document = JSON.objectNode();
        document.put("resourceType", "DocumentReference");
        document.put("id", id);
        document.put("status", OnyxPasConventions.DOCUMENT_STATUS);
        if (attachment.type() != null && Texts.present(attachment.type().code())) {
            document.set("type", coded(attachment.type()));
        } else {
            notes.missing(documentProfile(notes) + " type (attachment " + position + ")", "Attachment " + position + " was sent with no "
                    + "document type, because none was given. " + documentRule(notes)
                    + " requires one: give the attachment a type, such as a LOINC document code.");
        }
        if (attachment.category() != null && Texts.present(attachment.category().code())) {
            document.putArray("category").add(coded(attachment.category()));
        } else {
            notes.missing(documentProfile(notes) + " category (attachment " + position + ")", "Attachment " + position + " was sent "
                    + "with no category, because none was given. " + documentRule(notes)
                    + " requires one: give the attachment a category, such as clinical-note.");
        }
        document.putObject("subject").put("reference", patientRef);
        document.put("description", title);
        ObjectNode content = document.putArray("content").addObject().putObject("attachment");
        content.put("contentType", attachment.contentType().strip());
        content.put("title", title);
        if (Texts.present(attachment.data())) {
            content.put("data", attachment.data().strip());
            if (attachment.size() != null) {
                content.put("size", attachment.size());
            }
        } else if (Texts.present(attachment.text())) {
            byte[] bytes = attachment.text().getBytes(StandardCharsets.UTF_8);
            content.put("data", Base64.getEncoder().encodeToString(bytes));
            content.put("size", bytes.length);
        } else if (!notes.guide().is221()) {
            // US Core 3.1.1 DocumentReference takes the content as data or as a url; PAS 2.0.1 adds no rule.
            content.put("url", attachment.url().strip());
            notes.add("Attachment " + position + " was sent by its url, as it was given. PAS 2.0.1 allows a url; choose the file on the "
                    + "Submit step to send it inline instead.");
        } else {
            content.put("url", attachment.url().strip());
            notes.missing("PASDocumentReference content.attachment.data (attachment " + position + ")", "Attachment " + position
                    + " was sent by its url, as it was given. " + notes.pas() + " PASDocumentReference requires the document inline as data and "
                    + "forbids a url, so the service may refuse the request for that alone. Choose the file on the Submit step to send it "
                    + "inline.");
        }
        return document;
    }

    /** What a note calls the document's profile: 2.0.1 has no PASDocumentReference, only US Core 3.1.1 DocumentReference. */
    private static String documentProfile(PasNotes notes) {
        return notes.guide().is221() ? "PASDocumentReference" : "US Core 3.1.1 DocumentReference";
    }

    private static String documentRule(PasNotes notes) {
        return notes.guide().is221() ? notes.pas() + " PASDocumentReference, on " + notes.usCore() + " DocumentReference,"
                : "PAS 2.0.1 asks that a document meet " + notes.usCore() + " DocumentReference, which";
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
    /** The Patient's resource id, chosen where CRD and DTR choose it, so a chained run names one patient throughout (R-40). */
    static String patientId(MemberDetail member) {
        return CaseResources.patientId(member);
    }

    static String patientRef(MemberDetail member) {
        return "Patient/" + patientId(member);
    }

    /** The requesting Practitioner's resource id, chosen where CRD and DTR choose it, so DTR's answers name the same author (R-40). */
    static String practitionerId(ProviderDetail provider) {
        return CaseResources.practitionerId(provider);
    }
}
