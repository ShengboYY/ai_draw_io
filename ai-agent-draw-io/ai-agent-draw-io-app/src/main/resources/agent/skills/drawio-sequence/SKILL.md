---
name: drawio-sequence
description: Draw.io sequence diagram skill. Use for sequence diagrams, interactions, lifelines, synchronous calls, asynchronous events, returns, self calls, and API call order.
schemaVersion: 1
category: drawio-design
diagramType: sequence
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io Sequence Diagram Skill

## When To Use [P0]
Use for: sequence diagrams, API call order, request–response chains, time-ordered interactions.
Prefer `drawio-architecture` for static structure, `drawio-flowchart` for steps without participants, `drawio-state` for status transitions.

## Rules [P0]
1. Participants are `shape=umlLifeline` shapes, all sharing the same y, evenly spaced x (e.g. 80, 320, 560, 800), width 120. Order left-to-right by call direction: initiator → gateway → services → storage/external.
2. 3–6 participants; merge similar ones beyond that. Restrained coloring — temporal order matters more than decoration.
3. Messages connect lifelines with `exitX=0.5;exitY=<f>;entryX=0.5;entryY=<f>` where the fraction f = (message y − lifeline y) / lifeline height. Fractions strictly increase down the page; keep ≥0.08 between consecutive messages.
4. Message styles: synchronous call `endArrow=block;endFill=1`, async event `endArrow=open`, return `dashed=1;endArrow=open` placed below the call it answers. All with `html=1;verticalAlign=bottom;labelBackgroundColor=none;`.
5. Number the labels: `1. submitOrder()`, `2. createOrder()`, returns as values (`5. orderId`).
6. Lifeline height covers the last message plus margin; no lifeline without messages.
7. Activation bars are optional children of a lifeline (`parent="<lifeline-id>"`, x centered, relative y) — add them only when nesting depth matters.
8. Combined fragments (`alt`/`opt`/`loop`/`par`) are transparent dashed rectangles spanning the involved lifelines and time range, label top-left — only when they clarify real control logic.
9. A sequence diagram is not a flowchart: no diamonds, no free-floating boxes.

## Golden Example [P0]

```xml
<mxCell id="2" value="Client" style="shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="80" y="60" width="120" height="420" as="geometry"/></mxCell>
<mxCell id="3" value="API Gateway" style="shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="320" y="60" width="120" height="420" as="geometry"/></mxCell>
<mxCell id="4" value="Order Service" style="shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="560" y="60" width="120" height="420" as="geometry"/></mxCell>
<mxCell id="5" value="Database" style="shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=12;" vertex="1" parent="1"><mxGeometry x="800" y="60" width="120" height="420" as="geometry"/></mxCell>
<mxCell id="6" value="1. submitOrder()" style="html=1;verticalAlign=bottom;endArrow=block;endFill=1;exitX=0.5;exitY=0.2;entryX=0.5;entryY=0.2;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="2" target="3"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="7" value="2. createOrder()" style="html=1;verticalAlign=bottom;endArrow=block;endFill=1;exitX=0.5;exitY=0.32;entryX=0.5;entryY=0.32;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="8" value="3. INSERT order" style="html=1;verticalAlign=bottom;endArrow=block;endFill=1;exitX=0.5;exitY=0.45;entryX=0.5;entryY=0.45;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="9" value="4. ok" style="html=1;verticalAlign=bottom;dashed=1;endArrow=open;exitX=0.5;exitY=0.57;entryX=0.5;entryY=0.57;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="5. orderId" style="html=1;verticalAlign=bottom;dashed=1;endArrow=open;exitX=0.5;exitY=0.72;entryX=0.5;entryY=0.72;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="2"><mxGeometry relative="1" as="geometry"/></mxCell>
```

Note the pattern: fixed lifeline columns, message fractions strictly increasing (0.2 → 0.32 → 0.45 → 0.57 → 0.72), solid filled arrows for calls, dashed open arrows for returns below their calls.

## Checklist [P1]
- Time flows strictly downward; every message has source and target lifelines.
- Returns dashed and below their calls; labels numbered.
- No unused participants, no flowchart shapes.
