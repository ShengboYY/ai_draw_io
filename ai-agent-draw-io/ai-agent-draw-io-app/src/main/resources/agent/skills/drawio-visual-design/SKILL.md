---
name: drawio-visual-design
description: Draw.io house style baseline and canonical example. Always applied with every drawing action. Owns the color palette, typography, boundary/legend patterns, and the golden example that defines what a finished diagram looks like.
schemaVersion: 1
category: drawio-design
diagramType: shared
selectable: false
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io House Style

`drawio-xml-guide` owns structure/layout. This skill owns the finished look; imitate the Golden Example.

## Palette [P0]
Low-saturation fills with darker strokes. One color = one semantic role. Default to 3-4 semantic colors; complex diagrams may use 5-6 named role colors when roles are real and the legend explains them. Gray boundaries, white/transparent fills, and text colors do not count. Beyond 6 roles, group details into regions, line styles, icons, or lanes.

| Role | fillColor | strokeColor | Typical use |
| --- | --- | --- | --- |
| blue | #dae8fc | #6c8ebf | primary services, main process steps |
| green | #d5e8d4 | #82b366 | entry points, actors, success states |
| orange | #ffe6cc | #d79b00 | data stores, storage, persistence |
| purple | #e1d5e7 | #9673a6 | AI/ML, middleware, queues, special systems |
| yellow | #fff2cc | #d6b656 | notes, legend, highlights |
| red | #f8cecc | #b85450 | errors, alerts, termination |
| gray | #f5f5f5 | #666666 | boundaries, neutral groups |

## Typography And Shapes [P0]
- Diagram title: transparent text cell, fontSize=18, bold, centered above the diagram.
- Container/boundary label: 13, bold. Node label: 12. Secondary detail line: 10–11 via `&lt;br&gt;` inside the value.
- The hierarchy must be visible: the largest font (title) is at least 1.5× the smallest (detail). A diagram where everything is 11–12pt has no hierarchy.
- Stroke ladder — exactly three levels, never more: region/boundary borders `strokeWidth=2`; ordinary nodes and edges default 1px; at most one emphasized main-path edge may use `strokeWidth=2`.
- Process/service nodes: `rounded=1;whiteSpace=wrap;html=1;` + role fill. Storage: `shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;` + orange.
- Standalone text (titles, captions, edge annotations) is always transparent: `text;html=1;strokeColor=none;fillColor=none;labelBackgroundColor=none;whiteSpace=wrap;`. Filled boxes are only for real nodes, notes, and the legend.
- No shadows by default; in the Modern Product profile only, one focal node or boundary may use a subtle `shadow=1` if it clarifies hierarchy. Consistent corner rounding within the same tier.

## Modern Product Profile [P1]
- Use this for product/system architecture unless the user asks for stricter notation: quiet SaaS UI, low-saturation fills, slate text/edges, clean spacing, no decoration.
- Primary request/data edges stay solid and orthogonal with `rounded=0;strokeColor=#334155;`. Return, async, callback, and secondary edges are dashed, softer slate (`strokeColor=#64748b`), and may use `rounded=1;arcSize=10` while keeping orthogonal ports.
- Opposite request/return pairs must not share the center line. Put the request on the upper/left track (`0.3`) and the return on the lower/right track (`0.7`) so arrow direction is visually obvious.

## House Patterns [P1]
- System boundary: a dashed transparent rectangle drawn first, behind content: `rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;`. All content nodes keep `parent="1"` with absolute coordinates. Use a real container (`swimlane`) only for role/lane semantics.
- Main-path edges carry numbered labels: `1. submit request`, `2. call model`, … in the edge `value` with `labelBackgroundColor=none;fontSize=11;`.
- Return/async/secondary edges are dashed (`dashed=1`) and placed on separate tracks; requests stay solid.
- Legend: small yellow box in an empty corner, with standalone sample lines (sourcePoint/targetPoint, no source/target) and small transparent labels. Add it only when line styles or colors carry meaning that isn't obvious.

## Golden Example [P0]
Canonical output shape: a request flow with boundary, numbered edges, distinct tracks for the request/return pair, and a legend.

When exact XML anchors are needed, call `get_drawio_skill_section` with `name=drawio-visual-design`, `sectionId=golden-example`, and `source=reference`.

Read from the reference example: numbered solid request path, dashed returns on separate tracks, boundary behind content, storage cylinder, point-anchored legend lines, and clear routing channels.

## Final Self-Check [P1]
- First principle: every shape, color, and arrow carries meaning. If removing an element loses no information, do not draw it — no decorative boxes, filler nodes, or unlabeled color blocks.
- One coherent style: consistent palette roles, aligned tiers, equal node sizes, no random colors. Complex diagrams may use 5-6 semantic colors, but every extra color needs a named role.
- No node overlaps another node; no edge label sits on a node label; no opaque white text boxes over content.
- Boundaries render behind content; legend is small and out of the way.
- Labels are short; details go to a second smaller line, not a longer box.
