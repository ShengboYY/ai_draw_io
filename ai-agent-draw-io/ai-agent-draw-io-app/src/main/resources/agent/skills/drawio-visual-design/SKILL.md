---
name: drawio-visual-design
description: Draw.io house style baseline and canonical example. Always applied with every drawing action. Owns the color palette, typography, boundary/legend patterns, and the golden example that defines what a finished diagram looks like.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
  category: drawio-design
---

# Draw.io House Style

`drawio-xml-guide` owns structure/layout. This skill owns the finished look; imitate the Golden Example.

## Palette
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

## Typography And Shapes
- Diagram title: transparent text cell, fontSize=18, bold, centered above the diagram.
- Container/boundary label: 13, bold. Node label: 12. Secondary detail line: 10–11 via `&lt;br&gt;` inside the value.
- The hierarchy must be visible: the largest font (title) is at least 1.5× the smallest (detail). A diagram where everything is 11–12pt has no hierarchy.
- Stroke ladder — exactly three levels, never more: region/boundary borders `strokeWidth=2`; ordinary nodes and edges default 1px; at most one emphasized main-path edge may use `strokeWidth=2`.
- Process/service nodes: `rounded=1;whiteSpace=wrap;html=1;` + role fill. Storage: `shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;` + orange.
- Standalone text (titles, captions, edge annotations) is always transparent: `text;html=1;strokeColor=none;fillColor=none;labelBackgroundColor=none;whiteSpace=wrap;`. Filled boxes are only for real nodes, notes, and the legend.
- No shadows by default; in the Modern Product profile only, one focal node or boundary may use a subtle `shadow=1` if it clarifies hierarchy. Consistent corner rounding within the same tier.

## Modern Product Profile
- Use this for product/system architecture unless the user asks for stricter notation: quiet SaaS UI, low-saturation fills, slate text/edges, clean spacing, no decoration.
- Primary request/data edges stay solid and orthogonal with `rounded=0;strokeColor=#334155;`. Return, async, callback, and secondary edges are dashed, softer slate (`strokeColor=#64748b`), and may use `rounded=1;arcSize=10` while keeping orthogonal ports.
- Opposite request/return pairs must not share the center line. Put the request on the upper/left track (`0.3`) and the return on the lower/right track (`0.7`) so arrow direction is visually obvious.

## House Patterns
- System boundary: a dashed transparent rectangle drawn first, behind content: `rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;`. All content nodes keep `parent="1"` with absolute coordinates. Use a real container (`swimlane`) only for role/lane semantics.
- Main-path edges carry numbered labels: `1. submit request`, `2. call model`, … in the edge `value` with `labelBackgroundColor=none;fontSize=11;`.
- Return/async/secondary edges are dashed (`dashed=1`) and placed on separate tracks; requests stay solid.
- Legend: small yellow box in an empty corner, with standalone sample lines (sourcePoint/targetPoint, no source/target) and small transparent labels. Add it only when line styles or colors carry meaning that isn't obvious.

## Golden Example
Canonical output shape — a request flow with boundary, numbered edges, distinct tracks for the request/return pair, and a legend:

```xml
<mxCell id="2" value="AI Diagram System" style="text;html=1;strokeColor=none;fillColor=none;align=center;fontSize=18;fontStyle=1;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="340" y="20" width="380" height="30" as="geometry"/></mxCell>
<mxCell id="3" value="AI Diagram Service" style="rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="300" y="70" width="660" height="480" as="geometry"/></mxCell>
<mxCell id="4" value="Web Frontend" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="60" y="230" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="5" value="Backend API&lt;br&gt;&lt;font style=&quot;font-size:10px&quot;&gt;auth / routing&lt;/font&gt;" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="380" y="230" width="160" height="70" as="geometry"/></mxCell>
<mxCell id="6" value="AI Service&lt;br&gt;&lt;font style=&quot;font-size:10px&quot;&gt;generates draw.io XML&lt;/font&gt;" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontSize=12;" vertex="1" parent="1"><mxGeometry x="700" y="120" width="180" height="70" as="geometry"/></mxCell>
<mxCell id="7" value="User History DB" style="shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;backgroundOutline=1;size=15;fillColor=#ffe6cc;strokeColor=#d79b00;fontSize=12;" vertex="1" parent="1"><mxGeometry x="720" y="380" width="140" height="80" as="geometry"/></mxCell>
<mxCell id="8" value="1. draw request" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.3;entryX=0;entryY=0.3;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="9" value="2. call model" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.3;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="3. return XML" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0;exitY=0.8;entryX=1;entryY=0.6;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="6" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="11" value="4. save history" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.8;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="12" value="5. render diagram" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0;exitY=0.7;entryX=1;entryY=0.7;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="13" value="" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;" vertex="1" parent="1"><mxGeometry x="60" y="420" width="200" height="90" as="geometry"/></mxCell>
<mxCell id="14" value="Legend" style="text;html=1;strokeColor=none;fillColor=none;fontSize=11;fontStyle=1;align=left;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="72" y="428" width="80" height="18" as="geometry"/></mxCell>
<mxCell id="15" value="" style="endArrow=classic;html=1;" edge="1" parent="1"><mxGeometry relative="1" as="geometry"><mxPoint x="75" y="470" as="sourcePoint"/><mxPoint x="120" y="470" as="targetPoint"/></mxGeometry></mxCell>
<mxCell id="16" value="request / write" style="text;html=1;strokeColor=none;fillColor=none;fontSize=10;align=left;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="128" y="460" width="120" height="18" as="geometry"/></mxCell>
<mxCell id="17" value="" style="endArrow=classic;html=1;dashed=1;" edge="1" parent="1"><mxGeometry relative="1" as="geometry"><mxPoint x="75" y="495" as="sourcePoint"/><mxPoint x="120" y="495" as="targetPoint"/></mxGeometry></mxCell>
<mxCell id="18" value="return / async" style="text;html=1;strokeColor=none;fillColor=none;fontSize=10;align=left;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="128" y="485" width="120" height="18" as="geometry"/></mxCell>
```

Read from the example: numbered solid request path, dashed returns on separate tracks, boundary behind content, storage cylinder, point-anchored legend lines, and clear routing channels.

## Final Self-Check
- First principle: every shape, color, and arrow carries meaning. If removing an element loses no information, do not draw it — no decorative boxes, filler nodes, or unlabeled color blocks.
- One coherent style: consistent palette roles, aligned tiers, equal node sizes, no random colors. Complex diagrams may use 5-6 semantic colors, but every extra color needs a named role.
- No node overlaps another node; no edge label sits on a node label; no opaque white text boxes over content.
- Boundaries render behind content; legend is small and out of the way.
- Labels are short; details go to a second smaller line, not a longer box.
