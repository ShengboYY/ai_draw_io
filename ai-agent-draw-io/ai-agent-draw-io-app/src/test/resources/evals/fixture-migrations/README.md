# Eval fixture migrations

Fixture and Draw.io XML contracts are immutable at runtime. When `fixtureVersion` or
`xmlContractVersion` changes:

1. add a versioned migration tool or script in this directory;
2. migrate fixtures in a dedicated commit;
3. attach before/after normalized graph and rendered-image diffs;
4. update case versions and the loader's supported contract only after review;
5. rerun the full Mode B batch.

The loader must reject old contracts; it must never silently rewrite them during evaluation.
