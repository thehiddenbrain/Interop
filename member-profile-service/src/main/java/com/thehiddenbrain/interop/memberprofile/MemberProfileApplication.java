package com.thehiddenbrain.interop.memberprofile;

import com.thehiddenbrain.interop.memberprofile.config.MemberProfileProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(MemberProfileProperties.class)
public class MemberProfileApplication {

    public static void main(String[] args) {
        SpringApplication.run(MemberProfileApplication.class, args);
    }
}
