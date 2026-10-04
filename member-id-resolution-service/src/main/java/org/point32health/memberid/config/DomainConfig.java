package org.point32health.memberid.config;

import org.point32health.memberid.domain.CoverageEvaluator;
import org.point32health.memberid.domain.MemberSelector;
import org.point32health.memberid.mmi.MmiRecordMapper;
import org.point32health.memberid.vendor.VendorFormatter;
import org.point32health.memberid.vendor.VendorRegistry;
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
    public VendorRegistry vendorRegistry(MemberIdProperties properties) {
        return new VendorRegistry(properties.vendors());
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
