# E6b Development task hydration rerun 1

- Date: 2026-07-23
- Commit: `916fd9af311fd5c170958b3ffb0e9acf0c68bfb0`
- Run ID: `drawiohydrationpdfresearch_9021e47dab45433db7ae53e0c6ca8f89`
- Scope: six synthetic Development tasks, temporary Pinecone dev namespace only; vectors deleted after the run.
- Result: 61 retrieval chunks. The repaired Figure 2 visual chunk was searchable and carried the frozen source image, but it ranked 10 for `dgt-dev-02`; the frozen paired context limit is 8.
- Decision: exporter correctly rejected the context for its required-artifact gate. No prompt bundle, model call, or Validation run occurred.
- Next: rerun after the source-backed caption wording was refined without adding visual node sequence or evaluator gold.
