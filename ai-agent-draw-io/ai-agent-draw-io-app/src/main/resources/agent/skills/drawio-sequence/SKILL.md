---
name: drawio-sequence
description: Draw.io sequence diagram skill. Use for sequence diagrams, interactions, lifelines, synchronous calls, asynchronous events, returns, self calls, and API call order.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Sequence Diagram Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

For sequence diagrams, use `sequence_profile`: prioritize lifeline alignment and downward message order over rich coloring. Keep the style restrained so temporal order remains easy to scan.

## 1. When To Use
Use this skill when the user asks for:
- Sequence diagrams.
- Interaction diagrams.
- API call order.
- Request-response chains.
- Time-ordered communication between actors, services, databases, and external systems.

Prefer another skill when:
- The user describes static module relationships: use `drawio-architecture`.
- The user describes business steps without participants: use `drawio-flowchart`.
- The user describes object state changes: use `drawio-state`.

## 2. Interaction Scope
Choose one scope and keep the diagram focused.

- `system_sequence`: actors interact with the system as a black box. Use when the request is about a use case scenario.
- `service_sequence`: services, gateways, databases, and external systems exchange calls/events. Use for API flows and distributed systems.
- `object_sequence`: objects/classes collaborate. Use only when the request is object-design oriented.

## 3. Combined Fragments
Use framed fragment containers when they clarify control logic:

- `alt`: mutually exclusive paths. Use one fragment with an `else` divider label.
- `opt`: optional path with no else branch.
- `loop`: repeated messages with a loop condition label.
- `par`: parallel interactions split into lanes.
- `critical`: critical section or non-interleavable operation.

Rules:
- Do not use fragments for every small condition; add them only when they clarify the scenario.
- Fragment frames must span the relevant lifelines and the relevant vertical time range.
- Return messages should be dashed and placed below the call they answer.
- Activation bars should start at the incoming call and end after the return or last nested call.

## 4. Node Styles

### 4.1 Lifeline
Use UML lifelines for participants.

```xml
<mxCell id="2" value="Client" style="shape=umlLifeline;perimeter=lifelinePerimeter;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;outlineConnect=0;fillColor=#dae8fc;strokeColor=#6c8ebf;" vertex="1" parent="1">
  <mxGeometry x="100" y="50" width="110" height="420" as="geometry"/>
</mxCell>
```

Rules:
- Participants should be ordered by call direction: initiator -> gateway -> business services -> storage/external systems.
- Keep participant count around 3-6. If there are more, merge similar participants.
- Common names: `User`, `Web`, `API Gateway`, `OrderService`, `PaymentService`, `Database`.

### 4.2 Activation
Use activation bars as child elements of lifelines when needed.

```xml
<mxCell id="3" value="" style="html=1;points=[];perimeter=orthogonalPerimeter;fillColor=#fff2cc;strokeColor=#d6b656;" vertex="1" parent="2">
  <mxGeometry x="50" y="80" width="10" height="120" as="geometry"/>
</mxCell>
```

## 5. Message Edge Styles

### 5.1 Synchronous Message
Use solid filled arrows when the caller waits for a response.

Style:
`html=1;verticalAlign=bottom;endArrow=block;edgeStyle=elbowEdgeStyle;elbow=vertical;`

### 5.2 Asynchronous Message
Use open arrows when the caller does not wait.

Style:
`html=1;verticalAlign=bottom;endArrow=open;endSize=8;edgeStyle=elbowEdgeStyle;elbow=vertical;`

### 5.3 Return Message
Use dashed open arrows.

Style:
`html=1;verticalAlign=bottom;endArrow=open;dashed=1;endSize=8;edgeStyle=elbowEdgeStyle;elbow=vertical;`

### 5.4 Self Call
Use a looped edge when a participant calls itself for validation, retry, or internal state update.

## 6. Layout Rules
- Lifeline x coordinates should be fixed, for example x=100, 320, 540, 760.
- All lifelines share the same y coordinate.
- Message y coordinates must increase over time, for example y=120, 170, 220.
- Return messages must appear below their corresponding calls.
- Activation bars cover only actual processing time.
- Use numbered labels such as `1. submitOrder()`, `1.1 validate()`, and `2. paymentUrl`.

## 7. Quality Checklist
- Every message has a source and target.
- Message order moves downward over time.
- Lifelines are not drawn without messages.
- Combined fragments are used for meaningful alt/opt/loop/par/critical logic, not as decoration.
- Activation bars align with the actual processing interval.
- Return messages are below their corresponding calls and use dashed open arrows.
- Do not draw a sequence diagram as a flowchart.
- Labels are actions or returned values, not just object names.
