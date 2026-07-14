package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks down the MyBatis registration required by the Trace Analysis admin API. */
public class TraceAnalysisMapperConfigurationTest {

    @Test
    public void shouldRegisterTraceAnalysisMapper() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull("application.yml must be available on the test classpath", input);
            String application = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue("Trace Analysis mapper must be listed in mybatis.mapper-locations",
                    application.contains("classpath:/mybatis/mapper/trace_analysis_mapper.xml"));
        }
    }
}
