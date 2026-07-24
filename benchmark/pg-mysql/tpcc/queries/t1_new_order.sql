-- TPC-C T1: New-Order 事务

-- T1-a
SELECT w_tax FROM warehouse WHERE w_id = 1;

-- T1-b
SELECT d_tax, d_next_o_id FROM district WHERE d_w_id = 1 AND d_id = 1;

-- T1-c
SELECT c_discount, c_last, c_credit FROM customer
WHERE c_w_id = 1 AND c_d_id = 1 AND c_id = 1;

-- T1-d
SELECT i_price, i_name, i_data FROM item WHERE i_id = 1;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 2;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 3;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 5;
SELECT i_price, i_name, i_data FROM item WHERE i_id = 10;

-- T1-e
SELECT s_quantity, s_dist_01, s_ytd FROM stock
WHERE s_i_id = 1 AND s_w_id = 1;

-- T1-f
UPDATE stock SET s_quantity = s_quantity - 5, s_ytd = s_ytd + 5, s_order_cnt = s_order_cnt + 1
WHERE s_i_id = 1 AND s_w_id = 1;

-- T1-g
UPDATE district SET d_next_o_id = d_next_o_id + 1 WHERE d_w_id = 1 AND d_id = 1;

-- T1-h
INSERT INTO orders (o_id, o_d_id, o_w_id, o_c_id, o_entry_d, o_carrier_id, o_ol_cnt, o_all_local)
VALUES (3001, 1, 1, 1, CURRENT_TIMESTAMP, NULL, 5, 0);

-- T1-i
INSERT INTO new_orders (no_o_id, no_d_id, no_w_id) VALUES (3001, 1, 1);

-- T1-j
INSERT INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, ol_i_id, ol_supply_w_id,
    ol_delivery_d, ol_quantity, ol_amount, ol_dist_info)
VALUES (3001, 1, 1, 1, 1, 1, NULL, 5, 45.00, 'dist_info_01');
INSERT INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, ol_i_id, ol_supply_w_id,
    ol_delivery_d, ol_quantity, ol_amount, ol_dist_info)
VALUES (3001, 1, 1, 2, 2, 1, NULL, 3, 22.50, 'dist_info_02');
INSERT INTO order_line (ol_o_id, ol_d_id, ol_w_id, ol_number, ol_i_id, ol_supply_w_id,
    ol_delivery_d, ol_quantity, ol_amount, ol_dist_info)
VALUES (3001, 1, 1, 3, 3, 1, NULL, 1, 15.00, 'dist_info_03');