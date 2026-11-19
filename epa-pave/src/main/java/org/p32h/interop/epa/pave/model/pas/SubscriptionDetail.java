package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.IssueView;
import org.p32h.interop.epa.pave.model.Shown;
import java.util.List;

/**
 * Everything the tool understood in the response to a subscription call, for the designed view.
 *
 * @param responseKind    what kind of thing came back
 * @param responseNote    one plain sentence about it, or null when it needs none
 * @param subscriptions   every subscription the response carried: one for a read, a create or an
 *                        update that returned it, any number for a list
 * @param total           the total a list states, when it states one
 * @param otherResources  anything else a list bundle carried
 * @param issues          the issues of an OperationOutcome, when one came back
 * @param subscriptionId  the id this run is about: the one asked for, or the one a create returned.
 *                        Null for a list, and for a create whose response gave no id
 * @param idSource        where a created subscription's id was read from, in words, or null
 * @param callbackUrl     the url a create or update named as the receiver, or null when it named none
 * @param requestNotes    what the tool left out of, or worked out for, the request it built
 * @param bodyBuiltByTool false when the tester's edited body was sent in place of a built one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionDetail(SubscriptionResponseKind responseKind, String responseNote, List<SubscriptionView> subscriptions,
                                 Shown total, List<BundleResourceView> otherResources, List<IssueView> issues, String subscriptionId,
                                 String idSource, String callbackUrl, List<String> requestNotes, boolean bodyBuiltByTool) {

    public SubscriptionDetail {
        subscriptions = subscriptions == null ? List.of() : List.copyOf(subscriptions);
        otherResources = otherResources == null ? List.of() : List.copyOf(otherResources);
        issues = issues == null ? List.of() : List.copyOf(issues);
        requestNotes = requestNotes == null ? List.of() : List.copyOf(requestNotes);
    }

    /** The same detail with what is known about the request that produced it. */
    public SubscriptionDetail withRequest(String id, String source, String receiver, List<String> notes, boolean builtByTool) {
        return new SubscriptionDetail(responseKind, responseNote, subscriptions, total, otherResources, issues, id, source, receiver,
                notes, builtByTool);
    }
}
