package com.gatekeeper.analytics.pipeline;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@Profile("!foundation")
public class AnalyticsPipelineConfig {
}
