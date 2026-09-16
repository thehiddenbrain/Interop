package com.thehiddenbrain.interop.patientaccess.conformance.checks;

import com.thehiddenbrain.interop.patientaccess.conformance.Check;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckContext;
import com.thehiddenbrain.interop.patientaccess.conformance.CheckResult;
import com.thehiddenbrain.interop.patientaccess.conformance.Severity;
import com.thehiddenbrain.interop.patientaccess.fhir.SearchPage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.MultiValueMap;

/**
 * Da Vinci US Drug Formulary resources. CMS-9115-F requires formulary data for Part D / QHP plans; the
 * checks are MAY because a payer without a formulary obligation legitimately answers 404.
 */
@Configuration
public class FormularyChecks {

    private static final String USDF = "Da Vinci US Drug Formulary 2.1.0 CapabilityStatement usdf-server; CMS-9115-F 42 CFR 422.119(b)(2) "
            + "(Part D formulary) / 45 CFR 156.221(b)(2) (QHP formulary)";

    static CheckResult probe(CheckResult.Builder b, CheckContext ctx, String type, MultiValueMap<String, String> params) {
        SearchPage page = CheckSupport.search(ctx, b, type, params);
        if (page.response().status() == 404) {
            return b.info(type + " is not part of this API (HTTP 404); formulary data is only required of Part D and QHP plans");
        }
        String problem = CheckSupport.bundleProblem(page, type);
        if (problem != null) {
            return b.fail(type + " search answered " + problem);
        }
        return b.pass(CheckSupport.plural(page.count(), type + " resource") + " on the first page");
    }

    @Bean
    Check insurancePlan() {
        return SimpleCheck.of("formulary.insurancePlan", "formulary", "InsurancePlan search", Severity.MAY,
                "GET InsurancePlan?_count=5 returns HTTP 200 with a searchset (Formulary / PayerInsurancePlan resources); 404 is reported "
                        + "as information", USDF + " InsurancePlan", false,
                (b, ctx) -> probe(b, ctx, "InsurancePlan", CheckContext.params("_count", "5")));
    }

    @Bean
    Check formularyItem() {
        return SimpleCheck.of("formulary.formularyItem", "formulary", "FormularyItem (Basic) search", Severity.MAY,
                "GET Basic?code=formulary-item&_count=5 returns HTTP 200 with a searchset of FormularyItem resources; 404 is reported as "
                        + "information", USDF + " Basic (FormularyItem) code search", false,
                (b, ctx) -> probe(b, ctx, "Basic", CheckContext.params("code", "formulary-item", "_count", "5")));
    }

    @Bean
    Check medicationKnowledge() {
        return SimpleCheck.of("formulary.medicationKnowledge", "formulary", "MedicationKnowledge search", Severity.MAY,
                "GET MedicationKnowledge?status=active&_count=5 returns HTTP 200 with a searchset of FormularyDrug resources; 404 is reported "
                        + "as information", USDF + " MedicationKnowledge status search", false,
                (b, ctx) -> probe(b, ctx, "MedicationKnowledge", CheckContext.params("status", "active", "_count", "5")));
    }
}
