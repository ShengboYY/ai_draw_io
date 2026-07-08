---
name: drawio-architecture
description: Draw.io architecture diagram skill. Use for system architecture, deployment diagrams, microservices, infrastructure, network topology, gateways, services, storage, middleware, and external integrations.
schemaVersion: 1
category: drawio-design
diagramType: architecture
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "2.0.0"
---

# Draw.io Architecture Diagram Skill

## When To Use [P0]
Use for: system architecture, deployment, microservices, infrastructure, topology, runtime internals (JVM/browser/OS).
Prefer `drawio-uml` for class relationships, `drawio-flowchart` for operation steps, `drawio-sequence` for time-ordered call chains.

## View Selection [P0]
Pick exactly one view and keep every element at that abstraction level. Never mix levels (a context view shows no databases; a container view shows no classes; a deployment view shows no business methods).

| View | When | Show |
| --- | --- | --- |
| context | "how it fits with users/external systems" | focal system centered, actors left, external systems right; no internals |
| container | default for "architecture diagram" | apps, services, DBs, queues inside one system boundary; clients and externals outside |
| component | "inside service/module X" | one service boundary; adapters left/top, domain center, infrastructure right/bottom |
| deployment | cloud, topology, K8s, VPC, environments | regions/zones/clusters as nested boundaries, nodes and managed services inside |
| dynamic | "what happens when X", one request/event path | only participating elements, numbered steps along the path |
| runtime | JVM/browser/OS internals | one runtime boundary, real regions (loading left, memory center, execution right, native bottom) |

## Rules [P0]
1. One focal boundary; actors and external systems stay outside it. At most two nested boundary levels (three only for deployment zones or runtime regions).
2. 6–14 core nodes; summarize the rest into aggregate nodes ("Other services") instead of crowding.
3. Layer order: clients top/left → gateway/access → services → middleware/AI → data stores bottom; external systems right.
4. Databases and caches are cylinders, never plain rectangles; queues/brokers use `shape=process`; human actors use `shape=umlActor`. For cloud/Kubernetes/network deployment views, use named Draw.io library shapes/stencils when known instead of generic rounded rectangles.
5. Each edge has one semantic type: request (solid), async event (dashed), data read/write (to storage). Label with protocol or purpose, not sentences.
6. Routing follows relationship shape: hierarchy/fan-out/dependency edges use straight `edgeStyle=none` with no waypoints; network wiring, cross-zone traffic, dense workflow, and runtime gutter paths use orthogonal tracks.
7. Don't draw every dependency: one summarized edge per repeated relationship; keep the main request path visually dominant; route secondary edges through outer gutters.
8. External/third-party systems use a dashed stroke.
9. Add a small legend only when ≥3 edge colors or dashed meanings aren't obvious.
10. Technology names go on a second smaller label line, not the primary name.
11. For runtime views (JVM/browser/OS internals):
    - Regions are real containers (`container=1`) with pastel fills and top-left bold labels; children are parented to the region with region-relative coordinates.
    - Memory areas and runtime modules are rounded rectangles — NEVER cylinders. Cylinders are reserved for external data stores in container/deployment views.
    - Size each region to its content with ~30px padding: no oversized regions around one node, no empty bands between child rows.
    - At most 7 cross-region edges. Each takes its own gutter x-track (never stack two edges on one track); put the label near the source turn, not floating mid-channel. Dashed for GC/management/native paths.

## Container View Example [P1]

The golden example in `drawio-visual-design` is the canonical container-view shape: dashed transparent boundary behind content, clients outside left, external systems dashed-stroke outside right, storage as cylinders directly below their owning service (clear vertical drop, no crossings), and parallel edges into one node on distinct tracks (entryY 0.3 / 0.7). Follow it for context/container/component/deployment views.

## Golden Example (runtime view — JVM) [P0]

```xml
<mxCell id="2" value="JVM Runtime" style="rounded=0;whiteSpace=wrap;html=1;fillColor=none;dashed=1;strokeColor=#666666;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="40" y="60" width="1060" height="580" as="geometry"/></mxCell>
<mxCell id="3" value="Class Loading" style="rounded=1;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;fillColor=#dae8fc;strokeColor=#6c8ebf;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="70" y="120" width="230" height="180" as="geometry"/></mxCell>
<mxCell id="4" value="Class Loader Subsystem&lt;br&gt;&lt;font style=&quot;font-size:10px&quot;&gt;load / link / init&lt;/font&gt;" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#6c8ebf;fontSize=12;" vertex="1" parent="3"><mxGeometry x="35" y="60" width="160" height="70" as="geometry"/></mxCell>
<mxCell id="5" value="Runtime Data Areas" style="rounded=1;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;fillColor=#fff2cc;strokeColor=#d6b656;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="330" y="120" width="370" height="330" as="geometry"/></mxCell>
<mxCell id="6" value="Method Area" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="5"><mxGeometry x="30" y="60" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="7" value="Heap" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="5"><mxGeometry x="190" y="60" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="8" value="Java Stack" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="5"><mxGeometry x="30" y="170" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="9" value="PC Register" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="5"><mxGeometry x="190" y="170" width="150" height="70" as="geometry"/></mxCell>
<mxCell id="10" value="Native Method Stack" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#d6b656;fontSize=12;" vertex="1" parent="5"><mxGeometry x="110" y="270" width="150" height="50" as="geometry"/></mxCell>
<mxCell id="11" value="Execution Engine" style="rounded=1;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;fillColor=#e1d5e7;strokeColor=#9673a6;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="800" y="120" width="230" height="330" as="geometry"/></mxCell>
<mxCell id="12" value="Interpreter" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#9673a6;fontSize=12;" vertex="1" parent="11"><mxGeometry x="35" y="60" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="13" value="JIT Compiler" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#9673a6;fontSize=12;" vertex="1" parent="11"><mxGeometry x="35" y="150" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="14" value="Garbage Collector" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#9673a6;fontSize=12;" vertex="1" parent="11"><mxGeometry x="35" y="240" width="160" height="60" as="geometry"/></mxCell>
<mxCell id="15" value="Native Interface" style="rounded=1;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;fillColor=#f8cecc;strokeColor=#b85450;verticalAlign=top;fontStyle=1;fontSize=13;align=left;spacingLeft=8;" vertex="1" parent="1"><mxGeometry x="800" y="500" width="230" height="100" as="geometry"/></mxCell>
<mxCell id="16" value="JNI Libraries" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#ffffff;strokeColor=#b85450;fontSize=12;" vertex="1" parent="15"><mxGeometry x="35" y="30" width="160" height="50" as="geometry"/></mxCell>
<mxCell id="17" value=".class Files" style="rounded=1;whiteSpace=wrap;html=1;fillColor=#d5e8d4;strokeColor=#82b366;fontSize=12;" vertex="1" parent="1"><mxGeometry x="70" y="380" width="150" height="60" as="geometry"/></mxCell>
<mxCell id="18" value="bytecode" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=0;entryX=0.5;entryY=1;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="17" target="4"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="19" value="loads metadata" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="4" target="6"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="20" value="reads / writes" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.3;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="7" target="12"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="21" value="hot methods" style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="12" target="13"><mxGeometry relative="1" as="geometry"/></mxCell>
<mxCell id="22" value="reclaims" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=0;exitY=0.5;entryX=1;entryY=0.7;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="14" target="7"><mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="750" y="390"/><mxPoint x="750" y="229"/></Array></mxGeometry></mxCell>
<mxCell id="23" value="native call" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;entryX=1;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="13" target="16"><mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="1055" y="300"/><mxPoint x="1055" y="555"/></Array></mxGeometry></mxCell>
<mxCell id="24" value="JNI calls" style="endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;fontSize=11;" edge="1" parent="1" source="10" target="16"><mxGeometry relative="1" as="geometry"><Array as="points"><mxPoint x="760" y="415"/><mxPoint x="760" y="555"/></Array></mxGeometry></mxCell>
```

Note the runtime pattern: pastel region containers sized to their content with children parented inside (region-relative coordinates), memory areas as plain rounded rectangles (no cylinders), only 7 cross-region edges, and each long edge on its own gutter track (x=750 / x=760 / x=1055) with dashed styling for GC and native paths.

## Checklist [P1]
- Single abstraction level; boundary semantics are real (system/zone), not decoration.
- Main request path readable at a glance; no edge crosses a service body; storage under its owning service.
- Every edge labeled with protocol or purpose; async paths dashed.
