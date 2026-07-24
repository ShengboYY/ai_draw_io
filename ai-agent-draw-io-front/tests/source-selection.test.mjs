import test from 'node:test';
import assert from 'node:assert/strict';

import {
  buildSourceDeclaration,
  sourceModeAfterVersionSelection,
} from '../src/features/sources/source-selection.ts';

test('source declaration preserves the existing chat payload when no material is selected', () => {
  assert.deepEqual(buildSourceDeclaration({ attachmentUploadIds: [], sourceMode: 'AUTO', selectedVersionIds: [] }), {});
});

test('source declaration only passes opaque attachment and version ids', () => {
  assert.deepEqual(buildSourceDeclaration({
    attachmentUploadIds: ['upl-1', 'upl-1'],
    sourceMode: 'EXPLICIT_ONLY',
    selectedVersionIds: ['ver-1', 'ver-2', 'ver-1'],
  }), {
    attachmentUploadIds: ['upl-1'],
    sourceMode: 'EXPLICIT_ONLY',
    selectedVersionIds: ['ver-1', 'ver-2'],
  });
});

test('library selection opts into explicit retrieval without silently widening scope when cleared', () => {
  assert.equal(sourceModeAfterVersionSelection('AUTO', 1), 'EXPLICIT');
  assert.equal(sourceModeAfterVersionSelection('NONE', 1), 'EXPLICIT_ONLY');
  assert.equal(sourceModeAfterVersionSelection('EXPLICIT', 0), 'AUTO');
  assert.equal(sourceModeAfterVersionSelection('EXPLICIT_ONLY', 0), 'NONE');
});
