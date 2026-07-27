package org.zipp.ai.infrastructure.evaluation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.zipp.ai.domain.operations.RagEvaluationCase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RagEvaluationCaseLoaderTest {

    @Test
    void loadsAContentSafeVersionedGroundingCase() throws Exception {
        RagEvaluationCase value = new RagEvaluationCaseLoader().load(stream("""
                {
                  "schemaVersion":"rag-eval-case-v1",
                  "caseId":"text-definition-001",
                  "datasetVersion":"rag-beta-v1",
                  "category":"DIGITAL_PDF",
                  "language":"zh",
                  "locked":true,
                  "ownerFixture":{"ownerType":"USER","ownerKeyAlias":"owner_a",
                    "scopeType":"LIBRARY","scopeKeyAlias":"personal"},
                  "queryShape":"FACT","targetKind":"WHOLE_DIAGRAM",
                  "query":"总结迭代计划的定义","expectedRoute":"TEXT",
                  "allowedSourceVersions":["version_agile_v1"],
                  "goldEvidenceIds":["evidence_iteration_definition"],
                  "hardNegativeIds":["evidence_iteration_review"],
                  "requiredFacets":["迭代计划"],
                  "forbiddenSourceIds":["version_other_owner"],
                  "visualVerificationRequired":false,"canvasMutationAllowed":false,
                  "expectedCitationClaims":["iteration_planning_definition"]
                }
                """));

        assertEquals("text-definition-001", value.caseId());
        assertEquals(1, value.goldEvidenceIds().size());
        assertEquals("owner_a", value.ownerFixture().ownerKeyAlias());
    }

    @Test
    void rejectsCasesWithoutGoldEvidenceOrWithRawOwnerIdentifiers() {
        String invalid = """
                {"schemaVersion":"rag-eval-case-v1","caseId":"invalid-001",
                 "datasetVersion":"rag-beta-v1","category":"DIGITAL_PDF","language":"en",
                 "locked":false,"ownerFixture":{"ownerType":"USER","ownerKeyAlias":"usr_live_123",
                 "scopeType":"LIBRARY","scopeKeyAlias":"personal"},"queryShape":"FACT",
                 "targetKind":"WHOLE_DIAGRAM","query":"Define iteration planning",
                 "expectedRoute":"TEXT","allowedSourceVersions":["version_1"],"goldEvidenceIds":[],
                 "hardNegativeIds":[],"requiredFacets":["planning"],"forbiddenSourceIds":[],
                 "visualVerificationRequired":false,"canvasMutationAllowed":false,
                 "expectedCitationClaims":["definition"]}
                """;

        assertThrows(IllegalArgumentException.class,
                () -> new RagEvaluationCaseLoader().load(stream(invalid)));
    }

    private ByteArrayInputStream stream(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
