---
name: drawio-visual-design
description: Shared Draw.io visual design system. Use with every Draw.io diagram skill to choose a diagram-specific design profile, semantic colors, spacing, typography, grouping, and edge routing without forcing every diagram to look the same.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Visual Design System Skill

## 1. Purpose
Use this skill together with the selected diagram skill, such as `drawio-architecture`, `drawio-flowchart`, or `drawio-sequence`.

This skill fixes design principles, not a single fixed visual style. The selected diagram skill still controls domain semantics, node types, and edge meanings.

## 2. Design Workflow
Privately perform this design workflow before generating Draw.io XML; never output this workflow:
1. Identify the diagram type and the main message: hierarchy, process, time order, data model, scope, lifecycle, or relationship network.
2. Select the diagram subtype from the selected diagram skill, such as architecture view, flow type, sequence scope, UML model scope, ER model scope, use case scope, or state machine scope.
3. Select one design profile from Section 3.
4. Apply the type-specific content and design rubric from Section 4.
5. Choose a semantic color role for each group or node category.
6. Create a visual blueprint before writing XML: main boundary, regions or lanes, focal nodes, legend need, and connector gutters.
7. Place containers, lanes, lifelines, or main axes first when the selected profile requires them.
8. Place child nodes, then route edges.
9. Check for overlaps, line crossings, line labels covering nodes, and inconsistent styles.

## 3. Design Profiles

### 3.1 Architecture Profile
Use for system architecture, deployment, infrastructure, and topology.
- Prioritize large region grouping, layer separation, and dependency direction.
- Use large containers for domains, networks, clusters, runtime areas, or layers.
- Place clients/access near the top or left, core services in the center, data stores near the bottom, and external systems to the right.
- Use soft filled regions and stronger inner node borders.
- Use C4-style discipline: choose one architecture view, draw boundaries before components, draw only meaningful relationships, and include a compact legend when visual encodings carry meaning.
- Apply the architecture view contract before drawing: scope boundary, abstraction level, region map, main axis, legend need, connector types, and omissions must be clear.
- For complex architecture diagrams, layout presets are adaptable skeletons: preserve their proportions, slots, gutters, and legend placement, but adapt content instead of copying them exactly.

### 3.2 Flow Profile
Use for flowcharts, approval flows, algorithms, and business processes.
- Prioritize the main path and branch readability.
- Use one dominant direction: top-to-bottom for processes, left-to-right for short pipelines.
- Keep the main path aligned on one axis.
- Put exception paths to one side and reconnect only when it improves readability.

### 3.3 Sequence Profile
Use for sequence diagrams and time-ordered interactions.
- Prioritize time moving downward.
- Align lifelines horizontally at equal spacing.
- Use a restrained palette because message order matters more than decoration.
- Keep message labels short and place returns below calls.

### 3.4 Model Profile
Use for UML class diagrams and ER diagrams.
- Prioritize entity grouping, stable row/column alignment, and readable table/class content.
- Put central domain entities in the center, supporting entities around them, and parent/base abstractions above children.
- Use fewer colors than architecture diagrams; color should distinguish categories, not every entity.

### 3.5 Scope Profile
Use for use case diagrams and actor-function boundary diagrams.
- Prioritize the system boundary and actor placement.
- Keep actors outside boundaries and use cases inside.
- Put primary actors on the left, secondary systems on the right, and core use cases near the center.

### 3.6 Lifecycle Profile
Use for state diagrams and status transitions.
- Prioritize the main lifecycle path.
- Put initial state on the left/top and final state on the right/bottom.
- Put error, cancel, retry, or rollback paths above/below the main path.

### 3.7 Concept Profile
Use when the request is a general concept map or basic diagram and no specialized skill applies.
- Prioritize visual clusters and central topic prominence.
- Use the center for the main concept and place related clusters around it.
- Keep edges sparse; summarize dense relationships into group labels.

## 4. Type-Specific Content And Design Rubric

### 4.1 Architecture Diagrams
- Content focus: system boundaries, layers, components, dependencies, data stores, external systems, and main traffic/data paths.
- Design focus: region hierarchy, layer separation, and low-crossing dependency routes.
- Use containers only for real boundaries such as runtime areas, service groups, networks, teams, layers, or deployment zones.
- Prefer a few meaningful paths over drawing every possible dependency.
- Avoid turning architecture diagrams into step-by-step flowcharts unless the user asks for a request flow overlay.
- For context, container, component, deployment, dynamic, integration/data, and runtime architecture subtypes, keep the selected abstraction level visible and omit details that belong to another architecture view.
- For runtime architecture subtypes, use real execution or memory regions rather than generic boxes. Group by ownership or scope, such as thread-private, thread-shared, execution engine, class loading, native interface, or platform boundary.
- When a subtype has a layout preset, use it as a starting skeleton and apply slot expansion rules before adding free-form regions.

### 4.2 Flowcharts
- Content focus: start, actions, decisions, branches, merges, and terminal outcomes.
- Design focus: one dominant main path with side branches that are easy to follow.
- Node labels should be actions or decisions, not static system components.
- Decision nodes must have clearly labeled outgoing branches.
- Avoid large architecture-style containers unless the user explicitly asks for swimlanes or phases.

### 4.3 Sequence Diagrams
- Content focus: participants, ordered messages, returns, async events, and optional activations.
- Design focus: fixed participant columns and strict top-to-bottom time order.
- Message labels should describe calls, events, or returned values.
- Use a restrained palette; color should not compete with temporal order.
- Avoid placing unrelated state boxes, database schemas, or architecture regions inside a sequence diagram.

### 4.4 UML Class Diagrams
- Content focus: classes, interfaces, enums, attributes, methods, and object relationships.
- Design focus: readable class compartments and relationship semantics.
- Put parent abstractions above child classes and central domain classes near the middle.
- Use color only for real categories such as interface, enum, abstract, external dependency, or core domain.
- Avoid process arrows, step labels, and database table notation unless the user asks for a mixed model.

### 4.5 ER Diagrams
- Content focus: entities/tables, fields, primary keys, foreign keys, and cardinality.
- Design focus: readable table content and clear relationship direction.
- Put central transaction or aggregate tables near the middle.
- Use join tables for many-to-many relationships unless the user requests a conceptual ER diagram.
- Avoid methods, service names, and workflow steps inside ER entities.

### 4.6 Use Case Diagrams
- Content focus: actors, user goals, system boundary, include relationships, and extend relationships.
- Design focus: boundary clarity and actor-to-use-case readability.
- Keep actors outside the boundary and use cases inside it.
- Use case labels should be user goals, not UI buttons or implementation classes.
- Avoid internal database tables, service modules, or sequence messages.

### 4.7 State Diagrams
- Content focus: stable states, events, guards, actions, initial state, and final states.
- Design focus: main lifecycle path plus clearly separated failure/retry/cancel paths.
- State labels should be nouns or status phrases; transition labels should be events or conditions.
- Put terminal or failure states where the lifecycle naturally ends.
- Avoid representing a state diagram as a generic business process unless the user asks for process steps.

### 4.8 Concept Or Basic Diagrams
- Content focus: one central idea, major clusters, and a small number of important relationships.
- Design focus: readable clustering and sparse relation lines.
- Use central emphasis for the main topic and place related clusters around it.
- Prefer summaries and group labels over dense spider-web relationships.

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
- Use a predictable grid. Same row uses the same y coordinate; same column uses the same x coordinate.
- Keep outer margins at least 60px and leave enough whitespace around group boundaries.
- Keep sibling node sizes consistent unless content length requires a larger box.
- Containers must be drawn behind child nodes and must be large enough to include padding of at least 30px.
- Avoid large empty holes inside a main container unless they intentionally separate layers.
- Prefer abstraction over crowding. If a diagram would exceed about 16 core nodes, group similar nodes or create summarized submodules unless the user explicitly asks for detail.

## 7. Edge Routing Rules
- Prefer orthogonal routing for most diagrams: `edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;`.
- Avoid diagonal lines through node bodies.
- Connect from stable sides of nodes: left-to-right flows use east/west ports; top-to-bottom flows use north/south ports.
- Use explicit exit/entry ports or waypoints when a direct connector would cross a node, container title, or important label.
- Reserve connector gutters outside dense node groups; long cross-group edges should run around the group perimeter instead of through the middle.
- Separate parallel or opposite-direction edges by distinct horizontal/vertical tracks so they do not stack on top of each other.
- Keep edge labels short, transparent, and offset from node text. Do not place labels on top of nodes, container headers, or crossing points.
- Use color for edge semantics only when it adds meaning, such as sync request, async event, data read/write, error path, or inheritance.
- If more than three crossings appear in a central area, change layout before outputting XML.
- For dense diagrams, use grouping and fewer edges instead of drawing every possible dependency.

## 8. Output Self-Check
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
