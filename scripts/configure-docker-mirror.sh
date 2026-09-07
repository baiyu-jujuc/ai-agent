#!/bin/bash
# Configure Docker daemon with Chinese registry mirrors
pkill dockerd 2>/dev/null
sleep 2

mkdir -p /etc/docker
cat > /etc/docker/daemon.json << 'CONF'
{
  "registry-mirrors": [
    "https://docker.1panel.live",
    "https://docker.m.daocloud.io",
    "https://docker.mirrors.ustc.edu.cn"
  ],
  "log-driver": "json-file",
  "log-opts": {
    "max-size": "10m",
    "max-file": "3"
  }
}
CONF

echo "=== Docker mirror configured ==="
cat /etc/docker/daemon.json

# Restart dockerd
nohup dockerd --host=unix:///var/run/docker.sock > /dev/null 2>&1 &
sleep 4

echo "=== Testing pull ==="
docker pull hello-world 2>&1 | tail -5
echo "=== DONE ==="
