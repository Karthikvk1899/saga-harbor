# Operations

## Monitoring

Scrape `/actuator/prometheus` only on a private network. Monitor `saga_commands_published_total` and `saga_results_total` without identifiers as labels. Alert on Kafka consumer lag, outbox hash length (`HLEN outbox:payload`), stale leases (`ZRANGE outbox:leases 0 -1 WITHSCORES`), Redis memory and persistence errors, and sagas stuck in `RESERVING`, `CHARGING`, `RELEASING`, or `MANUAL_REVIEW`. Each saga records `updatedAtMs` in Redis server time. The API exposes no scan endpoint; build an indexed operational view before production use.

## Recovery

Kafka downtime leaves commands leased, then eligible for retry after 30 seconds. Restarting the relay can duplicate a command already published. Workers need persistent idempotency on `commandId` and should repeat the same result on duplicate delivery. If Redis loses state, restore from a verified snapshot or reconcile every open saga against authoritative downstream records; Kafka output alone is insufficient to reconstruct all acknowledged API starts. Do not blindly replay a payment command. Investigate DLT messages; republishing is a deliberate operator action after correcting the envelope or underlying data.

## Deployment boundaries

Compose uses a single Kafka broker, single Redis instance, plain internal traffic, and loopback host ports. For deployment, supply TLS, Kafka ACLs, network isolation, secrets management, Redis persistence and backups, topic provisioning, resource limits, monitoring, and access control for metrics and health. Protect result production: an attacker who can publish a matching command ID can influence saga state. Scale replicas only after load tests and failure injection. The coordinator does not enforce deadlines, reconciliation with workers, or distributed consistency with their stores. No AWS deployment is included.

Production API mode requires a validated HTTPS OIDC issuer, audience and JWKS endpoint. Give callers only the `saga:read` or `saga:write` scopes they need. Keep management metrics off the public API listener. The demo console is unsuitable for this mode; replace it with an identity-aware operator client. Run an IdP integration exercise, network policy validation, backup restore drill, worker idempotency and compensation tests, and representative load/failover tests before a real checkout rollout.

The Compose fault test stops Kafka, starts a saga into the Redis outbox, restarts Kafka and observes the delayed command. It also stops Redis and verifies a request is not accepted during the outage before retrying its idempotency key after recovery. These single-node interruptions are useful smoke evidence; they do not simulate Redis failover, network partitions, multi-broker Kafka, or a downstream payment provider.
