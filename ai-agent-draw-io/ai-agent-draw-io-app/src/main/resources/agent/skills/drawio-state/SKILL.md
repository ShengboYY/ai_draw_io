---
name: drawio-state
description: Draw.io state diagram skill. Use for state diagrams, state machines, lifecycle transitions, status flows, events, guards, and final states.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io State Diagram Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

For state diagrams, use `lifecycle_profile`: make the happy-path lifecycle easy to follow, and place cancellation, failure, retry, or rollback states on separate side paths.

## 1. When To Use
Use this skill when the user asks for:
- State diagrams.
- State machines.
- Lifecycle transitions.
- Status flows for orders, tasks, tickets, payments, or jobs.
- Event-triggered transitions.

Prefer another skill when:
- The user describes business steps: use `drawio-flowchart`.
- The user describes actors and functions: use `drawio-usecase`.
- The user describes time-ordered service calls: use `drawio-sequence`.

## 2. State Machine Scope
Choose one scope before drawing.

- `simple_lifecycle`: one object moves through a main set of statuses.
- `composite_state`: one or more states contain nested substates. Use when the user mentions internal phases or modes.
- `protocol_state`: legal usage sequence for an API, connection, order, device, or resource.
- `concurrent_state`: parallel regions or independent modes. Use only when concurrency is essential.

Rules:
- States are stable conditions and should be nouns or status phrases.
- Transitions are events, commands, timeouts, guards, or actions.
- Use choice nodes for conditional branching after an event.
- Use fork/join bars only for real concurrency.
- Avoid modeling ordinary process steps as states.

## 3. Node Styles

### 3.1 Initial State
Use a black filled circle.

```xml
<mxCell id="2" value="" style="ellipse;html=1;shape=ellipse;fillColor=#000000;strokeColor=#000000;" vertex="1" parent="1">
  <mxGeometry x="100" y="120" width="24" height="24" as="geometry"/>
</mxCell>
```

### 3.2 Normal State
Use rounded rectangles.

```xml
<mxCell id="3" value="Pending Payment" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;" vertex="1" parent="1">
  <mxGeometry x="180" y="100" width="150" height="70" as="geometry"/>
</mxCell>
```

State labels should be nouns or status phrases, such as `Draft`, `Pending Payment`, `Paid`, `Shipped`, or `Cancelled`.

### 3.3 Final State
Use a final-state symbol or a labeled terminal circle.

Style:
`ellipse;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;fontStyle=1;`

## 4. Transition Rules
- Use single-direction arrows.
- Label each transition with an event or condition.
- Recommended label format: `event [guard] / action`.
- Examples: `paymentSuccess`, `timeout / cancel`, `approve [valid]`.
- Initial transition is normally unlabeled unless an initialization action is important.
- Internal actions may be shown inside a state as `entry / action`, `exit / action`, or `event / action` when they clarify lifecycle behavior.
- Composite states should contain nested states inside a clearly labeled container.

Transition style:
`endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;`

## 5. Layout Rules
- Put the initial state on the left or top.
- Put final state on the right or bottom.
- Main lifecycle should move left-to-right or top-to-bottom.
- Put error, cancel, or failure states below the main path.
- Put retry or rollback loops above or to the side.
- Keep state node width 130-170 and height 60-80.

## 6. Quality Checklist
- Include an initial state.
- Include a final state when the lifecycle has a clear completion.
- Every transition has an event or condition label.
- State labels are states, not actions. For example, `Pending Payment` is a state; `Submit Order` is an event/action.
- Avoid orphan states unless they are intentional terminal states.
- Choice, fork, join, or composite states are used only when they clarify the lifecycle.
- Failure, cancellation, retry, and rollback paths are separated from the main lifecycle.
- A state diagram is not drawn as a generic step-by-step flowchart.
