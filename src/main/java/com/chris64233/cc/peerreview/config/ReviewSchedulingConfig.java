package com.chris64233.cc.peerreview.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(ReviewDeadlineProperties.class)
public class ReviewSchedulingConfig {
}
