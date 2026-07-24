#!/bin/bash
# =============================================================================
# run-docker-test.sh — 通过 Docker 容器执行 SDTP 客户端集成测试
#
# 支持模式 (mysql-pg 分类):
#   mysql-client-5  : Docker mysql:5.7 原生客户端
#   mysql-client-8  : Docker mysql:8.0 原生客户端
#   mysql-jdbc-5    : Docker mysql5-sqlline (JDBC 5.1.49)
#   mysql-jdbc-8    : Docker mysql8-sqlline (JDBC 8.0.x)
#   python-mysql    : Docker python-mysql (mysql-connector-python)
#
# 支持模式 (pg-mysql 分类):
#   pg-client       : Docker postgres:15-alpine 原生 psql 客户端
#   pg-jdbc         : Docker pg-sqlline (PostgreSQL JDBC)
#   python-pg       : Docker python-pg (psycopg2)
#
# 用法:
#   bash run-docker-test.sh \
#     --mode mysql-client-5 \
#     --host host.docker.internal --port 3306 \
#     --user root --password proxy_password \
#     --database mydb \
#     --benchmark all \
#     --output-dir ../reports
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ========================= 默认参数 =========================
MODE=""
HOST="host.docker.internal"
PORT=3306
USER="root"
PASSWORD="proxy_password"
DATABASE="mydb"
BENCHMARK="all"
TPCH_QUERIES=""
TPCC_QUERIES=""
OUTPUT_DIR="."
TIMEOUT=60
ADD_HOST="true"
TPCH_DATABASE="tpch"
TPCC_DATABASE="tpcc"

# ========================= 帮助信息 =========================
usage() {
    cat << 'EOF'
用法: run-docker-test.sh [选项]

选项:
  --mode MODE              测试模式（必填）
                             mysql-pg: mysql-client-5, mysql-client-8, mysql-jdbc-5, mysql-jdbc-8, python-mysql
                             pg-mysql: pg-client, pg-jdbc, python-pg
  --host HOST              目标主机（默认: host.docker.internal）
  --port PORT              目标端口（默认: 3306）
  --user USER              用户名（默认: root）
  --password PASSWORD      密码（默认: proxy_password）
  --database DB            数据库名（默认: mydb）
  --benchmark TYPE         测试集: tpch / tpcc / all（默认: all）
  --tpch-queries PATH     TPC-H 查询目录路径（默认自动检测）
  --tpcc-queries PATH     TPC-C 查询目录路径（默认自动检测）
  --output-dir DIR         报告输出目录（默认: .）
  --timeout SEC            单条查询超时秒数（默认: 60）
  --tpch-database DB      TPC-H 测试使用的 database（默认: tpch）
  --tpcc-database DB      TPC-C 测试使用的 database（默认: tpcc）
  --no-add-host            禁用 --add-host（Docker Desktop 不需要）
  --help                   显示帮助信息
EOF
    exit 0
}

# ========================= 参数解析 =========================
while [[ $# -gt 0 ]]; do
    case "$1" in
        --mode)          MODE="$2"; shift 2 ;;
        --host)          HOST="$2"; shift 2 ;;
        --port)          PORT="$2"; shift 2 ;;
        --user)          USER="$2"; shift 2 ;;
        --password)      PASSWORD="$2"; shift 2 ;;
        --database)      DATABASE="$2"; shift 2 ;;
        --benchmark)     BENCHMARK="$2"; shift 2 ;;
        --tpch-queries)  TPCH_QUERIES="$2"; shift 2 ;;
        --tpcc-queries)  TPCC_QUERIES="$2"; shift 2 ;;
        --output-dir)    OUTPUT_DIR="$2"; shift 2 ;;
        --timeout)       TIMEOUT="$2"; shift 2 ;;
        --tpch-database) TPCH_DATABASE="$2"; shift 2 ;;
        --tpcc-database) TPCC_DATABASE="$2"; shift 2 ;;
        --no-add-host)   ADD_HOST="false"; shift ;;
        --help)          usage ;;
        *) echo "未知参数: $1"; usage ;;
    esac
done

# ========================= 校验参数 =========================
if [[ -z "$MODE" ]]; then
    echo "错误: 必须指定 --mode"
    usage
fi

case "$MODE" in
    mysql-client-5) DOCKER_IMAGE="mysql:5.7" ;;
    mysql-client-8) DOCKER_IMAGE="mysql:8.0" ;;
    mysql-jdbc-5)   DOCKER_IMAGE="mysql5-sqlline:latest" ;;
    mysql-jdbc-8)   DOCKER_IMAGE="mysql8-sqlline:latest" ;;
    python-mysql)   DOCKER_IMAGE="python-mysql:latest" ;;
    pg-client)      DOCKER_IMAGE="postgres:15-alpine" ;;
    pg-jdbc)        DOCKER_IMAGE="pg-sqlline:latest" ;;
    python-pg)      DOCKER_IMAGE="python-pg:latest" ;;
    *)
        echo "错误: 不支持的 mode: $MODE"
        echo "  支持 (mysql-pg): mysql-client-5, mysql-client-8, mysql-jdbc-5, mysql-jdbc-8, python-mysql"
        echo "  支持 (pg-mysql): pg-client, pg-jdbc, python-pg"
        exit 1
        ;;
esac

# 根据 MODE 自动确定类别目录及查询目录
if [[ "$MODE" == pg-* || "$MODE" == python-pg ]]; then
    CATEGORY_DIR="$SCRIPT_DIR/pg-mysql"
else
    CATEGORY_DIR="$SCRIPT_DIR/mysql-pg"
fi

TPCH_QUERIES="${TPCH_QUERIES:-$CATEGORY_DIR/tpch/queries}"
TPCC_QUERIES="${TPCC_QUERIES:-$CATEGORY_DIR/tpcc/queries}"

# 确保输出目录存在
mkdir -p "$OUTPUT_DIR"

# ========================= Docker 参数构建 =========================
DOCKER_BASE_FLAGS="--rm"
if [[ "$ADD_HOST" == "true" ]]; then
    DOCKER_BASE_FLAGS="$DOCKER_BASE_FLAGS --add-host host.docker.internal:host-gateway"
fi

# ========================= 读取查询 =========================
read_queries_from_dir() {
    local dir="$1"
    local -n result_ref="$2"

    if [[ ! -d "$dir" ]]; then
        echo "  警告: 查询目录不存在: $dir"
        return 1
    fi

    result_ref=()

    local files
    mapfile -t files < <(find "$dir" -maxdepth 1 -name '*.sql' -type f | sort)

    for f in "${files[@]}"; do
        local name
        name="$(basename "$f" .sql)"
        local sql
        sql="$(cat "$f")"
        if [[ -z "${sql// }" ]]; then
            continue
        fi
        result_ref+=("${name}|${sql}")
    done
}

# ========================= 执行 SQL =========================
_EXEC_RC=0
_EXEC_OUTPUT=""
_EXEC_DURATION=0

execute_sql() {
    local sql="$1"
    local db="${2:-$DATABASE}"
    local start_time end_time

    _EXEC_RC=0
    _EXEC_OUTPUT=""
    _EXEC_DURATION=0

    start_time="$(date +%s.%N)"

    case "$MODE" in
        mysql-client-*)
            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS \
                    "$DOCKER_IMAGE" \
                    mysql -h "$HOST" -P "$PORT" --protocol TCP \
                    -u "$USER" -p"$PASSWORD" "$db" \
                    --connect-timeout=10 \
                    -e "$sql" 2>&1
            )" || _EXEC_RC=$?
            ;;
        pg-client)
            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS \
                    -e PGPASSWORD="$PASSWORD" \
                    "$DOCKER_IMAGE" \
                    psql -h "$HOST" ""-p """$PORT" \
                    -U "$USER" -d "$db" \
                    -c "$sql" 2>&1
            )" || _EXEC_RC=$?
            ;;
        mysql-jdbc-*)
            local tmpfile
            tmpfile="$(mktemp)"
            printf '%s\n' "$sql" > "$tmpfile"

            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS \
                    -v "$tmpfile:/tmp/q.sql:ro" \
                    "$DOCKER_IMAGE" \
                    -u "jdbc:mysql://$HOST:$PORT/$db?useSSL=false&characterEncoding=utf8" \
                    -n "$USER" -p "$PASSWORD" \
                    -e "!run /tmp/q.sql" 2>&1
            )" || _EXEC_RC=$?

            rm -f "$tmpfile"
            ;;
        pg-jdbc)
            local tmpfile
            tmpfile="$(mktemp)"
            printf '%s\n' "$sql" > "$tmpfile"

            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS \
                    -v "$tmpfile:/tmp/q.sql:ro" \
                    "$DOCKER_IMAGE" \
                    -u "jdbc:postgresql://$HOST:$PORT/$db" \
                    -n "$USER" -p "$PASSWORD" \
                    -e "!run /tmp/q.sql" 2>&1
            )" || _EXEC_RC=$?

            rm -f "$tmpfile"
            ;;
        python-mysql)
            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS --rm \
                    -e MYSQL_HOST="$HOST" \
                    -e MYSQL_PORT="$PORT" \
                    -e MYSQL_USER="$USER" \
                    -e MYSQL_PASSWORD="$PASSWORD" \
                    -e MYSQL_DATABASE="$db" \
                    -e SQL_STATEMENTS="$sql" \
                    "$DOCKER_IMAGE" 2>&1
            )" || _EXEC_RC=$?
            ;;
        python-pg)
            _EXEC_OUTPUT="$(
                docker run $DOCKER_BASE_FLAGS --rm \
                    -e PG_HOST="$HOST" \
                    -e PG_PORT="$PORT" \
                    -e PG_USER="$USER" \
                    -e PG_PASSWORD="$PASSWORD" \
                    -e PG_DATABASE="$db" \
                    -e SQL_STATEMENTS="$sql" \
                    "$DOCKER_IMAGE" 2>&1
            )" || _EXEC_RC=$?
            ;;
    esac

    end_time="$(date +%s.%N)"
    _EXEC_DURATION="$(echo "scale=4; ($end_time - $start_time)" | bc 2>/dev/null || echo "0")"
}

# ========================= 判断执行是否成功 =========================
is_success() {
    local rc="$1"
    local output="$2"

    if [[ "$MODE" == "python-mysql" || "$MODE" == "python-pg" ]]; then
        local success_val
        success_val="$(echo "$output" | jq -r '.success' 2>/dev/null)"
        if [[ "$success_val" == "true" ]]; then
            return 0
        else
            return 1
        fi
    fi

    if [[ "$rc" -ne 0 ]]; then
        return 1
    fi

    if echo "$output" | grep -qE '(?<!INFO |WARN )(ERROR|Exception|Error:|FAILED|psql.*: ERROR:)'; then
        return 1
    fi

    if echo "$output" | grep -qE '^[0-9]+/[0-9]+.*Error'; then
        return 1
    fi

    return 0
}

# ========================= 运行基准测试 =========================
run_benchmark() {
    local benchmark_name="$1"
    local queries_dir="$2"
    local db="$3"

    echo ""
    echo "=========================================="
    echo "  运行 $benchmark_name 基准测试"
    echo "  模式: $MODE"
    echo "  镜像: $DOCKER_IMAGE"
    echo "  目标: $USER@$HOST:$PORT/$db"
    echo "=========================================="

    local queries=()
    read_queries_from_dir "$queries_dir" queries

    if [[ ${#queries[@]} -eq 0 ]]; then
        echo "  警告: 没有找到 SQL 查询文件: $queries_dir"
        return
    fi

    local total=${#queries[@]}
    local success_count=0
    local failed_count=0
    local details="["
    local start_time
    start_time="$(date +%s.%N)"
    local failed_names=()
    local durations=()

    local i=1
    for entry in "${queries[@]}"; do
        local name="${entry%%|*}"
        local sql="${entry#*|}"

        echo ""
        echo "[$i/$total] $name"
        local preview="${sql:0:120}"
        if [[ ${#sql} -gt 120 ]]; then
            preview="${preview}..."
        fi
        echo "  SQL: $preview"

        execute_sql "$sql" "$db"

        local ok
        if is_success "$_EXEC_RC" "$_EXEC_OUTPUT"; then
            ok=true
            success_count=$((success_count + 1))
            echo "  ✅ OK (${_EXEC_DURATION}s)"
        else
            ok=false
            failed_count=$((failed_count + 1))
            failed_names+=("$name")
            local err_msg="${_EXEC_OUTPUT:0:300}"
            echo "  ❌ ERROR (${_EXEC_DURATION}s)"
            echo "  ${err_msg}"
        fi

        durations+=("$_EXEC_DURATION")
        local escaped_sql
        escaped_sql="$(echo "$sql" | jq -R -s '.')"
        local escaped_result
        escaped_result="$(echo "${_EXEC_OUTPUT:0:500}" | jq -R -s '.')"

        if [[ $i -gt 1 ]]; then
            details+=","
        fi
        details+="{\"name\":\"$name\",\"success\":$ok,\"duration\":$_EXEC_DURATION,\"result\":$escaped_result}"

        i=$((i + 1))
    done

    local end_time
    end_time="$(date +%s.%N)"
    local total_time
    total_time="$(echo "scale=2; ($end_time - $start_time)" | bc 2>/dev/null || echo "0")"

    details+="]"

    local avg_time=0 max_time=0 min_time=0
    if [[ ${#durations[@]} -gt 0 ]]; then
        local sum=0
        for d in "${durations[@]}"; do
            sum="$(echo "scale=4; $sum + $d" | bc 2>/dev/null || echo "0")"
        done
        avg_time="$(echo "scale=4; $sum / ${#durations[@]}" | bc 2>/dev/null || echo "0")"

        max_time="${durations[0]}"
        min_time="${durations[0]}"
        for d in "${durations[@]}"; do
            if [[ "$(echo "$d > $max_time" | bc -l 2>/dev/null || echo "0")" == "1" ]]; then
                max_time="$d"
            fi
            if [[ "$(echo "$d < $min_time" | bc -l 2>/dev/null || echo "0")" == "1" ]]; then
                min_time="$d"
            fi
        done
    fi

    local rate=0
    if [[ $total -gt 0 ]]; then
        rate="$(echo "scale=1; $success_count * 100 / $total" | bc 2>/dev/null || echo "0")"
    fi

    local timestamp
    timestamp="$(date -Iseconds)"

    local report
    report="$(cat << JSONEND
{
  "benchmark": "$benchmark_name",
  "mode": "$MODE",
  "docker_image": "$DOCKER_IMAGE",
  "database": "$db",
  "timestamp": "$timestamp",
  "total": $total,
  "success": $success_count,
  "failed": $failed_count,
  "rate": $rate,
  "total_time": $total_time,
  "avg_time": $avg_time,
  "max_time": $max_time,
  "min_time": $min_time,
  "details": $details
}
JSONEND
)"

    local ts
    ts="$(date +%Y%m%d_%H%M%S)"
    local json_path="$OUTPUT_DIR/report_${benchmark_name}_${MODE}_${ts}.json"
    local txt_path="$OUTPUT_DIR/report_${benchmark_name}_${MODE}_${ts}.txt"

    echo "$report" | jq '.' > "$json_path" 2>/dev/null || echo "$report" > "$json_path"
    echo "  JSON 报告: $json_path"

    {
        echo "========================================"
        echo "  SDTP Docker 集成测试报告"
        echo "  测试时间: $timestamp"
        echo "  测试集: $benchmark_name"
        echo "  执行模式: $MODE"
        echo "  Docker 镜像: $DOCKER_IMAGE"
        echo "  目标: $USER@$HOST:$PORT/$db"
        echo "  总 SQL 数: $total"
        echo "  成功: $success_count"
        echo "  失败: $failed_count"
        echo "  成功率: ${rate}%"
        echo "  总耗时: ${total_time}s"
        echo "  平均耗时: ${avg_time}s"
        echo "  最大耗时: ${max_time}s"
        echo "  最小耗时: ${min_time}s"
        echo "========================================"

        if [[ ${#failed_names[@]} -gt 0 ]]; then
            echo ""
            echo "失败查询列表:"
            for fq in "${failed_names[@]}"; do
                echo "  ❌ $fq"
            done
        fi
    } > "$txt_path"
    echo "  文本报告: $txt_path"

    echo ""
    echo "----------------------------------------"
    echo "  $benchmark_name 汇总:"
    echo "    总查询: $total  |  成功: $success_count  |  失败: $failed_count"
    echo "    成功率: ${rate}%  |  总耗时: ${total_time}s  |  平均: ${avg_time}s"
    echo "----------------------------------------"

    BENCH_TOTAL=$total
    BENCH_SUCCESS=$success_count
    BENCH_FAILED=$failed_count
    BENCH_TOTAL_TIME=$total_time
}

# ========================= 主流程 =========================
main() {
    echo "SDTP Docker 集成测试执行器"
    echo "  模式: $MODE"
    echo "  镜像: $DOCKER_IMAGE"
    echo "  目标: $USER@$HOST:$PORT/$DATABASE"
    echo "  测试集: $BENCHMARK"

    if ! docker version --format '{{.Server.Version}}' > /dev/null 2>&1; then
        echo "❌ Docker 不可用，请确保 Docker 已安装并运行"
        exit 1
    fi
    echo "  Docker 版本: $(docker version --format '{{.Server.Version}}')"

    echo ""
    echo "拉取 Docker 镜像: $DOCKER_IMAGE"
    docker pull "$DOCKER_IMAGE" > /dev/null 2>&1 || {
        echo "  ⚠️ 拉取失败，将尝试本地镜像或运行时自动拉取"
    }

    echo ""
    echo "验证连通性..."
    local ping_sql="SELECT 1 AS ping;"
    local ping_ok=0
    local ping_tries=5
    local ping_wait=3
    for ((try=1; try<=ping_tries; try++)); do
        execute_sql "$ping_sql" "$DATABASE"
        if is_success "$_EXEC_RC" "$_EXEC_OUTPUT"; then
            ping_ok=1
            echo "  ✅ SDTP 可达 ($MODE) (第 $try 次尝试)"
            break
        fi
        echo "  ⚠️ 第 $try/$ping_tries 次连通性检查未通过，重试前等待 ${ping_wait}s ..."
        echo "    (诊断) rc=$_EXEC_RC output=${_EXEC_OUTPUT:0:200}"
        if [[ $try -lt $ping_tries ]]; then
            sleep "$ping_wait"
        fi
    done
    if [[ $ping_ok -ne 1 ]]; then
        echo "  ❌ SDTP 不可达（已重试 $ping_tries 次）"
        echo "  ${_EXEC_OUTPUT:0:300}"
        exit 1
    fi

    local grand_total=0 grand_success=0 grand_failed=0 grand_time=0

    if [[ "$BENCHMARK" == "tpch" || "$BENCHMARK" == "all" ]]; then
        run_benchmark "TPC-H" "$TPCH_QUERIES" "$TPCH_DATABASE"
        grand_total=$((grand_total + BENCH_TOTAL))
        grand_success=$((grand_success + BENCH_SUCCESS))
        grand_failed=$((grand_failed + BENCH_FAILED))
        grand_time="$(echo "scale=2; $grand_time + $BENCH_TOTAL_TIME" | bc 2>/dev/null || echo "0")"
    fi

    if [[ "$BENCHMARK" == "tpcc" || "$BENCHMARK" == "all" ]]; then
        run_benchmark "TPC-C" "$TPCC_QUERIES" "$TPCC_DATABASE"
        grand_total=$((grand_total + BENCH_TOTAL))
        grand_success=$((grand_success + BENCH_SUCCESS))
        grand_failed=$((grand_failed + BENCH_FAILED))
        grand_time="$(echo "scale=2; $grand_time + $BENCH_TOTAL_TIME" | bc 2>/dev/null || echo "0")"
    fi

    echo ""
    echo "============================================================"
    echo "  总汇总报告"
    echo "============================================================"
    echo "  模式: $MODE"
    echo "  总 SQL 数: $grand_total"
    echo "  总成功: $grand_success"
    echo "  总失败: $grand_failed"
    if [[ $grand_total -gt 0 ]]; then
        local grand_rate
        grand_rate="$(echo "scale=1; $grand_success * 100 / $grand_total" | bc 2>/dev/null || echo "0")"
        echo "  总成功率: ${grand_rate}%"
    fi
    echo "  总耗时: ${grand_time}s"
    echo "============================================================"

    if [[ $grand_failed -gt 0 ]]; then
        exit 1
    else
        exit 0
    fi
}

main
