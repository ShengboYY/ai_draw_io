# Anonymous Workspace Design

## Context

The project does not need a full account system yet, but the canvas state work already depends on a stable `userId + diagramId` owner key. The current frontend redirects users without a login cookie to `/login`, while the backend already stores canvas state by `userId` and `diagramId`.

This stage introduces an anonymous workspace identity so the product can move toward a diagram-first home page without building registration, password auth, or permission management.

## Assumptions

- A browser-local anonymous identity is enough for the current product stage.
- The anonymous identity only scopes local diagrams and backend canvas state; it is not an authentication boundary.
- A future real account system can replace the identity source without changing the canvas state storage key shape.
- Anonymous data migration into a real account can be handled later as a separate feature.

## Options Considered

### Recommended: Anonymous Workspace ID

Generate one stable anonymous user id in the browser and reuse it as the current `userId` when no login cookie exists.

Pros:
- Smallest change that unblocks diagram ownership.
- Keeps backend contracts unchanged.
- Creates a clean replacement point for future logged-in users.

Cons:
- Data is tied to the browser storage until an account migration exists.
- It is not secure authorization.

### Keep Login Page Required

Continue requiring the current fake login page before entering Draw.io.

Pros:
- No immediate code change.

Cons:
- Blocks the planned diagram-first home experience.
- Keeps a misleading "login" concept before there is a real user system.

### Build Real User System Now

Add registration, login, sessions, and user-owned diagrams now.

Pros:
- Best long-term identity model.

Cons:
- Too large for the current refactor.
- Distracts from the canvas/session chain that needs to stabilize first.

## Chosen Design

Add a frontend identity adapter that returns the active workspace owner:

- If a login cookie exists, return its `user` value and mark the identity as `authenticated`.
- Otherwise, read or create an anonymous id in localStorage and mark it as `anonymous`.
- Use this owner id everywhere the frontend currently needs `currentUser`.

The anonymous id format should be explicit, for example `anon_<random>`, so backend logs and future migration logic can distinguish anonymous owners from real account ids.

The adapter is the only place that knows how identity is resolved. Draw.io page code should consume the resolved owner id, not directly decide whether login exists.

## Frontend Flow

1. App startup resolves the workspace identity.
2. `/` can route directly to `/drawio` when anonymous mode is allowed.
3. `/drawio` uses the resolved owner id to load skills, create backend sessions, and send chat stream requests.
4. Existing local sessions remain in localStorage for now.
5. Future home page work can list diagrams for the resolved owner id.

## Backend Flow

No backend identity system is added in this stage.

The backend continues to receive `userId` from request DTOs and uses it in:

- chat/session creation
- canvas state lookup
- canvas state save/version checks
- tool calls that can load canvas state from `userId + diagramId`

## Error Handling

- If localStorage is unavailable, fall back to an in-memory anonymous id for the current page session.
- If anonymous identity generation fails, show the existing frontend error path instead of creating backend requests with a blank `userId`.
- Do not silently convert blank ids into shared global users.

## Testing

Add focused frontend tests for:

- returning login-cookie identity when present
- creating and reusing anonymous identity when no login cookie exists
- never returning a blank owner id

Existing frontend checks should still pass:

- node test suite
- TypeScript compile
- eslint

## Out Of Scope

- real registration/login
- server-side authentication
- permissions or sharing
- anonymous-to-account migration
- diagram list API and home page UI
- moving existing localStorage sessions into MySQL

## Next Implementation Step

Implement the frontend identity adapter first, then update the root redirect and Draw.io page to use it. This keeps the current API shape stable while making the next diagram-list phase possible.
