package org.zipp.ai.test.domain.agent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.zipp.ai.domain.agent.service.usage.AgentTelemetryMetrics;

import static org.assertj.core.api.Assertions.assertThat;

public class AgentTelemetryMetricsBeanTest {

    @Test
    public void metricsBeanUsesProviderConstructorInSpringContext() {
        new ApplicationContextRunner()
                .withUserConfiguration(MetricsConfig.class)
                .run(context -> assertThat(context).hasSingleBean(AgentTelemetryMetrics.class));
    }

    @Configuration
    @Import(AgentTelemetryMetrics.class)
    static class MetricsConfig {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }
}
