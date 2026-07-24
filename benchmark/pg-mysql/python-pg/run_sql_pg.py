#!/usr/bin/env python3
"""
容器内多语句 PostgreSQL SQL 执行器。
通过环境变量接收连接参数和 SQL，逐条执行，输出 JSON 格式结果。

环境变量:
  PG_HOST        — PG 主机（默认: host.docker.internal）
  PG_PORT        — PG 端口（默认: 5432）
  PG_USER        — 用户名（默认: postgres）
  PG_PASSWORD    — 密码（默认: 空）
  PG_DATABASE    — 数据库名（默认: mydb）
  SQL_STATEMENTS — 要执行的 SQL 文本，支持多条语句（以 ; 分隔）

输出 (stdout):
  {"success": true/false, "duration": 0.0234, "error": "..."}
"""

import json
import os
import sys
import time

import psycopg2


def parse_statements(sql_text):
    """将 SQL 文本按 ; 分割为多条语句，过滤空语句和纯注释行。"""
    statements = []
    current = []

    for line in sql_text.split('\n'):
        stripped = line.strip()

        # 跳过空行和纯注释行
        if not stripped:
            continue
        if stripped.startswith('--'):
            continue

        current.append(line)

        # 以 ; 结尾视为一条完整语句
        if stripped.endswith(';'):
            stmt = '\n'.join(current).strip()
            if stmt.endswith(';'):
                stmt = stmt[:-1].strip()
            if stmt:
                statements.append(stmt)
            current = []

    leftover = '\n'.join(current).strip()
    if leftover:
        if leftover.endswith(';'):
            leftover = leftover[:-1].strip()
        if leftover:
            statements.append(leftover)

    return statements


def is_query(stmt):
    """判断语句是否为查询（需要 fetch 结果）。"""
    upper = stmt.upper().strip()
    return (
        upper.startswith('SELECT')
        or upper.startswith('WITH')
        or upper.startswith('SHOW')
        or upper.startswith('EXPLAIN')
    )


def main():
    host = os.environ.get('PG_HOST', 'host.docker.internal')
    port = int(os.environ.get('PG_PORT', '5432'))
    user = os.environ.get('PG_USER', 'postgres')
    password = os.environ.get('PG_PASSWORD', '')
    database = os.environ.get('PG_DATABASE', 'mydb')
    sql_text = os.environ.get('SQL_STATEMENTS', '')

    if not sql_text.strip():
        result = {
            'success': False,
            'duration': 0.0,
            'error': 'SQL_STATEMENTS 为空',
        }
        print(json.dumps(result))
        sys.exit(1)

    statements = parse_statements(sql_text)
    if not statements:
        result = {
            'success': False,
            'duration': 0.0,
            'error': '解析后无有效 SQL 语句',
        }
        print(json.dumps(result))
        sys.exit(1)

    start_time = time.time()
    try:
        conn = psycopg2.connect(
            host=host,
            port=port,
            user=user,
            password=password,
            dbname=database,
            connect_timeout=10,
        )
        conn.autocommit = True
    except Exception as e:
        duration = time.time() - start_time
        result = {
            'success': False,
            'duration': round(duration, 4),
            'error': f'连接失败: {str(e)[:300]}',
        }
        print(json.dumps(result))
        sys.exit(1)

    cursor = conn.cursor()
    error_msg = None

    try:
        for stmt in statements:
            try:
                cursor.execute(stmt)
                if is_query(stmt):
                    cursor.fetchall()
            except Exception as e:
                error_msg = f'执行失败 [{stmt[:80]}]: {str(e)[:200]}'
                break
    finally:
        cursor.close()
        conn.close()

    duration = time.time() - start_time
    result = {
        'success': error_msg is None,
        'duration': round(duration, 4),
        'error': error_msg,
    }
    print(json.dumps(result))

    if error_msg:
        sys.exit(1)
    else:
        sys.exit(0)


if __name__ == '__main__':
    main()
