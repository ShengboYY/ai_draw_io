# User System Issue Drafts

Parent PRD: `docs/superpowers/prds/2026-07-02-user-system-prd.md`

These drafts are ready-for-agent issue bodies. Publish them in dependency order and replace draft blocked-by references with real issue numbers after creation.

## 1. Establish Account Bounded Context And Owner Resolution

User stories covered: 1, 4, 45, 46

### What to build

Create the account/identity bounded context skeleton and connect it to the existing anonymous workspace flow through a single owner-resolution seam. Anonymous users should continue to work exactly as they do now, while the backend gains a central way to distinguish anonymous owners from future authenticated users. Add a minimal account status endpoint so the frontend can render the current owner state without duplicating identity rules.

### Acceptance criteria

- [ ] A distinct account/identity domain exists and does not place account security logic inside the existing agent domain.
- [ ] A current-owner resolver returns anonymous owner details for valid anonymous workspace requests.
- [ ] Invalid or blank anonymous workspace ids are rejected consistently.
- [ ] A current-account/status endpoint returns anonymous state for anonymous visitors.
- [ ] The frontend can display anonymous/current-owner state from the new endpoint without breaking the existing diagram flow.
- [ ] Existing diagram list, restore, and chat behavior still work for anonymous workspaces.
- [ ] Tests cover valid anonymous owner resolution, invalid anonymous ids, and the account status endpoint.

### Blocked by

None - can start immediately.

## 2. Register And Verify Email Before Login

User stories covered: 5, 6, 7, 8, 9

### What to build

Add email/password registration with required email verification. A registered user begins as pending verification, receives a one-time verification link through the local email sender, and becomes active only after the verification token is used. The frontend should support registration, verification result, and resend-verification flows.

### Acceptance criteria

- [ ] Users can register with email and password.
- [ ] Email is normalized before uniqueness checks.
- [ ] Passwords are stored as hashes, never plaintext.
- [ ] New users are created as pending verification.
- [ ] Verification tokens are stored only as hashes and expire after 30 minutes.
- [ ] Local development sends verification links through a console email sender.
- [ ] Verification activates the user and prevents token reuse.
- [ ] Expired or invalid verification links fail safely.
- [ ] The frontend supports registration and verification result states.
- [ ] Tests cover registration, password hashing, token hashing, successful verification, expired token rejection, and reused token rejection.

### Blocked by

- Draft 1. Establish Account Bounded Context And Owner Resolution

## 3. Log In And Out With Secure Spring Security Sessions

User stories covered: 9, 10, 11, 12

### What to build

Add login and logout backed by Spring Security server-side sessions. Only active verified users can log in. Logged-in requests resolve the authenticated user from the session instead of trusting request-body owner ids. The frontend should support login, logout, and authenticated account status.

### Acceptance criteria

- [ ] Verified active users can log in with email and password.
- [ ] Pending, disabled, deleted, or unknown users cannot log in.
- [ ] Login creates a server-side session with a 7-day lifetime.
- [ ] Session cookies are HttpOnly and SameSite, with Secure enabled for production configuration.
- [ ] Logout invalidates the session.
- [ ] Authenticated account status returns the logged-in user state.
- [ ] Existing chat and diagram endpoints prefer session user identity for authenticated requests.
- [ ] The frontend supports login/logout and refreshes authenticated state.
- [ ] Tests cover successful login, unverified login rejection, logout, and session-based owner resolution.

### Blocked by

- Draft 2. Register And Verify Email Before Login

## 4. Reset Password By Verified Email Flow

User stories covered: 13, 14, 15

### What to build

Add password-reset request and confirm flows. Password-reset links are delivered by email, expire after 30 minutes, are one-time use, and are stored as token hashes. Resetting a password should invalidate old sessions. The frontend should support request-reset and set-new-password screens.

### Acceptance criteria

- [ ] Users can request a password reset with an email address.
- [ ] Password-reset request responses do not reveal whether the email exists.
- [ ] Password-reset tokens are stored only as hashes and expire after 30 minutes.
- [ ] A valid reset token allows setting a new password hash.
- [ ] Reset tokens are one-time use.
- [ ] Resetting a password invalidates old sessions.
- [ ] The frontend supports password-reset request and confirmation flows.
- [ ] Tests cover generic responses, successful reset, expired token rejection, reused token rejection, and old-session invalidation.

### Blocked by

- Draft 3. Log In And Out With Secure Spring Security Sessions

## 5. Protect Auth And Email Flows With Rate Limits

User stories covered: 42, 43

### What to build

Add a shared usage-counter/rate-limit mechanism for registration, verification email sends, password-reset email sends, login attempts, and repeated login failures. The goal is to prevent email abuse and brute-force login attempts while keeping responses safe and user-facing.

### Acceptance criteria

- [ ] Verification email sends are limited by email address to once per 60 seconds and 5 per day.
- [ ] Registration and verification-send attempts are limited by IP to 20 per hour and 100 per day.
- [ ] Password-reset email sends are limited by email address to once per 60 seconds and 5 per day.
- [ ] Password-reset attempts are limited by IP to 20 per hour.
- [ ] Login attempts are limited by IP to 30 per hour.
- [ ] Five consecutive login failures for an email lock further login attempts for 15 minutes.
- [ ] Successful login clears that email's consecutive failure count.
- [ ] Rate-limit denials return typed errors that the frontend can present clearly.
- [ ] Tests cover each configured limit and reset behavior.

### Blocked by

- Draft 2. Register And Verify Email Before Login
- Draft 3. Log In And Out With Secure Spring Security Sessions
- Draft 4. Reset Password By Verified Email Flow

## 6. Enforce Anonymous Demo AI Quota

User stories covered: 1, 2, 3, 44, 50

### What to build

Limit anonymous workspaces to 5 total platform AI requests. The quota must be enforced before expensive model work for both blocking and streaming chat paths. The frontend should show demo usage state and guide users to sign up or add their own key after quota exhaustion.

### Acceptance criteria

- [ ] Anonymous platform AI requests consume a total quota counter.
- [ ] The first 5 anonymous platform AI requests are allowed.
- [ ] The 6th anonymous platform AI request is rejected before model execution.
- [ ] Both blocking chat and streaming chat enforce the same anonymous quota.
- [ ] Quota exhaustion returns a typed error.
- [ ] The frontend displays remaining demo quota and a clear exhausted state.
- [ ] Tests cover quota consumption, rejection, and no model call after exhaustion.

### Blocked by

- Draft 1. Establish Account Bounded Context And Owner Resolution

## 7. Enforce Verified User Daily Platform Quota

User stories covered: 19, 20, 21, 44, 50

### What to build

Give verified users 20 platform-key AI requests per UTC day. Requests that use the platform key consume daily quota; requests that later use a user-owned key should not. The frontend should show the user's daily free quota and render quota-exhausted messages clearly.

### Acceptance criteria

- [ ] Verified users have a daily platform quota of 20 requests.
- [ ] Platform-key blocking chat and streaming chat consume daily quota before model execution.
- [ ] The 21st daily platform request is rejected before model execution.
- [ ] The account usage endpoint exposes today's platform usage and remaining quota.
- [ ] The frontend shows daily quota and quota-exhausted state for logged-in users.
- [ ] Anonymous quota behavior remains unchanged.
- [ ] Tests cover daily quota consumption, daily rejection, and account usage response.

### Blocked by

- Draft 3. Log In And Out With Secure Spring Security Sessions
- Draft 6. Enforce Anonymous Demo AI Quota

## 8. Manage Encrypted User Model Credentials

User stories covered: 22, 23, 24, 25, 28, 47

### What to build

Let verified users create, list, disable, and delete saved model credentials. API keys must be encrypted server-side before persistence, returned only as masked values, and never exposed in admin or user read responses. The encryption seam must support environment-key AES-GCM now and AWS KMS later.

### Acceptance criteria

- [ ] Verified users can create model credentials with provider, base URL, model, completion path, display name, and API key.
- [ ] API keys are encrypted before persistence and are not stored in plaintext.
- [ ] Credential rows record encryption provider, key id, nonce, and key last four characters.
- [ ] Credential list responses return masked key display only.
- [ ] Users can disable and delete their own model credentials.
- [ ] Users cannot access another user's credentials.
- [ ] Admin-facing reads never return raw API keys.
- [ ] Tests cover encryption, masking, ownership enforcement, disable, and delete behavior.

### Blocked by

- Draft 3. Log In And Out With Secure Spring Security Sessions

## 9. Use Saved Model Credentials For Chat

User stories covered: 26, 27, 44, 50

### What to build

Update chat to reference saved model credential ids instead of raw API keys. The backend should verify credential ownership, decrypt the API key only for the current model call, and route it through the existing custom model path. User-key requests should not consume platform quota, while anonymous or unauthorized raw-key usage should be rejected.

### Acceptance criteria

- [ ] Chat requests can reference a saved model credential id.
- [ ] The backend rejects model credential ids not owned by the authenticated user.
- [ ] The backend decrypts the saved key only after ownership is verified.
- [ ] User-key chat requests do not consume platform free quota.
- [ ] Anonymous chat requests with raw custom API keys are rejected.
- [ ] Authenticated raw custom API key chat usage is phased out or rejected according to the account credential flow.
- [ ] The frontend model picker sends credential ids rather than raw API keys.
- [ ] Tests cover successful user-key chat, cross-user credential rejection, anonymous raw-key rejection, and no platform quota consumption for user-key calls.

### Blocked by

- Draft 7. Enforce Verified User Daily Platform Quota
- Draft 8. Manage Encrypted User Model Credentials

## 10. Import Anonymous Workspace After Login

User stories covered: 16, 17, 18

### What to build

After a verified user logs in, offer an explicit import of diagrams from the current browser's anonymous workspace. Confirmed imports copy diagrams, canvas state, and conversation messages into the authenticated user's ownership and soft-delete the anonymous originals.

### Acceptance criteria

- [ ] The frontend detects an anonymous workspace after login and prompts before importing.
- [ ] Import requires an authenticated user.
- [ ] Import rejects invalid anonymous workspace ids.
- [ ] Confirmed import copies diagrams, canvas state, and conversation messages to the authenticated owner.
- [ ] Imported diagrams receive safe ids that avoid collisions.
- [ ] Anonymous originals are soft-deleted after successful import.
- [ ] The imported diagrams appear in the authenticated user's diagram list.
- [ ] Tests cover import success, invalid anonymous workspace rejection, unauthenticated rejection, id collision handling, and soft deletion.

### Blocked by

- Draft 3. Log In And Out With Secure Spring Security Sessions

## 11. Record Personal Agent Usage Metadata

User stories covered: 29, 30, 48, 49, 50

### What to build

Record metadata for user-visible agent runs, logical run steps, LLM calls, and tool calls. The account usage page should show the logged-in user's own platform quota and user-key usage. Normal telemetry must not store full prompts, full responses, system prompts, raw API keys, or full Draw.io XML.

### Acceptance criteria

- [ ] Agent runs are recorded for successful and failed user-visible requests.
- [ ] Logical run steps are recorded for routing, drawing, review, repair, direct answer, or equivalent phases.
- [ ] LLM call usage records provider, model, credential source, token counts when available, latency, status, and error class.
- [ ] Tool calls record tool name, latency, status, and error class.
- [ ] Missing provider token counts are stored as unknown, not zero.
- [ ] Normal telemetry does not persist full prompts, full responses, raw API keys, full system prompts, or full Draw.io XML.
- [ ] User account usage page shows only the current user's usage and quota information.
- [ ] Tests cover successful run telemetry, failed run telemetry, missing token metadata, user-key versus platform-key usage, and sensitive-content exclusion.

### Blocked by

- Draft 7. Enforce Verified User Daily Platform Quota
- Draft 9. Use Saved Model Credentials For Chat

## 12. Add Minimal Admin Usage And User Controls

User stories covered: 32, 33, 34, 35, 36, 37, 41

### What to build

Add minimal admin capabilities for operations: user list, disable user, global usage dashboard, run detail, and audit log viewing. Admins can inspect metadata and aggregate usage, but cannot view raw model API keys.

### Acceptance criteria

- [ ] Admin users can list users and see account status.
- [ ] Admin users can disable a user.
- [ ] Disabled users cannot log in or continue authenticated use.
- [ ] Admin users can see global request counts, token usage, success/failure rates, and latency summaries.
- [ ] Admin users can group usage by provider, model, and credential source.
- [ ] Admin users can inspect run metadata and step/call metadata.
- [ ] Admin actions write audit logs.
- [ ] Admin views never expose raw API keys.
- [ ] Non-admin users cannot access admin endpoints.
- [ ] Tests cover admin authorization, user disable behavior, usage dashboard access, audit logging, and raw-key non-exposure.

### Blocked by

- Draft 11. Record Personal Agent Usage Metadata

## 13. Add Debug Trace Controls And Retention Cleanup

User stories covered: 38, 39, 40, 41

### What to build

Add admin-only debug trace controls that are disabled by default and scoped narrowly by user, run, or time window. Debug trace may capture sensitive content only when enabled, expires by default after 7 days, and supports audited retention extension for a specific run.

### Acceptance criteria

- [ ] Debug trace capture is disabled by default.
- [ ] Only admins can enable debug trace.
- [ ] Debug trace can be scoped by user, run, or time window.
- [ ] Debug trace content records an expiry time 7 days from capture by default.
- [ ] Expired debug trace content is deleted by cleanup.
- [ ] Admins can extend retention for a specific run.
- [ ] Retention extension writes an audit log.
- [ ] Metadata remains available after debug trace content expires.
- [ ] Tests cover disabled-by-default behavior, admin-only access, scoped capture, expiry cleanup, retention extension, and audit logging.

### Blocked by

- Draft 11. Record Personal Agent Usage Metadata
- Draft 12. Add Minimal Admin Usage And User Controls

## 14. Handle Account Deletion And Retention Policy

User stories covered: 31

### What to build

Implement account deletion and data retention behavior. Deletion removes or soft-deletes personal account data, diagrams, conversations, and model credentials; immediately deletes debug trace content; anonymizes usage metadata; and keeps anonymized operational summaries.

### Acceptance criteria

- [ ] Account deletion deletes or soft-deletes profile data.
- [ ] Account deletion deletes or soft-deletes diagrams and conversation data.
- [ ] Account deletion deletes model credentials and prevents future decryption.
- [ ] Account deletion immediately deletes debug trace content for the user.
- [ ] Usage detail is anonymized while preserving operational statistics.
- [ ] Daily usage summaries remain only in anonymized form.
- [ ] Audit logs retain accountability while redacting or irreversibly hashing deleted user identifiers.
- [ ] Tests cover deletion effects across account data, diagrams, credentials, debug trace, usage detail, summaries, and audit logs.

### Blocked by

- Draft 10. Import Anonymous Workspace After Login
- Draft 11. Record Personal Agent Usage Metadata
- Draft 12. Add Minimal Admin Usage And User Controls
- Draft 13. Add Debug Trace Controls And Retention Cleanup
