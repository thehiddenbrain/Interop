package com.thehiddenbrain.interop.cms1500;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class Cms1500ClaimServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(Cms1500ClaimServiceApplication.class, args);
    }
}
