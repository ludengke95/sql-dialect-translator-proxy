-- TPC-H Table Structure (MySQL DDL)
SET FOREIGN_KEY_CHECKS=0;
CREATE DATABASE IF NOT EXISTS tpch;
USE tpch;

-- Region Table
DROP TABLE IF EXISTS region;
CREATE TABLE region (
    r_regionkey  INT NOT NULL,
    r_name       CHAR(25) NOT NULL,
    r_comment    VARCHAR(152),
    PRIMARY KEY (r_regionkey)
);

-- Nation Table
DROP TABLE IF EXISTS nation;
CREATE TABLE nation (
    n_nationkey  INT NOT NULL,
    n_name       CHAR(25) NOT NULL,
    n_regionkey  INT NOT NULL,
    n_comment    VARCHAR(152),
    PRIMARY KEY (n_nationkey),
    FOREIGN KEY (n_regionkey) REFERENCES region(r_regionkey)
);

-- Supplier Table
DROP TABLE IF EXISTS supplier;
CREATE TABLE supplier (
    s_suppkey   INT NOT NULL,
    s_name      CHAR(25) NOT NULL,
    s_address   VARCHAR(40) NOT NULL,
    s_nationkey INT NOT NULL,
    s_phone     CHAR(15) NOT NULL,
    s_acctbal   DECIMAL(12,2) NOT NULL,
    s_comment   VARCHAR(101) NOT NULL,
    PRIMARY KEY (s_suppkey),
    FOREIGN KEY (s_nationkey) REFERENCES nation(n_nationkey)
);

-- Part Table
DROP TABLE IF EXISTS part;
CREATE TABLE part (
    p_partkey     INT NOT NULL,
    p_name        VARCHAR(55) NOT NULL,
    p_mfgr        CHAR(25) NOT NULL,
    p_brand       CHAR(10) NOT NULL,
    p_type        VARCHAR(55) NOT NULL,
    p_size        INT NOT NULL,
    p_container   CHAR(10) NOT NULL,
    p_retailprice DECIMAL(12,2) NOT NULL,
    p_comment     VARCHAR(23) NOT NULL,
    PRIMARY KEY (p_partkey)
);

-- Partsupp Table
DROP TABLE IF EXISTS partsupp;
CREATE TABLE partsupp (
    ps_partkey    INT NOT NULL,
    ps_suppkey    INT NOT NULL,
    ps_availqty   INT NOT NULL,
    ps_supplycost DECIMAL(12,2) NOT NULL,
    ps_comment    VARCHAR(199) NOT NULL,
    PRIMARY KEY (ps_partkey, ps_suppkey),
    FOREIGN KEY (ps_partkey) REFERENCES part(p_partkey),
    FOREIGN KEY (ps_suppkey) REFERENCES supplier(s_suppkey)
);

-- Customer Table
DROP TABLE IF EXISTS customer;
CREATE TABLE customer (
    c_custkey    INT NOT NULL,
    c_name       VARCHAR(25) NOT NULL,
    c_address    VARCHAR(40) NOT NULL,
    c_nationkey  INT NOT NULL,
    c_phone      CHAR(15) NOT NULL,
    c_acctbal    DECIMAL(12,2) NOT NULL,
    c_mktsegment CHAR(10) NOT NULL,
    c_comment    VARCHAR(117) NOT NULL,
    PRIMARY KEY (c_custkey),
    FOREIGN KEY (c_nationkey) REFERENCES nation(n_nationkey)
);

-- Orders Table
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
    o_orderkey      BIGINT NOT NULL,
    o_custkey       INT NOT NULL,
    o_orderstatus   CHAR(1) NOT NULL,
    o_totalprice    DECIMAL(12,2) NOT NULL,
    o_orderdate     DATE NOT NULL,
    o_orderpriority CHAR(15) NOT NULL,
    o_clerk         CHAR(15) NOT NULL,
    o_shippriority  INT NOT NULL,
    o_comment       VARCHAR(79) NOT NULL,
    PRIMARY KEY (o_orderkey),
    FOREIGN KEY (o_custkey) REFERENCES customer(c_custkey)
);

-- Lineitem Table
DROP TABLE IF EXISTS lineitem;
CREATE TABLE lineitem (
    l_orderkey      BIGINT NOT NULL,
    l_partkey       INT NOT NULL,
    l_suppkey       INT NOT NULL,
    l_linenumber    INT NOT NULL,
    l_quantity      DECIMAL(12,2) NOT NULL,
    l_extendedprice DECIMAL(12,2) NOT NULL,
    l_discount      DECIMAL(12,2) NOT NULL,
    l_tax           DECIMAL(12,2) NOT NULL,
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
