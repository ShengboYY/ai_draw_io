---
name: drawio-visual-design
description: Shared Draw.io visual design and XML pattern system. Use with every Draw.io diagram skill to choose a design profile, semantic colors, spacing, typography, grouping, edge routing, and reusable Draw.io XML patterns without forcing every diagram to look the same.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Visual Design And XML Pattern Skill

## 1. Purpose
Use this skill together with the selected diagram skill, such as `drawio-architecture`, `drawio-flowchart`, or `drawio-sequence`.

This skill fixes design principles and reusable Draw.io XML patterns, not a single fixed visual style. The selected diagram skill still controls domain semantics, node types, and edge meanings.

Shared scope: these rules apply to every Draw.io diagram type. Do not duplicate this shared contract or the XML patterns inside diagram-specific skills; those skills should add domain semantics, presets, and notation rules only.

## 2. Design Workflow
Privately perform this design workflow before generating Draw.io XML; never output this workflow:
1. Identify the diagram type and the main message: hierarchy, process, time order, data model, scope, lifecycle, or relationship network.
2. Select the diagram subtype from the selected diagram skill, such as architecture view, flow type, sequence scope, UML model scope, ER model scope, use case scope, or state machine scope.
3. Select one design profile from Section 3.
4. Apply the selected diagram skill's domain rules, then use Section 4 to map that domain intent onto the shared visual profiles.
5. Choose a semantic color role for each group or node category.
6. Create a visual blueprint before writing XML: main boundary, regions or lanes, focal nodes, legend need, and connector gutters.
7. Place containers, lanes, lifelines, or main axes first when the selected profile requires them.
8. Place child nodes, then route edges.
9. Check for overlaps, line crossings, line labels covering nodes, and inconsistent styles.

## 3. Design Profiles

### 3.1 Architecture Profile
Use for system architecture, deployment, infrastructure, and topology.
- Prioritize region grouping, layer separation, and dependency direction.
- Use large visual regions only when they represent real boundaries.
- Use soft filled regions and stronger inner node borders.

### 3.2 Flow Profile
Use for flowcharts, approval flows, algorithms, and business processes.
- Prioritize the main path and branch readability.
- Use one dominant direction chosen by the flowchart skill.
- Keep the main path aligned on one axis.

### 3.3 Sequence Profile
Use for sequence diagrams and time-ordered interactions.
- Prioritize time moving downward.
- Keep participant columns stable.
- Use a restrained palette because message order matters more than decoration.

### 3.4 Model Profile
Use for UML class diagrams and ER diagrams.
- Prioritize entity grouping, stable row/column alignment, and readable table/class content.
- Use fewer colors than architecture diagrams; color should distinguish categories, not every entity.

### 3.5 Scope Profile
Use for use case diagrams and actor-function boundary diagrams.
- Prioritize the system boundary and actor placement.

### 3.6 Lifecycle Profile
Use for state diagrams and status transitions.
- Prioritize the main lifecycle path.

### 3.7 Concept Profile
Use when the request is a general concept map or basic diagram and no specialized skill applies.
- Prioritize visual clusters and central topic prominence.
- Use the center for the main concept and place related clusters around it.
- Keep edges sparse; summarize dense relationships into group labels.

## 4. Shared Profile Handoff
This shared skill chooses visual profiles and reusable XML patterns only. The selected diagram skill owns domain semantics, notation, subtype choice, and any preset-specific layout rules.

- Use the selected diagram skill to decide what objects, relationships, labels, and omissions belong in the diagram.
- Use the matching profile in Section 3 to decide the broad visual rhythm, not the domain semantics.
- Use Sections 5-8 for shared color, typography, spacing, routing, text transparency, container, waypoint, and XML hygiene.
- If a domain skill conflicts with a shared visual rule, preserve the domain meaning and adapt the visual treatment without duplicating XML snippets in the domain skill.
- For custom user skills, keep any new domain-specific rules in that custom skill and keep generic Draw.io XML/routing patterns here.

## 5. Visual System Rules
- Use low-saturation fills with darker strokes. Do not use highly saturated fills as large backgrounds.
- Keep one semantic category mapped to one color role across the whole diagram.
- Prefer 3-5 color roles total. If more categories exist, group or abstract them.
- Use consistent rounded corners within the same semantic level.
- Use title text only when it improves orientation; keep it clearly separated from nodes.
- Hard rule for non-node text: standalone labels, captions, section hints, and edge annotations must be transparent and must not hide grid lines, connectors, or nearby shapes.
- For standalone text, use styles like `text;html=1;strokeColor=none;fillColor=none;labelBackgroundColor=none;labelBorderColor=none;align=center;verticalAlign=middle;whiteSpace=wrap;rounded=0;`.
- Do not use `fillColor=#ffffff`, `fillColor=#FFFFFF`, `strokeColor=#ffffff`, or a visible border on plain text cells unless the element is an intentional note, callout, legend, or semantic node.
- For edge labels, prefer the edge `value` with `labelBackgroundColor=none;labelBorderColor=none;` instead of a separate white text box.
- Only use a filled text container when it is a real semantic node, callout, note, legend, or grouping element.
- Use font sizes by hierarchy: title 18-24, container title 13-16, node title 12-14, small detail 10-12.
- Use `whiteSpace=wrap;html=1;` on text nodes so labels remain readable.
- Use shadows sparingly. If used, apply them consistently only to primary nodes, not to large containers.
- Do not create nested decorative cards. Use containers only when they represent real grouping.

## 6. Layout Rules
- Apply the Global Draw.io Layout Contract from the drawer prompt before any diagram-specific preset.
- Use a predictable grid. Same row uses the same y coordinate; same column uses the same x coordinate.
- Keep outer margins at least 60px and leave enough whitespace around group boundaries and connector gutters.
- Keep sibling node sizes consistent unless content length requires a larger box.
- Containers must be drawn behind child nodes and must be large enough to include padding of at least 30px.
- Avoid large empty holes inside a main container unless they intentionally separate layers.
- Prefer abstraction over crowding. If a diagram would exceed about 16 core nodes, group similar nodes or create summarized submodules unless the user explicitly asks for detail.
- First drafts should already be readable. Review repair is a safety net, not the primary layout mechanism.

## 7. Edge Routing Rules
- Prefer orthogonal routing for most diagrams: `edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;`.
- Avoid diagonal lines through node bodies.
- Connect from stable sides of nodes: left-to-right flows use east/west ports; top-to-bottom flows use north/south ports.
- Use explicit exit/entry ports or waypoints when a direct connector would cross a node, container title, lane, group header, or important label.
- Reserve connector gutters outside dense node groups; long cross-group edges should run around the group perimeter instead of through the middle.
- Separate parallel or opposite-direction edges by distinct horizontal/vertical tracks so they do not stack on top of each other.
- Keep edge labels short, transparent, and offset from node text. Do not place labels on top of nodes, container headers, or crossing points.
- Use color for edge semantics only when it adds meaning, such as sync request, async event, data read/write, error path, or inheritance.
- If more than three crossings appear in a central area, change layout before outputting XML.
- For dense diagrams, use grouping and fewer edges instead of drawing every possible dependency.

## 8. Draw.io XML Patterns
Use these snippets as shared XML patterns. The selected diagram skill may choose arrowheads, colors, and labels, but it should keep the structure and style hygiene below.

### 8.1 Standard Orthogonal Connector
Use this for most connected relationships. Put ports inside the `style` string, never as mxCell attributes.

```xml
<mxCell id="edge-1" value="calls" edge="1" parent="1" source="source-id" target="target-id"
  style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;labelBorderColor=none;">
  <mxGeometry relative="1" as="geometry"/>
</mxCell>
```

### 8.2 Obstacle-Avoidance Waypoints
Use waypoints when a direct connector would cross a node, label, group header, lane, or central story area.

```xml
<mxCell id="edge-2" value="async event" edge="1" parent="1" source="source-id" target="target-id"
  style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;exitX=1;exitY=0.5;entryX=0;entryY=0.5;labelBackgroundColor=none;labelBorderColor=none;">
  <mxGeometry relative="1" as="geometry">
    <Array as="points">
      <mxPoint x="360" y="140"/>
      <mxPoint x="360" y="260"/>
    </Array>
  </mxGeometry>
</mxCell>
```

### 8.3 Sequence Message Connector
Use elbow connectors for sequence-message rows where time moves downward and lifelines stay fixed.

```xml
<mxCell id="edge-3" value="request()" edge="1" parent="1" source="client" target="service"
  style="html=1;verticalAlign=bottom;endArrow=block;edgeStyle=elbowEdgeStyle;elbow=vertical;labelBackgroundColor=none;labelBorderColor=none;">
  <mxGeometry relative="1" as="geometry"/>
</mxCell>
```

### 8.4 Unavoidable Crossing Marker
Prefer layout changes, side ports, gutters, or waypoints first. Use `jumpStyle=arc;jumpSize=10` only when a remaining crossing is unavoidable and marking it improves readability.

```xml
<mxCell id="edge-4" value="secondary" edge="1" parent="1" source="source-id" target="target-id"
  style="endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;jumpStyle=arc;jumpSize=10;exitX=0.5;exitY=1;entryX=0.5;entryY=0;labelBackgroundColor=none;labelBorderColor=none;">
  <mxGeometry relative="1" as="geometry"/>
</mxCell>
```

### 8.5 Containers And Children
Containers should be sibling mxCells under `parent="1"`. Child nodes reference the container id through `parent="<container-id>"`; never nest an mxCell element inside another mxCell element.

```xml
<mxCell id="group-1" value="Subsystem" vertex="1" parent="1"
  style="rounded=1;whiteSpace=wrap;html=1;container=1;collapsible=0;recursiveResize=0;fillColor=#f5f5f5;strokeColor=#666666;">
  <mxGeometry x="80" y="80" width="360" height="220" as="geometry"/>
</mxCell>
<mxCell id="node-1" value="Worker" vertex="1" parent="<container-id>"
  style="rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;">
  <mxGeometry x="40" y="60" width="140" height="60" as="geometry"/>
</mxCell>
```

## 9. Output Self-Check
Before outputting the final `drawio_done`, check:
- The selected design profile matches the requested diagram type.
- The content matches the selected diagram type, not just the visual style.
- The diagram has a visible hierarchy: title, containers or lanes, nodes, then details.
- Same-level nodes are aligned and similarly sized.
- No node overlaps another node or container title.
- No edge label sits on top of node text.
- No plain `text` mxCell uses an opaque fill, white background, or visible border unless it is an intentional callout, note, legend, or semantic node.
- Edge labels and standalone labels include `labelBackgroundColor=none` and do not visually mask connectors.
- Connectors use stable ports, clear gutters, and minimal crossings instead of running through the center of unrelated groups.
- The diagram has one coherent style system and not a random mix of colors.
- The result is readable at normal Draw.io zoom without requiring the user to inspect tiny text.
