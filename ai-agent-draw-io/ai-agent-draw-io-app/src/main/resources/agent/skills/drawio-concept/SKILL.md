---
name: drawio-concept
description: Draw.io radial concept skill. Use for onion/ring models, ecosystem maps, hub-and-spoke views, circular cycles/loops, mind maps, and any model organized around a center or in concentric layers rather than a reading direction.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Radial Concept Skill

Use for: onion models (concentric layers around a core), ecosystem/stakeholder maps, hub-and-spoke views, circular cycles (PDCA, flywheels), mind maps.
Prefer `drawio-flowchart` when the content is a step-by-step process with a start and end, `drawio-architecture` for component/deployment structure.

This skill works in the **radial mode** of the Global Draw.io Layout Contract: no `orthogonalEdgeStyle`, no exit/entry ports, no grid rows.

## Layout Recipes

### Onion / ecosystem (concentric zones)
1. Pick a center `(cx, cy)` ≈ (530, 415). Draw zone ellipses first, biggest first, so they render behind content: outer zone ≈ 980×670, inner zone ≈ 600×420, both centered on `(cx, cy)`.
2. Zone style: `ellipse;whiteSpace=wrap;html=1;fillColor=<soft fill>;strokeColor=<matching stroke>;verticalAlign=top;fontStyle=1;fontSize=13;spacingTop=12;`. A permeable/dashed boundary adds `dashed=1`. Zones are backgrounds: content nodes keep `parent="1"` and absolute coordinates — never parent them to a zone.
3. Put the hub node (160×60, bold, strongest color) exactly on the center: `x = cx−80, y = cy−30`.
4. Place each layer's nodes on a ring between its zone's edge and the next zone, using the ring table below. Inner ring: `rx≈200, ry≈140`; outer ring: `rx≈400, ry≈280`.

### Ring placement table
Node center = `(cx + rx·u, cy + ry·v)`; node x/y = center minus half width/height. Take `(u, v)` pairs from here instead of computing trigonometry:

| N | (u, v) per node |
| --- | --- |
| 3 | (0, −1), (0.87, 0.5), (−0.87, 0.5) |
| 4 cardinal | (1, 0), (0, 1), (−1, 0), (0, −1) |
| 4 diagonal | (0.71, −0.71), (0.71, 0.71), (−0.71, 0.71), (−0.71, −0.71) |
| 5 | (0, −1), (0.95, −0.31), (0.59, 0.81), (−0.59, 0.81), (−0.95, −0.31) |
| 6 | (1, 0), (0.5, 0.87), (−0.5, 0.87), (−1, 0), (−0.5, −0.87), (0.5, −0.87) |
| 8 | 4 cardinal + 4 diagonal |
| 12 | every 30°: (1, 0), (0.87, 0.5), (0.5, 0.87), (0, 1), (−0.5, 0.87), (−0.87, 0.5), (−1, 0), (−0.87, −0.5), (−0.5, −0.87), (0, −1), (0.5, −0.87), (0.87, −0.5) |

Verify after placement: every node stays ≥ 30 px inside its own zone's bounding box and ≥ 40 px away from ring neighbors; shrink `rx/ry` or the node size if not.

### Cycle / flywheel (PDCA, loops)
1. Place the N steps on one ring (table above), first step at the top `(0, −1)`, proceeding clockwise.
2. Connect step i → i+1 (and last → first) with `curved=1;html=1;endArrow=classic;` and ONE waypoint at the arc midpoint pushed slightly outward: `(cx + 1.15·rx·u_mid, cy + 1.15·ry·v_mid)` where `(u_mid, v_mid)` is the table entry halfway between the two steps.
3. Optional center label (cycle name) as a transparent text cell on `(cx, cy)`.

### Hub-and-spoke / mind map
1. Hub at center; first-level topics on one ring (table above), 6–8 max.
2. Spokes are straight: `edgeStyle=none;html=1;` — with NO exit/entry ports, so draw.io anchors both ends on the facing perimeters at any angle.
3. Second-level leaves fan outward from their topic on a short local ring (rx≈150, ry≈100 around the topic), never back toward the center.

## Edge Rules
- Spokes and cross-ring links: `edgeStyle=none;html=1;endArrow=classic;` solid for primary structure, `dashed=1;strokeColor=#64748b;` for inflows/feedback/secondary.
- Ring arcs and cycle edges: `curved=1;html=1;` (+ one outward waypoint as above).
- Never set exitX/exitY/entryX/entryY in radial mode; never use `edgeStyle=orthogonalEdgeStyle` — the backend snaps orthogonal edges back to grid rules and would destroy the radial shape.
- A chord between two ring nodes must not pass through the hub: use `curved=1` with one waypoint offset ≥ 60 px from the center.
- Label only edges whose meaning is not obvious; keep labels ≤ 3 words with `labelBackgroundColor=none;fontSize=11;`.

## Golden Example
Ecosystem onion — two concentric zones, purple hub, white inner ring (4 cardinal), green outer ring (4 diagonal), dashed inflows, one curved arc:

```xml
<mxCell id="2" value="Platform Ecosystem Model" style="text;html=1;strokeColor=none;fillColor=none;align=center;fontSize=18;fontStyle=1;whiteSpace=wrap;" vertex="1" parent="1"><mxGeometry x="340" y="20" width="380" height="30" as="geometry"/></mxCell>
<mxCell id="3" value="External Environment" style="ellipse;whiteSpace=wrap;html=1;fillColor=#f5f5f5;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;spacingTop=12;" vertex="1" parent="1"><mxGeometry x="40" y="80" width="980" height="670" as="geometry"/></mxCell>
<mxCell id="4" value="The Firm (Internal)" style="ellipse;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;dashed=1;verticalAlign=top;fontStyle=1;fontSize=13;spacingTop=12;" vertex="1" parent="1"><mxGeometry x="230" y="205" width="600" height="420" as="geometry"/></mxCell>
<mxCell id="5" value="Core Platform" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#e1d5e7;strokeColor=#9673a6;fontStyle=1;fontSize=12;" vertex="1" parent="1"><mxGeometry x="450" y="385" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="6" value="Product &amp; R&amp;D" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="460" y="250" width="140" height="50" as="geometry"/></mxCell>
<mxCell id="7" value="Data &amp; APIs" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="660" y="390" width="140" height="50" as="geometry"/></mxCell>
<mxCell id="8" value="Operations" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="460" y="530" width="140" height="50" as="geometry"/></mxCell>
<mxCell id="9" value="Partnerships" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="1"><mxGeometry x="260" y="390" width="140" height="50" as="geometry"/></mxCell>
<mxCell id="10" value="Universities &amp; Research" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="167" y="187" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="11" value="Startups &amp; Inventors" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="733" y="187" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="12" value="Partners &amp; Channels" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="733" y="583" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="13" value="Community &amp; Users" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="167" y="583" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="14" value="" style="endArrow=classic;html=1;edgeStyle=none;strokeColor=#334155;" edge="1" parent="1" source="5" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="15" value="" style="endArrow=classic;html=1;edgeStyle=none;strokeColor=#334155;" edge="1" parent="1" source="5" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="16" value="" style="endArrow=classic;html=1;edgeStyle=none;strokeColor=#334155;" edge="1" parent="1" source="5" target="8"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="17" value="" style="endArrow=classic;html=1;edgeStyle=none;strokeColor=#334155;" edge="1" parent="1" source="5" target="9"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="18" value="research inflow" style="endArrow=classic;html=1;edgeStyle=none;dashed=1;strokeColor=#64748b;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="10" target="9"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="19" value="external tech" style="endArrow=classic;html=1;edgeStyle=none;dashed=1;strokeColor=#64748b;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="11" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="20" value="co-sell" style="endArrow=classic;html=1;edgeStyle=none;dashed=1;strokeColor=#64748b;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="12" target="7"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="21" value="feedback" style="endArrow=classic;html=1;edgeStyle=none;dashed=1;strokeColor=#64748b;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="13" target="8"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="22" value="talent &amp; spin-offs" style="endArrow=classic;html=1;curved=1;dashed=1;strokeColor=#64748b;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="10" target="11"><mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="530" y="145"/></Array></mxGeometry></mxCell>
```

Read from the example: zones first and behind everything, hub dead-center, ring nodes from the placement table, straight port-less spokes, dashed inflows crossing the permeable boundary, and a curved arc with one outward waypoint.

## Final Self-Check
- Every connected edge omits exit/entry ports and uses `edgeStyle=none` or `curved=1`.
- Zones render before (behind) all content; no content node is parented to a zone.
- No chord passes through the hub; ring neighbors keep ≥ 40 px clearance.
- The whole model reads from the center outward (or around the cycle) — if it reads left-to-right instead, switch back to grid-flow mode.
