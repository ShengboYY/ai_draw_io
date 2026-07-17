# Phase 3 ECS Baseline Audit

Audit date: 2026-07-17  
Region: `ap-southeast-2`  
Scope: ECS Fargate, ALB, target groups, security groups, task definitions, IAM task roles, ECR, CloudWatch, RDS, ACM, and GitHub deployment prerequisites.

This audit is read-only. It records the production baseline before automated ECS deployment is enabled.

Remediation progress on 2026-07-17:

| Item | Status |
| --- | --- |
| RDS automated backup retention | Completed: 7 days |
| RDS storage auto scaling | Intentionally disabled; storage remains fixed at 20 GiB |
| ECS deployment circuit breaker | Completed for backend and frontend, with rollback enabled |
| Backend readiness health check | Deferred until the first deployment of an image that contains Actuator readiness |
| CloudWatch alarms | Deferred for cost control; manual monitoring risk accepted |
| GitHub `production` Environment | Completed; deployment branch restricted to `main` |
| GitHub OIDC deployer role | Completed with project-scoped ECS, ECR read, and `iam:PassRole` permissions |
| Production deploy and rollback workflows | Completed and merged in PR #20 |
| Release `20260717` database migration package | Locally validated from the 2026-07-05 production baseline; production inventory, snapshot, and one-off ECS run remain |

## 1. Executive conclusion

The current production service is healthy, but the first SHA-based release remains **blocked on the production database migration**.

The public website and backend route both return HTTP 200 over valid HTTPS. The ALB, target groups, ECS tasks, and RDS instance are currently available. Network ingress is well restricted: the ALB is the only public application entry point, ECS containers only accept traffic from the ALB security group, and MySQL only accepts traffic from the backend security group.

ECS automatic rollback, the GitHub production trust boundary, deployment workflows, and the seven-day RDS recovery window are now in place. Local validation proved that 30 migrations added after the 2026-07-05 database initialization must run before the current backend image can be deployed. The dedicated migration package is under `deploy/aws/database/`.

## 2. Current architecture

```text
Internet
  -> Cloudflare DNS/proxy
  -> ALB (HTTP 80 redirects to HTTPS 443)
      -> /api/v1/* -> backend target group -> backend Fargate task:8091
      -> default    -> frontend target group -> frontend Fargate task:3000
  -> backend task -> private RDS MySQL:3306
```

Current ECS resources:

| Resource | Current state |
| --- | --- |
| Cluster | `ai-drawio-cluster`, active, two running Fargate tasks |
| Backend service | `ai-drawio-backend-service`, desired 1, running 1 |
| Frontend service | `ai-drawio-frontend-service`, desired 1, running 1 |
| Backend task definition | `ai-drawio-backend-prod:4`, 1 vCPU, 2 GiB |
| Frontend task definition | `ai-drawio-frontend-prod:2`, 0.5 vCPU, 1 GiB |
| Deployment strategy | Rolling, minimum healthy 100%, maximum 200% |
| Public URL | `https://freedrawai.com` |

## 3. Release blockers

These items should be completed before creating or running the production deployment workflow.

### 3.1 Enable the ECS deployment circuit breaker

Both services currently have:

```text
deploymentCircuitBreaker.enable = false
deploymentCircuitBreaker.rollback = false
```

Keep `minimumHealthyPercent=100` and `maximumPercent=200`, then enable the circuit breaker and rollback for both services. With desired count 1, ECS can start a replacement task while retaining the old healthy task. If the new task cannot become healthy, ECS can return to the last successful deployment.

This protects against image startup errors and failed ALB health checks. It does not reverse database migrations or detect every delayed business bug.

### 3.2 Replace the backend business endpoint health check

The backend target group currently checks:

```text
/api/v1/query_ai_agent_config_list
```

This is a business API, not a dedicated health endpoint. It can fail because of response-shape, authentication, or business-logic changes even when the process is healthy. It may also query more dependencies than a load balancer needs.

The current repository enables Spring Boot Actuator probes, but the production image is from an older commit that predates the Actuator dependency. Do not change the target group path while that image is running.

For the first controlled SHA deployment, retain the existing health path. After the new backend task is healthy, verify that the deployed image exposes the readiness endpoint and then change the target group to:

```text
/actuator/health/readiness
```

Recommended backend target group values:

| Setting | Value |
| --- | --- |
| Protocol | HTTP |
| Port | Traffic port (`8091`) |
| Path | `/actuator/health/readiness` |
| Matcher | `200` |
| Interval | 30 seconds |
| Timeout | 5 seconds |
| Healthy threshold | 2 or 3 |
| Unhealthy threshold | 2 |

The ALB listener does not route `/actuator/*` to the backend, so this endpoint remains internal to the target group.

For the frontend, keep `/` and consider a `200-399` matcher.

### 3.3 Create a separate GitHub deployment trust boundary

The repository currently has an ECR publisher role and repository variables for image publishing. That role must remain limited to ECR publication.

Missing deployment prerequisites:

- GitHub Environment named `production`.
- A separate OIDC role such as `ai-drawio-github-deployer-role`.
- Repository or environment variables for the ECS cluster, services, task families, deploy role, and production URL.
- `deploy-production.yml` and `rollback-production.yml`.

The deployment role should only be able to:

- Read the two ECR repositories.
- Read the current ECS services and task definitions.
- Register task definitions in the two approved families.
- Update the two approved ECS services.
- Pass only the existing task execution and application task roles.

Do not give the publisher role ECS, IAM, Secrets Manager, RDS, or broad `AdministratorAccess` permissions.

### 3.4 Add minimum production alarms

There are currently no CloudWatch alarms. The project owner has chosen to defer paid alarms for cost control. This leaves a known detection gap after a deployment.

When the budget permits, create alarms for:

- Backend and frontend target group `UnHealthyHostCount >= 1`.
- ALB target HTTP 5xx errors.
- ECS service running task count below desired count, or equivalent service-health monitoring.
- RDS `FreeStorageSpace` low.
- RDS CPU high for a sustained period.
- RDS database connections close to the practical limit for `db.t4g.micro`.

Route notifications to an SNS topic with a verified email subscription. Until then, every deployment must include workflow smoke tests and a manual review of ECS service events, target health, CloudWatch logs, and RDS free storage. Alarms do not replace deployment smoke tests; they detect degradation during and after deployment.

### 3.5 Increase the RDS recovery window

RDS automated backups are active and encrypted, but retention is only **1 day**. The observed point-in-time restore window covers approximately the previous 24 hours.

Before any release that can change persistent data:

- Increase automated backup retention to 7 days.
- Keep allocated storage fixed at 20 GiB; storage auto scaling remains disabled for cost control.
- Monitor `FreeStorageSpace` and increase storage manually before it becomes critically low.
- Keep deletion protection enabled.
- Keep copy-tags-to-snapshots enabled.
- Create a manual snapshot before a risky migration.
- Test a restore procedure periodically.

Application rollback and database rollback are different operations. ECS can restore an old task definition, but schema changes should use backward-compatible expand-and-contract migrations and forward fixes.

## 4. Important hardening after the first safe deployment

These items matter, but they do not need to block the first controlled manual workflow run.

### 4.1 Adopt hardened task definitions

Current strengths:

- Images declare non-root users.
- Linux capabilities drop `ALL`.
- Backend and frontend use separate task roles.
- Secrets use ECS `secrets.valueFrom` bindings.
- Logs use the `awslogs` driver.
- Runtime architecture is explicitly `X86_64`, matching CI image builds.

Current gaps:

- `readonlyRootFilesystem` is not enabled.
- No writable `/tmp` volume is mounted.
- ECS managed tags and tag propagation are disabled.
- Container-level health checks are absent, so ECS reports container health as `UNKNOWN`; ALB target health is still healthy.
- Production task definitions have no resource tags.

The example task definitions already show the intended read-only root filesystem and writable `/tmp` volume. Validate application writes, especially backend logs and Java temporary files, before adopting them.

### 4.2 Move ECS tasks to private subnets

Both current Fargate tasks receive public IP addresses and run in default VPC subnets with an Internet Gateway route. Their security groups still prevent direct public inbound traffic, which limits immediate exposure, but private subnets are the stronger production design.

Do not simply disable public IP assignment. The tasks need outbound access for ECR image pulls, Secrets Manager, CloudWatch Logs, SES, and external AI/search APIs. A private-subnet migration requires one of these designs:

- NAT Gateway for general outbound access, with additional hourly and data-processing cost.
- VPC endpoints for AWS services plus a controlled egress path for external APIs.

Treat this as a planned network migration with a rollback path, not a console toggle.

### 4.3 Improve observability and ALB protection

Current settings:

- ECS Container Insights is disabled.
- ALB access logs are disabled.
- ALB deletion protection is disabled.
- Invalid HTTP header dropping is disabled.
- ECS log groups retain 14 days of logs.
- No custom KMS key is configured for log groups; CloudWatch Logs still applies AWS-managed server-side encryption.

Recommended sequence:

1. Add alarms first.
2. Enable ALB deletion protection.
3. Enable invalid-header dropping after compatibility verification.
4. Enable ALB access logs to a protected S3 bucket if traffic investigation is needed.
5. Enable Container Insights only if its added telemetry justifies the cost.

### 4.4 Review availability and scaling choices

The project currently uses one backend task, one frontend task, and a Single-AZ `db.t4g.micro` RDS instance. No ECS auto scaling target exists. RDS storage is intentionally fixed at 20 GiB to control cost.

This is a reasonable personal-project cost choice, but it means:

- A task or Availability Zone failure can temporarily reduce application availability.
- RDS maintenance or instance failure can cause database downtime.
- RDS storage must be monitored and increased manually before it fills.
- Traffic spikes do not automatically add ECS capacity.

For higher availability, run at least two tasks per service across Availability Zones and use Multi-AZ RDS. These changes materially increase monthly cost.

### 4.5 Clean up IAM duplication

The ECS task execution role has two overlapping inline policies for Secrets Manager. The broader policy includes the application secret and the RDS-managed secret; the narrower policy repeats application-secret access.

After confirming the database initialization task still needs the RDS-managed secret, remove the redundant inline policy. Keep resource-level Secret ARNs and `secretsmanager:GetSecretValue` only.

The backend task role is appropriately narrow: it only allows `ses:SendEmail` for verified identities. The frontend task role has no AWS permissions.

## 5. Controls already working well

- HTTP redirects to HTTPS.
- ACM certificate is issued, attached, covers the root and `www` domains, and is eligible for managed renewal.
- TLS 1.2/1.3 ALB security policy is active.
- ALB is the only public inbound application entry point.
- Frontend accepts port 3000 only from the ALB security group.
- Backend accepts port 8091 only from the ALB security group.
- RDS accepts port 3306 only from the backend security group.
- RDS is not publicly accessible.
- RDS storage is encrypted and deletion protection is enabled.
- The JDBC production URL requires TLS and disables public-key retrieval.
- Secure session cookies and production CORS origins are configured.
- CloudWatch ECS logs have a 14-day retention policy.
- ECR tags are immutable, scan-on-push is enabled, and repositories are encrypted.
- ECR lifecycle rules remove untagged images after 7 days and keep the latest 30 SHA releases.
- `main` requires the backend, frontend, and container CI checks, and branch protection applies to administrators.

## 6. Artifact gap

Production is healthy but still runs older manually named image tags. It does not yet run the immutable `sha-<full-git-sha>` images produced and scanned by Phase 2.

Do not manually retag or replace the tasks in the console. After release `20260717` database migration completes, the first controlled deployment should select a full commit SHA whose backend and frontend images both exist in ECR. The workflow saves the current task definition revisions, updates only the image fields, deploys backend first, runs a backend smoke test, deploys frontend, and runs a frontend smoke test.

## 7. Recommended execution order

1. Increase RDS backup retention to 7 days; keep storage auto scaling disabled and monitor free storage.
2. Enable ECS circuit breaker and automatic rollback on both services.
3. Create the GitHub `production` Environment.
4. Create the separate GitHub OIDC deployer role with least privilege.
5. Add deployment variables without adding long-lived AWS secrets.
6. Implement and review the production deployment workflow.
7. Implement and review the manual rollback workflow.
8. Inspect the production schema from a read-only one-off task and confirm it still matches the 2026-07-05 baseline.
9. Create a manual RDS snapshot and wait until it is `available`.
10. Run the reviewed release `20260717` migration image as an ECS one-off task and verify all 30 history records.
11. Deploy the known-safe full SHA with `migration-completed`, retaining the existing backend health path for this first deployment.
12. Verify the deployed backend readiness endpoint, then change the target group path to `/actuator/health/readiness` and reduce healthy threshold to 2 or 3.
13. Manually verify ECS stability, target health, CloudWatch logs, RDS free storage, and application smoke tests.
14. Add CloudWatch alarms when the monitoring budget permits.
15. Schedule the private-subnet and read-only-root-filesystem hardening as separate changes.

## 8. Go/no-go checklist for the first automated deployment

- [x] RDS backup retention is at least 7 days.
- [ ] A current recovery point or manual snapshot exists.
- [x] Both ECS services have circuit breaker and rollback enabled.
- [ ] Before the first SHA deployment, the existing backend target group is healthy.
- [ ] After deploying the Actuator-enabled backend, the target group is changed to `/actuator/health/readiness`.
- [ ] Both target groups are healthy before deployment.
- [ ] CloudWatch alarms are active, or the temporary manual monitoring risk is explicitly accepted.
- [x] GitHub `production` Environment exists.
- [x] The deployer OIDC role is separate from the image publisher role.
- [x] Deployment IAM can pass only approved task roles.
- [x] Both `sha-<full-git-sha>` images exist and have acceptable scan results.
- [x] The release SHA belongs to `main`.
- [ ] Production schema matches the audited 2026-07-05 baseline.
- [ ] Release `20260717` migration task exits successfully and records all 30 checksums.
- [ ] Database migration state is explicitly confirmed as `migration-completed`.
- [ ] Previous backend and frontend task definition revisions are recorded.
- [ ] Backend and frontend smoke tests pass after deployment.
