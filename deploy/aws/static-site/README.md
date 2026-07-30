# Production static frontend and Tunnel ingress

The production web path is:

```text
freedrawai.com / www.freedrawai.com
  -> Cloudflare DNS/proxy
  -> CloudFront
  -> private S3 bucket

api.freedrawai.com
  -> Cloudflare Tunnel
  -> cloudflared sidecar in the backend ECS task
  -> Spring Boot on localhost:8091
  -> private RDS MySQL
```

This removes the always-on frontend Fargate task, Application Load Balancer, and
the ALB's two public IPv4 addresses. The backend task retains a public IP only for
outbound access because this VPC has no NAT gateway; its security group has no
inbound application rule after cutover.

## Static site bootstrap

1. Request an ACM certificate in `us-east-1` for `freedrawai.com` and
   `www.freedrawai.com`.
2. Add the ACM validation CNAME records to Cloudflare with proxying disabled.
3. Deploy `cloudformation.yml` in `ap-southeast-2`.
4. Upload `ai-agent-draw-io-front/out/` to the stack's `BucketName` output.
5. Point the root and `www` Cloudflare records to the stack's
   `DistributionDomainName` output.
6. Subscribe the distribution to the CloudFront Free flat-rate plan when the
   account is eligible. The Free plan includes 1 million requests, 100 GB of
   transfer, and 5 GB of S3 Standard storage credit per month.

The bucket is private and retained if the stack is deleted. Non-current object
versions expire after seven days to keep rollback protection without unbounded
storage cost.

## Tunnel bootstrap

1. Create a remotely managed Cloudflare Tunnel named
   `ai-drawio-backend-prod`.
2. Add public hostname `api.freedrawai.com` with service
   `http://localhost:8091`.
3. Store the generated token under `CLOUDFLARE_TUNNEL_TOKEN` in the existing
   `ai-drawio/prod` Secrets Manager JSON secret.
4. Deploy the backend task. The workflow injects an essential, digest-pinned
   `cloudflared` sidecar and an ECS backend readiness check.
5. Verify the API through `https://api.freedrawai.com/api/v1/` before removing
   the backend service's ALB target group.

Never commit or print the Tunnel token. The ECS task execution role reads it
directly from Secrets Manager.

## Automatic deployment

Every successful `CI` run on `main` triggers `Deploy Production`. The workflow:

1. verifies the immutable backend and migration images;
2. temporarily starts RDS when the cost schedule has stopped it;
3. runs the idempotent migration task;
4. deploys the backend plus Tunnel with ECS circuit-breaker rollback;
5. builds and publishes the static frontend;
6. tests the API, frontend, and runtime API configuration; and
7. restores the previous ECS/RDS scheduled state.

No observation delay is required. The old ALB path must remain in place only
until the new frontend and API tests pass once.
