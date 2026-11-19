package org.p32h.interop.epa.pave.model.pas;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.p32h.interop.epa.pave.model.Shown;
import org.p32h.interop.epa.pave.model.crd.CodedValue;
import java.util.List;

/**
 * One Subscription as returned: its id, its status, what it is subscribed to and where it calls back.
 *
 * @param foundAt        where in the response the subscription sat
 * @param fullUrl        the full url of the bundle entry it came in, when it came in a list
 * @param profiles       the profiles the resource declares, which say whether the service treats it
 *                       as a topic-based subscription
 * @param status         the subscription status code with what the tool knows of it
 * @param criteria       what the subscription is subscribed to: the topic, or a search expression
 * @param filters        the filters applied to the topic, as returned
 * @param error          the last error the service recorded for the subscription, when it gives one
 * @param channelType    how notifications are delivered, for example rest-hook
 * @param endpoint       the url the service calls back
 * @param payload        the media type of a notification
 * @param payloadContent how much a notification carries: empty, id-only or full-resource
 * @param heartbeat      the heartbeat period in seconds, as returned
 * @param headers        the names of the headers the service sends with a notification. Their
 *                       values are never shown: such a header is a credential for the receiver
 * @param searchMode     why a list returned it, as the bundle entry says
 * @param extensions     every other extension on the subscription and its channel, each named with
 *                       where it sat when that was not the subscription itself
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubscriptionView(String foundAt, Shown fullUrl, Shown id, List<Shown> profiles, CodedValue status, Shown criteria,
                               List<Shown> filters, Shown reason, Shown end, Shown error, Shown channelType, Shown endpoint, Shown payload,
                               CodedValue payloadContent, Shown heartbeat, Shown timeout, Shown maxCount, List<Shown> headers,
                               Shown searchMode, List<ExtensionView> extensions) {

    public SubscriptionView {
        profiles = profiles == null ? List.of() : List.copyOf(profiles);
        filters = filters == null ? List.of() : List.copyOf(filters);
        headers = headers == null ? List.of() : List.copyOf(headers);
        extensions = extensions == null ? List.of() : List.copyOf(extensions);
    }

    /** How this subscription is named when a result holds several: by its id, else by where it sat. */
    public String label() {
        return id.hasValue() ? id.value() : foundAt;
    }
}
