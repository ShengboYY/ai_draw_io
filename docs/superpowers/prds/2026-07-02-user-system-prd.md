# User System PRD

## Problem Statement

Users can currently create and restore diagrams through an anonymous browser workspace, but the product does not yet have a secure account system. That creates several product and operational problems:

- A user's diagrams and conversations are tied to one browser instead of a durable account.
- Anonymous usage has no hard platform-cost limit.
- The current custom model flow relies on browser-local API key storage, which is not safe enough for user-owned credentials.
- There is no verified identity for saving model credentials, importing anonymous diagrams, or showing personal usage.
- The system has no product-level view of token usage, request counts, model cost drivers, latency, or failure patterns.
- Admins lack basic tools to disable abusive users or inspect aggregate usage.

The product needs a security-first personal account system that preserves the existing anonymous demo experience while creating a reliable foundation for quotas, encrypted model credentials, usage observability, and future agent harness engineering.

## Solution

Add a personal user account system alongside the existing anonymous workspace flow.

Anonymous visitors can still try the product, but they are limited to 5 total platform AI requests. Users can register with email and password, verify their email, and then log in using a secure server-side session. Verified users receive 20 free platform AI requests per day and can save their own model API keys. User API keys are encrypted server-side and shown only as masked values in the UI.

The backend treats account identity as a separate DDD bounded context, not as part of the existing agent domain. The account context owns users, verification, sessions, credential ownership, and account security rules. The agent context continues to own diagram generation and canvas-related behavior. The contexts collaborate through small interfaces for owner resolution, credential resolution, quota decisions, and telemetry recording.

The system records usage metadata for every user-visible agent run, logical run step, model call, and tool call. Normal telemetry never stores full prompts, full model responses, full system prompts, raw API keys, or full Draw.io XML. Admin-only debug trace can capture sensitive content only when explicitly enabled and expires by default after 7 days.

## User Stories

1. As an anonymous visitor, I want to try the diagram agent without creating an account, so that I can evaluate the product quickly.
2. As an anonymous visitor, I want to see when my demo quota is nearly exhausted, so that I understand why I should sign up.
3. As an anonymous visitor, I want the product to stop platform AI requests after my 5 demo uses, so that the product owner can control cost.
4. As an anonymous visitor, I want my browser-local diagrams to remain available before I create an account, so that I do not lose work during the demo.
5. As an anonymous visitor, I want to register with an email and password, so that I can keep my diagrams across sessions.
6. As a new registrant, I want to receive an email verification link, so that the service can verify I control the email address.
7. As a new registrant, I want the verification link to expire after a reasonable time, so that exposed links do not remain valid indefinitely.
8. As a new registrant, I want to request a new verification email if the first one expires, so that I can still activate my account.
9. As a new registrant, I want the app to block login until my email is verified, so that account benefits are only available to verified users.
10. As a verified user, I want to log in with a secure browser session, so that I do not have to re-enter my credentials on every visit.
11. As a verified user, I want my session to last 7 days, so that the product balances convenience and security.
12. As a verified user, I want to log out, so that I can end access on a shared device.
13. As a verified user, I want to reset my password by email, so that I can regain access if I forget it.
14. As a verified user, I want password-reset links to be one-time use, so that old links cannot be reused.
15. As a verified user, I want old sessions invalidated after a password reset, so that stolen sessions are less useful.
16. As a verified user, I want my anonymous diagrams from this browser to be imported only after I confirm, so that diagrams from a shared device are not silently attached to my account.
17. As a verified user, I want imported anonymous diagrams to appear in my account, so that my demo work becomes durable.
18. As a verified user, I want imported anonymous originals to stop appearing in the anonymous workspace, so that I do not see duplicates.
19. As a verified user, I want 20 free platform AI requests per day, so that I can keep using the product without immediately adding my own model key.
20. As a verified user, I want my remaining daily free quota to be visible, so that I know how many platform requests are left.
21. As a verified user, I want a clear quota-exhausted message, so that I know whether to wait, use my own key, or change behavior.
22. As a verified user, I want to save my own model API key, so that I can use my own provider quota instead of the platform free quota.
23. As a verified user, I want saved API keys encrypted server-side, so that they are not stored in browser local storage or in database plaintext.
24. As a verified user, I want saved API keys displayed only as masked values, so that people looking at my screen cannot copy them.
25. As a verified user, I want to disable or delete a saved model credential, so that I can stop the app from using a key.
26. As a verified user, I want to choose a saved model credential for a diagram request, so that the backend can call the model provider using my key.
27. As a verified user, I want user-key model calls not to consume my free platform quota, so that I am not penalized for bringing my own key.
28. As a verified user, I want the app to protect my model key even from admins in the UI, so that admins cannot view or copy the raw secret.
29. As a verified user, I want to see my own usage summary, so that I understand my platform quota and user-key activity.
30. As a verified user, I want the system to avoid storing my full prompt and model response by default, so that normal telemetry respects my privacy.
31. As a verified user, I want account deletion to remove or anonymize my personal data, so that I can leave the product without leaving identifiable history behind.
32. As an admin, I want to see user accounts, so that I can support users and respond to abuse.
33. As an admin, I want to disable a user, so that I can stop abusive behavior.
34. As an admin, I want to see global token usage, request counts, success rates, failure rates, and latency, so that I can operate the system.
35. As an admin, I want to distinguish platform-key usage from user-key usage, so that I can separate platform cost from user-provided provider calls.
36. As an admin, I want usage grouped by model and provider, so that I can understand cost and reliability by provider.
37. As an admin, I want to inspect agent runs at the step and model-call level, so that I can prepare for agent harness engineering.
38. As an admin, I want debug trace disabled by default, so that sensitive content is not collected casually.
39. As an admin, I want to enable debug trace for a narrow user, run, or time window, so that I can diagnose hard failures.
40. As an admin, I want debug trace content to expire by default after 7 days, so that sensitive diagnostic data does not accumulate.
41. As an admin, I want an audit log for sensitive operations, so that admin actions are accountable.
42. As a product owner, I want email sending rate limits, so that attackers cannot use the product as an email spam tool.
43. As a product owner, I want login rate limits, so that attackers cannot brute-force passwords.
44. As a product owner, I want anonymous and free-user quotas enforced on the backend, so that frontend bypasses cannot create unexpected model cost.
45. As a developer, I want a DDD account domain separated from the agent domain, so that account security rules do not leak into diagram-generation code.
46. As a developer, I want a single owner-resolution seam, so that controllers and domain services do not duplicate header, session, and account-status rules.
47. As a developer, I want a single credential-crypto seam, so that environment-key encryption can later migrate to AWS KMS without rewriting callers.
48. As a developer, I want token counts recorded as unknown when a provider omits them, so that missing data is not confused with zero usage.
49. As a developer, I want usage telemetry to record failures as well as successes, so that the system can explain reliability problems.
50. As a developer, I want quota checks before expensive model calls, so that denied requests do not consume unnecessary model cost.

## Implementation Decisions

- The user system is a separate DDD bounded context, referred to as the account or identity domain. It owns users, email verification, password reset, account status, login eligibility, saved model credentials, and account security rules.
- The existing agent domain remains responsible for diagram generation, intent routing, canvas state, skills, reviews, and agent workflow behavior.
- The account domain and agent domain communicate through small interfaces: owner resolution, usage-limit decisions, model-credential resolution, and telemetry recording.
- Authenticated owner identity is resolved from the Spring Security session. Anonymous owner identity is resolved from the existing anonymous workspace header only when it matches the existing anonymous id format.
- Authenticated user ids must be generated by the server and should not reuse email addresses or anonymous workspace ids as primary identifiers.
- Spring Security is the authentication foundation.
- Browser authentication uses server-side session cookies. JWTs must not be stored in browser local storage.
- Passwords are stored only as password hashes using BCrypt or Argon2id.
- Email verification is required before login.
- Email verification and password-reset tokens expire after 30 minutes, are one-time use, and are stored only as token hashes.
- Sessions last 7 days.
- Password reset invalidates old user sessions.
- Email sending is abstracted behind an email sender interface with local console and production email-provider adapters.
- The AWS production adapter is expected to use Amazon SES.
- Anonymous platform AI usage is limited to 5 total requests per anonymous workspace.
- Verified platform AI usage is limited to 20 requests per user per UTC day.
- User-provided API key usage requires a verified authenticated user.
- User-provided API key usage does not consume platform free quota.
- User-provided API key usage still has abuse-oriented request-rate protection because the backend remains a proxy path.
- Email verification, password reset, registration, login, anonymous AI usage, verified platform AI usage, and user-key proxy usage all use one rate-limit or usage-counter mechanism.
- Same-email verification sends are limited to once per 60 seconds and 5 per day.
- Same-IP registration or verification-send requests are limited to 20 per hour and 100 per day.
- Same-email password reset sends are limited to once per 60 seconds and 5 per day.
- Same-IP password reset requests are limited to 20 per hour.
- Same-email login failures lock login attempts for 15 minutes after 5 consecutive failures.
- Same-IP login attempts are limited to 30 per hour.
- User model credentials are stored server-side and encrypted before persistence.
- Frontend never receives the raw saved API key after creation. It receives only masked display data.
- Chat requests should reference a saved model credential id instead of sending a raw API key.
- Raw custom API keys in chat requests are rejected for anonymous users and phased out for authenticated users.
- Credential encryption is behind a crypto service interface.
- First implementation uses AES-GCM with a base64-encoded 32-byte environment master key and fresh nonce per encryption.
- Credential rows store encryption provider and key id so that AWS KMS can be introduced later.
- AWS KMS integration is deferred until AWS deployment, but the schema and interface must support migration.
- Anonymous workspace import is an explicit login-time action, not silent migration.
- Anonymous import copies diagrams, canvas state, and conversation messages into the authenticated user's ownership.
- Imported anonymous originals are soft-deleted after successful import.
- Private skills are not imported unless anonymous private skill creation is later introduced.
- Usage observability is part of the first version, not an afterthought.
- A user-visible agent request is modeled separately from actual model calls because one request may include intent routing, drawing, review, and repair calls.
- Usage telemetry contains run, run-step, LLM-call, tool-call, and daily-summary records.
- Normal telemetry stores metadata only. It must not store full user prompts, full model responses, full system prompts, full Draw.io XML, or raw API keys.
- Streaming model calls store token usage when provider metadata is available.
- Unknown token counts are stored as null or an equivalent unknown state, not as zero.
- Debug trace is admin-only, disabled by default, and can be enabled only for narrow scopes such as a user, run, or time window.
- Debug trace may store sensitive content only when explicitly enabled.
- Debug trace content expires after 7 days by default.
- Admins may extend debug-trace retention for a specific run, and that operation writes an audit log.
- Usage metadata detail is retained for 1 year.
- Daily usage summaries are retained for 3 years.
- Account deletion deletes or soft-deletes profile, diagrams, conversations, and model credentials.
- Account deletion immediately deletes debug trace content.
- Account deletion anonymizes usage detail while preserving operational statistics.
- Admin capability is intentionally minimal in the first version: user list, disable user, global usage dashboards, run details, debug trace controls, and audit log viewing.
- Admins can never view raw API keys.
- Regular users can only view their own quota and usage data.
- Organization accounts, shared diagram links, OAuth login, paid billing, and full admin back-office workflows are excluded.

## Testing Decisions

- Tests should exercise external behavior at the highest useful seam. They should not lock onto private helper methods or implementation details.
- Authentication behavior should be tested through HTTP-level flows with Spring Security session behavior where possible.
- Account domain rules should be tested through the account service interface, including registration, verification, login eligibility, password reset, account status changes, and session invalidation.
- Owner resolution should be tested as a small dedicated seam because every protected or anonymous endpoint depends on it.
- Rate-limit and quota behavior should be tested through the usage-limit service and through representative API flows that consume quotas.
- Model credential behavior should be tested through service and HTTP seams, including create, list masked, disable, delete, ownership enforcement, and decrypt-on-use.
- Credential encryption should be tested through the crypto interface. Tests must prove stored values are not plaintext and can be decrypted only through the service.
- Telemetry should be tested through the agent-run telemetry service and representative chat paths, including success, failure, platform-key usage, user-key usage, and missing token metadata.
- Admin behavior should be tested through admin HTTP endpoints with authenticated admin and non-admin users.
- Frontend tests should focus on user-visible flows and API payload shape: registration, verification result, login, quota display, model credential management, model selection, anonymous import prompt, and chat using model credential ids.
- Existing backend repository and mapper tests for diagram and canvas ownership are good prior art for persistence-level checks.
- Existing frontend utility tests are good prior art for testing identity, restore, title, and request payload behavior.
- Existing controller and trigger service tests are good prior art for testing API behavior and chat request handling.
- Security tests should explicitly cover negative paths: unverified login, anonymous custom key usage, cross-user credential access, quota exhaustion, repeated token use, expired tokens, and disabled users.
- Tests should assert that normal telemetry does not persist sensitive content.

## Out of Scope

- Team, organization, or workspace-member accounts.
- Shared diagram links or public diagram access.
- OAuth login.
- Paid plans, billing, invoices, or subscriptions.
- Long-term server-side debug-content storage.
- Admin ability to view raw user API keys.
- Full admin back office.
- User-managed roles and permissions beyond normal user and admin.
- Anonymous private skill creation.
- Immediate AWS KMS implementation before AWS deployment.
- Exact cost calculation when provider token metadata is unavailable.

## Further Notes

- The implementation should respect the existing DDD layering. New account behavior belongs in a distinct domain context with infrastructure adapters and trigger/controllers around it.
- The account context is security-sensitive. Its interfaces should be deliberately small and should avoid leaking password, token, or encryption details into the agent context.
- The existing anonymous workspace design remains useful and should be preserved as the no-account demo path.
- Existing chat endpoints can continue to support anonymous requests, but authenticated requests should stop trusting body-provided user ids.
- The migration from browser-local custom API key storage to encrypted server-side model credentials is a security fix as much as a product feature.
- The usage model should prepare the project for future agent harness engineering by preserving run, step, model-call, and tool-call structure from the beginning.
