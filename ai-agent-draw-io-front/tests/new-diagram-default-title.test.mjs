import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

test('new diagrams from home and canvas start as Untitled Diagram', () => {
  const homePath = fileURLToPath(new URL('../src/app/diagrams/page.tsx', import.meta.url));
  const drawioPath = fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url));
  const homeSource = readFileSync(homePath, 'utf8');
  const drawioSource = readFileSync(drawioPath, 'utf8');

  assert.match(homeSource, /router\.push\('\/drawio\?new=1'\)/);
  assert.match(drawioSource, /title: DEFAULT_DIAGRAM_TITLE/);
  assert.match(drawioSource, /session\.title === DEFAULT_DIAGRAM_TITLE/);
  assert.doesNotMatch(drawioSource, /title: 'New Chat'/);
});
