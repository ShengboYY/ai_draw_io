import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import {
  buildManualCanvasSaveRequest,
  latestCanvasVersion,
  shouldHandleManualAutosave,
  shouldCreateConversationDiagramShell,
} from '../src/app/drawio/manual-canvas-save.ts';
import {
  shouldRetryManualCanvasSaveConflict,
} from '../src/app/drawio/canvas-persistence-coordinator.ts';

test('buildManualCanvasSaveRequest keeps the current canvas version for autosave', () => {
  const request = buildManualCanvasSaveRequest({
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    canvasVersion: 4,
    canvasXml: '<mxGraphModel />',
  });

  assert.deepEqual(request, {
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    expectedVersion: 4,
    canvasXml: '<mxGraphModel />',
  });
});

test('buildManualCanvasSaveRequest skips incomplete autosave payloads', () => {
  assert.equal(buildManualCanvasSaveRequest({
    userId: '',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    canvasXml: '<mxGraphModel />',
  }), null);
  assert.equal(buildManualCanvasSaveRequest({
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: '',
    canvasXml: '<mxGraphModel />',
  }), null);
  assert.equal(buildManualCanvasSaveRequest({
    userId: 'anon_123',
    sessionId: 'session-1',
    diagramId: 'diagram-1',
    canvasXml: '   ',
  }), null);
});

test('buildManualCanvasSaveRequest carries repair lineage only for a later manual edit', () => {
  const provenance = {
    sourceRunId: 'aru_source123',
    repairRunId: 'aru_repair123',
    repairRound: 1,
    repairedCanvasXml: '<mxGraphModel><root/></mxGraphModel>',
  };
  const unchanged = buildManualCanvasSaveRequest({
    userId: 'anon_123', sessionId: 'session-1', diagramId: 'diagram-1',
    canvasXml: provenance.repairedCanvasXml, visualRepairProvenance: provenance,
  });
  const edited = buildManualCanvasSaveRequest({
    userId: 'anon_123', sessionId: 'session-1', diagramId: 'diagram-1',
    canvasXml: '<mxGraphModel><root><mxCell id="2"/></root></mxGraphModel>',
    visualRepairProvenance: provenance,
  });

  assert.equal(unchanged.visualRepairRunId, undefined);
  assert.equal(edited.visualRepairSourceRunId, 'aru_source123');
  assert.equal(edited.visualRepairRunId, 'aru_repair123');
  assert.equal(edited.visualRepairRound, 1);
});

test('latestCanvasVersion picks the highest known version', () => {
  assert.equal(latestCanvasVersion(4, 5), 5);
  assert.equal(latestCanvasVersion(5, 4), 5);
  assert.equal(latestCanvasVersion(undefined, 4), 4);
  assert.equal(latestCanvasVersion(4, undefined), 4);
});

test('latestCanvasVersion returns undefined when no version is known', () => {
  assert.equal(latestCanvasVersion(), undefined);
  assert.equal(latestCanvasVersion(undefined, undefined), undefined);
  assert.equal(latestCanvasVersion(Number.NaN), undefined);
});

test('shouldRetryManualCanvasSaveConflict retries only when the queued XML is still current', () => {
  assert.equal(shouldRetryManualCanvasSaveConflict({
    queuedCanvasXml: '<mxGraphModel><root><mxCell id="2" value="old"/></root></mxGraphModel>',
    currentCanvasXml: '<mxGraphModel><root><mxCell id="2" value="old"/></root></mxGraphModel>',
    aiMutationInFlight: false,
  }), true);
});

test('shouldRetryManualCanvasSaveConflict drops stale autosave after AI updates the canvas', () => {
  assert.equal(shouldRetryManualCanvasSaveConflict({
    queuedCanvasXml: '<mxGraphModel><root><mxCell id="2" value="old"/></root></mxGraphModel>',
    currentCanvasXml: '<mxGraphModel><root><mxCell id="2" value="new"/></root></mxGraphModel>',
    aiMutationInFlight: false,
  }), false);
});

test('shouldRetryManualCanvasSaveConflict does not retry while an AI mutation owns the diagram', () => {
  assert.equal(shouldRetryManualCanvasSaveConflict({
    queuedCanvasXml: '<mxGraphModel><root><mxCell id="2" value="old"/></root></mxGraphModel>',
    currentCanvasXml: '<mxGraphModel><root><mxCell id="2" value="old"/></root></mxGraphModel>',
    aiMutationInFlight: true,
  }), false);
});

test('shouldHandleManualAutosave accepts XML events before editor ready state catches up', () => {
  assert.equal(shouldHandleManualAutosave({
    currentSessionId: 'session-1',
    editorReady: false,
    exportingForChat: false,
    exportingThumbnail: false,
    hasInlineXml: true,
  }), true);
});

test('shouldHandleManualAutosave only needs editor readiness for export fallback events', () => {
  assert.equal(shouldHandleManualAutosave({
    currentSessionId: 'session-1',
    editorReady: false,
    exportingForChat: false,
    exportingThumbnail: false,
    hasInlineXml: false,
  }), false);
  assert.equal(shouldHandleManualAutosave({
    currentSessionId: 'session-1',
    editorReady: true,
    exportingForChat: false,
    exportingThumbnail: false,
    hasInlineXml: false,
  }), true);
});

test('shouldCreateConversationDiagramShell saves a chat-only empty diagram', () => {
  assert.equal(shouldCreateConversationDiagramShell({
    diagramId: 'diagram-1',
    canvasVersion: undefined,
    hasConversationMessages: true,
  }), true);
});

test('shouldCreateConversationDiagramShell skips requests without conversation messages', () => {
  assert.equal(shouldCreateConversationDiagramShell({
    diagramId: 'diagram-1',
    canvasVersion: undefined,
    hasConversationMessages: false,
  }), false);
});

test('shouldCreateConversationDiagramShell skips already-saved diagrams', () => {
  assert.equal(shouldCreateConversationDiagramShell({
    diagramId: 'diagram-1',
    canvasVersion: 1,
    hasConversationMessages: true,
  }), false);
});

test('drawio page prepares the diagram before building the chat request', () => {
  const pageSource = readFileSync(
    fileURLToPath(new URL('../src/app/drawio/page.tsx', import.meta.url)),
    'utf8',
  );
  const preparation = pageSource.indexOf('const diagramPrepared = await ensureConversationDiagramShell({');
  const request = pageSource.indexOf('const requestPayload = buildDrawioChatRequestPayload({');

  assert.notEqual(preparation, -1);
  assert.notEqual(request, -1);
  assert.ok(preparation < request);
});
