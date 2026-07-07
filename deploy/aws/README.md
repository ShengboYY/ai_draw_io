# AWS 部署说明

这套部署文件面向一个稳妥的生产起步方案：前端 Next.js 和后端 Spring Boot 都跑在 ECS Fargate，数据库使用私有 RDS MySQL，敏感配置放 Secrets Manager，入口由 ALB 统一提供 HTTPS。

## 文件说明

- `ai-agent-draw-io/Dockerfile`: 后端 Spring Boot 多阶段构建镜像。
- `ai-agent-draw-io-front/Dockerfile`: 前端 Next.js standalone 镜像。
- `ai-agent-draw-io-front/docker-entrypoint.sh`: 启动时写入运行时 API 地址。
- `deploy/aws/env/*.example`: 本地或 ECS 环境变量模板。
- `deploy/aws/ecs/*.example.json`: ECS Fargate task definition 示例。
- `deploy/aws/docker-compose.prod.example.yml`: 本地生产化 smoke test 示例。

## 推荐架构

```text
Route 53
  -> ALB HTTPS 443 + ACM certificate + AWS WAF
      /api/v1/*  -> backend ECS service: 8091
      /*         -> frontend ECS service: 3000
backend ECS service
  -> RDS MySQL in private subnets
  -> Secrets Manager for API keys, DB password, encryption key
CloudWatch
  -> ECS logs, ALB metrics, alarms
```

优先使用同一个域名，例如 `https://draw.example.com`。前端设置 `NEXT_PUBLIC_API_BASE_URL=/api/v1`，这样 Cookie、CSRF、CORS 都更简单。

## 上线前必须确认

1. `ConsoleEmailSender` 默认会把邮箱验证和密码重置链接写进日志。正式开放注册/找回密码前，请先实现 SES 或 SMTP adapter，并切换 `account.email.sender`。
2. 当前登录态是默认 HTTP session。单副本可以运行；多副本生产环境建议接 Spring Session + Redis。临时方案是 ALB target group stickiness。
3. `MODEL_CREDENTIAL_ENCRYPTION_KEY` 必须稳定保存，不能随部署更换。否则数据库里已加密的用户模型凭据无法解密。
4. RDS 不要公开访问。只允许后端 ECS security group 访问 3306。
5. 生产后端必须使用 `SPRING_PROFILES_ACTIVE=prod`。
6. 项目的 `application-prod.yml` 默认被 `.gitignore` 排除；ECS 模板已经用 `SPRING_CONFIG_IMPORT` 和 `SPRING_DATASOURCE_*` 环境变量补齐生产配置，不依赖这个本地文件。

## 本地生产化 smoke test

这个步骤只验证容器、生产 profile、数据库 schema 和前后端联通，不代表真实 AWS 安全边界。

```bash
cd deploy/aws
docker compose -f docker-compose.prod.example.yml up --build
```

打开：

- 前端: `http://localhost:3000`
- 后端: `http://localhost:8091/api/v1/query_ai_agent_config_list`

如果你要测试真实模型调用，把 `deploy/aws/env/backend.env.example` 里的 `LLM_API_KEY` 换成自己的临时 key，或通过 shell 环境变量覆盖。不要提交真实 key。

## 1. 创建 ECR

```bash
export AWS_REGION=ap-southeast-2
export AWS_ACCOUNT_ID=123456789012

aws ecr create-repository \
  --repository-name ai-drawio-backend \
  --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true \
  --region "$AWS_REGION"

aws ecr create-repository \
  --repository-name ai-drawio-frontend \
  --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true \
  --region "$AWS_REGION"
```

登录 ECR：

```bash
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
```

## 2. 构建并推送镜像

```bash
export IMAGE_TAG=$(git rev-parse --short HEAD)

docker build --platform linux/amd64 \
  -t "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/ai-drawio-backend:$IMAGE_TAG" \
  ../../ai-agent-draw-io

docker push "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/ai-drawio-backend:$IMAGE_TAG"

docker build --platform linux/amd64 \
  -t "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/ai-drawio-frontend:$IMAGE_TAG" \
  ../../ai-agent-draw-io-front

docker push "$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/ai-drawio-frontend:$IMAGE_TAG"
```

如果你选择 ARM64 Fargate，把 build platform 和 task definition 的 `cpuArchitecture` 一起改成 `ARM64`。

## 3. 创建 Secrets Manager

生成模型凭据加密主密钥：

```bash
openssl rand -base64 32
```

创建 secret。下面是示例，不要把真实值写进仓库：

```bash
aws secretsmanager create-secret \
  --name ai-drawio/prod \
  --region "$AWS_REGION" \
  --secret-string '{
    "MYSQL_PASSWORD":"replace-with-rds-app-user-password",
    "LLM_API_KEY":"replace-with-provider-api-key",
    "MODEL_CREDENTIAL_ENCRYPTION_KEY":"base64:replace-with-openssl-output",
    "BAIDU_SEARCH_API_KEY":"",
    "SKILL_ADMIN_TOKEN":""
  }'
```

ECS task execution role 需要读取这个 secret 的权限，范围限制到这一个 ARN。

## 4. 创建 RDS MySQL

建议配置：

- Engine: MySQL 8.x
- Network: private subnets only
- Public access: off
- Storage encryption: on
- Automated backups: on，生产建议 7 到 35 天
- Deletion protection: on
- Multi-AZ: 生产开启，开发/演示可关闭省成本
- Security group inbound: 只允许 backend ECS task security group 的 3306

用 RDS 管理员账号初始化 schema：

```bash
export RDS_ENDPOINT=ai-drawio-prod.cluster-abcdefghijkl.ap-southeast-2.rds.amazonaws.com
export RDS_ADMIN=admin

mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/account.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/skill.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/diagram.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/usage-counter.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/usage-telemetry.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/admin.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/debug-trace.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p ai_draw_io < ../../ai-agent-draw-io/docs/sql/migrations/2026-07-02-create-model-credential.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p ai_draw_io < ../../ai-agent-draw-io/docs/sql/migrations/2026-07-03-create-usage-counter.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p ai_draw_io < ../../ai-agent-draw-io/docs/sql/migrations/2026-07-03-debug-trace-retention.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p < ../../ai-agent-draw-io/docs/sql/migrations/2026-07-03-expand-diagram-thumbnail-url.sql
mysql -h "$RDS_ENDPOINT" -u "$RDS_ADMIN" -p ai_draw_io < ../../ai-agent-draw-io/docs/sql/migrations/2026-07-07-add-canvas-content-hash.sql
```

不要在全新库执行 `2026-07-02-drop-diagram-current-version.sql`。它只给旧库迁移使用。

创建最小权限应用用户：

```sql
CREATE USER 'ai_drawio_app'@'%' IDENTIFIED BY 'replace-with-strong-password';
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_draw_io.* TO 'ai_drawio_app'@'%';
FLUSH PRIVILEGES;
```

如果后续应用迁移需要自动建表，再单独给迁移任务使用更高权限账号，不要给运行时应用账号 `CREATE` 或 `ALTER`。

## 5. 创建 ALB 和证书

1. 在 ACM 申请 `draw.example.com` 证书。
2. ALB 放 public subnets，开启 HTTPS 443 listener。
3. HTTP 80 listener 只做重定向到 HTTPS。
4. 创建两个 target group：
   - `ai-drawio-backend`: IP target，port `8091`
   - `ai-drawio-frontend`: IP target，port `3000`
5. Listener rules：
   - `/api/v1/*` -> backend target group
   - `/*` -> frontend target group
6. 后端健康检查可以先用 `/api/v1/query_ai_agent_config_list`。长期建议给后端加 `/actuator/health`。

## 6. 创建 ECS 服务

先创建 CloudWatch log groups：

```bash
aws logs create-log-group --log-group-name /ecs/ai-drawio/backend --region "$AWS_REGION"
aws logs create-log-group --log-group-name /ecs/ai-drawio/frontend --region "$AWS_REGION"
```

替换 task definition 示例里的：

- AWS account id
- Region
- ECR image tag
- IAM role ARN
- RDS endpoint
- 域名和管理员邮箱
- Secrets Manager ARN

注册 task definitions：

```bash
aws ecs register-task-definition \
  --cli-input-json file://ecs/backend-task-definition.example.json \
  --region "$AWS_REGION"

aws ecs register-task-definition \
  --cli-input-json file://ecs/frontend-task-definition.example.json \
  --region "$AWS_REGION"
```

创建 ECS cluster，然后创建两个 Fargate service，选择 private subnets，不分配 public IP，并挂到对应 target group。

安全组建议：

- ALB security group: inbound `80/443` from internet。
- Frontend task security group: inbound `3000` only from ALB security group。
- Backend task security group: inbound `8091` only from ALB security group。
- RDS security group: inbound `3306` only from backend task security group。
- ECS outbound: 允许访问 RDS、Secrets Manager/ECR/CloudWatch endpoints，以及模型供应商 API。

如果 private subnet 没有 NAT Gateway，需要创建 VPC endpoints：ECR API、ECR Docker、CloudWatch Logs、Secrets Manager、S3 gateway endpoint。模型供应商公网 API 仍需要 NAT 或其他出网方案。

## 7. Route 53 和 WAF

Route 53 创建 `A`/`AAAA` Alias 到 ALB。

WAF 建议先启用：

- AWSManagedRulesCommonRuleSet
- AWSManagedRulesKnownBadInputsRuleSet
- 针对 `/api/v1/auth/*` 和 `/api/v1/chat_stream` 的 rate limit

先用 count 模式观察误杀，再切到 block。

## 8. 发布和回滚

发布：

1. 构建新镜像并用 git SHA 做不可变 tag。
2. 更新 ECS task definition image tag。
3. 更新 ECS service，等待 deployment stable。
4. 观察 ALB 5xx、ECS task restarts、CloudWatch error logs、RDS connection count。

回滚：

1. 把 ECS service 切回上一版 task definition。
2. 如果包含数据库变更，优先使用向后兼容 schema。没有回滚脚本时不要直接删列或改类型。

## 9. 生产安全检查清单

- AWS root account 开 MFA，不用 root 日常操作。
- 使用 IAM Identity Center 或短期凭证，不发长期 access key。
- ECR 开 scan on push，镜像 tag immutable。
- ECS task 使用最小权限 task role。
- Secrets Manager secret 不写入 Docker image、Git、CloudWatch 明文日志。
- RDS private only、加密、备份、删除保护。
- ALB 只开放 80/443；80 强制跳 HTTPS。
- Cookie secure 已由 `prod` profile 开启。
- `APP_SECURITY_ALLOWED_ORIGINS` 不使用通配符。
- 管理员接口只配置明确的 `ADMIN_EMAILS` 和 `ADMIN_ALLOWED_ORIGINS`。
- 打开 CloudWatch alarms：ALB 5xx、target unhealthy、ECS CPU/memory、task restart、RDS storage/connection/CPU。
