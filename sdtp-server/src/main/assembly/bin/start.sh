#!/bin/bash
# SDT Proxy 启动脚本
# 用法:
#   ./start.sh           前台运行（Docker 默认）
#   ./start.sh start     后台运行
#   ./start.sh start-fg  前台运行
#   ./start.sh stop      停止
#   ./start.sh restart   重启

set -e

APP_HOME="$(cd "$(dirname "$0")/.." && pwd)"
LIB_DIR="$APP_HOME/lib"
JDBC_DIR="$APP_HOME/jdbc"
CONFIG_DIR="$APP_HOME/config"
LOG_DIR="$APP_HOME/logs"
PID_FILE="$APP_HOME/sdtp.pid"

# JVM 参数 -O com.translator.proxy.server.ProxyBootstrap
JAVA_OPTS="${JAVA_OPTS:- -Xms256m -Xmx512m -XX:+UseG1GC}"
JAVA_OPTS="$JAVA_OPTS -Dproxy.config=${PROXY_CONFIG:-$CONFIG_DIR/proxy-config.yml}"
JAVA_OPTS="$JAVA_OPTS -Dlog.dir=${LOG_DIR:-$APP_HOME/logs}"

CLASS_PATH="$LIB_DIR/*:$JDBC_DIR/*"
MAIN_CLASS="com.translator.proxy.server.ProxyBootstrap"

start_foreground() {
    mkdir -p "$LOG_DIR"
    echo "Starting SDT Proxy (foreground)..."
    echo "  Config: $CONFIG_DIR/proxy-config.yml"
    echo "  Classpath: $CLASS_PATH"
    exec java $JAVA_OPTS -cp "$CLASS_PATH" "$MAIN_CLASS"
}

start_background() {
    mkdir -p "$LOG_DIR"
    echo "Starting SDT Proxy (background)..."
    nohup java $JAVA_OPTS -cp "$CLASS_PATH" "$MAIN_CLASS" \
        > "$LOG_DIR/sdtp.log" 2>&1 &
    echo $! > "$PID_FILE"
    echo "PID: $(cat $PID_FILE)"
}

stop() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        echo "Stopping SDT Proxy (PID: $PID)..."
        kill "$PID" 2>/dev/null || true
        rm -f "$PID_FILE"
        echo "Stopped."
    else
        echo "PID file not found."
    fi
}

case "${1:-start-fg}" in
    start)
        start_background
        ;;
    start-fg)
        start_foreground
        ;;
    stop)
        stop
        ;;
    restart)
        stop
        sleep 2
        start_background
        ;;
    *)
        echo "Usage: $0 {start|start-fg|stop|restart}"
        exit 1
        ;;
esac
