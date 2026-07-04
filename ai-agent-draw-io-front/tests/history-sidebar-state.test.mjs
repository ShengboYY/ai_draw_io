import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

test('drawio history sidebar does not reopen from persisted browser state', () => {
  const pagePath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');

  const restoresPersistedHistory = /localStorage\.getItem\(SIDEBAR_OPEN_STORAGE_KEY\)/.test(pageSource);
  const writesPersistedHistory = /localStorage\.setItem\(SIDEBAR_OPEN_STORAGE_KEY/.test(pageSource);
  const clearsLegacyPersistedHistory = /localStorage\.removeItem\(SIDEBAR_OPEN_STORAGE_KEY\)/.test(pageSource);

  assert.equal(restoresPersistedHistory, false, 'history sidebar should not restore from localStorage');
  assert.equal(writesPersistedHistory, false, 'history sidebar should not persist to localStorage');
  assert.equal(clearsLegacyPersistedHistory, true, 'legacy persisted history sidebar state should be cleared');
});

test('drawio history sidebar renders database diagrams instead of local sessions', () => {
  const pagePath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');
  const sidebarSource = pageSource.slice(
    pageSource.indexOf('{isSidebarOpen && ('),
    pageSource.indexOf('{/* Main Layout */}'),
  );

  const loadsDatabaseDiagrams = /agentApi\.listDiagrams/.test(pageSource);
  const rendersHistoryEntries = /historyEntries\.map/.test(sidebarSource);
  const rendersLocalSessions = /\[\.\.\.sessions\]\.sort/.test(sidebarSource);

  assert.equal(loadsDatabaseDiagrams, true, 'history sidebar should load diagrams from the API');
  assert.equal(rendersHistoryEntries, true, 'history sidebar should render database history entries');
  assert.equal(rendersLocalSessions, false, 'history sidebar should not render local sessions');
});
