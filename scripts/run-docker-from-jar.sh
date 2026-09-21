#!/usr/bin/env bash
# 用本机已构建好的 jar 打一个运行时镜像，并启动 compose 里的 app 服务（不重新构建）。
#
# 为什么需要这个脚本：
#   本机的 docker 容器**没有外网出口**（实测容器内连 maven.aliyun.com 直接超时），
#   所以 Dockerfile 里 "在容器里跑 mvn package" 的那条路在本机走不通。
#   改成：Windows 侧 `mvn -B verify` 出 jar → 容器只负责运行，一样能验证"容器化运行"。
#   等容器网络可用时，直接使用 `docker compose up -d --build` 即可（Dockerfile 已配阿里云镜像）。
#
# 用法（在 WSL 里执行）：
#   bash "/mnt/d/AI Agent (test)/scripts/run-docker-from-jar.sh"
set -euo pipefail

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$REPO_DIR/target/ai-agent-0.0.1-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "找不到 $JAR，请先在 Windows 侧执行：mvn -B verify --no-transfer-progress"
  exit 1
fi

echo "== 用 $JAR 构建运行时镜像 aiagenttest-app:latest"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT
cp "$JAR" "$BUILD_DIR/app.jar"
cat > "$BUILD_DIR/Dockerfile" <<'EOF'
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
EOF
docker build -q -t aiagenttest-app:latest "$BUILD_DIR"

echo "== 启动 compose app 服务（不重新 build）"
cd "$REPO_DIR"
docker compose up -d --no-build app

echo "== 等待健康检查"
for i in $(seq 1 30); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' aiagenttest-app-1 2>/dev/null || echo unknown)"
  echo "   [$i] app = $status"
  [ "$status" = "healthy" ] && break
  sleep 5
done

docker compose ps --format 'table {{.Name}}\t{{.Service}}\t{{.Status}}'
