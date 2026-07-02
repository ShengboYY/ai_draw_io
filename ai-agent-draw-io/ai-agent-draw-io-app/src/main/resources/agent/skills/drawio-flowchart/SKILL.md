---
name: drawio-flowchart
description: Draw.io flowchart skill. Use for flowcharts, business processes, approval flows, algorithm steps, decisions, branches, and process outcomes.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Flowchart Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

Shared visual/XML/layout contract: use `drawio-visual-design` for colors, typography, grouping, spacing rhythm, connector routing, ports, waypoints, transparent labels, XML snippets, and container parent rules. Do not repeat generic connector routing, spacing, transparent label, waypoint, or container-parent XML rules here.

For flowcharts, use `flow_profile`: keep the main path visually dominant, push side branches away from the main axis, and avoid using decorative containers unless they represent real phases or swimlanes.

## 1. When To Use
Use this skill when the user asks for:
- Flowcharts.
- Business processes.
- Approval flows.
- Algorithm control flow.
- Step-by-step operational workflows.

Prefer another skill when:
- The user describes static system components: use `drawio-architecture`.
- The user describes time-ordered service interactions: use `drawio-sequence`.
- The user describes object state transitions: use `drawio-state`.

## 2. Flow Type Selection
Before drawing, choose the flow type and keep the shape vocabulary consistent.

- `process`: business or operational workflow with a clear start, actions, decisions, and terminal outcomes.
- `approval`: approval/rejection workflow with actors, review steps, decisions, and rework paths.
- `algorithm`: computational logic with input, processing, decisions, loops, and output.
- `data_flow`: high-level movement of data between process steps and stores. Use sparingly; if the user mainly wants entities/tables, use `drawio-er`.
- `swimlane`: workflow split by role, team, or system. Use lanes only when the user mentions roles/owners or when responsibility is central.

## 3. Semantic Shape Map
- Start/end: pill or ellipse.
- Normal action/process: rectangle.
- Decision/branch: diamond with a question label.
- Input/output: parallelogram.
- Database/data store: cylinder.
- Document/report/form: document shape.
- Subprocess/reusable process: framed or double-sided rectangle.
- Manual/user task: trapezoid or clearly labeled process node.
- Connector/junction: small circle only when it reduces crossing or page breaks.

Rules:
- Do not use many shape types in one small flowchart. Prefer 4-6 semantic shape types.
- Branch labels must be on the outgoing edge, not inside the decision node.
- Loops should route back on the side and must have a clear condition.
- Exception paths should be visually weaker or placed to the side.

## 4. Node Styles

### 4.1 Start / End
Use a pill or ellipse.

```xml
<mxCell id="2" value="Start" style="rounded=1;whiteSpace=wrap;html=1;arcSize=50;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;" vertex="1" parent="1">
  <mxGeometry x="300" y="60" width="140" height="60" as="geometry"/>
</mxCell>
```

### 4.2 Process Step
Use a rectangle for actions.

Style:
`rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;`

Labels should be action phrases, for example `Validate order`, `Reserve stock`, or `Send notification`.

### 4.3 Decision
Use a diamond for branches.

Style:
`rhombus;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;`

Labels should be questions, for example `Stock available?`.

### 4.4 Input / Output
Use a parallelogram for submitted data, returned data, or user input.

Style:
`shape=parallelogram;perimeter=parallelogramPerimeter;whiteSpace=wrap;html=1;fixedSize=1;fillColor=#e1d5e7;strokeColor=#9673a6;`

### 4.5 Document
Use a document shape for reports, receipts, forms, or generated documents.

Style:
`shape=document;whiteSpace=wrap;html=1;boundedLbl=1;fillColor=#f8cecc;strokeColor=#b85450;`

## 5. Flow Structure Rules

### 5.1 Main Flow Rules
- Main flow should usually move top-to-bottom; use left-to-right only for short pipelines.
- Keep the main path on one clear axis from start to terminal outcome.
- Process nodes should use action phrases, not static nouns or component names.
- Merge branches only when the story actually rejoins.

### 5.2 Branch Rules
- Decision branches must have labels such as `Yes`, `No`, `Approved`, `Rejected`, `Success`, or `Failure`.
- Branch labels belong on outgoing edges, not inside the decision node.
- Keep branch destinations visually distinct from the main path while preserving reading order.

### 5.3 Loop Rules
- Loops should route back on the side and must have a clear condition.
- Retry loops should return to the smallest meaningful earlier step, not to the global start unless that is the real behavior.
- Avoid multiple nested loops in one small flowchart; summarize or split if necessary.

### 5.4 Exception Path Rules
- Exception branches should move to one side and either end or merge back into the main flow.
- Failure, cancellation, timeout, or manual-rework paths should not obscure the normal path.
- Use weaker visual emphasis for rare exception paths unless the user asks to highlight them.

## 6. Edge Semantics
- Default process edge: single-direction arrow.
- Use the shared standard connector pattern for normal process flow.
- Use labels to distinguish branch outcomes and loop conditions.

## 7. Layout Rules
- Keep the main path centered on a fixed visual axis.
- Put branch nodes far enough from the main path that branch meaning is clear.
- Keep nodes aligned by row and column.
- Keep same-role nodes similarly sized unless labels require more room.

## 8. Quality Checklist
- A complete flow should have a clear Start and End.
- Each decision has at least two outgoing branches.
- Decision branch labels are present and meaningful.
- Process nodes represent actions, not data entities.
- Shape choices match the semantic shape map.
- The main path is visually obvious before reading all branch details.
- Loops and exception paths do not obscure the main path.
- Avoid orphan steps unless they are notes or external references.
- Avoid crossing lines through node bodies.
