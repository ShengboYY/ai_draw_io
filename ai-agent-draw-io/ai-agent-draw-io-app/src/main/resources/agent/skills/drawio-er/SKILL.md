---
name: drawio-er
description: Draw.io ER diagram skill. Use for entity relationship diagrams, database schema diagrams, tables, fields, primary keys, foreign keys, cardinality, and table relationships.
schemaVersion: 1
category: drawio-design
diagramType: er
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io ER Diagram Skill

## When To Use [P0]
Use for: ER diagrams, database schemas, tables/fields/PK/FK, cardinality.
Prefer `drawio-uml` for classes with methods and inheritance, `drawio-architecture` for system modules.

## Rules [P0]
1. Entity = Draw.io Entity Relation table/list pattern: a parent `swimlane;childLayout=stackLayout;horizontal=1;startSize=28;resizeParent=1;resizeLast=0;whiteSpace=wrap;html=1;` cell for the table name, plus child row cells for fields. Do not fake tables as one plain rectangle with HTML divider rules.
2. Field rows are sibling `mxCell`s parented to the entity. Use `text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;`. Width matches the parent; `y` is relative to the parent.
3. Table names are concise snake_case (`users`, `orders`, `order_items`). Fields use common types (bigint, varchar, int, decimal, datetime, boolean).
4. Mark keys in the field line: `PK id: bigint`, `FK user_id: bigint`. Every entity has a PK; every FK points at the target table's PK.
5. Relationships use crow's-foot arrows when cardinality is clear: `startArrow=ERone;endArrow=ERmany` for 1:N, `ERone`/`ERone` for 1:1. Label with the relationship verb (`places`, `contains`); add `0..1`, `1..*`, or `1:N` text only when it clarifies.
6. Relationship edges prefer straight Draw.io connectors: `edgeStyle=none;html=1;rounded=0;labelBackgroundColor=none;fontSize=11;`, no waypoints. Use orthogonal routing only to avoid crossing a table body.
7. Many-to-many becomes a join table in physical schemas. Identifying relationship: solid edge. Non-identifying: dashed edge.
8. Layout: core tables (`users`, `orders`) center-left, detail tables right (`order_items`), lookup tables top, logs/payments far right. Same-row tables share y.
9. No methods, workflow arrows, or implementation notes inside entities; keep one color role for normal tables, a second one only for join/lookup tables.

## Golden Example [P0]

```xml
<mxCell id="2" value="users" style="swimlane;childLayout=stackLayout;horizontal=1;startSize=28;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="80" y="120" width="220" height="124" as="geometry"/></mxCell>
<mxCell id="3" value="PK id: bigint" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="2"><mxGeometry y="28" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="4" value="username: varchar" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="2"><mxGeometry y="52" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="5" value="email: varchar" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="2"><mxGeometry y="76" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="6" value="created_at: datetime" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="2"><mxGeometry y="100" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="7" value="orders" style="swimlane;childLayout=stackLayout;horizontal=1;startSize=28;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="440" y="120" width="220" height="148" as="geometry"/></mxCell>
<mxCell id="8" value="PK id: bigint" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="7"><mxGeometry y="28" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="9" value="FK user_id: bigint" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="7"><mxGeometry y="52" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="10" value="status: varchar" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="7"><mxGeometry y="76" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="11" value="total: decimal" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="7"><mxGeometry y="100" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="12" value="created_at: datetime" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="7"><mxGeometry y="124" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="13" value="order_items" style="swimlane;childLayout=stackLayout;horizontal=1;startSize=28;horizontalStack=0;resizeParent=1;resizeParentMax=0;resizeLast=0;collapsible=1;marginBottom=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="800" y="120" width="220" height="148" as="geometry"/></mxCell>
<mxCell id="14" value="PK id: bigint" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="13"><mxGeometry y="28" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="15" value="FK order_id: bigint" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="13"><mxGeometry y="52" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="16" value="sku: varchar" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="13"><mxGeometry y="76" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="17" value="quantity: int" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="13"><mxGeometry y="100" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="18" value="price: decimal" style="text;strokeColor=none;fillColor=none;align=left;verticalAlign=middle;spacingLeft=6;spacingRight=4;overflow=hidden;rotatable=0;points=[[0,0.5],[1,0.5]];portConstraint=eastwest;whiteSpace=wrap;html=1;fontSize=12;" vertex="1" parent="13"><mxGeometry y="124" width="220" height="24" as="geometry"/></mxCell>
<mxCell id="19" value="places" style="startArrow=ERone;endArrow=ERmany;html=1;edgeStyle=none;rounded=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="2" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="20" value="contains" style="startArrow=ERone;endArrow=ERmany;html=1;edgeStyle=none;rounded=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="7" target="13"><mxGeometry relative="1" as="geometry"/></mxCell>
```

Note the pattern: parent → child left-to-right, table headers are parent cells, fields are child rows, FK rows mirror each edge, and crow's-foot relationships stay straight unless a table blocks the path.

## Checklist [P1]
- Every entity has a PK; every FK connected to its parent's PK.
- Cardinality labeled; M:N promoted to join tables in physical schemas.
- No UML methods, no duplicate entities under different names.
