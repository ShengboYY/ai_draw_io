package org.zipp.ai.test.operations;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Locks down the explicit MyBatis registration required by the capability dashboard. */
public class MaterialOperationsMapperConfigurationTest {
    @Test
    public void shouldRegisterMaterialOperationsMapper() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull("application.yml must be available on the test classpath", input);
            String application = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue("material operations mapper must be explicitly registered",
                    application.contains("classpath*:/mybatis/mapper/material_operations_mapper.xml"));
        }
    }
}
