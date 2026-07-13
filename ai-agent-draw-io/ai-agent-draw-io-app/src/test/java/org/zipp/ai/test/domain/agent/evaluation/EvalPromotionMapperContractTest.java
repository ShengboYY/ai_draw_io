package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Locks the database invariants that make Promote idempotent and keep published lineage restricted. */
public class EvalPromotionMapperContractTest {

    @Test
    public void workingCopyMapperUsesTheCandidateUniqueKeyAsItsConcurrencyBoundary() throws Exception {
        String mapper = resource("mybatis/mapper/eval_case_working_copy_mapper.xml");
        assertTrue(mapper.contains("selectByCandidateId"));
        assertTrue(mapper.contains("insertTraceDraftIfAbsent"));
        assertTrue(mapper.contains("on duplicate key update id=id"));
    }

    @Test
    public void restrictedLinkMapperContainsNoTracePayloadColumns() throws Exception {
        String mapper = resource("mybatis/mapper/eval_candidate_promotion_link_mapper.xml");
        assertTrue(mapper.contains("eval_candidate_promotion_link"));
        assertFalse(mapper.contains("source_run_id"));
        assertFalse(mapper.contains("payload_json"));
        assertFalse(mapper.contains("prompt_json"));
        assertFalse(mapper.contains("canvas_xml"));
    }

    @Test
    public void migrationUsesAnExecutableDuplicateGuardInsteadOfPreparingSignal() throws Exception {
        Path migrationPath = Path.of("docs/sql/migrations/2026-07-14-create-eval-promotion-links.sql");
        if (!Files.exists(migrationPath)) {
            migrationPath = Path.of("../docs/sql/migrations/2026-07-14-create-eval-promotion-links.sql");
        }
        String migration = Files.readString(migrationPath);
        assertTrue(migration.contains("CREATE PROCEDURE migrate_r7_working_candidate_unique"));
        assertTrue(migration.contains("SIGNAL SQLSTATE '45000'"));
        assertFalse(migration.contains("PREPARE candidate_unique_stmt"));
    }

    private String resource(String path) throws Exception {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(path)) {
            if (stream == null) throw new IllegalStateException("missing resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
