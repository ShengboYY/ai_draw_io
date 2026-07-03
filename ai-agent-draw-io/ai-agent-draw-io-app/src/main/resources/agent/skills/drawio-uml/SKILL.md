---
name: drawio-uml
description: Draw.io UML class diagram skill. Use for UML class diagrams, domain models, object models, attributes, methods, inheritance, implementation, aggregation, composition, association, and dependency relationships.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
  category: drawio-design
---

# Draw.io UML Class Diagram Skill

Use for: UML class diagrams, domain models, inheritance/implementation/association/aggregation/composition.
Prefer `drawio-er` for database tables and PK/FK, `drawio-usecase` for actors and functions, `drawio-sequence` for interactions.

## Rules
1. Class block format inside `value` (html): `&lt;b&gt;Name&lt;/b&gt;&lt;hr&gt;` + attributes (`+ field: Type` per `&lt;br&gt;` line) + `&lt;hr&gt;` + methods (`+ method(): Return`). Style: `rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacing=5;` + role fill. Width 180–220; height grows with content.
2. Stereotypes when they clarify: `&lt;i&gt;&amp;lt;&amp;lt;interface&amp;gt;&amp;gt;&lt;/i&gt;` line above the name (green fill); same pattern for `&lt;&lt;abstract&gt;&gt;` and `&lt;&lt;enum&gt;&gt;`.
3. Attributes only for a domain model; methods only when behavior matters — never invent long method lists.
4. Edge notation (drawio styles):
   - association: `endArrow=none` (or `endArrow=open` if directed)
   - generalization (is-a): `endArrow=block;endFill=0` pointing at the parent
   - realization (implements): `dashed=1;endArrow=block;endFill=0` pointing at the interface
   - composition (strong ownership): `startArrow=diamondThin;startFill=1;endArrow=none`, diamond on the whole
   - aggregation (weak grouping): `startArrow=diamondThin;startFill=0;endArrow=none`, diamond on the whole
   - dependency (uses): `dashed=1;endArrow=open` pointing at the supplier
5. Multiplicity labels (`1`, `0..1`, `1..*`, `0..*`) on the edge near each end when cardinality matters.
6. Layout: parents/interfaces above children; core aggregate in the center; actors/owners left, details right or below.
7. Relationships express structure, not workflow — no numbered process arrows between classes.
8. No duplicate semantic classes; every edge endpoint exists.

## Golden Example

```xml
<mxCell id="2" value="&lt;i&gt;&amp;lt;&amp;lt;interface&amp;gt;&amp;gt;&lt;/i&gt;&lt;br&gt;&lt;b&gt;PaymentProvider&lt;/b&gt;&lt;hr&gt;+ pay(order: Order): Receipt" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;align=left;verticalAlign=top;spacing=5;fontSize=12;" vertex="1" parent="1"><mxGeometry x="460" y="60" width="210" height="90" as="geometry"/></mxCell>
<mxCell id="3" value="&lt;b&gt;AlipayProvider&lt;/b&gt;&lt;hr&gt;+ pay(order: Order): Receipt" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;fontSize=12;" vertex="1" parent="1"><mxGeometry x="790" y="60" width="210" height="90" as="geometry"/></mxCell>
<mxCell id="4" value="&lt;b&gt;User&lt;/b&gt;&lt;hr&gt;+ userId: Long&lt;br&gt;+ username: String&lt;br&gt;+ email: String" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;fontSize=12;" vertex="1" parent="1"><mxGeometry x="80" y="300" width="200" height="120" as="geometry"/></mxCell>
<mxCell id="5" value="&lt;b&gt;Order&lt;/b&gt;&lt;hr&gt;+ orderId: Long&lt;br&gt;+ status: OrderStatus&lt;br&gt;+ total: BigDecimal&lt;hr&gt;+ checkout(): void" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;fontSize=12;" vertex="1" parent="1"><mxGeometry x="460" y="280" width="210" height="150" as="geometry"/></mxCell>
<mxCell id="6" value="&lt;b&gt;OrderItem&lt;/b&gt;&lt;hr&gt;+ sku: String&lt;br&gt;+ quantity: int&lt;br&gt;+ price: BigDecimal" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;fontSize=12;" vertex="1" parent="1"><mxGeometry x="790" y="300" width="200" height="120" as="geometry"/></mxCell>
<mxCell id="7" value="places" style="endArrow=open;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="8" value="1..*" style="startArrow=diamondThin;startFill=1;endArrow=none;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="9" value="" style="dashed=1;endArrow=block;endFill=0;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=0;exitY=0.5;entryX=1;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="2"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="uses" style="dashed=1;endArrow=open;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=0.5;exitY=0;entryX=0.5;entryY=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="2"><mxGeometry relative="1" as="geometry"/></mxCell>
```

Note the pattern: interface above its implementor and its consumer, realization dashed with hollow triangle, composition diamond on the whole (`Order`), classes aligned on two rows with wide routing channels.

## Checklist
- Every class named; arrows match UML semantics (triangle → parent, diamond → whole, dashed → dependency/realization).
- Interfaces visually distinct; multiplicity present where cardinality matters.
- No workflow arrows, no database column notation.
