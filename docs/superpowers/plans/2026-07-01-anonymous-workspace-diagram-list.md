# Anonymous Workspace Diagram List Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users without a real account get a stable anonymous owner id, see their saved diagrams on the home page, and open a diagram back into the Draw.io canvas.

**Architecture:** Keep backend ownership keyed by the existing `userId + diagramId`. Add a small frontend identity adapter that returns either the login-cookie user or a browser-local anonymous id. Add thin backend diagram list/detail APIs over the existing `diagram` and `diagram_canvas_state` tables, then wire a minimal home page and Draw.io restore path.

**Tech Stack:** Next.js/React frontend, TypeScript utility tests with `node --experimental-strip-types`, Spring Boot controller/service, MyBatis mapper, JUnit tests.

---

### Task 1: Anonymous Workspace Identity

**Files:**
- Create: `ai-agent-draw-io-front/src/utils/workspace-identity.ts`
- Create: `ai-agent-draw-io-front/tests/workspace-identity.test.mjs`
- Modify: `ai-agent-draw-io-front/src/app/page.tsx`
- Modify: `ai-agent-draw-io-front/src/app/drawio/page.tsx`

- [ ] Write failing tests for login-cookie identity, anonymous id reuse, anonymous id creation, and non-blank fallback.
- [ ] Implement `resolveWorkspaceIdentity` and `getWorkspaceIdentity`.
- [ ] Update `/` to route to `/drawio` without requiring the fake login page.
- [ ] Update Draw.io page initialization to use `getWorkspaceIdentity()` instead of redirecting anonymous users to `/login`.
- [ ] Run frontend tests, TypeScript, lint, and commit.

### Task 2: Backend Diagram List And Restore API

**Files:**
- Create: `ai-agent-draw-io/ai-agent-draw-io-api/src/main/java/org/zipp/ai/api/dto/DiagramSummaryResponseDTO.java`
- Create: `ai-agent-draw-io/ai-agent-draw-io-api/src/main/java/org/zipp/ai/api/dto/DiagramCanvasStateResponseDTO.java`
- Modify: `ai-agent-draw-io/ai-agent-draw-io-domain/src/main/java/org/zipp/ai/domain/agent/service/ICanvasStateStore.java`
- Modify: `ai-agent-draw-io/ai-agent-draw-io-infrastructure/src/main/java/org/zipp/ai/infrastructure/adapter/repository/CanvasStateRepository.java`
- Modify: `ai-agent-draw-io/ai-agent-draw-io-infrastructure/src/main/java/org/zipp/ai/infrastructure/dao/ICanvasStateMapper.java`
- Modify: `ai-agent-draw-io/ai-agent-draw-io-app/src/main/resources/mybatis/mapper/canvas_state_mapper.xml`
- Modify: `ai-agent-draw-io/ai-agent-draw-io-trigger/src/main/java/org/zipp/ai/trigger/http/AgentServiceController.java`
- Modify: `ai-agent-draw-io/ai-agent-draw-io-app/src/test/java/org/zipp/ai/test/infrastructure/CanvasStateRepositoryTest.java`

- [ ] Write failing repository tests for listing diagrams by owner and returning latest canvas detail.
- [ ] Add mapper query methods and MyBatis SQL over existing diagram tables.
- [ ] Add repository/domain methods with blank-owner guards.
- [ ] Add controller endpoints: `GET /api/v1/diagrams?userId=...` and `GET /api/v1/diagrams/{diagramId}?userId=...`.
- [ ] Run focused backend tests, then commit.

### Task 3: Home Page Diagram Entry And Draw.io Restore

**Files:**
- Modify: `ai-agent-draw-io-front/src/types/api.ts`
- Modify: `ai-agent-draw-io-front/src/api/agent.ts`
- Create: `ai-agent-draw-io-front/src/app/drawio/diagram-restore.ts`
- Create: `ai-agent-draw-io-front/tests/diagram-restore.test.mjs`
- Modify: `ai-agent-draw-io-front/src/app/page.tsx`
- Modify: `ai-agent-draw-io-front/src/app/drawio/page.tsx`

- [ ] Write failing tests for converting a restored diagram response into local session metadata.
- [ ] Add frontend API types and calls for diagram list/detail.
- [ ] Replace the root redirect page with a minimal diagram dashboard: load identity, load diagrams, open existing diagram, start a new diagram.
- [ ] Update Draw.io page to read `?diagramId=...`, fetch latest canvas state, and restore it as the active session.
- [ ] Run frontend tests, TypeScript, lint, and commit.

### Task 4: Final Verification

- [ ] Run `node --experimental-strip-types --test --test-reporter=dot tests/*.test.mjs` in `ai-agent-draw-io-front`.
- [ ] Run `npx tsc --noEmit` in `ai-agent-draw-io-front`.
- [ ] Run `npm run lint -- --quiet` in `ai-agent-draw-io-front`.
- [ ] Run focused Maven tests for repository/controller-safe backend behavior.
- [ ] Run `git diff --check` and confirm the worktree is clean after commits.
