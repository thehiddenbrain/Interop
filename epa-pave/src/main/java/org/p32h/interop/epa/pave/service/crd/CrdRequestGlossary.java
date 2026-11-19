package org.p32h.interop.epa.pave.service.crd;

import static org.p32h.interop.epa.pave.service.compare.ElementRole.BUILD;
import static org.p32h.interop.epa.pave.service.compare.ElementRole.CASE;
import static org.p32h.interop.epa.pave.service.compare.ElementRole.EVERY_CALL;
import static org.p32h.interop.epa.pave.service.compare.ElementRole.LABEL;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.p32h.interop.epa.pave.model.MemberIds;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.compare.DifferenceKind;
import org.p32h.interop.epa.pave.service.compare.CompareRules;
import org.p32h.interop.epa.pave.service.compare.ElementPath;
import org.p32h.interop.epa.pave.service.compare.ElementRole;
import org.p32h.interop.epa.pave.service.compare.Glossary;
import org.p32h.interop.epa.pave.service.run.CaseResources;

/**
 * What each element of a CRD request is, and which part it plays.
 *
 * <p>A CRD request is a CDS Hooks request that carries FHIR resources, so there are three layers
 * here: the hook request itself, the resources a CRD request carries with the elements that
 * matter to a coverage decision, and the shapes every FHIR resource shares.
 *
 * <p>Everything is written in this tool's own words from what its author is sure of. An element
 * that is not here is not described, and a difference in it is reported as not classified. That
 * is deliberate: a tester repeats these sentences to a vendor, so a wrong one is worse than a
 * missing one. A role is left out, and the description kept, where what an element is is certain
 * and which part it plays depends on the request.
 *
 * <p>The remarks say how this tool builds its own request, which is {@link CrdRequestBuilder} and
 * {@link CaseResources}. They are to be kept in step with those two, and a test compares them.
 */
public final class CrdRequestGlossary implements Glossary {

    /**
     * What the engine cannot know about a hook request from JSON and FHIR alone: prefetch is a map
     * whose keys the service chooses, and the context names resources by text that is not a FHIR
     * Reference. An id on its own stands for a Patient or an Encounter by where it sits. An order
     * says what was ordered in the element the reader reads its code from, and fhirServer names
     * the server the ids of the request belong to.
     */
    public static final CompareRules RULES = new CompareRules(Set.of("prefetch"),
            Map.of("context.patientId", "Patient", "context.encounterId", "Encounter", "context.userId", "", "context.selections", ""),
            CrdRequestReader.CODE_ELEMENT, "fhirServer");

    static final String PREFETCH_KEYS = "A CDS service names the prefetch keys it asks for in its discovery response. GET "
            + "/api/crd/discover lists the keys the service names.";

    static final String PREFETCH_SHAPES = "In CDS Hooks a prefetch entry is the answer to a FHIR read or search: a read gives the "
            + "resource itself and a search gives a Bundle. GET /api/crd/discover shows the query the service names for each key.";

    /** The hook request itself, by its path from the top of the document. */
    private static final Map<String, Entry> DOCUMENT = new HashMap<>();
    /** The members of a free-keyed object, used when the member is not a resource this glossary describes. */
    private static final Map<String, Entry> ANY_KEY = new HashMap<>();
    /** The resources, by resource type and path. */
    private static final Map<String, Entry> FHIR = new HashMap<>();

    private static void document(ElementRole role, String path, String what) {
        DOCUMENT.put(path, new Entry(what, role));
    }

    private static void fhir(ElementRole role, String path, String what) {
        FHIR.put(path, new Entry(what, role));
    }

    static {
        // ---- the CDS Hooks request ----
        document(BUILD, "hook", "The name of the CDS Hooks event the request is for, such as order-sign or order-select.");
        document(EVERY_CALL, "hookInstance", "An id for this one call of the hook, which the sender makes anew for every call.");
        document(BUILD, "fhirServer", "The base url of the sender's FHIR server, where a service can fetch what the request does not carry.");
        document(BUILD, "fhirAuthorization", "The access the sender gives a service to its FHIR server: a token and what the token allows.");
        document(null, "fhirAuthorization.access_token", "The token a service presents to the sender's FHIR server.");
        document(BUILD, "fhirAuthorization.token_type", "The kind of token, which CDS Hooks fixes as Bearer.");
        document(null, "fhirAuthorization.expires_in", "How many seconds the token stays valid.");
        document(null, "fhirAuthorization.scope", "What the token allows a service to do on the sender's FHIR server.");
        document(null, "fhirAuthorization.subject", "The client id the service is registered under with the sender's authorization server.");
        document(BUILD, "context", "What the hook is about: who is ordering, for which patient, and the orders themselves.");
        document(LABEL, "context.userId", "The clinician using the sender's system, written as a resource type and an id.");
        document(LABEL, "context.patientId", "The FHIR id of the patient the orders are for, which is a label and not a member ID.");
        document(LABEL, "context.encounterId", "The FHIR id of the encounter the orders belong to.");
        document(BUILD, "context.selections", "For order-select, which of the draft orders were just selected, each written as a resource type and an id.");
        document(BUILD, "context.draftOrders", "The orders being worked on, as a FHIR Bundle.");
        document(BUILD, "prefetch", "Resources the sender supplies up front so that the service does not have to fetch them, each under a key.");
        ANY_KEY.put("prefetch.*", new Entry("One entry of prefetch, under its key.", BUILD));

        // ---- the Bundle ----
        fhir(BUILD, "Bundle", "A FHIR Bundle: a set of resources sent together.");
        fhir(BUILD, "Bundle.type", "What kind of Bundle this is, for example collection or searchset.");
        fhir(BUILD, "Bundle.entry", "One entry of the Bundle, which holds one resource.");
        fhir(LABEL, "Bundle.entry.fullUrl", "The address of the resource in this entry, which a reference can point at.");
        fhir(BUILD, "Bundle.entry.resource", "The resource this entry holds.");
        fhir(null, "Bundle.total", "For a search result, how many resources matched.");
        fhir(BUILD, "Bundle.link", "A link that belongs to the Bundle, such as the search that produced it.");
        fhir(null, "Bundle.timestamp", "When the Bundle was put together.");

        // ---- the member ----
        fhir(BUILD, "Patient", "The member, as a FHIR Patient resource.");
        fhir(BUILD, "Patient.identifier", "One identifier of the member, such as the member ID.");
        fhir(CASE, "Patient.identifier.value", "The value of one identifier of the patient, such as a member ID or a record number.");
        fhir(CASE, "Patient.name", "One name of the member.");
        fhir(CASE, "Patient.birthDate", "The member's date of birth.");
        fhir(CASE, "Patient.gender", "The member's administrative gender: male, female, other or unknown.");
        fhir(null, "Patient.active", "Whether the patient record is in active use.");
        fhir(BUILD, "Patient.managingOrganization", "The organization that keeps the patient record, as a reference.");

        fhir(BUILD, "Coverage", "The member's coverage with a payer, as a FHIR Coverage resource.");
        fhir(CASE, "Coverage.status", "The status of the Coverage record: active, cancelled, draft or entered-in-error.");
        fhir(null, "Coverage.type", "The kind of coverage, as a code.");
        fhir(BUILD, "Coverage.identifier", "One identifier the payer gave the coverage, such as the member ID.");
        fhir(CASE, "Coverage.identifier.value", "The coverage identifier itself, which can be the member ID.");
        fhir(CASE, "Coverage.subscriberId", "The id of the subscriber to the coverage.");
        fhir(BUILD, "Coverage.subscriber", "The subscriber to the coverage, as a reference.");
        fhir(BUILD, "Coverage.policyHolder", "The owner of the policy, as a reference.");
        fhir(BUILD, "Coverage.beneficiary", "The person the coverage is for, as a reference to the Patient.");
        fhir(CASE, "Coverage.dependent", "An identifier that tells one covered person from another under the same coverage.");
        fhir(CASE, "Coverage.relationship", "How the member is related to the subscriber, for example self or spouse.");
        fhir(CASE, "Coverage.period", "The dates between which the coverage is in force.");
        fhir(CASE, "Coverage.period.start", "The date the coverage starts.");
        fhir(CASE, "Coverage.period.end", "The date the coverage ends.");
        fhir(BUILD, "Coverage.payor", "The payer of the coverage, as a reference or an identifier.");
        fhir(null, "Coverage.payor.display", "The payer's name.");
        fhir(BUILD, "Coverage.class", "One classification of the coverage, such as its plan or its group.");
        fhir(BUILD, "Coverage.class.type", "Which kind of classification this is, such as plan or group.");
        fhir(BUILD, "Coverage.class.type.coding.code", "The code for the kind of classification, such as plan or group.");
        fhir(CASE, "Coverage.class.value", "The value of the classification, for example the identifier of the plan.");
        fhir(CASE, "Coverage.class.name", "A readable name for the classification, for example the plan name.");
        fhir(null, "Coverage.order", "The order in which coverages apply when a member has more than one.");
        fhir(null, "Coverage.network", "The network the coverage gives access to.");

        // ---- the provider ----
        fhir(BUILD, "Practitioner", "A person who provides care, as a FHIR Practitioner resource.");
        fhir(BUILD, "Practitioner.identifier", "One identifier of the practitioner, such as the NPI.");
        fhir(CASE, "Practitioner.identifier.value", "The value of one identifier of the practitioner, such as an NPI.");
        fhir(CASE, "Practitioner.name", "One name of the practitioner.");
        fhir(CASE, "Practitioner.qualification", "A qualification the practitioner holds, such as a licence.");
        fhir(null, "Practitioner.active", "Whether the practitioner record is in active use.");

        fhir(BUILD, "PractitionerRole", "A practitioner in one role at one organization, as a FHIR PractitionerRole resource.");
        fhir(BUILD, "PractitionerRole.practitioner", "The practitioner who has this role, as a reference.");
        fhir(BUILD, "PractitionerRole.organization", "The organization the practitioner has this role at, as a reference.");
        fhir(BUILD, "PractitionerRole.location", "Where the practitioner works in this role, as a reference.");
        fhir(CASE, "PractitionerRole.specialty", "The practitioner's specialty in this role, as a code.");
        fhir(null, "PractitionerRole.code", "The role itself, as a code.");
        fhir(null, "PractitionerRole.active", "Whether the role record is in active use.");

        fhir(BUILD, "Organization", "An organization, such as a provider group or a payer, as a FHIR Organization resource.");
        fhir(null, "Organization.name", "The organization's name.");
        fhir(null, "Organization.type", "The kind of organization, as a code.");
        fhir(null, "Organization.active", "Whether the organization record is in active use.");
        fhir(BUILD, "Organization.partOf", "The larger organization this one belongs to, as a reference.");

        fhir(BUILD, "Location", "A place where care is given, as a FHIR Location resource.");
        fhir(CASE, "Location.name", "The name of the place.");
        fhir(null, "Location.type", "The kind of place, as a code.");
        fhir(null, "Location.status", "Whether the place is in use: active, suspended or inactive.");
        fhir(BUILD, "Location.managingOrganization", "The organization responsible for the place, as a reference.");

        fhir(BUILD, "Encounter", "The visit or stay the orders belong to, as a FHIR Encounter resource.");
        fhir(CASE, "Encounter.status", "Where the encounter stands, for example planned, in-progress or finished.");
        fhir(CASE, "Encounter.class", "The kind of encounter, for example ambulatory or inpatient.");
        fhir(BUILD, "Encounter.class.system", "The url of the code system the kind of encounter comes from.");
        fhir(CASE, "Encounter.class.code", "The code for the kind of encounter.");
        fhir(CASE, "Encounter.class.display", "The text that describes the kind of encounter.");
        fhir(null, "Encounter.type", "A more specific kind of encounter, as a code.");
        fhir(BUILD, "Encounter.subject", "The patient the encounter is for, as a reference.");
        fhir(CASE, "Encounter.period", "When the encounter starts and ends.");
        fhir(CASE, "Encounter.period.start", "When the encounter starts.");
        fhir(CASE, "Encounter.period.end", "When the encounter ends.");
        fhir(CASE, "Encounter.reasonCode", "Why the encounter takes place, as a code.");
        fhir(BUILD, "Encounter.serviceProvider", "The organization responsible for the encounter, as a reference.");

        // ---- the orders ----
        fhir(BUILD, "ServiceRequest", "An order for a service, such as a procedure or a test, as a FHIR ServiceRequest resource.");
        fhir(BUILD, "DeviceRequest", "An order for a device or equipment, as a FHIR DeviceRequest resource.");
        fhir(BUILD, "MedicationRequest", "An order for a medication, as a FHIR MedicationRequest resource.");
        fhir(BUILD, "NutritionOrder", "An order for a diet, a supplement or tube feeding, as a FHIR NutritionOrder resource.");
        fhir(BUILD, "VisionPrescription", "A prescription for glasses or contact lenses, as a FHIR VisionPrescription resource.");
        fhir(BUILD, "CommunicationRequest", "A request to pass information to someone, as a FHIR CommunicationRequest resource.");
        fhir(BUILD, "Appointment", "A booking of a visit or a service at a set time, as a FHIR Appointment resource.");

        // What several order resources share, under the element names each of them really has.
        for (String type : List.of("ServiceRequest", "DeviceRequest", "MedicationRequest", "NutritionOrder")) {
            fhir(BUILD, type + ".intent", "Whether this is an order, a plan or a proposal.");
        }
        for (String type : List.of("ServiceRequest", "DeviceRequest", "MedicationRequest", "NutritionOrder", "VisionPrescription",
                "CommunicationRequest")) {
            fhir(BUILD, type + ".status", "Where the order stands, for example draft for one that is not yet signed.");
            fhir(BUILD, type + ".encounter", "The encounter the order was made in, as a reference.");
        }
        for (String type : List.of("ServiceRequest", "DeviceRequest", "MedicationRequest", "CommunicationRequest")) {
            fhir(BUILD, type + ".subject", "The patient the order is for, as a reference.");
            fhir(BUILD, type + ".requester", "Who made the order, as a reference.");
            fhir(CASE, type + ".authoredOn", "When the order was written.");
            fhir(CASE, type + ".priority", "How urgent the order is: routine, urgent, asap or stat.");
            fhir(CASE, type + ".reasonCode", "Why the order was made, as a code such as a diagnosis.");
            fhir(CASE, type + ".reasonCode.coding", "One code for the reason, with the code system it comes from.");
            fhir(CASE, type + ".reasonCode.coding.code", "A code for the reason, such as a diagnosis code.");
            fhir(CASE, type + ".reasonCode.coding.display", "The text that describes the reason code.");
            fhir(CASE, type + ".reasonCode.text", "The reason, in words.");
            fhir(BUILD, type + ".reasonReference", "Why the order was made, as a reference to a condition or another resource.");
            fhir(CASE, type + ".note", "A comment on the order, in words.");
        }
        for (String type : List.of("ServiceRequest", "DeviceRequest", "MedicationRequest")) {
            fhir(BUILD, type + ".insurance", "The coverage that goes with the order, as a reference.");
            fhir(BUILD, type + ".performer", "Who is asked to carry the order out, as a reference.");
            fhir(CASE, type + ".performerType", "The kind of provider asked to carry the order out, as a code.");
            fhir(BUILD, type + ".basedOn", "The plan or order this one carries out, as a reference.");
        }
        for (String type : List.of("ServiceRequest", "DeviceRequest", "CommunicationRequest")) {
            fhir(CASE, type + ".occurrenceDateTime", "When the order is to be carried out.");
            fhir(CASE, type + ".occurrencePeriod", "The period in which the order is to be carried out.");
            fhir(CASE, type + ".occurrencePeriod.start", "The start of the period in which the order is to be carried out.");
            fhir(CASE, type + ".occurrencePeriod.end", "The end of the period in which the order is to be carried out.");
        }
        for (String type : List.of("ServiceRequest", "DeviceRequest")) {
            fhir(CASE, type + ".occurrenceTiming", "The schedule on which the order is to be carried out.");
            fhir(BUILD, type + ".supportingInfo", "Other information that supports the order, as a reference.");
        }
        coded("ServiceRequest.code", "what was ordered", "The procedure code that was ordered.");
        coded("DeviceRequest.codeCodeableConcept", "the device that was ordered", "The code of the device that was ordered.");
        coded("MedicationRequest.medicationCodeableConcept", "the medication that was ordered", "The code of the medication that was ordered.");

        fhir(null, "ServiceRequest.category", "The broad kind of service, as a code.");
        fhir(CASE, "ServiceRequest.doNotPerform", "True when the order says the service is not to be performed.");
        fhir(CASE, "ServiceRequest.orderDetail", "More about how the service is to be carried out, as a code.");
        fhir(CASE, "ServiceRequest.quantityQuantity", "How much of the service was ordered, as an amount.");
        fhir(CASE, "ServiceRequest.quantityQuantity.value", "The amount that was ordered.");
        fhir(CASE, "ServiceRequest.quantityQuantity.unit", "The unit the amount is counted in.");
        fhir(CASE, "ServiceRequest.quantityRatio", "How much of the service was ordered, as a ratio.");
        fhir(CASE, "ServiceRequest.quantityRange", "How much of the service was ordered, as a range.");
        fhir(CASE, "ServiceRequest.locationCode", "Where the service is to be performed, as a code.");
        fhir(BUILD, "ServiceRequest.locationReference", "Where the service is to be performed, as a reference.");
        fhir(CASE, "ServiceRequest.bodySite", "The part of the body the service applies to, as a code.");
        fhir(CASE, "ServiceRequest.patientInstruction", "Instructions for the patient, in words.");

        fhir(BUILD, "DeviceRequest.codeReference", "The device that was ordered, as a reference to a Device resource.");
        fhir(CASE, "DeviceRequest.parameter", "A detail of the device that was ordered, such as a setting.");

        fhir(BUILD, "MedicationRequest.medicationReference", "The medication that was ordered, as a reference to a Medication resource.");
        fhir(null, "MedicationRequest.category", "The setting the medication is to be used in, as a code.");
        fhir(CASE, "MedicationRequest.doNotPerform", "True when the order says the medication is not to be given.");
        fhir(BUILD, "MedicationRequest.supportingInformation", "Other information that supports the order, as a reference.");
        fhir(CASE, "MedicationRequest.dosageInstruction", "How the medication is to be taken.");
        fhir(CASE, "MedicationRequest.dispenseRequest", "What the pharmacy is asked to dispense: how much, how many refills and for how long.");
        fhir(CASE, "MedicationRequest.dispenseRequest.quantity", "How much is to be dispensed in one fill.");
        fhir(CASE, "MedicationRequest.dispenseRequest.numberOfRepeatsAllowed", "How many refills are allowed.");
        fhir(CASE, "MedicationRequest.dispenseRequest.expectedSupplyDuration", "How long one fill is expected to last.");
        fhir(CASE, "MedicationRequest.dispenseRequest.validityPeriod", "The period in which the prescription can be filled.");

        fhir(BUILD, "NutritionOrder.patient", "The patient the order is for, as a reference.");
        fhir(BUILD, "NutritionOrder.orderer", "Who made the order, as a reference.");
        fhir(CASE, "NutritionOrder.dateTime", "When the order was written.");
        fhir(CASE, "NutritionOrder.oralDiet", "The diet that was ordered.");
        fhir(CASE, "NutritionOrder.oralDiet.type", "The kind of diet, as a code.");
        fhir(CASE, "NutritionOrder.supplement", "A nutritional supplement that was ordered.");
        fhir(CASE, "NutritionOrder.supplement.type", "The kind of supplement, as a code.");
        fhir(CASE, "NutritionOrder.supplement.productName", "The product name of the supplement.");
        fhir(CASE, "NutritionOrder.supplement.quantity", "How much of the supplement was ordered.");
        fhir(CASE, "NutritionOrder.enteralFormula", "The tube feeding that was ordered.");
        fhir(CASE, "NutritionOrder.enteralFormula.baseFormulaType", "The kind of formula, as a code.");
        fhir(CASE, "NutritionOrder.note", "A comment on the order, in words.");

        fhir(BUILD, "VisionPrescription.patient", "The patient the prescription is for, as a reference.");
        fhir(BUILD, "VisionPrescription.prescriber", "Who wrote the prescription, as a reference.");
        fhir(CASE, "VisionPrescription.created", "When the prescription record was created.");
        fhir(CASE, "VisionPrescription.dateWritten", "When the prescription was written.");
        fhir(CASE, "VisionPrescription.lensSpecification", "One lens that was prescribed, with its measurements.");
        fhir(CASE, "VisionPrescription.lensSpecification.product", "The kind of lens, as a code.");
        fhir(CASE, "VisionPrescription.lensSpecification.eye", "Which eye the lens is for: right or left.");

        fhir(null, "CommunicationRequest.category", "The kind of communication, as a code.");
        fhir(null, "CommunicationRequest.medium", "How the information is to be passed, as a code.");
        fhir(CASE, "CommunicationRequest.doNotPerform", "True when the request says the communication is not to happen.");
        fhir(BUILD, "CommunicationRequest.about", "What the communication is about, as a reference.");
        fhir(CASE, "CommunicationRequest.payload", "What is to be communicated.");
        fhir(BUILD, "CommunicationRequest.recipient", "Who the information is to go to, as a reference.");
        fhir(BUILD, "CommunicationRequest.sender", "Who is to send the information, as a reference.");

        fhir(null, "Appointment.status", "Where the booking stands, for example proposed, booked or cancelled.");
        fhir(null, "Appointment.serviceCategory", "The broad kind of service to be performed, as a code.");
        fhir(CASE, "Appointment.serviceType", "The service to be performed at the appointment, as a code.");
        fhir(CASE, "Appointment.specialty", "The specialty asked for, as a code.");
        fhir(null, "Appointment.appointmentType", "The style of appointment, as a code.");
        fhir(CASE, "Appointment.reasonCode", "Why the appointment was made, as a code.");
        fhir(BUILD, "Appointment.reasonReference", "Why the appointment was made, as a reference to a condition or another resource.");
        fhir(CASE, "Appointment.description", "What the appointment is for, in words.");
        fhir(CASE, "Appointment.start", "When the appointment starts.");
        fhir(CASE, "Appointment.end", "When the appointment ends.");
        fhir(CASE, "Appointment.minutesDuration", "How long the appointment lasts, in minutes.");
        fhir(CASE, "Appointment.comment", "A comment on the appointment, in words.");
        fhir(BUILD, "Appointment.basedOn", "The order this appointment carries out, as a reference.");
        fhir(BUILD, "Appointment.supportingInformation", "Other information that supports the appointment, as a reference.");
        fhir(null, "Appointment.participant", "Someone or something that takes part in the appointment: a patient, a practitioner or a place.");
        fhir(BUILD, "Appointment.participant.actor", "Who or what takes part, as a reference.");
        fhir(null, "Appointment.participant.status", "Whether this participant has accepted the appointment.");
    }

    /** A coded element that says what was ordered: the concept, its codings and its words. */
    private static void coded(String path, String subject, String code) {
        fhir(CASE, path, upperFirst(subject) + ", as a code.");
        fhir(CASE, path + ".coding", "One code for " + subject + ", with the code system it comes from.");
        fhir(CASE, path + ".coding.code", code);
        fhir(CASE, path + ".coding.display", "The text that describes the code.");
        fhir(CASE, path + ".text", upperFirst(subject) + ", in words.");
    }

    private static String upperFirst(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Every entry that is listed by its path, for the test that reads each sentence. The shapes are not among them. */
    static Map<String, Entry> listed() {
        Map<String, Entry> all = new HashMap<>(DOCUMENT);
        all.putAll(ANY_KEY);
        all.putAll(FHIR);
        return all;
    }

    @Override
    public Entry describe(ElementPath element) {
        Entry entry = DOCUMENT.get(element.document());
        if (entry == null && element.fhir() != null) {
            entry = FHIR.get(element.fhir());
            if (entry == null) {
                entry = shape(element.fhir().split("\\."));
            }
        }
        return entry != null ? entry : ANY_KEY.get(element.document());
    }

    /**
     * The shapes every FHIR resource shares, recognised by the end of the path whatever the
     * resource type. The first segment is always a resource type.
     */
    private static Entry shape(String[] path) {
        int length = path.length;
        String last = path[length - 1];
        String parent = length >= 2 ? path[length - 2] : "";
        if (length == 2) {
            switch (last) {
                case "resourceType":
                    return new Entry("The kind of FHIR resource this is.", BUILD);
                case "id":
                    return new Entry("The id of the resource, which is its label inside the request and not a member ID.", LABEL);
                case "meta":
                    return new Entry("Facts about the resource record itself, such as its profiles and when it last changed.", BUILD);
                case "text":
                    return new Entry("A summary of the resource written for people to read.", BUILD);
                case "contained":
                    return new Entry("A resource carried inside this one.", BUILD);
                default:
                    break;
            }
        }
        if (length == 3 && parent.equals("meta")) {
            switch (last) {
                case "profile":
                    return new Entry("The profiles the resource says it conforms to.", BUILD);
                case "lastUpdated":
                    return new Entry("When the resource was last changed on the server it came from.", null);
                case "versionId":
                    return new Entry("The version of the resource on the server it came from.", null);
                default:
                    break;
            }
        }
        if (last.equals("extension")) {
            return new Entry("An extension: an element added beyond the base FHIR definition, named by its url.", BUILD);
        }
        if (last.equals("modifierExtension")) {
            return new Entry("An extension that changes the meaning of the element that holds it, named by its url.", BUILD);
        }
        if (parent.equals("extension") || parent.equals("modifierExtension")) {
            if (last.equals("url")) {
                return new Entry("The url that names the extension.", BUILD);
            }
            if (last.length() > 5 && last.startsWith("value") && Character.isUpperCase(last.charAt(5))) {
                return new Entry("The value the extension carries.", null);
            }
        }
        if (last.equals("identifier")) {
            return new Entry("An identifier: a value, with a system that says what kind of identifier it is.", BUILD);
        }
        if (endsWith(path, "identifier", "type", "coding", "code")) {
            return new Entry("The code for the kind of identifier, for example MB for a member number.", BUILD);
        }
        if (endsWith(path, "identifier", "type", "coding", "display") || endsWith(path, "identifier", "type", "text")) {
            return new Entry("The kind of identifier, in words.", BUILD);
        }
        if (endsWith(path, "identifier", "type", "coding")) {
            return new Entry("One code for the kind of identifier, with the code system it comes from.", BUILD);
        }
        if (parent.equals("identifier")) {
            switch (last) {
                case "system":
                    return new Entry("The url that says what kind of identifier this is.", BUILD);
                case "value":
                    return new Entry("The identifier itself.", null);
                case "type":
                    return new Entry("What kind of identifier this is, as a code.", BUILD);
                case "use":
                    return new Entry("What the identifier is used for, such as official.", BUILD);
                case "assigner":
                    return new Entry("The organization that issued the identifier.", null);
                default:
                    break;
            }
        }
        if (last.equals("coding")) {
            return new Entry("One code, with the code system it comes from.", null);
        }
        if (parent.equals("coding")) {
            switch (last) {
                case "system":
                    return new Entry("The url of the code system the code comes from.", BUILD);
                case "code":
                    return new Entry("The code itself.", null);
                case "display":
                    return new Entry("The text that describes the code.", null);
                case "version":
                    return new Entry("The version of the code system.", BUILD);
                default:
                    break;
            }
        }
        if (last.equals("reference")) {
            return new Entry("A reference to another resource, written as its type and id or as a url.", BUILD);
        }
        if (last.equals("display")) {
            return new Entry("The text shown for the code or the reference it sits beside.", null);
        }
        if (parent.endsWith("eriod") && (last.equals("start") || last.equals("end"))) {
            return new Entry(last.equals("start") ? "The start of the period." : "The end of the period.", null);
        }
        if (parent.equals("name")) {
            switch (last) {
                case "family":
                    return new Entry("The family name.", CASE);
                case "given":
                    return new Entry("The given names.", CASE);
                case "prefix":
                    return new Entry("What comes before the name, such as a title.", CASE);
                case "suffix":
                    return new Entry("What comes after the name, such as a degree.", CASE);
                case "text":
                    return new Entry("The whole name as one text.", CASE);
                case "use":
                    return new Entry("What the name is used for, such as official.", BUILD);
                default:
                    break;
            }
        }
        if (last.equals("address")) {
            return new Entry("A postal address.", CASE);
        }
        if (parent.equals("address")) {
            switch (last) {
                case "line", "city", "district", "state", "postalCode", "country", "text":
                    return new Entry("One part of a postal address.", CASE);
                case "use", "type":
                    return new Entry("What kind of address this is, such as home or postal.", BUILD);
                default:
                    break;
            }
        }
        if (last.equals("telecom")) {
            return new Entry("One way to reach the person or the organization, such as a phone number.", CASE);
        }
        if (parent.equals("telecom")) {
            switch (last) {
                case "system":
                    return new Entry("The kind of contact: phone, email, fax and so on.", BUILD);
                case "value":
                    return new Entry("The number or the address itself.", CASE);
                case "use":
                    return new Entry("What the contact is used for, such as home or work.", BUILD);
                default:
                    break;
            }
        }
        return null;
    }

    private static boolean endsWith(String[] path, String... tail) {
        if (path.length <= tail.length) {
            return false;
        }
        for (int i = 0; i < tail.length; i++) {
            if (!path[path.length - tail.length + i].equals(tail[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * A remark that explains only some values: that this tool removes the spaces from a member ID
     * explains two identifiers that differ in nothing else, and says nothing about two that differ
     * in more.
     */
    @Override
    public String remark(ElementPath element, DifferenceKind kind, Shown toolValue, Shown yourValue) {
        String fhir = element.fhir() == null ? "" : element.fhir();
        if (kind == DifferenceKind.DIFFERENT_VALUE && (fhir.equals("Patient.identifier.value") || fhir.equals("Coverage.identifier.value"))) {
            boolean onlySpaces = toolValue != null && yourValue != null && toolValue.hasValue() && yourValue.hasValue()
                    && MemberIds.noSpace(toolValue.value()).equals(MemberIds.noSpace(yourValue.value()));
            return onlySpaces ? "This tool removes the spaces from a member ID before it sends it." : null;
        }
        return remark(element, kind);
    }

    /**
     * What this tool's own builder does at an element, where that explains a difference. Each
     * sentence states a fact about {@link CrdRequestBuilder} and {@link CaseResources}, never
     * about what a service expects.
     */
    @Override
    public String remark(ElementPath element, DifferenceKind kind) {
        boolean onlyInTool = kind == DifferenceKind.ONLY_IN_TOOL;
        boolean onlyInYours = kind == DifferenceKind.ONLY_IN_YOURS;
        boolean differentValue = kind == DifferenceKind.DIFFERENT_VALUE;
        String fhir = element.fhir() == null ? "" : element.fhir();
        switch (element.document()) {
            case "hookInstance":
                return differentValue ? "A sender makes a new one for every call. Nothing is sent for a comparison, so the tool's side "
                        + "holds a text in its place." : null;
            case "fhirServer":
                return onlyInYours ? "This tool sends fhirServer only when the variable " + CaseResources.VAR_FHIR_SERVER + " has a value."
                        : differentValue ? "This tool takes its value from the variable " + CaseResources.VAR_FHIR_SERVER + "." : null;
            case "fhirAuthorization":
                return onlyInYours ? "This tool does not build fhirAuthorization." : null;
            case "context.encounterId":
                return onlyInYours ? "This tool does not build an encounterId." : null;
            case "context.selections":
                // Said of the whole element and of one selection alike, since both sit at this path.
                return onlyInTool || onlyInYours ? "This tool selects every order it builds, and sends selections for order-select only." : null;
            case "prefetch":
                return onlyInTool ? "This tool sends prefetch unless the form says to leave it out."
                        : onlyInYours ? "This tool left prefetch out because the form said so." : null;
            case "prefetch.*":
                return onlyInTool || onlyInYours || kind == DifferenceKind.DIFFERENT_KEY ? PREFETCH_KEYS
                        : differentValue ? PREFETCH_SHAPES : null;
            case "context.draftOrders.entry":
                return onlyInTool || onlyInYours ? "This tool builds one ServiceRequest for each procedure code." : null;
            case "context.draftOrders.entry.fullUrl":
                return onlyInYours ? "This tool does not write a fullUrl on the entries of its draft orders." : null;
            default:
                break;
        }
        switch (fhir) {
            case "Patient.identifier", "Coverage.identifier":
                return onlyInTool || onlyInYours ? "This tool writes the member ID as an identifier typed MB, with a system only when the "
                        + "variable " + CaseResources.VAR_MEMBER_ID_SYSTEM + " has a value." : null;
            case "Patient.gender":
                return onlyInYours ? "This tool sends a gender only when the case gives one it can read as male, female, other or unknown." : null;
            case "Coverage.status":
                return onlyInYours ? "This tool sends a coverage status only when the case gives one of active, cancelled, draft or "
                        + "entered-in-error." : null;
            case "Coverage.payor":
                return onlyInYours ? "This tool names a payer only when the variable " + CaseResources.VAR_PAYER_ID + " or "
                        + CaseResources.VAR_PAYER_NAME + " has a value." : null;
            case "ServiceRequest.code.coding":
                return onlyInTool || onlyInYours ? "When a code comes without a system, this tool takes one from the code list, or else from "
                        + "the format of the code." : null;
            case "ServiceRequest.occurrenceDateTime":
                return differentValue ? "This tool sends the date of service as a date, with no time."
                        : onlyInTool ? "This tool writes the date of service as occurrenceDateTime." : null;
            case "ServiceRequest.occurrencePeriod":
                return onlyInYours ? "This tool writes the date of service as occurrenceDateTime." : null;
            default:
                return null;
        }
    }
}
