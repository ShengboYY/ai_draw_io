# drawio-core-v1 E0/E1 Development check

The new draw.io primary slice contains 47 dense-eligible Development cases. E1 improves Recall@10 from 0.8511 to 0.8723 (+0.0213), Recall@40 from 0.8936 to 0.9362 (+0.0426), and MRR@10 from 0.6857 to 0.7369 (+0.0512). However, all paired 95% delta intervals include zero because this initial draw.io-only Development slice is small.

**Decision:** retain E1 as the working representation, but do not use this 47-case check to open Validation or claim a new promotion. Expand draw.io generation/edit coverage first; then rerun the frozen E0/E1 comparison on the enlarged drawio-core split.

Run lock: `2026-07-22-drawio-core-run-corpus-lock.json`; comparison: `2026-07-22-drawio-core-e0-e1-development-comparison.json`.
