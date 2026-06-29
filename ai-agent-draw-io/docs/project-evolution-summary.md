# AI Draw.io Agent Project Evolution Summary

## 1. Overall Evolution

Compared with the initial demo, the current project has evolved from a simple AI-assisted Draw.io generation prototype into a multi-agent diagram generation and review system. The original version mainly focused on sending a user prompt to an AI agent and rendering the generated Draw.io result. The current version adds clearer workflow control, canvas-aware interaction, diagram-type skills, review capability, and a cleaner backend architecture.

The main direction of the evolution is not only to make the system generate diagrams, but also to make it understand user intent, preserve canvas context, choose suitable diagram expertise, review the output, and support iterative improvement.

## 2. Frontend Experience Improvements

The frontend was simplified and adjusted around the Draw.io use case. PPT-related entrance points were hidden or redirected so that users can enter the Draw.io workspace directly. Unnecessary top navigation controls were also removed to reduce visual noise and keep the interface focused on diagram editing and AI interaction.

Several layout improvements were made to support a better working experience:

- The Draw.io canvas and the chat panel were enlarged vertically after removing unused top controls.
- The right-side chat panel can still be collapsed, so users can focus on the canvas when needed.
- The canvas and chat panel can be resized by dragging, which gives users more control over the workspace.
- The canvas context button was removed, and canvas context is now enabled by default within the same chat session.
- Session renaming was added, making drawing records easier to organize.
- The model selector and maximum review loop setting were moved above the chat input, making runtime configuration more visible.

The chat experience was also improved. Execution steps such as analysis, drawing, review, and revision can now be displayed in the conversation. Redundant log-like text below the input was removed, and answer formatting was improved so that direct answers and review suggestions are easier to read.

## 3. Backend Workflow Upgrade

The backend workflow has changed from a mostly direct drawing pipeline into a routed multi-agent workflow. A key upgrade is the introduction of an independent Intent Agent. Instead of relying only on keyword checks, the system now asks the Intent Agent to classify the user request into different types, such as drawing a new diagram, editing the current canvas, asking a general question, requesting a review, or needing clarification.

This change solves an important problem in the original demo: simple messages such as greetings, canvas questions, or "please review but do not modify" should not trigger the full drawing workflow. The system can now route these requests to direct answer or review-answer paths when drawing is not needed.

The backend now supports:

- Intent routing before entering the drawing workflow.
- Direct answers for greetings, capability questions, and non-mutating requests.
- Canvas-aware review answers for questions about current diagram quality.
- Configurable maximum review loops.
- Environment-variable-based LLM configuration for safer demo usage.
- Cleaner separation between HTTP endpoints, conversation orchestration, and stream response writing.

Most recently, the heavy controller problem was addressed. `AgentServiceController` was reduced to a thin HTTP entry layer. Conversation orchestration was moved into `AgentConversationService`, while streaming and Draw.io response formatting were moved into `DrawioStreamResponseWriter`.

## 4. Multi-Agent Drawing and Review Capability

The system now uses a more complete multi-agent process rather than a single drawing agent. The general workflow can be understood as:

- Intent Agent: decides whether the user wants to draw, edit, ask, review, or clarify.
- Analyst Agent: analyzes the drawing requirement and prepares a drawing brief.
- Drawer Agent: generates or updates the Draw.io diagram.
- Reviewer Agent: reviews the generated result.
- Revision Analyst Agent: converts review feedback into the next revision plan.
- Drawer Agent: applies the revision when another loop is needed.

This design allows the system to move closer to an iterative diagram-building workflow. Instead of generating a diagram once and stopping, the system can review and refine the result for a configurable number of loops.

The review capability was also expanded. The system now includes a deterministic quality inspector and semantic review chain. The quality inspector checks visual and structural issues such as layout, spacing, readability, isolated nodes, duplicate labels, and edge routing risks. The semantic reviewer focuses more on content correctness, missing concepts, and relationship quality.

This is important because a diagram can look visually acceptable but still be conceptually incomplete or inaccurate. By separating visual quality from semantic correctness, the system can provide more useful review feedback.

## 5. Skill System Expansion

The project now includes a skill-oriented design for different diagram types. Instead of using one generic drawing prompt for every scenario, different diagram categories can use different skill instructions.

Supported diagram skills include:

- UML class diagram
- Flowchart
- Architecture diagram
- Sequence diagram
- ER diagram
- Use case diagram
- State diagram

This improves extensibility because each diagram type has its own conventions, expected elements, relationship rules, and layout preferences. For example, a UML class diagram needs classes, fields, methods, and associations, while an architecture diagram focuses more on components, boundaries, dependencies, and data flow.

The system prompt, skill descriptions, and frontend-visible labels were also adjusted toward English, which makes the project easier to present in an English-speaking academic or demo environment while still supporting both English and Chinese user input.

## 6. Canvas Context and Session Handling

The original demo was more likely to treat each request as an isolated generation task. The current version keeps canvas context active by default in the same chat session. This helps the system understand whether the user is asking to create a new diagram or modify the existing one.

The frontend stores drawing sessions and canvas records locally, which is suitable for demo usage. Users can close and reopen the page and still recover local records on the same browser. However, this is not yet a production-level persistence solution. For real deployment, user accounts, backend storage, and database-backed session records would be needed.

## 7. Project Cleanup and Configuration

Several cleanup tasks were completed to make the project more focused:

- Docker and deployment-related files were removed because the current demo does not rely on Docker deployment.
- LLM API keys were moved away from hardcoded YAML values and can be configured through environment variables.
- A `.env`-style workflow was discussed and supported for easier demo startup.
- Author/name-related traces and unnecessary deployment references were cleaned up.
- PPT-related behavior was reduced from the active Draw.io workflow.

These changes make the project easier to explain as a focused AI Draw.io agent system rather than a mixed PPT/diagram demo.

## 8. Current Architectural State

The current architecture is cleaner than the initial demo, especially after moving conversation orchestration out of the controller. The backend now has a clearer separation:

- Controller layer: handles HTTP requests and responses.
- Conversation service: coordinates routing, sessions, review context, and agent execution.
- Stream writer: handles SSE/NDJSON response formatting and Draw.io output parsing.
- Domain services: provide chat, intent routing, quality inspection, and canvas review capabilities.

There are still areas that can be improved later:

- Internal agent IDs are still configured in code and can be moved to properties.
- The frontend Draw.io page is still large and can be split into hooks and smaller components.
- Canvas context can be further structured into a formal snapshot object instead of relying mainly on raw XML and prompt text.
- Production deployment would need backend persistence for sessions, users, and canvas versions.

## 9. Summary

Overall, the project has moved from a basic AI drawing demo toward a more complete AI diagramming system. The most important improvements are intent routing, canvas-aware interaction, diagram-specific skills, review and revision loops, and cleaner backend orchestration.

The current version is suitable for demonstrating a multi-agent Draw.io workflow: the system can understand user intent, decide whether drawing is needed, generate diagrams with type-specific guidance, review visual and semantic quality, and support iterative refinement. The next stage should focus on production-level persistence, stronger canvas snapshot modeling, frontend component decomposition, and configuration cleanup.
