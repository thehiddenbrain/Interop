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
     */
    public record Built(String json, List<String> notes, List<SentOrder> orders) {
    }

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final CaseResources resources;

    /** @param codeList consulted for a code's system when the tester did not state one. May be null */
    public CrdRequestBuilder(ProcedureCodeDirectory codeList) {
        this.resources = new CaseResources(codeList);
    }

    /**
     * @param hookInstance the unique id of this hook invocation
     * @param variables    resolves a placeholder name to its value, or to an unresolved result
     */
    public Built build(CrdRunRequest request, String hookInstance, Function<String, ResolvedText> variables) {
        List<String> notes = new ArrayList<>();
        List<SentOrder> orders = new ArrayList<>();
        String hook = hook(request);
        MemberDetail member = request.member();
        ProviderDetail provider = request.provider();

        ObjectNode root = JSON.objectNode();
        root.put("hook", hook);
        root.put("hookInstance", hookInstance);
        String fhirServer = CaseResources.value(variables, CaseResources.VAR_FHIR_SERVER);
        if (fhirServer != null) {
            root.put("fhirServer", fhirServer);
        } else {
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
        int position = 1;
        for (CodeInput code : request.procedureCodes()) {
            String id = CaseResources.serviceRequestId(position);
            entries.addObject().set("resource", resources.serviceRequest(id, code, member, request.dateOfService(), notes));
            selections.add(CaseResources.serviceRequestRef(position));
            orders.add(new SentOrder(CaseResources.serviceRequestRef(position), code.code().strip()));
            position++;
        }
        if (CrdRunRequest.ORDER_SELECT.equals(hook)) {
            context.set("selections", selections);
        }
        context.set("draftOrders", bundle);

        if (!Boolean.FALSE.equals(request.includePrefetch())) {
            ObjectNode prefetch = root.putObject("prefetch");
            prefetch.set("patient", resources.patient(member, variables, notes));
            prefetch.set("coverage", resources.coverage(member, variables, notes));
            prefetch.set("practitionerRole", CaseResources.practitionerRole());
            prefetch.set("practitioner", CaseResources.practitioner(provider));
            notes.add("The prefetch keys patient, coverage, practitionerRole and practitioner are this tool's own choice. The keys the "
                    + "live service declares are not known: compare them with its discovery response.");
        }
        return new Built(JsonSafe.pretty(root), List.copyOf(notes), List.copyOf(orders));
    }

    /** The hook name this request is for. Blank means order-sign. */
    public static String hook(CrdRunRequest request) {
        return Texts.present(request.hook()) ? request.hook().strip().toLowerCase(Locale.ROOT) : CrdRunRequest.ORDER_SIGN;
    }
}
