import test from 'node:test';
import assert from 'node:assert/strict';

import { buildDiagramTitleFromPrompt } from '../src/app/drawio/diagram-title.ts';

test('buildDiagramTitleFromPrompt trims and compacts whitespace', () => {
  assert.equal(
    buildDiagramTitleFromPrompt('  Create   checkout   flow  diagram  '),
    'Create checkout flow diagram',
  );
});

test('buildDiagramTitleFromPrompt falls back for blank prompts', () => {
  assert.equal(buildDiagramTitleFromPrompt('   '), 'Untitled Diagram');
});

test('buildDiagramTitleFromPrompt keeps generated titles bounded', () => {
  const title = buildDiagramTitleFromPrompt('a'.repeat(80));

  assert.equal(title.length, 60);
});
