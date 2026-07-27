import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = path => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');
const page = read('../src/app/chartbooks/[chartbookId]/memory/page.tsx');
const client = read('../src/api/memory.ts');

test('Memory page exposes explicit confirmation and owner-fenced management actions', () => {
  assert.match(page, /explicit confirmation only/);
  assert.match(page, /client\.confirm/);
  assert.match(page, /client\.revoke/);
  assert.match(page, /client\.edit/);
  assert.match(page, /client\.disable/);
  assert.match(page, /client\.remove/);
  assert.match(client, /declarationDigest/);
  assert.match(client, /If-Match/);
  assert.match(client, /\/candidates/);
});
