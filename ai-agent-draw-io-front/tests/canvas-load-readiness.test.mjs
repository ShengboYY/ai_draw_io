import test from 'node:test';
import assert from 'node:assert/strict';

import {
  drawioCanvasLoadFingerprint,
  isExpectedDrawioCanvasLoaded,
} from '../src/app/drawio/canvas-load-readiness.ts';

const fullCanvas = [
  '<mxGraphModel dx="1200"><root><mxCell id="0"/><mxCell id="1" parent="0"/>',
  '<mxCell id="start" value="开始" style="whiteSpace=wrap;rounded=1;" vertex="1" parent="1">',
  '<mxGeometry x="100" y="40" width="120" height="60" as="geometry"/></mxCell>',
  '<mxCell id="login" value="登录" style="rounded=1;whiteSpace=wrap;" vertex="1" parent="1">',
  '<mxGeometry x="100" y="140" width="120" height="60" as="geometry"/></mxCell>',
  '<mxCell id="edge-1" edge="1" source="start" target="login" parent="1">',
  '<mxGeometry relative="1" as="geometry"/></mxCell>',
  '</root></mxGraphModel>',
].join('');

test('final load does not match a partial streaming preview', () => {
  const preview = [
    '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/>',
    '<mxCell id="start" value="开始" style="rounded=1;whiteSpace=wrap;" vertex="1" parent="1">',
    '<mxGeometry x="100" y="40" width="120" height="60" as="geometry"/></mxCell>',
    '</root></mxGraphModel>',
  ].join('');

  assert.equal(isExpectedDrawioCanvasLoaded(drawioCanvasLoadFingerprint(fullCanvas), preview), false);
});

test('load matching tolerates Draw.io attribute and style ordering changes', () => {
  const loaded = [
    '<mxGraphModel grid="1"><root><mxCell id="0"/><mxCell parent="0" id="1"/>',
    '<mxCell parent="1" vertex="1" style="rounded=1;whiteSpace=wrap;" value="开始" id="start">',
    '<mxGeometry height="60" as="geometry" width="120" y="40" x="100"/></mxCell>',
    '<mxCell parent="1" id="login" vertex="1" value="登录" style="whiteSpace=wrap;rounded=1;">',
    '<mxGeometry as="geometry" width="120" height="60" x="100" y="140"/></mxCell>',
    '<mxCell target="login" parent="1" edge="1" id="edge-1" source="start">',
    '<mxGeometry as="geometry" relative="1"/></mxCell>',
    '</root></mxGraphModel>',
  ].join('');

  assert.equal(isExpectedDrawioCanvasLoaded(drawioCanvasLoadFingerprint(fullCanvas), loaded), true);
});

test('load matching rejects an old canvas with the same cells but stale geometry', () => {
  const stale = fullCanvas.replace('y="140"', 'y="240"');

  assert.equal(isExpectedDrawioCanvasLoaded(drawioCanvasLoadFingerprint(fullCanvas), stale), false);
});
