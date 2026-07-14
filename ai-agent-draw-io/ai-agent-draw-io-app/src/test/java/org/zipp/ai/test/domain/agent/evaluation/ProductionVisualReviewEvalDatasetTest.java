package org.zipp.ai.test.domain.agent.evaluation;

import org.junit.Test;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalExecution;
import org.zipp.ai.domain.agent.model.valobj.evaluation.EvaluationTarget;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewCommand;
import org.zipp.ai.domain.agent.model.valobj.visualreview.CanvasVisualReviewResult;
import org.zipp.ai.domain.agent.service.evaluation.EvalCaseLoader;
import org.zipp.ai.domain.agent.service.visualreview.ICanvasVisualReviewer;
import org.zipp.ai.trigger.evaluation.ProductionVisualReviewLiveEvalAdapter;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ProductionVisualReviewEvalDatasetTest {

    @Test
    public void allVisualReviewCasesLoadAndRenderRealPngPixels() throws Exception {
        Path root = Path.of(getClass().getResource("/evals/visual-review-v1").toURI());
        List<Path> fixtures;
        try (var files = Files.list(root)) {
            fixtures = files.filter(path -> path.toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        List<CanvasVisualReviewCommand> commands = new ArrayList<>();
        ICanvasVisualReviewer reviewer = command -> {
            commands.add(command);
            return CanvasVisualReviewResult.builder().available(true).summary("captured").issues(List.of()).build();
        };
        ProductionVisualReviewLiveEvalAdapter adapter =
                new ProductionVisualReviewLiveEvalAdapter(reviewer, "unconfigured", "test");

        for (Path fixture : fixtures) {
            EvalCaseDefinition evalCase;
            try (var input = Files.newInputStream(fixture)) {
                evalCase = new EvalCaseLoader().load(input);
            }
            assertEquals(EvaluationTarget.VISUAL_REVIEW, evalCase.getEvaluationTarget());
            int callsBefore = commands.size();
            EvalExecution execution = adapter.execute(evalCase);
            if (commands.size() > callsBefore) {
                assertPng(commands.get(commands.size() - 1).getAfterImageDataUrl());
            }
            if (fixture.getFileName().toString().equals("provider-timeout.yaml")) {
                assertFalse(execution.getTrace().getVisualReview().getAvailable());
                assertEquals("timeout", execution.getTrace().getVisualReview().getUnavailableReason());
            }
            if (fixture.getFileName().toString().equals("schema-malformed.yaml")) {
                assertFalse(execution.getTrace().getVisualReview().getAvailable());
                assertEquals("output_schema_error", execution.getTrace().getVisualReview().getUnavailableReason());
            }
        }

        assertEquals(16, fixtures.size());
        assertEquals(fixtures.size() - 2, commands.size());
        assertTrue(fixtures.stream().anyMatch(path -> path.getFileName().toString().equals("provider-timeout.yaml")));
        assertTrue(fixtures.stream().anyMatch(path -> path.getFileName().toString().equals("schema-malformed.yaml")));
    }

    private void assertPng(String dataUrl) throws Exception {
        assertTrue(dataUrl.startsWith("data:image/png;base64,"));
        byte[] bytes = Base64.getDecoder().decode(dataUrl.substring("data:image/png;base64,".length()));
        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        assertEquals(800, image.getWidth());
        assertEquals(480, image.getHeight());
    }
}
