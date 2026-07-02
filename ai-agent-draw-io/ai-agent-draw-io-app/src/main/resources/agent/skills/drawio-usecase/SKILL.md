---
name: drawio-usecase
description: Draw.io use case diagram skill. Use for actors, use cases, system boundaries, actor-function relationships, include relationships, extend relationships, and requirement scope diagrams.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Use Case Diagram Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

Shared visual/XML/layout contract: use `drawio-visual-design` for colors, typography, grouping, spacing rhythm, connector routing, ports, waypoints, transparent labels, XML snippets, and container parent rules. Do not repeat generic connector routing, spacing, transparent label, waypoint, or container-parent XML rules here.

For use case diagrams, use `scope_profile`: make the system boundary the main visual anchor, keep actors outside it, and place use cases inside with consistent ellipse sizes.

## 1. When To Use
Use this skill when the user asks for:
- Use case diagrams.
- Actors and system functions.
- Requirement scope diagrams.
- Include and extend relationships.
- Functional boundaries of a system.

Prefer another skill when:
- The user asks for internal classes: use `drawio-uml`.
- The user asks for detailed operation steps: use `drawio-flowchart`.
- The user asks for database tables: use `drawio-er`.

## 2. Use Case Scope
Choose one scope before drawing.

- `system_scope`: one system boundary with primary and secondary actors.
- `subsystem_scope`: a named module or subsystem boundary with only relevant use cases.
- `actor_goal_map`: emphasizes actor goals and relationships. Use when the user asks about roles or permissions.

Rules:
- Use cases must be goal-level capabilities, not UI buttons, database tables, REST endpoints, or internal services.
- Actor names are roles, people, or external systems.
- Primary actors usually go on the left; supporting/external systems usually go on the right.
- Use `<<include>>` only for mandatory reused behavior.
- Use `<<extend>>` only for optional or conditional behavior extending a base use case.

## 3. Node Styles

### 3.1 Actor
Use UML actor shapes. Actors must stay outside the system boundary.

```xml
<mxCell id="2" value="Customer" style="shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;" vertex="1" parent="1">
  <mxGeometry x="80" y="160" width="40" height="80" as="geometry"/>
</mxCell>
```

### 3.2 System Boundary
Use a large rectangle or swimlane for the target system.

```xml
<mxCell id="3" value="E-Commerce System" style="swimlane;whiteSpace=wrap;html=1;startSize=30;fillColor=#f5f5f5;strokeColor=#666666;" vertex="1" parent="1">
  <mxGeometry x="220" y="80" width="540" height="430" as="geometry"/>
</mxCell>
```

### 3.3 Use Case
Use ellipses inside the system boundary.

Style:
`ellipse;whiteSpace=wrap;html=1;fillColor=#fff2cc;strokeColor=#d6b656;`

Use case labels should be verb-object phrases, for example `Place Order`, `Manage Products`, or `Track Shipment`.

## 4. Edge Rules
- Actor to use case: association line.
- Include: dashed open arrow labeled `<<include>>`, pointing to the included use case.
- Extend: dashed open arrow labeled `<<extend>>`, pointing to the extended use case.
- Use the shared standard connector pattern and select association, dashed include, or dashed extend styling by relationship meaning.

## 5. Layout Rules
- Place the system boundary in the center.
- Put primary human actors on the left.
- Put external systems or secondary actors on the right.
- Put core use cases in the center.
- Put supporting use cases below or to the right.
- Keep use cases uniformly sized and avoid line crossings.

## 6. Quality Checklist
- Actors are outside the system boundary.
- Use cases are inside the system boundary.
- Actors do not connect directly to actors.
- Use cases should not be database tables or internal classes.
- Include and extend relationships must be labeled.
- Avoid button-level use cases unless the user explicitly asks for that detail.
- Use cases are named as user goals with verb-object phrases.
- Include and extend arrows point to the correct target use case.
- The diagram remains at requirements scope and avoids implementation detail.
