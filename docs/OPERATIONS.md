# Operations

## Monitoring

Scrape `/actuator/prometheus` only on a private network. Alert on Kafka consumer lag, outbox hash length (`HLEN outbox:payload`), stale leases (`ZRANGE outbox:leases 0 -1 WITHSCORES`), Redis memory and persistence errors, and sagas stuck in `RESERVING`, `CHARGING`, `RELEASING`, or `MANUAL_REVIEW`. The API exposes no scan endpoint; build an indexed operational view before production use.

## Recovery

Kafka downtime leaves commands leased, then eligible for retry after 30 seconds. Restarting the relay can duplicate a command already published. Workers need persistent idempotency on `commandId` and should repeat the same result on duplicate delivery. If Redis loses state, restore from a verified snapshot or reconcile every open saga against authoritative downstream records; Kafka output alone is insufficient to reconstruct all acknowledged API starts. Do not blindly replay a payment command. Investigate DLT messages; republishing is a deliberate operator action after correcting the envelope or underlying data.

## Deployment boundaries

Compose uses a single Kafka broker, single Redis instance, plain internal traffic, and loopback host ports. For deployment, supply TLS, Kafka ACLs, network isolation, secrets management, Redis persistence and backups, topic provisioning, resource limits, monitoring, and access control for metrics and health. Protect result production: an attacker who can publish a matching command ID can influence saga state. Scale replicas only after load tests and failure injection. The coordinator does not enforce deadlines, reconciliation with workers, or distributed consistency with their stores. No AWS deployment is included.
