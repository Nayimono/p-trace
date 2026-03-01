#!/bin/bash
# Spring Boot WebFlux 배포 스크립트

AGENT_JAR="/opt/agents/pnones-trace-agent-0.1.0-jar-with-dependencies.jar"
APP_JAR="/opt/apps/myapp.jar"
CONFIG_DIR="/opt/apps/config"

echo "Deploying PTrace Agent to Spring Boot WebFlux..."

# 1. Agent JAR 준비
mkdir -p /opt/agents
cp /path/to/pnones-trace-agent-0.1.0-jar-with-dependencies.jar "$AGENT_JAR"

# 2. 설정 파일
mkdir -p "$CONFIG_DIR"
cat > "${CONFIG_DIR}/pnonestrace.properties" << 'EOF'
trace.enabled=true
http.trace.enabled=true
http.trace.body.enabled=true
sql.trace.enabled=true
sql.result.capture-data=true
log.dir=/var/log/myapp/pnonestrace-logs
mask.patterns=auth_token,api_key,secret,password
EOF

# 3. 애플리케이션 실행
java -javaagent:"${AGENT_JAR}" \
     -Dfile.encoding=UTF-8 \
     -Dspring.config.location="file:${CONFIG_DIR}/" \
     -jar "${APP_JAR}"
