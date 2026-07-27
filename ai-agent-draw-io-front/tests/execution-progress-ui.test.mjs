import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const pageSource = readFileSync(
  new URL('../src/app/drawio/page.tsx', import.meta.url),
  'utf8',
);

test('execution progress expands only for the active run and stays collapsed after completion', () => {
  // isLatestRunningAgent becomes false at completion, preserving the existing collapsed summary.
  assert.match(pageSource, /<details className="group\/details w-full" open=\{isLatestRunningAgent\}>/);
  assert.match(pageSource, /useChineseMessage \? '执行过程' : 'Execution trace'/);
  assert.match(pageSource, /group-open\/details:hidden.*\{useChineseMessage \? '展开' : 'Show'\}/s);
});

test('diagram restore merges cached execution progress into durable conversation messages', () => {
  assert.match(
    pageSource,
    /const cachedSession = sessionsRef\.current\.find[\s\S]*mergeRestoredConversationPresentation\([\s\S]*durableMessages,[\s\S]*cachedSession\?\.messages \|\| \[\]/,
  );
});
