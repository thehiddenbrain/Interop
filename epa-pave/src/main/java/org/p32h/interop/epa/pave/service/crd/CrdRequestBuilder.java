package org.p32h.interop.epa.pave.service.crd;

import org.p32h.interop.epa.pave.client.crd.SentOrder;
import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.JsonSafe;
import org.p32h.interop.epa.pave.model.MemberDetail;
import org.p32h.interop.epa.pave.model.ProviderDetail;
import org.p32h.interop.epa.pave.model.ResolvedText;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.ProcedureCodeDirectory;
import org.p32h.interop.epa.pave.service.run.CaseResources;
import java.util.ArrayList;
import java.util.List;
import java.time.Clock;
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
 * the user id. The resources themselves come from {@link CaseResources}, which DTR and PAS build
 * from as well, so the three protocols describe one case the same way.
 */
public final class CrdRequestBuilder {

    /**
     * The request body, what was left out of it, and the orders it carries.
     *
     * @param json   the request as pretty JSON
     * @param notes  one plain sentence for each thing left out or worked out
     * @param orders each order sent, with the procedure code on it, so an answer can be tied to its code
     * @param incomplete each element a CRD 2.0.1 profile requires that the request was sent without,
     *                   because nobody supplied it. Empty when nothing is missing
     */
    public record Built(String json, List<String> notes, List<SentOrder> orders, List<CaseResources.Required> incomplete) {

        public Built {
            notes = notes == null ? List.of() : List.copyOf(notes);
            orders = orders == null ? List.of() : List.copyOf(orders);
            incomplete = incomplete == null ? List.of() : List.copyOf(incomplete);
        }
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final CaseResources resources;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public CrdRequestBuilder(ProcedureCodeDirectory codeList) {
        this(codeList, Clock.systemUTC());
    }

    /** @param clock when each order is written: CRD 2.0.1 requires authoredOn and nobody supplies it */
    public CrdRequestBuilder(ProcedureCodeDirectory codeList, Clock clock) {
        this.resources = new CaseResources(codeList, clock);
    }

    /**
     * @param hookInstance the unique id of this hook invocation
     * @param variables    resolves a placeholder name to its value, or to an unresolved result
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        List<SentOrder> orders = new ArrayList<>();
        List<CaseResources.Required> missing = new ArrayList<>();
        String hook = hook(request);
        MemberDetail member = request.member();
        ProviderDetail provider = request.provider();

        ObjectNode root = JSON.objectNode();
        root.put("hook", hook);
        root.put("hookInstance", hookInstance);
        String fhirServer = CaseResources.value(variables, CaseResources.VAR_FHIR_SERVER);
        boolean prefetchSent = !Boolean.FALSE.equals(request.includePrefetch());
        if (fhirServer != null) {
            root.put("fhirServer", fhirServer);
        } else if (prefetchSent) {
            // Without prefetch either, the note below says both at once, so the two notes never
            // send the service to a prefetch that was not sent.
            notes.add("No fhirServer was sent: the variable " + CaseResources.VAR_FHIR_SERVER + " is not set. This tool is not an "
                    + "EHR, so the service must work from the prefetch it is given.");
        }

        ObjectNode context = root.putObject("context");
        context.put("userId", CaseResources.PRACTITIONER_ROLE_REF);
        context.put("patientId", CaseResources.PATIENT_ID);

        ObjectNode bundle = JSON.objectNode();
        bundle.put("resourceType", "Bundle");
        bundle.put("type", "collection");
        ArrayNode entries = bundle.putArray("entry");
        ArrayNode selections = JSON.arrayNode();
        String authoredOn = resources.now();
        String hcpcs = CaseResources.orderHcpcsSystem(variables);
        boolean hcpcsSent = false;
        int position = 1;
        for (CodeInput code : request.procedureCodes()) {
            String id = CaseResources.serviceRequestId(position);
            ObjectNode order = resources.serviceRequest(id, code, member, request.dateOfService(), authoredOn, hcpcs, notes);
            hcpcsSent |= !Texts.present(code.system()) && hcpcs.equals(JsonSafe.text(order.at("/code/coding/0/system")));
            entries.addObject().set("resource", order);
            selections.add(CaseResources.serviceRequestRef(position));
            orders.add(new SentOrder(CaseResources.serviceRequestRef(position), code.code().strip()));
            position++;
        }
        if (!orders.isEmpty()) {
            notes.add(CaseResources.authoredOnNote(authoredOn));
        }
        if (hcpcsSent) {
            notes.add(CaseResources.hcpcsNote(hcpcs));
        }
        if (CrdRunRequest.ORDER_SELECT.equals(hook)) {
            context.set("selections", selections);
        }
        context.set("draftOrders", bundle);

        if (prefetchSent) {
            ObjectNode prefetch = root.putObject("prefetch");
            prefetch.set("patient", resources.crdPatient(member, variables, notes, missing));
            prefetch.set("coverage", resources.crdCoverage(member, variables, notes, missing));
            CaseResources.Provider named = CaseResources.crdProvider(provider, notes, missing);
            prefetch.set("practitionerRole", named.role());
            if (named.practitioner() != null) {
                prefetch.set("practitioner", named.practitioner());
            } else {
                prefetch.set("organization", named.organization());
            }
            notes.add("The prefetch keys patient, coverage, practitionerRole and " + (named.practitioner() != null ? "practitioner" : "organization")
                    + " are this tool's own choice. The keys the live service declares are not known: compare them with its discovery "
                    + "response.");
        } else if (fhirServer != null) {
            notes.add("No prefetch was sent, because the form said to leave it out. The Patient, Coverage and provider resources, and "
                    + "what " + CaseResources.CRD_VERSION + " requires of them, are then the service's to fetch from the fhirServer sent.");
        } else {
            notes.add("No prefetch was sent, because the form said to leave it out, and no fhirServer was sent either: the variable "
                    + CaseResources.VAR_FHIR_SERVER + " is not set. This request gives the service no way to read the Patient, the "
                    + "Coverage or the provider.");
        }
        if (!missing.isEmpty()) {
            // First, so a refusal by the service is read beside it and not taken for a fault of the service.
            notes.add(0, "This request leaves out " + missing.size() + (missing.size() == 1 ? " element" : " elements") + " that the "
                    + CaseResources.CRD_VERSION + " profiles require, because nobody supplied " + (missing.size() == 1 ? "it" : "them")
                    + ". It goes all the same. The notes below say which, and what would supply each.");
        }
        return new Built(JsonSafe.pretty(root), notes, orders, missing);
    }

    /** The hook name this request is for. Blank means order-sign. */
    public static String hook(CrdRunRequest request) {
        return Texts.present(request.hook()) ? request.hook().strip().toLowerCase(Locale.ROOT) : CrdRunRequest.ORDER_SIGN;
    }
}
