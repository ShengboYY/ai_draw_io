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
1. Entity = table-like block: `&lt;b&gt;table_name&lt;/b&gt;&lt;hr&gt;` + one field per `&lt;br&gt;` line, style `rounded=0;whiteSpace=wrap;html=1;align=left;verticalAlign=top;spacing=6;` + blue fill. Width 200–230.
2. Table names are concise snake_case (`users`, `orders`, `order_items`). Fields use common types (bigint, varchar, int, decimal, datetime, boolean).
3. Mark keys in the field line: `PK id: bigint`, `FK user_id: bigint`. Every entity has a PK; every FK points at the target table's PK.
4. Cardinality on the edge: `1:1`, `1:N`, plus optionality (`0..1`, `1..*`) when it matters; relationship verb as the edge label (`places`, `contains`).
5. Many-to-many becomes a join table in physical schemas.
6. Identifying relationship (child depends on parent identity): solid edge. Non-identifying: dashed edge.
7. Layout: core tables (`users`, `orders`) center-left, detail tables right (`order_items`), lookup tables top, logs/payments far right. Same-row tables share y.
8. No methods, workflow arrows, or implementation notes inside entities; keep one color role for all normal tables, a second one only for join/lookup tables.

## Golden Example [P0]

```xml
<mxCell id="2" value="&lt;b&gt;users&lt;/b&gt;&lt;hr&gt;PK id: bigint&lt;br&gt;username: varchar&lt;br&gt;email: varchar&lt;br&gt;created_at: datetime" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=6;fontSize=12;" vertex="1" parent="1"><mxGeometry x="80" y="120" width="220" height="140" as="geometry"/></mxCell>
<mxCell id="3" value="&lt;b&gt;orders&lt;/b&gt;&lt;hr&gt;PK id: bigint&lt;br&gt;FK user_id: bigint&lt;br&gt;status: varchar&lt;br&gt;total: decimal&lt;br&gt;created_at: datetime" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=6;fontSize=12;" vertex="1" parent="1"><mxGeometry x="440" y="120" width="220" height="160" as="geometry"/></mxCell>
<mxCell id="4" value="&lt;b&gt;order_items&lt;/b&gt;&lt;hr&gt;PK id: bigint&lt;br&gt;FK order_id: bigint&lt;br&gt;sku: varchar&lt;br&gt;quantity: int&lt;br&gt;price: decimal" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=6;fontSize=12;" vertex="1" parent="1"><mxGeometry x="800" y="120" width="220" height="160" as="geometry"/></mxCell>
<mxCell id="5" value="places 1:N" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="2" target="3"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="6" value="contains 1:N" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
```

Note the pattern: parent → child left-to-right, FK lines mirroring each edge, cardinality and verb on the edge, one consistent table style.

## Checklist [P1]
- Every entity has a PK; every FK connected to its parent's PK.
- Cardinality labeled; M:N promoted to join tables in physical schemas.
- No UML methods, no duplicate entities under different names.
