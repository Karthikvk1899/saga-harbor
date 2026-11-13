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
  for i in {1..40}; do
    record=$(get)
    command_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["commandId"])' <<<"$record")
    messages=$(docker compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic saga.commands --from-beginning --timeout-ms 1500 2>/dev/null || true)
    if grep -q "$command_id" <<<"$messages"; then break; fi
    sleep 1
  done
  test "$i" -lt 40
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
  for j in {1..40}; do
    actual=$(get | python3 -c 'import json,sys; print(json.load(sys.stdin)["state"])')
    [[ "$actual" == "$expected" ]] && break
    sleep 1
  done
  test "$actual" = "$expected"
done
test "$(curl -s -o /dev/null -w '%{http_code}' "$base/api/v1/sagas/$saga_id")" = 401
echo "Verified compensation workflow for $saga_id"
