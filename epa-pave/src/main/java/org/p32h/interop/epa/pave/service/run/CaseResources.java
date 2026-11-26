package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberIdRule;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.ProcedureCode;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import tools.jackson.databind.node.ArrayNode;
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
 *
 * <p>The {@code crd} forms build to the CRD 2.0.1 profiles, which a CRD request carries and which
 * the DTR 2.0.1 package request names for its coverage and order. Each element such a profile
 * requires and nobody supplied is collected as a {@link Required}, so the request can say how many
 * there are and the result can carry them as a finding. The plain forms are the ones a claim was
 * built from before PAS shaped its own resources, and they are kept as they were for it.
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

    /**
     * The two coding systems this tool has a uri for. Used when neither the tester nor the code
     * list states a system and the format of the code decides it, and when the code list names one
     * of the two by a label where a uri belongs.
     *
     * <p>{@link #HCPCS} is the HCPCS address a claim item is built with. An order is not: the
     * CRD 2.0.1 value set bound to ServiceRequest.code (serviceRequestCodes) names HCPCS by
     * {@link #HCPCS_CRD_201}, and the cms.gov address appears in CRD 2.0.1 only in its DeviceRequest
     * value set. The 2.2.0 ballot moved ServiceRequest to the cms.gov address.
     */
    public static final String CPT = "http://www.ama-assn.org/go/cpt";
    public static final String HCPCS = "https://www.cms.gov/Medicare/Coding/HCPCSReleaseCodeSets";
    /** HCPCS as the CRD 2.0.1 ServiceRequest value set names it. The variable {@link #VAR_HCPCS_SYSTEM} overrides it on an order. */
    public static final String HCPCS_CRD_201 = "urn:oid:2.16.840.1.113883.6.285";

    public static final String NPI_SYSTEM = "http://hl7.org/fhir/sid/us-npi";
    public static final String IDENTIFIER_TYPES = "http://terminology.hl7.org/CodeSystem/v2-0203";

    /**
     * The labels a code list may hold where a coding system belongs, in the form
     * {@link #labelKey} gives them, each with the uri this tool uses for that system. A list that
     * writes HCPCS with no level gets the same uri as one that writes HCPCS Level II, because that
     * is the only uri this tool has for HCPCS. The request note names what was sent, so a list
     * that meant something else is not followed in silence.
     */
    private static final Map<String, String> LABELS = Map.of("cpt", CPT, "cpt4", CPT, "hcpcs", HCPCS, "hcpcslevelii", HCPCS,
            "hcpcsii", HCPCS);
    // In LABELS and in byFormat, HCPCS stands for "HCPCS Level II" and is replaced by the address
    // the caller sends HCPCS with, which differs between an order and a claim item.

    /**
     * A uri as far as this tool looks: a scheme of two or more characters, a colon, and something
     * after it that is not only slashes, with no white space anywhere. That takes the http, https
     * and urn forms and leaves out a name, a bare identifier and a path that starts with a drive
     * letter. Whether the uri is the right one for the code is not this tool's to say.
     */
    private static final Pattern URI_SHAPE = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]+:/*[^\\s/]\\S*");

    /** The most characters of a code list cell a request note quotes. */
    private static final int QUOTED_LENGTH = 60;

    /** Placeholder names the built resources read. Each is optional; an unset one leaves its element out. */
    public static final String VAR_FHIR_SERVER = "fhirServer";
    public static final String VAR_MEMBER_ID_SYSTEM = "memberIdSystem";
    public static final String VAR_PAYER_NAME = "payerName";
    public static final String VAR_PAYER_ID_SYSTEM = "payerIdentifierSystem";
    public static final String VAR_PAYER_ID = "payerIdentifier";
    /** The payer as a reference to an Organization the service holds (D-320); written on a Coverage of CRD and DTR only. */
    public static final String VAR_PAYER_REFERENCE = org.p32h.interop.epa.pave.service.config.RequestValues.PAYER_REFERENCE;
    /** The member's medical record number, for the MR identifier CRD 2.0.1 profile-patient requires. Never the member ID. */
    public static final String VAR_PATIENT_MRN = "patientMrn";
    /** The system of that medical record number, which CRD 2.0.1 requires beside the value. */
    public static final String VAR_PATIENT_MRN_SYSTEM = "patientMrnSystem";
    /** The HCPCS system an order is sent with, in place of {@link #HCPCS_CRD_201}. */
    public static final String VAR_HCPCS_SYSTEM = "hcpcsSystem";

    public static final String ORGANIZATION_ID = "organization-1";
    public static final String ORGANIZATION_REF = "Organization/" + ORGANIZATION_ID;

    /** The version whose profiles the CRD form of these resources is built to, named in every note about a requirement. */
    public static final String CRD_VERSION = "CRD 2.0.1";

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /**
     * Something a CRD 2.0.1 profile requires that a request was sent without, because nobody
     * supplied it. The request still goes: one that breaks a profile is a negative case a tester
     * may want, and saying so keeps a refusal by the service from being read as its fault.
     *
     * @param element    the element, such as {@code Patient.identifier:MRIdentifier}
     * @param profile    the profile that requires it, such as {@code profile-patient}
     * @param suppliedBy the variable or field that would supply it
     */
    public record Required(String element, String profile, String suppliedBy) {

        /** One line, as a finding lists it. */
        public String line() {
            return element + " (" + CRD_VERSION + " " + profile + "), supplied by " + suppliedBy;
        }
    }

    private final ProcedureCodeDirectory codeList;
    private final Clock clock;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public CaseResources(ProcedureCodeDirectory codeList) {
        this(codeList, Clock.systemUTC());
    }

    /**
     * @param clock the moment an order is written, which CRD 2.0.1 requires as authoredOn and which
     *              nobody supplies, comes from here
     */
    public CaseResources(ProcedureCodeDirectory codeList, Clock clock) {
        this.codeList = codeList;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /** The moment a request is built, to the second, as authoredOn carries it. */
    public String now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    /** The most characters a FHIR resource id may hold, and so a vendor id this tool sends as one. */
    public static final int LONGEST_RESOURCE_ID = 64;

    /**
     * The Practitioner's resource id: the vendor practitioner id when one was given, else this
     * tool's own. The vendor's sandbox refused a Practitioner it did not hold under its own id, so
     * the id the tester gives is the one every request names (R-40).
     */
    public static String practitionerId(ProviderDetail provider) {
        String vendor = provider == null ? null : Texts.trimToNull(provider.vendorPractitionerId());
        return vendor == null ? PRACTITIONER_ID : vendor;
    }

    public static String practitionerRef(ProviderDetail provider) {
        return "Practitioner/" + practitionerId(provider);
    }

    /**
     * The Patient's resource id and the CRD patientId: the vendor patient id when one was given,
     * else {@value #PATIENT_ID}. Never a form of the member ID, which is an identifier and not an id.
     */
    public static String patientId(MemberDetail member) {
        String vendor = member == null ? null : Texts.trimToNull(member.vendorPatientId());
        return vendor == null ? PATIENT_ID : vendor;
    }

    /**
     * The member ID as typed and as the call's rule sends it, for the wire view and the preview, or
     * null when the request names none. The ledger keeps the packed form whatever this gives (R-10).
     *
     * @param rule the call's member ID rule; null reads as packed
     */
    public static MemberIdForms memberIdForms(MemberDetail member, MemberIdRule rule) {
        String typed = member == null ? null : member.memberId();
        return Texts.present(typed) ? MemberIds.forms(typed, rule) : null;
    }

    public static String patientRef(MemberDetail member) {
        return "Patient/" + patientId(member);
    }

    /**
     * True when the provider is known by an organization name alone, and so is sent as an
     * Organization with no Practitioner. Every other provider, one with no name at all included,
     * is sent as a Practitioner.
     */
    public static boolean organizationOnly(ProviderDetail provider) {
        return provider != null && Texts.blank(provider.lastName()) && Texts.blank(provider.firstName())
                && Texts.present(provider.organizationName());
    }

    /**
     * The reference the orders name as their requester and CRD sends as userId: the Practitioner
     * for a person, as the vendor's accepted request had it, and the PractitionerRole that names
     * the organization for a provider known by an organization name alone, since no Practitioner
     * is sent for one.
     */
    public static String requesterRef(ProviderDetail provider) {
        return organizationOnly(provider) ? PRACTITIONER_ROLE_REF : practitionerRef(provider);
    }

    /**
     * What is wrong with a vendor id that is to be sent as a FHIR resource id, or null when nothing
     * is or none was given. A FHIR id holds letters, digits, '-' and '.' only, at most 64 of them;
     * checked one character at a time, so its cost is the length of the text.
     *
     * @param what how the field is named in the sentence, such as "A vendor patient id"
     */
    public static String vendorIdProblem(String what, String id) {
        if (Texts.blank(id)) {
            return null;
        }
        String value = id.strip();
        boolean allowed = value.length() <= LONGEST_RESOURCE_ID;
        for (int i = 0; allowed && i < value.length(); i++) {
            char c = value.charAt(i);
            allowed = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
        }
        return allowed ? null : what + " holds letters, digits, - and . only, at most " + LONGEST_RESOURCE_ID + " characters. It is sent "
                + "as a FHIR resource id, which allows nothing else.";
    }

    /** The note that says which id the Patient went with, and patientId with it where the request carries one. */
    public static String patientIdNote(MemberDetail member, boolean withPatientId) {
        String id = patientId(member);
        String what = withPatientId ? "The Patient and patientId were sent with the id " : "The Patient was sent with the id ";
        if (member != null && Texts.present(member.vendorPatientId())) {
            return what + id + ", the vendor patient id given.";
        }
        return what + PATIENT_ID + ", this tool's own: no vendor patient id was given. Whether the service accepts a patient id its FHIR "
                + "server does not hold is not known; give the vendor patient id under Edit member fields to send that instead.";
    }

    /** The note that says the Practitioner went under the vendor practitioner id, or null when none was given. */
    public static String practitionerIdNote(ProviderDetail provider, boolean crd) {
        if (provider == null || Texts.blank(provider.vendorPractitionerId()) || organizationOnly(provider)) {
            return null;
        }
        String ref = practitionerRef(provider);
        return "The Practitioner was sent with the id " + practitionerId(provider) + ", the vendor practitioner id given, so "
                + (crd ? "userId and the requester of every order read " : "every reference to the provider reads ") + ref + ".";
    }

    /**
     * An absolute fullUrl for a resource in a bundle this tool sends: the FHIR server's address,
     * the type and the id when the FHIR server is known, else a urn:uuid named by the type and the
     * id, so the same resource has the same fullUrl in every bundle of one request.
     */
    public static String fullUrl(String fhirServer, String type, String id) {
        if (Texts.present(fhirServer)) {
            String base = fhirServer.strip();
            int end = base.length();
            while (end > 0 && base.charAt(end - 1) == '/') {
                end--;
            }
            return base.substring(0, end) + "/" + type + "/" + id;
        }
        return "urn:uuid:" + java.util.UUID.nameUUIDFromBytes((type + "/" + id).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Adds a resource to a bundle's entries with its fullUrl. */
    public static void entry(ArrayNode entries, ObjectNode resource, String fhirServer) {
        ObjectNode entry = entries.addObject();
        entry.put("fullUrl", fullUrl(fhirServer, JsonSafe.text(resource, "resourceType"), JsonSafe.text(resource, "id")));
        entry.set("resource", resource);
    }

    /** The note that says how every fullUrl was written. */
    public static String fullUrlNote(String fhirServer) {
        return Texts.present(fhirServer)
                ? "Every bundle entry carries a fullUrl made of the FHIR server, the resource type and its id."
                : "Every bundle entry carries a fullUrl of the form urn:uuid: because the variable " + VAR_FHIR_SERVER + " is not set; "
                        + "with it set, each would be the FHIR server, the resource type and its id.";
    }

    /**
     * The one note for quantities and units on the codes, which CRD and DTR do not send: the
     * order a CRD or DTR request carries states no quantity in what the vendor's sandbox accepted.
     * Null when no code carries either.
     */
    public static String quantityNote(List<CodeInput> codes) {
        boolean any = codes != null && codes.stream().anyMatch(code -> code != null && (code.quantity() != null || Texts.present(code.unit())));
        return any ? "The quantity and unit given on the codes were left out of this request: Submit sends them, the orders of a CRD or "
                + "DTR request do not." : null;
    }

    /** The local id of the order at a position, counting from one. */
    public static String serviceRequestId(int position) {
        return "service-request-" + position;
    }

    public static String serviceRequestRef(int position) {
        return "ServiceRequest/" + serviceRequestId(position);
    }

    /**
     * Resolves variables for a builder and remembers each secret one it reads, so that the body
     * built from them can be shown with every such value masked.
     *
     * <p>A body this tool builds is otherwise shown exactly as it is sent, and so is taken to hold
     * no secret: the wire view then shows a secret variable the builder wrote into it, and a
     * service that repeats that value is shown repeating it everywhere. {@link #body} gives the
     * shown form its masks and names the secret variables it used, which is what lets the
     * response be searched for them, as it is for a secret resolved into a collection's body.
     */
    public static Reads reading(Function<String, ResolvedText> variables) {
        return new Reads(variables, null);
    }

    /**
     * As {@link #reading(Function)} for a prepared call: its values, and under them the values the
     * collection request it is sent from states (D-320). A preview and a send each read through
     * this, so the two build the same body.
     */
    public static Reads reading(Function<String, ResolvedText> variables, org.p32h.interop.epa.pave.service.config.RequestValues.ForCall request) {
        return new Reads(variables, request);
    }

    /** See {@link #reading(Function, org.p32h.interop.epa.pave.service.config.RequestValues.ForCall)}. */
    public static Reads reading(org.p32h.interop.epa.pave.service.config.PreparedCall call) {
        return call == null ? new Reads(null, null) : new Reads(call::resolve, call.requestValues());
    }

    /** See {@link #reading}. Used for one body. */
    public static final class Reads implements Function<String, ResolvedText> {

        private final Function<String, ResolvedText> variables;
        private final org.p32h.interop.epa.pave.service.config.RequestValues.ForCall request;
        private final Map<String, String> secrets = new java.util.LinkedHashMap<>();
        private final List<org.p32h.interop.epa.pave.model.VariableUse> uses = new java.util.ArrayList<>();
        private final java.util.Set<String> fromRequest = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> unset = new java.util.LinkedHashSet<>();

        private Reads(Function<String, ResolvedText> variables, org.p32h.interop.epa.pave.service.config.RequestValues.ForCall request) {
            this.variables = variables;
            this.request = request == null ? org.p32h.interop.epa.pave.service.config.RequestValues.ForCall.NONE : request;
        }

        @Override
        public ResolvedText apply(String template) {
            ResolvedText resolved = variables == null ? null : variables.apply(template);
            String name = requestValue(template);
            if (name != null && !hasValue(resolved)) {
                // The lowest of all sources: asked only when nothing typed, stored, exported or configured gives the value.
                String stated = request.values().get(name);
                if (stated != null && pairedInForce(name)) {
                    fromRequest.add(name);
                    return ResolvedText.plain(stated);
                }
                unset.add(name);
                return resolved;
            }
            if (resolved == null || !resolved.resolved() || resolved.value() == null) {
                return resolved;
            }
            for (org.p32h.interop.epa.pave.model.VariableUse use : resolved.variables()) {
                // Only a read of one variable alone tells its value apart from the text around it.
                if (use.secret() && template != null && template.strip().equals("{{" + use.name() + "}}") && !secrets.containsKey(use.name())) {
                    secrets.put(use.name(), resolved.value());
                    uses.add(use);
                }
            }
            return resolved;
        }

        /** The name of one of the values a call takes from its request, when the text is that placeholder alone; else null. */
        private static String requestValue(String template) {
            if (template == null) {
                return null;
            }
            String text = template.strip();
            if (!text.startsWith("{{") || !text.endsWith("}}")) {
                return null;
            }
            String name = text.substring(2, text.length() - 2);
            return org.p32h.interop.epa.pave.service.config.RequestValues.NAMES.contains(name) ? name : null;
        }

        private static boolean hasValue(ResolvedText resolved) {
            return resolved != null && resolved.resolved() && Texts.present(resolved.value());
        }

        /**
         * The payer identifier's system is the system of the identifier the same request states: it
         * is not laid beside an identifier from elsewhere, which may have another system (D-308).
         */
        private boolean pairedInForce(String name) {
            if (!VAR_PAYER_ID_SYSTEM.equals(name)) {
                return true;
            }
            return request.values().containsKey(VAR_PAYER_ID) && !hasValue(variables == null ? null : variables.apply("{{" + VAR_PAYER_ID + "}}"));
        }

        /**
         * What the request's notes say of the values this body read from the request it is sent
         * from, and, for one nothing gave, which rule kept the request's own out (D-320). Empty when
         * neither applies.
         */
        public List<String> requestNotes() {
            List<String> notes = new java.util.ArrayList<>();
            String taken = org.p32h.interop.epa.pave.service.config.RequestValues.takenNote(request.request(), List.copyOf(fromRequest));
            if (taken != null) {
                notes.add(taken);
            }
            java.util.Set<String> lines = new java.util.LinkedHashSet<>();
            for (String name : unset) {
                lines.addAll(request.lines(name));
            }
            notes.addAll(lines);
            return notes;
        }

        /** The names of the values this body took from the request it is sent from, in the order they were read. */
        public List<String> fromRequest() {
            return List.copyOf(fromRequest);
        }

        /** The built body, sent as built and shown with each secret value this builder read masked. */
        public ResolvedText body(String json) {
            if (json == null || secrets.isEmpty()) {
                return ResolvedText.plain(json);
            }
            String shown = json;
            for (Map.Entry<String, String> secret : secrets.entrySet()) {
                if (secret.getValue().isEmpty()) {
                    continue;
                }
                String written = JsonNodeFactory.instance.stringNode(secret.getValue()).toString();
                String escaped = written.substring(1, written.length() - 1);
                shown = shown.replace(escaped, org.p32h.interop.epa.pave.model.Secrets.mask(secret.getKey()));
            }
            return new ResolvedText(json, shown, json, uses, List.of());
        }

        /** The built body as it may be shown, for a preview. */
        public String shown(String json) {
            return body(json).display();
        }
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

    /**
     * An order as CRD 2.0.1 profile-servicerequest has it: status draft, intent order, the code, the
     * patient, authoredOn and the requester, all of which the profile requires.
     *
     * @param authoredOn  when the order is written. Nobody supplies it, so the caller passes the
     *                    moment the request is built and says so in a note ({@link #authoredOnNote})
     * @param hcpcsSystem the address a HCPCS Level II code is sent with
     */
    public ObjectNode serviceRequest(String id, CodeInput code, MemberDetail member, ProviderDetail provider, String dateOfService,
                                     String authoredOn, String hcpcsSystem, List<String> notes) {
        ObjectNode resource = JSON.objectNode();
        resource.put("resourceType", "ServiceRequest");
        resource.put("id", id);
        resource.put("status", "draft");
        resource.put("intent", "order");
        resource.set("code", codeableConcept(code, member == null ? null : member.lineOfBusiness(), notes, hcpcsSystem));
        resource.putObject("subject").put("reference", patientRef(member));
        if (Texts.present(dateOfService)) {
            resource.put("occurrenceDateTime", dateOfService.strip());
        }
        resource.put("authoredOn", authoredOn);
        resource.putObject("requester").put("reference", requesterRef(provider));
        resource.putArray("insurance").addObject().put("reference", COVERAGE_REF);
        return resource;
    }

    /** The note that goes with an authoredOn this tool wrote itself. */
    public static String authoredOnNote(String authoredOn) {
        return "Each order was sent with authoredOn " + authoredOn + ", the moment this request was built: " + CRD_VERSION
                + " profile-servicerequest requires authoredOn, and nobody supplies it.";
    }

    /**
     * The HCPCS address an order is sent with: the variable {@value #VAR_HCPCS_SYSTEM} when it has a
     * value, else the one the CRD 2.0.1 ServiceRequest value set names.
     */
    public static String orderHcpcsSystem(Function<String, ResolvedText> variables) {
        return Texts.orElse(value(variables, VAR_HCPCS_SYSTEM), HCPCS_CRD_201);
    }

    /** The note that says which HCPCS address the orders went with, and where it came from. */
    public static String hcpcsNote(String hcpcsSystem) {
        if (HCPCS_CRD_201.equals(hcpcsSystem)) {
            return "HCPCS Level II codes were sent with the system " + HCPCS_CRD_201 + ", the address the " + CRD_VERSION
                    + " ServiceRequest value set (serviceRequestCodes) gives HCPCS. The CRD 2.2.0 ballot names " + HCPCS
                    + " instead. The variable " + VAR_HCPCS_SYSTEM + " sets another.";
        }
        return "HCPCS Level II codes were sent with the system " + hcpcsSystem + ", from the variable " + VAR_HCPCS_SYSTEM + ". The "
                + CRD_VERSION + " ServiceRequest value set names HCPCS as " + HCPCS_CRD_201 + ".";
    }

    /** A procedure code as a CodeableConcept with one coding, HCPCS Level II sent under the address given. */
    public ObjectNode codeableConcept(CodeInput code, String lineOfBusiness, List<String> notes, String hcpcsSystem) {
        ObjectNode concept = JSON.objectNode();
        ObjectNode coding = concept.putArray("coding").addObject();
        String system = codingSystem(code, lineOfBusiness, notes, hcpcsSystem);
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
     *
     * <p>The two sources that state a system are not treated alike. What the tester typed is sent
     * exactly as typed, whatever it is: a wrong system is a negative case a tester is entitled to
     * send. What the code list holds is read first, because a FHIR coding system is a uri and an
     * export may hold a name there, such as CPT. Sent as it stands, that name would be the system
     * of every order built from the list, which nobody asked for. So from the list:
     * <ul>
     *   <li>a uri is used as it is</li>
     *   <li>a label this tool knows is replaced by the uri this tool uses for it, and a note says so</li>
     *   <li>anything else is not sent as the system. A note says what the list held, and the
     *       format of the code decides</li>
     * </ul>
     */
    public String codingSystem(CodeInput code, String lineOfBusiness, List<String> notes) {
        return codingSystem(code, lineOfBusiness, notes, HCPCS);
    }

    /** As {@link #codingSystem(CodeInput, String, List)}, with HCPCS Level II sent under the address given. */
    public String codingSystem(CodeInput code, String lineOfBusiness, List<String> notes, String hcpcsSystem) {
        if (Texts.present(code.system())) {
            return code.system().strip();
        }
        String hcpcs = Texts.orElse(hcpcsSystem, HCPCS);
        String value = code.code().strip();
        String byFormat = HCPCS.equals(byFormat(value)) ? hcpcs : byFormat(value);
        String stated = statedByTheCodeList(value, lineOfBusiness);
        if (stated != null) {
            // The label is looked for first. A cell written "CPT:4" has the shape of a uri and is not one.
            String labelled = HCPCS.equals(LABELS.get(labelKey(stated))) ? hcpcs : LABELS.get(labelKey(stated));
            if (labelled != null) {
                String note = "The code list gives the coding system for " + value + " as the label " + quoted(stated) + ", which is not "
                        + "a uri. The uri this tool uses for " + name(labelled) + " was sent in its place: " + labelled + ".";
                // The list is followed, since it is the list's statement. When the format of the
                // code points elsewhere, that is the first thing to look at if the service refuses
                // the request, so it is said here and not left for the tester to work out.
                if (byFormat != null && !byFormat.equals(labelled)) {
                    note += " By its format alone the code would have been sent as " + name(byFormat) + ".";
                }
                notes.add(note);
                return labelled;
            }
            if (URI_SHAPE.matcher(stated).matches()) {
                return stated;
            }
            notes.add("The code list gives the coding system for " + value + " as " + quoted(stated) + ". That is not a uri, and it is "
                    + "not a label this tool knows, so it was not sent as the system.");
        }
        if (byFormat == null) {
            notes.add("No coding system could be worked out for " + value + ", so it was sent without one.");
            return null;
        }
        notes.add("The coding system for " + value + " was taken from its format: " + name(byFormat) + ".");
        return byFormat;
    }

    /** What the first row of the code list that states a system for the code holds there, or null when no row states one. */
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

    /** The system the format of a code points to, or null when the format decides nothing. */
    private static String byFormat(String code) {
        String upper = code.toUpperCase(Locale.ROOT);
        if (upper.matches("\\d{5}") || upper.matches("\\d{4}[FTU]")) {
            return CPT;
        }
        return upper.matches("[A-V]\\d{4}") ? HCPCS : null;
    }

    /** What a note calls one of the two systems this tool has a uri for, whichever HCPCS address was chosen. */
    private static String name(String system) {
        return CPT.equals(system) ? "CPT" : "HCPCS Level II";
    }

    private static void addOnce(List<String> notes, String note) {
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }

    /** The form a label is looked up in: its letters and digits in lower case, so "CPT-4", "cpt 4" and "Cpt4" are one label. */
    private static String labelKey(String cell) {
        return cell.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** A cell as a note shows it: in double quotes, and cut when long, since a note is read on screen beside the request. */
    private static String quoted(String cell) {
        boolean cut = cell.codePointCount(0, cell.length()) > QUOTED_LENGTH;
        return "\"" + (cut ? cell.substring(0, cell.offsetByCodePoints(0, QUOTED_LENGTH)) + "..." : cell) + "\"";
    }

    private static ObjectNode memberIdentifier(MemberDetail member, MemberIdRule rule, Function<String, ResolvedText> variables,
                                               List<String> notes, boolean note) {
        ObjectNode identifier = JSON.objectNode();
        identifier.putObject("type").putArray("coding").addObject().put("system", IDENTIFIER_TYPES).put("code", "MB");
        String system = value(variables, VAR_MEMBER_ID_SYSTEM);
        if (system != null) {
            identifier.put("system", system);
        } else if (note) {
            notes.add("The member ID was sent without an identifier system: the variable " + VAR_MEMBER_ID_SYSTEM + " is not set.");
        }
        // The form the environment's member ID rule gives (R-10); packed, the form the vendor's sandbox accepts, when none is stated.
        identifier.put("value", MemberIds.sent(member.memberId(), rule));
        return identifier;
    }

    /**
     * The payer as a reference: a reference to an Organization the service holds, an identifier, a
     * display name, or any of them, from the payer placeholders. Empty when none is set, and the
     * caller then leaves the element out.
     */
    public static ObjectNode payer(Function<String, ResolvedText> variables) {
        ObjectNode payor = JSON.objectNode();
        // FHIR orders a Reference as reference, type, identifier, display. A reference is to an
        // Organization the service holds, beside what names it here (D-320).
        String payerReference = value(variables, VAR_PAYER_REFERENCE);
        if (payerReference != null) {
            payor.put("reference", payerReference);
        }
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
     * The member as CRD 2.0.1 profile-patient has it. Beside the member ID it carries the medical
     * record number the profile requires (identifier:MRIdentifier, 1..1, with type MR, a system
     * and a value), taken from {@value #VAR_PATIENT_MRN} and {@value #VAR_PATIENT_MRN_SYSTEM}. The
     * member ID is never sent as a medical record number: the two identify different things.
     * Everything the profile requires and nobody supplied is left out, noted, and added to
     * {@code missing}.
     */
    public ObjectNode crdPatient(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes,
                                 List<Required> missing) {
        return crdPatient(member, MemberIdRule.PACKED, variables, notes, missing);
    }

    /** @param rule the member ID rule of the call (R-10); null reads as packed */
    public ObjectNode crdPatient(MemberDetail member, MemberIdRule rule, Function<String, ResolvedText> variables, List<String> notes,
                                 List<Required> missing) {
        ObjectNode patient = JSON.objectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", patientId(member));
        ArrayNode identifiers = patient.putArray("identifier");
        identifiers.add(memberIdentifier(member, rule, variables, notes, false));
        if (value(variables, VAR_MEMBER_ID_SYSTEM) == null) {
            missing.add(new Required("Patient.identifier.system (the member ID)", "profile-patient", "the variable " + VAR_MEMBER_ID_SYSTEM));
            notes.add(CRD_VERSION + " profile-patient requires a system on every identifier. The member ID was sent without one: the "
                    + "variable " + VAR_MEMBER_ID_SYSTEM + " is not set.");
        }
        String mrn = value(variables, VAR_PATIENT_MRN);
        String mrnSystem = value(variables, VAR_PATIENT_MRN_SYSTEM);
        if (mrn != null) {
            ObjectNode record = identifiers.addObject();
            record.putObject("type").putArray("coding").addObject().put("system", IDENTIFIER_TYPES).put("code", "MR");
            if (mrnSystem != null) {
                record.put("system", mrnSystem);
            } else {
                missing.add(new Required("Patient.identifier:MRIdentifier.system", "profile-patient",
                        "the variable " + VAR_PATIENT_MRN_SYSTEM));
                notes.add(CRD_VERSION + " profile-patient requires a system on the medical record number. It was sent without one: "
                        + "the variable " + VAR_PATIENT_MRN_SYSTEM + " is not set.");
            }
            record.put("value", mrn);
        } else {
            missing.add(new Required("Patient.identifier:MRIdentifier", "profile-patient",
                    "the variables " + VAR_PATIENT_MRN + " and " + VAR_PATIENT_MRN_SYSTEM));
            notes.add(CRD_VERSION + " profile-patient requires one medical record number identifier (type MR, with a system and a "
                    + "value). None was sent: the variable " + VAR_PATIENT_MRN + " is not set. The member ID is not a medical record "
                    + "number and is not sent as one.");
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
            missing.add(new Required("Patient.name", "profile-patient", "the member lookup or the member's name on the form"));
            notes.add(CRD_VERSION + " profile-patient requires the member's name. None was sent, because none was supplied.");
        }
        if (Texts.present(member.dateOfBirth())) {
            patient.put("birthDate", member.dateOfBirth().strip());
        } else {
            notes.add("No date of birth was sent, because none was supplied.");
        }
        String gender = gender(member.gender());
        if (gender != null) {
            patient.put("gender", gender);
        } else {
            missing.add(new Required("Patient.gender", "profile-patient", "the member lookup or the member's gender on the form"));
            notes.add(Texts.present(member.gender())
                    ? CRD_VERSION + " profile-patient requires a gender. The gender \"" + member.gender().strip() + "\" is not one this "
                            + "tool can map to a FHIR code (male, female, other or unknown), so none was sent."
                    : CRD_VERSION + " profile-patient requires a gender. None was sent, because none was supplied.");
        }
        return patient;
    }

    /**
     * The coverage as CRD 2.0.1 profile-coverage has it. Its status and its payer are required
     * there and are sent only when supplied: a status the lookup did not return is not "active".
     * The period is sent when the lookup gave the coverage dates.
     */
    public ObjectNode crdCoverage(MemberDetail member, Function<String, ResolvedText> variables, List<String> notes,
                                  List<Required> missing) {
        return crdCoverage(member, MemberIdRule.PACKED, variables, notes, missing);
    }

    /** @param rule the member ID rule of the call (R-10); null reads as packed */
    public ObjectNode crdCoverage(MemberDetail member, MemberIdRule rule, Function<String, ResolvedText> variables, List<String> notes,
                                  List<Required> missing) {
        ObjectNode coverage = JSON.objectNode();
        coverage.put("resourceType", "Coverage");
        coverage.put("id", COVERAGE_ID);
        String status = coverageStatus(member.coverageStatus());
        if (status != null) {
            coverage.put("status", status);
        } else {
            missing.add(new Required("Coverage.status", "profile-coverage", "the member lookup's coverage status"));
            notes.add(Texts.present(member.coverageStatus())
                    ? CRD_VERSION + " profile-coverage requires a status. The coverage status \"" + member.coverageStatus().strip()
                            + "\" is not one this tool can map to a FHIR code, so none was sent."
                    : CRD_VERSION + " profile-coverage requires a status. None was sent, because none was supplied: a status nobody "
                            + "stated is not taken to be active.");
        }
        coverage.putArray("identifier").add(memberIdentifier(member, rule, variables, notes, false));
        if (Texts.present(member.subscriberId())) {
            coverage.put("subscriberId", member.subscriberId().strip());
        }
        coverage.putObject("beneficiary").put("reference", patientRef(member));
        ObjectNode period = JSON.objectNode();
        periodPart(period, "start", member.coverageStart(), notes);
        periodPart(period, "end", member.coverageEnd(), notes);
        if (!period.isEmpty()) {
            coverage.set("period", period);
        }
        ObjectNode payor = payer(variables);
        if (payor.isEmpty()) {
            missing.add(new Required("Coverage.payor", "profile-coverage", "the variables " + VAR_PAYER_ID + " or " + VAR_PAYER_NAME));
            notes.add(CRD_VERSION + " profile-coverage requires a payer. None was named on the coverage: neither " + VAR_PAYER_ID
                    + " nor " + VAR_PAYER_NAME + " is set.");
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

    private static void periodPart(ObjectNode period, String part, String date, List<String> notes) {
        if (Texts.blank(date)) {
            return;
        }
        if (IsoDates.valid(date.strip())) {
            period.put(part, date.strip());
        } else {
            notes.add("The coverage " + part + " date \"" + date.strip() + "\" is not a date of the form YYYY-MM-DD, so it was not sent "
                    + "in Coverage.period.");
        }
    }

    /**
     * The provider as a CRD request names it: a PractitionerRole, and the person or the
     * organization it is. A provider known only by an organization name is an Organization with a
     * PractitionerRole that names it, never a Practitioner, because CRD 2.0.1 profile-practitioner
     * requires a family name an organization does not have.
     *
     * @param role         the PractitionerRole the orders name as their requester
     * @param practitioner the Practitioner, or null when the provider is an organization
     * @param organization the Organization, or null when the provider is a person
     */
    public record Provider(ObjectNode role, ObjectNode practitioner, ObjectNode organization) {
    }

    public static Provider crdProvider(ProviderDetail provider, List<String> notes, List<Required> missing) {
        boolean person = Texts.present(provider.lastName()) || Texts.present(provider.firstName());
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", PRACTITIONER_ROLE_ID);
        if (organizationOnly(provider)) {
            ObjectNode organization = organization(ORGANIZATION_ID, provider);
            role.putObject("organization").put("reference", ORGANIZATION_REF);
            notes.add("The provider is known by an organization name only, so it was sent as an Organization with its NPI and a "
                    + "PractitionerRole that names it, and not as a Practitioner.");
            missing.add(new Required("Organization.active", "profile-organization",
                    "nothing this tool reads: the provider directory does not say whether the organization record is active"));
            notes.add(CRD_VERSION + " profile-organization requires active, which says whether the organization record is in use. "
                    + "Nothing this tool reads states it, so it was not sent.");
            return new Provider(role, null, organization);
        }
        ObjectNode practitioner = practitioner(provider);
        role.putObject("practitioner").put("reference", practitionerRef(provider));
        if (Texts.blank(provider.npi())) {
            missing.add(new Required("Practitioner.identifier", "profile-practitioner", "the provider's NPI"));
            notes.add(CRD_VERSION + " profile-practitioner requires an identifier. No NPI was given, so the Practitioner carries none"
                    + (Texts.present(provider.vendorPractitionerId()) ? " and is named by the vendor practitioner id " + practitionerId(provider)
                    + " alone." : "."));
        }
        if (!person) {
            // practitioner() writes an organization name as name.text. That case went above, so here there is no name at all.
            missing.add(new Required("Practitioner.name", "profile-practitioner", "the provider lookup or the provider's name on the form"));
            notes.add(CRD_VERSION + " profile-practitioner requires the practitioner's name. None was sent, because none was supplied.");
        } else if (Texts.blank(provider.lastName())) {
            missing.add(new Required("Practitioner.name.family", "profile-practitioner", "the provider lookup or the provider's last name"));
            notes.add(CRD_VERSION + " profile-practitioner requires a family name. Only a given name was supplied, so the name was sent "
                    + "without one.");
        }
        return new Provider(role, practitioner, null);
    }

    public static ObjectNode practitionerRole(ProviderDetail provider) {
        return practitionerRole(PRACTITIONER_ROLE_ID, practitionerRef(provider));
    }

    /** A PractitionerRole under an id, naming the Practitioner a reference gives. */
    static ObjectNode practitionerRole(String id, String practitionerReference) {
        ObjectNode role = JSON.objectNode();
        role.put("resourceType", "PractitionerRole");
        role.put("id", id);
        role.putObject("practitioner").put("reference", practitionerReference);
        return role;
    }

    // ---------------------------------------------------------------------------------------------
    // The servicing provider (D-321)
    // ---------------------------------------------------------------------------------------------

    /**
     * True when a case names a servicing provider: any of its NPI, names or vendor practitioner id.
     * A provider object with nothing in it names nobody, and nothing is sent for it.
     */
    public static boolean names(ProviderDetail provider) {
        return provider != null && (Texts.present(provider.npi()) || Texts.present(provider.lastName()) || Texts.present(provider.firstName())
                || Texts.present(provider.organizationName()) || Texts.present(provider.vendorPractitionerId()));
    }

    /**
     * The servicing provider as the order of a coverage check or a questionnaire request names it,
     * and the resources that travel for it.
     *
     * @param reference the order's performer: Practitioner/, Organization/ or PractitionerRole/ and its id
     * @param kind      the type of the resource the reference names
     * @param resources the resources to send beside the requesting provider's, each of its own id; empty when the servicing provider
     *                  names the requesting provider's Practitioner
     * @param shared    true when the servicing provider carries the requesting provider's vendor practitioner id, so the two are
     *                  one Practitioner
     */
    public record Servicing(String reference, String kind, List<ObjectNode> resources, boolean shared) {

        public Servicing {
            resources = resources == null ? List.of() : List.copyOf(resources);
        }

        /** The resources of one type, in the order they are sent. */
        public List<ObjectNode> of(String type) {
            return resources.stream().filter(resource -> type.equals(JsonSafe.text(resource, "resourceType"))).toList();
        }

        /** The sentence a request's notes give it: what goes as the performer. */
        public String performerNote() {
            String note = "The servicing provider goes as the order's performer, " + reference + ".";
            if (shared) {
                note += " It carries the requesting provider's vendor practitioner id, so it names the same Practitioner and no second "
                        + "Practitioner is sent.";
            }
            return note;
        }

        /** Redacted: the resources hold the provider's NPI and name. */
        @Override
        public String toString() {
            return "Servicing[" + kind + "]";
        }
    }

    /**
     * The servicing provider of a case as an order's performer (D-321), with the ids Submit gives it,
     * so one case names one servicing provider the same way at every step: a person as
     * Practitioner/ the vendor practitioner id, else Practitioner/practitioner-servicing-1; one known
     * by an organization name alone as Organization/organization-servicing-1; a person, where the
     * order of the collection's request names its performer as a PractitionerRole, as the role
     * PractitionerRole/practitioner-role-servicing-1 that names the Practitioner. Nothing is filled
     * in: a case that names no servicing provider gets none, and the requesting provider never stands in for one.
     *
     * @param servicing  the servicing provider of the case, or null
     * @param requesting the requesting provider, or null. When the servicing provider carries the same vendor practitioner id, the
     *                   two are one Practitioner, as Submit rules it: a Bundle may not hold two resources of one type under one id
     * @param sampleType the resource type the performer of the sample's order refers to, or null
     * @return null when the case names no servicing provider
     */
    public static Servicing servicing(ProviderDetail servicing, ProviderDetail requesting, String sampleType) {
        if (!names(servicing)) {
            return null;
        }
        if (organizationOnly(servicing)) {
            String id = org.p32h.interop.epa.pave.client.pas.OnyxPasConventions.SERVICING_ORGANIZATION_ID;
            return new Servicing("Organization/" + id, "Organization", List.of(organization(id, servicing)), false);
        }
        String vendorId = Texts.trimToNull(servicing.vendorPractitionerId());
        String id = vendorId == null ? org.p32h.interop.epa.pave.client.pas.OnyxPasConventions.SERVICING_PRACTITIONER_ID : vendorId;
        boolean shared = vendorId != null && requesting != null && !organizationOnly(requesting)
                && vendorId.equals(Texts.trimToNull(requesting.vendorPractitionerId()));
        List<ObjectNode> resources = new java.util.ArrayList<>();
        if (!shared) {
            resources.add(practitioner(id, servicing));
        }
        if ("PractitionerRole".equals(sampleType)) {
            String roleId = org.p32h.interop.epa.pave.client.pas.OnyxPasConventions.SERVICING_ROLE_ID;
            resources.add(practitionerRole(roleId, "Practitioner/" + id));
            return new Servicing("PractitionerRole/" + roleId, "PractitionerRole", resources, shared);
        }
        return new Servicing("Practitioner/" + id, "Practitioner", resources, shared);
    }

    /**
     * Puts the case's diagnoses and its servicing provider on an order, each where FHIR's order of
     * the elements of a ServiceRequest puts it: performer and reasonCode after requester and before
     * insurance, so the order reads as a vendor's does.
     *
     * @param reasonCode the diagnoses as {@link Diagnoses#reasonCodes} gives them; nothing when empty
     * @param performer  the reference of the servicing provider, or null
     */
    public static void onOrder(ObjectNode order, ArrayNode reasonCode, String performer) {
        if ((reasonCode == null || reasonCode.isEmpty()) && performer == null) {
            return;
        }
        tools.jackson.databind.JsonNode insurance = order.remove("insurance");
        if (performer != null) {
            order.putArray("performer").addObject().put("reference", performer);
        }
        if (reasonCode != null && !reasonCode.isEmpty()) {
            order.set("reasonCode", reasonCode.deepCopy());
        }
        if (insurance != null) {
            order.set("insurance", insurance);
        }
    }

    /** An Organization under an id, with the NPI as its identifier when one was given and the organization name. */
    static ObjectNode organization(String id, ProviderDetail provider) {
        ObjectNode organization = JSON.objectNode();
        organization.put("resourceType", "Organization");
        organization.put("id", id);
        if (Texts.present(provider.npi())) {
            organization.putArray("identifier").addObject().put("system", NPI_SYSTEM).put("value", provider.npi().strip());
        }
        organization.put("name", provider.organizationName().strip());
        return organization;
    }

    /** The Practitioner under the id {@link #practitionerId} gives, with the NPI as its identifier when one was given. */
    public static ObjectNode practitioner(ProviderDetail provider) {
        return practitioner(practitionerId(provider), provider);
    }

    /** As {@link #practitioner(ProviderDetail)}, under the id given: the requesting provider's and the servicing provider's are built alike. */
    static ObjectNode practitioner(String id, ProviderDetail provider) {
        ObjectNode practitioner = JSON.objectNode();
        practitioner.put("resourceType", "Practitioner");
        practitioner.put("id", id);
        if (Texts.present(provider.npi())) {
            practitioner.putArray("identifier").addObject().put("system", NPI_SYSTEM).put("value", provider.npi().strip());
        }
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
