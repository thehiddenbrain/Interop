package org.p32h.interop.epa.pave.client.lookup;

import java.util.List;
import org.p32h.interop.epa.pave.client.support.Exchanges;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.model.VendorExchange;
import org.p32h.interop.epa.pave.model.WireHeader;
import org.p32h.interop.epa.pave.model.WireRequest;

/**
 * Turns a fixture into the same {@link VendorExchange} a live lookup would have produced, for the
 * two mock clients.
 *
 * <p>The response is classified by the rules the live path uses and then read by the same mapping,
 * so what a tester learns on mock about a status and a body holds on live. The request says that
 * nothing was sent and which file answered.
 */
final class MockLookups {

    private MockLookups() {
    }

    static VendorExchange answer(LookupFixtures.Fixture fixture, String urlTemplate) {
        WireRequest request = request(urlTemplate, "Mock profile. Answered from the fixture file " + fixture.source()
                + ". Nothing left this machine.");
        return switch (fixture.behavior()) {
            case RESPOND -> Exchanges.fromResponse(request, fixture.httpStatus(),
                    fixture.json() ? List.of(new WireHeader("Content-Type", "application/json")) : List.of(), fixture.body(), 0L, true);
            // Reported at once. The mock does not hold a tester for the configured timeout to make the point.
            case TIMEOUT -> Exchanges.timeout(request, 0L);
            case UNAVAILABLE -> Exchanges.unavailable(request, 0L, "The connection was refused or could not be opened.");
        };
    }

    /**
     * The answer for an identifier no fixture holds: HTTP 404 with a small body, which is how a
     * lookup by identifier usually says it does not know one. How the real services say it has not
     * been observed. The mapping reads the other common way, an answer that holds no record, as
     * not found as well.
     */
    static VendorExchange unknown(String urlTemplate, LookupWords words) {
        WireRequest request = request(urlTemplate, "Mock profile. No fixture file holds this " + words.identifier()
                + ", so the mock answered as a service that does not know it. Nothing left this machine.");
        String body = "{\n  \"error\" : \"not_found\",\n  \"message\" : \"No " + words.record() + " matches the identifier supplied.\"\n}";
        return Exchanges.fromResponse(request, 404, List.of(new WireHeader("Content-Type", "application/json")), body, 0L, true);
    }

    /** The lookup as it would be asked, with no resolved url: the mock profile needs none, and nothing is sent. */
    private static WireRequest request(String urlTemplate, String note) {
        return new WireRequest("GET", null, Texts.present(urlTemplate) ? urlTemplate.strip() : "",
                List.of(new WireHeader("Accept", "application/json")), null, false, note);
    }
}
