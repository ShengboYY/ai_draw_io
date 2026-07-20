package org.zipp.ai.infrastructure.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.zipp.ai.domain.operations.RagEvaluationCase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagDatasetSeedContractTest {

    @Test
    void seedCasesUseTheProductionSchemaButCannotMasqueradeAsTheReleaseDataset() throws Exception {
        Path root = datasetRoot();
        List<Path> cases;
        try (var files = Files.list(root.resolve("cases"))) {
            cases = files.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        }

        assertTrue(cases.size() >= 3);
        RagEvaluationCaseLoader loader = new RagEvaluationCaseLoader();
        for (Path path : cases) {
            try (var input = Files.newInputStream(path)) {
                assertEquals("rag-beta-v1-seed", loader.load(input).datasetVersion());
            }
        }
    }

    @Test
    void releaseDatasetPlanPreservesTheAgreed165CaseCategoryBudget() throws Exception {
        JsonNode plan = new ObjectMapper().readTree(datasetRoot().resolve("dataset-plan.json").toFile());
        int categoryTotal = 0;
        var values = plan.path("categories").elements();
        while (values.hasNext()) categoryTotal += values.next().asInt();

        assertEquals(165, plan.path("minimumCaseCount").asInt());
        assertEquals(50, plan.path("minimumLockedCaseCount").asInt());
        assertEquals(165, categoryTotal);
    }

    private Path datasetRoot() {
        Path root = Path.of("evaluation/material-rag-beta-v1");
        return Files.exists(root) ? root : Path.of("../evaluation/material-rag-beta-v1");
    }
}
