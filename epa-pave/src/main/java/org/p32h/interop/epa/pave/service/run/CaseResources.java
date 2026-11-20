package org.p32h.interop.epa.pave.service.run;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.MemberDetail;
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
        return new Reads(variables);
    }

    /** See {@link #reading}. Used for one body. */
    public static final class Reads implements Function<String, ResolvedText> {

        private final Function<String, ResolvedText> variables;
        private final Map<String, String> secrets = new java.util.LinkedHashMap<>();
        private final List<org.p32h.interop.epa.pave.model.VariableUse> uses = new java.util.ArrayList<>();

        private Reads(Function<String, ResolvedText> variables) {
            this.variables = variables;
        }

        @Override
        public ResolvedText apply(String template) {
            ResolvedText resolved = variables == null ? null : variables.apply(template);
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

    public static String value(Function<String, ResolvedText> variables, String name) {
        ResolvedText resolved = variables == null ? null : variables.apply("{{" + name + "}}");
        return resolved != null && resolved.resolved() ? Texts.trimToNull(resolved.value()) : null;
    }

    /**
     * An order as CRD 2.0.1 profile-servicerequest has it, written now and with HCPCS under the
     * address that profile's value set names. DTR sends its orders through this form.
     */
    public ObjectNode serviceRequest(String id, CodeInput code, MemberDetail member, String dateOfService, List<String> notes) {
        String authoredOn = now();
        addOnce(notes, authoredOnNote(authoredOn));
        return serviceRequest(id, code, member, dateOfService, authoredOn, HCPCS_CRD_201, notes);
    }

    /**
     * An order as CRD 2.0.1 profile-servicerequest has it: status draft, intent order, the code, the
     * patient, authoredOn and the requester, all of which the profile requires.
     *
     * @param authoredOn  when the order is written. Nobody supplies it, so the caller passes the
     *                    moment the request is built and says so in a note ({@link #authoredOnNote})
     * @param hcpcsSystem the address a HCPCS Level II code is sent with
     */
    public ObjectNode serviceRequest(String id, CodeInput code, MemberDetail member, String dateOfService, String authoredOn,
                                     String hcpcsSystem, List<String> notes) {
        ObjectNode resource = JSON.objectNode();
        resource.put("resourceType", "ServiceRequest");
        resource.put("id", id);
        resource.put("status", "draft");
        resource.put("intent", "order");
        resource.set("code", codeableConcept(code, member == null ? null : member.lineOfBusiness(), notes, hcpcsSystem));
        resource.putObject("subject").put("reference", PATIENT_REF);
        if (Texts.present(dateOfService)) {
            resource.put("occurrenceDateTime", dateOfService.strip());
        }
        resource.put("authoredOn", authoredOn);
        resource.putObject("requester").put("reference", PRACTITIONER_ROLE_REF);
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

    /** A procedure code as a CodeableConcept with one coding, as a claim item carries it, HCPCS under {@link #HCPCS}. */
    public ObjectNode codeableConcept(CodeInput code, String lineOfBusiness, List<String> notes) {
        return codeableConcept(code, lineOfBusiness, notes, HCPCS);
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
        ObjectNode patient = JSON.objectNode();
        patient.put("resourceType", "Patient");
        patient.put("id", PATIENT_ID);
        ArrayNode identifiers = patient.putArray("identifier");
        identifiers.add(memberIdentifier(member, variables, notes, false));
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
        coverage.putArray("identifier").add(memberIdentifier(member, variables, notes, false));
        if (Texts.present(member.subscriberId())) {
            coverage.put("subscriberId", member.subscriberId().strip());
        }
        coverage.putObject("beneficiary").put("reference", PATIENT_REF);
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
        if (!person && Texts.present(provider.organizationName())) {
            ObjectNode organization = JSON.objectNode();
            organization.put("resourceType", "Organization");
            organization.put("id", ORGANIZATION_ID);
            organization.putArray("identifier").addObject().put("system", NPI_SYSTEM).put("value", provider.npi().strip());
            organization.put("name", provider.organizationName().strip());
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
        role.putObject("practitioner").put("reference", PRACTITIONER_REF);
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
