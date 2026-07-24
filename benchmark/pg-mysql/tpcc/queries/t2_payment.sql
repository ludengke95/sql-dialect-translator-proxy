-- TPC-C T2: Payment 事务

-- T2-a
SELECT c_id, c_first, c_middle, c_last, c_street_1, c_street_2, c_city, c_state, c_zip,
    c_phone, c_since, c_credit, c_credit_lim, c_discount, c_balance
FROM customer
WHERE c_w_id = 1 AND c_d_id = 1 AND c_last = 'BAR'
ORDER BY c_first
LIMIT 1;

-- T2-b
SELECT w_name, w_street_1, w_street_2, w_city, w_state, w_zip
FROM warehouse WHERE w_id = 1;

-- T2-c
SELECT d_name, d_street_1, d_street_2, d_city, d_state, d_zip
FROM district WHERE d_w_id = 1 AND d_id = 1;

-- T2-d
UPDATE customer
SET c_balance = c_balance - 100.00,
    c_ytd_payment = c_ytd_payment + 100.00,
    c_payment_cnt = c_payment_cnt + 1
WHERE c_w_id = 1 AND c_d_id = 1 AND c_id = 1;

-- T2-e
UPDATE district SET d_ytd = d_ytd + 100.00 WHERE d_w_id = 1 AND d_id = 1;

-- T2-f
UPDATE warehouse SET w_ytd = w_ytd + 100.00 WHERE w_id = 1;

-- T2-g
INSERT INTO history (h_c_id, h_c_d_id, h_c_w_id, h_d_id, h_w_id, h_date, h_amount, h_data)
VALUES (1, 1, 1, 1, 1, CURRENT_TIMESTAMP, 100.00, 'payment for order');