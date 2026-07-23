package org.zipp.ai.test.domain.agent;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks down the MyBatis registration required before a grounded agent run can start. */
public class GroundedRunControlMapperConfigurationTest {

    @Test
    public void shouldRegisterGroundedRunControlMapper() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull("application.yml must be available on the test classpath", input);
            String application = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue("Grounded run control mapper must be listed in mybatis.mapper-locations",
                    application.contains("classpath*:/mybatis/mapper/grounded_run_control_mapper.xml"));
            assertTrue("Request source snapshot mapper must be listed in mybatis.mapper-locations",
                    application.contains("classpath*:/mybatis/mapper/request_source_snapshot_mapper.xml"));
        }
    }

    @Test
    public void shouldMapSnakeCaseColumnsToPersistenceObjects() throws Exception {
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("mybatis/config/mybatis-config.xml")) {
            assertNotNull("mybatis-config.xml must be available on the test classpath", input);
            String configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue("MyBatis must map snake_case database columns to camelCase persistence fields",
                    configuration.contains("name=\"mapUnderscoreToCamelCase\" value=\"true\""));
        }
    }
}
