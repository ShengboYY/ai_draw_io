---
name: drawio-xml-guide
description: Core Draw.io contract for XML structure and layout. Always applied with every drawing action. Owns output scope, mxCell grammar, geometry, ids, ports, and the layout hard constraints that make a first draft readable.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
  category: drawio-design
  selectable: false
---

# Draw.io Core Contract

Applies to every drawing action, together with `drawio-visual-design` (style baseline + canonical example) and the selected diagram skill (domain notation only).

## Output Scope
- `create_diagram` / `modify_diagram mode=patch|append`: mxCell fragments only. No `mxfile`, `mxGraphModel`, `root`, or cells `id="0"`/`id="1"` — the backend wraps them.
- `modify_diagram mode=replace_cells`: current full XML in `xml` plus only the replacement cells in `cells`.
- `optimize_diagram mode=layout_optimize`: one complete optimized `mxGraphModel`.
- Never include markdown fences, XML comments, prose, or placeholders inside tool arguments.

## XML Rules
1. Vertex: unique `id`, `vertex="1"`, valid `parent`, `style`, and `mxGeometry` with `x`, `y`, `width`, `height`, `as="geometry"`.
2. Connected edge: `edge="1"`, `parent="1"`, `source` and `target` referencing ids that exist in the same XML, `mxGeometry relative="1" as="geometry"`.
3. Standalone line (legend sample, divider, annotation arrow): no `source`/`target`; use `mxPoint as="sourcePoint"` and `as="targetPoint"` inside the geometry instead.
4. All mxCell elements are siblings. Grouping is expressed with the `parent` attribute, never by nesting mxCell tags.
5. Children of a real container use `parent="<container-id>"` and coordinates relative to that container; keep them inside its bounds with padding.
6. On edits, preserve existing ids, geometry, and styles of untouched cells; new ids continue after the largest existing numeric id.
7. Ports are style tokens only, e.g. `exitX=1;exitY=0.5;entryX=0;entryY=0.5;` — never mxCell attributes or child tags.
8. Escape `&`, `<`, `>`, and quotes inside `value`.
9. Emit in render order: boundaries/containers first, then nodes, then edges, then floating labels and legend.
10. Text-bearing shapes use `whiteSpace=wrap;html=1;`.

## Layout Hard Constraints (Global Draw.io Layout Contract)
- Viewport: start near x=40, y=40; keep the diagram inside x 0–1100, y 0–800 unless the user asks for a large map.
- One reading direction per diagram: left-to-right for systems/data flows, top-to-bottom for processes/lifecycles, time downward for sequences.
- Grid placement: nodes in the same row share y; same column shares x. Leave a clear channel between neighboring nodes: ≥ 120 px horizontal, ≥ 60 px vertical — wider when an edge label must fit inside the channel.
- Same-tier nodes share the same size (default 160×60; storage cylinders 140×80). Do not resize a node just to fit a long label — shorten the label.
- Scope: prefer 6–14 nodes and 5–18 edges. Aggregate secondary detail into one grouped node instead of crowding.
- Hybrid routing: primary request/data edges are orthogonal with `edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;` and all four ports explicit. Return, async, callback, and secondary edges may be dashed with `rounded=1;arcSize=10`, but they still use orthogonal ports and waypoints.
- Ports follow flow direction: left-to-right uses `exitX=1` → `entryX=0`; top-to-bottom uses `exitY=1` → `entryY=0`. Never corner ports (both coordinates extreme).
- Two edges between the same pair, or a request/return pair, take different tracks: `exitY=0.3` vs `exitY=0.7`, or opposite sides. Never stack opposite arrows on the same center track.
- If a node sits on an edge's straight path, add 2–3 orthogonal waypoints with 20–30 px clearance, or route along the outer perimeter. Long cross-region edges always take the perimeter, not the center.

## Pre-flight Check (before every tool call)
- Every edge `source`/`target` id exists; every id is unique.
- No two sibling nodes overlap; every child is inside its region with ≥ 30 px container padding.
- Mentally trace each edge: no node body on its path, no label sitting on a node or another label.
- More than 3 crossings in the central reading area → rearrange nodes before emitting, don't rely on repair.
