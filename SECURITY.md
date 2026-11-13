# Security

## Authentication modes

`APP_MODE=demo` (the Compose default) uses one shared bearer key in `SAGA_API_KEY`. The console holds it only in page memory. Compose binds the UI and API to localhost. This mode is **not** an internet-facing deployment configuration.

`APP_MODE=production` requires `OIDC_ISSUER`, `OIDC_AUDIENCE`, and `OIDC_JWKS_URI`. Issuer and JWKS URL must be HTTPS. Spring Security verifies the JWT signature from the JWKS endpoint, expiration/not-before and issuer; the application adds audience validation. `GET /api/v1/sagas/{id}` needs `saga:read`; `POST /api/v1/sagas` needs `saga:write` in the JWT `scope` claim. All other routes are denied except a minimal health endpoint. No shared API key is accepted in this mode. The demo console does not implement OIDC sign-in; deploy an identity-aware client or use an authorized service caller in production.

The security web tests use synthetic JWT principals to verify route authorization and configuration rejection. They do **not** exercise a live identity provider or a full signed-token exchange. Do not claim that an IdP integration has been validated.

## Network and data controls

Terminate TLS at a trusted ingress, restrict ingress and egress, and enforce Kafka and Redis authentication/TLS plus Kafka producer ACLs. Restrict who may publish to `saga.results`: a result can authorize a state transition. Keep `/actuator/prometheus` on a separate private management surface if needed; production mode denies it on the API listener. Use secrets management and rotation for any credentials. The console sets a restrictive CSP and related response headers, but it is a demo interface and does not replace an identity-aware frontend. Do not put payment credentials or sensitive order data in commands; only opaque IDs are intended. Report vulnerabilities privately to the repository owner rather than opening a public issue with exploit details.

See [threat model](docs/THREAT_MODEL.md) for boundaries and remaining risks.
