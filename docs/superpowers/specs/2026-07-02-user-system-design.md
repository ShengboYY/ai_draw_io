# User System Design

## Context

The product currently uses a browser-local anonymous workspace identity. The frontend creates an `anon_<uuid>` owner id and sends it as `X-Workspace-Id`; the backend only accepts that anonymous capability id as the current owner. Diagrams, canvas state, conversation messages, and private skills are already shaped around an owner key such as `user_id` or `owner_id`.

The next phase adds a real user system without breaking the anonymous diagram-first flow. Security is a primary constraint because the system will handle passwords, email tokens, user-owned model API keys, quotas, and system usage telemetry.

## Goals

- Keep anonymous demo usage available with a strict quota.
- Add personal user accounts with email and password.
- Require email verification before login.
- Use secure server-side authentication with Spring Security.
- Let verified users save their own model API keys securely.
- Track platform and user-key usage for quota, cost, and future agent harness engineering.
- Add minimal admin controls for operations and abuse response.
- Preserve the current diagram ownership model while adding migration from anonymous workspaces to accounts.

## Non-Goals

- Organization or team accounts.
- Shared diagram links.
- OAuth login.
- Billing or paid plans.
- Full admin back office.
- Storing full prompts, model responses, system prompts, or Draw.io XML as normal telemetry.

## Key Decisions

- Anonymous users get 5 total platform AI requests for demo use.
- Logged-in users get 20 platform AI requests per day.
- User-provided API keys require login and verified email.
- User-provided API key calls do not consume platform free quota.
- User API keys are encrypted server-side before storage.
- AWS KMS is deferred until AWS deployment, but the crypto interface and schema must support it.
- Email verification is required before login.
- Verification and password-reset links expire after 30 minutes.
- Sessions last 7 days.
- Anonymous workspace import is explicit after login and soft-deletes the anonymous original.
- Usage metadata is retained for 1 year, daily summaries for 3 years.
- Debug trace content is disabled by default and retained for 7 days when explicitly enabled.

## Identity Model

There are two owner types:

- Anonymous workspace: `anon_<uuid>`, stored in the browser and sent with `X-Workspace-Id`.
- Authenticated user: stable server-generated user id, for example `usr_<uuid>`, resolved from the Spring Security session.

The backend should centralize owner resolution in one module. Callers should not parse cookies, headers, or account status directly.

Recommended interface:

```java
ResolvedOwner resolveOwner(HttpServletRequest request);
```

`ResolvedOwner` should expose:

- `ownerId`
- `ownerType`: `ANONYMOUS` or `USER`
- `authenticated`
- `emailVerified`
- `accountStatus`

For authenticated requests, the session user id wins. For anonymous requests, the backend may use `X-Workspace-Id` only when it matches the existing anonymous id pattern.

## Authentication

Use Spring Security as the authentication foundation.

Requirements:

- Store passwords as BCrypt or Argon2id hashes.
- Never store plain-text passwords.
- Use server-side sessions with `HttpOnly`, `Secure` in production, and `SameSite` cookies.
- Session duration is 7 days.
- Do not store JWTs in `localStorage`.
- Configure CORS with explicit frontend origins. Do not use wildcard CORS for credentialed authenticated endpoints.
- Enable CSRF protection for cookie-authenticated state-changing endpoints.

Local development may run over HTTP with relaxed `Secure` cookie behavior. Production must require HTTPS.

## Account Lifecycle

### Registration

1. User submits email and password.
2. Backend normalizes email to lowercase and trims whitespace.
3. Backend creates a user in `PENDING_VERIFICATION`.
4. Backend creates a one-time email verification token.
5. Backend sends a verification email.
6. Response does not reveal sensitive internal state beyond a successful registration flow.

### Email Verification

1. User opens verification link.
2. Backend hashes the submitted token and looks up an unused `EMAIL_VERIFY` token.
3. Token must be unexpired and unused.
4. User status becomes `ACTIVE`.
5. Token is marked used.

Unverified users cannot log in.

### Login

1. User submits email and password.
2. Backend enforces login rate limits.
3. Backend validates password and requires `ACTIVE` account status.
4. Spring Security creates a 7-day server-side session.
5. Frontend may prompt for anonymous workspace import.

Login failures should use a generic message for invalid email or password. If the account exists but is unverified, show a verify-email prompt with a resend option.

### Password Reset

1. User submits email.
2. Response is always generic: if the email exists, a reset link has been sent.
3. Reset tokens expire after 30 minutes and are stored only as hashes.
4. Token is one-time use.
5. Successful reset updates the password hash and invalidates old sessions.

## Email Sending

Introduce a small `EmailSender` interface:

```java
void sendVerificationEmail(String email, String verificationUrl);
void sendPasswordResetEmail(String email, String resetUrl);
```

Adapters:

- `ConsoleEmailSender` for local development and tests.
- `SesEmailSender` for AWS production.

Email sending must share the general rate-limit mechanism.

## Rate Limits And Quotas

Use backend enforcement. Frontend quota display is advisory only.

### Anonymous AI Demo

- Limit: 5 total platform AI requests per anonymous workspace.
- Applies to `chat` and `chat_stream`.
- Does not reset daily.
- Exceeding the limit should return a typed quota error that the frontend can render as an upgrade/login prompt.

### Logged-In Platform Free Usage

- Limit: 20 platform AI requests per verified user per day.
- Applies only when the request uses the platform model key.
- Resets by UTC day unless product requirements later choose account-local time zones.

### User API Key Usage

- Requires authenticated, verified user.
- Does not consume platform free quota.
- Still requires abuse protection, for example per-minute request limits, because the backend is still acting as a model proxy.

### Email And Auth Abuse Protection

- Same email: verification email at most once per 60 seconds.
- Same email: verification email at most 5 times per day.
- Same IP: registration or verification-send requests at most 20 per hour and 100 per day.
- Same email: password reset email at most once per 60 seconds and 5 times per day.
- Same IP: password reset requests at most 20 per hour.
- Same email: after 5 consecutive login failures, lock login attempts for 15 minutes.
- Same IP: login attempts at most 30 per hour.
- Successful login clears that email's consecutive failure count.

Recommended module:

```java
UsageLimitDecision checkAndConsume(UsageLimitCommand command);
```

This module should own windowing, counters, and typed denial reasons.

## User API Key Storage

The current frontend stores custom model configuration in `localStorage` and sends `customApiKey` on each chat request. The secure account system should replace that flow.

New behavior:

- Users add model credentials through authenticated account endpoints.
- Backend encrypts the API key before storing it.
- Frontend receives only masked key display, for example `sk-...abcd`.
- Chat requests reference a saved `modelCredentialId`.
- Backend resolves ownership, decrypts the key just in time, and passes it into the existing custom model config path.
- Raw `customApiKey` in chat requests should be rejected for anonymous users and phased out for authenticated users.

### Crypto Design

Introduce a `CredentialCryptoService` interface:

```java
EncryptedCredential encrypt(String plaintext);
String decrypt(EncryptedCredential encrypted);
```

First adapter:

- `EnvAesGcmCredentialCryptoService`
- Reads a base64-encoded 32-byte master key from an environment variable.
- Uses AES-GCM with a fresh nonce per encryption.

Future AWS adapter:

- `AwsKmsCredentialCryptoService`
- Uses AWS KMS to protect or generate data keys.

Database rows should record `encryption_provider` and `key_id` so credentials can be migrated from environment-key encryption to AWS KMS later.

## Usage Observability

Usage telemetry is part of the first version because quotas, cost visibility, admin operations, and future agent harness engineering all need it.

### Model

A single user request can trigger several model calls. The telemetry model should reflect that:

- `agent_run`: one user-visible request.
- `agent_run_step`: a logical step inside the run, such as intent routing, drawing, review, repair, or direct answer.
- `llm_call_usage`: one actual model call.
- `tool_call_usage`: one tool call, such as canvas mutation.
- `usage_daily_summary`: aggregated counters for dashboard queries.

### Data Captured By Default

Capture metadata only:

- owner id and owner type
- user id when authenticated
- diagram id
- agent id
- run id
- step name
- provider
- model
- credential source: `PLATFORM` or `USER_KEY`
- model credential id when user-owned, never the key
- prompt tokens
- completion tokens
- total tokens
- latency
- status
- error code or normalized error class
- created time

Do not store full user prompts, model responses, system prompts, API keys, or Draw.io XML by default.

### Streaming Token Counts

Streaming currently records success without token counts. The implementation should inspect provider metadata in streamed responses and update the final `llm_call_usage` row when usage metadata becomes available. If a provider does not return token counts, store token fields as `NULL`, not `0`, so unknown usage is not confused with zero usage.

### Debug Trace

Debug trace is optional and off by default.

Rules:

- Admin-only.
- May be enabled by user, run, or time window.
- May capture full prompt, response, selected context, and XML when required for diagnosis.
- Default retention is 7 days.
- Admin can extend retention for a specific run.
- Retention extension must write an audit log.
- Expired debug trace content is deleted automatically.
- Metadata remains after trace content is deleted.

## Admin Capabilities

First version should include minimal admin capabilities, not a full back office:

- View user list.
- Disable a user.
- View usage by user, model, provider, and credential source.
- View global request, token, latency, and failure dashboards.
- View audit logs.
- Never view raw API keys.
- Display only masked model credentials.

Admin actions must write audit logs.

## User-Facing Usage

Regular users can view only their own usage:

- Remaining daily platform free quota.
- Platform requests used today.
- User-key request count and approximate token usage.
- Saved model credential list with masked keys.

Users must not see global system usage, other users, or admin-only diagnostics.

## Anonymous Workspace Import

After a verified user logs in, the frontend checks whether the browser has an anonymous workspace id. If there are anonymous diagrams, the frontend asks:

`Import diagrams from this browser?`

Only after explicit confirmation should it call:

```http
POST /api/v1/account/import-anonymous-workspace
```

Backend requirements:

- Current user must be authenticated.
- Anonymous workspace id must match the `anon_<uuid>` pattern.
- Copy diagrams, canvas state, and conversation messages to the authenticated user.
- Generate new diagram ids for imported copies to avoid primary-key collisions.
- Soft-delete the anonymous originals after successful import.
- Do not import private skills unless anonymous private skill creation is later allowed.

## Data Retention And Deletion

Retention:

- Debug trace content: 7 days by default.
- Usage metadata detail: 1 year.
- Daily usage summaries: 3 years.

Account deletion:

- Delete or soft-delete profile, diagrams, conversations, and model credentials.
- Immediately delete debug trace content.
- Anonymize usage detail user ids while preserving operational statistics.
- Keep daily summaries in anonymized form.
- Keep audit logs with user identifiers redacted or replaced by irreversible hashes.

## Proposed Tables

### `app_user`

- `id`
- `email`
- `email_normalized`
- `password_hash`
- `status`: `PENDING_VERIFICATION`, `ACTIVE`, `DISABLED`, `DELETED`
- `session_version`
- `created_at`
- `updated_at`
- `verified_at`
- `deleted_at`

### `account_token`

- `id`
- `user_id`
- `purpose`: `EMAIL_VERIFY`, `PASSWORD_RESET`
- `token_hash`
- `expires_at`
- `used_at`
- `created_at`

### `usage_counter`

- `id`
- `subject_type`: `ANONYMOUS`, `USER`, `EMAIL`, `IP`
- `subject_id`
- `action`
- `window_type`: `TOTAL`, `MINUTE`, `HOUR`, `DAY`
- `window_start`
- `count`
- `created_at`
- `updated_at`

### `model_credential`

- `id`
- `user_id`
- `display_name`
- `provider`
- `base_url`
- `completions_path`
- `model`
- `encrypted_api_key`
- `nonce`
- `encryption_provider`
- `key_id`
- `key_last4`
- `enabled`
- `created_at`
- `updated_at`
- `deleted_at`

### `agent_run`

- `id`
- `owner_id`
- `owner_type`
- `user_id`
- `diagram_id`
- `agent_id`
- `request_kind`
- `status`
- `started_at`
- `completed_at`
- `error_code`

### `agent_run_step`

- `id`
- `run_id`
- `step_name`
- `status`
- `started_at`
- `completed_at`
- `error_code`

### `llm_call_usage`

- `id`
- `run_id`
- `step_id`
- `owner_id`
- `owner_type`
- `user_id`
- `provider`
- `model`
- `credential_source`
- `model_credential_id`
- `prompt_tokens`
- `completion_tokens`
- `total_tokens`
- `latency_ms`
- `status`
- `error_code`
- `created_at`

### `tool_call_usage`

- `id`
- `run_id`
- `step_id`
- `tool_name`
- `latency_ms`
- `status`
- `error_code`
- `created_at`

### `usage_daily_summary`

- `id`
- `summary_date`
- `owner_type`
- `user_id`
- `provider`
- `model`
- `credential_source`
- `request_count`
- `success_count`
- `failure_count`
- `prompt_tokens`
- `completion_tokens`
- `total_tokens`
- `latency_ms_sum`
- `latency_ms_p95`
- `created_at`
- `updated_at`

### `audit_log`

- `id`
- `actor_user_id`
- `actor_role`
- `action`
- `target_type`
- `target_id`
- `metadata_json`
- `created_at`

## Backend Module Plan

Recommended modules:

- `AccountService`: registration, verification, login support, password reset.
- `CurrentOwnerResolver`: converts session/header state into a resolved owner.
- `UsageLimitService`: quota and rate-limit checks.
- `ModelCredentialService`: credential CRUD and ownership checks.
- `CredentialCryptoService`: encryption and decryption seam.
- `AgentRunTelemetryService`: run, step, model-call, and tool-call recording.
- `EmailSender`: email delivery seam.
- `AdminService`: minimal admin operations.

The quota checks should run before expensive model work. Telemetry should record both successful and failed runs.

## API Sketch

Public:

- `POST /api/v1/auth/register`
- `GET /api/v1/auth/verify-email?token=...`
- `POST /api/v1/auth/resend-verification`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/logout`
- `POST /api/v1/auth/password-reset/request`
- `POST /api/v1/auth/password-reset/confirm`

Authenticated:

- `GET /api/v1/account/me`
- `POST /api/v1/account/import-anonymous-workspace`
- `GET /api/v1/account/usage`
- `GET /api/v1/account/model-credentials`
- `POST /api/v1/account/model-credentials`
- `PATCH /api/v1/account/model-credentials/{id}`
- `DELETE /api/v1/account/model-credentials/{id}`

Admin:

- `GET /api/v1/admin/users`
- `PATCH /api/v1/admin/users/{id}/disable`
- `GET /api/v1/admin/usage`
- `GET /api/v1/admin/usage/runs/{runId}`
- `POST /api/v1/admin/debug-trace`
- `POST /api/v1/admin/debug-trace/{runId}/extend-retention`

Existing chat endpoints should keep accepting anonymous workspace requests, but authenticated requests should resolve `userId` from the session rather than trusting request body values.

## Frontend Plan

Update identity handling:

- Keep anonymous identity for demo flow.
- Add auth state from `/account/me`.
- When authenticated, use session identity rather than anonymous owner for account-backed data.

Account UI:

- Register.
- Verify-email result page.
- Login.
- Forgot/reset password.
- Account usage page.
- Model credentials page with masked key display.

Draw.io UI:

- Anonymous users see remaining demo count.
- Logged-in users see daily platform quota.
- Model picker uses saved credential ids instead of raw API keys.
- If anonymous workspace data exists after login, show import prompt.

Admin UI:

- Minimal dashboard for usage, users, failures, and disable actions.
- No raw API key display.

## Security Notes

- Do not log passwords, tokens, raw API keys, full prompts, full responses, or full canvas XML.
- Store email verification and password reset tokens as hashes only.
- Normalize email before uniqueness checks.
- Use typed quota errors so the frontend can distinguish quota exhaustion from ordinary failures.
- Ensure credential ownership checks happen before decrypting API keys.
- Keep decrypted API keys in memory only for the current model call.
- Use explicit CORS origins with credentials.
- Prefer same-site frontend/backend deployment in production to simplify CSRF and cookie behavior.

## Testing Strategy

Backend tests:

- Registration stores password hash and pending user.
- Verification activates a user and rejects expired or reused tokens.
- Unverified users cannot log in.
- Login creates a secure session.
- Login failure limits lock the account/email window.
- Verification and reset email rate limits work.
- Anonymous quota allows 5 platform AI requests and rejects the 6th.
- Logged-in platform quota allows 20 daily requests and rejects the 21st.
- User-key requests require authentication and do not consume platform quota.
- Model credentials are encrypted at rest and returned masked.
- Credential ownership is enforced before decrypt.
- Usage telemetry records run, step, LLM call, and failures.
- Account deletion anonymizes usage and deletes debug trace.

Frontend tests:

- Anonymous quota display.
- Registration and verification flows.
- Login blocked until verification.
- Model credential form never displays raw saved key.
- Chat request uses `modelCredentialId` instead of raw `customApiKey`.
- Anonymous import prompt appears only after login and only with an anonymous workspace.

Manual verification:

- Local email links print through `ConsoleEmailSender`.
- Production-like cookie settings work behind HTTPS.
- Debug trace remains disabled unless explicitly enabled.

## Rollout Order

1. Add database migrations and core account/credential/usage tables.
2. Add Spring Security session authentication and owner resolution.
3. Add registration, verification, login, logout, password reset, and email sender seam.
4. Add rate limits and AI quotas.
5. Replace localStorage API key persistence with encrypted server-side model credentials.
6. Add authenticated chat credential resolution.
7. Add usage telemetry and daily aggregation.
8. Add anonymous workspace import.
9. Add user usage page and minimal admin dashboard.
10. Add debug trace controls and retention cleanup.

## Risks

- Cookie auth plus cross-origin frontend/backend can create CSRF and CORS pitfalls. Prefer same-site production routing.
- Streaming token counts may be unavailable for some providers. Store `NULL` for unknown counts and do not use those rows as exact cost data.
- Environment-key encryption requires careful secret management. The schema must preserve enough metadata to migrate to AWS KMS.
- API key proxying can be abused even when users bring their own key. Keep per-minute protection for user-key calls.
- Usage telemetry must avoid content capture by default to preserve user trust.
