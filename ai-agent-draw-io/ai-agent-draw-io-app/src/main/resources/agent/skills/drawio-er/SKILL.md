---
name: drawio-er
description: Draw.io ER diagram skill. Use for entity relationship diagrams, database schema diagrams, tables, fields, primary keys, foreign keys, cardinality, and table relationships.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io ER Diagram Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

For ER diagrams, use `model_profile`: prioritize readable table fields, primary/foreign-key alignment, and cardinality clarity. Do not use many unrelated colors for individual tables.

## 1. When To Use
Use this skill when the user asks for:
- ER diagrams.
- Database table relationships.
- Entities, fields, primary keys, and foreign keys.
- Schema relationship diagrams.
- Business data model diagrams.

Prefer another skill when:
- The user asks for classes, methods, and inheritance: use `drawio-uml`.
- The user asks for system modules and deployment: use `drawio-architecture`.

## 2. ER Model Scope
Choose one model scope before drawing.

- `logical_er`: business entities, relationships, and cardinality. Attributes may be summarized.
- `physical_schema`: database tables, PK/FK fields, data types, indexes when requested, and join tables.
- `conceptual_er`: high-level entities and relationships for communication. Keep fields minimal.

Rules:
- Use table-like entities for physical schema diagrams.
- Use crow's-foot-like cardinality labels or edge markers wherever possible.
- Promote many-to-many relationships into join tables for physical schemas.
- Use dashed edges for non-identifying relationships when the child can exist independently.
- Use solid edges for identifying relationships when the child depends on the parent identity.

## 3. Entity Node Style
Use a table-like rectangle with entity name and fields.

```xml
<mxCell id="2" value="&lt;b&gt;users&lt;/b&gt;&lt;hr&gt;PK id: bigint&lt;br&gt;username: varchar&lt;br&gt;email: varchar&lt;br&gt;created_at: datetime" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=6;" vertex="1" parent="1">
  <mxGeometry x="100" y="100" width="210" height="150" as="geometry"/>
</mxCell>
```

Rules:
- Table names should be stable and concise, for example `users`, `orders`, `order_items`.
- Mark primary keys with `PK`.
- Mark foreign keys with `FK`.
- Use common field types: bigint, varchar, int, decimal, datetime, boolean, text.
- Do not put methods or workflow steps inside ER entities.

## 4. Relationship Rules
- One-to-many: parent table to child table. Use labels like `1:N`.
- One-to-one: use label `1:1`.
- Many-to-many: prefer a join table unless the user explicitly asks for conceptual ER only.
- Foreign key fields should correspond to the target primary key.
- Optionality should be represented when relevant: `0..1`, `1`, `0..*`, `1..*`.
- Relationship labels should be verbs or role names, for example `places`, `contains`, or `belongs to`.
- Do not connect a foreign key to a non-primary or non-unique field unless the user explicitly asks.

Suggested style:
`endArrow=ERmany;startArrow=ERone;html=1;edgeStyle=orthogonalEdgeStyle;`

## 5. Layout Rules
- Put core business tables in the center, such as `orders` or `users`.
- Put detail tables below or to the right, such as `order_items`.
- Put lookup/category tables on the left or top.
- Put payment, shipment, inventory, and log tables on the right.
- Keep horizontal spacing >= 230 and vertical spacing >= 170.

## 6. Quality Checklist
- Every entity has a primary key.
- Foreign key fields are represented and connected.
- Cardinality is labeled clearly.
- Do not draw ER entities as UML classes with methods.
- Avoid duplicate semantic entities with different labels.
- Many-to-many relationships have join tables in physical schema diagrams.
- Identifying and non-identifying relationships are visually distinct when this matters.
- Field lists are readable and not overloaded with unrelated implementation notes.
