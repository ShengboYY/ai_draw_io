---
name: drawio-state
description: Draw.io state diagram skill. Use for state diagrams, state machines, lifecycle transitions, status flows, events, guards, and final states.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
  category: drawio-design
---

# Draw.io State Diagram Skill

Use for: state machines, lifecycle transitions, status flows (orders, tasks, tickets, jobs).
Prefer `drawio-flowchart` for business steps, `drawio-sequence` for service calls, `drawio-usecase` for actors/functions.

## Rules
1. States are stable conditions named as nouns/status phrases (`Draft`, `Pending Payment`) — never actions. Style: `rounded=1;whiteSpace=wrap;html=1;arcSize=20;fontStyle=1;` + blue fill; ~150×70, same size per tier.
2. Initial state: small filled black circle (`ellipse;html=1;fillColor=#000000;strokeColor=#000000;` 24×24). Final state: gray terminal circle (`ellipse;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;fontStyle=1;` 40×40) — include it whenever the lifecycle completes.
3. Every transition is a single-direction arrow labeled `event [guard] / action` (e.g. `paymentSuccess`, `timeout / cancel`). The initial transition may be unlabeled.
4. Happy path runs left-to-right on one row; cancel/failure states sit on a row below; retry/rollback loops route back along the side with a condition.
5. Error/cancel states use the red role; success/terminal states gray or green.
6. Choice diamonds, fork/join bars, and composite (nested) states only when the lifecycle really needs them; composite states are labeled containers with substates parented inside.
7. No orphan states; a state diagram is not a step-by-step flowchart.

## Golden Example

```xml
<mxCell id="2" value="" style="ellipse;html=1;fillColor=#000000;strokeColor=#000000;" vertex="1" parent="1"><mxGeometry x="80" y="133" width="24" height="24" as="geometry"/></mxCell>
<mxCell id="3" value="Draft" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="180" y="110" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="4" value="Pending Payment" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#dae8fc;strokeColor=#6c8ebf;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="440" y="110" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="5" value="Paid" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#d5e8d4;strokeColor=#82b366;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="700" y="110" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="6" value="Cancelled" style="rounded=1;whiteSpace=wrap;html=1;arcSize=20;fillColor=#f8cecc;strokeColor=#b85450;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="440" y="290" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="7" value="" style="ellipse;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;fontStyle=1;" vertex="1" parent="1"><mxGeometry x="940" y="125" width="40" height="40" as="geometry"/></mxCell>
<mxCell id="8" value="" style="endArrow=classic;html=1;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="2" target="3"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="9" value="submit" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="3" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="10" value="paymentSuccess" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="5"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="11" value="complete" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="5" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="12" value="timeout / cancel" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="13" value="" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;exitX=1;exitY=0.5;entryX=0.5;entryY=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="6" target="7"><mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="960" y="325"/></Array></mxGeometry></mxCell>
```

Note the pattern: happy path on one row ending in a terminal circle, cancellation on a lower row rejoining the terminal via a side waypoint, every transition labeled with its event.

## Checklist
- Initial state present; final state present when the lifecycle completes.
- Labels are events/conditions on edges; states are nouns.
- Failure/cancel paths separated below the happy path.
