#!/bin/bash
# Tomcat 9 (javax.servlet) 배포 스크립트

TOMCAT_HOME="/opt/tomcat9"
AGENT_JAR="/opt/agents/pnones-trace-agent-0.1.0-jar-with-dependencies.jar"
CONFIG_DIR="${TOMCAT_HOME}/config"

# 1. Agent JAR 준비
echo "Preparing agent JAR..."
mkdir -p /opt/agents
cp /path/to/pnones-trace-agent-0.1.0-jar-with-dependencies.jar "$AGENT_JAR"

# 2. 설정 파일 준비
echo "Creating configuration..."
cat > "${TOMCAT_HOME}/pnonestrace.properties" << 'EOF'
trace.enabled=true
http.trace.enabled=true
http.trace.body.enabled=true
http.trace.body.max-size=10240
sql.trace.enabled=true
sql.result.capture-data=true
sql.result.max-rows=100
log.dir=${TOMCAT_HOME}/pnonestrace-logs
mask.patterns=auth_token,passwordToken,apiKey,secret
mask.replacement=***
EOF

# 3. setenv.sh 수정
echo "Updating Tomcat environment..."
cat >> "${TOMCAT_HOME}/bin/setenv.sh" << EOF
export JAVA_OPTS="\$JAVA_OPTS -javaagent:${AGENT_JAR}"
EOF

chmod +x "${TOMCAT_HOME}/bin/setenv.sh"

# 4. 로그 디렉토리 생성
echo "Creating log directory..."
mkdir -p "${TOMCAT_HOME}/pnonestrace-logs"
chmod 755 "${TOMCAT_HOME}/pnonestrace-logs"

# 5. 재시작
echo "Restarting Tomcat..."
"${TOMCAT_HOME}/bin/catalina.sh" stop
sleep 3
"${TOMCAT_HOME}/bin/catalina.sh" start

# 6. 검증
echo "Waiting for Tomcat startup..."
sleep 5

if [ -f "${TOMCAT_HOME}/pnonestrace-logs/agent-started.txt" ]; then
    echo "✓ Agent loaded successfully!"
    cat "${TOMCAT_HOME}/pnonestrace-logs/agent-started.txt"
else
    echo "✗ Agent failed to load. Check logs:"
    tail -50 "${TOMCAT_HOME}/logs/catalina.out"
fi
