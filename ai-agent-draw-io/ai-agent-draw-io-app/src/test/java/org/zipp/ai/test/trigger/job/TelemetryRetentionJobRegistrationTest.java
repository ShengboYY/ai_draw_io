package org.zipp.ai.test.trigger.job;

import org.junit.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.zipp.ai.domain.agent.service.debugtrace.AgentDebugTraceService;
import org.zipp.ai.domain.agent.service.usage.AgentUsageTelemetryService;
import org.zipp.ai.trigger.job.TelemetryRetentionJob;

import static org.assertj.core.api.Assertions.assertThat;

public class TelemetryRetentionJobRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(JobConfig.class);

    @Test
    public void cleanupJobIsDisabledUnlessExplicitlyEnabled() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean(TelemetryRetentionJob.class));
    }

    @Test
    public void cleanupJobRegistersWhenExplicitlyEnabled() {
        contextRunner
                .withPropertyValues("zipp.telemetry.cleanup-enabled=true")
                .run(context -> assertThat(context).hasSingleBean(TelemetryRetentionJob.class));
    }

    @Configuration
    @Import(TelemetryRetentionJob.class)
    static class JobConfig {
        @Bean
        AgentDebugTraceService agentDebugTraceService() {
            return new AgentDebugTraceService(null, null);
        }

        @Bean
        AgentUsageTelemetryService agentUsageTelemetryService() {
            return new AgentUsageTelemetryService(null);
        }
    }
}
