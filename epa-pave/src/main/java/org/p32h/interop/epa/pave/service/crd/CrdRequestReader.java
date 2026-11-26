package org.p32h.interop.epa.pave.service.crd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.p32h.interop.epa.pave.model.IsoDates;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.service.compare.Paths;
import org.p32h.interop.epa.pave.service.compare.ReferenceText;
import org.p32h.interop.epa.pave.service.compare.ResourceIndex;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import tools.jackson.databind.JsonNode;

/**
 * Reads the case out of a CRD request somebody else wrote: the hook, the member, the provider,
 * the orders and their date.
 *
 * <p>It exists so that this tool can build its own request for the same case and compare the
 * two. It reads first from the places {@link CaseResources} writes to, which is what makes a
 * request this tool built read back as the case it was built from, and then from the other
 * places a sender commonly uses.
 *
 * <p>It never guesses. A resource id and {@code context.patientId} are labels and are never
 * offered as a member ID. A value found in two places that do not agree is not used at all: both
 * are reported with their places, for the tester to choose. The line of business is never read,
 * because no element this tool writes carries it, and a plan name is not a line of business.
 * Every value comes with the place it was read from.
 */
public final class CrdRequestReader {

    /** The values of a case, under the names the form gives them. */
    public enum CaseField {
        HOOK("hook", "Hook"),
        MEMBER_ID("member.memberId", "Member ID"),
        MEMBER_FIRST_NAME("member.firstName", "Member's given name"),
        MEMBER_LAST_NAME("member.lastName", "Member's family name"),
        MEMBER_DATE_OF_BIRTH("member.dateOfBirth", "Member's date of birth"),
        MEMBER_GENDER("member.gender", "Member's gender"),
        SUBSCRIBER_ID("member.subscriberId", "Subscriber ID"),
        PLAN_NAME("member.planName", "Plan"),
        COVERAGE_STATUS("member.coverageStatus", "Coverage status"),
        LINE_OF_BUSINESS("member.lineOfBusiness", "Line of business"),
        NPI("provider.npi", "NPI"),
        PROVIDER_FIRST_NAME("provider.firstName", "Provider's given name"),
        PROVIDER_LAST_NAME("provider.lastName", "Provider's family name"),
        PROVIDER_NAME_TEXT("provider.organizationName", "Provider's name as one text"),
        PROCEDURE_CODES("procedureCodes", "Procedure codes"),
        DATE_OF_SERVICE("dateOfService", "Date of service");

        private final String key;
        private final String label;

        CaseField(String key, String label) {
            this.key = key;
            this.label = label;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One value read from the request.
     *
     * @param path    where it was read, written with list positions
     * @param pointer the JSON Pointer of that place
     * @param note    anything to know about how the value was read, or null
     */
    public record Found(String value, String path, String pointer, String note) {

        /** Prints the place and never the value, which can be a member ID or a name. */
        @Override
        public String toString() {
            return "Found[" + path + "]";
        }
    }

    /**
     * A value that was looked for and not used.
     *
     * @param lookedIn   where it was looked for, or null when there is no place it could be read from
     * @param note       why nothing was used, when there is more to say than that it is not there
     * @param candidates the values found, when they do not agree. Empty otherwise
     */
    public record Missing(String lookedIn, String note, List<Found> candidates) {

        public Missing {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /**
     * One order of the request.
     *
     * @param path         where the order resource sits
     * @param code         the code of the first coding that has one, or null when the order carries none
     * @param codingPath   where the coding that code was read from sits
     * @param system       the system of that coding, or null when it states none
     * @param display      the display of that coding, or null
     * @param otherCodings how many further codings with a code the order carries
     * @param date         the date of service on this order, as a date, or null
     */
    public record Order(String resourceType, String path, String pointer, Found code, String codingPath, Found system, Found display,
                        int otherCodings, Found date) {

        /** Prints the kind of order and its place, never what it holds. */
        @Override
        public String toString() {
            return "Order[" + resourceType + " at " + path + "]";
        }
    }

    /**
     * Everything read from one request.
     *
     * @param found   the values that were read
     * @param missing the values that were looked for and not used, each with where it was looked for
     * @param orders  the orders in {@code context.draftOrders}, in their order
     * @param notes   facts about the request that bear on reading it
     * @param patient      the JSON Pointer of the Patient the member was read from, or null when none was
     * @param practitioner the JSON Pointer of the resource the provider was read from, or null when none was: the
     *                     Practitioner, or the Organization when the PractitionerRole names an organization and no
     *                     practitioner, which is how this tool sends a provider known only by an organization name
     */
    public record Reading(Map<CaseField, Found> found, Map<CaseField, Missing> missing, List<Order> orders, List<String> notes,
                          String patient, String practitioner) {

        public Reading {
            // Copied by putAll: the copy constructor of an EnumMap refuses an empty map of another kind.
            Map<CaseField, Found> foundCopy = new EnumMap<>(CaseField.class);
            foundCopy.putAll(found);
            Map<CaseField, Missing> missingCopy = new EnumMap<>(CaseField.class);
            missingCopy.putAll(missing);
            found = Collections.unmodifiableMap(foundCopy);
            missing = Collections.unmodifiableMap(missingCopy);
            orders = List.copyOf(orders);
            notes = List.copyOf(notes);
        }

        public Found found(CaseField field) {
            return found.get(field);
        }

        public String value(CaseField field) {
            Found value = found.get(field);
            return value == null ? null : value.value();
        }

        /** The orders that carry a code, which are the ones this tool can build an order for. */
        public List<Order> ordersWithCode() {
            return orders.stream().filter(order -> order.code() != null).toList();
        }

        /** Prints what was read by name and never a value. */
        @Override
        public String toString() {
            return "Reading[found=" + found.keySet() + ", orders=" + orders.size() + "]";
        }
    }

    /** The order resources this tool reads a procedure code from, each with the element that holds the code. */
    static final Map<String, String> CODE_ELEMENT = Map.of("ServiceRequest", "code", "DeviceRequest", "codeCodeableConcept",
            "MedicationRequest", "medicationCodeableConcept");
    /** The order resources whose occurrence is the date the service is to be performed. */
    private static final Set<String> HAS_OCCURRENCE = Set.of("ServiceRequest", "DeviceRequest");
    /** The other resources CRD accepts as an order. No procedure code is read from them. */
    private static final Set<String> OTHER_ORDERS = Set.of("NutritionOrder", "VisionPrescription", "CommunicationRequest", "Appointment");
    private static final String COVERAGE_CLASS = "http://terminology.hl7.org/CodeSystem/coverage-class";
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T.+");
    static final String NAMES_ANOTHER_PATIENT = "context.patientId names a Patient your request does not carry.";

    private final Map<CaseField, Found> found = new EnumMap<>(CaseField.class);
    private final Map<CaseField, Missing> missing = new EnumMap<>(CaseField.class);
    private final List<String> notes = new ArrayList<>();
    private final JsonNode root;
    private final ResourceIndex index;
    private String patientPointer;
    private String practitionerPointer;

    private CrdRequestReader(JsonNode root) {
        this.root = root;
        this.index = ResourceIndex.of(root, CrdRequestGlossary.RULES);
    }

    /** Reads a request. Never throws, whatever the document holds: what cannot be read is reported as missing. */
    public static Reading read(JsonNode root) {
        CrdRequestReader reader = new CrdRequestReader(root);
        reader.hook();
        reader.member();
        reader.provider();
        List<Order> orders = reader.orders();
        reader.missing.put(CaseField.LINE_OF_BUSINESS, new Missing(null, "A CRD request does not carry the line of business in any "
                + "element this tool writes, so it is never read from a request. A plan name is not taken for one.", List.of()));
        return new Reading(reader.found, reader.missing, orders, reader.notes, reader.patientPointer, reader.practitionerPointer);
    }

    // ---- the hook ----

    private void hook() {
        JsonNode hook = JsonSafe.child(root, "hook");
        if (hook.isString() && !hook.stringValue().isBlank()) {
            found.put(CaseField.HOOK, new Found(hook.stringValue(), "hook", "/hook", null));
        } else {
            missing.put(CaseField.HOOK, new Missing("hook", null, List.of()));
        }
    }

    // ---- the member ----

    private void member() {
        List<ResourceIndex.Resource> coverages = index.resources("Coverage");
        String noPatient = null;
        ResourceIndex.Resource patient = null;
        List<ResourceIndex.Resource> patients = index.resources("Patient");
        JsonNode patientId = JsonSafe.at(root, "context", "patientId");
        // Only a request that does not name its patient is read from its only Patient. One that
        // names another is not: that Patient is then not the one the request is about.
        boolean named = !JsonSafe.nullOrMissing(patientId);
        if (named && patientId.isString() && !patientId.stringValue().isBlank()) {
            List<ResourceIndex.Resource> targets = index.resolve(ReferenceText.parse(patientId.stringValue(), "Patient")).stream()
                    .filter(resource -> resource.type().equals("Patient")).toList();
            if (targets.size() == 1) {
                patient = targets.get(0);
            } else {
                noPatient = targets.isEmpty() ? NAMES_ANOTHER_PATIENT
                        : "context.patientId names " + targets.size() + " Patient resources in your request, which carry the same id, so which "
                                + "one is the member cannot be told.";
            }
        } else if (named) {
            noPatient = "context.patientId is not written as an id, so the Patient it names cannot be told.";
        } else if (patients.size() == 1) {
            patient = patients.get(0);
        } else {
            noPatient = patients.isEmpty() ? "Your request carries no Patient resource."
                    : "Your request carries " + patients.size() + " Patient resources and no context.patientId to name one of them.";
        }
        patientPointer = patient == null ? null : patient.pointer();

        memberId(patient, coverages);
        if (patient == null && named && found.containsKey(CaseField.MEMBER_ID)) {
            // Read from a Coverage, and the Coverage is not tied to the patient the request names.
            found.put(CaseField.MEMBER_ID, withNote(found.get(CaseField.MEMBER_ID), noPatient + " The member ID was read from a Coverage, "
                    + "and nothing shows that the Coverage is that patient's."));
        }
        if (patient == null) {
            for (CaseField field : List.of(CaseField.MEMBER_FIRST_NAME, CaseField.MEMBER_LAST_NAME, CaseField.MEMBER_DATE_OF_BIRTH,
                    CaseField.MEMBER_GENDER)) {
                missing.put(field, new Missing("the Patient", noPatient, List.of()));
            }
        } else {
            JsonNode name = JsonSafe.elements(patient.node(), "name").stream().findFirst().orElse(null);
            String namePath = patient.path() + ".name[0]";
            String namePointer = Paths.element(Paths.member(patient.pointer(), "name"), 0);
            take(CaseField.MEMBER_LAST_NAME, text(name, "family", namePath, namePointer), "Patient.name[0].family");
            take(CaseField.MEMBER_FIRST_NAME, first(name, "given", namePath, namePointer), "Patient.name[0].given[0]");
            take(CaseField.MEMBER_DATE_OF_BIRTH, text(patient.node(), "birthDate", patient.path(), patient.pointer()), "Patient.birthDate");
            take(CaseField.MEMBER_GENDER, text(patient.node(), "gender", patient.path(), patient.pointer()), "Patient.gender");
        }

        agree(CaseField.SUBSCRIBER_ID, each(coverages, coverage -> text(coverage.node(), "subscriberId", coverage.path(), coverage.pointer())),
                "Coverage.subscriberId", Function.identity(), "subscriber IDs");
        agree(CaseField.COVERAGE_STATUS, each(coverages, coverage -> text(coverage.node(), "status", coverage.path(), coverage.pointer())),
                "Coverage.status", Function.identity(), "coverage statuses");
        List<Found> plans = new ArrayList<>();
        for (ResourceIndex.Resource coverage : coverages) {
            List<JsonNode> classes = JsonSafe.elements(coverage.node(), "class");
            for (int i = 0; i < classes.size(); i++) {
                if (typed(classes.get(i), "plan", COVERAGE_CLASS)) {
                    add(plans, text(classes.get(i), "value", coverage.path() + ".class[" + i + "]",
                            Paths.element(Paths.member(coverage.pointer(), "class"), i)));
                }
            }
        }
        agree(CaseField.PLAN_NAME, plans, "the value of a Coverage.class typed plan", Function.identity(), "plans");
    }

    /**
     * The member ID: first from an identifier typed as a member number on the Patient or on a
     * Coverage, which is where this tool writes it. Only when there is none of those, from the
     * subscriber id and the other identifiers of a Coverage. The two sets are never mixed: a
     * subscriber id is a different thing from a member ID wherever both are stated.
     */
    private void memberId(ResourceIndex.Resource patient, List<ResourceIndex.Resource> coverages) {
        List<Found> typed = new ArrayList<>();
        List<Found> others = new ArrayList<>();
        if (patient != null) {
            identifiers(patient, typed, null);
        }
        for (ResourceIndex.Resource coverage : coverages) {
            identifiers(coverage, typed, others);
            add(others, text(coverage.node(), "subscriberId", coverage.path(), coverage.pointer()));
        }
        agree(CaseField.MEMBER_ID, typed.isEmpty() ? others : typed,
                "an identifier typed as a member number (MB) on the Patient or a Coverage, then Coverage.subscriberId and the other "
                        + "identifiers of a Coverage", MemberIds::noSpace, "member IDs");
        Found read = found.get(CaseField.MEMBER_ID);
        if (read != null && typed.isEmpty()) {
            found.put(CaseField.MEMBER_ID, withNote(read, read.path().endsWith(".subscriberId")
                    ? "Read from Coverage.subscriberId, because no identifier is typed as a member number. Coverage.subscriberId is the "
                            + "subscriber's ID, which for a dependent can differ from the member's own."
                    : "Read from an identifier of a Coverage that is not typed as a member number, because no identifier is."));
        }
    }

    private static Found withNote(Found value, String note) {
        return new Found(value.value(), value.path(), value.pointer(), value.note() == null ? note : value.note() + " " + note);
    }

    /**
     * @param typed   receives the identifiers typed as a member number
     * @param untyped receives every other identifier, or is null to leave them out
     */
    private void identifiers(ResourceIndex.Resource resource, List<Found> typed, List<Found> untyped) {
        List<JsonNode> identifiers = JsonSafe.elements(resource.node(), "identifier");
        for (int i = 0; i < identifiers.size(); i++) {
            Found value = text(identifiers.get(i), "value", resource.path() + ".identifier[" + i + "]",
                    Paths.element(Paths.member(resource.pointer(), "identifier"), i));
            if (typed(identifiers.get(i), "MB", CaseResources.IDENTIFIER_TYPES)) {
                add(typed, value);
            } else if (untyped != null) {
                add(untyped, value);
            }
        }
    }

    /** True when the element's type carries the code, in the given system or with no system stated. */
    private static boolean typed(JsonNode element, String code, String system) {
        for (JsonNode coding : JsonSafe.elements(JsonSafe.child(element, "type"), "coding")) {
            String stated = JsonSafe.presentText(coding, "system");
            if (code.equals(JsonSafe.presentText(coding, "code")) && (stated == null || stated.equals(system))) {
                return true;
            }
        }
        return false;
    }

    // ---- the provider ----

    private void provider() {
        String lookedIn = "the Practitioner that context.userId leads to";
        String why = null;
        ResourceIndex.Resource practitioner = null;
        ResourceIndex.Resource organization = null;
        JsonNode userId = JsonSafe.at(root, "context", "userId");
        if (!userId.isString() || userId.stringValue().isBlank()) {
            why = "Your request has no context.userId.";
        } else {
            ReferenceText user = ReferenceText.parse(userId.stringValue(), null);
            if (user.type() == null) {
                why = "context.userId is not written as a resource type and an id.";
            } else if (index.resolve(user).size() > 1) {
                why = "context.userId names " + index.resolve(user).size() + " resources in your request, which carry the same id, so which "
                        + "one is meant cannot be told.";
            } else if (user.type().equals("Practitioner")) {
                practitioner = index.resolve(user).stream().findFirst().orElse(null);
                why = practitioner == null ? "context.userId names a Practitioner that your request does not carry." : null;
            } else if (user.type().equals("PractitionerRole")) {
                ResourceIndex.Resource role = index.resolve(user).stream().findFirst().orElse(null);
                JsonNode reference = role == null ? null : JsonSafe.at(role.node(), "practitioner", "reference");
                JsonNode organizationReference = role == null ? null : JsonSafe.at(role.node(), "organization", "reference");
                if (role == null) {
                    why = "context.userId names a PractitionerRole that your request does not carry.";
                } else if (!reference.isString() && organizationReference.isString()) {
                    // A role that names an organization and no person: the provider is the organization.
                    List<ResourceIndex.Resource> named = index.resolve(ReferenceText.parse(organizationReference.stringValue(), null)).stream()
                            .filter(resource -> resource.type().equals("Organization")).toList();
                    organization = named.size() == 1 ? named.get(0) : null;
                    why = named.isEmpty() ? "The PractitionerRole that context.userId names has no practitioner and points at an Organization "
                            + "that your request does not carry." : named.size() > 1 ? "The PractitionerRole that context.userId names points "
                            + "at " + named.size() + " Organization resources in your request, which carry the same id, so which one is meant "
                            + "cannot be told." : null;
                } else if (!reference.isString()) {
                    why = "The PractitionerRole that context.userId names has no reference to a practitioner.";
                } else {
                    List<ResourceIndex.Resource> named = index.resolve(ReferenceText.parse(reference.stringValue(), null)).stream()
                            .filter(resource -> resource.type().equals("Practitioner")).toList();
                    practitioner = named.size() == 1 ? named.get(0) : null;
                    why = named.isEmpty() ? "The PractitionerRole that context.userId names points at a Practitioner that your request does "
                            + "not carry." : named.size() > 1 ? "The PractitionerRole that context.userId names points at " + named.size()
                            + " Practitioner resources in your request, which carry the same id, so which one is meant cannot be told." : null;
                }
            } else {
                why = "context.userId names a resource that is neither a Practitioner nor a PractitionerRole.";
            }
        }
        if (organization != null) {
            organizationProvider(organization);
            return;
        }
        practitionerPointer = practitioner == null ? null : practitioner.pointer();
        if (practitioner == null) {
            for (CaseField field : List.of(CaseField.NPI, CaseField.PROVIDER_LAST_NAME, CaseField.PROVIDER_FIRST_NAME, CaseField.PROVIDER_NAME_TEXT)) {
                missing.put(field, new Missing(lookedIn, why, List.of()));
            }
            return;
        }
        List<Found> npis = new ArrayList<>();
        List<JsonNode> identifiers = JsonSafe.elements(practitioner.node(), "identifier");
        for (int i = 0; i < identifiers.size(); i++) {
            if (CaseResources.NPI_SYSTEM.equals(JsonSafe.presentText(identifiers.get(i), "system"))) {
                add(npis, text(identifiers.get(i), "value", practitioner.path() + ".identifier[" + i + "]",
                        Paths.element(Paths.member(practitioner.pointer(), "identifier"), i)));
            }
        }
        agree(CaseField.NPI, npis, "an identifier with the NPI system on " + lookedIn, Function.identity(), "NPIs");
        JsonNode name = JsonSafe.elements(practitioner.node(), "name").stream().findFirst().orElse(null);
        String namePath = practitioner.path() + ".name[0]";
        String namePointer = Paths.element(Paths.member(practitioner.pointer(), "name"), 0);
        take(CaseField.PROVIDER_LAST_NAME, text(name, "family", namePath, namePointer), "name[0].family of " + lookedIn);
        take(CaseField.PROVIDER_FIRST_NAME, first(name, "given", namePath, namePointer), "name[0].given[0] of " + lookedIn);
        take(CaseField.PROVIDER_NAME_TEXT, text(name, "text", namePath, namePointer), "name[0].text of " + lookedIn);
    }

    /** The provider read from an Organization: its NPI and its name. It has no person's name to read. */
    private void organizationProvider(ResourceIndex.Resource organization) {
        practitionerPointer = organization.pointer();
        String lookedIn = "the Organization that the PractitionerRole of context.userId names";
        List<Found> npis = new ArrayList<>();
        List<JsonNode> identifiers = JsonSafe.elements(organization.node(), "identifier");
        for (int i = 0; i < identifiers.size(); i++) {
            if (CaseResources.NPI_SYSTEM.equals(JsonSafe.presentText(identifiers.get(i), "system"))) {
                add(npis, text(identifiers.get(i), "value", organization.path() + ".identifier[" + i + "]",
                        Paths.element(Paths.member(organization.pointer(), "identifier"), i)));
            }
        }
        agree(CaseField.NPI, npis, "an identifier with the NPI system on " + lookedIn, Function.identity(), "NPIs");
        take(CaseField.PROVIDER_NAME_TEXT, text(organization.node(), "name", organization.path(), organization.pointer()),
                "Organization.name of " + lookedIn);
        for (CaseField field : List.of(CaseField.PROVIDER_LAST_NAME, CaseField.PROVIDER_FIRST_NAME)) {
            missing.put(field, new Missing(lookedIn, "The provider is an Organization, which has no person's name.", List.of()));
        }
    }

    // ---- the orders ----

    private List<Order> orders() {
        List<Order> orders = new ArrayList<>();
        String codesLookedIn = "the orders in context.draftOrders: ServiceRequest.code, DeviceRequest.codeCodeableConcept and "
                + "MedicationRequest.medicationCodeableConcept";
        String datesLookedIn = "occurrenceDateTime, then occurrencePeriod.start, on each ServiceRequest and DeviceRequest in context.draftOrders";
        List<JsonNode> entries = JsonSafe.elements(JsonSafe.at(root, "context", "draftOrders"), "entry");
        List<Found> dates = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            JsonNode resource = JsonSafe.child(entries.get(i), "resource");
            String type = ResourceIndex.resourceType(resource);
            String path = "context.draftOrders.entry[" + i + "].resource";
            String pointer = "/context/draftOrders/entry/" + i + "/resource";
            if (type == null) {
                continue;
            }
            if (OTHER_ORDERS.contains(type)) {
                notes.add("This tool does not read a procedure code from " + article(type) + ", which is what " + path + " is.");
            }
            String codeElement = CODE_ELEMENT.get(type);
            if (codeElement == null) {
                continue;
            }
            Found code = null;
            String codingPath = null;
            Found system = null;
            Found display = null;
            int others = 0;
            List<JsonNode> codings = JsonSafe.elements(JsonSafe.child(resource, codeElement), "coding");
            for (int k = 0; k < codings.size(); k++) {
                String value = JsonSafe.presentText(codings.get(k), "code");
                if (value == null) {
                    continue;
                }
                if (code == null) {
                    codingPath = path + "." + codeElement + ".coding[" + k + "]";
                    String codingPointer = Paths.element(Paths.member(Paths.member(pointer, codeElement), "coding"), k);
                    code = new Found(value, codingPath + ".code", Paths.member(codingPointer, "code"), null);
                    system = text(codings.get(k), "system", codingPath, codingPointer);
                    display = text(codings.get(k), "display", codingPath, codingPointer);
                } else {
                    others++;
                }
            }
            if (code == null) {
                notes.add("No code was found on the " + type + " at " + path + ". It was looked for in " + codeElement + ".coding.");
            } else if (others > 0) {
                notes.add("The " + type + " at " + path + " carries " + (others + 1) + " codings with a code. This tool sends one coding for "
                        + "each order, so the first was read and the rest were not.");
            }
            Found date = HAS_OCCURRENCE.contains(type) ? date(resource, path, pointer) : null;
            add(dates, date);
            orders.add(new Order(type, path, pointer, code, codingPath, system, display, others, date));
        }
        if (orders.stream().noneMatch(order -> order.code() != null)) {
            missing.put(CaseField.PROCEDURE_CODES, new Missing(codesLookedIn, entries.isEmpty() ? "Your request has no entries in "
                    + "context.draftOrders." : null, List.of()));
        }
        agree(CaseField.DATE_OF_SERVICE, dates, datesLookedIn, Function.identity(), "dates of service");
        return orders;
    }

    /**
     * The date of service on one order, as the date this tool would send. A date and time gives
     * its date. Anything that is not a full date is reported and not used: this tool sends full
     * dates only, and what is meant by half of one would be a guess.
     */
    private Found date(JsonNode resource, String path, String pointer) {
        Found written = text(resource, "occurrenceDateTime", path, pointer);
        if (written == null) {
            written = text(JsonSafe.child(resource, "occurrencePeriod"), "start", path + ".occurrencePeriod", Paths.member(pointer, "occurrencePeriod"));
        }
        if (written == null) {
            return null;
        }
        if (IsoDates.valid(written.value())) {
            return written;
        }
        Matcher dateTime = DATE_TIME.matcher(written.value());
        if (dateTime.matches() && IsoDates.valid(dateTime.group(1))) {
            return new Found(dateTime.group(1), written.path(), written.pointer(), "Your request holds a date and time here. This tool sends "
                    + "the date of service as a date, so the date was used.");
        }
        notes.add("The value at " + written.path() + " is not a full date written as YYYY-MM-DD, so it was not used as the date of service.");
        return null;
    }

    // ---- reading and agreeing ----

    private static String article(String resourceType) {
        return ("AEIOU".indexOf(resourceType.charAt(0)) >= 0 ? "an " : "a ") + resourceType;
    }

    /** The text of a member of an object, with its place, or null when there is none or it is blank. */
    private static Found text(JsonNode object, String key, String path, String pointer) {
        JsonNode value = JsonSafe.child(object, key);
        String text = value.isString() ? JsonSafe.presentText(value) : null;
        return text == null ? null : new Found(text, path + "." + key, Paths.member(pointer, key), null);
    }

    /** The first element of a list of texts, with its place. */
    private static Found first(JsonNode object, String key, String path, String pointer) {
        List<JsonNode> elements = JsonSafe.elements(object, key);
        String text = elements.isEmpty() || !elements.get(0).isString() ? null : JsonSafe.presentText(elements.get(0));
        return text == null ? null : new Found(text, path + "." + key + "[0]", Paths.element(Paths.member(pointer, key), 0), null);
    }

    private static List<Found> each(List<ResourceIndex.Resource> resources, Function<ResourceIndex.Resource, Found> read) {
        List<Found> out = new ArrayList<>();
        resources.forEach(resource -> add(out, read.apply(resource)));
        return out;
    }

    private static void add(List<Found> list, Found value) {
        if (value != null) {
            list.add(value);
        }
    }

    private void take(CaseField field, Found value, String lookedIn) {
        if (value != null) {
            found.put(field, value);
        } else {
            missing.put(field, new Missing(lookedIn, null, List.of()));
        }
    }

    /**
     * Uses a value only when every place it was found in agrees.
     *
     * @param sameAs what makes two values the same value, for example a member ID without its spaces
     * @param plural the values in words, for the note that says they disagree
     */
    private void agree(CaseField field, List<Found> candidates, String lookedIn, Function<String, String> sameAs, String plural) {
        Map<String, Found> distinct = new LinkedHashMap<>();
        candidates.forEach(candidate -> distinct.putIfAbsent(sameAs.apply(candidate.value()), candidate));
        if (distinct.size() == 1) {
            found.put(field, candidates.get(0));
        } else if (distinct.isEmpty()) {
            missing.put(field, new Missing(lookedIn, null, List.of()));
        } else {
            missing.put(field, new Missing(lookedIn, "Your request holds " + distinct.size() + " different " + plural + ", so none was used. "
                    + "Each is listed with its place. Type the one you mean on the form.", candidates));
        }
    }
}
