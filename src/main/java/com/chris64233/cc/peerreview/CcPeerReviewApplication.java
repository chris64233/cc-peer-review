package com.chris64233.cc.peerreview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class CcPeerReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(CcPeerReviewApplication.class, args);
    }
}
