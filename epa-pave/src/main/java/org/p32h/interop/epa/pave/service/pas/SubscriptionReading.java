package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.AdditionalField;
import org.p32h.interop.epa.pave.model.Expectations;
import org.p32h.interop.epa.pave.model.pas.SubscriptionDetail;
import org.p32h.interop.epa.pave.model.pas.SubscriptionOutcome;
import org.p32h.interop.epa.pave.model.pas.SubscriptionView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the mapper made of the response to one subscription call.
 *
 * @param additionalFields everything the response carried that the designed view did not use
 */
public record SubscriptionReading(SubscriptionDetail detail, SubscriptionOutcome outcome, List<AdditionalField> additionalFields) {

    public SubscriptionReading {
        additionalFields = additionalFields == null ? List.of() : List.copyOf(additionalFields);
    }

    /** True when the result holds more than one subscription to tell apart. Each is then named wherever it is shown. */
    public boolean several() {
        return detail.subscriptions().size() > 1;
    }

    /**
     * The answers a declared expectation is compared with: one for each subscription returned,
     * each with its own codes. The codes of different subscriptions are never pooled, so an
     * assertion about a list holds only when every subscription in it carries the code. A response
     * that returned no subscription gives no answer, and no code assertion can hold for it.
     */
    public List<Expectations.Answer> answers() {
        boolean named = several();
        List<Expectations.Answer> out = new ArrayList<>();
        for (SubscriptionView view : detail.subscriptions()) {
            out.add(Expectations.Answer.of(named ? view.label() : null, codes(view)));
        }
        return out;
    }

    /** The codes one subscription carried, by the element names an expectation uses. */
    public static Map<String, List<String>> codes(SubscriptionView view) {
        Map<String, List<String>> codes = new LinkedHashMap<>();
        if (view.status().code() != null) {
            codes.put(PasCodes.SUBSCRIPTION_STATUS, List.of(view.status().code()));
        }
        if (view.channelType().hasValue()) {
            codes.put(PasCodes.CHANNEL_TYPE, List.of(view.channelType().value()));
        }
        if (view.payloadContent().code() != null) {
            codes.put(PasCodes.PAYLOAD_CONTENT, List.of(view.payloadContent().code()));
        }
        return codes;
    }

    /**
     * The codes for the ledger, as {@code name=code} pairs. When a list returned several
     * subscriptions each pair starts with where its subscription sat in the response, never with
     * the id the response gave it: that id is text the service supplied.
     */
    public List<String> ledgerCodes() {
        boolean named = several();
        List<String> out = new ArrayList<>();
        for (SubscriptionView view : detail.subscriptions()) {
            String prefix = named ? view.foundAt() + ": " : "";
            int before = out.size();
            codes(view).forEach((name, values) -> values.forEach(value -> out.add(prefix + name + "=" + value)));
            if (named && out.size() == before) {
                out.add(view.foundAt() + ": (no answer)");
            }
        }
        return out;
    }
}
