---
name: drawio-flowchart
description: Draw.io flowchart skill. Use for flowcharts, business processes, approval flows, algorithm steps, decisions, branches, and process outcomes.
schemaVersion: 1
category: drawio-design
diagramType: flowchart
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io Flowchart Skill

## When To Use [P0]
Use for: flowcharts, business processes, approval flows, algorithm control flow.
Prefer `drawio-architecture` for static components, `drawio-sequence` for time-ordered service calls, `drawio-state` for object status transitions.

## Shape Vocabulary [P0]
| Meaning | Shape | Style |
| --- | --- | --- |
| Start / End | pill | `rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;` |
| Action step | rectangle | `rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;` |
| Decision | diamond | `rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;` |
| Input / Output | parallelogram | `shape=parallelogram;perimeter=parallelogramPerimeter;whiteSpace=wrap;html=1;fixedSize=1;fillColor=#e1d5e7;strokeColor=#9673a6;` |
| Document / report | document | `shape=document;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=#f8cecc;strokeColor=#b85450;` |

Use 4–6 shape types at most in one flowchart.

## Rules [P0]
1. Main path runs top-to-bottom on one fixed x axis, from Start to the terminal outcome. Left-to-right only for short pipelines.
2. Routing: simple top-down paths, short pipelines, and decision tree/fan-out flows may use straight `edgeStyle=none`; complex branches, swimlanes, loops, and rejoin paths use orthogonal routing.
3. Step labels are action phrases (`Validate order`), never nouns or component names. Decision labels are questions (`Stock available?`).
4. Every decision has ≥2 outgoing edges, each labeled on the edge (`Yes`/`No`/`Approved`/`Rejected`) — never inside the diamond.
5. Branches and exceptions move to the side (usually right), visually weaker than the main path, and either terminate or merge back cleanly.
6. Retry/correction branches stay local: return to the nearest input/action by the nearest side gutter. Do not centralize unrelated failures in one distant error box; duplicate a small correction step when it keeps edges short and readable.
7. Loops route back along the side with a clear condition label; retry loops return to the smallest meaningful earlier step.
8. Never run several long retry edges through one narrow exception column. Prefer separate local correction steps; if a shared side gutter is unavoidable, use explicit orthogonal waypoints on parallel lanes at least 24 px apart. Retry routes must not overlap, use diagonal segments, or pass through/alongside alert boxes and their labels.
9. Use swimlanes only when the user mentions roles/owners; steps then get `parent="<lane-id>"` with lane-relative coordinates.
10. No orphan steps; merge branches only where the story actually rejoins.

## Golden Example [P0]

```xml
<mxCell id="2" value="Start" style="rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="420" y="40" width="140" height="50" as="geometry"/></mxCell>
<mxCell id="3" value="Validate order" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="420" y="150" width="140" height="60" as="geometry"/></mxCell>
<mxCell id="4" value="Stock available?" style="rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="1"><mxGeometry x="410" y="270" width="160" height="80" as="geometry"/></mxCell>
<mxCell id="5" value="Reserve stock" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="420" y="410" width="140" height="60" as="geometry"/></mxCell>
<mxCell id="6" value="Send confirmation" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="420" y="530" width="140" height="60" as="geometry"/></mxCell>
<mxCell id="7" value="End" style="rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="420" y="650" width="140" height="50" as="geometry"/></mxCell>
<mxCell id="8" value="Notify out of stock" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#f8cecc;strokeColor=#b85450;fontSize=12;" vertex="1" parent="1"><mxGeometry x="680" y="280" width="150" height="60" as="geometry"/></mxCell>
<mxCell id="9" value="" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="2" target="3"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="11" value="Yes" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="12" value="No" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="8"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="13" value="" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="14" value="" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="6" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="15" value="" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=1;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="8" target="7"><mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="755" y="675"/></Array></mxGeometry></mxCell>
```

Note the pattern: main path on one axis (x=490 center), exception branch pushed right, branch labels on edges, exception path rejoining End via a side waypoint instead of crossing the main path.

## Checklist [P1]
- Clear Start and End; main path obvious before reading branches.
- Every decision edge labeled; loops and exceptions don't obscure the main axis.
- Shapes follow the vocabulary table; steps are actions.
