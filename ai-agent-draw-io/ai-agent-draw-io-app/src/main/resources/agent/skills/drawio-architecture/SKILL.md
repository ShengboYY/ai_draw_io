---
name: drawio-architecture
description: Draw.io architecture diagram skill. Use for system architecture, deployment diagrams, microservices, infrastructure, network topology, gateways, services, storage, middleware, and external integrations.
license: Apache-2.0
metadata:
  author: ai-draw-io
  version: "1.0.0"
  category: drawio-design
---

# Draw.io Architecture Diagram Skill

## 0. Companion Visual Design Skill
Always use this skill together with `drawio-visual-design`.

For architecture diagrams, use `architecture_profile`: build clear regions first, then layers, then components, then dependency edges. Do not force architecture diagrams into a plain flowchart style.
Runtime internals are an architecture subtype, not a separate diagram category. Keep JVM, browser, OS, and language runtime internals inside this skill with `diagramSubtype=runtime`.

## 1. When To Use
Use this skill when the user asks for:
- System architecture diagrams.
- Deployment diagrams.
- Microservice architecture.
- Network topology.
- Cloud or infrastructure layout.
- Service, storage, middleware, and external integration relationships.

Prefer another skill when:
- The user describes classes and object relationships: use `drawio-uml`.
- The user describes user operation steps: use `drawio-flowchart`.
- The user describes API call order over time: use `drawio-sequence`.

## 2. Architecture View Selection
Before drawing, choose one architecture view and keep the diagram at that level.

- `context`: show the system in the center, users/actors around it, and external systems it directly interacts with. Avoid internal services, databases, protocols, and deployment detail.
- `container`: show applications, services, databases, queues, and major external dependencies inside or around one software system boundary. Include important technology labels only when useful.
- `component`: zoom into one container/service and show internal components, responsibilities, and dependencies. Avoid infrastructure nodes unless they are directly relevant.
- `deployment`: show environments, nodes, clusters, networks, regions, runtime instances, and infrastructure relationships. Avoid business class or table details.
- `dynamic`: show one important request, event, job, or failure path across architecture elements. Use numbered relationship labels and architecture boundaries, but do not turn it into a full sequence diagram.
- `integration_data`: show external systems, APIs, event streams, ETL/data pipelines, ownership boundaries, and data stores. Avoid table fields and ER cardinality unless the user explicitly asks for an ER diagram.
- `runtime`: show major runtime areas or execution subsystems for platforms such as JVM, browser, OS, or language runtimes. Group by real runtime regions, not by arbitrary colors. Favor a clean left-to-right or top-to-bottom reading order with sparse cross-region links.

Rules:
- If the user asks broadly for an architecture diagram, default to `container`.
- If the user asks "how it fits with users/external systems", use `context`.
- If the user asks "inside service/module X", use `component`.
- If the user asks for cloud, topology, nodes, Kubernetes, VPC, or environments, use `deployment`.
- If the user asks for a request path, event path, job path, failure path, or "what happens when X", use `dynamic` unless they explicitly want lifelines or API call order, in which case use `drawio-sequence`.
- If the user asks for integration landscape, API ecosystem, event-driven architecture, data flow between systems, CDC, ETL, or analytics pipeline, use `integration_data`.
- If the user asks for JVM/browser/OS/runtime internals, use `runtime`.
- Do not mix all views in one diagram unless the user explicitly asks for a multi-view overview.

## 3. Node Styles

### 3.1 Boundary / Group
Use for VPC, subnet, cluster, module group, availability zone, or deployment boundary.

```xml
<mxCell id="2" value="Production VPC" style="swimlane;whiteSpace=wrap;html=1;dashed=1;fillColor=#f5f5f5;fontColor=#333333;strokeColor=#666666;startSize=30;align=center;" vertex="1" parent="1">
  <mxGeometry x="50" y="50" width="700" height="420" as="geometry"/>
</mxCell>
```

### 3.2 Service / Server
Use for application services, microservices, containers, workers, and schedulers.

Style:
`rounded=1;whiteSpace=wrap;html=1;fillColor=#dae8fc;strokeColor=#6c8ebf;shadow=1;fontStyle=1;`

### 3.3 Database / Storage
Use a cylinder for relational databases, caches, object storage, vector databases, or document stores.

Style:
`shape=cylinder3;whiteSpace=wrap;html=1;boundedLbl=1;backgroundOutline=1;size=15;fillColor=#ffe6cc;strokeColor=#d79b00;shadow=1;`

### 3.4 Message Queue / Middleware
Use a process or component shape for Kafka, RabbitMQ, Redis Stream, workflow engines, or brokers.

Style:
`shape=process;whiteSpace=wrap;html=1;backgroundOutline=1;fillColor=#e1d5e7;strokeColor=#9673a6;`

### 3.5 Client / User
Use actor or device-like nodes for Web, Mobile, Admin, or external users.

Style:
`shape=umlActor;verticalLabelPosition=bottom;verticalAlign=top;html=1;outlineConnect=0;fillColor=#f8cecc;strokeColor=#b85450;`

## 4. Layering Rules
- Client layer: Web, Mobile, Admin, Third-party.
- Access layer: CDN, Nginx, API Gateway, Load Balancer.
- Application layer: business services, microservices, workers, schedulers.
- Capability layer: LLM, MCP Server, Search, RAG, Message Queue.
- Data layer: MySQL, PostgreSQL, Redis, Object Storage, Vector DB.
- Observability layer if needed: Logging, Metrics, Tracing, CI/CD.

## 4.1 General Architecture View Contract
Use this contract for every `drawio-architecture` subtype. It is inspired by C4/Structurizr-style view discipline and diagram-as-code layout rules: define scope first, then boundaries, then elements, then relationships, then legend.

- First create a visual blueprint in the drawing brief or internal planning step: selected subtype, scope boundary, abstraction level, region map, main reading axis, legend need, connector types, connector gutters, and what to omit.
- Keep one focal system or boundary. Put external actors/systems outside it, and put internal elements inside it only when they belong to the selected view.
- Do not mix abstraction levels. Context diagrams do not show containers; container diagrams do not show classes; deployment diagrams do not show business methods; integration/data diagrams do not show table fields.
- Use two nested boundary levels at most for normal diagrams. Add a third level only for deployment zones, runtime memory regions, or a user-requested deep zoom.
- Prefer 6-14 core nodes and 5-18 meaningful relationships. If the source domain is larger, summarize with grouped capabilities, buses, or "other services" style aggregate nodes.
- Each edge must have one clear semantic type: request, event, data read/write, dependency, deployment placement, control/management, or native/platform call.
- Add a compact legend when the diagram uses three or more edge colors, dashed edges, icon categories, or region colors whose meaning is not obvious.
- Route long cross-boundary edges through outer gutters and connect from stable sides. Keep the center for the main story, not for every secondary dependency.

### 4.1.1 Adaptive Layout Presets
Use adaptive layout presets for complex architecture diagrams. A preset is a layout skeleton with slots, proportions, connector gutters, and style grammar. It is not a fixed answer.

- Start from the closest adaptive layout preset when the requested subtype matches one. Preserve its layout grammar, spacing rhythm, region proportions, connector gutters, and legend placement.
- Do not copy templates exactly. Adapt labels, optional nodes, omitted nodes, and relationships to the user request.
- Treat preset regions as slots. Place new concepts in the matching slot before creating a new region.
- If a slot overflows, use the expansion rules for that preset: split into a small grid, summarize into an aggregate node, or choose a larger preset.
- If the user request conflicts with a preset, preserve readability over template fidelity. Pick the closest larger preset or create a clean variant that keeps the same visual grammar.
- Do not force every requested concept into the preset. When extra details would cause clutter, summarize them or mention them as a grouped capability.

#### JVM Runtime Adaptive Preset
Use this preset for JVM/runtime architecture diagrams with 10-20 important concepts.

Layout skeleton:
- Main boundary slot: x=120, y=90, width=1040, height=660. Use a neutral soft background, 2px dark outline, and a simple title label, not a heavy swimlane header when avoidable.
- External input slot: x=40, y=285, width=150, height=95 for `.class Bytecode`.
- Left slot: x=160, y=145, width=210, height=390 for `Class Loader Subsystem`. Place 3-4 vertical child slots: Loading, Linking, Initialization, optional Verification/Preparation/Resolution group.
- Center top slot: x=410, y=145, width=390, height=210 for thread-private runtime data. Use a 2-column mini-grid for PC Register, VM Stack, Native Method Stack, and optional Thread Local/Frame detail.
- Center bottom slot: x=410, y=390, width=390, height=220 for thread-shared runtime data. Use 2-4 child slots for Heap, Method Area/Metaspace, Runtime Constant Pool, String Pool, or Code Cache.
- Right slot: x=850, y=145, width=260, height=390 for `Execution Engine`. Use 3 vertical child slots for Interpreter, JIT Compiler, and Garbage Collector.
- Bottom native slot: x=330, y=635, width=570, height=95 for JNI and Native Method Libraries.
- Platform slot: x=460, y=785, width=440, height=70 for Operating System & Hardware.
- Legend slot: outside the main boundary, normally x=930, y=785, width=230, height=120. Keep legend smaller than any major region.

Connector grammar:
- Use a blue loading/class-metadata bus from `.class Bytecode` through Class Loader to runtime data. Do not draw separate long class-loading edges for every child node.
- Use one orange memory-access bus between Execution Engine and thread-shared data. Connect Interpreter/JIT/GC to this bus with short stubs.
- Use one purple native-call route from Execution Engine down the right gutter to the bottom native slot.
- Use one red platform route from Native Libraries down to OS/Hardware.
- Use one green dashed GC route from Garbage Collector to Heap/Method Area. Keep it outside node bodies.
- Place connector labels on gutter segments only. Do not place labels on region titles, child nodes, or central crossing points.

Expansion rules:
- Left slot overflow: merge low-level phases into one `Linking` node with detail text `verify / prepare / resolve`.
- Center top overflow: keep only PC Register, VM Stack, Native Method Stack as visible nodes; move extra per-thread details into the VM Stack detail line.
- Center bottom overflow: use a 2x2 mini-grid. If more than four memory concepts are requested, summarize extras as `Other Shared Runtime Data`.
- Right slot overflow: keep Interpreter, JIT Compiler, and Garbage Collector visible; summarize profiling, optimization tiers, or safepoints as detail text.
- Connector overflow: keep at most seven visible cross-region connectors. Convert repeated or secondary relationships into bus labels or legend entries.

## 4.2 Context Architecture Pattern
Use when `diagramSubtype=context`.

- Layout: focal system in the center; primary users/actors on the left; external systems on the right; upstream/downstream platforms around the perimeter.
- Boundaries: one system boundary only if it clarifies what is inside/outside. Avoid nested internal service regions.
- Nodes: people, roles, external organizations, external software systems, and the focal system. Do not include internal services, databases, queues, or deployment nodes.
- Edges: label with business purpose first and protocol only when helpful, such as `uses`, `submits orders`, `syncs account data`, or `sends notifications`.
- Style: use a neutral focal-system color, soft actor color, and muted external-system color. Keep labels readable for non-technical viewers.
- Legend: optional; use only when edge colors or dashed trust-boundary relationships need explanation.

## 4.3 Container Architecture Pattern
Use when `diagramSubtype=container`.

- Layout: one software-system boundary; clients/access on the left or top; API/gateway layer before core services; data stores at the bottom; external dependencies to the right.
- Nodes: applications, services, workers, queues, caches, databases, object stores, and major third-party systems. Use technology labels as a small second line, not as the primary name.
- Boundaries: group by domain, layer, team ownership, or deployment/runtime zone when that grouping helps scanning. Do not create decorative groups.
- Edges: show user traffic, service calls, async events, and data read/write paths. Use one summarized edge for repeated service-to-data relationships.
- Visual hierarchy: boundary > layer/group > service/store > protocol/detail. Main request path should be visually stronger than secondary dependencies.
- Legend: include when sync, async, data, and external dependency edges use different colors or line styles.

## 4.4 Component Architecture Pattern
Use when `diagramSubtype=component`.

- Layout: one container/service boundary; interface/adapters on the left/top; domain/application components in the center; infrastructure adapters and stores on the right/bottom.
- Nodes: components, modules, ports/adapters, controllers, handlers, use cases, repositories, clients, and local infrastructure dependencies.
- Labels: emphasize responsibility, such as `Auth Policy`, `Order Use Case`, or `Vector Search Adapter`. Technology names are secondary details.
- Edges: dependency direction should be consistent. Prefer inward dependencies for layered/hexagonal designs and label important contracts, events, or repositories.
- Boundaries: use small groups for packages, layers, or bounded contexts only when they clarify ownership. Avoid class-level attributes or methods.
- Legend: include if colors distinguish layers such as interface, domain, infrastructure, and external.

## 4.5 Deployment Architecture Pattern
Use when `diagramSubtype=deployment`.

- Layout: environments or regions as large outer boundaries; networks/VPCs/clusters inside; nodes or managed services inside those boundaries; observability/control plane off to the side.
- Nodes: regions, availability zones, VPCs, subnets, clusters, pods, VMs, runtime instances, load balancers, gateways, managed databases, queues, storage, secrets, and monitoring tools.
- Boundaries: show real deployment or network containment. Use dashed borders for external/shared/provider-managed areas.
- Edges: distinguish traffic path, deployment placement, replication, management/control plane, and observability telemetry. Avoid drawing business method calls.
- Style: use infrastructure-shaped nodes where possible and keep application containers visually different from physical/runtime nodes.
- Legend: recommended when traffic, management, replication, and telemetry use different line styles.

## 4.6 Dynamic Architecture Pattern
Use when `diagramSubtype=dynamic`.

- Layout: keep the normal architecture boundaries but highlight one scenario path across them. Arrange participants roughly left-to-right by request/event progression.
- Nodes: only the elements that participate in the scenario plus one or two context nodes needed for orientation.
- Edges: number important steps (`1`, `2`, `3`) and use short labels. Dashed edges can show async events or eventual side effects.
- Do not include every possible dependency from the static architecture. The point is one architectural behavior, not a full dependency map.
- Use callouts sparingly for failure, retry, fallback, or consistency behavior. Plain callout text must remain transparent unless it is a deliberate note box.
- Legend: include when numbered steps combine with sync/async/data line styles.

## 4.7 Integration And Data Architecture Pattern
Use when `diagramSubtype=integration_data`.

- Layout: producers on the left, integration/API/event layer in the center, consumers and analytical systems on the right, data stores or lakes at the bottom.
- Nodes: source systems, API gateways, integration services, message brokers, schemas/contracts, CDC/ETL jobs, stream processors, data stores, warehouses/lakes, and consuming apps.
- Boundaries: separate ownership domains, trust zones, or data platforms. Use dashed borders for external organizations or SaaS systems.
- Edges: distinguish synchronous API calls, async events, batch movement, CDC/replication, and read/query access. Do not show table-level ER relationships here.
- Labels: use data product/event names such as `OrderCreated`, `customer profile`, or `daily settlement batch`, not implementation paragraphs.
- Legend: usually needed because data/integration diagrams depend on edge colors and dashed/solid styles.

## 4.8 Runtime Architecture Pattern
Use this pattern when `diagramSubtype=runtime`. It is inspired by C4-style boundaries: first define the runtime boundary, then real regions, then child subsystems, then a small set of meaningful relationships.

### 4.8.1 Region Layout
- Keep `runtime` inside `drawio-architecture`; do not introduce a separate runtime skill or diagram type.
- Draw one main runtime boundary first, using a neutral light background and a strong dark outline.
- Place input/loading or ingress subsystems on the left.
- Place central runtime data or memory regions in the middle.
- Place execution, scheduling, interpretation, compilation, or dispatch engines on the right.
- Place native interfaces, platform APIs, OS, hardware, or external dependencies at the bottom or outside the main boundary.
- For JVM diagrams, prefer this default layout:
  - left: `Class Loader Subsystem` with `Loading`, `Linking`, `Initialization`;
  - center top: thread-private runtime data such as `PC Register`, `VM Stack`, `Native Method Stack`;
  - center bottom: thread-shared runtime data such as `Heap` and `Method Area / Metaspace`;
  - right: `Execution Engine` with `Interpreter`, `JIT Compiler`, `Garbage Collector`;
  - bottom: `JNI / Native Interface` and `Native Method Libraries`;
  - outside left/bottom: `.class bytecode` and optional `Operating System & Hardware`.

### 4.8.2 Visual Style
- Use soft pastel region fills with stronger strokes: blue for thread-private or control regions, yellow/gold for thread-shared memory, green for execution engine, purple for class loading, red/pink for native interface and libraries.
- Use dashed borders for secondary or external-adjacent regions, such as class loading and native libraries.
- Use 2px strokes for major regions, 1px strokes for leaf nodes, and consistent 8-12px rounded corners.
- Keep at most two nested region levels, except when a runtime memory area naturally has subregions such as young/old heap generations.
- Prefer compact labels with one primary name and one smaller detail line. Avoid paragraph-length explanations inside nodes.
- Add a legend when the diagram uses three or more edge colors, dashed lines, or more than four region colors.
- Put the legend outside the busiest area, normally bottom-right, and keep it small with swatches and line samples.

### 4.8.3 Runtime Edge Semantics
- Use blue edges for loading, class metadata, or lifecycle/control movement.
- Use orange edges for bytecode execution, hot code, object allocation, heap access, or GC-related memory management.
- Use green or gray dashed edges for management, monitoring, or background coordination.
- Use purple edges for native calls and JNI/native library interaction.
- Use red edges only for native boundary crossings, callbacks, or potentially unsafe direct platform calls.
- Route cross-region edges through outer gutters; avoid long horizontal lines through the middle of runtime data regions.
- Prefer one labeled bus or summarized edge for repeated similar interactions instead of drawing every low-level relation.

## 5. Edge Semantics
- Synchronous request: solid arrow, label with protocol or purpose.
  `endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;strokeWidth=2;strokeColor=#666666;labelBackgroundColor=none;labelBorderColor=none;`
- Asynchronous event: dashed arrow, label with event name.
  `endArrow=classic;html=1;dashed=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;strokeColor=#9673a6;labelBackgroundColor=none;labelBorderColor=none;`
- Data read/write: orange or brown arrow, label with `read`, `write`, or `query`.
  `endArrow=classic;html=1;edgeStyle=orthogonalEdgeStyle;rounded=0;orthogonalLoop=1;jettySize=auto;strokeColor=#d79b00;labelBackgroundColor=none;labelBorderColor=none;`

## 6. Layout Rules
- Arrange layers from top to bottom or left to right.
- Put users and clients at the top or far left.
- Put databases and stores at the bottom.
- Put external services to the right.
- Draw large boundaries first, then place child components inside them.
- Keep service nodes the same size when possible.
- Use special shapes for databases and queues instead of all-rectangle diagrams.
- Keep horizontal spacing >= 180 and vertical spacing >= 140.
- For runtime diagrams, place major regions in clear columns or rows, then route cross-region dependencies through outer gutters.
- Do not draw every low-level dependency when it creates spaghetti lines; summarize repeated relationships with one labeled edge or bus.
- Use side ports and waypoints for long edges so arrows bend around containers and node bodies.
- For runtime diagrams, reserve a vertical connector gutter between the central runtime data region and the execution engine.
- Keep edge labels outside node bodies and outside region titles; put labels near turns or along gutter segments.

## 7. Quality Checklist
- The diagram must show both system structure and main interaction paths.
- The selected architecture view is clear and not mixed with unrelated detail levels.
- Databases should not be represented as normal service rectangles.
- Edges should not cross important service nodes.
- Edges should not stack or cross heavily in the main reading area; reroute them around regions or reduce edge count.
- If there are many services, group or abstract them rather than creating a crowded diagram.
- Labels should describe protocols, dependencies, or data flow clearly.
- Context diagrams should be understandable to non-technical viewers.
- Container and component diagrams should show responsibilities, not only technology names.
- Deployment diagrams should distinguish logical services from physical/runtime nodes.
- Dynamic architecture diagrams should show one scenario path and omit static dependencies that do not participate in that path.
- Integration/data architecture diagrams should distinguish API, event, batch, CDC/replication, and query/read flows without turning into an ER diagram.
- Runtime diagrams should show the major runtime regions, their ownership or scope, and only the relationships needed to explain the architecture.
- Runtime diagrams should include a legend when semantic colors or dashed line meanings are not obvious.
