import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const pagePath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
const pageSource = readFileSync(pagePath, 'utf8');

test('deterministic repair round state defaults to zero', () => {
  assert.match(
    pageSource,
    /\[maxDeterministicRepairRounds, setMaxDeterministicRepairRounds\] = useState\(0\)/,
  );
});
