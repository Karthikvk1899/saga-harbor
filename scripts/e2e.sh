#!/usr/bin/env bash
set -euo pipefail
: "${SAGA_API_KEY:?Set SAGA_API_KEY}"
base=http://localhost:8080
for i in {1..60}; do if curl -fsS "$base/actuator/health" | grep -q 'UP'; then break; fi; sleep 2; done
idempotency=$(cat /proc/sys/kernel/random/uuid)
created=$(curl -fsS -X POST "$base/api/v1/sagas" -H "Authorization: Bearer $SAGA_API_KEY" -H "Idempotency-Key: $idempotency" -H 'Content-Type: application/json' -d '{"orderId":"smoke-order"}')
saga_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])' <<<"$created")
test "$(curl -fsS -X POST "$base/api/v1/sagas" -H "Authorization: Bearer $SAGA_API_KEY" -H "Idempotency-Key: $idempotency" -H 'Content-Type: application/json' -d '{"orderId":"smoke-order"}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')" = "$saga_id"
get() { curl -fsS "$base/api/v1/sagas/$saga_id" -H "Authorization: Bearer $SAGA_API_KEY"; }
for stage in RESERVING CHARGING RELEASING; do
  echo "Waiting for $stage command for saga $saga_id"
  echo "Outbox pending: $(docker compose exec -T redis redis-cli HLEN outbox:payload), ready: $(docker compose exec -T redis redis-cli LLEN outbox:ready)"
  docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic saga.commands || true
  found=0
  for i in {1..15}; do
    record=$(get)
    command_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["commandId"])' <<<"$record")
    messages=$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic saga.commands --from-beginning --timeout-ms 1500 2>/dev/null || true)
    if grep -q "$command_id" <<<"$messages"; then found=1; break; fi
    sleep 1
  done
  if [[ "$found" != 1 ]]; then
    echo "Command not observed: $command_id" >&2
    docker compose exec -T redis redis-cli HLEN outbox:payload
    docker compose exec -T redis redis-cli ZRANGE outbox:leases 0 -1 WITHSCORES
    docker compose exec -T kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --topic saga.commands || true
    exit 1
  fi
  if [[ "$stage" == CHARGING ]]; then success=false; else success=true; fi
  payload=$(python3 - "$saga_id" "$command_id" "$success" <<'PY'
import json,sys
print(json.dumps({"schemaVersion":1,"sagaId":sys.argv[1],"commandId":sys.argv[2],"success":sys.argv[3]=="true"},separators=(',',':')))
PY
)
  printf '%s:%s\n' "$saga_id" "$payload" | docker compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic saga.results --property parse.key=true --property key.separator=: >/dev/null
  expected=CHARGING
  [[ "$stage" == CHARGING ]] && expected=RELEASING
  [[ "$stage" == RELEASING ]] && expected=COMPENSATED
  for j in {1..20}; do
    actual=$(get | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')
    [[ "$actual" == "$expected" ]] && break
    sleep 1
  done
  if [[ "$actual" != "$expected" ]]; then echo "Expected $expected, found $actual" >&2; exit 1; fi
  echo "Observed $actual"
done
test "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/sagas/$saga_id")" = 401
echo "Verified compensation workflow for $saga_id"
