#!/bin/bash
# Jetty 배포 스크립트

JETTY_HOME="/opt/jetty"
AGENT_JAR="/opt/agents/pnones-trace-agent-0.1.0-jar-with-dependencies.jar"

echo "Deploying PTrace Agent to Jetty..."

# 1. Agent JAR 준비
mkdir -p /opt/agents
cp /path/to/pnones-trace-agent-0.1.0-jar-with-dependencies.jar "$AGENT_JAR"

# 2. jetty.sh 수정
cat > "${JETTY_HOME}/bin/jetty.sh.patch" << EOF
# Add before start_jetty line:
export JAVA_OPTIONS="-javaagent:${AGENT_JAR} \$JAVA_OPTIONS"
EOF

echo "export JAVA_OPTIONS=\"-javaagent:${AGENT_JAR} \$JAVA_OPTIONS\"" >> "${JETTY_HOME}/bin/jetty.sh"

# 3. 설정 파일
cat > "${JETTY_HOME}/pnonestrace.properties" << 'EOF'
trace.enabled=true
http.trace.enabled=true
sql.trace.enabled=true
log.dir=${JETTY_HOME}/pnonestrace-logs
EOF

mkdir -p "${JETTY_HOME}/pnonestrace-logs"

echo "✓ Jetty configuration complete. Start Jetty to activate agent."
