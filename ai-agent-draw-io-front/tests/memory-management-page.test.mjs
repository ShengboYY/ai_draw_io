import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = path => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');
const page = read('../src/app/chartbooks/memory/page.tsx');
const client = read('../src/api/memory.ts');

test('Memory page exposes scoped automatic Memory and reversible management actions', () => {
  assert.match(page, /automatic · reversible/);
  assert.match(page, /This Chartbook/);
  assert.match(page, /Across all Chartbooks/);
  assert.match(page, /client\.listUser/);
  assert.match(page, /client\.listChartbook/);
  assert.match(page, /client\.edit/);
  assert.match(page, /client\.disable/);
  assert.match(page, /client\.activate/);
  assert.match(page, /client\.remove/);
  assert.doesNotMatch(page, /client\.confirm/);
  assert.doesNotMatch(page, /client\.revoke/);
  assert.match(client, /If-Match/);
  assert.doesNotMatch(client, /\/candidates/);
});
