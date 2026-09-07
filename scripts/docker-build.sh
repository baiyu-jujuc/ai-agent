#!/bin/bash
cd "/mnt/d/AI Agent (test)"
echo "=== Building AI Agent Docker image ==="
docker build --network=host -t ai-agent . 2>&1
echo "=== Build exit code: $? ==="
