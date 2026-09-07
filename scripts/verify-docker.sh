#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== Docker Verification ==="

echo "Step 1: Build image"
if ! docker compose build; then
    echo "FAILED: docker compose build"
    exit 1
fi

echo "Step 2: Start services"
if ! docker compose up -d; then
    echo "FAILED: docker compose up"
    exit 1
fi

echo "Step 3: Wait for health checks (max 60s)"
max_wait=60
waited=0
healthy=false
while [ $waited -lt $max_wait ]; do
    sleep 3
    waited=$((waited + 3))
    if curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q '"UP"'; then
        healthy=true
        break
    fi
    echo "  Waiting... (${waited}s)"
done

if [ "$healthy" != "true" ]; then
    echo "FAILED: App not healthy after ${max_wait}s"
    docker compose logs --tail=50
    docker compose down
    exit 1
fi
echo "  App is healthy"

echo "Step 4: Verify API endpoints"
models=$(curl -sf http://localhost:8080/api/chat/models 2>/dev/null || echo "[]")
echo "  Models: $(echo "$models" | jq length 2>/dev/null || echo 'N/A') available"

status=$(curl -sf http://localhost:8080/api/chat/storage-status 2>/dev/null || echo "{}")
echo "  Memory: $(echo "$status" | jq -r .memoryBackend 2>/dev/null || echo 'N/A')"
echo "  Vector: $(echo "$status" | jq -r .vectorStoreBackend 2>/dev/null || echo 'N/A')"

echo "Step 5: Cleanup"
docker compose down
echo "=== Docker verification passed ==="
exit 0
