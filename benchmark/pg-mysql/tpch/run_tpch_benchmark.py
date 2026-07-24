#!/usr/bin/env python3
import argparse
import time
import json
import os
import re

# =============================================================================
# TPC-H 基准测试运行器 (直连 PG vs 通过 SDTP 代理)
# =============================================================================

def to_pg_sql(mysql_sql):
    """将 MySQL 日期函数转换为 PostgreSQL 原生语法，以便对照组直连执行。"""
    pg_sql = mysql_sql

    # 处理 DATE_ADD('1998-12-01', INTERVAL -90 DAY)
    def repl_add(m):
        date_str = m.group(1)
        val = m.group(2)
        unit = m.group(3).upper()
        if not unit.endswith('S'):
            unit += 'S'
        return f"CAST('{date_str}' AS TIMESTAMP) + '{val} {unit}'"
    
    pg_sql = re.sub(
        r"DATE_ADD\(\s*'([^']+)'\s*,\s*INTERVAL\s+(-?\d+)\s+(\w+)\s*\)",
        repl_add, pg_sql, flags=re.IGNORECASE
    )

    # 处理 DATE_SUB('1998-12-01', INTERVAL 90 DAY)
    def repl_sub(m):
        date_str = m.group(1)
        val = m.group(2)
        unit = m.group(3).upper()
        if val.startswith('-'):
            val = val[1:]
        else:
            val = '-' + val
        if not unit.endswith('S'):
            unit += 'S'
        return f"CAST('{date_str}' AS TIMESTAMP) + '{val} {unit}'"
        
    pg_sql = re.sub(
        r"DATE_SUB\(\s*'([^']+)'\s*,\s*INTERVAL\s+(-?\d+)\s+(\w+)\s*\)",
        repl_sub, pg_sql, flags=re.IGNORECASE
    )
    
    return pg_sql

def run_query(conn, sql, mode):
    cursor = conn.cursor()
    # 为 PG 显式设定 schema 搜索路径
    if mode == "baseline-pg":
        cursor.execute("SET search_path TO tpch")
    
    t0 = time.time()
    cursor.execute(sql)
    rows = cursor.fetchall()
    elapsed = time.time() - t0
    cursor.close()
    
    # 格式化数据结果用于比对
    formatted_rows = []
    for row in rows:
        formatted_row = []
        for val in row:
            if isinstance(val, (int, float)):
                # 浮点数四舍五入保留两位，防止浮点计算精度细微差异误报
                formatted_row.append(round(float(val), 2))
            else:
                formatted_row.append(str(val))
        formatted_rows.append(tuple(formatted_row))
        
    return elapsed, formatted_rows

def main():
    parser = argparse.ArgumentParser(description='TPC-H Benchmark Runner')
    parser.add_argument('--mode', required=True, choices=['baseline-pg', 'sdtp-mysql'])
    parser.add_argument('--host', default='localhost')
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--password', required=True)
    parser.add_argument('--db', required=True)
    parser.add_argument('--queries-dir', default='benchmark/tpch/queries')
    parser.add_argument('--output', required=True, help='输出的 JSON 文件路径')
    args = parser.parse_args()

    results = {}
    
    # 1. 建立连接
    if args.mode == 'baseline-pg':
        import psycopg2
        print(f"正在建立 PostgreSQL 连接: {args.host}:{args.port}/{args.db} ...")
        conn = psycopg2.connect(
            host=args.host, port=args.port,
            user=args.user, password=args.password, database=args.db
        )
    else:
        import pymysql
        print(f"正在建立 SDTP (MySQL 协议) 连接: {args.host}:{args.port}/{args.db} ...")
        conn = pymysql.connect(
            host=args.host, port=args.port,
            user=args.user, password=args.password, database=args.db
        )
        
    try:
        # 2. 依次执行 22 个查询
        for q in range(1, 23):
            q_file = os.path.join(args.queries_dir, f"q{q:02d}.sql")
            if not os.path.exists(q_file):
                print(f"警告: 找不到查询文件 {q_file}，跳过。")
                continue
                
            raw_sql = open(q_file, 'r', encoding='utf-8').read()
            
            # 直连 PG 对照组需在内存中转换为 PG SQL 方言
            if args.mode == 'baseline-pg':
                exec_sql = to_pg_sql(raw_sql)
            else:
                exec_sql = raw_sql
                
            print(f"正在运行 Q{q:02d} ...")
            try:
                elapsed, rows = run_query(conn, exec_sql, args.mode)
                print(f"  Q{q:02d} 成功: 耗时 {elapsed:.3f}s, 返回 {len(rows)} 行")
                results[f"Q{q:02d}"] = {
                    "status": "SUCCESS",
                    "elapsed": elapsed,
                    "row_count": len(rows),
                    "data": rows
                }
            except Exception as e:
                print(f"  ❌ Q{q:02d} 失败: {str(e)}")
                results[f"Q{q:02d}"] = {
                    "status": "FAILED",
                    "error": str(e),
                    "elapsed": 0.0,
                    "row_count": 0,
                    "data": []
                }
    finally:
        conn.close()
        
    # 3. 写入报告文件
    with open(args.output, 'w', encoding='utf-8') as f:
        json.dump(results, f, ensure_ascii=False, indent=2)
    print(f"测试完成，报告已写入 {args.output}")

if __name__ == '__main__':
    main()
