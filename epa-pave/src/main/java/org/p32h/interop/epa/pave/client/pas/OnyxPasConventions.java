package org.p32h.interop.epa.pave.client.pas;

import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.OperationOutcomes;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.VendorSignal;
import org.p32h.interop.epa.pave.model.WireHeader;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * ASSUMPTIONS ABOUT THE VENDOR, ALL IN ONE FILE: PAS, subscriptions and notifications.
 *
 * <p>Nothing here has been observed from the live service. The tool is held to PAS 2.0.1 (D-136),
 * read from the guide's own source at its 2.0.1 tag, with the 2.2.0 ballot read for what changes.
 * A name or rule taken from the guide says so and names the version; one marked ASSUMPTION is how
 * this tool expects the vendor to apply the guide, or covers a case the guide does not define.
 * The subscriptions backport 1.1.0 that PAS 2.0.1 depends on could not be read, so its names are
 * taken from the 1.2.0 ballot. Each rule reads a coded element or the structure of a resource, never the wording
 * of a disposition or a diagnostic, and each is deliberately narrow: when a response does not
 * match a rule, nothing is inferred and the response is shown in full under the outcome its codes
 * support.
 *
 * <p>When the real behaviour is known, this is the file to change, together with the mock scenario
 * files that say they model these cases. The request builders and the readers take every name
 * they need from here.
 */
public final class OnyxPasConventions {

    /** The extension that carries a review action: the payer's coded decision. */
    public static final String REVIEW_ACTION = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewAction";
    /** The part of a review action that holds the decision code, as later versions of the guide name it. */
    public static final String REVIEW_ACTION_CODE = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-reviewActionCode";
    /** The code system of the review action codes (A1, A2, A3, A4, A6, C, CT, NA). */
    public static final String REVIEW_ACTION_CODES = "https://codesystem.x12.org/005010/306";
    public static final String ERROR_ELEMENT = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-errorElement";
    public static final String ERROR_PATH = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-errorPath";
    public static final String ERROR_FOLLOWUP_ACTION = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-errorFollowupAction";

    /** A part of a review action, by what it holds. */
    public enum ReviewPart {
        CODE, NUMBER, REASON, SECOND_SURGICAL_OPINION, OTHER
    }

    /** An extension on a ClaimResponse error, by what it holds. */
    public enum ErrorPart {
        ELEMENT, PATH, FOLLOWUP_ACTION, OTHER
    }

    /**
     * ASSUMPTION: a request for supporting documentation is closed only when the resource the
     * ClaimResponse refers to is carried with one of these statuses. A reference whose resource is
     * not carried has no visible status, and is treated as open: the element it sits in exists to
     * ask for more information, and closing it unseen would hide a request from the tester.
     *
     * <p>PAS 2.0.1 fixes the status of a PASCommunicationRequest to active, so a service that
     * follows it never sends one of these, and shows that documentation was accepted by a later
     * decision instead. The rule is kept for a service that does not follow the profile.
     */
    private static final Set<String> CLOSED_REQUEST_STATUSES = Set.of("completed", "revoked", "entered-in-error");

    /**
     * ASSUMPTION: the X12 reject reason codes that say the payer does not know the member: 67
     * patient not found, 75 subscriber or insured not found, 77 subscriber found and patient not
     * found. Taken from memory of the X12 code list, not checked against it.
     */
    private static final Set<String> MEMBER_NOT_FOUND_CODES = Set.of("67", "75", "77");

    /** ASSUMPTION: the X12 reject reason code that says the payer does not know the provider: 51 provider not on file. */
    private static final Set<String> PROVIDER_NOT_FOUND_CODES = Set.of("51");

    private static final List<String> MEMBER_WORDS = List.of("patient", "member", "coverage", "subscriber", "beneficiary");
    private static final List<String> PROVIDER_WORDS = List.of("practitioner", "provider", "requester", "requestor", "performer",
            "careteam", "npi");
    private static final List<String> AUTHORIZATION_WORDS = List.of("claim", "preauth", "authorization");

    /** FHIR operations exchange FHIR JSON, not plain JSON. */
    public static final String FHIR_JSON = "application/fhir+json";

    public static final String CLAIM_ID = "claim-1";
    public static final String CLAIM_REF = "Claim/" + CLAIM_ID;
    public static final String CLAIM_TYPES = "http://terminology.hl7.org/CodeSystem/claim-type";
    public static final String PROCESS_PRIORITIES = "http://terminology.hl7.org/CodeSystem/processpriority";
    public static final String ICD_10_CM = "http://hl7.org/fhir/sid/icd-10-cm";

    /**
     * ASSUMPTION: a claim type and a priority the request must carry, used when the tester states
     * neither. They are this tool's own defaults, not something known about the vendor, and the
     * request notes say so each time they are used.
     */
    public static final String DEFAULT_CLAIM_TYPE = "professional";
    public static final String DEFAULT_CLAIM_PRIORITY = "normal";

    /**
     * The category under which a claim names supporting documentation: PAS 2.0.1 PASClaim slice
     * supportingInfo[AdditionalInformation] fixes it to PASTempCodes#additionalInformation, and the
     * slice is told apart by that value. PASSupportingInfoType is the value set, not the code system.
     */
    public static final String SUPPORTING_INFO_TYPES = "http://hl7.org/fhir/us/davinci-pas/CodeSystem/PASTempCodes";
    public static final String ADDITIONAL_INFORMATION = "additionalInformation";

    /** PAS 2.0.1 item extensions, by their canonical urls (the ids in the guide's Claim-update.fsh). */
    private static final String PAS_EXTENSION = "http://hl7.org/fhir/us/davinci-pas/StructureDefinition/extension-";
    public static final String ITEM_TRACE_NUMBER = PAS_EXTENSION + "itemTraceNumber";
    public static final String ITEM_REQUEST_TYPE = PAS_EXTENSION + "serviceItemRequestType";
    public static final String ITEM_CERTIFICATION_TYPE = PAS_EXTENSION + "certificationType";
    public static final String ITEM_REQUESTED_SERVICE = PAS_EXTENSION + "requestedService";
    public static final String ITEM_PRE_AUTH_PERIOD = PAS_EXTENSION + "itemPreAuthPeriod";
    public static final String ITEM_AUTHORIZATION_NUMBER = PAS_EXTENSION + "authorizationNumber";

    /** X12 code systems PAS 2.0.1 binds, required, to the elements named. */
    public static final String X12_SERVICE_TYPES = "https://codesystem.x12.org/005010/1365";
    public static final String X12_REQUEST_TYPES = "https://codesystem.x12.org/005010/1525";
    public static final String X12_CERTIFICATION_TYPES = "https://codesystem.x12.org/005010/1322";
    public static final String X12_ENTITY_TYPES = "https://codesystem.x12.org/005010/98";
    public static final String X12_RELATIONSHIP = "https://codesystem.x12.org/005010/1069";

    /** X12 1322 code I, "Initial": the certification type of a request that is new, not a revision. */
    public static final String CERTIFICATION_INITIAL = "I";

    /**
     * ASSUMPTION, attachment mode fhir: a document travels in the request bundle as a
     * DocumentReference with status current, its content inline as base64 or as a url, and the
     * claim names it in supportingInfo by a reference to that resource.
     */
    public static final String DOCUMENT_STATUS = "current";

    /**
     * ASSUMPTION, attachment mode x12-275: the document travels separately in an X12 275
     * transaction, which this tool does not send. The request bundle does not carry it. The claim
     * names it in supportingInfo by a logical reference whose identifier is the attachment control
     * number the 275 will carry, so the payer can put the two together.
     */
    public static String attachmentControlNumber(String claimId, int position) {
        return claimId + "-A" + position;
    }

    public static String documentReferenceId(int position) {
        return "document-reference-" + position;
    }

    public static String questionnaireResponseId(int position) {
        return "questionnaire-response-" + position;
    }

    private static final String BACKPORT = "http://hl7.org/fhir/uv/subscriptions-backport/StructureDefinition/";
    public static final String SUBSCRIPTION_PROFILE = BACKPORT + "backport-subscription";
    public static final String FILTER_CRITERIA = BACKPORT + "backport-filter-criteria";
    public static final String HEARTBEAT_PERIOD = BACKPORT + "backport-heartbeat-period";
    public static final String TIMEOUT = BACKPORT + "backport-timeout";
    public static final String MAX_COUNT = BACKPORT + "backport-max-count";
    public static final String PAYLOAD_CONTENT = BACKPORT + "backport-payload-content";

    /**
     * The subscription topic PAS 2.0.1 defines (input/resources/SubscriptionTopic.json), which both
     * of its CapabilityStatements name: an event when a ClaimResponse result is available. The 2.2.0
     * ballot keeps the same canonical address.
     */
    public static final String PAS_TOPIC = "http://hl7.org/fhir/us/davinci-pas/SubscriptionTopic/PASSubscriptionTopic";

    /**
     * The filter parameter the PAS 2.0.1 topic offers (canFilterBy orgIdentifier, comparator eq).
     * PAS 2.0.1 says a subscription SHALL be filtered by the requesting provider organization.
     */
    public static final String ORG_IDENTIFIER_FILTER = "orgIdentifier";

    /**
     * A filter as the backport filter-criteria string holds it: {@code orgIdentifier=system|value},
     * or {@code orgIdentifier=value} when no system is known. This is the form the PAS 2.2.0 ballot's
     * own example uses; the backport 1.1.0 format PAS 2.0.1 depends on could not be read here.
     */
    public static String orgIdentifierFilter(String system, String value) {
        return ORG_IDENTIFIER_FILTER + "=" + (Texts.present(system) ? system.strip() + "|" : "") + value.strip();
    }

    /**
     * A subscription that calls a receiver back is a rest-hook carrying FHIR JSON: PAS 2.0.1 says
     * a server SHOULD support rest-hook, and the 2.2.0 ballot profile fixes it.
     */
    public static final String DEFAULT_CHANNEL_TYPE = "rest-hook";

    /**
     * Ids only: PAS 2.0.1 says clients and intermediaries SHALL support id-only and MAY agree on
     * full-resource. It also puts the least member data on the wire. The PAS 2.2.0 ballot profile
     * fixes full-resource instead, and the request notes say so.
     */
    public static final String DEFAULT_PAYLOAD_CONTENT = "id-only";

    /**
     * The status a client asks for when it creates a subscription. The service decides whether to
     * activate it. PAS 2.0.1 has no Subscription profile; the 2.2.0 ballot profile fixes active.
     */
    public static final String REQUESTED = "requested";

    /**
     * A subscription has to say why it exists. This is the tool's own sentence, sent when the
     * tester states none, so that whoever reads the subscription on the far side can see where it
     * came from. The request notes say it was a default.
     */
    public static final String DEFAULT_SUBSCRIPTION_REASON = "Prior authorization status notifications for a PAVE test run";

    /** The R4 form of a subscription status: a Parameters resource. Later FHIR versions use a SubscriptionStatus resource. */
    public static final String STATUS_PROFILE_FRAGMENT = "backport-subscription-status";
    public static final String STATUS_RESOURCE = "SubscriptionStatus";
    public static final String PARAMETERS = "Parameters";

    private OnyxPasConventions() {
    }

    /** True when an extension url is an item extension of PAS, by its canonical url or, served from another host, by its last segment. */
    public static boolean pasExtension(String extensionUrl, String canonical) {
        return extensionUrl != null && (extensionUrl.equals(canonical) || extensionUrl.endsWith(canonical.substring(canonical.lastIndexOf('/'))));
    }

    /** True when an extension url is the review action extension. A copy served from another host is accepted by its last segment. */
    public static boolean reviewAction(String extensionUrl) {
        return extensionUrl != null && (extensionUrl.equals(REVIEW_ACTION) || extensionUrl.endsWith("/extension-reviewAction"));
    }

    /**
     * The decision code of a review action sits in a part named by the full reviewActionCode url,
     * as PAS 2.0.1 and the 2.2.0 ballot both write it. A part named {@code code} is read as the
     * same thing, because that is the slice name the guide gives it and a vendor may write it so.
     * The second surgical opinion part is {@code secondSurgicalOpinionFlag}, as PAS 2.0.1 names it.
     */
    public static ReviewPart reviewPart(String partUrl) {
        if (partUrl == null) {
            return ReviewPart.OTHER;
        }
        if (partUrl.equals(REVIEW_ACTION_CODE) || partUrl.endsWith("/extension-reviewActionCode") || partUrl.equals("code")) {
            return ReviewPart.CODE;
        }
        return switch (partUrl) {
            case "number" -> ReviewPart.NUMBER;
            case "reasonCode" -> ReviewPart.REASON;
            case "secondSurgicalOpinionFlag" -> ReviewPart.SECOND_SURGICAL_OPINION;
            default -> ReviewPart.OTHER;
        };
    }

    public static ErrorPart errorPart(String extensionUrl) {
        if (extensionUrl == null) {
            return ErrorPart.OTHER;
        }
        if (extensionUrl.equals(ERROR_ELEMENT) || extensionUrl.endsWith("/extension-errorElement")) {
            return ErrorPart.ELEMENT;
        }
        if (extensionUrl.equals(ERROR_PATH) || extensionUrl.endsWith("/extension-errorPath")) {
            return ErrorPart.PATH;
        }
        if (extensionUrl.equals(ERROR_FOLLOWUP_ACTION) || extensionUrl.endsWith("/extension-errorFollowupAction")) {
            return ErrorPart.FOLLOWUP_ACTION;
        }
        return ErrorPart.OTHER;
    }

    /**
     * True when a request for supporting documentation is shown to be closed.
     *
     * @param status the status of the resource the ClaimResponse refers to, or null when that
     *               resource was not carried and so its status cannot be seen
     */
    public static boolean documentationRequestClosed(String status) {
        return status != null && CLOSED_REQUEST_STATUSES.contains(status.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * ASSUMPTION: an unknown member or provider arrives in one of two ways. Either as a FHIR
     * OperationOutcome whose issue code is {@code not-found} and whose {@code expression},
     * {@code location} or {@code details} coding names the element, as for CRD. Or as a
     * ClaimResponse error whose code is one of the X12 reject reason codes listed above. An
     * OperationOutcome issue that names both a member element and a provider element is ambiguous
     * and is left unclassified.
     */
    public static Set<VendorSignal> signals(VendorExchange exchange) {
        EnumSet<VendorSignal> signals = EnumSet.noneOf(VendorSignal.class);
        JsonNode body = exchange == null ? null : exchange.json();
        if (body == null) {
            return signals;
        }
        for (JsonNode resource : resources(body)) {
            if (OperationOutcomes.is(resource)) {
                for (JsonNode issue : JsonSafe.elements(resource, "issue")) {
                    if (!"not-found".equals(JsonSafe.text(issue, "code"))) {
                        continue;
                    }
                    List<String> pointers = pointers(issue);
                    boolean member = mentions(pointers, MEMBER_WORDS);
                    boolean provider = mentions(pointers, PROVIDER_WORDS);
                    if (member && !provider) {
                        signals.add(VendorSignal.MEMBER_NOT_FOUND);
                    } else if (provider && !member) {
                        signals.add(VendorSignal.PROVIDER_NOT_FOUND);
                    }
                }
            } else if ("ClaimResponse".equals(JsonSafe.text(resource, "resourceType"))) {
                for (JsonNode error : JsonSafe.elements(resource, "error")) {
                    for (JsonNode coding : JsonSafe.elements(JsonSafe.child(error, "code"), "coding")) {
                        if (!rejectReasons(JsonSafe.presentText(coding, "system"))) {
                            continue;
                        }
                        String code = JsonSafe.presentText(coding, "code");
                        if (code != null && MEMBER_NOT_FOUND_CODES.contains(code)) {
                            signals.add(VendorSignal.MEMBER_NOT_FOUND);
                        } else if (code != null && PROVIDER_NOT_FOUND_CODES.contains(code)) {
                            signals.add(VendorSignal.PROVIDER_NOT_FOUND);
                        }
                    }
                }
            }
        }
        return signals;
    }

    /**
     * ASSUMPTION: an inquiry about a request the payer does not know arrives as an OperationOutcome
     * whose issue code is {@code not-found} and whose pointers name the claim and neither the
     * member nor the provider. The published operation does not define this case.
     */
    public static boolean authorizationNotFound(VendorExchange exchange) {
        JsonNode body = exchange == null ? null : exchange.json();
        if (body == null) {
            return false;
        }
        for (JsonNode resource : resources(body)) {
            if (!OperationOutcomes.is(resource)) {
                continue;
            }
            for (JsonNode issue : JsonSafe.elements(resource, "issue")) {
                if ("not-found".equals(JsonSafe.text(issue, "code"))) {
                    List<String> pointers = pointers(issue);
                    if (mentions(pointers, AUTHORIZATION_WORDS) && !mentions(pointers, MEMBER_WORDS) && !mentions(pointers, PROVIDER_WORDS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * ASSUMPTION: a subscription id the service does not know is answered with HTTP 410, or with
     * an OperationOutcome whose issue code is {@code not-found} or {@code deleted}. A bare 404 is
     * not read that way, because a wrong url answers 404 as well and the two need different fixes.
     */
    public static boolean subscriptionNotFound(VendorExchange exchange) {
        if (exchange == null || exchange.httpStatus() == null) {
            return false;
        }
        if (exchange.httpStatus() == 410) {
            return true;
        }
        JsonNode body = exchange.json();
        if (body == null || !OperationOutcomes.is(body) || exchange.httpStatus() < 400) {
            return false;
        }
        for (JsonNode issue : JsonSafe.elements(body, "issue")) {
            String code = JsonSafe.text(issue, "code");
            if ("not-found".equals(code) || "deleted".equals(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * ASSUMPTION: a create that answers without the resource says where it put it in a Location
     * header, as the FHIR create interaction defines: {@code [base]/Subscription/[id]}, optionally
     * followed by {@code /_history/[version]}.
     *
     * @return the id, or null when the response has no such header
     */
    public static String createdSubscriptionId(VendorExchange exchange) {
        if (exchange == null) {
            return null;
        }
        for (WireHeader header : exchange.responseHeaders()) {
            if (header.name() == null || header.value() == null
                    || !(header.name().equalsIgnoreCase("Location") || header.name().equalsIgnoreCase("Content-Location"))) {
                continue;
            }
            String id = idAfter(header.value(), "Subscription");
            if (id != null) {
                return id;
            }
        }
        return null;
    }

    /**
     * The id a reference to a resource of the given type names: the path segment after the type.
     * A reference written as a full url and one that ends in a history version are both read.
     * Null when the reference does not name that type.
     */
    public static String idAfter(String reference, String resourceType) {
        if (Texts.blank(reference)) {
            return null;
        }
        String[] segments = reference.strip().split("[?#]")[0].split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            if (segments[i].equals(resourceType) && Texts.present(segments[i + 1])) {
                return segments[i + 1];
            }
        }
        return null;
    }

    /**
     * ASSUMPTION: a notification is a bundle whose first entry holds the subscription status. In
     * R4 that is a Parameters resource declaring the backport status profile, or simply carrying a
     * {@code subscription} parameter. In later FHIR versions it is a SubscriptionStatus resource.
     */
    public static boolean subscriptionStatus(JsonNode resource) {
        String type = JsonSafe.text(resource, "resourceType");
        if (STATUS_RESOURCE.equals(type)) {
            return true;
        }
        if (!PARAMETERS.equals(type)) {
            return false;
        }
        for (JsonNode profile : JsonSafe.elements(JsonSafe.child(resource, "meta"), "profile")) {
            String url = JsonSafe.text(profile);
            if (url != null && url.contains(STATUS_PROFILE_FRAGMENT)) {
                return true;
            }
        }
        for (JsonNode parameter : JsonSafe.elements(resource, "parameter")) {
            if ("subscription".equals(JsonSafe.text(parameter, "name"))) {
                return true;
            }
        }
        return false;
    }

    /** The resources a body holds: each entry of a bundle, or the body itself when it is one resource. */
    public static List<JsonNode> resources(JsonNode body) {
        List<JsonNode> out = new ArrayList<>();
        if (body == null || !body.isObject()) {
            return out;
        }
        if ("Bundle".equals(JsonSafe.text(body, "resourceType"))) {
            for (JsonNode entry : JsonSafe.elements(body, "entry")) {
                JsonNode resource = JsonSafe.child(entry, "resource");
                if (resource.isObject()) {
                    out.add(resource);
                }
            }
        } else {
            out.add(body);
        }
        return out;
    }

    private static boolean rejectReasons(String system) {
        return system != null && system.contains("x12") && system.endsWith("/901");
    }

    private static List<String> pointers(JsonNode issue) {
        List<String> pointers = new ArrayList<>();
        JsonSafe.elements(issue, "expression").forEach(e -> pointers.add(JsonSafe.text(e)));
        JsonSafe.elements(issue, "location").forEach(e -> pointers.add(JsonSafe.text(e)));
        JsonSafe.elements(JsonSafe.child(issue, "details"), "coding").forEach(c -> pointers.add(JsonSafe.text(c, "code")));
        return pointers;
    }

    private static boolean mentions(List<String> pointers, List<String> words) {
        for (String pointer : pointers) {
            if (Texts.present(pointer)) {
                String lower = pointer.toLowerCase(Locale.ROOT);
                if (words.stream().anyMatch(lower::contains)) {
                    return true;
                }
            }
        }
        return false;
    }
}
