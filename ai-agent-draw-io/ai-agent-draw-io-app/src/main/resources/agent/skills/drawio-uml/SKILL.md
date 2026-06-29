---
name: drawio-uml
description: Draw.io UML class diagram skill. Use for UML class diagrams, domain models, object models, attributes, methods, inheritance, implementation, aggregation, composition, association, and dependency relationships.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io UML Class Diagram Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

For UML class diagrams, use `model_profile`: align class blocks on a stable grid, keep compartments readable, and use color only to distinguish meaningful categories such as interfaces, abstract classes, and core domain classes.

## 1. When To Use
Use this skill when the user asks for:
- UML class diagrams.
- Domain models or object models.
- Classes with attributes and methods.
- Inheritance, implementation, association, aggregation, or composition.
- Static object-oriented structure.

Prefer another skill when:
- Database tables and primary/foreign keys are the main concern: use `drawio-er`.
- Actors and system functions are the main concern: use `drawio-usecase`.
- Time-ordered interactions are the main concern: use `drawio-sequence`.
- Business steps and decisions are the main concern: use `drawio-flowchart`.

## 2. UML Model Scope
Choose one model scope before drawing.

- `domain_model`: show business concepts, attributes, and associations. Methods are usually omitted or kept minimal.
- `design_class`: show implementation-oriented classes, interfaces, attributes, methods, and dependencies.
- `interface_model`: emphasize interfaces, implementations, adapters, ports, and external dependencies.
- `inheritance_model`: emphasize abstract classes, base types, subclasses, and realizations.

Rules:
- Do not mix database table notation with UML class notation unless the user explicitly asks for a hybrid.
- Use methods only when behavior is important to the request; avoid inventing large method lists.
- Use stereotypes such as `<<interface>>`, `<<abstract>>`, or `<<enum>>` when they clarify semantics.
- Relationship semantics matter more than edge quantity.

## 3. Node Styles

### 3.1 Class Node
Use a rectangular UML-style class block with class name, attributes, and methods.

```xml
<mxCell id="2" value="&lt;b&gt;User&lt;/b&gt;&lt;hr&gt;+ userId: Long&lt;br&gt;+ username: String&lt;br&gt;+ email: String&lt;hr&gt;+ login(): boolean&lt;br&gt;+ logout(): void" style="rounded=0;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;align=left;verticalAlign=top;spacing=5;" vertex="1" parent="1">
  <mxGeometry x="100" y="100" width="190" height="150" as="geometry"/>
</mxCell>
```

Rules:
- Class name must be concise and stable.
- If the user writes in Chinese, diagram labels may follow the user's language, but avoid unnecessary duplicate bilingual labels.
- Attribute format: `+ fieldName: Type`.
- Method format: `+ methodName(): ReturnType`.
- Do not put explanations or workflow steps inside the attribute or method area.

### 3.2 Interface Node
Use a class-like block with an interface stereotype.

Style:
`rounded=0;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;align=left;verticalAlign=top;spacing=5;`

Label example:
`&lt;i&gt;&lt;&lt;interface&gt;&gt;&lt;/i&gt;&lt;br&gt;&lt;b&gt;PaymentProvider&lt;/b&gt;&lt;hr&gt;+ pay(): PaymentResult`

### 3.3 Enum Node
Use a class-like block with an enum stereotype.

Label example:
`&lt;i&gt;&lt;&lt;enum&gt;&gt;&lt;/i&gt;&lt;br&gt;&lt;b&gt;OrderStatus&lt;/b&gt;&lt;hr&gt;CREATED&lt;br&gt;PAID&lt;br&gt;SHIPPED&lt;br&gt;CANCELLED`

## 4. Edge Semantics

### 4.1 Association
Use for references or normal relationships.

Style:
`endArrow=none;html=1;edgeStyle=orthogonalEdgeStyle;`

### 4.2 Generalization
Use for inheritance. The arrow points to the parent class.

Style:
`endArrow=block;html=1;endFill=0;edgeStyle=orthogonalEdgeStyle;`

### 4.3 Realization
Use when a class implements an interface. The arrow points to the interface.

Style:
`endArrow=block;dashed=1;html=1;endFill=0;edgeStyle=orthogonalEdgeStyle;`

### 4.4 Aggregation
Use when one object owns or groups another but the child can exist independently.

Style:
`endArrow=diamondThin;endFill=0;html=1;edgeStyle=orthogonalEdgeStyle;`

### 4.5 Composition
Use when the child is strongly owned by the whole.

Style:
`endArrow=diamondThin;endFill=1;html=1;edgeStyle=orthogonalEdgeStyle;`

### 4.6 Dependency
Use when one class temporarily uses another.

Style:
`endArrow=open;dashed=1;html=1;endSize=8;edgeStyle=orthogonalEdgeStyle;`

## 5. Relationship Rules
- "A owns many B" usually means composition from A to B.
- "A has a B" may be aggregation or association depending on strength.
- "A is a kind of B" means generalization.
- "A implements B" means realization.
- Multiplicity labels should use `1`, `0..1`, `1..*`, or `0..*`.
- Do not draw UML class relationships as process arrows.
- Generalization and realization arrows point to the parent class or interface.
- Composition uses a filled diamond on the whole/owner side.
- Aggregation uses a hollow diamond on the aggregate/whole side.
- Dependency is dashed and points to the supplier that is used.

## 6. Layout Rules
- Put the core aggregate or central business class in the middle.
- Put users, accounts, and organizations on the left.
- Put orders, tasks, or transactions in the center.
- Put details, items, payments, products, and logs on the right or below.
- Put parent classes above child classes.
- Keep horizontal spacing >= 220 and vertical spacing >= 160.
- Use orthogonal edges to reduce crossings.
- Node width should usually be 180-220; increase height based on fields and methods.

## 7. Quality Checklist
- Every class has a class name.
- Important classes have meaningful attributes.
- Methods are optional; do not invent too many if the user did not ask.
- No duplicate semantic nodes, such as both `OrderItem` and another identical item class.
- Every edge source and target points to an existing node.
- Relationships match UML semantics, not workflow semantics.
- Parent abstractions are above or visually upstream of child classes.
- Interfaces and implementations are distinguishable.
- Multiplicity is present when cardinality is important to the request.
