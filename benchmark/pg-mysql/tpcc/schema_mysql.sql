-- TPC-C Table Structure (MySQL DDL)
SET FOREIGN_KEY_CHECKS=0;
CREATE DATABASE IF NOT EXISTS tpcc;
USE tpcc;

-- Warehouse Table
DROP TABLE IF EXISTS warehouse;
CREATE TABLE warehouse (
    w_id        INT NOT NULL,
    w_name      VARCHAR(16) NOT NULL,
    w_street_1  VARCHAR(20) NOT NULL,
    w_street_2  VARCHAR(20) NOT NULL,
    w_city      VARCHAR(20) NOT NULL,
    w_state     CHAR(2) NOT NULL,
    w_zip       CHAR(10) NOT NULL,
    w_tax       DECIMAL(4,4) NOT NULL,
    w_ytd       DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (w_id)
);

-- District Table
DROP TABLE IF EXISTS district;
CREATE TABLE district (
    d_id        INT NOT NULL,
    d_w_id      INT NOT NULL,
    d_name      VARCHAR(10) NOT NULL,
    d_street_1  VARCHAR(20) NOT NULL,
    d_street_2  VARCHAR(20) NOT NULL,
    d_city      VARCHAR(20) NOT NULL,
    d_state     CHAR(2) NOT NULL,
    d_zip       CHAR(10) NOT NULL,
    d_tax       DECIMAL(4,4) NOT NULL,
    d_ytd       DECIMAL(12,2) NOT NULL,
    d_next_o_id INT NOT NULL,
    PRIMARY KEY (d_w_id, d_id),
    FOREIGN KEY (d_w_id) REFERENCES warehouse(w_id)
);

-- Customer Table
DROP TABLE IF EXISTS customer;
CREATE TABLE customer (
    c_id           INT NOT NULL,
    c_d_id         INT NOT NULL,
    c_w_id         INT NOT NULL,
    c_first        VARCHAR(16) NOT NULL,
    c_middle       CHAR(2) NOT NULL,
    c_last         VARCHAR(16) NOT NULL,
    c_street_1     VARCHAR(20) NOT NULL,
    c_street_2     VARCHAR(20) NOT NULL,
    c_city         VARCHAR(20) NOT NULL,
    c_state        CHAR(2) NOT NULL,
    c_zip          CHAR(10) NOT NULL,
    c_phone        CHAR(16) NOT NULL,
    c_since        DATETIME NOT NULL,
    c_credit       CHAR(2) NOT NULL,
    c_credit_lim   DECIMAL(12,2) NOT NULL,
    c_discount     DECIMAL(4,4) NOT NULL,
    c_balance      DECIMAL(12,2) NOT NULL,
    c_ytd_payment  DECIMAL(12,2) NOT NULL,
    c_payment_cnt  INT NOT NULL,
    c_delivery_cnt INT NOT NULL,
    c_data         VARCHAR(500) NOT NULL,
    PRIMARY KEY (c_w_id, c_d_id, c_id),
    FOREIGN KEY (c_w_id, c_d_id) REFERENCES district(d_w_id, d_id)
);

-- Item Table
DROP TABLE IF EXISTS item;
CREATE TABLE item (
    i_id     INT NOT NULL,
    i_im_id  INT NOT NULL,
    i_name   VARCHAR(24) NOT NULL,
    i_price  DECIMAL(5,2) NOT NULL,
    i_data   VARCHAR(50) NOT NULL,
    PRIMARY KEY (i_id)
);

-- History Table
DROP TABLE IF EXISTS history;
CREATE TABLE history (
    h_c_id   INT NOT NULL,
    h_c_d_id INT NOT NULL,
    h_c_w_id INT NOT NULL,
    h_d_id   INT NOT NULL,
    h_w_id   INT NOT NULL,
    h_date   DATETIME NOT NULL,
    h_amount DECIMAL(6,2) NOT NULL,
    h_data   VARCHAR(24) NOT NULL
);

-- Orders Table
DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
    o_id         INT NOT NULL,
    o_d_id       INT NOT NULL,
    o_w_id       INT NOT NULL,
    o_c_id       INT NOT NULL,
    o_entry_d    DATETIME NOT NULL,
    o_carrier_id INT,
    o_ol_cnt     INT NOT NULL,
    o_all_local  INT NOT NULL,
    PRIMARY KEY (o_w_id, o_d_id, o_id),
    FOREIGN KEY (o_w_id, o_d_id, o_c_id) REFERENCES customer(c_w_id, c_d_id, c_id)
);

-- New Orders Table
DROP TABLE IF EXISTS new_orders;
CREATE TABLE new_orders (
    no_o_id INT NOT NULL,
    no_d_id INT NOT NULL,
    no_w_id INT NOT NULL,
    PRIMARY KEY (no_w_id, no_d_id, no_o_id),
    FOREIGN KEY (no_w_id, no_d_id, no_o_id) REFERENCES orders(o_w_id, o_d_id, o_id)
);

-- Stock Table
DROP TABLE IF EXISTS stock;
CREATE TABLE stock (
    s_i_id       INT NOT NULL,
    s_w_id       INT NOT NULL,
    s_quantity   DECIMAL(6,2) NOT NULL,
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
    s_ytd        DECIMAL(8,2) NOT NULL,
    s_order_cnt  INT NOT NULL,
    s_remote_cnt INT NOT NULL,
    s_data       VARCHAR(50) NOT NULL,
    PRIMARY KEY (s_w_id, s_i_id),
    FOREIGN KEY (s_w_id) REFERENCES warehouse(w_id),
    FOREIGN KEY (s_i_id) REFERENCES item(i_id)
);

-- Order Line Table
DROP TABLE IF EXISTS order_line;
CREATE TABLE order_line (
    ol_o_id      INT NOT NULL,
    ol_d_id      INT NOT NULL,
    ol_w_id      INT NOT NULL,
    ol_number    INT NOT NULL,
    ol_i_id      INT NOT NULL,
    ol_supply_w_id INT NOT NULL,
    ol_delivery_d DATETIME,
    ol_quantity  INT NOT NULL,
    ol_amount    DECIMAL(6,2) NOT NULL,
    ol_dist_info CHAR(24) NOT NULL,
    PRIMARY KEY (ol_w_id, ol_d_id, ol_o_id, ol_number),
    FOREIGN KEY (ol_w_id, ol_d_id, ol_o_id) REFERENCES orders(o_w_id, o_d_id, o_id),
    FOREIGN KEY (ol_i_id) REFERENCES item(i_id)
);
