package org.p32h.interop.epa.pave.client.lookup;

import java.time.Clock;
import java.time.LocalDate;
import org.p32h.interop.epa.pave.client.support.ExchangeExecutor;
import org.p32h.interop.epa.pave.client.support.OnyxTokenService;
import org.p32h.interop.epa.pave.client.support.RestClients;
import org.p32h.interop.epa.pave.config.LookupProperties;
import org.p32h.interop.epa.pave.model.EpaOperation;
import org.p32h.interop.epa.pave.model.MemberIdForms;
import org.p32h.interop.epa.pave.model.MemberLookupResult;
import org.p32h.interop.epa.pave.service.config.OperationGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Member lookup on the live profile: one call to the member API for one member ID.
 *
 * <p>What comes back is a real member. It is returned for the screen and goes nowhere else: it is
 * not cached, not kept and not logged. The response is read by {@link MemberResponseMapping#map},
 * which the mock client uses too, and everything this tool assumes about the API is in that file.
 */
@Component
@Profile("live")
public class HttpMemberLookupClient implements MemberLookupClient {

    private static final Logger log = LoggerFactory.getLogger(HttpMemberLookupClient.class);

    private final LookupSender sender;
    private final Clock clock;

    @Autowired
    public HttpMemberLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                                  RestClient.Builder builder, Clock clock) {
        // The lookup timeouts, which are shorter than the protocol timeouts: a lookup sits in front
        // of a tester finishing a field, so it gives up quickly and lets them type.
        this(executor, tokens, gateway, lookup,
                RestClients.withTimeouts(builder, lookup.member().connectTimeoutMs(), lookup.member().readTimeoutMs()), clock);
    }

    /** For tests, which bind the client to a mock server. */
    HttpMemberLookupClient(ExchangeExecutor executor, OnyxTokenService tokens, OperationGateway gateway, LookupProperties lookup,
                           RestClient client, Clock clock) {
        this.sender = new LookupSender(EpaOperation.MEMBER_LOOKUP, LookupWords.MEMBER, lookup.member(), executor, tokens, gateway, client);
        this.clock = clock;
    }

    @Override
    public MemberLookupResult lookup(MemberIdForms memberId) {
        LookupSender.Attempt attempt = sender.send(memberId.sent());
        MemberLookupResult result = attempt.call() == null
                ? MemberResponseMapping.notSent(attempt.notSent(), memberId, attempt.request())
                : MemberResponseMapping.map(attempt.call(), memberId, LocalDate.now(clock));
        // The outcome and the HTTP status only. Never the member ID, never the url, which holds the
        // ID, and never anything the API returned.
        log.info("Member lookup ended {} with HTTP status {}", result.status(),
                attempt.call() == null || attempt.call().httpStatus() == null ? "none" : attempt.call().httpStatus());
        return result;
    }
}
