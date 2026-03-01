#!/bin/bash
# Vert.x 배포 스크립트

AGENT_JAR="/opt/agents/pnones-trace-agent-0.1.0-jar-with-dependencies.jar"
APP_JAR="/opt/apps/myvertx-app.jar"
CONFIG_DIR="/opt/apps/config"

echo "Deploying PTrace Agent to Vert.x..."

# 1. Agent JAR 준비
mkdir -p /opt/agents
cp /path/to/pnones-trace-agent-0.1.0-jar-with-dependencies.jar "$AGENT_JAR"

# 2. 설정 파일
mkdir -p "$CONFIG_DIR"
cat > "${CONFIG_DIR}/pnonestrace.properties" << 'EOF'
trace.enabled=true
http.trace.enabled=true
sql.trace.enabled=true
log.dir=/var/log/myapp/pnonestrace-logs
EOF

# 3. Vert.x 애플리케이션 실행
java -javaagent:"${AGENT_JAR}" \
     -cp "${APP_JAR}" \
     -Dvertx.config.path="${CONFIG_DIR}/application.json" \
     io.vertx.core.Launcher run MyMainVerticle
