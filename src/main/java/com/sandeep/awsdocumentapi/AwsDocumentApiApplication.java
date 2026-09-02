package com.sandeep.awsdocumentapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AwsDocumentApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AwsDocumentApiApplication.class, args);
    }

}
