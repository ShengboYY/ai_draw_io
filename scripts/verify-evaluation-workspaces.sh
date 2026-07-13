#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# These tests cover the six acceptance journeys in the workspace design with synthetic data and test adapters.
(
  cd "$ROOT_DIR/ai-agent-draw-io"
  mvn -pl ai-agent-draw-io-app -am \
    -Dtest=EvalPublishingServiceTest,EvalCaseLifecycleServiceTest,EvaluationTargetAdapterDiskE2ETest,EvalRunOrchestratorTest,EvalLiveRunOrchestratorTest,EvalTargetReportServiceTest,TraceAnalysisJobServiceTest,ChatSemanticAnomalyMinerTest,EvalCasePromotionServiceTest,EvalReleaseGateServiceTest,EvalTargetGateCompositionServiceTest,EvaluationReleaseGateAdminControllerTest \
    -Dsurefire.failIfNoSpecifiedTests=false test
)

# Node's type stripping is required because several focused UI tests import small TypeScript helpers directly.
(
  cd "$ROOT_DIR/ai-agent-draw-io-front"
  # --experimental-strip-types is available from Node 22.6; fail with an actionable message on older runtimes.
  node -e 'const [major, minor] = process.versions.node.split(".").map(Number); if (major < 22 || (major === 22 && minor < 6)) { console.error("Evaluation UI acceptance tests require Node >= 22.6; found " + process.versions.node); process.exit(1); }'
  node --experimental-strip-types --test \
    tests/admin-evaluation-workspace.test.mjs \
    tests/admin-eval-cases-page.test.mjs \
    tests/admin-eval-datasets-page.test.mjs \
    tests/admin-eval-runs-page.test.mjs \
    tests/admin-eval-candidates-page.test.mjs \
    tests/admin-eval-candidate-review-workflow.test.mjs \
    tests/admin-eval-operations-page.test.mjs \
    tests/admin-diagram-trace-page.test.mjs
)

echo "Evaluation/Trace workspace acceptance verification passed."
