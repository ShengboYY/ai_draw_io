import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const drawioPageSource = () => {
  const pagePath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
  return readFileSync(pagePath, 'utf8');
};

test('template buttons draft a short prompt instead of sending immediately', () => {
  const pageSource = drawioPageSource();
  const quickActionsSource = pageSource.slice(
    pageSource.indexOf('const quickActions = ['),
    pageSource.indexOf('const historyEntries ='),
  );

  assert.match(quickActionsSource, /Create a UML class diagram/);
  assert.match(quickActionsSource, /Create a sequence diagram/);
  assert.match(quickActionsSource, /Create an architecture diagram/);
  assert.match(quickActionsSource, /Create a flowchart/);
  assert.match(pageSource, /handleTemplatePrompt\(action\.text\)/);
  assert.match(pageSource, /setInputValue\(prompt\)/);
  assert.doesNotMatch(quickActionsSource, /sendContent\(action\.text\)/);
});
