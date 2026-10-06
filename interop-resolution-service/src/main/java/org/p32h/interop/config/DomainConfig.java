package org.p32h.interop.config;

import org.p32h.interop.domain.CoverageEvaluator;
import org.p32h.interop.domain.MemberSelector;
import org.p32h.interop.mmi.MmiRecordMapper;
import org.p32h.interop.vendor.Payer;
import org.p32h.interop.vendor.VendorFormatter;
import org.p32h.interop.vendor.VendorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The pure domain components, wired once. None of them holds state. */
@Configuration
public class DomainConfig {

    @Bean
    public CoverageEvaluator coverageEvaluator() {
        return new CoverageEvaluator();
    }

    @Bean
    public MemberSelector memberSelector(CoverageEvaluator evaluator) {
        return new MemberSelector(evaluator);
    }

    @Bean
    public VendorRegistry vendorRegistry(MemberIdProperties properties, PayerProperties payer) {
        return new VendorRegistry(properties.vendors(), new Payer(payer.id(), payer.name()));
    }

    @Bean
    public VendorFormatter vendorFormatter() {
        return new VendorFormatter();
    }

    @Bean
    public MmiRecordMapper mmiRecordMapper() {
        return new MmiRecordMapper();
    }
}
