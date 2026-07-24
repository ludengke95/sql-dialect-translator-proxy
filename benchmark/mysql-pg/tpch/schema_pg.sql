-- TPC-H 表结构（PostgreSQL DDL）
-- 用于 SDTP 基准测试：直连 PostgreSQL 创建，数据通过 SDTP 用 MySQL 方言查询
-- 独立 schema 隔离，避免与 TPC-C 同名表冲突

CREATE SCHEMA IF NOT EXISTS tpch;
SET search_path TO tpch;

-- 区域表
DROP TABLE IF EXISTS region CASCADE;
CREATE TABLE region (
    r_regionkey  INTEGER NOT NULL,
    r_name       CHAR(25) NOT NULL,
    r_comment    VARCHAR(152),
    PRIMARY KEY (r_regionkey)
);

-- 国家表
DROP TABLE IF EXISTS nation CASCADE;
CREATE TABLE nation (
    n_nationkey  INTEGER NOT NULL,
    n_name       CHAR(25) NOT NULL,
    n_regionkey  INTEGER NOT NULL,
    n_comment    VARCHAR(152),
    PRIMARY KEY (n_nationkey),
    FOREIGN KEY (n_regionkey) REFERENCES region(r_regionkey)
);

-- 供应商表
DROP TABLE IF EXISTS supplier CASCADE;
CREATE TABLE supplier (
    s_suppkey   INTEGER NOT NULL,
    s_name      CHAR(25) NOT NULL,
    s_address   VARCHAR(40) NOT NULL,
    s_nationkey INTEGER NOT NULL,
    s_phone     CHAR(15) NOT NULL,
    s_acctbal   NUMERIC(12,2) NOT NULL,
    s_comment   VARCHAR(101) NOT NULL,
    PRIMARY KEY (s_suppkey),
    FOREIGN KEY (s_nationkey) REFERENCES nation(n_nationkey)
);

-- 零件表
DROP TABLE IF EXISTS part CASCADE;
CREATE TABLE part (
    p_partkey     INTEGER NOT NULL,
    p_name        VARCHAR(55) NOT NULL,
    p_mfgr        CHAR(25) NOT NULL,
    p_brand       CHAR(10) NOT NULL,
    p_type        VARCHAR(55) NOT NULL,
    p_size        INTEGER NOT NULL,
    p_container   CHAR(10) NOT NULL,
    p_retailprice NUMERIC(12,2) NOT NULL,
    p_comment     VARCHAR(23) NOT NULL,
    PRIMARY KEY (p_partkey)
);

-- 零件供应商关联表
DROP TABLE IF EXISTS partsupp CASCADE;
CREATE TABLE partsupp (
    ps_partkey    INTEGER NOT NULL,
    ps_suppkey    INTEGER NOT NULL,
    ps_availqty   INTEGER NOT NULL,
    ps_supplycost NUMERIC(12,2) NOT NULL,
    ps_comment    VARCHAR(199) NOT NULL,
    PRIMARY KEY (ps_partkey, ps_suppkey),
    FOREIGN KEY (ps_partkey) REFERENCES part(p_partkey),
    FOREIGN KEY (ps_suppkey) REFERENCES supplier(s_suppkey)
);

-- 客户表
DROP TABLE IF EXISTS customer CASCADE;
CREATE TABLE customer (
    c_custkey    INTEGER NOT NULL,
    c_name       VARCHAR(25) NOT NULL,
    c_address    VARCHAR(40) NOT NULL,
    c_nationkey  INTEGER NOT NULL,
    c_phone      CHAR(15) NOT NULL,
    c_acctbal    NUMERIC(12,2) NOT NULL,
    c_mktsegment CHAR(10) NOT NULL,
    c_comment    VARCHAR(117) NOT NULL,
    PRIMARY KEY (c_custkey),
    FOREIGN KEY (c_nationkey) REFERENCES nation(n_nationkey)
);

-- 订单表
DROP TABLE IF EXISTS orders CASCADE;
CREATE TABLE orders (
    o_orderkey      BIGINT NOT NULL,
    o_custkey       INTEGER NOT NULL,
    o_orderstatus   CHAR(1) NOT NULL,
    o_totalprice    NUMERIC(12,2) NOT NULL,
    o_orderdate     DATE NOT NULL,
    o_orderpriority CHAR(15) NOT NULL,
    o_clerk         CHAR(15) NOT NULL,
    o_shippriority  INTEGER NOT NULL,
    o_comment       VARCHAR(79) NOT NULL,
    PRIMARY KEY (o_orderkey),
    FOREIGN KEY (o_custkey) REFERENCES customer(c_custkey)
);

-- 订单明细表
DROP TABLE IF EXISTS lineitem CASCADE;
CREATE TABLE lineitem (
    l_orderkey      BIGINT NOT NULL,
    l_partkey       INTEGER NOT NULL,
    l_suppkey       INTEGER NOT NULL,
    l_linenumber    INTEGER NOT NULL,
    l_quantity      NUMERIC(12,2) NOT NULL,
    l_extendedprice NUMERIC(12,2) NOT NULL,
    l_discount      NUMERIC(12,2) NOT NULL,
    l_tax           NUMERIC(12,2) NOT NULL,
    l_returnflag    CHAR(1) NOT NULL,
    l_linestatus    CHAR(1) NOT NULL,
    l_shipdate      DATE NOT NULL,
    l_commitdate    DATE NOT NULL,
    l_receiptdate   DATE NOT NULL,
    l_shipinstruct  CHAR(25) NOT NULL,
    l_shipmode      CHAR(10) NOT NULL,
    l_comment       VARCHAR(44) NOT NULL,
    PRIMARY KEY (l_orderkey, l_linenumber),
    FOREIGN KEY (l_orderkey) REFERENCES orders(o_orderkey),
    FOREIGN KEY (l_partkey) REFERENCES part(p_partkey),
    FOREIGN KEY (l_suppkey) REFERENCES supplier(s_suppkey)
);
