---
name: drawio-usecase
description: Draw.io use case diagram skill. Use for actors, use cases, system boundaries, actor-function relationships, include relationships, extend relationships, and requirement scope diagrams.
schemaVersion: 1
category: drawio-design
diagramType: usecase
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io Use Case Diagram Skill

## When To Use [P0]
Use for: use case diagrams, actors and system functions, requirement scope, include/extend.
Prefer `drawio-uml` for internal classes, `drawio-flowchart` for operation steps, `drawio-er` for tables.

## Rules [P0]
1. The system boundary is the visual anchor: one labeled transparent rectangle in the center (`rounded=0;whiteSpace=wrap;html=1;fillColor=none;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;`). Use cases inside, actors outside — no exceptions.
2. Actors are `shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;` sized ~40×80. Primary human actors left, external systems / secondary actors right. Actor names are roles or external systems, never components.
3. Use cases are same-sized ellipses (`ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;` ~160×70) named as verb-object goals (`Place Order`) — never UI buttons, tables, or endpoints.
4. Actor→use case: plain association line (`endArrow=none`).
5. `&lt;&lt;include&gt;&gt;`: dashed open arrow pointing at the included (mandatory, reused) use case, labeled. `&lt;&lt;extend&gt;&gt;`: dashed open arrow pointing at the base use case, labeled. Use each only when the semantics are real.
6. Actors never connect to actors; use cases never connect to actors' internals.
7. Core use cases center, supporting ones below/right; keep association lines short and uncrossed.

## Golden Example [P0]

```xml
<mxCell id="2" value="E-Commerce System" style="rounded=0;whiteSpace=wrap;html=1;fillColor=none;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;" vertex="1" parent="1"><mxGeometry x="260" y="80" width="620" height="400" as="geometry"/></mxCell>
<mxCell id="3" value="Customer" style="shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="80" y="220" width="40" height="80" as="geometry"/></mxCell>
<mxCell id="4" value="Payment Gateway" style="shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f5f5f5;strokeColor=#666666;fontSize=12;" vertex="1" parent="1"><mxGeometry x="980" y="220" width="40" height="80" as="geometry"/></mxCell>
<mxCell id="5" value="Place Order" style="ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="1"><mxGeometry x="340" y="140" width="160" height="70" as="geometry"/></mxCell>
<mxCell id="6" value="Pay Order" style="ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="1"><mxGeometry x="640" y="240" width="160" height="70" as="geometry"/></mxCell>
<mxCell id="7" value="Track Shipment" style="ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="1"><mxGeometry x="340" y="360" width="160" height="70" as="geometry"/></mxCell>
<mxCell id="8" value="" style="endArrow=none;html=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="9" value="" style="endArrow=none;html=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="&amp;lt;&amp;lt;include&amp;gt;&amp;gt;" style="dashed=1;endArrow=open;html=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="11" value="" style="endArrow=none;html=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
```

Note the pattern: actors flank the boundary (human left, external system right), goals as uniform ellipses inside, include labeled and pointing at the included use case.

## Checklist [P1]
- Actors outside, use cases inside; verb-object goal names.
- include/extend labeled and pointing the right way.
- Requirements scope only — no implementation detail.
