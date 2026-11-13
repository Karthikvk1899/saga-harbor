# Threat model

## Assets and boundaries

| Boundary | Assets | Expected controls |
|---|---|---|
| HTTP client → coordinator | Saga identifiers, write permission | Production JWT signature, issuer, audience, expiry, scope; TLS ingress |
| Coordinator → Redis | Workflow state, idempotency map, pending commands | Private network, Redis auth/TLS, persistence, backups |
| Coordinator → Kafka | Commands and results | Broker ACLs, auth/TLS, topic ownership, retention |
| Workers → downstream stores | Inventory reservation, charge and release | Worker idempotency by command ID, authoritative reconciliation |

## Abuse and failure cases

| Case | Current defense | Remaining work before a live deployment |
|---|---|---|
| Forged HTTP request | Scoped JWT in production mode; demo key on loopback | Provision IdP, TLS, key rotation, ingress limits |
| Forged result with known command ID | Kafka producer ACLs required; schema and key checked | Configure broker ACLs and mutually authenticated clients |
| Replay or duplicate delivery | Atomic Redis transition checks expected command ID; leased outbox | Workers must persist deduplication before effects |
| Kafka outage | Bounded Redis outbox and retry | Capacity alarms, disk and Redis failover tests |
| Redis data loss | AOF in local Compose | Backup/restore drills and downstream reconciliation |
| Unbounded API requests | Outbox capacity, validated order IDs | Ingress rate limits, tenant quotas, memory and retention policy |
| Stuck or ambiguous side effect | Manual review state and aging timestamps | Incident workflow with authoritative worker evidence |
| Dependency vulnerability | npm audit gate and automated dependency review | Continuous advisory triage and Java artifact scanning |

This document describes controls supplied by code and controls the deployment operator must provide. The latter have not been validated in a live environment.
