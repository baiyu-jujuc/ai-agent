#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

echo "=== Local Verification ==="
echo "Step 1: Clean build + test (no Docker, no API key)"
if ! mvn clean verify --no-transfer-progress; then
    echo "FAILED: mvn clean verify"
    exit 1
fi

echo "Step 2: Verify artifacts"
JAR=$(ls target/*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then
    echo "FAILED: No JAR found in target/"
    exit 1
fi
JAR_SIZE=$(du -k "$JAR" | cut -f1)
echo "  JAR: $(basename "$JAR") (${JAR_SIZE} KB)"

echo "=== Local verification passed ==="
exit 0
