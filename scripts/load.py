#!/usr/bin/env python3
"""Small concurrent acceptance smoke test; reported latency is CI runner HTTP time."""
import argparse
import concurrent.futures
import json
import os
import statistics
import time
import urllib.error
import urllib.request
import uuid


def create(index: int, url: str, key: str) -> tuple[str, float]:
    body = json.dumps({"orderId": f"load-{uuid.uuid4()}-{index}"}).encode()
    request = urllib.request.Request(
        url + "/api/v1/sagas", body,
        {"Authorization": "Bearer " + key, "Idempotency-Key": str(uuid.uuid4()),
         "Content-Type": "application/json"}, method="POST")
    start = time.monotonic()
    with urllib.request.urlopen(request, timeout=15) as response:
        if response.status != 201:
            raise RuntimeError(f"request {index}: unexpected status {response.status}")
        result = json.load(response)
    return result["id"], (time.monotonic() - start) * 1000


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=200)
    parser.add_argument("--workers", type=int, default=20)
    parser.add_argument("--api", default="http://localhost:8080")
    args = parser.parse_args()
    if not 1 <= args.count <= 10000 or not 1 <= args.workers <= 100:
        parser.error("count must be 1..10000 and workers 1..100")
    key = os.environ["SAGA_API_KEY"]
    start = time.monotonic()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        results = list(pool.map(lambda i: create(i, args.api, key), range(args.count)))
    seconds = time.monotonic() - start
    ids, latencies = zip(*results)
    if len(set(ids)) != args.count:
        raise AssertionError("duplicate saga IDs")
    ordered = sorted(latencies)
    print(json.dumps({"count": args.count, "workers": args.workers,
                      "accepted_per_second": round(args.count / seconds, 1),
                      "http_p50_ms": round(statistics.median(ordered), 1),
                      "http_p95_ms": round(ordered[max(0, int(.95 * len(ordered)) - 1)], 1)}))


if __name__ == "__main__":
    main()
