package org.p32h.interop.epa.pave.service.pas;

import org.p32h.interop.epa.pave.model.CodeInput;
import org.p32h.interop.epa.pave.model.Texts;
import org.p32h.interop.epa.pave.service.run.RunInputs;
import java.math.BigDecimal;
import java.util.List;

/**
 * The checks the Service card's fields pass before a submission is built, each refused at its own
 * field so the Submit step can point at it.
 *
 * <p>Three of the lists are closed: X12 has no certification type, request category or level of
 * service for a 278 request beyond the ones {@link PasOptions} offers, so any other value is a typing
 * slip and is refused with the list. Place of service, service type and unit are bound to code lists
 * far longer than the screen offers, so only their form is checked: refusing a real code the short
 * list lacks would stop a tester who knows the code their payer wants. Every value is sent exactly as
 * given, because X12 codes are case sensitive.
 *
 * <p>Nothing here is required. A field left blank is a gap the preview lists under "To complete
 * before sending", never a refusal: an incomplete request is a negative test the tester may send.
 */
final class PasCardChecks {

    private PasCardChecks() {
    }

    static void check(PasSubmitRequest request, RunInputs inputs) {
        closed(inputs, "certificationType", request.certificationType(), PasOptions.CURRENT.certificationTypes(), "certification type");
        closed(inputs, "requestType", request.requestType(), PasOptions.CURRENT.requestTypes(), "request type");
        closed(inputs, "levelOfService", request.levelOfService(), PasOptions.CURRENT.levelsOfService(), "level of service");
        String place = Texts.trimToNull(request.placeOfService());
        inputs.require(place == null || (place.length() == 2 && digits(place)), "placeOfService",
                "A place of service is a two-digit CMS code, such as 11 for office.");
        String service = Texts.trimToNull(request.serviceType());
        inputs.require(service == null || (service.length() <= 2 && lettersOrDigits(service)), "serviceType",
                "A service type is an X12 code of one or two capital letters or digits, such as 1 for medical care.");
        List<CodeInput> codes = request.procedureCodes();
        for (int i = 0; i < codes.size(); i++) {
            CodeInput code = codes.get(i);
            if (code == null) {
                continue;
            }
            BigDecimal quantity = code.quantity();
            inputs.require(quantity == null || quantity.signum() > 0, "procedureCodes[" + i + "].quantity",
                    "A quantity is more than 0. Leave it blank when none is stated.");
            String unit = Texts.trimToNull(code.unit());
            inputs.require(unit == null || (unit.length() == 2 && lettersOrDigits(unit)), "procedureCodes[" + i + "].unit",
                    "A unit is an X12 code of two capital letters or digits, such as FL for units or DY for days.");
        }
        // The same checks the coverage check and the questionnaire request give it (D-321).
        inputs.servicingProvider(request.servicingProvider());
    }

    private static void closed(RunInputs inputs, String field, String value, List<PasOptions.Option> list, String what) {
        String given = Texts.trimToNull(value);
        if (given == null || PasOptions.label(list, given) != null) {
            return;
        }
        StringBuilder codes = new StringBuilder();
        for (PasOptions.Option option : list) {
            codes.append(codes.isEmpty() ? "" : ", ").append(option.code()).append(" (").append(option.label()).append(')');
        }
        inputs.require(false, field, "A " + what + " is one of " + codes + ".");
    }

    private static boolean digits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean lettersOrDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z'))) {
                return false;
            }
        }
        return true;
    }
}
