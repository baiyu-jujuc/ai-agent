#!/usr/bin/env bash
# 在容器里跑语义缓存的 Qdrant 运行时集成测试。
#
# 为什么要在容器里跑（而不是在 Windows 上直接 mvn test）：
#   本机的 Windows 访问不到 WSL 里 docker 映射出来的端口（实测 localhost 与 WSL IP 都不通），
#   所以测试 JVM 必须和 Qdrant 待在同一个网络命名空间里 —— 用 --network host 最省事。
#
# 前置：先在 WSL 里起一个带端口映射的 Qdrant
#   docker run -d --name qdrant-it -p 6333:6333 -p 6334:6334 docker.m.daocloud.io/qdrant/qdrant:latest
#
# 用法（在 WSL 里执行）：bash "/mnt/d/AI Agent (test)/scripts/run-cache-it.sh"
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "== repo: $REPO_DIR"

# WSL 的 VM 会在没有活动会话时休眠，dockerd 跟着停，没有 restart 策略的容器就死了。
# 所以这里自己保证 Qdrant 在跑，而不是要求人工先起好。
if docker ps -a --format '{{.Names}}' | grep -q '^qdrant-it$'; then
  echo "== starting existing qdrant-it"
  docker update --restart unless-stopped qdrant-it >/dev/null
  docker start qdrant-it >/dev/null
else
  echo "== creating qdrant-it"
  docker run -d --name qdrant-it --restart unless-stopped \
    -p 6333:6333 -p 6334:6334 \
    docker.m.daocloud.io/qdrant/qdrant:latest >/dev/null
fi

for i in $(seq 1 20); do
  if curl -sf -m 3 http://localhost:6333/readyz >/dev/null; then
    echo "== qdrant ready"
    break
  fi
  sleep 2
done

# 复用 Windows 侧已经下好的 Maven 仓库（可选）：容器里重新下 Spring AI 全家桶太慢
HOST_M2="${MAVEN_REPO_HOST:-/mnt/d/DevCache/MavenRepo}"
MAVEN_REPO_MOUNT=""
if [ -d "$HOST_M2" ]; then
  echo "== reuse host maven repo: $HOST_M2"
  MAVEN_REPO_MOUNT="-v $HOST_M2:/root/.m2/repository"
fi

docker run --rm --network host \
  -v "$REPO_DIR":/app \
  -w /app \
  ${MAVEN_REPO_MOUNT:-} \
  maven:3.9-eclipse-temurin-21 \
  mvn -B -s scripts/maven-aliyun-settings.xml \
      test -Dqdrant.it=true -Dtest=SemanticCacheQdrantTest -DfailIfNoTests=false
