package com.thehiddenbrain.interop.memberid.config;

import com.thehiddenbrain.interop.memberid.domain.CoverageEvaluator;
import com.thehiddenbrain.interop.memberid.domain.MemberIdParser;
import com.thehiddenbrain.interop.memberid.domain.MemberSelector;
import com.thehiddenbrain.interop.memberid.mmi.MmiRecordMapper;
import com.thehiddenbrain.interop.memberid.vendor.VendorFormatter;
import com.thehiddenbrain.interop.memberid.vendor.VendorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The pure domain components, wired once. None of them holds state. */
@Configuration
public class DomainConfig {

    @Bean
    public MemberIdParser memberIdParser(MemberIdProperties properties) {
        return new MemberIdParser(properties.hphcDigitLengths());
    }

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
