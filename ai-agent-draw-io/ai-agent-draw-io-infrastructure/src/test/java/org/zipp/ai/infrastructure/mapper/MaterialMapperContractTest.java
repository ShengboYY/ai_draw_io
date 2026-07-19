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
        assertTrue(mapper.contains("lease_until &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("collection=\"stages\""));
    }

    @Test
    void uploadMapperScopesBrowserReadsAndFencesEveryWorkerCommit() throws Exception {
        String mapper = resource("mybatis/mapper/material_upload_session_mapper.xml");

        assertTrue(mapper.contains("owner_type = #{ownerType}"));
        assertTrue(mapper.contains("owner_key = #{ownerKey}"));
        assertTrue(mapper.contains("state = 'CREATED' AND generation = #{expectedGeneration}"));
        assertTrue(mapper.contains("state = 'CREATED' AND policy_expires_at &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("j.lease_owner = #{workerId}"));
        assertTrue(mapper.contains("j.fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("j.lease_until &gt; UTC_TIMESTAMP(3)"));
    }

    @Test
    void materializationMapperLocksOwnedContentAndFencesVisibilityTransitions() throws Exception {
        String mapper = resource("mybatis/mapper/material_materialization_mapper.xml");

        assertTrue(mapper.contains("FOR UPDATE"));
        assertTrue(mapper.contains("owner_type = #{ownerType}"));
        assertTrue(mapper.contains("owner_key = #{ownerKey}"));
        assertTrue(mapper.contains("content_sha256 = #{contentSha256}"));
        assertTrue(mapper.contains("lifecycle_state = 'ACTIVE'"));
        assertTrue(mapper.contains("security_status = 'VALIDATED'"));
        assertTrue(mapper.contains("j.fence_token = #{fenceToken}"));
        assertTrue(mapper.contains("j.lease_until &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("j.upload_session_id = u.id"));
        assertTrue(mapper.contains("j.stage = 'PROMOTE_ORIGINAL'"));
        assertTrue(mapper.contains("m.lifecycle_generation = u.material_lifecycle_generation"));
        assertTrue(mapper.contains("m.expires_at &gt; UTC_TIMESTAMP(3)"));
        assertTrue(mapper.contains("r.state = 'PROCESSING'"));
        assertTrue(mapper.contains("b.status = 'PROMOTING'"));
        assertTrue(mapper.contains("b.original_object_version_id = #{objectVersionId}"));
        assertTrue(mapper.contains("status = 'AVAILABLE'"));
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
