---
name: drawio-uml
description: Draw.io UML class diagram skill. Use for UML class diagrams, domain models, object models, attributes, methods, inheritance, implementation, aggregation, composition, association, and dependency relationships.
schemaVersion: 1
category: drawio-design
diagramType: uml_class
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io UML Class Diagram Skill

## When To Use [P0]
Use for: UML class diagrams, domain models, inheritance/implementation/association/aggregation/composition.
Prefer `drawio-er` for database tables and PK/FK, `drawio-usecase` for actors and functions, `drawio-sequence` for interactions.

## Rules [P0]
1. Class blocks use the Draw.io UML library pattern: a parent `swimlane;fontStyle=0;childLayout=stackLayout;horizontal=1;startSize=30;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;` cell, plus child text section cells. Do not fake classes as one plain rectangle with HTML divider rules.
2. Emit class parent and section cells as sibling `mxCell`s. Section cells use `text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;`. Width matches the parent; `y` is relative to the parent (`30`, `80`, ...).
3. Stereotypes when they clarify: include `&lt;i&gt;&amp;lt;&amp;lt;interface&amp;gt;&amp;gt;&lt;/i&gt;&lt;br&gt;Name` in the parent `value` (green fill); same pattern for `&lt;&lt;abstract&gt;&gt;` and `&lt;&lt;enum&gt;&gt;`.
4. Model shaping before routing: Pick one intent (domain model, implementation class model, or persistence-like data model). Use `drawio-er` for tables, PK/FK, or database schema. Keep 5-9 core classes; split by bounded context/package when larger.
5. Show attributes that explain identity, core state, or relationships; methods only when behavior matters. Do not turn a UML class into a database field checklist; section heights must fit their text; leave bottom padding so attributes/methods never touch borders or relationship arrows.
6. Do not invent support/admin relationships just to connect every class. Omit weak relations not stated by the prompt; UML relationships express structure, not workflow or org-chart glue.
7. Diamonds are only for part-whole or lifecycle ownership. Use plain associations for manages/employs/borrows/issues unless the prompt explicitly says one class contains the other.
8. Edge notation (drawio styles):
   - association: `endArrow=none` (or `endArrow=open` if directed)
   - generalization (is-a): `endArrow=block;endFill=0` pointing at the parent
   - realization (implements): `dashed=1;endArrow=block;endFill=0` pointing at the interface
   - composition (strong ownership): `startArrow=diamondThin;startFill=1;endArrow=none`, diamond on the whole
   - aggregation (weak grouping): `startArrow=diamondThin;startFill=0;endArrow=none`, diamond on the whole
   - dependency (uses): `dashed=1;endArrow=open` pointing at the supplier
9. UML relationship edges prefer straight Draw.io connectors: include `edgeStyle=none;html=1;rounded=0;labelBackgroundColor=none;fontSize=11;`, omit waypoints, and let Draw.io anchor to the class perimeter. Use orthogonal style and waypoints only when a straight relation would cross another class body.
10. Center labels are verb-only (`owns`, `contains`, `assigned`); multiplicities (`1`, `0..1`, `1..*`, `0..*`) belong near endpoints, not combined into one crowded center label. Labeled relationships need readable edge length: reserve 80-120 px of clear line between class borders; move classes farther apart when a label collides with nodes, or omit weak labels on very short edges.
11. Readable labels must not create an over-wide diagram: keep related class gaps compact, about 160-260 px border-to-border, and arrange local clusters rather than long cross-canvas chains. Shorten verbose labels to verbs (`selects`, `generates`) and put exact cardinality near endpoints.
12. Layout: core aggregate/root in the center; owned entities close beside or below it; subclasses directly below or beside the parent with short generalization arrows; enums/value objects near the owning class; organization/support classes in a side group.
13. Reserve a clear inheritance row for subclasses. Keep non-subclass classes out of the subclass row; inheritance arrows attach to the class border, not through attribute/method text.
14. Avoid long diagonal or cross-group relationships. Move classes closer, add a package boundary, or split the diagram; never route through another class body or solve UML layout with long detours.
15. Do not draw a type-only dependency for an enum/value object already shown as an attribute type, unless that type is the diagram focus.
16. No duplicate semantic classes; every edge endpoint exists.

## Golden Example [P0]

```xml
<mxCell id="2" value="&lt;i&gt;&amp;lt;&amp;lt;interface&amp;gt;&amp;gt;&lt;/i&gt;&lt;br&gt;PaymentProvider" style="swimlane;fontStyle=0;childLayout=stackLayout;horizontal=1;startSize=38;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="460" y="60" width="220" height="82" as="geometry"/></mxCell>
<mxCell id="3" value="+ pay(order: Order): Receipt" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="2"><mxGeometry y="38" width="220" height="44" as="geometry"/></mxCell>
<mxCell id="4" value="AlipayProvider" style="swimlane;fontStyle=0;childLayout=stackLayout;horizontal=1;startSize=30;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="790" y="60" width="220" height="74" as="geometry"/></mxCell>
<mxCell id="5" value="+ pay(order: Order): Receipt" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="4"><mxGeometry y="30" width="220" height="44" as="geometry"/></mxCell>
<mxCell id="6" value="User" style="swimlane;fontStyle=0;childLayout=stackLayout;horizontal=1;startSize=30;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="80" y="300" width="210" height="104" as="geometry"/></mxCell>
<mxCell id="7" value="+ userId: Long&lt;br&gt;+ username: String&lt;br&gt;+ email: String" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="6"><mxGeometry y="30" width="210" height="74" as="geometry"/></mxCell>
<mxCell id="8" value="Order" style="swimlane;fontStyle=0;childLayout=stackLayout;horizontal=1;startSize=30;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="460" y="280" width="220" height="144" as="geometry"/></mxCell>
<mxCell id="9" value="+ orderId: Long&lt;br&gt;+ status: OrderStatus&lt;br&gt;+ total: BigDecimal" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="8"><mxGeometry y="30" width="220" height="74" as="geometry"/></mxCell>
<mxCell id="10" value="+ checkout(): void" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="8"><mxGeometry y="104" width="220" height="40" as="geometry"/></mxCell>
<mxCell id="11" value="OrderItem" style="swimlane;fontStyle=0;childLayout=stackLayout;horizontal=1;startSize=30;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="790" y="300" width="210" height="104" as="geometry"/></mxCell>
<mxCell id="12" value="+ sku: String&lt;br&gt;+ quantity: int&lt;br&gt;+ price: BigDecimal" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=top;spacingLeft=4;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="11"><mxGeometry y="30" width="210" height="74" as="geometry"/></mxCell>
<mxCell id="13" value="places" style="endArrow=open;html=1;edgeStyle=none;rounded=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="6" target="8"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="14" value="1..*" style="startArrow=diamondThin;startFill=1;endArrow=none;html=1;edgeStyle=none;rounded=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="8" target="11"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="15" value="" style="dashed=1;endArrow=block;endFill=0;html=1;edgeStyle=none;rounded=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="2"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="16" value="uses" style="dashed=1;endArrow=open;html=1;edgeStyle=none;rounded=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="8" target="2"><mxGeometry relative="1" as="geometry"/></mxCell>
```

Note the pattern: class parents are UML `swimlane` stack containers, attributes/methods are child section cells, edges connect to the parent class ids, and straight UML relationship lines have no waypoint arrays.

## Checklist [P1]
- Every class named; arrows match UML semantics (triangle → parent, diamond → whole, dashed → dependency/realization).
- Interfaces visually distinct; multiplicity present where cardinality matters.
- No invented support/admin edges, workflow arrows, or database column notation.
