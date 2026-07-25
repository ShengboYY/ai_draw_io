package org.zipp.ai.test.app;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks down the mapper registration required before anonymous uploads can establish ownership. */
public class AnonymousWorkspaceMapperConfigurationTest {

    @Test
    public void shouldRegisterAnonymousWorkspaceMapper() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull("application.yml must be available on the test classpath", input);
            String application = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue("Anonymous workspace mapper must be listed in mybatis.mapper-locations",
                    application.contains("classpath:/mybatis/mapper/anonymous_workspace_mapper.xml"));
        }
    }
}
