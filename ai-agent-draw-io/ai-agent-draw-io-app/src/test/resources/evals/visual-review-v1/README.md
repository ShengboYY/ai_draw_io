# Production visual reviewer evals

These cases run through `ProductionVisualReviewLiveEvalAdapter` and the production reviewer port (agent 300018).
Synthetic fixtures are rendered to real PNG pixels at runtime and are never used by user traffic.

`provider-timeout.yaml` and `schema-malformed.yaml` are fault-injection cases. They are expected to pass only when the
evaluation environment injects the named provider failure; an ordinary live run should fail those expectations rather
than silently simulating production availability.
