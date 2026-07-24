#!/usr/bin/env python3
"""
TPC-H 数据生成器（SF 0.01 小数据量）

使用 Python 内置生成器加载 TPC-H 标准数据到 PostgreSQL。

用法:
    python data_gen.py --pg-host localhost --pg-port 5432 --pg-user sdtpu --pg-password pg_password --pg-db mydb
    python data_gen.py --pg-host localhost --pg-port 5432 --pg-user sdtpu --pg-password pg_password --pg-db mydb --scale 0.01 --schema tpch
"""

import argparse
import csv
import io
import random
import string
from datetime import date, timedelta

SCALE_DEFAULT = 0.01

# ==============================================================
# TPC-H 标准数据生成（Python 实现，SF 0.01 级别足够）
# ==============================================================

class TpchDataGenerator:
    """TPC-H 数据生成器，按 SF 生成标准分布的数据。"""

    # TPC-H 规格中 region 固定为 5 行
    REGIONS = [
        (0, "AFRICA", "lar deposits. blithely final packages cajo"),
        (1, "AMERICA", "hs use ironic, even requests. s"),
        (2, "ASIA", "ges. thinly even pinto beans ca"),
        (3, "EUROPE", "ly final courts cajole furiously final excuse"),
        (4, "MIDDLE EAST", "uickly special accounts cajole carefully blithely close requests."),
    ]

    # TPC-H 规格中 nation 固定为 25 行（简化版取前 10 个主要国家）
    NATIONS = [
        (0, "ALGERIA", 0, " haggle. carefully final deposits detect slyly agai"),
        (1, "ARGENTINA", 1, "al ideas use. furiously thin notornis"),
        (2, "BRAZIL", 1, "y final packages cajole furiously"),
        (3, "CANADA", 1, "eas. furiously final instructions"),
        (4, "CHINA", 2, "cial gifts. quickly final requests"),
        (5, "FRANCE", 3, "refully final requests. regular, iron"),
        (6, "GERMANY", 3, "l platelets. regular accounts"),
        (7, "INDIA", 2, "nic dependencies"),
        (8, "INDONESIA", 2, " pending excuses haggle"),
        (9, "IRAN", 4, "nt dependencies."),
        (10, "IRAQ", 4, "slyly regular dependencies."),
        (11, "JAPAN", 2, "ular accounts boost"),
        (12, "JORDAN", 4, "ackages cajole furiously"),
        (13, "KENYA", 0, " pending excuses integrate furiously"),
        (14, "MOROCCO", 0, "rns. blithely bold courts among the carefully regular"),
        (15, "PERU", 1, "press. carefully final instructions"),
        (16, "ROMANIA", 3, "ular packages. carefully"),
        (17, "SAUDI ARABIA", 4, "ts. furiously silent requests"),
        (18, "UNITED KINGDOM", 3, "eans boost. carefully even instructions"),
        (19, "UNITED STATES", 1, "slyly express pinto beans"),
        (20, "RUSSIA", 3, "ular accounts about the furiously"),
        (21, "VIETNAM", 2, "hely enticingly express accounts"),
        (22, "SOUTH KOREA", 2, "ackages cajole"),
        (23, "EGYPT", 0, "n accounts. requests integrate"),
        (24, "NIGERIA", 0, "deposits. fluffily ironic depos"),
    ]

    def __init__(self, scale_factor: float = SCALE_DEFAULT):
        self.sf = scale_factor
        # 基础行数（SF=1 时）
        self.base_rows = {
            'region': 5,
            'nation': 25,
            'supplier': 10000,
            'part': 200000,
            'partsupp': 800000,
            'customer': 150000,
            'orders': 1500000,
            'lineitem': 6000000,
        }

    def _scale(self, name: str) -> int:
        return max(int(self.base_rows[name] * self.sf), 1)

    def _rand_str(self, length: int, rng: random.Random) -> str:
        chars = string.ascii_letters + string.digits + ' '
        return ''.join(rng.choice(chars) for _ in range(length))

    def _rand_phone(self, rng: random.Random) -> str:
        return f"{rng.randint(10, 99)}-{rng.randint(100, 999)}-{rng.randint(100, 999)}-{rng.randint(1000, 9999)}"

    def _rand_date(self, start: date, end: date, rng: random.Random) -> date:
        days = (end - start).days
        return start + timedelta(days=rng.randint(0, days))

    def generate_region(self):
        return self.REGIONS

    def generate_nation(self):
        return self.NATIONS

    def generate_supplier(self):
        rows = []
        rng = random.Random(42)
        n = self._scale('supplier')
        for i in range(1, n + 1):
            nation = rng.choice(self.NATIONS)[0]
            rows.append((
                i,
                f"Supplier#{i:09d}",
                self._rand_str(25, rng),
                nation,
                self._rand_phone(rng),
                round(rng.uniform(-1000, 10000), 2),
                self._rand_str(100, rng),
            ))
        return rows

    def generate_part(self):
        rows = []
        rng = random.Random(43)
        n = self._scale('part')
        colors = ['almond', 'antique', 'aquamarine', 'azure', 'beige', 'bisque', 'black',
                  'blanched', 'blue', 'blush', 'brown', 'burlywood', 'burnished', 'chartreuse',
                  'chiffon', 'chocolate', 'cream', 'cyan', 'dark', 'deep', 'dim', 'dodger',
                  'drab', 'firebrick', 'floral', 'forest', 'frosted', 'gainsboro', 'ghost',
                  'goldenrod', 'green', 'grey', 'honeydew', 'hot', 'indian', 'ivory', 'khaki',
                  'lace', 'lavender', 'lawn', 'lemon', 'light', 'lime', 'linen', 'magenta',
                  'maroon', 'medium', 'mint', 'misty', 'moccasin', 'navajo', 'navy', 'olive',
                  'olivedrab', 'orange', 'orchid', 'pale', 'papaya', 'peach', 'peru', 'pink',
                  'plum', 'powder', 'puff', 'purple', 'red', 'rose', 'rosy', 'royal', 'saddle',
                  'salmon', 'sandy', 'seashell', 'sienna', 'sky', 'slate', 'smoke', 'snow',
                  'spring', 'steel', 'tan', 'thistle', 'tomato', 'turquoise', 'violet', 'wheat',
                  'white', 'yellow']
        types1 = ['STANDARD', 'SMALL', 'MEDIUM', 'LARGE', 'ECONOMY', 'PROMO']
        types2 = ['ANODIZED', 'BURNISHED', 'PLATED', 'POLISHED', 'BRUSHED']
        types3 = ['TIN', 'NICKEL', 'BRASS', 'STEEL', 'COPPER']
        containers = ['SMALL BOX', 'SMALL PKG', 'SMALL PACK', 'MED BOX', 'MED PKG', 'MED PACK',
                      'LG BOX', 'LG PKG', 'LG PACK', 'JUMBO BOX', 'JUMBO PKG', 'JUMBO PACK',
                      'WRAP BOX', 'WRAP PKG', 'WRAP PACK', 'JAR', 'DRUM', 'CAN', 'BAG', 'CASE']
        for i in range(1, n + 1):
            color = rng.choice(colors)
            type1 = rng.choice(types1)
            type2 = rng.choice(types2)
            type3 = rng.choice(types3)
            rows.append((
                i,
                f"{color} {type1} {type2}",
                f"Manufacturer#{rng.randint(1, 5)}",
                f"Brand#{rng.randint(11, 55)}",
                f"{type1} {type2} {type3}",
                rng.randint(1, 50),
                rng.choice(containers),
                round(rng.uniform(1, 2000), 2),
                self._rand_str(21, rng),
            ))
        return rows

    def generate_partsupp(self):
        rows = []
        rng = random.Random(44)
        n = self._scale('partsupp')
        n_part = self._scale('part')
        n_supp = self._scale('supplier')
        seen = set()
        while len(rows) < n:
            pk = rng.randint(1, n_part)
            sk = rng.randint(1, n_supp)
            key = (pk, sk)
            if key in seen:
                continue
            seen.add(key)
            rows.append((
                pk, sk,
                rng.randint(1, 9999),
                round(rng.uniform(1, 1000), 2),
                self._rand_str(198, rng),
            ))
        return rows

    def generate_customer(self):
        rows = []
        rng = random.Random(45)
        n = self._scale('customer')
        segments = ['AUTOMOBILE', 'BUILDING', 'FURNITURE', 'MACHINERY', 'HOUSEHOLD']
        for i in range(1, n + 1):
            nation = rng.choice(self.NATIONS)[0]
            rows.append((
                i,
                f"Customer#{i:09d}",
                self._rand_str(25, rng),
                nation,
                self._rand_phone(rng),
                round(rng.uniform(-1000, 10000), 2),
                rng.choice(segments),
                self._rand_str(116, rng),
            ))
        return rows

    def generate_orders(self):
        rows = []
        rng = random.Random(46)
        n = self._scale('orders')
        n_cust = self._scale('customer')
        priorities = ['1-URGENT', '2-HIGH', '3-MEDIUM', '4-NOT SPECIFIED', '5-LOW']
        statuses = ['F', 'O', 'P']
        start_date = date(1992, 1, 1)
        end_date = date(1998, 12, 31)
        self.order_dates = {}
        for i in range(1, n + 1):
            cust = rng.randint(1, n_cust)
            # 官方 TPC-H 规范：o_custkey % 3 != 0，以确保有 1/3 的客户从来不下任何订单
            while cust % 3 == 0:
                cust = rng.randint(1, n_cust)
            od = self._rand_date(start_date, end_date, rng)
            self.order_dates[i] = od
            rows.append((
                i, cust,
                rng.choice(statuses),
                round(rng.uniform(100, 500000), 2),
                od,
                rng.choice(priorities),
                f"Clerk#{rng.randint(1, 1000):05d}",
                rng.randint(0, 5),
                self._rand_str(78, rng),
            ))
        return rows

    def generate_lineitem(self):
        rows = []
        rng = random.Random(47)
        n = self._scale('lineitem')
        n_orders = self._scale('orders')
        n_part = self._scale('part')
        n_supp = self._scale('supplier')
        ship_instruct = ['DELIVER IN PERSON', 'COLLECT COD', 'NONE', 'TAKE BACK RETURN']
        ship_mode = ['REG AIR', 'AIR', 'RAIL', 'SHIP', 'TRUCK', 'MAIL', 'FOB']
        statuses = ['F', 'O']
        return_flags = ['A', 'N', 'R']
        start_date = date(1992, 1, 1)
        seen = set()
        while len(rows) < n:
            ok = rng.randint(1, n_orders)
            pk = rng.randint(1, n_part)
            sk = rng.randint(1, n_supp)
            ln = rng.randint(1, 7)
            key = (ok, ln)
            if key in seen:
                continue
            seen.add(key)
            qty = rng.randint(1, 50)
            ep = round(qty * rng.uniform(10, 2000), 2)
            disc = round(rng.uniform(0, 0.1), 2)
            tax = round(rng.uniform(0, 0.08), 2)
            
            # 官方 TPC-H 规格：以订单日期 (o_orderdate) 为基准生成发货、承诺与签收日期
            od = getattr(self, 'order_dates', {}).get(ok, start_date)
            sd = od + timedelta(days=rng.randint(1, 121))
            cd = od + timedelta(days=rng.randint(30, 90))
            rd = sd + timedelta(days=rng.randint(1, 30))
            
            rows.append((
                ok, pk, sk, ln, qty, ep, disc, tax,
                rng.choice(return_flags),
                rng.choice(statuses),
                sd, cd, rd,
                rng.choice(ship_instruct),
                rng.choice(ship_mode),
                self._rand_str(43, rng),
            ))
        return rows


def load_csv_to_pg(conn_kwargs, table_name, columns, rows, schema, batch_size=1000):
    """使用 psycopg2 copy_from 将数据批量导入 PostgreSQL。"""
    import psycopg2
    conn = psycopg2.connect(**conn_kwargs)
    try:
        cur = conn.cursor()
        buf = io.StringIO()
        writer = csv.writer(buf)
        for row in rows:
            writer.writerow(row)
        buf.seek(0)
        qualified_name = f"{schema}.{table_name}"
        columns_sql = ', '.join(columns)
        cur.copy_expert(
            f"COPY {qualified_name} ({columns_sql}) FROM STDIN WITH CSV NULL ''",
            buf
        )
        conn.commit()
        print(f"  导入 {qualified_name}: {len(rows)} 行")
    finally:
        conn.close()

def load_data_to_mysql(conn_kwargs, table_name, columns, rows, schema):
    """使用 mysql.connector executemany 将数据批量导入 MySQL。"""
    import mysql.connector
    conn = mysql.connector.connect(**conn_kwargs)
    try:
        cur = conn.cursor()
        if schema:
            cur.execute(f"USE {schema}")
        
        placeholders = ', '.join(['%s'] * len(columns))
        columns_sql = ', '.join(columns)
        sql = f"INSERT INTO {table_name} ({columns_sql}) VALUES ({placeholders})"
        
        cur.executemany(sql, rows)
        conn.commit()
        print(f"  导入 {table_name}: {len(rows)} 行")
    finally:
        conn.close()

def load_tpch_data(load_func, conn_kwargs, scale, schema):
    """生成并加载所有 TPC-H 表。"""
    gen = TpchDataGenerator(scale)
    load_specs = [
        ('region', ['r_regionkey', 'r_name', 'r_comment'], gen.generate_region),
        ('nation', ['n_nationkey', 'n_name', 'n_regionkey', 'n_comment'], gen.generate_nation),
        ('supplier', ['s_suppkey', 's_name', 's_address', 's_nationkey', 's_phone',
                       's_acctbal', 's_comment'], gen.generate_supplier),
        ('part', ['p_partkey', 'p_name', 'p_mfgr', 'p_brand', 'p_type', 'p_size',
                   'p_container', 'p_retailprice', 'p_comment'], gen.generate_part),
        ('partsupp', ['ps_partkey', 'ps_suppkey', 'ps_availqty', 'ps_supplycost', 'ps_comment'],
                      gen.generate_partsupp),
        ('customer', ['c_custkey', 'c_name', 'c_address', 'c_nationkey', 'c_phone',
                       'c_acctbal', 'c_mktsegment', 'c_comment'], gen.generate_customer),
        ('orders', ['o_orderkey', 'o_custkey', 'o_orderstatus', 'o_totalprice',
                     'o_orderdate', 'o_orderpriority', 'o_clerk', 'o_shippriority', 'o_comment'],
                    gen.generate_orders),
        ('lineitem', ['l_orderkey', 'l_partkey', 'l_suppkey', 'l_linenumber', 'l_quantity',
                       'l_extendedprice', 'l_discount', 'l_tax', 'l_returnflag', 'l_linestatus',
                       'l_shipdate', 'l_commitdate', 'l_receiptdate', 'l_shipinstruct',
                       'l_shipmode', 'l_comment'], gen.generate_lineitem),
    ]
    for table, cols, gen_func in load_specs:
        rows = gen_func()
        load_func(conn_kwargs, table, cols, rows, schema)


def main():
    parser = argparse.ArgumentParser(description='TPC-H 数据生成器')
    parser.add_argument('--db-type', default='postgres', choices=['postgres', 'mysql'])
    # PG args
    parser.add_argument('--pg-host', default='localhost')
    parser.add_argument('--pg-port', type=int, default=5432)
    parser.add_argument('--pg-user', default='sdtpu')
    parser.add_argument('--pg-password', default='pg_password')
    parser.add_argument('--pg-db', default='mydb')
    # MySQL args
    parser.add_argument('--mysql-host', default='localhost')
    parser.add_argument('--mysql-port', type=int, default=3306)
    parser.add_argument('--mysql-user', default='root')
    parser.add_argument('--mysql-password', default='')
    parser.add_argument('--mysql-db', default='tpch')
    
    parser.add_argument('--scale', type=float, default=SCALE_DEFAULT,
                        help='Scale factor (default: 0.01)')
    parser.add_argument('--schema', default='tpch',
                        help='数据库 schema/database 名 (默认: tpch)')
    args = parser.parse_args()

    if args.db_type == 'postgres':
        import psycopg2
        conn_kwargs = {
            'host': args.pg_host,
            'port': args.pg_port,
            'user': args.pg_user,
            'password': args.pg_password,
            'dbname': args.pg_db,
        }
        load_func = load_csv_to_pg
        connector = psycopg2
        print(f"  目标: postgresql://{args.pg_host}:{args.pg_port}/{args.pg_db}")
    else: # mysql
        import mysql.connector
        conn_kwargs = {
            'host': args.mysql_host,
            'port': args.mysql_port,
            'user': args.mysql_user,
            'password': args.mysql_password,
            'database': args.mysql_db,
        }
        load_func = load_data_to_mysql
        connector = mysql.connector
        print(f"  目标: mysql://{args.mysql_host}:{args.mysql_port}/{args.mysql_db}")

    print(f"TPC-H 数据生成 (SF={args.scale}, schema={args.schema})")

    # 清空所有 TPC-H 表
    conn_clear = connector.connect(**conn_kwargs)
    cur_clear = conn_clear.cursor()
    if args.db_type == 'mysql':
        cur_clear.execute(f"USE {args.schema}")
        cur_clear.execute("SET FOREIGN_KEY_CHECKS = 0")
    
    tables_to_clear = ['lineitem', 'orders', 'customer', 'partsupp', 'part', 'supplier', 'nation', 'region']
    for t in tables_to_clear:
        if args.db_type == 'postgres':
            cur_clear.execute(f"DELETE FROM {args.schema}.{t}")
        else:
            cur_clear.execute(f"TRUNCATE TABLE {t}")
    
    if args.db_type == 'mysql':
        cur_clear.execute("SET FOREIGN_KEY_CHECKS = 1")
    conn_clear.commit()
    conn_clear.close()

    # 使用 Python 生成器加载数据
    print("  使用 Python 生成器加载数据...")
    load_tpch_data(load_func, conn_kwargs, args.scale, args.schema)
    print("  Python 生成器加载完成")

    # 验证数据量
    conn = connector.connect(**conn_kwargs)
    cur = conn.cursor()
    if args.db_type == 'mysql':
        cur.execute(f"USE {args.schema}")
    
    for table in tables_to_clear:
        if args.db_type == 'postgres':
            cur.execute(f"SELECT COUNT(*) FROM {args.schema}.{table}")
        else:
            cur.execute(f"SELECT COUNT(*) FROM {table}")
        cnt = cur.fetchone()[0]
        print(f"  {args.schema}.{table}: {cnt} 行")
    conn.close()


if __name__ == '__main__':
    main()
