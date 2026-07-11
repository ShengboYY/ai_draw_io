package org.zipp.ai.domain.agent.service.evaluation;

import org.zipp.ai.domain.agent.model.valobj.evaluation.EvalCaseDefinition;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Loads release-only cases from an external controlled path, never from the product repository. */
public class SequesteredEvalCaseLoader {
    private final EvalCaseLoader loader = new EvalCaseLoader();

    public List<EvalCaseDefinition> load(Path configuredRoot, Path repositoryRoot) throws Exception {
        if (configuredRoot == null || !Files.isDirectory(configuredRoot)) return List.of();
        Path root = configuredRoot.toRealPath();
        Path repository = repositoryRoot == null ? null : repositoryRoot.toRealPath();
        if (repository != null && root.startsWith(repository)) {
            throw new IllegalArgumentException("sequestered eval root must be outside the product repository");
        }
        List<EvalCaseDefinition> cases = new ArrayList<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(file -> file.toString().endsWith(".yaml") || file.toString().endsWith(".yml")).sorted().toList()) {
                Path real = path.toRealPath();
                if (!real.startsWith(root)) throw new IllegalArgumentException("sequestered case escaped configured root");
                try (InputStream input = Files.newInputStream(real)) { cases.add(loader.load(input)); }
            }
        }
        return cases;
    }
}
