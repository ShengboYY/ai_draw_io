# Material RAG fault-injection matrix

Run only against synthetic non-production owners and disposable Pinecone namespaces.

| Fault | Injection | Required result |
|---|---|---|
| Worker kill | Terminate the task after lease claim | Expired lease requeues; stale fence cannot commit |
| ClamAV timeout/unavailable | Block clamd port | Upload remains quarantined; no preview/model/vector delivery |
| Pinecone 429/5xx | Contract proxy returns retry response | Bounded retry/circuit open; plain text path unchanged |
| S3 partial delete | Fail one exact VersionId delete | Durable deletion retries; no broad prefix delete |
| MySQL deadlock | Deadlock commit transaction | Whole answer/canvas transaction rolls back and retries safely |
| SSE disconnect/Stop | Disconnect before commit | Run cancellation CAS wins or returns already completed |
| Lease/expiry race | Expire material during retrieval | Existing valid lease finishes; no new lease is granted |
| Stale vector hit | Return trashed/other-owner chunk ID | MySQL reauthorization drops it; successful hydration count is zero |
| Capacity telemetry outage | Make snapshot query fail | Anonymous new work fails closed; registered/plain text remain available |

Record the deployment SHA, processing/ranking/model profile, fixture IDs, timestamps and low-cardinality
error codes. Never retain raw evidence prompts, owner IDs, file names or document content in the report.
