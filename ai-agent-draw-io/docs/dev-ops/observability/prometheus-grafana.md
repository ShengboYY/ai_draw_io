# Prometheus and Grafana Observability

The application exposes production metrics through Spring Boot Actuator and Micrometer.
Prometheus should scrape the app; Grafana should read from Prometheus.

## Application Endpoints

- Health: `GET /actuator/health`
- Prometheus metrics: `GET /actuator/prometheus`

Only `health` and `prometheus` are exposed. Do not expose `/actuator/prometheus` publicly in production.
Restrict it to the Prometheus server by VPC, security group, Kubernetes NetworkPolicy, ingress allowlist,
or reverse-proxy ACL.

## Metrics

Micrometer counter names are defined without a `total` suffix in code. Prometheus exports them with `_total`.

- `ai_agent_run_total`: run count by `request_type`, `credential_source`, `status`
- `ai_agent_run_latency_seconds`: run latency histogram
- `ai_agent_llm_call_total`: LLM call count by `phase`, `provider`, `model`, `credential_source`, `status`
- `ai_agent_llm_call_latency_seconds`: LLM call latency histogram
- `ai_agent_llm_tokens_total`: token usage by `provider`, `model`, `credential_source`, `token_type`
- `ai_agent_tool_call_total`: tool call count by `phase`, `tool_name`, `status`
- `ai_agent_tool_call_latency_seconds`: tool call latency histogram
- `ai_agent_telemetry_write_dropped_total`: dropped telemetry writes
- `ai_agent_telemetry_queue_size`: queued telemetry writes
- `ai_agent_telemetry_queue_remaining_capacity`: remaining telemetry queue capacity
- `ai_agent_debug_trace_capture_total`: debug trace capture count by `event_type`
- `ai_agent_debug_trace_view_total`: debug trace content view count by `outcome`

Tags intentionally exclude `userId`, `runId`, `requestId`, `sessionId`, model credential IDs,
prompt text, and canvas content.

## Prometheus Scrape Config

```yaml
scrape_configs:
  - job_name: ai-agent-draw-io
    metrics_path: /actuator/prometheus
    scrape_interval: 15s
    static_configs:
      - targets:
          - ai-agent-draw-io:8091
```

Replace `ai-agent-draw-io:8091` with the service DNS name or host:port used in your environment.

## Grafana Panels

Run throughput:

```promql
sum(rate(ai_agent_run_total[5m])) by (status)
```

Run failure ratio:

```promql
sum(rate(ai_agent_run_total{status="failed"}[5m]))
/
sum(rate(ai_agent_run_total[5m]))
```

Run p95 latency:

```promql
histogram_quantile(0.95, sum(rate(ai_agent_run_latency_seconds_bucket[5m])) by (le))
```

LLM p95 latency by model:

```promql
histogram_quantile(0.95, sum(rate(ai_agent_llm_call_latency_seconds_bucket[5m])) by (le, provider, model))
```

LLM/tool failures:

```promql
sum(rate(ai_agent_llm_call_total{status="failed"}[5m])) by (provider, model)
sum(rate(ai_agent_tool_call_total{status="failed"}[5m])) by (tool_name)
```

Token burn rate:

```promql
sum(rate(ai_agent_llm_tokens_total[5m])) by (token_type, provider, model, credential_source)
```

Telemetry health:

```promql
increase(ai_agent_telemetry_write_dropped_total[5m])
ai_agent_telemetry_queue_size
```

Debug trace access:

```promql
sum(rate(ai_agent_debug_trace_capture_total[5m])) by (event_type)
sum(rate(ai_agent_debug_trace_view_total[5m])) by (outcome)
```

## Alert Examples

- Run failure ratio `> 5%` for 10 minutes.
- LLM failure ratio `> 3%` for 10 minutes, grouped by provider/model.
- Run p95 latency above the product SLO for 10 minutes.
- `increase(ai_agent_telemetry_write_dropped_total[5m]) > 0`.
- `ai_agent_telemetry_queue_size / (ai_agent_telemetry_queue_size + ai_agent_telemetry_queue_remaining_capacity) > 0.8`.
- Any unexpected spike in `ai_agent_debug_trace_view_total`.

## Manual Operations

1. Deploy the new application build.
2. Verify `GET /actuator/health` returns healthy.
3. Verify Prometheus can reach `GET /actuator/prometheus`.
4. Restrict `/actuator/prometheus` so public users cannot access it.
5. Add the Prometheus scrape job.
6. Add Prometheus as a Grafana data source.
7. Create dashboard panels from the PromQL above.
8. Configure alert routing, such as Slack, email, PagerDuty, or webhook.

## Retention Cleanup

Telemetry cleanup is disabled by default so multi-instance deployments do not all run the same
database purge at once. Enable it on exactly one app instance, or run it from a dedicated scheduler:

```bash
ZIPP_TELEMETRY_CLEANUP_ENABLED=true
```

Useful knobs:

- `ZIPP_TELEMETRY_RETENTION_DAYS`: default `30`
- `ZIPP_TELEMETRY_CLEANUP_CRON`: default `0 30 3 * * *`
- `ZIPP_TELEMETRY_DELETE_BATCH_SIZE`: default `500`

The cleanup deletes expired telemetry by batches of run IDs. This avoids one large `DELETE` over all
old rows and keeps database lock/log pressure bounded.
