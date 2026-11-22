package org.p32h.interop.epa.pave.service.config;

import java.util.List;

/**
 * The values the requests read that a vendor's Postman environment may not hold, each with its
 * label and one line why (R-42). The environment form lists these first, then any other name typed.
 * A name here is a variable name the request builders already read, so a value typed for it
 * reaches them with no change of their own.
 */
final class KnownValues {

    /** The value that stands in for {@code onyx.subscription.callback-url} for one environment (R-42). */
    static final String CALLBACK_URL = "callbackUrl";

    /**
     * One known value.
     *
     * @param name  the variable name
     * @param label what the form calls it
     * @param why   why a request needs it, in one line
     */
    record Known(String name, String label, String why) {
    }

    static final List<Known> ALL = List.of(
            new Known("fhirServer", "FHIR server", "Sent in CRD as fhirServer, and the base of every fullUrl."),
            new Known("payerName", "Payer name", "PAS requires the payer as insurer."),
            new Known("payerIdentifier", "Payer identifier", "The insurer's identifier in PAS; PAS 2.2.1 also requires its system."),
            new Known("payerIdentifierSystem", "Payer identifier system", "The system of the insurer's identifier in PAS."),
            new Known("memberIdSystem", "Member ID system", "The system of the member ID on the Coverage."),
            new Known("pasRequestType", "PAS request type", "The X12 request type of each item when the Service card states none."),
            new Known("pasServiceType", "PAS service type", "The X12 service type of each item when the Service card states none."),
            new Known("pasCertificationType", "PAS certification type",
                    "The X12 certification type of each item when the Service card states none."),
            new Known("pasReportType", "PAS report type",
                    "An X12 report type code: under PAS 2.2.1 each attachment and the questionnaire response then carry documentInformation."),
            new Known(CALLBACK_URL, "Callback url",
                    "Where the vendor sends notifications, in place of onyx.subscription.callback-url for this environment."));

    private KnownValues() {
    }

    /** The known value of this name, or null for a name the form lists under "Another value". */
    static Known of(String name) {
        for (Known known : ALL) {
            if (known.name().equals(name)) {
                return known;
            }
        }
        return null;
    }
}
