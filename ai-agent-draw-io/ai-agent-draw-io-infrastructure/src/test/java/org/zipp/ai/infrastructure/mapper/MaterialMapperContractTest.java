package org.zipp.ai.infrastructure.mapper;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialMapperContractTest {

    @Test
    void materialMapperAlwaysScopesReadsAndTtlWritesByOwnerAndGeneration() throws Exception {
        String mapper = resource("mybatis/mapper/material_foundation_mapper.xml");

        assertTrue(mapper.contains("owner_type = #{ownerType}"));
        assertTrue(mapper.contains("owner_key = #{ownerKey}"));
        assertTrue(mapper.contains("lifecycle_generation = #{expectedGeneration}"));
        assertTrue(mapper.contains("expires_at &gt; UTC_TIMESTAMP(3)"));
    }

    @Test
    void processingQueueMapperClaimsInAShortLockedTransactionAndFencesEveryWrite() throws Exception {
        String mapper = resource("mybatis/mapper/material_processing_job_mapper.xml");

        assertTrue(mapper.contains("FOR UPDATE SKIP LOCKED"));
        assertTrue(mapper.contains("fence_token = fence_token + 1"));
        assertTrue(mapper.contains("lease_owner = #{workerId}"));
        assertTrue(mapper.contains("fence_token = #{fenceToken}"));
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
