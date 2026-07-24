-- TPC-C 表结构（PostgreSQL DDL）
-- 用于 SDTP 基准测试：直连 PostgreSQL 建表，MySQL 方言查询通过 SDTP 执行
-- 注意：建表顺序按依赖关系排列（被外键引用的表先创建）
-- 独立 schema 隔离，避免与 TPC-H 同名表冲突

CREATE SCHEMA IF NOT EXISTS tpcc;
SET search_path TO tpcc;

-- 仓库表
DROP TABLE IF EXISTS warehouse CASCADE;
CREATE TABLE warehouse (
    w_id        INTEGER NOT NULL,
    w_name      VARCHAR(16) NOT NULL,
    w_street_1  VARCHAR(20) NOT NULL,
    w_street_2  VARCHAR(20) NOT NULL,
    w_city      VARCHAR(20) NOT NULL,
    w_state     CHAR(2) NOT NULL,
    w_zip       CHAR(10) NOT NULL,
    w_tax       NUMERIC(4,4) NOT NULL,
    w_ytd       NUMERIC(12,2) NOT NULL,
    PRIMARY KEY (w_id)
);

-- 区域表
DROP TABLE IF EXISTS district CASCADE;
CREATE TABLE district (
    d_id        INTEGER NOT NULL,
    d_w_id      INTEGER NOT NULL,
    d_name      VARCHAR(10) NOT NULL,
    d_street_1  VARCHAR(20) NOT NULL,
    d_street_2  VARCHAR(20) NOT NULL,
    d_city      VARCHAR(20) NOT NULL,
    d_state     CHAR(2) NOT NULL,
    d_zip       CHAR(10) NOT NULL,
    d_tax       NUMERIC(4,4) NOT NULL,
    d_ytd       NUMERIC(12,2) NOT NULL,
    d_next_o_id INTEGER NOT NULL,
    PRIMARY KEY (d_w_id, d_id),
    FOREIGN KEY (d_w_id) REFERENCES warehouse(w_id)
);

-- 客户表
DROP TABLE IF EXISTS customer CASCADE;
CREATE TABLE customer (
    c_id           INTEGER NOT NULL,
    c_d_id         INTEGER NOT NULL,
    c_w_id         INTEGER NOT NULL,
    c_first        VARCHAR(16) NOT NULL,
    c_middle       CHAR(2) NOT NULL,
    c_last         VARCHAR(16) NOT NULL,
    c_street_1     VARCHAR(20) NOT NULL,
    c_street_2     VARCHAR(20) NOT NULL,
    c_city         VARCHAR(20) NOT NULL,
    c_state        CHAR(2) NOT NULL,
    c_zip          CHAR(10) NOT NULL,
    c_phone        CHAR(16) NOT NULL,
    c_since        TIMESTAMP NOT NULL,
    c_credit       CHAR(2) NOT NULL,
    c_credit_lim   NUMERIC(12,2) NOT NULL,
    c_discount     NUMERIC(4,4) NOT NULL,
    c_balance      NUMERIC(12,2) NOT NULL,
    c_ytd_payment  NUMERIC(12,2) NOT NULL,
    c_payment_cnt  INTEGER NOT NULL,
    c_delivery_cnt INTEGER NOT NULL,
    c_data         VARCHAR(500) NOT NULL,
    PRIMARY KEY (c_w_id, c_d_id, c_id),
    FOREIGN KEY (c_w_id, c_d_id) REFERENCES district(d_w_id, d_id)
);

-- 商品表（被 stock 和 order_line 引用，需在其之前创建）
DROP TABLE IF EXISTS item CASCADE;
CREATE TABLE item (
    i_id     INTEGER NOT NULL,
    i_im_id  INTEGER NOT NULL,
    i_name   VARCHAR(24) NOT NULL,
    i_price  NUMERIC(5,2) NOT NULL,
    i_data   VARCHAR(50) NOT NULL,
    PRIMARY KEY (i_id)
);

-- 历史表
DROP TABLE IF EXISTS history CASCADE;
CREATE TABLE history (
    h_c_id   INTEGER NOT NULL,
    h_c_d_id INTEGER NOT NULL,
    h_c_w_id INTEGER NOT NULL,
    h_d_id   INTEGER NOT NULL,
    h_w_id   INTEGER NOT NULL,
    h_date   TIMESTAMP NOT NULL,
    h_amount NUMERIC(6,2) NOT NULL,
    h_data   VARCHAR(24) NOT NULL
);

-- 订单表
DROP TABLE IF EXISTS orders CASCADE;
CREATE TABLE orders (
    o_id         INTEGER NOT NULL,
    o_d_id       INTEGER NOT NULL,
    o_w_id       INTEGER NOT NULL,
    o_c_id       INTEGER NOT NULL,
    o_entry_d    TIMESTAMP NOT NULL,
    o_carrier_id INTEGER,
    o_ol_cnt     INTEGER NOT NULL,
    o_all_local  INTEGER NOT NULL,
    PRIMARY KEY (o_w_id, o_d_id, o_id),
    FOREIGN KEY (o_w_id, o_d_id, o_c_id) REFERENCES customer(c_w_id, c_d_id, c_id)
);

-- 新订单表
DROP TABLE IF EXISTS new_orders CASCADE;
CREATE TABLE new_orders (
    no_o_id INTEGER NOT NULL,
    no_d_id INTEGER NOT NULL,
    no_w_id INTEGER NOT NULL,
    PRIMARY KEY (no_w_id, no_d_id, no_o_id),
    FOREIGN KEY (no_w_id, no_d_id, no_o_id) REFERENCES orders(o_w_id, o_d_id, o_id)
);

-- 库存表（引用 item）
DROP TABLE IF EXISTS stock CASCADE;
CREATE TABLE stock (
    s_i_id       INTEGER NOT NULL,
    s_w_id       INTEGER NOT NULL,
    s_quantity   NUMERIC(6,2) NOT NULL,
    s_dist_01    CHAR(24) NOT NULL,
    s_dist_02    CHAR(24) NOT NULL,
    s_dist_03    CHAR(24) NOT NULL,
    s_dist_04    CHAR(24) NOT NULL,
    s_dist_05    CHAR(24) NOT NULL,
    s_dist_06    CHAR(24) NOT NULL,
    s_dist_07    CHAR(24) NOT NULL,
    s_dist_08    CHAR(24) NOT NULL,
    s_dist_09    CHAR(24) NOT NULL,
    s_dist_10    CHAR(24) NOT NULL,
    s_ytd        NUMERIC(8,2) NOT NULL,
    s_order_cnt  INTEGER NOT NULL,
    s_remote_cnt INTEGER NOT NULL,
    s_data       VARCHAR(50) NOT NULL,
    PRIMARY KEY (s_w_id, s_i_id),
    FOREIGN KEY (s_w_id) REFERENCES warehouse(w_id),
    FOREIGN KEY (s_i_id) REFERENCES item(i_id)
);

-- 订单详情表（引用 item 和 orders）
DROP TABLE IF EXISTS order_line CASCADE;
CREATE TABLE order_line (
    ol_o_id      INTEGER NOT NULL,
    ol_d_id      INTEGER NOT NULL,
    ol_w_id      INTEGER NOT NULL,
    ol_number    INTEGER NOT NULL,
    ol_i_id      INTEGER NOT NULL,
    ol_supply_w_id INTEGER NOT NULL,
    ol_delivery_d TIMESTAMP,
    ol_quantity  INTEGER NOT NULL,
    ol_amount    NUMERIC(6,2) NOT NULL,
    ol_dist_info CHAR(24) NOT NULL,
    PRIMARY KEY (ol_w_id, ol_d_id, ol_o_id, ol_number),
    FOREIGN KEY (ol_w_id, ol_d_id, ol_o_id) REFERENCES orders(o_w_id, o_d_id, o_id),
    FOREIGN KEY (ol_i_id) REFERENCES item(i_id)
);
